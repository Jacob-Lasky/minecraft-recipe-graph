package io.github.jacoblasky.recipedump.client.jei;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;

/**
 * The interaction the whole of #19 exists for: point at anything in JEI, press a key, get a
 * plan for it.
 *
 * BOUND TO `=` BY DEFAULT, and the choice is not arbitrary. JEI itself uses `R` and `U` for
 * recipes and uses, and Just Enough Calculation -- the thing this is a better version of --
 * uses `=` for exactly this "send it to the calculator" gesture. A player who has used JEC
 * will try that key first.
 *
 * THE HANDLER MUST NEVER THROW. It runs inside Forge's input dispatch, so an exception here
 * takes down the frame rather than the feature, and every path it touches is a JEI call that
 * does not exist on a client without JEI. {@link JeiBridge} answers rather than throwing, and
 * this only has to honour that by treating every "no" as ordinary.
 *
 * `@Mod.EventBusSubscriber(value = Side.CLIENT)` rather than a `@SideOnly` method, matching
 * `ClientRegistration`: the annotation is what stops FML registering the subscriber on a
 * server at all, so this class is never loaded there and `Keyboard` never has to resolve.
 */
@Mod.EventBusSubscriber(modid = RecipeDumpMod.MODID, value = Side.CLIENT)
public final class PlanTargetKeybind {

    /** The category the controls screen groups this under. */
    private static final String CATEGORY = "key.categories." + RecipeDumpMod.MODID;

    private static KeyBinding planTarget;

    private PlanTargetKeybind() {
    }

    /**
     * Called from the client proxy during init.
     *
     * NOT a static initialiser: `ClientRegistry.registerKeyBinding` writes into the game
     * settings, and doing that while the class happens to load is how a keybind ends up
     * registered twice or before the settings exist.
     */
    public static void register() {
        if (planTarget != null) {
            return;
        }
        planTarget = new KeyBinding("key." + RecipeDumpMod.MODID + ".plan_target",
                                    Keyboard.KEY_EQUALS, CATEGORY);
        ClientRegistry.registerKeyBinding(planTarget);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        if (planTarget == null || !planTarget.isPressed()) {
            return;
        }
        onPressed();
    }

    /**
     * What the key does. Separated from the event so it can be driven without an input queue.
     *
     * FOUR WAYS TO DO NOTHING, and they are deliberately indistinguishable to the player: no
     * JEI, nothing under the mouse, something under the mouse that is not an item, or nobody
     * listening yet. Every one of them is an ordinary state rather than an error, and a
     * message for any of them would fire on an idle keypress.
     *
     * Returns whether a target was delivered, so the screenshot harness and the tests can
     * tell those apart without reading a log.
     */
    static boolean onPressed() {
        if (!JeiBridge.isAvailable()) {
            return false;
        }
        Object ingredient = JeiBridge.ingredientUnderMouse();
        if (!(ingredient instanceof ItemStack)) {
            return false;
        }
        ItemStack stack = (ItemStack) ingredient;
        return !stack.isEmpty() && PlannerHooks.deliver(stack);
    }
}
