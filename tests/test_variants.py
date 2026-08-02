"""Variant tables: a JEI entry that lists the same stacks in both columns.

Issue #110. Chisel and Unlimited Chisel Works publish one entry per material with every
variant on BOTH sides -- 37 slots in, the same 37 stacks out -- meaning "any one of these
becomes any other one". Flattened that way it reads as "all 37 in, all 37 out", which
`produces_nothing_new` scores as a no-op, so all 341 tables in the reference pack were
dropped and 6,856 variant keys were left with zero producers. A key with no producer is
seeded at `BASE_RAW_COST`, so `chisel:lapis:1` priced BELOW the `minecraft:lapis_block` it
is chiselled from, and 33 recipes could reach Lapis Lazuli through the decorative block.

The same shape #61 fixed for Diamond, surviving in the keys `Solver.ore_backed` cannot
rank because they are not ores.

The reference group is real and is the fixture below:

    minecraft:lapis_block, chisel:lapis:0 ... chisel:lapis:8

THE STRUCTURAL SHAPE ALONE DOES NOT IDENTIFY A TABLE, and `DocumentationIsNotAConversion`
is the class that pins it. An Actually Additions manual page listing all six crystal
colours is bit-for-bit the same shape and is not a conversion: you cannot chisel a black
crystal into a red one. Measured on the reference pack before the category filter went in,
the structural test matched 354 entries, 13 of them documentation.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, index  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402

CHISELING = "chisel.chiseling"
REAL = "minecraft:lapis_block"
VARIANTS = ["chisel:lapis:%d" % i for i in range(9)]
GROUP = [REAL] + VARIANTS

STATES = {
    "minecraft.crafting": ("have", ""),
    CHISELING: ("have", ""),
    "mod.expensive": ("buildable", ""),
}


def table_recipe(members, category=CHISELING, rid="tbl"):
    """The dump's own shape: one unambiguous slot per member, the same stacks as outputs.

    Deliberately NOT a tidier encoding. A table written as one many-alternative slot would
    already survive `produces_nothing_new`, so it could not reproduce the bug.
    """
    return Recipe(rid, "hei_dump", [(m, 1) for m in members],
                  [Ingredient([m], 1) for m in members],
                  category=category, machine="Chisel")


def lapis_graph():
    """The real group, an expensive way to the anchor, and something that consumes it.

    Two fixture properties carry the bug, and neither is decoration:

    * THE ANCHOR MUST COST MORE THAN THE FLOOR, or every member ties at
      `BASE_RAW_COST` and "the variant undercuts its source" is not observable.
    * SOMETHING MUST CONSUME THE GROUP through a slot that also accepts the anchor. That
      is the #61 shape and it is what makes the variant reachable in a plan at all: of the
      371 producerless `<M> Block` keys measured on the reference pack, the 61 that matter
      are exactly the ones something consumes. With no consumer the variant is not in the
      graph's cost table at all and the defect has nowhere to show.
    """
    g = Graph()
    g.names = dict({REAL: "Lapis Lazuli Block"},
                   **{v: "Lapis Lazuli Block" for v in VARIANTS})
    g.names.update({"mod:gem": "Lapis Lazuli", "mod:dye": "Blue Dye"})
    g.catalysts = {CHISELING: ["chisel:chisel_iron"]}
    g.add(Recipe("pack", "t", [(REAL, 1)],
                 [Ingredient(["mod:gem"], 9)], category="mod.expensive"))
    g.add(table_recipe(GROUP))
    # The slot accepts the real block OR a decorative one, exactly as the pack's
    # iron-ingot unpacking recipe does.
    g.add(Recipe("grind", "t", [("mod:dye", 8)],
                 [Ingredient([REAL] + VARIANTS, 1)], category="minecraft.crafting"))
    return g


class TableSurvivesTheFilterTest(unittest.TestCase):
    """#110's repro at the graph level: the table becomes conversions, not nothing."""

    def test_every_member_gains_a_usable_producer(self):
        """Usable meaning "does not demand the thing it makes".

        The unfiltered table already counts as a producer of all ten members, which is
        exactly why the bug is subtle: the key looks produced right up until the filter
        pass deletes the only entry naming it. What the filter has to leave behind is a
        producer whose inputs the player could actually satisfy.
        """
        g = lapis_graph()
        raw = g.producers(VARIANTS[3])
        self.assertEqual(len(raw), 1, "precondition: only the table itself")
        self.assertIn(VARIANTS[3], [a for i in raw[0].inputs for a in i.alternatives],
                      "precondition: as written, the table demands what it makes")
        index.apply_recipe_filters(g)
        for member in GROUP:
            made = g.producers(member)
            self.assertTrue(made, "%s has no producer after the filter pass" % member)
            for r in made:
                self.assertNotIn(member,
                                 [a for i in r.inputs for a in i.alternatives],
                                 "%s is still produced only by consuming itself" % member)

    def test_expansion_offers_the_other_members_as_one_choice(self):
        g = lapis_graph()
        index.apply_recipe_filters(g)
        made = [r for r in g.producers(VARIANTS[0]) if r.category == CHISELING]
        self.assertEqual(len(made), 1)
        self.assertEqual(len(made[0].inputs), 1, "a table conversion takes ONE slot")
        self.assertEqual(sorted(made[0].inputs[0].alternatives),
                         sorted(m for m in GROUP if m != VARIANTS[0]))

    def test_counts_are_reported(self):
        stats = index.apply_recipe_filters(lapis_graph())
        self.assertEqual(stats["tables"], {CHISELING: 1})
        self.assertEqual(stats["expanded"], len(GROUP))
        self.assertEqual(stats["loops"], {})

    def test_a_second_pass_does_not_drop_the_expansion(self):
        """The ambiguous slot is what protects it, so prove the protection holds.

        A conversion's slot lists the other members, so `produces_nothing_new` cannot see
        its output as required. Written as unambiguous slots the expansion would delete
        itself on the next pass and the bug would return one refactor later.
        """
        g = lapis_graph()
        index.apply_recipe_filters(g)
        after_first = len(g.recipes)
        stats = index.apply_recipe_filters(g)
        self.assertEqual(len(g.recipes), after_first)
        self.assertEqual(stats["loops"], {})
        self.assertEqual(stats["tables"], {})


