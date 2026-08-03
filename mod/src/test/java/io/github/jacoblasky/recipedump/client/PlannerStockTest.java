package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The join #191 was about: a snapshot the client holds becoming the `have` a plan is priced
 * against, and a plan waiting for the read rather than guessing ahead of it.
 *
 * WHAT THIS CAN REACH WITHOUT A CLIENT. There is no `Minecraft` here, so `canAsk` is false and
 * `planWhenRead` runs straight through -- which is the disconnected path, and worth asserting
 * because it is also the screenshot harness's path. The WAITING path is reached through
 * {@link PlannerStock#hold}, which is the same decision with the ask separated out, and the
 * reply through {@link PlannerStock#accept}, which is what the proxy's scheduled task calls.
 *
 * What no test here can prove is that the proxy installs the reader and schedules the reply at
 * all; `SeamInstallationTest` makes that claim about the seam, and it is the claim whose
 * absence let this whole path ship dead.
 */
public class PlannerStockTest {

    @Before
    @After
    public void isolate() {
        // All three are static, so a case that installs a reader or leaves a snapshot behind
        // would decide the next one's answer.
        LiveStock.forget();
        PlannerStock.forgetWaiting();
        ScenarioSource.resetReaders();
    }

    // -- what a held snapshot means for a plan -------------------------------------------------

    @Test
    public void nobodyHasAskedIsAReasonThatSaysWhatToDo() {
        ScenarioSource.Status status = PlannerStock.statusOf(null);
        assertFalse(status.live());
        assertTrue(status.note(), status.note().contains("own nothing"));
        assertTrue("a caveat has to name an action; this is the string #191 removed from"
                   + " ScenarioSource for failing that", status.note().contains("open the"));
        assertEquals("nothing was read, so nothing may be priced against it",
                     null, status.contents());
    }

    @Test
    public void aRefusalIsReportedAsTheREASONRatherThanAsNotReadYet() {
        // The point of the whole seam. "Out of range" tells the player to walk to their base;
        // "not read yet" tells them nothing, and the two are indistinguishable to a planner
        // that only knows whether a snapshot exists.
        ScenarioSource.Status status = PlannerStock.statusOf(
                StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        assertFalse(status.live());
        assertEquals(StockSnapshot.Reason.OUT_OF_RANGE.message(), status.note());
        assertEquals(null, status.contents());
    }

    @Test
    public void aReadNetworkBecomesTheHaveFieldAndTheCaveatGoes() {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("minecraft:iron_ingot", Long.valueOf(377L));
        LiveStock.accept(StockSnapshot.of(counts));
        PlannerStock.install();

        assertTrue(ScenarioSource.HAVE.live());
        assertEquals("", ScenarioSource.HAVE.note());
        JsonObject have = PlannerService.liveScenario().getAsJsonObject("have");
        assertEquals(377L, have.get("minecraft:iron_ingot").getAsLong());
    }

    /**
     * THE BUG THIS WHOLE ISSUE IS ABOUT, asserted from the other side.
     *
     * Before #191 the snapshot arrived, was stored, and `liveScenario` emitted an empty `have`
     * anyway -- so the plan said "go and get 377 iron ingots" about ingots sitting in the ME
     * system, with a caveat underneath saying stock had not been read. Both halves were
     * individually correct.
     */
    @Test
    public void anEmptyHaveIsNotWhatAReadNetworkProduces() {
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:iron_ingot", Long.valueOf(377L))));
        PlannerStock.install();
        assertFalse("the read landed and the scenario still claims the player owns nothing",
                    PlannerService.liveScenario().getAsJsonObject("have").entrySet().isEmpty());
    }

    @Test
    public void anEmptyNetworkIsStillARead() {
        // An available snapshot may legitimately be empty, and that is a different statement
        // from every refusal: the plan is correct and carries no caveat.
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap()));
        PlannerStock.install();
        assertTrue(ScenarioSource.HAVE.live());
        assertTrue(PlannerService.liveScenario().getAsJsonObject("have").entrySet().isEmpty());
    }

    @Test
    public void aRefusalDoesNotSilentlyPriceAPlanAgainstNothing() {
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL));
        PlannerStock.install();
        assertFalse(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.summary(),
                   ScenarioSource.summary().contains(ScenarioSource.HAVE.field()));
        assertTrue(ScenarioSource.missingNotes()
                                 .contains(StockSnapshot.Reason.NO_TERMINAL.message()));
    }

    @Test
    public void theReaderFollowsTheHeldSnapshotRatherThanTheOneItWasInstalledWith() {
        // A grid can go out of range between two plans, so the reader has to look each time.
        PlannerStock.install();
        assertFalse(ScenarioSource.HAVE.live());
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L))));
        assertTrue(ScenarioSource.HAVE.live());
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        assertFalse(ScenarioSource.HAVE.live());
    }

    // -- waiting for the read ------------------------------------------------------------------

    @Test
    public void withNobodyToAskThePlanRunsNowRatherThanNever() {
        // The disconnected client and the screenshot harness. Waiting for a reply that cannot
        // come would leave the planner sitting on "nothing planned" with no explanation.
        //
        // Through `hold` and not `planWhenRead`, because the latter reaches `Minecraft` to
        // decide whether there is anyone to ask and a JUnit classpath cannot load that class
        // at all. What is asserted here is the decision; that the decision is fed by the
        // connection is one line, and it is stated in the PR as untested rather than implied.
        Ran plan = new Ran();
        assertFalse("nobody to ask, so nothing is left waiting for a reply",
                    PlannerStock.hold(plan, false));
        assertTrue(plan.ran);
    }

    @Test
    public void anEarlierQuestionIsDroppedRatherThanAnsweredAfterANewerOne() {
        // Two outstanding asks means the player moved on. `PlannerService.plan` refuses a
        // second solve while one is running, so answering the abandoned question first would
        // let it win and the one on screen lose.
        Ran abandoned = new Ran();
        Ran current = new Ran();
        assertTrue("the first question sends the ask", PlannerStock.hold(abandoned, true));
        assertFalse("and the second must NOT send a second one -- the reply to the first is"
                    + " already coming, and a read is a megabyte on the wire",
                    PlannerStock.hold(current, true));
        PlannerStock.accept(
                StockSnapshot.of(Collections.<String, Long>emptyMap()).serializeNBT());
        assertFalse(abandoned.ran);
        assertTrue(current.ran);
    }

    @Test
    public void theAskGoesOutAgainOnceTheReplyHasLanded() {
        // The other side of the no-second-ask rule: it must suppress a DUPLICATE ask, not
        // every later one. A snapshot that has aged out is asked for again.
        assertTrue(PlannerStock.hold(new Ran(), true));
        PlannerStock.accept(
                StockSnapshot.unavailable(StockSnapshot.Reason.NO_AE2).serializeNBT());
        LiveStock.forget();
        assertTrue("nothing is outstanding any more, so a new question asks",
                   PlannerStock.hold(new Ran(), true));
    }

    @Test
    public void aReplyRunsThePlanThatWasWaitingOnIt() {
        Ran plan = new Ran();
        assertTrue("nothing has been read, so the plan waits", PlannerStock.hold(plan, true));
        assertFalse(plan.ran);

        StockSnapshot snapshot = StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)));
        PlannerStock.accept(snapshot.serializeNBT());

        assertTrue("the reply landed and the question it was asked for went unanswered",
                   plan.ran);
        assertEquals(64L, LiveStock.latest().count("minecraft:stone"));
        assertTrue(LiveStock.isFresh());
    }

    @Test
    public void aRefusalStillAnswersTheQuestionRatherThanLeavingItHanging() {
        // The direction that matters: "no wireless terminal" must not mean the planner shows
        // nothing forever. The plan runs, and the caveat says why it is a lower bound.
        Ran plan = new Ran();
        PlannerStock.hold(plan, true);
        PlannerStock.accept(
                StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL).serializeNBT());
        assertTrue(plan.ran);
        assertFalse(LiveStock.latest().isAvailable());
    }

    @Test
    public void aSecondReplyWithNothingWaitingIsHeldRatherThanPlannedOn() {
        Ran first = new Ran();
        PlannerStock.hold(first, true);
        PlannerStock.accept(StockSnapshot.of(
                Collections.singletonMap("a", Long.valueOf(1L))).serializeNBT());
        assertTrue(first.ran);
        first.ran = false;

        PlannerStock.accept(StockSnapshot.of(
                Collections.singletonMap("b", Long.valueOf(2L))).serializeNBT());
        assertFalse("a solve nobody asked for would replace what the player is looking at",
                    first.ran);
        assertEquals(2L, LiveStock.latest().count("b"));
    }

    @Test
    public void aFreshReadIsNotAskedForTwice() {
        // Opening the planner twice in a row must not put a megabyte of item list on the wire
        // twice -- which is the whole reason `isFresh` exists.
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap()));
        assertTrue(LiveStock.isFresh());
        Ran plan = new Ran();
        assertFalse("a fresh read must not be asked for again",
                    PlannerStock.hold(plan, true));
        assertTrue(plan.ran);
    }

    @Test
    public void aBulkCountSurvivesTheWireAndReachesTheScenario() {
        // End to end through the join, on the value most likely to be quietly narrowed: this
        // pack's reference network holds 71.8 million of some items, and an `int` anywhere on
        // the path wraps negative and reads as owning none.
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("minecraft:cobblestone", Long.valueOf(3_000_000_000L));
        PlannerStock.accept(StockSnapshot.of(counts).serializeNBT());
        PlannerStock.install();
        assertEquals(3_000_000_000L,
                     PlannerService.liveScenario().getAsJsonObject("have")
                                   .get("minecraft:cobblestone").getAsLong());
    }

    /** A plan that records whether it was run. */
    private static final class Ran implements Runnable {

        boolean ran;

        @Override
        public void run() {
            ran = true;
        }
    }
}
