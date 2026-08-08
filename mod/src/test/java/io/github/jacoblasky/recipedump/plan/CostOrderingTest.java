package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * THE ORDERING IS THE CLAIM, NOT THE MAGNITUDES -- asserted rather than left to the reader.
 *
 * Every constant in {@link Cost} carries a paragraph explaining what it is FOR, and almost
 * none of them carries an argument for its exact value: nobody has measured whether a Sedna
 * trip is 800 afternoons or 8. What IS defensible, and what the whole cost model rests on, is
 * how the numbers sit relative to one another. So that is what gets pinned.
 *
 * These mirror `tests/test_progression.py` and `tests/test_dimensions.py` on the python side
 * one for one. A change that moves a magnitude while preserving every ordering is a tuning
 * decision; one that reorders any pair here is a behaviour change, and it should have to
 * argue with a test rather than slip through as a number edit.
 */
public class CostOrderingTest {

    @Test
    public void aGateCostsMoreThanLootWhichCostsMoreThanARawLeaf() {
        // Unlocking a chapter is a bigger ask than farming a boss, which is a bigger ask than
        // picking something up.
        assertTrue(Cost.GATE_COST > Cost.LOOT_COST);
        assertTrue(Cost.LOOT_COST > Cost.BASE_RAW_COST);
    }

    @Test
    public void aGateOutweighsAnyMachineSoAnUngatedRouteAlwaysWins() {
        // A lock you cannot open yet is a worse obstacle than a machine this tool merely
        // failed to identify.
        assertTrue(Cost.GATE_COST > Cost.MACHINE_COST[MachineInfo.UNKNOWN]);
        assertTrue(Cost.GATE_COST > Cost.MACHINE_COST[MachineInfo.BUILDABLE]);
    }

