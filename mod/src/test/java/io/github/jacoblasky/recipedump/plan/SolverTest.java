package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * The solver's documented rules, on graphs small enough to reason about.
 *
 * WHY HAND-BUILT GRAPHS AND NOT THE REAL ONE. `data/graph.json` is 110 MB, is not in git, and
 * changes every time the game is re-dumped. Each fixture below carries exactly one of the
 * shapes `solve.py` records as having been got wrong, so a failure names the rule rather than
 * the pack.
 *
 * These are the FAST half of the acceptance criteria and not the whole of it. The golden plan
 * fixtures under `tests/fixtures/plan/` are what hold this port to the Python oracle field for
 * field; see {@link PlanFixtureTest}. A rule asserted here is a rule this implementation has,
 * not proof that it is Python's rule.
 */
public class SolverTest {

    // -- inventory ---------------------------------------------------------------------

    @Test
    public void stockIsConsumedRatherThanJustChecked() {
        // Two sibling branches must not both claim the same 5 redstone. This is why the walk
        // is single-pass and ordered rather than a pure function per node.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:widget", "crafting_shaped", "minecraft:widget", 1,
                slot("minecraft:redstone", 5), slot("minecraft:redstone", 5));
        RecipeGraph g = b.build();
        Solver solver = solver(g).have(stock(g, "minecraft:redstone", 5)).build();

