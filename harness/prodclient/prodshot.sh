#!/bin/sh
#
# Run the WHOLE PACK, obfuscated, headless, and render one of this mod's screens to a PNG.
#
#   harness/prodclient/prodshot.sh                        # boot only, no screen
#   harness/prodclient/prodshot.sh fixture                # -> shots/fixture.png
#   harness/prodclient/prodshot.sh <screen>[:<arg>] [out-name] [extra jvm args...]
#
# THIS IS NOT `harness/shot.sh` WITH MORE MODS, AND THE DIFFERENCE IS NOT THE MOD COUNT.
# `shot.sh` drives RetroFuturaGradle's `runClient`, which is a DEOBFUSCATED workspace: FML
# rewrites every production mod jar from SRG names to MCP names as it loads. That is right for
# developing one mod and fatal for a pack, because a coremod that string-matches an SRG name
# finds nothing after the rename. Measured on the 2026-08-03 boot, out of ThaumcraftFix:
#
#   IllegalArgumentException: Target method boolean
#     thaumcraft/common/entities/construct/EntityArcaneBore.func_184645_a(...) does not exist
#
# reported to the caller as a `ClassNotFoundException` for a class that is demonstrably in the
# jar. The pack has 75 coremods. This script launches the client the way a launcher does, with
# no Gradle and no remapping, which is also the way Jake's own client runs it.
#
# The tree it launches is built by `assemble.py`; run that first, it is idempotent and fast
# once warm. The game directory (mods, config, saves) is separate and is NOT built here.
#
# WHY THE `-v` SOURCES ARE NOT THE PATHS YOU SEE. Every mount source is resolved by the docker
# daemon on the UnRAID HOST, and this container's `/coding` is the host's
# `/mnt/user/misc/coding`. A wrong source does not fail, it mounts an EMPTY directory, and the
# client then reports a missing file rather than a bad path.
set -e

SCREEN="${1:-}"
OUT_NAME="${2:-$(printf '%s' "$SCREEN" | tr ':' '-')}"
# Written as two separate `if`s rather than `[ $# -gt 0 ] && shift`, because that form's
# compound exit status is 1 when there is nothing to shift, and under `set -e` that aborts the
# script on the zero-argument invocation, which is a valid one here (boot only).
if [ "$#" -gt 0 ]; then shift; fi
if [ "$#" -gt 0 ]; then shift; fi

. "$(dirname "$0")/../../tools/gate.sh"

HOST_CODING="${HOST_CODING:-/mnt/user/misc/coding}"
REPO_NAME="${REPO_NAME:-$(basename "$(cd "$(dirname "$0")/../.." && pwd)")}"
HOST_REPO="${HOST_REPO:-$HOST_CODING/$REPO_NAME}"
HOST_BUILD="${HOST_BUILD:-$HOST_CODING/.recipegraph-build}"
LOCAL_BUILD="${LOCAL_BUILD:-/coding/.recipegraph-build}"

PRODCLIENT="${PRODCLIENT:-prodclient}"
INSTANCE="${INSTANCE:-prodinstance}"
IMAGE="${IMAGE:-mcrecipedump-prodclient:latest}"

# 7g of container against 5g of heap. DO NOT raise the container past 8g: Tower runs the
# household's Home Assistant and its doorbell in sibling containers, and an OOM here takes
# those with it.
#
# THE NUMBERS ARE MEASURED AND THE FIRST MEASUREMENT WAS MISLEADING. The dev-workspace attempt
# peaked at 2.9 GiB, which looked like plenty of headroom, but that run CRASHED during mod
# construction and never reached the expensive part. A production boot that gets through to
# item model baking peaked at 5.7 GiB against a 6g cap, which is not headroom, it is a near
# miss. A peak read off a run that died early measures how far it got, not what the work costs.
MEMORY="${MEMORY:-7g}"
CLIENT_HEAP="${CLIENT_HEAP:-5G}"
SHOT_WIDTH="${SHOT_WIDTH:-1280}"
SHOT_HEIGHT="${SHOT_HEIGHT:-800}"

if [ ! -f "$LOCAL_BUILD/$PRODCLIENT/classpath.txt" ]; then
    echo "prodshot.sh: no assembled client at $LOCAL_BUILD/$PRODCLIENT" >&2
    echo "  run: python3 $(dirname "$0")/assemble.py" >&2
    exit 1
