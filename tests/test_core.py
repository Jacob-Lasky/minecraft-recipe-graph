"""Smoke tests: no game instance or world save required.

Run: python3 -m unittest discover -s tests
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import solve as solve_mod  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe, norm_key  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402
from recipegraph.sources.jar_json import parse_recipe_json  # noqa: E402
from recipegraph.sources.oredict import guess_from_names  # noqa: E402


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


class TestPool(unittest.TestCase):
    """The stock pool, which `take` and `available` read on every single expansion."""

    def _split_calls(self, pool_size, takes=50):
        """How many split_key calls N takes cost against a pool of `pool_size` keys."""
        pool = {"mod:thing%d" % i: 10 for i in range(pool_size)}
        s = Solver(_graph(), have=pool)
        calls = []
        real = solve_mod.split_key
        solve_mod.split_key = lambda k: (calls.append(k), real(k))[1]
        try:
            for i in range(takes):
                s.take("mod:thing%d" % i, 1)
        finally:
            solve_mod.split_key = real
        return len(calls)

    def test_take_cost_does_not_grow_with_the_pool(self):
        """#18: `take` scanned every pool key to discover it needed only one of them.

        3,389 stocked keys made a 4,000-node plan cost 23 million split_key calls and
        20 seconds. Asserting the two pool sizes cost the SAME is the durable form of
        "not O(pool)" -- a threshold would drift with unrelated changes.
        """
        small = self._split_calls(100)
        large = self._split_calls(2000)
        self.assertEqual(small, large,
                         "take is scanning the pool: %d calls at 100 keys, %d at 2000"
                         % (small, large))

    def test_wildcard_take_still_spans_metas(self):
        """The pool scan existed to serve wildcards; the index must keep that working."""
        s = Solver(_graph(), have={"mod:thing:1": 3, "mod:thing:2": 4, "other:x": 9})
        self.assertEqual(s.available("mod:thing:*"), 7)
        self.assertEqual(s.take("mod:thing:*", 6), 6)
        self.assertEqual(s.pool["mod:thing:1"], 0)
        self.assertEqual(s.pool["mod:thing:2"], 1)
        self.assertEqual(s.pool["other:x"], 9, "an unrelated base must not be drained")

    def test_wildcard_in_stock_is_not_counted_twice(self):
        """`available` added pool[key] and then re-added it while scanning for the base."""
        s = Solver(_graph(), have={"mod:thing:*": 5, "mod:thing:1": 2})
        self.assertEqual(s.available("mod:thing:*"), 7)
        self.assertEqual(s.take("mod:thing:*", 7), 7,
                         "take must be able to deliver everything available() promised")

    def test_pool_key_set_is_fixed_for_a_solvers_lifetime(self):
        """The tripwire for the base index, which is built once and never invalidated.

        `take` only DECREMENTS, and `_restore` swaps in a copy of the same keys, so the
        index cannot go stale. If a future change inserts a key into `pool`, this fails
        first and points at `_by_base` -- see the comment on `_index_pool`.
        """
        g = _graph()
        g.add(Recipe("two", "t", [("mod:dust", 1)],
                     [Ingredient(["mod:lattice"], 3), Ingredient(["mod:block"], 1)]))
        s = Solver(g, have={"mod:lattice": 4, "mod:ingot": 2})
        before = set(s.pool)
        s.solve("mod:dust", 3)
        self.assertEqual(set(s.pool), before)


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
