"""AE2 cell parsing: amount fields, essentia, and NBT-discriminated stacks.

Every case here is taken from real cell NBT on the reference network, because each one
was a bug found by reading the save rather than by reasoning about it.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import ae2_inventory  # noqa: E402
from recipegraph.ae2_inventory import classify  # noqa: E402
from recipegraph.anvil_nbt import Int, LongArray  # noqa: E402
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

    def test_nbt_becomes_the_digest_the_dump_wrote(self):
        # GROUND TRUTH: `thaumadditions:vis_pod#531b71e0ba2d` is a real key in the
        # schema-3 dump and names "Perditio Vis Pod". The reader used to emit
        # `#perditio`, which is more readable and matched nothing at all -- all 52
        # vis-pod keys on the reference network were dead. See #21.
        got = classify({"id": "thaumadditions:vis_pod", "Cnt": 42447,
                        "tag": {"Aspect": "perditio"}})
        self.assertEqual(got, ("item", "thaumadditions:vis_pod#531b71e0ba2d", 42447))

    def test_differing_aspects_are_different_keys(self):
        a = classify({"id": "thaumadditions:vis_pod", "Cnt": 1, "tag": {"Aspect": "lux"}})
        b = classify({"id": "thaumadditions:vis_pod", "Cnt": 1, "tag": {"Aspect": "sol"}})
        self.assertNotEqual(a[1], b[1], "aspects must not unify into one ingredient")

    def test_a_species_stack_lands_on_the_key_the_recipes_use(self):
        # The headline of #21: 8,629 drones read as zero against every bee recipe.
        got = classify({"id": "forestry:bee_drone_ge", "Cnt": 8629,
                        "tag": {"Genome": {"Chromosomes": [
                            {"UID0": "forestry.speciesForest",
                             "UID1": "forestry.speciesForest"}]}}})
        self.assertEqual(got[1], "forestry:bee_drone_ge#69d9078abf2f")

    def test_a_stack_the_mod_cannot_digest_keeps_the_opaque_marker(self):
        # TAG_Long_Array is serialised by Java's toString() and cannot be reproduced
        # here, so the reader admits it rather than inventing a digest. The marker
        # matches no recipe, which over-reports what you need rather than claiming you
        # own something you do not.
        got = classify({"id": "mod:thing", "Cnt": 5,
                        "tag": {"Ids": LongArray([1, 2])}})
        self.assertEqual(got[1], "mod:thing (+nbt)")

    def test_cosmetic_only_nbt_does_not_split_an_item(self):
        # A renamed stack is the same ingredient, and the dump strips the same tags.
        plain = classify({"id": "mod:thing", "Cnt": 1})
        renamed = classify({"id": "mod:thing", "Cnt": 1,
                            "tag": {"display": {"Name": "Steve's Thing"},
                                    "RepairCost": Int(7)}})
        self.assertEqual(plain[1], renamed[1])

    def test_meta_is_preserved(self):
        got = classify({"id": "thermalfoundation:ore", "Damage": 1, "Cnt": 87890})
        self.assertEqual(got[1], "thermalfoundation:ore:1")


class OpaqueMarkerTest(unittest.TestCase):
    """The one string two languages have to agree on.

    `tools/ae2_dump.lua` runs on an OpenComputers computer with no python and no NBT: all
    it can see is a `hasTag` boolean, so it writes the marker rather than a digest. If the
    two spellings drift, an OC-sourced stock file silently stops being recognisable as
    undigested NBT and `gaps` misreports why a plan ignored it.
    """

    LUA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "tools", "ae2_dump.lua")

    def test_the_lua_dumper_writes_the_same_marker(self):
        with open(self.LUA) as fh:
            src = fh.read()
        self.assertIn('k .. "%s"' % ae2_inventory.OPAQUE_MARKER, src)

    def test_the_lua_dumper_never_hands_a_tagged_stack_the_bare_key(self):
        # The dangerous direction: bare `forestry:bee_drone_ge` is a live ingredient of
        # real recipes, so unifying a species onto it drops a requirement off the
        # shopping list rather than adding a spurious one. See #21.
        with open(self.LUA) as fh:
            src = fh.read()
        self.assertIn("local function key(name, damage, hasTag)", src)
        self.assertNotIn("key(it.name, it.damage)", src)
        self.assertNotIn("key(st.name, st.damage)", src)

    def test_gaps_recognises_the_marker_it_is_handed(self):
        self.assertTrue(
            classify({"id": "mod:thing", "Cnt": 1,
                      "tag": {"Ids": LongArray([1])}})[1].endswith(
                          ae2_inventory.OPAQUE_MARKER))


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