class VariantDoesNotUndercutItsSourceTest(unittest.TestCase):
    """#110 as reported: the decorative block priced below the block it is chiselled from.

    This is the assertion that fails on the broken code, and it fails with the reported
    symptom rather than an adjacent one -- the variant lands exactly on `BASE_RAW_COST`
    while the real block costs an order of magnitude more.
    """

    def _costs(self):
        g = lapis_graph()
        index.apply_recipe_filters(g)
        return cost.estimate(g, machine_states=STATES)

    def test_the_real_block_is_not_free(self):
        costs = self._costs()
        self.assertGreater(costs[REAL], cost.BASE_RAW_COST,
                           "fixture precondition: the anchor has a real price")

    def test_no_variant_is_cheaper_than_the_anchor(self):
        costs = self._costs()
        for variant in VARIANTS:
            self.assertGreaterEqual(
                costs[variant], costs[REAL],
                "%s undercuts the block it is chiselled from" % variant)

    def test_no_variant_sits_on_the_raw_leaf_floor(self):
        costs = self._costs()
        for variant in VARIANTS:
            self.assertGreater(costs[variant], cost.BASE_RAW_COST,
                               "%s is still priced as though you could just go get one"
                               % variant)

    def test_the_consuming_recipe_is_priced_at_the_real_block(self):
        """#110's user-visible consequence: 33 recipes reaching Lapis through the decor.

        The grind slot takes the real block or any variant. Priced correctly the cheapest
        member IS the real block, so the recipe costs one entry plus the block over its
        batch of eight. On the broken code the variant is a free raw leaf and the same
        recipe prices at a tenth of that, which is how a decorative block wins.
        """
        costs = self._costs()
        expected = cost.MACHINE_COST["have"] + costs[REAL] / 8.0
        self.assertAlmostEqual(costs["mod:dye"], expected, places=6)

    def test_a_variant_costs_the_anchor_plus_an_entry(self):
        """Not merely "above", but the specific number, so a future change has to mean it.

        Chiselling is one hop off the anchor and the chisel is held, so the price is the
        block plus one entry. `MACHINE_COST["have"]` equalling `BASE_RAW_COST` is what
        makes an entry cost unable to be zero, which is the argument that this fix cannot
        ever make a variant the cheap route.
        """
        costs = self._costs()
        expected = costs[REAL] + cost.MACHINE_COST["have"]
        for variant in VARIANTS:
            self.assertAlmostEqual(costs[variant], expected, places=6)


