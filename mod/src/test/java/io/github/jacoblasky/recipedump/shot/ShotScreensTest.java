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

    /**
     * Opening a screen wipes the previous screen's settle request and verdict.
     *
     * SAME ARGUMENT AS `animate`: one process opens one screen today, so nothing here is
     * currently wrong, and a leftover would be invisible in exactly the way that matters. A
     * stale `pendingReport` fails a run over a screen that never declared one; a stale
     * `failedVerdict` fails it over another screen's finding; a stale `settleRequest` makes a
     * static screen sit through 150 frames for no reason. All three read as flakes.
     */
    @Test
    public void openingAScreenClearsTheVerdictTheLastOneLeftBehind() {
        ShotScreens.register("test-leftovers", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
            }
        });
        ShotScreens.expectReport("something owed");
        ShotScreens.reportFail("something failed");
        ShotScreens.requestSettleFrames(150);
        assertNotNull(ShotScreens.failedVerdict());
        assertEquals(150, ShotScreens.settleRequest());

        assertNull(ShotScreens.open("test-leftovers"));
        assertNull("a fresh screen owes nothing", ShotScreens.pendingReport());
        assertNull("a fresh screen has failed nothing", ShotScreens.failedVerdict());
        assertEquals(0, ShotScreens.settleRequest());
    }

    /**
     * The two ways to clear the debt, and the difference between them.
     *
     * A PASS AND A NO BOTH DISCHARGE THE DEBT AND ONLY ONE OF THEM IS A PASS. That distinction
     * is what the previous single `reported()` could not express, which is how `Ae2ProbeShot`
     * came to call it on five failure paths and read as successful.
     */
    @Test
    public void aPassAndAFailBothClearTheDebtAndOnlyOneIsAFailure() {
        ShotScreens.register("test-verdicts", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
            }
        });

        assertNull(ShotScreens.open("test-verdicts"));
        ShotScreens.expectReport("a verdict");
        assertNotNull(ShotScreens.pendingReport());
        ShotScreens.reportPass();
        assertNull(ShotScreens.pendingReport());
        assertNull("a pass is not a failure", ShotScreens.failedVerdict());

        assertNull(ShotScreens.open("test-verdicts"));
        ShotScreens.expectReport("a verdict");
        ShotScreens.reportFail("nodes>=2 false");
        assertNull("a NO still discharges the debt", ShotScreens.pendingReport());
        assertEquals("nodes>=2 false", ShotScreens.failedVerdict());

        // A REASON IS NOT OPTIONAL IN PRACTICE. `reportFail(null)` from a path that forgot to
        // say why must still fail the run, because a failure with no reason is worth strictly
        // more than a silent success -- so it substitutes a string rather than staying null.
        assertNull(ShotScreens.open("test-verdicts"));
        ShotScreens.reportFail(null);
        assertNotNull(ShotScreens.failedVerdict());
        assertNull(ShotScreens.open("test-verdicts"));
        ShotScreens.reportFail("   ");
        assertNotNull(ShotScreens.failedVerdict());
    }

    @Test
    public void aScreenThatContradictsItselfFailsRatherThanPasses() {
        // A screen reporting both is a screen with a defect, and the two ways to resolve that
        // are not symmetric: failing closed costs a re-run, passing closed publishes a green
        // result over a criterion that did not hold. `ae2-probe` reaches `reportPass` only
        // inside `nodesOk && powered && storedOk`, so this is the backstop for a future screen
        // that is less careful about where its pass lives.
        ShotScreens.register("test-contradiction", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
            }
        });
        assertNull(ShotScreens.open("test-contradiction"));
        ShotScreens.expectReport("a verdict");
        ShotScreens.reportFail("stored==64 false");
        ShotScreens.reportPass();
        assertNull(ShotScreens.pendingReport());
        assertEquals("a pass must not erase a failure already reported",
                "stored==64 false", ShotScreens.failedVerdict());
    }

    @Test
    public void theAe2ProbeIsReachableByName() {
        // The only thing that makes `Ae2ProbeShot` reachable at all: it is opened through
        // `-Dmcrecipedump.shot=ae2-probe` and nothing else references the class. Asserting the
        // NAME rather than the class on purpose -- the entries are anonymous openers precisely
        // so that looking one up does not load AE2, and a test that loaded the class here would
        // need AE2 on the test runtime classpath, where it deliberately is not.
        assertTrue(ShotScreens.names().toString(), ShotScreens.names().contains("ae2-probe"));
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
