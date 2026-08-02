package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.github.jacoblasky.recipedump.common.net.PlanBookNetwork;
import io.github.jacoblasky.recipedump.common.net.StockRequestMessage;

/**
 * The last stock snapshot the server sent, and the ask that gets a fresh one.
 *
 * WHY THE CLIENT HOLDS ONE AT ALL, rather than the planner reading the grid itself: the grid
 * only exists on the server. Every read is a round trip, so the answer has to land somewhere
 * between the reply arriving and the planner looking at it.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * No polling, no subscription, no refresh timer. AE2's own terminal streams deltas because it
 * is a window the player leaves open; a plan is one question asked at one moment. Adding a
 * tick handler here would put a megabyte of item list on the wire repeatedly to keep a number
 * fresh that nobody is looking at.
 *
 * No merging either -- see `ClientProxy.applyStockSnapshot`. A snapshot is what the network
 * held at an instant.
 *
 * <h2>The staleness rule</h2>
 *
 * A snapshot has no expiry and is NOT cleared on a timer, but {@link #isFresh} exists so a
 * caller can decline to plan against an old one rather than doing it unknowingly. The planner
 * should {@link #request} when it opens and plan when the reply lands; the held value is for
 * the frames in between and for a second plan in the same sitting.
 */
public final class LiveStock {

    /**
     * How long a snapshot is treated as describing the network now.
     *
     * Thirty seconds is a judgement, not a measurement: long enough that opening the planner
     * twice in a row does not cost two reads, short enough that a plan cannot be priced
     * against a base the player has since emptied into a machine. Nothing breaks at the
     * boundary -- it only decides whether {@link #isFresh} suggests asking again.
     */
    private static final long FRESH_FOR_MILLIS = 30_000L;

    private static StockSnapshot latest;
    private static long receivedAt;

    private LiveStock() {
    }

    /** Ask the server to read the network. The reply arrives asynchronously. */
    public static void request() {
        PlanBookNetwork.CHANNEL.sendToServer(new StockRequestMessage());
    }

    /** Called from the client thread when a reply lands. */
    public static void accept(StockSnapshot snapshot) {
        latest = snapshot;
        receivedAt = System.currentTimeMillis();
    }

    /**
     * The last snapshot, or null if none has arrived this session.
     *
     * NULL AND A REFUSAL ARE DIFFERENT ANSWERS. Null means nobody has asked yet; a refusal
     * means the server answered and said why not. A caller that treats them alike will report
     * "no network in range" before it has looked.
     */
    public static StockSnapshot latest() {
        return latest;
    }

    /** True when a snapshot has arrived and is recent enough to plan against. */
    public static boolean isFresh() {
        return latest != null && System.currentTimeMillis() - receivedAt < FRESH_FOR_MILLIS;
    }

    /** Test seam: forget everything, so one test cannot leak into the next. */
    static void forget() {
        latest = null;
        receivedAt = 0L;
    }
}
