package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerEntry;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;

/**
 * The planner opened DURING the graph read, photographed on either side of the graph landing.
 *
 * `-Dmcrecipedump.shot=planner-recovery:loading` is the wait a player sees when they
 * right-click the calculator a second after joining, and `planner-recovery:recovered` is the
 * same window a few seconds later. Two runs rather than one because the harness captures one
 * frame, and the pair IS the artifact: either picture alone is consistent with the bug.
 *
 * <h2>Why this is a probe and not only a picture</h2>
 *
 * #201 is that the second picture did not exist. `PlannerEntry.startPlan` returned early with
 * no graph, so no plan was started; the window rebuilds on a counter and no counter moved; and
 * the window therefore showed "reading graph.json" until the player closed it and used the
 * item again. Every piece was individually correct, which is why a screenshot of the FIRST
 * state proves nothing at all -- it is the correct picture in both the fixed and the broken
 * build.
 *
 * So `recovered` states its criteria and answers them: the graph reached READY, a plan for the
 * target this screen asked for finished, and {@code PlannerEntry.stateFor} now says "draw the
 * tree" rather than naming a not-yet state. A build with the defect cannot satisfy those, and
 * says so through {@link ShotScreens#reportFail} with the frozen window still captured beside
 * the verdict.
 *
 * <h2>NOTHING HERE STARTS THE PLAN, AND THAT IS THE POINT</h2>
 *
 * {@link LivePlanShot} calls `PlannerService.plan` itself, which is right for what it proves --
 * that the graph, the cost model and the solver work in a client. This must not, because the
 * behaviour under test is that the WINDOW starts the plan when the graph lands. The only calls
 * below are `startLoad` and `PlannerEntry.open`, which is exactly what a player's right-click
 * does; everything after that is `PlannerScreen.PlannerWindow.onUpdate` on the client tick.
 *
 * <h2>IT HOLDS THE CAPTURE RATHER THAN BLOCKING THE TICK</h2>
 *
 * The opposite of `LivePlanShot`, deliberately and for a reason that would break this screen
 * if copied: that one blocks the client tick inside its opener because it has already got its
 * answer and only needs the screen to settle. Blocking here would stop the very tick the
 * recovery runs on, so the window could never rebuild and this screen would report the defect
 * against a build that does not have it. {@link ShotScreens.Hold} keeps the client running.
 */
final class PlannerRecoveryShot {

    /**
     * Borax, which is what {@link LivePlanShot} plans and for the reason recorded there: its
     * route is the canary `tools/cost-probe.py` tunes the cost model against, and it is 15
     * nodes, which fits a panel. Overridable, because a pack whose graph lacks it should be
     * able to shoot this without editing the mod.
     */
    private static final String PROP_TARGET = "mcrecipedump.planTarget";
    private static final String DEFAULT_TARGET = "nuclearcraft:compound:7";

    /**
     * How long `recovered` waits for the window to catch up, in milliseconds.
     *
     * Longer than {@link LivePlanShot}'s two minutes because this waits for BOTH halves in
     * sequence -- the 116 MB read and then a first plan, which prices the whole pack's cost
     * table -- and the wait is bounded by a stated verdict rather than by the run timeout, so
     * being generous costs nothing on a passing run and produces a legible failure on a
     * failing one.
     */
    private static final long RECOVERY_WAIT_MILLIS = 300_000L;

    /** Ceiling on the read `CommonProxy.preInit` started, which is finished long before this. */
    private static final long STARTUP_LOAD_WAIT_MILLIS = 300_000L;

    /**
     * Polls to keep holding AFTER the criteria are met, so the rebuilt panel is not caught
     * mid-fade.
     *
     * The window that gets photographed here is not the one that was opened: it is a REPLACEMENT
     * opened by `onUpdate` when the plan landed, and ModularUI animates every panel open. The
     * harness's own settle window was spent on the first window, so without this the capture is
     * of a tree fading in -- which reads as a rendering defect rather than as a timing one, the
     * same trap `ShotHarness.DEFAULT_SETTLE_FRAMES` exists for.
     */
    private static final int SETTLE_POLLS_AFTER_RECOVERY = 30;

    private PlannerRecoveryShot() {
    }

