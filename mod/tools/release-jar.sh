#!/bin/sh
#
# Publish a built jar as a GitHub release, so the machine that PLAYS can install one.
#
#   mod/tools/release-jar.sh              # cut the release for mod_version
#   mod/tools/release-jar.sh --dry-run    # every check, print the notes, publish nothing
#
# WHY THIS EXISTS AND WHY IT IS NOT A WORKFLOW. There is no route for a jar to reach a
# player's client. `mod/build/` is gitignored, `.github/workflows/` builds no jar, and no
# release has ever been cut -- so #19's acceptance criteria, all of which need the mod
# installed on a real client, are not merely unticked but UNREACHABLE.
#
# CI CANNOT BUILD IT AND THAT IS NOT FIXABLE FROM THIS REPOSITORY. `mod/build.gradle` compiles
# against three jars taken from the pack, and `checkPackJars` is a `dependsOn` of `compileJava`
# (build.gradle), so any gradle build fails before javac starts without them. They are
# CurseForge distributions on no maven a runner can reach. Re-probed 2026-08-09 and it
# replicates the 2026-08-03 finding in `tools/ci-java.sh`, control included:
#
#     GTNH nexus  HadEnoughItems   0 items
#     GTNH nexus  modularui       25 items   <- the control: the API works, the zero is real
#     Modrinth    hadenoughitems  HTTP 404
#     maven.cleanroommc.com/      HTTP 404
#
# Mirroring them is a supply-chain and licensing commitment rather than a code change, so the
# route is a release cut from a jar built where the pack lives. That is a MANUAL step and this
# script does not pretend otherwise; see "What a human still has to do" at the bottom.
#
# IT PUBLISHES NOTHING UNTIL EVERY CHECK HAS PASSED, and the checks are the point. A release is
# outward-facing and hard to unsay: a wrong jar under a version number is worse than no jar,
# because the next person builds on the assumption that a published artifact was verified.
set -e

cd "$(dirname "$0")/../.."

DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

fail() { echo "release-jar: $*" >&2; exit 1; }

VERSION=$(sed -n 's/^mod_version=//p' mod/gradle.properties)
[ -n "$VERSION" ] || fail "no mod_version in mod/gradle.properties"
TAG="mod-v$VERSION"
JAR="mod/build/libs/mc-recipe-dump-$VERSION.jar"

# ---------------------------------------------------------------------------
# Refusals, in the order that makes the FIRST failure the most informative one.
# ---------------------------------------------------------------------------

# A RELEASE MUST NAME A COMMIT ANYONE ELSE CAN CHECK OUT. A tag on a dirty tree describes a
# state that exists on exactly one machine, which defeats the point of publishing the jar at
# all -- nobody could rebuild it to compare.
[ -z "$(git status --porcelain)" ] || fail \
    "the working tree is dirty. A release tags a commit, and a jar built from uncommitted
  changes cannot be rebuilt by anyone else. Commit or stash first."

COMMIT=$(git rev-parse HEAD)
git merge-base --is-ancestor "$COMMIT" origin/master 2>/dev/null || fail \
    "HEAD is not on origin/master. Push and merge first: a tag pointing at a commit that
  exists only here is a dangling release the moment the branch is deleted."

[ -f "$JAR" ] || fail \
    "no $JAR. Build it where the pack lives:
      PACK_MODS=<pack mods dir> mod/tools/build-jar.sh"

# THE STAMP CHECK IS THE ONE THAT MATTERS, and it is why this does not simply upload whatever
# is in `libs/`. The jar carries a sha256 over `mod/src/main/java`, so comparing it against the
# tree proves the artifact was built FROM THE COMMIT BEING TAGGED rather than from whatever was
# lying around. `tests/test_dist_jar.py` asserts the same thing, but a release must not depend
# on somebody having remembered to run the suite -- and #279 is the standing example of a
# staleness that loads cleanly and says nothing.
python3 - "$JAR" "$VERSION" "$COMMIT" <<'PY' > /tmp/release-jar-notes.$$ || fail "the jar does not match this tree; see above"
import re, sys, zipfile
sys.path.insert(0, ".")
from tests.test_dist_jar import _jar_schema, _source_hash

jar, version, commit = sys.argv[1], sys.argv[2], sys.argv[3]
z = zipfile.ZipFile(jar)

stamp = z.read("mcrecipedump-source.sha256").decode().strip()
tree = _source_hash()
if stamp != tree:
    sys.stderr.write(
        "release-jar: the jar was built from different source than this tree holds.\n"
        "  jar stamp  %s\n  this tree  %s\n"
        "  Rebuild with mod/tools/build-jar.sh, then re-run.\n" % (stamp[:16], tree[:16]))
    raise SystemExit(1)

# ZERO SRG REFERENCES MEANS THE `-dev` JAR. A dev jar installs, loads, and then dies on the
# first Minecraft call because its names were never reobfuscated -- in the CLIENT, after a
# launch, which is the most expensive place to find out. It is also the failure a HAND-CUT
# release is most likely to hit, because both files sit in the same directory one character
# apart.
srg = sum(len(re.findall(rb"func_|field_", z.read(n)))
          for n in z.namelist() if n.endswith(".class"))
if srg == 0:
    sys.stderr.write(
        "release-jar: %s has ZERO SRG references, so it is the -dev jar. Publishing it would\n"
        "  ship something that loads and then throws on its first Minecraft call.\n" % jar)
    raise SystemExit(1)

schema = _jar_schema(jar)
if schema is None:
    sys.stderr.write("release-jar: could not read SCHEMA out of %s\n" % jar)
    raise SystemExit(1)

