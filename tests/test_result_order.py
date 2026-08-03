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

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import index  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402
from recipegraph.sources import dump_meta, oredict  # noqa: E402


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


class OredictMemberOrderIsStableForTheSameReason(unittest.TestCase):
    """`ore_members` values decide oredict ties, and the same first-wins rule applies.

    THE SIBLING OF THE CLASS ABOVE, for the other list a tie falls through to.
    `Solver.resolve_ore` picks with `max(members, key=...)` and `cost.input_cost` scans them
    keeping the first strictly-cheaper one, so with nothing in stock -- which is when the
    interesting plans are computed -- two equally priced members are separated by nothing but
    the order `oredict.from_json` built the list in. That is a list comprehension over the
    dump's own JSON, so it is insertion-ordered and stable across processes.

    Untested until `tests/fixtures/plan/*.json` started freezing whole solver results (#19):
    `plan-same-name` and `plan-fluid-chain` between them hold 27 resolved oredict slots, so
    a set here would reshuffle real fixtures. Same failure shape as the producer list, same
    invisibility -- the plan stays plausible and simply names a different member.
    """

    def _graph(self):
        g = Graph()
        # Members whose insertion order and alphabetical order DISAGREE, per this file's
        # header: with equal cost, a sorted implementation picks `mod:a` and the correct one
        # picks `mod:z`, so the assertion can actually fail.
        g.names = {"mod:z": "Zed Plate", "mod:a": "Aye Plate", "mod:target": "Target"}
        g.ore_members = {"plateStuff": ["mod:z", "mod:a"]}
        return g

    def test_members_keep_the_order_the_dump_listed_them_in(self):
        # THROUGH `oredict.from_json`, which is the function this class's docstring names as
        # the thing that builds the list. It used to set `g.ore_members` by hand and read the
        # literal straight back, so it asserted that a Python list preserves its own order
        # and nothing about the reader -- `from_json` could have been `sorted(...)` or a
        # `set` and this stayed green.
        #
        # `from_json` WAS being executed and its ORDER was asserted by nothing, in either
        # language. Three tests reach it through `index.build`, and not one can see the
        # order: `test_schema_five.py:611` passes `{}`, `test_sources.py:402` passes one
        # member per group, and `test_dimensions.py:464` passes two whose only assertion
        # sorts both sides -- and whose two keys are in alphabetical order anyway, so it
        # would not discriminate even unsorted. Note which of the module's two functions was
        # covered: `guess_from_names`, the best-effort FALLBACK, is tested directly at
        # `test_core.py:488`. The one carrying the contract was not.
        #
        # WHY THIS IS NOT MERELY A MISSING UNIT TEST. The Java port never calls `from_json`.
        # `DumpCommand.writeOreDict` emits oredict.json in game, Python's `from_json` turns
        # it into ordered lists, and `GraphJsonReader` reads the ALREADY-BUILT graph. So
        # member order is decided once, upstream of both implementations. If `from_json`
        # built its lists wrong, both languages would inherit the identical wrong order,
        # every golden fixture would be regenerated against it, and the cross-language gate
        # would agree perfectly while both sides were wrong -- across the 27 resolved oredict
        # slots `plan-same-name` and `plan-fluid-chain` carry between them. A golden-fixture
        # gate proves the two implementations AGREE; it can prove neither of them right about
        # anything they share an upstream for. This is the only test on that link.
        #
        # The spellings are the dump's real ones, so `_norm_entry` is exercised too -- it is
        # reachable only from here and from `from_crafttweaker_log`, which has no test caller
        # at all, so its bracket-stripping and `* 4` stack-size handling were uncovered.
        expected = ["mod:z", "mod:a"]
        self.assertNotEqual(expected, sorted(expected),
                            "the fixture must not be in alphabetical order, or a sorting "
                            "reader passes this test")
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "oredict.json")
            with open(path, "w") as fh:
                json.dump({"plateStuff": ["<mod:z>", "mod:a * 4"]}, fh)
            self.assertEqual(oredict.from_json(path)["plateStuff"], expected)

    def test_the_graph_reads_the_dumps_oredict_in_that_order_too(self):
        # And the whole way up: `index.build` is what puts `from_json`'s answer on the graph,
        # so an order preserved by the reader and lost by the loader is the same wrong plan.
        # Written UNSORTED, for the reason the class docstring gives.
        with tempfile.TemporaryDirectory() as tmp:
            dump = os.path.join(tmp, dump_meta.DIR_NAME)
            os.makedirs(dump)
            with open(os.path.join(dump, "oredict.json"), "w") as fh:
                json.dump({"plateStuff": ["mod:z", "mod:a"]}, fh)
            with open(os.path.join(dump, "recipes.ndjson"), "w") as fh:
                fh.write(json.dumps({"cat": "minecraft.crafting",
                                     "in": [[{"i": "ore:plateStuff", "c": 1}]],
                                     "out": [{"i": "mod:target", "c": 1}]}) + "\n")
            g = index.build(tmp, quiet=True)
        self.assertEqual(g.ore_members["plateStuff"], ["mod:z", "mod:a"])

    def test_an_untied_oredict_slot_resolves_to_the_first_member(self):
        # No stock and no costs, so every member ties on availability and on price, and the
        # choice falls through to order alone -- which is exactly the state #61 showed the
        # interesting plans are computed in.
        g = self._graph()
        node = Solver(g).resolve_ore("ore:plateStuff", 1, frozenset(), 0)
        self.assertEqual(node["resolved_to"], "mod:z",
                         "an oredict tie must resolve by member order, not alphabetically")

    def test_a_stocked_member_still_wins_over_an_earlier_one(self):
        # Guards the guard: if order beat availability the test above would pass for the
        # wrong reason, and `resolve_ore`'s documented three-tier rule would be broken.
        g = self._graph()
        node = Solver(g, have={"mod:a": 64}).resolve_ore("ore:plateStuff", 1, frozenset(), 0)
        self.assertEqual(node["resolved_to"], "mod:a")


if __name__ == "__main__":
    unittest.main()
