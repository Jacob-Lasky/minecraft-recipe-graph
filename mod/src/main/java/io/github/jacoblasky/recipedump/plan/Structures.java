package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.Multiblocks;

/**
 * What a Modular Machinery structure costs to place, and how much of it cannot be. Ports the
 * two functions `cost.py` reads out of `recipegraph/multiblocks.py`.
 *
 * WHY A MULTIBLOCK NEEDS ITS OWN PRICING AT ALL. An MM machine's controller recipe is a
 * blueprint plus a blank controller -- two items -- for a machine of up to 8,813 placed
 * blocks that appears in no recipe. Priced by the controller alone, every multiblock in the
 * pack sits at the floor of the buildable band. See #93.
 */
public final class Structures {

    private Structures() {
    }

    /**
     * What one block POSITION costs: its cheapest acceptable alternative, or +inf.
     *
     * ONE definition, read by {@link #structureCost} and by {@link #blockedFraction}. They
     * held a copy each for one commit in python, which is a standing invitation to disagree
     * about which positions are blocked -- and the two are used TOGETHER, on the same
     * structure, to produce a price and the ordinal that ranks it. A position the sum thinks
     * is affordable and the fraction thinks is missing would be a contradiction nothing
     * reports.
     *
     * Cheapest alternative, matching how a machine with several candidate items is priced: if
     * any acceptable block is affordable, that is what a player would use.
     */
    public static double positionCost(Multiblocks machines, int part, CostTable cost) {
        double best = Double.POSITIVE_INFINITY;
        for (int p = machines.partAltStart(part); p < machines.partAltEnd(part); p++) {
            double candidate = cost.cost(machines.partAltKeyAt(p));
            if (candidate < best) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * What placing this multiblock costs. +inf when it cannot be placed at all.
     *
     * AN UNREACHABLE COMPONENT MEANS THE MACHINE CANNOT BE BUILT, so the answer is +inf
     * rather than the sum of the parts that happen to price. Skipping those positions would
     * report the Dyson Extruder as merely expensive when 6,456 of its blocks are galaxy
     * conduits with no obtainable recipe, which is the same underpricing this exists to fix.
     * 155 of the reference pack's 259 machines are in that state, which matches the
     * observation that these multiblocks are frequently unobtainable rather than merely dear.
     *
     * {@link #blockedFraction} is how the blocked ones are told apart without weakening this.
     */
    public static double structureCost(Multiblocks machines, int machine, CostTable cost) {
        double total = 0.0;
        for (int part = machines.partStart(machine); part < machines.partEnd(machine); part++) {
            double best = positionCost(machines, part, cost);
            if (Double.isInfinite(best)) {
                return Double.POSITIVE_INFINITY;
            }
            total += best * machines.partCount(part);
        }
        return total;
    }

    /**
     * Share of this structure's block POSITIONS with no PRICED candidate, in [0, 1].
     *
     * {@link #structureCost} answers "can this be placed at all" and deliberately collapses
     * to +inf the moment one position is unsatisfiable -- which is right, and #95 is what it
     * cost: every blocked machine reached the ranker as one indistinguishable number, so a
     * structure missing 3 of its 2,125 positions priced identically to one where all 135 are
     * missing. This is the ORDINAL that separates them, and it is deliberately NOT a cost: a
     * partial sum would say "merely expensive" about a machine that cannot be built.
     *
     * POSITIONS, NOT PART GROUPS. The parts list collapses 69,181 positions into ~2,229
     * groups, so counting groups would weigh one missing galaxy conduit the same as 6,456 of
     * them. The grouping is a storage optimisation and must not become the unit of judgement.
     *
     * A structure with no parts at all is 0.0: nothing is missing from nothing. That agrees
     * with {@link #structureCost}, which prices an empty structure at 0.0 rather than +inf.
     *
     * "PRICED", NOT "OBTAINABLE", and the wording is a correction rather than a nicety. This
     * reads the cost table, so a position is blocked when no candidate has a finite price --
     * a weaker statement than the pack proving the block unobtainable. Measured: of 26,236
     * blocked positions, 25,109 (95.7%) are keys the pack DOES have a recipe for. That is why
     * the whole slice this feeds stays below the `unknown` machine cost.
     */
    public static double blockedFraction(Multiblocks machines, int machine, CostTable cost) {
        long total = 0;
        long bad = 0;
        for (int part = machines.partStart(machine); part < machines.partEnd(machine); part++) {
            total += machines.partCount(part);
            if (Double.isInfinite(positionCost(machines, part, cost))) {
                bad += machines.partCount(part);
            }
        }
        if (total == 0) {
            return 0.0;
        }
        return (double) bad / (double) total;
    }
}
