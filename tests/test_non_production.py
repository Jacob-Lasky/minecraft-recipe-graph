"""JEI entries that are documentation, not routes: loot tables (#211) and cards (#169).

The two reported symptoms:

  * Planning a vanilla Chest went `Chest -> Chest Cart -> Scrap Box -> Matter Reprocessor`
    and asked for four machines and hundreds of bee princesses. The answer is eight planks.
  * Planning a Mithrillium Ingot asked for an Item Router, a Distributor Module and a Bee
    Sample, none of which are used to make Mithrillium.

EVERY FIXTURE HERE IS SHAPED FROM A MEASURED CASE, named in the docstring that uses it, and
the negative cases matter more than the positive ones. `dungeon_drop` is honest -- a callstone
plus the marker maps one to one onto a spinel -- and a rule that demotes it deletes 206 real
routes. `multiblock_preview` and `dream_infusion_crafting` are METHOD markers doing real work.
`good_woot_drops` scores identically to the annotation family on the structural test and
demoting it is a 248x price regression. A green run without those four proves very little.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, index, notproduction, tokens  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

SCRAPBOX_CATEGORY = "TechReborn.Scrapbox"
MARKER = "contenttweaker:infusion_pseudo_automation"
HONEST = "contenttweaker:dungeon_drop"
PREVIEW = "contenttweaker:multiblock_preview"
WOOT = "contenttweaker:good_woot_drops"

KINDS = {MARKER: tokens.METHOD, PREVIEW: tokens.METHOD,
         HONEST: tokens.LOOT, WOOT: tokens.LOOT}


def annotation_graph():
    """#169's shape, plus the three cases a wrong rule would break.

    * `MARKER` plus one fixed rig list produces three different outputs. That is the card:
      one shapeless recipe cannot produce three different items.
    * every output also has a real producer, so the guard permits the demotion.
    * `HONEST` (`dungeon_drop`, LOOT) pairs with a DIFFERENT callstone per spinel, one to
      one, and neither spinel has any other producer. Two independent reasons it must survive.
    * `PREVIEW` (`multiblock_preview`, METHOD) varies its other input per output, which is
      what makes it real work rather than a card.
    * `WOOT` (`good_woot_drops`, LOOT) has the card's exact structural shape -- one factory
      heart, three outputs -- and is honest. Only its LOOT kind separates it from `MARKER`.
    """
    g = Graph()

    def rig():
        # FRESH `Ingredient` OBJECTS PER CARD, not a shared list. The rule compares slots by
        # VALUE, and a fixture that shares the objects would pass even if it compared identity.
        return [Ingredient([MARKER], 1), Ingredient(["mod:router"], 1),
                Ingredient(["mod:module"], 1)]

    for n, out in enumerate(("mod:mithrillium", "mod:catalyst", "mod:pick")):
        g.add(Recipe("card:%d" % n, "t", [(out, 1)], rig(), "minecraft.crafting"))
        # The real route each card claims to be a cheaper version of.
        g.add(Recipe("real:%d" % n, "t", [(out, 1)],
                     [Ingredient(["mod:void_metal"], 4 + n)], "THAUMCRAFT_INFUSION"))
    for stone, spinel in (("mod:trinity_callstone", "mod:hator_spinel"),
                          ("mod:pharos_callstone", "mod:ptah_spinel")):
        g.add(Recipe("drop:%s" % spinel, "t", [(spinel, 1)],
                     [Ingredient([HONEST], 1), Ingredient([stone], 1)],
                     "minecraft.crafting"))
    for n, out in enumerate(("mod:preview_a", "mod:preview_b")):
        g.add(Recipe("preview:%d" % n, "t", [(out, 1)],
                     [Ingredient([PREVIEW], 1), Ingredient(["mod:frame_%d" % n], 1)],
                     "compactmachines3.MultiblockMiniaturization"))
        g.add(Recipe("preview_real:%d" % n, "t", [(out, 1)],
                     [Ingredient(["mod:steel"], 1)], "minecraft.crafting"))
    for n, out in enumerate(("mod:imp_skin", "mod:divine_shard", "mod:nebulous_soul")):
        g.add(Recipe("woot:%d" % n, "t", [(out, 1)],
                     [Ingredient([WOOT], 1), Ingredient(["mod:factory_heart"], 1)],
                     "minecraft.crafting"))
        g.add(Recipe("woot_real:%d" % n, "t", [(out, 1)],
                     [Ingredient(["mod:steel"], 9)], "minecraft.crafting"))
    return g


def scrapbox_graph():
    """#211's shape: one container, three outcomes, one of them with no other producer.

    `mod:chest_cart` has a real crafting recipe, which is the Chest Cart the reproduction went
    through. `mod:plating` has none, which is `ebwizardry:crystal_silver_plating` -- one of the
    three of 343 the guard withholds on the reference graph.
    """
    g = Graph()
    for out in ("mod:chest_cart", "mod:fork", "mod:plating"):
        g.add(Recipe("box:%s" % out, "t", [(out, 1)],
                     [Ingredient(["mod:scrapbox"], 1)], SCRAPBOX_CATEGORY))
    g.add(Recipe("cart", "t", [("mod:chest_cart", 1)],
                 [Ingredient(["mod:chest"], 1), Ingredient(["mod:minecart"], 1)],
                 "minecraft.crafting"))
    g.add(Recipe("fork", "t", [("mod:fork", 1)],
                 [Ingredient(["mod:steel"], 2)], "minecraft.crafting"))
    g.add(Recipe("scrapbox", "t", [("mod:scrapbox", 1)],
                 [Ingredient(["mod:scrap"], 9)], "minecraft.crafting"))
    return g


class TheAnnotationRuleTest(unittest.TestCase):
    def setUp(self):
        self.g = annotation_graph()

    def markers(self):
        return set(notproduction.annotation_markers(self.g, KINDS))

    def test_the_card_marker_is_recognised(self):
        self.assertIn(MARKER, self.markers())

    def test_an_honest_loot_marker_is_not(self):
        """`dungeon_drop`: the callstone is a real prerequisite and the marker is honest.
        Excluding every token-bearing recipe deletes 206 genuine routes on the reference
        graph, 114 of which are the only provenance their output has."""
        self.assertNotIn(HONEST, self.markers())

    def test_a_loot_marker_with_the_cards_exact_shape_is_not(self):
        """`good_woot_drops`: 25 recipes, ONE other-input set, 25 outputs -- structurally
        indistinguishable from the card family. Its kind is what separates them, and
        demoting it takes `contenttweaker:imp_skin` from a reported 248.35 to 1.0."""
        self.assertNotIn(WOOT, self.markers())

    def test_a_method_marker_that_varies_its_inputs_is_not(self):
        """`multiblock_preview` and `dream_infusion_crafting` are METHOD and genuine. Between
        them 42 recipes sit in a multi-output family, so a per-RECIPE version of the
        does-not-vary test demotes all 42."""
        self.assertNotIn(PREVIEW, self.markers())

    def test_a_marker_used_across_several_cards_is_still_recognised(self):
        """The correction #169's issue body needs. Its rule was "the marker has ONE distinct
        other-input set"; `passive_crafting_subnets` has THREE, one per card (a Berserker
        Forge with 13 outputs, a Honeysmelter Oven with 10, a Package Crafter with 2). A
        per-marker test calls it genuine and misses all 25 recipes."""
        second = [Ingredient([MARKER], 1), Ingredient(["mod:altar"], 1)]
        for n, out in enumerate(("mod:will_crystal", "mod:reagent")):
            self.g.add(Recipe("card2:%d" % n, "t", [(out, 1)], list(second),
                              "minecraft.crafting"))
            self.g.add(Recipe("real2:%d" % n, "t", [(out, 1)],
                              [Ingredient(["mod:steel"], 3)], "minecraft.crafting"))
        self.assertIn(MARKER, self.markers())

    def test_one_card_family_with_a_single_output_is_not_an_annotation(self):
        """A marker whose every family maps to exactly one output is the honest shape, even
        with several families. That is `dungeon_drop` at full size: 121 sets over 206
        recipes."""
        g = Graph()
        for n, out in enumerate(("mod:a", "mod:b")):
            g.add(Recipe("m:%d" % n, "t", [(out, 1)],
                         [Ingredient([MARKER], 1), Ingredient(["mod:tool_%d" % n], 1)],
                         "minecraft.crafting"))
            g.add(Recipe("r:%d" % n, "t", [(out, 1)],
                         [Ingredient(["mod:steel"], 1)], "minecraft.crafting"))
        self.assertNotIn(MARKER, notproduction.annotation_markers(g, KINDS))

    def test_the_marker_slot_is_found_by_membership_and_not_by_position(self):
        """`battle_tower`'s recipes carry TWO markers, so dropping "the first slot" removes
        the wrong one on half of them and every family looks distinct."""
        g = Graph()
        for n, out in enumerate(("mod:a", "mod:b")):
            g.add(Recipe("two:%d" % n, "t", [(out, 1)],
                         [Ingredient([HONEST], 1), Ingredient([MARKER], 1),
                          Ingredient(["mod:rig"], 1)], "minecraft.crafting"))
            g.add(Recipe("r:%d" % n, "t", [(out, 1)],
                         [Ingredient(["mod:steel"], 1)], "minecraft.crafting"))
        self.assertIn(MARKER, notproduction.annotation_markers(g, KINDS))

    def test_the_other_inputs_compare_by_value_and_not_by_ingredient_identity(self):
        """Two cards written with separate `Ingredient` objects naming the same things are
        one family. Comparing the objects makes every recipe look distinct, which is the
        version of this that silently reports no annotations at all."""
        first = self.g.by_rid["card:0"][0]
        second = self.g.by_rid["card:1"][0]
        self.assertIsNot(first.inputs[1], second.inputs[1])
        self.assertEqual(notproduction._other_inputs(first, MARKER),
                         notproduction._other_inputs(second, MARKER))


class TheAlternativeProducerGuardTest(unittest.TestCase):
    def test_a_card_whose_outputs_have_real_routes_is_demoted(self):
        g = annotation_graph()
        marks = notproduction.demoted(g, KINDS)
        self.assertEqual({g.by_rid["card:%d" % n][0].rid for n in range(3)},
                         {r.rid for r in g.recipes if id(r) in marks})

    def test_a_card_that_is_its_outputs_only_producer_is_withheld(self):
        """The `imp_skin` lesson, as a mechanism rather than as a special case. Demoting the
        sole producer leaves a `cost._seed` leaf at `BASE_RAW_COST`, so the plan stops lying
        about HOW and starts lying about HOW MUCH."""
        g = annotation_graph()
        for rid in ("real:0", "real:1", "real:2"):
            g.recipes = [r for r in g.recipes if r.rid != rid]
        g._invalidate()
        self.assertEqual(notproduction.demoted(g, KINDS), {})

    def test_the_loot_table_entry_with_no_other_producer_is_withheld(self):
        g = scrapbox_graph()
        counts = notproduction.mark(g, {})
        self.assertEqual(counts["demoted"][notproduction.LOOT_TABLE], 2)
        self.assertEqual(counts["withheld"][notproduction.LOOT_TABLE], 1)
        self.assertIsNone(g.by_rid["box:mod:plating"][0].not_production)

    def test_two_cards_covering_each_others_outputs_are_both_withheld(self):
        """"Not itself a CANDIDATE" rather than "not itself DEMOTED", so the answer cannot
        depend on which recipe is visited first. Each of these would otherwise see the other
        as a real alternative and both would demote, orphaning the key."""
        g = Graph()
        for n in range(2):
            g.add(Recipe("card:%d" % n, "t", [("mod:thing", 1)],
                         [Ingredient([MARKER], 1), Ingredient(["mod:rig_%d" % n], 1)],
                         "minecraft.crafting"))
            g.add(Recipe("other:%d" % n, "t", [("mod:extra_%d" % n, 1)],
                         [Ingredient([MARKER], 1), Ingredient(["mod:rig_%d" % n], 1)],
                         "minecraft.crafting"))
            g.add(Recipe("real:%d" % n, "t", [("mod:extra_%d" % n, 1)],
                         [Ingredient(["mod:steel"], 1)], "minecraft.crafting"))
        self.assertEqual([r.rid for r in g.recipes
                          if id(r) in notproduction.demoted(g, KINDS)],
                         ["other:0", "other:1"])

    def test_no_demotion_ever_empties_real_producers(self):
        """The safety property, asserted over the whole graph rather than per case. This is
        what makes `real_producers` withholding a route unable to move a key into
        `unsourced_keys`, whose price is 2,000."""
        for g, kinds in ((annotation_graph(), KINDS), (scrapbox_graph(), {})):
            g.mark_non_production(kinds)
            for recipe in g.recipes:
                if not recipe.not_production:
                    continue
                for key, _qty in recipe.outputs:
                    self.assertTrue(g.real_producers(key),
                                    "%s left %s with no route" % (recipe.rid, key))


class TheDeclaredLootCategoriesTest(unittest.TestCase):
    def test_all_three_reported_categories_are_declared(self):
        """343, 34 and 19 entries in the reference dump. The last two reach no built graph
        because `NON_RECIPE_CATEGORY_PATTERNS` matches `loot` as a substring; they are
        declared anyway, because a list that names only the survivor teaches the next reader
        that the pattern list is the whole answer."""
        for name in ("TechReborn.Scrapbox", "intestines_loot_table", "aoa_extraction_loot"):
            self.assertIn(name, tokens.LOOT_TABLE_CATEGORIES)

    def test_a_declared_category_is_not_dropped_by_the_pattern_list(self):
        """The precedence that makes all three go through ONE mechanism. Both of these match
        `loot` as a substring, so before #211 they were deleted -- which strands any output
        whose only route was the loot table at `BASE_RAW_COST` and throws away a readable JEI
        card. The undeclared sibling is the control: it is the SAME pattern match, and it is
        still dropped, so the exemption is the declaration and not a weakened filter."""
        for name in ("intestines_loot_table", "aoa_extraction_loot"):
            self.assertFalse(index.is_non_recipe(name), name)
        self.assertTrue(index.is_non_recipe("someothermod_loot_table"))

    def test_the_scrapbox_category_matches_no_pattern_on_its_own(self):
        """Which is why a name pattern could not have caught #211, and why "scrap" is not
        one: TechReborn's Recycler turns items INTO scrap and is real production."""
        self.assertFalse(any(pat in SCRAPBOX_CATEGORY.lower()
                             for pat in index.NON_RECIPE_CATEGORY_PATTERNS))

    def test_the_filter_still_drops_an_undeclared_loot_category(self):
        self.assertTrue(index.is_non_recipe("jeresources.mob_loot"))

    def test_a_real_recipe_consuming_the_container_is_untouched(self):
        """The discriminating case #169's analysis names: `1x Scrap Box` gives 347
        `THAUMCRAFT_CRUCIBLE` recipes with 346 distinct outputs, a perfect score on
        "identical input set, different outputs" at 50x the card family's size, and every one
        is real production. The rule is scoped to the CATEGORY, so it cannot reach them."""
        g = scrapbox_graph()
        g.add(Recipe("crucible", "t", [("mod:tallow", 1)],
                     [Ingredient(["mod:scrapbox"], 1)], "THAUMCRAFT_CRUCIBLE"))
        g.add(Recipe("tallow_real", "t", [("mod:tallow", 1)],
                     [Ingredient(["mod:fat"], 1)], "minecraft.crafting"))
        g.mark_non_production({})
        self.assertIsNone(g.by_rid["crucible"][0].not_production)


