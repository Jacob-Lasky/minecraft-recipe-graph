package io.github.jacoblasky.recipedump.shot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The screens `-Dmcrecipedump.shot=<name>[:arg]` can open, by name.
 *
 * ONE `register` CALL PER SCREEN AND NOTHING ELSE. Every GUI #19 adds gets a line here so a
 * PR can attach a picture of it, so the cost of adding one has to stay at one line -- the
 * moment this needs a switch, a cast or a per-screen argument type, people stop adding
 * screens and the harness stops being used.
 *
 * An {@link Opener} rather than a `GuiScreen` factory, deliberately: a ModularUI screen is
 * NOT a `GuiScreen` and is opened through `ClientGUI.open(ModularScreen)`, which wraps it.
 * Returning a `GuiScreen` would force every ModularUI entry to build the wrapper by hand and
 * would tie this registry to which UI toolkit each screen happens to use.
 *
 * The registry does NOT touch any screen class at class-init time. Entries are anonymous
 * classes, so `HarnessFixtureScreen` -- and through it ModularUI -- is loaded only when its
 * name is actually asked for. That matters because the shipped jar declares no ModularUI
 * dependency: a client without ModularUI must be able to load this class, and it can, as
 * long as nothing forces the screen class.
 */
public final class ShotScreens {

    /** Opens one screen. `arg` is whatever followed the first `:` in the spec, or null. */
    public interface Opener {
        void open(String arg);
    }

    /**
     * A screen that can be DRIVEN, so the harness can time it doing something rather than
     * time it sitting still.
     *
     * The 60 fps gate on #19 phase 3b is about PANNING a 4,000 node plan, and a static
     * screenshot of one measures nothing: the frame cost that matters is the one paid while
     * the viewport is moving and the culler is re-deciding what is on screen. A screen
     * implementing this gets `step` called once per timed frame and is expected to move
     * itself a little.
     *
     * Registered by the opener through {@link #animate}, rather than found by casting
     * `Minecraft.currentScreen`, because a ModularUI screen is wrapped in a
     * `GuiScreenWrapper` and the thing on the screen field is not the thing that knows how
     * to pan.
     */
    public interface Animated {
        /** @param frame 0-based index of the timed frame about to be drawn. */
        void step(int frame);
    }

    /** Set by the current run's opener; null when the screen cannot be driven. */
    private static Animated animated;

    /** See {@link #requestSettleFrames}. */
    private static int settleRequest;

    /**
     * See {@link #expectReport}. Non-null means a verdict is still owed.
     *
     * VOLATILE BECAUSE THESE TWO CROSS THREADS AND THE OTHER FIELDS HERE DO NOT. `animated`
     * and `settleRequest` are written by an opener on the client tick and read by the harness
     * on the client thread, so they need nothing. A verdict is different: `Ae2ProbeShot` walks
     * an AE2 grid on the SERVER thread -- it has to, a grid exists nowhere else -- and reports
     * from there, while {@link ShotHarness} reads it on the client thread on the way out.
     * Without the barrier the client is entitled to see the stale null and fail a run that did
     * report, which is precisely the flake this machinery exists to make impossible.
     */
    private static volatile String pendingReport;

    /**
     * See {@link #reportFail}. Non-null means a verdict arrived and it was NO.
     *
     * A SECOND FIELD RATHER THAN A SENTINEL IN {@link #pendingReport}, because "said nothing"
     * and "said no" are different outcomes and the whole point of this machinery is that they
     * must not collapse into each other. One nullable field can carry one of those two facts.
     * Volatile for the same reason as {@link #pendingReport}.
     */
    private static volatile String failedVerdict;

    /**
     * Offer the harness something to drive. Call from inside an {@link Opener}.
     *
     * CLEARED BY {@link #open} BEFORE EVERY OPEN, so a screen that does not register one
     * cannot inherit the previous screen's. One process opens one screen today, but a
     * leftover here would make a static screen report the pan timings of another.
     */
    public static void animate(Animated screen) {
        animated = screen;
    }

    /** What the last opened screen registered, or null. */
    public static Animated animated() {
        return animated;
    }

    /**
     * Frames this screen needs before the capture, if more than the harness default.
     *
     * A SCREEN THAT NEEDS TIME MUST BE ABLE TO SAY SO. `ae2-probe` waits twenty SERVER ticks
     * for AE2 to build and connect its grid, which is about a second, and the default settle
     * of twenty RENDER frames is under a second on this rasteriser -- so the capture and the
     * exit happened before the probe reached its verdict, and the log simply had no verdict
     * line in it. That reads as "the probe did not run", which is indistinguishable from "the
     * probe found nothing", and the fix was an incantation on the command line that the next
     * person would not know to type.
     *
     * So the screen declares it and the harness takes the larger of the two. An opt-in flag
     * the caller has to remember is a footgun; a requirement the screen states is a contract.
     */
    public static void requestSettleFrames(int frames) {
        settleRequest = Math.max(settleRequest, frames);
    }

    /** {@link #requestSettleFrames}, or 0 if the screen asked for nothing. */
    public static int settleRequest() {
        return settleRequest;
    }

