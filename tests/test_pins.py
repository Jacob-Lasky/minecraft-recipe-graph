"""Pinned recipe choices: identity that survives a redump, and a solver that obeys them.

The feature is one sentence -- "let me pick the route and have it stick" -- and all of the
difficulty is in the word STICK. A recipe id is `hei:<category>:<line number>`, a redump
renumbers every one of them, and Jake redumps often. So most of this file is about what a
pin is stored AS.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import pins  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402


def smelt(rid="hei:smelting:12"):
    return Recipe(rid, "hei", [("mod:ingot", 1)],
                  [Ingredient(["mod:ore"], 1)], category="smelting",
                  machine="Furnace")


def crush(rid="hei:crusher:4"):
    return Recipe(rid, "hei", [("mod:ingot", 2)],
                  [Ingredient(["mod:ore"], 1)], category="crusher", machine="Crusher")


def unpack(rid="hei:minecraft.crafting:900"):
    return Recipe(rid, "hei", [("mod:ingot", 9)],
                  [Ingredient(["mod:block"], 1)], category="minecraft.crafting")


def iron_graph():
    g = Graph()
    g.names = {"mod:ingot": "Ingot", "mod:ore": "Ore", "mod:block": "Block"}
    for recipe in (smelt(), crush(), unpack()):
        g.add(recipe)
    g.add(Recipe("hei:minecraft.crafting:901", "hei", [("mod:block", 1)],
                 [Ingredient(["mod:ingot"], 9)], category="minecraft.crafting"))
    return g


class FingerprintTest(unittest.TestCase):
    def test_the_id_does_not_enter_it(self):
        # The whole point. A redump renumbers `hei:<category>:<line>`, so a pin stored by
        # id would stop applying the first time Jake redumps, silently.
        self.assertEqual(pins.fingerprint(smelt("hei:smelting:12")),
                         pins.fingerprint(smelt("hei:smelting:9999")))

    def test_the_machine_name_does_not_enter_it(self):
        # A display string, and a localised one. It does not change what the recipe does.
        a = smelt()
        b = smelt()
        b.machine = "Blast Furnace"
        self.assertEqual(pins.fingerprint(a), pins.fingerprint(b))

    def test_the_extractor_does_not_enter_it(self):
        a, b = smelt(), smelt()
        b.source = "jar_json"
        self.assertEqual(pins.fingerprint(a), pins.fingerprint(b))

    def test_changing_what_the_recipe_makes_lapses_the_pin(self):
        # The one case where a pin SHOULD stop matching: the pack changed the recipe, and
        # continuing to point at it would silently mean something else.
        a = smelt()
        b = Recipe(a.rid, "hei", [("mod:ingot", 4)],
                   [Ingredient(["mod:ore"], 1)], category="smelting")
        self.assertNotEqual(pins.fingerprint(a), pins.fingerprint(b))

    def test_category_inputs_quantities_and_roles_all_count(self):
        base = smelt()
        variants = [
            Recipe("r", "hei", [("mod:ingot", 1)], [Ingredient(["mod:ore"], 1)],
                   category="blasting"),
            Recipe("r", "hei", [("mod:ingot", 1)], [Ingredient(["mod:dust"], 1)],
                   category="smelting"),
            Recipe("r", "hei", [("mod:ingot", 1)], [Ingredient(["mod:ore"], 2)],
                   category="smelting"),
            Recipe("r", "hei", [("mod:ingot", 1)], [Ingredient(["mod:ore"], 1, "fluid")],
                   category="smelting"),
        ]
        got = {pins.fingerprint(base)} | {pins.fingerprint(v) for v in variants}
        self.assertEqual(len(got), 5, "each of these is a different recipe")

    def test_slot_order_is_not_part_of_the_recipe(self):
        # Which order an extractor walked the slots in is an artefact, and two dumps must
        # not fingerprint one recipe two ways over it.
        a = Recipe("r", "hei", [("mod:x", 1)],
                   [Ingredient(["mod:a"], 1), Ingredient(["mod:b"], 2)], category="c")
        b = Recipe("r", "hei", [("mod:x", 1)],
                   [Ingredient(["mod:b"], 2), Ingredient(["mod:a"], 1)], category="c")
        self.assertEqual(pins.fingerprint(a), pins.fingerprint(b))

    def test_alternative_order_within_a_slot_is_not_either(self):
        a = Recipe("r", "hei", [("mod:x", 1)],
                   [Ingredient(["mod:a", "mod:b"], 1)], category="c")
        b = Recipe("r", "hei", [("mod:x", 1)],
                   [Ingredient(["mod:b", "mod:a"], 1)], category="c")
        self.assertEqual(pins.fingerprint(a), pins.fingerprint(b))

    def test_a_slot_of_two_is_not_two_slots_of_one(self):
        # The separators are control characters precisely so this cannot collide: an item
        # key can contain a colon and a hash, but not these.
        one = Recipe("r", "hei", [("mod:x", 1)],
                     [Ingredient(["mod:a", "mod:b"], 1)], category="c")
        two = Recipe("r", "hei", [("mod:x", 1)],
                     [Ingredient(["mod:a"], 1), Ingredient(["mod:b"], 1)], category="c")
        self.assertNotEqual(pins.fingerprint(one), pins.fingerprint(two))

    def test_it_is_twelve_hex_digits(self):
        self.assertRegex(pins.fingerprint(smelt()), r"^[0-9a-f]{12}$")


class ResolveTest(unittest.TestCase):
    def test_an_intact_pin_resolves_to_exactly_that_recipe(self):
        g = iron_graph()
        pin = pins.make(g, crush())
        accepted, notes = pins.resolve(g, {"mod:ingot": pin})
        self.assertEqual(accepted["mod:ingot"], frozenset(["hei:crusher:4"]))
        self.assertEqual(notes["mod:ingot"][0], pins.EXACT)

    def test_a_pin_survives_the_recipe_being_renumbered(self):
        # A redump: same recipes, all new ids.
        g = Graph()
        g.names = {"mod:ingot": "Ingot"}
        g.add(smelt("hei:smelting:5001"))
        g.add(crush("hei:crusher:7777"))
        accepted, notes = pins.resolve(g, {"mod:ingot": pins.make(iron_graph(), crush())})
        self.assertEqual(accepted["mod:ingot"], frozenset(["hei:crusher:7777"]))
        self.assertEqual(notes["mod:ingot"][0], pins.EXACT)

    def test_a_changed_recipe_falls_back_to_the_category_and_says_so(self):
        # "Make it in the crusher" is usually what the pin MEANT, so keep that rather than
        # silently reverting to the ranking. The note is the "i'm fine with suggestions"
        # half of the request: the tool may adapt, it may not do so quietly.
        g = Graph()
        g.names = {"mod:ingot": "Ingot"}
        g.add(smelt())
        g.add(Recipe("hei:crusher:8", "hei", [("mod:ingot", 3)],
                     [Ingredient(["mod:ore"], 1)], category="crusher"))
        accepted, notes = pins.resolve(g, {"mod:ingot": pins.make(iron_graph(), crush())})
        self.assertEqual(accepted["mod:ingot"], frozenset(["hei:crusher:8"]))
        state, why = notes["mod:ingot"]
        self.assertEqual(state, pins.CATEGORY)
        self.assertIn("crusher", why)

    def test_a_pin_with_nowhere_to_go_is_dead_and_changes_nothing(self):
        g = Graph()
        g.names = {"mod:ingot": "Ingot"}
        g.add(smelt())
        accepted, notes = pins.resolve(g, {"mod:ingot": pins.make(iron_graph(), crush())})
        self.assertNotIn("mod:ingot", accepted)
        self.assertEqual(notes["mod:ingot"][0], pins.DEAD)

    def test_identical_recipes_all_satisfy_one_pin(self):
        # 437 of the pack's bee mutations are byte-identical. Pinning one means any of
        # them will do, and forcing a choice between them would be a lie about the data.
        g = Graph()
        g.names = {"mod:ingot": "Ingot"}
        g.add(crush("hei:crusher:1"))
        g.add(crush("hei:crusher:2"))
        accepted, _notes = pins.resolve(g, {"mod:ingot": pins.make(g, crush())})
        self.assertEqual(accepted["mod:ingot"],
                         frozenset(["hei:crusher:1", "hei:crusher:2"]))

    def test_a_pin_on_an_item_nothing_makes_is_dead_not_a_crash(self):
        accepted, notes = pins.resolve(iron_graph(), {"mod:nothing": pins.make(
            iron_graph(), crush())})
        self.assertEqual(accepted, {})
        self.assertEqual(notes["mod:nothing"][0], pins.DEAD)


class FileTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "recipes.json")

    def test_round_trip(self):
        pin = pins.make(iron_graph(), crush())
        pins.save(self.path, {"mod:ingot": pin})
        self.assertEqual(pins.load(self.path), {"mod:ingot": pin})

    def test_the_stored_label_survives_the_recipe_vanishing(self):
        # A dead pin that can only report a hex string is a pin nobody can decide what to
        # do about, so the human text is stored rather than looked up when shown.
        pin = pins.make(iron_graph(), crush())
        self.assertIn("Ore", pin["label"])
        pins.save(self.path, {"mod:ingot": pin})
        self.assertIn("Ore", pins.load(self.path)["mod:ingot"]["label"])

    def test_a_missing_file_is_no_pins_not_an_error(self):
        self.assertEqual(pins.load(os.path.join(self.dir, "nope.json")), {})

    def test_a_broken_file_does_not_break_planning(self):
        # This file is hand-editable and sits next to machines.json. A syntax error in it
        # must cost you your pins, not your ability to plan.
        with open(self.path, "w") as fh:
            fh.write("{not json")
        self.assertEqual(pins.load(self.path), {})

    def test_entries_without_a_fingerprint_are_dropped(self):
        with open(self.path, "w") as fh:
            json.dump({"pins": {"a": {"category": "c"}, "b": "nonsense",
                                "c": {"fingerprint": "abc", "category": "x"}}}, fh)
        self.assertEqual(sorted(pins.load(self.path)), ["c"])


class SolverTest(unittest.TestCase):
    def route(self, solver):
        tree = solver.solve("mod:ingot", 1)["tree"]
        return tree.get("category"), tree.get("pinned", False)

    def test_without_a_pin_the_ranking_chooses(self):
        # Establishes the baseline the pin has to overrule; without it a passing pin test
        # could just be agreeing with the ranking.
        category, pinned = self.route(Solver(iron_graph()))
        self.assertFalse(pinned)
        self.assertNotEqual(category, "crusher")

    def test_a_pin_overrules_the_ranking(self):
        g = iron_graph()
        accepted, _n = pins.resolve(g, {"mod:ingot": pins.make(g, crush())})
        self.assertEqual(self.route(Solver(g, pinned=accepted)), ("crusher", True))

    def test_a_lapsed_pin_still_holds_the_category(self):
        g = Graph()
        g.names = {"mod:ingot": "Ingot"}
        g.add(smelt())
        g.add(Recipe("hei:crusher:8", "hei", [("mod:ingot", 3)],
                     [Ingredient(["mod:ore"], 1)], category="crusher"))
        accepted, _n = pins.resolve(g, {"mod:ingot": pins.make(iron_graph(), crush())})
        self.assertEqual(self.route(Solver(g, pinned=accepted)), ("crusher", True))

    def test_a_pin_that_matches_nothing_leaves_the_plan_alone(self):
        # A plan beats an error. `pins.resolve` has already reported the lapse, so this
        # is a fallback rather than a silence.
        g = iron_graph()
        solver = Solver(g, pinned={"mod:ingot": frozenset(["hei:gone:1"])})
        _category, pinned = self.route(solver)
        self.assertFalse(pinned)
        self.assertEqual(len(solver.solve("mod:ingot", 1)["tree"]["children"]), 1)

    def test_pick_recipe_ranks_within_the_pin_rather_than_taking_dump_order(self):
        # Two recipes in one pinned category. The pin says which category; the ranking
        # still says which of them, or a category pin would silently mean "the first one
        # the dump happened to list".
        g = Graph()
        g.names = {"mod:ingot": "Ingot", "mod:ore": "Ore", "mod:rare": "Rare"}
        g.add(Recipe("hei:c:1", "hei", [("mod:ingot", 1)],
                     [Ingredient(["mod:rare"], 8)], category="crusher"))
        g.add(Recipe("hei:c:2", "hei", [("mod:ingot", 1)],
                     [Ingredient(["mod:ore"], 1)], category="crusher"))
        solver = Solver(g, have={"mod:ore": 64},
                        pinned={"mod:ingot": frozenset(["hei:c:1", "hei:c:2"])})
        self.assertEqual(solver.pick_recipe("mod:ingot").rid, "hei:c:2")

    def test_a_pin_never_wins_over_terminating_the_tree(self):
        # A pinned recipe that only cycles must not defeat the backtracking: the plan
        # would ask for the item being crafted. See the uncrafting guard in `expand`.
        g = iron_graph()
        accepted, _n = pins.resolve(g, {"mod:ingot": pins.make(g, unpack())})
        tree = Solver(g, pinned=accepted).solve("mod:ingot", 1)["tree"]
        self.assertNotIn("mod:ingot", list(_keys_below(tree)))
        self.assertEqual(tree["category"], "smelting", "should have backtracked")
        # And the badge must not claim a pin was honoured when it was backtracked out of.
        # A badge on the wrong recipe is worse than no badge: it says the choice stuck.
        self.assertFalse(tree.get("pinned"))

    def test_a_pin_the_cycle_guard_ignores_is_reported_not_swallowed(self):
        # The chooser badges the choice as taken the moment it is saved, so a plan that
        # quietly used something else is worse than one that never accepted the pin.
        g = iron_graph()
        accepted, _n = pins.resolve(g, {"mod:ingot": pins.make(g, unpack())})
        result = Solver(g, pinned=accepted).solve("mod:ingot", 1)
        self.assertIn("mod:ingot", result["pins_overruled"])
        self.assertIn("loops back", result["pins_overruled"]["mod:ingot"])

    def test_a_pin_that_was_honoured_reports_nothing(self):
        g = iron_graph()
        accepted, _n = pins.resolve(g, {"mod:ingot": pins.make(g, crush())})
        self.assertEqual(Solver(g, pinned=accepted).solve("mod:ingot", 1)["pins_overruled"],
                         {})

    def test_no_pin_at_all_reports_nothing(self):
        self.assertEqual(Solver(iron_graph()).solve("mod:ingot", 1)["pins_overruled"], {})


def _keys_below(node):
    for child in node.get("children") or ():
        yield child["key"]
        for key in _keys_below(child):
            yield key


if __name__ == "__main__":
    unittest.main()
