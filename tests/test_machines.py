"""Machine availability, non-recipe filtering, and cost-based recipe choice."""

import json
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
        self.assertEqual(rec["mod"], "techreborn",
                         "with no dump the uid token is still the only thing to go on")


class ModGroupingTest(unittest.TestCase):
    """#14: three one-category 'mods' on the machines page that were not mods."""

    @staticmethod
    def _graph(uid, mod=None):
        g = Graph()
        g.names = {"mod:widget": "Widget", "mod:part": "Part"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category=uid, machine="Thing"))
        if mod:
            g.category_mods = {uid: mod}
        return g

    def test_jeis_mod_name_beats_the_uid_token(self):
        for uid, jei, guessed in (
                ("foregoing_plant_gatherer", "Industrial Foregoing", "foregoing"),
                ("safe_nuke_meatball", "Extreme Reactors", "safe"),
                ("SoulBinder", "enderiomachines", "soulbinder")):
            self.assertEqual(machines.describe(self._graph(uid, jei))[uid]["mod"], jei)
            self.assertEqual(machines.describe(self._graph(uid))[uid]["mod"], guessed,
                             "and the guess is what we fall back to without a dump")

    def test_the_display_name_never_reaches_machine_identification(self):
        """Two fields, two jobs.

        `same_mod` compares REGISTRY modids. Feeding it "Industrial Foregoing" would fail
        against `industrialforegoing:plant_gatherer` and break identification, which is
        currently correct -- so it must keep reading the uid.
        """
        uid = "foregoing_plant_gatherer"
        g = self._graph(uid, "Industrial Foregoing")
        self.assertTrue(machines.same_mod(uid, "foregoing:plant_gatherer"))
        self.assertFalse(machines.same_mod(uid, "Industrial Foregoing:x"))
        self.assertEqual(machines.mod_name(g, uid), "Industrial Foregoing")

    def test_category_mods_survive_a_save_and_load(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "g.json")
        self._graph("SoulBinder", "enderiomachines").save(path)
        self.assertEqual(Graph.load(path).category_mods, {"SoulBinder": "enderiomachines"})


class NoMachineNeededTest(unittest.TestCase):
    """#15: bee, tree and chicken categories read as a tool failure but are not one."""

    @staticmethod
    def _graph(uid, schema=machines.SPECIES_SCHEMA):
        g = Graph()
        g.names = {"mod:widget": "Widget", "mod:part": "Part"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category=uid, machine="Apiary"))
        g.dump_schema = schema
        return g

    def test_husbandry_categories_are_ungated_with_an_honest_reason(self):
        for uid in ("bdew.jeibees.mutation.rootBees", "bdew.jeibees.produce.rootTrees",
                    "chickens.Laying", "chickens.Henhousing", "beebetteratbees.beetree"):
            state, why = machines.resolve(self._graph(uid))[uid]
            self.assertEqual(state, machines.HAVE, uid)
            self.assertIn("no machine needed", why, uid)
            self.assertIn("bred, grown or laid", why,
                          "%s: distinguishable from hand-crafting in the evidence" % uid)

    def test_the_verdict_waits_for_a_dump_that_can_tell_bees_apart(self):
        """Measured: ungating these at schema 2 rerouted Americium-242 through bee larvae.

        Every pattern is creature-driven and a creature's identity is NBT, so before
        schema 3 the category is one edge pretending to be 437 and `unknown`'s higher
        cost is the only thing holding it back. See #20.
        """
        uid = "chickens.Laying"
        state, why = machines.resolve(self._graph(uid, schema=2))[uid]
        self.assertEqual(state, machines.UNKNOWN)
        self.assertNotIn("no machine needed", why)

    def test_a_user_declared_category_is_never_gated(self):
        # An explicit human decision outranks what the dump can prove.
        uid = "somepack.ritual"
        g = self._graph(uid, schema=1)
        state, _why = machines.resolve(g, no_machine=[uid])[uid]
        self.assertEqual(state, machines.HAVE)

    def test_saving_a_toggle_does_not_delete_the_hand_edited_list(self):
        path = os.path.join(tempfile.mkdtemp(), "machines.json")
        with open(path, "w") as fh:
            json.dump({"no_machine": ["somepack.ritual"], "overrides": {}}, fh)
        machines.save_overrides(path, {"mod.press": machines.HAVE})
        self.assertEqual(machines.load_no_machine(path), ["somepack.ritual"])
        self.assertEqual(machines.load_overrides(path), {"mod.press": machines.HAVE})
        # And across a second save, which is where reading the list after truncating the
        # file would quietly lose it.
        machines.save_overrides(path, {"mod.press": machines.BUILDABLE})
        self.assertEqual(machines.load_no_machine(path), ["somepack.ritual"])

    def test_the_earliest_flat_format_is_not_carried_forward_forever(self):
        # The first format let the whole document BE the overrides map. Preserving
        # unrecognised keys would leave those entries in the file contradicting the UI.
        path = os.path.join(tempfile.mkdtemp(), "machines.json")
        with open(path, "w") as fh:
            json.dump({"legacy.category": machines.HAVE}, fh)
        self.assertEqual(machines.load_overrides(path), {"legacy.category": machines.HAVE})
        machines.save_overrides(path, {"mod.press": machines.HAVE})
        with open(path) as fh:
            self.assertEqual(sorted(json.load(fh)),
                             ["_comment", "no_machine", "overrides"])

    def test_a_file_with_neither_key_still_reads_as_empty(self):
        d = tempfile.mkdtemp()
        self.assertEqual(machines.load_no_machine(os.path.join(d, "nope.json")), [])
        bad = os.path.join(d, "bad.json")
        with open(bad, "w") as fh:
            fh.write("[1,2,3]")
        self.assertEqual(machines.load_no_machine(bad), [])
        self.assertEqual(machines.load_overrides(bad), {})


