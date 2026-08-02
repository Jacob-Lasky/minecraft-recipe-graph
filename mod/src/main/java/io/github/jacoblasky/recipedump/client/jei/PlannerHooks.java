package io.github.jacoblasky.recipedump.client.jei;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;

/**
 * The two seams between the JEI wiring and the planner screen, in one place.
 *
 * WHY THEY ARE SETTABLE STATICS, which is not a shape to reach for lightly. Both are
 * genuinely one-per-client, and both are consulted by code JEI or Forge constructs long
 * before any screen exists: JEI builds its gui handler at plugin-registration time and keeps
 * it forever, and the keybind handler is a static event subscriber. Threading a reference
 * into either is not something those APIs allow.
 *
 * ONE CLASS RATHER THAN A SETTER ON EACH, so that "what does the planner have to wire up" has
 * a single answer a reader can find. Both default to doing nothing, and doing nothing is a
 * correct and shippable state: JEI lays out exactly as it does today, and the keybind
 * resolves a target and discards it.
 *
 * NEITHER DEFAULT PRETENDS. An unwired area source reports no rectangles rather than a
 * guessed one, and an unwired target listener does not open a window it has no plan for --
 * the alternative in both cases is a feature that looks connected and is not.
 */
public final class PlannerHooks {

    /** Where the planner is on screen right now, so JEI can lay out around it. */
    public interface AreaSource {
        /** Screen-pixel rectangles, empty when the planner is closed. Never null. */
        List<Rectangle> occupiedAreas();
    }

    /** What to do with an item the player asked to plan. */
    public interface TargetListener {
        /**
         * @param stack the item under the mouse when the keybind fired, never null or empty
         * @return true when the target was accepted, false to leave the player where they are
         */
        boolean onPlanTarget(ItemStack stack);
    }

    private static final AreaSource NOTHING_OPEN = new AreaSource() {
        @Override
        public List<Rectangle> occupiedAreas() {
            return Collections.emptyList();
        }
    };

    private static final TargetListener NOBODY_LISTENING = new TargetListener() {
        @Override
        public boolean onPlanTarget(ItemStack stack) {
            return false;
        }
    };

    private static AreaSource areas = NOTHING_OPEN;
    private static TargetListener listener = NOBODY_LISTENING;

    private PlannerHooks() {
    }

    /** Null resets to the default rather than installing a null, which would NPE per frame. */
    public static void setAreaSource(AreaSource source) {
        areas = source == null ? NOTHING_OPEN : source;
    }

    public static void setTargetListener(TargetListener target) {
        listener = target == null ? NOBODY_LISTENING : target;
    }

    /** True when something is listening, so a menu can grey the entry rather than lie. */
    public static boolean hasTargetListener() {
        return listener != NOBODY_LISTENING;
    }

    static List<Rectangle> occupiedAreas() {
        return areas.occupiedAreas();
    }

    static boolean deliver(ItemStack stack) {
        return listener.onPlanTarget(stack);
    }
}