        PlanResult plan = solver.solve(g.keyId("minecraft:widget"), 1);
        // One merged slot of 10, and only 5 of it came out of the pool.
        PlanNode redstone = plan.tree.children.get(0);
        assertEquals(10, redstone.need);
        assertEquals(Long.valueOf(5), redstone.fromStock);
        assertEquals(PlanStatus.RAW, redstone.status);
        assertEquals(1, plan.shoppingList.size());
        assertEquals(5, plan.shoppingList.get(0).qty);
        assertEquals(5, plan.usedFromStock.get(0).qty);
    }

    @Test
    public void aWildcardMetaDrawsOnItsConcreteSiblings() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:carpet", "crafting_shaped", "minecraft:carpet", 3,
                slot("minecraft:wool:*", 2));
        // The concrete sibling has to EXIST as a key before it can be stocked. Naming it is
        // the cheapest way to register one without inventing a recipe for it.
        b.name("minecraft:wool:5", "Wool");
        RecipeGraph g = b.build();
        Map<Integer, Long> have = new LinkedHashMap<Integer, Long>();
        have.put(g.keyId("minecraft:wool:5"), 2L);
        Solver solver = solver(g).have(have).build();

        PlanResult plan = solver.solve(g.keyId("minecraft:carpet"), 3);
        assertEquals(PlanStatus.HAVE, plan.tree.children.get(0).status);
        // Spent against the CONCRETE key, which is what the player actually owns.
        assertEquals("minecraft:wool:5", plan.usedFromStock.get(0).key);
    }

    // -- merging -----------------------------------------------------------------------

    @Test
    public void aThreeByThreeOfOneIngredientBecomesOneChild() {
        // Expanding a 3x3 per slot drew nine copies of an identical subtree, so the node cap
        // was spent on duplicates and the tree that got truncated was mostly repeat.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        for (int i = 0; i < 9; i++) {
            b.beginSlot(1, "item");
            b.alternative(b.key("minecraft:iron_ingot"));
            b.endSlot();
        }
        b.output(b.key("minecraft:iron_block"), 1);
        b.endRecipe("r:block", "crafting_shaped", null, "jar_json", false, false);
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("minecraft:iron_block"), 1);
        assertEquals(1, plan.tree.children.size());
        assertEquals(9, plan.tree.children.get(0).need);
    }

    @Test
    public void aMergedSlotReportsTheWidestAlternativeCountItStoodFor() {
        // A merged row reporting 1 option while standing in for a slot that accepted 3 would
        // misstate the choice that was made.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:plank"));
        b.endSlot();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:plank"));
        b.alternative(b.key("mod:other"));
        b.alternative(b.key("mod:third"));
        b.endSlot();
        b.output(b.key("mod:table"), 1);
        b.endRecipe("r:table", "crafting_shaped", null, "jar_json", false, false);
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:table"), 1);
        assertEquals(1, plan.tree.children.size());
        assertEquals(Integer.valueOf(3), plan.tree.children.get(0).altCount);
    }

    // -- terminators, in the order `expand` checks them ---------------------------------

    @Test
    public void anInfiniteSourceTerminatesAndIsTalliedApartFromStock() {
        // A pool is finite and would report a made-up number as "drawn from stock", so draw
        // is tallied separately and the quantity stays visible.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:dough", "crafting_shaped", "mod:dough", 1, slot("mod:water", 1000));
        RecipeGraph g = b.build();
        Map<Integer, String> sources = new LinkedHashMap<Integer, String>();
        sources.put(g.keyId("mod:water"), "Aqueous Accumulator");

        PlanResult plan = solver(g).freeSources(sources).build()
                .solve(g.keyId("mod:dough"), 1);
        PlanNode water = plan.tree.children.get(0);
        assertEquals(PlanStatus.SOURCE, water.status);
        assertEquals("Aqueous Accumulator", water.note);
        assertEquals(0, plan.shoppingList.size());
        assertEquals(1, plan.fromSources.size());
        assertEquals(1000, plan.fromSources.get(0).qty);
        assertEquals("Aqueous Accumulator", plan.fromSources.get(0).why);
    }

    @Test
    public void emcStopsBeforeARecipeIsEvenLookedAt() {
        // `erebus:materials` has a recipe in the graph -- "dropped by a dungeon", expressed
        // as a pseudo-item -- and descending into it produces the dead end #50 was reported
        // for. A player with a working transmutation network does not go and farm a dungeon
        // for an item their network already makes.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:drop", "dungeon", "mod:material", 1, slot("mod:dungeon_token", 1));
        recipe(b, "r:use", "crafting_shaped", "mod:tool", 1, slot("mod:material", 2));
        b.emc(b.key("mod:material"), 2048);
        RecipeGraph g = b.build();

        PlanResult plan = solver(g)
                .emcAvailable(new HashSet<Integer>(
                        Arrays.asList(g.keyId("mod:material"))))
                .build()
                .solve(g.keyId("mod:tool"), 1);
        PlanNode material = plan.tree.children.get(0);
        assertEquals(PlanStatus.EMC, material.status);
        assertNull("EMC terminates the branch; it must not expand", material.children);
        // Grouped with a comma, and grouped the same way whatever locale the client runs in.
        assertEquals("EMC 2,048, learned", material.note);
        assertEquals(Long.valueOf(2048), plan.fromEmc.get(0).emc);
    }

    @Test
    public void aWorldOreIsMinedRatherThanCrafted() {
        // #106: the ore had two recipes and so looked craftable, while the nugget was the
        // only rung with no producer and therefore the only place the walk COULD stop. A
        // plan must bottom out at "18 Sednanite Ore", not at "18 Sednanite Nugget".
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:condense", "plasma", "mod:sednanite_ore", 1, slot("mod:plasma", 160000));
        recipe(b, "r:smelt", "smelting", "mod:sednanite_ingot", 1,
                slot("mod:sednanite_ore", 1));
        b.beginOreGroup("oreSednanite");
        b.oreMember(b.key("mod:sednanite_ore"));
        b.endOreGroup();
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:sednanite_ingot"), 4);
        PlanNode ore = plan.tree.children.get(0);
        assertEquals(PlanStatus.RAW, ore.status);
        assertEquals("mined, not crafted", ore.note);
        assertNull(ore.children);
        assertEquals(4, plan.shoppingList.get(0).qty);
    }

    @Test
    public void anOreInADimensionYouHaveNotVisitedSaysWhere() {
        // The cost model already charges for the trip. A route that got dearer without saying
        // why is worse than one that never mentioned it: the number is invisible and the plan
        // just looks wrong.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:smelt", "smelting", "mod:ingot", 1, slot("mod:ore", 1));
        b.beginOreGroup("oreThing");
        b.oreMember(b.key("mod:ore"));
        b.endOreGroup();
        RecipeGraph g = b.build();
        Map<Integer, String> gates = new LinkedHashMap<Integer, String>();
        gates.put(g.keyId("mod:ore"), "Nethengeic Waste");

        PlanResult plan = solver(g).dimensionGates(gates).build()
                .solve(g.keyId("mod:ingot"), 1);
        PlanNode ore = plan.tree.children.get(0);
        assertEquals("mined on Nethengeic Waste, and you have not been there", ore.note);
        assertEquals("Nethengeic Waste", ore.dimension);
    }

    @Test
    public void aPlaceholderWithNoRecipeIsAnInstructionAndNotAShoppingListRow() {
        // "1 Dungeon Drop" on a list of materials to gather reads as a thing to acquire. It
        // is an instruction, and it belongs with the other instructions.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:use", "crafting_shaped", "mod:tool", 1, slot("mod:quest_gate", 1));
        RecipeGraph g = b.build();
        Map<Integer, Integer> kinds = new LinkedHashMap<Integer, Integer>();
        kinds.put(g.keyId("mod:quest_gate"), Tokens.GATE);

        PlanResult plan = solver(g).tokenKinds(kinds).build().solve(g.keyId("mod:tool"), 1);
        PlanNode gate = plan.tree.children.get(0);
        assertEquals(PlanStatus.TOKEN, gate.status);
        // LOWER CASE on the wire. `tokens.py` spells the constants `GATE = "gate"`, and the
        // oracle fixtures carry "gate"/"loot" -- reading the constant's NAME off the Python
        // source and asserting "GATE" here is a mistake this test made once already.
        assertEquals(Tokens.kindName(Tokens.GATE), gate.tokenKind);
        assertEquals("a token must not reach the shopping list", 0, plan.shoppingList.size());
        assertEquals(1, plan.tokensNeeded.size());
        assertEquals(Tokens.kindName(Tokens.GATE), plan.tokensNeeded.get(0).tokenKind);
    }

    @Test
    public void aUserDeclaredRawItemStopsEvenThoughARecipeExists() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:planks", "crafting_shaped", "mod:planks", 4, slot("mod:log", 1));
        recipe(b, "r:table", "crafting_shaped", "mod:table", 1, slot("mod:planks", 4));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g)
                .raw(new HashSet<Integer>(Arrays.asList(g.keyId("mod:planks"))))
                .build().solve(g.keyId("mod:table"), 1);
        PlanNode planks = plan.tree.children.get(0);
        assertEquals(PlanStatus.RAW, planks.status);
        assertNull(planks.children);
    }

    @Test
    public void anAe2CraftableCountsAsHadAndSaysWhy() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:table", "crafting_shaped", "mod:table", 1, slot("mod:planks", 4));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g)
                .craftables(new HashSet<Integer>(Arrays.asList(g.keyId("mod:planks"))))
                .build().solve(g.keyId("mod:table"), 1);
        PlanNode planks = plan.tree.children.get(0);
        assertEquals(PlanStatus.HAVE, planks.status);
        assertEquals("AE2 can autocraft", planks.note);
        assertEquals("autocraftable is not a thing to go and get", 0, plan.shoppingList.size());
    }

    // -- cycles ------------------------------------------------------------------------

    @Test
    public void anUncraftingRecipeIsNotChosenOverARealRoute() {
        // `ingot -> block -> 9 ingots` scores well on one simple input and produces a plan
        // that asks for the very thing being crafted. The cycle guard catches it; the ranking
        // is what stops it being chosen.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:unpack", "crafting_shapeless", "mod:ingot", 9, slot("mod:block", 1));
        recipe(b, "r:smelt", "smelting", "mod:ingot", 1, slot("mod:ore", 1));
        recipe(b, "r:pack", "crafting_shaped", "mod:block", 1, slot("mod:ingot", 9));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:block"), 1);
        PlanNode ingot = plan.tree.children.get(0);
        assertEquals("r:smelt", ingot.recipe);
        assertEquals(0, plan.tree.countCycles());
    }

    @Test
    public void aRouteThatOnlyCyclesStillReportsWhatItResolved() {
        // All routes may cycle. Keep the most informative attempt rather than giving up:
        // fewest cycle leaves, then the largest expansion, so the plan still shows the real
        // ingredients it did resolve.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:a", "crafting_shaped", "mod:a", 1, slot("mod:b", 1));
        recipe(b, "r:b", "crafting_shaped", "mod:b", 1, slot("mod:a", 1));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:a"), 1);
        assertEquals(PlanStatus.CRAFT, plan.tree.status);
        assertEquals(1, plan.tree.countCycles());
        // The cycle leaf still goes on the shopping list, because it IS what you need.
        assertEquals("mod:a", plan.shoppingList.get(0).key);
    }

    // -- machines ----------------------------------------------------------------------

    @Test
    public void onlyMachinesYouDoNotHaveReachTheChecklistAndItIsSorted() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:z", "zeta", "mod:mid", 1, slot("mod:raw", 1));
        recipe(b, "r:a", "alpha", "mod:out", 1, slot("mod:mid", 1));
        recipe(b, "r:h", "have_one", "mod:raw", 1, slot("mod:dirt", 1));
        RecipeGraph g = b.build();
        Evidence evidence = new Evidence()
                .override("zeta", MachineInfo.BUILDABLE)
                .override("alpha", MachineInfo.UNKNOWN)
                .override("have_one", MachineInfo.HAVE);
        PlanResult plan = solver(g).machineStates(Machines.resolve(g, evidence))
                .build().solve(g.keyId("mod:out"), 1);

        List<String> categories = new ArrayList<String>();
        for (PlanResult.MachineToBuild machine : plan.machinesToBuild) {
            categories.add(machine.category);
        }
        // "have" is excluded; the rest are alphabetical because this is a checklist to scan,
        // not a worklist that should shuffle as the plan changes.
        assertEquals(Arrays.asList("alpha", "zeta"), categories);
    }

    // -- pins --------------------------------------------------------------------------

    @Test
    public void aPinnedRecipeIsUsedAndBadged() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:cheap", "crafting_shaped", "mod:out", 1, slot("mod:easy", 1));
        recipe(b, "r:dear", "furnace", "mod:out", 1, slot("mod:hard", 1), slot("mod:x", 1));
        RecipeGraph g = b.build();
        Map<Integer, Set<String>> pins = new LinkedHashMap<Integer, Set<String>>();
        pins.put(g.keyId("mod:out"), new LinkedHashSet<String>(Arrays.asList("r:dear")));

        PlanResult plan = solver(g).pinned(pins).build().solve(g.keyId("mod:out"), 1);
        assertEquals("r:dear", plan.tree.recipe);
        assertEquals(Boolean.TRUE, plan.tree.pinned);
        assertTrue("a used pin is not an overruled one", plan.pinsOverruled.isEmpty());
    }

    @Test
    public void aPinThatOnlyCyclesIsOverruledAndThePlanSaysSo() {
        // The cycle guard outranks a pin, and it has to. But "your choice was not used" is
        // exactly the silence #30 exists to end, so the plan reports it.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:unpack", "crafting_shapeless", "mod:ingot", 9, slot("mod:block", 1));
        recipe(b, "r:smelt", "smelting", "mod:ingot", 1, slot("mod:ore", 1));
        recipe(b, "r:pack", "crafting_shaped", "mod:block", 1, slot("mod:ingot", 9));
        RecipeGraph g = b.build();
        Map<Integer, Set<String>> pins = new LinkedHashMap<Integer, Set<String>>();
        pins.put(g.keyId("mod:ingot"), new LinkedHashSet<String>(Arrays.asList("r:unpack")));

        PlanResult plan = solver(g).pinned(pins).build().solve(g.keyId("mod:block"), 1);
        assertEquals("r:smelt", plan.tree.children.get(0).recipe);
        assertNotNull(plan.pinsOverruled.get("mod:ingot"));
        assertTrue(plan.pinsOverruled.get("mod:ingot"),
                plan.pinsOverruled.get("mod:ingot").contains("loops back"));
    }

    // -- arithmetic and caps -------------------------------------------------------------

    @Test
    public void runsRoundUpAndTheYieldIsReported() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:planks", "crafting_shaped", "mod:planks", 4, slot("mod:log", 1));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:planks"), 5);
        assertEquals(Long.valueOf(2), plan.tree.runs);   // ceil(5 / 4)
        assertEquals(Double.valueOf(4.0), plan.tree.perRun);
        // #223 writes the ratio only when the expected yield is below the nominal one, and
        // a certain recipe is exactly the case where it must stay absent.
        assertNull("a certain recipe must not carry a yield_chance", plan.tree.yieldChance);
        assertEquals("two runs need two logs", 2, plan.tree.children.get(0).need);
    }

    @Test
    public void theNodeCapTruncatesAndSaysWhatTheCapWas() {
        GraphBuilder b = new GraphBuilder();
        // A chain long enough to blow a tiny cap.
        for (int i = 0; i < 20; i++) {
            recipe(b, "r:" + i, "crafting_shaped", "mod:step" + i, 1,
                    slot("mod:step" + (i + 1), 1));
        }
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).maxNodes(5).build().solve(g.keyId("mod:step0"), 1);
        assertTrue(plan.truncated);
        assertEquals("the cap, not the count it stopped at", 5, plan.maxNodes);
        assertTrue("a truncated plan reports more nodes than the cap", plan.nodes > 5);
    }

    @Test
    public void theDepthCapStopsABranchWithoutTruncatingTheWholePlan() {
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < 10; i++) {
            recipe(b, "r:" + i, "crafting_shaped", "mod:step" + i, 1,
                    slot("mod:step" + (i + 1), 1));
        }
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).maxDepth(3).build().solve(g.keyId("mod:step0"), 1);
        PlanNode at = plan.tree;
        for (int depth = 0; depth < 4; depth++) {
            at = at.children.get(0);
        }
        assertEquals(PlanStatus.DEPTH, at.status);
        assertTrue("the depth cap alone is not truncation", !plan.truncated);
    }

    // -- the summary lists ----------------------------------------------------------------

    @Test
    public void theShoppingListIsCountDescendingWithTiesInFirstReachedOrder() {
        // The `Counter.most_common` contract, end to end through a real plan rather than
        // through KeyCounter alone. Deliberately NOT alphabetical and NOT by key.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:out", "crafting_shaped", "mod:out", 1,
                slot("zzz:first_seen", 3), slot("aaa:second_seen", 3),
                slot("mmm:most", 7));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:out"), 1);
        assertEquals(Arrays.asList("mmm:most", "zzz:first_seen", "aaa:second_seen"),
                keysOf(plan.shoppingList));
    }

    @Test
    public void theShoppingListTieFollowsDiscoveryAndNotTheKeyIdOrTheAlphabet() {
        // `tests/test_result_order.py:TheShoppingListKeepsDiscoveryOrderNotAlphabeticalOrder`,
        // ported. THIS AND `theUsedFromStockTieFollowsTheSameRule` ARE THE ONLY TWO ASSERTIONS
        // IN THIS FILE THAT DISCRIMINATE AGAINST ALL THREE WRONG MAPS AT ONCE: apple holds the
        // lower key id and sorts first alphabetically, and the plan reaches zebra first.
        // `HashMap` and `TreeMap<Integer>` both order small non-negative int keys ascending
        // and put apple first; `TreeMap<String>` and a bare `sorted()` put apple first for the
        // other reason. Only insertion order gives zebra.
        //
        // The case above cannot see any of that: its tie is zzz before aaa, which is not
        // alphabetical but IS ascending by key id, so it passes under a HashMap. Measured, not
        // reasoned -- see the PR for #192 for the sabotage runs.
        RecipeGraph g = tieGraph();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:widget"), 1);
        assertEquals("ties must follow slot order, not sorted() order and not key-id order",
                Arrays.asList("mod:zebra", "mod:apple"), keysOf(plan.shoppingList));
    }

    @Test
    public void theTwoOrdersGenuinelyDisagreeSoTheTieTestsCanFail() {
        // The guard on the guard, as `tests/test_result_order.py:test_the_two_orders_genuinely`
        // `_disagree_so_the_test_above_can_fail` does on the Python side, plus the key-id half
        // that Python has no equivalent of because its Counter is keyed by string. If a later
        // edit renames these keys, or drops the intern that gives apple the lower id, the two
        // tie tests silently stop discriminating and keep passing forever.
        RecipeGraph g = tieGraph();
        assertTrue("mod:apple must hold the LOWER key id, or an id-ordered map would agree "
                        + "with the expected list by accident",
                g.keyId("mod:zebra") > g.keyId("mod:apple"));
        List<String> expected = Arrays.asList("mod:zebra", "mod:apple");
        List<String> alphabetical = new ArrayList<String>(expected);
        Collections.sort(alphabetical);
        assertFalse("the expected list must not be alphabetical by accident",
                expected.equals(alphabetical));
    }

    @Test
    public void theUsedFromStockTieFollowsTheSameRule() {
        // `tests/test_result_order.py:UsedFromStockKeepsTheSameOrderingRule`, ported, and it is
        // the only discriminating tie `used_from_stock` has anywhere: the census in #192 found
        // 13 in `shopping_list` and 1 in `tokens_needed` across all 20 golden fixtures, and
        // NONE in `used_from_stock`, `from_sources` or `from_emc`, which hold 10 rows between
        // them. So the golden gate cannot cover this list's order at all and this is the whole
        // of its coverage.
        RecipeGraph g = tieGraph();
        Map<Integer, Long> have = new LinkedHashMap<Integer, Long>();
        // Stocked in APPLE-FIRST order, so a pool that leaked its own iteration order into the
        // result would be caught rather than accidentally agreed with.
        have.put(g.keyId("mod:apple"), 3L);
        have.put(g.keyId("mod:zebra"), 3L);

        PlanResult plan = solver(g).have(have).build().solve(g.keyId("mod:widget"), 1);
        assertEquals("both draws tie at 3, so the order is the order the solver reached them",
                Arrays.asList("mod:zebra", "mod:apple"), keysOf(plan.usedFromStock));
        assertTrue("nothing may be left to buy, or the draws did not both happen",
                plan.shoppingList.isEmpty());
    }

    /**
     * `tests/test_result_order.py:_tie_graph`, ported: one recipe needing equal amounts of two
     * leaves, reached ZEBRA-first while APPLE holds the lower key id.
     *
     * BOTH DISAGREEMENTS ARE DELIBERATE AND ARE THE ONLY REASON THE TESTS ABOVE CAN FAIL.
     * `mod:apple` is interned before the recipe is authored so it takes the lower id, and the
     * zebra slot is written FIRST so the solver reaches it first. Do not reorder either line,
     * and do not rename the keys: `theTwoOrdersGenuinelyDisagreeSoTheTieTestsCanFail` asserts
     * both properties so a tidy-up has to argue with a test rather than with this comment.
     */
    private static RecipeGraph tieGraph() {
        GraphBuilder b = new GraphBuilder();
        b.name("mod:widget", "Widget");
        b.name("mod:apple", "Apple Part");
        b.name("mod:zebra", "Zebra Part");
        recipe(b, "make-widget", "crafting_shaped", "mod:widget", 1,
                slot("mod:zebra", 3), slot("mod:apple", 3));
        return b.build();
    }

    @Test
    public void everyNodeCarriesItsKindSoRenderersNeedNoGraph() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:mix", "mixer", "mod:goo", 1, slot("fluid:water", 1000));
        RecipeGraph g = b.build();

        PlanResult plan = solver(g).build().solve(g.keyId("mod:goo"), 1);
        assertEquals("item", plan.tree.kind);
        assertEquals("fluid", plan.tree.children.get(0).kind);
    }

    @Test
    public void theWholeResultSerialisesWithAbsentFieldsOmitted() {
        // `"from_stock": null` would differ from every golden fixture while being
        // behaviourally identical, so absence is the contract and not null.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:planks", "crafting_shaped", "mod:planks", 4, slot("mod:log", 1));
        RecipeGraph g = b.build();

        String json = PlanJson.toJson(solver(g).build().solve(g.keyId("mod:planks"), 1));
        assertTrue(json, json.contains("\"target\": \"mod:planks\""));
        // `4.0` AND NOT `4`: #223 made `per_run` a double on both sides, because a chance
        // recipe yields a fraction of an item per run. Pinned with the decimal point so a
        // silent narrowing back to a long fails here rather than only on a chance graph.
        assertTrue(json, json.contains("\"per_run\": 4.0"));
        assertTrue("a certain recipe must not carry a yield_chance",
                !json.contains("\"yield_chance\""));
        // QUOTED ON BOTH SIDES. A bare `from_stock` is a substring of `used_from_stock`,
        // which is always present, so the unquoted check passes on a plan that wrongly
        // emitted the field and fails on one that correctly did not.
        assertTrue("nothing came from stock, so the key must be absent",
                !json.contains("\"from_stock\""));
        assertTrue("no pin was set, so the badge must be absent",
                !json.contains("\"pinned\""));
    }

    @Test
    public void aSolverRefusesToProduceASecondPlan() {
        // The accumulators are instance state: the pool is drawn down and the shopping list
        // adds up. A second call would return the first plan's totals with the second plan's
        // tree bolted on, which is entirely plausible-looking and completely wrong.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "r:planks", "crafting_shaped", "mod:planks", 4, slot("mod:log", 1));
        RecipeGraph g = b.build();
        Solver solver = solver(g).build();
        solver.solve(g.keyId("mod:planks"), 1);
        try {
            solver.solve(g.keyId("mod:planks"), 1);
            org.junit.Assert.fail("a second solve must throw rather than answer wrongly");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("already produced a plan"));
        }
    }

    // -- helpers ---------------------------------------------------------------------------

    private static Solver.Builder solver(RecipeGraph g) {
        return new Solver.Builder(g);
    }

    /** One input slot: a key and how much of it. */
    private static String[] slot(String key, int qty) {
        return new String[] {key, String.valueOf(qty)};
    }

    private static void recipe(GraphBuilder b, String rid, String category, String output,
                               int yield, String[]... slots) {
        b.beginRecipe();
        for (String[] slot : slots) {
            b.beginSlot(Integer.parseInt(slot[1]), "item");
            b.alternative(b.key(slot[0]));
            b.endSlot();
        }
        b.output(b.key(output), yield);
        b.endRecipe(rid, category, null, "jar_json", false, false);
    }

    /** The `key` of every row, which is what every ordering assertion here is about. */
    private static List<String> keysOf(List<PlanEntry> entries) {
        List<String> keys = new ArrayList<String>(entries.size());
        for (PlanEntry entry : entries) {
            keys.add(entry.key);
        }
        return keys;
    }

    private static Map<Integer, Long> stock(RecipeGraph g, String key, long count) {
        Map<Integer, Long> have = new LinkedHashMap<Integer, Long>();
        have.put(g.keyId(key), count);
        return have;
    }

}
