package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Adjacency and naming, against a hand-built fixture small enough to reason about.
 *
 * WHY A FIXTURE RATHER THAN THE REAL GRAPH. `data/graph.json` is 110 MB, is not in git, and
 * changes every time the game is re-dumped, so a test asserting anything about it is a test
 * that fails for reasons unrelated to this code. The fixture below instead carries exactly
 * the shapes `model.py` documents as having been got wrong: the oredict widening, the
 * wildcard-meta fallback and the one place it must NOT fire, the container-transfer
 * suppression, the live-key widenings, and each branch of the display-name fallback chain.
 *
 * A case here that looks arbitrary is a case someone paid for. The comments say which.
 */
public class RecipeGraphTest {

    private static RecipeGraph graph;

    /** Recipe ids, in the order they are added, so assertions can name them. */
    private static final int IRON_BLOCK_FROM_INGOTS = 0;
    private static final int WOOL_WILDCARD_OUT = 1;
    private static final int CARPET_FROM_WOOL_WILDCARD = 2;
    private static final int EMPTY_THE_CAN = 3;
    private static final int BOIL_BORIC_ACID = 4;
    private static final int CHISEL_LAPIS_VARIANT = 5;
    private static final int CHISEL_SLAB_VARIANT = 6;
    private static final int CRAFT_A_CHISEL_SLAB = 7;
    private static final int DOUBLE_OUTPUT = 8;
    private static final int FILL_THE_CAN = 9;
    private static final int MAKE_A_BARE_VARIANT = 10;
    private static final int THING_WILDCARD_OUT = 11;
    private static final int RECIPE_COUNT = 12;