    @Test
    public void aGateIsNotAnImpossibility() {
        // Chapters unlock and bosses die, so a gated route must stay FINITE and still be
        // chosen when it is the only one there is. That is the whole difference from the wall.
        assertTrue(Cost.GATE_COST < Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
        assertTrue(Cost.LOOT_COST < Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
        assertTrue(Cost.DIMENSION_COST < Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
    }

    @Test
    public void lootAndGateAreDifferentNumbers() {
        // #95's lesson: one shared number for two unrelated statements destroys the ordering
        // among both.
        assertNotEquals(Cost.LOOT_COST, Cost.GATE_COST, 0.0);
    }

    @Test
    public void aTripCostsMoreThanABossAndLessThanALockedChapter() {
        // A dimension is a construction project you can decide to do this afternoon; a locked
        // chapter is not, and nothing you build opens it ahead of the story.
        assertTrue(Cost.DIMENSION_COST > Cost.LOOT_COST);
        assertTrue(Cost.DIMENSION_COST < Cost.GATE_COST);
    }

    @Test
    public void aMachineYouCanBuildIsNotHarderThanAPortal() {
        // #248's BINDING constraint, and it is not LOOT_COST. The sweep found every positive
        // toll routing-equivalent across the probe set, so the magnitude is settled by the
        // ordering alone, and the tightest bound is the machine band: an ore you walk to
        // through a portal must not read as a bigger obstacle than a machine you have to
        // BUILD. At a toll of 100 the floor is 101 and this fails.
        assertTrue(Cost.BASE_RAW_COST + Cost.OVERWORLD_TOLL
                < Cost.MACHINE_COST[MachineInfo.BUILDABLE]);
    }

    @Test
    public void aPortalCostsMoreThanGoingOutsideAndFarLessThanABoss() {
        // #248. Above BASE_RAW_COST so the overworld ore wins OUTRIGHT rather than by dump
        // order, which is the entire fix; the whole tolled band below LOOT_COST so a rock
        // behind a portal never loses to farming a boss. The FLOOR is what has to be bounded,
        // so the sum is what is compared, not the term on its own.
        assertTrue(Cost.OVERWORLD_TOLL > 0.0);
        assertTrue(Cost.BASE_RAW_COST + Cost.OVERWORLD_TOLL < Cost.LOOT_COST);
        assertTrue(Cost.OVERWORLD_TOLL < Cost.DIMENSION_COST);
    }

    @Test
    public void aGateAndATollCanBothApplyAndStayFinite() {
        // The two terms coexist -- an ore in a dimension you have never been to pays for the
        // trip AND the portal -- so the SUM is what must still be a route the solver will take
        // when it is the only one.
        assertTrue(Cost.BASE_RAW_COST + Cost.DIMENSION_COST + Cost.OVERWORLD_TOLL
                < Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
    }

    @Test
    public void theTollIsItsOwnNumber() {
        // #95's lesson again: one figure for two unrelated statements destroys both orderings.
        assertNotEquals(Cost.OVERWORLD_TOLL, Cost.DIMENSION_COST, 0.0);
        assertNotEquals(Cost.OVERWORLD_TOLL, Cost.LOOT_COST, 0.0);
        assertNotEquals(Cost.OVERWORLD_TOLL, Cost.BASE_RAW_COST, 0.0);
    }

    @Test
    public void transmutingSitsBetweenStockAndGoingToFarmTheDungeon() {
        // Above a generator because EMC is spent and has to be earned back; below a raw leaf
        // because a player with a working network does not go and farm the drop.
        assertTrue(0.0 < Cost.SOURCE_COST);
        assertTrue(Cost.SOURCE_COST < Cost.EMC_COST);
        assertTrue(Cost.EMC_COST < Cost.BASE_RAW_COST);
    }

    @Test
    public void aGeneratorIsNearlyFreeAndNeverActuallyFree() {
        // At zero the ranker cannot see quantity and will plan a swimming pool of water.
        assertTrue(Cost.SOURCE_COST > 0.0);
    }

    @Test
    public void everyTokenKindHasAPriceAndNoPriceIsOrphaned() {
        assertEquals(Cost.LOOT_COST, Cost.tokenCost(Tokens.LOOT), 0.0);
        assertEquals(Cost.GATE_COST, Cost.tokenCost(Tokens.GATE), 0.0);
        // HINT stands in for one ordinary material; METHOD says the work happens in a
        // machine, which the entry cost has already charged for. Pricing either as an
        // obstacle would double-count.
        assertEquals(Cost.BASE_RAW_COST, Cost.tokenCost(Tokens.HINT), 0.0);
        assertEquals(Cost.BASE_RAW_COST, Cost.tokenCost(Tokens.METHOD), 0.0);
    }

    @Test
    public void everyTokenKindNameRoundTripsThroughItsConstant() {
        // The vocabulary a user's `tokens.json` is read with. A kind that parses to -1 falls
        // through to the raw-leaf price, so a typo there is a silently ungated route.
        for (int kind = 0; kind < Tokens.KIND_COUNT; kind++) {
            assertEquals(kind, Tokens.kindOf(Tokens.kindName(kind)));
        }
        assertEquals(-1, Tokens.kindOf("nonsense"));
        // An unrecognised kind is priced as an ordinary material rather than as an obstacle,
        // which is the safe direction: it under-gates rather than walling off a real route.
        assertEquals(Cost.BASE_RAW_COST, Cost.tokenCost(-1), 0.0);
    }

    @Test
    public void theMachineBandRunsHaveThenBuildableThenUnknownThenUnavailable() {
        assertTrue(Cost.MACHINE_COST[MachineInfo.HAVE]
                < Cost.MACHINE_COST[MachineInfo.BUILDABLE]);
        assertTrue(Cost.MACHINE_COST[MachineInfo.BUILDABLE]
                < Cost.MACHINE_COST[MachineInfo.UNKNOWN]);
        assertTrue(Cost.MACHINE_COST[MachineInfo.UNKNOWN]
                < Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
    }

    @Test
    public void theThreeSlicesUnderUnknownStayOrderedAndDoNotOverlap() {
        // have 1.0 < priced [40, 110) < unpriced item 111 < blocked [112, 119] < unknown 120
        assertTrue(Cost.MACHINE_COST[MachineInfo.BUILDABLE] < Cost.PRICED_CEILING);
        assertTrue(Cost.PRICED_CEILING < Cost.UNPRICED_MACHINE_COST);
        // THE UNPRICED ITEM SITS BELOW THE BLOCKED STRUCTURE ON PURPOSE, and this is the one
        // pair not to swap. "This model failed to compute a number" is a weaker claim than
        // "the pack says this needs a block nothing makes", so it has to be the more
        // optimistic of the two.
        assertTrue(Cost.UNPRICED_MACHINE_COST < Cost.BLOCKED_FLOOR);
        assertTrue(Cost.BLOCKED_FLOOR <= Cost.BLOCKED_CEILING);
        // AND THE WHOLE BLOCKED SLICE STAYS BELOW `unknown`, which looks backwards and is
        // not: 95.7% of blocked positions are keys the pack DOES have a recipe for, so the
        // slice mostly reports this model's coverage rather than the pack's content (#100).
        assertTrue(Cost.BLOCKED_CEILING < Cost.MACHINE_COST[MachineInfo.UNKNOWN]);
    }

    @Test
    public void everyBuildableMachinePricesInsideTheBandAndOrderedByWhatItCosts() {
        double floor = Cost.MACHINE_COST[MachineInfo.BUILDABLE];
        double previous = -1.0;
        // Spanning the measured range: min 1.0, median 2.0, p90 67, max 9,288 for a machine
        // ITEM, and up to 279,861 once a multiblock structure is added in.
        double[] buildCosts = {0.0, 1.0, 2.0, 67.0, 9288.0, 279861.0, 1e9};
        for (double build : buildCosts) {
            double entry = Cost.buildEntryCost(build);
            assertTrue("floor " + entry, entry >= floor);
            // Strictly BELOW the ceiling: feeding the raw cost in would sail past `unknown`
            // and past `unavailable`, making a buildable machine read as worse than a
            // proven-impossible one.
            assertTrue("ceiling " + entry, entry < Cost.PRICED_CEILING);
            assertTrue("monotonic " + entry, entry > previous);
            previous = entry;
        }
    }

    @Test
    public void aMachineItemNothingCanPriceIsNotTreatedAsUnavailable() {
        // The state was decided on EVIDENCE and a price this model failed to compute is a gap
        // in the pricing, not a fact about the base. Charging the wall would override an
        // evidence-based verdict with a numerical failure.
        assertEquals(Cost.UNPRICED_MACHINE_COST,
                Cost.buildEntryCost(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(Cost.UNPRICED_MACHINE_COST, Cost.buildEntryCost(Double.NaN), 0.0);
        assertTrue(Cost.buildEntryCost(Double.POSITIVE_INFINITY)
                < Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
    }

    @Test
    public void aBlockedStructureIsRankedByHowMuchOfItIsMissing() {
        // The 117 categories that used to share one number, separated. Linear, because a
        // fraction is already bounded and uniform -- a curve here would imply a precision the
        // ordinal does not have.
        assertEquals(Cost.BLOCKED_FLOOR, Cost.blockedEntryCost(0.0), 0.0);
        assertEquals(Cost.BLOCKED_CEILING, Cost.blockedEntryCost(1.0), 0.0);
        assertTrue(Cost.blockedEntryCost(0.0014) < Cost.blockedEntryCost(1.0));
        // Clamped rather than raising: a machine vanishing from the band because its
        // structure parsed oddly would be worse than one ranked at the wrong end of a slice
        // every member of which is unbuildable anyway.
        assertEquals(Cost.BLOCKED_FLOOR, Cost.blockedEntryCost(-5.0), 0.0);
        assertEquals(Cost.BLOCKED_CEILING, Cost.blockedEntryCost(5.0), 0.0);
        assertEquals(Cost.BLOCKED_CEILING, Cost.blockedEntryCost(Double.NaN), 0.0);
    }

    @Test
    public void theBandBoundariesAreDerivedFromTheAnchorsRatherThanTypedIn() {
        // If someone gives one of these a literal value, the derivation stops protecting the
        // ordering and this is where that shows up.
        assertEquals(Cost.MACHINE_COST[MachineInfo.BUILDABLE] + Cost.BUILD_SPREAD,
                Cost.PRICED_CEILING, 0.0);
        assertEquals(Cost.PRICED_CEILING + 1.0, Cost.UNPRICED_MACHINE_COST, 0.0);
        assertEquals(Cost.UNPRICED_MACHINE_COST + 1.0, Cost.BLOCKED_FLOOR, 0.0);
        assertEquals(Cost.MACHINE_COST[MachineInfo.UNKNOWN] - 1.0, Cost.BLOCKED_CEILING, 0.0);
        assertEquals(Cost.BUILD_SPREAD / Cost.BUILD_SLOPE, Cost.BUILD_KNEE, 0.0);
    }
}
