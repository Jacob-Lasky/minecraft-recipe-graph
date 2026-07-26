"""Search ranking, the typeahead payload, and the indexes that make it fast."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import explore  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402


def chem_graph():
    """An item and a fluid that share a display name, as NuclearCraft really does."""
    g = Graph()
    g.names = {
        "nuclearcraft:fluid_boric_acid": "Boric Acid",   # the placed block, no recipes
        "chickens:liquid_egg": "Water Egg",
        "mod:widget": "Widget",
    }
    g.add(Recipe("acid", "t", [("fluid:boric_acid", 2000)],
                 [Ingredient(["fluid:diborane"], 1000, "fluid")], category="reactor"))
    g.add(Recipe("egg", "t", [("chickens:liquid_egg", 1)],
                 [Ingredient(["mod:widget"], 1)], category="minecraft.crafting"))
    g.add(Recipe("use", "t", [("mod:widget", 1)],
                 [Ingredient(["fluid:water"], 1000, "fluid")], category="minecraft.crafting"))
    return g


class RankingTest(unittest.TestCase):
    def test_a_fluid_the_recipes_reference_is_findable(self):
        # items.csv covers items only, so searching "boric" used to find the placed block
        # (0 recipes) and never the fluid the chemistry chain actually needs.
        keys = explore.rank_keys(chem_graph(), "boric")
        self.assertIn("fluid:boric_acid", keys)

    def test_an_exact_fluid_name_outranks_items_merely_containing_the_word(self):
        # `fluid:water` was indexed as "[fluid] water", so "water" matched only as a
        # substring and "Water Egg" came first.
        keys = explore.rank_keys(chem_graph(), "water")
        self.assertEqual(keys[0], "fluid:water")

    def test_matching_on_the_registry_id_still_works(self):
        keys = explore.rank_keys(chem_graph(), "boric_acid")
        self.assertIn("fluid:boric_acid", keys)

    def test_an_empty_query_matches_nothing(self):
        self.assertEqual(explore.rank_keys(chem_graph(), "   "), [])

    def test_limit_is_respected(self):
        self.assertEqual(len(explore.rank_keys(chem_graph(), "a", limit=1)), 1)


class SuggestTest(unittest.TestCase):
    def test_rows_carry_what_the_typeahead_shows(self):
        rows = explore.suggest(chem_graph(), "boric")
        row = next(r for r in rows if r["key"] == "fluid:boric_acid")
        self.assertEqual(row["kind"], "fluid")
        self.assertEqual(row["label"], "Boric Acid")
        self.assertEqual(row["makes"], 1)
        self.assertEqual(row["stock"], 0)

    def test_stock_comes_from_the_have_set(self):
        rows = explore.suggest(chem_graph(), "water egg",
                               have={"chickens:liquid_egg": 12})
        self.assertEqual(rows[0]["stock"], 12)

    def test_makes_excludes_container_transfers(self):
        # A transfer is not a producer of a fluid, so "1 recipe" must not appear next to a
        # fluid whose only route is emptying a can.
        g = Graph()
        g.names = {"mod:can": "Can"}
        transfer = Recipe("empty", "t", [("fluid:stuff", 1000)],
                          [Ingredient(["mod:can"], 1)], category="squeezer")
        transfer.transfer = True
        g.add(transfer)
        rows = explore.suggest(g, "stuff")
        self.assertEqual(rows[0]["makes"], 0)

    def test_suggest_and_search_agree_on_order(self):
        g = chem_graph()
        self.assertEqual([r["key"] for r in explore.suggest(g, "boric")],
                         [r["key"] for r in explore.search(g, "boric")])


class IndexTest(unittest.TestCase):
    def test_ore_membership_is_indexed_not_rescanned(self):
        g = Graph()
        g.ore_members = {"ingotIron": ["minecraft:iron_ingot"], "dustRedstone": ["x:y"]}
        self.assertEqual(list(g.ores_of("minecraft:iron_ingot")), ["ingotIron"])
        self.assertEqual(list(g.ores_of("nothing:here")), [])

    def test_consumers_reach_a_recipe_through_an_oredict_slot(self):
        g = Graph()
        g.ore_members = {"ingotIron": ["minecraft:iron_ingot"]}
        g.add(Recipe("r", "t", [("mod:out", 1)],
                     [Ingredient(["ore:ingotIron"], 4)], category="c"))
        self.assertEqual(len(g.consumers("minecraft:iron_ingot")), 1)

    def test_adding_a_recipe_invalidates_the_indexes(self):
        g = Graph()
        g.add(Recipe("a", "t", [("mod:x", 1)],
                     [Ingredient(["fluid:water"], 1)], category="c"))
        self.assertIn("fluid:water", g.labels)
        g.add(Recipe("b", "t", [("mod:y", 1)],
                     [Ingredient(["fluid:lava"], 1)], category="c"))
        self.assertIn("fluid:lava", g.labels)


if __name__ == "__main__":
    unittest.main()
