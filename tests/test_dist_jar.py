"""A jar about to go into the client has to be the jar this source tree would produce.

THE JAR IS NO LONGER TRACKED IN GIT, and that changed what this file points at rather than
what it guarantees. `dist/` held one 50 KB binary that every change under `mod/src/main/java`
invalidated, so with several branches in flight it conflicted on essentially every merge while
no source file conflicted at all -- a generated artifact being hand-carried through review.
It is built now, by `mod/tools/build-jar.sh`, and these assertions moved onto the built
artifact where they protect the thing that actually matters: the jar someone is one `cp` away
from putting in a client.

So this SKIPS when no jar has been built, and CI has none. That is not a weakening. The
failure it exists to prevent is a stale or `-dev` jar reaching Jake's `mods/`, and that only
happens by way of a build; a repository with no jar in it cannot ship a bad one.


It rotted silently once and the failure mode is the worst kind: `dist/mc-recipe-dump-0.4.2.jar`
sat in a public repo at `SCHEMA = 2`, containing none of the NBT-discrimination code, while
the source had moved to `SCHEMA = 3` and the Python side required it. Anyone who cloned the
repo, dropped that jar in, and ran `/recipedump` got a dump that reintroduced #20 and #21
exactly: every bee, tree and chicken collapsed to one key, AE2 stock could not match a
species key, and `machines.SPECIES_SCHEMA` quietly withheld the no-machine patterns. Nothing
anywhere said so, because a schema-2 dump is a VALID dump, just an old one.

So the jar cannot be checked by looking at it. It has to be checked against the source that
claims to have built it, which is what this does, by reading the `SCHEMA` int constant
straight out of the compiled `DumpCommand.class` constant pool and the version out of
`mcmod.info`.

Those two catch a jar from an older SCHEMA or an older version, and NOT a jar from the same
version built several commits ago -- most changes move neither number. That second, subtler
staleness happened while adding the #80 trace: the jar was rebuilt, DumpCommand.java was then
edited again, and every assertion here still passed. So the decisive check is
`test_it_was_built_from_the_source_that_is_checked_in_now`, which compares a SHA-256 over
`mod/src/main/java` against the copy `stampSourceHash` embeds in the jar at build time.

Nothing here rebuilds the jar. That needs a JDK and about five minutes, so this asserts the
built artifact agrees with the source and fails loudly when it stops doing so. If it fails,
rebuild it with `mod/tools/build-jar.sh`; do not edit the numbers to match.
"""

import hashlib
import os
import re
import struct
import sys
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from recipegraph.sources import dump_meta  # noqa: E402

# Where a built jar lands. `$MCRECIPEDUMP_JAR` overrides it so the jar already sitting in a
# client's `mods/` can be checked in place, which is the one copy whose staleness actually
# costs a game launch.
DIST = os.environ.get("MCRECIPEDUMP_JAR") or os.path.join(ROOT, "mod", "build", "libs")
JAVA_SRC = os.path.join(ROOT, "mod", "src", "main", "java", "io", "github",
                        "jacoblasky", "recipedump", "DumpCommand.java")
GRADLE_PROPS = os.path.join(ROOT, "mod", "gradle.properties")

# Java bytecode major version for Java 8, which is what a 1.12.2 mod must be.
JAVA_8_MAJOR = 52


def _jars():
    """Built jars, EXCLUDING the `-dev` one, newest-version-agnostic.

    `build` writes `mc-recipe-dump-<v>.jar` and `mc-recipe-dump-<v>-dev.jar` side by side,
    within a few hundred bytes of each other. The dev jar is not reobfuscated: Forge loads it
    and then dies on obfuscated names at runtime, in the client, after a launch. Filtering it
    here rather than asserting against it keeps `test_it_is_not_the_dev_jar` meaningful for
    the jar a person would actually copy.
    """
    if os.path.isfile(DIST):
        return [os.path.basename(DIST)]
    if not os.path.isdir(DIST):
        return []
    return sorted(n for n in os.listdir(DIST)
                  if n.endswith(".jar") and not n.endswith("-dev.jar"))


def _dist_dir():
    return os.path.dirname(DIST) if os.path.isfile(DIST) else DIST


def _readme():
    with open(os.path.join(ROOT, "README.md")) as fh:
        return fh.read()


def _source_schema():
    with open(JAVA_SRC) as fh:
        m = re.search(r"static\s+final\s+int\s+SCHEMA\s*=\s*(\d+)", fh.read())
    return int(m.group(1)) if m else None


def _source_version():
    with open(GRADLE_PROPS) as fh:
        m = re.search(r"^\s*mod_version\s*=\s*(\S+)", fh.read(), re.M)
    return m.group(1) if m else None


