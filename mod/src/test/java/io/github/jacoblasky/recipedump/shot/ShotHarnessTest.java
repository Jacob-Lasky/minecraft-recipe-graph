package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * The parts of the harness that answer without a window, plus the contract it has with
 * `mod/build.gradle`.
 */
public class ShotHarnessTest {

    @Test
    public void anUnsetPropertyFallsBackAndAnUnparseableOneDoesNotThrow() {
        System.clearProperty("mcrecipedump.testInt");
        assertEquals(7, ShotHarness.intProperty("mcrecipedump.testInt", 7));
        System.setProperty("mcrecipedump.testInt", "  12 ");
        assertEquals(12, ShotHarness.intProperty("mcrecipedump.testInt", 7));
        // A typo in a settle-frame count must not cost a screenshot. The client is eighty
        // seconds away from being able to report anything, so the harness takes the default
        // and says so rather than dying at the far end of that.
        System.setProperty("mcrecipedump.testInt", "twenty");
        assertEquals(7, ShotHarness.intProperty("mcrecipedump.testInt", 7));
        System.clearProperty("mcrecipedump.testInt");
    }

    @Test
    public void armDoesNothingWhenNoScreenWasAskedFor() {
        // The claim under test is that a NORMAL client pays nothing for the harness being
        // compiled in: `arm()` must return before it touches the Forge event bus, before it
        // constructs a Runner, and therefore before anything loads a ModularUI class. If this
        // ever throws, every client without the harness's dev mod set crashes at init.
        String saved = System.getProperty(ShotHarness.PROP_SHOT);
        try {
            System.clearProperty(ShotHarness.PROP_SHOT);
            ShotHarness.arm();
            System.setProperty(ShotHarness.PROP_SHOT, "   ");
            ShotHarness.arm();
        } finally {
            if (saved == null) {
                System.clearProperty(ShotHarness.PROP_SHOT);
            } else {
                System.setProperty(ShotHarness.PROP_SHOT, saved);
            }
        }
    }

    /**
     * The properties are named in Java and FORWARDED in Groovy, and nothing else joins them.
     *
     * `mod/build.gradle` copies every `-D` starting with a prefix from the Gradle JVM into the
     * forked client, because a `-D` on the gradlew command line otherwise reaches only Gradle.
     * If the prefix in either language moves, the harness stops arming and the client boots
     * to the main menu and sits there until the timeout -- which reads as a broken client, not
     * as a renamed constant. This is the same shape of cross-language pin as
     * `test_nbt_digest.JavaSourceContractTest`, run from the side that has a JVM.
     */
    @Test
    public void buildGradleForwardsThePropertyNamespaceTheseConstantsUse() throws IOException {
        String prefix = ShotHarness.PROP_SHOT.substring(0, ShotHarness.PROP_SHOT.indexOf('.') + 1);
        assertEquals("mcrecipedump.", prefix);
        for (String property : new String[] {ShotHarness.PROP_SHOT, ShotHarness.PROP_OUT,
                ShotHarness.PROP_SETTLE, ShotHarness.PROP_TIMEOUT,
                ShotHarness.PROP_DEBUG_OVERLAY}) {
            assertTrue(property + " is outside the forwarded namespace",
                    property.startsWith(prefix));
        }

        String script = readBuildGradle();
        assertTrue("mod/build.gradle no longer forwards " + prefix + " to the client JVM",
                script.contains("startsWith('" + prefix + "')"));
        // Read by build.gradle alone -- it turns them into Minecraft's own --width/--height
        // game args -- so they have no Java constant. They still have to sit inside the
        // forwarded namespace or the single forwarding rule above would not reach them.
        for (String property : new String[] {"shotWidth", "shotHeight"}) {
            assertTrue("mod/build.gradle no longer reads " + prefix + property,
                    script.contains(prefix + property));
        }
    }

    /**
     * Gradle runs tests with the working directory set to `mod/`, but a direct run from the
     * repository root is a thing people do. Try both rather than depending on which; this is
     * the same two-candidate lookup `DigestFixtureTest` uses for the shared fixture.
     */
    private static String readBuildGradle() throws IOException {
        for (String candidate : new String[] {"build.gradle", "mod/build.gradle"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        fail("could not find build.gradle from " + new File(".").getAbsolutePath());
        return null;
    }
}