class ThePricingAndRankingTest(unittest.TestCase):
    def test_the_penalty_outranks_the_worst_obstacle_the_model_can_state(self):
        """A machine you cannot have is a true statement about the world and a route through
        it is a real route. A loot table is not a route at all, so it has to lose even to
        that."""
        self.assertGreater(cost.NON_PRODUCTION_PENALTY, cost.MACHINE_COST["unavailable"])

    def test_a_demoted_recipe_is_withheld_from_real_producers(self):
        g = scrapbox_graph()
        g.mark_non_production({})
        self.assertEqual([r.rid for r in g.real_producers("mod:chest_cart")], ["cart"])
        self.assertEqual(len(g.producers("mod:chest_cart")), 2)

    def test_the_demoted_route_stays_in_the_graph_and_in_used_in(self):
        """#211 asked for a kind the solver refuses to route through while the UI can still
        show it, and #169 for the card to stay visible. Dropping it costs `used_in`, `makes`,
        and `tokens.candidates`, whose whole structural test is "consumed by some recipe,
        produced by none"."""
        g = scrapbox_graph()
        g.mark_non_production({})
        self.assertIn("box:mod:chest_cart",
                      [r.rid for r in g.producers("mod:chest_cart")])
        self.assertIn("mod:scrapbox", g.by_input)

    def test_a_withheld_entry_keeps_pricing_its_output_normally(self):
        """`mod:plating` is the entry the guard withheld, so nothing about it changed: it is
        still the only route to that key and it still prices as one."""
        g = scrapbox_graph()
        g.mark_non_production({})
        table = cost.estimate(g)
        self.assertLess(table["mod:plating"], cost.NON_PRODUCTION_PENALTY)

    def test_an_output_whose_real_route_is_unreachable_stays_priced(self):
        """Declining to relax through a demoted recipe strands 26 keys at infinity on the
        reference graph -- five of #169's seven infusion catalysts among them -- because their
        only route that is not documentation is itself unreachable. The penalty strands 0,
        which is the same finding `UNSOURCED_COST` records for the same reason.

        `mod:sealed` is that shape: a loot-table route, and a real route through a key nothing
        can ever finitely price."""
        g = scrapbox_graph()
        g.add(Recipe("box:sealed", "t", [("mod:sealed", 1)],
                     [Ingredient(["mod:scrapbox"], 1)], SCRAPBOX_CATEGORY))
        g.add(Recipe("sealed_real", "t", [("mod:sealed", 1)],
                     [Ingredient(["mod:vapour"], 1)], "minecraft.crafting"))
        g.add(Recipe("vapour", "t", [("mod:vapour", 1)],
                     [Ingredient(["mod:vapour"], 2)], "minecraft.crafting"))
        g.mark_non_production({})
        self.assertEqual(g.by_rid["box:sealed"][0].not_production,
                         notproduction.LOOT_TABLE)
        table = cost.estimate(g)
        # ABSENT rather than stored as infinity: an unreachable key gets no entry at all,
        # which is what makes "stranded" and "never priced" the same reading downstream.
        self.assertEqual(table.get("mod:vapour", float("inf")), float("inf"))
        self.assertGreater(table["mod:sealed"], cost.NON_PRODUCTION_PENALTY)
        self.assertNotEqual(table["mod:sealed"], float("inf"))
        # And the solver still refuses the route the price came through, which is the pair
        # `real_producers` and `_relax` deliberately do NOT mirror. See both docstrings.
        self.assertEqual(g.real_producers("mod:sealed"),
                         [g.by_rid["sealed_real"][0]])

    def test_the_loot_table_loses_the_route_it_used_to_win(self):
        """#211's reproduction, at fixture size. `mod:fork` is reachable both ways and the
        scrapbox is the cheaper of the two before the fix, because one container stands in
        for every outcome."""
        g = scrapbox_graph()
        g.mark_non_production({})
        table = cost.estimate(g)
        solver = Solver(g, costs=table)
        scored = {r.rid: solver.score_recipe(r) for r in g.producers("mod:fork")}
        self.assertGreater(scored["fork"], scored["box:mod:fork"])
        self.assertEqual(solver.score_recipe(g.by_rid["box:mod:fork"][0])[0], 0)

    def test_the_plan_takes_the_real_route(self):
        g = scrapbox_graph()
        g.mark_non_production({})
        tree = Solver(g, costs=cost.estimate(g)).solve("mod:chest_cart", 1)["tree"]
        self.assertEqual(tree["recipe"], "cart")

    def test_an_honest_loot_marker_keeps_its_price_and_its_route(self):
        """`hator_spinel` reads exactly 202.0 on the reference graph: `LOOT_COST` 200 plus
        the callstone at 1.0 plus the crafting-table entry at 1.0. Each way of getting this
        wrong moves it to a DIFFERENT number, so the value says which mistake was made --
        1.0 means the recipe was dropped, infinity means it was priced out without the guard,
        3.0 means the marker was re-typed."""
        g = annotation_graph()
        g.mark_non_production(KINDS)
        recipe = g.by_rid["drop:mod:hator_spinel"][0]
        self.assertIsNone(recipe.not_production)
        self.assertEqual([r.rid for r in g.real_producers("mod:hator_spinel")], [recipe.rid])
        table = cost.estimate(g, token_kinds=KINDS)
        # THE THREE-TERM SUM IS THE ASSERTION, not the literal 202.0: each way of getting this
        # wrong lands on a different term. The entry cost is read from the model rather than
        # typed, because a fixture graph declares no catalysts and so charges the ungated
        # figure where the reference pack charges a crafting table it owns.
        self.assertEqual(table["mod:hator_spinel"],
                         cost.LOOT_COST + cost.BASE_RAW_COST
                         + cost.category_entry_cost("minecraft.crafting"))

    def test_the_card_stops_being_the_cheapest_route_to_its_outputs(self):
        """#169's positive control: the plan must stop naming the rig parts. The card prices
        at one crafting-table entry plus three raw leaves; the real route is four."""
        g = annotation_graph()
        g.mark_non_production(KINDS)
        table = cost.estimate(g, token_kinds=KINDS)
        solver = Solver(g, costs=table, token_kinds=KINDS)
        tree = solver.solve("mod:mithrillium", 1)["tree"]
        self.assertEqual(tree["recipe"], "real:0")
        self.assertNotIn("mod:router", [c["key"] for c in tree.get("children", ())])

    def test_repricing_the_marker_could_not_have_fixed_it(self):
        """Settles #169's open question 4. The marker is one raw leaf of the card's price and
        the rig parts are the rest, so no `TOKEN_COST` for METHOD removes them from the plan.
        Only demoting the recipe can."""
        g = annotation_graph()
        card = g.by_rid["card:0"][0]
        rig = [alt for ing in card.inputs for alt in ing.alternatives if alt != MARKER]
        self.assertEqual(rig, ["mod:router", "mod:module"])

    def test_the_flag_separates_two_otherwise_identical_offers(self):
        """#181's interchangeable-subset mark. A key with a real route and a loot-table route
        can have candidates whose merged slots, per-run output and category all coincide, and
        calling those the same OFFER reports the one non-arbitrary decision in the ranking as
        a coin toss."""
        g = annotation_graph()
        # Two slots, one option each, one output, one category: identical on every other term
        # `offer_shape` carries. The card's marker slot is what makes the counts line up.
        g.add(Recipe("twin", "t", [("mod:mithrillium", 1)],
                     [Ingredient(["mod:steel"], 1), Ingredient(["mod:brass"], 1)],
                     "minecraft.crafting"))
        g.mark_non_production(KINDS)
        card = g.by_rid["card:0"][0]
        twin = g.by_rid["twin"][0]
        self.assertEqual(card.not_production, notproduction.ANNOTATION)
        self.assertIsNone(twin.not_production)
        solver = Solver(g, token_kinds=KINDS)
        self.assertNotEqual(solver.offer_shape(card, "mod:mithrillium"),
                            solver.offer_shape(twin, "mod:mithrillium"))


