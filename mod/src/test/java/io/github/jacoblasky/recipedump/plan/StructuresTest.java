package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.Multiblocks;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import org.junit.Test;

/**
 * Pricing a Modular Machinery structure, and the distinction the two functions keep apart.
 *
 * `structureCost` answers "can this be placed at all" and `blockedFraction` answers "how much
 * of it is missing". They are used TOGETHER, on the same structure, to produce a price and
 * the ordinal that ranks it -- so a position the sum thinks is affordable and the fraction
 * thinks is missing would be a contradiction nothing reports. That is why both read one
 * shared `positionCost`, and why these cases check them in pairs.
 */
public class StructuresTest {

    /** A structure of `[count, keys...]` parts, with a controller that costs `1`. */
    private static RecipeGraph machineWith(int[][] partCounts, String[][] partKeys,
                                           String... pricedKeys) {
        GraphBuilder b = new GraphBuilder();
        for (String key : pricedKeys) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:leaf"));
            b.endSlot();
            b.output(b.key(key), 1);
            b.endRecipe("make_" + key, "minecraft.crafting", null, "test", false, false);
        }
        Multiblocks.Builder machines = b.multiblocks();
        machines.beginMachine();
        for (int part = 0; part < partCounts.length; part++) {
            machines.beginPart(partCounts[part][0]);
            for (String key : partKeys[part]) {
                machines.addPartAlternative(b.key(key));
            }
            machines.endPart();
        }
        machines.endMachine("crucible", "Crucible", b.key("mod:controller"), 10, 0);
        return b.build();
    }

    private static CostTable pricesFor(RecipeGraph graph) {
        return Cost.estimate(graph, new CostInputs());
    }

    @Test
    public void aPositionCostsItsCheapestAcceptableAlternative() {
        // Matching how a machine with several candidate items is priced: if any acceptable
        // block is affordable, that is what a player would use.
        RecipeGraph graph = machineWith(
                new int[][] {{4}},
                new String[][] {{"mod:dear", "mod:cheap"}},
                "mod:dear", "mod:cheap");
        CostTable prices = Cost.estimate(graph,
                new CostInputs().have(graph.keyId("mod:cheap")));
        assertEquals(0.0, Structures.positionCost(graph.multiblocks(), 0, prices), 0.0);
        // Four positions of a free block cost nothing to place.
        assertEquals(0.0, Structures.structureCost(graph.multiblocks(), 0, prices), 0.0);
    }

    @Test
    public void placingAStructureChargesEveryPositionNotEveryPartGroup() {
        // The parts list collapses 69,181 positions into ~2,229 groups on the real pack. A
        // sum over groups would price a wall of 40 bricks the same as one brick.
        RecipeGraph graph = machineWith(
                new int[][] {{40}, {2}},
                new String[][] {{"mod:brick"}, {"mod:core"}},
                "mod:brick", "mod:core");
        CostTable prices = pricesFor(graph);
        double brick = prices.cost(graph.keyId("mod:brick"));
        double core = prices.cost(graph.keyId("mod:core"));
        assertEquals(brick * 40 + core * 2,
                Structures.structureCost(graph.multiblocks(), 0, prices), 0.0);
    }

    @Test
    public void oneUnreachableComponentMakesTheWholeMachineUnplaceable() {
        // Skipping those positions would report the Dyson Extruder as merely expensive when
        // 6,456 of its blocks are galaxy conduits with no obtainable recipe -- the same
        // underpricing this exists to fix. 155 of the pack's 259 machines are in that state.
        RecipeGraph graph = machineWith(
                new int[][] {{2000}, {1}},
                new String[][] {{"mod:brick"}, {"mod:galaxy_conduit"}},
                "mod:brick");
        CostTable prices = pricesFor(graph);
        assertTrue(Double.isInfinite(
                Structures.structureCost(graph.multiblocks(), 0, prices)));
    }

    @Test
    public void theBlockedFractionSeparatesAlmostCleanFromWhollyMissing() {
        // #95: every blocked machine reached the ranker as one indistinguishable number, so a
        // structure missing 3 of its 2,125 positions priced identically to one where all 135
        // are missing.
        RecipeGraph nearlyClean = machineWith(
                new int[][] {{2122}, {3}},
                new String[][] {{"mod:brick"}, {"mod:missing"}},
                "mod:brick");
        RecipeGraph hopeless = machineWith(
                new int[][] {{135}},
                new String[][] {{"mod:missing"}});

        double small = Structures.blockedFraction(nearlyClean.multiblocks(), 0,
                pricesFor(nearlyClean));
        double total = Structures.blockedFraction(hopeless.multiblocks(), 0,
                pricesFor(hopeless));
        assertEquals(3.0 / 2125.0, small, 0.0);
        assertEquals(1.0, total, 0.0);
        // Both are unbuildable, and both stay above every priced machine -- but they are no
        // longer the same number.
        assertTrue(Cost.blockedEntryCost(small) < Cost.blockedEntryCost(total));
        assertTrue(Cost.blockedEntryCost(small) > Cost.UNPRICED_MACHINE_COST);
    }

    @Test
    public void anEmptyStructureIsFreeRatherThanBlocked() {
        // Nothing is missing from nothing, and that agrees with the cost side, which prices
        // an empty structure at 0.0 rather than at infinity.
        RecipeGraph graph = machineWith(new int[0][], new String[0][]);
        CostTable prices = pricesFor(graph);
        assertEquals(0.0, Structures.structureCost(graph.multiblocks(), 0, prices), 0.0);
        assertEquals(0.0, Structures.blockedFraction(graph.multiblocks(), 0, prices), 0.0);
    }

    @Test
    public void aControllerIsChargedItsRecipePLUSTheStructureItStandsFor() {
        // The recipe alone is a blueprint and a blank controller, for a machine of up to
        // 8,813 placed blocks. The two are ADDED rather than one replacing the other: you
        // need the controller AND the structure, and the controller is not among the
        // machinery file's own parts (#93).
        RecipeGraph graph = machineWith(
                new int[][] {{100}},
                new String[][] {{"mod:brick"}},
                "mod:brick", "mod:controller");
        CostTable prices = pricesFor(graph);
        double controller = prices.cost(graph.keyId("mod:controller"));
        double placed = Structures.structureCost(graph.multiblocks(), 0, prices);
        assertTrue(placed > 0.0);

        java.util.Map<Integer, int[]> items = new java.util.HashMap<Integer, int[]>();
        items.put(Integer.valueOf(graph.categoryId("minecraft.crafting")),
                new int[] {graph.keyId("mod:controller")});
        CostTable withEntry = Cost.estimate(graph, new CostInputs().machineItems(items));
        assertEquals(Cost.buildEntryCost(controller + placed),
                withEntry.machineEntry(graph.categoryId("minecraft.crafting")), 0.0);
        // And strictly dearer than the controller on its own, which is the whole point.
        assertTrue(withEntry.machineEntry(graph.categoryId("minecraft.crafting"))
                > Cost.buildEntryCost(controller));
    }

    @Test
    public void aBlockedStructureFallsIntoTheBlockedBandRatherThanThePricedOne() {
        RecipeGraph graph = machineWith(
                new int[][] {{50}, {50}},
                new String[][] {{"mod:brick"}, {"mod:missing"}},
                "mod:brick", "mod:controller");
        java.util.Map<Integer, int[]> items = new java.util.HashMap<Integer, int[]>();
        items.put(Integer.valueOf(graph.categoryId("minecraft.crafting")),
                new int[] {graph.keyId("mod:controller")});
        CostTable table = Cost.estimate(graph, new CostInputs().machineItems(items));

        double entry = table.machineEntry(graph.categoryId("minecraft.crafting"));
        assertEquals(Cost.blockedEntryCost(0.5), entry, 0.0);
        assertTrue(entry >= Cost.BLOCKED_FLOOR);
        assertTrue(entry <= Cost.BLOCKED_CEILING);
        // Above every priced machine, and still below an unidentified one.
        assertTrue(entry > Cost.PRICED_CEILING);
        assertTrue(entry < Cost.MACHINE_COST[MachineInfo.UNKNOWN]);
    }
}
