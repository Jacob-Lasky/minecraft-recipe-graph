"""The prebuilt jar in `dist/` has to be the jar this source tree would produce.

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

Nothing here rebuilds the jar. That needs a JDK and about five minutes, so this asserts the
committed artifact agrees with the committed source and fails loudly when it stops doing so.
If it fails, rebuild and re-commit the jar; do not edit the numbers to match.
"""

import os
import re
import struct
import sys
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

DIST = os.path.join(ROOT, "dist")
JAVA_SRC = os.path.join(ROOT, "mod", "src", "main", "java", "io", "github",
                        "jacoblasky", "recipedump", "DumpCommand.java")
GRADLE_PROPS = os.path.join(ROOT, "mod", "gradle.properties")

# Java bytecode major version for Java 8, which is what a 1.12.2 mod must be.
JAVA_8_MAJOR = 52


def _jars():
    if not os.path.isdir(DIST):
        return []
    return sorted(n for n in os.listdir(DIST) if n.endswith(".jar"))


def _source_schema():
    with open(JAVA_SRC) as fh:
        m = re.search(r"static\s+final\s+int\s+SCHEMA\s*=\s*(\d+)", fh.read())
    return int(m.group(1)) if m else None


def _source_version():
    with open(GRADLE_PROPS) as fh:
        m = re.search(r"^\s*mod_version\s*=\s*(\S+)", fh.read(), re.M)
    return m.group(1) if m else None


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
    def test_there_is_exactly_one_jar(self):
        # Two jars means a reader has to guess which is current, which is how the stale one
        # kept being handed out. The old 0.4.2 was removed rather than kept beside its
        # replacement for that reason.
        self.assertEqual(len(_jars()), 1, "expected one jar in dist/, found %s" % _jars())

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
        with zipfile.ZipFile(os.path.join(DIST, _jars()[0])) as z:
            blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".class"))
        srg = re.findall(rb"func_\d+_[a-zA-Z]+|field_\d+_[a-zA-Z]+", blob)
        self.assertGreater(len(srg), 0, "no SRG references: this looks like the -dev jar")

    def test_the_schema_matches_the_source(self):
        # The assertion the stale jar would have failed: it carried 2 against a source of 3.
        want = _source_schema()
        self.assertIsNotNone(want, "could not read SCHEMA from DumpCommand.java")
        self.assertEqual(_jar_schema(os.path.join(DIST, _jars()[0])), want)

    def test_it_carries_the_discrimination_code(self):
        # Coarse but decisive, and independent of the schema int: the schema-2 jar contained
        # no `nbt` or `discriminator` strings anywhere, because that code did not exist yet.
        with zipfile.ZipFile(os.path.join(DIST, _jars()[0])) as z:
            blob = b"".join(z.read(n) for n in z.namelist() if n.endswith(".class"))
        for marker in (b"nbt", b"discriminator"):
            self.assertIn(marker, blob)

    def test_it_targets_java_8(self):
        with zipfile.ZipFile(os.path.join(DIST, _jars()[0])) as z:
            name = [n for n in z.namelist() if n.endswith("DumpCommand.class")][0]
            data = z.read(name)
        self.assertEqual(struct.unpack(">H", data[6:8])[0], JAVA_8_MAJOR)

    def test_the_readme_points_at_the_jar_that_exists(self):
        # A README naming a filename that is no longer there sends people to a 404, and a
        # README naming the OLD filename is how the stale jar stayed discoverable.
        with open(os.path.join(ROOT, "README.md")) as fh:
            readme = fh.read()
        for stale in re.findall(r"mc-recipe-dump-[\d.]+\.jar", readme):
            self.assertEqual(stale, _jars()[0],
                             "README references %s but dist/ holds %s" % (stale, _jars()[0]))


if __name__ == "__main__":
    unittest.main()