fi
if [ ! -d "$LOCAL_BUILD/$INSTANCE/mods" ]; then
    echo "prodshot.sh: no game directory at $LOCAL_BUILD/$INSTANCE (needs mods/)" >&2
    exit 1
fi

OBJECTS=$(sed -n 's/.*"assetObjectsSource": *"\([^"]*\)".*/\1/p' \
    "$LOCAL_BUILD/$PRODCLIENT/launch.json")
if [ -z "$OBJECTS" ]; then
    echo "prodshot.sh: launch.json names no assetObjectsSource; re-run assemble.py" >&2
    exit 1
fi
HOST_OBJECTS=$(printf '%s' "$OBJECTS" | sed "s|^/coding|$HOST_CODING|")

mkdir -p "$LOCAL_BUILD/shots"

# ALWAYS BUILD, rather than only when the image is missing. A cached build is about a second, and
# the alternative is that an edit to the Dockerfile keeps launching the OLD image with no sign that
# it has. `harness/shot.sh` says the same thing about itself and calls it the worst way to lose an
# afternoon, because the screenshot still appears and still looks plausible. The stakes are higher
# here: this image supplies the JVM the game runs on, so a stale one can be the wrong Java.
docker build -q -t "$IMAGE" "$(dirname "$0")" >/dev/null

# AND ALWAYS REINSTALL THE MOD, for the identical reason one layer up. The image was rebuilt
# every run and the JAR UNDER TEST was not, so a shot silently measured whatever was staged the
# last time anybody ran `stage-instance.sh` -- which, on a branch that changes Java, is the one
# thing in the container that must not be stale. It presents as a screenshot of the old
# behaviour, taken 22 minutes after the change that was supposed to alter it, and there is
# nothing in the log to say so.
#
# UNDER ITS OWN GATE, SEQUENTIALLY, NOT NESTED INSIDE THE RUN'S. The copy has to be excluded
# from `mod/tools/build-jar.sh`, which holds the gate while gradle rewrites the very file being
# copied -- a `cp` landing mid-`reobfJar` puts a truncated jar in the instance, and Forge
# reports that as a mod that will not load rather than as a bad copy. Two SEQUENTIAL
# acquisitions are fine and a nested one is not: `tools/gate.sh` warns that `flock` is not
# recursive, so taking this inside the `gated docker run` below would deadlock.
gated "$(dirname "$0")/stage-instance.sh" --mod-only

SHOT_ARGS=""
OUT_PNG=""
if [ -n "$SCREEN" ]; then
    OUT_PNG="$LOCAL_BUILD/shots/$OUT_NAME.png"
    # Delete any previous PNG at this path FIRST. Without it, a run that dies after the client
    # starts leaves the last good screenshot sitting there and the next reader takes it for the
    # new one. The exit code exists to prevent exactly that, and a stale file defeats it.
    rm -f "$OUT_PNG"
    # NO `-Dmcrecipedump.shotWidth` / `shotHeight` HERE, and their absence is deliberate.
    # Nothing in the mod reads them: `mod/build.gradle` reads that pair and turns it into
    # Minecraft's own `--width` / `--height` game arguments, which is why they have no Java
    # constant while every other `mcrecipedump.*` property does. There is no build.gradle in
    # this launch, so passing them would set two properties nobody reads while the window
    # silently kept its default size. `launch.sh` passes the real game arguments instead,
    # from SHOT_WIDTH and SHOT_HEIGHT in the environment.
    SHOT_ARGS="-Dmcrecipedump.shot=$SCREEN -Dmcrecipedump.shotOut=/shots/$OUT_NAME.png"
fi

STARTED=$(date +%s)
CONTAINER="mrg-prodshot-$$"
echo "prodshot.sh: $(ls "$LOCAL_BUILD/$INSTANCE/mods"/*.jar 2>/dev/null | wc -l) mods," \
     "${MEMORY} container, ${CLIENT_HEAP} heap${SCREEN:+, screen '$SCREEN' -> $OUT_PNG}"

