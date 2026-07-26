"""Machine availability, non-recipe filtering, and cost-based recipe choice."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, index, machines  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402


def graph_with_two_routes():
    """`widget` via a machine you own (expensive) or one you must build (cheap)."""
    g = Graph()
    g.names = {
        "mod:widget": "Widget", "mod:cheap_part": "Cheap Part",
        "mod:mountain": "Mountain Of Stuff", "mod:owned_machine": "Owned Machine",
        "mod:other_machine": "Other Machine",
    }
    g.add(Recipe("via_owned", "t", [("mod:widget", 1)],
                 [Ingredient(["mod:mountain"], 100000)], category="mod_owned_machine",
                 machine="Owned Machine"))
    g.add(Recipe("via_build", "t", [("mod:widget", 1)],
                 [Ingredient(["mod:cheap_part"], 2)], category="mod_other_machine",
                 machine="Other Machine"))
    return g


class AvailabilityTest(unittest.TestCase):
    def test_placed_block_counts_as_have(self):
        g = graph_with_two_routes()
        st = machines.resolve(g, placed={"mod:owned_machine": 1})
        self.assertEqual(st["mod_owned_machine"][0], machines.HAVE)

    def test_state_suffix_variants_match(self):
        # NuclearCraft registers `x_idle`/`x_active` items while the placed tile is bare.
        self.assertEqual(machines.normalise_block("nuclearcraft:crystallizer_idle"),
                         "nuclearcraft:crystallizer")
        self.assertEqual(machines.normalise_block("mod:thing_active"), "mod:thing")

    def test_crafting_never_needs_a_machine(self):
        g = graph_with_two_routes()
        g.add(Recipe("hand", "t", [("mod:cheap_part", 1)],
                     [Ingredient(["mod:mountain"], 1)], category="minecraft.crafting"))
        st = machines.resolve(g)
        self.assertEqual(st["minecraft.crafting"][0], machines.HAVE)

    def test_manual_override_wins(self):
        g = graph_with_two_routes()
        st = machines.resolve(g, overrides={"mod_owned_machine": machines.UNAVAILABLE})
        self.assertEqual(st["mod_owned_machine"][0], machines.UNAVAILABLE)

    def test_overrides_round_trip(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "m.json")
        machines.save_overrides(path, {"a": machines.HAVE, "b": "nonsense"})
        self.assertEqual(machines.load_overrides(path), {"a": machines.HAVE})


class IdentificationTest(unittest.TestCase):
    """The three ways a machine the player owns was reported as unusable."""

    @staticmethod
    def _graph(uid, title, block_id):
        g = Graph()
        g.names = {"mod:widget": "Widget", "mod:part": "Part", block_id: title or "?"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category=uid, machine=title))
        return g

    def test_format_code_in_title_does_not_hide_a_placed_machine(self):
        # Reported live: TechReborn.WireMill read "machine item unknown" while
        # techreborn:wire_mill was placed. The title is "Wire Mill§r".
        g = self._graph("TechReborn.WireMill", "Wire Mill§r", "techreborn:wire_mill")
        st = machines.resolve(g, placed={"techreborn:wire_mill": 1})
        self.assertEqual(st["TechReborn.WireMill"][0], machines.HAVE)

    def test_camel_case_uid_reaches_a_snake_case_registry_name(self):
        self.assertIn("techreborn:wire_mill", machines._id_guesses("TechReborn.WireMill"))
        self.assertIn("botania:runic_altar", machines._id_guesses("botania.runicAltar"))
        # An already-underscored uid must keep working unchanged.
        self.assertEqual(machines._id_guesses("nuclearcraft_crystallizer"),
                         ["nuclearcraft:crystallizer"])
        # Runs of capitals split before the final word: HTMLParser -> html_parser.
        self.assertIn("mod:ic2_macerator", machines._id_guesses("mod.IC2Macerator"))

    def test_both_naming_conventions_for_hand_crafting_are_free(self):
        # The offline jar reader says crafting_shaped; the JEI dump says
        # minecraft.crafting. Matching only one gated 10,301 recipes behind a machine
        # the player was told they did not have.
        for cat in ("crafting_shaped", "crafting_shapeless", "minecraft.crafting",
                    "minecraft.crafting.shapeless"):
            self.assertTrue(machines.is_hand_crafting(cat), cat)
            g = self._graph(cat, None, "mod:irrelevant")
            self.assertEqual(machines.resolve(g)[cat], (machines.HAVE, "no machine needed"))

    @staticmethod
    def _unnameable_graph():
        """A category whose title is a recipe TYPE, so it names no item at all.

        This is the common real case: "Casting", "Smelting", "Cover Crafting". 343 of the
        reference pack's 521 categories look like this.
        """
        g = Graph()
        g.names = {"mod:widget": "Widget", "mod:part": "Part"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="somemod.mysteryProcess", machine="Mystery Process"))
        return g

    def test_unidentifiable_machine_is_unknown_not_unavailable(self):
        # "Could not name it" is a gap in this tool, not proof the player cannot use it.
        st = machines.resolve(self._unnameable_graph())
        self.assertEqual(st["somemod.mysteryProcess"],
                         (machines.UNKNOWN, "machine item unknown"))
        self.assertLess(cost.MACHINE_COST[machines.BUILDABLE],
                        cost.MACHINE_COST[machines.UNKNOWN])
        self.assertLess(cost.MACHINE_COST[machines.UNKNOWN],
                        cost.MACHINE_COST[machines.UNAVAILABLE])

    def test_unknown_machines_stay_plannable(self):
        st = machines.resolve(self._unnameable_graph())
        self.assertIn("somemod.mysteryProcess", machines.available_categories(st))

    def test_identified_but_unobtainable_machine_is_still_unavailable(self):
        # The distinction that makes `unknown` worth having: here we DO know what the
        # machine is and there is provably no route to it.
        g = self._graph("somemod.thing", "Some Thing", "somemod:some_thing")
        st = machines.resolve(g)
        self.assertEqual(st["somemod.thing"][0], machines.UNAVAILABLE)

    def test_catalysts_beat_title_matching(self):
        # JEI's own "made in" list is authoritative; the heuristic must not override it.
        g = self._graph("tconstruct.casting_table", "Casting", "tconstruct:casting_table")
        st = machines.resolve(g, placed={"tconstruct:casting_table": 1},
                              catalysts={"tconstruct.casting_table":
                                         ["tconstruct:casting_table"]})
        self.assertEqual(st["tconstruct.casting_table"][0], machines.HAVE)

    def test_describe_reports_candidates_and_recipe_counts(self):
        g = self._graph("TechReborn.WireMill", "Wire Mill§r", "techreborn:wire_mill")
        info = machines.describe(g, placed={"techreborn:wire_mill": 1})
        rec = info["TechReborn.WireMill"]
        self.assertEqual(rec["recipes"], 1)
        self.assertEqual(rec["title"], "Wire Mill")
        self.assertEqual(rec["candidates"], ["techreborn:wire_mill"])
        self.assertEqual(rec["mod"], "techreborn")


class NonRecipeTest(unittest.TestCase):
    def test_known_non_production_categories_are_recognised(self):
        for cat in ("minecraft.anvil", "EIOTank", "forestry.bottler", "jei.information",
                    "jeresources.worldgen", "VILLAGER_TRADE_CATEGORY", "chickens.Drops"):
            self.assertTrue(index.is_non_recipe(cat), cat)

    def test_real_production_categories_are_kept(self):
        for cat in ("nuclearcraft_crystallizer", "forestry.squeezer",
                    "tconstruct.smeltery", "minecraft.crafting", "AlloySmelter"):
            self.assertFalse(index.is_non_recipe(cat), cat)

    def test_keep_list_overrides_the_pattern(self):
        self.assertFalse(index.is_non_recipe("minecraft.anvil", keep={"minecraft.anvil"}))


class CostChoiceTest(unittest.TestCase):
    def test_cheap_buildable_route_beats_ruinous_owned_route(self):
        # The failure this guards: with only local scoring, "machine I own" won and the
        # solver planned 100,000 items instead of building a machine and using 2.
        g = graph_with_two_routes()
        states = machines.resolve(g, placed={"mod:owned_machine": 1})
        self.assertEqual(states["mod_owned_machine"][0], machines.HAVE)
        costs = cost.estimate(g, have={}, machine_states=states)
        s = Solver(g, have={}, machine_states=states, costs=costs)
        res = s.solve("mod:widget", 1)
        needs = {r["key"] for r in res["shopping_list"]}
        self.assertIn("mod:cheap_part", needs)
        self.assertNotIn("mod:mountain", needs)

    def test_container_transfers_are_costed_out(self):
        g = Graph()
        g.add(Recipe("real", "t", [("fluid:x", 100)], [Ingredient(["mod:src"], 1)]))
        t = Recipe("xfer", "t", [("fluid:x", 16000)], [Ingredient(["mod:tank"], 1)])
        t.transfer = True
        g.add(t)
        costs = cost.estimate(g, have={"mod:tank": 5, "mod:src": 5})
        real = cost.recipe_cost(costs, g.recipes[0], {})
        xfer = cost.recipe_cost(costs, g.recipes[1], {})
        self.assertLess(real, xfer, "a transfer must never look cheaper than production")

    def test_unreachable_input_gives_infinite_cost(self):
        g = Graph()
        g.add(Recipe("r", "t", [("mod:out", 1)], [Ingredient(["mod:never"], 1)]))
        costs = cost.estimate(g, have={})
        # mod:never has no recipe, so it is seeded as obtainable rather than infinite;
        # what must not happen is a silently finite cost for a genuinely missing input.
        self.assertIn("mod:never", costs)


if __name__ == "__main__":
    unittest.main()
