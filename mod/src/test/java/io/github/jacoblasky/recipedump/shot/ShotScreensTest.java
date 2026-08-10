package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    public void aHoldIsRegisteredAndDoesNotSurviveTheNextScreen() {
        final boolean[] busy = {true};
        ShotScreens.register("test-hold", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
                ShotScreens.holdCapture(new ShotScreens.Hold() {
                    @Override
                    public boolean busy() {
                        return busy[0];
                    }
                });
            }
        });
        assertNull(ShotScreens.open("test-hold"));
        assertNotNull(ShotScreens.hold());
        assertTrue(ShotScreens.hold().busy());
        busy[0] = false;
        assertFalse(ShotScreens.hold().busy());

        // SAME ARGUMENT AS `animate`, and it bites harder here: a leftover hold belongs to a
        // screen that is no longer open, so the harness would poll a finished job and either
        // hold forever or release instantly. Neither is about the screen being photographed.
        ShotScreens.register("test-nohold", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
            }
        });
        assertNull(ShotScreens.open("test-nohold"));
        assertNull("a screen that asked for no hold must not inherit one", ShotScreens.hold());
    }

    /**
     * Every screen that ships is named here, so a registration cannot go missing quietly.
     *
     * BECAUSE THE PER-SCREEN TESTS COVER 6 OF 24 AND NOTHING SAID SO. `ShotScreens` registers
     * twenty-four screens; six have a `...IsReachableByName` test. The other eighteen -- `flow`,
     * `flow-hit`, `graph`, `machines`, `planner`, `sources` and the rest -- could have their
     * `register(...)` block deleted and every test here would still pass, because a screen with
     * no test has nothing to notice its absence. The harness would then report `no screen named
     * 'flow'` at the far end of a twelve-minute container run, which is where this project's own
     * header says a five-minute confusion becomes an afternoon.
     *
     * THE RISK IS NOT HYPOTHETICAL AND IT IS NOT DECAY, IT IS MERGES. These registrations are
     * append-only blocks in one file, and three branches are adding to them at once. A conflict
     * resolver that takes one side wholesale drops the other side's screen AND the test that
     * would have caught it, in the same edit, leaving a green suite and a missing screen. This
     * assertion is the thing that survives that, because it fails on the SET rather than on
     * whether some particular test still exists.
     *
     * A LIST AND NOT A COUNT. A count passes when one screen is dropped and another added,
     * which is exactly what a bad three-way merge produces. It also could not say WHICH.
     *
     * ADDING A SCREEN MEANS EDITING THIS LIST, and that is the feature. It costs one line and
     * it makes "is this screen meant to exist" a decision somebody made rather than a thing
     * that drifted. DO NOT replace this with `assertEquals(24, names().size())` to avoid the
     * maintenance; the count is the version that cannot tell you what changed.
     */
    @Test
    public void everyRegisteredScreenIsAccountedForByName() {
        List<String> expected = Arrays.asList(
                "ae2-probe", "dump", "fixture", "flow", "flow-hit", "flow-selected",
                "graph", "jei", "jei-keybind", "machines", "machines-detail", "machines-mods",
                "planner", "planner-caveats", "planner-live", "planner-menu", "planner-recipes",
                "planner-recovery", "planner-selected", "planner-todo", "planner-yield",
                "row-menu", "sources", "world-probe");

        List<String> actual = new ArrayList<String>(ShotScreens.names());
        // SORTED ON BOTH SIDES, because `names()` is documented as registration ORDER and that
        // is a property of where a `register` call sits in the file. Asserting the order would
        // make this fail on a harmless reordering, which trains people to update the list
        // without reading it -- and a list nobody reads is the count again.
        Collections.sort(actual);
        List<String> want = new ArrayList<String>(expected);
        Collections.sort(want);

        // BY PREFIX, NOT BY AN ENUMERATED LIST OF FIXTURE NAMES. Other tests in this class
        // register into the same static map, and JUnit does not promise an order, so which of
        // them have run by the time this one does is not fixed. The first version of this
        // hard-coded seven names, four of which do not exist and five of which it missed -- a
        // guessed expected value, which is the defect this whole test is about, committed
        // inside the test itself.
        //
        // The prefix is the contract: a SHIPPED screen must never be named `test-...`, and
        // none is. Anything that is gets filtered here and would also be invisible to the
        // harness user, so the two rules point the same way.
        List<String> shipped = new ArrayList<String>();
        for (String name : actual) {
            if (!name.startsWith("test-")) {
                shipped.add(name);
            }
        }
        actual = shipped;

        assertEquals("a screen was added or dropped; if this was deliberate, edit the list "
                + "above and say why in the commit", want, actual);
    }

    @Test
    public void aPoseAndADrawnCheckAreRegisteredAndNeitherSurvivesTheNextScreen() {
        final int[] posed = {0};
        ShotScreens.register("test-pose", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
                ShotScreens.preCapture(new ShotScreens.PreCapture() {
                    @Override
                    public void beforeCapture() {
                        posed[0]++;
                    }
                });
                ShotScreens.expectDrawn(new ShotScreens.Drawn() {
                    @Override
                    public boolean drewSomething() {
                        return posed[0] > 0;
                    }

                    @Override
                    public String describe() {
                        return "posed " + posed[0] + " time(s)";
                    }
                });
            }
        });
        assertNull(ShotScreens.open("test-pose"));
        assertNotNull(ShotScreens.preCaptureScreen());
        assertNotNull(ShotScreens.drawnCheck());

        // THE CHECK MUST BE ABLE TO SAY NO, which is the property #293 is about. A drawn check
        // that only ever returns true is the blank screenshot again with an extra step, so the
        // test asserts the false BEFORE the true rather than only confirming the happy path.
        assertFalse("nothing has posed yet, so nothing was drawn",
                ShotScreens.drawnCheck().drewSomething());
        ShotScreens.preCaptureScreen().beforeCapture();
        assertTrue(ShotScreens.drawnCheck().drewSomething());
        assertEquals(1, posed[0]);

        // SAME ARGUMENT AS `animate` AND `holdCapture`. A leftover pose would move a screen
        // that never asked to be moved, and a leftover drawn check would answer for a canvas
        // that is no longer open -- which is a green run certified by the previous screen.
        ShotScreens.register("test-nopose", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
            }
        });
        assertNull(ShotScreens.open("test-nopose"));
        assertNull("a screen that asked for no pose must not inherit one",
                ShotScreens.preCaptureScreen());
        assertNull("a screen that cannot answer must not inherit an answer",
                ShotScreens.drawnCheck());
    }

    @Test
    public void onlyAnEntryThatSaysSoIsAllowedToOpenNoScreen() {
        // THE DEFAULT MUST STAY FALSE. This flag suppresses the harness's check that an opener
        // actually did something, and for the ten entries that photograph a GUI that check is
        // the only thing standing between "the screen declined to open" and a photograph of
        // the main menu reported as a success.
        ShotScreens.register("test-opens-nothing", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
                ShotScreens.expectNoScreen();
            }
        });
        ShotScreens.register("test-opens-a-screen", new ShotScreens.Opener() {
            @Override
            public void open(String arg) {
            }
        });

        assertNull(ShotScreens.open("test-opens-nothing"));
        assertTrue(ShotScreens.noScreenExpected());

        assertNull(ShotScreens.open("test-opens-a-screen"));
        assertFalse("the declaration must not outlive the entry that made it",
                ShotScreens.noScreenExpected());
    }

    @Test
    public void theDumpScreenIsReachableByName() {
        // Same reasoning as the AE2 probe: the name is the whole interface, and asserting the
        // name rather than the class keeps `DumpShot` -- and through it `ClientCommandHandler`
        // -- off the test runtime classpath.
        assertTrue(ShotScreens.names().toString(), ShotScreens.names().contains("dump"));
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

    /**
     * #201's artifact is two runs of one screen, so the name has to survive a rename.
     *
     * WORTH ITS THREE LINES BECAUSE OF WHAT A MISSING SCREEN LOOKS LIKE FROM THE OUTSIDE. A
     * shot run against an unregistered name exits non-zero with no PNG -- which is also what a
     * boot that died and a boot that measured somebody else's jar look like. This is the cheap
     * one of the three to rule out, and ruling it out here means it never has to be ruled out
     * by launching a client.
     */
    @Test
    public void thePlannerRecoveryScreenIsReachableByName() {
        assertTrue(ShotScreens.names().toString(),
                   ShotScreens.names().contains("planner-recovery"));
    }

    @Test
    public void theJeiKeybindProbeIsReachableByName() {
        // Same reasoning again, and it is the only assertion about `JeiKeybindShot` that a
        // JUnit JVM can make: everything else in that class needs a JEI runtime, a world and a
        // real cursor. If this name is dropped from the registry the probe silently stops
        // existing and every run that asked for it fails on the NAME rather than on the
        // gesture, which reads as a typo (#240).
        //
        // AND THE NAME IS WHY `ShotScreens` REPEATS THE LITERAL RATHER THAN READING A CONSTANT
        // OFF THE SHOT. `JeiKeybindShot` names `GuiInventory` and `Minecraft`, so referring to
        // a field on it from the registry's static initialiser would load LWJGL and throw
        // NoClassDefFoundError in every test in this file. The duplication is the price of the
        // registry staying loadable headlessly, and this assertion is what makes it safe.
        assertTrue(ShotScreens.names().toString(),
                   ShotScreens.names().contains("jei-keybind"));
    }

    @Test
    public void theRowMenuShotIsReachableByName() {
        // The name is the whole interface, as with every entry here. It matters more for this
        // one than for most: #251's change to the TODO panel is invisible in a screenshot by
        // design -- `ClickableGroup` draws nothing -- so this menu is the ONLY artifact the
        // issue has, and a dropped registration would leave it with none while every layout
        // test still passed.
        assertTrue(ShotScreens.names().toString(), ShotScreens.names().contains("row-menu"));
    }

    @Test
    public void theYieldShotIsReachableByName() {
        // #252's render had no artifact for months because no fixture could express it, and
        // then no SHOT could frame it: the earliest chance-yielded row in any fixture is index
        // 38 and the tree viewport is about 14 rows. `planner-yield` is the only screen that
        // scrolls to one, so a dropped registration takes the artifact with it while every
        // layout test still passes.
        assertTrue(ShotScreens.names().toString(),
                   ShotScreens.names().contains("planner-yield"));
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