class DocumentationIsNotAConversionTest(unittest.TestCase):
    """A manual page has the same shape and must not become a recipe.

    Every booklet and manual page in the reference pack lists its subject on both sides, so
    until #110 the no-op test swallowed all 386 by accident. That accident stopped being
    harmless the moment a two-sided entry started reading as a conversion.
    """

    CRYSTALS = ["actuallyadditions:item_crystal:%d" % i for i in range(6)]

    def _filtered(self, category):
        g = Graph()
        g.add(table_recipe(self.CRYSTALS, category=category))
        stats = index.apply_recipe_filters(g)
        return g, stats

    def test_a_booklet_page_is_dropped(self):
        g, stats = self._filtered("actuallyadditions.booklet")
        self.assertEqual(g.recipes, [])
        self.assertEqual(stats["tables"], {})

    def test_an_industrial_foregoing_manual_page_is_dropped(self):
        g, _stats = self._filtered("if_manual_category")
        self.assertEqual(g.recipes, [])

    def test_a_block_pattern_display_is_dropped(self):
        g, _stats = self._filtered("extrautils2.blockPatterns")
        self.assertEqual(g.recipes, [])

    def test_no_crystal_colour_becomes_a_recipe_for_another(self):
        g, _stats = self._filtered("actuallyadditions.booklet")
        for crystal in self.CRYSTALS:
            self.assertEqual(g.producers(crystal), [])

    def test_the_category_filter_is_what_catches_them(self):
        """Not the no-op test, which would have let the six-colour page through.

        Naming the mechanism matters: if a later change moves booklets out of the category
        list on the theory that the no-op test covers them, this fails.
        """
        for category in ("actuallyadditions.booklet", "if_manual_category",
                         "extrautils2.blockPatterns"):
            self.assertTrue(index.is_non_recipe(category), category)


class NotEveryTwoSidedEntryIsATableTest(unittest.TestCase):
    """The shapes that must keep being dropped, or must never have been touched."""

    def test_charging_one_item_stays_a_no_op(self):
        """`Flux Capacitor -> Flux Capacitor` has one key, so there is nothing to convert."""
        g = Graph()
        g.add(Recipe("charge", "t", [("mod:cap", 1)],
                     [Ingredient(["mod:cap"], 1)], category="mod.charger"))
        stats = index.apply_recipe_filters(g)
        self.assertEqual(g.recipes, [])
        self.assertEqual(stats["loops"], {"mod.charger": 1})
        self.assertEqual(stats["tables"], {})

    def test_a_choice_slot_is_not_a_table(self):
        """It never reaches the expansion, because it is not a no-op in the first place."""
        g = Graph()
        g.add(Recipe("real", "t", [("mod:a", 1)],
                     [Ingredient(["mod:a", "mod:b"], 1)], category="minecraft.crafting"))
        stats = index.apply_recipe_filters(g)
        self.assertEqual(len(g.recipes), 1)
        self.assertEqual(stats["tables"], {})

    def test_multiplication_is_left_alone(self):
        """`1 Spectral Fern -> 3 Spectral Fern` is the point of the machine, not a table."""
        g = Graph()
        g.add(Recipe("grow", "t", [("mod:fern", 3)],
                     [Ingredient(["mod:fern"], 1)], category="mod.insolator"))
        index.apply_recipe_filters(g)
        self.assertEqual(len(g.recipes), 1)

    def test_a_fluid_slot_blocks_expansion(self):
        """Nothing chisels a fluid, and a table with one is a shape nobody has measured."""
        g = Graph()
        g.add(Recipe("wet", "t", [("mod:a", 1), ("fluid:x", 1)],
                     [Ingredient(["mod:a"], 1),
                      Ingredient(["fluid:x"], 1, "fluid")], category=CHISELING))
        stats = index.apply_recipe_filters(g)
        self.assertEqual(g.recipes, [])
        self.assertEqual(stats["tables"], {})

    def test_a_transfer_is_never_a_table(self):
        r = table_recipe(GROUP)
        r.transfer = True
        self.assertIsNone(index.expand_interconversion(r))

    def test_unequal_columns_are_not_a_table(self):
        """Two in, one out is a real combine, and must not be read as interconversion."""
        r = Recipe("combine", "t", [("mod:a", 1)],
                   [Ingredient(["mod:a"], 1), Ingredient(["mod:b"], 1)],
                   category=CHISELING)
        self.assertIsNone(index.expand_interconversion(r))