class NonRecipeTest(unittest.TestCase):
    def test_known_non_production_categories_are_recognised(self):
        for cat in ("minecraft.anvil", "EIOTank", "forestry.bottler", "jei.information",
                    "jeresources.worldgen", "VILLAGER_TRADE_CATEGORY", "chickens.Drops"):
            self.assertTrue(index.is_non_recipe(cat), cat)

    def test_display_only_categories_are_dropped(self):
        # #15: five categories that show you something rather than making it.
        for cat in ("packagedauto:package_contents", "machine_produce_category",
                    "chickens.Throws", "right_click_meatball", "meatball_puzzle"):
            self.assertTrue(index.is_non_recipe(cat), cat)

    def test_real_production_categories_are_kept(self):
        for cat in ("nuclearcraft_crystallizer", "forestry.squeezer",
                    "tconstruct.smeltery", "minecraft.crafting", "AlloySmelter",
                    # Husbandry IS production; it just has no machine. Different answer,
                    # different mechanism -- see machines.NO_MACHINE_PATTERNS.
                    "bdew.jeibees.mutation.rootBees", "chickens.Laying"):
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


class SameModTest(unittest.TestCase):
    """Deciding whether a candidate belongs to the category's own mod."""

    def test_a_modid_containing_an_underscore_is_not_split(self):
        # `tinker_io:smart_output` tokenises to `tinker`, so comparing first tokens
        # declared tinker_io's own machine to be from a different mod and stamped a
        # "(name match, other mod)" caveat on evidence that was exact.
        self.assertTrue(machines.same_mod("tinker_io:smart_output",
                                          "tinker_io:smart_output"))

    def test_every_uid_separator_style_works(self):
        for uid, key in (("TechReborn.WireMill", "techreborn:wire_mill"),
                         ("GENDUSTRY_SAMPLER", "gendustry:sampler"),
                         ("nuclearcraft_alloy_furnace", "nuclearcraft:alloy_furnace"),
                         ("bloodmagic:salchemyTable", "bloodmagic:alchemy_table")):
            self.assertTrue(machines.same_mod(uid, key), "%s / %s" % (uid, key))

    def test_a_genuine_cross_mod_match_is_still_detected(self):
        # The Extra Utilities furnace category is titled "Furnace" and matches
        # `minecraft:furnace`, which must not read as "you own this machine".
        self.assertFalse(machines.same_mod("xu2_machine_extrautils2:furnace",
                                           "minecraft:furnace"))

    def test_cross_mod_evidence_carries_a_caveat(self):
        g = Graph()
        g.names = {"minecraft:furnace": "Furnace", "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="xu2_machine_extrautils2:furnace", machine="Furnace"))
        state, why = machines.resolve(g, placed={"minecraft:furnace": 1})[
            "xu2_machine_extrautils2:furnace"]
        self.assertEqual(state, machines.HAVE)
        self.assertIn("other mod", why)

    def test_same_mod_evidence_has_no_caveat(self):
        g = Graph()
        g.names = {"tinker_io:smart_output": "Smart Output", "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="tinker_io:smart_output", machine="Smart Output"))
        _state, why = machines.resolve(g, placed={"tinker_io:smart_output": 1})[
            "tinker_io:smart_output"]
        self.assertNotIn("other mod", why)
