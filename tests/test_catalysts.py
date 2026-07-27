"""JEI recipe catalysts, and the no-op recipes that hunting for them exposed."""

import json
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import index, machines  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.sources import catalysts as catalysts_src  # noqa: E402
from recipegraph.sources import dump_meta, dump_names  # noqa: E402
from recipegraph.sources import hei_dump  # noqa: E402


def write(doc):
    path = os.path.join(tempfile.mkdtemp(), "catalysts.json")
    with open(path, "w") as fh:
        json.dump(doc, fh)
    return path


class LoadTest(unittest.TestCase):
    def test_plain_ids_and_metas(self):
        got = catalysts_src.load(write({
            "tconstruct.casting_table": ["tconstruct:casting_table"],
            "te.pulverizer": ["thermalexpansion:machine:2"],
            "wild": ["mod:thing:32767"],
        }))
        self.assertEqual(got["tconstruct.casting_table"], ["tconstruct:casting_table"])
        # A trailing meta must be split off before norm_key, or `machine:2` is treated as
        # an id with no meta and never matches the rest of the graph.
        self.assertEqual(got["te.pulverizer"], ["thermalexpansion:machine:2"])
        self.assertEqual(got["wild"], ["mod:thing:*"])

    def test_order_is_preserved(self):
        # JEI lists the primary machine first; that decides which name a plan shows.
        got = catalysts_src.load(write({"c": ["mod:basic", "mod:advanced", "mod:elite"]}))
        self.assertEqual(got["c"], ["mod:basic", "mod:advanced", "mod:elite"])

    def test_a_single_string_is_accepted(self):
        self.assertEqual(catalysts_src.load(write({"c": "mod:x"}))["c"], ["mod:x"])

    def test_missing_or_malformed_file_yields_nothing(self):
        self.assertEqual(catalysts_src.load("/nonexistent/catalysts.json"), {})
        path = os.path.join(tempfile.mkdtemp(), "bad.json")
        with open(path, "w") as fh:
            fh.write("[]")
        self.assertEqual(catalysts_src.load(path), {})

    def test_empty_lists_are_dropped_not_kept_as_empty(self):
        # An empty entry would look like "identified, with no candidates", which resolves
        # to UNAVAILABLE instead of UNKNOWN.
        self.assertEqual(catalysts_src.load(write({"a": [], "b": ["mod:x"]})),
                         {"b": ["mod:x"]})


class ResolveWithCatalystsTest(unittest.TestCase):
    UID = "tconstruct.smeltery"
    BLOCK = "tconstruct:smeltery_controller"

    @classmethod
    def _graph(cls):
        g = Graph()
        # The real shape of the hard case: the title is the recipe TYPE ("Smelting"), the
        # machine is a Smeltery Controller, and the uid tokenises to `tconstruct:smeltery`
        # which is not a registered item. Neither the title nor the uid fallback can get
        # there, and 343 of 521 reference-pack categories look like this.
        g.names = {cls.BLOCK: "Smeltery Controller", "mod:widget": "Widget"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category=cls.UID, machine="Smelting"))
        return g

    def test_title_matching_alone_fails_on_a_recipe_type_title(self):
        self.assertEqual(machines.resolve(self._graph())[self.UID][0], machines.UNKNOWN)

    def test_graph_catalysts_are_used_automatically(self):
        g = self._graph()
        g.catalysts = {self.UID: [self.BLOCK]}
        state, why = machines.resolve(g, placed={self.BLOCK: 1})[self.UID]
        self.assertEqual(state, machines.HAVE)
        self.assertIn("smeltery_controller", why)

    def test_an_explicit_argument_still_wins_over_the_graph(self):
        g = self._graph()
        g.catalysts = {self.UID: ["mod:wrong"]}
        info = machines.describe(g, placed={self.BLOCK: 1},
                                 catalysts={self.UID: [self.BLOCK]})
        self.assertEqual(info[self.UID]["state"], machines.HAVE)

    def test_catalysts_survive_a_graph_save_and_load(self):
        g = self._graph()
        g.catalysts = {self.UID: [self.BLOCK]}
        path = os.path.join(tempfile.mkdtemp(), "g.json")
        g.save(path)
        self.assertEqual(Graph.load(path).catalysts, g.catalysts)

    def test_a_graph_built_before_catalysts_existed_still_loads(self):
        path = os.path.join(tempfile.mkdtemp(), "old.json")
        with open(path, "w") as fh:
            json.dump({"recipes": [], "names": {}, "ore_members": {}}, fh)
        self.assertEqual(Graph.load(path).catalysts, {})


