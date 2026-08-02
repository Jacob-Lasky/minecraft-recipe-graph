#!/bin/sh
#
# Rebuild `dist/` so its source stamp matches `mod/src/main/java` as it stands now.
#
# Usage, from the repository root:
#
#   mod/tools/rebuild-dist.sh              # bump the patch version and rebuild
#   mod/tools/rebuild-dist.sh 0.10.0       # rebuild at an explicit version
#   PACK_MODS=/some/instance/mods mod/tools/rebuild-dist.sh
#
# WHY THIS EXISTS. `test_dist_jar` asserts the committed jar was built from the source that
# is checked in NOW, by comparing a hash embedded at build time against one recomputed over
# `mod/src/main/java`. That check earns its keep -- a jar rebuilt three commits ago passes
# every other assertion in that file, because the SCHEMA int deliberately does not move for
# most changes -- but it means ANY change under `mod/src/main/java` makes `dist/` stale, and
# the failure surfaces at merge time as a bare hash mismatch on a branch that never touched
# Python. It has already bitten twice in one day: once merging #132 onto a master that had
# taken #130, and once merging #126, which added a package the jar had never seen.
#
# #19 moves the whole planner into the mod, so this is about to be the common case rather
# than an occasional one. Run it as the last step of any change touching mod sources.
#
# THE VERSION IS BUMPED, NOT REUSED, AND THAT IS THE POINT. Two jars that both say 0.9.2 and
# differ is the exact confusion the source stamp exists to prevent, and the one situation in
# which a filename tells a confident lie. Bumping costs nothing: nobody outside this
# repository consumes these version numbers.
#
# DO NOT commit the `-dev` jar. It sits beside the real one, within a few hundred bytes of
# it, and is distinguishable only by carrying zero SRG references -- Forge will load it and
# then fail on obfuscated names at runtime, in the client, after a launch. This script copies
# by exact name so it cannot pick the wrong one, and verifies the SRG count afterwards.

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
if [ $# -ge 1 ]; then
    next=$1
else
    next=$(echo "$current" | awk -F. '{printf "%s.%s.%d", $1, $2, $3 + 1}')
fi

echo "[rebuild-dist] $current -> $next"
sed -i "s/^mod_version=$current\$/mod_version=$next/" "$PROPS"

# PUT THE VERSION BACK IF ANYTHING BELOW FAILS. `set -e` plus a bump before the build means a
# container that dies -- and with several agents on one host the usual cause is Gradle's
# exclusive lock on GRADLE_USER_HOME, nothing to do with this repository -- leaves the tree
# claiming a version whose jar was never produced. `test_dist_jar` then fails TWO ways at once,
# on the filename and on the source stamp, which is indistinguishable from an ordinary stale
# jar and sends the reader looking for a code problem that is not there. Observed exactly that.
#
# Only the version is reverted. `dist/` and the README are not touched until after a
# successful build, so there is nothing else to undo.
revert_version() {
    status=$?
    if [ "$status" -ne 0 ]; then
        sed -i "s/^mod_version=$next\$/mod_version=$current/" "$PROPS"
        echo "[rebuild-dist] FAILED (exit $status); reverted $PROPS to $current" >&2
        echo "[rebuild-dist] if this was a gradle lock timeout, another build holds" >&2
        echo "[rebuild-dist]   $GRADLE_CACHE -- pass GRADLE_CACHE=<your own copy> and retry" >&2
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
[ -f "$built" ] || { echo "[rebuild-dist] $built was not produced" >&2; exit 1; }

# `dist/` holds exactly one jar. Removing the old one first means a failed copy leaves an
# empty directory rather than two jars, and two jars is the state that loads twice under one
# modid and fails the game at startup if both ever reach a mods/ folder.
rm -f dist/mc-recipe-dump-*.jar
mkdir -p dist
cp "$built" dist/

# The README names the jar in two places, and `test_dist_jar` checks that too.
sed -i "s/mc-recipe-dump-[0-9.]*\.jar/mc-recipe-dump-$next.jar/g" README.md

python3 - "$next" <<'PY'
import re, sys, zipfile
sys.path.insert(0, ".")
from tests.test_dist_jar import _jar_schema

path = "dist/mc-recipe-dump-%s.jar" % sys.argv[1]
z = zipfile.ZipFile(path)
srg = sum(len(re.findall(rb"func_|field_", z.read(n)))
          for n in z.namelist() if n.endswith(".class"))
print("[rebuild-dist] %s  SCHEMA %s  SRG %d  stamp %s"
      % (path, _jar_schema(path), srg,
         z.read("mcrecipedump-source.sha256").decode().strip()[:16]))
# Zero SRG references means the -dev jar got copied. Fail loudly here rather than in the
# client after a launch, which is the only other place it shows up.
if srg == 0:
    raise SystemExit("[rebuild-dist] 0 SRG refs: this is the -dev jar, not the real one")
PY

git add -A dist "$PROPS" README.md

# STAGED, NOT COMMITTED, AND SAYING SO IS THE POINT. This script writes three things that are
# easy to leave behind, and leaving them behind fails in the most confusing possible place:
# the branch's own test run is green because the working tree is right, the push carries only
# the commits, and `test_dist_jar` goes red on MASTER after the merge, naming a hash mismatch
# on a change that had nothing to do with the jar. Done exactly that once.
echo
echo "[rebuild-dist] STAGED, NOT COMMITTED. Commit before you push, or master goes red:"
git --no-pager diff --cached --stat
echo
echo "[rebuild-dist] then: python3 -m unittest discover -s tests -q"
