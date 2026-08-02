package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Opens JEI's own recipe screen THROUGH the planner's bridge, so the wiring can be
 * photographed rather than asserted.
 *
 * WHY THIS SCREEN EXISTS. Almost nothing in #19 Phase 4 is reachable from a unit test: the
 * ingredient under the mouse, the focus, the recipes GUI and JEI's layout all need a live
 * runtime, and a stub that returns what the test wants proves only that the stub matches
 * itself. The dev client the harness boots HAS HadEnoughItems in its mod set, so the real
 * call against the real runtime is reachable headlessly -- and a PNG of a recipe screen that
 * `JeiBridge` opened is evidence of the whole chain: runtime captured, focus created, GUI
 * shown.
 *
 * WHAT IT STILL DOES NOT COVER, so nobody reads more into the picture than is there: nothing
 * here moves a mouse, so `getIngredientUnderMouse` and the keybind are not exercised. Those
 * need a hand on a keyboard and the PR says so rather than implying otherwise.
 *
 * Loaded only when its name is asked for -- see the registry's note -- so a client without
 * JEI never resolves it.
 */
public final class JeiRecipeShot {

    /**
     * A vanilla item so the shot does not depend on which mods the dev set happens to carry.
     *
     * An iron pickaxe has a plain shaped recipe AND several uses, so the screen it opens is
     * unmistakably a real JEI recipe page rather than an empty one -- which is the difference
     * between a picture that proves the wiring and a picture of a blank panel.
     */
    private static final String DEFAULT_ITEM = "minecraft:iron_pickaxe";

    private JeiRecipeShot() {
    }

    /** `arg` is a registry name, or null for the default. */
    public static void open(String arg) {
        String id = arg == null || arg.isEmpty() ? DEFAULT_ITEM : arg;
        Item item = Item.REGISTRY.getObject(new ResourceLocation(id));
        if (item == null) {
            throw new IllegalStateException("no such item: " + id);
        }
        if (!JeiBridge.isAvailable()) {
            // Loudly, because a blank screenshot would otherwise read as a layout problem
            // rather than as "JEI never handed us a runtime".
            throw new IllegalStateException("JEI runtime not available; nothing to show");
        }
        if (!JeiBridge.showRecipesFor(new ItemStack(item))) {
            throw new IllegalStateException("JEI declined to show recipes for " + id);
        }
    }
}