class TheMarkingPassTest(unittest.TestCase):
    def test_marking_is_total_so_a_new_token_map_clears_the_old_verdict(self):
        """A server reloading `data/tokens.json` must get the new answer and not the union of
        two."""
        g = annotation_graph()
        g.mark_non_production(KINDS)
        self.assertTrue(any(r.not_production for r in g.recipes))
        g.mark_non_production({})
        self.assertFalse(any(r.not_production for r in g.recipes))

    def test_the_memo_holds_for_one_map_and_releases_for_another(self):
        g = annotation_graph()
        first = g.mark_non_production(KINDS)
        self.assertIs(g.mark_non_production(dict(KINDS)), first)
        self.assertIsNot(g.mark_non_production({}), first)

    def test_adding_a_recipe_invalidates_the_verdict(self):
        """The rule reads `by_output`, so a graph whose recipes changed has to be re-marked
        rather than left holding an answer about a producer set that is gone."""
        g = annotation_graph()
        g.mark_non_production(KINDS)
        g.add(Recipe("late", "t", [("mod:late", 1)], [Ingredient(["mod:steel"], 1)]))
        self.assertIsNone(g._non_production_signature)

    def test_the_flag_is_not_written_into_the_graph_json(self):
        """Derived, not persisted: the rule reads the per-world token map, so a value written
        into the graph would be a stale answer to a question the reader can settle in under a
        second, and it would be a schema bump on 124,467 records."""
        g = scrapbox_graph()
        g.mark_non_production({})
        box = g.by_rid["box:mod:chest_cart"][0]
        self.assertEqual(box.not_production, notproduction.LOOT_TABLE)
        self.assertNotIn("np", box.to_json())
        self.assertIsNone(Recipe.from_json(box.to_json()).not_production)

    def test_a_fresh_recipe_is_a_route_until_something_says_otherwise(self):
        self.assertIsNone(Recipe("r", "t", [("mod:a", 1)], []).not_production)


