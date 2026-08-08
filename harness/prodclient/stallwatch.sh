#!/bin/sh
#
# Watch a running headless client and, the moment its log stops moving, ask the JVM what it is
# doing. Meant to be started in the background beside `prodshot.sh`; it never fails a run and
# never kills anything, it only turns silence into a stack trace.
#
#   harness/prodclient/stallwatch.sh <container> [stall-seconds]
#
# WHY THIS EXISTS. Twice now a whole-pack boot has gone quiet with one core busy and no crash
# report, and both times the guess about why was wrong before anyone looked:
#
#   * "almost certainly texture atlas stitching" -- it had crashed 90 minutes earlier and was
#     re-rendering a mod-error screen forever, which is what a 1.12.2 client does instead of
#     exiting.
#   * "still loading, it is a big pack" -- Modpack Config Checker had opened a Swing
#     `JOptionPane` from preInit because the heap was under the pack's recommended 7000 MB, and
#     the client thread was WAITING on an AWT monitor for a click that could never come.
#
# Both were answered in seconds by a thread dump and neither was answerable from the log, the exit
# code, or `docker stats`. A busy CPU distinguishes nothing: an error screen renders at 200%, and a
# blocked client still has render and chunk threads spinning. THE ONLY THING THAT ANSWERS "what is
# it waiting on" IS ASKING IT.
set -e

CONTAINER="${1:?usage: stallwatch.sh <container> [stall-seconds]}"
# 180s. Long enough that the genuinely quiet stretches of a 377-jar boot (item model baking logs
# in bursts) do not trip it, short enough to beat a 1800s screenshot timeout by a wide margin.
STALL="${2:-180}"
POLL=15

# ASK DOCKER, NOT A FILE. The obvious version of this watched the log file the caller redirects
# into, which couples the watchdog to how it was invoked: point it at the wrong path, or run
# `prodshot.sh` without a redirect, and it measures a file that never grows and fires on a
# perfectly healthy run. `docker logs --since` asks the daemon what the CONTAINER emitted, which is
# the thing actually being measured, and needs no argument beyond the name.

# WAIT FOR THE CONTAINER TO APPEAR BEFORE WATCHING FOR IT TO GO QUIET, and this is not a nicety:
# without it this whole script is a no-op. `prodshot.sh` starts the watchdog before `docker run`,
# and `docker run` sits behind the container gate, which blocks for as long as another heavy
# container is running. A bare `while docker ps | grep -q` therefore finds nothing on its first
# poll and exits immediately, leaving every gated run unwatched. MEASURED, not reasoned: a real
# `prodshot.sh` dump run had zero `stallwatch.sh` processes alive while its client was booting.
#
# That is the same shape as the `GATE_WAIT` defect in `tools/gate.sh`: a guard that behaves
# correctly in the case it was tried in and is inert in the case it exists for.
#
# The wait is BOUNDED and its expiry is silent. A container that never appears means the run
# failed before it started, which is `prodshot.sh`'s business to report, not this script's.
#
# THE BOUND IS THE GATE'S BOUND, and the old hardcoded hour was the same defect one level up as
# the missing wait loop itself. `tools/gate.sh` waits GATE_WAIT (six hours by default, sized for
# a QUEUE rather than for one container after #214), so any run that queued for more than an
# hour outlived its own watchdog and booted unwatched -- and with a dozen agents sharing this
# host, queues longer than an hour are the normal case, not the exotic one. A guard whose
# timeout is shorter than the wait it exists to survive is inert exactly when it is needed.
APPEAR_TIMEOUT="${STALLWATCH_APPEAR_TIMEOUT:-${GATE_WAIT:-21600}}"
waited=0
until docker ps --filter "name=^${CONTAINER}$" --quiet | grep -q .; do
    waited=$(( waited + POLL ))
    if [ "$waited" -ge "$APPEAR_TIMEOUT" ]; then
        exit 0
    fi
    sleep "$POLL"
done

dumped=0

while docker ps --filter "name=^${CONTAINER}$" --quiet | grep -q .; do
    recent=$(docker logs --since "${STALL}s" "$CONTAINER" 2>&1 | wc -c)
    if [ "$recent" -gt 0 ]; then
        # RE-ARM after any output. A run can stall, recover, and stall again for a different
        # reason, and one dump per container would hide the second one.
        dumped=0
    elif [ "$dumped" -eq 0 ]; then
        dumped=1
        echo "=============================================================="
        echo "stallwatch: $CONTAINER emitted nothing for ${STALL}s. Thread dump:"
        echo "=============================================================="
        # pid 1 is the JVM: `entrypoint.sh` execs `launch.sh` which execs `java`, so nothing
        # wraps it. `jstack` is present because the image is a JDK and not a JRE.
        docker exec "$CONTAINER" jstack 1 2>&1 \
            | sed -n '/"Client thread"/,/^$/p' \
            | head -40
        echo "-- (client thread only; full dump: docker exec $CONTAINER jstack 1) --"
        echo "=============================================================="
    fi
    sleep "$POLL"
done
