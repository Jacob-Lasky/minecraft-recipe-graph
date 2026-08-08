package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Inputs a run does NOT spend, the Java half of #175.
 *
 * The dump's input-stack schema had no field for consumption, so every input read as spent: a
 * Deep Mob Learning data model that sits in the machine forever was bought once per run, and a
 * Spinel Ring plan asked for 64 when the answer is one.
 *
 * THE PYTHON SIDE IS THE SPECIFICATION AND THIS MUST AGREE WITH IT, because the golden plan
 * fixtures compare the two byte for byte. `tests/test_consumption.py` is the same set of
 * assertions against `recipegraph`; if one of these changes, that one changes with it.
 *
 * NOT CALLED `CatalystTest`. This port already means the JEI machine BLOCK by "catalyst", in
 * `RecipeGraph.catalysts` and six other files, and a non-consumed input is unrelated to that.
 */
public class RetainedInputTest {

    /** One recipe: the shard is retained, the material is spent. */
    private static RecipeGraph forgeGraph(double shardChance) {
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:widget"), "Widget");
        b.name(b.key("mod:shard"), "Shard");
        b.name(b.key("mod:material"), "Material");
        b.beginRecipe();
        b.beginSlot(1, "item", shardChance);
        b.alternative(b.key("mod:shard"));
        b.endSlot();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:material"));
        b.endSlot();
        b.output(b.key("mod:widget"), 1);
        b.endRecipe("forge", "mod.forge", "Forge", "hei_dump", false, false);
        return b.build();
    }

    private static Map<String, PlanNode> childrenByKey(PlanNode tree) {
        Map<String, PlanNode> out = new HashMap<String, PlanNode>();
        for (PlanNode child : tree.children()) {
            out.put(child.key(), child);
        }
        return out;
    }

    @Test
    public void theDefaultIsFullyConsumedSoAnOldGraphIsUnchanged() {
        // The property that let this land before the mod change: an absent `p` is 1.0, so every
        // graph any existing dump can produce behaves exactly as it did.
        RecipeGraph g = forgeGraph(1.0);
        assertFalse("a default slot must not read as retained",
                g.recipes().slotSurvivesRun(g.recipes().slotStart(0)));
        assertEquals(1.0, g.recipes().slotConsumeChance(g.recipes().slotStart(0)), 0.0);
    }

    @Test
    public void theTwoArgumentBeginSlotStillMeansFullyConsumed() {
        // `beginSlot(qty, role)` is the default spelled once. If it ever started meaning 0.0,
        // every slot of every existing graph would become a permanent requirement at once.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "minecraft.crafting", null, "jar_json", false, false);
        RecipeGraph g = b.build();
        assertEquals(1.0, g.recipes().slotConsumeChance(0), 0.0);
        assertFalse(g.recipes().slotSurvivesRun(0));
    }

    @Test
    public void aRetainedSlotDoesNotScaleWithRuns() {
        RecipeGraph g = forgeGraph(0.0);
        PlanResult plan = new Solver.Builder(g).build().solve(g.keyId("mod:widget"), 64);
        Map<String, PlanNode> kids = childrenByKey(plan.tree);
        assertEquals("64 runs need the same one shard, not 64 of them",
                1L, kids.get("mod:shard").need());
        assertEquals("the consumed input must still scale with runs",
                64L, kids.get("mod:material").need());
    }

    @Test
    public void theRowSaysItIsNotConsumed() {
        RecipeGraph g = forgeGraph(0.0);
        PlanResult plan = new Solver.Builder(g).build().solve(g.keyId("mod:widget"), 64);
        Map<String, PlanNode> kids = childrenByKey(plan.tree);
        assertTrue(kids.get("mod:shard").notConsumed());
        assertFalse(kids.get("mod:material").notConsumed());
    }

