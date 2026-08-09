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

    /**
     * A screen that is not finished yet, so the harness must not capture or exit.
     *
     * BECAUSE A FRAME COUNT IS A GUESS AND THIS PROJECT KEEPS PAYING FOR GUESSES.
     * {@link #requestSettleFrames} answers "how long does this screen take to look right",
     * which is a rendering question with a stable answer. `dump` asks a different one: it
     * drives `/recipedump`, which takes as long as the pack takes, and the number of frames
     * that fits in is a property of how busy Tower is. Sizing a settle window to cover it
     * would produce exactly the under-wait the AE2 probe's header is about -- a run that
     * captures and exits mid-dump and reports whatever it had got to.
     *
     * So the screen is ASKED. The harness polls this once per render tick after the settle
     * window and holds the capture while it answers true, with the run's own
     * `-Dmcrecipedump.shotTimeoutSeconds` as the backstop, so a screen that never finishes
     * still fails on the clock instead of hanging.
     */
    public interface Hold {
        /** @return true while the harness must keep waiting. */
        boolean busy();
    }

    /** Set by the current run's opener; null when the screen cannot be driven. */
    private static Animated animated;

    /** See {@link Hold}. Null when the screen never asked the harness to wait. */
    private static volatile Hold hold;

    /** See {@link #expectNoScreen}. */
    private static boolean noScreenExpected;

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
     * Ask the harness to hold the capture until {@link Hold#busy} goes false.
     *
     * A HOLD IS NOT A SUBSTITUTE FOR A VERDICT. `busy()` going false means the screen has
     * stopped working, not that it succeeded, so a screen that holds must still
     * {@link #expectReport} and answer -- otherwise it buys itself all the time it needs and
     * then reports nothing, which is the same silent success the verdict machinery exists for.
     */
    public static void holdCapture(Hold screen) {
        hold = screen;
    }

    /** What the last opened screen registered, or null. */
    public static Hold hold() {
        return hold;
    }

    /**
     * Declare that this entry deliberately opens no `GuiScreen` at all.
     *
     * BECAUSE `ShotHarness` OTHERWISE TREATS AN UNCHANGED SCREEN AS A FAILED OPEN, and it is
     * right to: for the ten entries that photograph a GUI, "the opener ran and the screen did
     * not change" means the screen declined to open and the run would photograph the menu.
     *
     * `dump` is the first entry that is not a screen at all. It runs `/recipedump` through the
     * command handler, and the output goes to CHAT, which is the in-game HUD and not
     * `Minecraft.currentScreen` -- so in a world with no GUI open, `currentScreen` is null
     * before and null after, and the harness would have exited EXIT_NO_SCREEN before the dump
     * did anything. Opening a throwaway GUI to satisfy the check would be worse than saying so:
     * a panel would sit on top of the very chat that is the artifact.
     */
    public static void expectNoScreen() {
        noScreenExpected = true;
    }

    /** Whether the current entry said it opens no screen. See {@link #expectNoScreen}. */
    public static boolean noScreenExpected() {
        return noScreenExpected;
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
        // The only screen that runs a COMMAND. See DumpShot for why it goes through
        // `ClientCommandHandler` rather than calling `DumpCommand.execute`.
        register("dump", new Opener() {
            @Override
            public void open(String arg) {
                DumpShot.open(arg);
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
        // The only screen that photographs the planner RECOVERING rather than a finished
        // state. See PlannerRecoveryShot: `:loading` and `:recovered` are one artifact in two
        // runs, and the second one is a probe as well as a picture (#201).
        register("planner-recovery", new Opener() {
            @Override
            public void open(String arg) {
                PlannerRecoveryShot.open(arg);
            }
        });
        register("planner-selected", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openSelectedTree(arg);
            }
        });
        register("flow-selected", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openSelectedFlow(arg);
            }
        });
        // The shopping row's menu (#251). Registered beside the node menu because they are the
        // same KIND of artifact and differ only in which quantity they act on, which is the
        // distinction the issue exists to keep.
        register("row-menu", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openRowMenu(arg);
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
        register("planner-caveats", new Opener() {
            @Override
            public void open(String arg) {
                PlannerShot.openCaveats(arg);
            }
        });
        register("jei", new Opener() {
            @Override
            public void open(String arg) {
                JeiRecipeShot.open(arg);
            }
        });
        register("machines", new Opener() {
            @Override
            public void open(String arg) {
                MachinesShot.open(arg);
            }
        });
        register("machines-mods", new Opener() {
            @Override
            public void open(String arg) {
                MachinesShot.openModPicker(arg);
            }
        });
        register("machines-detail", new Opener() {
            @Override
            public void open(String arg) {
                MachinesShot.openDetail(arg);
            }
        });
        // The only screen that drives the WHOLE gesture: hover, press, plan. `jei` above
        // photographs a recipe page and its own header says it moves no mouse, so the half
        // the feature exists for is unexercised by it. See JeiKeybindShot for why this one
        // asserts rather than photographs (#240).
        register("jei-keybind", new Opener() {
            @Override
            public void open(String arg) {
                JeiKeybindShot.open(arg);
            }
        });
        register("sources", new Opener() {
            @Override
            public void open(String arg) {
                BrowseShot.openSources(arg);
            }
        });
        register("graph", new Opener() {
            @Override
            public void open(String arg) {
                BrowseShot.openGraph(arg);
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
        hold = null;
        noScreenExpected = false;
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
