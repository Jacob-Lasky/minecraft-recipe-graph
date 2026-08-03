"""Recovering a fluid's real name from the containers it is bottled in. See #103."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import explore, fluidnames  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402


def empty(rid, container, fluid, qty=1000):
    """A container-emptying recipe: one filled container in, its contents out."""
    return Recipe(rid, "t", [("techreborn:dynamiccell", 1), (fluid, qty)],
                  [Ingredient([container], 1)], category="transposer")


def fill(rid, fluid, container, qty=1000):
    """A container-filling recipe: one fluid plus an empty container in, the filled one out."""
    return Recipe(rid, "t", [(container, 1)],
                  [Ingredient(["techreborn:dynamiccell"], 1),
                   Ingredient([fluid], qty, "fluid")], category="transposer")


def renamed_graph():
    """The reported case: a fluid the pack renamed away from its registry name.

    `fluid:nethengeic_fluid` is AoA3's, and MeatballCraft calls it Strong Mythic Essence.
    """
    g = Graph()
    g.names = {
        "techreborn:dynamiccell": "Empty Cell",
        "techreborn:dynamiccell#aaaa": "Strong Mythic Essence Cell",
        "forestry:can:1#aaaa": "Strong Mythic Essence Can",
        "mod:sludge": "Sludge",
    }
    g.add(empty("e1", "techreborn:dynamiccell#aaaa", "fluid:nethengeic_fluid"))
    g.add(empty("e2", "forestry:can:1#aaaa", "fluid:nethengeic_fluid"))
    # An unbottled fluid, so the `_prettify` fallback is exercised in the same graph.
    g.add(Recipe("s", "t", [("mod:sludge", 1)],
                 [Ingredient(["fluid:boric_acid"], 500, "fluid")], category="minecraft.crafting"))
    return g


class TheNameComesFromTheContainerTest(unittest.TestCase):
    def test_the_reported_fluid_is_named_what_the_pack_calls_it(self):
        # Was "Nethengeic Fluid", the pre-rename identity of a fluid the pack reuses.
        self.assertEqual(renamed_graph().bare_name("fluid:nethengeic_fluid"),
                         "Strong Mythic Essence")

    def test_filling_a_container_names_the_fluid_too(self):
        # Both directions, because the pack authors fill and empty in separate machines and
        # a fluid may only ever appear in one of them.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "techreborn:dynamiccell#bbbb": "Molten Sednanite Cell"}
        g.add(fill("f1", "fluid:sednanite", "techreborn:dynamiccell#bbbb"))
        self.assertEqual(g.bare_name("fluid:sednanite"), "Molten Sednanite")

    def test_a_fluid_no_container_touches_keeps_the_prettified_registry_name(self):
        # The fallback has to stay: a smaller pack may bottle nothing at all.
        self.assertEqual(renamed_graph().bare_name("fluid:boric_acid"), "Boric Acid")

    def test_the_display_form_carries_the_recovered_name(self):
        self.assertEqual(renamed_graph().display("fluid:nethengeic_fluid"),
                         "[fluid] Strong Mythic Essence")

    def test_the_label_index_carries_it_so_search_can_reach_it(self):
        g = renamed_graph()
        self.assertEqual(g.labels["fluid:nethengeic_fluid"], "Strong Mythic Essence")

    def test_searching_the_name_on_screen_finds_the_fluid(self):
        # The whole point of #103. "strong mythic essence" matched the cell and the can and
        # never the fluid, which is what "it doesn't exist as a fluid" meant.
        keys = explore.rank_matches(renamed_graph(), "strong mythic essence").results
        self.assertIn("fluid:nethengeic_fluid", keys)
        self.assertEqual(keys[0], "fluid:nethengeic_fluid")


class OnlyCuratedContainersVoteTest(unittest.TestCase):
    """A suffix guess is wrong here, and it was measured wrong. See fluidnames.CONTAINERS."""

    def test_an_item_merely_named_after_a_fluid_casts_no_vote(self):
        # `plustic:battery_cell` labels itself "Manyullyn Battery Cell" and outvoted the
        # truth 5 to 2 on the reference pack, renaming fluid:manyullyn to "Manyullyn
        # Battery". It is not a container; it is an item made FROM the fluid.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "plustic:battery_cell#cccc": "Manyullyn Battery Cell"}
        g.add(empty("e", "plustic:battery_cell#cccc", "fluid:manyullyn"))
        self.assertEqual(g.fluid_names, {})
        self.assertEqual(g.bare_name("fluid:manyullyn"), "Manyullyn")

    def test_every_curated_base_actually_parses_a_label_of_its_own_shape(self):
        # A base whose suffix word is wrong -- a typo, or a mod that renamed its container --
        # silently stops voting and takes its fluids' names with it. Nothing else notices,
        # because the fallback still produces a plausible-looking name.
        for base, word in fluidnames.CONTAINERS.items():
            g = Graph()
            key = base + "#0000"
            g.names = {"techreborn:dynamiccell": "Empty Cell",
                       key: "Test Substance %s" % word}
            g.add(empty("e", key, "fluid:test_substance"))
            self.assertEqual(g.bare_name("fluid:test_substance"), "Test Substance",
                             "%s did not parse its own '%s' suffix" % (base, word))

    def test_a_container_word_inside_the_fluid_name_survives(self):
        # Non-greedy `(.+?)` splits at the LAST container word, not the first.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "techreborn:dynamiccell#dddd": "Cell Culture Cell"}
        g.add(empty("e", "techreborn:dynamiccell#dddd", "fluid:cell_culture"))
        self.assertEqual(g.bare_name("fluid:cell_culture"), "Cell Culture")

    def test_a_bare_container_name_with_no_contents_is_not_a_vote(self):
        # "Empty Cell" has nothing before the suffix, so the pattern must not match it and
        # hand back "".
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Cell"}
        g.add(empty("e", "techreborn:dynamiccell", "fluid:mystery"))
        self.assertEqual(g.fluid_names, {})


class AmbiguousEvidenceTest(unittest.TestCase):
    def test_the_catch_all_transposer_entry_casts_no_vote(self):
        # The pack's generic "Fluid Transposer - Empty" lists 1,198 filled containers in one
        # slot against an output of water. Counting it would vote "Water" for every fluid in
        # the game -- and, read the other way, name water after whichever container sorted
        # first. Only unambiguous slots may speak.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "techreborn:dynamiccell#eeee": "Molten Sednanite Cell",
                   "techreborn:dynamiccell#ffff": "Lava Cell"}
        g.add(Recipe("wide", "t", [("techreborn:dynamiccell", 1), ("fluid:water", 1000)],
                     [Ingredient(["techreborn:dynamiccell#eeee",
                                  "techreborn:dynamiccell#ffff"], 1)], category="transposer"))
        self.assertEqual(g.fluid_names, {})

    def test_the_majority_wins(self):
        # THE MAJORITY MUST SORT LAST, and that is the whole design of this fixture. The
        # previous names were "Right Name" (2 votes) against "Wrong Name" (1), and "Right"
        # sorts before "Wrong" -- so `decide` ignoring the count entirely and returning
        # `min(sorted(names))` picked the same winner and this test had no property left.
        # Count and alphabet must DISAGREE or the count is not being asserted.
        # `FluidNamesTest.votesAreSettledByCountAndThenAlphabetically` is the Java sibling
        # and already had this shape.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "techreborn:dynamiccell#1111": "Zed Name Cell",
                   "forestry:can:1#1111": "Zed Name Can",
                   "openblocks:tank#1111": "Aye Name Tank"}
        g.add(empty("e1", "techreborn:dynamiccell#1111", "fluid:x"))
        g.add(empty("e2", "forestry:can:1#1111", "fluid:x"))
        g.add(empty("e3", "openblocks:tank#1111", "fluid:x"))
        self.assertEqual(dict(fluidnames.tally(g.recipes, g.bare_name)["fluid:x"]),
                         {"Zed Name": 2, "Aye Name": 1},
                         "the fixture must give the majority the LATER name, or an "
                         "alphabetical decide passes this test")
        self.assertEqual(g.bare_name("fluid:x"), "Zed Name")

    def test_a_tie_is_broken_alphabetically_and_does_not_flicker(self):
        # One fluid ties on the reference pack (`fluid:eternal_dragon_fire`, "Eternal Dragon
        # Fire" against "Niddhog Dragonfire", four votes each). Two names with nothing in
        # common, so an unstable choice would change the label between graph loads.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "techreborn:dynamiccell#2222": "Niddhog Dragonfire Cell",
                   "forestry:can:1#2222": "Eternal Dragon Fire Can"}
        g.add(empty("e1", "techreborn:dynamiccell#2222", "fluid:eternal_dragon_fire"))
        g.add(empty("e2", "forestry:can:1#2222", "fluid:eternal_dragon_fire"))
        self.assertEqual(g.bare_name("fluid:eternal_dragon_fire"), "Eternal Dragon Fire")
        self.assertEqual(fluidnames.decide(
            {"Niddhog Dragonfire": 4, "Eternal Dragon Fire": 4}), "Eternal Dragon Fire")

    def test_the_tally_keeps_the_evidence_rather_than_only_the_verdict(self):
        # A one-vote name and a unanimous one are different claims; an audit needs both.
        g = renamed_graph()
        votes = fluidnames.tally(g.recipes, g.bare_name)
        self.assertEqual(dict(votes["fluid:nethengeic_fluid"]), {"Strong Mythic Essence": 2})


class CacheTest(unittest.TestCase):
    def test_adding_a_recipe_renames_the_fluid(self):
        # `_invalidate` has to drop this index with the others, or a graph edited in place
        # keeps serving names derived from recipes it no longer has.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell",
                   "techreborn:dynamiccell#3333": "Molten Sednanite Cell"}
        self.assertEqual(g.bare_name("fluid:sednanite"), "Sednanite")
        g.add(empty("e", "techreborn:dynamiccell#3333", "fluid:sednanite"))
        self.assertEqual(g.bare_name("fluid:sednanite"), "Molten Sednanite")

    def test_a_container_wearing_a_fluid_key_cannot_recurse_forever(self):
        # `derive` calls `bare_name`, which reads this property. The curated list makes a
        # `fluid:` container structurally impossible, and the pre-seeded {} makes it
        # survivable anyway. Without the seed this is a RecursionError, not a wrong name.
        g = Graph()
        g.names = {"techreborn:dynamiccell": "Empty Cell"}
        g.add(Recipe("weird", "t", [("techreborn:dynamiccell", 1), ("fluid:a", 1000)],
                     [Ingredient(["fluid:b"], 1)], category="transposer"))
        self.assertEqual(g.bare_name("fluid:a"), "A")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
