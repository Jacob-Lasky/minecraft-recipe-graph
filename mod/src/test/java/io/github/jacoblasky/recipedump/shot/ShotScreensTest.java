package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The screen registry, exercised with no window and no game.
 *
 * Worth testing because the whole harness is unreachable if the spec parsing is wrong, and
 * the way it would be wrong is silent: `plan:iron` would look up a screen called `plan:iron`,
 * find nothing, and report "no screen named ..." for a screen that IS registered. That is a
 * five-minute confusion at the far end of an eighty-second feedback loop.
 */
public class ShotScreensTest {

    @Test
    public void theFixtureScreenIsRegistered() {
        // The name `harness/shot.sh` defaults to. If it ever moves, the documented
        // zero-argument invocation stops working.
        assertTrue(ShotScreens.names().toString(), ShotScreens.names().contains("fixture"));
    }

    @Test
    public void anUnknownNameIsReportedAndListsWhatDoesExist() {
        String problem = ShotScreens.open("definitely-not-a-screen");
        assertNotNull(problem);
        assertTrue(problem, problem.contains("definitely-not-a-screen"));
        // The list matters as much as the rejection: it is the only discovery mechanism
        // there is, and it arrives in the log of the run that got the name wrong.
        assertTrue(problem, problem.contains("fixture"));
    }

    @Test
    public void anEmptySpecIsRejectedRatherThanTreatedAsAName() {
        assertNotNull(ShotScreens.open(null));
        assertNotNull(ShotScreens.open("   "));
    }

    @Test
    public void everythingAfterTheFirstColonIsTheArgument() {
        final String[] seen = new String[1];
        ShotScreens.register("test-arg", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
                seen[0] = arg;
            }
        });
        // Two colons on purpose: the split is on the FIRST one, so a screen whose argument is
        // itself a key (`plan:fluid:nethengeic_fluid`) survives. Splitting on every colon
        // would silently truncate the one namespace this project uses most.
        assertNull(ShotScreens.open("test-arg:fluid:nethengeic_fluid"));
        assertEquals("fluid:nethengeic_fluid", seen[0]);
    }

    @Test
    public void aScreenWithNoArgumentGetsNull() {
        final String[] seen = {"not called"};
        ShotScreens.register("test-noarg", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
                seen[0] = arg;
            }
        });
        assertNull(ShotScreens.open("test-noarg"));
        assertNull(seen[0]);
    }

    @Test
    public void anOpenerThatThrowsBecomesAMessageRatherThanAnEscapingException() {
        // The harness turns this into a non-zero exit and a log line. If it escaped instead,
        // it would be swallowed by Forge's event bus and the run would hang until the
        // timeout, reporting the wrong cause.
        ShotScreens.register("test-throws", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
                throw new IllegalStateException("boom");
            }
        });
        String problem = ShotScreens.open("test-throws");
        assertNotNull(problem);
        assertTrue(problem, problem.contains("boom"));
    }
}
