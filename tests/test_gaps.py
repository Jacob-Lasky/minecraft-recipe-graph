"""Coverage-gap analysis: blind spots must outrank merely-noisy categories."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import gaps  # noqa: E402

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


if __name__ == "__main__":
    unittest.main()
