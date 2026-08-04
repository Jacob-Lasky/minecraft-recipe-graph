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

    private static final Map<String, Opener> SCREENS = new LinkedHashMap<String, Opener>();

    static {
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
