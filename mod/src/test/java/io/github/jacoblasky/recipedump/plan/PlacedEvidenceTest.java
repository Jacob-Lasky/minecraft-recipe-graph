package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import org.junit.Test;

/**
 * What a `placed` sighting buys, through the ONE resolver the golden fixtures use. #320 #322
 *
 * <h2>WHY THROUGH `ScenarioInputs.resolve` AND NOT THROUGH THE PIECES</h2>
 *
 * `MachinesTest` already covers `Machines.resolve` given an `Evidence`, and `SourceTableTest`
 * covers `SourceTable.of` given a free-source map. Neither says anything about the step between a
 * scenario DOCUMENT and those two, which is where the in-game read arrives: #320 phase 2 fills the
 * `placed` field on the wire, and everything downstream of that field is only as good as
 * `resolve`'s reading of it. So these hand it a document, exactly as the game does.
 *
 * WHAT THIS DOES NOT COVER, said plainly: the SCAN. `PlacedTileEntities` walks a live server's
 * loaded worlds, which no JUnit case can stand up -- the live proof for that is the `ae2-placed`
 * probe, which places a vanilla furnace in a real world and asserts the production read yields it.
 * These tests cover the half a document can express.
 */
public class PlacedEvidenceTest {

    /**
     * A placed generator makes its output free, and the evidence says it was placed.
     *
     * #320 PHASE 2'S SECOND HALF, AND IT IS A DIFFERENT PATH FROM MACHINE VERDICTS.
     * `resolveFreeSources` checks placed blocks BEFORE stock, on purpose -- a generator in a drive
     * is one place-block away and counts, but a placed one is directly producing -- and phase 1
     * could never light that branch up because a `nuclearcraft:water_source` joins no ME grid. It
     * is a tile entity (`nc.tile.passive.TilePassive$WaterSource`, checked in the pack's own jar),
     * so the loaded-chunk scan sees it and this is the branch that then fires.
     *
     * THE `why` STRING IS THE ASSERTION, not the free-ness. `fluid:water` is free by default from
     * vanilla infinite water, so a test that only checked "is water free" would pass with the
     * placed branch dead -- which is exactly the trap #320's own probe header describes. The
     * evidence has to say `placed:`, and it has to name the block, or the Sources screen (#255) is
     * explaining the wrong plan.
     */
    @Test
    public void aPlacedGeneratorIsWhatMakesItsOutputFreeAndSaysSo() {
        RecipeGraph graph = graphKnowing("fluid:water");
        JsonObject scenario = new JsonObject();
        JsonObject placed = new JsonObject();
        placed.addProperty("nuclearcraft:water_source", 1);
        scenario.add("placed", placed);

        ScenarioInputs.Resolved resolved = ScenarioInputs.resolve(graph, scenario);
        String why = resolved.freeSources().get(Integer.valueOf(graph.keyId("fluid:water")));
        assertNotNull("water is not free at all, so the generator table never matched", why);
        assertEquals("the placed-first branch did not fire; this is what a placed generator buys"
                     + " over the vanilla default", "placed: nuclearcraft:water_source", why);
    }

    /**
     * And with nothing placed, the same key is free for the OTHER reason.
     *
     * THE CONTROL THAT STOPS THE TEST ABOVE PASSING VACUOUSLY. If `resolve` reported
     * `placed: ...` regardless of the document, this would fail -- and without it, "water is free
     * because it was placed" is indistinguishable from "water is free, and I wrote the sentence I
     * expected".
     */
    @Test
    public void withNothingPlacedTheSameKeyIsFreeForADifferentReason() {
        RecipeGraph graph = graphKnowing("fluid:water");
        ScenarioInputs.Resolved resolved =
                ScenarioInputs.resolve(graph, new JsonObject());
        String why = resolved.freeSources().get(Integer.valueOf(graph.keyId("fluid:water")));
        assertNotNull(why);
        assertFalse("nothing was placed, so nothing may be reported as placed: " + why,
                    why.startsWith("placed:"));
    }

    /**
     * A machine key seen as placed reads `have`, and one seen nowhere reads `buildable`.
     *
     * THIS IS THE SHAPE `ae2-placed` ASSERTS AGAINST A REAL WORLD, kept here as the fast version so
     * a regression in the DOCUMENT reading is caught by a fourteen-minute suite rather than by a
     * ninety-second client launch. The two are not redundant: this proves the resolver reads the
     * field, the probe proves the field gets filled.
     *
     * BOTH MACHINES CRAFTABLE IS LOAD-BEARING, for the probe's stated reason: without a producer
     * the unowned one reads `unavailable`, and #320 is about `buildable` versus `have`.
     */
    @Test
    public void aPlacedMachineReadsHaveWhileAnUnseenOneReadsBuildable() {
        GraphBuilder b = new GraphBuilder();
        machineCategory(b, "probe.seen", "Seen", "mod:seen_machine");
        machineCategory(b, "probe.unseen", "Unseen", "mod:unseen_machine");
        RecipeGraph graph = b.build();

        JsonObject scenario = new JsonObject();
        JsonObject placed = new JsonObject();
        placed.addProperty("mod:seen_machine", 1);
        scenario.add("placed", placed);

        ScenarioInputs.Resolved resolved = ScenarioInputs.resolve(graph, scenario);
        MachineInfo seen = resolved.machineStates().info(graph.categoryId("probe.seen"));
        MachineInfo unseen = resolved.machineStates().info(graph.categoryId("probe.unseen"));
        assertEquals(MachineInfo.HAVE, seen.state());
        assertTrue("the verdict has to say it came from a sighting, or a machine ITEM in stock is"
                   + " indistinguishable from a machine standing in the world: " + seen.why(),
                   seen.why().startsWith("placed:"));
        assertEquals(MachineInfo.BUILDABLE, unseen.state());
    }

