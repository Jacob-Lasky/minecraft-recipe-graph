package io.github.jacoblasky.recipedump.client.jei;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiScreenEvent;
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

    /**
     * The no-GUI-open path. Present for completeness; the gesture almost never happens here.
     *
     * `InputEvent.KeyInputEvent` is the event for keys pressed with nothing on screen, and
     * with nothing on screen JEI is drawing no overlay, so there is nothing under the mouse
     * and {@link #onPressed} answers false. It stays because a key that works in one place
     * and silently does not exist in another is worse than one that does nothing when there
     * is nothing to do -- and because `Minecraft` only maintains `isPressed` on the frames
     * this event covers, so the two paths cannot share a trigger even if they wanted to.
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        if (planTarget == null || !planTarget.isPressed()) {
            return;
        }
        if (Minecraft.getMinecraft().currentScreen != null) {
            // Unreachable in practice and cheap to state: the GUI path below owns this frame,
            // and a keypress delivered twice would plan the same target twice.
            return;
        }
        onPressed();
    }

    /**
     * THE PATH THAT ACTUALLY CARRIES THE GESTURE, and the one this feature was missing.
     *
     * Pointing at something in JEI means a `GuiScreen` is open -- an inventory, a machine, a
     * recipe page -- and while one is, Minecraft routes the keyboard to that screen instead of
     * maintaining the keybind state `InputEvent.KeyInputEvent` reports. So the handler above
     * cannot fire on any frame where JEI has an overlay to read, which made the whole keybind
     * a no-op in exactly the situation it exists for.
     *
     * THE EVIDENCE IS THE PACK'S OWN JEI BUILD, not an inference about Forge:
     * `HadEnoughItems_1.12.2-4.28.1.jar` references `GuiScreenEvent$KeyboardInputEvent` from
     * `mezz/jei/input/InputHandler` and does not reference `InputEvent$KeyInputEvent` from
     * anywhere. R and U -- JEI's own over-the-item recipe and uses keys, the same gesture as
     * this one -- arrive that way, so that is the event a sibling key has to use.
     *
     * `isPressed()` IS NOT AVAILABLE HERE and using it would break this the way it broke the
     * other path. `KeyBinding`'s press counter is fed by `Minecraft`'s own key handling, which
     * is the thing being bypassed while a screen is open, so the raw LWJGL event is the only
     * honest source. `getEventKeyState` filters the release half of the same keystroke.
     *
     * `Post` rather than `Pre`, so a screen that wanted the key gets first refusal. Nothing
     * currently binds `=`, but this is a plain key on a shared keyboard and the polite order
     * costs nothing.
     */
    @SubscribeEvent
    public static void onGuiKeyInput(GuiScreenEvent.KeyboardInputEvent.Post event) {
        if (planTarget == null || !Keyboard.getEventKeyState()) {
            return;
        }
        if (Keyboard.getEventKey() != planTarget.getKeyCode()) {
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
     * tell those apart without reading a log. PUBLIC for the harness, which is in another
     * package: `jei-keybind` presses the key through THIS method rather than calling the
     * router, so that the JEI read and the handover are both inside what it proves.
     */
    public static boolean onPressed() {
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
