package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.Csr;
import io.github.jacoblasky.recipedump.graph.Multiblocks;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.graph.RecipeStore;
import java.util.Arrays;
import java.util.Map;

/**
 * Estimated cost to obtain each item, so recipe choice can stop being greedy. Ports
 * `recipegraph/cost.py`.
 *
 * WHY THIS EXISTS. Scoring a recipe by local properties alone -- do I own the machine, are
 * its inputs in stock, how few inputs does it have -- cannot see what a branch costs further
 * down. Two observed failures, both from real runs: without machine gating, "64 Borax" routed
 * through Chaos Fragments and Nether Stars; with gating but still greedy, it preferred an
 * enormous chain through machines it owned (11,000,000 mB of water, bacteria vectors, Pink
 * Tulips) over a two-step chemical route through a Crystallizer it merely had to build. Both
 * are the same mistake: a locally attractive recipe whose subtree is ruinous.
 *
 * THE ESTIMATE IS A RANKING, NOT A PLAN. It ignores cycles, assumes inputs can be had
 * independently, and never double-counts shared intermediates. DO NOT surface these numbers
 * to users as quantities.
 *
 * It is deliberately NOT a lower bound, and that distinction is the fix for #29. A true lower
 * bound amortises everything over the output quantity, and the pack contains recipes that
 * output 1,024 iron ingots or 60,466,176 fruit at once; amortising the machine over those
 * makes it free and prices the output at nothing. Cost of ENTRY is charged per run and only
 * the ingredients amortise, so a route cannot be made cheap by being enormous.
 *
 * <h2>Exact reproduction of the python arithmetic is a requirement, not an aspiration</h2>
 *
 * `tests/fixtures/plan/` freezes prices computed in python and this side is asserted against
 * them. IEEE 754 binary64 is the same in both languages -- measured, 0 of 6,012 doubles
 * differ in value -- so exact agreement is achievable and anything less is a defect.
 *
 * Three rules keep it:
 *
 * <ul>
 * <li>Everything is {@code double}. Never {@code float}, never a widened {@code int}
 *     division.</li>
 * <li>OPERATION ORDER IS PRESERVED. {@code a+b+c} is not {@code a+c+b}, so where python folds
 *     over a collection this folds over the same collection in the same order. That is the
 *     same insertion-order constraint the graph model documents, with a second reason.</li>
 * <li>Every {@code min} keeps the FIRST extremum, because python's {@code min} does. Strict
 *     {@code &lt;}, never {@code &lt;=}.</li>
 * </ul>
 *
 * {@code log1p} is the only transcendental here and it was the one real risk, since Java does
 * not require it to be correctly rounded. MEASURED across 6,011 values spanning the build-cost
 * range: {@code Math.log1p} and {@code StrictMath.log1p} both agree with python bit for bit,
 * on Java 8 and on 25. {@code strictfp} on this class documents the intent and is a no-op on
 * the SSE2 path every supported JVM uses.
 */
