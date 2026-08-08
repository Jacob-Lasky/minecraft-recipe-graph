"""Smoke tests: no game instance or world save required.

Run: python3 -m unittest discover -s tests
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import render  # noqa: E402
from recipegraph import solve as solve_mod  # noqa: E402
from recipegraph import explore  # noqa: E402
from recipegraph.model import (  # noqa: E402
    NON_ITEM_KINDS, Graph, Ingredient, Recipe, base_key, is_item_key, is_unlocalized,
    merge_slots, norm_key, path_of, split_discriminator,
)
from recipegraph.solve import Solver  # noqa: E402
from recipegraph.sources.jar_json import parse_recipe_json  # noqa: E402
from recipegraph.sources import jar_json  # noqa: E402
from recipegraph.sources.oredict import guess_from_names  # noqa: E402


class TestKeys(unittest.TestCase):
    def test_meta_zero_is_omitted(self):
        self.assertEqual(norm_key("minecraft:stone", 0), "minecraft:stone")
        self.assertEqual(norm_key("minecraft:stone"), "minecraft:stone")

    def test_meta_kept(self):
        self.assertEqual(norm_key("nuclearcraft:compound", 7), "nuclearcraft:compound:7")

    def test_wildcard(self):
        self.assertEqual(norm_key("mod:thing", 32767), "mod:thing:*")

    def test_bare_name_gets_minecraft_namespace(self):
        self.assertEqual(norm_key("diamond"), "minecraft:diamond")

    def test_base_key_strips_only_the_discriminator(self):
        self.assertEqual(base_key("forestry:can:1#48a337d94489"), "forestry:can:1")
        # The meta survives. Collapsing it would merge every metadata variant of an item
        # into one pseudo-item; see the container detector.
        self.assertEqual(base_key("tconstruct:ingots:3"), "tconstruct:ingots:3")
        self.assertEqual(base_key("minecraft:stone"), "minecraft:stone")
        self.assertEqual(base_key("fluid:water"), "fluid:water")

    def test_split_discriminator_reports_absence_as_none(self):
        # None rather than "" so a caller can tell "no discriminator" from a key that
        # ends in a bare '#'.
        self.assertEqual(split_discriminator("mod:x"), ("mod:x", None))
        self.assertEqual(split_discriminator("mod:x#abc"), ("mod:x", "abc"))
        # An aspect key is discriminated too, and predates the dump digests.
        self.assertEqual(split_discriminator("thaumadditions:vis_pod#perditio"),
                         ("thaumadditions:vis_pod", "perditio"))

    def test_is_item_key_covers_every_non_item_namespace(self):
        # Guards the single list the detector, split_key and kind all read. A namespace
        # added to NON_ITEM_KINDS without a `kind` entry would fail here.
        g = Graph()
        for name in NON_ITEM_KINDS:
            key = "%s:thing" % name
            self.assertFalse(is_item_key(key), key)
            self.assertEqual(g.kind(key), name)
        self.assertTrue(is_item_key("minecraft:stone"))
        self.assertEqual(g.kind("minecraft:stone"), "item")


class TestJarJson(unittest.TestCase):
    def test_shaped_counts_pattern_occurrences(self):
        doc = {
            "type": "minecraft:crafting_shaped",
            "pattern": ["XXX", "XXX", "XXX"],
            "key": {"X": {"item": "minecraft:diamond"}},
            "result": {"item": "minecraft:diamond_block", "count": 1},
        }
        r = parse_recipe_json(doc, "t")
        self.assertEqual(r.outputs, [("minecraft:diamond_block", 1)])
        self.assertEqual(len(r.inputs), 1)
        self.assertEqual(r.inputs[0].qty, 9)

    def test_oredict_ingredient(self):
        doc = {
            "type": "forge:ore_shapeless",
            "ingredients": [{"type": "forge:ore_dict", "ore": "ingotIron"}],
            "result": {"item": "mod:plate"},
        }
        r = parse_recipe_json(doc, "t")
        self.assertEqual(r.inputs[0].alternatives, ["ore:ingotIron"])

    def test_constants_reference_resolves(self):
        constants = {"lattice": {"item": "avaritia:resource", "data": 0}}
        doc = {
            "type": "minecraft:crafting_shaped",
            "pattern": ["LL"],
            "key": {"L": {"type": "minecraft:item", "item": "#lattice"}},
            "result": {"item": "avaritia:resource", "data": 1},
        }
        r = parse_recipe_json(doc, "t", constants)
        self.assertEqual(r.inputs[0].alternatives, ["avaritia:resource"])

    def test_unresolved_constant_is_dropped_not_faked(self):
        # A bogus `minecraft:#name` key would become a phantom shopping-list item.
        doc = {
            "type": "minecraft:crafting_shaped",
            "pattern": ["LL"],
            "key": {"L": {"item": "#missing"}},
            "result": {"item": "mod:thing"},
        }
        self.assertIsNone(parse_recipe_json(doc, "t", {}))


class TestJarJsonNamespace(unittest.TestCase):
    """#227: an unqualified id belongs to the recipe file's namespace, not to `minecraft`.

    EVERY DOCUMENT BELOW IS COPIED VERBATIM OUT OF A PACK JAR, not written for the test. The
    defect was that `norm_key` prepends `minecraft:`, and a document invented here would only
    prove the code does what it was written to do; these prove it produces the key the RUNNING
    GAME uses, each of which was checked against `names.json` in the reference dump.
    """

    def test_unqualified_result_takes_the_file_namespace(self):
        # tombstone-1.12.2-4.7.5.jar!assets/tombstone/recipes/tablet_of_home.json
        doc = {
            "type": "tombstone:disableable_shapeless",
            "ingredients": [{"item": "tombstone:crafting_ingredient", "data": 1},
                            {"item": "minecraft:red_mushroom"}],
            "result": {"item": "tablet_of_home"},
        }
        r = parse_recipe_json(doc, "t", None, "tombstone")
        self.assertEqual(r.outputs, [("tombstone:tablet_of_home", 1)])
        # And the qualified ingredients beside it are left exactly alone.
        self.assertIn(["tombstone:crafting_ingredient:1"],
                      [i.alternatives for i in r.inputs])

    def test_unqualified_result_with_a_count(self):
        # Tesslocator-1.1.0.15.jar!assets/tesslocator/recipes/basic_item_tesslocator.json
        doc = {
            "type": "minecraft:crafting_shaped",
            "group": "tesslocator",
            "result": {"item": "basic_item_tesslocator", "count": 4},
            "pattern": ["GHG", "HFH", "GHG"],
            "key": {"G": {"type": "forge:ore_dict", "ore": "nuggetGold"},
                    "H": {"item": "minecraft:hopper"},
                    "F": {"item": "itemfilters:filter"}},
        }
        r = parse_recipe_json(doc, "t", None, "tesslocator")
        self.assertEqual(r.outputs, [("tesslocator:basic_item_tesslocator", 4)])

    def test_unqualified_INGREDIENT_takes_the_file_namespace(self):
        """The damaging half: a phantom INPUT is a demand nothing can ever satisfy.

        BiblioCraft[v2.4.6][MC1.12.2].jar!assets/bibliocraft/recipes/atlasbook.json qualifies
        its result and not its ingredients, so before #227 this recipe asked for
        `minecraft:maptool` and `minecraft:slottedbook`. Both exist as `bibliocraft:*` in the
        dump; neither exists under `minecraft:` in any registry.
        """
        doc = {
            "type": "minecraft:crafting_shaped",
            "group": "bibliocraft",
            "pattern": ["RTR", "RBR", "RMR"],
            "key": {"R": {"item": "minecraft:paper"}, "T": {"item": "maptool"},
                    "M": {"item": "minecraft:map"}, "B": {"item": "slottedbook"}},
            "result": {"item": "bibliocraft:atlasbook", "data": 0},
        }
        r = parse_recipe_json(doc, "t", None, "bibliocraft")
        alts = sorted(a for i in r.inputs for a in i.alternatives)
        self.assertEqual(alts, ["bibliocraft:maptool", "bibliocraft:slottedbook",
                                "minecraft:map", "minecraft:paper"])
        self.assertNotIn("minecraft:maptool", alts)

    def test_no_namespace_given_keeps_the_old_behaviour(self):
        # `default_ns=None` is what the direct callers above pass, and it must not move.
        doc = {"type": "minecraft:crafting_shapeless",
               "ingredients": [{"item": "stick"}], "result": {"item": "thing"}}
        r = parse_recipe_json(doc, "t")
        self.assertEqual(r.outputs, [("minecraft:thing", 1)])

    def test_qualify_leaves_a_qualified_id_alone(self):
        self.assertEqual(jar_json.qualify("mod:thing", "other"), "mod:thing")
        self.assertEqual(jar_json.qualify("thing", "other"), "other:thing")
        self.assertEqual(jar_json.qualify("thing", None), "thing")
        # A non-string reaches `norm_key` unchanged, which is the only reader that knows
        # what to do with garbage; qualifying it would turn `None` into `"ns:None"`.
        self.assertIsNone(jar_json.qualify(None, "ns"))
        self.assertEqual(jar_json.qualify(7, "ns"), 7)

    def test_a_non_string_id_yields_nothing_rather_than_a_repr_key(self):
        """`norm_key` str()s whatever it gets, so a list id used to build the literal key
        `minecraft:['a', 'b']` -- a phantom arriving by a different door than #227's. No pack
        document does this today, which is a fact about the pack and not about the format."""
        self.assertEqual(parse_recipe_json(
            {"type": "minecraft:crafting_shapeless", "ingredients": [{"item": "x:y"}],
             "result": {"item": ["a", "b"]}}, "t", None, "mod"), None)
        # And on the ingredient side the slot is dropped, which drops the recipe.
        self.assertIsNone(parse_recipe_json(
            {"type": "minecraft:crafting_shapeless", "ingredients": [{"item": {"bad": 1}}],
             "result": {"item": "mod:thing"}}, "t", None, "mod"))

    def test_a_constant_target_also_takes_the_file_namespace(self):
        """`#name` resolves through `_constants.json`, whose targets are in the SAME
        namespace as the recipe. Missing this leaves the phantom key in the one place the
        file already warns about faking keys."""
        constants = {"gear": {"item": "cog", "data": 2}}
        doc = {"type": "minecraft:crafting_shaped", "pattern": ["GG"],
               "key": {"G": {"item": "#gear"}}, "result": {"item": "mod:thing"}}
        r = parse_recipe_json(doc, "t", constants, "mymod")
        self.assertEqual(r.inputs[0].alternatives, ["mymod:cog:2"])

    def test_asset_namespace_is_the_one_spelling_of_that_shape(self):
        self.assertEqual(jar_json.asset_namespace("assets/mod/recipes/x.json"), "mod")
        self.assertEqual(jar_json.asset_namespace("assets/mod/lang/en_us.lang"), "mod")
        self.assertIsNone(jar_json.asset_namespace("mcmod.info"))
        self.assertIsNone(jar_json.asset_namespace("assets/"))
        self.assertIsNone(jar_json.asset_namespace("assets/mod"))
        self.assertIsNone(jar_json.asset_namespace("assets//recipes/x.json"))


def _graph():
    g = Graph()
    g.names = {
        "mod:ingot": "Test Ingot",
        "mod:block": "Test Block",
        "mod:lattice": "Test Lattice",
        "mod:dust": "Test Dust",
    }
    # real route: 4 lattice -> 1 ingot
    g.add(Recipe("real", "t", [("mod:ingot", 1)], [Ingredient(["mod:lattice"], 4)]))
    # uncrafting route: 1 block -> 9 ingots  (and block is 9 ingots -> cycle)
    g.add(Recipe("uncraft", "t", [("mod:ingot", 9)], [Ingredient(["mod:block"], 1)]))
    g.add(Recipe("block", "t", [("mod:block", 1)], [Ingredient(["mod:ingot"], 9)]))
    return g


class TestSolver(unittest.TestCase):
    def test_backtracks_out_of_uncrafting_cycle(self):
        res = Solver(_graph(), have={}).solve("mod:ingot", 1)
        needs = {r["key"]: r["qty"] for r in res["shopping_list"]}
        self.assertEqual(needs, {"mod:lattice": 4},
                         "should take the real route, not block->9 ingots")

    def test_stock_short_circuits_expansion(self):
        res = Solver(_graph(), have={"mod:ingot": 5}).solve("mod:ingot", 5)
        self.assertEqual(res["shopping_list"], [])
        self.assertEqual(res["tree"]["status"], "have")

    def test_stock_is_consumed_not_reused_across_branches(self):
        g = _graph()
        g.add(Recipe("two", "t", [("mod:dust", 1)],
                     [Ingredient(["mod:lattice"], 3), Ingredient(["mod:lattice"], 3)]))
        # only 4 lattice in stock but 6 required -> 2 must still be sourced
        res = Solver(g, have={"mod:lattice": 4}).solve("mod:dust", 1)
        needs = {r["key"]: r["qty"] for r in res["shopping_list"]}
        self.assertEqual(needs.get("mod:lattice"), 2)

    def test_partial_stock_reduces_remainder(self):
        res = Solver(_graph(), have={"mod:lattice": 1}).solve("mod:ingot", 1)
        needs = {r["key"]: r["qty"] for r in res["shopping_list"]}
        self.assertEqual(needs, {"mod:lattice": 3})


class TestPool(unittest.TestCase):
    """The stock pool, which `take` and `available` read on every single expansion."""

    def _split_calls(self, pool_size, takes=50):
        """How many split_key calls N takes cost against a pool of `pool_size` keys."""
        pool = {"mod:thing%d" % i: 10 for i in range(pool_size)}
        s = Solver(_graph(), have=pool)
        calls = []
        real = solve_mod.split_key
        solve_mod.split_key = lambda k: (calls.append(k), real(k))[1]
        try:
            for i in range(takes):
                s.take("mod:thing%d" % i, 1)
        finally:
            solve_mod.split_key = real
        return len(calls)

    def test_take_cost_does_not_grow_with_the_pool(self):
        """#18: `take` scanned every pool key to discover it needed only one of them.

        3,389 stocked keys made a 4,000-node plan cost 23 million split_key calls and
        20 seconds. Asserting the two pool sizes cost the SAME is the durable form of
        "not O(pool)" -- a threshold would drift with unrelated changes.
        """
        small = self._split_calls(100)
        large = self._split_calls(2000)
        self.assertEqual(small, large,
                         "take is scanning the pool: %d calls at 100 keys, %d at 2000"
                         % (small, large))

    def test_wildcard_take_still_spans_metas(self):
        """The pool scan existed to serve wildcards; the index must keep that working."""
        s = Solver(_graph(), have={"mod:thing:1": 3, "mod:thing:2": 4, "other:x": 9})
        self.assertEqual(s.available("mod:thing:*"), 7)
        self.assertEqual(s.take("mod:thing:*", 6), 6)
        self.assertEqual(s.pool["mod:thing:1"], 0)
        self.assertEqual(s.pool["mod:thing:2"], 1)
        self.assertEqual(s.pool["other:x"], 9, "an unrelated base must not be drained")

    def test_wildcard_in_stock_is_not_counted_twice(self):
        """`available` added pool[key] and then re-added it while scanning for the base."""
        s = Solver(_graph(), have={"mod:thing:*": 5, "mod:thing:1": 2})
        self.assertEqual(s.available("mod:thing:*"), 7)
        self.assertEqual(s.take("mod:thing:*", 7), 7,
                         "take must be able to deliver everything available() promised")

    def test_pool_key_set_is_fixed_for_a_solvers_lifetime(self):
        """The tripwire for the base index, which is built once and never invalidated.

        `take` only DECREMENTS, and `_restore` swaps in a copy of the same keys, so the
        index cannot go stale. If a future change inserts a key into `pool`, this fails
        first and points at `_by_base` -- see the comment on `_index_pool`.
        """
        g = _graph()
        g.add(Recipe("two", "t", [("mod:dust", 1)],
                     [Ingredient(["mod:lattice"], 3), Ingredient(["mod:block"], 1)]))
        s = Solver(g, have={"mod:lattice": 4, "mod:ingot": 2})
        before = set(s.pool)
        s.solve("mod:dust", 3)
        self.assertEqual(set(s.pool), before)


def _grid_graph():
    """A 3x3 of one ingredient, which is how a shaped recipe really parses: nine slots."""
    g = Graph()
    g.names = {"mod:clump": "Tiny Clump", "mod:ingot": "Ingot", "mod:ore": "Ore",
               "mod:rod": "Rod", "mod:plate": "Plate"}
    g.add(Recipe("compress", "t", [("mod:ingot", 1)],
                 [Ingredient(["mod:clump"], 1) for _ in range(9)]))
    g.add(Recipe("smelt", "t", [("mod:clump", 1)], [Ingredient(["mod:ore"], 1)]))
    return g


class TestMergeSlots(unittest.TestCase):
    """The shared collapse both the solver and the item page are built on."""

    def test_it_returns_the_first_slot_of_each_group(self):
        first = Ingredient(["mod:clump"], 1, role="item")
        rows = merge_slots([first, Ingredient(["mod:clump"], 2)],
                           lambda i: tuple(i.alternatives))
        self.assertEqual(len(rows), 1)
        key, ing, qty, options = rows[0]
        self.assertEqual(key, ("mod:clump",))
        self.assertIs(ing, first, "the group's row describes its first slot")
        self.assertEqual((qty, options), (3, 1))

    def test_order_is_first_appearance(self):
        rows = merge_slots([Ingredient(["b"], 1), Ingredient(["a"], 1),
                            Ingredient(["b"], 1)], lambda i: tuple(i.alternatives))
        self.assertEqual([r[0] for r in rows], [("b",), ("a",)])

    def test_no_inputs_is_no_rows(self):
        self.assertEqual(merge_slots([], lambda i: i), [])

    def test_the_widest_slot_sets_the_option_count(self):
        rows = merge_slots([Ingredient(["a"], 1), Ingredient(["a", "b", "c"], 1)],
                           lambda i: i.alternatives[0])
        self.assertEqual(rows[0][3], 3)


class TestSlotMerging(unittest.TestCase):
    """Slots resolving to the same thing collapse into one node (#24).

    Nine identical subtrees where one would do also multiplies the node count by nine
    at every such step, so the node cap was reached nine times sooner and the tree that
    got truncated was mostly duplicate.
    """

    def test_a_3x3_of_one_ingredient_is_one_node_of_nine(self):
        res = Solver(_grid_graph()).solve("mod:ingot", 1)
        kids = res["tree"]["children"]
        self.assertEqual(len(kids), 1, "nine slots of the same clump are one ingredient")
        self.assertEqual(kids[0]["need"], 9)

    def test_merging_collapses_the_duplicated_subtrees(self):
        res = Solver(_grid_graph()).solve("mod:ingot", 1)
        # ingot + clump + ore, rather than ingot + nine (clump + ore)
        self.assertEqual(res["nodes"], 3)

    def test_the_shopping_list_is_unchanged_by_merging(self):
        res = Solver(_grid_graph()).solve("mod:ingot", 1)
        self.assertEqual({r["key"]: r["qty"] for r in res["shopping_list"]},
                         {"mod:ore": 9})

    def test_quantities_still_scale_with_runs(self):
        res = Solver(_grid_graph()).solve("mod:ingot", 4)
        self.assertEqual(res["tree"]["children"][0]["need"], 36)

    def test_slots_landing_on_different_items_do_not_merge(self):
        g = _grid_graph()
        g.add(Recipe("mixed", "t", [("mod:plate", 1)],
                     [Ingredient(["mod:clump"], 2), Ingredient(["mod:rod"], 3)]))
        kids = Solver(g).solve("mod:plate", 1)["tree"]["children"]
        self.assertEqual([(c["key"], c["need"]) for c in kids],
                         [("mod:clump", 2), ("mod:rod", 3)])

    def test_slots_merge_on_the_RESOLVED_alternative_not_the_declared_one(self):
        """Different alternative LISTS that pick the same item still merge."""
        g = _grid_graph()
        g.ore_members = {"clumps": ["mod:clump"]}
        g.add(Recipe("mixed", "t", [("mod:plate", 1)],
                     [Ingredient(["mod:clump"], 2),
                      Ingredient(["mod:clump", "mod:rod"], 3)]))
        kids = Solver(g).solve("mod:plate", 1)["tree"]["children"]
        self.assertEqual(len(kids), 1, "both slots picked mod:clump")
        self.assertEqual(kids[0]["need"], 5)

    def test_an_ore_slot_does_not_merge_with_a_slot_naming_its_member(self):
        """Deliberate: oredict resolution happens a level below the merge.

        `ore:clumps` and `mod:clump` are different NODES -- the oredict one renders as
        "any of these" with its own resolved_to. Merging them would have to throw one
        label away. Measured on the reference pack, 13 of 117,685 recipes mix an ore
        slot with a concrete member of that same ore, so the honest split costs almost
        nothing and keeps both slots reported as authored.
        """
        g = _grid_graph()
        g.ore_members = {"clumps": ["mod:clump"]}
        g.add(Recipe("ores", "t", [("mod:plate", 1)],
                     [Ingredient(["ore:clumps"], 2), Ingredient(["mod:clump"], 3)]))
        kids = Solver(g).solve("mod:plate", 1)["tree"]["children"]
        self.assertEqual([c["key"] for c in kids], ["ore:clumps", "mod:clump"])

    def test_a_merged_node_reports_the_widest_slot_alternative_count(self):
        g = _grid_graph()
        g.add(Recipe("wide", "t", [("mod:plate", 1)],
                     [Ingredient(["mod:clump"], 1),
                      Ingredient(["mod:clump", "mod:rod", "mod:ore"], 1)]))
        kid = Solver(g, have={"mod:clump": 2}).solve("mod:plate", 1)["tree"]["children"][0]
        self.assertEqual(kid["need"], 2)
        self.assertEqual(kid["alt_count"], 3,
                         "the merged node must not inherit the first slot's count of 1")

    def test_stock_for_one_slot_does_not_satisfy_nine_of_them(self):
        """`score_recipe` counted a satisfied INGREDIENT once per slot.

        With one clump in stock, nine slots asking for one clump each all read as
        satisfied, so a recipe the pool could barely start outranked one it could
        finish.
        """
        g = _grid_graph()
        # Two routes to a plate: nine clumps (one in stock) or two rods (both in stock).
        g.add(Recipe("nine", "t", [("mod:plate", 1)],
                     [Ingredient(["mod:clump"], 1) for _ in range(9)]))
        g.add(Recipe("two", "t", [("mod:plate", 1)], [Ingredient(["mod:rod"], 2)]))
        res = Solver(g, have={"mod:clump": 1, "mod:rod": 2}).solve("mod:plate", 1)
        self.assertEqual(res["tree"]["recipe"], "two")

    def test_a_single_option_slot_carries_no_alternative_count(self):
        kid = Solver(_grid_graph()).solve("mod:ingot", 1)["tree"]["children"][0]
        self.assertNotIn("alt_count", kid, "nine slots of one option are still one option")

    def test_the_slot_alternative_count_reaches_the_tree_html(self):
        g = _grid_graph()
        g.add(Recipe("wide", "t", [("mod:plate", 1)],
                     [Ingredient(["mod:clump", "mod:rod", "mod:ore"], 1)]))
        html = render.render_html(Solver(g).solve("mod:plate", 1))
        self.assertIn("any of 3", html)


class UnlocalizedNameTest(unittest.TestCase):
    """#52: `tile.null.name` is a lang key, not a name, and 268 items shared that one."""

    def test_the_shape_is_recognised(self):
        for label in ("tile.null.name", "item.ccluster2.name",
                      "parttype.parttypes.integrateddynamics.audio_reader.name.name",
                      "fluid.molten_hepatizon.name",
                      "item.bloodmagic.livingArmour..name"):
            self.assertTrue(is_unlocalized(label), label)

    def test_a_real_name_is_left_alone(self):
        for label in ("Machine Controller", "Borax", "R.T.G.", "Block of Iron",
                      # Ends in `.name` but is half localized, and the space says so.
                      "Spawn entity.blackfrost.name",
                      # One dot only: a name may legitimately contain a period.
                      "Dr.name", "", None):
            self.assertFalse(is_unlocalized(label), label)

    def test_the_registry_path_replaces_it(self):
        g = Graph()
        g.names = {"modularmachinery:safe_fission_hep239_controller": "tile.null.name"}
        self.assertEqual(g.relabel_unlocalized(), 1)
        self.assertEqual(g.bare_name("modularmachinery:safe_fission_hep239_controller"),
                         "Safe Fission Hep239 Controller")

    def test_items_that_shared_one_lang_key_become_distinguishable(self):
        """The whole point. Eight reactors read identically before this."""
        g = Graph()
        g.names = {"modularmachinery:safe_fission_%s_controller" % v: "tile.null.name"
                   for v in ("amfm", "hea242", "hecf249", "hep239")}
        g.relabel_unlocalized()
        self.assertEqual(len(set(g.names.values())), 4)

    def test_the_key_stays_searchable(self):
        """Relabelling, NOT deleting: `labels` is built from `names`, so a dropped key
        would vanish from search altogether, which is worse than an ugly name."""
        g = Graph()
        g.names = {"mod:thing": "tile.null.name"}
        g.relabel_unlocalized()
        self.assertIn("mod:thing", g.names)
        self.assertIn("mod:thing", g.labels)

    def test_a_variant_of_an_unlocalized_stem_still_names_its_variant(self):
        """Both the stem and the variant are junk, and the two passes have to agree
        whichever order the dict yields them in."""
        for order in (("mod:thing", "mod:thing#a3f19c02b8d1"),
                      ("mod:thing#a3f19c02b8d1", "mod:thing")):
            g = Graph()
            g.names = {k: "tile.null.name" for k in order}
            g.relabel_unlocalized()
            self.assertEqual(g.bare_name("mod:thing#a3f19c02b8d1"),
                             "Thing (variant a3f19c)", order)

    def test_a_localized_stem_still_wins_for_its_variant(self):
        g = Graph()
        g.names = {"mod:thing": "Real Thing", "mod:thing#a3f19c02b8d1": "tile.null.name"}
        g.relabel_unlocalized()
        self.assertEqual(g.bare_name("mod:thing#a3f19c02b8d1"),
                         "Real Thing (variant a3f19c)")

    def test_meta_survives_the_fallback(self):
        g = Graph()
        g.names = {"mod:thing:3": "tile.null.name"}
        g.relabel_unlocalized()
        self.assertEqual(g.bare_name("mod:thing:3"), "Thing (3)")

    def test_an_acronym_is_not_title_cased_into_nonsense(self):
        g = Graph()
        g.names = {"mod:TBU_block": "tile.null.name"}
        g.relabel_unlocalized()
        self.assertEqual(g.bare_name("mod:TBU_block"), "TBU Block")

    def test_name_to_key_lookup_stops_resolving_the_lang_key(self):
        """`build_reverse` mapped the string "tile.null.name" onto 268 unrelated items.

        Moved onto `explore.resolve_query` when #107 deleted `names.resolve`: the claim is
        about relabelling, not about which resolver asks, and it has to keep holding for
        whichever one the CLI actually calls.
        """
        g = Graph()
        g.names = {"mod:a": "tile.null.name", "mod:b": "tile.null.name"}
        g.add(Recipe("r", "t", [("mod:out", 1)],
                     [Ingredient(["mod:a"], 1), Ingredient(["mod:b"], 1)]))
        g.relabel_unlocalized()
        self.assertEqual(explore.resolve_query(g, "tile.null.name"), [])
        self.assertEqual(explore.resolve_query(g, "A"), ["mod:a"])

    def test_an_item_with_no_name_at_all_also_reads_as_its_path(self):
        """174 recipe-referenced keys have no name from any source. They used to render
        as a raw id next to properly-cased names."""
        self.assertEqual(Graph().bare_name("minecraft:scroll_buff"), "Scroll Buff")

    def test_display_does_not_bracket_a_relabelled_item_as_a_non_item(self):
        """`display` decides the `[fluid]`/`[oredict]` prefix by whether the key is in
        `names`, so relabelling in place (rather than deleting) is what keeps an item
        from suddenly reading as a bracketed type."""
        g = Graph()
        g.names = {"mod:thing": "tile.null.name"}
        g.relabel_unlocalized()
        self.assertEqual(g.display("mod:thing"), "Thing")
        # And a key no source ever named still gets no bogus type prefix.
        self.assertEqual(Graph().display("mod:thing"), "Thing")
        # while a genuine non-item keeps the prefix it has always had
        self.assertEqual(Graph().display("fluid:boric_acid"), "[fluid] Boric Acid")

    def test_path_of_strips_modid_meta_and_discriminator(self):
        self.assertEqual(path_of("mod:thing"), "thing")
        self.assertEqual(path_of("mod:thing:3"), "thing")
        self.assertEqual(path_of("mod:thing:*"), "thing")
        self.assertEqual(path_of("mod:thing#a3f19c02b8d1"), "thing")
        self.assertEqual(path_of("mod:thing:3#a3f19c02b8d1"), "thing")
        self.assertEqual(path_of("fluid:boric_acid"), "boric_acid")
        # No colon at all: the whole string is the path, not an empty one.
        self.assertEqual(path_of("thing"), "thing")

    def test_loading_a_graph_json_relabels_without_a_rebuild(self):
        """The fix has to reach the 115 MB file already on disk: rebuilding needs the game
        running and a fresh dump."""
        d = tempfile.mkdtemp()
        path = os.path.join(d, "graph.json")
        with open(path, "w") as fh:
            json.dump({"recipes": [], "names": {"mod:thing": "tile.null.name"}}, fh)
        self.assertEqual(Graph.load(path).bare_name("mod:thing"), "Thing")


class TestOredictGuess(unittest.TestCase):
    def test_common_forms(self):
        names = {
            "minecraft:iron_ingot": "Iron Ingot",
            "minecraft:iron_block": "Block of Iron",
            "nuclearcraft:compound:7": "Borax",
        }
        got = guess_from_names(["ingotIron", "blockIron", "dustBorax"], names)
        self.assertEqual(got["ingotIron"], ["minecraft:iron_ingot"])
        self.assertEqual(got["blockIron"], ["minecraft:iron_block"])
        # bare-name form: NuclearCraft calls dustBorax simply "Borax"
        self.assertEqual(got["dustBorax"], ["nuclearcraft:compound:7"])

    def test_unknown_prefix_is_not_guessed(self):
        self.assertEqual(guess_from_names(["netherStar"], {"x:y": "Nether Star"}), {})


if __name__ == "__main__":
    unittest.main()


class UnreadSubdirJarsTest(unittest.TestCase):
    """`jar_json.extract` reads `mods/*.jar` flat, but Forge ALSO loads `mods/<mcversion>/`.

    The gap is silent by construction, so these pin the REPORT rather than the read. The
    reference pack has 13 jars in `mods/1.12.2/` carrying zero recipe files, which is why
    recursing was rejected: it would move every plan fixture to cover a case nobody has
    created yet.
    """

    def _mods(self, layout):
        """`{subdir_or_None: {jarname: [entry, ...]}}` -> a mods dir on disk."""
        import zipfile as zf
        d = tempfile.mkdtemp()
        mods = os.path.join(d, "mods")
        os.makedirs(mods)
        for sub, jars in layout.items():
            target = mods if sub is None else os.path.join(mods, sub)
            os.makedirs(target, exist_ok=True)
            for name, entries in jars.items():
                with zf.ZipFile(os.path.join(target, name), "w") as z:
                    for e in entries:
                        z.writestr(e, "{}")
        return mods

    def test_a_subdirectory_of_recipeless_jars_is_reported_as_costing_nothing(self):
        mods = self._mods({None: {"a.jar": []},
                           "1.12.2": {"scala.jar": ["scala/Predef.class"]}})
        self.assertEqual(jar_json.unread_subdir_jars(mods), [("1.12.2", 1, 0)])

    def test_a_subdirectory_holding_recipes_reports_the_count_that_is_being_lost(self):
        """The whole point: this is the case that must not stay silent."""
        mods = self._mods({None: {"a.jar": []},
                           "1.12.2": {"m.jar": ["assets/m/recipes/x.json",
                                                "assets/m/recipes/y.json",
                                                "assets/m/textures/z.png"]}})
        self.assertEqual(jar_json.unread_subdir_jars(mods), [("1.12.2", 1, 2)])

    def test_those_recipes_really_are_absent_from_extract(self):
        """The report would be pointless if `extract` picked them up anyway.

        This is the assertion that makes the other two mean something: it establishes the
        loss the report describes, rather than trusting the docstring.
        """
        mods = self._mods({None: {},
                           "1.12.2": {"m.jar": ["assets/m/recipes/x.json"]}})
        self.assertEqual(list(jar_json.extract(mods)), [])
        self.assertEqual(jar_json.unread_subdir_jars(mods)[0][2], 1)

    def test_a_subdirectory_with_no_jars_at_all_is_not_reported(self):
        mods = self._mods({None: {"a.jar": []}, "config": {}})
        os.makedirs(os.path.join(mods, "memory_repo", "net"), exist_ok=True)
        self.assertEqual(jar_json.unread_subdir_jars(mods), [])

    def test_the_worst_subdirectory_leads(self):
        mods = self._mods({None: {},
                           "aaa": {"m.jar": ["assets/m/recipes/x.json"]},
                           "zzz": {"n.jar": []}})
        self.assertEqual([r[0] for r in jar_json.unread_subdir_jars(mods)], ["aaa", "zzz"])

    def test_a_corrupt_jar_in_a_subdirectory_does_not_abort_the_report(self):
        mods = self._mods({None: {}, "1.12.2": {"good.jar": ["assets/m/recipes/x.json"]}})
        with open(os.path.join(mods, "1.12.2", "bad.jar"), "w") as fh:
            fh.write("not a zip")
        self.assertEqual(jar_json.unread_subdir_jars(mods), [("1.12.2", 2, 1)])

    def test_extract_and_the_subdir_check_share_one_predicate(self):
        """Two copies of "is this a recipe entry" is how they stop agreeing.

        Derived from the shapes rather than hand-picked, so a change to the predicate cannot
        satisfy this by accident.
        """
        yes = ["assets/m/recipes/x.json", "assets/m/recipes/sub/x.json"]
        no = ["assets/m/recipes/x.txt", "m/recipes/x.json", "assets/m/textures/x.json",
              "assets/m/recipes/", "recipes/x.json"]
        for e in yes:
            self.assertTrue(jar_json._is_recipe_entry(e), e)
        for e in no:
            self.assertFalse(jar_json._is_recipe_entry(e), e)
        # and `extract` must agree, via the same function rather than a second copy
        mods = self._mods({None: {"m.jar": yes + no}})
        self.assertEqual(len(list(jar_json.extract(mods))), 0)  # `{}` parses to no recipe
