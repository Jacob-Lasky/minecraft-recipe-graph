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
        keys = explore.rank_matches(chem_graph(), "boric").results
        self.assertIn("fluid:boric_acid", keys)

    def test_an_exact_fluid_name_outranks_items_merely_containing_the_word(self):
        # `fluid:water` was indexed as "[fluid] water", so "water" matched only as a
        # substring and "Water Egg" came first.
        keys = explore.rank_matches(chem_graph(), "water").results
        self.assertEqual(keys[0], "fluid:water")

    def test_matching_on_the_registry_id_still_works(self):
        keys = explore.rank_matches(chem_graph(), "boric_acid").results
        self.assertIn("fluid:boric_acid", keys)

    def test_an_empty_query_matches_nothing(self):
        self.assertEqual(explore.rank_matches(chem_graph(), "   "), ([], 0, 0))

    def test_limit_is_respected(self):
        self.assertEqual(len(explore.rank_matches(chem_graph(), "a", limit=1).results), 1)


class DeadKeyTest(unittest.TestCase):
    """A key nothing touches is not searchable, and the count says so. See #26.

    On the reference pack this is 174,705 of 342,070 labels. Six identical "Pluton Scythe"
    NBT variants and a "Pluton Banner", all with no recipe and no use, pushed
    Plutonium-238 through -242 off the first page of a search for `plut`.
    """

    def test_a_key_no_recipe_touches_is_not_offered(self):
        # `nuclearcraft:fluid_boric_acid` is the PLACED BLOCK. It shares its display name
        # with the fluid the chemistry chain needs and has no recipes at all, so it could
        # only ever push the real answer down.
        hits = explore.rank_matches(chem_graph(), "boric")
        self.assertEqual(hits.results, ["fluid:boric_acid"])
        self.assertEqual(hits.hidden, 1)

    def test_nothing_live_is_lost(self):
        g = chem_graph()
        for key in ("fluid:boric_acid", "chickens:liquid_egg", "mod:widget", "fluid:water"):
            self.assertIn(key, g.live_keys, key)

    def test_something_you_hold_is_never_hidden(self):
        # A stack in the AE2 system is a fact about the world; "no recipe touches it" is a
        # fact about the dump, and the dump does not overrule the world.
        hits = explore.rank_matches(chem_graph(), "boric",
                                    have={"nuclearcraft:fluid_boric_acid": 3})
        self.assertIn("nuclearcraft:fluid_boric_acid", hits.results)
        self.assertEqual(hits.hidden, 0)

    def test_a_catalyst_stays_searchable_with_no_edge_of_its_own(self):
        # 51 keys on the reference pack, `thermalexpansion:machine:1` among them, appear
        # ONLY as a JEI catalyst: their crafting recipes output a discriminated variant
        # instead (#28). Hiding those makes the Pulverizer unfindable.
        g = chem_graph()
        g.names["mod:press"] = "Hydraulic Press"
        g.catalysts = {"mod.press": ["mod:press"]}
        g._invalidate()
        self.assertEqual(explore.rank_matches(g, "press").results, ["mod:press"])

    def test_an_oredict_member_stays_searchable(self):
        # `consumers` reaches an item through any ore it belongs to, so a member of an ore
        # some recipe consumes is reachable even when nothing names the item directly.
        # Hiding it here would contradict the item page, which lists that recipe.
        g = Graph()
        g.names = {"mod:tin_ingot": "Tin Ingot"}
        g.ore_members = {"ingotTin": ["mod:tin_ingot"]}
        g.add(Recipe("r", "t", [("mod:wire", 1)],
                     [Ingredient(["ore:ingotTin"], 1)], category="c"))
        hits = explore.rank_matches(g, "tin ingot")
        # `ore:ingotTin` matches on its key too, and is a real searchable thing.
        self.assertEqual(hits.results[0], "mod:tin_ingot")
        self.assertEqual(hits.hidden, 0)
        self.assertTrue(g.consumers("mod:tin_ingot"))

    def test_a_wildcard_meta_keeps_its_siblings_searchable(self):
        # `producers`/`consumers` fall back to `base:*`, so every meta of a wildcarded base
        # is reachable and must stay findable.
        g = Graph()
        g.names = {"mod:dye:4": "Lapis Lazuli"}
        g.add(Recipe("r", "t", [("mod:out", 1)],
                     [Ingredient(["mod:dye:*"], 1)], category="c"))
        self.assertEqual(explore.rank_matches(g, "lapis").results, ["mod:dye:4"])
        self.assertTrue(g.consumers("mod:dye:4"))

    def test_did_you_mean_never_offers_a_dead_name_or_the_query_itself(self):
        # This line only prints when the search found nothing, so "no item name matched
        # 'boric acid' / did you mean: boric acid" reads as a malfunction. The name really
        # does exist; every key wearing it is dead, which the hidden count already said.
        g = Graph()
        g.names = {"mod:dead": "Boric Acid", "mod:live": "Boric Acid Cell"}
        g.add(Recipe("r", "t", [("mod:live", 1)], [Ingredient(["mod:x"], 1)], category="c"))
        # `build_reverse` keys on the lowercased label, which is what this returns.
        self.assertEqual(explore.name_hints(g, "boric acid"), ["boric acid cell"])

    def test_the_filter_never_hides_more_than_the_accessors_would(self):
        # The invariant that matters: anything `live_keys` drops must genuinely have no
        # recipes, or search and the item page disagree about whether an item exists.
        g = chem_graph()
        g.names["mod:dead"] = "Dead Thing"
        for key in g.labels:
            if key not in g.live_keys:
                self.assertFalse(g.producers(key), key)
                self.assertFalse(g.consumers(key), key)


