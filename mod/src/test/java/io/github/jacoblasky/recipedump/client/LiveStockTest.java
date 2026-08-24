package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static io.github.jacoblasky.recipedump.common.ae2.Snapshots.NO_MACHINES;

import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The client's held snapshot, and the three states a caller has to keep apart.
 *
 * "Nobody has asked yet", "the server said no and why", and "here is what you own" are three
 * different things, and the first two are the pair that gets conflated. A planner that treats
 * a null as a refusal reports "your network is not loaded" before it has looked -- which is a
 * confident answer to a question nobody put. (It quoted "no network in range" until #331 deleted
 * the range gate; a class note that quotes a sentence the mod cannot produce is a standing
 * invitation to conclude that gate is still there.)
 */
public class LiveStockTest {

    @Before
    @After
    public void forget() {
        // Static state, so one case must not leak into the next.
        LiveStock.forget();
    }

    @Test
    public void nothingAskedIsNotTheSameAsAnswerNo() {
        assertNull(LiveStock.latest());
        assertFalse(LiveStock.isFresh());

        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_POWER));
        // Now there IS an answer, and it is a refusal -- distinguishable from the null above.
        assertFalse(LiveStock.latest().isAvailable());
        assertEquals(StockSnapshot.Reason.NO_POWER, LiveStock.latest().reason());
        // AND IT IS STILL NOT FRESH, which this case used to assert the opposite of. See
        // `aRefusalIsNeverFreshSoTheNextPlanAsksAgain` for why the flip is the fix and not a
        // loosening: the two states differ in `latest()`, which is what this case is about, and
        // they agree about freshness because neither is worth planning against without re-asking.
        assertFalse(LiveStock.isFresh());
    }

    /**
     * WITNESS. A refusal is never fresh, so the plan after it asks again. #321 §3
     *
     * ON MASTER THIS FAILS, and the failure is a measured wrong answer rather than a tidiness
     * complaint. `isFresh` checked only `latest != null` and the thirty-second window, and a
     * refusal satisfies both -- so: plan, get NO_TERMINAL, pick up your wireless terminal, re-plan
     * within thirty seconds, and `PlannerStock.hold` saw a fresh snapshot, skipped the ask, and
     * planned stock-blind against the refusal you had just fixed. The window exists so that opening
     * the planner twice does not cost two reads of a megabyte item list; re-asking after a refusal
     * costs a packet carrying one enum value.
     *
     * EVERY REFUSAL, NOT THE ONE I THOUGHT OF. `NO_TERMINAL` is the one the issue was reported
     * from, but the argument is about refusals as a class -- and `NO_REPLY` in particular MUST be
     * unfresh or a server that was briefly slow would stay written off for thirty seconds.
     */
    @Test
    public void aRefusalIsNeverFreshSoTheNextPlanAsksAgain() {
        int checked = 0;
        for (StockSnapshot.Reason reason : StockSnapshot.Reason.values()) {
            if (reason == StockSnapshot.Reason.OK) {
                continue;
            }
            LiveStock.accept(StockSnapshot.unavailable(reason));
            assertFalse(reason + " counts as fresh, so re-planning will not re-ask",
                        LiveStock.isFresh());
            // The refusal is still HELD -- this narrows what counts as fresh, it does not discard
            // the answer. `PlannerStock.statusOf` needs the reason to tell the player.
            assertEquals(reason, LiveStock.latest().reason());
            checked++;
        }
        assertTrue("no refusal was checked, so this asserted nothing", checked > 0);
    }

    /**
     * And a SUCCESSFUL read still is, which is the half that must not change.
     *
     * THE CONTROL FOR THE WITNESS ABOVE. "A refusal is not fresh" implemented as "nothing is
     * fresh" would pass that test and would reintroduce the cost the window exists to avoid: two
     * reads of a megabyte item list every time the planner is opened twice.
     */
    @Test
    public void aSuccessfulReadIsStillFreshForTheFullWindow() {
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)), NO_MACHINES));
        assertTrue("success freshness was narrowed along with refusal freshness",
                   LiveStock.isFresh());
    }

    @Test
    public void aFreshReadIsPlannableAndIsHeldWhole() {
        StockSnapshot snapshot = StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)), NO_MACHINES);
        LiveStock.accept(snapshot);
        assertSame(snapshot, LiveStock.latest());
        assertTrue(LiveStock.isFresh());
        assertEquals(64L, LiveStock.latest().count("minecraft:stone"));
    }

    @Test
    public void aSecondReadReplacesTheFirstRatherThanMergingIntoIt() {
        // A snapshot is what the network held at an instant. Merging would invent a state it
        // was never in, and the item that quietly persisted is the one already spent.
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)), NO_MACHINES));
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:dirt", Long.valueOf(1L)), NO_MACHINES));

        assertEquals(0L, LiveStock.latest().count("minecraft:stone"));
        assertEquals(1L, LiveStock.latest().count("minecraft:dirt"));
        assertEquals(1, LiveStock.latest().distinctKeys());
    }

    @Test
    public void aRefusalReplacesAGoodReadRatherThanLeavingTheOldOneStanding() {
        // The important direction. If the network goes unloaded, the planner must stop
        // planning against what it used to hold -- keeping the last good read is exactly the
        // stale-file behaviour this whole phase exists to retire.
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)), NO_MACHINES));
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_POWER));

        assertFalse(LiveStock.latest().isAvailable());
        assertEquals(0L, LiveStock.latest().count("minecraft:stone"));
    }
}