# THE WATCHDOG IS NOT OPTIONAL POLISH. This client's two worst failures were both silent: it does
# not exit on a mod-loading crash (it renders an error screen forever) and it does not time out on
# a modal AWT dialog (it waits for a click). Both look like a slow boot from outside and both are
# answered instantly by a thread dump. `stallwatch.sh` takes one automatically and prints it into
# this run's output. It never kills anything, so it cannot turn a slow run into a failed one.
#
# Started BEFORE `docker run` on purpose: it polls for the container by name, so a container that
# dies in its first seconds is simply never seen rather than being a race.
STALLWATCH_PID=""
if [ "${PRODSHOT_STALLWATCH:-1}" != "0" ]; then
    "$(dirname "$0")/stallwatch.sh" "$CONTAINER" "${STALL_SECONDS:-180}" &
    STALLWATCH_PID=$!
fi

set +e
# --user 99:100 is UnRAID's nobody:users. A root container writing into these bind mounts
# leaves root-owned files that wedge the array share for every other writer.
gated docker run --rm --name "$CONTAINER" \
    --user 99:100 \
    --memory="$MEMORY" --memory-swap="$MEMORY" \
    -v "$HOST_BUILD/$PRODCLIENT:/prodclient" \
    -v "$HOST_OBJECTS:/prodclient/assets/objects:ro" \
    -v "$HOST_BUILD/$INSTANCE:/instance" \
    -v "$HOST_REPO/tests:/instance/tests:ro" \
    -v "$HOST_BUILD/shots:/shots" \
    -v "$HOST_REPO/harness/prodclient:/harness:ro" \
    -e XVFB_SCREEN="${SHOT_WIDTH}x${SHOT_HEIGHT}x24" \
    -e CLIENT_HEAP="$CLIENT_HEAP" \
    -e SHOT_WIDTH="$SHOT_WIDTH" -e SHOT_HEIGHT="$SHOT_HEIGHT" \
    "$IMAGE" /harness/launch.sh $SHOT_ARGS "$@"
STATUS=$?
set -e

# The watchdog also exits on its own when the container disappears, but only at its next poll, so
# a caller that reads this script's output right after it returns can otherwise catch a trailing
# line from it. Killed explicitly, and failure to kill is ignored: it has usually already gone.
[ -n "$STALLWATCH_PID" ] && kill "$STALLWATCH_PID" 2>/dev/null || true

ELAPSED=$(( $(date +%s) - STARTED ))

# A CRASH REPORT IS A VERDICT AND THE EXIT CODE IS NOT ENOUGH ON ITS OWN. A 1.12.2 client that
# fails during mod loading does not exit: it opens an error screen and renders it forever, so a
# run that has already definitively failed will otherwise burn the whole timeout before saying
# so. Reported after the status because the two answer different questions and the interesting
# case is when they disagree.
CRASHES="$LOCAL_BUILD/$INSTANCE/crash-reports"
LATEST_CRASH=$(ls -t "$CRASHES"/*.txt 2>/dev/null | head -1)
if [ -n "$LATEST_CRASH" ]; then
    # Compared against the run's START TIME, not against its duration. Both answer "is this
    # report ours" for a long run, but the age-under-elapsed form gets steadily worse as runs get
    # shorter: a run that dies in ten seconds would adopt any report written in the last ten
    # seconds and, worse, disown one written by itself a moment before the clock was read.
    if [ "$(stat -c %Y "$LATEST_CRASH")" -ge "$STARTED" ]; then
        echo "prodshot.sh: THIS RUN WROTE A CRASH REPORT: $LATEST_CRASH" >&2
        sed -n '1,12p' "$LATEST_CRASH" >&2
    fi
fi

if [ "$STATUS" -ne 0 ]; then
    if [ -n "$OUT_PNG" ] && [ -s "$OUT_PNG" ]; then
        echo "prodshot.sh: FAILED after ${ELAPSED}s (exit $STATUS); the PNG at $OUT_PNG IS" \
             "this run's, read the verdict in the log before believing the picture" >&2
    else
        echo "prodshot.sh: FAILED after ${ELAPSED}s (exit $STATUS)" >&2
    fi
    exit "$STATUS"
fi
if [ -n "$OUT_PNG" ] && [ ! -s "$OUT_PNG" ]; then
    echo "prodshot.sh: client exited 0 but wrote no PNG at $OUT_PNG" >&2
    exit 1
fi
echo "prodshot.sh: OK in ${ELAPSED}s${OUT_PNG:+ -- $OUT_PNG}"
