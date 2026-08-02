package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
 * a null as a refusal reports "no network in range" before it has looked -- which is a
 * confident answer to a question nobody put.
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

        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        // Now there IS an answer, and it is a refusal -- distinguishable from the null above.
        assertFalse(LiveStock.latest().isAvailable());
        assertEquals(StockSnapshot.Reason.OUT_OF_RANGE, LiveStock.latest().reason());
        assertTrue(LiveStock.isFresh());
    }

    @Test
    public void aFreshReadIsPlannableAndIsHeldWhole() {
        StockSnapshot snapshot = StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)));
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
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L))));
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:dirt", Long.valueOf(1L))));

        assertEquals(0L, LiveStock.latest().count("minecraft:stone"));
        assertEquals(1L, LiveStock.latest().count("minecraft:dirt"));
        assertEquals(1, LiveStock.latest().distinctKeys());
    }

    @Test
    public void aRefusalReplacesAGoodReadRatherThanLeavingTheOldOneStanding() {
        // The important direction. If the network goes out of range, the planner must stop
        // planning against what it used to hold -- keeping the last good read is exactly the
        // stale-file behaviour this whole phase exists to retire.
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L))));
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));

        assertFalse(LiveStock.latest().isAvailable());
        assertEquals(0L, LiveStock.latest().count("minecraft:stone"));
    }
}