    @BeforeClass
    public static void buildFixture() {
        GraphBuilder b = new GraphBuilder();

        // Nine slots of one ingredient, which is what a shaped 3x3 looks like in the dump.
        // `by_input` must still count this recipe ONCE against `ore:ingotIron`.
        b.beginRecipe();
        for (int slot = 0; slot < 9; slot++) {
            b.beginSlot(1, "item");
            b.alternative(b.key("ore:ingotIron"));
            b.endSlot();
        }
        b.output(b.key("minecraft:iron_block"), 1);
        b.endRecipe("fixture:iron_block", "crafting_shaped", "Crafting (shaped)", "jar_json",
                false, false);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:string"));
        b.endSlot();
        b.output(b.key("minecraft:wool:*"), 1);
        b.endRecipe("fixture:wool", "crafting_shaped", null, "jar_json", false, false);

        b.beginRecipe();
        b.beginSlot(2, "item");
        b.alternative(b.key("minecraft:wool:*"));
        b.endSlot();
        b.output(b.key("minecraft:carpet"), 3);
        b.endRecipe("fixture:carpet", "crafting_shaped", null, "jar_json", false, false);

        // A container empty: the can's MATERIAL comes back, not an empty can, and the fluid
        // is NOT produced by this in any sense the solver may use.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("forestry:can:1#48a337d94489"));
        b.endSlot();
        b.output(b.key("forestry:ingot_tin"), 1);
        b.output(b.key("fluid:nethengeic_fluid"), 1000);
        b.endRecipe("fixture:squeeze", "forestry.squeezer", "Squeezer", "hei_dump", true,
                false);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:sand"));
        b.endSlot();
        b.output(b.key("fluid:boric_acid"), 500);
        b.endRecipe("fixture:boric", "nuclearcraft.chemistry", "Chemical Reactor", "hei_dump",
                false, false);

        // Only a chisel table makes this, so the graph knows how to CONVERT it and not how
        // to obtain any of it.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:lapis_block"));
        b.endSlot();
        b.output(b.key("chisel:lapis:1"), 1);
        b.endRecipe("fixture:chisel_lapis", "chisel.chiseling", "Chisel", "hei_dump", false,
                true);

        // The same shape, but something else also makes it -- so it is NOT reshaped-only,
        // however unreachable that other producer turns out to be.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:stone_slab"));
        b.endSlot();
        b.output(b.key("chisel:slab:1"), 1);
        b.endRecipe("fixture:chisel_slab", "chisel.chiseling", "Chisel", "hei_dump", false,
                true);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:stone"));
        b.endSlot();
        b.output(b.key("chisel:slab:1"), 2);
        b.endRecipe("fixture:craft_slab", "crafting_shaped", null, "jar_json", false, false);

        // A recipe listing one key in two output stacks really does say so twice.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:log"));
        b.endSlot();
        b.output(b.key("minecraft:planks"), 4);
        b.output(b.key("minecraft:planks"), 2);
        b.endRecipe("fixture:planks", "crafting_shapeless", null, "jar_json", false, false);

        // A container FILL, which is the other direction the fluid-name derivation reads,
        // and the only slot in this fixture with the `fluid` role.
        b.beginRecipe();
        b.beginSlot(1000, "fluid");
        b.alternative(b.key("fluid:nethengeic_fluid"));
        b.endSlot();
        b.beginSlot(1, "item");
        b.alternative(b.key("forestry:can"));
        b.endSlot();
        b.output(b.key("forestry:can:1#48a337d94489"), 1);
        b.endRecipe("fixture:fill", "forestry.bottler", "Bottler", "hei_dump", true, false);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:clay"));
        b.endSlot();
        b.output(b.key("mod:bare#deadbeef1234"), 1);
        b.endRecipe("fixture:bare_variant", "crafting_shaped", null, "jar_json", false, false);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:dirt"));
        b.endSlot();
        b.output(b.key("mod:thing:*"), 1);
        b.endRecipe("fixture:thing", "crafting_shaped", null, "jar_json", false, false);

        b.beginOreGroup("ingotIron");
        b.oreMember(b.key("minecraft:iron_ingot"));
        // Named nowhere and used by no recipe directly. It is reachable ONLY through the
        // group, which is the widening `live_keys` has to make.
        b.oreMember(b.key("thermalfoundation:material:32"));
        b.endOreGroup();

        b.beginOreGroup("oreIron");
        b.oreMember(b.key("minecraft:iron_ore"));
        b.endOreGroup();

        // `chisel:iron_block` is a decorative block in `blockIron`. It must NOT read as a
        // world ore, which is the whole reason the prefix test is `ore` and not "any group".
        b.beginOreGroup("blockIron");
        b.oreMember(b.key("chisel:iron_block"));
        b.endOreGroup();
        b.markOreGuessed("blockIron");

        // Named ONLY as a JEI catalyst, because its own recipes output a discriminated
        // variant. Hiding it would make the Pulverizer unsearchable.
        b.beginCatalyst("thermalexpansion.pulverizer");
        b.catalystKey(b.key("thermalexpansion:machine:1"));
        b.endCatalyst();
        b.categoryMod("thermalexpansion.pulverizer", "Thermal Expansion");

        b.name("minecraft:iron_ingot", "Iron Ingot");
        b.name("minecraft:wool", "Wool");
        // Named, alive nowhere: the shape `live_keys` exists to keep out of search.
        b.name("aoa3:dead_thing", "Dead Thing");
        // A base whose only produced form is an NBT variant. `live_keys` is deliberately NOT
        // widened from a bare key to its variants, so this one stays dead.
        b.name("mod:bare", "Bare Thing");
        // Named and dead on its own, but its base is wildcarded by a live recipe.
        b.name("minecraft:wool:5", "Wool");
        // items.csv stores aspect-parameterised names as format strings.
        b.name("thaumadditions:vis_pod", "%s Vis Pod");
        b.key("thaumadditions:vis_pod#0116bb2287a7");
        // What the game renders for a block whose mod shipped no lang entry.
        b.name("modularmachinery:fission_controller", "tile.null.name");
        // The filled container that names the fluid it holds. See #103.
        b.name("forestry:can:1#48a337d94489", "Strong Mythic Essence Can");
        b.key("essentia:aer");
        // A concrete meta no recipe and no name mentions. It exists because the wildcard
        // recipe covers it, which is the whole point of the fallback.
        b.key("minecraft:wool:3");
        b.key("mod:thing:1");
        b.key("mod:thing:1#abcdef123456");

        b.dumpSchema(4);
        b.dumpVersion("0.8.0");
        graph = b.build();
    }

    private static int id(String key) {
        int keyId = graph.keyId(key);
        assertTrue("fixture does not know " + key, keyId >= 0);
        return keyId;
    }

    private static List<Integer> producers(String key) {
        IntArray out = new IntArray();
        graph.producers(id(key), out);
        return toList(out);
    }

    private static List<Integer> realProducers(String key) {
        IntArray out = new IntArray();
        graph.realProducers(id(key), out);
        return toList(out);
    }

    private static List<Integer> consumers(String key) {
        IntArray out = new IntArray();
        graph.consumers(id(key), out);
        return toList(out);
    }