    /** `planner-recovery`, `planner-recovery:loading` or `planner-recovery:recovered`. */
    static void open(String arg) {
        boolean recovered = "recovered".equalsIgnoreCase(trimmed(arg));
        String target = System.getProperty(PROP_TARGET, DEFAULT_TARGET);

        // BUT NOT UNDER preInit'S OWN READ. `GraphService.reset` does not stop a running loader
        // and says so on itself: a reset mid-read is overwritten seconds later by the load it
        // was meant to cancel, and the service comes back READY with a graph nobody asked for.
        // By the time the main menu exists that read finished minutes ago, so this wait normally
        // returns at once -- it is here because "normally" is the word that makes a race.
        final GraphService graphs = GraphService.get();
        ShotWaits.until("preInit's own graph load", STARTUP_LOAD_WAIT_MILLIS,
                new ShotWaits.Busy() {
                    @Override
                    public boolean busy() {
                        return graphs.state() == GraphService.State.LOADING;
                    }
                });
        // A LOAD THAT IS GENUINELY IN FLIGHT. Without this the window would open against a graph
        // that has been READY since before the main menu and photograph the ordinary path;
        // `reset` then `startLoad` puts the service back into the state a player is in a second
        // after joining. The planner service goes too, or a plan left over from an earlier
        // screen would make the window draw a tree it never had to recover.
        PlannerService.get().reset();
        GraphService.get().reset();
        // null config directory: `GraphSource.locate` drops that candidate and falls through to
        // `-Dmcrecipedump.graph`, which `harness/shot.sh` sets from $RECIPEGRAPH_ORACLE. See
        // `GraphSource.locate`, which takes the directory as an argument for this case.
        GraphService.get().startLoad(null);
        ShotHarness.log("graph after startLoad: " + GraphService.get().describe());

        PlanBook book = new PlanBook();
        book.setTodo(target, 1L);
        // THE SAME DOOR THE CALCULATOR ITEM USES. `ClientProxy.openPlanner` calls this with the
        // player's book; nothing else about the path differs, and going anywhere else would
        // photograph a window a player cannot get to.
        PlannerEntry.open(book);

        ShotScreens.expectReport(recovered
                ? "the planner opened during the load must end up showing the plan"
                : "the planner opened during the load must show the load");
        ShotScreens.holdCapture(recovered ? recoveryHold(target) : loadingHold());
    }

    /**
     * `loading`: capture straight away, and say whether the graph really was still loading.
     *
     * NEVER BUSY, so this changes no timing at all -- it is polled once, immediately after the
     * harness's settle window and immediately before the capture, which is the only moment at
     * which the question "is this picture of a load" has an answer worth recording.
     *
     * IT CAN FAIL, and the failure is worth having rather than a nuisance: a graph small enough
     * or a host fast enough to finish inside the settle window produces a perfectly good
     * picture of something else entirely, and this is what stops that being filed as the
     * "planner during load" artifact.
     */
    private static ShotScreens.Hold loadingHold() {
        return new ShotScreens.Hold() {
            @Override
            public boolean busy() {
                GraphService graphs = GraphService.get();
                ShotHarness.log("at capture: " + graphs.describe());
                if (graphs.state() == GraphService.State.LOADING) {
                    ShotScreens.reportPass();
                } else {
                    ShotScreens.reportFail("the graph was " + graphs.state()
                            + " at capture, so this is not a picture of the load: "
                            + graphs.describe());
                }
                return false;
            }
        };
    }

    /**
     * `recovered`: hold until the window has caught up with the graph, then say whether it did.
     *
     * THE THREE CRITERIA ARE ASKED SEPARATELY so the failure names which one did not hold. "No
     * graph" is a missing `$RECIPEGRAPH_ORACLE` and a problem with the invocation; "the graph
     * landed and nothing planned" is #201 itself; and `stateFor` still naming a state after a
     * finished plan would be a fourth defect again, in the chooser rather than in the window.
     */
    private static ShotScreens.Hold recoveryHold(final String target) {
        return new ShotScreens.Hold() {

            private final long deadline = System.currentTimeMillis() + RECOVERY_WAIT_MILLIS;
            private int settling = SETTLE_POLLS_AFTER_RECOVERY;
            private long lastReport;

            @Override
            public boolean busy() {
                GraphService graphs = GraphService.get();
                PlannerService planner = PlannerService.get();
                if (planner.state() == PlannerService.State.DONE) {
                    if (settling-- > 0) {
                        return true;
                    }
                    verdict(graphs, planner);
                    return false;
                }
                if (System.currentTimeMillis() > deadline) {
                    verdict(graphs, planner);
                    return false;
                }
                report(graphs, planner);
                return true;
            }

            /** Progress on a cadence, because a silent container reads as a dead one. */
            private void report(GraphService graphs, PlannerService planner) {
                long now = System.currentTimeMillis();
                if (now - lastReport < 5_000L) {
                    return;
                }
                lastReport = now;
                ShotHarness.log("waiting: graph " + graphs.describe()
                        + "; plan " + planner.describe());
            }

            private void verdict(GraphService graphs, PlannerService planner) {
                ShotHarness.log("graph: " + graphs.describe());
                ShotHarness.log("plan: " + planner.describe());
                if (graphs.state() != GraphService.State.READY) {
                    ShotScreens.reportFail("the graph never became READY, so this run says"
                            + " nothing about the planner: " + graphs.describe());
                    return;
                }
                if (planner.state() != PlannerService.State.DONE
                        || !target.equals(planner.targetKey())) {
                    // #201 EXACTLY. The graph landed and the window that was open when it did
                    // never asked for the plan it was opened for.
                    ShotScreens.reportFail("the graph landed and no plan for " + target
                            + " was ever started -- the planner is " + planner.state()
                            + " (" + planner.describe() + "). The window opened during the"
                            + " load did not replay its target.");
                    return;
                }
                if (PlannerEntry.stateFor(graphs, planner) != null) {
                    ShotScreens.reportFail("a plan finished and the chooser still wants to"
                            + " draw a not-yet panel: "
                            + PlannerEntry.stateFor(graphs, planner).message());
                    return;
                }
                ShotScreens.reportPass();
            }
        };
    }

    private static String trimmed(String arg) {
        return arg == null ? "" : arg.trim();
    }
}
