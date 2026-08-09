package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import org.junit.Test;

/**
 * What the relaxation actually computes, against graphs small enough to price by hand.
 *
 * Every case here is a failure that shipped. The comments name which, because the numbers are
 * arbitrary and the BEHAVIOURS are not: a test that only checked "the table is populated"
 * would have passed during the episode where every fluid in the pack converged to 0.0.
 *
 * Prices are asserted with an exact delta of 0.0 wherever the arithmetic is exact. That is
 * deliberate -- the golden fixtures demand bit-identical reproduction of python, and a test
 * suite that tolerates 1e-9 here while the fixture does not would let drift through in the
 * one place it is cheapest to catch.
 */
public class CostTest {

    /** A recipe with one single-alternative input slot and one output. */
    private static void recipe(GraphBuilder b, String rid, String category, String output,
                               int outQty, String input, int inQty) {
        b.beginRecipe();
        b.beginSlot(inQty, "item");
        b.alternative(b.key(input));
        b.endSlot();
        b.output(b.key(output), outQty);
        b.endRecipe(rid, category, null, "test", false, false);
    }

    private static double priceOf(RecipeGraph graph, CostTable table, String key) {
        return table.cost(graph.keyId(key));
    }

    // -- seeding ----------------------------------------------------------------------------

    @Test
    public void anItemNoRecipeMakesIsPricedAsSomethingYouCanGetSomehow() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "mod:out", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());
        assertEquals(Cost.BASE_RAW_COST, priceOf(graph, table, "mod:leaf"), 0.0);
    }

    @Test
    public void anythingInStockIsFreeAtTheMargin() {
        // That is what makes the solver prefer using what you already own, with no separate
        // rule for it anywhere else.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "mod:out", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph,
                new CostInputs().have(graph.keyId("mod:leaf")));
        assertEquals(0.0, priceOf(graph, table, "mod:leaf"), 0.0);
    }

    @Test
    public void aGeneratorOutputBeatsEveryCraftedRouteWithoutBeingFree() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "fluid:water", 1000, "mod:ice", 1);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph,
                new CostInputs().freeSource(graph.keyId("fluid:water")));
        assertEquals(Cost.SOURCE_COST, priceOf(graph, table, "fluid:water"), 0.0);
    }

    // -- the amortisation rule, which is #29 --------------------------------------------------

    @Test
    public void onlyTheIngredientsAmortiseOverAHugeOutputAndTheMachineDoesNot() {
        // The pack has a recipe yielding 1,024 iron ingots and one yielding 60,466,176 fruit.
        // Dividing the machine by the batch collapsed the 5,000 wall in front of an
        // unavailable machine to 8e-5 and priced 126 items under 0.1 -- which is how "one
        // iron ingot" came out as "smelt a Spawner Shard".
        GraphBuilder b = new GraphBuilder();
        recipe(b, "huge", "machine", "mod:out", 1000000, "mod:leaf", 1);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());
        double price = priceOf(graph, table, "mod:out");
        // The entry cost survives the batch intact; only the one leaf is divided down.
        assertEquals(Cost.UNGATED_MACHINE_COST + Cost.BASE_RAW_COST / 1000000.0, price, 0.0);
        assertTrue(price > Cost.UNGATED_MACHINE_COST);
    }

    @Test
    public void aFluidOutputIsScaledTooOrEveryChainDividesItselfToNothing() {
        // Scaling only the input side made every fluid-to-fluid hop divide the cost by 1000,
        // so a ten-hop chain priced at 1e-30 and the whole table stopped discriminating
        // between routes while still looking populated.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "hop", "machine", "fluid:b", 1000, "fluid:a", 1000);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());
        // One bucket in, one bucket out: the ingredient term must come out unchanged at
        // BASE_RAW_COST rather than divided by a thousand.
        assertEquals(Cost.UNGATED_MACHINE_COST + Cost.BASE_RAW_COST,
                priceOf(graph, table, "fluid:b"), 0.0);
    }

    // -- container transfers -------------------------------------------------------------------

    /** The can shape: emptying it yields a fluid and the tin it was made of. */
    private static GraphBuilder cannedFluid() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:full_can"));
        b.endSlot();
        b.output(b.key("fluid:water"), 1000);
        b.output(b.key("mod:tin"), 1);
        b.endRecipe("empty", "squeezer", null, "test", true, false);
        return b;
    }

    @Test
    public void aContainerEmptyNeverMakesItsFluidCheaperButStillMakesTheItem() {
        // Emptying a can you own is not production of its contents. Left in, the ranker
        // prices a route the solver cannot take, because the solver walks real producers.
        GraphBuilder b = cannedFluid();
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());

        // UNSOURCED_COST EVEN THOUGH NOTHING CONSUMES THIS FLUID, and the "even though" is the
        // part worth knowing. The LEAF rule walks recipe INPUTS, so an unconsumed key is never
        // offered to it -- but the rule that raises `Unsourced.producedInNameOnly` sweeps every
        // key and reads an infinite slot as BASE_RAW_COST, so it INSERTS rather than only
        // raising. That is the same second effect `Unsourced.keys` has for 39 keys and is
        // deliberate in both: these keys reach no plan and they DO reach a cost report.
        //
        // WHAT THIS ASSERTS is still the thing the test is named for: the price is not one the
        // squeezer computed. Drop the exclusion and `relax` writes 521.0 through it.
        assertEquals(Cost.UNSOURCED_COST, priceOf(graph, table, "fluid:water"), 0.0);
        // The item direction is real work and stays -- with the transfer penalty on top, so
        // it can never be preferred to a genuine route.
        assertEquals(Cost.UNGATED_MACHINE_COST + Cost.TRANSFER_PENALTY + Cost.BASE_RAW_COST,
                priceOf(graph, table, "mod:tin"), 0.0);
    }

    @Test
    public void aFluidOnlyAContainerEmptyMakesIsPricedRatherThanUnreachable() {
        // #193. `seed` used to ask `byOutput().count(key) == 0`, the one reader that counted a
        // container empty as production, so this fluid was not a leaf and got no seed -- and
        // then `relax` applied the exclusion and refused to price it from the only recipe there
        // is. Nothing seeded it and nothing relaxed it, while `Solver.expand` reported it raw
        // and shopping-listed it. 120 fluids on the reference graph.
        //
        // UNSOURCED_COST, NOT BASE_RAW_COST, and that was measured: the cheapest value in the
        // model steers the solver INTO fluids the tool cannot source, which is #176's defect in
        // the one population #176's set cannot reach. See `Unsourced.producedInNameOnly`.
        GraphBuilder b = cannedFluid();
        recipe(b, "enrich", "crafting", "mod:enriched", 1, "fluid:water", 1000);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());

        assertEquals(Cost.UNSOURCED_COST, priceOf(graph, table, "fluid:water"), 0.0);
        // And the consumer prices at all, which is what the ranker needed: one infinite
        // ingredient made every route through this fluid invisible.
        assertTrue(Double.isFinite(priceOf(graph, table, "mod:enriched")));
    }

    @Test
    public void theTwoUnexplainedPopulationsAreDisjoint() {
        // `seed` walks both in one loop, which is only equivalent to two loops because they
        // cannot overlap: `Unsourced.keys` needs byOutput EMPTY and `producedInNameOnly` needs
        // it non-empty. Pinned rather than reasoned about, so a widening of either has to
        // confront the claim. Mirrors `TheTwoUnexplainedPopulationsAreDisjointTest` in python.
        GraphBuilder b = cannedFluid();
        recipe(b, "enrich", "crafting", "mod:enriched", 1, "fluid:water", 1000);
        RecipeGraph graph = b.build();
        long[] unsourced = Unsourced.keys(graph);
        long[] inNameOnly = Unsourced.producedInNameOnly(graph);

        int both = 0;
        for (int key = 0; key < graph.keyCount(); key++) {
            if (Bits.get(unsourced, key) && Bits.get(inNameOnly, key)) {
                both++;
            }
        }
        assertEquals(0, both);
        assertTrue(Bits.get(inNameOnly, graph.keyId("fluid:water")));
        // And an ordinary leaf is in NEITHER, which is what keeps the set narrow: pricing every
        // key with no producer at UNSOURCED_COST would put most of a shopping list behind it.
        assertFalse(Bits.get(inNameOnly, graph.keyId("mod:full_can")));
    }

    @Test
    public void aPackAuthoredMarkerIsUnexplainedAndItsLookalikesAreNot() {
        // #171/#242, and the THIRD population `seed` walks in that same loop. Directly rather
        // than only through the golden fixtures, because its two siblings above are tested
        // directly and because a set that quietly caught the wrong keys would show up in the
        // fixtures as a diff nobody could attribute.
        //
        // FOUR KEYS, ONE PER POPULATION THE RULE HAS TO TELL APART. All four are consumed and
        // none is produced, which is the structural signal; what separates them is only the
        // pack-declared data attached below, exactly as in python's `test_pack_provenance`.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "via_marker", "crafting", "mod:widget", 1,
               "contenttweaker:multiblock_preview", 1);
        recipe(b, "via_ore", "crafting", "mod:widget", 1, "contenttweaker:candyte_ore", 1);
        recipe(b, "via_worn", "crafting", "mod:widget", 1, "contenttweaker:plate:28400", 1);
        recipe(b, "via_outsider", "crafting", "mod:widget", 1, "somemod:gravel", 1);
        // The undamaged plate IS produced, which is what makes its worn variant ordinary.
        recipe(b, "make_plate", "crafting", "contenttweaker:plate", 1, "mod:ingot", 4);
        b.beginOreGroup("oreCandyte");
        b.oreMember(b.key("contenttweaker:candyte_ore"));
        b.endOreGroup();
        b.damageable(b.key("contenttweaker:plate"), 32767);
        RecipeGraph graph = b.build();

        long[] pack = Unsourced.packAuthored(graph);
        assertTrue("a pack marker nothing makes is the population",
                   Bits.get(pack, graph.keyId("contenttweaker:multiblock_preview")));
        // Worldgen is not a recipe, so zero producers is CORRECT here. 47 such keys on the
        // reference graph and only 11 are ores; the rest are nuggets, blocks and foods.
        assertFalse("an oredict member is a real material",
                    Bits.get(pack, graph.keyId("contenttweaker:candyte_ore")));
        // 837 of the reference pool's 1,120, and the undamaged key is made on a bench.
        assertFalse("a durability variant is not unsourced",
                    Bits.get(pack, graph.keyId("contenttweaker:plate:28400")));
        // The clause that keeps this off 117,350 keys: a MOD shipping gravel with no recipe is
        // the ordinary case BASE_RAW_COST exists for; a pack script doing it is not.
        assertFalse("a mod's own raw leaf is not pack-authored",
                    Bits.get(pack, graph.keyId("somemod:gravel")));

        // And it is disjoint from both siblings, which is what lets `seed` walk all three in
        // one loop. Mirrors `theTwoUnexplainedPopulationsAreDisjoint` above.
        long[] unsourced = Unsourced.keys(graph);
        long[] inNameOnly = Unsourced.producedInNameOnly(graph);
        for (int key = 0; key < graph.keyCount(); key++) {
            assertFalse("packAuthored overlaps Unsourced.keys at " + graph.key(key),
                        Bits.get(pack, key) && Bits.get(unsourced, key));
            assertFalse("packAuthored overlaps producedInNameOnly at " + graph.key(key),
                        Bits.get(pack, key) && Bits.get(inNameOnly, key));
        }

        CostTable table = Cost.estimate(graph, new CostInputs());
        assertEquals(Cost.UNSOURCED_COST,
                     priceOf(graph, table, "contenttweaker:multiblock_preview"), 0.0);
        assertEquals(Cost.BASE_RAW_COST,
                     priceOf(graph, table, "contenttweaker:candyte_ore"), 0.0);
        assertEquals(Cost.BASE_RAW_COST, priceOf(graph, table, "somemod:gravel"), 0.0);
    }

    // -- the two terminals the player declares, which is #193 ------------------------------

    @Test
    public void anAutocraftableItemPricesAsOneRequest() {
        // `Solver.expand` has stopped at these since the feature shipped and the cost model was
        // never told, so a route through one was priced at its full subtree while the real cost
        // is one request -- and so it lost to worse routes.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "deep", "crafting", "mod:part", 1, "mod:grit", 64);
        recipe(b, "assemble", "crafting", "mod:widget", 1, "mod:part", 1);
        RecipeGraph graph = b.build();
        int part = graph.keyId("mod:part");

        CostTable blind = Cost.estimate(graph, new CostInputs());
        CostTable told = Cost.estimate(graph, new CostInputs().craftable(part));
        assertEquals(Cost.CRAFTABLE_COST, told.cost(part), 0.0);
        // The consequence, which is why the price matters: the parent sees the request rather
        // than the 64 grit.
        assertTrue(priceOf(graph, told, "mod:widget") < priceOf(graph, blind, "mod:widget"));
    }

    @Test
    public void aDeclaredStopPricesAsSomethingToGoAndGet() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "deep", "crafting", "mod:part", 1, "mod:grit", 64);
        RecipeGraph graph = b.build();
        int part = graph.keyId("mod:part");
        assertEquals(Cost.BASE_RAW_COST,
                Cost.estimate(graph, new CostInputs().raw(part)).cost(part), 0.0);
    }

    @Test
    public void stockAndEmcStillWinOverBothDeclaredTerminals() {
        // `expand` returns at the stock, source and EMC branches before it reaches
        // raw/craftables, so the price has to as well. It is the under-a-raw-leaf guard doing
        // that rather than the relative size of the constants.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "deep", "crafting", "mod:part", 1, "mod:grit", 64);
        RecipeGraph graph = b.build();
        int part = graph.keyId("mod:part");

        assertEquals(0.0, Cost.estimate(graph,
                new CostInputs().have(part).craftable(part).raw(part)).cost(part), 0.0);
        assertEquals(Cost.EMC_COST, Cost.estimate(graph,
                new CostInputs().emcAvailable(part).craftable(part)).cost(part), 0.0);
    }

    @Test
    public void aCraftableOutranksADeclaredStopForTheSameKey() {
        // `expand` reports `have` with the autocraft note rather than shopping-listing it.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "deep", "crafting", "mod:part", 1, "mod:grit", 64);
        RecipeGraph graph = b.build();
        int part = graph.keyId("mod:part");
        assertEquals(Cost.CRAFTABLE_COST, Cost.estimate(graph,
                new CostInputs().raw(part).craftable(part)).cost(part), 0.0);
    }

    // -- world ores, which is #106 ---------------------------------------------------------------

    @Test
    public void anOreYouMineIsPricedAsMinedEvenWhenSomeRecipeClaimsToMakeIt() {
        // `contenttweaker:sednanite_ore` is registered `oreSednanite` AND emitted by two
        // Plasmatic Condenser recipes wanting 160,000 mB of Dense Plasma each. It therefore
        // counted as produced, both routes priced at infinity, and an ore you dig up became
        // unreachable -- which made every honest route to the ingot invisible.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(160000, "fluid");
        b.alternative(b.key("fluid:unobtainable"));
        b.endSlot();
        b.output(b.key("mod:sednanite_ore"), 1);
        b.endRecipe("condenser", "machine", null, "test", false, false);
        // The fluid is produced by nothing, so the condenser route prices at infinity.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:sednanite_ore"));
        b.endSlot();
        b.output(b.key("fluid:unobtainable"), 1);
        b.endRecipe("cycle", "machine", null, "test", false, false);
        b.beginOreGroup("oreSednanite");
        b.oreMember(b.key("mod:sednanite_ore"));
        b.endOreGroup();
        RecipeGraph graph = b.build();

        CostTable table = Cost.estimate(graph, new CostInputs());
        assertEquals(Cost.BASE_RAW_COST, priceOf(graph, table, "mod:sednanite_ore"), 0.0);
    }

    @Test
    public void miningOnlyEverLowersAPriceSoStockAndAGeneratorStillWin() {
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("oreIron");
        b.oreMember(b.key("mod:iron_ore"));
        b.endOreGroup();
        b.key("mod:iron_ore");
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph,
                new CostInputs().have(graph.keyId("mod:iron_ore")));
        assertEquals(0.0, priceOf(graph, table, "mod:iron_ore"), 0.0);
    }

    // -- dimension gates, which is #112 ------------------------------------------------------------

    @Test
    public void anOreOnAPlanetYouHaveNeverVisitedCostsTheTrip() {
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("oreSednanite");
        b.oreMember(b.key("mod:sednanite_ore"));
        b.endOreGroup();
        RecipeGraph graph = b.build();
        int ore = graph.keyId("mod:sednanite_ore");

        assertEquals(Cost.BASE_RAW_COST + Cost.DIMENSION_COST,
                Cost.estimate(graph, new CostInputs().dimensionGated(ore)).cost(ore), 0.0);
        // Visiting it lifts the gate with no edit to any list: the pack data did not move,
        // the world state did.
        assertEquals(Cost.BASE_RAW_COST,
                Cost.estimate(graph, new CostInputs()).cost(ore), 0.0);
    }

    @Test
    public void anOreNothingProducesIsStillGated() {
        // THE STRONGEST GATE CASE, and it silently did nothing before it was pinned. The leaf
        // rule runs FIRST and the world-ore rule is a `min`, so a gated ore with no producer
        // kept 1.0: the gate computed, the plan's note appeared, and no price moved.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:sednanite_ore"));
        b.endSlot();
        b.output(b.key("mod:ingot"), 1);
        b.endRecipe("smelt", "furnace", null, "test", false, false);
        b.beginOreGroup("oreSednanite");
        b.oreMember(b.key("mod:sednanite_ore"));
        b.endOreGroup();
        RecipeGraph graph = b.build();
        int ore = graph.keyId("mod:sednanite_ore");

        CostTable table = Cost.estimate(graph, new CostInputs().dimensionGated(ore));
        assertEquals(Cost.BASE_RAW_COST + Cost.DIMENSION_COST, table.cost(ore), 0.0);
        // And the surcharge reaches what is made from it.
        assertTrue(priceOf(graph, table, "mod:ingot") > Cost.DIMENSION_COST);
    }

    @Test
    public void stockStillBeatsTheGateBecauseTheSurchargeOnlyRaisesAFloor() {
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("oreSednanite");
        b.oreMember(b.key("mod:sednanite_ore"));
        b.endOreGroup();
        RecipeGraph graph = b.build();
        int ore = graph.keyId("mod:sednanite_ore");
        CostTable table = Cost.estimate(graph,
                new CostInputs().dimensionGated(ore).have(ore));
        assertEquals(0.0, table.cost(ore), 0.0);
    }

    // -- the off-world toll, which is #248 -----------------------------------------------------------

    /** Two `oreIron` members, one in the overworld and one only in the Nether. */
    private static RecipeGraph tiedIronGraph(boolean tolled) {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        // DUMP ORDER PUTS THE NETHER ONE FIRST, which is the point of the fixture: on a
        // perfect tie the ranker returns the first alternative, so listing the overworld ore
        // first would pass with no toll at all.
        b.alternative(b.key("cyclicmagic:nether_iron_ore"));
        b.alternative(b.key("minecraft:iron_ore"));
        b.endSlot();
        b.output(b.key("minecraft:iron_ingot"), 1);
        b.endRecipe("smelt", "furnace", null, "test", false, false);
        b.beginOreGroup("oreIron");
        b.oreMember(b.key("cyclicmagic:nether_iron_ore"));
        b.oreMember(b.key("minecraft:iron_ore"));
        b.endOreGroup();
        if (tolled) {
            b.offworldOre(b.key("cyclicmagic:nether_iron_ore"));
        }
        return b.build();
    }

    @Test
    public void anOreBehindAPortalCostsMoreThanTheIdenticalOreOutside() {
        RecipeGraph graph = tiedIronGraph(true);
        CostTable table = Cost.estimate(graph, new CostInputs());
        assertEquals(Cost.BASE_RAW_COST, priceOf(graph, table, "minecraft:iron_ore"), 0.0);
        assertEquals(Cost.BASE_RAW_COST + Cost.OVERWORLD_TOLL,
                priceOf(graph, table, "cyclicmagic:nether_iron_ore"), 0.0);
    }

    @Test
    public void withoutTheTollTheyTieAndDumpOrderDecides() {
        // The bug, pinned. Dropping the toll data restores the reported behaviour, which is
        // what makes the fixture above evidence rather than decoration.
        RecipeGraph graph = tiedIronGraph(false);
        CostTable table = Cost.estimate(graph, new CostInputs());
        assertEquals(priceOf(graph, table, "minecraft:iron_ore"),
                priceOf(graph, table, "cyclicmagic:nether_iron_ore"), 0.0);
    }

    @Test
    public void liftingTheGateRemovesTheGateAndLeavesTheToll() {
        // THE WHOLE DIFFERENCE FROM #112, and it has to be asserted as a DIFFERENCE rather
        // than as "the ungated price is still high" -- that would restate the test above and
        // pass with no gate in the picture at all. Going to the dimension must subtract
        // exactly DIMENSION_COST and nothing else, leaving the portal still paid for.
        RecipeGraph graph = tiedIronGraph(true);
        int ore = graph.keyId("cyclicmagic:nether_iron_ore");
        double gated = Cost.estimate(graph, new CostInputs().dimensionGated(ore)).cost(ore);
        CostTable lifted = Cost.estimate(graph, new CostInputs());
        double visited = lifted.cost(ore);
        assertEquals(Cost.DIMENSION_COST, gated - visited, 0.0);
        assertEquals(Cost.OVERWORLD_TOLL, visited - Cost.BASE_RAW_COST, 0.0);
        assertTrue(visited > priceOf(graph, lifted, "minecraft:iron_ore"));
    }

    @Test
    public void aGateAndATollAreBothCharged() {
        RecipeGraph graph = tiedIronGraph(true);
        int ore = graph.keyId("cyclicmagic:nether_iron_ore");
        CostTable table = Cost.estimate(graph, new CostInputs().dimensionGated(ore));
        assertEquals(Cost.BASE_RAW_COST + Cost.DIMENSION_COST + Cost.OVERWORLD_TOLL,
                table.cost(ore), 0.0);
    }

    @Test
    public void stockStillBeatsTheTollBecauseItOnlyRaisesAFloor() {
        RecipeGraph graph = tiedIronGraph(true);
        int ore = graph.keyId("cyclicmagic:nether_iron_ore");
        assertEquals(0.0, Cost.estimate(graph, new CostInputs().have(ore)).cost(ore), 0.0);
    }

    // -- transmutation, which is #50 ----------------------------------------------------------------

    @Test
    public void anItemTheNetworkHasLearnedIsCheaperThanFarmingTheDungeon() {
        // `erebus:materials` is the reported case: its only "recipe" is a pseudo-item saying
        // it drops in a dungeon, so the solver dead-ends while the network already makes it.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "drop", "loot", "mod:material", 1, "mod:unobtainable_token", 1);
        RecipeGraph graph = b.build();
        int material = graph.keyId("mod:material");
        CostTable table = Cost.estimate(graph, new CostInputs().emcAvailable(material));
        assertEquals(Cost.EMC_COST, table.cost(material), 0.0);
    }

    // -- tokens, which is #105 --------------------------------------------------------------------

    @Test
    public void aLockedChapterNoLongerPricesLikeACobblestone() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "mod:out", 1, "mod:gate_token", 1);
        RecipeGraph graph = b.build();
        int token = graph.keyId("mod:gate_token");
        assertEquals(Cost.GATE_COST,
                Cost.estimate(graph, new CostInputs().token(token, Tokens.GATE)).cost(token),
                0.0);
        assertEquals(Cost.LOOT_COST,
                Cost.estimate(graph, new CostInputs().token(token, Tokens.LOOT)).cost(token),
                0.0);
        // A hint stands in for one ordinary material and a method's machine is already
        // charged, so both stay exactly where the leaf rule put them.
        assertEquals(Cost.BASE_RAW_COST,
                Cost.estimate(graph, new CostInputs().token(token, Tokens.HINT)).cost(token),
                0.0);
        // With no token map at all, every price stays exactly where it was.
        assertEquals(Cost.BASE_RAW_COST,
                Cost.estimate(graph, new CostInputs()).cost(token), 0.0);
    }

    @Test
    public void aTokenIsSkippedWhenSomethingAlreadyPricedItBelowARawLeaf() {
        // Stock or an infinite generator is a stronger claim about this world than a curated
        // list is. Nonsense for a placeholder in practice, and free to honour.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "mod:out", 1, "mod:gate_token", 1);
        RecipeGraph graph = b.build();
        int token = graph.keyId("mod:gate_token");
        CostTable table = Cost.estimate(graph,
                new CostInputs().have(token).token(token, Tokens.GATE));
        assertEquals(0.0, table.cost(token), 0.0);
    }

    // -- oredict slots -------------------------------------------------------------------------------

    @Test
    public void aQuantityPastIntRangeIsPricedRatherThanWrappedNegative() {
        // The solver passes a computed NEED here, not a slot's own quantity, and this pack has
        // a recipe yielding 60,466,176 at once -- so a deep chain really can multiply past
        // 2^31. An int parameter wraps NEGATIVE, `Math.max(qty, 1)` then reports 1, and the
        // dearest slot in a plan prices as the cheapest.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "mod:out", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());
        int leaf = graph.keyId("mod:leaf");

        long huge = 3L * Integer.MAX_VALUE;
        assertEquals(Cost.BASE_RAW_COST * huge, Cost.inputCost(table, graph, leaf, huge), 0.0);
        // Monotonic across the boundary, which a wrap would not be.
        assertTrue(Cost.inputCost(table, graph, leaf, huge)
                > Cost.inputCost(table, graph, leaf, Integer.MAX_VALUE));
        // And a fluid still scales to buckets at that size.
        int water = graph.keyId("mod:leaf");
        assertEquals(Cost.inputCost(table, graph, water, 1L), Cost.BASE_RAW_COST, 0.0);
    }

    @Test
    public void anOredictSlotCostsItsCheapestMemberAndFallsBackToItsOwnLeafPrice() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("ore:ingotIron"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "crafting", null, "test", false, false);
        b.beginOreGroup("ingotIron");
        b.oreMember(b.key("mod:dear_ingot"));
        b.oreMember(b.key("mod:cheap_ingot"));
        b.endOreGroup();
        RecipeGraph graph = b.build();

        CostTable table = Cost.estimate(graph,
                new CostInputs().have(graph.keyId("mod:cheap_ingot")));
        // The member in stock sets the slot's price, not the group's own leaf value.
        assertEquals(0.0, Cost.inputCost(table, graph, graph.keyId("ore:ingotIron"), 1), 0.0);
        // With no member priced at all, the group falls back to its OWN leaf price -- one of
        // the two places where "absent from the table" means a raw leaf rather than infinity.
        GraphBuilder bare = new GraphBuilder();
        bare.beginRecipe();
        bare.beginSlot(1, "item");
        bare.alternative(bare.key("ore:nothing"));
        bare.endSlot();
        bare.output(bare.key("mod:out"), 1);
        bare.endRecipe("r", "crafting", null, "test", false, false);
        RecipeGraph empty = bare.build();
        CostTable bareTable = Cost.estimate(empty, new CostInputs());
        assertEquals(Cost.BASE_RAW_COST,
                Cost.inputCost(bareTable, empty, empty.keyId("ore:nothing"), 1), 0.0);
    }

    // -- reshaped-only keys, which is #110 -----------------------------------------------------------

    @Test
    public void aKeyOnlyAChiselTableMakesGetsItsLeafPriceBackAndPropagatesIt() {
        // The group knows how to CONVERT this material and no way to obtain any of it, which
        // is exactly what it knew before the table was expanded -- and back then the key was a
        // leaf. #110 tried to answer this at build time and left 168 keys unreachable that
        // had been finite.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:variant_b"));
        b.endSlot();
        b.output(b.key("mod:variant_a"), 1);
        b.endRecipe("chisel_ab", "chisel", null, "test", false, true);
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:variant_a"));
        b.endSlot();
        b.output(b.key("mod:variant_b"), 1);
        b.endRecipe("chisel_ba", "chisel", null, "test", false, true);
        // Something outside the closed group consumes one of them, so the restored price has
        // somewhere to propagate to.
        recipe(b, "use", "crafting", "mod:thing", 1, "mod:variant_a", 1);
        RecipeGraph graph = b.build();

        CostTable table = Cost.estimate(graph, new CostInputs());
        assertEquals(Cost.BASE_RAW_COST, priceOf(graph, table, "mod:variant_a"), 0.0);
        assertTrue(Double.isFinite(priceOf(graph, table, "mod:thing")));
    }

    // -- machine entry costs, which is #86 and #93 -------------------------------------------------------

    @Test
    public void aCategoryWithNoStatesAtAllChargesTheUngatedFigure() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "machine", "mod:out", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();
        assertEquals(Cost.UNGATED_MACHINE_COST + Cost.BASE_RAW_COST,
                priceOf(graph, Cost.estimate(graph, new CostInputs()), "mod:out"), 0.0);
    }

    @Test
    public void theEntryCostRidesOnTheTableSoTheRankerCannotDriftFromTheRelaxation() {
        // `estimate` and `recipe_cost` held separate MACHINE_COST lookups once, so a change
        // to how a machine is priced applied to one and not the other -- and the symptom is a
        // solver expanding a route the ranker never priced.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "machine", "mod:out", 1, "mod:leaf", 1);
        recipe(b, "make_machine", "crafting", "mod:machine_block", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();

        java.util.Map<Integer, int[]> items = new java.util.HashMap<Integer, int[]>();
        items.put(Integer.valueOf(graph.categoryId("machine")),
                new int[] {graph.keyId("mod:machine_block")});
        CostTable table = Cost.estimate(graph, new CostInputs().machineItems(items));

        int category = graph.categoryId("machine");
        assertTrue(table.hasMachineEntry(category));
        double entry = table.machineEntry(category);
        // Derived from what the machine costs to build, and inside the buildable band.
        assertTrue(entry >= Cost.MACHINE_COST[MachineInfo.BUILDABLE]);
        assertTrue(entry < Cost.PRICED_CEILING);
        // The ranker charges the SAME number the relaxation used.
        assertEquals(entry, Cost.categoryEntryCost(category, null, table), 0.0);
        assertEquals(entry + Cost.BASE_RAW_COST,
                Cost.recipeCost(table, graph, 0, null, null), 0.0);
    }

    @Test
    public void theCheapestCandidateSetsTheCategorysPrice() {
        // Several blocks open one category and a player would build the cheapest that works,
        // so pricing the first listed would charge for a machine nobody would choose.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "machine", "mod:out", 1, "mod:leaf", 1);
        // Dear by needing 500 of the leaf, NOT by yielding fewer: a big output makes a recipe
        // CHEAPER per unit, so a fixture built that way would assert the opposite of what it
        // reads as.
        recipe(b, "dear", "crafting", "mod:dear_block", 1, "mod:leaf", 500);
        recipe(b, "cheap", "crafting", "mod:cheap_block", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();

        java.util.Map<Integer, int[]> items = new java.util.HashMap<Integer, int[]>();
        items.put(Integer.valueOf(graph.categoryId("machine")),
                new int[] {graph.keyId("mod:dear_block"), graph.keyId("mod:cheap_block")});
        CostTable both = Cost.estimate(graph, new CostInputs().machineItems(items));

        items.put(Integer.valueOf(graph.categoryId("machine")),
                new int[] {graph.keyId("mod:dear_block")});
        CostTable dearOnly = Cost.estimate(graph, new CostInputs().machineItems(items));

        int category = graph.categoryId("machine");
        assertTrue(both.machineEntry(category) < dearOnly.machineEntry(category));
    }

    @Test
    public void aMachineItemThatNeverPricesChargesTheUnpricedFigureNotTheWall() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "machine", "mod:out", 1, "mod:leaf", 1);
        // Interned but produced by nothing and consumed by nothing, so it never prices.
        b.key("mod:ghost_block");
        RecipeGraph graph = b.build();

        java.util.Map<Integer, int[]> items = new java.util.HashMap<Integer, int[]>();
        items.put(Integer.valueOf(graph.categoryId("machine")),
                new int[] {graph.keyId("mod:ghost_block")});
        CostTable table = Cost.estimate(graph, new CostInputs().machineItems(items));
        assertEquals(Cost.UNPRICED_MACHINE_COST,
                table.machineEntry(graph.categoryId("machine")), 0.0);
    }

    // -- picking an alternative, which is the other half of #29 ---------------------------------------

    @Test
    public void theRankerPricesTheAlternativeTheCallerWillActuallyExpand() {
        // Pricing a slot at its cheapest option and then expanding a different one is how "1
        // Iron Ingot" became "cast 1,296 mB of molten iron". Nothing was mispriced; the price
        // was simply for a route nobody took.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:dear_option"));
        b.alternative(b.key("mod:cheap_option"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "crafting", null, "test", false, false);
        RecipeGraph graph = b.build();
        final int dear = graph.keyId("mod:dear_option");
        CostTable table = Cost.estimate(graph,
                new CostInputs().have(graph.keyId("mod:cheap_option")));

        // Left to itself the ranker takes the free one.
        assertEquals(Cost.UNGATED_MACHINE_COST,
                Cost.recipeCost(table, graph, 0, null, null), 0.0);
        // Told which one the caller will expand, it prices THAT one.
        assertEquals(Cost.UNGATED_MACHINE_COST + Cost.BASE_RAW_COST,
                Cost.recipeCost(table, graph, 0, null, new Cost.AlternativePicker() {
                    @Override
                    public int pick(int slot) {
                        return dear;
                    }
                }), 0.0);
    }

    @Test
    public void theFirstCheapestAlternativeWinsATieBecausePythonsMinDoes() {
        // With several unreachable alternatives every candidate is +inf, so a `<=` comparison
        // would silently take the LAST one and the two languages would disagree about which
        // route a plan describes.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:first"));
        b.alternative(b.key("mod:second"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "crafting", null, "test", false, false);
        RecipeGraph graph = b.build();
        CostTable table = Cost.estimate(graph, new CostInputs());
        // Both are leaves at the same price, so the tie is real.
        assertEquals(priceOf(graph, table, "mod:first"), priceOf(graph, table, "mod:second"),
                0.0);
        assertEquals(graph.keyId("mod:first"), Cost.cheapestAlternative(table, graph, 0));
    }

    @Test
    public void anUnreachableIngredientMakesTheWholeRecipeUnreachable() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:ghost"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "crafting", null, "test", false, false);
        // `mod:ghost` is an output of a recipe that itself cannot run, so it is produced (and
        // therefore not a leaf) yet never priced.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:out"));
        b.endSlot();
        b.output(b.key("mod:ghost"), 1);
        b.endRecipe("cycle", "crafting", null, "test", false, false);
        RecipeGraph graph = b.build();

        CostTable table = Cost.estimate(graph, new CostInputs());
        assertTrue(Double.isInfinite(priceOf(graph, table, "mod:out")));
        assertTrue(Double.isInfinite(Cost.recipeCost(table, graph, 0, null, null)));
    }

    /**
     * A 15-hop chain whose recipes are listed in REVERSE dependency order.
     *
     * THE ORDER IS THE WHOLE POINT. Relaxation mutates the table in place as it walks the
     * recipe list, so a chain listed in dependency order prices end to end in ONE pass -- and
     * a "deep chain" fixture built that way asserts nothing about the pass count at all. Read
     * backwards, each pass can only advance one hop, which is what makes the ceiling
     * observable.
     */
    private static RecipeGraph reversedChain(int hops) {
        GraphBuilder b = new GraphBuilder();
        for (int step = hops - 1; step >= 1; step--) {
            recipe(b, "r" + step, "crafting", "mod:step" + step, 1, "mod:step" + (step - 1), 1);
        }
        recipe(b, "r0", "crafting", "mod:step0", 1, "mod:leaf", 1);
        return b.build();
    }

    @Test
    public void thePassCountIsHonouredSoADeepChainCanBeLeftUnpriced() {
        // The other side of the pass-count claim: with too few passes the deep end genuinely
        // does not price, which is what 6 passes did to MeatballCraft's chemistry. A test
        // that only showed 20 passes working could not tell a working ceiling from an ignored
        // one.
        RecipeGraph graph = reversedChain(15);
        CostTable shallow = Cost.estimate(graph, new CostInputs().passes(2));
        assertTrue(Double.isFinite(priceOf(graph, shallow, "mod:step0")));
        assertTrue(Double.isInfinite(priceOf(graph, shallow, "mod:step14")));
    }

    @Test
    public void stockFlowsFromOneEvidenceObjectIntoBothHalvesOfThePlanner() {
        // ONE definition of "the player holds this". Machine resolution and the cost seed used
        // to filter separately, which is two chances to disagree about whether owning nothing
        // counts as owning something.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "crafting", "mod:out", 1, "mod:leaf", 1);
        RecipeGraph graph = b.build();
        Evidence evidence = new Evidence().stock("mod:leaf", 5).stock("mod:none", 0)
                .stock("mod:not_in_graph", 9);

        CostInputs inputs = new CostInputs();
        for (Integer key : evidence.stockKeyIds(graph)) {
            inputs.have(key.intValue());
        }
        CostTable table = Cost.estimate(graph, inputs);
        assertEquals(0.0, priceOf(graph, table, "mod:leaf"), 0.0);
        // A zero holding is not a holding, and a key the graph never saw is simply dropped.
        assertEquals(1, evidence.stockKeyIds(graph).size());
    }

    @Test
    public void aDeepChainStillPricesBecauseThereAreEnoughPasses() {
        // MeatballCraft's chemistry runs 10+ hops deep and 6 passes left the deep end of
        // every chain unpriced. The last item gets a price at pass 12 on the real graph, and
        // nothing new appears after 20 -- which is where the ceiling comes from.
        RecipeGraph graph = reversedChain(15);
        CostTable table = Cost.estimate(graph, new CostInputs());
        assertTrue(Double.isFinite(priceOf(graph, table, "mod:step14")));
        // Each hop is one machine entry dearer than the last, so the price also proves the
        // chain was walked rather than short-circuited.
        assertEquals(Cost.UNGATED_MACHINE_COST * 15 + Cost.BASE_RAW_COST,
                priceOf(graph, table, "mod:step14"), 0.0);
    }
}