class NoOpRecipeTest(unittest.TestCase):
    """Recipes that consume as much of their output as they make.

    Each of these is a case observed in the reference pack. The two `keep` cases are false
    positives from the first, naive version of the check.
    """

    @staticmethod
    def _r(outputs, inputs):
        return Recipe("r", "t", outputs, [Ingredient(a, q) for a, q in inputs])

    def test_a_pure_no_op_is_dropped(self):
        # TechReborn Extractor: Empty Cell -> Empty Cell.
        self.assertTrue(index.produces_nothing_new(
            self._r([("tr:cell", 1)], [(["tr:cell"], 1)])))

    def test_recharging_is_dropped(self):
        # Scepter + Ender Pearl -> Scepter. Real in game, but it makes no new item.
        self.assertTrue(index.produces_nothing_new(
            self._r([("tf:scepter", 1)],
                    [(["tf:scepter"], 1), (["minecraft:ender_pearl"], 1)])))

    def test_variant_tables_are_dropped(self):
        self.assertTrue(index.produces_nothing_new(
            self._r([("c:a", 1), ("c:b", 1)],
                    [(["c:a"], 1), (["c:b"], 1), (["c:b:1"], 1)])))

    def test_an_output_hidden_among_oredict_alternatives_is_kept(self):
        # `Chest + Tripwire Hook -> Trapped Chest`: a trapped chest is one of the three
        # things the chest slot accepts, so the naive set test dropped a real recipe.
        self.assertFalse(index.produces_nothing_new(
            self._r([("minecraft:trapped_chest", 1)],
                    [(["minecraft:chest", "minecraft:trapped_chest", "mod:c"], 1),
                     (["minecraft:tripwire_hook"], 1)])))

    def test_multiplication_is_kept(self):
        # Phytogenic Insolator: 1 Spectral Fern -> 3 Spectral Fern.
        self.assertFalse(index.produces_nothing_new(
            self._r([("te:fern", 3)], [(["te:phyto"], 1), (["te:fern"], 1)])))

    def test_a_returned_catalyst_alongside_new_output_is_kept(self):
        self.assertFalse(index.produces_nothing_new(
            self._r([("m:plate", 1), ("m:mold", 1)],
                    [(["m:mold"], 1), (["m:ingot"], 1)])))

    def test_a_recipe_with_no_outputs_is_dropped(self):
        self.assertTrue(index.produces_nothing_new(self._r([], [(["x:y"], 1)])))


class InformationalCategoryTest(unittest.TestCase):
    def test_stat_tables_and_structure_previews_are_not_recipes(self):
        for cat in ("tconstruct:harvest_stats", "tconstruct:ranged_stats",
                    "modularmachinery.preview"):
            self.assertTrue(index.is_non_recipe(cat), cat)

    def test_real_categories_with_similar_names_are_kept(self):
        for cat in ("abyssalcraft.upgrade", "aoa3.upgradeKits",
                    "bioreactor_accepted_items", "nuclearcraft_crystallizer"):
            self.assertFalse(index.is_non_recipe(cat), cat)


if __name__ == "__main__":
    unittest.main()


