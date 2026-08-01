"""Where a plan is allowed to bottom out. See #106.

Reported as "to make that strong mythic essence the base material are sednanite nuggets,
which is obviously wrong". It was, and the mechanism is general: the solver stops only on a
key with no producer, so on a nugget/ingot/dust ladder the NUGGET is the only rung that
qualifies, while the ore a player would actually go and get has two Plasmatic Condenser
recipes and therefore looks craftable.

The graph does carry a signal for "you obtain this by hitting it with a pick": the pack's
own `ore*` oredict registration, which `Graph.world_ores` reads back. These tests pin both
halves of using it -- an ore can never price above a raw leaf, and the solver stops there.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import STATUS_HAVE, STATUS_RAW, Solver  # noqa: E402

# The reported shape, reduced to its bones. Nugget -> Ingot -> Dust -> the fluid, with the
# ore off to one side behind a recipe nothing can afford.
ORE = "mod:sednanite_ore"
NUGGET = "mod:sednanite_nugget"
INGOT = "mod:sednanite_ingot"
DUST = "mod:sednanite_dust"
FLUID = "fluid:molten_sednanite"
PLASMA = "fluid:dense_plasma"


def ladder():
    g = Graph()
    g.names = {ORE: "Sednanite Ore", NUGGET: "Sednanite Nugget",
               INGOT: "Sednanite Ingot", DUST: "Sednanite Dust",
               "mod:condenser": "Plasmatic Condenser"}
    # The pack says this block is an ore. Everything below turns on that one fact.
    g.ore_members = {"oreSednanite": [ORE]}

    # The ladder: nine nuggets make an ingot, and nothing makes a nugget.
    g.add(Recipe("nugget_to_ingot", "t", [(INGOT, 1)], [Ingredient([NUGGET], 9)],
                 category="minecraft.crafting"))
    # The honest route, which must win once the ore is priced.
    g.add(Recipe("smelt_ore", "t", [(INGOT, 1)], [Ingredient([ORE], 1)],
                 category="minecraft.smelting"))
    g.add(Recipe("ingot_to_dust", "t", [(DUST, 1)], [Ingredient([INGOT], 1)],
                 category="minecraft.crafting"))
    g.add(Recipe("melt_dust", "t", [(FLUID, 144)], [Ingredient([DUST], 1)],
                 category="tconstruct.smeltery"))
    # And the reason the ore looked craftable: a recipe that emits it, wanting 160,000 mB
    # of a fluid nothing produces. Priced at infinity, so the ore was too.
    g.add(Recipe("condense", "t", [(ORE, 1)], [Ingredient([PLASMA], 160000, "fluid")],
                 category="modularmachinery.recipes.plasmatic_condenser"))
    return g


def costs_for(graph, have=None):
    return cost_mod.estimate(graph, have=have or {})


def solve(graph, key, qty=1, have=None, **kw):
    have = have or {}
    return Solver(graph, have=have, costs=costs_for(graph, have), **kw).solve(key, qty)


def _condenser_price(graph):
    """What the ore's own recipe costs, with the ore's mining seed taken back out.

    Written as its own function because the number is the whole argument for the fix: the
    seed has to beat this, and reading it off a patched table would be circular.
    """
    table = cost_mod.estimate(graph)
    return cost_mod.recipe_cost(table, graph.by_rid["condense"][0], graph.ore_members)


def leaves(node, out=None):
    """Every terminal node of a plan tree, as `(key, status)`."""
    out = [] if out is None else out
    kids = node.get("children") or []
    if not kids:
        out.append((node["key"], node.get("status")))
    for child in kids:
        leaves(child, out)
    return out


class AnOreIsNeverDearerThanMiningItTest(unittest.TestCase):
    def test_an_ore_a_recipe_produces_is_still_priced_as_obtainable(self):
        # THE COST HALF OF #106. `_seed` used to price a leaf as obtainable only when no
        # recipe output it, which assumes the only way to get a thing is to make one.
        table = costs_for(ladder())
        self.assertEqual(table[ORE], cost_mod.BASE_RAW_COST)

    def test_the_ore_counts_as_produced_which_is_why_it_needed_the_seed(self):
        # The condition the fix removes: the ore HAS a producer, so the leaf rule above it
        # skipped it, and its only route wants 160,000 mB of plasma. The fixture prices that
        # at 180 rather than the reference graph's infinity, which makes this the harder
        # case -- the fix has to beat an expensive route, not merely an impossible one.
        g = ladder()
        self.assertTrue(g.producers(ORE))
        self.assertGreater(_condenser_price(g), 100.0)

    def test_an_ore_whose_only_route_is_genuinely_unreachable(self):
        # 14 of the 286 world ores on the reference graph are the infinite version of this:
        # produced only by recipes the relaxation cannot price at all.
        g = ladder()
        g.add(Recipe("plasma_loop", "t", [(PLASMA, 1000)],
                     [Ingredient(["mod:unobtainium"], 1)], category="mod.void"))
        g.add(Recipe("unobtainium", "t", [("mod:unobtainium", 1)],
                     [Ingredient([PLASMA], 1000, "fluid")], category="mod.void"))
        table = cost_mod.estimate(g)
        # `.get`, because a key reachable only through a cycle never enters the table at
        # all: `_seed` skips it as produced and no relaxation pass ever prices it. Absent
        # and infinite are the same claim here, and every reader treats them alike.
        self.assertEqual(table.get(PLASMA, float("inf")), float("inf"))
        self.assertEqual(table[ORE], cost_mod.BASE_RAW_COST)

    def test_stock_still_beats_mining(self):
        # `min`, not assignment. Something on the shelf is free at the margin and mining
        # is not, so the seed must never raise a price.
        self.assertEqual(costs_for(ladder(), {ORE: 64})[ORE], 0.0)

    def test_the_seed_only_ever_lowers_a_price(self):
        # `min`, not assignment. Nothing here may make an ore DEARER than the relaxation
        # already found it, whatever else changes.
        g = ladder()
        g.names["mod:pebble"] = "Pebble"
        g.add(Recipe("cheap_ore", "t", [(ORE, 100)], [Ingredient(["mod:pebble"], 1)],
                     category="minecraft.crafting"))
        self.assertLessEqual(costs_for(g)[ORE], cost_mod.BASE_RAW_COST)

    def test_a_block_that_is_not_an_ore_is_untouched(self):
        # The signal is the pack's `ore*` registration and nothing else. A decorative block
        # wearing a similar name must not become free, which is the failure `world_ores`
        # was introduced in #61 to avoid in the first place.
        g = ladder()
        g.names["mod:sednanite_panel"] = "Sednanite Panel"
        g.add(Recipe("panel", "t", [("mod:sednanite_panel", 1)],
                     [Ingredient([PLASMA], 160000, "fluid")], category="minecraft.crafting"))
        # Far above mining, where the ore beside it is at exactly BASE_RAW_COST.
        self.assertGreater(costs_for(g)["mod:sednanite_panel"], cost_mod.BASE_RAW_COST)
        self.assertEqual(costs_for(g)[ORE], cost_mod.BASE_RAW_COST)

    def test_the_formula_version_moved_with_the_formula(self):
        # A cost cache keyed on graph, stock and machine states sees NONE of this change,
        # so without the bump a warm `.cost-cache.json` keeps serving nugget-ladder prices
        # forever, which reads as "the fix did not work" rather than as a stale cache.
        self.assertGreaterEqual(cost_mod.FORMULA_VERSION, 5)


class AMachineCostsAtLeastAsMuchAsMiningTest(unittest.TestCase):
    """Why `expand` stops at a world ore UNCONDITIONALLY rather than comparing prices.

    `Solver.expand` names this class. If it starts failing, that stop is no longer provably
    right and has to become a comparison against the best candidate's per-unit price.
    """

    def test_the_cheapest_possible_machine_entry_is_not_below_a_raw_leaf(self):
        # `_relax` prices an output at `machine_entry + inputs / qty`, and the entry is NOT
        # divided by the yield. So the floor on any crafted price is the cheapest entry
        # there is, and while that is >= BASE_RAW_COST, no recipe can undercut mining --
        # however large its yield or however free its inputs.
        self.assertGreaterEqual(cost_mod.MACHINE_COST["have"], cost_mod.BASE_RAW_COST)

    def test_even_a_free_machine_with_a_huge_yield_cannot_undercut_mining(self):
        # The claim above, exercised rather than asserted about constants: a hand-crafting
        # recipe turning one held pebble into a hundred ore still does not beat the pick.
        g = ladder()
        g.names["mod:pebble"] = "Pebble"
        g.add(Recipe("bulk_ore", "t", [(ORE, 100)], [Ingredient(["mod:pebble"], 1)],
                     category="minecraft.crafting"))
        table = cost_mod.estimate(g, have={"mod:pebble": 9999},
                                  machine_states={"minecraft.crafting": ("have", "")})
        self.assertEqual(table[ORE], cost_mod.BASE_RAW_COST)


class ThePlanStopsAtTheOreTest(unittest.TestCase):
    def test_the_reported_plan_no_longer_bottoms_out_at_nuggets(self):
        # The regression this whole file is for. Before the fix:
        #   Molten Sednanite -> Dust -> Ingot -> Sednanite Nugget x18   <- "you still need"
        result = solve(ladder(), FLUID, 288)
        shopping = {row["key"] for row in result["shopping_list"]}
        self.assertNotIn(NUGGET, shopping)
        self.assertEqual(shopping, {ORE})

    def test_the_ore_is_a_leaf_rather_than_a_condenser_subtree(self):
        # The other way to get the nugget off the list would be to expand the ore into its
        # Plasmatic Condenser recipe, which is not an improvement: it is a bigger wrong
        # answer resting on 160,000 mB of a fluid nothing makes.
        result = solve(ladder(), FLUID, 288)
        self.assertEqual(leaves(result["tree"]), [(ORE, STATUS_RAW)])
        self.assertNotIn(PLASMA, {key for key, _s in leaves(result["tree"])})

    def test_the_leaf_says_why_it_stopped(self):
        node = solve(ladder(), FLUID, 144)["tree"]
        while node.get("children"):
            node = node["children"][0]
        self.assertEqual(node["note"], "mined, not crafted")

    def test_stock_is_spent_before_anyone_is_sent_mining(self):
        # The ore check sits after `take`, so holding some means holding some.
        result = solve(ladder(), INGOT, 4, have={ORE: 10})
        self.assertEqual(result["shopping_list"], [])
        self.assertEqual(leaves(result["tree"]), [(ORE, STATUS_HAVE)])

    def test_only_the_shortfall_is_charged_to_a_pickaxe(self):
        result = solve(ladder(), INGOT, 10, have={ORE: 4})
        self.assertEqual([(r["key"], r["qty"]) for r in result["shopping_list"]],
                         [(ORE, 6)])

    def test_a_declared_raw_stop_still_wins_over_the_ore_rule(self):
        # `raw` is the user saying "stop here", and it is checked first. Both produce a
        # shopping-list entry, so the assertion is that the ORE never gets expanded past a
        # stop the user put above it.
        result = solve(ladder(), INGOT, 1, raw={INGOT})
        self.assertEqual(leaves(result["tree"]), [(INGOT, STATUS_RAW)])

    def test_a_non_ore_with_producers_still_expands(self):
        # The rule must not leak into ordinary keys: the ingot has recipes and is not an
        # ore, so it is still crafted rather than added to the shopping list.
        tree = solve(ladder(), INGOT, 1)["tree"]
        self.assertEqual(tree["status"], "craft")
        self.assertEqual(tree["recipe"], "smelt_ore")

    def test_the_ore_route_is_chosen_over_the_nugget_route(self):
        # The cost half and the expansion half are separable, and this is the cost half
        # showing up in a plan: with the ore priced, `9x Nugget` at 9.0 loses to `1x Ore`.
        self.assertEqual(solve(ladder(), INGOT, 1)["tree"]["recipe"], "smelt_ore")

    def test_the_nugget_route_is_still_taken_when_there_is_no_ore_route(self):
        # Nothing here forbids a denomination change. A pack where the only way to an ingot
        # really is nine nuggets must still plan it.
        g = ladder()
        g.recipes = [r for r in g.recipes if r.rid != "smelt_ore"]
        g._invalidate()
        result = solve(g, INGOT, 1)
        self.assertEqual(result["tree"]["recipe"], "nugget_to_ingot")
        self.assertEqual([r["key"] for r in result["shopping_list"]], [NUGGET])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
