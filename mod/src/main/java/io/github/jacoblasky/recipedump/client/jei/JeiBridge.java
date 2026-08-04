package io.github.jacoblasky.recipedump.client.jei;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.DumpPlugin;
import io.github.jacoblasky.recipedump.client.StackIndex;
import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.util.Collection;
import java.util.Collections;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import net.minecraft.item.ItemStack;

/**
 * Everything the planner asks of JEI, behind one null-safe surface.
 *
 * EVERY METHOD HERE ANSWERS RATHER THAN THROWS when JEI is absent, and that is a
 * requirement rather than politeness. Since #19 Phase 2 the mod declares `after:jei` instead
 * of `required-after`, so it loads on a client with no JEI at all and on a dedicated server
 * where JEI does not exist -- and `DumpPlugin.runtime` stays null forever in both cases.
 * `DumpCommand` already models the pattern by SAYING "JEI runtime not available yet" instead
 * of failing.
 *
 * NOTHING OUTSIDE THIS PACKAGE SHOULD TOUCH `DumpPlugin.runtime`. One null check in one place
 * is checkable; the same check scattered across a keybind, a menu item and a screen is three
 * chances to forget it, and the one that forgets throws inside a render call.
 *
 * A caller wanting to grey a menu entry should ask {@link #isAvailable}; a caller acting on a
 * click should ignore the boolean return and let false mean "nothing happened", because by
 * then the user has already clicked and an error dialog helps nobody.
 */
public final class JeiBridge {

    /**
     * A graph and the index built FOR it, as ONE object behind ONE volatile field.
     *
     * Held statically because there is exactly one graph and exactly one JEI item list in a
     * client, and threading it through a keybind handler and a widget callback would be
     * ceremony around a singleton that genuinely is one.
     *
     * DO NOT SPLIT THESE BACK INTO TWO FIELDS. They were two, and that was safe only while
     * every writer and reader was the client thread. It stopped being true when
     * `GraphService` started building the index from its LOADER thread, so the item-list walk
     * stays off the render path: with two non-volatile fields there is no ordering between
     * them, so the client thread can observe the NEW graph reference beside the OLD index and
     * serve the previous graph's stacks for this one. Every key would resolve, TO THE WRONG
     * ITEM -- a plausible icon and a plausible recipe screen rather than a visible failure,
     * per-session and unreproducible, and unreachable by any single-threaded test. One
     * immutable pair behind one volatile reference makes the mismatch unrepresentable rather
     * than unlikely.
     */
    private static final class Indexed {

        final RecipeGraph graph;
        final StackIndex index;

        Indexed(RecipeGraph graph, StackIndex index) {
            this.graph = graph;
            this.index = index;
        }
    }

    private static final Indexed NOTHING = new Indexed(null, StackIndex.empty());

    private static volatile Indexed current = NOTHING;

    private JeiBridge() {
    }

    /** True when a JEI runtime exists. False on a client without JEI, permanently. */
    public static boolean isAvailable() {
        return DumpPlugin.runtime != null;
    }

    /**
     * Builds the key-to-stack index for `graph` from JEI's own item list.
     *
     * THE SAME POPULATION THE DUMP WALKS -- `getAllIngredients(VanillaTypes.ITEM)` -- which
     * is what makes the keys line up. Walking anything else, the item registry for one, would
     * key stacks JEI never showed and miss the runtime-added ones it did.
     *
     * Safe to call with no JEI: the index comes out empty and every lookup answers null,
     * which is the same answer a key with no item behind it gives anyway.
     */
    public static void indexFor(RecipeGraph graph) {
        indexFor(graph, allItemStacks());
    }

    /**
     * The same, from a population handed in rather than asked of JEI.
     *
     * THE SEAM {@link StackIndex} ALREADY DOCUMENTS, one level up. That class takes its stacks
     * as an argument "so it can be unit-tested against hand-built ones with no runtime --
     * which is the only way any of Phase 4 gets tested at all", and then this class went and
     * put the untestable call back in front of it. Without this overload, every path that
     * needs a NON-EMPTY index -- which is every interesting answer {@link JeiNodeActions}
     * gives -- is reachable only from a live client, so the whole true branch of the node menu
     * would ship asserted by nothing. The alternative was a 21-method fake
     * `IIngredientRegistry`, which tests the fake.
     */
    public static void indexFor(RecipeGraph graph, Collection<ItemStack> stacks) {
        current = graph == null ? NOTHING
                : new Indexed(graph, StackIndex.build(graph, stacks));
    }

