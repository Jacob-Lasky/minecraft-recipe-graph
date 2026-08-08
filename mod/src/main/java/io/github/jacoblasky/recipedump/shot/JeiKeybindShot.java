package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.client.ScreenCursor;
import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.PlanTargetKeybind;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.item.ItemStack;

/**
 * The whole gesture, driven with nobody at the keyboard: hover an item in JEI, press the plan
 * key, get a plan for THAT item.
 *
 * WHAT THE OTHER JEI SCREEN CANNOT SHOW. `jei` asks {@link JeiBridge} to open a recipe page
 * and photographs it, which is evidence that the runtime was captured and a focus was created.
 * Its own header says what it leaves out: "nothing here moves a mouse, so
 * `getIngredientUnderMouse` and the keybind are not exercised". That was the untested half,
 * and it is the half the feature is FOR -- and it was broken in two independent ways that no
 * unit test could see, because each lived between two components that were each tested alone.
 * `PlannerHooks.setTargetListener` was never called by the shipped mod, so every press was
 * handed to a listener that does nothing (#191); and the keybind subscribed only to
 * `InputEvent.KeyInputEvent`, which cannot fire while a `GuiScreen` is open, which is every
 * frame on which JEI has an overlay to read.
 *
 * SO THIS SCREEN ASSERTS RATHER THAN PHOTOGRAPHS, and owes a verdict. A picture of a plan
 * cannot distinguish "the key planned the item under the cursor" from "the planner opened on
 * whatever happened to be first in the TODO list" -- and that second one is a live branch,
 * not a hypothetical: `PlannerEntry.startPlan` still targets the book's first TODO when
 * nobody names one, and it produces an entirely convincing screenshot. The verdict compares
 * the key of the stack that was under the cursor against the key the planner went on to
 * solve, and fails when they differ. DO NOT convert this to a photograph.
 *
 * IT NEEDS A WORLD, and the reason is the seam under test rather than the rendering. The plan
 * book is a capability on the PLAYER, so with no player there is no book and `PlanTarget`
 * declines for a reason that has nothing to do with JEI. A world also gives the realistic
 * surface: JEI draws its item list beside an open inventory, which is where a player makes
 * this gesture.
 *
 * IT FINDS THE SLOT BY SWEEPING RATHER THAN BY COMPUTING IT. JEI publishes no geometry --
 * `IIngredientListOverlay` offers `getIngredientUnderMouse` and `getVisibleIngredients` and
 * nothing about where anything is -- and a hard-coded position would bake this pack's screen
 * size, GUI scale and JEI layout config into the probe. So the cursor walks a coarse grid over
 * the right-hand side and stops at the first thing JEI reports under it. A sweep that finds
 * nothing is a failed verdict, never a quiet pass.
 */
final class JeiKeybindShot {

    /**
     * What to type into JEI's search box first. `jei-keybind:<filter>` overrides it.
     *
     * NARROWING THE LIST IS WHAT MAKES THE RUN REPRODUCIBLE. Unfiltered, the first slot holds
     * whatever this pack's 35,000-item list happens to sort first, which changes with the mod
     * set and makes two runs incomparable. The mod filter picks a vanilla item so the same
     * probe means the same thing on the ten-mod dev set and on the whole pack.
     */
    private static final String DEFAULT_FILTER = "@minecraft hopper";

    /** How far in from the left the sweep starts, as a fraction of the width. */
    private static final double SWEEP_FROM = 0.66;

    /** Gap between sample points, in GUI pixels. Below a slot, which is 18. */
    private static final int STEP = 8;

    /** Rows to leave alone at the top and bottom, where JEI puts its buttons. */
    private static final int MARGIN = 24;

    /**
     * Polls to allow between an accepted press and the solve starting.
     *
     * BECAUSE ON MASTER THE PRESS DOES NOT START THE SOLVE. `PlanTarget` hands the key to
     * `PlannerEntry.openOn`, which goes through `PlannerStock.planWhenRead` -- and in a world
     * with a server to ask, that holds the plan until the ME stock reply lands, which is a
     * round trip and several ticks (#203/#204 wired it this way; #212, which this probe was
     * written against, planned on the client thread inside the press). A probe that read the
     * verdict on the next poll would read the PREVIOUS answer, which at the start of a run is
     * the empty target -- a fail with a misleading reason. So it waits for the service to say
     * something new, and a wait that never ends is its own failure rather than the run's
     * timeout.
     *
     * Two hundred is about three seconds of render ticks: far longer than a local round trip
     * and far shorter than the run timeout, so hitting it means the reply never came.
     */
    private static final int UPTAKE_POLLS = 200;