class AGroupWithNoSourceKeepsTheLeafPriceTest(unittest.TestCase):
    """#110's other half: the fix must not trade a cheap lie for an expensive one.

    Expanding a table makes its members produced keys, and `cost._seed` hands
    `BASE_RAW_COST` only to keys NOTHING produces. A group with no way in therefore stops
    being N cheap leaves and becomes N keys that produce each other and nothing else -- a
    closed cycle with no base case, which relaxation can only leave at infinity.

    Measured on the reference dump before `cost._settle_reshaped` existed: 327 keys went
    from a finite price to unreachable, `abyssalcraft:abybrick` among them at 3.0 before.
    Worse than the bug being fixed, and the same "reads unobtainable when it is trivially
    obtainable" failure `cost.BLOCKED_CEILING` is written against.
    """

    ORPHANS = ["mod:decor:%d" % i for i in range(4)]

    def _orphan_graph(self):
        g = Graph()
        g.add(table_recipe(self.ORPHANS, rid="orphan"))
        g.add(Recipe("use", "t", [("mod:out", 1)],
                     [Ingredient(list(self.ORPHANS), 1)], category="minecraft.crafting"))
        index.apply_recipe_filters(g)
        return g

    def test_the_table_is_still_expanded(self):
        """The conversions are real and the graph should say so; only the PRICE is special."""
        g = self._orphan_graph()
        self.assertTrue(g.producers(self.ORPHANS[0]))

    def test_every_member_is_reshaped_only(self):
        g = self._orphan_graph()
        for orphan in self.ORPHANS:
            self.assertIn(orphan, g.reshaped_only)

    def test_no_member_becomes_unreachable(self):
        g = self._orphan_graph()
        costs = cost.estimate(g, machine_states=STATES)
        for orphan in self.ORPHANS:
            self.assertEqual(costs[orphan], cost.BASE_RAW_COST,
                             "%s lost the leaf price it had before #110" % orphan)

    def test_what_consumes_them_stays_reachable(self):
        """The re-relaxation is what carries the restored price outward."""
        g = self._orphan_graph()
        costs = cost.estimate(g, machine_states=STATES)
        self.assertLess(costs["mod:out"], float("inf"))

    def test_an_unreachable_anchor_does_not_strand_the_group(self):
        """The case a build-time anchor test cannot see, and the one that left 168 keys.

        The group HAS a member something produces, so "does any member have a producer"
        answers yes -- but that producer sits in a cycle with no base case, so the anchor
        is itself unreachable and the whole group would follow it to infinity.

        Both keys come out finite, and the anchor's does NOT come from its own broken
        recipe: the sibling is reshaped-only, gets its leaf price back, and the anchor is
        then one chisel away from it. That is the equivalence class doing its job -- assume
        any member obtainable and every member is -- and it is why the fallback is stated
        over the group rather than per key.
        """
        g = Graph()
        g.add(table_recipe(["mod:anchor", "mod:sibling"], rid="pair"))
        # A two-recipe cycle with no base case. Both keys are PRODUCED, so neither is
        # seeded as a leaf, and neither can ever be relaxed to a finite price. An
        # ingredient that simply does not exist would not do: an unproduced key is a leaf
        # at BASE_RAW_COST, which makes the anchor merely expensive.
        g.add(Recipe("a_from_b", "t", [("mod:anchor", 1)],
                     [Ingredient(["mod:loop"], 1)], category="minecraft.crafting"))
        g.add(Recipe("b_from_a", "t", [("mod:loop", 1)],
                     [Ingredient(["mod:anchor"], 1)], category="minecraft.crafting"))
        index.apply_recipe_filters(g)
        self.assertIn("mod:sibling", g.reshaped_only)
        self.assertNotIn("mod:anchor", g.reshaped_only,
                         "precondition: the anchor has an ordinary producer")
        costs = cost.estimate(g, machine_states=STATES)
        self.assertEqual(costs["mod:sibling"], cost.BASE_RAW_COST)
        self.assertEqual(costs["mod:anchor"],
                         cost.BASE_RAW_COST + cost.MACHINE_COST["have"],
                         "the anchor is reachable through the sibling, not through its "
                         "own unreachable recipe")

    def test_an_anchored_group_is_not_given_the_leaf_price(self):
        """The fallback must not fire where relaxation succeeded, or #110 comes straight back."""
        g = lapis_graph()
        index.apply_recipe_filters(g)
        self.assertEqual(g.reshaped_only & set(VARIANTS), set(VARIANTS),
                         "the variants are reshaped-only; only the PRICE distinguishes them")
        costs = cost.estimate(g, machine_states=STATES)
        for variant in VARIANTS:
            self.assertGreater(costs[variant], cost.BASE_RAW_COST)

    def test_a_key_with_an_ordinary_producer_is_not_reshaped_only(self):
        """Its unreachability, if any, is a real statement and must not be papered over."""
        g = lapis_graph()
        index.apply_recipe_filters(g)
        self.assertNotIn(REAL, g.reshaped_only)


