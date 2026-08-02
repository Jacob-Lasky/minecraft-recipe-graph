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
     * The key-to-stack index, rebuilt whenever a graph is loaded.
     *
     * Held statically because there is exactly one graph and exactly one JEI item list in a
     * client, and threading it through a keybind handler and a widget callback would be
     * ceremony around a singleton that genuinely is one.
     */
    private static StackIndex index = StackIndex.empty();
    private static RecipeGraph indexed;

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
        indexed = graph;
        index = graph == null ? StackIndex.empty()
                : StackIndex.build(graph, allItemStacks());
    }

    /** The index built for `graph`, building it first if this is a different graph. */
    public static StackIndex indexOf(RecipeGraph graph) {
        if (graph != indexed) {
            indexFor(graph);
        }
        return index;
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
        IJeiRuntime runtime = DumpPlugin.runtime;
        if (runtime == null || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            IFocus<ItemStack> focus =
                    runtime.getRecipeRegistry().createFocus(IFocus.Mode.OUTPUT, stack);
            runtime.getRecipesGui().show(focus);
            return true;
        } catch (Throwable failed) {
            // A category whose wrapper throws while building its layout would otherwise take
            // the whole GUI down from inside a click handler.
            return false;
        }
    }

    /**
     * Whatever ingredient the mouse is over, from any of JEI's three surfaces, or null.
     *
     * THE ORDER IS DELIBERATE and it is "most specific context first". The recipes GUI is
     * checked before the item list because when a recipe screen is open it is drawn OVER the
     * list, so the thing under the cursor is the one in the recipe. Bookmarks last, because
     * the bookmark overlay only holds what the player put there and is the least likely of
     * the three to be what they meant.
     *
     * Returns JEI's raw ingredient rather than an `ItemStack`: it may be a fluid or another
     * mod's ingredient type, and the caller decides whether it can plan one.
     */
    public static Object ingredientUnderMouse() {
        IJeiRuntime runtime = DumpPlugin.runtime;
        if (runtime == null) {
            return null;
        }
        try {
            Object found = runtime.getRecipesGui().getIngredientUnderMouse();
            if (found == null) {
                found = runtime.getIngredientListOverlay().getIngredientUnderMouse();
            }
            if (found == null) {
                found = runtime.getBookmarkOverlay().getIngredientUnderMouse();
            }
            return found;
        } catch (Throwable failed) {
            return null;
        }
    }

    /**
     * The graph key for whatever the mouse is over, or -1.
     *
     * Keys the stack the same way the dump did, so an ingredient the player is looking at
     * lands on the same key the graph holds. A non-item ingredient -- a fluid, another mod's
     * type -- answers -1, because the planner's targets are keys and only items have one
     * here.
     */
    public static int keyUnderMouse(RecipeGraph graph) {
        Object ingredient = ingredientUnderMouse();
        return ingredient instanceof ItemStack ? keyIdFor((ItemStack) ingredient, graph) : -1;
    }

    /**
     * The graph key id for a stack, or -1. The inverse direction of {@link StackIndex}.
     *
     * SEPARATE FROM {@link #keyUnderMouse} SO IT CAN BE TESTED. Reading the mouse needs a
     * live JEI runtime and this does not, and this is where the behaviour worth asserting
     * lives -- everything above it is one instanceof.
     *
     * Falls back to the undiscriminated key when the exact one is unknown, which is the same
     * single weakening `StackIndex` makes in the other direction. It is what stops "plan
     * this" doing nothing on a stack whose enchantment list happens to differ from the one
     * the dump recorded. ONE STEP ONLY: it does not go on to collapse durability, because a
     * pristine tool and a worn one are the same PLAN target while a differently-worn one is
     * not necessarily the same thing the player pointed at.
     */
    public static int keyIdFor(ItemStack stack, RecipeGraph graph) {
        if (graph == null) {
            return -1;
        }
        String key = DumpCommand.stackKey(stack);
        if (key == null) {
            return -1;
        }
        int keyId = graph.keyId(key);
        return keyId >= 0 ? keyId : graph.keyId(Keys.baseKey(key));
    }
}
