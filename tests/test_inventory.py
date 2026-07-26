"""AE2 cell parsing: amount fields, essentia, and NBT-discriminated stacks.

Every case here is taken from real cell NBT on the reference network, because each one
was a bug found by reading the save rather than by reasoning about it.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph.ae2_inventory import classify  # noqa: E402
from recipegraph.model import Graph, essentia_key  # noqa: E402


class ClassifyTest(unittest.TestCase):
    def test_item_cell_uses_cnt_not_count(self):
        # Count is the ItemStack byte and reads 0; Cnt carries the real total.
        got = classify({"id": "minecraft:redstone", "Count": 0, "Cnt": 27605921})
        self.assertEqual(got, ("item", "minecraft:redstone", 27605921))

    def test_fluid_cell(self):
        got = classify({"FluidName": "sludge", "Count": 0, "Cnt": 143560})
        self.assertEqual(got, ("fluid", "sludge", 143560))

    def test_essentia_cell_uses_amount(self):
        # ThaumicEnergistics writes Amount, not Cnt. Preferring Count dropped these
        # entirely and made every essentia cell aggregate to zero.
        got = classify({"Aspect": "terra", "Count": 0, "Amount": 5245})
        self.assertEqual(got, ("essentia", "terra", 5245))

    def test_zero_amount_entry_is_skipped(self):
        self.assertIsNone(classify({"Aspect": "aer", "Count": 0, "Amount": 0}))
        self.assertIsNone(classify({"id": "minecraft:stone", "Cnt": 0, "Count": 0}))

    def test_aspect_nbt_is_decoded_into_the_key(self):
        got = classify({"id": "thaumadditions:vis_pod", "Cnt": 42447,
                        "tag": {"Aspect": "perditio"}})
        self.assertEqual(got, ("item", "thaumadditions:vis_pod#perditio", 42447))

    def test_differing_aspects_are_different_keys(self):
        a = classify({"id": "thaumadditions:vis_pod", "Cnt": 1, "tag": {"Aspect": "lux"}})
        b = classify({"id": "thaumadditions:vis_pod", "Cnt": 1, "tag": {"Aspect": "sol"}})
        self.assertNotEqual(a[1], b[1], "aspects must not unify into one ingredient")

    def test_undecodable_nbt_still_kept_distinct_from_base(self):
        got = classify({"id": "mod:thing", "Cnt": 5, "tag": {"Energy": 100}})
        self.assertEqual(got[1], "mod:thing (+nbt)")

    def test_meta_is_preserved(self):
        got = classify({"id": "thermalfoundation:ore", "Damage": 1, "Cnt": 87890})
        self.assertEqual(got[1], "thermalfoundation:ore:1")


class DisplayTest(unittest.TestCase):
    def test_aspect_key_fills_the_format_placeholder(self):
        g = Graph()
        g.names = {"thaumadditions:vis_pod": "%s Vis Pod"}
        self.assertEqual(g.display("thaumadditions:vis_pod#perditio"), "Perditio Vis Pod")

    def test_bare_key_drops_the_placeholder(self):
        g = Graph()
        g.names = {"thaumadditions:vis_pod": "%s Vis Pod"}
        self.assertEqual(g.display("thaumadditions:vis_pod"), "Vis Pod")

    def test_essentia_and_fluid_keys_are_labelled(self):
        g = Graph()
        self.assertEqual(g.display(essentia_key("Terra")), "[essentia] Terra")
        # A fluid has no items.csv entry, so the registry name is prettified rather than
        # shown raw next to properly-cased item names.
        self.assertEqual(g.display("fluid:borax_solution"), "[fluid] Borax Solution")
        self.assertEqual(g.bare_name("fluid:borax_solution"), "Borax Solution")

    def test_prettify_leaves_existing_capitals_alone(self):
        # `.title()` would turn TBU into Tbu and NaOH into Naoh.
        g = Graph()
        self.assertEqual(g.bare_name("fluid:fuel_TBU"), "Fuel TBU")
        self.assertEqual(g.bare_name("fluid:NaOH_solution"), "NaOH Solution")

    def test_searchable_labels_cover_fluids_the_recipes_reference(self):
        # items.csv covers items only. Without this, "Boric Acid" found the placed block
        # (no recipes) and never the fluid the chemistry chains actually need.
        from recipegraph.model import Ingredient, Recipe

        g = Graph()
        g.names = {"mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("fluid:boric_acid", 1000)],
                     [Ingredient(["mod:widget"], 1)], category="c"))
        self.assertEqual(g.labels.get("fluid:boric_acid"), "Boric Acid")

    def test_labels_hold_the_bare_name_not_the_bracketed_form(self):
        # Indexing "[fluid] water" made the query "water" a mere substring, so every item
        # containing the word outranked the fluid itself.
        from recipegraph.model import Ingredient, Recipe

        g = Graph()
        g.add(Recipe("r", "t", [("mod:x", 1)],
                     [Ingredient(["fluid:water"], 1000, "fluid")], category="c"))
        self.assertEqual(g.labels["fluid:water"], "Water")

    def test_essentia_key_is_case_normalised(self):
        self.assertEqual(essentia_key("Terra"), essentia_key("terra"))


if __name__ == "__main__":
    unittest.main()
