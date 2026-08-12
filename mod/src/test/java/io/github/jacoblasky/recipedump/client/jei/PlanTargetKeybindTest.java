package io.github.jacoblasky.recipedump.client.jei;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * WHICH EVENTS THE `=` KEY IS SUBSCRIBED TO, WHICH IS THE ONE THING NOTHING ELSE HERE CAN SEE.
 *
 * WHAT BREAKS WITHOUT THIS, and it shipped exactly this way. The keybind subscribed only to
 * `InputEvent.KeyInputEvent`, which Minecraft cannot fire while a `GuiScreen` is open -- and
 * JEI draws its ingredient list only while one IS open. So the key was a no-op on every frame
 * it exists for, and #19's MVP was unreachable end to end: naming a target is the only way to
 * get a FIRST plan, `PlannerEntry.open` otherwise falls back to the TODO book's first entry,
 * and entries reach that book only from a node menu inside a plan. Craft the calculator,
 * right-click it, get "nothing planned yet", forever.
 *
 * AND THE SUITE WAS GREEN THROUGHOUT, INCLUDING THE HARNESS BUILT FOR THIS GESTURE.
 * `JeiKeybindShot` boots a real client with real JEI, sweeps for a real slot, and asserts the
 * planner solved the key that was under the cursor -- and it enters at
 * `PlanTargetKeybind.onPressed`, which is DOWNSTREAM OF BOTH SUBSCRIPTIONS. Its own header
 * says so and gives the reason (reaching past the router would skip the handover). It proves
 * the JEI read and the handover; it cannot prove a keypress arrives. `PlanTargetTest` is
 * downstream too. Every test of this feature was a test of everything except its trigger.
 *
 * SO THIS ASSERTS THE WIRING STRUCTURALLY, which is unusual here and is the honest shape for
 * the claim. A unit test cannot stand up Forge's event bus, and the defect is not in any
 * method body -- it is in which method exists. Reflection over the declared subscriptions is
 * the smallest thing that can fail when the handler is deleted.
 *
 * IT IS NOT A SUBSTITUTE FOR THE LIVE RUN. `jei-keybind` remains the proof that the gesture
 * works against the real pack; this is the proof that the gesture can be DELIVERED, which is
 * the half that was missing and the half a green suite hid.
 */
public class PlanTargetKeybindTest {

    /** Every `@SubscribeEvent` method on the keybind, by its single event parameter. */
    private static List<Class<?>> subscribedEvents() {
        List<Class<?>> events = new ArrayList<Class<?>>();
        for (Method m : PlanTargetKeybind.class.getDeclaredMethods()) {
            if (m.getAnnotation(SubscribeEvent.class) == null) {
                continue;
            }
            assertEquals("a @SubscribeEvent method takes exactly one event: " + m.getName(),
                         1, m.getParameterTypes().length);
            events.add(m.getParameterTypes()[0]);
        }
        return events;
    }

    @Test
    public void theKeyIsDeliverableWhileAGuiIsOpen() {
        // THE REGRESSION TEST. `GuiScreenEvent.KeyboardInputEvent` is the family JEI's own R
        // and U keys arrive on -- verified against `HadEnoughItems_1.12.2-4.28.1.jar`, which
        // references it from `mezz/jei/input/InputHandler` and references
        // `InputEvent$KeyInputEvent` from nowhere. Without a subscription in this family the
        // key cannot fire on any frame where JEI has an overlay to read.
        boolean overGui = false;
        for (Class<?> event : subscribedEvents()) {
            if (GuiScreenEvent.KeyboardInputEvent.class.isAssignableFrom(event)) {
                overGui = true;
            }
        }
        assertTrue("PlanTargetKeybind has no GuiScreenEvent.KeyboardInputEvent subscription, "
                   + "so the = key cannot fire while JEI's list is on screen -- which is every "
                   + "frame the gesture exists for. #19's MVP is unreachable without it.",
                   overGui);
    }

    @Test
    public void theKeyAlsoStillWorksWithNothingOnScreen() {
        // Kept deliberately rather than replaced: a key that works in one place and silently
        // does not exist in another is worse than one that does nothing when there is nothing
        // to do. This fails if someone "simplifies" by deleting the original handler.
        boolean noGui = false;
        for (Class<?> event : subscribedEvents()) {
            if (InputEvent.KeyInputEvent.class.isAssignableFrom(event)) {
                noGui = true;
            }
        }
        assertTrue("the no-GUI-open path was removed", noGui);
    }

    @Test
    public void theTwoPathsAreTheOnlyOnes() {
        // A third subscription would mean a third frame on which the gesture could fire, and
        // two handlers running for one keystroke plans the same target twice. Stated as a
        // count so adding one is a decision someone makes on purpose.
        assertEquals("PlanTargetKeybind should subscribe to exactly the two disjoint input "
                     + "paths; a new one needs a reason and a double-fire argument",
                     2, subscribedEvents().size());
    }
}
