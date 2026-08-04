"""A NEED leaf the graph has no route to must SAY so. #136

THE REPORTED SHAPE, in miniature. A plan for Strong Mythic Essence bottomed out on

    Strong Mythic Essence > Mildly Recursive Goo > Life Essence > Blaze Data Model (Superior)

listed as an ordinary shopping-list line beside "128 Granite". It is not ordinary: Deep Mob
Learning's tiers are reached by killing mobs in a Simulation Chamber, which is a kill counter
rather than a recipe, so no dump can carry it and the graph has no route to that tier at all.
Measured on the reference graph: 374 data-model keys, 125 with any producer, and those 125
are exactly two tiers -- the craftable fresh model and Self-Aware. The four middle tiers are
248 keys the graph cannot reach.

WHAT THIS DOES **NOT** CLAIM. It does not say "nothing produces this", which is true of
cobblestone too and would badge most of a shopping list. #136 measured every rule that keyed
on `producers == 0` alone and rejected all of them.

It claims exactly one thing: THE GRAPH CAN MAKE THIS MATERIAL, BUT NOT IN THE SHAPE BEING
ASKED FOR. That is checkable, non-obvious, and it is the sentence a reader needs in order to
distrust the line.

THE MARK IS ALSO THE PRICE, SINCE #176. #139 shipped the badge and deliberately left the
number alone, so an item the graph had proven it could not account for still seeded at
`BASE_RAW_COST` -- the cheapest value in the model, which is what won it the route in the
first place. `cost._seed` now prices this same set at `UNSOURCED_COST`, so the badge and the
routing read one predicate and cannot disagree. What is asserted here is the badge; the
ordering of the constant is `tests/test_progression.py`.

FOUR WAYS A KEY CAN BE THE WRONG SHAPE, and all four are covered, one class each:

  * an NBT STATE of a producible item -- a levelled data model, the case above.
    `TheReportedFailureTest`.
  * a PROCESSED FORM of a producible material -- the Sednanite Nugget that opened #136.
    `nuggetSednanite` and `ingotSednanite` are one material by Forge's own convention, and
    the ingot has 27 producers, so there is a specific other form to name. `ProcessedFormTest`.
  * a STORAGE BLOCK of a producible material -- the Block of Myrmitite the planner fell to
    once the nuggets were priced, and where #136 finished. `StorageBlockTest`.
  * a BARE key made only under an NBT digest -- `animus:kama_bound`, #170.
    `ProducedOnlyAsAVariantTest`.

The second clause -- that there is a specific other form to NAME -- is what every one of
them refuses to badge without, and `ScopeTest` is where that refusal is pinned.

THE FOURTH SHAPE IS NOW MOSTLY ROUTED RATHER THAN BADGED, which is why its class carries
twice as many tests as the others. The graph holds a real recipe for those keys and had merely
filed it under a key nothing asked for, so #170 plans through it and prices it; the badge
survives for the 11 of 99 where every variant is made FROM the bare key or from a sibling of
it, and for the 6 whose variants are all unreachable. `ProducedOnlyAsAVariantTest` holds both
halves and `Graph.variant_subsumption` carries the argument.

ONE PREDICATE ANSWERS ALL FOUR, and it lives on `Graph`. It used to live on `Solver` with a
hand-kept copy in `api._reachable_form`, and the processed-form shape and the produced-variant
shape were each added to one spelling and not the other, so `/api/sweep` under-reported for
both while a test comparing the two copies passed. `ThereIsOnlyOneSpellingOfThePredicateTest`
is what replaced that comparison; see it for why the assertion is structural rather than
behavioural.
`ThereIsOnlyOneSpellingOfTheSubsumptionRelationTest` is the same structural guard over #170's
own relation, which has the same two-caller shape that made `reachable_form` drift.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph import present  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

BASE = "deepmoblearning:data_model_blaze"
SUPERIOR = BASE + "#71bcc2df6d33"
GOO = "contenttweaker:mildly_recursive_goo"


def _graph():
    """The Blaze Data Model shape: a craftable base, and a tier nothing reaches."""
    g = Graph()
    g.names = {BASE: "Data Model Blaze", SUPERIOR: "Blaze Data Model (Superior)",
               GOO: "Mildly Recursive Goo", "minecraft:cobblestone": "Cobblestone",
               "mod:blank": "Blank Data Model"}
    # The fresh model is craftable; the Superior tier is not, because levelling is a kill
    # counter rather than a recipe.
    g.add(Recipe("r-base", "test", [(BASE, 1)], [Ingredient(["mod:blank"], 1)]))
    # The thing a plan wants, resting on the tier AND on an ordinary raw leaf.
    g.add(Recipe("r-goo", "test", [(GOO, 1)],
                 [Ingredient([SUPERIOR], 1), Ingredient(["minecraft:cobblestone"], 4)]))
    return g


class TheReportedFailureTest(unittest.TestCase):
    def setUp(self):
        self.tree = Solver(_graph()).solve(GOO, 1)["tree"]
        self.leaves = {}

        def walk(n):
            self.leaves[n["key"]] = n
            for c in n.get("children") or ():
                walk(c)
        walk(self.tree)

    def test_the_unreachable_tier_is_marked(self):
        """The assertion that fails before the fix and passes after."""
        node = self.leaves[SUPERIOR]
        self.assertEqual(node["status"], "raw")
        self.assertTrue(node.get("unsourced"),
                        "the Superior tier is a state no recipe reaches, and the plan "
                        "presents it as an ordinary thing to go and get")

    def test_an_ordinary_raw_leaf_is_NOT_marked(self):
        """The half that keeps the badge worth reading.

        Cobblestone has no producer either. Marking it would badge most of a shopping list
        and the mark would stop carrying information -- which is the failure mode #136
        measured for every rule that keyed on "producers == 0" alone.
        """
        self.assertFalse(self.leaves["minecraft:cobblestone"].get("unsourced"))

    def test_the_note_names_the_form_that_IS_reachable(self):
        # "no known source" alone leaves the reader nowhere. Naming the base is what makes
        # it actionable: the fresh model is craftable, the tier is not.
        self.assertIn("Data Model Blaze", self.leaves[SUPERIOR].get("note", ""))

    def test_the_shopping_list_row_carries_it_too(self):
        # The tree is the diagnosis and the shopping list is what gets acted on. A mark on
        # only one of them is a mark on the surface nobody reads while gathering.
        row = [r for r in Solver(_graph()).solve(GOO, 1)["shopping_list"]
               if r["key"] == SUPERIOR]
        self.assertEqual(len(row), 1)
        self.assertTrue(row[0].get("unsourced"))


class ScopeTest(unittest.TestCase):
    """The mark fires on a STATE of a producible item, and on nothing else."""

    def test_a_plain_key_with_no_producer_and_no_family_is_not_marked(self):
        # THE COBBLESTONE BOUNDARY, and the reason the mark stays narrow. This key has no
        # oredict registration at all, so the pack says nothing about what material it is a
        # shape of and there is no other form to point a reader at. Marking it would collapse
        # to "no recipe", which the NEED badge already says.
        #
        # This test used to stand for the Sednanite Nugget being out of scope. It no longer
        # does: a nugget in a `<form><Material>` group WITH a makeable sibling is now marked,
        # and `ProcessedFormTest` below covers it. What survives here is the case that has no
        # family, which is still correctly silent.
        g = Graph()
        g.names = {"mod:nugget": "Sednanite Nugget", "mod:thing": "Thing"}
        g.add(Recipe("r", "test", [("mod:thing", 1)], [Ingredient(["mod:nugget"], 9)]))
        tree = Solver(g).solve("mod:thing", 1)["tree"]
        leaf = tree["children"][0]
        self.assertEqual(leaf["key"], "mod:nugget")
        self.assertFalse(leaf.get("unsourced"))

    def test_a_variant_whose_base_is_also_unreachable_is_not_marked(self):
        # Nothing to point the reader at, so the mark would be noise: "we cannot make this,
        # and we cannot make the plain one either" is just "no recipe", which NEED says.
        g = Graph()
        g.names = {"mod:x": "X", "mod:x#aa": "X (odd)", "mod:out": "Out"}
        g.add(Recipe("r", "test", [("mod:out", 1)], [Ingredient(["mod:x#aa"], 1)]))
        tree = Solver(g).solve("mod:out", 1)["tree"]
        self.assertFalse(tree["children"][0].get("unsourced"))

    def test_stock_wins_over_the_mark(self):
        # Holding one is decisive evidence you can have it, whatever the graph knows about
        # recipes. `take` returns before the raw branch is ever reached.
        g = _graph()
        tree = Solver(g, have={SUPERIOR: 5}).solve(GOO, 1)["tree"]
        node = [c for c in tree["children"] if c["key"] == SUPERIOR][0]
        self.assertEqual(node["status"], "have")
        self.assertFalse(node.get("unsourced"))

    def test_a_token_wins_over_the_mark(self):
        # A pack placeholder is already an instruction with its own badge and its own list.
        # Two marks on one row would be two answers to "what do I do with this".
        g = _graph()
        tree = Solver(g, token_kinds={SUPERIOR: "loot"}).solve(GOO, 1)["tree"]
        node = [c for c in tree["children"] if c["key"] == SUPERIOR][0]
        self.assertEqual(node["status"], "token")
        self.assertFalse(node.get("unsourced"))


class PresentationTest(unittest.TestCase):
    def test_the_badge_is_distinct_from_a_plain_need(self):
        plain = present.status_badge("raw")
        marked = present.status_badge("raw", unsourced=True)
        self.assertNotEqual(plain[0], marked[0])
        self.assertIn("no known source", marked[0].lower())

    def test_it_keeps_the_need_colour(self):
        # It is still something you have to obtain; what changed is that the tool cannot say
        # how. A new colour would imply a new KIND of row.
        self.assertEqual(present.status_badge("raw")[1],
                         present.status_badge("raw", unsourced=True)[1])

    def test_a_token_kind_still_wins_the_word(self):
        # `unsourced` never reaches a token node (ScopeTest pins that), but the refinement
        # order has to be unambiguous where both are passed.
        text, _cls = present.status_badge("token", token_kind="loot", unsourced=True)
        self.assertNotIn("no known source", text.lower())


class ProcessedFormTest(unittest.TestCase):
    """A shape of a material the pack does not make. The #136 half that was left open.

    The reported plan asked for 18 Sednanite Nuggets. Nothing in the pack makes one -- there
    is a nugget-to-ingot recipe and no ingot-to-nugget, confirmed against the dump -- so the
    shopping list named a step that cannot be performed. The ingot, by contrast, has 27
    producers. Forge's convention makes `nuggetSednanite` and `ingotSednanite` one material,
    which is the signal that was missing when this was first declared out of scope.
    """

    @staticmethod
    def _graph(nugget_group="nuggetSednanite", ingot_producible=True):
        g = Graph()
        g.names = {"mod:nugget": "Sednanite Nugget", "mod:ingot": "Sednanite Ingot",
                   "mod:ore": "Sednanite Ore", "mod:out": "Sednanite Block"}
        g.ore_members = {nugget_group: ["mod:nugget"], "ingotSednanite": ["mod:ingot"]}
        # The plan reaches the nugget: nine of them make the thing being planned.
        g.add(Recipe("r1", "test", [("mod:out", 1)], [Ingredient(["mod:nugget"], 9)]))
        if ingot_producible:
            g.add(Recipe("r2", "test", [("mod:ingot", 1)], [Ingredient(["mod:ore"], 1)]))
        return g

    def _leaf(self, g):
        tree = Solver(g).solve("mod:out", 1)["tree"]
        return tree["children"][0]

    def test_the_unmakeable_nugget_is_marked(self):
        leaf = self._leaf(self._graph())
        self.assertEqual(leaf["key"], "mod:nugget")
        self.assertTrue(leaf.get("unsourced"))

    def test_the_note_names_the_form_that_IS_makeable(self):
        # The point of the second clause: a reader gets somewhere to go, not just a warning.
        self.assertIn("Sednanite Ingot", self._leaf(self._graph())["note"])

    def test_the_wording_says_FORM_rather_than_STATE(self):
        # A state means "you have the item, this tier is out of reach"; a form means "this
        # shape is not made, use the other one". One sentence for both would make the second
        # read as though levelling were involved.
        note = self._leaf(self._graph())["note"]
        self.assertIn("nothing makes this form", note)
        self.assertNotIn("reaches this state", note)

    def test_a_family_with_nothing_makeable_is_NOT_marked(self):
        # Same refusal the NBT half makes: with no obtainable sibling there is nothing to
        # name, and the mark degenerates into "no recipe".
        leaf = self._leaf(self._graph(ingot_producible=False))
        self.assertFalse(leaf.get("unsourced"))

    def test_a_group_that_is_not_a_form_is_NOT_marked(self):
        # `ore*` is deliberately absent from PROCESSED_FORM_PREFIXES: an ore is the
        # obtainable end of a family, and a key registered only as one is something you mine.
        leaf = self._leaf(self._graph(nugget_group="oreSednanite"))
        self.assertFalse(leaf.get("unsourced"))

    def test_a_different_material_is_not_a_sibling(self):
        # The family link is the MATERIAL, not the form. An unmakeable Sednanite Nugget must
        # not be excused by a perfectly makeable Iron Ingot.
        g = self._graph(ingot_producible=False)
        g.names["mod:iron"] = "Iron Ingot"
        g.ore_members["ingotIron"] = ["mod:iron"]
        g.add(Recipe("r3", "test", [("mod:iron", 1)], [Ingredient(["mod:ore"], 1)]))
        self.assertFalse(self._leaf(g).get("unsourced"))

    def test_a_WILDCARD_meta_is_never_marked(self):
        # `Graph.producers` gathers `base:*` for a concrete meta and never the reverse, so a
        # wildcard key is producerless by construction and its count is evidence of nothing.
        # The first regeneration of the fixtures badged `natura:sticks:*` and told the reader
        # to use Sawdust; the concrete metas are ordinary craftable sticks.
        g = self._graph(ingot_producible=True)
        g.names["mod:nugget:*"] = "Sednanite Nugget (*)"
        g.ore_members["nuggetSednanite"].append("mod:nugget:*")
        g.add(Recipe("r5", "test", [("mod:out2", 1)], [Ingredient(["mod:nugget:*"], 1)]))
        g.names["mod:out2"] = "Other"
        leaf = Solver(g).solve("mod:out2", 1)["tree"]["children"][0]
        self.assertEqual(leaf["key"], "mod:nugget:*")
        self.assertFalse(leaf.get("unsourced"))

    def test_a_makeable_form_is_never_marked(self):
        # The first clause still applies: having a producer settles it, family or no family.
        g = self._graph()
        g.add(Recipe("r4", "test", [("mod:nugget", 9)], [Ingredient(["mod:ingot"], 1)]))
        self.assertFalse(self._leaf(g).get("unsourced"))

    def test_the_sibling_choice_is_deterministic(self):
        # It reaches a plan tree, and `tests/fixtures/plan/*.json` freezes those for the Java
        # port -- so "which sibling gets named" cannot depend on dict order. Most producers
        # wins, then the key, which also makes it the form the pack actually makes.
        g = self._graph()
        g.names["mod:plate"] = "Sednanite Plate"
        g.ore_members["plateSednanite"] = ["mod:plate"]
        for rid in ("p1", "p2"):
            g.add(Recipe(rid, "test", [("mod:plate", 1)], [Ingredient(["mod:ore"], 1)]))
        for _ in range(20):
            self.assertEqual("mod:plate", Graph.obtainable_sibling(g, "mod:nugget"))


class StorageBlockTest(unittest.TestCase):
    """A `block<Material>` nobody can press. The #136 shape one denomination the other way.

    #176 priced the processed forms nothing makes, which killed the nugget ladder #136
    reported -- and the planner fell the other way. Measured on the reference graph, a plan
    for Myrmitite Ingot came out as

        Myrmitite Ingot  <- Molten Myrmitite  <- 1 Block of Myrmitite   [raw]

    and a Block of Myrmitite has no producer, no stock and no world generation. It is the
    reported defect with the denomination reversed: nine ingots pressed into one block is
    production, so a block nobody can press is as unobtainable as a nugget nobody can split.

    THE SECOND CLAUSE IS WHAT MAKES THIS SAFE, and the two refusal tests below are where that
    is pinned rather than asserted in prose. On the reference graph 50 producerless keys
    priced at a raw leaf carry a `block*` group and no processed one; 36 are excluded and 14
    are badged. The 36 break down as 19 world glasses whose `Glass` family has no producible
    member, 15 wildcard metas, and 2 materials registered in no processed shape at all. See
    `model.storage_form_material` for the full census.
    """

    @staticmethod
    def _graph(block_group="blockMyrmitite", ingot_producible=True):
        g = Graph()
        g.names = {"mod:block": "Block of Myrmitite", "mod:ingot": "Myrmitite Ingot",
                   "mod:ore": "Myrmitite Ore", "mod:out": "Molten Myrmitite"}
        g.ore_members = {block_group: ["mod:block"], "ingotMyrmitite": ["mod:ingot"]}
        # The plan reaches the block: the melter takes one and gives the fluid.
        g.add(Recipe("r1", "test", [("mod:out", 1)], [Ingredient(["mod:block"], 1)]))
        if ingot_producible:
            g.add(Recipe("r2", "test", [("mod:ingot", 1)], [Ingredient(["mod:ore"], 1)]))
        return g

    def _leaf(self, g, target="mod:out"):
        return Solver(g).solve(target, 1)["tree"]["children"][0]

    def test_the_unpressable_storage_block_is_marked(self):
        leaf = self._leaf(self._graph())
        self.assertEqual(leaf["key"], "mod:block")
        self.assertTrue(leaf.get("unsourced"))

    def test_the_note_names_the_form_that_IS_makeable(self):
        self.assertIn("Myrmitite Ingot", self._leaf(self._graph())["note"])

    def test_the_wording_says_FORM(self):
        # A storage block is a shape of the material, so it gets the FORM sentence rather
        # than the STATE one. Nothing about levelling is involved.
        self.assertIn("nothing makes this form", self._leaf(self._graph())["note"])

    @staticmethod
    def _world_glass():
        """A world block and an ordinary pane, both members of `blockGlass`.

        A FRESH GRAPH PER ARM, because `material_forms` is cached on the instance and the
        widening test below moves the list the cache was built from.
        """
        g = Graph()
        g.names = {"mod:quicksoil": "Quicksoil Glass", "mod:pane": "Glass Pane",
                   "mod:sand": "Sand", "mod:out": "Something"}
        g.ore_members = {"blockGlass": ["mod:quicksoil", "mod:pane"]}
        g.add(Recipe("r1", "test", [("mod:out", 1)], [Ingredient(["mod:quicksoil"], 1)]))
        g.add(Recipe("r2", "test", [("mod:pane", 1)], [Ingredient(["mod:sand"], 1)]))
        return g

    def test_a_world_block_whose_material_has_no_processed_form_is_NOT_marked(self):
        # THE 35. `blockGlass` names a material the pack registers in no processed shape, so
        # there is nothing to point a reader at and Quicksoil Glass stays an ordinary NEED --
        # which is right, because you mine it in the Aether.
        self.assertFalse(self._leaf(self._world_glass()).get("unsourced"))

    def test_admitting_block_to_the_naming_list_would_break_the_glass_family(self):
        # WHY THE TWO LISTS ARE SEPARATE, stated as a test rather than only as a comment.
        # With `block` in PROCESSED_FORM_PREFIXES the Glass family gains a producible member
        # -- the ordinary pane above -- and the world glass beside it is suddenly badged and
        # repriced. On the reference graph that is 20 keys, each consumed by ~320 recipes.
        from recipegraph import model
        original = model.PROCESSED_FORM_PREFIXES
        try:
            model.PROCESSED_FORM_PREFIXES = original + ("block",)
            widened = Graph.reachable_form(self._world_glass(), "mod:quicksoil")
        finally:
            model.PROCESSED_FORM_PREFIXES = original
        self.assertEqual("mod:pane", widened)
        self.assertIsNone(Graph.reachable_form(self._world_glass(), "mod:quicksoil"))

    def test_a_family_with_nothing_makeable_is_NOT_marked(self):
        # Same refusal every other shape makes: with no obtainable sibling the mark collapses
        # to "no recipe", which the NEED badge already says.
        self.assertFalse(self._leaf(self._graph(ingot_producible=False)).get("unsourced"))

    def test_a_world_ore_group_is_still_not_a_form(self):
        # `oreMyrmitite` is the obtainable end of the family. A key registered only as one is
        # something you mine, and the storage split must not have widened that.
        self.assertFalse(self._leaf(self._graph(block_group="oreMyrmitite")).get("unsourced"))

    def test_a_key_registered_as_BOTH_an_ore_and_a_block_is_not_marked(self):
        # THE CEILING `cost._seed` PROMISES FOR AN ORE. Mining is what a world ore costs at
        # worst, and the unsourced seed runs after that loop and only raises -- so badging one
        # would price it above its own ceiling, and `Solver.expand` returns "mined, not
        # crafted" before this mark is consulted, so the badge would appear only in a sweep.
        # 0 keys on the reference graph carry both groups; this is what keeps that true.
        g = self._graph()
        g.ore_members["oreMyrmitite"] = ["mod:block"]
        self.assertIsNone(Graph.reachable_form(g, "mod:block"))
        self.assertFalse(self._leaf(g).get("unsourced"))

    def test_the_named_form_is_never_a_block(self):
        # The naming set is unchanged, so a decorative block can never be the answer however
        # many producers it has. That is the half of the `block` exclusion that still stands.
        g = self._graph()
        g.names["mod:decorative"] = "Chiselled Myrmitite"
        g.ore_members["blockMyrmitite"].append("mod:decorative")
        for rid in ("d1", "d2", "d3"):
            g.add(Recipe(rid, "test", [("mod:decorative", 1)], [Ingredient(["mod:ore"], 1)]))
        self.assertEqual("mod:ingot", Graph.obtainable_sibling(g, "mod:block"))

    def test_a_processed_group_wins_over_a_storage_one(self):
        # A key in both `blockX` and `dustY` resolves through the processed group, so nothing
        # that answered before the widening answers differently now.
        g = self._graph()
        g.names["mod:dust"] = "Sednanite Dust"
        g.ore_members["dustSednanite"] = ["mod:block", "mod:dust"]
        g.add(Recipe("r3", "test", [("mod:dust", 1)], [Ingredient(["mod:ore"], 1)]))
        self.assertEqual("mod:dust", Graph.obtainable_sibling(g, "mod:block"))

    def test_it_is_priced_as_unsourced_rather_than_as_a_raw_leaf(self):
        # THE HALF THAT MOVES A PLAN. The badge alone left the block at `BASE_RAW_COST`, the
        # cheapest value in the model, so the melter route through it beat mining the ore.
        table = cost_mod.estimate(self._graph())
        self.assertEqual(cost_mod.UNSOURCED_COST, table["mod:block"])
        self.assertGreater(table["mod:block"], cost_mod.BASE_RAW_COST)

    def test_the_formula_version_moved(self):
        # A WARM `.cost-cache.json` MUST NOT SURVIVE THIS, and the same assertion guards #61's,
        # #93's, #112's and #176's price moves for the same reason. Measured before the bump: the
        # two trees produced an IDENTICAL fingerprint while 10,810 prices differed, and a cache
        # written by the earlier one was served to the later one in 0.1s with the 14 fixed keys
        # back at 1.0. `assertGreaterEqual` rather than equality on purpose, so a later bump does
        # not have to come back and edit this line.
        self.assertGreaterEqual(cost_mod.FORMULA_VERSION, 11)


class TheFormListIsTheSameInBothLanguagesTest(unittest.TestCase):
    """`PROCESSED_FORM_PREFIXES` decides who gets marked, so the two copies must agree.

    WHY A SOURCE-TEXT TEST RATHER THAN TRUST. The list decides which keys carry the
    `unsourced` field, that field is compared node for node by `PlanFixtureTest`, and the
    fixtures are only regenerated deliberately -- so a prefix added on one side and not the
    other is a failing golden gate with no behavioural change to point at, discovered by
    whoever next regenerates rather than by whoever caused it. `test_nbt_digest` pins the
    digest constants across the same seam for the same reason, and needs no JVM either.
    """

    JAVA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "mod", "src", "main", "java", "io", "github", "jacoblasky",
                        "recipedump", "graph", "Keys.java")

    def _java_list(self):
        with open(self.JAVA) as fh:
            src = fh.read()
        start = src.index("PROCESSED_FORM_PREFIXES = {")
        body = src[start:src.index("};", start)]
        return [chunk.split('"')[1] for chunk in body.split(",") if '"' in chunk]

    def test_both_languages_list_the_same_forms_in_the_same_order(self):
        from recipegraph.model import PROCESSED_FORM_PREFIXES
        self.assertEqual(list(PROCESSED_FORM_PREFIXES), self._java_list())

    def test_neither_list_admits_ore_or_block(self):
        # Both absences are load-bearing and both are argued at the declaration: `ore` is the
        # OBTAINABLE end of a family, so admitting it would let a family be named by the very
        # thing that is out of reach; `block` would readmit `chisel:diamond` through
        # `blockDiamond`, which is the decorative-block cluster #61 demoted.
        #
        # `block` IS READ ELSEWHERE, and `StorageBlockTest` is where. Keeping it out of THIS
        # list is what stops the widening from badging 20 world glasses; see
        # `test_admitting_block_to_the_naming_list_would_break_the_glass_family`.
        from recipegraph.model import PROCESSED_FORM_PREFIXES
        for forms in (list(PROCESSED_FORM_PREFIXES), self._java_list()):
            self.assertNotIn("ore", forms)
            self.assertNotIn("block", forms)

    def test_both_languages_spell_the_storage_prefix_the_same_way(self):
        # Same seam as the list above and the same failure mode: it decides which keys carry
        # the `unsourced` field, and `PlanFixtureTest` compares that field node for node.
        from recipegraph.model import STORAGE_FORM_PREFIX
        with open(self.JAVA) as fh:
            src = fh.read()
        self.assertIn('STORAGE_FORM_PREFIX = "%s"' % STORAGE_FORM_PREFIX, src)

    def test_the_split_agrees_with_the_convention(self):
        from recipegraph.model import split_ore_group
        self.assertEqual(("nugget", "Sednanite"), split_ore_group("nuggetSednanite"))
        self.assertEqual(("ingot", "Iron"), split_ore_group("ingotIron"))
        # An `ore*` group is not a processed form, so it does not split -- which is what
        # keeps a mined thing from being described as a shape of something else.
        self.assertIsNone(split_ore_group("oreSednanite"))
        # A bare form name has no material after it and names nothing.
        self.assertIsNone(split_ore_group("dust"))
        self.assertIsNone(split_ore_group("plankWood".replace("plank", "zzz")))

    def test_the_storage_split_agrees_with_the_convention(self):
        from recipegraph.model import storage_form_material
        self.assertEqual("Myrmitite", storage_form_material("blockMyrmitite"))
        self.assertEqual("Iridium", storage_form_material("blockIridium"))
        # Not a storage group, so a nugget keeps being answered by the processed-form split.
        self.assertIsNone(storage_form_material("nuggetSednanite"))
        # A bare form name names no material, exactly as `split_ore_group` refuses `dust`.
        self.assertIsNone(storage_form_material("block"))


class TheWordingsAreTheSameInBothLanguagesTest(unittest.TestCase):
    """The four sentences a reader acts on, pinned across the seam as TEXT.

    WHY A SOURCE-TEXT TEST AND NOT A FIXTURE. Three of these were gated by
    `PlanFixtureTest` comparing plan trees node for node, which worked only while some fixture
    happened to contain each wording. #170 routed `plan-unsourced-variant`'s key, so the plan
    set no longer reaches the VARIANT wording at all -- the two languages could disagree about
    it with every test green, discovered by whoever next met one of the 11 refused keys in
    game. A regex over both sources answers it without a JVM, which is what `test_nbt_digest`
    does for the digest constants and `NodeStatusTest` does for the badge words.

    THE SUBSTITUTION NOTE IS HERE FOR THE OPPOSITE REASON: it IS in a fixture today, and it is
    one string in two languages, which is the pair that drifts when someone rewords one side.
    """

    JAVA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "mod", "src", "main", "java", "io", "github", "jacoblasky",
                        "recipedump", "plan", "Solver.java")

    def _java(self):
        with open(self.JAVA) as fh:
            return fh.read()

    def _python(self):
        path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph", "solve.py")
        with open(path) as fh:
            return fh.read()

    def test_the_three_unsourced_wordings_match(self):
        for wording in ("no recipe reaches this state; the graph can only make ",
                        "nothing makes this exact item; the graph makes ",
                        "nothing makes this form; the graph can only make "):
            self.assertIn(wording, self._java(),
                          "Java's unsourcedNote no longer says %r" % wording)
            # Python interpolates the name, so the sentence is the prefix up to the `%s`.
            self.assertIn(wording.rstrip(), self._python(),
                          "python's _unsourced_note no longer says %r" % wording)

    def test_the_substitution_note_matches(self):
        wording = "nothing makes this exact item; planned as "
        self.assertIn(wording, self._java())
        self.assertIn(wording.rstrip() + " %s", self._python())

    def test_the_two_exact_item_sentences_stay_distinguishable(self):
        # They share a prefix and make DIFFERENT claims: the graph makes it and cannot get you
        # one, against the graph made it and this is which stack. A reader acts on the
        # difference, so the reporting path must not emit the routing sentence. Asserted
        # through the solver rather than by comparing two literals, which would be a test that
        # cannot fail.
        marked = ProducedOnlyAsAVariantTest._leaf(
            ProducedOnlyAsAVariantTest._graph(variant_from_bare=True))
        self.assertIn("nothing makes this exact item; the graph makes", marked["note"])
        self.assertNotIn("planned as", marked["note"])
        routed = ProducedOnlyAsAVariantTest._leaf(
            ProducedOnlyAsAVariantTest._graph())
        self.assertIn("nothing makes this exact item; planned as", routed["note"])
        self.assertNotIn("the graph makes", routed["note"])


class ProducedOnlyAsAVariantTest(unittest.TestCase):
    """The bare key nothing makes, while an NBT variant of it IS made. #170.

    Reported as "`animus:kama_bound` just says need, but the way it is made is via the
    alchemy array". The recipe is in the dump and the graph has it -- filed under
    `animus:kama_bound#fd1adc426e12`, while four recipes ask for the bare key. So the graph
    knows a 60.0 route, `cost._seed` found no producer, and the plan reported an item the
    graph can make as one it cannot. 99 bare keys are in that state on the reference graph.

    REPORTED BY #178, ROUTED AND REPRICED BY #170, and those are two behaviours in one class
    because the second is only correct where the first stops being enough. `expand` now
    offers the variant's recipes as candidates and `cost._relax` prices the bare key at what
    making the variant costs; where that route cannot be taken -- the variant is made from the
    bare key, or from a sibling variant, or is unreachable -- the mark is still all there is
    to say, and the tests below hold both halves.

    THE #28 CONSTRAINT IS NOT RELAXED BY ANY OF THIS, and
    `test_the_reverse_direction_is_still_refused` is where that is pinned. #28 refused to let
    a demand for `X#d` be satisfied by producing bare `X` or a sibling variant, on the
    grounds that "the solver asks for exactly this stack". That direction is still refused
    and `Graph.producers` is untouched. What shipped is the opposite direction, where the
    demanding slot named no NBT at all.
    """

    BARE = "mod:kama_bound"
    MADE = "mod:kama_bound#fd1adc426e12"

    @staticmethod
    def _graph(variant_producible=True, variant_from_bare=False):
        g = Graph()
        g.names = {ProducedOnlyAsAVariantTest.BARE: "Bound Khopesh",
                   ProducedOnlyAsAVariantTest.MADE: "Bound Khopesh",
                   "mod:reagent": "Binding Reagent", "mod:out": "Ritual Output"}
        # Four recipes ask for the BARE key, as the report describes.
        g.add(Recipe("use", "test", [("mod:out", 1)],
                     [Ingredient([ProducedOnlyAsAVariantTest.BARE], 1)]))
        if variant_producible:
            reagent = (ProducedOnlyAsAVariantTest.BARE if variant_from_bare
                       else "mod:reagent")
            g.add(Recipe("array", "bloodmagic:alchemyArray",
                         [(ProducedOnlyAsAVariantTest.MADE, 1)],
                         [Ingredient([reagent], 1)]))
        return g

    @staticmethod
    def _leaf(g, **kwargs):
        return Solver(g, **kwargs).solve("mod:out", 1)["tree"]["children"][0]

    def test_the_bare_demand_is_routed_through_the_variants_recipe(self):
        leaf = self._leaf(self._graph())
        self.assertEqual(leaf["key"], self.BARE)
        self.assertEqual(leaf["status"], "craft")
        self.assertEqual(leaf["recipe"], "array")
        # The plan reaches the reagent the array actually consumes, which is the whole of
        # what the report asked for.
        self.assertEqual([c["key"] for c in leaf["children"]], ["mod:reagent"])

    def test_the_substitution_is_named_rather_than_silent(self):
        # The premise -- that a slot naming the bare item accepts any variant -- cannot be
        # proved from the dump for a mod machine category, so a reader has to be able to see
        # the swap and pin something else. `Graph.variant_subsumption` carries the argument.
        leaf = self._leaf(self._graph())
        self.assertEqual(leaf["resolved_to"], self.MADE)
        self.assertIn("nothing makes this exact item", leaf["note"])
        # THE KEY, NOT THE LABEL. Both keys here are named "Bound Khopesh", which is the
        # common case on the reference graph -- 68 of the 99 -- so a note quoting the label
        # would name nothing.
        self.assertIn(self.MADE, leaf["note"])

    def test_the_runs_are_counted_off_the_variants_own_yield(self):
        # `_build` reads `per_run` out of the recipe's outputs, and a substituted recipe does
        # not output the bare key at all: read against the bare key it falls back to 1 and
        # plans four runs of a recipe that makes four.
        g = self._graph(variant_producible=False)
        g.add(Recipe("array", "bloodmagic:alchemyArray", [(self.MADE, 4)],
                     [Ingredient(["mod:reagent"], 1)]))
        leaf = Solver(g).solve(self.BARE, 8)["tree"]
        self.assertEqual(leaf["per_run"], 4)
        self.assertEqual(leaf["runs"], 2)

    def test_with_no_producible_variant_it_is_a_marked_leaf(self):
        # Nothing to route through and nothing to name, so the NEED badge is the answer and
        # the mark does not fire either -- the same refusal as the other two faces.
        leaf = self._leaf(self._graph(variant_producible=False))
        self.assertEqual(leaf["status"], "raw")
        self.assertFalse(leaf.get("unsourced"))

    def test_a_variant_made_FROM_the_bare_key_is_refused_and_still_marked(self):
        # `X -> X#d -> X`: the Ender IO Soul Binder shape, 9 keys on the reference graph.
        # Routing through it would price "get one" at what "get one and upgrade it" costs, so
        # the route is refused and the report is what is left.
        g = self._graph(variant_from_bare=True)
        leaf = self._leaf(g)
        self.assertEqual(leaf["status"], "raw")
        self.assertTrue(leaf.get("unsourced"))
        self.assertEqual(g.satisfying_variants(self.BARE), [])
        # And the report still names the variant, because the graph does make it.
        self.assertIn("nothing makes this exact item", leaf["note"])
        self.assertNotIn("this form", leaf["note"])

    def test_a_variant_made_from_a_SIBLING_variant_is_refused(self):
        # The container gate, one hop longer than the clause above.
        # `thermalexpansion:reservoir:32000` is the measured case: one recipe demands the bare
        # key directly and both produced variants are Fluid Transposer output made from the
        # family, so without this half it prices 521.0 against the 2,000 floor. The shape is
        # clearest on `extratrees:drink`, a Beer Mug whose 30 variants are all filled mugs made
        # from an EMPTY mug variant, but that key has no direct consumer and no plan moves for
        # it -- see `Graph.variant_subsumption`, which keeps the two apart because an earlier
        # version of this comment conflated them.
        empty = "mod:kama_bound#eeeeeeeeeeee"
        g = self._graph(variant_producible=False)
        g.add(Recipe("fill", "mod.transposer", [(self.MADE, 1)],
                     [Ingredient([empty], 1)]))
        g.add(Recipe("empty", "mod.transposer", [(empty, 1)],
                     [Ingredient([self.BARE], 1)]))
        self.assertEqual(g.satisfying_variants(self.BARE), [])
        self.assertEqual(self._leaf(g)["status"], "raw")

    def test_an_unreachable_variant_is_not_offered_as_a_route(self):
        # 6 of the 99 have no variant the cost table can reach. Offering one trades a
        # one-line "no known source" for a subtree bottoming out on the same dead end, while
        # the price stays at UNSOURCED_COST because `_relax` cannot lower through infinity --
        # a route the price does not reflect, which is #176's defect at a lower magnitude.
        g = self._graph()
        costs = {self.MADE: float("inf")}
        leaf = self._leaf(g, costs=costs)
        self.assertEqual(leaf["status"], "raw")
        self.assertTrue(leaf.get("unsourced"))

    def test_the_reverse_direction_is_still_refused(self):
        # THE #28 CONSTRAINT. A slot naming a specific variant is matched by that stack and
        # not by the bare item, so producing the bare key must not satisfy a demand for
        # `X#d`, and neither must producing a sibling variant. `producers` is untouched;
        # `satisfying_variants` only ever answers for a bare key.
        g = self._graph(variant_producible=False)
        g.add(Recipe("plain", "test", [(self.BARE, 1)],
                     [Ingredient(["mod:reagent"], 1)]))
        g.add(Recipe("sibling", "test", [("mod:kama_bound#aaaaaaaaaaaa", 1)],
                     [Ingredient(["mod:reagent"], 1)]))
        self.assertEqual(g.satisfying_variants(self.MADE), [])
        self.assertEqual(g.producers(self.MADE), [])
        self.assertEqual(Solver(g).solve(self.MADE, 1)["tree"]["status"], "raw")

    def test_a_bare_key_with_its_own_route_keeps_its_own_price(self):
        # THE CONTROL, and it is twenty times the size of the defect: 2,117 bare keys on the
        # reference graph carry this relation with an honest route of their own, and 82 have a
        # produced variant CHEAPER than themselves -- `simplyjetpacks:itemjetpack:14` at
        # 1,491.72 against a 2.5 variant. A rule keyed on the relation rather than on the
        # absence of a route cheapens that by 597x.
        g = self._graph()
        g.add(Recipe("plain", "test", [(self.BARE, 1)],
                     [Ingredient(["mod:expensive"], 9)]))
        self.assertEqual(g.satisfying_variants(self.BARE), [])
        self.assertEqual({}, {v: b for v, b in g.variant_subsumption.items()
                              if b == self.BARE})

    def test_the_choice_of_variant_is_deterministic(self):
        # It reaches a plan tree and the fixtures freeze those for the Java port, so with two
        # equally priced producible variants the answer cannot depend on dict order.
        # `variant_index` is insertion-ordered off `by_output` and `scored.sort` is stable, so
        # the first the dump saw wins -- the same invariant `RecipeGraphOrderTest` pins for
        # producer lists.
        def built():
            g = self._graph()
            g.names["mod:kama_bound#aaaaaaaaaaaa"] = "Bound Khopesh"
            g.add(Recipe("array2", "bloodmagic:alchemyArray",
                         [("mod:kama_bound#aaaaaaaaaaaa", 1)],
                         [Ingredient(["mod:reagent"], 1)]))
            return g

        first = self._leaf(built())["resolved_to"]
        self.assertEqual(first, self.MADE)
        for _ in range(20):
            self.assertEqual(first, self._leaf(built())["resolved_to"])
        self.assertEqual(self.MADE, built().reachable_form(self.BARE))


class ItMarksOnlyTheShoppingListTest(unittest.TestCase):
    """`_entry` builds FIVE lists, and the mark means something on exactly one of them.

    Decorating it inside `_entry` put "no known source" on rows in "Drawn from AE2 stock" --
    a row that exists precisely BECAUSE you are holding the item. Same for an infinite
    source and for a token, each of which already carries its own, contradictory, answer to
    "how do I get this".
    """

    def _result(self):
        g = _graph()
        # Holding some of the tier, so it lands on used_from_stock as well as being short.
        return Solver(g, have={SUPERIOR: 1}).solve(GOO, 3)

    def test_the_shopping_list_row_is_marked(self):
        rows = [r for r in self._result()["shopping_list"] if r["key"] == SUPERIOR]
        self.assertEqual(len(rows), 1)
        self.assertTrue(rows[0].get("unsourced"))

    def test_the_stock_row_for_the_same_key_is_NOT_marked(self):
        rows = [r for r in self._result()["used_from_stock"] if r["key"] == SUPERIOR]
        self.assertEqual(len(rows), 1, "expected the held one to be drawn from stock")
        self.assertFalse(rows[0].get("unsourced"),
                         "a row that exists because you are HOLDING the item must not also "
                         "say the tool cannot find you one")

    def test_a_token_row_is_not_marked(self):
        g = _graph()
        result = Solver(g, token_kinds={SUPERIOR: "loot"}).solve(GOO, 1)
        for row in result["tokens_needed"]:
            self.assertFalse(row.get("unsourced"), row)


class RenderedSurfacesTest(unittest.TestCase):
    """The badge has to survive into the HTML, on both surfaces a reader looks at."""

    def _html(self):
        from recipegraph.render import render_html
        return render_html(Solver(_graph()).solve(GOO, 2), _graph())

    def test_the_tree_and_the_shopping_list_both_carry_it(self):
        # Counted BEFORE the footer, which documents the badge and would otherwise inflate
        # the count and make this pass for the wrong reason.
        body = self._html().split('class="foot"')[0]
        # Split by surface rather than counted flat. The word reaches four places and only
        # two of them are the ones this test is about; a bare total would go on passing if
        # one moved into the other.
        in_titles = sum(1 for t in re.findall(r"<title>([^<]*)</title>", body)
                        if present.UNSOURCED_BADGE in t)
        self.assertEqual(body.count(present.UNSOURCED_BADGE) - in_titles, 2,
                         "expected the badge on the tree node and on the shopping-list row")

    def test_the_diagram_says_which_red_the_box_is(self):
        """Both orientations, in the `<title>`. #174 carried `unsourced` into the record.

        The diagram box has no room for a word, so the fill was the whole of what it said --
        and `token` and `raw` deliberately share that fill, so a red box could not say which
        red it was. `graphview.layout` dropped the field this needs.
        """
        body = self._html().split('class="foot"')[0]
        titles = [t for t in re.findall(r"<title>([^<]*)</title>", body)
                  if present.UNSOURCED_BADGE in t]
        self.assertEqual(len(titles), 2,
                         "expected the word in both orientations' hover titles, got %r"
                         % (titles,))

    def test_the_note_explains_it_in_the_tree(self):
        self.assertIn("no recipe reaches this state", self._html())

    def test_an_ordinary_need_row_is_untouched(self):
        # Cobblestone is in this plan and must still read as a plain NEED.
        html = self._html()
        self.assertIn(">NEED<", html)

    def test_the_footer_documents_the_badge(self):
        # The plan explains its own badges; a badge the footer does not mention is one the
        # reader has to guess at.
        self.assertIn(present.UNSOURCED_BADGE, self._html().split('class="foot"')[1])


class ThereIsOnlyOneSpellingOfThePredicateTest(unittest.TestCase):
    """The badge, the sweep and the shopping list must be one predicate, not three copies.

    THIS TEST REPLACES ONE THAT PASSED WHILE THE THING IT GUARDED WAS BROKEN, which is worth
    stating because the replacement is a different KIND of assertion. `api` used to keep its
    own `_reachable_form` on the reasoning that a sweep must not import `solve` -- a true
    constraint with the wrong remedy -- and the old `TheTwoPredicatesAgreeTest` compared the
    two spellings on five keys. Every one of those five was an NBT-variant shape, so when
    #136 added the processed-form branch and #170 added the produced-variant branch to the
    solver's copy and neither touched api's, the comparison kept passing and `/api/sweep`
    silently under-reported `unsourced` on two of the four shapes.

    A COMPARISON TEST CANNOT CATCH THAT, because the cases it compares are written by the
    same person who forgot the branch. So the assertion is structural instead: there is ONE
    definition, and everything reads it. Behaviour is covered by the four shape classes
    above, which now exercise the single implementation by construction.
    """

    def test_the_predicate_is_defined_exactly_once_in_the_package(self):
        import glob
        import os
        import re
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph")
        found = []
        for path in sorted(glob.glob(os.path.join(root, "*.py"))):
            with open(path) as fh:
                for line in fh:
                    if re.match(r"\s*def _?reachable_form\b", line):
                        found.append(os.path.basename(path))
        self.assertEqual(["model.py"], found,
                         "reachable_form must live on Graph and nowhere else; a second "
                         "spelling is how #136 and #170 drifted out of /api/sweep")

    def test_the_solver_reads_the_graph_rather_than_carrying_its_own(self):
        # `Solver` deliberately has no `reachable_form` of its own. Asserted rather than
        # assumed, because re-adding one is the exact regression this class exists for and
        # it would not fail any behavioural test in this file.
        self.assertFalse(hasattr(Solver(_graph()), "reachable_form"))

    def test_the_sweep_field_is_wired_to_it(self):
        from recipegraph import api
        g = _graph()

        class _Ctx:
            graph = g
            have = {}
            costs = {}

        self.assertTrue(api.FIELDS["unsourced"][0](_Ctx(), SUPERIOR))
        self.assertFalse(api.FIELDS["unsourced"][0](_Ctx(), "minecraft:cobblestone"))

    def test_the_sweep_sees_the_branches_it_used_to_miss(self):
        """The two shapes the old comparison test never covered, through `/api/sweep`'s field.

        This is the regression in its reported form: before the unification the sweep
        answered False on both of these while the plan badged them, so "how many keys would
        the badge fire on" was answerable only by running the solver.
        """
        from recipegraph import api

        def sweep(graph, key):
            class _Ctx:
                pass
            ctx = _Ctx()
            ctx.graph, ctx.have, ctx.costs = graph, {}, {}
            return api.FIELDS["unsourced"][0](ctx, key)

        # #136's processed form: a nugget nothing makes beside an ingot the graph does.
        self.assertTrue(sweep(ProcessedFormTest._graph(), "mod:nugget"))
        # #170's produced variant: a bare key nothing makes, made under an NBT digest.
        self.assertTrue(sweep(ProducedOnlyAsAVariantTest._graph(),
                              ProducedOnlyAsAVariantTest.BARE))


class ThereIsOnlyOneSpellingOfTheSubsumptionRelationTest(unittest.TestCase):
    """`variant_subsumption` is defined once, and both consumers read that one definition.

    THE SAME ASSERTION AS THE CLASS ABOVE, FOR THE SAME REASON, AND IT IS NOT A COPY OF IT:
    that one guards `reachable_form`, this one guards the relation #170 added beside it. The
    relation has two consumers -- `cost._relax`, which lets a variant's production price the
    bare key, and `Solver.expand`, which routes a bare demand through the variant's recipe --
    which is exactly the shape that drifted before: two callers, one hand-kept copy, and a
    comparison test that passed for two releases because it only compared the branch nobody
    had changed.

    STRUCTURAL RATHER THAN COMPARATIVE, again deliberately. Pricing a route the solver cannot
    take is #176's defect and pricing one it takes DIFFERENTLY is worse, so what has to be
    true is that there is nothing to compare.
    """

    def _sources(self):
        import glob
        import os
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph")
        return sorted(glob.glob(os.path.join(root, "*.py")))

    def test_the_relation_is_defined_exactly_once_in_the_package(self):
        import os
        import re
        found = []
        for path in self._sources():
            with open(path) as fh:
                for line in fh:
                    if re.match(r"\s*def _?(variant_subsumption|satisfying_variants)\b", line):
                        found.append(os.path.basename(path))
        self.assertEqual(["model.py", "model.py"], found,
                         "the bare-to-variant relation and its view must live on Graph and "
                         "nowhere else; a second spelling is how #136 and #170 drifted out "
                         "of /api/sweep")

    def test_no_module_reimplements_the_clauses(self):
        # The cheap way to write a second copy is to inline the two clauses that matter --
        # "the bare key has no producer" and "a variant of it does" -- next to the caller
        # that needs them. `cost` and `solve` are the two callers and neither may.
        import os
        import re
        clause = re.compile(r"variants_of\(|variant_index")
        for path in self._sources():
            if os.path.basename(path) in ("model.py", "machines.py"):
                continue   # `machines` predates this and uses `producers_any_variant`; see #28
            with open(path) as fh:
                for number, line in enumerate(fh, 1):
                    if clause.search(line) and not line.lstrip().startswith("#"):
                        self.fail("%s:%d reaches into the variant index directly; call "
                                  "`Graph.satisfying_variants` instead: %s"
                                  % (os.path.basename(path), number, line.strip()))

    def test_the_solver_carries_no_relation_of_its_own(self):
        # A source scan would miss an alias, which is how the previous version of this guard
        # was defeated: `Solver.reachable_form = Graph.reachable_form` is one line and no
        # `def`. Neither name may exist on the solver.
        solver = Solver(_graph())
        self.assertFalse(hasattr(solver, "variant_subsumption"))
        self.assertFalse(hasattr(solver, "satisfying_variants"))

    def test_the_price_and_the_route_are_computed_from_the_same_pairs(self):
        """Every recipe the solver would substitute prices the key the cost model priced.

        The one behavioural claim worth making here, because the two consumers use the
        relation differently -- one reads the dict by output key, the other by bare key -- and
        agreeing on the DICT is not the same as agreeing on the pairs.
        """
        from recipegraph import cost as cost_mod

        g = ProducedOnlyAsAVariantTest._graph()
        solver = Solver(g)
        recipes, variant_of = solver._variant_candidates(
            ProducedOnlyAsAVariantTest.BARE)
        self.assertTrue(recipes)
        for recipe in recipes:
            variant = variant_of[recipe.rid]
            self.assertEqual(g.variant_subsumption[variant],
                             ProducedOnlyAsAVariantTest.BARE)
        # And the price the relaxation lands on is the recipe's own per-unit cost, not the
        # leaf floor and not the variant's stock.
        costs = cost_mod.estimate(g)
        self.assertEqual(costs[ProducedOnlyAsAVariantTest.BARE],
                         costs[ProducedOnlyAsAVariantTest.MADE])


class TheTreeRowDoesNotCrushItsIconTest(unittest.TestCase):
    """A flex item with `min-width:0` shrinks below its own ATOMIC children.

    Measured on the live plan at 390px: a deep node carrying an item icon and a wide badge
    squeezed `.nm` to a 6px content box around a 16px sprite -- `scrollWidth` 22 against
    `clientWidth` 6. Every bounding rect looked correct and the page did not scroll
    horizontally, which is why this repo measures `scrollWidth` rather than geometry.

    Asserted as CSS rather than by driving a browser because the suite is stdlib-only and
    has no Playwright; the browser measurement is the artifact on the PR. What can be pinned
    here is that the two rules which prevent it are present and in the phone block.
    """

    def _phone_block(self):
        from recipegraph.render import CSS
        # The last @media block in the sheet is the phone one; the file's own comment says
        # the phone blocks sit last so they win by cascade order.
        return CSS[CSS.rindex("@media"):]

    def test_the_row_may_wrap(self):
        self.assertIn("flex-wrap:wrap", self._phone_block())

    def test_the_name_keeps_a_floor(self):
        block = self._phone_block()
        self.assertRegex(block, r"\.nm\{min-width:\d")

    def test_min_width_zero_survives_for_long_ids(self):
        # The floor must not undo what `min-width:0` buys in the base rule: an unbreakable
        # registry id has to be able to break, which needs the base `.nm` untouched.
        from recipegraph.render import CSS
        base = CSS[:CSS.rindex("@media")]
        self.assertIn("min-width:0", base)
        self.assertIn("overflow-wrap:anywhere", base)


if __name__ == "__main__":
    unittest.main()
