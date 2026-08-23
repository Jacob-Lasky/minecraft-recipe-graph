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

    /**
     * VOLATILE BECAUSE THE PLAN THREAD READS THEM. These are written on the client thread and
     * read by `PlannerService`'s worker through the `ScenarioSource.HAVE` reader (#191), which
     * is the same argument that made `ScenarioSource.reader` volatile. Thread.start() already
     * orders the usual case -- the client thread stores the snapshot and then starts the solve
     * -- but a reply landing DURING a solve has no such edge, and a plain field read there may
     * see the previous snapshot indefinitely.
     *
     * TWO FIELDS AND NOT ONE IMMUTABLE PAIR, unlike `JeiBridge.Indexed`, because the skew is
     * harmless here and there it was not. A reader seeing a new snapshot beside an old
     * timestamp decides the read is stale and asks again; one seeing the reverse plans against
     * a snapshot at most one round trip old. Neither is a wrong answer, where a graph beside
     * another graph's index resolved every key to the wrong item.
     */
    private static volatile StockSnapshot latest;
    private static volatile long receivedAt;

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

    /**
     * True when a snapshot has arrived, SUCCEEDED, and is recent enough to plan against.
     *
     * <h2>A REFUSAL IS NEVER FRESH, WHICH IS THE WHOLE OF #321 §3</h2>
     *
     * This used to check only `latest != null` and the window, and a refusal satisfies both. The
     * consequence was measurable and exactly backwards: plan, get `NO_TERMINAL`, pick up your
     * wireless terminal or walk into range, re-plan within thirty seconds -- and
     * `PlannerStock.hold` saw a fresh snapshot, skipped the ask, and planned stock-blind again
     * against the refusal the player had just fixed. The thirty-second window exists so that
     * opening the planner twice does not cost two reads of a megabyte item list; a refusal costs
     * nothing to re-ask, and its cause is precisely the thing the player has gone away to change.
     *
     * SUCCESS FRESHNESS IS UNCHANGED at thirty seconds. This is a narrowing of what counts as
     * fresh, not a shortening of the window: the case #321 is about is the one where re-asking is
     * free and the held answer is worthless.
     *
     * NOTE WHAT THIS DOES **NOT** DO. It does not discard the refusal --
     * {@link PlannerStock#statusOf} still reports the reason to the player, and must, because
     * "your terminal is out of power" is a lower bound they can act on while an empty `have` is a
     * confident falsehood. This decides only whether to ASK AGAIN, which is the distinction
     * `PlannerStock`'s own javadoc draws about staleness.
     */
    public static boolean isFresh() {
        StockSnapshot held = latest;
        return held != null && held.isAvailable()
                && System.currentTimeMillis() - receivedAt < FRESH_FOR_MILLIS;
    }

    /**
     * Test seam: forget everything, so one test cannot leak into the next.
     *
     * PUBLIC RATHER THAN PACKAGE PRIVATE SINCE #321, and the reason is a package boundary rather
     * than a widening of what this is for. The held snapshot now decides what `PlanCaveats` puts on
     * the always-visible caveat line, and `PlanCaveats` lives in `client.planner` -- so the test
     * that has to establish "no snapshot at all" is in a different package from the one holding it.
     * The alternative was `accept(null)`, which spells the same reset as a delivery.
     *
     * PRODUCTION MUST NEVER CALL THIS. Clearing a snapshot is not a state the game has: a read
     * either succeeded, refused, or has not happened, and the last of those is the state BEFORE the
     * first ask rather than one to return to. A production caller would hand the planner a
     * nobody-has-asked-yet caveat over a read that DID happen, which is #321 §1's defect inverted.
     * `SeamInstallationTest.noProductionClassCallsATestOnlySeam` is the gate that says so -- built
     * because an earlier version of this paragraph claimed a gate that did not exist.
     */
    public static void forget() {
        latest = null;
        receivedAt = 0L;
    }
}
