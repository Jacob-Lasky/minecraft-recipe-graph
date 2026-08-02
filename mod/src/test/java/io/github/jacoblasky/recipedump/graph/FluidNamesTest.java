package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The container-name derivation, including the two ways it was measured WRONG before #103.
 *
 * Both failures were quiet: one renamed real fluids after items that merely sound like
 * containers, and the other let a single generic recipe listing 1,198 filled containers vote
 * every fluid in the game as "Water". Neither throws, neither shows up in a count, and both
 * produce a label a player cannot search for -- so they are asserted rather than trusted.
 */
public class FluidNamesTest {

    @Test
    public void aContainerLabelSplitsAtTheLASTOccurrenceOfItsWord() {
        assertEquals("Molten Sednanite", FluidNames.heldBy("Molten Sednanite Cell", "Cell"));
        // A fluid whose own name contains the container word still reads.
        assertEquals("Cell Culture", FluidNames.heldBy("Cell Culture Cell", "Cell"));
    }

    @Test
    public void aLabelWithNothingBeforeTheContainerWordNamesNoFluid() {
        assertNull(FluidNames.heldBy("Cell", "Cell"));
        assertNull(FluidNames.heldBy("  Cell", "Cell"));
        // The word has to be its own word: "Fuelcell" is not a cell of Fuel.
        assertNull(FluidNames.heldBy("Fuelcell", "cell"));
        assertNull(FluidNames.heldBy("Empty Can", "Cell"));
        assertNull(FluidNames.heldBy(null, "Cell"));
    }

    @Test
    public void votesAreSettledByCountAndThenAlphabetically() {
        Map<String, int[]> counter = new HashMap<String, int[]>();
        counter.put("Niddhog Dragonfire", new int[] {4});
        counter.put("Eternal Dragon Fire", new int[] {4});
        // Exactly one fluid ties on the reference pack and the two names have nothing in
        // common, so the choice has to be deterministic or the label flickers between loads.
        assertEquals("Eternal Dragon Fire", FluidNames.decide(counter));
        counter.put("Niddhog Dragonfire", new int[] {5});
        assertEquals("Niddhog Dragonfire", FluidNames.decide(counter));
    }

    @Test
    public void anItemMerelyNamedAfterAFluidCastsNoVote() {
        // `plustic:battery_cell` is "Manyullyn Battery Cell": a perfectly well-formed label
        // on an item that holds no fluid. Accepting any "<something> Cell" let it outvote
        // the truth 5 to 2 and rename `fluid:manyullyn` to "Manyullyn Battery". The curated
        // list is the fix, and a suffix guess is what it is protecting against.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("plustic:battery_cell"));
        b.endSlot();
        b.output(b.key("fluid:manyullyn"), 144);
        b.endRecipe("t:squeeze", "transposer", null, "hei_dump", true, false);
        b.name("plustic:battery_cell", "Manyullyn Battery Cell");
        RecipeGraph graph = b.build();

        assertEquals(0, graph.fluidNames().size());
        assertEquals("Manyullyn", graph.bareName(graph.keyId("fluid:manyullyn")));
    }

    @Test
    public void aSlotListingSeveralContainersCastsNoVoteAtAll() {
        // The pack's generic "Fluid Transposer - Empty" entry lists every filled container in
        // ONE slot against an output of water. Without the single-alternative test, every
        // fluid in the game votes for "Water".
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("forestry:can:1#aaaaaaaaaaaa"));
        b.alternative(b.key("forestry:can:1#bbbbbbbbbbbb"));
        b.endSlot();
        b.output(b.key("fluid:water"), 1000);
        b.endRecipe("t:generic", "transposer", null, "hei_dump", true, false);
        b.name("forestry:can:1#aaaaaaaaaaaa", "Lava Can");
        b.name("forestry:can:1#bbbbbbbbbbbb", "Milk Can");
        RecipeGraph graph = b.build();

        assertEquals(0, graph.fluidNames().size());
        assertEquals("Water", graph.bareName(graph.keyId("fluid:water")));
    }

    @Test
    public void aCuratedContainerInItsOwnSlotNamesTheFluidItHolds() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("techreborn:dynamiccell#cccccccccccc"));
        b.endSlot();
        b.output(b.key("fluid:sednanite"), 1000);
        b.endRecipe("t:empty", "transposer", null, "hei_dump", true, false);
        b.name("techreborn:dynamiccell#cccccccccccc", "Molten Sednanite Cell");
        RecipeGraph graph = b.build();

        assertEquals(1, graph.fluidNames().size());
        assertEquals("Molten Sednanite", graph.bareName(graph.keyId("fluid:sednanite")));
    }
}
