"""The ORDER of a solver result, pinned, because #19 froze it into a cross-language contract.

WHY THIS EXISTS. `Solver.solve` returns five lists built by `Counter.most_common()`, which
sorts by count descending and breaks ties by INSERTION order. Nothing tested that. It was
load-bearing anyway, and about to become much more so:

  - `tests/fixtures/plan/*.json` freeze whole solver results so the Java port of the planner
    can be asserted against them byte for byte. A list that comes back with the same members
    in a different order is a failing fixture with no behavioural change to point at, and the
    obvious reading of that failure ("the fixture is stale, regenerate it") is the wrong one.
  - Reproducing the order in Java needs a STABLE sort by count descending over an
    insertion-ordered map. `HashMap` plus `sort` gives the right multiset and the wrong order.
    `TreeMap` gives alphabetical, which is wrong differently. Neither fails loudly.

So these tests exist to make an implicit property explicit, and to fail on the DAY someone
swaps a Counter for a dict-plus-`sorted` rather than months later inside a port.

The tie-break cases below deliberately choose keys whose insertion order and alphabetical
order DISAGREE. A fixture where they happen to agree passes under either implementation and
tests nothing, which is the trap this file is written to avoid.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402


def _tie_graph():
    """One recipe needing equal amounts of two leaves, reached zebra-first.

    Alphabetically `mod:apple` sorts first; by discovery `mod:zebra` does, because it is the
    earlier slot. Every assertion about tie-breaking below turns on that disagreement.
    """
    g = Graph()
    g.names = {
        "mod:widget": "Widget",
        "mod:zebra": "Zebra Part",
        "mod:apple": "Apple Part",
    }
    g.add(Recipe("make-widget", "t", [("mod:widget", 1)],
                 [Ingredient(["mod:zebra"], 3), Ingredient(["mod:apple"], 3)]))
    return g


class TheShoppingListKeepsDiscoveryOrderNotAlphabeticalOrder(unittest.TestCase):

    def test_equal_quantities_break_the_tie_by_when_the_solver_reached_them(self):
        res = Solver(_tie_graph()).solve("mod:widget", 1)
        self.assertEqual([row["key"] for row in res["shopping_list"]],
                         ["mod:zebra", "mod:apple"],
                         "ties must follow slot order, not sorted() order")

    def test_the_two_orders_genuinely_disagree_so_the_test_above_can_fail(self):
        # Guards the guard. If a later edit renames these keys such that discovery order and
        # alphabetical order coincide, the test above silently stops discriminating between a
        # correct implementation and a `sorted()` one, and would keep passing forever.
        res = Solver(_tie_graph()).solve("mod:widget", 1)
        keys = [row["key"] for row in res["shopping_list"]]
        self.assertNotEqual(keys, sorted(keys),
                            "the fixture must not be alphabetical by accident")

    def test_a_larger_quantity_still_outranks_an_earlier_one(self):
        # Count dominates; insertion order only decides ties. Without this, an implementation
        # that ignored counts entirely and returned pure insertion order would pass the tests
        # above.
        g = _tie_graph()
        g.add(Recipe("make-widget-2", "t", [("mod:widget2", 1)],
                     [Ingredient(["mod:zebra"], 1), Ingredient(["mod:apple"], 9)]))
        g.names["mod:widget2"] = "Widget Two"
        res = Solver(g).solve("mod:widget2", 1)
        self.assertEqual([row["key"] for row in res["shopping_list"]],
                         ["mod:apple", "mod:zebra"])


class UsedFromStockKeepsTheSameOrderingRule(unittest.TestCase):

    def test_equal_stock_draws_break_the_tie_by_discovery_order(self):
        res = Solver(_tie_graph(),
                     have={"mod:zebra": 99, "mod:apple": 99}).solve("mod:widget", 1)
        self.assertEqual([row["key"] for row in res["used_from_stock"]],
                         ["mod:zebra", "mod:apple"])


class MachinesToBuildIsTheDeliberateExceptionAndIsAlphabetical(unittest.TestCase):
    """`machines_to_build` is `sorted()` on purpose, and that difference is intentional.

    It is a checklist rather than a worklist: a stable alphabetical order is easier to scan
    and does not reshuffle as the plan changes. Pinned here so a future tidy-up that makes
    "all the lists consistent" has to argue with a test rather than with a comment.
    """

    def test_it_is_sorted_by_category_rather_than_by_discovery(self):
        g = Graph()
        g.names = {"mod:out": "Out", "mod:za": "Za", "mod:ab": "Ab"}
        g.add(Recipe("r1", "t", [("mod:out", 1)], [Ingredient(["mod:mid"], 1)],
                     category="zulu.press", machine="Zulu Press"))
        g.add(Recipe("r2", "t", [("mod:mid", 1)], [Ingredient(["mod:za"], 1)],
                     category="alpha.mill", machine="Alpha Mill"))
        states = {"zulu.press": ("buildable", "no machine"),
                  "alpha.mill": ("buildable", "no machine")}
        res = Solver(g, machine_states=states).solve("mod:out", 1)
        cats = [row["category"] for row in res["machines_to_build"]]
        self.assertEqual(cats, sorted(cats))


class ProducerOrderIsStableBecauseTieBreakingRidesOnIt(unittest.TestCase):
    """`real_producers` returning an ORDERED list is what makes plans reproducible.

    `Solver.pick_recipe` uses `max(candidates, key=score_recipe)`, and Python's `max` returns
    the FIRST maximal element. So when two recipes score identically -- which is common, since
    the pack has 437 byte-identical bee mutations among others -- the winner is decided purely
    by the order this list comes back in. Measured across three `PYTHONHASHSEED` values on the
    real pack graph, a 90 node budget-exhausted plan is byte-identical, and that result rests
    on this property alone.

    Turn either `producers` or `real_producers` into a set and plans start varying between
    processes, with nothing in the output pointing at the cause.
    """

    def test_producers_come_back_in_the_order_they_were_added(self):
        g = Graph()
        g.names = {"mod:target": "Target"}
        for rid in ("third", "first", "second"):
            g.add(Recipe(rid, "t", [("mod:target", 1)], [Ingredient(["mod:in-" + rid], 1)]))
        self.assertEqual([r.rid for r in g.producers("mod:target")],
                         ["third", "first", "second"])

    def test_real_producers_preserves_that_order_while_filtering(self):
        g = Graph()
        g.names = {"fluid:stuff": "Stuff"}
        for rid, transfer in (("keep-a", False), ("drop", True), ("keep-b", False)):
            g.add(Recipe(rid, "t", [("fluid:stuff", 1000)], [Ingredient(["mod:x"], 1)],
                         transfer=transfer))
        self.assertEqual([r.rid for r in g.real_producers("fluid:stuff")],
                         ["keep-a", "keep-b"],
                         "filtering a container transfer must not reorder the survivors")


if __name__ == "__main__":
    unittest.main()
