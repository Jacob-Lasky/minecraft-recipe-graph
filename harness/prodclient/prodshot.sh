#!/bin/sh
#
# Run the WHOLE PACK, obfuscated, headless, and render one of this mod's screens to a PNG.
#
#   harness/prodclient/prodshot.sh                        # boot only, no screen
#   harness/prodclient/prodshot.sh fixture                # -> shots/fixture.png
#   harness/prodclient/prodshot.sh <screen>[:<arg>] [out-name] [extra jvm args...]
#
# IN THE BACKGROUND, RUN IT UNDER `setsid nohup` AND NOTHING ELSE:
#
#   setsid nohup harness/prodclient/prodshot.sh dump > /tmp/dump.log 2>&1 < /dev/null &
#
# A plain `... &` leaves this run in the wrapper's process group, so whatever kills the wrapper
# -- a tool-call timeout, a closed session, a `kill %1` -- takes the client with it. Measured
# during #228: a run died seconds after the world loaded and wrote NO crash report, because
# nothing had crashed, and the absence of a report is what made it read as a pack problem.
# `setsid` puts the run in its own session so a group signal cannot reach it, and `nohup`
# covers the SIGHUP. The signal handler below turns the remaining cases into one loud line
# instead of silence, but a handler cannot catch SIGKILL: the invocation is the real fix.
#
# THE LOG ALWAYS ENDS WITH EXACTLY ONE OF THREE LINES -- `OK in`, `FAILED after`, `KILLED BY`
# -- AND A LOG THAT STOPS WITHOUT ONE WAS KILLED, NOT SLOW. That distinction is the whole
# problem: a wrapper death and a long queue produce the same silence, and on this host gate
# contention is usually true as well, so the wrong reading gets corroborated. Check the run,
# and check it for LIVENESS rather than for existence:
#
#   ps -o pid,ppid,stat,etime,cmd -p <pid>    # STAT must not contain Z
#   ps --ppid <pid>                           # a live run HAS children
#
# A zombie keeps its `/proc` entry and its elapsed time keeps climbing, so `/proc/<pid>`
# answers "identity", never "alive". Measured on this box: another agent read `01:11:29`,
# `01:32:13` and `02:33:51` off a process that had been `<defunct>` since the first reading,
# and waited two and a half hours on a run that was two minutes dead.
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

CONTAINER="mrg-prodshot-$$"
# WHEN THE BOOT ITSELF STARTED, written from inside the gate and read back out here, because a
# shell function that `gated` runs executes in a SUBSHELL and no variable it sets is visible
# afterwards. It exists for the crash-report check at the bottom, which asks "is that report
# ours" -- and the answer has to be measured from the moment the container started, not from
# the moment this script did. Those differ by the whole queue: with a dozen agents on one host
# the gate wait is hours, and every crash report any of THEM wrote in that time is newer than
# our start. The old code compared against the script's start and would happily print another
# agent's crash report under "THIS RUN WROTE A CRASH REPORT".
#
# Absent means no container ever ran, in which case no crash report can be ours at all.
BOOT_STAMP="${TMPDIR:-/tmp}/mrg-prodshot-$$.boot"
rm -f "$BOOT_STAMP"

