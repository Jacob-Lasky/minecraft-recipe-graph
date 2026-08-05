"""Machine availability, non-recipe filtering, and cost-based recipe choice."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# tests/ itself, so `import fixtures` works under `-m unittest tests.<mod>` and
# not only under `discover -s tests`, which inserts this directory for us.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fixtures  # noqa: E402
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


class BuildTargetsTest(unittest.TestCase):
    """Which item the cost model must price to know what building a machine costs (#86).

    `resolve` returns (state, why) and throws the machine item away, so this reads
    `describe`. The evidence string is not a substitute: "craftable: mod:x" is prose for a
    human, and parsing a key back out of it would make the cost model depend on how the
    machines page words itself.
    """

    def targets(self, **kwargs):
        g = graph_with_two_routes()
        # The base fixture has no recipe producing the machine, which makes it `unavailable`
        # rather than `buildable`, and `build_targets` is only ever about buildable ones.
        g.add(Recipe("mk_machine", "t", [("mod:owned_machine", 1)],
                     [Ingredient(["mod:cheap_part"], 1)], category="minecraft.crafting"))
        return machines.build_targets(machines.describe(g, **kwargs))

    def test_a_buildable_category_names_its_machine_item(self):
        t = self.targets()
        self.assertIn("mod_owned_machine", t)
        self.assertIn("mod:owned_machine", t["mod_owned_machine"])

    def test_a_machine_you_have_is_absent_rather_than_empty(self):
        """Absent means "price this by the flat MACHINE_COST figure", which is correct here.

        Present with an empty tuple would mean "priced from a machine item" and
        `machine_entry_costs` would charge the top of the band for a machine you are standing
        next to, since no candidate would price.
        """
        t = self.targets(placed={"mod:owned_machine": 1})
        self.assertNotIn("mod_owned_machine", t)

    def test_an_unavailable_category_is_absent(self):
        # It must keep MACHINE_COST["unavailable"], whose 5,000 figure is the wall that stops
        # the solver routing through a machine with no route to it.
        t = self.targets(overrides={"mod_owned_machine": machines.UNAVAILABLE})
        self.assertNotIn("mod_owned_machine", t)

    def test_hand_crafting_is_absent(self):
        g = graph_with_two_routes()
        g.add(Recipe("hand", "t", [("mod:cheap_part", 1)],
                     [Ingredient(["mod:mountain"], 1)], category="minecraft.crafting"))
        self.assertNotIn("minecraft.crafting", machines.build_targets(machines.describe(g)))

    def test_only_the_buildable_candidates_are_returned(self):
        """A category can offer several blocks and have only some of them craftable.

        Returning a candidate that is placed or in stock would let the cheapest-candidate rule
        price a buildable category at a machine the player already owns, which is 1.0, and the
        category would then read as nearly free while still needing a machine built.
        """
        info = {"cat": {"state": machines.BUILDABLE, "candidate_states": [
            {"key": "mod:a", "state": machines.BUILDABLE, "why": "craftable: mod:a"},
            {"key": "mod:b", "state": machines.UNAVAILABLE, "why": "no route to mod:b"},
            {"key": "mod:c", "state": machines.HAVE, "why": "in stock: mod:c"},
        ]}}
        self.assertEqual(machines.build_targets(info), {"cat": ("mod:a",)})

    def test_a_buildable_category_with_no_candidate_key_is_dropped(self):
        # Rather than mapped to an empty tuple, for the same reason `have` is absent: an empty
        # tuple prices at the top of the band, which is a claim, not a missing value.
        info = {"cat": {"state": machines.BUILDABLE, "candidate_states": []}}
        self.assertEqual(machines.build_targets(info), {})

    def test_missing_keys_and_empty_input_do_not_raise(self):
        # `describe` promises `candidate_states` is always present, but this runs on every
        # category on every graph load and a KeyError here would be a 500 on the plan page.
        self.assertEqual(machines.build_targets({}), {})
        self.assertEqual(machines.build_targets(None), {})
        self.assertEqual(machines.build_targets({"c": {"state": machines.BUILDABLE}}), {})


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
                    "chickens.Laying", "chickens.Henhousing", "chickens.Breeding",
                    "beebetteratbees.beetree"):
            state, why = machines.resolve(self._graph(uid))[uid]
            self.assertEqual(state, machines.HAVE, uid)
            self.assertIn("no machine needed", why, uid)
            self.assertIn("bred, grown or laid", why,
                          "%s: distinguishable from hand-crafting in the evidence" % uid)

    def test_every_chickens_production_category_is_covered(self):
        """#33: `laying` and `henhousing` were listed and `breeding` was not.

        The list was written from the categories that were visibly wrong rather than from
        the categories that are wrong, which is how one of three from the same mod, run
        the same way, got left reading as a tool failure. Asserting on the SET rather than
        on `breeding` alone is what stops the next one being found by a user report.
        """
        chickens = {p for p in machines.NO_MACHINE_PATTERNS if p.startswith("chickens.")}
        self.assertEqual(chickens,
                         {"chickens.laying", "chickens.henhousing", "chickens.breeding"})

    def test_a_chickens_display_category_is_not_granted_no_machine_needed(self):
        # The guard on writing `chickens.` as one prefix. `drops` and `throws` are shown,
        # not made, and `index.is_non_recipe` dropping them today is not a reason for this
        # list to claim them too.
        for uid in ("chickens.Drops", "chickens.Throws"):
            self.assertFalse(machines.needs_no_machine(uid, schema=machines.SPECIES_SCHEMA),
                             uid)

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


class LegacyTileEntityIdTest(unittest.TestCase):
    """A dotted tile-entity id in the minecraft namespace is not a vanilla block (#27).

    Tinkers registers old-style DOTTED tile-entity ids, and Forge namespaces a colon-less
    registration into `minecraft:`, so the world save literally records
    `minecraft:tconstruct.smeltery_controller` for a placed Smeltery Controller while
    JEI's catalyst calls the machine `tconstruct:smeltery_controller`. The two can never
    compare equal, so a machine Jake is standing next to read as one he could build.
    """

    def test_a_placed_smeltery_controller_is_recognised(self):
        g = Graph()
        g.names = {"tconstruct:smeltery_controller": "Smeltery Controller",
                   "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="tconstruct.smeltery", machine="Smeltery"))
        g.catalysts = {"tconstruct.smeltery": ["tconstruct:smeltery_controller"]}
        state, why = machines.resolve(
            g, placed={"minecraft:tconstruct.smeltery_controller": 1})[
                "tconstruct.smeltery"]
        self.assertEqual(state, machines.HAVE)
        self.assertIn("placed", why)

    def test_the_evidence_quotes_the_id_the_world_actually_recorded(self):
        """The alias is for matching only. Evidence must stay verbatim, or it stops
        being something Jake can grep for in the save."""
        g = Graph()
        g.names = {"tconstruct:smeltery_controller": "Smeltery Controller",
                   "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="tconstruct.smeltery", machine="Smeltery"))
        g.catalysts = {"tconstruct.smeltery": ["tconstruct:smeltery_controller"]}
        _state, why = machines.resolve(
            g, placed={"minecraft:tconstruct.smeltery_controller": 1})[
                "tconstruct.smeltery"]
        self.assertIn("minecraft:tconstruct.smeltery_controller", why)

    def test_legacy_forms_are_aliased(self):
        self.assertIn("tconstruct:smeltery_controller",
                      machines.match_forms("minecraft:tconstruct.smeltery_controller"))
        self.assertIn("ironchest:diamond", machines.match_forms("minecraft:ironchest.diamond"))

    def test_the_state_suffix_is_still_stripped_from_an_aliased_id(self):
        # `minecraft:mod.machine_idle` has to reach `mod:machine`, not `mod:machine_idle`.
        self.assertIn("mod:machine", machines.match_forms("minecraft:mod.machine_idle"))

    def test_a_real_vanilla_block_is_not_re_namespaced(self):
        forms = machines.match_forms("minecraft:furnace")
        self.assertEqual(forms, ("minecraft:furnace",))

    def test_a_modded_id_that_already_has_a_namespace_is_left_alone(self):
        # `agricraft:tile.crop` is correctly namespaced already; splitting on the dot
        # would invent `tile:crop`.
        self.assertEqual(machines.match_forms("agricraft:tile.crop"),
                         ("agricraft:tile.crop",))

    def test_an_undecodable_legacy_id_yields_no_bogus_modid(self):
        """Woot registers `tile.woot_anvil`, so the save says `minecraft:tile.woot_anvil`.

        `tile` is not a modid and nothing in the id says which mod owns it, so the alias
        is `tile:woot_anvil`, which matches nothing. That is the honest outcome: 9 of the
        29 dotted ids in the reference save are this shape, and guessing a modid out of
        `woot_anvil` would be fabricating evidence. A manual override in machines.json is
        the documented answer for those.
        """
        forms = machines.match_forms("minecraft:tile.woot_anvil")
        self.assertNotIn("woot:anvil", forms)
        self.assertNotIn("woot:woot_anvil", forms)

    def test_every_candidate_carries_its_own_verdict(self):
        """One category, several machines: "smelting is done in more than just that"."""
        g = Graph()
        g.names = {"mod:controller": "Controller", "mod:drain": "Drain",
                   "mod:tank": "Tank", "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="mod.smeltery", machine="Smeltery"))
        g.add(Recipe("mk", "t", [("mod:drain", 1)], [Ingredient(["mod:part"], 1)],
                     category="minecraft.crafting"))
        g.catalysts = {"mod.smeltery": ["mod:controller", "mod:drain", "mod:tank"]}
        info = machines.describe(g, placed={"mod:controller": 1})["mod.smeltery"]
        self.assertEqual([(c["key"], c["state"]) for c in info["candidate_states"]],
                         [("mod:controller", machines.HAVE),
                          ("mod:drain", machines.BUILDABLE),
                          ("mod:tank", machines.UNAVAILABLE)])

    def test_the_category_takes_the_most_direct_evidence_not_the_first_candidate(self):
        # A block PLACED third beats one merely in stock at the top: standing next to a
        # machine is stronger evidence than owning its item.
        g = Graph()
        g.names = {"mod:a": "A", "mod:b": "B", "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="mod.cat", machine="Cat"))
        g.catalysts = {"mod.cat": ["mod:a", "mod:b"]}
        info = machines.describe(g, placed={"mod:b": 1},
                                 stock={"mod:a": 1})["mod.cat"]
        self.assertEqual(info["state"], machines.HAVE)
        self.assertIn("placed: mod:b", info["why"])

    def test_a_zero_count_is_not_a_sighting(self):
        g = Graph()
        g.names = {"mod:a": "A", "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="mod.cat", machine="Cat"))
        g.catalysts = {"mod.cat": ["mod:a"]}
        info = machines.describe(g, placed={"mod:a": 0})["mod.cat"]
        self.assertEqual(info["state"], machines.UNAVAILABLE)

    def test_a_placed_id_with_no_namespace_at_all_still_aliases(self):
        # Nothing in the save writes this today, but `normalise_block` is public and a
        # hand-written override may well say `tconstruct.smeltery_controller`.
        self.assertIn("tconstruct:smeltery_controller",
                      machines.match_forms("tconstruct.smeltery_controller"))


class CatalystVariantTest(unittest.TestCase):
    """A catalyst is a claim about an ITEM, not about one NBT state of it.

    #28: at schema 3, every crafting recipe for a machine that carries its level or
    augments in NBT outputs a discriminated key, while `catalysts.json` still names the
    bare one. Availability looked the bare key up, found nothing, and reported "no route"
    for 16 Thermal Expansion categories and 3 Botania flowers that are plainly craftable.
    Unavailable is the most expensive state in the cost model, so every plan that could
    touch those machines was distorted.
    """

    def test_a_bare_catalyst_finds_its_discriminated_variants(self):
        g = fixtures.discriminated_graph()
        self.assertEqual(g.producers(fixtures.MACHINE_BASE), [],
                         "the bare key genuinely has no recipe of its own")
        self.assertTrue(g.producers_any_variant(fixtures.MACHINE_BASE))
        self.assertEqual(list(g.variants_of(fixtures.MACHINE_BASE)),
                         [fixtures.MACHINE_VARIANT])

    def test_the_category_reads_buildable_not_unavailable(self):
        g = fixtures.discriminated_graph()
        rec = machines.describe(g)["mod.arc_furnace"]
        self.assertEqual(rec["state"], machines.BUILDABLE)
        self.assertIn(fixtures.MACHINE_BASE, rec["why"])

    def test_the_evidence_names_the_variant_that_is_actually_craftable(self):
        # Otherwise the page asserts a route to a key with no producers, and the reader
        # cannot check the claim by clicking it.
        g = fixtures.discriminated_graph()
        rec = machines.describe(g)["mod.arc_furnace"]
        self.assertIn(fixtures.MACHINE_VARIANT, rec["why"])

    def test_a_catalyst_with_no_variants_anywhere_is_still_unavailable(self):
        # The widening must not turn "genuinely unreachable" into "buildable"; three real
        # categories on the reference pack are correctly unavailable, and stay so.
        g = fixtures.discriminated_graph()
        g.catalysts = {"mod.arc_furnace": ["mod:nonexistent_machine"]}
        rec = machines.describe(g)["mod.arc_furnace"]
        self.assertEqual(rec["state"], machines.UNAVAILABLE)

    def test_producers_is_not_widened(self):
        """`producers` must keep answering the narrow question.

        The solver asks "give me exactly this stack". A machine with different augments
        is not a substitute for the one a recipe called for, so widening `producers`
        itself would let any plan satisfy an NBT-bearing ingredient with the wrong
        variant. Only the catalyst question is widened.

        THE PLAN STATUS USED TO BE ASSERTED HERE AND IS NOT ANY MORE, and the reason is the
        whole of #170. This test read `Solver(g).solve(MACHINE_BASE, 1)["tree"]["status"] ==
        "raw"` as evidence that `producers` was narrow, which conflated two claims: that a
        demand for `X#d` is not satisfied by `X` or a sibling, which is #28's refusal and is
        still in force, and that a demand for BARE `X` is not satisfied by a produced variant,
        which #170 measured as a defect over 99 keys and fixed. The bare key here is exactly
        that shape -- a JEI catalyst naming `mod:machine:1` while the only recipe makes
        `mod:machine:1#f56885268ad5` -- so the plan now routes it and says which variant it
        took. `test_unsourced.ProducedOnlyAsAVariantTest` owns both directions; what belongs
        here is the relation this file is about, which is the catalyst widening.
        """
        g = fixtures.discriminated_graph()
        self.assertEqual(g.producers(fixtures.MACHINE_BASE), [])
        self.assertEqual(g.producers(fixtures.MACHINE_VARIANT),
                         g.by_output[fixtures.MACHINE_VARIANT])

    def test_the_bare_catalyst_key_is_planned_through_its_variant(self):
        # #170, and #28's own example is one of the 99: the machines page has always said the
        # Pulverizer is craftable while a plan for the same key said NEED, because
        # `producers_any_variant` answered the page's question and nothing answered the
        # solver's. The two surfaces now agree, and the plan names the stack it planned.
        g = fixtures.discriminated_graph()
        tree = Solver(g).solve(fixtures.MACHINE_BASE, 1)["tree"]
        self.assertEqual(tree["status"], "craft")
        self.assertEqual(tree["resolved_to"], fixtures.MACHINE_VARIANT)

    def test_a_discriminated_key_does_not_widen_to_its_siblings(self):
        # Asking for one variant must not pick up another variant's recipes.
        g = fixtures.discriminated_graph()
        self.assertEqual(g.producers_any_variant(fixtures.MACHINE_VARIANT),
                         g.producers(fixtures.MACHINE_VARIANT))

    def test_the_variant_index_survives_a_recipe_being_added(self):
        # Every derived index is dropped through one `_invalidate`; a stale variant index
        # would report a machine unbuildable until the process restarted.
        g = fixtures.discriminated_graph()
        self.assertEqual(list(g.variants_of("mod:later")), [])
        g.add(Recipe("late", "t", [("mod:later#abcdef123456", 1)],
                     [Ingredient(["mod:plate"], 1)], category="crafting"))
        self.assertEqual(list(g.variants_of("mod:later")), ["mod:later#abcdef123456"])


class CatalystMetaVariantTest(unittest.TestCase):
    """A catalyst that names a metadata state the pack never crafts.

    Found auditing #33. `moarsigns.exchange` read `no route to moarsigns:sign_toolbox:4`
    while `moarsigns:sign_toolbox` has two recipes: the Sign Toolbox selects its mode with
    the damage value, so JEI catalogues the exchange mode and only meta 0 is ever made.
    One category on the reference pack, which is why the evidence names the variant
    instead of the verdict quietly widening. See `Graph.meta_sibling_made`.
    """

    TOOLBOX = "mod:toolbox"
    MODE = "mod:toolbox:4"

    def _graph(self):
        g = Graph()
        g.names = {self.TOOLBOX: "Sign Toolbox", "mod:plank": "Plank"}
        g.add(Recipe("make_toolbox", "Crafting Table", [(self.TOOLBOX, 1)],
                     [Ingredient(["mod:plank"], 4)], category="minecraft.crafting"))
        g.add(Recipe("exchange", "Sign Toolbox", [("mod:sign_b", 1)],
                     [Ingredient(["mod:sign_a"], 1)], category="mod.exchange"))
        g.catalysts = {"mod.exchange": [self.MODE]}
        return g

    def test_a_mode_meta_no_recipe_makes_reads_buildable(self):
        rec = machines.describe(self._graph())["mod.exchange"]
        self.assertEqual(rec["state"], machines.BUILDABLE)

    def test_the_evidence_names_both_the_catalyst_and_what_is_craftable(self):
        # The whole reason this widening is allowed to ship on one measured case: the
        # reader can see the two metas and judge whether they are the same object.
        # On the WHOLE string, not two assertIns: `mod:toolbox` is a substring of
        # `mod:toolbox:4`, so "the evidence mentions the craftable variant" passes against
        # the old "no route to mod:toolbox:4" and proves nothing.
        rec = machines.describe(self._graph())["mod.exchange"]
        self.assertEqual(rec["why"],
                         "craftable: %s (as %s)" % (self.MODE, self.TOOLBOX))

    def test_a_meta_the_pack_NAMED_is_never_widened(self):
        """The whole safety argument, and it was missing when this shipped.

        An unnamed meta is the dump reporting a stack STATE nobody registered as an item.
        A meta the pack DID name is its own item, and calling it craftable "as" a sibling is
        the falsehood `model.base_key`'s DO NOT forbids. Measured on the reference pack,
        without this gate the widening fired on four categories and two were false:
        `bloodmagic:ritual_diviner:2` ("[Dawn]") via `:1` ("[Dusk]"), and
        `genetics:geneticdatabase:1` ("Master Gene Database") via "Gene Database".
        """
        g = self._graph()
        g.names[self.MODE] = "Sign Toolbox [Exchange Mode]"
        self.assertIsNone(g.meta_sibling_made(self.MODE))
        self.assertEqual(machines.describe(g)["mod.exchange"]["state"],
                         machines.UNAVAILABLE)

    def test_an_unnamed_meta_is_still_widened(self):
        # The other direction, so the gate cannot be tightened into doing nothing.
        g = self._graph()
        self.assertNotIn(self.MODE, g.names)
        self.assertEqual(g.meta_sibling_made(self.MODE), self.TOOLBOX)

    def test_an_nbt_variant_beats_a_meta_sibling(self):
        """The branch order in `_candidate_verdict`, which nothing pinned.

        Splitting the block into exact -> meta -> NBT left all 596 tests green while
        reintroducing #28 on the real pack: `thermalexpansion.pulverizer` read
        "craftable: thermalexpansion:machine:1 (as thermalexpansion:machine#...)", naming a
        Redstone Furnace as the route to a Pulverizer. Asserted on the WHOLE string,
        because the shared fixture's meta sibling happens to BE its NBT variant.
        """
        g = self._graph()
        variant = self.MODE + "#f56885268ad5"
        g.add(Recipe("make_variant", "Crafting Table", [(variant, 1)],
                     [Ingredient(["mod:plank"], 4)], category="minecraft.crafting"))
        rec = machines.describe(g)["mod.exchange"]
        self.assertEqual(rec["why"], "craftable: %s (as %s)" % (self.MODE, variant))

    def test_the_sibling_pick_is_ordered_not_insertion_ordered(self):
        """`by_output` order is the order recipes came out of the dump.

        Taking element 0 meant a re-dump could silently change which sibling the machines
        page names. Plain base first, then ascending meta, NBT last.
        """
        g = self._graph()
        for key in ("mod:toolbox:9", "mod:toolbox:2"):
            g.add(Recipe("make_" + key, "Crafting Table", [(key, 1)],
                         [Ingredient(["mod:plank"], 1)], category="minecraft.crafting"))
        g.add(Recipe("make_nbt", "Crafting Table", [("mod:toolbox:2#aaaaaaaaaaaa", 1)],
                     [Ingredient(["mod:plank"], 1)], category="minecraft.crafting"))
        self.assertEqual(g.siblings_made("mod:toolbox"),
                         ["mod:toolbox", "mod:toolbox:2", "mod:toolbox:9",
                          "mod:toolbox:2#aaaaaaaaaaaa"])
        self.assertEqual(g.meta_sibling_made(self.MODE), "mod:toolbox")

    def test_a_non_item_output_is_not_filed_under_a_stem(self):
        # `item_stem` returns None for fluid/oredict/essentia keys. Filing them anyway put
        # 1,198 of them under a None key in an index whose docstring says it holds registry
        # names, waiting for a second caller to trip over.
        g = self._graph()
        g.add(Recipe("brine", "t", [("fluid:brine", 1000), ("ore:plankWood", 1)],
                     [Ingredient(["mod:plank"], 1)], category="minecraft.crafting"))
        self.assertNotIn(None, g.meta_index)
        self.assertNotIn("fluid:brine", sum(g.meta_index.values(), []))

    def test_a_registry_name_nothing_makes_is_still_unavailable(self):
        g = self._graph()
        g.catalysts = {"mod.exchange": ["mod:absent:4"]}
        rec = machines.describe(g)["mod.exchange"]
        self.assertEqual(rec["state"], machines.UNAVAILABLE)

    def test_the_exact_key_wins_when_it_has_its_own_recipe(self):
        # Only a LAST resort. A meta that is genuinely made must not be reported through
        # a sibling, or the evidence would name the wrong item.
        g = self._graph()
        g.add(Recipe("make_mode", "Crafting Table", [(self.MODE, 1)],
                     [Ingredient(["mod:plank"], 4)], category="minecraft.crafting"))
        rec = machines.describe(g)["mod.exchange"]
        self.assertEqual(rec["why"], "craftable: %s" % self.MODE)

    def test_the_solver_is_not_widened_across_metadata(self):
        """The constraint `model.base_key` records: meta separates different items.

        `tconstruct:ingots:0` is not `tconstruct:ingots:3`, and a plan allowed to
        substitute one for the other melts the wrong ingot into every molten metal.
        """
        g = self._graph()
        self.assertEqual(g.producers(self.MODE), [])
        self.assertEqual(g.producers_any_variant(self.MODE), [])
        self.assertEqual(Solver(g).solve(self.MODE, 1)["tree"]["status"], "raw")

    def test_the_meta_index_survives_a_recipe_being_added(self):
        # Same `_invalidate` contract as the variant index; a stale one reports a machine
        # unbuildable until the process restarts.
        g = self._graph()
        self.assertIsNone(g.meta_sibling_made("mod:later:2"))
        g.add(Recipe("late", "t", [("mod:later", 1)],
                     [Ingredient(["mod:plank"], 1)], category="minecraft.crafting"))
        self.assertEqual(g.meta_sibling_made("mod:later:2"), "mod:later")

    def test_a_fluid_or_oredict_key_is_never_stemmed(self):
        # `fluid:x` and `ore:x` split on `:` too, and treating the prefix as a registry
        # name would make every fluid a metadata sibling of every other.
        g = self._graph()
        g.add(Recipe("brine", "t", [("fluid:brine", 1000)],
                     [Ingredient(["mod:plank"], 1)], category="minecraft.crafting"))
        self.assertIsNone(g.meta_sibling_made("fluid:water"))
        self.assertIsNone(g.meta_sibling_made("ore:plankWood"))


class ModCrossTabTest(unittest.TestCase):
    """The machines page's two filters narrow each other off a SERVER-computed cross-tab.

    `MACHINES_JS` used to walk all 503 rendered rows and build this itself on every
    keystroke, which put a domain fact where this suite could not reach it: #16 and #32
    were both reported by a human rather than caught by a test, and #32 shipped covered
    only by a checked-in browser audit.
    """

    @staticmethod
    def _info():
        return {
            "a.one":   {"mod": "Alpha", "state": machines.HAVE},
            "a.two":   {"mod": "Alpha", "state": machines.HAVE},
            "a.three": {"mod": "Alpha", "state": machines.UNAVAILABLE},
            "b.one":   {"mod": "beta_mod", "state": machines.BUILDABLE},
            "c.one":   {"mod": "Gamma", "state": machines.HAVE},
            "c.two":   {"mod": "Gamma", "state": machines.BUILDABLE},
        }

    def test_the_cross_tab_counts_categories_per_mod_per_state(self):
        self.assertEqual(machines.mod_state_counts(self._info()), {
            "Alpha": {machines.HAVE: 2, machines.UNAVAILABLE: 1},
            "beta_mod": {machines.BUILDABLE: 1},
            "Gamma": {machines.HAVE: 1, machines.BUILDABLE: 1},
        })

    def test_a_state_a_mod_has_none_of_is_absent_rather_than_zero(self):
        # 77 mods x 4 states with explicit zeroes is a third bigger for no information;
        # the client already treats a missing entry as none.
        self.assertNotIn(machines.UNKNOWN,
                         machines.mod_state_counts(self._info())["Alpha"])

    def test_the_totals_agree_with_the_input(self):
        counts = machines.mod_state_counts(self._info())
        self.assertEqual(sum(sum(v.values()) for v in counts.values()), len(self._info()))

    def test_mods_are_ordered_by_category_count_then_by_name(self):
        counts = machines.mod_state_counts(self._info())
        self.assertEqual(machines.mod_order(counts), ["Alpha", "Gamma", "beta_mod"])

    def test_the_order_is_case_insensitive(self):
        """`aether_legacy` belongs next to `Advent of Ascension`, not after `Woot`.

        Python's `sorted` is codepoint order, which puts every lowercase modid below every
        capitalised display name. Measured on the reference pack, that disagrees with the
        browser's `localeCompare` at every one of the 77 names, which is why collation
        happens here and the client sorts on the rank this assigns.
        """
        counts = {"Zebra": {machines.HAVE: 1}, "apple": {machines.HAVE: 1},
                  "Banana": {machines.HAVE: 1}}
        self.assertEqual(machines.mod_order(counts), ["apple", "Banana", "Zebra"])

    def test_the_order_is_stable_for_equal_counts(self):
        # Two mods with the same count must not swap between requests, or the dropdown
        # reshuffles on reload for no reason the reader can see.
        counts = {"One": {machines.HAVE: 3}, "Two": {machines.BUILDABLE: 3}}
        self.assertEqual(machines.mod_order(counts), machines.mod_order(dict(counts)))

    def test_an_empty_graph_produces_an_empty_cross_tab(self):
        self.assertEqual(machines.mod_state_counts({}), {})
        self.assertEqual(machines.mod_order({}), [])


class StateTotalsTest(unittest.TestCase):
    """The per-state figures the machines page shows come out of the cross-tab.

    They used to come from `summarise` while the browser recomputed the same numbers off
    the cross-tab: two derivations of one figure, free to disagree the instant a chip was
    clicked. `summarise` survives for the CLI, which holds `resolve`'s two-tuples rather
    than `describe`'s records, so this pins the two together.
    """

    @staticmethod
    def _graph():
        g = Graph()
        g.names = {"mod:widget": "Widget", "mod:part": "Part",
                   "mod:press": "Press", "mod:absent": "Absent Machine"}
        for uid, title, machine in (
                ("minecraft.crafting", None, None),        # have, no machine needed
                ("mod.press", "Press", "mod:press"),       # unavailable, no route
                ("somemod.mystery", "Mystery Process", None),   # unknown
        ):
            g.add(Recipe("r_" + uid, "t", [("mod:widget", 1)],
                         [Ingredient(["mod:part"], 1)], category=uid, machine=title))
        return g

    def test_the_totals_match_summarise_on_the_same_graph(self):
        g = self._graph()
        info = machines.describe(g)
        states = {uid: (rec["state"], rec["why"]) for uid, rec in info.items()}
        self.assertEqual(machines.state_totals(machines.mod_state_counts(info)),
                         machines.summarise(states))

    def test_every_state_is_present_even_at_zero(self):
        # The chip row renders all four whatever the graph holds, so a missing key would be
        # a KeyError on the page rather than a zero.
        totals = machines.state_totals({"Alpha": {machines.HAVE: 2}})
        self.assertEqual(set(totals), set(machines.STATES))
        self.assertEqual(totals[machines.UNAVAILABLE], 0)

    def test_an_empty_cross_tab_totals_to_zeroes(self):
        self.assertEqual(machines.state_totals({}),
                         dict.fromkeys(machines.STATES, 0))

    def test_the_totals_agree_with_the_cross_tab_grand_total(self):
        counts = {"A": {machines.HAVE: 3, machines.BUILDABLE: 1},
                  "B": {machines.BUILDABLE: 2}}
        self.assertEqual(sum(machines.state_totals(counts).values()), 6)


if __name__ == "__main__":
    unittest.main()
