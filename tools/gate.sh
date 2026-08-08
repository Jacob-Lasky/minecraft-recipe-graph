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
#
# WHEN THE CALLER GENUINELY HAS TO HOLD IT ACROSS THE CALLEE, PASS `GATE_LOCK=`. #265: two
# sequential acquisitions around two steps leave a window between them, and another agent
# restaging shared state in that window is exactly the wrong-jar measurement the staging step
# exists to prevent. The fix is one acquisition spanning both steps, which means the outer
# script holds the gate while the inner one runs -- so the inner one must not try to take it:
#
#     gated my_whole_run                  # the OUTER acquisition, taken once
#     ...
#     my_whole_run() { GATE_LOCK= inner.sh; docker run ...; }
#
# `GATE_LOCK=` is the opt-out documented above and it is the right spelling here, because the
# thing it opts out of has already happened. IN A SCRIPT it is correct only on a line that is
# lexically inside a held gate, and anywhere else it is an ungated container;
# `tests/test_container_gate.py` checks that and nothing else in the tree would notice, because
# the container it lets loose is in a different file. A human typing it in front of a command
# is the separate, deliberate opt-out at the top of this header -- that one is a choice being
# made once, in the open, by someone who can see the host.
#
# THE COMMAND RUNS WITH THE LOCK'S DESCRIPTOR OPEN, AND SO DOES EVERY CHILD IT LEAVES BEHIND.
# The lock lives on fd 9 of the subshell below; the kernel holds it until the LAST copy of that
# descriptor closes. A process backgrounded from inside `"$@"` inherits it, so the gate stays
# held for as long as that process lives, however long after the container exited -- silently,
# because nothing here is waiting on it. Start watchdogs and other background helpers OUTSIDE
# the `gated` call. The same property is what makes the gate safe against death: a holder that
# is SIGKILLed drops the lock immediately, because its descriptors close with it, so there is
# no path where a crash leaves the fleet queueing forever.
#
# WHICH MEANS THERE IS NOTHING TO RECLAIM AND NO STALE LOCK TO CLEAN UP, and that is worth
# saying out loud because every pid-in-a-file lock this pattern resembles DOES need reclaiming.
# Measured here, 2026-08-08, one holder and one waiter on a /tmp lock:
#
#   holder alive, child `sleep` running          lock HELD
#   holder SIGKILLed, its child still running    lock HELD   -- holder's STAT is now Z
#   child gone as well                           lock FREE   -- nothing was cleaned up
#
# So the identity line written below names a process that may be a zombie while the lock is
# genuinely held by an orphaned child of it, and a "is the holder alive" check answers neither
# question. That is why the give-up message tells the reader to look at STAT and at children
# rather than at `/proc/<pid>`, and why it says not to delete this file.

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
# EXPORTED so that a child can size its own timeouts against the wait it is going to inherit.
# `harness/prodclient/stallwatch.sh` gives up waiting for its container after this long, and
# hardcoded an hour until #265: a run that queued for longer than that outlived its watchdog and
# booted unwatched, which on a host with a dozen agents is the ordinary case. Anything that waits
# for a gated run to START has to wait at least as long as the gate will.
export GATE_WAIT

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
                    # HOW to check it, because the obvious way answers the wrong question and
                    # the wrong answer is always "still working". Both halves measured.
                    echo "!! Check that pid for LIVENESS, not existence:" >&2
                    echo "!!   ps -o pid,ppid,stat,etime,cmd -p <pid>   # STAT must not be Z" >&2
                    echo "!! A zombie keeps its /proc entry and its etime keeps climbing, so" >&2
                    echo "!! \`test -d /proc/<pid>\` reports a dead run as a busy one." >&2
                    echo "!! And DELETING THIS FILE RELEASES NOTHING: flock lives on the open" >&2
                    echo "!! descriptor, so a new file just gives you a second, emptier queue" >&2
                    echo "!! to stand in while the real one is still held. What releases it is" >&2
                    echo "!! the holder AND its children exiting -- a child that inherited the" >&2
                    echo "!! descriptor keeps the lock after the holder is already a zombie." >&2
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
