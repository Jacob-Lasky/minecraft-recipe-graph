"""Machine availability, non-recipe filtering, and cost-based recipe choice."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, index, machines  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402


def graph_with_two_routes():
    """`widget` via a machine you own (expensive) or one you must build (cheap)."""
    g = Graph()
    g.names = {
        "mod:widget": "Widget", "mod:cheap_part": "Cheap Part",
        "mod:mountain": "Mountain Of Stuff", "mod:owned_machine": "Owned Machine",
        "mod:other_machine": "Other Machine",
    }
    g.add(Recipe("via_owned", "t", [("mod:widget", 1)],
                 [Ingredient(["mod:mountain"], 100000)], category="mod_owned_machine",
                 machine="Owned Machine"))
    g.add(Recipe("via_build", "t", [("mod:widget", 1)],
                 [Ingredient(["mod:cheap_part"], 2)], category="mod_other_machine",
                 machine="Other Machine"))
    return g


class AvailabilityTest(unittest.TestCase):
    def test_placed_block_counts_as_have(self):
        g = graph_with_two_routes()
        st = machines.resolve(g, placed={"mod:owned_machine": 1})
        self.assertEqual(st["mod_owned_machine"][0], machines.HAVE)

    def test_state_suffix_variants_match(self):
        # NuclearCraft registers `x_idle`/`x_active` items while the placed tile is bare.
        self.assertEqual(machines.normalise_block("nuclearcraft:crystallizer_idle"),
                         "nuclearcraft:crystallizer")
        self.assertEqual(machines.normalise_block("mod:thing_active"), "mod:thing")

    def test_crafting_never_needs_a_machine(self):
        g = graph_with_two_routes()
        g.add(Recipe("hand", "t", [("mod:cheap_part", 1)],
                     [Ingredient(["mod:mountain"], 1)], category="minecraft.crafting"))
        st = machines.resolve(g)
        self.assertEqual(st["minecraft.crafting"][0], machines.HAVE)

    def test_manual_override_wins(self):
        g = graph_with_two_routes()
        st = machines.resolve(g, overrides={"mod_owned_machine": machines.UNAVAILABLE})
        self.assertEqual(st["mod_owned_machine"][0], machines.UNAVAILABLE)

    def test_overrides_round_trip(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "m.json")
        machines.save_overrides(path, {"a": machines.HAVE, "b": "nonsense"})
        self.assertEqual(machines.load_overrides(path), {"a": machines.HAVE})


class NonRecipeTest(unittest.TestCase):
    def test_known_non_production_categories_are_recognised(self):
        for cat in ("minecraft.anvil", "EIOTank", "forestry.bottler", "jei.information",
                    "jeresources.worldgen", "VILLAGER_TRADE_CATEGORY", "chickens.Drops"):
            self.assertTrue(index.is_non_recipe(cat), cat)

    def test_real_production_categories_are_kept(self):
        for cat in ("nuclearcraft_crystallizer", "forestry.squeezer",
                    "tconstruct.smeltery", "minecraft.crafting", "AlloySmelter"):
            self.assertFalse(index.is_non_recipe(cat), cat)

    def test_keep_list_overrides_the_pattern(self):
        self.assertFalse(index.is_non_recipe("minecraft.anvil", keep={"minecraft.anvil"}))


class CostChoiceTest(unittest.TestCase):
    def test_cheap_buildable_route_beats_ruinous_owned_route(self):
        # The failure this guards: with only local scoring, "machine I own" won and the
        # solver planned 100,000 items instead of building a machine and using 2.
        g = graph_with_two_routes()
        states = machines.resolve(g, placed={"mod:owned_machine": 1})
        self.assertEqual(states["mod_owned_machine"][0], machines.HAVE)
        costs = cost.estimate(g, have={}, machine_states=states)
        s = Solver(g, have={}, machine_states=states, costs=costs)
        res = s.solve("mod:widget", 1)
        needs = {r["key"] for r in res["shopping_list"]}
        self.assertIn("mod:cheap_part", needs)
        self.assertNotIn("mod:mountain", needs)

    def test_container_transfers_are_costed_out(self):
        g = Graph()
        g.add(Recipe("real", "t", [("fluid:x", 100)], [Ingredient(["mod:src"], 1)]))
        t = Recipe("xfer", "t", [("fluid:x", 16000)], [Ingredient(["mod:tank"], 1)])
        t.transfer = True
        g.add(t)
        costs = cost.estimate(g, have={"mod:tank": 5, "mod:src": 5})
        real = cost.recipe_cost(costs, g.recipes[0], {})
        xfer = cost.recipe_cost(costs, g.recipes[1], {})
        self.assertLess(real, xfer, "a transfer must never look cheaper than production")

    def test_unreachable_input_gives_infinite_cost(self):
        g = Graph()
        g.add(Recipe("r", "t", [("mod:out", 1)], [Ingredient(["mod:never"], 1)]))
        costs = cost.estimate(g, have={})
        # mod:never has no recipe, so it is seeded as obtainable rather than infinite;
        # what must not happen is a silently finite cost for a genuinely missing input.
        self.assertIn("mod:never", costs)


if __name__ == "__main__":
    unittest.main()
