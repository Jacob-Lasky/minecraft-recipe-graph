package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * The cost-driven half of recipe choice, which every test in {@link SolverTest} leaves inert.
 *
 * WHY THAT MATTERS ENOUGH FOR ITS OWN FILE. `Solver.slotCost` returns 0.0 when no
 * {@link CostTable} is supplied, deliberately, so that a Solver built without one behaves
 * exactly as it did before cost became a factor. Every other test in this package is built
 * that way -- which means every cost tiebreak, the `cheap` term that DOMINATES
 * `score_recipe`, and the fluid scaling are unexercised by all of them. A port could have the
 * cost path wired backwards and pass the entire rest of the suite.
 *
 * The {@link CostTable} here is hand-built. That is not the same as being held to `cost.py`'s
 * numbers -- the relaxation that produces a real table is graphmodel's half and the golden
 * fixtures are what will check it. What these assert is the SOLVER's use of a table: that
 * cost outranks the local signals, that infinity does not win, and that a fluid is priced by
 * the bucket.
 */
public class CostRankingTest {

    /**
     * A table over a fixed `{key id: cost}` map, everything else reading as infinity.
     *
     * Built through `CostTable`'s package-private constructor rather than through
     * `Cost.estimate`, on purpose: these tests are about what the SOLVER does with a set of
     * prices, so the prices have to be chosen rather than derived. Holding the relaxation's
     * output steady enough to isolate a tiebreak would be a much larger fixture and would be
     * testing graphmodel's half by accident.
     *
     * Every machine entry is NaN, which is `CostTable`'s way of saying "this table carries
     * none", sending `categoryEntryCost` to the flat `MACHINE_COST` figures.
     */
    private static CostTable table(RecipeGraph g, Map<Integer, Double> prices) {
        double[] cost = new double[g.keyCount()];
        java.util.Arrays.fill(cost, Double.POSITIVE_INFINITY);
        for (Map.Entry<Integer, Double> entry : prices.entrySet()) {
            if (entry.getKey() >= 0 && entry.getKey() < cost.length) {
                cost[entry.getKey()] = entry.getValue();
            }
        }
        double[] entries = new double[Math.max(g.categoryCount(), 1)];
        java.util.Arrays.fill(entries, Double.NaN);
        return new CostTable(cost, entries);
    }

    @Test
    public void costOutranksHowMuchOfARecipeIsAlreadyInStock() {
        // The ordering `score_recipe` calls out: `satisfied` and `simple` only break ties
        // between comparable routes. Promoting them above cost is what made the solver prefer
        // a million-bucket chain through a machine that happened to be owned.
        GraphBuilder b = new GraphBuilder();
        // One slot, fully in stock, but the ingredient is ruinous.
        recipe(b, "r:dear", "crafting_shaped", "mod:out", "mod:expensive", 1);
        // Two slots, nothing in stock, but cheap.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:cheap_a"));
        b.endSlot();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:cheap_b"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r:cheap", "crafting_shaped", null, "jar_json", false, false);
        RecipeGraph g = b.build();

        Map<Integer, Double> prices = new LinkedHashMap<Integer, Double>();
        prices.put(g.keyId("mod:expensive"), 9000.0);
        prices.put(g.keyId("mod:cheap_a"), 1.0);
        prices.put(g.keyId("mod:cheap_b"), 1.0);
        Map<Integer, Long> have = new LinkedHashMap<Integer, Long>();
        have.put(g.keyId("mod:expensive"), 64L);

