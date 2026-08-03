package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * The live-input list, held to the fixtures' own vocabulary.
 *
 * WHY THIS IS A CONTRACT TEST AND NOT A SPELLING CHECK. `ScenarioSource` names the fields of
 * a plan scenario so the planner can tell a player which of them it could not read. If a name
 * drifts from what `tests/fixtures/plan/*.json` uses, two things break quietly: the planner
 * warns about an input that no longer exists, and it stops warning about one that does --
 * so the player is told the plan accounts for their AE2 stock when it does not. Nothing
 * throws either way.
 *
 * Asserted against a COMMITTED FIXTURE rather than a list restated here, because a second
 * copy of the field names in this test would drift in exactly the same way and agree with
 * itself while doing it.
 */
public class ScenarioSourceTest {

    /**
     * Any plan fixture will do -- every one carries a full `scenario` block, which is itself
     * asserted by `tests/test_plan_fixtures.py`. The bare one is chosen because it is the
     * smallest and sets no field to anything interesting.
     */
    private static final String FIXTURE = "tests/fixtures/plan/plan-dimension-gate.json";

    /**
     * Gradle runs tests with the module directory as the working directory, and a run from
     * the repository root is reasonable too. Same two candidates `DigestFixtureTest` tries,
     * for the same reason.
     */
    private static JsonObject fixture() throws IOException {
        File[] candidates = {new File("../" + FIXTURE), new File(FIXTURE)};
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                Reader reader = new FileReader(candidate);
                try {
                    return new JsonParser().parse(reader).getAsJsonObject();
                } finally {
                    reader.close();
                }
            }
        }
        throw new IOException("cannot find " + FIXTURE + " from "
                + new File(".").getAbsolutePath());
    }

    /** `entrySet`, not `keySet`: gson 2.8.0 is what 1.12.2 ships and it has no `keySet`. */
    private static Set<String> keysOf(JsonObject object) {
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            out.add(entry.getKey());
        }
        return out;
    }

    private static Set<String> fixtureFields() throws IOException {
        return keysOf(fixture().getAsJsonObject("scenario"));
    }

    private static Set<String> declaredFields() {
        Set<String> out = new LinkedHashSet<String>();
        for (ScenarioSource source : ScenarioSource.values()) {
            out.add(source.field());
        }
        return out;
    }

    @Test
    public void everyScenarioFieldIsDeclaredAndNothingExtraIs() throws IOException {
        // BOTH DIRECTIONS. A missing entry means an input the planner never warns about; an
        // extra one means it warns about a field the solver does not read.
        assertEquals(fixtureFields(), declaredFields());
    }

    @Test
    public void thePlannerBuildsEveryFieldTheFixturesCarry() throws IOException {
        // `PlannerService.liveScenario` is what `ScenarioInputs.resolve` is handed in game,
        // so a field it forgets is one the game silently defaults where a fixture states it.
        assertEquals(fixtureFields(), keysOf(PlannerService.liveScenario()));
    }

    @Test
    public void anInputThatIsNotLiveCarriesAReason() {
        // A warning with no reason is one a player cannot act on, and it is the reason rather
        // than the flag that stops "planned without: have" reading as a defect.
        //
        // COUNTED, because the assertion is inside an `if` and every source is expected to
        // report live eventually. On the day the last one does, "not live" selects nothing,
        // the loop asserts nothing, and this test goes green forever over a rule it stopped
        // checking. The count is what makes that day a FAILURE that has to be looked at
        // rather than a silent no-op.
        //
        // WHAT THIS TEST COVERS IS THE DECLARED CONSTANTS, and only those.
        // `aReaderCanSayWHICHRefusalHappened` below asserts the same rule through a Reader
        // and cannot go vacuous, so the rule itself outlives this test. That is why the
        // failure message says to delete THIS sweep rather than to keep `note()` alive for
        // it: `note()` is still how a runtime refusal names itself.
        //
        // A SECOND REASON THIS GUARD MATTERS, and it is the worse one: THE REASONS DO NOT
        // REACH A SCREEN (#190). Be precise about which half is missing, because the two
        // read alike from outside. `summary()` IS drawn -- `PlannerWidgets:290` wraps it into
        // the caveat line -- but it is built from `source.field`, so what a player sees is
        // "planned without: have, craftables, placed, visited_dimensions, emc_knowledge".
        // The five hand-written `note()` strings, the ones carrying what to DO about it, are
        // collected only by `missingNotes()`, whose sole callers are this test and
        // `PinStoreTest`.
        //
        // That is exactly the failure `Status.unavailable`'s own javadoc argues against in
        // the sentence justifying why it takes a string rather than a boolean: "AE2 stock is
        // not read yet" tells a player nothing they can act on, "no wireless access point in
        // range" tells them to walk toward their base. The strings were written for that
        // reason and the player gets the boolean. Until `missingNotes()` is rendered, this
        // loop is the only thing asserting those five strings exist at all -- which is
        // precisely the state in which it must not quietly stop running.
        int asserted = 0;
        for (ScenarioSource source : ScenarioSource.values()) {
            if (!source.live()) {
                assertFalse(source + " is not live and says nothing about why",
                            source.note().isEmpty());
                asserted++;
            }
        }
        assertTrue("no source declares itself not-live, so this test asserted nothing. Every "
                   + "source now reports live: delete this sweep. Do NOT delete note() or "
                   + "aReaderCanSayWHICHRefusalHappened -- a runtime refusal still has to say "
                   + "why, and that test is where the rule lives afterwards", asserted > 0);
    }

    @Test
    public void aLiveInputDoesNotWarn() {
        // Counted for the same reason as the test above, in the other direction: a change
        // that made every source not-live -- a reader wired wrong, an enum row edited --
        // would empty this loop and leave it green over the rule it exists for.
        int asserted = 0;
        for (ScenarioSource source : ScenarioSource.values()) {
            if (source.live()) {
                assertTrue(source + " is live and should have nothing to warn about",
                           source.note().isEmpty());
                asserted++;
            }
        }
        assertTrue("no source is live, so this test asserted nothing", asserted > 0);
    }

    @Test
    public void theSummaryNamesTheUnreadInputs() {
        // The one line that keeps an incomplete plan from reading as a complete one. Stock is
        // the case that matters: an unread `have` is the claim "you own nothing".
        String summary = ScenarioSource.summary();
        assertTrue(summary, summary.contains(ScenarioSource.HAVE.field()));
        assertFalse(summary, summary.contains(ScenarioSource.PINS.field()));
    }

    @Test
    public void theSummaryIsEmptyOnceEverythingIsLive() {
        // Pins the shape of the end state rather than today's: when every source reports
        // live, the caveat must disappear instead of becoming an empty "planned without: "
        // that reads like a bug.
        //
        // NOT "WHEN PHASE 5 LANDS". Phase 5's grid read already landed (#150 --
        // `Ae2StockReader` walks the network through `IStorageGrid`); what is missing is the
        // JOIN, and only the join: nothing calls `HAVE.readBy` and `liveScenario()` never
        // feeds the read into the `have` field. That is #191, and naming a phase here dated
        // the comment against work that is already half done.
        boolean anyMissing = false;
        for (ScenarioSource source : ScenarioSource.values()) {
            anyMissing |= !source.live();
        }
        assertEquals(anyMissing, !ScenarioSource.summary().isEmpty());
    }

    @Test
    public void stockIsNotSilentlyAssumedEmpty() {
        // The specific wrong answer this whole enum exists to prevent: planning as though the
        // player owns nothing, which tells them to fetch things already in their ME system.
        assertFalse(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.HAVE.note(),
                   ScenarioSource.HAVE.note().contains("own nothing"));
        assertTrue(ScenarioSource.missingNotes().contains(ScenarioSource.HAVE.note()));
    }

    // -- the runtime reader, which is how a source goes live (#191) ----------------------

    @org.junit.After
    public void dropReaders() {
        // GLOBAL STATE, so it has to be undone. A reader left installed by one test makes the
        // next one assert against a source nobody wired -- and because every assertion here
        // is about what the planner SAYS, the failure would read as a wording change.
        ScenarioSource.resetReaders();
    }

    @Test
    public void withNoReaderASourceAnswersItsDeclaredConstant() {
        assertFalse(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.HAVE.note().contains("own nothing"));
        assertTrue(ScenarioSource.PINS.live());
        assertTrue(ScenarioSource.PINS.note().isEmpty());
    }

    @Test
    public void aReaderCanReportTheInputAsLiveAndTheCaveatDrops() {
        // The state a successful read puts the source in: `have` stops being a warning and
        // the summary stops naming it. Nothing else about the source changes. This is the
        // half of #191 that is already built -- `Ae2StockReader` produces exactly this
        // answer -- and the half that is missing is the caller that installs it.
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available();
            }
        });
        assertTrue(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.HAVE.note().isEmpty());
        assertFalse(ScenarioSource.summary(),
                    ScenarioSource.summary().contains(ScenarioSource.HAVE.field()));
    }

    @Test
    public void aReaderCanSayWHICHRefusalHappened() {
        // THE POINT OF THE WHOLE SEAM. "AE2 stock is not read yet" tells a player nothing
        // they can act on. `StockSnapshot` knows the difference between an empty network, no
        // network in range, and no wireless terminal, and only it knows -- a caller flipping
        // a boolean afterwards would be re-deriving that from outside and getting it wrong
        // the first time a read failed.
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.unavailable("no wireless terminal in inventory");
            }
        });
        assertFalse(ScenarioSource.HAVE.live());
        assertEquals("no wireless terminal in inventory", ScenarioSource.HAVE.note());
        assertTrue(ScenarioSource.missingNotes()
                                 .contains("no wireless terminal in inventory"));
    }

    @Test
    public void theReaderIsAskedEveryTimeRatherThanCached() {
        // A grid can go out of range between two plans, so a status resolved once at install
        // would keep claiming a read that no longer happens.
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return calls.incrementAndGet() > 1
                        ? ScenarioSource.Status.unavailable("out of range")
                        : ScenarioSource.Status.available();
            }
        });
        assertTrue(ScenarioSource.HAVE.live());
        assertFalse(ScenarioSource.HAVE.live());
        assertEquals("out of range", ScenarioSource.HAVE.note());
    }

    @Test
    public void aNullReaderRestoresTheDeclaredConstant() {
        // What a world unload does. A reader that outlived its world would go on answering
        // for a grid nobody is near.
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available();
            }
        });
        assertTrue(ScenarioSource.HAVE.live());
        ScenarioSource.HAVE.readBy(null);
        assertFalse(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.HAVE.note().contains("own nothing"));
    }

    @Test
    public void aReaderReturningNullFallsBackRatherThanClaimingALiveRead() {
        // A broken reader must not be read as success. Defaulting to live would silently
        // assert the planner accounted for stock it never saw, which is the one outcome this
        // whole enum exists to prevent.
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return null;
            }
        });
        assertFalse(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.HAVE.note().contains("own nothing"));
    }

    @Test
    public void anUnavailableStatusWithNoReasonSaysSoRatherThanFillingIn() {
        // An empty note renders as a blank line under the caveat, which looks like a
        // rendering fault. A TIDY filler would be worse: a reader that refuses without a
        // reason is a bug in the reader, and "not available" reads as ordinary UI so nobody
        // reports it. This is worded to read as a fault. graphmodel's argument, and right.
        assertEquals(ScenarioSource.NO_REASON_GIVEN,
                     ScenarioSource.Status.unavailable("").note());
        assertEquals(ScenarioSource.NO_REASON_GIVEN,
                     ScenarioSource.Status.unavailable(null).note());
        assertFalse("the placeholder must not read as an ordinary state",
                    ScenarioSource.NO_REASON_GIVEN.equals("not available"));
    }

    @Test
    public void everySourceGoingLiveEmptiesTheCaveatEntirely() {
        // The end state, asserted now so the caveat disappears rather than degrading into an
        // empty "planned without: ". Reached one source at a time as each read is joined up
        // (#191); this asserts the destination rather than any particular phase.
        for (ScenarioSource source : ScenarioSource.values()) {
            source.readBy(new ScenarioSource.Reader() {
                @Override
                public ScenarioSource.Status status() {
                    return ScenarioSource.Status.available();
                }
            });
        }
        assertTrue(ScenarioSource.summary(), ScenarioSource.summary().isEmpty());
        assertTrue(ScenarioSource.missingNotes().isEmpty());
    }
}