    /**
     * The index built for `graph`, building it first if this is a different graph.
     *
     * The lazy rebuild is a FALLBACK, not the main path: `GraphService` builds the index
     * eagerly on its loader thread as soon as a graph is ready, so the first menu to open
     * pays nothing. This covers the orders that wiring cannot -- a test, the screenshot
     * harness, or a graph that arrived without anyone announcing it.
     *
     * Two threads racing here both build a correct index for their own graph and the later
     * write wins, so the cost of the race is duplicated work rather than a wrong answer.
     * Locking to save that would put a lock on a path the render thread walks.
     */
    public static StackIndex indexOf(RecipeGraph graph) {
        Indexed held = current;
        if (held.graph != graph) {
            indexFor(graph);
            held = current;
        }
        return held.index;
    }

    private static Collection<ItemStack> allItemStacks() {
        if (DumpPlugin.ingredients == null) {
            return Collections.emptyList();
        }
        try {
            return DumpPlugin.ingredients.getAllIngredients(VanillaTypes.ITEM);
        } catch (Throwable failed) {
            // Matching how the dump guards the same call: a mod whose ingredient helper
            // throws must cost the planner its icons, not its startup.
            return Collections.emptyList();
        }
    }

    /** The stack a graph key names, or null. */
    public static ItemStack stackFor(int keyId, RecipeGraph graph) {
        return indexOf(graph).stackFor(keyId);
    }

    /**
     * Opens JEI's recipe screen showing what MAKES this key. False when it could not.
     *
     * `Mode.OUTPUT`, because a node in a plan is a thing you need and the question is how to
     * get it. The input direction -- "what can I do with this" -- is a different question and
     * would want its own action rather than a flag on this one.
     */
    public static boolean showRecipesFor(int keyId, RecipeGraph graph) {
        ItemStack stack = stackFor(keyId, graph);
        return stack != null && showRecipesFor(stack);
    }

    public static boolean showRecipesFor(ItemStack stack) {
        return show(stack, IFocus.Mode.OUTPUT);
    }

    /**
     * Opens JEI showing what this key is USED IN. False when it could not.
     *
     * The other direction, and a genuinely different question from
     * {@link #showRecipesFor}: "how do I get this" against "what can I do with this". Two
     * actions rather than one with a flag, because a menu has to name them separately anyway
     * and a boolean argument at the call site reads as neither.
     */
    public static boolean showUsesOf(int keyId, RecipeGraph graph) {
        ItemStack stack = stackFor(keyId, graph);
        return stack != null && showUsesOf(stack);
    }

    public static boolean showUsesOf(ItemStack stack) {
        return show(stack, IFocus.Mode.INPUT);
    }

    private static boolean show(ItemStack stack, IFocus.Mode mode) {
        IJeiRuntime runtime = DumpPlugin.runtime;
        if (runtime == null || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            IFocus<ItemStack> focus = runtime.getRecipeRegistry().createFocus(mode, stack);
            runtime.getRecipesGui().show(focus);
            return true;
        } catch (Throwable failed) {
            // A category whose wrapper throws while building its layout would otherwise take
            // the whole GUI down from inside a click handler.
            return false;
        }
    }

    /** An ingredient and WHICH of JEI's surfaces it came from. See {@link #hovered}. */
    public static final class Hovered {

        /** `recipes`, `list` or `bookmarks`. Never null. */
        public final String surface;

        /** JEI's raw ingredient. Never null. */
        public final Object ingredient;

        Hovered(String surface, Object ingredient) {
            this.surface = surface;
            this.ingredient = ingredient;
        }
    }

