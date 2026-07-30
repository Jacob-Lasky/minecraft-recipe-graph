"""Coverage-gap analysis: blind spots must outrank merely-noisy categories."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import gaps, nbt_digest  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402

SUMMARY = {
    "recipes": 42000,
    "skipped": 51,
    "categories": {
        "nuclearcraft.chemical_reactor": {"dumped": 120, "threw": 0, "empty": 0,
                                          "mod": "NuclearCraft"},
        "modularmachinery.blueprint": {"dumped": 0, "threw": 12, "empty": 3,
                                       "mod": "Modular Machinery"},
        "thaumcraft.infusion": {"dumped": 40, "threw": 30, "empty": 0, "mod": "Thaumcraft"},
        "minecraft.crafting": {"dumped": 39000, "threw": 6, "empty": 0, "mod": "Minecraft"},
    },
}

SKIPS = [
    {"cat": "modularmachinery.blueprint", "i": -1, "reason": "getRecipeWrappers failed",
     "err": "java.lang.NullPointerException"},
    {"cat": "thaumcraft.infusion", "i": 7, "reason": "threw",
     "wrapper": "thaumcraft.jei.InfusionRecipeWrapper",
     "err": "java.lang.NullPointerException"},
    {"cat": "minecraft.crafting", "i": 9, "reason": "no outputs",
     "wrapper": "mezz.jei.plugins.vanilla.crafting.ShapedWrapper"},
]


class GapsTest(unittest.TestCase):
    def setUp(self):
        self.a = gaps.analyse(SUMMARY, SKIPS)

    def test_zero_dumped_category_is_a_blind_spot(self):
        blind = [row[0] for row in self.a["blind_categories"]]
        self.assertEqual(blind, ["modularmachinery.blueprint"])

    def test_fully_covered_category_is_not_reported(self):
        listed = ([r[0] for r in self.a["blind_categories"]]
                  + [r[0] for r in self.a["partial_categories"]])
        self.assertNotIn("nuclearcraft.chemical_reactor", listed,
                         "a category that lost nothing should not appear as a gap")

    def test_partials_ranked_by_share_lost_not_raw_count(self):
        # Thaumcraft lost 30 of 70; vanilla lost 6 of 39,006. Ranking by raw count
        # would bury the one that actually matters.
        order = [r[0] for r in self.a["partial_categories"]]
        self.assertLess(order.index("thaumcraft.infusion"),
                        order.index("minecraft.crafting"))

    def test_reasons_are_tallied(self):
        self.assertEqual(dict(self.a["reasons"]),
                         {"getRecipeWrappers failed": 1, "threw": 1, "no outputs": 1})

    def test_exceptions_are_grouped(self):
        self.assertEqual(dict(self.a["errors"])["java.lang.NullPointerException"], 2)

    def test_report_renders_without_error(self):
        text = gaps.report(self.a)
        self.assertIn("TOTAL BLIND SPOTS", text)
        self.assertIn("modularmachinery.blueprint", text)

    def test_empty_input_is_handled(self):
        a = gaps.analyse({}, [])
        self.assertEqual(a["blind_categories"], [])
        self.assertIn("none", gaps.report(a))


def stocked_graph():
    g = Graph()
    g.names = {"mod:widget": "Widget", "forestry:bee_drone_ge#69d9078abf2f": "Forest Drone"}
    g.add(Recipe("r", "t", [("mod:out", 1)],
                 [Ingredient(["forestry:bee_drone_ge#69d9078abf2f"], 1)], category="c"))
    return g


class StockCoverageTest(unittest.TestCase):
    """Stock the graph cannot see. The failure this reports is a silence, so it has to be
    counted rather than noticed: see #21."""

    def cover(self, items, reader=gaps.DIGEST_READER):
        return gaps.stock_coverage(stocked_graph(), items, reader=reader)

    def test_a_key_the_graph_knows_is_matched(self):
        cov = self.cover({"mod:widget": 5, "forestry:bee_drone_ge#69d9078abf2f": 8629})
        self.assertEqual(cov["matched"], 2)
        self.assertEqual(cov["unmatched"], [])
        self.assertEqual(gaps.stock_report(cov),
                         "every stock key matches a key in the graph")

    def test_each_cause_is_counted_apart_because_each_has_its_own_fix(self):
        cov = self.cover({
            "mod:thing (+nbt)": 5,                       # the mod cannot digest it
            "thaumadditions:vis_pod#perditio": 42447,    # a have file older than #21
            "forestry:bee_drone_ge#ffffffffffff": 3,     # digested, but not in this dump
            "mod:removed": 1,                            # the pack dropped the item
        })
        self.assertEqual(cov["matched"], 0)
        self.assertEqual(cov["causes"], {
            gaps.CAUSE_OPAQUE: (1, 5),
            gaps.CAUSE_STALE: (1, 42447),
            gaps.CAUSE_UNKNOWN_VARIANT: (1, 3),
            gaps.CAUSE_UNKNOWN: (1, 1),
        })

    def test_an_unstamped_file_blames_the_scan_not_the_dump(self):
        # Reader 1 wrote ` (+nbt)` for every NBT-bearing stack, so on a file with no
        # stamp the marker means "rescan", not "the mod cannot digest this". Getting
        # this backwards sends someone hunting a schema bug that does not exist.
        items = {"mod:thing (+nbt)": 5}
        self.assertEqual(self.cover(items, reader=1)["causes"],
                         {gaps.CAUSE_STALE: (1, 5)})
        self.assertEqual(self.cover(items)["causes"], {gaps.CAUSE_OPAQUE: (1, 5)})

    def test_an_unstamped_file_is_the_default_assumption(self):
        # Only the pre-digest reader wrote files without a stamp, so a missing stamp is
        # evidence, not an unknown.
        cov = gaps.stock_coverage(stocked_graph(), {"mod:thing (+nbt)": 5})
        self.assertEqual(cov["causes"], {gaps.CAUSE_STALE: (1, 5)})

    def test_the_worst_offenders_are_listed_by_stock_not_by_name(self):
        cov = self.cover({"a:one": 1, "b:two": 500, "c:three": 20})
        self.assertEqual([k for k, _n in cov["unmatched"]], ["b:two", "c:three", "a:one"])

    def test_the_report_leads_with_the_number_that_matters(self):
        text = gaps.stock_report(self.cover({"mod:widget": 1, "mod:gone": 1_473_740}))
        self.assertIn("1 of 2 stock keys match nothing", text)
        self.assertIn("1,473,740", text)
        self.assertIn(gaps.CAUSE_UNKNOWN, text)

    def test_no_stock_is_not_an_error(self):
        self.assertEqual(gaps.stock_report(self.cover({})), "no stock to reconcile")


