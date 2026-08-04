package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.client.jei.PlanTargetKeybind;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.junit.Test;

/**
 * That the JEI plan key is actually PLUGGED IN, which is the one thing its own tests cannot
 * see.
 *
 * THIS IS THE DEFECT CLASS #191 CATALOGUES, and this feature had two of them at once. A seam
 * whose producer and consumer are both well tested, joined by nothing: `JeiBridgeTest`,
 * `JeiNodeActionsTest` and `PlanTargetRouterTest` each install a `TargetListener` of their own
 * before asserting, so all of them stayed green through two releases in which the shipped mod
 * never called `PlannerHooks.setTargetListener` and the key was a no-op on every real client.
 * The second was the same shape one layer down: the handler existed, was tested by calling it
 * directly, and was subscribed to the one Forge event that cannot fire while a `GuiScreen` is
 * open -- which is every frame on which JEI has an overlay to read.
 *
 * SO THESE TESTS READ THE WIRING RATHER THAN THE BEHAVIOUR, because the behaviour is
 * unreachable: `ClientProxy.init` wants a running client and the keybind wants a keyboard.
 * That makes them weaker than the tests around them, and worth having anyway -- they fail when
 * someone deletes the line, which is the whole failure mode. The live evidence that the
 * gesture works end to end is the `jei-keybind` screenshot screen, and it is not a substitute
 * for this: a harness run happens when somebody remembers to run it.
 */
public class ClientWiringTest {

    /**
     * `ClientProxy` installs a target listener.
     *
     * READ OUT OF THE CLASS FILE, NOT BY CALLING IT. `init` registers a command, arms the
     * screenshot harness and reaches for `Minecraft.getMinecraft()`, none of which a unit test
     * can survive. A method reference puts the callee's name in the constant pool as plain
     * UTF-8, so the presence of the call is a fact about the bytes.
     */
    @Test
    public void clientProxyInstallsTheJeiTargetListener() throws Exception {
        String bytes = classBytes("io/github/jacoblasky/recipedump/client/ClientProxy.class");
        assertTrue("ClientProxy must install a PlannerHooks target listener -- without it the"
                   + " JEI plan key resolves an item and hands it to a listener that does"
                   + " nothing, on every real client",
                   bytes.contains("setTargetListener"));
        assertTrue("and the listener it installs must be the router",
                   bytes.contains("PlanTargetRouter"));
    }

    /**
     * The keybind subscribes to the event that fires while a GUI is open.
     *
     * `InputEvent.KeyInputEvent` alone is the bug: Minecraft routes the keyboard to the open
     * screen instead of maintaining keybind state, so that handler cannot fire on any frame
     * where JEI is drawing an overlay to point at. The pack's own JEI build agrees --
     * `HadEnoughItems_1.12.2-4.28.1.jar` references `GuiScreenEvent$KeyboardInputEvent` and
     * never `InputEvent$KeyInputEvent`, and R and U are the same gesture as this key.
     */
    @Test
    public void theKeybindListensOnTheEventThatFiresWhileAGuiIsOpen() {
        Method found = null;
        for (Method method : PlanTargetKeybind.class.getDeclaredMethods()) {
            if (method.getAnnotation(SubscribeEvent.class) == null) {
                continue;
            }
            Class<?>[] takes = method.getParameterTypes();
            if (takes.length == 1
                    && GuiScreenEvent.KeyboardInputEvent.Post.class.isAssignableFrom(takes[0])) {
                found = method;
            }
        }
        assertNotNull("PlanTargetKeybind must subscribe to GuiScreenEvent.KeyboardInputEvent"
                      + " -- pointing at something in JEI means a GuiScreen is open, and"
                      + " InputEvent.KeyInputEvent does not fire then", found);
    }

    /** The class file as a string, so a constant-pool name can be searched for literally. */
    private String classBytes(String resource) throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull("compiled " + resource + " must be on the test classpath", in);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "ISO-8859-1");
        } finally {
            in.close();
        }
    }
}