    /**
     * Whatever ingredient the mouse is over, and where it came from, or null.
     *
     * THE ORDER IS DELIBERATE and it is "most specific context first". The recipes GUI is
     * checked before the item list because when a recipe screen is open it is drawn OVER the
     * list, so the thing under the cursor is the one in the recipe. Bookmarks last, because
     * the bookmark overlay only holds what the player put there and is the least likely of
     * the three to be what they meant.
     *
     * WHICH SURFACE ANSWERED IS PART OF THE RESULT RATHER THAN A SECOND CALL, because the
     * mouse moves between calls and a caller that asked twice could be told the ingredient
     * came from a surface that is no longer the one under the cursor. It matters to the
     * screenshot harness, which has to say what it proved: "the item list answered" and "a
     * recipe slot answered" are different claims about the gesture, and a log that says only
     * "found an ingredient" cannot distinguish the one a player makes from the one that
     * happened to be reachable.
     */
    public static Hovered hovered() {
        IJeiRuntime runtime = DumpPlugin.runtime;
        if (runtime == null) {
            return null;
        }
        try {
            Object found = runtime.getRecipesGui().getIngredientUnderMouse();
            if (found != null) {
                return new Hovered("recipes", found);
            }
            found = runtime.getIngredientListOverlay().getIngredientUnderMouse();
            if (found != null) {
                return new Hovered("list", found);
            }
            found = runtime.getBookmarkOverlay().getIngredientUnderMouse();
            return found == null ? null : new Hovered("bookmarks", found);
        } catch (Throwable failed) {
            return null;
        }
    }

    /**
     * Type into JEI's search box. False when there is no JEI to type into.
     *
     * FOR THE SCREENSHOT HARNESS AND NOTHING ELSE SO FAR. A probe that hovers "whatever slot
     * is first" is not reproducible across mod sets, and narrowing the list first is how JEI
     * itself expects to be pointed at an item. It is a real user-visible action -- the search
     * box keeps the text -- so it belongs behind the same null-safe surface as the rest and
     * not in a probe reaching for `DumpPlugin.runtime` directly.
     */
    public static boolean filterTo(String text) {
        IJeiRuntime runtime = DumpPlugin.runtime;
        if (runtime == null || text == null) {
            return false;
        }
        try {
            runtime.getIngredientFilter().setFilterText(text);
            return true;
        } catch (Throwable failed) {
            return false;
        }
    }

    /**
     * Whatever ingredient the mouse is over, or null. See {@link #hovered} for the order.
     *
     * Returns JEI's raw ingredient rather than an `ItemStack`: it may be a fluid or another
     * mod's ingredient type, and the caller decides whether it can plan one.
     */
    public static Object ingredientUnderMouse() {
        Hovered found = hovered();
        return found == null ? null : found.ingredient;
    }

    // THERE WAS A `keyUnderMouse(RecipeGraph)` HERE AND IT HAD NO PRODUCTION CALLER. Removed
    // 2026-08-04 with the rest of this class's Phase 4 work, because it is the same shape as the
    // `setTargetListener` defect one file over: a public method whose only exercise was a test
    // asserting it answers -1 with no runtime, sitting beside the path that really is used.
    // The live path is `hovered()` -> `ItemStack` -> `PlannerHooks.deliver`, and the router
    // resolves the key with `keyFor` on the other side of that handover, so nothing wants a
    // combined read-and-resolve. Bring it back only with a caller.


    /**
     * The graph KEY for a stack, or null. The inverse direction of {@link StackIndex}.
     *
     * BOTH FORMS BECAUSE THE TWO CONSUMERS GENUINELY DIFFER, and the id is not convertible
     * back. A node menu works in ids -- it is asking the graph questions about something the
     * graph already holds -- while a plan target is a `PlanBook` entry and a `Solver`
     * argument, both of which are keyed by string and both of which outlive the graph object
     * that resolved them. Deriving one from the other at each call site is where the
     * exact-then-base fallback below would get dropped from one of them.
     */
    public static String keyFor(ItemStack stack, RecipeGraph graph) {
        if (graph == null) {
            return null;
        }
        String key = DumpCommand.stackKey(stack);
        if (key == null) {
            return null;
        }
        if (graph.keyId(key) >= 0) {
            return key;
        }
        String base = Keys.baseKey(key);
        return graph.keyId(base) >= 0 ? base : null;
    }
}