    @Test
    public void aConsumedInputIsUnchangedByAllOfThis() {
        // The control, so the test above cannot pass by shrinking every slot to its base qty.
        RecipeGraph g = forgeGraph(1.0);
        PlanResult plan = new Solver.Builder(g).build().solve(g.keyId("mod:widget"), 64);
        Map<String, PlanNode> kids = childrenByKey(plan.tree);
        assertEquals(64L, kids.get("mod:shard").need());
        assertEquals(64L, kids.get("mod:material").need());
        assertFalse(kids.get("mod:shard").notConsumed());
    }

    @Test
    public void mergingKeepsTWOFRACTIONALChancesOfOneItemApart() {
        // THE CASE THE 0.0-VERSUS-1.0 TEST BELOW CANNOT REACH. #175 packed the bucket key as
        // 32 bits of item id plus the 32 bits of a float chance, which was lossless while the
        // chance was a float; #223 widened it to a double, and any fold back down to 32 bits
        // would let two DIFFERENT chances collide and be summed -- the exact defect the
        // bucket was widened to fix, arriving through the hash instead of through the key.
        // 0.95 and 0.5 are both real `setChance` values in the reference pack.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:a"), "A");
        b.beginRecipe();
        b.beginSlot(2, "item", 0.95);
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.beginSlot(3, "item", 0.5);
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();

        List<Solver.MergedSlot> slots = new Solver.Builder(g).build().mergeSlots(0);
        assertEquals("two chances of one item are two requirements", 2, slots.size());
        assertEquals(2L, slots.get(0).qty);
        assertEquals(0.95, slots.get(0).consumeChance, 0.0);
        assertEquals(3L, slots.get(1).qty);
        assertEquals(0.5, slots.get(1).consumeChance, 0.0);
    }

    @Test
    public void mergingSTILLCollapsesTwoSlotsThatShareAChance() {
        // The control for the test above, and the case the merge exists for: a 3x3 of one
        // ingredient is nine slots and one row. A bucket that had become unique per slot
        // would pass the split assertions above while destroying the collapse.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.beginRecipe();
        for (int i = 0; i < 9; i++) {
            b.beginSlot(1, "item", 0.95);
            b.alternative(b.key("mod:a"));
            b.endSlot();
        }
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();

        List<Solver.MergedSlot> slots = new Solver.Builder(g).build().mergeSlots(0);
        assertEquals("nine slots sharing a chance are one row", 1, slots.size());
        assertEquals(9L, slots.get(0).qty);
    }

    @Test
    public void mergingKeepsARetainedSlotApartFromASpentOneOfTheSameItem() {
        // Own one AND spend five is not six spent. Bucketing on the item alone fused these and
        // summed their quantities, turning the retained one into stock you spend.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:a"), "A");
        b.beginRecipe();
        b.beginSlot(1, "item", 0.0);
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.beginSlot(5, "item");
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();

        Solver solver = new Solver.Builder(g).build();
        List<Solver.MergedSlot> slots = solver.mergeSlots(0);
        assertEquals("the two requirements must not merge", 2, slots.size());
        long retained = 0;
        long spent = 0;
        for (Solver.MergedSlot slot : slots) {
            if (slot.survivesRun()) {
                retained += slot.qty;
            } else {
                spent += slot.qty;
            }
        }
        assertEquals(1L, retained);
        assertEquals(5L, spent);
    }

    @Test
    public void slotsThatShareAChanceStillCollapse() {
        // The case `mergeSlots` exists for: nine cells of one ingredient are one row. Bucketing
        // on the chance as well must not break that, or the node cap goes back to being spent
        // on duplicate subtrees.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:panel"), "Panel");
        b.beginRecipe();
        for (int i = 0; i < 9; i++) {
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:panel"));
            b.endSlot();
        }
        b.output(b.key("mod:out"), 1);
        b.endRecipe("compress", "minecraft.crafting", null, "jar_json", false, false);
        RecipeGraph g = b.build();

        List<Solver.MergedSlot> slots = new Solver.Builder(g).build().mergeSlots(0);
        assertEquals(1, slots.size());
        assertEquals(9L, slots.get(0).qty);
    }