class StaleDigestGraphTest(unittest.TestCase):
    """A graph built before the digest format changed cannot match a current scan.

    Schema 4 changed how `n` is computed (#80, #63) and `have` recomputes it from the world
    save. Against a schema-3 graph that disagreement is total: every discriminated key misses.
    Without this the reconcile blames `digest not in this dump` -- which is true of each key
    and useless as a diagnosis, because the answer is not "that variant is gone", it is "this
    graph predates the format".
    """

    @staticmethod
    def _graph(schema):
        g = stocked_graph()
        g.dump_schema = schema
        return g

    def _cover(self, schema, items=None):
        return gaps.stock_coverage(self._graph(schema),
                                   items if items is not None else {"mod:gone": 3},
                                   reader=gaps.DIGEST_READER)

    def test_a_graph_older_than_the_digest_format_is_flagged(self):
        self.assertTrue(self._cover(3)["stale_digest_graph"])

    def test_a_current_graph_is_not_flagged(self):
        self.assertFalse(self._cover(nbt_digest.DIGEST_FORMAT_SCHEMA)["stale_digest_graph"])

    def test_a_newer_graph_is_not_flagged_either(self):
        # A reader older than the graph is a different problem, and `dump_meta.describe`
        # already says so. Claiming the digest moved would be a guess about a future schema.
        self.assertFalse(
            self._cover(nbt_digest.DIGEST_FORMAT_SCHEMA + 1)["stale_digest_graph"])

    def test_a_graph_with_no_recorded_schema_is_not_flagged(self):
        # 0 means the field predates the graph, not that the graph is old. Every graph.json
        # written before #38 reads as 0, and warning on all of them would be noise.
        self.assertFalse(self._cover(0)["stale_digest_graph"])

    def test_the_warning_leads_the_report(self):
        text = gaps.stock_report(self._cover(3))
        self.assertTrue(text.startswith(gaps.STALE_DIGEST_WARNING),
                        "the warning has to come before the numbers it invalidates")
        self.assertIn("1 of 1 stock keys match nothing", text)

    def test_a_clean_match_on_a_stale_graph_still_warns(self):
        """The case a `return` in the wrong order would swallow.

        If nothing in the stock is discriminated, every key matches and the report would say
        so cheerfully -- while the graph is still one no current scan can match for the keys
        that DO carry a digest. The clean result is not evidence the graph is fine.
        """
        text = gaps.stock_report(self._cover(3, {"mod:widget": 1}))
        self.assertIn("every stock key matches", text)
        self.assertIn(gaps.STALE_DIGEST_WARNING, text)

    def test_the_warning_names_the_schema_and_the_way_out(self):
        self.assertIn(str(nbt_digest.DIGEST_FORMAT_SCHEMA), gaps.STALE_DIGEST_WARNING)
        self.assertIn("/recipedump", gaps.STALE_DIGEST_WARNING)


if __name__ == "__main__":
    unittest.main()