    private JeiKeybindShot() {
    }

    static void open(String arg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            throw new IllegalStateException("jei-keybind needs a world: run it with "
                    + "-D" + ShotHarness.PROP_WORLD + "=<name>");
        }
        if (!JeiBridge.isAvailable()) {
            throw new IllegalStateException("JEI runtime not available; nothing to hover");
        }
        String filter = arg == null || arg.trim().isEmpty() ? DEFAULT_FILTER : arg.trim();
        // The realistic surface. JEI draws its item list beside any `GuiContainer`, and the
        // player's own inventory is the one that needs no block placed to open it. Opened
        // BEFORE the filter, because JEI builds the overlay when the screen appears.
        mc.displayGuiScreen(new GuiInventory(mc.player));
        if (!JeiBridge.filterTo(filter)) {
            throw new IllegalStateException("JEI would not take the filter '" + filter + "'");
        }
        ShotHarness.log("jei-keybind: filtered JEI to '" + filter + "'");
        ShotScreens.expectReport("jei-keybind");
        ShotScreens.holdCapture(new Sweep());
    }

    /**
     * Walks the cursor until JEI reports something under it, presses the key, checks the plan.
     *
     * A HOLD RATHER THAN AN `Animated`, because this is not a measurement. `Animated` exists
     * for the 60 fps gate and runs a fixed number of TIMED frames whose durations are the
     * artifact; this needs an unknown number of frames and cares only that they happen. The
     * harness polls a hold once per render tick with the run's own timeout as the backstop,
     * which is exactly the shape of "keep going until you find one".
     *
     * READ FIRST, THEN MOVE. JEI decides what is under the cursor while it DRAWS, so the
     * answer available on any given poll belongs to the position set on the PREVIOUS one.
     * Reading after moving would report the previous position's hit against the new
     * coordinates, which is the off-by-one-frame error `flow-hit` already paid for once: six
     * tidy lines that were all one row out of step. DO NOT reorder {@link #press} after the
     * {@link ScreenCursor#park} below.
     */
    private static final class Sweep implements ShotScreens.Hold {

        private final int[] xs;

        private final int[] ys;

        /** Index of the point the cursor is currently parked on; -1 before the first move. */
        private int at = -1;

        /** The key of the stack the press was made on, once a press has happened. */
        private String pressedKey;

        /**
         * What {@link PlannerService#generation} read at press time.
         *
         * See {@link JeiKeybindShot#UPTAKE_POLLS} for why the press alone is not the signal.
         */
        private long generationAtPress;

        /** Polls spent waiting for the press to reach the planner. */
        private int uptakePolls;

        /** Set once no further poll should do anything. */
        private boolean done;

        Sweep() {
            int width = ScreenCursor.guiWidth();
            int height = ScreenCursor.guiHeight();
            this.xs = axis((int) (width * SWEEP_FROM), width - 2);
            this.ys = axis(MARGIN, height - MARGIN);
            ShotHarness.log("jei-keybind: sweeping " + xs.length + "x" + ys.length
                    + " points over " + width + "x" + height + " gui px");
        }

        private static int[] axis(int from, int to) {
            int count = Math.max(1, (to - from) / STEP + 1);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                out[i] = from + i * STEP;
            }
            return out;
        }

        @Override
        public boolean busy() {
            if (done) {
                return false;
            }
            if (pressedKey != null) {
                return waitingForPlan();
            }
            if (at >= 0 && press()) {
                return pressedKey != null && waitingForPlan();
            }
            at++;
            if (at >= xs.length * ys.length) {
                ShotScreens.reportFail("swept " + (xs.length * ys.length)
                        + " points and JEI reported no item under any of them");
                done = true;
                return false;
            }
            // Column-major, so consecutive points share a column and the sweep crosses the
            // slot grid the short way. Row-major over a 1280px screen spends most of its
            // samples in the empty gap left of the list before it ever reaches a slot.
            ScreenCursor.park(xs[at / ys.length], ys[at % ys.length]);
            return true;
        }

        /**
         * Try the gesture where the cursor is. True once something was there, hit or miss.
         *
         * THROUGH `PlanTargetKeybind.onPressed` RATHER THAN `PlanTarget`, deliberately.
         * Calling the listener directly would exercise the half that was already wired and
         * skip the half that was not, which is how both of this feature's real defects stayed
         * invisible. The probe reads the ingredient the same way the key does, so a
         * regression in either the JEI read or the handover fails it.
         *
         * IT KEYS THE STACK WITH `DumpCommand.stackKey` AND NOTHING ELSE, because that is the
         * one place the key format exists and it is the same function `PlanTarget` uses on
         * the other side of the handover -- see `PlanTarget`'s own "DO NOT key a stack any
         * other way here". A probe that keyed it a second way would fail runs over a
         * disagreement between two spellings rather than over the gesture. What it does NOT
         * share with `PlanTarget` is WHICH STACK, and that is the whole assertion.
         */
        private boolean press() {
            JeiBridge.Hovered found = JeiBridge.hovered();
            if (found == null || !(found.ingredient instanceof ItemStack)) {
                return false;
            }
            ItemStack stack = (ItemStack) found.ingredient;
            if (stack.isEmpty()) {
                return false;
            }
            String key = DumpCommand.stackKey(stack);
            ShotHarness.log("jei-keybind: JEI's " + found.surface + " overlay has "
                    + stack.getItem().getRegistryName() + "@" + stack.getMetadata()
                    + " at gui " + ScreenCursor.guiX() + "," + ScreenCursor.guiY()
                    + "; the graph calls it " + key);
            // BOTH CHECKS BEFORE THE PRESS, so the reason names the probe's own aim rather
            // than the wiring. `PlannerService.plan` refuses a key the graph does not hold and
            // `PlannerEntry` drops the plan entirely while the graph is still loading; either
            // refusal reaching the verdict below would read as "the gesture is broken" when
            // what happened is that the probe asked at the wrong moment or landed on an item
            // this pack cannot make. THEY ARE TWO MESSAGES AND NOT ONE, because "wait longer"
            // and "the sweep found the wrong thing" are different things to go and do.
            RecipeGraph graph = GraphService.get().graph();
            if (graph == null) {
                ShotScreens.reportFail("the cursor found " + key + " and there is no graph to"
                        + " plan against yet: " + GraphService.get().describe());
                done = true;
                return true;
            }
            if (key == null || key.isEmpty() || graph.keyId(key) < 0) {
                ShotScreens.reportFail("the cursor found " + key + ", which is not in the"
                        + " graph, so there is no plan to compare: "
                        + GraphService.get().describe());
                done = true;
                return true;
            }
            PlannerService planner = PlannerService.get();
            generationAtPress = planner.generation();
            boolean delivered = PlanTargetKeybind.onPressed();
            if (!delivered) {
                ShotScreens.reportFail("an item was under the cursor and pressing the plan key"
                        + " delivered nothing -- graph is " + GraphService.get().describe()
                        + ", planner is " + planner.describe());
                done = true;
                return true;
            }
            pressedKey = key;
            return true;
        }

        /**
         * Hold while the solve is taken up and runs, then check it answered the RIGHT
         * question.
         *
         * TWO WAITS AND NOT ONE. The first is for the press to REACH the planner, which on
         * master is not immediate -- see {@link JeiKeybindShot#UPTAKE_POLLS}. The second is
         * for the solve to finish, because it runs off the client thread, so at the moment
         * the key is handled `targetKey` still holds the previous answer or nothing at all.
         * Holding for both is also what gets a drawn plan into the screenshot rather than a
         * "planning..." panel.
         */
        private boolean waitingForPlan() {
            PlannerService planner = PlannerService.get();
            if (planner.generation() == generationAtPress) {
                uptakePolls++;
                if (uptakePolls < UPTAKE_POLLS) {
                    return true;
                }
                done = true;
                ShotScreens.reportFail("the plan key was accepted for " + pressedKey
                        + " and after " + UPTAKE_POLLS + " polls the planner has still not"
                        + " been asked anything: " + planner.describe()
                        + "; graph is " + GraphService.get().describe());
                return false;
            }
            if (planner.state() == PlannerService.State.PLANNING) {
                return true;
            }
            done = true;
            String planned = planner.targetKey();
            if (planned == null || planned.isEmpty()) {
                // EMPTY AND NOT NULL is the shape master's `PlannerService` has: `targetKey`
                // starts as "" and `plan` leaves it alone when it refuses, so a refused solve
                // arrives here as an empty string. Testing only for null would compare "" to
                // the pressed key and report a mismatch, which names the wrong culprit.
                ShotScreens.reportFail("the key was delivered and the planner has no target: "
                        + planner.describe());
            } else if (!pressedKey.equals(planned)) {
                ShotScreens.reportFail("the cursor was on " + pressedKey
                        + " and the planner solved " + planned);
            } else {
                ShotHarness.log("jei-keybind: the planner solved " + planned
                        + " -- " + planner.describe());
                ShotScreens.reportPass();
            }
            return false;
        }
    }
}
