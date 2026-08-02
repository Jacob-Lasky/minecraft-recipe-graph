#!/bin/sh
#
# Build the mod jar and verify it is the one this source tree would produce.
#
# Usage, from the repository root:
#
#   mod/tools/build-jar.sh                 # build at the current version
#   mod/tools/build-jar.sh 0.10.0          # build at an explicit version, bumping first
#   GRADLE_CACHE=<dir> mod/tools/build-jar.sh
#   PACK_MODS=<instance mods dir> mod/tools/build-jar.sh
#
# THE JAR IS NOT TRACKED IN GIT, and that is deliberate. `dist/` used to hold one 50 KB
# binary that every change under `mod/src/main/java` invalidated, so with several branches in
# flight it conflicted on essentially every merge while no source file conflicted at all. A
# generated artifact was being hand-carried through code review. It is produced on demand
# now, and `tests/test_dist_jar.py` verifies whatever this leaves in `mod/build/libs`.
#
# DO NOT install the `-dev` jar. `build` writes it beside the real one, within a few hundred
# bytes of it, and the only reliable tell is that it carries ZERO SRG references. Forge loads
# it happily and then fails on obfuscated names at runtime -- in the client, after a launch,
# which is the single most expensive place in this project to discover a mistake. This script
# refuses to finish if what it produced has no SRG references.
#
# A VERSION ARGUMENT BUMPS `mod_version`; no argument leaves it alone. Bumping used to happen
# on every build, back when the jar was committed and two jars sharing a version was a real
# confusion. With nothing tracked there is no second copy to confuse it with, so the version
# now moves when someone means it to.

set -eu

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
PROPS=mod/gradle.properties
PACK_MODS=${PACK_MODS:-/coding/.recipegraph-build/deps}
# Overridable, because Gradle takes an EXCLUSIVE lock on GRADLE_USER_HOME: with this pinned,
# running the script while anyone else is building dies on "Timeout waiting to lock journal
# cache", which reads as a broken build rather than as a busy directory. Seed an alternative
# with `cp -a` from this one to skip the ~9m fernflower decompile.
GRADLE_CACHE=${GRADLE_CACHE:-/coding/.recipegraph-build/gradle-cache}

current=$(sed -n 's/^mod_version=//p' "$PROPS")
next=${1:-$current}

if [ "$next" != "$current" ]; then
    echo "[build-jar] $current -> $next"
fi
sed -i "s/^mod_version=$current\$/mod_version=$next/" "$PROPS"

# PUT THE VERSION BACK IF ANYTHING BELOW FAILS. `set -e` plus a bump before the build means a
# container that dies -- and with several agents on one host the usual cause is Gradle's
# exclusive lock on GRADLE_USER_HOME, nothing to do with this repository -- leaves the tree
# claiming a version whose jar was never produced. `test_dist_jar` then fails TWO ways at once,
# on the filename and on the source stamp, which is indistinguishable from an ordinary stale
# jar and sends the reader looking for a code problem that is not there. Observed exactly that.
#
# Only the version needs undoing: nothing else is written before the build.
revert_version() {
    status=$?
    if [ "$status" -ne 0 ]; then
        sed -i "s/^mod_version=$next\$/mod_version=$current/" "$PROPS"
        echo "[build-jar] FAILED (exit $status); reverted $PROPS to $current" >&2
        echo "[build-jar] if this was a gradle lock timeout, another build holds" >&2
        echo "[build-jar]   $GRADLE_CACHE -- pass GRADLE_CACHE=<your own copy> and retry" >&2
    fi
}
trap revert_version EXIT

# Host paths, because a bind mount source is resolved on the UnRAID host and not on this
# container's view of it. Getting this wrong mounts an EMPTY directory and the build fails
# with a missing-jar error that points at the wrong problem entirely.
host_path() {
    echo "$1" | sed 's|^/coding|/mnt/user/misc/coding|'
}

docker run --rm --user 99:100 --memory=4g --memory-swap=4g \
    -v "$(host_path "$ROOT")":/repo \
    -v "$(host_path "$PACK_MODS")":/deps:ro \
    -v "$(host_path "$GRADLE_CACHE")":/gradle \
    -e GRADLE_USER_HOME=/gradle -w /repo/mod eclipse-temurin:25-jdk \
    ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -Ppack_mods=/deps build

built="mod/build/libs/mc-recipe-dump-$next.jar"
[ -f "$built" ] || { echo "[build-jar] $built was not produced" >&2; exit 1; }

# Sweep stale sibling builds, so `test_dist_jar`'s "exactly one jar" assertion means what it
# says and nobody copies last week's version out of `libs/`. The `-dev` jar for THIS version
# stays where gradle put it; the verifier below filters it out by name and then refuses any
# jar that looks like one.
find mod/build/libs -name 'mc-recipe-dump-*.jar' ! -name "mc-recipe-dump-$next.jar" \
    ! -name "mc-recipe-dump-$next-dev.jar" -delete 2>/dev/null || true

python3 - "$next" <<'PY'
import re, sys, zipfile
sys.path.insert(0, ".")
from tests.test_dist_jar import _jar_schema

path = "mod/build/libs/mc-recipe-dump-%s.jar" % sys.argv[1]
z = zipfile.ZipFile(path)
srg = sum(len(re.findall(rb"func_|field_", z.read(n)))
          for n in z.namelist() if n.endswith(".class"))
print("[build-jar] %s  SCHEMA %s  SRG %d  stamp %s"
      % (path, _jar_schema(path), srg,
         z.read("mcrecipedump-source.sha256").decode().strip()[:16]))
# Zero SRG references means the -dev jar got copied. Fail loudly here rather than in the
# client after a launch, which is the only other place it shows up.
if srg == 0:
    raise SystemExit("[build-jar] 0 SRG refs: this is the -dev jar, not the real one")
PY

echo "[build-jar] install it with:"
echo "    cp $built '<instance>/minecraft/mods/'"
echo "[build-jar] and move the old jar OUT of mods/ first -- two jars under one modid is a"
echo "[build-jar] duplicate-mod load failure at startup, not a warning."