# ONE GATE ACQUISITION SPANS THE RESTAGE AND THE BOOT, AND SPLITTING IT MEASURES SOMEONE
# ELSE'S JAR. #265.
#
# Two things have to be true at once and they used to be answered by two separate `gated`
# calls with a gap between them:
#
#   1. The mod jar is REINSTALLED before every run. The image was rebuilt every run and the JAR
#      UNDER TEST was not, so a shot silently measured whatever was staged the last time anybody
#      ran `stage-instance.sh`. On a branch that changes Java that is a 22-minute run
#      photographing the OLD behaviour with nothing in the log to say so.
#   2. The restage is SERIALISED against `mod/tools/build-jar.sh`, which holds the gate while
#      gradle rewrites the very file being copied. A `cp` landing mid-`reobfJar` puts a
#      truncated jar in the instance, and Forge reports that as a mod that will not load rather
#      than as a bad copy.
#
# Both were satisfied by `gated stage; gated docker run`, and the gap between the two
# acquisitions was a third defect nobody was looking for: another agent's `stage-instance.sh`
# restages the SHARED instance in that window, and this run then boots THEIR jar. Measured in
# #228 -- 431,599 bytes where 432,379 was expected, and the run failed on a screen that only
# exists on someone else's branch, so it read as "my probe screen is not registered" rather
# than "I measured somebody else's jar". One ~30 minute boot, and a wrong answer that pointed
# at innocent code. The guarantee `--mod-only` exists to provide (#146, #239) was being broken
# through a hole in its own locking.
#
# So: ONE acquisition, taken once, released once, covering both steps. The gate is already held
# for a ~28 minute boot, so extending it across a ~1 minute restage costs the queue about 3%.
#
# DO NOT PUT `gated` BACK IN FRONT OF THE `stage-instance.sh` LINE BELOW. `tools/gate.sh` says
# `flock` is not recursive: a second acquisition from inside this one blocks on a lock this
# process already holds and deadlocks the whole fleet for GATE_WAIT. `GATE_LOCK=` is gate.sh's
# own documented opt-out and is how a script says "my caller already holds it" -- see the
# composition note in that file's header. It is correct HERE and nowhere else.
prodshot_staged_run() {
    # `"$@"` INSIDE THIS FUNCTION IS THE FUNCTION'S ARGUMENTS, NOT THE SCRIPT'S, which is why
    # the call site passes them through. Miss that and the extra JVM arguments -- the ones that
    # set the screenshot timeout on a run that costs half an hour -- are silently dropped, and
    # a run with a default timeout looks exactly like a run with the one you asked for.
    #
    # ALWAYS BUILD, rather than only when the image is missing. A cached build is about a second,
    # and the alternative is that an edit to the Dockerfile keeps launching the OLD image with no
    # sign that it has. `harness/shot.sh` says the same thing about itself and calls it the worst
    # way to lose an afternoon, because the screenshot still appears and still looks plausible.
    # The stakes are higher here: this image supplies the JVM the game runs on, so a stale one
    # can be the wrong Java.
    #
    # AND INSIDE THE GATE, WHICH IT WAS NOT UNTIL #265 WAS FINISHED PROPERLY. Two reasons, and
    # the second is the one that makes it the same bug as this whole issue:
    #
    #   * `$IMAGE` IS A TAG ON A SHARED DAEMON. Every worktree on this host builds
    #     `mcrecipedump-prodclient:latest`, and a branch is allowed to change the Dockerfile --
    #     that is the whole reason this rebuilds every run. Outside the gate, another agent's
    #     build can replace the tag between our build and our `docker run`, and we then boot
    #     THEIR image. That is #265 exactly, one layer up from the jar, and with a worse tell:
    #     the image supplies the JVM, so the failure can be a Java version nobody chose.
    #   * A COLD BUILD IS NOT FREE. The cached case is ~1s; the cold case pulls a JDK base and
    #     runs apt, and racing that against another agent's 7g gradle container is precisely the
    #     memory contention `tools/gate.sh` exists to prevent, on a host that also runs the
    #     household's Home Assistant and its doorbell.
    #
    # It cannot deadlock: `docker build` does not source `tools/gate.sh` and takes no gate of its
    # own, so this is still ONE acquisition covering build, restage and boot.
    if ! docker build -q -t "$IMAGE" "$(dirname "$0")" >/dev/null; then
        echo "prodshot.sh: IMAGE BUILD FAILED, so nothing was staged or booted (exit 89 below" \
             "is that, not the client's)" >&2
        return 89
    fi
    if ! GATE_LOCK= "$(dirname "$0")/stage-instance.sh" --mod-only; then
        echo "prodshot.sh: STAGING FAILED, so nothing was booted (exit 90 below is that, not" \
             "the client's)" >&2
        return 90
    fi
    echo "prodshot.sh: $(ls "$LOCAL_BUILD/$INSTANCE/mods"/*.jar 2>/dev/null | wc -l) mods," \
         "${MEMORY} container, ${CLIENT_HEAP} heap${SCREEN:+, screen '$SCREEN' -> $OUT_PNG}"
    date +%s > "$BOOT_STAMP"
    # --user 99:100 is UnRAID's nobody:users. A root container writing into these bind mounts
    # leaves root-owned files that wedge the array share for every other writer.
    docker run --rm --name "$CONTAINER" \
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
}