class SpecificityTest(unittest.TestCase):
    """A generic viewer item must not become the answer to "what machine is this"."""

    def test_a_broad_catalyst_loses_to_a_purpose_built_one(self):
        # Real case: modularmachinery:itemblueprint is the catalyst for 226 categories
        # because it is what you open the recipes with. Taken in JEI's order a plan read
        # "Mythic Processor: Melter -- craftable: modularmachinery:itemblueprint". You can
        # craft a blueprint; that does not give you the machine.
        raw = {
            "mm.melter": ["mm:blueprint", "mm:melter_controller"],
            "mm.pulverizer": ["mm:blueprint", "mm:pulverizer_controller"],
            "mm.infuser": ["mm:blueprint", "mm:infuser_controller"],
        }
        ordered = machines.order_by_specificity(raw)
        self.assertEqual(ordered["mm.melter"][0], "mm:melter_controller")
        self.assertEqual(ordered["mm.pulverizer"][0], "mm:pulverizer_controller")

    def test_a_broad_catalyst_is_demoted_not_dropped(self):
        # Extra Utilities registers 19 machine types under one `extrautils2:machine` block.
        # Where it is the only candidate it still has to answer.
        raw = {"a": ["mod:generic"], "b": ["mod:generic"], "c": ["mod:generic", "mod:own"]}
        ordered = machines.order_by_specificity(raw)
        self.assertEqual(ordered["a"], ["mod:generic"])
        self.assertEqual(ordered["c"], ["mod:own", "mod:generic"])

    def test_ties_keep_jeis_original_order(self):
        # JEI lists the primary machine first, so equal breadth must not be reshuffled.
        raw = {"x": ["mod:first", "mod:second", "mod:third"]}
        self.assertEqual(machines.order_by_specificity(raw)["x"],
                         ["mod:first", "mod:second", "mod:third"])

    def test_describe_uses_the_specific_controller_as_evidence(self):
        g = Graph()
        g.names = {"mm:melter_controller": "Melter Controller", "mm:blueprint": "Blueprint",
                   "mod:widget": "Widget"}
        g.catalysts = {"mm.melter": ["mm:blueprint", "mm:melter_controller"],
                       "mm.other": ["mm:blueprint", "mm:other_controller"]}
        for uid in ("mm.melter", "mm.other"):
            g.add(Recipe("r-" + uid, "t", [("mod:widget", 1)],
                         [Ingredient(["mod:part"], 1)], category=uid, machine="X"))
        info = machines.describe(g, placed={"mm:melter_controller": 1})
        self.assertEqual(info["mm.melter"]["state"], machines.HAVE)
        self.assertIn("melter_controller", info["mm.melter"]["why"])


class DiscriminatedStackTest(unittest.TestCase):
    """#20: every bee in the pack was the same four keys, because the dump drops NBT."""

    @staticmethod
    def _recipes(lines):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "recipes.ndjson")
        with open(path, "w") as fh:
            for line in lines:
                fh.write(json.dumps(line) + "\n")
        return list(hei_dump.extract(path))

    def test_the_nbt_digest_becomes_part_of_the_key(self):
        got = self._recipes([{
            "cat": "bdew.jeibees.mutation.rootBees", "title": "Bee Breeding",
            "in": [[{"i": "forestry:bee_drone_ge", "m": 0, "c": 1,
                     "n": "a3f19c02b8d1"}]],
            "out": [{"i": "forestry:bee_princess_ge", "m": 0, "c": 1,
                     "n": "7e40bb115c92"}],
        }])
        self.assertEqual(got[0].inputs[0].alternatives,
                         ["forestry:bee_drone_ge#a3f19c02b8d1"])
        self.assertEqual(got[0].outputs, [("forestry:bee_princess_ge#7e40bb115c92", 1)])

    def test_two_species_are_two_keys_not_one(self):
        got = self._recipes([
            {"cat": "c", "in": [[{"i": "forestry:bee_drone_ge", "n": "aaaaaaaaaaaa"}]],
             "out": [{"i": "mod:comb_a", "c": 1}]},
            {"cat": "c", "in": [[{"i": "forestry:bee_drone_ge", "n": "bbbbbbbbbbbb"}]],
             "out": [{"i": "mod:comb_b", "c": 1}]},
        ])
        self.assertNotEqual(got[0].inputs[0].alternatives,
                            got[1].inputs[0].alternatives)

    def test_a_stack_with_no_discriminator_is_untouched(self):
        # An older dump has no `n` at all and must behave exactly as it did before.
        got = self._recipes([{"cat": "c", "in": [[{"i": "minecraft:stone", "m": 0}]],
                              "out": [{"i": "minecraft:stone_brick", "c": 1}]}])
        self.assertEqual(got[0].inputs[0].alternatives, ["minecraft:stone"])

    def test_a_digest_reads_as_a_variant_rather_than_line_noise(self):
        g = Graph()
        g.names = {"forestry:bee_drone_ge": "Bee Drone"}
        self.assertEqual(g.bare_name("forestry:bee_drone_ge#a3f19c02b8d1"),
                         "Bee Drone (variant a3f19c)")
        # And JEI's own name wins outright once names.json has been read.
        g.names["forestry:bee_drone_ge#a3f19c02b8d1"] = "Forest Drone"
        self.assertEqual(g.bare_name("forestry:bee_drone_ge#a3f19c02b8d1"), "Forest Drone")

    def test_an_aspect_suffix_still_reads_as_a_word(self):
        g = Graph()
        g.names = {"thaumadditions:vis_pod": "%s Vis Pod"}
        self.assertEqual(g.bare_name("thaumadditions:vis_pod#perditio"),
                         "Perditio Vis Pod")

    def test_dumped_names_load_and_a_bad_file_is_empty(self):
        d = tempfile.mkdtemp()
        path = os.path.join(d, "names.json")
        with open(path, "w") as fh:
            json.dump({"forestry:bee_drone_ge#a3f19c02b8d1": "Forest Drone",
                       "mod:blank": "  ", "mod:notastring": 7}, fh)
        self.assertEqual(dump_names.load(path),
                         {"forestry:bee_drone_ge#a3f19c02b8d1": "Forest Drone"})
        self.assertEqual(dump_names.load(os.path.join(d, "nope.json")), {})
        with open(path, "w") as fh:
            fh.write("{oh no")
        self.assertEqual(dump_names.load(path), {})