    private static List<Integer> toList(IntArray values) {
        List<Integer> out = new ArrayList<Integer>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(Integer.valueOf(values.get(i)));
        }
        return out;
    }

    private static List<Integer> recipeList(int... ids) {
        List<Integer> out = new ArrayList<Integer>(ids.length);
        for (int recipe : ids) {
            out.add(Integer.valueOf(recipe));
        }
        return out;
    }

    private static IntArray rowOf(Csr csr, int row) {
        IntArray out = new IntArray();
        csr.appendRow(row, out);
        return out;
    }

    // -- adjacency ---------------------------------------------------------------------

    @Test
    public void aRecipeIsCountedOncePerKeyItConsumesHoweverManySlotsNameIt() {
        // Nine grid cells of one ingredient. Nine entries here would make the "used in"
        // count for every shaped recipe's filler ingredient nine times the truth.
        assertEquals(recipeList(IRON_BLOCK_FROM_INGOTS), consumers("ore:ingotIron"));
    }

    @Test
    public void aRecipeIsCountedOncePerOutputStackBecauseItReallyDoesSayItTwice() {
        assertEquals(recipeList(DOUBLE_OUTPUT, DOUBLE_OUTPUT), producers("minecraft:planks"));
    }

    @Test
    public void aConcreteMetaFindsTheRecipesThatWildcardItsBase() {
        assertEquals(recipeList(WOOL_WILDCARD_OUT), producers("minecraft:wool:3"));
        assertEquals(recipeList(CARPET_FROM_WOOL_WILDCARD), consumers("minecraft:wool:3"));
        // The bare base takes the same fallback -- `split_key` reads it as meta 0, which is
        // still "not the wildcard".
        assertEquals(recipeList(WOOL_WILDCARD_OUT), producers("minecraft:wool"));
    }

    @Test
    public void anNbtVariantDoesNotInheritItsBasesWildcardRecipes() {
        // `split_key` does not strip the discriminator, so `mod:thing:1#abcdef123456` reads
        // as meta 0 and its "base" is the whole key. That is what stops a Pulverizer with
        // different augments standing in for the one a recipe called for.
        assertEquals(-1, graph.wildcardSibling(id("mod:thing:1#abcdef123456")));
        assertTrue(producers("mod:thing:1#abcdef123456").isEmpty());
        // The undiscriminated sibling at the same meta does take the fallback.
        assertEquals(recipeList(THING_WILDCARD_OUT), producers("mod:thing:1"));
    }

    @Test
    public void anItemIsReachableThroughEveryOredictGroupItBelongsTo() {
        assertEquals(recipeList(IRON_BLOCK_FROM_INGOTS), consumers("minecraft:iron_ingot"));
        Csr ores = graph.oresOf();
        int ingot = id("minecraft:iron_ingot");
        assertEquals(1, ores.count(ingot));
        assertEquals("ingotIron", graph.oreGroupName(ores.at(ores.start(ingot))));
    }

    @Test
    public void aGroupNoRecipeMentionsDoesNotBreakTheConsumerWidening() {
        // Nothing consumes `ore:blockIron`, so that key was never interned and the group's
        // key id is -1. Walking it must be a no-op rather than an index error.
        assertTrue(consumers("chisel:iron_block").isEmpty());
    }

    @Test
    public void aGuessedGroupIsFlaggedWithoutDisturbingTheGroupTable() {
        // An inferred membership is not authoritative and callers have to be able to say so.
        // The flag arrives BEFORE the group itself when the sections are read in sorted
        // order, which is what the deferred resolution in the builder exists to survive.
        assertEquals(3, graph.oreGroupCount());
        assertTrue(graph.isOreGuessed(graph.oreGroupId("blockIron")));
        assertFalse(graph.isOreGuessed(graph.oreGroupId("ingotIron")));
        assertEquals(1, rowOf(graph.oreMembers(), graph.oreGroupId("blockIron")).size());
    }

    // -- container transfers ---------------------------------------------------------------

    @Test
    public void emptyingAContainerNeverCountsAsProducingItsFluid() {
        assertEquals(recipeList(EMPTY_THE_CAN), producers("fluid:nethengeic_fluid"));
        // A fluid whose only route is a container empty correctly has no real producer, so
        // it comes out as NEED. That is the honest answer.
        assertTrue(realProducers("fluid:nethengeic_fluid").isEmpty());
    }

    @Test
    public void aTransferMayStillProduceAnItemBecauseFillingOneIsRealWork() {
        assertEquals(recipeList(EMPTY_THE_CAN), realProducers("forestry:ingot_tin"));
        assertEquals(recipeList(FILL_THE_CAN),
                realProducers("forestry:can:1#48a337d94489"));
    }

    @Test
    public void aRealFluidProducerSurvivesTheTransferFilter() {
        assertEquals(recipeList(BOIL_BORIC_ACID), realProducers("fluid:boric_acid"));
    }

    @Test
    public void theTwoFormsOfRealProducersAgree() {
        // #193. `realProducers` hands back the memoised row untouched for a non-fluid key,
        // which is valid only while `realProduction` excludes nothing but fluids. Compared over
        // EVERY key and EVERY recipe rather than reasoned about, because the reasoning is what
        // goes stale: widen the predicate and this branch silently stops honouring it.
        for (int keyId = 0; keyId < graph.keyCount(); keyId++) {
            String key = graph.key(keyId);
            List<Integer> filtered = new ArrayList<Integer>();
            for (int recipe : producers(key)) {
                if (graph.realProduction(recipe, keyId)) {
                    filtered.add(Integer.valueOf(recipe));
                }
            }
            assertEquals(key, filtered, realProducers(key));
        }
    }

    @Test
    public void realOutputIsWhatTheRelaxationCanReachAndNotWhatRealProducersFinds() {
        // #193's second predicate, and the difference between the two is load-bearing:
        // `Cost.relax` writes only keys a recipe LITERALLY outputs, so `realOutput` must NOT
        // widen to the wildcard sibling the way `realProducers` does. Answering yes for a
        // wildcard-only key would strand it at infinity with nothing able to price it -- 478
        // input alternatives on the reference graph.
        //
        // `mod:thing:1` is made only through `mod:thing:*`, which is exactly that shape.
        assertFalse(realProducers("mod:thing:1").isEmpty());
        assertFalse(graph.realOutput(id("mod:thing:1")));
        // And the fluid nothing but a can empty makes: in byOutput, and not a real output.
        assertTrue(graph.byOutput().count(id("fluid:nethengeic_fluid")) > 0);
        assertFalse(graph.realOutput(id("fluid:nethengeic_fluid")));
        // While a real producer is a real output.
        assertTrue(graph.realOutput(id("fluid:boric_acid")));
    }

    // -- world ores --------------------------------------------------------------------

    @Test
    public void onlyAnOrePrefixedGroupMakesItsMembersWorldOres() {
        assertTrue(graph.isWorldOre(id("minecraft:iron_ore")));
        // `blockIron` is where the decorative blocks live. Accepting it would readmit
        // exactly what the world-ore signal exists to demote.
        assertFalse(graph.isWorldOre(id("chisel:iron_block")));
        assertFalse(graph.isWorldOre(id("minecraft:iron_ingot")));
        assertEquals(1, graph.worldOreCount());
    }

    @Test
    public void theMiningPopulationIsTheRegistryPlusWhatTheWorldgenRecordsKnow() {
        // #270. `worldOres` is membership of the FINISHED registry, and the dimension records
        // are not a subset of it: `dimensions.shadow_ores` deliberately reaches a SECOND id
        // for a rock the registry does not list, because the pack removed it from its group
        // again. A key in that gap is still something you hit with a pick.
        GraphBuilder b = new GraphBuilder();
        b.beginOreGroup("oreRhenium");
        b.oreMember(b.key("contenttweaker:rhenium_ore"));
        b.endOreGroup();
        // The twin, in no group at all, with the worldgen record its anchor's gate spread.
        b.dimensionOre(b.key("contenttweaker:sub_block_holder_1:8"), 163, "Rhenia");
        b.key("mod:ingot");
        RecipeGraph g = b.build();

        assertTrue(g.isMineableOre(g.keyId("contenttweaker:rhenium_ore")));
        assertFalse("the twin is absent from the finished registry",
                g.isWorldOre(g.keyId("contenttweaker:sub_block_holder_1:8")));
        assertTrue("and is still an ore you mine",
                g.isMineableOre(g.keyId("contenttweaker:sub_block_holder_1:8")));
        // THE SCREEN HAS TO BE ABLE TO SAY NO, or its yes means nothing.
        assertFalse(g.isMineableOre(g.keyId("mod:ingot")));
    }

    // -- derived key sets --------------------------------------------------------------

    @Test
    public void aKeyReachableOnlyThroughAConsumedOredictGroupIsStillLive() {
        assertTrue(graph.isLive(id("thermalfoundation:material:32")));
    }

    @Test
    public void aKeyNamedOnlyAsAJeiCatalystIsStillLive() {
        assertTrue(graph.isLive(id("thermalexpansion:machine:1")));
    }

    @Test
    public void aMetaOfAWildcardedBaseIsLiveEvenWhenNoRecipeNamesIt() {
        assertTrue(graph.isLive(id("minecraft:wool:5")));
    }

    @Test
    public void aNamedKeyNothingTouchesIsDeadAndStaysOutOfTheLiveSet() {
        assertFalse(graph.isLive(id("aoa3:dead_thing")));
    }

    @Test
    public void theLiveSetIsDELIBERATELYNotWidenedFromABareKeyToItsVariants() {
        // Widening it would re-admit the duplicate search rows the live set exists to
        // remove. The bare key stays reachable by direct link and from the machines page.
        assertTrue(graph.isLive(id("mod:bare#deadbeef1234")));
        assertFalse(graph.isLive(id("mod:bare")));
    }

    @Test
    public void aKeyOnlyAVariantRecipeMakesIsReshapedOnly() {
        assertTrue(graph.isReshapedOnly(id("chisel:lapis:1")));
        assertEquals(recipeList(CHISEL_LAPIS_VARIANT), producers("chisel:lapis:1"));
    }

    @Test
    public void oneOrdinaryProducerIsEnoughToStopAKeyBeingReshapedOnly() {
        assertEquals(recipeList(CHISEL_SLAB_VARIANT, CRAFT_A_CHISEL_SLAB),
                producers("chisel:slab:1"));
        assertFalse(graph.isReshapedOnly(id("chisel:slab:1")));
        // A key nothing produces is not reshaped-only either; it is simply a leaf.
        assertFalse(graph.isReshapedOnly(id("minecraft:string")));
        assertEquals(1, graph.reshapedOnlyCount());
    }

    // -- display names -------------------------------------------------------------------

    @Test
    public void aRecordedNameIsUsedVerbatimAndCarriesNoTypePrefix() {
        assertEquals("Iron Ingot", graph.bareName(id("minecraft:iron_ingot")));
        assertEquals("Iron Ingot", graph.display(id("minecraft:iron_ingot")));
    }

    @Test
    public void anUnnamedKeyFallsBackToItsPrettifiedRegistryPath() {
        assertEquals("Iron Block", graph.bareName(id("minecraft:iron_block")));
    }

    @Test
    public void aNamedMetaSiblingIsLabelledWithItsMetaInBrackets() {
        assertEquals("Wool (3)", graph.bareName(id("minecraft:wool:3")));
    }

    @Test
    public void anAspectFormatStringIsFilledInRatherThanShownRaw() {
        // "%s Vis Pod" with no aspect to fill in loses the placeholder...
        assertEquals("Vis Pod", graph.bareName(id("thaumadditions:vis_pod")));
        // ...and with one, the digest is rendered as what it is rather than as line noise.
        assertEquals("variant 0116bb Vis Pod",
                graph.bareName(id("thaumadditions:vis_pod#0116bb2287a7")));
    }

    @Test
    public void anUnlocalizedLangKeyIsReplacedWhileTheKeyStaysNamed() {
        int controller = id("modularmachinery:fission_controller");
        // 268 unrelated items render as `tile.null.name` on the reference pack, so the label
        // is replaced -- but the key is KEPT and still counts as named, because deleting it
        // would drop the item out of search entirely, which is worse than an ugly name.
        assertEquals("Fission Controller", graph.bareName(controller));
        assertTrue(graph.hasName(controller));
        assertEquals("Fission Controller", graph.display(controller));
        assertEquals(1, graph.unlocalizedNameCount());
    }

    @Test
    public void aFluidTakesItsNameFromTheContainerItIsBottledInNotItsRegistryName() {
        // The registry name would prettify to "Nethengeic Fluid", which is the PRE-RENAME
        // identity of a different substance and unreachable by any search for what the pack
        // calls it. See #103.
        assertEquals("Strong Mythic Essence",
                graph.bareName(id("fluid:nethengeic_fluid")));
        assertEquals("[fluid] Strong Mythic Essence",
                graph.display(id("fluid:nethengeic_fluid")));
        // Two recipes vote for it, the empty and the fill, so both directions are read.
        assertEquals(1, graph.fluidNames().size());
    }

    @Test
    public void aFluidNoContainerTouchesKeepsItsPrettifiedRegistryName() {
        assertEquals("Boric Acid", graph.bareName(id("fluid:boric_acid")));
    }

    @Test
    public void nonItemKindsCarryABracketedTypePrefix() {
        assertEquals("[oredict] ingotIron", graph.display(id("ore:ingotIron")));
        assertEquals("[essentia] Aer", graph.display(id("essentia:aer")));
        assertEquals("ore", graph.kind(id("ore:ingotIron")));
        assertEquals("essentia", graph.kind(id("essentia:aer")));
        assertEquals("fluid", graph.kind(id("fluid:boric_acid")));
        assertEquals("item", graph.kind(id("minecraft:iron_ingot")));
    }

    // -- recipe attributes and side tables --------------------------------------------------

    @Test
    public void everyRecipeAttributeSurvivesTheIntRoundTrip() {
        RecipeStore recipes = graph.recipes();
        assertEquals(RECIPE_COUNT, recipes.count());
        assertEquals("fixture:squeeze", recipes.rid(EMPTY_THE_CAN));
        assertEquals("forestry.squeezer",
                graph.categoryName(recipes.categoryId(EMPTY_THE_CAN)));
        assertEquals("Squeezer", graph.machineName(recipes.machineId(EMPTY_THE_CAN)));
        assertEquals("hei_dump", graph.sourceName(recipes.sourceId(EMPTY_THE_CAN)));
        assertTrue(recipes.isTransfer(EMPTY_THE_CAN));
        assertFalse(recipes.isVariant(EMPTY_THE_CAN));
        assertTrue(recipes.isVariant(CHISEL_LAPIS_VARIANT));
        // A recipe that names no machine reports -1 rather than an empty string, so "no
        // machine" and "a machine called nothing" stay distinguishable.
        assertEquals(-1, recipes.machineId(WOOL_WILDCARD_OUT));
        assertEquals(4, graph.dumpSchema());
        assertEquals("0.8.0", graph.dumpVersion());
    }

    @Test
    public void everyAlternativeOfEverySlotSurvivesInAuthoredOrder() {
        RecipeStore recipes = graph.recipes();
        int slots = recipes.slotEnd(IRON_BLOCK_FROM_INGOTS)
                - recipes.slotStart(IRON_BLOCK_FROM_INGOTS);
        assertEquals(9, slots);
        int firstSlot = recipes.slotStart(IRON_BLOCK_FROM_INGOTS);
        assertEquals(1, recipes.altEnd(firstSlot) - recipes.altStart(firstSlot));
        assertEquals(id("ore:ingotIron"), recipes.altKeyAt(recipes.altStart(firstSlot)));
        assertEquals("item", graph.roleName(recipes.slotRoleId(firstSlot)));
        // The fill recipe's first slot is the one non-default role in the fixture.
        int fluidSlot = recipes.slotStart(FILL_THE_CAN);
        assertEquals("fluid", graph.roleName(recipes.slotRoleId(fluidSlot)));
        assertEquals(1000, recipes.slotQty(fluidSlot));
    }

    @Test
    public void aCategorysModDisplayNameIsCarriedSeparatelyFromItsUid() {
        int category = graph.categoryId("thermalexpansion.pulverizer");
        assertTrue(category >= 0);
        // NOT a registry modid: "Thermal Expansion" will never match `thermalexpansion:...`,
        // and machine identification must keep matching on the uid.
        assertEquals("Thermal Expansion", graph.categoryMod(category));
        assertEquals(Arrays.asList(Integer.valueOf(id("thermalexpansion:machine:1"))),
                toList(rowOf(graph.catalysts(), category)));
        // A category with recipes but no catalyst has no mod name rather than a blank one.
        assertEquals(null, graph.categoryMod(graph.categoryId("crafting_shaped")));
    }

    @Test
    public void theAccountedSizeIsPositiveAndAddsUpToItsParts() {
        GraphSizes sizes = graph.sizes();
        assertTrue(sizes.keyTable > 0);
        assertTrue(sizes.recipes > 0);
        assertTrue(sizes.names > 0);
        assertTrue(sizes.adjacency > 0);
        assertEquals(sizes.keyTable + sizes.recipes + sizes.names + sizes.adjacency
                + sizes.itemFacts + sizes.other, sizes.total());
    }
}
