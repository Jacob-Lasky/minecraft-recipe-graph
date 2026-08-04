# One heavy container at a time on this host. SOURCED, NOT EXECUTED:
#
#   . tools/gate.sh          # after cd'ing to the repository root
#   gated docker run ...
#
# WHY THIS IS A LOCK AND NOT A SENTENCE IN A README. "Run one container at a time" was an
# instruction given to several people working the same host at once, and every one of them can
# honour it perfectly while eight containers run: the rule is per-person and the constraint is
# per-host. Three 8 GB JVM caps against 20 GB free on the box that also runs the household is
# not a style problem. An instruction nothing enforces is not a constraint, which is the same
# argument `tools/ci-java.sh` makes about a test suite nobody is required to run.
#
# THE WAIT IS ANNOUNCED BEFORE IT HAPPENS, because a script that blocks silently for eleven
# minutes is indistinguishable from a script that has hung -- and reaching for ^C at that
# moment kills a build that was about to start. The holder writes its identity into the lock
# file so the waiter can say WHO it is waiting for.
#
# It blocks; it does not poll. Several waiters polling for a free slot race each other and the
# loser can starve indefinitely, whereas `flock` queues them.
#
# GATE_LOCK= (empty) opts out entirely, for the case where someone really does want two.
# GATE_WAIT=<seconds> caps the wait; the default hour is longer than any container here (a cold
# RFG build is ~9 minutes) so hitting it means something is wedged, and it FAILS rather than
# waiting forever, because a legible failure beats an invisible hang.
#
# THE LOCK FILE MUST LIVE ON A LOCAL FILESYSTEM, AND `/coding` IS NOT ONE. DO NOT move this
# default onto `/coding`, `/appdata`, `/home/claude` or any other share for "durability": every
# one of those is `fuse.shfs` here, and `flock -w`'s timeout is delivered as a signal to a
# waiter that FUSE parks in uninterruptible D state, where it cannot be delivered. The cap then
# never fires. Measured, same code, one holder sleeping 12s and a waiter capped at 3:
#
#     /tmp/gatefs.lock                        waited  3s, exit 75   the cap fired
#     /coding/.recipegraph-build/gatefs.lock  waited 11s, exit 0    the cap did nothing
#
# So a guard that is correct everywhere it is tested and inert where it is used -- the shape
# this project keeps re-finding, and the first version of this file shipped it, because the
# probe that "proved" GATE_WAIT was run on /tmp while the default pointed at /coding. A case
# picked for convenience is immune to the defect.
#
# The second consequence is worse than the first: a D-state waiter cannot be interrupted at
# all, so ^C does not free it. A pending SIGKILL lands when the lock releases and the process
# dies instead of running, which leaves zombie `flock` entries behind and no explanation.
#
# The lock only has to be shared by the things that LAUNCH containers, and all of those run
# inside this one container, so a local path is not a compromise -- it is the correct scope.
# IF YOU WRAP A CONTAINER BY HAND, WRAP IT ON THIS PATH, or you are queuing in a different
# lock's line and serialising nothing.
#
# DO NOT NEST IT. `flock` is not recursive: a gated script calling another gated script
# deadlocks against itself, with the second waiting on a lock its own parent holds. Each script
# that runs a container takes the gate around its OWN `docker run`, and callers do not wrap it.

GATE_LOCK=${GATE_LOCK-/tmp/recipegraph-container.gate.lock}
# SIX HOURS, AND THE OLD ONE HOUR WAS DERIVED FROM THE WRONG QUANTITY. The reasoning above for
# an hour is per-CONTAINER: longer than any single run here, so hitting it means something is
# wedged. That does not hold for a QUEUE. The honest wait for the last arrival is up to N times
# the container time, so the cap fires on a healthy host as soon as more than a handful of
# runners share it, and what it then prints tells the reader to consider deleting a lock that is
# being held correctly. Measured 2026-08-04, ten agents on this repo: `check.sh --java` took 16
# minutes rather than its documented 3 under the load they created, four waiters were queued at
# once, and the later ones would have failed at 3600 with the host working exactly as designed.
#
# So this is now longer than any plausible QUEUE rather than any single container, and the
# give-up message below reports whether the holder changed while waiting -- which is the signal
# that tells "busy" from "wedged" and is the thing the old message lacked. #214 records the
# better fix, which resets the deadline each time the lock changes hands rather than picking a
# bigger number; it needs a loop of bounded waits, and this file's header rejects polling for a
# stated starvation reason, so it is a design change rather than a constant change and it is not
# being made while ten runners are live on the lock.
GATE_WAIT=${GATE_WAIT:-21600}

gated() {
    if [ -z "${GATE_LOCK:-}" ]; then
        "$@"
        return $?
    fi
    if ! command -v flock > /dev/null 2>&1; then
        # SAID OUT LOUD rather than silently unserialised: the whole point is that nobody is
        # meant to be relying on remembering, so an unenforced run has to announce itself.
        echo "!! no flock on this host: containers are NOT being serialised" >&2
        "$@"
        return $?
    fi
    [ -e "$GATE_LOCK" ] || : > "$GATE_LOCK" 2>/dev/null || true
    (
        if ! flock -n 9; then
            echo "== waiting for the container gate: $GATE_LOCK =="
            if [ -s "$GATE_LOCK" ]; then
                echo "   held by: $(cat "$GATE_LOCK")"
            fi
            echo "   (one heavy container at a time; GATE_LOCK= to opt out, GATE_WAIT= to cap)"
            waited_from=$(date +%s)
            # CAPTURED BEFORE THE WAIT so the failure below can tell "busy" from "wedged". A
            # holder that CHANGED while we waited means the queue is moving and the cap was
            # simply too short; the same holder throughout means one run is stuck. The old
            # message could not distinguish them and advised deleting the lock either way,
            # which on a moving queue destroys a run that is doing the right thing. #214.
            held_before=$(cat "$GATE_LOCK" 2>/dev/null || echo "(unreadable)")
            flock -w "$GATE_WAIT" 9 || {
                held_after=$(cat "$GATE_LOCK" 2>/dev/null || echo "(unreadable)")
                echo "!! gave up after ${GATE_WAIT}s waiting for $GATE_LOCK." >&2
                if [ "$held_before" = "$held_after" ]; then
                    echo "!! The SAME run held it the whole time, so it is probably stuck:" >&2
                    echo "!!   $held_after" >&2
                    echo "!! Check \`docker ps\` and that pid before deleting the lock." >&2
                else
                    echo "!! The holder CHANGED while waiting, so the queue is MOVING and this" >&2
                    echo "!! cap was too short for it. Nothing is wedged." >&2
                    echo "!!   at the start: $held_before" >&2
                    echo "!!   at give-up:   $held_after" >&2
                    echo "!! Re-run, or raise GATE_WAIT. DO NOT delete the lock: you would be" >&2
                    echo "!! killing a run that is working correctly." >&2
                fi
                exit 75
            }
            echo "== gate acquired after $(( $(date +%s) - waited_from ))s =="
        fi
        printf 'pid %s, %s, since %s\n' "$$" "$0" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
            > "$GATE_LOCK" 2>/dev/null || true
        "$@"
    ) 9< "$GATE_LOCK"
}