# THE SCHEMA GOES IN THE NOTES, PROMINENTLY, and #279 is why. A graph and a jar that disagree
# about the dump schema produce a planner that answers "that item is not in the pack" for keys
# the pack really has -- silently, because `GraphJsonReader` is deliberately tolerant. The
# harness refuses that mismatch since #279; a player has no harness, so the least this can do
# is make the number impossible to miss before they install it. #285 tracks the runtime half.
print("""**Dump schema %d.** A `graph.json` produced by a different schema will still LOAD and
will then answer "not in the pack" for keys the pack really has, so regenerate your graph with
this jar rather than reusing an older one.

| | |
| --- | --- |
| Mod version | `%s` |
| Dump schema | `%d` |
| Commit | `%s` |
| Source stamp | `%s` |
| SRG references | `%d` (non-zero confirms this is the reobfuscated jar, not `-dev`) |

## Install

**BOTH SIDES, SAME JAR, OR THE CLIENT IS REFUSED AT CONNECT.** Since #19 phase 2 this mod
registers a real item and opens its own network channels, and it declares no
`acceptableRemoteVersions` -- so a client holding an item the server's registry does not have
fails Forge's handshake. Singleplayer needs only the client copy.

1. Jar into `mods/` on every client **and** on the server, with the server stopped.
2. **Move any older `mc-recipe-dump-*.jar` out first** -- two jars under one mod id is a
   startup failure rather than a newest-wins.

## The planner also needs a graph, and it does not come in the jar

`<instance>/config/mcrecipedump/graph.json`, on **each client**. The planner solves
client-side; the server never reads it.

Build it once with `/recipedump` in game plus `recipegraph build`, then **share that one file**
with everyone on the pack -- it is pack data, not player data, so one graph serves the whole
server. It is around 116 MB and must be plain JSON (no gzip support yet), though it sends at
about 9 MB compressed.

**Dump it from a CLIENT, never from the server.** A server runs fewer mods than the clients
connecting to it -- 367 against roughly 406 on MeatballCraft -- so a server-side dump is
missing everything client-only. The graph carries the mod-set digest it was built from and the
planner reports `pack: MISMATCH` when it disagrees with what is loaded, so this is a thing
you get told rather than a thing that quietly plans wrong.""" % (schema, version, schema,
                                                                commit, stamp[:16], srg))

sys.stderr.write("release-jar: %s  SCHEMA %d  SRG %d  stamp %s\n"
                 % (jar, schema, srg, stamp[:16]))
PY

NOTES=$(cat /tmp/release-jar-notes.$$)
rm -f /tmp/release-jar-notes.$$

if [ "$DRY" -eq 1 ]; then
    echo "release-jar: DRY RUN, publishing nothing. Tag would be $TAG on $COMMIT."
    echo "--- release notes ---"
    echo "$NOTES"
    exit 0
fi

# ALREADY-RELEASED IS A REFUSAL RATHER THAN AN OVERWRITE. `gh release create` on an existing
# tag fails anyway, but failing HERE says why: bumping `mod_version` is the intended way to
# publish a second jar, and re-pointing a tag people may already have downloaded is not.
if gh release view "$TAG" >/dev/null 2>&1; then
    fail "$TAG already exists. Bump mod_version in mod/gradle.properties to publish a new jar;
  do not re-point a tag somebody may already have installed from."
fi

gh release create "$TAG" "$JAR" \
    --target "$COMMIT" \
    --title "Dump mod $VERSION" \
    --notes "$NOTES"

echo "release-jar: published $TAG from $COMMIT"

# ---------------------------------------------------------------------------
# WHAT A HUMAN STILL HAS TO DO, and this list is not an apology for the script.
#
# Two steps here need a person, and neither is automatable from this repository:
#
#   1. BUILD THE JAR ON A MACHINE THAT HAS THE PACK. `PACK_MODS=<pack mods dir>
#      mod/tools/build-jar.sh`, on pocket-dev or the desktop. A GitHub runner cannot, for the
#      reason at the top: `checkPackJars` gates `compileJava` and the three pack jars are
#      CurseForge distributions on no maven CI can reach. Automating this needs those jars
#      mirrored somewhere fetchable, which is a supply-chain and licensing decision for Jake
#      rather than a change anyone should make quietly.
#
#   2. RUN THIS SCRIPT. It is deliberately not wired to a push or a merge. A release is
#      outward-facing and hard to unsay, and cutting one is a decision about what other people
#      should install rather than a consequence of a commit landing.
#
# THE INSTALL IS ALSO A HUMAN STEP and always will be: the jar goes in `mods/` on the machines
# that play and on the server they play on, which no automation here can reach. That is the
# whole point -- the jar becoming DOWNLOADABLE is what this fixes, and it was the blocking half.
#
# THIS COMMENT AND THE NOTES ABOVE BOTH SAID "a client's `mods/`" UNTIL 0.10.0, AND THAT HAD
# BEEN WRONG SINCE #19 PHASE 2 MADE THE MOD COMMON-SIDE. It registers `CalculatorItem` and
# opens `PlanBookNetwork`, and it declares no `acceptableRemoteVersions`, so a client carrying
# an item the server's registry lacks is refused at connect. Someone following the old notes
# onto a multiplayer server got a handshake failure with nothing pointing at the cause. Caught
# on the first release ever cut, which is the run where a stale install instruction stops being
# a docs problem and becomes the only thing anybody reads.
#
# WHAT THIS DOES REMOVE is every way of getting it wrong that a hand-cut release invites:
# publishing the `-dev` jar, publishing a jar built from other source than the commit being
# tagged, publishing from a dirty tree nobody else can reproduce, publishing a jar whose schema
# nobody stated, and re-pointing a tag somebody has already installed from. All five are
# refusals above, and all five were exercised rather than reasoned about.
# ---------------------------------------------------------------------------