    @Test
    public void aRetainedInputDoesNotAmortiseAwayOverABigBatch() {
        // `base` is what running the recipe costs at all and does not divide by the batch;
        // dividing a permanent requirement by it says a big enough output makes it free, which
        // is the error the amortisation comment in `Cost` was written about for machines. The
        // pack has a recipe yielding 60,466,176 fruit.
        double one = contribution(0.0, 1);
        double many = contribution(0.0, 4096);
        assertTrue("the shard has to cost something to begin with: " + one, one > 0.0);
        assertEquals("a retained input's contribution must not shrink with the batch",
                one, many, 1e-9);
    }

    @Test
    public void aConsumedInputDoesAmortise() {
        // The mirror image, and it is what proves the test above measures anything: the same
        // slot at the default chance must have its contribution collapse over 4096.
        double one = contribution(1.0, 1);
        double many = contribution(1.0, 4096);
        assertTrue(one > 0.0);
        assertTrue("a consumed input must amortise: " + one + " at 1, " + many + " at 4096",
                many < one / 100.0);
    }

    @Test
    public void theRankerChargesAFractionalInputAtItsFraction() {
        // `Cost.recipeCost` is a SECOND pricing path, used by the solver's own ranking, and the
        // review pass found it had not been taught the chance at all. A ranker that prices a
        // route differently from the relaxation is the divergence #29 is about.
        double full = runCost(1.0);
        double quarter = runCost(0.25);
        assertEquals("a quarter-consumed input must cost a quarter per run",
                Cost.BASE_RAW_COST * 0.75, full - quarter, 1e-6);
    }

    @Test
    public void theRankerChargesARetainedInputInFullRatherThanScalingItToZero() {
        // The one place scaling by `p` would be wrong: this prices ONE RUN, and you must own
        // the shard before the forge runs. Scaling to zero would tell the ranker a recipe
        // needing an unobtainable permanent input is the cheapest available, which is #176's
        // defect through the ranking door.
        assertEquals(runCost(1.0), runCost(0.0), 1e-6);
        assertTrue("a retained input must still cost what it costs",
                runCost(0.0) >= Cost.BASE_RAW_COST - 1e-9);
    }

    /**
     * `Cost.recipeCost` for a one-slot recipe, in the idiom `CostTest` already uses: estimate a
     * real table first, then rank against it, so the price charged is the one the relaxation
     * produced rather than a number invented here.
     *
     * The shard is a leaf with no producer, so it is seeded at `BASE_RAW_COST` whatever the
     * chance is, which is what makes the two calls comparable.
     */
    private static double runCost(double chance) {
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:shard"), "Shard");
        b.beginRecipe();
        b.beginSlot(1, "item", chance);
        b.alternative(b.key("mod:shard"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("forge", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();
        CostTable table = Cost.estimate(g, new CostInputs());
        return Cost.recipeCost(table, g, 0, null, null);
    }

    /**
     * What the shard slot alone adds to the price of `mod:out`, isolated by removing it.
     *
     * MEASURED AS A DIFFERENCE AND NOT AS A TOTAL, because the total is the wrong instrument
     * and the first version of this test used it. The recipe also holds a CONSUMED input which
     * genuinely amortises, so the total legitimately falls as the batch grows: this test failed
     * with "22.0 -> 21.000244140625" and the code was right. Any tolerance wide enough to
     * accept that drop is wide enough to accept the retained term vanishing as well.
     */
    private static double contribution(double chance, int batch) {
        return priceOfOut(chance, batch, true) - priceOfOut(chance, batch, false);
    }

    /** The price of `mod:out`, with or without the shard slot in front of the material. */
    private static double priceOfOut(double chance, int batch, boolean withShard) {
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:shard"), "Shard");
        b.name(b.key("mod:material"), "Material");
        b.beginRecipe();
        if (withShard) {
            b.beginSlot(1, "item", chance);
            b.alternative(b.key("mod:shard"));
            b.endSlot();
        }
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:material"));
        b.endSlot();
        b.output(b.key("mod:out"), batch);
        b.endRecipe("forge", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();
        CostTable table = Cost.estimate(g, new CostInputs());
        return table.cost(g.keyId("mod:out"));
    }
}
