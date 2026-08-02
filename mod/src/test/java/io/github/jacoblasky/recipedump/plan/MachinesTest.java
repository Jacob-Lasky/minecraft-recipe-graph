package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.util.Arrays;
import org.junit.Test;

/**
 * Machine identification, and the four states it has to keep apart.
 *
 * Nearly every case here is a NAMED FAILURE. Machine identification is a pile of heuristics
 * over strings mods were never obliged to make consistent, and each rule in it was added
 * after a specific wrong answer -- a Smeltery Controller the player was standing next to
 * reading as "buildable", a Pulverizer with no route, tinker_io's own machine declared to be
 * from another mod. The comments say which, because a rule with the reason stripped off looks
 * arbitrary and gets "simplified" back into the bug.
 */
public class MachinesTest {

    private static void recipe(GraphBuilder b, String rid, String category, String machine,
                               String output) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key(output), 1);
        b.endRecipe(rid, category, machine, "test", false, false);
    }

    // -- the text rules ------------------------------------------------------------------------

    @Test
    public void bothSourcesSpellingsOfHandCraftingAreRecognised() {
        // The JEI dump says `minecraft.crafting`, the offline jar reader says
        // `crafting_shaped`. Matching only one left 10,301 offline recipes gated behind a
        // machine the player was told they did not have.
        assertTrue(Machines.isHandCrafting("minecraft.crafting"));
        assertTrue(Machines.isHandCrafting("crafting_shaped"));
        assertTrue(Machines.isHandCrafting("crafting_shapeless"));
        assertFalse(Machines.isHandCrafting("techreborn.wire_mill"));
    }

    @Test
    public void aRunningStateSuffixIsStrippedSoVariantsCompareEqual() {
        // NuclearCraft ships `alloy_furnace_idle` and `_active` as separate items while the
        // placed tile entity is the bare name, so a literal comparison reports a machine you
        // are standing next to as merely "buildable".
        assertEquals("mod:alloy_furnace", Machines.normaliseBlock("mod:alloy_furnace_idle"));
        assertEquals("mod:alloy_furnace", Machines.normaliseBlock("mod:alloy_furnace_active"));
        // Repeatedly, to a fixed point: `_idle_off` has to reach the bare name too.
        assertEquals("mod:machine", Machines.normaliseBlock("mod:machine_idle_off"));
        assertEquals("mod:furnace", Machines.normaliseBlock("MOD:Furnace"));
    }

    @Test
    public void aLegacyTileEntityIdIsAlsoIndexedUnderTheModidItImplies() {
        // Forge namespaces a colon-less registration into `minecraft:`, so the save records
        // `minecraft:tconstruct.smeltery_controller` while JEI calls the machine
        // `tconstruct:smeltery_controller`, and the two can never compare equal.
        assertArrayEquals(new String[] {"minecraft:tconstruct.smeltery_controller",
                                        "tconstruct:smeltery_controller"},
                Machines.matchForms("minecraft:tconstruct.smeltery_controller"));
        // The state suffix sits on the END of the legacy path, so the promoted form has to be
        // normalised again -- `mod:machine`, not `mod:machine_idle`.
        assertArrayEquals(new String[] {"minecraft:mod.machine", "mod:machine"},
                Machines.matchForms("minecraft:mod.machine_idle"));
        // An id that already names its mod is not guessing at anything, so it is left alone.
        assertArrayEquals(new String[] {"agricraft:tile.crop"},
                Machines.matchForms("agricraft:tile.crop"));
    }

    @Test
    public void aModidContainingAnUnderscoreIsStillItsOwnMod() {
        // `tinker_io:smart_output` tokenises to `tinker`, so a first-token comparison declares
        // tinker_io's own machine to be from a different mod. Substring on the squashed form
        // is the only comparison that survives every separator a uid might use.
        assertTrue(Machines.sameMod("tinker_io.smeltery", "tinker_io:smart_output"));
        assertTrue(Machines.sameMod("TechReborn.WireMill", "techreborn:wire_mill"));
        assertFalse(Machines.sameMod("extrautils2.furnace", "minecraft:furnace"));
    }

    @Test
    public void aCamelCaseUidIsSplitBeforeItsCaseIsThrownAway() {
        // Lowercasing alone gives `wiremill`, which matches nothing.
        assertEquals(Arrays.asList("techreborn:wire_mill", "techreborn:wiremill"),
                Machines.idGuesses("TechReborn.WireMill"));
        assertEquals(Arrays.asList("botania:runic_altar", "botania:runicaltar"),
                Machines.idGuesses("botania.runicAltar"));
        // A uid with no separator at all yields nothing rather than a guess.
        assertTrue(Machines.idGuesses("smelting").isEmpty());
    }

    @Test
    public void formatCodesAreStrippedFromATitleAndAnEmptyOneBecomesNull() {
        assertEquals("Wire Mill", Machines.cleanLabel("§rWire Mill§r"));
        assertNull(Machines.cleanLabel(null));
        assertNull(Machines.cleanLabel("§r"));
    }

    @Test
    public void aCreatureCategoryNeedsNoMachineOnlyOnceTheDumpCanTellOneApart() {
        // Below schema 3 all 437 bee mutations are the same four keys and one input claims to
        // make 323 unrelated items. Pricing that as free let Americium-242 reroute onto bee
        // larvae, so the verdict waits for data that can support it -- and self-heals on the
        // next dump rather than needing a flag anyone has to remember.
        assertTrue(Machines.needsNoMachine("jeibees.mutation", null, Machines.SPECIES_SCHEMA));
        assertFalse(Machines.needsNoMachine("jeibees.mutation", null,
                Machines.SPECIES_SCHEMA - 1));
        // The user's own list is NEVER gated: an explicit human decision outranks what the
        // dump can prove.
        assertTrue(Machines.needsNoMachine("mod.whatever",
                Arrays.asList("mod.whatever"), 0));
        assertFalse(Machines.needsNoMachine("chickens.drops", null, 5));
    }

    // -- the verdicts --------------------------------------------------------------------------

    @Test
    public void handCraftingAndTheAlwaysAvailableListNeedNoMachine() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r1", "minecraft.crafting", "Crafting", "mod:a");
        recipe(b, "r2", "minecraft.smelting", "Smelting", "mod:b");
        RecipeGraph graph = b.build();
        MachineStates states = Machines.resolve(graph, new Evidence());
        assertEquals(MachineInfo.HAVE, states.state(graph.categoryId("minecraft.crafting")));
        assertEquals("no machine needed",
                states.info(graph.categoryId("minecraft.smelting")).why());
    }

    @Test
    public void aCategoryNothingIdentifiesIsUnknownRatherThanUnavailable() {
        // Folding these into `unavailable` put 40% of the reference pack behind a 5,000 wall.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mystery.category", null, "mod:a");
        RecipeGraph graph = b.build();
        MachineStates states = Machines.resolve(graph, new Evidence());
        assertEquals(MachineInfo.UNKNOWN, states.state(graph.categoryId("mystery.category")));
        assertEquals("machine item unknown",
                states.info(graph.categoryId("mystery.category")).why());
    }

    @Test
    public void aPlacedBlockBeatsOneMerelyInStockHoweverTheyAreOrdered() {
        // Standing next to a machine is stronger evidence than owning its item, so a placed
        // block THIRD in the candidate list still wins.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mod.press", "Press", "mod:a");
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press_stocked"));
        b.catalystKey(b.key("mod:press_placed"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        MachineStates states = Machines.resolve(graph, new Evidence()
                .stock("mod:press_stocked", 1)
                .placed("mod:press_placed", 1));
        MachineInfo info = states.info(graph.categoryId("mod.press"));
        assertEquals(MachineInfo.HAVE, info.state());
        assertEquals("placed: mod:press_placed", info.why());
        // Every candidate is judged, not just the winner: "smelting is done in more than the
        // controller" is true of a lot of categories (#27).
        assertEquals(2, info.candidates().length);
        assertEquals("in stock: mod:press_stocked", info.candidateWhy()[0]);
    }

    @Test
    public void aCatalystWithARecipeIsBuildableAndOneWithoutIsOutOfReach() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mod.press", "Press", "mod:a");
        recipe(b, "make", "minecraft.crafting", "Crafting", "mod:press");
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press"));
        b.endCatalyst();
        b.beginCatalyst("mod.ghost");
        b.catalystKey(b.key("mod:ghost_machine"));
        b.endCatalyst();
        recipe(b, "r2", "mod.ghost", "Ghost", "mod:c");
        RecipeGraph graph = b.build();

        MachineStates states = Machines.resolve(graph, new Evidence());
        assertEquals(MachineInfo.BUILDABLE, states.state(graph.categoryId("mod.press")));
        assertEquals("craftable: mod:press",
                states.info(graph.categoryId("mod.press")).why());
        assertEquals(MachineInfo.UNAVAILABLE, states.state(graph.categoryId("mod.ghost")));
        assertEquals("no route to mod:ghost_machine",
                states.info(graph.categoryId("mod.ghost")).why());
    }

    @Test
    public void aMachineWhoseRecipesOutputAnNbtVariantIsStillCraftable() {
        // A catalyst names `thermalexpansion:machine:1` while every recipe for a Pulverizer
        // outputs a discriminated key, because the level and augments live in NBT. Asking the
        // narrow question put 16 Thermal Expansion categories into "no route" for machines
        // that are plainly craftable (#28).
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "te.pulverizer", "Pulverizer", "mod:a");
        recipe(b, "make", "minecraft.crafting", "Crafting", "te:machine:1#f56885268ad5");
        b.beginCatalyst("te.pulverizer");
        b.catalystKey(b.key("te:machine:1"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        MachineInfo info = Machines.resolve(graph, new Evidence())
                .info(graph.categoryId("te.pulverizer"));
        assertEquals(MachineInfo.BUILDABLE, info.state());
        // The evidence NAMES the variant, so the claim stays checkable rather than asserting
        // a route to a key with no producers of its own.
        assertEquals("craftable: te:machine:1 (as te:machine:1#f56885268ad5)", info.why());
    }

    @Test
    public void aCrossModNameMatchSaysSoRatherThanPresentingAGuessAsASighting() {
        // The Extra Utilities furnace category is titled "Furnace" and matches
        // `minecraft:furnace`, so "placed" would otherwise assert you own a machine you have
        // never built.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "extrautils2.furnace", "Furnace", "mod:a");
        b.name("minecraft:furnace", "Furnace");
        RecipeGraph graph = b.build();

        MachineInfo info = Machines.resolve(graph,
                new Evidence().placed("minecraft:furnace", 1))
                .info(graph.categoryId("extrautils2.furnace"));
        assertEquals(MachineInfo.HAVE, info.state());
        assertTrue(info.why(), info.why().endsWith(" (name match, other mod)"));
        assertFalse(info.fromCatalyst());
    }

    @Test
    public void aManualOverrideWinsOverEveryAutomaticVerdict() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mod.press", "Press", "mod:a");
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        MachineInfo info = Machines.resolve(graph, new Evidence()
                .placed("mod:press", 1)
                .override("mod.press", MachineInfo.UNAVAILABLE))
                .info(graph.categoryId("mod.press"));
        assertEquals(MachineInfo.UNAVAILABLE, info.state());
        assertEquals("manual override", info.why());
        assertTrue(info.manual());
    }

    @Test
    public void aBlockCountedZeroTimesIsNotASighting() {
        // Reporting "placed: X" for a block the scan counted none of is a false claim rather
        // than a weak one.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mod.press", "Press", "mod:a");
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        assertEquals(MachineInfo.UNAVAILABLE,
                Machines.resolve(graph, new Evidence().placed("mod:press", 0))
                        .state(graph.categoryId("mod.press")));
    }

    // -- specificity ordering and build targets ---------------------------------------------------

    @Test
    public void theMostSpecificCatalystIsNamedRatherThanWhicheverJeiListedFirst() {
        // Modular Machinery's blueprint catalyses 226 unrelated categories, so taken in JEI's
        // order a plan reads "Mythic Processor: Melter -- craftable: itemblueprint". Ordering
        // by how many categories an item opens fixes it with no threshold and no per-mod list.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r1", "mm.recipes.melter", "Melter", "mod:a");
        recipe(b, "r2", "mm.recipes.crucible", "Crucible", "mod:b");
        b.beginCatalyst("mm.recipes.melter");
        b.catalystKey(b.key("modularmachinery:itemblueprint"));
        b.catalystKey(b.key("modularmachinery:melter_controller"));
        b.endCatalyst();
        b.beginCatalyst("mm.recipes.crucible");
        b.catalystKey(b.key("modularmachinery:itemblueprint"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        MachineInfo melter = Machines.resolve(graph, new Evidence())
                .info(graph.categoryId("mm.recipes.melter"));
        assertEquals("modularmachinery:melter_controller",
                graph.key(melter.candidates()[0]));
        // The generic block is DEMOTED rather than dropped, so it still answers when it is
        // genuinely the only candidate.
        MachineInfo crucible = Machines.resolve(graph, new Evidence())
                .info(graph.categoryId("mm.recipes.crucible"));
        assertEquals("modularmachinery:itemblueprint", graph.key(crucible.candidates()[0]));
    }

    @Test
    public void theGenericBlueprintIsNeverWhatAMachineIsPricedFrom() {
        // Demoting is not enough for PRICING: the caller takes the CHEAPEST candidate and a
        // blueprint is cheap, so one non-machine candidate would set the price for all 188
        // Modular Machinery categories and every multiblock would read as trivial (#93).
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mm.recipes.melter", "Melter", "mod:a");
        recipe(b, "bp", "minecraft.crafting", "Crafting", "modularmachinery:itemblueprint");
        recipe(b, "ctrl", "minecraft.crafting", "Crafting",
                "modularmachinery:melter_controller");
        b.beginCatalyst("mm.recipes.melter");
        b.catalystKey(b.key("modularmachinery:itemblueprint"));
        b.catalystKey(b.key("modularmachinery:melter_controller"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        MachineStates states = Machines.resolve(graph, new Evidence());
        int[] targets = states.buildTargets(graph.categoryId("mm.recipes.melter"));
        assertArrayEquals(new int[] {graph.keyId("modularmachinery:melter_controller")},
                targets);
    }

    @Test
    public void aCategoryWhoseOnlyCandidateIsABlueprintKeepsItRatherThanReadingAsIdentified() {
        // A category whose ONLY identified candidate is a blueprint is no better identified
        // than an `unknown` one, and inventing an empty answer would charge the top of the
        // band on this module's silence rather than on evidence.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mm.recipes.crucible", "Crucible", "mod:a");
        recipe(b, "bp", "minecraft.crafting", "Crafting", "modularmachinery:itemblueprint");
        b.beginCatalyst("mm.recipes.crucible");
        b.catalystKey(b.key("modularmachinery:itemblueprint"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        int[] targets = Machines.resolve(graph, new Evidence())
                .buildTargets(graph.categoryId("mm.recipes.crucible"));
        assertArrayEquals(new int[] {graph.keyId("modularmachinery:itemblueprint")}, targets);
    }

    @Test
    public void onlyBuildableCategoriesHaveABuildTargetAtAll() {
        // `have`, `unknown` and `unavailable` must keep the flat figures whose reasoning is
        // recorded on MACHINE_COST. A key here would mean "priced from a machine item", which
        // for those three is a false claim.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "mod.press", "Press", "mod:a");
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        MachineStates placed = Machines.resolve(graph,
                new Evidence().placed("mod:press", 1));
        assertNull(placed.buildTargets(graph.categoryId("mod.press")));
        assertEquals(0, placed.categoriesWithBuildTargets().length);
    }

    // -- the views the rest of the planner takes -----------------------------------------------------

    @Test
    public void anUnidentifiedMachineIsStillSomethingAPlanMayRouteThrough() {
        // Excluding `unknown` would hide 40% of the graph, which is the whole reason it is a
        // separate state.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "mystery.one", null, "mod:a");
        recipe(b, "b", "minecraft.crafting", "Crafting", "mod:b");
        b.beginCatalyst("mod.ghost");
        b.catalystKey(b.key("mod:ghost"));
        b.endCatalyst();
        recipe(b, "c", "mod.ghost", "Ghost", "mod:c");
        RecipeGraph graph = b.build();
        MachineStates states = Machines.resolve(graph, new Evidence());

        assertTrue(states.isAvailable(graph.categoryId("mystery.one"), true));
        assertFalse(states.isAvailable(graph.categoryId("mystery.one"), false));
        assertTrue(states.isAvailable(graph.categoryId("minecraft.crafting"), false));
        assertFalse(states.isAvailable(graph.categoryId("mod.ghost"), true));
        // A category with no recipes was never described, so nothing can route through it.
        assertFalse(states.isAvailable(graph.categoryCount() - 1, true));
    }

    @Test
    public void categoriesComeBackInFirstAppearanceOrderNotAscendingId() {
        // Catalyst categories are interned before any recipe is read, so the two orders
        // genuinely differ. Python builds its map by walking the recipe list.
        GraphBuilder b = new GraphBuilder();
        b.beginCatalyst("zzz.late");
        b.catalystKey(b.key("mod:thing"));
        b.endCatalyst();
        recipe(b, "a", "aaa.first", null, "mod:a");
        recipe(b, "b", "zzz.late", null, "mod:b");
        RecipeGraph graph = b.build();

        MachineStates states = Machines.resolve(graph, new Evidence());
        assertArrayEquals(new int[] {graph.categoryId("aaa.first"),
                                     graph.categoryId("zzz.late")},
                states.describedCategories());
        // The catalyst category was interned FIRST, so ascending id would put it first.
        assertTrue(graph.categoryId("zzz.late") < graph.categoryId("aaa.first"));
    }

    @Test
    public void availabilityRankKeepsBuildableAndUnknownTogether() {
        // NOT the inverse of the state constant. The constants are ordered by COST, where
        // `unknown` sits above `buildable`; the rank puts them BOTH at 1, because an
        // unidentified machine is not evidence the player cannot use it. A caller inverting
        // the constants would separate exactly the pair that must stay together, which is the
        // 40%-of-the-graph failure in a new place.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "minecraft.crafting", "Crafting", "mod:a");
        recipe(b, "b", "mystery.one", null, "mod:b");
        recipe(b, "c", "mod.ghost", "Ghost", "mod:c");
        recipe(b, "d", "mod.press", "Press", "mod:d");
        recipe(b, "make", "minecraft.crafting", "Crafting", "mod:press");
        b.beginCatalyst("mod.ghost");
        b.catalystKey(b.key("mod:ghost_machine"));
        b.endCatalyst();
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press"));
        b.endCatalyst();
        RecipeGraph graph = b.build();
        MachineStates states = Machines.resolve(graph, new Evidence());

        assertEquals(2, states.availabilityRank(graph.categoryId("minecraft.crafting")));
        assertEquals(1, states.availabilityRank(graph.categoryId("mod.press")));
        assertEquals(1, states.availabilityRank(graph.categoryId("mystery.one")));
        assertEquals(0, states.availabilityRank(graph.categoryId("mod.ghost")));
        // Silence is not evidence of absence: an undescribed category ranks with buildable,
        // never with proven-unavailable.
        assertEquals(1, states.availabilityRank(-1));
    }

    @Test
    public void handCraftingIsAskableByCategoryIdWithoutLeavingIntSpace() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "minecraft.crafting", "Crafting", "mod:a");
        recipe(b, "b", "techreborn.wire_mill", "Wire Mill", "mod:b");
        RecipeGraph graph = b.build();
        assertTrue(Machines.isHandCrafting(graph, graph.categoryId("minecraft.crafting")));
        assertFalse(Machines.isHandCrafting(graph, graph.categoryId("techreborn.wire_mill")));
        // The -1 an unknown-category lookup returns is an ordinary answer, not an error.
        assertFalse(Machines.isHandCrafting(graph, -1));
    }

    @Test
    public void theSummaryCountsEveryDescribedCategoryExactlyOnce() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "minecraft.crafting", "Crafting", "mod:a");
        recipe(b, "b", "mystery.one", null, "mod:b");
        recipe(b, "c", "mystery.two", null, "mod:c");
        RecipeGraph graph = b.build();
        int[] counts = Machines.resolve(graph, new Evidence()).summarise();
        assertEquals(1, counts[MachineInfo.HAVE]);
        assertEquals(2, counts[MachineInfo.UNKNOWN]);
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        assertEquals(3, total);
    }

    @Test
    public void theNameIndexMatchesTheRECORDEDLabelNotTheRenderedOne() {
        // Python builds its reverse index from `graph.names` directly, so an
        // aspect-parameterised entry is keyed under the literal "%s Vis Pod" and a meta
        // sibling under its base's label. Keying it under the RENDERED name -- "Vis Pod",
        // "Wool (3)" -- silently changes which JEI titles match, and titles are matched
        // against these verbatim.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r", "thaum.infusion", "%s Vis Pod", "mod:a");
        b.name("thaumadditions:vis_pod", "%s Vis Pod");
        recipe(b, "make", "minecraft.crafting", "Crafting", "thaumadditions:vis_pod");
        RecipeGraph graph = b.build();

        MachineInfo info = Machines.resolve(graph, new Evidence())
                .info(graph.categoryId("thaum.infusion"));
        // The title is the literal format string, so it matches the RECORDED label and finds
        // the pod. Rendered, the label would be "Vis Pod" and nothing would match at all.
        assertEquals(MachineInfo.BUILDABLE, info.state());
        assertEquals("craftable: thaumadditions:vis_pod (name match, other mod)",
                info.why());
    }

    @Test
    public void aStateNameRoundTripsThroughItsConstant() {
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            assertEquals(state, MachineInfo.stateOf(MachineInfo.stateName(state)));
        }
        assertEquals(-1, MachineInfo.stateOf("nonsense"));
    }
}
