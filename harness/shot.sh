#!/bin/sh
#
# Render one of this mod's GUIs to a PNG with no GPU, no window and nobody at the keyboard.
#
#   harness/shot.sh                     # the harness fixture panel -> shots/fixture.png
#   harness/shot.sh fixture             # the same, named explicitly
#   harness/shot.sh <screen>[:<arg>] [output-name] [extra gradle args...]
#
# Anything after the output name goes to gradle untouched, which is how the knobs the mod
# reads but this script does not set get through:
#
#   harness/shot.sh fixture fixture -Dmcrecipedump.shotDebugOverlay=true
#
# It builds a container image if one is missing, starts Xvfb plus mesa's llvmpipe inside it,
# runs RetroFuturaGradle's `runClient` against a five-mod dev set (Forge, MixinBooter,
# ModularUI, HEI, JEC and this mod), and the mod opens the named screen, writes the PNG and
# exits. Screens are registered one line each in `ShotScreens`; `mod/build.gradle`'s
# `stageDevMods` decides what is in the dev set.
#
# The exit code is the verdict: 0 means the PNG at the reported path is this run's. A PNG on
# disk on its own proves nothing, because a failed run leaves the previous one there.
#
# WHY THE PATHS BELOW ARE NOT THE PATHS YOU SEE. Every `-v` source is resolved by the docker
# daemon on the UnRAID HOST, not inside pocket-dev, and pocket-dev's `/coding` is the host's
# `/mnt/user/misc/coding`. Get this wrong and the mount SUCCEEDS with an empty directory:
# the build then fails on a missing repo rather than on a bad path. Override HOST_CODING if
# this ever runs somewhere else.
set -e

SCREEN="${1:-fixture}"
OUT_NAME="${2:-$(printf '%s' "$SCREEN" | tr ':' '-')}"
# `$@` is left holding the pass-through tail. Written as `if`s and not
# `[ $# -gt 0 ] && shift`, because that form's compound exit status is 1 when there is
# nothing to shift, and under `set -e` that silently aborts the whole script on the
# zero-argument invocation -- which is the DEFAULT one.
if [ "$#" -gt 0 ]; then shift; fi
if [ "$#" -gt 0 ]; then shift; fi

HOST_CODING="${HOST_CODING:-/mnt/user/misc/coding}"
REPO_NAME="${REPO_NAME:-$(basename "$(cd "$(dirname "$0")/.." && pwd)")}"
HOST_REPO="${HOST_REPO:-$HOST_CODING/$REPO_NAME}"
HOST_BUILD="${HOST_BUILD:-$HOST_CODING/.recipegraph-build}"

# This container's view of the same three places, for the checks below and for reading the
# PNG back out afterwards.
LOCAL_BUILD="${LOCAL_BUILD:-/coding/.recipegraph-build}"
LOCAL_SHOTS="$LOCAL_BUILD/shots"

# ITS OWN GRADLE HOME, not the `gradle-cache` a `build` uses. Gradle takes an exclusive lock
# on GRADLE_USER_HOME, so sharing one means a screenshot taken while someone is building dies
# with "Timeout waiting to lock journal cache" -- and the whole point of this harness is that
# it can be run at any moment without arranging anything. Seed it once from the warm build
# cache (`cp -a`) to skip the ~9m fernflower decompile; it is ~850 MB plus the vanilla assets
# `runClient` downloads on its first run.
CACHE_NAME="${CACHE_NAME:-gradle-cache-shot}"

IMAGE="${IMAGE:-mcrecipedump-shot:latest}"
# 4g, and DO NOT raise it past 8g. Tower runs the household's Home Assistant and its
# doorbell in sibling containers; an OOM here takes those with it.
MEMORY="${MEMORY:-4g}"
# 1280x800 gives a GUI screenshot room to breathe at Minecraft's default 2x GUI scale.
#
# ONE SOURCE OF TRUTH: this drives both Minecraft's --width/--height AND the Xvfb screen
# passed in as XVFB_SCREEN below. Setting only the game's size leaves the X screen at the
# entrypoint's default, and a window larger than its screen is where LWJGL starts behaving
# oddly for reasons that look nothing like a mismatched resolution.
SHOT_WIDTH="${SHOT_WIDTH:-1280}"
SHOT_HEIGHT="${SHOT_HEIGHT:-800}"

if [ ! -d "$LOCAL_BUILD/deps" ]; then
    echo "shot.sh: no dev mod jars at $LOCAL_BUILD/deps" >&2
    echo "  stage them out of the pack first; see the minecraft-recipe-graph skill." >&2
    exit 1
fi

mkdir -p "$LOCAL_SHOTS" "$LOCAL_BUILD/$CACHE_NAME"

# ALWAYS build, rather than only when the image is missing. A cached build is about a second,
# and the alternative is that an edit to the Dockerfile keeps rendering against the old
# libraries with no sign that it has -- which is the worst way to lose an afternoon, because
# the screenshot still appears and still looks plausible.
docker build -q -t "$IMAGE" "$(dirname "$0")" >/dev/null

OUT_PNG="$LOCAL_SHOTS/$OUT_NAME.png"
# Remove any previous PNG at this path FIRST. Without it a run that fails after the client
# starts leaves the last successful screenshot sitting there, and the next person reads a
# stale picture as the new one -- the exact failure the exit code exists to prevent.
rm -f "$OUT_PNG"

STARTED=$(date +%s)
echo "shot.sh: screen '$SCREEN' -> $OUT_PNG"

set +e
# --user 99:100 is UnRAID's nobody:users. A root container writing into these bind mounts
# leaves root-owned files that wedge the array share for everything else that writes to it.
#
# `/repo` is the REPOSITORY ROOT and not `mod/`. `runClient` alone would survive a mod/-only
# mount, but the Java test suite would not: DigestFixtureTest reads
# tests/fixtures/nbt_digest.json from above `mod/` and fails with a bare IOException that
# names nothing. Keep the mount the same shape as the one the skill documents for `build`.
docker run --rm \
    --user 99:100 \
    --memory="$MEMORY" --memory-swap="$MEMORY" \
    -v "$HOST_REPO:/repo" \
    -v "$HOST_BUILD/deps:/deps:ro" \
    -v "$HOST_BUILD/$CACHE_NAME:/gradle" \
    -v "$HOST_BUILD/shots:/shots" \
    -e GRADLE_USER_HOME=/gradle \
    -e XVFB_SCREEN="${SHOT_WIDTH}x${SHOT_HEIGHT}x24" \
    -w /repo/mod \
    "$IMAGE" \
    ./gradlew --no-daemon --console=plain runClient \
        -Ppack_mods=/deps \
        -Dorg.gradle.jvmargs=-Xmx1g \
        -Dmcrecipedump.shot="$SCREEN" \
        -Dmcrecipedump.shotOut="/shots/$OUT_NAME.png" \
        -Dmcrecipedump.shotWidth="$SHOT_WIDTH" \
        -Dmcrecipedump.shotHeight="$SHOT_HEIGHT" \
        "$@"
STATUS=$?
set -e

ELAPSED=$(( $(date +%s) - STARTED ))

if [ "$STATUS" -ne 0 ]; then
    echo "shot.sh: FAILED after ${ELAPSED}s (exit $STATUS); no screenshot" >&2
    exit "$STATUS"
fi
if [ ! -s "$OUT_PNG" ]; then
    echo "shot.sh: client exited 0 but wrote no PNG at $OUT_PNG" >&2
    exit 1
fi
echo "shot.sh: OK in ${ELAPSED}s -- $OUT_PNG"