    /**
     * Declare that this screen owes a verdict before the run may be called successful.
     *
     * BECAUSE A PROBE THAT SAYS NOTHING LOOKS EXACTLY LIKE A PROBE THAT WAS NEVER RUN. On
     * 2026-08-03 the AE2 probe produced a full verdict on three runs and, on a fourth with no
     * relevant change, produced its "placed" line and then nothing at all -- and the log of
     * that run is indistinguishable from a build where the screen had been deleted. I could
     * not reproduce it, which is precisely why silence must not be an available outcome: a
     * flake that reports nothing gets read as a clean run by whoever greps for a failure.
     *
     * So the screen says up front that a verdict is owed, and {@link ShotHarness} treats a
     * capture with a verdict still outstanding as a failed run rather than a successful one.
     *
     * THE DEBT IS CLEARED BY {@link #reportPass} OR {@link #reportFail} AND BY NOTHING ELSE.
     * DO NOT add a bare `reported()` that clears it without saying which way the verdict went.
     * The first version of this had one, and `Ae2ProbeShot` called it on all five of its
     * FAILURE paths and not on its success path -- so a passing run would have turned EXIT_OK
     * into a failure and every run that stopped at step 1 would have exited 0. A guard written
     * to stop a silent success from passing had been wired to invert the signal, and would have
     * lied in both directions at once.
     *
     * READ OFF THE CODE, NOT OFF A LOG, and worth saying which: the guard was written after the
     * last recorded probe run, so no run ever executed it and no log shows the inversion. That
     * is the good version of this outcome and it is also why the mapping now has unit tests --
     * the live runs on this branch each exercise exactly one direction. A verdict-shaped API
     * cannot be miswired this way, because there is no call that means only "I spoke".
     */
    public static void expectReport(String what) {
        pendingReport = what;
    }

    /**
     * The screen's criteria all held. Clears the debt declared by {@link #expectReport}.
     *
     * DELIBERATELY DOES NOT UNDO A {@link #reportFail}, so a screen that reports a failure and
     * then a pass still fails the run. A screen doing both has a defect in it, and of the two
     * ways to resolve the contradiction, failing closed costs a re-run while passing closed
     * publishes a green result over a criterion that did not hold.
     */
    public static void reportPass() {
        pendingReport = null;
    }

    /**
     * The screen reached its verdict and the verdict is NO. Clears the debt and fails the run.
     *
     * `why` reaches the harness log and nothing else, so write it for whoever is reading the
     * exit code six weeks from now: name the criterion that did not hold, not the fact that
     * one did not.
     */
    public static void reportFail(String why) {
        pendingReport = null;
        failedVerdict = why == null || why.trim().isEmpty() ? "no reason given" : why.trim();
    }

    /** What the screen still owes, or null. */
    public static String pendingReport() {
        return pendingReport;
    }

    /** Why the screen's verdict was NO, or null if it did not fail. */
    public static String failedVerdict() {
        return failedVerdict;
    }

    private static final Map<String, Opener> SCREENS = new LinkedHashMap<String, Opener>();

    static {
        register("ae2-probe", new Opener() {
            @Override
            public void open(String arg) {
                Ae2ProbeShot.open(arg);
            }
        });
        register("world-probe", new Opener() {
            @Override
            public void open(String arg) {
                WorldProbeShot.open(arg);
            }
        });
        register("fixture", new Opener() {
            @Override
            public void open(String arg) {
                HarnessFixtureScreen.open();
            }
        });
        register("planner", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openTree(arg);
            }
        });
        // The only screen that SOLVES rather than reading a fixture. See LivePlanShot for
        // why both exist: fixtures prove the drawing, this proves the plumbing.
        register("planner-live", new Opener() {
            @Override
            public void open(String arg) {
                LivePlanShot.open(arg);
            }
        });
        register("planner-menu", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openMenu(arg);
            }
        });
        register("planner-recipes", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openRecipePicker(arg);
            }
        });
        register("flow-hit", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openFlowHit(arg);
            }
        });
        register("flow", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openFlow(arg);
            }
        });
        register("planner-todo", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openTodo(arg);
            }
        });
        register("jei", new Opener() {
            @Override
            public void open(String arg) {
                JeiRecipeShot.open(arg);
            }
        });
    }

    private ShotScreens() {
    }

    public static void register(String name, Opener opener) {
        SCREENS.put(name, opener);
    }

    /** Registered names, in registration order, for the "no such screen" message. */
    public static List<String> names() {
        return new ArrayList<String>(SCREENS.keySet());
    }

    /**
     * Open the screen named by `spec`, which is `name` or `name:arg`.
     *
     * @return null on success, or a human-readable reason the screen could not be opened.
     *         A STRING RATHER THAN AN EXCEPTION because every caller of this ends up writing
     *         the reason into the log and exiting non-zero, and a stack trace through an
     *         anonymous class says less than the name that was not found.
     */
    public static String open(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return "empty screen spec";
        }
        String name = spec.trim();
        String arg = null;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            arg = name.substring(colon + 1);
            name = name.substring(0, colon);
        }
        Opener opener = SCREENS.get(name);
        if (opener == null) {
            return "no screen named '" + name + "'; known screens: " + names();
        }
        animated = null;
        settleRequest = 0;
        pendingReport = null;
        failedVerdict = null;
        try {
            opener.open(arg);
        } catch (Throwable t) {
            // Catches NoClassDefFoundError as well as a genuine failure inside the screen.
            // A missing ModularUI is the expected shape of the former and is worth naming,
            // because it is a mistake in the DEV MOD SET rather than in the screen.
            return "opening '" + name + "' threw " + t;
        }
        return null;
    }
}