def _source_hash():
    """SHA-256 over the mod's main Java sources, matching `stampSourceHash` in build.gradle.

    Sorted by relative path using Python's own string ordering, which is code-point order and
    locale-INDEPENDENT, the same guarantee Java's String ordering gives on the Gradle side. A
    locale-collated sort would make two byte-identical trees hash differently between hosts
    and read as code drift -- the failure the skill records for exactly this shape of check.

    Path AND content go in, so a rename with identical bytes still counts as a change.
    """
    src = os.path.join(ROOT, "mod", "src", "main", "java")
    rels = []
    for dirpath, _dirs, files in os.walk(src):
        for name in files:
            if name.endswith(".java"):
                full = os.path.join(dirpath, name)
                rels.append((os.path.relpath(full, src).replace(os.sep, "/"), full))
    digest = hashlib.sha256()
    for rel, full in sorted(rels):
        digest.update(rel.encode("utf-8"))
        with open(full, "rb") as fh:
            digest.update(fh.read())
    return digest.hexdigest()


def _jar_schema(path):
    """The `SCHEMA` constant as the compiler baked it into the class file.

    `static final int` is inlined as a ConstantValue attribute, so it survives into the jar
    as a big-endian int in the constant pool. Located by finding the UTF8 entry "SCHEMA" and
    reading the integer that the ConstantValue points at, which in practice sits immediately
    after the field descriptor. Crude, and adequate: a wrong answer here fails the test
    rather than passing it, because the source value is the thing being compared against.
    """
    with zipfile.ZipFile(path) as z:
        name = [n for n in z.namelist() if n.endswith("DumpCommand.class")]
        if not name:
            return None
        data = z.read(name[0])
    i = data.find(b"SCHEMA")
    if i < 0:
        return None
    # ...\x06SCHEMA \x01\x00\x01 I \x03 <4-byte int>
    window = data[i + len("SCHEMA"):i + len("SCHEMA") + 16]
    m = re.search(rb"I\x03(....)", window, re.S)
    return struct.unpack(">i", m.group(1))[0] if m else None