    // -- stock the graph could not understand (#322) --------------------------------------------

    /**
     * WITNESS. A `have` key the graph never heard of is counted, with the volume it carried.
     *
     * ON MASTER IT WAS DROPPED IN SILENCE at `keyId >= 0`, with no count, no log and no caveat,
     * while `PlanCaveats` went on reporting `have` as live -- so the plan claimed it had consulted
     * the player's stock while discarding part of it.
     *
     * THE KNOWN KEY STILL LANDS, which is the other half: a change that counted everything as
     * stranded would satisfy the count assertion and quietly stop pricing against any stock at all.
     */
    @Test
    public void stockTheGraphDoesNotKnowIsCountedRatherThanDroppedInSilence() {
        RecipeGraph graph = graphKnowing("mod:ingot");
        JsonObject scenario = new JsonObject();
        JsonObject have = new JsonObject();
        have.addProperty("mod:ingot", 12L);
        have.addProperty("mod:tool#deadbeef", 3L);
        have.addProperty("mod:cell#c0ffee", 40_000L);
        scenario.add("have", have);

        ScenarioInputs.Resolved resolved = ScenarioInputs.resolve(graph, scenario);
        assertTrue(resolved.strandedStock().any());
        assertEquals(2, resolved.strandedStock().keys());
        assertEquals("the VOLUME is what decides whether a player should care",
                     40_003L, resolved.strandedStock().volume());
    }

    /** And a scenario the graph fully understands strands nothing, so the caveat stays silent. */
    @Test
    public void aFullyUnderstoodScenarioStrandsNothing() {
        RecipeGraph graph = graphKnowing("mod:ingot");
        JsonObject scenario = new JsonObject();
        JsonObject have = new JsonObject();
        have.addProperty("mod:ingot", 12L);
        scenario.add("have", have);

        ScenarioInputs.Resolved resolved = ScenarioInputs.resolve(graph, scenario);
        assertFalse(resolved.strandedStock().any());
        assertEquals(0, resolved.strandedStock().keys());
        assertEquals(0L, resolved.strandedStock().volume());
    }

    /**
     * A `Resolved` nobody resolved reports nothing stranded rather than throwing.
     *
     * THE DEFAULT MATTERS BECAUSE THE FIELD IS READ FROM A CAVEAT SURFACE, and a null there would
     * be a NullPointerException while a panel is drawing -- the failure mode that reads as a broken
     * GUI. `Stranded.NONE` is the honest answer for a scenario nobody handed a `have` map.
     */
    @Test
    public void anEmptyScenarioStrandsNothingAndAnswersWithoutThrowing() {
        ScenarioInputs.Resolved resolved =
                ScenarioInputs.resolve(graphKnowing("mod:ingot"), new JsonObject());
        assertNotNull(resolved.strandedStock());
        assertFalse(resolved.strandedStock().any());
    }

    /** A graph that knows one key, so anything else in a scenario is genuinely unknown. */
    private static RecipeGraph graphKnowing(String key) {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key(key));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "minecraft.crafting", "Crafting Table", "mod", false, false);
        RecipeGraph graph = b.build();
        assertTrue("the fixture graph must know " + key, graph.keyId(key) >= 0);
        // AND MUST NOT KNOW WHAT THE STRANDING CASES HAND IT. Asserted rather than assumed: if a
        // future GraphBuilder pre-registered keys, `stockTheGraphDoesNotKnowIsCountedRatherThan
        // DroppedInSilence` would find zero stranded and read as a regression in the counting.
        assertTrue("a digest key must be unknown to this graph, or the stranding cases assert"
                   + " nothing", graph.keyId("mod:tool#deadbeef") < 0);
        return graph;
    }

    /** A category catalysed by one machine, with that machine craftable by hand. */
    private static void machineCategory(GraphBuilder b, String uid, String title, String key) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key("mod:out_" + uid), 1);
        b.endRecipe(uid + ".r", uid, title, "mod", false, false);
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key(key), 1);
        b.endRecipe(uid + ".machine", "minecraft.crafting", "Crafting Table", "mod", false, false);
        b.beginCatalyst(uid);
        b.catalystKey(b.key(key));
        b.endCatalyst();
    }
}