class ExpandedRecipesAreWellFormedTest(unittest.TestCase):
    """The expansion has to survive everything downstream that reads a Recipe."""

    def setUp(self):
        self.expanded = index.expand_interconversion(table_recipe(GROUP))

    def test_one_recipe_per_member(self):
        self.assertEqual(len(self.expanded), len(GROUP))
        self.assertEqual(sorted(r.outputs[0][0] for r in self.expanded), sorted(GROUP))

    def test_rids_are_distinct(self):
        """`by_rid` tolerates collisions, but two conversions are genuinely two recipes."""
        rids = [r.rid for r in self.expanded]
        self.assertEqual(len(set(rids)), len(rids))

    def test_the_category_and_machine_are_carried(self):
        for r in self.expanded:
            self.assertEqual(r.category, CHISELING)
            self.assertEqual(r.machine, "Chisel")

    def test_nothing_consumes_itself(self):
        for r in self.expanded:
            self.assertNotIn(r.outputs[0][0], r.inputs[0].alternatives)

    def test_a_round_trip_through_json_survives(self):
        for r in self.expanded:
            back = Recipe.from_json(r.to_json())
            self.assertEqual(back.outputs, r.outputs)
            self.assertEqual(back.inputs[0].alternatives, r.inputs[0].alternatives)