class DistJarMatchesSourceTest(unittest.TestCase):

    def setUp(self):
        # SKIP rather than fail when nothing has been built. CI never builds the mod, and a
        # repository holding no jar cannot ship a bad one -- the failure this file exists to
        # prevent only arrives by way of a build.
        if not _jars():
            self.skipTest("no built jar under %s; run mod/tools/build-jar.sh "
                          "(or set MCRECIPEDUMP_JAR to one)" % _dist_dir())

    def test_there_is_exactly_one_jar(self):
        # Two jars means a reader has to guess which is current, which is how the stale 0.4.2
        # kept being handed out. That risk did not go away when the jar stopped being tracked:
        # `mod/build/libs` ACCUMULATES, so anyone who has built more than once has several
        # versions sitting there and the newest is not necessarily the one they will copy.
        #
        # The message names the sweep, because two agents independently read this failure as a
        # packaging defect rather than as leftovers -- three red herrings that look exactly
        # like a real problem. `build-jar.sh` sweeps siblings itself; this is for the
        # directories it has not been run in.
        self.assertEqual(
            len(_jars()), 1,
            "expected one built jar in %s, found %s -- these are leftovers from earlier "
            "builds, not a packaging fault. `rm -rf mod/build/libs` and rebuild, or run "
            "mod/tools/build-jar.sh which sweeps stale siblings itself."
            % (_dist_dir(), _jars()))

    def test_the_filename_is_the_source_version(self):
        version = _source_version()
        self.assertIsNotNone(version, "mod/gradle.properties has no mod_version")
        self.assertEqual(_jars(), ["mc-recipe-dump-%s.jar" % version])

    def test_it_is_not_the_dev_jar(self):
        # The build writes both and they differ by a few hundred bytes, so the filename is
        # the only obvious tell. The dev jar is not reobfuscated and will not run in a normal
        # instance.
        self.assertNotIn("-dev", _jars()[0])

    def test_it_is_reobfuscated(self):
        # The real check behind the filename: the prod jar references SRG names, the dev jar
        # has none at all.
        with zipfile.ZipFile(os.path.join(_dist_dir(), _jars()[0])) as z:
            blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".class"))
        srg = re.findall(rb"func_\d+_[a-zA-Z]+|field_\d+_[a-zA-Z]+", blob)
        self.assertGreater(len(srg), 0, "no SRG references: this looks like the -dev jar")

    def test_the_schema_matches_the_source(self):
        # The assertion the stale jar would have failed: it carried 2 against a source of 3.
        want = _source_schema()
        self.assertIsNotNone(want, "could not read SCHEMA from DumpCommand.java")
        self.assertEqual(_jar_schema(os.path.join(_dist_dir(), _jars()[0])), want)

    def test_it_carries_the_discrimination_code(self):
        # Coarse but decisive, and independent of the schema int: the schema-2 jar contained
        # no `nbt` or `discriminator` strings anywhere, because that code did not exist yet.
        with zipfile.ZipFile(os.path.join(_dist_dir(), _jars()[0])) as z:
            blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".class"))
        for marker in (b"nbt", b"discriminator"):
            self.assertIn(marker, blob)

    def test_it_was_built_from_the_source_that_is_checked_in_now(self):
        """The gap every other assertion in this file leaves open.

        SCHEMA and the capability markers only move when a change happens to touch them, so a
        jar rebuilt several commits ago passes all of them while shipping behaviour that no
        longer matches `mod/`. That is #81's rot one level subtler, and it happened during
        this very change: DumpCommand.java was edited after the jar was built, and nothing
        here noticed until the hashes were compared by hand.

        `mod/build.gradle`'s `stampSourceHash` writes a SHA-256 over the main Java sources
        into the jar, so the comparison needs no JDK and no rebuild. If this fails: rebuild
        and re-commit the jar. Do NOT edit the expected value -- there isn't one to edit, it
        is recomputed from the tree.
        """
        want = _source_hash()
        with zipfile.ZipFile(os.path.join(_dist_dir(), _jars()[0])) as z:
            names = [n for n in z.namelist() if n.endswith("mcrecipedump-source.sha256")]
            self.assertEqual(len(names), 1,
                             "no source stamp in the jar: it predates stampSourceHash, so "
                             "it is certainly stale. Rebuild and re-commit.")
            got = z.read(names[0]).decode("utf-8").strip()
        self.assertEqual(got, want,
                         "the built jar is from different source than mod/ holds now; "
                         "rebuild it with mod/tools/build-jar.sh")

    def test_it_carries_the_nbt_trace_diagnostic(self):
        # Same rot class as the schema check above, one capability later. A jar predating
        # the issue #80 trace passes every other assertion here -- the schema did NOT move
        # for it, deliberately -- so nothing else in this file would notice. Someone who
        # clones the repo, installs a pre-0.6.0 jar and runs `/recipedump nbttrace` gets a
        # normal dump with no nbt_trace.json and no complaint, and only finds out after
        # spending a launch of the game on it.
        with zipfile.ZipFile(os.path.join(_dist_dir(), _jars()[0])) as z:
            blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".class"))
        # The suppress-arg literal is READ FROM THE SOURCE rather than written here. The
        # assertion is "this jar carries the capability", not "the flag is spelled X" -- so a
        # deliberate rename should follow along, while a jar that lost the capability still
        # fails. Hardcoding it meant renaming `nbttrace` to `notrace` failed this test for
        # the wrong reason and said nothing about the jar.
        with open(JAVA_SRC) as fh:
            arg = re.search(r'NO_TRACE_ARG\s*=\s*"([^"]+)"', fh.read())
        self.assertIsNotNone(arg, "NO_TRACE_ARG not found in DumpCommand.java")
        for marker in (arg.group(1).encode(), b"nbt_trace.json"):
            self.assertIn(marker, blob, "this jar cannot write the #80 trace")

    def test_it_targets_java_8(self):
        with zipfile.ZipFile(os.path.join(_dist_dir(), _jars()[0])) as z:
            name = [n for n in z.namelist() if n.endswith("DumpCommand.class")][0]
            data = z.read(name)
        self.assertEqual(struct.unpack(">H", data[6:8])[0], JAVA_8_MAJOR)

    def test_the_readme_names_no_jar_version_it_cannot_keep_current(self):
        # The README used to name the exact filename, which meant every rebuild edited prose
        # and every missed edit pointed a reader at a jar that was not there. Now that the jar
        # is built rather than committed there is no filename for the README to be right
        # about, so naming one is a promise it cannot keep. `<version>` is fine; a number is
        # not.
        for named in re.findall(r"mc-recipe-dump-[\d.]+\.jar", _readme()):
            self.fail("README names a specific jar version (%s); the jar is built, not "
                      "committed, so write mc-recipe-dump-<version>.jar instead" % named)

    def test_the_readme_states_the_schema_this_jar_actually_writes(self):
        """The version literal has a guard; the schema literal did not, and it is worse.

        A wrong filename is a 404 and the reader retries. A wrong schema number is the one
        claim this repo makes about when a graph must be rebuilt, and a reader who trusts a
        stale "writes dump schema 4" after a bump to 5 concludes their schema-4 graph is
        current -- which is exactly the silent AE2-reads-as-zero failure the sentence exists
        to prevent. The neighbouring "a schema-N graph is not compatible" is deliberately NOT
        matched: that one names the PREVIOUS schema and is correct as history.
        """
        claim = re.search(r"writes dump schema (\d+)", _readme())
        self.assertIsNotNone(claim, "README no longer states which schema the jar writes")
        self.assertEqual(int(claim.group(1)), dump_meta.SCHEMA,
                         "README says the jar writes schema %s, source says %s"
                         % (claim.group(1), dump_meta.SCHEMA))


if __name__ == "__main__":
    unittest.main()
