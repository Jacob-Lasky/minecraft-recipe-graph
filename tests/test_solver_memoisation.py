"""The two caches #308 added, and the mistakes each is shaped to survive.

WHAT BREAKS WITHOUT THIS. Both caches are invisible when they work: the plan is identical
either way, so every other test in this repository passes with them installed, removed, or
installed WRONG. `PlanFixtureTest` and `tests/test_plan_fixtures.py` prove the plans did not
move, which is the correctness half and is the stronger instrument -- but a cache that is
silently not installed also does not move any plan, and neither does a cache that is stale in
a way this pack's fixtures happen not to exercise. This file is the other half: it asserts the
caches EXIST, that they are consulted, and that the one with an invalidation hazard is scoped
where the hazard cannot reach it.

EACH TEST WAS RUN AGAINST THE MISTAKE IT DESCRIBES BEFORE THE PASS WAS BELIEVED. Where that is
not a one-line revert the docstring says what was done instead.
"""

import unittest

from recipegraph import solve
from recipegraph.model import Graph, Ingredient, Recipe, split_key


def _graph():
    """Two routes to one ingot, so `_routable` has something to say about several keys."""
    g = Graph()
    g.names = {"mod:ingot": "Iron Ingot", "mod:ore": "Iron Ore", "mod:rock": "Ore Chunk"}
    g.add(Recipe("smelt", "t", [("mod:ingot", 1)],
                 [Ingredient(["mod:ore"], 1)], category="minecraft.smelting"))
    g.add(Recipe("crush", "t", [("mod:ore", 1)],
                 [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
    return g


class SplitKeyIsMemoisedTest(unittest.TestCase):
    """`model.split_key`, 8,905,936 calls over 264,113 distinct keys in one real plan.

    Reverting the decorator makes every test here fail on `AttributeError`, which is the
    point: without it there is no `cache_info` to ask, and the perf claim has nothing
    standing behind it in the suite.
    """

    def test_it_has_a_cache_at_all(self):
        self.assertTrue(hasattr(split_key, "cache_info"),
                        "split_key is not memoised; #308's headline arm is not installed")

    def test_the_cache_is_actually_consulted(self):
        # A cache that is present and never hit would be pure overhead, and is what a
        # `maxsize` small enough to thrash would produce.
        before = split_key.cache_info()
        split_key("mod:ingot:3")
        split_key("mod:ingot:3")
        after = split_key.cache_info()
        self.assertGreater(after.hits, before.hits)

    def test_it_is_unbounded_so_the_hot_path_never_evicts(self):
        # `maxsize=None` is the deliberate choice recorded on the function: the key space is
        # the graph's and converges, so a bound would add eviction bookkeeping to the
        # solver's hottest function in exchange for capping something already capped.
        self.assertIsNone(split_key.cache_info().maxsize)

    def test_the_answer_is_the_same_one_the_uncached_body_gives(self):
        # The failure a pure-function cache can still have: a wrong key. Every shape the
        # body branches on, and the answers are the ones asserted elsewhere in the suite.
        self.assertEqual(split_key("mod:ingot:3"), ("mod:ingot", 3))
        self.assertEqual(split_key("mod:ingot:*"), ("mod:ingot", "*"))
        self.assertEqual(split_key("mod:ingot"), ("mod:ingot", 0))
        self.assertEqual(split_key("fluid:water"), ("fluid:water", None))
        self.assertEqual(split_key("ore:ingotIron"), ("ore:ingotIron", None))


class RoutableIsMemoisedPerSolverTest(unittest.TestCase):
    """`Solver._routable`, 5,287,585 calls over 536 distinct keys in one real plan."""

    def test_a_solver_holds_a_routable_cache(self):
        self.assertEqual(solve.Solver(_graph())._routable_cache, {})

    def test_a_repeated_question_is_answered_from_it(self):
        s = solve.Solver(_graph())
        self.assertTrue(s._routable("mod:ingot"))
        self.assertIn("mod:ingot", s._routable_cache)

    def test_a_false_answer_is_served_from_the_cache_and_not_recomputed(self):
        """THE BUG THIS EXISTS FOR, AND IT IS ONE CHARACTER WIDE.

        A cached `False` is indistinguishable from a miss under `if not got:`, so a
        falsy-guard implementation recomputes every negative answer forever. The negatives
        are the MAJORITY -- most keys are not routable -- so that version looks installed,
        stores the right value, and buys a fraction of what it claims.

        ASSERTING THE ENTRY EXISTS DOES NOT CATCH IT, and the first version of this test did
        exactly that and passed against the mutant. `if not got:` still writes the same
        `False` back on every call, so presence and value are identical either way. The only
        observable difference is whether the BODY runs again, so that is what this asserts:
        prime the cache, then make recomputation impossible, then ask again. A hit survives;
        a recompute raises.
        """
        s = solve.Solver(_graph())
        self.assertFalse(s._routable("mod:nothing_makes_this"))
        self.assertIs(s._routable_cache["mod:nothing_makes_this"], False)

        def refuse(_key):
            raise AssertionError("_routable recomputed a cached False instead of "
                                 "returning it; the guard is a falsy test, not `is None`")

        s.g.real_producers = refuse
        self.assertFalse(s._routable("mod:nothing_makes_this"))

    def test_a_true_answer_is_served_from_the_cache_too(self):
        # The positive half of the same claim, so the test above cannot pass merely because
        # nothing ever reaches `real_producers` for an unknown key.
        s = solve.Solver(_graph())
        self.assertTrue(s._routable("mod:ingot"))

        def refuse(_key):
            raise AssertionError("_routable recomputed a cached True")

        s.g.real_producers = refuse
        self.assertTrue(s._routable("mod:ingot"))

    def test_the_cache_does_not_outlive_the_solver_that_built_it(self):
        """THE HAZARD, AND THE REASON THIS CACHE IS NOT ON THE GRAPH.

        `_routable` reads `Recipe.not_production`, which `notproduction.mark` rewrites across
        the whole graph IN PLACE when a server reloads `data/tokens.json`. No recipe set
        changes, so `Graph._invalidate` never runs. A cache on the graph would go on serving
        the previous token map's verdict with nothing to notice -- which is precisely why
        `Graph.real_producers` declines to memoise and says so.

        A fresh `Solver` per plan is what makes that unreachable, so this asserts the
        property that keeps it unreachable rather than the implementation. It goes red the
        moment someone moves the dict onto `Graph`, which is the edit it is here to catch.
        """
        g = _graph()
        first = solve.Solver(g)
        self.assertTrue(first._routable("mod:ingot"))

        # The re-marking a token reload performs, in miniature: the recipe set is untouched
        # and every route to the ingot stops counting as production.
        for recipe in g.recipes:
            if any(key == "mod:ingot" for key, _qty in recipe.outputs):
                recipe.not_production = "loot"

        self.assertTrue(first._routable("mod:ingot"),
                        "a solve already in flight keeps the answer it started with")
        self.assertFalse(solve.Solver(g)._routable("mod:ingot"),
                         "a NEW solver must see the new marking; if this passes a stale "
                         "True, the cache has been promoted to the Graph and #308's "
                         "docstring is now wrong as well as this test")


if __name__ == "__main__":
    unittest.main()