# A SIGNAL IS NOT A PACK FAILURE AND USED TO BE INDISTINGUISHABLE FROM ONE. A killed run leaves
# no crash report, because nothing crashed, and #228 spent real time reading that silence as a
# broken pack. This says so in one line instead, which is the difference between a log that ends
# and a log that stops.
#
# THE HANDLER ALSO HAS TO END THE RUN, and that is the part that protects everyone else. The
# gate is held by processes this shell forked, and they do not care that their reader is gone:
# a boot in flight runs to completion, and a run still QUEUED wakes up when its turn comes and
# boots a 7g client for nobody, holding the gate for the whole 28 minutes. So:
#
#   * `docker rm -f` first, and it is NOT what frees the gate -- the tree kill below does that
#     on its own. It is what stops a 7g JVM being left running on a host that also runs the
#     household's Home Assistant and its doorbell. Killing the `docker run` CLI does not stop a
#     container, because the daemon owns it, so without this line a killed run releases the gate
#     to the next agent AND leaves its own container holding the memory: the worst of both.
#   * then the whole descendant tree, because THE PROCESS HOLDING THE LOCK IS NOT `$!`.
#     Measured: `gated job &` makes `$!` a wrapper subshell whose CHILD holds the descriptor, so
#     killing `$!` orphans the holder, which then acquires the gate and boots exactly as if
#     nothing had happened. Same shape as the "`$!` names the wrapper" trap this repository
#     already knows, one level further in.
#   * by descent from this pid, never by name. `pkill -f prodshot` matches every OTHER agent's
#     run on this host as well, and there are usually several; killing those is a far worse
#     outcome than the one being fixed here.
#   * with -KILL, not -TERM. A subshell INHERITS this shell's traps, so a polite TERM is caught
#     by this same handler down there and then DEFERRED until its own foreground command --
#     `flock` -- returns, which is precisely the wake-up-and-boot being prevented. Measured:
#     with -TERM the queued run still booted; with -KILL it does not. There is nothing in that
#     subshell to clean up, and `flock` releases the moment the descriptor closes.
#
# None of this is about the lock LEAKING. It cannot: `flock` lives on an open file descriptor
# and the kernel drops it when the last copy closes, SIGKILL included. It is about the 28
# minutes of everyone else's time between a reader dying and a run nobody wants finishing.
prodshot_on_signal() {
    echo "prodshot.sh: KILLED BY SIG$1 -- this run was stopped from outside. It is NOT a pack" \
         "failure and there will be no crash report. If it was backgrounded, use" \
         "\`setsid nohup\`; see the header." >&2
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    prodshot_kill_tree "$$"
    rm -f "$BOOT_STAMP"
    exit "$2"
}

prodshot_kill_tree() {
    for kid in $(ps -o pid= --ppid "$1" 2>/dev/null); do
        prodshot_kill_tree "$kid"
    done
    # Depth first, and never this shell itself: the handler still has a message to finish and an
    # exit status to return.
    [ "$1" = "$$" ] || kill -KILL "$1" 2>/dev/null
}
trap 'prodshot_on_signal HUP 129' HUP
trap 'prodshot_on_signal INT 130' INT
trap 'prodshot_on_signal TERM 143' TERM

QUEUED_AT=$(date +%s)

# THE WATCHDOG IS NOT OPTIONAL POLISH. This client's two worst failures were both silent: it does
# not exit on a mod-loading crash (it renders an error screen forever) and it does not time out on
# a modal AWT dialog (it waits for a click). Both look like a slow boot from outside and both are
# answered instantly by a thread dump. `stallwatch.sh` takes one automatically and prints it into
# this run's output. It never kills anything, so it cannot turn a slow run into a failed one.
#
# Started BEFORE `docker run` on purpose: it polls for the container by name, so a container that
# dies in its first seconds is simply never seen rather than being a race.
#
# AND OUTSIDE THE GATE, WHICH IS NOT A DETAIL OF LAYOUT. `gated` holds the lock through an open
# file descriptor in a subshell, and every child of that subshell INHERITS the descriptor. A
# background process started in there keeps the lock alive after the run has finished and the
# subshell has exited -- the watchdog polls on a 15s cycle, so that is up to 15s of the entire
# fleet queueing behind a run that is already over, and it would be invisible. DO NOT move this
# below the `gated` line.
STALLWATCH_PID=""
if [ "${PRODSHOT_STALLWATCH:-1}" != "0" ]; then
    "$(dirname "$0")/stallwatch.sh" "$CONTAINER" "${STALL_SECONDS:-180}" &
    STALLWATCH_PID=$!
fi

