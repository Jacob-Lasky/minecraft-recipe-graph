"""Recipe choice: the price a route is ranked at has to be the price of the route taken.

All of this is issue #29, reported as "it shows that to make 'iron' I need a 'molten iron
can' even though iron ore is perfectly acceptable". Three separate defects produced that
one plan, and each has its own class below:

  * `BatchAmortisationTest` -- the machine was divided by the output quantity, so a recipe
    yielding 1,024 ingots priced them at nothing and 126 items in the reference pack,
    diamond and coal among them, came out under 0.1.
  * `RankedRouteIsTheTakenRouteTest` -- a slot accepting either a Block of Iron or a
    decorative Chisel block was PRICED at the decorative one (a raw leaf, so cheap) and
    EXPANDED at the real one (which is cast from 1,296 mB of molten iron).
  * `CostBlindChoiceTest` -- with nothing in stock, every alternative tied and the choice
    fell through to whatever the dump listed first.

The fixtures deliberately mirror those shapes rather than inventing tidier ones; a graph
that cannot express the bug cannot prove the fix.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

# `minecraft.crafting` and `minecraft.smelting` are what `machines.is_hand_crafting`
# recognises, so using those names keeps the fixtures on the same code path as the pack.
STATES = {
    "minecraft.crafting": ("have", ""),
    "minecraft.smelting": ("have", ""),
    "mod.casting": ("buildable", ""),
    "mod.smeltery": ("buildable", ""),
    "mod.big": ("buildable", ""),
    "mod.gone": ("unavailable", ""),
}


def iron_graph():
    """The reported plan's shape: an honest smelt losing to an unpacking step.

    `mod:decor` is the decorative block. It is an input alternative that no recipe
    produces, which is the whole point -- it lands on BASE_RAW_COST and makes the
    unpacking recipe look like the cheapest thing in the graph.
    """
    g = Graph()
    g.names = {
        "mod:ingot": "Iron Ingot", "mod:ore": "Iron Ore", "mod:rock": "Ore Chunk",
        "mod:block": "Block of Iron", "mod:decor": "Chiselled Iron Panel",
    }
    # The honest route, and a recipe for the ore so the smelt is not the cheapest thing
    # by default. Without this the two routes TIE and the test would only be measuring
    # which one the dump happens to list first.
    g.add(Recipe("smelt", "t", [("mod:ingot", 1)],
                 [Ingredient(["mod:ore"], 1)], category="minecraft.smelting"))
    g.add(Recipe("crush_rock", "t", [("mod:ore", 1)],
                 [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
    # The decoy: unpack a block into nine ingots. The slot takes the real block OR the
    # decorative one, exactly as the pack's does.
    g.add(Recipe("uncraft", "t", [("mod:ingot", 9)],
                 [Ingredient(["mod:block", "mod:decor"], 1)],
                 category="minecraft.crafting"))
    # ...and the only way to a real block is casting molten metal, which is expensive.
    g.add(Recipe("cast", "t", [("mod:block", 1)],
                 [Ingredient(["fluid:molten"], 1296, "fluid")], category="mod.casting"))
    g.add(Recipe("melt", "t", [("fluid:molten", 288)],
                 [Ingredient(["mod:ore"], 1)], category="mod.smeltery"))
    return g


class BatchAmortisationTest(unittest.TestCase):
    """Cost of entry is charged per run; only the ingredients divide by the batch."""

    @staticmethod
    def _graph(qty, category):
        g = Graph()
        g.names = {}
        g.add(Recipe("batch", "t", [("mod:out", qty)],
                     [Ingredient(["mod:seed"], 1)], category=category))
        return g

    def test_a_huge_batch_does_not_make_its_machine_free(self):
        costs = cost.estimate(self._graph(1024, "mod.big"), machine_states=STATES)
        self.assertGreater(costs["mod:out"], cost.MACHINE_COST["buildable"])

    def test_the_ingredients_still_amortise(self):
        # The fix must not turn into "batching buys you nothing". One seed spread over
        # 1,024 outputs has to cost less per output than one seed spread over one.
        big = cost.estimate(self._graph(1024, "mod.big"), machine_states=STATES)
        one = cost.estimate(self._graph(1, "mod.big"), machine_states=STATES)
        self.assertLess(big["mod:out"], one["mod:out"])

    def test_an_unavailable_machine_cannot_be_diluted_away(self):
        # MACHINE_COST puts a 5,000 wall in front of a machine that cannot be obtained.
        # The pack has a recipe yielding 60,466,176 of one item, which divided that wall
        # down to 8e-5 -- so the wall existed only for recipes that did not need it.
        costs = cost.estimate(self._graph(60466176, "mod.gone"), machine_states=STATES)
        self.assertGreater(costs["mod:out"], cost.MACHINE_COST["unavailable"])

    def test_the_formula_version_invalidates_a_cached_table(self):
        # Every other input to the estimate is unchanged by an arithmetic change, so
        # without this a machine holding a cost cache serves pre-fix prices forever.
        args = ("graph.json", {}, {}, ())
        before = cost.fingerprint(*args)
        original = cost.FORMULA_VERSION
        try:
            cost.FORMULA_VERSION = original + 1
            self.assertNotEqual(cost.fingerprint(*args), before)
        finally:
            cost.FORMULA_VERSION = original


class RankedRouteIsTheTakenRouteTest(unittest.TestCase):
    """`score_recipe` must price the branch `_build` will expand, slot by slot."""

    @staticmethod
    def _solved():
        g = iron_graph()
        costs = cost.estimate(g, machine_states=STATES)
        return Solver(g, machine_states=STATES, costs=costs).solve("mod:ingot", 1)

    def test_one_ingot_comes_from_smelting_an_ore(self):
        result = self._solved()
        self.assertEqual(result["tree"]["recipe"], "smelt")
        self.assertEqual([c["key"] for c in result["tree"]["children"]], ["mod:ore"])

    def test_the_plan_never_reaches_the_molten_metal(self):
        # The reported symptom in the reporter's own words. Asserting on the chosen recipe
        # alone would still pass if a later slot wandered into the casting chain.
        result = self._solved()

        def keys(node):
            return [node["key"]] + [k for c in node.get("children") or () for k in keys(c)]

        self.assertNotIn("fluid:molten", keys(result["tree"]))

    def test_the_recipe_is_priced_at_the_alternative_it_would_expand(self):
        # The mechanism, stated directly: `mod:decor` is the cheaper option in that slot
        # and `mod:block` is the one `pick_alternative` returns, so the score has to come
        # from `mod:block`.
        g = iron_graph()
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs)
        uncraft = [r for r in g.recipes if r.rid == "uncraft"][0]
        self.assertEqual(solver.pick_alternative(uncraft.inputs[0]), "mod:block")
        self.assertLess(costs["mod:decor"], costs["mod:block"])
        self.assertAlmostEqual(
            solver.estimated_cost(uncraft),
            cost.MACHINE_COST["have"] + costs["mod:block"], places=6)

    def test_stock_still_makes_unpacking_the_right_answer(self):
        # The fix demotes unpacking because the block is expensive to MAKE, not because
        # unpacking is forbidden. Hand the solver a block and it should use it.
        #
        # `have` goes to BOTH the estimate and the Solver, which is what cli.py and
        # server.py do. Passing it to only the Solver leaves the ranker pricing the block
        # at what it costs to manufacture, and a stocked block loses to smelting an ore
        # it does not have -- the same class of disagreement this whole module is about.
        g = iron_graph()
        stock = {"mod:block": 4}
        costs = cost.estimate(g, have=stock, machine_states=STATES)
        result = Solver(g, have=dict(stock), machine_states=STATES,
                        costs=costs).solve("mod:ingot", 9)
        self.assertEqual(result["tree"]["recipe"], "uncraft")
        self.assertEqual(result["tree"]["children"][0]["status"], "have")


class CostBlindChoiceTest(unittest.TestCase):
    """With an empty pool, availability ties and cost is the only real signal left."""

    @staticmethod
    def _graph():
        """Two routes to the same material, the ruinous one listed first."""
        g = Graph()
        g.names = {}
        g.ore_members = {"stuff": ["mod:pricey", "mod:cheap"]}
        g.add(Recipe("use_alt", "t", [("mod:thing", 1)],
                     [Ingredient(["mod:pricey", "mod:cheap"], 1)],
                     category="minecraft.crafting"))
        g.add(Recipe("use_ore", "t", [("mod:widget", 1)],
                     [Ingredient(["ore:stuff"], 1)], category="minecraft.crafting"))
        # Both alternatives are producible, so the has-a-recipe bonus cannot separate them.
        g.add(Recipe("make_pricey", "t", [("mod:pricey", 1)],
                     [Ingredient(["mod:rock"], 64)], category="mod.big"))
        g.add(Recipe("make_cheap", "t", [("mod:cheap", 1)],
                     [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
        return g

    def _solve(self, target):
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        self.assertLess(costs["mod:cheap"], costs["mod:pricey"])
        return Solver(g, machine_states=STATES, costs=costs).solve(target, 1)

    def test_an_input_slot_takes_the_cheaper_alternative(self):
        result = self._solve("mod:thing")
        self.assertEqual([c["key"] for c in result["tree"]["children"]], ["mod:cheap"])

    def test_an_oredict_resolves_to_the_cheaper_member(self):
        result = self._solve("mod:widget")
        self.assertEqual(result["tree"]["children"][0]["resolved_to"], "mod:cheap")

    def test_stock_still_outranks_cost(self):
        # Cost is a TIEBREAK. Something already on the shelf ends the branch, which is
        # worth more than a cheaper thing that still has to be made.
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        result = Solver(g, have={"mod:pricey": 1}, machine_states=STATES,
                        costs=costs).solve("mod:thing", 1)
        self.assertEqual([c["key"] for c in result["tree"]["children"]], ["mod:pricey"])

    def test_slot_cost_is_the_same_arithmetic_the_ranker_uses(self):
        # `slot_cost` claims to be `cost.input_cost` rather than a private lookup, which is
        # what makes the tiebreak comparable to the price. Two things only that shared
        # normalisation gets right: an oredict key resolves to its cheapest MEMBER, and a
        # fluid is measured in buckets, so 1,000 mB does not read as a thousand items.
        g = self._graph()
        g.add(Recipe("boil", "t", [("fluid:brine", 1000)],
                     [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs)
        self.assertEqual(solver.slot_cost("ore:stuff"), costs["mod:cheap"])
        self.assertAlmostEqual(solver.slot_cost("fluid:brine", 1000),
                               costs["fluid:brine"], places=6)

    def test_a_solver_without_costs_is_unaffected(self):
        # `slot_cost` returns 0.0 rather than infinity precisely so this stays true, and
        # a bare `Solver(graph)` is what most of the other suites build.
        g = self._graph()
        solver = Solver(g)
        self.assertEqual(solver.slot_cost("mod:cheap"), 0.0)
        self.assertEqual(solver.slot_cost("ore:stuff"), 0.0)
        self.assertEqual(solver.solve("mod:thing", 1)["tree"]["children"][0]["key"],
                         "mod:pricey")


if __name__ == "__main__":
    unittest.main()
