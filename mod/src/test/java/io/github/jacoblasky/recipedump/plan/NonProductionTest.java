package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * The port of the loot-table and annotation-card rule. #211 and #169.
 *
 * The cases mirror `tests/test_non_production.py`, and the NEGATIVE ones carry the weight: an
 * honest LOOT marker (`dungeon_drop` plus a callstone maps one to one onto a spinel, 206 real
 * routes), a LOOT marker with the card's exact structural shape (`good_woot_drops`, whose
 * demotion is a 248x price regression on `contenttweaker:imp_skin`), and a METHOD marker that
 * varies its inputs per output (`multiblock_preview`). A green run without those three has
 * certified a blind spot rather than a fix.
 *
 * WHAT THE GOLDEN GATE DOES NOT COVER, and therefore why these exist. `PlanFixtureTest` asserts
 * the whole planner against python over 22 plan fixtures, but the reference pack's `Scrapbox`
 * entries and infusion cards do not appear in any of them, so a port that computed this rule
 * differently would pass that gate. These graphs are small enough to reason about by hand.
 */
public class NonProductionTest {

    private static final String MARKER = "contenttweaker:infusion_pseudo_automation";
    private static final String HONEST = "contenttweaker:dungeon_drop";
    private static final String PREVIEW = "contenttweaker:multiblock_preview";
    private static final String WOOT = "contenttweaker:good_woot_drops";
    private static final String SCRAPBOX_CATEGORY = "TechReborn.Scrapbox";

    /** One recipe, one output, and one single-alternative slot per named input. */
    private static void recipe(GraphBuilder b, String rid, String category, String output,
                               String... inputs) {
        b.beginRecipe();
        for (String input : inputs) {
            b.beginSlot(1, "item");
            b.alternative(b.key(input));
            b.endSlot();
        }
        b.output(b.key(output), 1);
        b.endRecipe(rid, category, null, "test", false, false);
    }

    private static Map<Integer, Integer> kinds(RecipeGraph g) {
        Map<Integer, Integer> out = new LinkedHashMap<Integer, Integer>();
        out.put(Integer.valueOf(g.keyId(MARKER)), Integer.valueOf(Tokens.METHOD));
        out.put(Integer.valueOf(g.keyId(PREVIEW)), Integer.valueOf(Tokens.METHOD));
        out.put(Integer.valueOf(g.keyId(HONEST)), Integer.valueOf(Tokens.LOOT));
        out.put(Integer.valueOf(g.keyId(WOOT)), Integer.valueOf(Tokens.LOOT));
        return out;
    }

    /**
     * #169's shape plus the three cases a wrong rule breaks. Mirrors `annotation_graph()`.
     *
     * The card produces three outputs off one fixed rig list and each output also has a real
     * route. `HONEST` pairs a different callstone with each spinel, one to one, and neither
     * spinel has any other producer. `PREVIEW` varies its frame per output. `WOOT` has the
     * card's exact shape -- one factory heart, three outputs -- and only its LOOT kind
     * separates it from the card.
     */
    private static RecipeGraph annotationGraph() {
        GraphBuilder b = new GraphBuilder();
        String[] outs = {"mod:mithrillium", "mod:catalyst", "mod:pick"};
        for (int n = 0; n < outs.length; n++) {
            recipe(b, "card:" + n, "minecraft.crafting", outs[n],
                    MARKER, "mod:router", "mod:module");
            recipe(b, "real:" + n, "THAUMCRAFT_INFUSION", outs[n], "mod:void_metal");
        }
        recipe(b, "drop:hator", "minecraft.crafting", "mod:hator_spinel",
                HONEST, "mod:trinity_callstone");
        recipe(b, "drop:ptah", "minecraft.crafting", "mod:ptah_spinel",
                HONEST, "mod:pharos_callstone");
        for (int n = 0; n < 2; n++) {
            recipe(b, "preview:" + n, "compactmachines3.MultiblockMiniaturization",
                    "mod:preview_" + n, PREVIEW, "mod:frame_" + n);
            recipe(b, "preview_real:" + n, "minecraft.crafting", "mod:preview_" + n,
                    "mod:steel");
        }
        String[] wootOuts = {"mod:imp_skin", "mod:divine_shard", "mod:nebulous_soul"};
        for (int n = 0; n < wootOuts.length; n++) {
            recipe(b, "woot:" + n, "minecraft.crafting", wootOuts[n], WOOT,
                    "mod:factory_heart");
            recipe(b, "woot_real:" + n, "minecraft.crafting", wootOuts[n], "mod:steel");
        }
        return b.build();
    }

