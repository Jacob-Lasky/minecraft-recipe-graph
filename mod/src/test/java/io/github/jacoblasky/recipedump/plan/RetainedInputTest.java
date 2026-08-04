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
    private static RecipeGraph forgeGraph(float shardChance) {
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
        RecipeGraph g = forgeGraph(1.0f);
        assertFalse("a default slot must not read as retained",
                g.recipes().slotSurvivesRun(g.recipes().slotStart(0)));
        assertEquals(1.0f, g.recipes().slotConsumeChance(g.recipes().slotStart(0)), 0.0f);
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
        assertEquals(1.0f, g.recipes().slotConsumeChance(0), 0.0f);
        assertFalse(g.recipes().slotSurvivesRun(0));
    }

    @Test
    public void aRetainedSlotDoesNotScaleWithRuns() {
        RecipeGraph g = forgeGraph(0.0f);
        PlanResult plan = new Solver.Builder(g).build().solve(g.keyId("mod:widget"), 64);
        Map<String, PlanNode> kids = childrenByKey(plan.tree);
        assertEquals("64 runs need the same one shard, not 64 of them",
                1L, kids.get("mod:shard").need());
        assertEquals("the consumed input must still scale with runs",
                64L, kids.get("mod:material").need());
    }

    @Test
    public void theRowSaysItIsNotConsumed() {
        RecipeGraph g = forgeGraph(0.0f);
        PlanResult plan = new Solver.Builder(g).build().solve(g.keyId("mod:widget"), 64);
        Map<String, PlanNode> kids = childrenByKey(plan.tree);
        assertTrue(kids.get("mod:shard").notConsumed());
        assertFalse(kids.get("mod:material").notConsumed());
    }

    @Test
    public void aConsumedInputIsUnchangedByAllOfThis() {
        // The control, so the test above cannot pass by shrinking every slot to its base qty.
        RecipeGraph g = forgeGraph(1.0f);
        PlanResult plan = new Solver.Builder(g).build().solve(g.keyId("mod:widget"), 64);
        Map<String, PlanNode> kids = childrenByKey(plan.tree);
        assertEquals(64L, kids.get("mod:shard").need());
        assertEquals(64L, kids.get("mod:material").need());
        assertFalse(kids.get("mod:shard").notConsumed());
    }

    @Test
    public void mergingKeepsARetainedSlotApartFromASpentOneOfTheSameItem() {
        // Own one AND spend five is not six spent. Bucketing on the item alone fused these and
        // summed their quantities, turning the retained one into stock you spend.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:a"), "A");
        b.beginRecipe();
        b.beginSlot(1, "item", 0.0f);
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
        double small = priceOfOut(0.0f, 1);
        double large = priceOfOut(0.0f, 4096);
        assertTrue("a retained input must not get cheaper with the batch: " + small + " -> "
                + large, large >= small - 1e-9);
    }

    @Test
    public void aConsumedInputStillAmortises() {
        // The other half, so the test above cannot pass by pricing everything into `base`.
        assertTrue(priceOfOut(1.0f, 4096) < priceOfOut(1.0f, 1));
    }

    /** The price of `mod:out` from a one-retained-one-spent recipe yielding `batch`. */
    private static double priceOfOut(float chance, int batch) {
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:out"), "Out");
        b.name(b.key("mod:shard"), "Shard");
        b.name(b.key("mod:material"), "Material");
        b.beginRecipe();
        b.beginSlot(1, "item", chance);
        b.alternative(b.key("mod:shard"));
        b.endSlot();
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
