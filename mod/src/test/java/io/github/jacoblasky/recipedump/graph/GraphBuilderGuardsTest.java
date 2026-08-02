package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The builder's refusals, each of which replaces a SILENT corruption.
 *
 * Every case here is one where the flat-array layout would otherwise accept the mistake and
 * produce a graph that loads, indexes and plans -- just for the wrong ingredients. That is
 * the worst failure mode this package has, because nothing downstream can tell a
 * misaligned CSR from a pack that really is shaped that way. A thrown exception naming the
 * misuse is the cheapest possible alternative, so it is asserted rather than assumed.
 */
public class GraphBuilderGuardsTest {

    private static GraphBuilder oneOpenRecipe() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        return b;
    }

    @Test
    public void openingASecondSlotWithoutClosingTheFirstIsRefused() {
        // Two quantities pushed against one alternative offset misaligns every later slot,
        // pairing recipes with the wrong ingredients.
        GraphBuilder b = oneOpenRecipe();
        b.beginSlot(1, "item");
        try {
            b.beginSlot(1, "item");
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("endSlot"));
        }
    }

    @Test
    public void anAlternativeWithNoOpenSlotIsRefused() {
        GraphBuilder b = oneOpenRecipe();
        try {
            b.alternative(b.key("minecraft:stone"));
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no slot is open"));
        }
    }

    @Test
    public void closingARecipeWithASlotStillOpenIsRefused() {
        GraphBuilder b = oneOpenRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:stone"));
        try {
            b.endRecipe("r", "crafting", null, "jar_json", false, false);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("slot is still open"));
        }
    }

    @Test
    public void buildingWithARecipeStillOpenIsRefused() {
        GraphBuilder b = oneOpenRecipe();
        try {
            b.build();
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("recipe is still open"));
        }
    }

    @Test
    public void openingASecondOredictGroupWithoutClosingTheFirstIsRefused() {
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("ingotIron");
        try {
            b.beginOreGroup("ingotGold");
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("endOreGroup"));
        }
    }

    @Test
    public void aCatalystKeyWithNoOpenCategoryIsRefused() {
        GraphBuilder b = new GraphBuilder();
        try {
            b.catalystKey(b.key("minecraft:furnace"));
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("catalyst category"));
        }
    }

    @Test
    public void aByteColumnThatOverflowsSaysSoRatherThanTruncating() {
        // 300 distinct extractor sources will not happen, but the column is a byte and a
        // silent truncation would relabel source 256 as source 0 -- a wrong attribution that
        // reads as a data problem rather than an overflow.
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < 300; i++) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("minecraft:stone"));
            b.endSlot();
            b.output(b.key("mod:out" + i), 1);
            b.endRecipe("r" + i, "crafting", null, "source" + i, false, false);
        }
        try {
            b.build();
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("widen it"));
        }
    }

    @Test
    public void namingAKeyTwiceTakesTheSecondLabelInBothDirections() {
        // A junk label followed by a real one must not leave the key stuck on its registry
        // path, and a real label followed by junk must not leave the junk on screen. A
        // set-only flag gets the first of those wrong.
        GraphBuilder b = new GraphBuilder();
        b.name("mod:fixed", "tile.null.name");
        b.name("mod:fixed", "Proper Name");
        b.name("mod:broken", "Proper Name");
        b.name("mod:broken", "tile.null.name");
        RecipeGraph graph = b.build();

        assertEquals("Proper Name", graph.bareName(graph.keyId("mod:fixed")));
        assertEquals("Broken", graph.bareName(graph.keyId("mod:broken")));
        assertEquals(1, graph.unlocalizedNameCount());
        assertEquals(2, graph.distinctNameCount());
    }

    @Test
    public void anUnknownCategoryAnswersNullRatherThanThrowing() {
        // The natural call is `categoryMod(categoryId(name))`, so the -1 that a typo produces
        // has to survive one more hop or a typo reads as a crash.
        RecipeGraph graph = new GraphBuilder().build();
        assertEquals(-1, graph.categoryId("no.such.category"));
        assertNull(graph.categoryMod(-1));
        assertNull(graph.categoryMod(graph.categoryId("no.such.category")));
    }

    @Test
    public void anEmptyGraphBuildsAndAnswersEveryQueryWithNothing() {
        // The mod opens its GUI before any dump exists, so "no graph yet" is a real state
        // rather than a degenerate one. Every derived index must be present and empty rather
        // than null.
        RecipeGraph graph = new GraphBuilder().build();
        assertEquals(0, graph.keyCount());
        assertEquals(0, graph.recipes().count());
        assertEquals(0, graph.oreGroupCount());
        assertEquals(0, graph.liveKeyCount());
        assertEquals(0, graph.multiblocks().count());
        assertEquals(0, graph.fluidNames().size());
        assertEquals(-1, graph.keyId("minecraft:stone"));
        assertEquals(0, graph.byOutput().rows());
        assertEquals(0, graph.byInput().edges());
        assertTrue(graph.sizes().total() > 0);
    }
}