class SuggestTest(unittest.TestCase):
    def test_rows_carry_what_the_typeahead_shows(self):
        rows = explore.suggest(chem_graph(), "boric").results
        row = next(r for r in rows if r["key"] == "fluid:boric_acid")
        self.assertEqual(row["kind"], "fluid")
        self.assertEqual(row["label"], "Boric Acid")
        self.assertEqual(row["makes"], 1)
        self.assertEqual(row["stock"], 0)

    def test_stock_comes_from_the_have_set(self):
        rows = explore.suggest(chem_graph(), "water egg",
                               have={"chickens:liquid_egg": 12}).results
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
        rows = explore.suggest(g, "stuff").results
        self.assertEqual(rows[0]["makes"], 0)

    def test_suggest_and_search_agree_on_order_and_on_what_was_hidden(self):
        g = chem_graph()
        sug, sea = explore.suggest(g, "boric"), explore.search(g, "boric")
        self.assertEqual([r["key"] for r in sug.results],
                         [r["key"] for r in sea.results])
        self.assertEqual(sug.hidden, sea.hidden)


class ItemPageSlotTest(unittest.TestCase):
    """The item page lists a recipe's ingredients, and had the same per-slot repeat (#24)."""

    def _brief(self, inputs):
        g = Graph()
        g.names = {"mod:clump": "Tiny Clump", "mod:ingot": "Ingot", "mod:rod": "Rod"}
        g.add(Recipe("r", "t", [("mod:ingot", 1)], inputs, category="c"))
        return explore.describe(g, "mod:ingot")["makes"][0]

    def test_nine_slots_of_one_ingredient_are_one_row_of_nine(self):
        rows = self._brief([Ingredient(["mod:clump"], 1) for _ in range(9)])["inputs"]
        self.assertEqual([(r["alts"][0]["key"], r["qty"]) for r in rows],
                         [("mod:clump", 9)])

    def test_distinct_ingredients_stay_distinct(self):
        rows = self._brief([Ingredient(["mod:clump"], 2),
                            Ingredient(["mod:rod"], 3)])["inputs"]
        self.assertEqual([(r["alts"][0]["key"], r["qty"]) for r in rows],
                         [("mod:clump", 2), ("mod:rod", 3)])

    def test_slots_offering_different_choices_are_not_merged_here(self):
        """Unlike the solver: with no inventory there is nothing to pick with, so the
        rows describe the recipe as authored."""
        rows = self._brief([Ingredient(["mod:clump"], 1),
                            Ingredient(["mod:clump", "mod:rod"], 1)])["inputs"]
        self.assertEqual(len(rows), 2)

    def test_the_alternative_count_survives_a_merge(self):
        rows = self._brief([Ingredient(["mod:clump", "mod:rod"], 1),
                            Ingredient(["mod:clump", "mod:rod"], 1)])["inputs"]
        self.assertEqual(rows[0]["qty"], 2)
        self.assertEqual(rows[0]["alt_total"], 2)


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


def plates():
    """Four items called "Iron Plate", as MeatballCraft really has six.

    `zz:plate` is the canonical one and sorts LAST alphabetically, which is the whole defect:
    on the reference pack `thermalfoundation:material:32` lost to `abyssalcraft:ironp` on the
    letter "t" against "a".
    """
    g = Graph()
    g.names = {"aa:plate": "Iron Plate", "mm:plate": "Iron Plate",
               "zz:plate": "Iron Plate", "qq:plate": "Iron Plate",
               "mod:ingot": "Iron Ingot"}
    # zz: eight consumers and a machine route. aa: one crafting recipe and one consumer.
    for i in range(8):
        g.add(Recipe("use%d" % i, "t", [("mod:thing%d" % i, 1)],
                     [Ingredient(["zz:plate"], 1)], category="c"))
    g.add(Recipe("press", "t", [("zz:plate", 1)],
                 [Ingredient(["mod:ingot"], 1)], category="ie.metalPress"))
    g.add(Recipe("craft", "t", [("aa:plate", 1)],
                 [Ingredient(["mod:ingot"], 1)], category="crafting_shaped"))
    g.add(Recipe("useaa", "t", [("mod:other", 1)],
                 [Ingredient(["aa:plate"], 1)], category="c"))
    # qq: exists, consumed by nothing, made by nothing but the graph knows the name.
    g.add(Recipe("useqq", "t", [("qq:plate", 1)],
                 [Ingredient(["mod:ingot"], 1)], category="crafting_shaped"))
    return g


