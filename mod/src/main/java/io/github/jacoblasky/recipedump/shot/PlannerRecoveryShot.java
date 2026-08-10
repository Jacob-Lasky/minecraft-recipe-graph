package io.github.jacoblasky.recipedump.shot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

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
 * `planner-recovery:progress` is the third, and it is about the INSIDE of that wait rather than
 * its ends (#271): the same window part-way through the read, with the panel's redraws counted
 * on the way. See {@link #progressHold} -- it is the one of the three that had to stop trusting
 * `GraphService` and start watching the client, because #271 is precisely a disagreement
 * between what the service reported and what the panel drew.
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
     * half-drawn.
     *
     * The window that gets photographed here is not the one that was opened: it is a REPLACEMENT
     * opened by `onUpdate` when the plan landed, so the harness's own settle window was spent on
     * the FIRST window and the one being captured has had none. What that costs is a tree caught
     * before its rows have laid out, which reads as a rendering defect rather than as a timing
     * one -- the same trap `ShotHarness.DEFAULT_SETTLE_FRAMES` exists for.
     *
     * IT IS NOT A FADE, AND THIS COMMENT USED TO SAY IT WAS. "ModularUI animates every panel
     * open" is true of ModularUI and false of both harnesses here: `ModularPanel`'s animator is
     * gated on `ModularUI.Mods.NEA.isLoaded` -- NeverEnoughAnimations -- and that mod is in
     * neither mod set, not in `stageDevMods`' five jars and not in the 368 of
     * `prodinstance/mods`. Measured in #271, which captured a panel TWO settle frames after
     * opening it and immediately after a rebuild, and got a fully opaque panel: see
     * `docs/shots/planner-mid-load.png`. The thirty polls still earn their keep for the layout
     * reason above; DO NOT diagnose a thin or missing panel on this host as a fade.
     */
    private static final int SETTLE_POLLS_AFTER_RECOVERY = 30;

    private PlannerRecoveryShot() {
    }

    /**
     * `planner-recovery:progress` holds until this much of the file has been read (#271).
     *
     * HALF, SO THE PHOTOGRAPHED NUMBER CANNOT BE MISTAKEN FOR THE OPENING ONE. The window is
     * opened microseconds after `startLoad`, so a build with the defect photographs `0%`; a
     * floor near the start would produce a picture that reads as low single digits either way
     * and leave the reader deciding whether `2%` is motion or rounding. Overridable, because a
     * host or a graph that makes half unreachable inside the run timeout should be able to shoot
     * this without editing the mod.
     */
    private static final String PROP_FLOOR = "mcrecipedump.progressFloor";
    private static final float DEFAULT_FLOOR = 0.5f;

    /**
     * Window rebuilds `progress` must observe during the read before it will pass.
     *
     * TWO, AND TWO IS THE WHOLE ASSERTION. #271 is a panel that is rebuilt ONCE -- when the
     * window opens -- and then never again for the rest of the read, so one is what the defect
     * produces and any number above one is what it cannot. Asking for more would be asking the
     * host to be slow rather than asking the code to be right.
     */
    private static final int MIN_REBUILDS = 2;

    /** `planner-recovery`, `:loading`, `:progress` or `:recovered`. */
    static void open(String arg) {
        String mode = trimmed(arg);
        boolean recovered = "recovered".equalsIgnoreCase(mode);
        boolean progress = "progress".equalsIgnoreCase(mode);
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

        if (recovered) {
            ShotScreens.expectReport(
                    "the planner opened during the load must end up showing the plan");
            ShotScreens.holdCapture(recoveryHold(target));
        } else if (progress) {
            ShotScreens.expectReport(
                    "the planner's loading panel must be redrawn as the read advances");
            ShotScreens.holdCapture(progressHold(floor()));
        } else {
            ShotScreens.expectReport("the planner opened during the load must show the load");
            ShotScreens.holdCapture(loadingHold());
        }
    }

    private static float floor() {
        String raw = System.getProperty(PROP_FLOOR);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_FLOOR;
        }
        return Float.parseFloat(raw.trim());
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
     * `progress`: watch the loading panel being REDRAWN, then photograph it part-way through.
     *
     * <h2>Why `loading` was not enough, and why it is the cautionary tale rather than the model</h2>
     *
     * `loading` captures one frame and logs `at capture: reading graph.json, 37%` beside it. On
     * #201's branch it did exactly that and the PNG said `0%`. Two sources of truth for one
     * frame, disagreeing -- and the log is the one that was believed, because nobody opened the
     * picture. #271 is that disagreement: the log reads `GraphService`, which was right all
     * along, and the panel was built once at 0% and never rebuilt.
     *
     * SO THIS PROBE MUST NOT ASK `GraphService` WHETHER THE PANEL MOVED. It asks the client.
     * `PlannerScreen.PlannerWindow.onUpdate` refreshes by constructing a NEW window and handing
     * it to `ClientGUI.open`, which wraps it in a NEW `GuiScreenWrapper` and displays that -- so
     * a redraw is an IDENTITY CHANGE on `Minecraft.currentScreen` and a frozen panel is the same
     * object for the whole read. Counting those is a measurement of the defect itself rather
     * than of the number the defect never reached.
     *
     * ONE FRAME CANNOT SHOW MOTION, WHICH IS WHY THERE ARE TWO ARTIFACTS AND NOT ONE. The PNG
     * is a single percentage well away from zero, which a build with the defect cannot produce
     * because its panel was built microseconds after `startLoad`. The log is the sequence: every
     * rebuild, with what the service said at the moment it appeared. Neither alone is the
     * evidence; the pair is.
     *
     * <h2>The picture has its own positive control, in frame</h2>
     *
     * `PlannerWidgets.statePanel` draws an eyebrow reading "Planner" above the message line, and
     * NOTHING about #271 can change it. So a legible "Planner" in the PNG proves the panel, the
     * font, the rasteriser and the capture all worked, and makes the line under it a real
     * reading rather than a hope -- which matters because a blank panel and a correct render of
     * nothing are the same image, and this repository has filed one of those as an artifact.
     * DO NOT report a `progress` PNG without saying what the eyebrow looked like.
     *
     * <h2>It fails rather than photographing something else</h2>
     *
     * A read that finishes before the floor produces a perfectly good picture of a READY
     * planner, which is not this artifact. Same reasoning as {@link #loadingHold}, and the same
     * outcome: a stated verdict, so the run cannot be filed as "the loading panel at 60%".
     *
     * <h2>RUN IT WITH `-Dmcrecipedump.shotSettleFrames=2`, AND THIS SCREEN CANNOT MAKE YOU</h2>
     *
     * The harness's twenty settle frames exist to let a freshly opened panel finish arriving,
     * and they are spent BEFORE the hold starts -- so on a slow rasteriser they come out of the
     * 5.47 s this screen is trying to watch, and the hold can open on a read that is nearly
     * finished. It then sees one or two windows and reports "never redrawn", which is the
     * defect's own verdict produced by a mis-set knob. The frames buy nothing here either way:
     * the captured frame is chosen by the hold, seconds after the settle window closed, and
     * this panel is two lines of text with nothing in it that takes a frame to arrive.
     *
     * IT IS A FLAG THE CALLER HAS TO REMEMBER, WHICH {@link ShotScreens#requestSettleFrames}
     * CALLS A FOOTGUN AND IS RIGHT TO. That method takes the LARGER of the screen's request and
     * the default, so a screen can raise the window and has no way to lower it, and giving it
     * one means changing the harness every other shot on this host shares. So this is written
     * down in two places instead -- here and in `docs/shots/README.md`'s command block -- and
     * the run that gets it wrong FAILS rather than filing a wrong picture, which is the half
     * that actually had to be true.
     */
    private static ShotScreens.Hold progressHold(final float floor) {
        return new ShotScreens.Hold() {

            /** The wrapper last seen on screen. A new one IS a redraw; see the header. */
            private GuiScreen lastSeen;
            private int rebuilds;
            private float firstProgress = -1.0f;
            private float lastProgress = -1.0f;
            private boolean sawProgressGoBackwards;

            @Override
            public boolean busy() {
                GraphService graphs = GraphService.get();
                float progress = graphs.progress();
                GuiScreen onScreen = Minecraft.getMinecraft().currentScreen;
                if (onScreen != lastSeen) {
                    lastSeen = onScreen;
                    rebuilds++;
                    // THE FIRST ONE IS THE OPEN, NOT A REDRAW, and it is counted anyway so the
                    // number in the log is "windows this read produced" rather than a quantity
                    // whose off-by-one the reader has to take on trust. MIN_REBUILDS is 2 for
                    // exactly this reason.
                    ShotHarness.log("window #" + rebuilds + " on screen at " + graphs.describe()
                            + " (" + describeScreen(onScreen) + ")");
                    if (firstProgress < 0.0f) {
                        firstProgress = progress;
                    }
                    if (progress >= 0.0f && progress < lastProgress) {
                        sawProgressGoBackwards = true;
                    }
                    lastProgress = progress;
                }
                boolean stillReading = graphs.state() == GraphService.State.LOADING;
                if (stillReading && progress < floor) {
                    return true;
                }
                ShotHarness.log("at capture: " + graphs.describe());
                ShotHarness.log("windows during the read: " + rebuilds
                        + "; first at " + percent(firstProgress)
                        + ", last at " + percent(lastProgress));
                String problem = progressProblem(stillReading, rebuilds, sawProgressGoBackwards,
                                                 firstProgress, progress, floor);
                if (problem == null) {
                    ShotScreens.reportPass();
                } else {
                    ShotScreens.reportFail(problem + " (" + graphs.describe() + ")");
                }
                return false;
            }
        };
    }

    /**
     * Did the run see what it came to see? Null means yes.
     *
     * A PURE FUNCTION AND NOT THREE `reportFail` CALLS INSIDE THE HOLD, for the reason
     * {@link ShotScreens#expectReport} records at length: a live run exercises exactly ONE of
     * these branches, so the other three are read-off-the-code until something executes them.
     * The last guard in this package that was never executed had been wired to invert its own
     * signal and would have lied in both directions at once. `PlannerShotTest` drives all four.
     *
     * @param stillReading whether the graph was still LOADING when the hold released
     * @param rebuilds     distinct windows seen on screen during the read, the open included
     * @param progress     how far through the read the capture happens
     * @return why the run proves nothing, or null if it proves what it claims
     */
    static String progressProblem(boolean stillReading, int rebuilds, boolean wentBackwards,
                                  float firstProgress, float progress, float floor) {
        if (!stillReading) {
            // A perfectly good picture of a READY planner, which is a different artifact. Same
            // reasoning as `loadingHold`, and it must not be filed as "the panel at 60%".
            return "the read finished before " + percent(floor) + ", so there was no loading"
                    + " panel left to photograph. Rebuilds seen: " + rebuilds + ". Lower -D"
                    + PROP_FLOOR + ", or point $RECIPEGRAPH_ORACLE at the full graph";
        }
        if (rebuilds < MIN_REBUILDS) {
            // #271 EXACTLY. The read is half done and the window the player is looking at is
            // the one built before any of it had happened.
            return "the panel was drawn once and never redrawn: the read reached "
                    + percent(progress) + " and `Minecraft.currentScreen` is still the window"
                    + " opened at " + percent(firstProgress) + ", so whatever the PNG says is"
                    + " what it said at 0%";
        }
        if (wentBackwards) {
            // Not #271, and worth its own sentence: a counter that can decrease lets a window
            // match a value it has already drawn and sit on a stale panel with nothing to say so.
            return "the read reported LESS progress at a later rebuild than at an earlier one,"
                    + " so this is not a progress bar";
        }
        return null;
    }

    /** For the log: which window this is, without leaning on a private class's name. */
    private static String describeScreen(GuiScreen screen) {
        return screen == null ? "no screen" : screen.toString();
    }

    private static String percent(float progress) {
        return progress < 0.0f ? "no read" : (int) (progress * 100.0f) + "%";
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
