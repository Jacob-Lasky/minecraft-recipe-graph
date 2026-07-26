"""JEI recipe catalysts, and the no-op recipes that hunting for them exposed."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import index, machines  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.sources import catalysts as catalysts_src  # noqa: E402


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