class TheFlagSurvivesTheRoundTripTest(unittest.TestCase):
    """`reshaped_only` reads `Recipe.variant`, so a graph on disk must carry it.

    `build` writes graph.json and the server reads it back; a flag that only exists in the
    process that computed it would make the fallback fire during a build and never again,
    which is the shape of bug that only shows up in production.
    """

    def test_a_saved_graph_still_knows_which_recipes_reshape(self):
        g = lapis_graph()
        index.apply_recipe_filters(g)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "graph.json")
            with open(path, "w") as fh:
                json.dump(g.to_json(), fh)
            back = Graph.load(path)
        self.assertEqual(back.reshaped_only, g.reshaped_only)
        self.assertTrue(back.reshaped_only)

    def test_an_ordinary_recipe_is_not_flagged(self):
        g = lapis_graph()
        index.apply_recipe_filters(g)
        for r in g.recipes:
            self.assertEqual(r.variant, r.category == CHISELING)

    def test_the_index_is_invalidated_with_the_others(self):
        """A stale `reshaped_only` would price a key against recipes that no longer exist."""
        g = lapis_graph()
        index.apply_recipe_filters(g)
        self.assertIn(VARIANTS[0], g.reshaped_only)
        g.add(Recipe("late", "t", [(VARIANTS[0], 1)],
                     [Ingredient(["mod:gem"], 1)], category="minecraft.crafting"))
        self.assertNotIn(VARIANTS[0], g.reshaped_only,
                         "adding an ordinary producer must drop it from the index")


class TheFallbackRunsOnBothRelaxationsTest(unittest.TestCase):
    """`estimate` relaxes twice when build targets are supplied (#86), and #110 must too.

    The second run is seeded clean and re-derives every price with real machine entry
    costs, so a fallback applied only to the first run would be thrown away and the keys
    would come back unreachable in exactly the configuration the server uses.
    """

    def test_machine_items_path_keeps_the_leaf_price(self):
        g = Graph()
        g.add(table_recipe(["mod:decor:0", "mod:decor:1"], rid="orphan"))
        g.add(Recipe("use", "t", [("mod:out", 1)],
                     [Ingredient(["mod:decor:0"], 1)], category="minecraft.crafting"))
        index.apply_recipe_filters(g)
        costs = cost.estimate(g, machine_states=STATES,
                              machine_items={CHISELING: ("chisel:chisel_iron",)})
        self.assertEqual(costs["mod:decor:0"], cost.BASE_RAW_COST)
        self.assertEqual(costs["mod:decor:1"], cost.BASE_RAW_COST)
        self.assertLess(costs["mod:out"], float("inf"))


class FilterInstallsItsResultTest(unittest.TestCase):
    """The pass must install `kept` whenever it did any work, tie or no tie.

    `len(kept) != before` was the old guard and expansion can make it coincidentally
    false: drop one recipe, expand one two-member table into two, and the count is
    unchanged while the contents are not. That guard is where `kept` gets installed, so a
    tie would have discarded every drop AND every expansion in the same graph.
    """

    def test_a_tie_between_drops_and_expansions_still_installs(self):
        g = Graph()
        g.add(Recipe("info", "t", [("mod:x", 1)],
                     [Ingredient(["mod:y"], 1)], category="jei.information"))
        # The anchor, without which the pair is correctly left dropped and there is no
        # expansion to tie against.
        g.add(Recipe("make_a", "t", [("mod:a", 1)],
                     [Ingredient(["mod:raw"], 1)], category="minecraft.crafting"))
        g.add(table_recipe(["mod:a", "mod:b"], rid="pair"))
        before = len(g.recipes)
        stats = index.apply_recipe_filters(g)
        self.assertEqual(len(g.recipes), before, "precondition: the counts tie")
        self.assertEqual(stats["dropped"], {"jei.information": 1})
        self.assertEqual(stats["expanded"], 2)
        self.assertEqual(sorted(r.category for r in g.recipes),
                         [CHISELING, CHISELING, "minecraft.crafting"])
        self.assertTrue(g.producers("mod:b"))

    def test_an_untouched_graph_is_left_alone(self):
        g = Graph()
        g.add(Recipe("real", "t", [("mod:a", 1)],
                     [Ingredient(["mod:b"], 1)], category="minecraft.crafting"))
        stats = index.apply_recipe_filters(g)
        self.assertEqual(len(g.recipes), 1)
        self.assertEqual(stats["expanded"], 0)


if __name__ == "__main__":
    unittest.main()