class TheReportTest(unittest.TestCase):
    def test_every_ground_has_a_reason_a_reader_can_act_on(self):
        for ground in notproduction.GROUNDS:
            self.assertIn(ground, notproduction.GROUND_REASON)

    def test_a_ground_that_demoted_nothing_still_reports(self):
        """"No line" and "zero" read identically in a log and mean opposite things: a rule
        that did not run, and a rule that ran and found nothing."""
        lines = notproduction.report(notproduction.mark(Graph(), {}))
        self.assertEqual(len(lines), len(notproduction.GROUNDS))
        for ground in notproduction.GROUNDS:
            self.assertTrue(any(line.startswith(ground) for line in lines), ground)

    def test_the_counts_name_both_what_was_done_and_what_was_withheld(self):
        counts = notproduction.mark(scrapbox_graph(), {})
        text = " ".join(notproduction.report(counts))
        self.assertIn("2 recipes priced out", text)
        self.assertIn("1 withheld", text)

    def test_coverage_reports_the_counts_without_being_handed_a_token_map(self):
        """Defaulting to empty would report zero annotation demotions for every pack and read
        as "the rule found nothing" instead of "the rule was not given its input"."""
        stats = index.coverage(annotation_graph())
        self.assertIn("not_production", stats)
        self.assertEqual(stats["not_production"]["priced_out"],
                         {notproduction.LOOT_TABLE: 0, notproduction.ANNOTATION: 3})
        self.assertIn(MARKER, stats["not_production"]["annotation_markers"])


if __name__ == "__main__":
    unittest.main()