class DumpProvenanceTest(unittest.TestCase):
    """A dump has to say what wrote it; its absence was previously unknowable."""

    @staticmethod
    def _dir(summary):
        d = tempfile.mkdtemp()
        if summary is not None:
            with open(os.path.join(d, "summary.json"), "w") as fh:
                json.dump(summary, fh)
        return d

    def test_a_stamped_dump_reports_its_version_and_schema(self):
        meta = dump_meta.read(self._dir({"mod_version": "0.4.2",
                                         "schema": dump_meta.SCHEMA, "recipes": 1}))
        self.assertEqual(meta["mod_version"], "0.4.2")
        self.assertEqual(meta["schema"], dump_meta.SCHEMA)
        self.assertIn("mod 0.4.2", dump_meta.describe(meta))

    def test_an_unstamped_dump_is_inferred_as_schema_1(self):
        # Reporting None would throw away the one thing the absence tells us.
        meta = dump_meta.read(self._dir({"recipes": 1, "skipped": 0}))
        self.assertEqual(meta["schema"], 1)
        self.assertIn("re-run /recipedump", dump_meta.describe(meta))

    def test_a_missing_summary_says_so_rather_than_guessing(self):
        meta = dump_meta.read(self._dir(None))
        self.assertFalse(meta["present"])
        self.assertIn("unknown", dump_meta.describe(meta))

    def test_category_mod_names_are_read_from_the_same_summary(self):
        """#14: the right answer was in the dump all along and nothing read it."""
        d = self._dir({"schema": dump_meta.SCHEMA, "categories": {
            "foregoing_plant_gatherer": {"dumped": 12, "mod": "Industrial Foregoing"},
            "safe_nuke_meatball": {"dumped": 3, "mod": "Extreme Reactors"},
            "SoulBinder": {"dumped": 9, "mod": "enderiomachines"},
        }})
        self.assertEqual(dump_meta.category_mods(d), {
            "foregoing_plant_gatherer": "Industrial Foregoing",
            "safe_nuke_meatball": "Extreme Reactors",
            "SoulBinder": "enderiomachines",
        })

    def test_a_blank_or_missing_mod_name_is_skipped_not_stored(self):
        # An empty string would win over the uid fallback and show a nameless group.
        d = self._dir({"categories": {"a": {"mod": "  "}, "b": {"dumped": 1},
                                      "c": {"mod": "Real"}, "d": "not a dict"}})
        self.assertEqual(dump_meta.category_mods(d), {"c": "Real"})

    def test_an_old_dump_yields_no_mod_names_rather_than_raising(self):
        for doc in (None, {"schema": 1, "recipes": 4}, {"categories": ["a", "b"]}):
            self.assertEqual(dump_meta.category_mods(self._dir(doc)), {})

    def test_a_newer_schema_tells_you_to_update_the_reader(self):
        meta = dump_meta.read(self._dir({"mod_version": "9.0.0",
                                         "schema": dump_meta.SCHEMA + 1}))
        self.assertIn("update recipegraph", dump_meta.describe(meta))

    def test_a_corrupt_summary_does_not_raise(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "summary.json"), "w") as fh:
            fh.write("{not json")
        self.assertFalse(dump_meta.read(d)["present"])

    def test_the_reader_and_the_mod_agree_on_the_schema_number(self):
        # The two constants are in different languages and cannot import each other, so
        # this asserts the Java source says the same number.
        java = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "mod", "src", "main", "java", "io", "github", "jacoblasky",
            "recipedump", "DumpCommand.java")
        with open(java) as fh:
            found = re.search(r"SCHEMA\s*=\s*(\d+)", fh.read())
        self.assertIsNotNone(found, "SCHEMA constant not found in DumpCommand.java")
        self.assertEqual(int(found.group(1)), dump_meta.SCHEMA)