public strictfp final class Cost {

    /**
     * What a machine costs to route through, indexed by the {@link MachineInfo} state.
     *
     * Owning it is nearly free; building one is a real but one-off expense; using one you
     * cannot get should lose to almost anything.
     *
     * `unknown` SITS BETWEEN BUILDABLE AND UNAVAILABLE ON PURPOSE. It means the category's
     * machine could not be identified, which is a gap in this tool and not a fact about the
     * player's base, so it must not be priced as unusable -- doing that put 40% of the
     * reference pack's recipes behind a 5,000 wall. It must also not undercut `buildable`, or
     * an unidentified machine would beat one the player can demonstrably build.
     */
    public static final double[] MACHINE_COST = {1.0, 40.0, 120.0, 5000.0};

    /**
     * `buildable` above is a FLOOR, not the whole answer.
     *
     * Two machines you have to build are not equally expensive, and on the reference pack
     * they are not remotely equally expensive: over the 380 buildable categories whose
     * machine item prices finitely, the build cost runs min 1.0, median 2.0, p90 67, max
     * 9,288 -- a 4,644x spread. Priced by the flat constant alone, an AE2 grindstone and a
     * fusion reactor both charge 40.0 and the ranker cannot prefer the one the player can
     * actually reach. That is #86.
     *
     * So a buildable machine's entry cost spans `[MACHINE_COST[BUILDABLE], PRICED_CEILING)`,
     * ordered by what building it costs. The map is bounded and monotonic rather than the raw
     * price, for two reasons:
     *
     * <ul>
     * <li>THE CEILING MUST STAY BELOW `unknown`. An unidentified machine outranking one the
     *     player can demonstrably build is the failure the `unknown` figure exists to avoid.
     *     Feeding the raw 9,288 in would sail past `unknown` AND past `unavailable`, making a
     *     buildable machine read as worse than a proven-impossible one.</li>
     * <li>THE FLOOR MUST STAY AT `buildable`. The have-vs-buildable gap is what the Borax and
     *     Crystallizer cases turn on; cheapening buildable machines across the board would
     *     relitigate a decision #86 is not about. It asks to tell two buildable machines
     *     apart, not to make building cheaper.</li>
     * </ul>
     *
     * BUILD_SCALE sets where the curve bends. At 64 it lands near p90 of the measured
     * distribution, so the mass of the pack spreads across the low end of the band instead of
     * compressing into the first percent of it.
     *
     * THE CURVE IS LOGARITHMIC IN THE BUILD COST, and #93 is why. It was `b / (b + SCALE)`,
     * calibrated for the range a machine ITEM's recipe spans and saturating hard above it.
     * Pricing an MM machine by its structure widened the input range by three decades, to
     * 279,861, and 42 of the 104 fully-priced multiblocks came out within 1.0 of the ceiling
     * -- flattened against each other, which is exactly the defect #86 removed, only among
     * the expensive machines instead of all of them.
     *
     * BUILD_SLOPE IS WHAT KEEPS THE LOW END WHERE #86 MEASURED IT. Making buildable machines
     * DEARER would relitigate the Crystallizer case just as surely as cheapening them. Near
     * b=0 the curve is `SLOPE * b / SCALE`, so the SLOPE is the calibrated quantity and the
     * spread is free to move as long as the knee follows. #93 measured the tolerance: a build
     * cost of 1.0 prices at 41.21 against the old 41.22, agreeing within 0.05 across the mass
     * of the pack. ANY CHANGE TO THE SLOPE MOVES THAT WHOLE LOW END; re-measure before
     * touching it.
     */
    public static final double BUILD_SLOPE = 79.0;
    public static final double BUILD_SCALE = 64.0;
    public static final double BUILD_SPREAD = 70.0;
    public static final double BUILD_KNEE = BUILD_SPREAD / BUILD_SLOPE;

    /**
     * The region between the priced band and `unknown` is NOT spare room. It is three claims
     * that used to share one number, which is #95.
     *
     * Everything a price could not be computed for landed on the single band ceiling, and on
     * the reference pack that was 140 of 403 categories -- 35% of them -- holding two
     * unrelated statements and destroying the ordering among both: 117 MM categories whose
     * structure needs a block nothing makes (running from 0.14% of positions blocked to
     * 100%, all charging 119.000), and 23 categories whose machine ITEM never priced. What
     * that cost in practice: `aoa3:holly_top_petals` had a blocked MM route beat a Phytogenic
     * Insolator, both at 119.000, by 0.037 of an ingredient point -- a tie between two
     * different failures, broken by noise.
     *
     * So they get separate slices, ordered by how strong the claim is, every boundary DERIVED
     * from the two anchors rather than typed in:
     *
     * <pre>
     *   have 1.0 &lt; priced [40.0, 110.0) &lt; unpriced item 111.0
     *            &lt; blocked structure [112.0, 119.0] &lt; unknown 120.0 &lt; unavailable 5000.0
     * </pre>
     *
     * THE UNPRICED ITEM SITS BELOW THE BLOCKED STRUCTURE ON PURPOSE, and that ordering is the
     * one thing here not to swap. "This model failed to compute a number" is a weaker claim
     * than "the pack says this needs a block nothing makes", so the failure has to be the
     * more optimistic of the two.
     *
     * AND THE WHOLE BLOCKED SLICE STAYS BELOW `unknown`, WHICH LOOKS BACKWARDS AND IS NOT. A
     * structure proven to need an unobtainable block sounds like the stronger claim. DO NOT
     * promote it: the blockage signal has a history of being WRONG in ways that read exactly
     * like a proof. #100 audited it -- of 26,236 blocked positions, 25,109 (95.7%) are keys
     * the pack DOES have a recipe for, across 190 of the 250 distinct blocking keys. Only
     * 1,127 positions (4.3%) are "nothing makes it". So the blocked slice is overwhelmingly
     * reporting THIS MODEL'S COVERAGE, not the pack's content. WHAT WOULD ACTUALLY MOVE IT is
     * pricing those chains, not reclassifying them.
     */
    public static final double PRICED_CEILING = MACHINE_COST[MachineInfo.BUILDABLE]
            + BUILD_SPREAD;
    public static final double UNPRICED_MACHINE_COST = PRICED_CEILING + 1.0;
    public static final double BLOCKED_FLOOR = UNPRICED_MACHINE_COST + 1.0;
    public static final double BLOCKED_CEILING = MACHINE_COST[MachineInfo.UNKNOWN] - 1.0;

    /**
     * Used only when machine gating is off entirely, where every category gets the same
     * figure and the value is arbitrary. NOT the cost of an unidentified machine -- that is
     * {@code MACHINE_COST[UNKNOWN]}.
     */
    public static final double UNGATED_MACHINE_COST = 20.0;

    /**
     * A fluid quantity is in mB, so 1000 mB of water would otherwise look a thousand times
     * dearer than one item. One normalised unit is one item OR one bucket.
     *
     * THIS MUST BE APPLIED TO OUTPUTS TOO. Scaling only the input side made every fluid-to-
     * fluid hop divide the cost by 1000, so a chain ten hops deep priced at 1e-30. Every
     * fluid in the reference pack converged to 0.0 and the cost model stopped discriminating
     * between routes at all -- the greedy behaviour it exists to prevent, hidden behind a
     * cost table that looked populated. Go through {@link #scaledQty} for both directions.
     */
    public static final double FLUID_SCALE = 1.0 / 1000.0;

    /** An item with no recipe: assume it can be obtained somehow. */
    public static final double BASE_RAW_COST = 1.0;
    /** Container fill/empty is not production; never prefer it. */
    public static final double TRANSFER_PENALTY = 500.0;

    /**
     * What a PLACEHOLDER costs, by what it asks of the player, indexed by {@link Tokens}.
     *
     * Until #105 the cost model never mentioned tokens, so every one fell through the generic
     * leaf rule and a locked quest chapter cost what a cobblestone costs. The error ran ONE
     * WAY -- a gated route was always at least as cheap as the ungated one beside it -- so
     * gated routes were systematically preferred.
     *
     * THE ORDERING IS THE CLAIM, NOT THE MAGNITUDES. Four properties:
     *
     * <ol>
     * <li>GATE &gt; LOOT &gt; BASE_RAW_COST. Unlocking a chapter is a bigger ask than farming
     *     a boss, which is bigger than picking something up.</li>
     * <li>GATE &gt; MACHINE_COST[UNKNOWN]. A lock you cannot open yet is a worse obstacle than
     *     a machine this tool merely failed to identify, so an ungated route through ANY
     *     machine wins against a gated one.</li>
     * <li>Both &lt; MACHINE_COST[UNAVAILABLE]. A gate is not an impossibility -- chapters
     *     unlock and bosses die -- so a gated route must stay FINITE and still be chosen when
     *     it is the only one. That is the whole difference from the 5,000 wall.</li>
     * <li>LOOT != GATE. #95 is the lesson: one shared number for two unrelated statements
     *     destroys the ordering among both.</li>
     * </ol>
     *
     * HINT and METHOD deliberately stay at `BASE_RAW_COST`. Neither is a thing to obtain: a
     * HINT says the recipe accepts any member of a class, so it stands in for one ordinary
     * material, and a METHOD says the work happens in a machine, which the entry cost has
     * already charged for. Pricing either as an obstacle would double-count.
     */
    public static final double LOOT_COST = 200.0;
    public static final double GATE_COST = 1000.0;

    /**
     * What MINING costs when the ore only generates somewhere you have never been. #112.
     *
     * A DISTINCT NUMBER FROM GATE_COST, and #95 is why it has to be. A locked chapter is a
     * lock and nothing you build opens it ahead of the story; a dimension is a construction
     * project -- a rocket, a suit, fuel -- so it is something you can decide to go and do this
     * afternoon. That makes it the SMALLER ask:
     *
     * <pre>
     *   BASE_RAW_COST &lt; LOOT_COST &lt; DIMENSION_COST &lt; GATE_COST &lt; MACHINE_COST[UNAVAILABLE]
     * </pre>
     *
     * THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE. Whether a Sedna trip is 800 afternoons or
     * 8 is not knowable from the graph, and no one has measured it.
     */
    public static final double DIMENSION_COST = 800.0;

    /**
     * What a transmutation costs, for an item the ProjectE network has learned. #50.
     *
     * BETWEEN STOCK AND A RAW LEAF, and both bounds are the claim. Above stock and above a
     * generator's source cost, because EMC is finite and fungible: it is spent, a collector
     * has to earn it back, and a route that burns it is not as good as one drawing on a pile
     * you already have. Below BASE_RAW_COST, because the alternative to transmuting a learned
     * item is farming the dungeon it drops in, and a player with a working network does not
     * do that.
     *
     * <pre>
     *   0.0 (stock)  &lt;  SOURCE_COST  &lt;  EMC_COST  &lt;  BASE_RAW_COST
     * </pre>
     *
     * NOT SCALED BY THE ITEM'S EMC VALUE, and that was considered. A Nether Star is 139,264
     * EMC and a cobblestone is 1, so scaling looks obviously right -- and it would make the
     * number a QUANTITY, which everything here says these are not. Feeding a five-order-of-
     * magnitude spread in would let one expensive transmutation outrank an `unavailable`
     * machine wall.
     */
    public static final double EMC_COST = 0.5;

    /**
     * What a key the graph has PROVEN it cannot explain costs. #176.
     *
     * {@link Unsourced#keys} is the set: nothing makes this exact key, and the graph
     * demonstrably makes another form of it. Until #176 those seeded at
     * {@link #BASE_RAW_COST} like any other leaf -- the CHEAPEST value in the model -- so
     * the solver actively PREFERRED routes through items it had already badged
     * "no known source".
     *
     * THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE:
     *
     * <pre>
     *   BASE_RAW_COST &lt; LOOT_COST &lt; DIMENSION_COST &lt; GATE_COST &lt; UNSOURCED_COST
     *                 &lt; MACHINE_COST[UNAVAILABLE]
     * </pre>
     *
     * Above GATE_COST because the claims differ in kind: a locked chapter is a lock with a
     * key somewhere in the story, while an unsourced item is one the TOOL cannot explain at
     * all, on positive evidence rather than silence. Below the 5,000 wall because that means
     * "you cannot have this machine", and #95's lesson is that two unrelated statements must
     * never share a figure.
     *
     * MEASURED INERT ACROSS THE BAND: over eight targets on the reference graph, 200 and
     * 2,000 give byte-identical plans, 5,000 diverges on one and is slightly worse there,
     * and infinity strands 2,372 currently-priced keys while every finite candidate strands
     * zero. Mirrors `cost.UNSOURCED_COST` in python; the golden gate holds them equal.
     */
    public static final double UNSOURCED_COST = 2000.0;

    /**
     * What one unit from an infinite generator costs the ranker.
     *
     * FREE IS NOT ZERO. At zero the ranker cannot see quantity and will cheerfully plan a
     * swimming pool of water; a small POSITIVE cost keeps the ordering intact -- any generator
     * output beats any crafted route, and among generator outputs less still beats more. DO
     * NOT set this to 0.
     */
    public static final double SOURCE_COST = 0.02;

    /**
     * Bellman-Ford needs one pass per edge in the longest useful path.
     *
     * MeatballCraft's chemistry runs 10+ hops deep, so 6 passes left the deep end of every
     * chain unpriced. Measured: the last item gets a price at pass 12 and nothing new appears
     * after 20, while relaxation over cycles keeps making sub-percent improvements forever --
     * hence a ceiling AND an early exit, not just one of them.
     */
    public static final int PASSES = 20;
    /** Stop when a pass improves under 0.2% of recipes' outputs. */
    public static final double SETTLED_FRACTION = 0.002;

    /** Which alternative the CALLER will actually expand, per input slot. */
    public interface AlternativePicker {
        /** @return the chosen alternative's key id for `slot` */
        int pick(int slot);
    }

    private Cost() {
    }

    /**
     * Entry cost for a machine you must build, ordered by what building it costs.
     *
     * An UNREACHABLE machine item (+inf, which 23 buildable categories on the reference pack
     * have: a producer exists but its own inputs never price) charges
     * {@link #UNPRICED_MACHINE_COST}. NOT `unavailable`: the state was decided on evidence,
     * and a price this model failed to compute is a gap in the pricing, not a fact about the
     * base -- charging 5,000 would override an evidence-based verdict with a numerical
     * failure. Nor the top of the reserved region, which is where a structure proven
     * unbuildable goes.
     */
    public static double buildEntryCost(double buildCost) {
        if (Double.isInfinite(buildCost) || Double.isNaN(buildCost)) {
            return UNPRICED_MACHINE_COST;
        }
        double b = Math.max(0.0, buildCost);
        double span = Math.log1p(b / BUILD_SCALE);
        return MACHINE_COST[MachineInfo.BUILDABLE] + BUILD_SPREAD * (span / (span + BUILD_KNEE));
    }

    /**
     * Entry cost for a multiblock the pack says needs a block nothing in the graph makes.
     *
     * The blocked FRACTION mapped linearly onto `[BLOCKED_FLOOR, BLOCKED_CEILING]`, so the
     * whole slice stays above every priced machine and above an unpriced machine item, while
     * the 117 categories inside it stop being one number.
     *
     * LINEAR, not the logarithmic curve {@link #buildEntryCost} uses, because this is not a
     * cost. A fraction is already bounded and already uniform over its range; there is no
     * long tail to compress and nothing to calibrate against, so a curve here would be
     * decoration implying a precision the ordinal does not have.
     *
     * An out-of-range or missing fraction CLAMPS rather than raising: this decides a ranking,
     * and a machine vanishing from the band because its structure parsed oddly would be worse
     * than one ranked at the wrong end of a slice every member of which is unbuildable.
     */
    public static double blockedEntryCost(double fraction) {
        if (Double.isNaN(fraction)) {
            return BLOCKED_CEILING;
        }
        double f = Math.min(1.0, Math.max(0.0, fraction));
        return BLOCKED_FLOOR + (BLOCKED_CEILING - BLOCKED_FLOOR) * f;
    }

    /**
     * Quantity in normalised units: 1 item, or 1 bucket of fluid.
     *
     * A `long`, NOT an `int`, and the reason is the solver rather than any recipe. A slot's
     * own quantity is small, but `Solver.slot_cost` passes a computed NEED -- the amount a
     * plan wants after multiplying down a chain -- and this pack has a recipe yielding
     * 60,466,176 at once. An int parameter makes a deep chain wrap NEGATIVE, and
     * `Math.max(qty, 1)` then reports 1, pricing the dearest slot in a plan as the cheapest.
     *
     * Widening also keeps the arithmetic identical to python's, which has no int ceiling: a
     * `long` converts to `double` exactly below 2^53 and rounds the same way beyond it.
     * Saturating at `Integer.MAX_VALUE` instead would agree with python only up to 2^31.
     */
    static double scaledQty(RecipeGraph graph, int keyId, long qty) {
        double q = Math.max(qty, 1L) * (graph.isFluid(keyId) ? FLUID_SCALE : 1.0);
        // A sub-millibucket output would divide by ~0 and manufacture a free resource.
        return Math.max(q, FLUID_SCALE);
    }

    /**
     * Cheapest cost of satisfying one ingredient slot with `keyId`, at `qty`.
     *
     * Public because the solver needs it too: its slot-cost tie-break is exactly this number,
     * and a second implementation over there would be a second place for the normalisation --
     * oredict members, fluid scale -- to drift.
     */
    public static double inputCost(CostTable cost, RecipeGraph graph, int keyId, long qty) {
        double best;
        if (graph.isOre(keyId)) {
            best = Double.POSITIVE_INFINITY;
            Csr members = graph.oreMembers();
            int group = graph.oreGroupOfKey(keyId);
            if (group >= 0) {
                for (int p = members.start(group); p < members.end(group); p++) {
                    double candidate = cost.cost(members.at(p));
                    if (candidate < best) {
                        best = candidate;
                    }
                }
            }
            if (Double.isInfinite(best)) {
                // The ore KEY's own price, which the leaf rule seeded, and only when no
                // member priced at all. Absent means BASE_RAW_COST here, not infinity --
                // one of the two places where "absent" and "+inf" differ.
                double own = cost.cost(keyId);
                best = Double.isInfinite(own) ? BASE_RAW_COST : own;
            }
        } else {
            best = cost.cost(keyId);
        }
        if (Double.isInfinite(best)) {
            return Double.POSITIVE_INFINITY;
        }
        return best * scaledQty(graph, keyId, qty);
    }

    /**
     * Which of an input slot's alternatives the ranker assumes you would use.
     *
     * ONE RULE, shared by the relaxation and the ranking, which used to hold two: the
     * relaxation compared alternatives at qty 1 and the ranking at the slot's real qty. Those
     * happen to agree, because the scale is linear in qty and the item/fluid ratio is
     * therefore the same at any quantity -- but they agreed by arithmetic ACCIDENT, not by
     * design, and "the relaxation priced one alternative and the ranking priced another" is a
     * bug nobody would find by reading either function alone.
     *
     * Returns the FIRST cheapest, matching python's `min`.
     */
    static int cheapestAlternative(CostTable cost, RecipeGraph graph, int slot) {
        RecipeStore recipes = graph.recipes();
        int from = recipes.altStart(slot);
        int to = recipes.altEnd(slot);
        if (from == to) {
            return -1;
        }
        int bestKey = recipes.altKeyAt(from);
        if (to - from == 1) {
            return bestKey;
        }
        int qty = recipes.slotQty(slot);
        double best = inputCost(cost, graph, bestKey, qty);
        for (int p = from + 1; p < to; p++) {
            int key = recipes.altKeyAt(p);
            double candidate = inputCost(cost, graph, key, qty);
            // Strictly less-than: python's `min` keeps the FIRST minimum, and with several
            // unreachable alternatives every candidate is +inf, so `<=` would silently take
            // the last one instead.
            if (candidate < best) {
                best = candidate;
                bestKey = key;
            }
        }
        return bestKey;
    }

    /**
     * What running a recipe in this category costs before any ingredient is counted.
     *
     * ONE definition, read by both the relaxation and the ranking. DO NOT inline the
     * MACHINE_COST lookup into either of them again: they held separate copies, so a change
     * to how a machine is priced silently applied to one and not the other, and the symptom
     * is a solver that expands a route the ranker did not price.
     */
    public static double categoryEntryCost(int categoryId, MachineStates states,
                                           CostTable table) {
        return entryCost(categoryId, states, table == null ? null : table.machineEntries());
    }

    private static double entryCost(int categoryId, MachineStates states,
                                    double[] machineEntry) {
        if (machineEntry != null && categoryId >= 0 && categoryId < machineEntry.length
                && !Double.isNaN(machineEntry[categoryId])) {
            return machineEntry[categoryId];
        }
        int state = states == null ? -1 : states.state(categoryId);
        return state < 0 ? UNGATED_MACHINE_COST : MACHINE_COST[state];
    }

    /**
     * {category: entry cost} for the categories whose machine has to be built.
     *
     * THE CHEAPEST CANDIDATE SETS THE PRICE: several blocks can open one category (smelting is
     * not only the furnace), and a player would build the cheapest that works, so pricing the
     * first listed would charge for a machine nobody would choose.
     *
     * A candidate that is a Modular Machinery controller is charged its recipe PLUS the
     * structure it stands for, because the recipe alone is a blueprint and a blank controller
     * while the machine is up to 8,813 placed blocks (#93). The two are ADDED rather than one
     * replacing the other: you need the controller AND the structure, and the controller is
     * not among the machinery file's own parts.
     *
     * THREE OUTCOMES PER CANDIDATE, not one number that might be infinite (#95): a price, a
     * structure the pack proves unbuildable, or a machine item this model could not price.
     * Which one a candidate reaches is decided here because this is the only place that has
     * both the structure and the item's price.
     */
    static double[] machineEntryCosts(RecipeGraph graph, Map<Integer, int[]> machineItems,
                                      CostTable cost) {
        double[] entry = new double[graph.categoryCount()];
        Arrays.fill(entry, Double.NaN);
        Multiblocks machines = graph.multiblocks();
        // controller key -> machine. LAST writer wins, matching python building a dict over
        // `multiblocks.values()`. A map of 259 entries rather than an array over 266,145 key
        // ids, which would cost a megabyte to answer a question about 0.1% of them.
        java.util.Map<Integer, Integer> byController =
                new java.util.HashMap<Integer, Integer>();
        for (int machine = 0; machine < machines.count(); machine++) {
            int controller = machines.controllerKeyId(machine);
            if (controller >= 0) {
                byController.put(Integer.valueOf(controller), Integer.valueOf(machine));
            }
        }
        for (Map.Entry<Integer, int[]> row : machineItems.entrySet()) {
            // THE MINIMUM IS TAKEN OVER ENTRY COSTS, NOT OVER RAW BUILD COSTS, because since
            // #95 two candidates for one category can fail in different ways and a raw +inf
            // no longer says which. For the all-priced case this is the same answer --
            // `buildEntryCost` is monotonic -- and where they differ it is the case the old
            // form could not express at all.
            double best = Double.POSITIVE_INFINITY;
            for (int key : row.getValue()) {
                double itemCost = cost.cost(key);
                Integer machine = byController.get(Integer.valueOf(key));
                int structure = machine == null ? -1 : machine.intValue();
                double priced;
                if (Double.isInfinite(itemCost)) {
                    priced = UNPRICED_MACHINE_COST;
                } else if (structure < 0) {
                    priced = buildEntryCost(itemCost);
                } else {
                    double placed = Structures.structureCost(machines, structure, cost);
                    priced = Double.isInfinite(placed)
                            ? blockedEntryCost(
                                    Structures.blockedFraction(machines, structure, cost))
                            : buildEntryCost(itemCost + placed);
                }
                if (priced < best) {
                    best = priced;
                }
            }
            entry[row.getKey().intValue()] =
                    best < Double.POSITIVE_INFINITY ? best : UNPRICED_MACHINE_COST;
        }
        return entry;
    }

    /**
     * {item key: estimated cost}. Lower is easier to get.
     *
     * With machine items supplied this runs the relaxation TWICE, and the second run is #86's
     * fix. A buildable machine's entry cost is what building it costs, which is itself a
     * number this function computes, so it cannot be known before the first run.
     *
     * NOTHING HERE IS CACHED, and that is a decision rather than an omission. Python
     * memoises this table to disk because a relaxation over the reference graph costs it 100
     * seconds; measured on the same graph this runs in 2.7, which is affordable once at
     * startup. ANYONE ADDING A CACHE MUST FINGERPRINT THE FORMULA ITSELF, not only the
     * inputs: a change to the arithmetic moves no input at all, so a warm table would go on
     * serving prices computed by the old formula forever -- and that failure reads as "the
     * fix did not work" rather than as a stale cache.
     *
     * TWO PASSES RATHER THAN RECOMPUTING ENTRY COSTS INSIDE THE LOOP, deliberately. The
     * relaxation only ever LOWERS a cost, so an entry price that RISES between passes --
     * which is exactly what happens when a machine's real cost replaces the optimistic flat
     * 40 -- never propagates: the cheap prices computed in pass 1 stick, and the result
     * silently depends on pass order. Seeding a second clean relaxation with entry costs
     * derived from the first is deterministic and says what it does.
     */
    public static CostTable estimate(RecipeGraph graph, CostInputs inputs) {
        CostInputs in = inputs == null ? new CostInputs() : inputs;
        double[] seed = seed(graph, in);
        double[] firstPass = settleReshaped(graph,
                relax(graph, seed.clone(), in.passes(), in.states(), null),
                in.passes(), in.states(), null);
        Map<Integer, int[]> machineItems = in.machineItems();
        if (machineItems == null || machineItems.isEmpty()) {
            return new CostTable(firstPass, null);
        }
        double[] entry = machineEntryCosts(graph, machineItems,
                new CostTable(firstPass, null));
        // A SECOND CLEAN RELAXATION FROM THE SAME SEED, not a continuation of the first.
        // Relaxation only ever lowers a cost, so the cheap prices pass one computed under the
        // optimistic flat entry would stick and the real entry costs would never propagate.
        double[] second = settleReshaped(graph,
                relax(graph, seed.clone(), in.passes(), in.states(), entry),
                in.passes(), in.states(), entry);
        return new CostTable(second, entry);
    }

    /**
     * Give back the leaf price to keys only a reshaping can make, when nothing can.
     *
     * RUNS AFTER RELAXATION BECAUSE IT CANNOT BE DECIDED BEFORE IT. Reshaped-only is
     * structural -- every producer is one arm of an expanded chisel table -- but whether the
     * group has any way IN is not: the anchor may have a producer that is itself unreachable,
     * two tables deep. #110 tried to answer it at build time from "does some member have a
     * producer" and left 168 keys unreachable that had been finite,
     * `bewitchment:coquina_shell` and `contenttweaker:stone_of_life_essence` among them.
     *
     * So the rule is stated where the answer exists. A reshaped-only key relaxation could not
     * reach means the graph knows how to CONVERT this material and no way to obtain any of
     * it, which is exactly what it knew before the table was expanded -- and back then the key
     * was a leaf. Restore that and relax again so the price propagates to whatever consumes
     * it.
     *
     * THIS CANNOT REINTRODUCE #110's UNDERCUT. It only ever fires on a key relaxation left at
     * infinity, so a variant with any real route keeps the price that route earned. The keys
     * it does fire on were priced at the leaf before #110 too, so the floor it restores is a
     * floor that already shipped.
     *
     * The stranded set is collected before anything is written, and every member gets the
     * SAME value, so the key-id order this walks in cannot affect the result -- which is what
     * makes it safe to iterate a bitset here while python iterates an unordered set.
     */
    static double[] settleReshaped(RecipeGraph graph, double[] cost, int passes,
                                   MachineStates states, double[] entry) {
        boolean stranded = false;
        for (int key = 0; key < cost.length; key++) {
            if (graph.isReshapedOnly(key) && Double.isInfinite(cost[key])) {
                stranded = true;
                break;
            }
        }
        if (!stranded) {
            return cost;
        }
        for (int key = 0; key < cost.length; key++) {
            if (graph.isReshapedOnly(key) && Double.isInfinite(cost[key])) {
                cost[key] = BASE_RAW_COST;
            }
        }
        return relax(graph, cost, passes, states, entry);
    }

    /** Starting costs, before any recipe is considered. Shared by both relaxation passes. */
    static double[] seed(RecipeGraph graph, CostInputs in) {
        double[] cost = new double[graph.keyCount()];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);

        // Anything in stock is free at the margin; that is what makes the solver prefer using
        // what you already own without needing a separate rule for it.
        for (int key : in.stockKeys()) {
            cost[key] = 0.0;
        }

        // An infinite generator's output is near-free but NOT free. At zero the ranker cannot
        // see quantity and will happily plan a swimming pool; at SOURCE_COST it still beats
        // every crafted route while preferring less over more.
        for (int key : in.freeSourceKeys()) {
            cost[key] = Math.min(cost[key], SOURCE_COST);
        }

        long[] gated = Bits.ofSize(graph.keyCount());
        for (int key : in.dimensionGatedKeys()) {
            Bits.set(gated, key);
        }

        // Every leaf -- a key no recipe outputs -- is cheap-ish.
        //
        // A LEAF BEHIND A DIMENSION IS NOT CHEAP, AND THIS RUNS FIRST, so the surcharge has to
        // be applied here too rather than only in the world-ore loop below. "No recipe, so
        // assume you can go and get one" is precisely the assumption #112 exists to qualify:
        // you cannot go and get it, you have never been where it is. Left to the loop below,
        // `min` would already be holding BASE_RAW_COST from this pass and would keep it --
        // the gate would compute, appear in the plan's note, and change no price at all.
        RecipeStore recipes = graph.recipes();
        for (int recipe = 0; recipe < recipes.count(); recipe++) {
            for (int slot = recipes.slotStart(recipe); slot < recipes.slotEnd(recipe); slot++) {
                for (int p = recipes.altStart(slot); p < recipes.altEnd(slot); p++) {
                    int alt = recipes.altKeyAt(p);
                    if (Double.isInfinite(cost[alt]) && graph.byOutput().count(alt) == 0) {
                        cost[alt] = BASE_RAW_COST
                                + (Bits.get(gated, alt) ? DIMENSION_COST : 0.0);
                    }
                }
            }
        }

        // AND EVERY WORLD ORE, WHETHER OR NOT SOMETHING PRODUCES IT. The loop above prices a
        // leaf as obtainable only when NO recipe outputs it, which quietly assumes the only
        // way to get a thing is to make it. That is false for the one class of key the graph
        // can positively identify as obtainable another way: the pack's own `ore*` oredict
        // registration, meaning a block you find in the ground and hit.
        //
        // `contenttweaker:sednanite_ore` is the case. Registered `oreSednanite`, and two
        // Plasmatic Condenser recipes also emit it wanting 160,000 mB of Dense Plasma each --
        // so it counted as produced, both routes priced at infinity, and an ore you MINE ended
        // up unreachable, which made every honest route to Sednanite Ingot invisible and sent
        // the planner down a nugget ladder instead. See #106.
        //
        // `min`, so this only ever LOWERS a price and can never overrule stock or a generator.
        // Mining is a CEILING on what an ore can cost, not a claim that mining is best: a
        // genuinely cheaper crafted route still wins, because relaxation lowers it further.
        for (int key = 0; key < cost.length; key++) {
            if (graph.isWorldOre(key)) {
                double floor = BASE_RAW_COST
                        + (Bits.get(gated, key) ? DIMENSION_COST : 0.0);
                cost[key] = Math.min(cost[key], floor);
            }
        }

        // AND EVERY KEY THE TRANSMUTATION NETWORK CAN MAKE, for the same reason and by the
        // same mechanism: it counts as produced, so the leaf rule never sees it, and its
        // recipes may all price at infinity while the player can simply make one.
        // `erebus:materials` is the reported case -- its only "recipe" is a pseudo-item saying
        // it drops in a dungeon, so the solver dead-ends there while the network already
        // makes it.
        for (int key : in.emcKeys()) {
            cost[key] = Math.min(cost[key], EMC_COST);
        }

        // THE KEYS THE GRAPH HAS PROVEN IT CANNOT EXPLAIN, the second seed that RAISES rather
        // than lowers. See UNSOURCED_COST. Until #176 these were BASE_RAW_COST like any other
        // leaf, so the solver preferred a route through an item it had already badged
        // "no known source" over any route it could account for.
        //
        // SAME GUARD AS THE TOKEN LOOP BELOW: anything already priced under a raw leaf is
        // stock, an infinite generator or a learned EMC item, and each is a stronger claim
        // about THIS world than a structural inference is.
        //
        // `max`, NOT ASSIGNMENT, AND DEFENSIVE RATHER THAN LOAD-BEARING TODAY. Measured on the
        // reference graph, all 47,674 of these keys hold exactly BASE_RAW_COST when this loop
        // runs, so the two produce identical tables and the count of keys where they differ is
        // 0. An earlier comment claimed a dimension-gated leaf "would be lowered by a bare
        // assignment"; that is backwards -- such a leaf sits at BASE_RAW_COST + DIMENSION_COST
        // = 801, BELOW UNSOURCED_COST, so an assignment raises it exactly as `max` does. Keep
        // `max` because it is what keeps the loop correct if DIMENSION_COST ever passes
        // UNSOURCED_COST, which nothing enforces. Mirrors the same note in `cost._seed`.
        //
        // BEFORE THE TOKENS, so a token wins: `expand` returns at the token branch before it
        // ever reaches the unsourced mark, so the price has to agree with the display about
        // which of the two answers a reader gets. Mirrors `cost._seed` in python.
        //
        // THE isInfinite BRANCH IS THE PYTHON DEFAULT ARGUMENT, NOT A JAVA CONVENIENCE, and it
        // is load-bearing for 39 keys. Python's `cost.get(key, BASE_RAW_COST)` returns the
        // default for a key the leaf rule never seeded -- one nothing consumes -- and then
        // WRITES it, taking it from infinite to UNSOURCED_COST. This array is pre-filled with
        // infinity, so reading an infinite slot as BASE_RAW_COST is what reproduces that
        // exactly. Drop it and those 39 keys stay infinite here while python prices them, and
        // the golden gate fails on a table difference rather than on a plan.
        long[] unsourced = Unsourced.keys(graph);
        for (int key = 0; key < cost.length; key++) {
            if (!Bits.get(unsourced, key)) {
                continue;
            }
            double current = Double.isInfinite(cost[key]) ? BASE_RAW_COST : cost[key];
            if (current < BASE_RAW_COST) {
                continue;
            }
            cost[key] = Math.max(current, UNSOURCED_COST);
        }

        // And LAST, the placeholders, because this is the one seed that RAISES a price. Every
        // rule above answers "how cheaply can this be had"; a token answers "what does the
        // player have to go and do", and the generic leaf rule has already given it 1.0.
        //
        // Skipped when something already priced it BELOW a raw leaf: that means stock or an
        // infinite generator, and either is a stronger claim about this world than a curated
        // list is.
        for (Map.Entry<Integer, Integer> token : in.tokens().entrySet()) {
            int key = token.getKey().intValue();
            double current = Double.isInfinite(cost[key]) ? BASE_RAW_COST : cost[key];
            if (current < BASE_RAW_COST) {
                continue;
            }
            cost[key] = tokenCost(token.getValue().intValue());
        }
        return cost;
    }

    static double tokenCost(int kind) {
        switch (kind) {
            case Tokens.LOOT:
                return LOOT_COST;
            case Tokens.GATE:
                return GATE_COST;
            default:
                // HINT and METHOD, both deliberately a raw leaf. See LOOT_COST's javadoc.
                return BASE_RAW_COST;
        }
    }

    /** One Bellman-Ford style relaxation over every recipe, mutating and returning `cost`. */
    static double[] relax(RecipeGraph graph, double[] cost, int passes, MachineStates states,
                          double[] entry) {
        RecipeStore recipes = graph.recipes();
        double[] machineCost = new double[graph.categoryCount()];
        boolean[] computed = new boolean[graph.categoryCount()];
        for (int recipe = 0; recipe < recipes.count(); recipe++) {
            int category = recipes.categoryId(recipe);
            if (!computed[category]) {
                computed[category] = true;
                machineCost[category] = entryCost(category, states, entry);
            }
        }

        // A VIEW OVER THE LIVE ARRAY, not a copy: the relaxation mutates `cost` in place and
        // every lookup inside the loop has to see the prices this pass has already lowered,
        // which is what makes it Bellman-Ford rather than one synchronous step.
        CostTable view = new CostTable(cost, null);
        int settled = Math.max(1, (int) (recipes.count() * SETTLED_FRACTION));
        for (int pass = 0; pass < passes; pass++) {
            int changed = 0;
            for (int recipe = 0; recipe < recipes.count(); recipe++) {
                boolean transfer = recipes.isTransfer(recipe);
                double base = machineCost[recipes.categoryId(recipe)];
                if (transfer) {
                    base += TRANSFER_PENALTY;
                }
                double ingredients = 0.0;
                // A RETAINED INPUT IS ECONOMICALLY A MACHINE, so it joins `base` and does NOT
                // amortise. #175, and it mirrors `cost.py:_relax` line for line: a slot the run
                // never spends is one you buy once and then run forever, so dividing it by the
                // batch is the identical error the amortisation comment below was written about
                // for machines. Not priced at zero either, or every route through one would be
                // the cheapest in the model and the solver would prefer machines whose retained
                // input the player cannot obtain.
                double retained = 0.0;
                boolean unreachable = false;
                for (int slot = recipes.slotStart(recipe); slot < recipes.slotEnd(recipe);
                        slot++) {
                    int alt = cheapestAlternative(view, graph, slot);
                    double c = alt < 0 ? Double.POSITIVE_INFINITY
                            : inputCost(view, graph, alt, recipes.slotQty(slot));
                    if (Double.isInfinite(c)) {
                        unreachable = true;
                        break;
                    }
                    // Left to right over the slots, in CSR order, because a+b+c is not a+c+b.
                    if (false) {
                        retained += c;
                    } else {
                        // A fractional chance genuinely amortises, at `p` of itself per run.
                        ingredients += c * recipes.slotConsumeChance(slot);
                    }
                }
                if (unreachable) {
                    continue;
                }
                for (int p = recipes.outputStart(recipe); p < recipes.outputEnd(recipe); p++) {
                    int key = recipes.outputKeyAt(p);
                    // A container transfer never makes its fluid cheaper: emptying a can you
                    // own is not production. Mirrors what the solver walks -- if these
                    // disagree the ranker prices a route the solver cannot take.
                    if (transfer && graph.isFluid(key)) {
                        continue;
                    }
                    // ONLY THE INGREDIENTS AMORTISE. `base` is what running this recipe costs
                    // you at all -- overwhelmingly the machine -- and dividing it by the batch
                    // says a big enough output makes the machine free. Not a small error: the
                    // pack has a recipe yielding 1,024 iron ingots and one yielding
                    // 60,466,176 fruit, so the 5,000 wall in front of an unavailable machine
                    // collapsed to 8e-5 and 126 items priced under 0.1. That is how "one iron
                    // ingot" came out as "smelt a Spawner Shard". See #29.
                    double perUnit = base + retained + ingredients
                            / scaledQty(graph, key, recipes.outputQtyAt(p));
                    if (perUnit < cost[key] - 1e-9) {
                        cost[key] = perUnit;
                        changed++;
                    }
                }
            }
            if (changed < settled) {
                break;
            }
        }
        return cost;
    }

    /**
     * Estimated cost of running one recipe once, given precomputed item costs.
     *
     * `pick` names, per slot, the option whoever is asking will ACTUALLY use; null means the
     * cheapest. THE SOLVER MUST PASS ITS OWN. Pricing a slot at its cheapest option and then
     * expanding a different one is how "1 Iron Ingot" became "cast 1,296 mB of molten iron":
     * that recipe's slot accepts a Block of Iron or a decorative Chisel block, the Chisel
     * block WAS a raw leaf, so the recipe priced at 2.0 and beat smelting an ore -- and then
     * the solver expanded the Block of Iron, because it is the one with a recipe. Nothing was
     * mispriced; the price was simply for a route nobody took. See #29.
     *
     * Past tense on the Chisel block because #110 expanded the chiselling tables and it now
     * prices one chisel ABOVE the Block of Iron. That removes this example's cheapest wrong
     * answer; it does NOT remove the need for `pick`. Any slot whose cheapest option is not
     * the one the caller will expand reproduces the same divergence, and the solver still
     * picks on grounds of its own -- stock, pins, ore-backing -- that the cheapest-alternative
     * default knows nothing about.
     */
    public static double recipeCost(CostTable cost, RecipeGraph graph, int recipeId,
                                    MachineStates states, AlternativePicker pick) {
        RecipeStore recipes = graph.recipes();
        // Off the TABLE, so the machine price charged here is the one the relaxation actually
        // used (#86). A table with no entry costs still works and still gets the flat
        // constants, which is what a caller with a hand-built table wants.
        double total = categoryEntryCost(recipes.categoryId(recipeId), states, cost);
        if (recipes.isTransfer(recipeId)) {
            total += TRANSFER_PENALTY;
        }
        for (int slot = recipes.slotStart(recipeId); slot < recipes.slotEnd(recipeId); slot++) {
            int alt = pick != null ? pick.pick(slot) : cheapestAlternative(cost, graph, slot);
            double best = alt < 0 ? Double.POSITIVE_INFINITY
                    : inputCost(cost, graph, alt, recipes.slotQty(slot));
            if (Double.isInfinite(best)) {
                return Double.POSITIVE_INFINITY;
            }
            total += best;
        }
        return total;
    }
}