    /**
     * #211's shape: one container, three outcomes, one with no other producer.
     *
     * `mod:chest_cart` is the Chest Cart the reproduction went through. `mod:plating` stands for
     * `ebwizardry:crystal_silver_plating`, one of the three of 343 the guard withholds.
     */
    private static RecipeGraph scrapboxGraph() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "box:cart", SCRAPBOX_CATEGORY, "mod:chest_cart", "mod:scrapbox");
        recipe(b, "box:fork", SCRAPBOX_CATEGORY, "mod:fork", "mod:scrapbox");
        recipe(b, "box:plating", SCRAPBOX_CATEGORY, "mod:plating", "mod:scrapbox");
        recipe(b, "cart", "minecraft.crafting", "mod:chest_cart", "mod:chest", "mod:minecart");
        recipe(b, "fork", "minecraft.crafting", "mod:fork", "mod:steel");
        recipe(b, "scrapbox", "minecraft.crafting", "mod:scrapbox", "mod:scrap");
        return b.build();
    }

    private static boolean demoted(RecipeGraph g, long[] bits, String rid) {
        for (int recipe = 0; recipe < g.recipes().count(); recipe++) {
            if (rid.equals(g.recipes().rid(recipe))) {
                return Bits.get(bits, recipe);
            }
        }
        throw new AssertionError("no recipe " + rid);
    }

    // -- the annotation rule ------------------------------------------------------------------

    @Test
    public void theCardMarkerIsRecognised() {
        RecipeGraph g = annotationGraph();
        assertTrue(NonProduction.markers(g, kinds(g))
                .contains(Integer.valueOf(g.keyId(MARKER))));
    }

    @Test
    public void anHonestLootMarkerIsNot() {
        // `dungeon_drop`: the callstone is a real prerequisite and the marker is honest.
        // Excluding every token-bearing recipe deletes 206 genuine routes on the reference
        // graph, 114 of which are the only provenance their output has.
        RecipeGraph g = annotationGraph();
        assertFalse(NonProduction.markers(g, kinds(g))
                .contains(Integer.valueOf(g.keyId(HONEST))));
    }

    @Test
    public void aLootMarkerWithTheCardsExactShapeIsNot() {
        // `good_woot_drops`: 25 recipes, ONE other-input set, 25 outputs -- structurally
        // indistinguishable from the card family. Its KIND is what separates them, and
        // demoting it takes `contenttweaker:imp_skin` from a reported 248.35 to 1.0.
        RecipeGraph g = annotationGraph();
        assertFalse(NonProduction.markers(g, kinds(g))
                .contains(Integer.valueOf(g.keyId(WOOT))));
    }

    @Test
    public void aMethodMarkerThatVariesItsInputsIsNot() {
        // `multiblock_preview` and `dream_infusion_crafting` are METHOD and genuine. Between
        // them 42 recipes sit in a multi-output family, so a per-RECIPE version of the
        // does-not-vary test demotes all 42.
        RecipeGraph g = annotationGraph();
        assertFalse(NonProduction.markers(g, kinds(g))
                .contains(Integer.valueOf(g.keyId(PREVIEW))));
    }

    @Test
    public void aMarkerUsedAcrossSeveralCardsIsStillRecognised() {
        // The correction #169's issue body needs. Its rule was "the marker has ONE distinct
        // other-input set"; `passive_crafting_subnets` has THREE, one per card, and a
        // per-marker test calls it genuine and misses all 25 of its recipes.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "card:0", "minecraft.crafting", "mod:a", MARKER, "mod:forge");
        recipe(b, "card:1", "minecraft.crafting", "mod:b", MARKER, "mod:forge");
        recipe(b, "card:2", "minecraft.crafting", "mod:c", MARKER, "mod:oven");
        recipe(b, "card:3", "minecraft.crafting", "mod:d", MARKER, "mod:oven");
        RecipeGraph g = b.build();
        Map<Integer, Integer> kinds = new LinkedHashMap<Integer, Integer>();
        kinds.put(Integer.valueOf(g.keyId(MARKER)), Integer.valueOf(Tokens.METHOD));
        assertTrue(NonProduction.markers(g, kinds)
                .contains(Integer.valueOf(g.keyId(MARKER))));
    }

    @Test
    public void oneFamilyPerOutputIsTheHonestShapeEvenWithManyFamilies() {
        // `dungeon_drop` at full size: 121 distinct sets over 206 recipes.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "m:0", "minecraft.crafting", "mod:a", MARKER, "mod:tool_0");
        recipe(b, "m:1", "minecraft.crafting", "mod:b", MARKER, "mod:tool_1");
        RecipeGraph g = b.build();
        Map<Integer, Integer> kinds = new LinkedHashMap<Integer, Integer>();
        kinds.put(Integer.valueOf(g.keyId(MARKER)), Integer.valueOf(Tokens.METHOD));
        assertTrue(NonProduction.markers(g, kinds).isEmpty());
    }

    @Test
    public void theMarkerSlotIsFoundByMembershipAndNotByPosition() {
        // `battle_tower`'s recipes carry TWO markers, so dropping "the first slot" removes the
        // wrong one on half of them and every family looks distinct.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "two:0", "minecraft.crafting", "mod:a", HONEST, MARKER, "mod:rig");
        recipe(b, "two:1", "minecraft.crafting", "mod:b", HONEST, MARKER, "mod:rig");
        RecipeGraph g = b.build();
        Map<Integer, Integer> kinds = new LinkedHashMap<Integer, Integer>();
        kinds.put(Integer.valueOf(g.keyId(MARKER)), Integer.valueOf(Tokens.METHOD));
        kinds.put(Integer.valueOf(g.keyId(HONEST)), Integer.valueOf(Tokens.LOOT));
        assertTrue(NonProduction.markers(g, kinds)
                .contains(Integer.valueOf(g.keyId(MARKER))));
    }

    // -- the guard ----------------------------------------------------------------------------

    @Test
    public void aCardWhoseOutputsHaveRealRoutesIsDemoted() {
        RecipeGraph g = annotationGraph();
        long[] bits = NonProduction.recipes(g, kinds(g), null);
        assertTrue(demoted(g, bits, "card:0"));
        assertTrue(demoted(g, bits, "card:1"));
        assertTrue(demoted(g, bits, "card:2"));
        assertFalse(demoted(g, bits, "real:0"));
    }

    @Test
    public void anHonestMarkersRecipeIsNeverDemoted() {
        // `contenttweaker:hator_spinel`'s sole producer, and its price must read exactly
        // LOOT_COST + callstone + the crafting-table entry afterwards. Each way of getting this
        // wrong lands on a different number.
        RecipeGraph g = annotationGraph();
        long[] bits = NonProduction.recipes(g, kinds(g), null);
        assertFalse(demoted(g, bits, "drop:hator"));
        assertFalse(demoted(g, bits, "woot:0"));
        assertFalse(demoted(g, bits, "preview:0"));
    }

    @Test
    public void theLootTableEntryWithNoOtherProducerIsWithheld() {
        // 340 of 343 `TechReborn.Scrapbox` entries demote on the reference graph and 3 do not.
        // A category-level verdict cannot express that and would strand all three at 1.0.
        RecipeGraph g = scrapboxGraph();
        NonProduction.Counts counts = new NonProduction.Counts();
        long[] bits = NonProduction.recipes(g, new LinkedHashMap<Integer, Integer>(), counts);
        assertTrue(demoted(g, bits, "box:cart"));
        assertTrue(demoted(g, bits, "box:fork"));
        assertFalse(demoted(g, bits, "box:plating"));
        assertEquals(2, counts.demoted[NonProduction.LOOT_TABLE]);
        assertEquals(1, counts.withheld[NonProduction.LOOT_TABLE]);
    }

    @Test
    public void twoCardsCoveringEachOthersOutputsAreBothWithheld() {
        // "Not itself a CANDIDATE" rather than "not itself DEMOTED", so the answer cannot depend
        // on which recipe is visited first. Each would otherwise see the other as a real
        // alternative and both would demote, orphaning the key.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "card:0", "minecraft.crafting", "mod:thing", MARKER, "mod:rig_0");
        recipe(b, "card:1", "minecraft.crafting", "mod:thing", MARKER, "mod:rig_1");
        recipe(b, "other:0", "minecraft.crafting", "mod:extra_0", MARKER, "mod:rig_0");
        recipe(b, "other:1", "minecraft.crafting", "mod:extra_1", MARKER, "mod:rig_1");
        recipe(b, "real:0", "minecraft.crafting", "mod:extra_0", "mod:steel");
        recipe(b, "real:1", "minecraft.crafting", "mod:extra_1", "mod:steel");
        RecipeGraph g = b.build();
        Map<Integer, Integer> kinds = new LinkedHashMap<Integer, Integer>();
        kinds.put(Integer.valueOf(g.keyId(MARKER)), Integer.valueOf(Tokens.METHOD));
        long[] bits = NonProduction.recipes(g, kinds, null);
        assertFalse(demoted(g, bits, "card:0"));
        assertFalse(demoted(g, bits, "card:1"));
        assertTrue(demoted(g, bits, "other:0"));
        assertTrue(demoted(g, bits, "other:1"));
    }

    // -- what the cost model and the solver do with it ----------------------------------------

    @Test
    public void thePenaltyOutranksTheWorstObstacleTheModelCanState() {
        // A machine you cannot have is a true statement about the world and a route through it
        // is a real route. A loot table is not a route at all, so it has to lose even to that.
        assertTrue(Cost.NON_PRODUCTION_PENALTY
                > Cost.MACHINE_COST[MachineInfo.UNAVAILABLE]);
    }

    @Test
    public void theOutputStaysPricedAndTheCheapRouteIsTheRealOne() {
        RecipeGraph g = scrapboxGraph();
        CostTable table = Cost.estimate(g, new CostInputs());
        assertTrue(table.cost(g.keyId("mod:fork")) < Cost.NON_PRODUCTION_PENALTY);
        assertTrue(table.cost(g.keyId("mod:plating")) < Cost.NON_PRODUCTION_PENALTY);
    }

    @Test
    public void theTableCarriesTheVerdictSoTheRankerChargesTheRelaxationsAnswer() {
        RecipeGraph g = scrapboxGraph();
        CostTable table = Cost.estimate(g, new CostInputs());
        int box = -1;
        int real = -1;
        for (int recipe = 0; recipe < g.recipes().count(); recipe++) {
            if ("box:fork".equals(g.recipes().rid(recipe))) {
                box = recipe;
            } else if ("fork".equals(g.recipes().rid(recipe))) {
                real = recipe;
            }
        }
        assertTrue(table.isNotProduction(box));
        assertFalse(table.isNotProduction(real));
        assertTrue(Cost.recipeCost(table, g, box, null, null)
                > Cost.recipeCost(table, g, real, null, null)
                + Cost.NON_PRODUCTION_PENALTY - 1.0);
    }

    @Test
    public void aHandBuiltTableCarriesNoVerdictAndChargesNothing() {
        // The pre-#211 reading, which is what a test passing its own table wants.
        RecipeGraph g = scrapboxGraph();
        CostTable bare = new CostTable(new double[g.keyCount()], null);
        assertFalse(bare.isNotProduction(0));
    }

    @Test
    public void thePlanTakesTheRealRoute() {
        // #211's reproduction at fixture size: the plan for the Chest Cart must stop going
        // through the scrapbox.
        RecipeGraph g = scrapboxGraph();
        PlanResult result = new Solver.Builder(g)
                .costs(Cost.estimate(g, new CostInputs()))
                .build()
                .solve(g.keyId("mod:chest_cart"), 1);
        assertEquals("cart", result.tree.recipe());
    }
}
