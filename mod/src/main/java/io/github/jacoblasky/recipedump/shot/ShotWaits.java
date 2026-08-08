package io.github.jacoblasky.recipedump.shot;

/**
 * Bounded polling, in one place, for the shots that have to wait on a background thread.
 *
 * THERE WERE THREE COPIES OF THIS LOOP AND {@link LivePlanShot#awaitGraph}'S OWN COMMENT SAYS
 * WHY THERE MUST NOT BE. It is package-visible "because `PlannerShot`'s recipe picker needs the
 * same wait for the same reason, and a second copy of a bounded-poll loop is a second timeout
 * to keep in step" -- and that file then carried `awaitGraph` and `awaitPlan` as two copies,
 * with `MachinesShot` (#254) about to add a third. The rule was right and the code had already
 * outgrown the one exemption it granted itself.
 *
 * WHAT A COPY ACTUALLY COSTS HERE, since "duplication" on its own is not an argument. Each copy
 * carries a deadline, a sleep interval, an interrupt policy and a log line, and the four are
 * not independent: a copy that swallows `InterruptedException` without re-setting the flag
 * leaves a shot that cannot be killed, and one that forgets to log on timeout produces a run
 * that captures whatever it had got to and exits ZERO. Both were live in the copies this
 * replaces -- `awaitPlan` returned void, so a plan that never finished was photographed as
 * though it had.
 *
 * IT BLOCKS THE CALLING THREAD, WHICH IS THE CLIENT TICK, and that is correct here and would be
 * indefensible in a real client: the harness is one-shot and headless, there is nobody to
 * freeze, the only thing the tick still has to do is let `currentScreen` change, and the
 * alternative -- opening on an unfinished job and hoping the settle frames covered it -- is a
 * screenshot that races a background thread.
 */
final class ShotWaits {

    /** What the caller is waiting to stop being true. */
    interface Busy {
        boolean busy();
    }

    /**
     * Fifty milliseconds. Long enough that the poll costs nothing against jobs measured in
     * seconds, short enough that it adds no perceptible latency to the shot.
     */
    private static final long POLL_MILLIS = 50L;

    private ShotWaits() {
    }

    /**
     * Wait for `busy` to go false, or give up and SAY SO.
     *
     * RETURNS A BOOLEAN AND EVERY CALLER MUST READ IT. A void wait cannot be told apart from a
     * successful one by the code after it, which is how a timed-out plan got photographed as a
     * finished one. The timeout is logged as well as returned, because the PNG is what a
     * reviewer looks at and the log is the only place the difference is recorded.
     *
     * AN INTERRUPT RE-SETS THE FLAG AND REPORTS FAILURE rather than looping. Swallowing it
     * leaves a headless run that cannot be killed, which on a shared host is somebody else's
     * problem an hour later.
     *
     * @param what human-readable name of the thing being waited for, for the log line
     * @return true when the wait cleared, false on timeout or interrupt
     */
    static boolean until(String what, long timeoutMillis, Busy busy) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (busy.busy()) {
            if (System.currentTimeMillis() > deadline) {
                ShotHarness.log("gave up waiting for " + what + " after "
                        + (timeoutMillis / 1000L) + "s");
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                ShotHarness.log("interrupted while waiting for " + what);
                return false;
            }
        }
        return true;
    }
}
