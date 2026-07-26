"""Smoke tests: no game instance or world save required.

Run: python3 -m unittest discover -s tests
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from mbcgraph.model import Graph, Ingredient, Recipe, norm_key  # noqa: E402
from mbcgraph.solve import Solver  # noqa: E402
from mbcgraph.sources.jar_json import parse_recipe_json  # noqa: E402
from mbcgraph.sources.oredict import guess_from_names  # noqa: E402


class TestKeys(unittest.TestCase):
    def test_meta_zero_is_omitted(self):
        self.assertEqual(norm_key("minecraft:stone", 0), "minecraft:stone")
        self.assertEqual(norm_key("minecraft:stone"), "minecraft:stone")

    def test_meta_kept(self):
        self.assertEqual(norm_key("nuclearcraft:compound", 7), "nuclearcraft:compound:7")

    def test_wildcard(self):
        self.assertEqual(norm_key("mod:thing", 32767), "mod:thing:*")

    def test_bare_name_gets_minecraft_namespace(self):
        self.assertEqual(norm_key("diamond"), "minecraft:diamond")


class TestJarJson(unittest.TestCase):
    def test_shaped_counts_pattern_occurrences(self):
        doc = {
            "type": "minecraft:crafting_shaped",
            "pattern": ["XXX", "XXX", "XXX"],
            "key": {"X": {"item": "minecraft:diamond"}},
            "result": {"item": "minecraft:diamond_block", "count": 1},
        }
        r = parse_recipe_json(doc, "t")
        self.assertEqual(r.outputs, [("minecraft:diamond_block", 1)])
        self.assertEqual(len(r.inputs), 1)
        self.assertEqual(r.inputs[0].qty, 9)

    def test_oredict_ingredient(self):
        doc = {
            "type": "forge:ore_shapeless",
            "ingredients": [{"type": "forge:ore_dict", "ore": "ingotIron"}],
            "result": {"item": "mod:plate"},
        }
        r = parse_recipe_json(doc, "t")
        self.assertEqual(r.inputs[0].alternatives, ["ore:ingotIron"])

    def test_constants_reference_resolves(self):
        constants = {"lattice": {"item": "avaritia:resource", "data": 0}}
        doc = {
            "type": "minecraft:crafting_shaped",
            "pattern": ["LL"],
            "key": {"L": {"type": "minecraft:item", "item": "#lattice"}},
            "result": {"item": "avaritia:resource", "data": 1},
        }
        r = parse_recipe_json(doc, "t", constants)
        self.assertEqual(r.inputs[0].alternatives, ["avaritia:resource"])

    def test_unresolved_constant_is_dropped_not_faked(self):
        # A bogus `minecraft:#name` key would become a phantom shopping-list item.
        doc = {
            "type": "minecraft:crafting_shaped",
            "pattern": ["LL"],
            "key": {"L": {"item": "#missing"}},
            "result": {"item": "mod:thing"},
        }
        self.assertIsNone(parse_recipe_json(doc, "t", {}))


def _graph():
    g = Graph()
    g.names = {
        "mod:ingot": "Test Ingot",
        "mod:block": "Test Block",
        "mod:lattice": "Test Lattice",
        "mod:dust": "Test Dust",
    }
    # real route: 4 lattice -> 1 ingot
    g.add(Recipe("real", "t", [("mod:ingot", 1)], [Ingredient(["mod:lattice"], 4)]))
    # uncrafting route: 1 block -> 9 ingots  (and block is 9 ingots -> cycle)
    g.add(Recipe("uncraft", "t", [("mod:ingot", 9)], [Ingredient(["mod:block"], 1)]))
    g.add(Recipe("block", "t", [("mod:block", 1)], [Ingredient(["mod:ingot"], 9)]))
    return g


class TestSolver(unittest.TestCase):
    def test_backtracks_out_of_uncrafting_cycle(self):
        res = Solver(_graph(), have={}).solve("mod:ingot", 1)
        needs = {r["key"]: r["qty"] for r in res["shopping_list"]}
        self.assertEqual(needs, {"mod:lattice": 4},
                         "should take the real route, not block->9 ingots")

    def test_stock_short_circuits_expansion(self):
        res = Solver(_graph(), have={"mod:ingot": 5}).solve("mod:ingot", 5)
        self.assertEqual(res["shopping_list"], [])
        self.assertEqual(res["tree"]["status"], "have")

    def test_stock_is_consumed_not_reused_across_branches(self):
        g = _graph()
        g.add(Recipe("two", "t", [("mod:dust", 1)],
                     [Ingredient(["mod:lattice"], 3), Ingredient(["mod:lattice"], 3)]))
        # only 4 lattice in stock but 6 required -> 2 must still be sourced
        res = Solver(g, have={"mod:lattice": 4}).solve("mod:dust", 1)
        needs = {r["key"]: r["qty"] for r in res["shopping_list"]}
        self.assertEqual(needs.get("mod:lattice"), 2)

    def test_partial_stock_reduces_remainder(self):
        res = Solver(_graph(), have={"mod:lattice": 1}).solve("mod:ingot", 1)
        needs = {r["key"]: r["qty"] for r in res["shopping_list"]}
        self.assertEqual(needs, {"mod:lattice": 3})


class TestOredictGuess(unittest.TestCase):
    def test_common_forms(self):
        names = {
            "minecraft:iron_ingot": "Iron Ingot",
            "minecraft:iron_block": "Block of Iron",
            "nuclearcraft:compound:7": "Borax",
        }
        got = guess_from_names(["ingotIron", "blockIron", "dustBorax"], names)
        self.assertEqual(got["ingotIron"], ["minecraft:iron_ingot"])
        self.assertEqual(got["blockIron"], ["minecraft:iron_block"])
        # bare-name form: NuclearCraft calls dustBorax simply "Borax"
        self.assertEqual(got["dustBorax"], ["nuclearcraft:compound:7"])

    def test_unknown_prefix_is_not_guessed(self):
        self.assertEqual(guess_from_names(["netherStar"], {"x:y": "Nether Star"}), {})


if __name__ == "__main__":
    unittest.main()