        PlanResult plan = new Solver.Builder(g).costs(table(g, prices)).have(have).build()
                .solve(g.keyId("mod:out"), 1);
        assertEquals("cost must beat a fully-stocked but ruinous route",
                "r:cheap", plan.tree.recipe);
    }

    @Test
    public void aRouteThroughAnUnpricedIngredientLosesToAPricedOne() {
        // An unpriced key reads as infinity, the recipe prices at infinity, and `cheap`
        // becomes negative infinity -- so the comparison falls through to the later terms
        // rather than the route winning on a missing number. A CostTable answering 0.0 for a
        // missing key would make every unpriced route the cheapest thing in the graph.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:unpriced", "crafting_shaped", "mod:out", "mod:mystery", 1);
        recipe(b, "r:priced", "crafting_shaped", "mod:out", "mod:known", 1);
        RecipeGraph g = b.build();

        Map<Integer, Double> prices = new LinkedHashMap<Integer, Double>();
        prices.put(g.keyId("mod:known"), 500.0);   // dear, but finite

        PlanResult plan = new Solver.Builder(g).costs(table(g, prices)).build()
                .solve(g.keyId("mod:out"), 1);
        assertEquals("r:priced", plan.tree.recipe);
    }

    @Test
    public void aFluidIsPricedByTheBucketAndNotByTheMillibucket() {
        // 1000 mB of water would otherwise look a thousand times dearer than one item, and
        // scaling only one side of the arithmetic once made every fluid-to-fluid hop divide
        // the cost by 1000 -- a ten-hop chain priced at 1e-30 and the table discriminated
        // between nothing.
        GraphBuilder b = new GraphBuilder();
        int water = b.key("fluid:water");
        int ingot = b.key("mod:ingot");
        RecipeGraph g = b.build();

        Map<Integer, Double> prices = new LinkedHashMap<Integer, Double>();
        prices.put(water, 1.0);
        prices.put(ingot, 1.0);
        CostTable costs = table(g, prices);

        assertEquals("1000 mB at cost 1.0 is one bucket, so 1.0",
                1.0, Cost.inputCost(costs, g, water, 1000), 1e-12);
        assertEquals("1000 items at cost 1.0 is 1000",
                1000.0, Cost.inputCost(costs, g, ingot, 1000), 1e-12);
        // A sub-millibucket quantity must not divide by ~0 and manufacture a free resource.
        assertTrue(Cost.inputCost(costs, g, water, 0) > 0.0);
    }

    @Test
    public void aQuantityPastIntRangeIsNotNarrowedOnTheWayToTheCostTable() {
        // `resolve_ore` prices a member at the computed NEED, which multiplies down a chain,
        // and this pack has a recipe yielding 60,466,176 at once. The solver briefly clamped
        // to Integer.MAX_VALUE because `inputCost` took an int; python has no int ceiling, so
        // that agreed with the oracle only below 2^31 and any fixture crossing it would have
        // diverged with nothing in the diff to say why.
        GraphBuilder b = new GraphBuilder();
        int ingot = b.key("mod:ingot");
        RecipeGraph g = b.build();
        Map<Integer, Double> prices = new LinkedHashMap<Integer, Double>();
        prices.put(ingot, 1.0);
        CostTable costs = table(g, prices);

        long past = (long) Integer.MAX_VALUE + 1000L;
        double priced = Cost.inputCost(costs, g, ingot, past);
        assertEquals("a narrowed quantity would come out negative or clamped",
                (double) past, priced, 0.0);
        assertTrue("and it must exceed what Integer.MAX_VALUE would have priced",
                priced > (double) Integer.MAX_VALUE);
    }

    @Test
    public void anOredictSlotIsPricedAtItsCheapestMember() {
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("ingotCopper");
        b.oreMember(b.key("modA:copper"));
        b.oreMember(b.key("modB:copper"));
        b.endOreGroup();
        int ore = b.key("ore:ingotCopper");
        RecipeGraph g = b.build();

        Map<Integer, Double> prices = new LinkedHashMap<Integer, Double>();
        prices.put(g.keyId("modA:copper"), 40.0);
        prices.put(g.keyId("modB:copper"), 3.0);
        assertEquals(3.0, Cost.inputCost(table(g, prices), g, ore, 1), 1e-12);
    }

    @Test
    public void anUnpricedOredictGroupFallsBackToTheRawLeafPrice() {
        // Not to infinity: an oredict group nothing prices is still a thing you can probably
        // get, and pricing it at infinity would make every recipe using it unreachable.
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("ingotUnknown");
        b.oreMember(b.key("modA:unknown"));
        b.endOreGroup();
        int ore = b.key("ore:ingotUnknown");
        RecipeGraph g = b.build();

        assertEquals(Cost.BASE_RAW_COST,
                Cost.inputCost(table(g, new LinkedHashMap<Integer, Double>()), g, ore, 1), 1e-12);
    }

    @Test
    public void aContainerTransferLosesToAnyRealRecipeHoweverCheapItLooks() {
        // A transfer MOVES a fluid rather than creating it. Treating it as production makes
        // every fluid free to anyone who owns a tank, so it is ranked last outright rather
        // than merely penalised.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:full_can"));
        b.endSlot();
        b.output(b.key("mod:goo"), 1);
        b.endRecipe("r:empty_the_can", "container", null, "hei_dump", true, false);
        recipe(b, "r:make", "mixer", "mod:goo", "mod:reagent", 1);
        RecipeGraph g = b.build();

        Map<Integer, Double> prices = new LinkedHashMap<Integer, Double>();
        prices.put(g.keyId("mod:full_can"), 0.001);   // absurdly cheap
        prices.put(g.keyId("mod:reagent"), 900.0);    // absurdly dear

        PlanResult plan = new Solver.Builder(g).costs(table(g, prices)).build()
                .solve(g.keyId("mod:goo"), 1);
        assertEquals("r:make", plan.tree.recipe);
    }

    private static void recipe(GraphBuilder b, String rid, String category, String output,
                               String input, int qty) {
        b.beginRecipe();
        b.beginSlot(qty, "item");
        b.alternative(b.key(input));
        b.endSlot();
        b.output(b.key(output), 1);
        b.endRecipe(rid, category, null, "jar_json", false, false);
    }
}