set +e
# BACKGROUNDED AND THEN WAITED ON, AND THAT IS NOT A STYLE CHOICE. A shell waiting for a
# FOREGROUND command does not run a trap handler when the signal arrives -- it records the
# signal and runs the handler after the command finishes. Measured here on dash: with `gated`
# in the foreground, a run killed while it was queued printed "KILLED BY SIGTERM" 20 seconds
# later, AFTER its turn came, AFTER it had booted the pack, and the handler then killed a
# process tree that had already finished doing the damage. The handler read as working and was
# inert, which is this project's most frequently rediscovered shape.
#
# `wait` is the exception: POSIX requires it to be interrupted by a trapped signal, so the
# handler runs at the moment the signal lands. Every other property of a foreground run --
# ordering, output, exit status -- is unchanged.
gated prodshot_staged_run "$@" &
wait $!
STATUS=$?
set -e

# The watchdog also exits on its own when the container disappears, but only at its next poll, so
# a caller that reads this script's output right after it returns can otherwise catch a trailing
# line from it. Killed explicitly, and failure to kill is ignored: it has usually already gone.
[ -n "$STALLWATCH_PID" ] && kill "$STALLWATCH_PID" 2>/dev/null || true

NOW=$(date +%s)
# Absent only when the run never reached `docker run`, i.e. staging failed. The whole elapsed
# time then counts as queue, which is true, and the crash-report check below is skipped
# outright: a run that booted nothing cannot have written a crash report, so every report in
# that directory belongs to somebody else.
BOOTED_AT=$(cat "$BOOT_STAMP" 2>/dev/null || true)
rm -f "$BOOT_STAMP"
ELAPSED=$(( NOW - QUEUED_AT ))
QUEUED=$(( ${BOOTED_AT:-$NOW} - QUEUED_AT ))

# A CRASH REPORT IS A VERDICT AND THE EXIT CODE IS NOT ENOUGH ON ITS OWN. A 1.12.2 client that
# fails during mod loading does not exit: it opens an error screen and renders it forever, so a
# run that has already definitively failed will otherwise burn the whole timeout before saying
# so. Reported after the status because the two answer different questions and the interesting
# case is when they disagree.
CRASHES="$LOCAL_BUILD/$INSTANCE/crash-reports"
LATEST_CRASH=$(ls -t "$CRASHES"/*.txt 2>/dev/null | head -1)
if [ -n "$LATEST_CRASH" ] && [ -n "$BOOTED_AT" ]; then
    # Compared against the moment the CONTAINER started, not against this script's start and not
    # against the run's duration. The duration form gets steadily worse as runs get shorter: a
    # run that dies in ten seconds would adopt any report written in the last ten seconds and,
    # worse, disown one written by itself a moment before the clock was read. The script-start
    # form is worse still on a busy host, because everything between the script starting and the
    # gate being granted is OTHER agents' runs, and their crash reports are all newer than it.
    if [ "$(stat -c %Y "$LATEST_CRASH")" -ge "$BOOTED_AT" ]; then
        echo "prodshot.sh: THIS RUN WROTE A CRASH REPORT: $LATEST_CRASH" >&2
        sed -n '1,12p' "$LATEST_CRASH" >&2
    fi
fi

# QUEUED IS REPORTED SEPARATELY, because the two numbers answer different questions and adding
# them together answers neither. `ELAPSED` is what the caller waited; `ELAPSED - QUEUED` is what
# the pack cost, which is the number worth comparing against the last run. With a dozen agents on
# this host the queue is routinely longer than the boot.
if [ "$STATUS" -ne 0 ]; then
    if [ -n "$OUT_PNG" ] && [ -s "$OUT_PNG" ]; then
        echo "prodshot.sh: FAILED after ${ELAPSED}s (${QUEUED}s queued; exit $STATUS); the PNG" \
             "at $OUT_PNG IS this run's, read the verdict in the log before believing the" \
             "picture" >&2
    else
        echo "prodshot.sh: FAILED after ${ELAPSED}s (${QUEUED}s queued; exit $STATUS)" >&2
    fi
    exit "$STATUS"
fi
if [ -n "$OUT_PNG" ] && [ ! -s "$OUT_PNG" ]; then
    echo "prodshot.sh: client exited 0 but wrote no PNG at $OUT_PNG" >&2
    exit 1
fi
echo "prodshot.sh: OK in ${ELAPSED}s (${QUEUED}s queued)${OUT_PNG:+ -- $OUT_PNG}"