class SameNamedItemsAreOrderedByEvidenceTest(unittest.TestCase):
    """Six items were called "Iron Plate" and the tie fell through to the registry id, so
    ordering among them was decided by its first letter. The one with 42 in stock, 152
    consumers and five machine routes came last, and the report was "the only way I can find
    to craft an iron plate is shaped crafting"."""

    def test_the_well_connected_item_wins_despite_sorting_last_alphabetically(self):
        keys = explore.rank_matches(plates(), "iron plate").results
        self.assertEqual(keys[0], "zz:plate")

    def test_a_stack_in_stock_outranks_being_well_connected(self):
        # 1,482 Sulfur is the pack telling you which one you use.
        keys = explore.rank_matches(plates(), "iron plate",
                                    have={"mm:plate": explore.STOCK_IS_DECISIVE}).results
        self.assertEqual(keys[0], "mm:plate")

    def test_holding_one_of_something_does_not_outrank_it(self):
        # ONE `railcraft:abyssal_stone` beating `aoa3:abyss_stone` and its 165 consumers is
        # the case the threshold exists to refuse.
        keys = explore.rank_matches(plates(), "iron plate", have={"mm:plate": 1}).results
        self.assertEqual(keys[0], "zz:plate")

    def test_an_item_nothing_consumes_sinks_below_one_with_recipes(self):
        keys = explore.rank_matches(plates(), "iron plate").results
        self.assertLess(keys.index("aa:plate"), keys.index("qq:plate"))

    def test_a_better_name_match_still_beats_a_better_connected_item(self):
        """The tie-break must not become a longer sort key.

        Applied to the whole list it would let connectivity outrank name relevance, which is
        what the first three components exist to express.
        """
        g = plates()
        g.names["mod:exact"] = "Plate"
        # Needs a recipe, or the dead-key filter drops it before ranking ever sees it --
        # which is what this test asserted the first time it was written.
        g.add(Recipe("exact", "t", [("mod:exact", 1)],
                     [Ingredient(["mod:ingot"], 1)], category="crafting_shaped"))
        keys = explore.rank_matches(g, "plate").results
        self.assertEqual(keys[0], "mod:exact")

    def test_items_with_different_names_are_left_alone(self):
        # Only character-identical labels are a tie; nothing else may be reordered.
        g = plates()
        before = explore.rank_matches(g, "iron").results
        self.assertIn("mod:ingot", before)
        self.assertEqual(sorted(before), sorted(set(before)))

    def test_a_tie_group_straddling_the_limit_still_reorders_across_it(self):
        """The `limit` cutoff is what keeps this off the keystroke path, and it is the part
        most likely to be "simplified" into a truncation.

        Stopping at the first group that CROSSES `limit`, rather than the first that starts at
        or after it, would leave the best candidate stranded just outside the returned window
        while the rows shown are the alphabetical ones. Built so the canonical item sits past
        the cut before refinement and must be pulled inside it.
        """
        g = Graph()
        g.names = {"mod:ingot": "Iron Ingot"}
        # Thirty identically-named plates. `zz:` sorts last alphabetically and is the only one
        # anything consumes, so before refinement it sits at index 29, outside a limit of 5.
        for i in range(29):
            key = "m%02d:plate" % i
            g.names[key] = "Iron Plate"
            g.add(Recipe("mk%d" % i, "t", [(key, 1)],
                         [Ingredient(["mod:ingot"], 1)], category="crafting_shaped"))
        g.names["zz:plate"] = "Iron Plate"
        g.add(Recipe("press", "t", [("zz:plate", 1)],
                     [Ingredient(["mod:ingot"], 1)], category="ie.metalPress"))
        for i in range(6):
            g.add(Recipe("use%d" % i, "t", [("mod:thing%d" % i, 1)],
                         [Ingredient(["zz:plate"], 1)], category="c"))
        keys = explore.rank_matches(g, "iron plate", limit=5).results
        self.assertEqual(len(keys), 5)
        self.assertEqual(keys[0], "zz:plate")

    def test_the_order_is_total_so_two_identical_items_cannot_swap(self):
        # Registry id remains the last resort, so the list is stable between requests rather
        # than depending on dict order.
        g = plates()
        once = explore.rank_matches(g, "iron plate").results
        twice = explore.rank_matches(g, "iron plate").results
        self.assertEqual(once, twice)
        self.assertEqual(once[-1], "qq:plate")


if __name__ == "__main__":
    unittest.main()
