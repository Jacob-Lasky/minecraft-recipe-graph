"""Everything the python side learned from dump schema 5. #90 #118 #55 #50 #36

ONE FILE FOR FIVE ISSUES, because they share the property that decides how they are tested:
each is a file the dump may or may not have written, and the thing most likely to go wrong
is not the parse but the ABSENCE -- a graph built from an older dump has to go on behaving
exactly as it did, and a feature that quietly half-works on missing data is worse than one
that is off. So every section here asserts the empty case as hard as the populated one.
"""

import json
import os
import shutil
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, explore, iconset, png, projecte  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import STATUS_EMC, Solver  # noqa: E402
from recipegraph.sources import damageable, dump_meta, emc, icons, machine_names  # noqa: E402


def _write(root, name, doc):
    path = os.path.join(root, name)
    with open(path, "w") as fh:
        json.dump(doc, fh)
    return path


class DamageableTest(unittest.TestCase):
    """#118: whether an item's metadata is its durability."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)

    def test_it_reads_only_items_whose_meta_is_damage(self):
        path = _write(self.root, "damageable.json", {
            "minecraft:iron_axe": {"d": 250, "s": False},
            # maxDamage 0: the meta is a subtype. This is the chisel/spell-book case and it
            # must not be listed, or nine different blocks collapse into one row.
            "chisel:lapis": {"d": 0, "s": True},
            # Damageable AND subtyped. Some of its metas are wear and some are variants and
            # nothing can say which, so the honest answer is to decline.
            "mod:hybrid": {"d": 100, "s": True},
        })
        self.assertEqual(damageable.load(path), {"minecraft:iron_axe": 250})

    def test_a_missing_or_broken_file_is_simply_empty(self):
        self.assertEqual(damageable.load(None), {})
        self.assertEqual(damageable.load(os.path.join(self.root, "nope.json")), {})
        broken = os.path.join(self.root, "damageable.json")
        with open(broken, "w") as fh:
            fh.write("{not json")
        self.assertEqual(damageable.load(broken), {})

    def test_damage_base_collapses_wear_and_nothing_else(self):
        g = Graph()
        g.max_damage = {"minecraft:iron_axe": 250}
        self.assertEqual(g.damage_base("minecraft:iron_axe:187"), "minecraft:iron_axe")
        self.assertEqual(g.damage_base("minecraft:iron_axe"), "minecraft:iron_axe")
        # Not damageable, so its meta is a real subtype and survives. #110.
        self.assertEqual(g.damage_base("chisel:lapis:3"), "chisel:lapis:3")

    def test_the_nbt_digest_survives_the_collapse(self):
        # A named or enchanted axe is still its own ingredient; only the durability tick is
        # noise. Stripping the digest here would merge it with every other iron axe.
        g = Graph()
        g.max_damage = {"minecraft:iron_axe": 250}
        self.assertEqual(g.damage_base("minecraft:iron_axe:187#abc123"),
                         "minecraft:iron_axe#abc123")

    def test_an_empty_map_changes_nothing(self):
        g = Graph()
        self.assertEqual(g.damage_base("minecraft:iron_axe:187"), "minecraft:iron_axe:187")
        self.assertIsNone(g.damage_of("minecraft:iron_axe:187"))

    def test_the_label_says_the_number_is_durability(self):
        g = Graph()
        g.max_damage = {"minecraft:iron_axe": 250}
        g.names = {"minecraft:iron_axe": "Iron Axe"}
        self.assertEqual(g.bare_name("minecraft:iron_axe:187"), "Iron Axe (187/250 damage)")
        # A meta that is NOT durability keeps the bare number, because that is all anyone
        # can honestly say about it.
        self.assertEqual(g.bare_name("chisel:lapis:3"), "Lapis (3)")


class DamageCollapseInSearchTest(unittest.TestCase):
    """#118's reported symptom: 46 rows called Iron Axe with the stock on one of them."""

    def _graph(self):
        g = Graph()
        g.max_damage = {"minecraft:iron_axe": 250}
        g.names = {"minecraft:iron_axe": "Iron Axe"}
        for meta in (1, 2, 41, 187, 250):
            g.names["minecraft:iron_axe:%d" % meta] = "Iron Axe"
        # One recipe so every key is live and none is filtered as dead.
        g.add(Recipe("r1", "test", [(k, 1) for k in g.names],
                     [Ingredient(["minecraft:iron_ingot"], 3)]))
        return g

    def test_one_row_survives_and_the_rest_are_reported(self):
        hits = explore.rank_matches(self._graph(), "iron axe", have={})
        self.assertEqual(hits.results, ["minecraft:iron_axe"])
        self.assertEqual(hits.collapsed, 5)

    def test_the_survivor_is_the_one_with_the_stock(self):
        """Not the undamaged key, and this is the whole reason the collapse is not two lines.

        Rows arrive sorted and the undamaged key sorts first, so keeping the first would
        show "Iron Axe, 0 in stock" to a player whose axes are all half-worn -- a new wrong
        answer in place of the old noisy one.
        """
        hits = explore.rank_matches(self._graph(), "iron axe",
                                    have={"minecraft:iron_axe:187": 4})
        self.assertEqual(hits.results, ["minecraft:iron_axe:187"])

    def test_without_the_dump_file_every_row_stays(self):
        g = self._graph()
        g.max_damage = {}
        hits = explore.rank_matches(g, "iron axe", have={})
        self.assertEqual(len(hits.results), 6)
        self.assertEqual(hits.collapsed, 0)

    def test_the_count_is_reported_not_silent(self):
        from recipegraph import present
        self.assertIn("folded in", present.hidden_note(0, 5))
        self.assertEqual(present.hidden_note(0, 0), "")


class MachineNamesTest(unittest.TestCase):
    """#55: which of the 261 items called "Machine Blueprint" is this one."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)

    def test_it_reads_both_maps(self):
        path = _write(self.root, "machine_names.json", {
            "machines": {"modularmachinery:dragonfire_crucible": "Dragonfire Crucible"},
            "blueprints": {"modularmachinery:itemblueprint#010c58f252c0":
                           "modularmachinery:dragonfire_crucible"},
        })
        machines, blueprints = machine_names.load(path)
        self.assertEqual(machines,
                         {"modularmachinery:dragonfire_crucible": "Dragonfire Crucible"})
        self.assertEqual(blueprints["modularmachinery:itemblueprint#010c58f252c0"],
                         "modularmachinery:dragonfire_crucible")

    def test_a_missing_file_is_two_empty_maps(self):
        self.assertEqual(machine_names.load(None), ({}, {}))

    def test_the_blueprint_is_named_after_the_machine_it_builds(self):
        g = Graph()
        g.names = {"modularmachinery:itemblueprint#010c58f252c0": "Machine Blueprint"}
        g.machine_names = {"modularmachinery:dragonfire_crucible": "Dragonfire Crucible"}
        g.blueprint_machines = {"modularmachinery:itemblueprint#010c58f252c0":
                                "modularmachinery:dragonfire_crucible"}
        self.assertEqual(g.bare_name("modularmachinery:itemblueprint#010c58f252c0"),
                         "Machine Blueprint (Dragonfire Crucible)")

    def test_an_unmapped_blueprint_keeps_its_own_name(self):
        # No data means the OLD behaviour, not a decorated absence: "Machine Blueprint ()"
        # would be worse than the ambiguity it was trying to resolve.
        g = Graph()
        g.names = {"modularmachinery:itemblueprint#deadbeef": "Machine Blueprint"}
        g.blueprint_machines = {"modularmachinery:itemblueprint#deadbeef":
                                "modularmachinery:nothing_named_this"}
        self.assertEqual(g.bare_name("modularmachinery:itemblueprint#deadbeef"),
                         "Machine Blueprint")

    def test_the_name_stays_searchable_under_both_words(self):
        """Why the parenthetical form won, which #55 left open.

        `labels` indexes exactly this string, so the parenthetical is findable under the
        name the game shows AND the name in the player's JEI. "Dragonfire Crucible
        Blueprint" would be findable under neither of the two words a player holding a
        blueprint would type.
        """
        g = Graph()
        g.names = {"modularmachinery:itemblueprint#010c58f252c0": "Machine Blueprint"}
        g.machine_names = {"modularmachinery:dragonfire_crucible": "Dragonfire Crucible"}
        g.blueprint_machines = {"modularmachinery:itemblueprint#010c58f252c0":
                                "modularmachinery:dragonfire_crucible"}
        label = g.bare_name("modularmachinery:itemblueprint#010c58f252c0").lower()
        self.assertIn("machine blueprint", label)
        self.assertIn("dragonfire crucible", label)


class EmcTest(unittest.TestCase):
    """#50: a drop-only item the transmutation network can simply make."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)

    def test_only_positive_values_are_kept(self):
        path = _write(self.root, "emc.json", {
            "erebus:materials:6": 2048,
            # ProjectE answers 0 for "cannot be transmuted", which is also what the mod's
            # bridge answers when the lookup throws. Absence must never read as a route.
            "mod:disabled": 0,
            "mod:negative": -5,
            "mod:notanumber": "2048",
            # bool is an int in python; True would price something at 1 EMC.
            "mod:boolean": True,
        })
        self.assertEqual(emc.load(path), {"erebus:materials:6": 2048})

    def _graph(self):
        g = Graph()
        g.emc = {"erebus:materials": 2048}
        g.names = {"erebus:materials": "Exoskeleton Plate"}
        # Its only "recipe" is the pack's dungeon-drop pseudo-item, which is exactly the
        # dead end #50 was reported for.
        g.add(Recipe("drop", "test", [("erebus:materials", 1)],
                     [Ingredient(["contenttweaker:dungeon_drop"], 1)]))
        return g

    def test_availability_needs_both_the_value_and_the_knowledge(self):
        g = self._graph()
        self.assertEqual(projecte.available(g, {}), set())
        self.assertEqual(
            projecte.available(g, {"learned": ["erebus:materials"]}),
            {"erebus:materials"})
        # Learned, but the pack gives it no value: NOT available. This is the clause that
        # stops #50 asserting a route the pack has disabled.
        self.assertEqual(
            projecte.available(g, {"learned": ["mod:disabled"]}), set())

    def test_full_knowledge_covers_everything_with_a_value(self):
        g = self._graph()
        self.assertEqual(projecte.available(g, {"full": True}), {"erebus:materials"})

    def test_a_graph_with_no_emc_table_is_never_available(self):
        g = self._graph()
        g.emc = {}
        self.assertEqual(projecte.available(g, {"full": True}), set())

    def test_the_plan_stops_at_the_transmutation_instead_of_the_loot_token(self):
        g = self._graph()
        solver = Solver(g, emc_available={"erebus:materials"})
        result = solver.solve("erebus:materials", 3)
        self.assertEqual(result["tree"]["status"], STATUS_EMC)
        self.assertEqual(result["tree"].get("children") or [], [])
        self.assertEqual([(r["key"], r["qty"], r["emc"]) for r in result["from_emc"]],
                         [("erebus:materials", 3, 2048)])
        # And it is NOT on the shopping list: it is not a thing to go and get.
        self.assertEqual(result["shopping_list"], [])

    def test_without_knowledge_it_dead_ends_exactly_as_before(self):
        result = Solver(self._graph()).solve("erebus:materials", 3)
        self.assertNotEqual(result["tree"]["status"], STATUS_EMC)
        self.assertEqual(result.get("from_emc"), [])

    def test_stock_is_spent_before_emc(self):
        # Spending what you already hold beats spending EMC, and `take` has drawn the pool
        # down so only the shortfall is charged to the network.
        g = self._graph()
        solver = Solver(g, have={"erebus:materials": 2},
                        emc_available={"erebus:materials"})
        result = solver.solve("erebus:materials", 5)
        self.assertEqual(result["tree"]["from_stock"], 2)
        self.assertEqual(result["from_emc"][0]["qty"], 3)

    def test_the_cost_ordering_is_the_claim(self):
        """#95's rule: what is asserted is the ORDER, not the magnitudes.

        Above stock and an infinite generator because EMC is spent and has to be earned
        back; below a raw leaf because the alternative to transmuting a learned item is
        going and farming the dungeon it drops in, which is the whole report.
        """
        from recipegraph.generators import SOURCE_COST
        self.assertLess(0.0, SOURCE_COST)
        self.assertLess(SOURCE_COST, cost.EMC_COST)
        self.assertLess(cost.EMC_COST, cost.BASE_RAW_COST)

    def test_the_seed_prices_a_learned_item_below_a_raw_leaf(self):
        g = self._graph()
        table = cost.estimate(g, emc_available={"erebus:materials"})
        self.assertEqual(table["erebus:materials"], cost.EMC_COST)
        plain = cost.estimate(g)
        self.assertGreater(plain["erebus:materials"], cost.EMC_COST)

    def test_the_fingerprint_moves_when_knowledge_moves(self):
        """Otherwise a cache written before an item was learned prices it as a drop forever.

        Nothing else in the fingerprint moves when a player learns something -- not the
        graph, not the stock, not a constant -- so without this the fix reads as "#50 does
        not work" rather than as a stale cache.
        """
        args = ("data/graph.json", {}, {}, ())
        bare = cost.fingerprint(*args)
        learned = cost.fingerprint(*args, emc_available={"erebus:materials"})
        self.assertNotEqual(bare, learned)


class ProjectEPlayerDataTest(unittest.TestCase):
    """Reading transmutation knowledge out of the save, not out of the graph. #50 #112"""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)

    def _player(self, name, learned, banked, full=False):
        import gzip

        from recipegraph import anvil_nbt as nbt
        os.makedirs(os.path.join(self.root, "playerdata"), exist_ok=True)
        stacks = b"".join(self._compound(entry) for entry in learned)
        # Hand-built NBT: a root compound holding ForgeCaps -> projecte:knowledge.
        cap = (b"\x09" + self._str("knowledge") + b"\x0a"
               + struct.pack(">i", len(learned)) + stacks
               + b"\x04" + self._str("transmutationEmc") + struct.pack(">q", banked)
               + b"\x01" + self._str("fullknowledge") + bytes([1 if full else 0])
               + b"\x00")
        caps = b"\x0a" + self._str("projecte:knowledge") + cap + b"\x00"
        root = b"\x0a" + self._str("") + b"\x0a" + self._str("ForgeCaps") + caps + b"\x00"
        path = os.path.join(self.root, "playerdata", name)
        with open(path, "wb") as fh:
            fh.write(gzip.compress(root))
        # Sanity: the fixture has to be readable by the project's own NBT reader, or the
        # test would be asserting against a format nothing produces.
        with open(path, "rb") as fh:
            self.assertIn("ForgeCaps", nbt.parse_nbt(gzip.decompress(fh.read())))
        return path

    @staticmethod
    def _str(text):
        raw = text.encode("utf-8")
        return struct.pack(">H", len(raw)) + raw

    def _compound(self, item_id):
        return (b"\x08" + self._str("id") + self._str(item_id)
                + b"\x01" + self._str("Count") + b"\x01"
                + b"\x02" + self._str("Damage") + struct.pack(">h", 0)
                + b"\x00")

    def test_it_reads_the_capability_out_of_playerdata(self):
        self._player("a.dat", ["minecraft:diamond", "erebus:materials"], 1024)
        got = projecte.read_knowledge(self.root)
        self.assertEqual(got["players"], 1)
        self.assertEqual(got["learned"], ["erebus:materials", "minecraft:diamond"])
        self.assertEqual(got["emc"], 1024)
        self.assertFalse(got["full"])

    def test_knowledge_is_unioned_and_emc_is_the_largest_single_balance(self):
        """A shared base is shared, but nobody's wallet is anyone else's.

        Summing the balances would report buying power no single account has, which is a
        number a player could act on and be wrong about.
        """
        self._player("a.dat", ["minecraft:diamond"], 1000)
        self._player("b.dat", ["erebus:materials"], 250)
        got = projecte.read_knowledge(self.root)
        self.assertEqual(got["players"], 2)
        self.assertEqual(got["learned"], ["erebus:materials", "minecraft:diamond"])
        self.assertEqual(got["emc"], 1000)

    def test_a_save_with_no_projecte_reports_nothing_rather_than_failing(self):
        self.assertEqual(projecte.read_knowledge(self.root)["learned"], [])
        self.assertEqual(projecte.read_knowledge(None)["players"], 0)

    def test_one_corrupt_player_file_does_not_take_down_the_scan(self):
        # `have` has just spent seven minutes over 1,536 region files; losing that to a
        # half-written player .dat would be a poor trade for one player's knowledge.
        self._player("good.dat", ["minecraft:diamond"], 5)
        os.makedirs(os.path.join(self.root, "playerdata"), exist_ok=True)
        with open(os.path.join(self.root, "playerdata", "bad.dat"), "wb") as fh:
            fh.write(b"\x1f\x8b truncated nonsense")
        got = projecte.read_knowledge(self.root)
        self.assertEqual(got["players"], 1)
        self.assertEqual(got["learned"], ["minecraft:diamond"])

    def test_keys_are_spelled_the_way_the_graph_spells_them(self):
        """The #21 failure in miniature: a reader whose keys do not match matches nothing,
        and reports a healthy count while doing it."""
        from recipegraph.ae2_inventory import stack_key
        self.assertEqual(stack_key({"id": "minecraft:diamond"}), "minecraft:diamond")
        self.assertEqual(stack_key({"id": "minecraft:wool", "Damage": 3}),
                         "minecraft:wool:3")


class IconIndexTest(unittest.TestCase):
    """#36: the atlas index, and what happens when a page is missing."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)

    def _index(self, **over):
        doc = {"icon": 16, "page": 32, "cols": 2, "pages": ["icons-0.png"],
               "keys": {"minecraft:stone": [0, 1, 1]}}
        doc.update(over)
        return _write(self.root, "icons.json", doc)

    def test_it_reads_a_well_formed_index(self):
        got = icons.load(self._index())
        self.assertEqual(got["icon"], 16)
        self.assertEqual(got["keys"]["minecraft:stone"], [0, 1, 1])

    def test_an_entry_naming_a_page_that_is_not_there_is_dropped(self):
        got = icons.load(self._index(keys={"minecraft:stone": [0, 1, 1],
                                           "mod:ghost": [9, 0, 0],
                                           "mod:offgrid": [0, 99, 0]}))
        self.assertEqual(sorted(got["keys"]), ["minecraft:stone"])

    def test_a_missing_or_malformed_index_is_empty(self):
        self.assertEqual(icons.load(None), {})
        self.assertEqual(icons.load(self._index(pages=[])), {})
        self.assertEqual(icons.load(self._index(icon=0)), {})
        self.assertEqual(icons.load(self._index(keys={})), {})

    def test_a_page_that_cannot_be_copied_takes_its_sprites_with_it(self):
        """And every surviving entry is RENUMBERED.

        Page indexes shift when one is dropped. Left alone, every key after the gap points
        at whatever now sits at its old position -- which draws a plausible wrong picture,
        the one failure mode worse than no picture at all.
        """
        blob = png.encode(32, 32, bytearray(b"\x00\x00\x00\xff" * 32 * 32))
        with open(os.path.join(self.root, "icons-1.png"), "wb") as fh:
            fh.write(blob)
        index = {"icon": 16, "cols": 2, "pages": ["icons-0.png", "icons-1.png"],
                 "keys": {"gone": [0, 0, 0], "kept": [1, 0, 0]}}
        out = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, out)
        said = []
        got = icons.copy_pages(index, self.root, out, said.append)
        self.assertEqual(got["pages"], ["icons-1.png"])
        self.assertEqual(got["keys"], {"kept": [0, 0, 0]})
        self.assertTrue(any("icons-0.png" in m for m in said), said)


class IconDeliveryTest(unittest.TestCase):
    """The two delivery shapes, and that neither can produce the other's output."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)
        # A 32x32 page of four 16x16 sprites; cell (1,0) is opaque red, the rest transparent.
        rgba = bytearray(32 * 32 * 4)
        for y in range(16):
            for x in range(16, 32):
                o = (y * 32 + x) * 4
                rgba[o:o + 4] = bytes([255, 0, 0, 255])
        with open(os.path.join(self.root, "icons-0.png"), "wb") as fh:
            fh.write(png.encode(32, 32, rgba))
        self.g = Graph()
        self.g.icons = {"icon": 16, "cols": 2, "pages": ["icons-0.png"],
                        "keys": {"minecraft:stone": [0, 1, 0],
                                 "mod:blank": [0, 0, 1]}}

    def test_the_served_shape_positions_an_atlas_page(self):
        style = iconset.css(self.g, "minecraft:stone")
        self.assertIn("/icons/0.png", style)
        self.assertIn("-16px -0px", style)
        self.assertIn('<span class="ico"', iconset.resolver(self.g)("minecraft:stone"))

    def test_the_standalone_shape_inlines_the_sprite_and_references_no_host(self):
        """The CSP constraint, asserted as a property rather than by eye.

        A plan publishes as a Claude Artifact under a policy that blocks every off-host
        request, so an inlined document containing a URL to this server would draw a broken
        image in every row -- and only once published.
        """
        html = iconset.resolver(self.g, self.root, inline=True)("minecraft:stone")
        self.assertIn("data:image/png;base64,", html)
        self.assertNotIn(iconset.URL_PREFIX, html)
        blob = __import__("base64").b64decode(html.split("base64,")[1].split('"')[0])
        w, h, rgba = png.decode(blob)
        self.assertEqual((w, h), (16, 16))
        self.assertEqual(list(rgba[:4]), [255, 0, 0, 255])

    def test_a_blank_sprite_yields_no_icon(self):
        # A hole where the neighbouring rows have art reads worse than no icons at all,
        # which is #36's own conclusion.
        self.assertEqual(iconset.resolver(self.g, self.root, inline=True)("mod:blank"), "")

    def test_a_key_with_no_sprite_yields_no_icon_in_either_shape(self):
        self.assertEqual(iconset.resolver(self.g)("mod:unknown"), "")
        self.assertEqual(iconset.resolver(self.g, self.root, inline=True)("mod:unknown"), "")

    def test_a_graph_with_no_atlas_yields_a_resolver_that_draws_nothing(self):
        self.assertEqual(iconset.resolver(Graph())("minecraft:stone"), "")

    def test_a_worn_tool_draws_its_undamaged_sprite(self):
        # The atlas is rendered per base key with damage collapsed, so a lookup that did not
        # collapse first would miss every worn tool.
        self.g.max_damage = {"minecraft:iron_axe": 250}
        self.g.icons["keys"]["minecraft:iron_axe"] = [0, 1, 0]
        self.assertEqual(iconset.locate(self.g, "minecraft:iron_axe:187"), (0, 1, 0))

    def test_a_page_index_outside_the_atlas_serves_nothing(self):
        # The server passes a number straight off the URL; it must never become a path.
        self.assertIsNone(iconset.page_bytes(self.g, 7, self.root))
        self.assertIsNone(iconset.page_bytes(self.g, -1, self.root))
        self.assertIsNotNone(iconset.page_bytes(self.g, 0, self.root))

    def test_a_corrupt_page_costs_the_icons_and_nothing_else(self):
        with open(os.path.join(self.root, "icons-0.png"), "wb") as fh:
            fh.write(b"\x89PNG\r\n\x1a\n" + b"garbage" * 20)
        self.assertEqual(iconset.resolver(self.g, self.root, inline=True)("minecraft:stone"),
                         "")


class GraphRoundTripTest(unittest.TestCase):
    """Every schema-5 field has to survive `save`/`load`, and be inert when absent."""

    def test_the_new_fields_persist(self):
        g = Graph()
        g.max_damage = {"minecraft:iron_axe": 250}
        g.machine_names = {"modularmachinery:x": "X"}
        g.blueprint_machines = {"modularmachinery:itemblueprint#a": "modularmachinery:x"}
        g.emc = {"minecraft:diamond": 8192}
        g.icons = {"icon": 16, "cols": 2, "pages": ["icons-0.png"], "keys": {"a": [0, 0, 0]}}
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            g.save(path)
            back = Graph.load(path)
        self.assertEqual(back.max_damage, g.max_damage)
        self.assertEqual(back.machine_names, g.machine_names)
        self.assertEqual(back.blueprint_machines, g.blueprint_machines)
        self.assertEqual(back.emc, g.emc)
        self.assertEqual(back.icons, g.icons)

    def test_a_schema_four_graph_still_loads_with_every_feature_off(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            with open(path, "w") as fh:
                json.dump({"recipes": [], "names": {}, "dump_schema": 4}, fh)
            g = Graph.load(path)
        self.assertEqual((g.max_damage, g.machine_names, g.blueprint_machines,
                          g.emc, g.icons), ({}, {}, {}, {}, {}))


class SchemaStampTest(unittest.TestCase):
    """THE SCHEMA NUMBER ITSELF IS PINNED IN `test_schema_six.py`, NOT HERE.

    It used to be, as `test_the_python_side_expects_five`, and that is a literal that goes
    stale in a file named for the number it holds: bumping the schema then has to edit an
    assertion whose own name lies about what it checks. It moves with the current schema.
    What stays here is the 4-to-5 severity distinction, which is about schema 5 and remains
    true however far the number travels past it.
    """

    def test_a_schema_four_dump_is_a_missing_FIELDS_warning_not_a_digest_one(self):
        """Schema 5 changed shapes, not the digest, so it must not fire the loud warning.

        `describe`'s digest sentence says "your stock reads as zero until you redump", which
        is true across schema 3 -> 4 and false across 4 -> 5. A warning that cries wolf gets
        trained away before the one time it matters, which is why DIGEST_FORMAT_SCHEMA
        stayed at 4.
        """
        said = dump_meta.describe({"present": True, "mod_version": "0.8.0", "schema": 4})
        self.assertIn("newer fields", said)
        self.assertNotIn("OLDER THAN THE DIGEST FORMAT", said)
        stale = dump_meta.describe({"present": True, "mod_version": "0.7.0", "schema": 3})
        self.assertIn("OLDER THAN THE DIGEST FORMAT", stale)


class SkipCountTest(unittest.TestCase):
    """#90: the number, its name, and the sentence built from it."""

    def test_the_new_field_is_read_and_the_old_one_still_works(self):
        from recipegraph import gaps
        skips = [{"reason": "no outputs"}] * 7
        new = gaps.analyse({"recipes": 10, "threw": 2, "skip_lines": 7}, skips)
        self.assertEqual((new["threw"], new["skip_lines"]), (2, 7))
        # Four dumps on disk in the reference instance predate the rename, and the
        # compatibility read costs one `or`.
        old = gaps.analyse({"recipes": 10, "skipped": 2}, skips)
        self.assertEqual((old["threw"], old["skip_lines"]), (2, 7))

    def test_the_report_gives_two_numbers_rather_than_one(self):
        from recipegraph import gaps
        skips = [{"reason": "no outputs"}] * 22188
        said = gaps.report(gaps.analyse({"recipes": 117681, "threw": 0}, skips))
        # The defect: "0 skipped, all recorded in skipped.ndjson" alongside a file with
        # 22,188 lines in it.
        self.assertIn("0 wrappers threw", said)
        self.assertIn("22,188 lines in skipped.ndjson", said)


if __name__ == "__main__":
    unittest.main()


class BuildWiresTheSchemaFiveFilesInTest(unittest.TestCase):
    """Four lines in `index.build`, and dropping any of them is SILENT.

    Nothing raises and no other test fails: the graph simply comes out with the feature off,
    which is indistinguishable from a graph built before schema 5. That is the exact shape
    `tests/test_dimensions.py` keeps a wiring test for, and the exact shape #110 and #112
    both shipped invisibly in -- merged, deployed, and absent from the served data because
    nothing between the reader and the graph asserted the connection.
    """

    def _instance(self):
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        dump = os.path.join(root, "mc-recipe-dump")
        os.makedirs(dump)
        with open(os.path.join(dump, "recipes.ndjson"), "w") as fh:
            fh.write(json.dumps({
                "cat": "minecraft.crafting", "title": "Crafting",
                "in": [[{"i": "minecraft:iron_ingot", "m": 0, "c": 3}]],
                "out": [{"i": "minecraft:iron_axe", "m": 0, "c": 1}],
                "fin": [], "fout": []}) + "\n")
        _write(dump, "summary.json", {"mod_version": "0.9.0", "schema": 5,
                                      "recipes": 1, "threw": 0, "skip_lines": 0,
                                      "categories": {}})
        _write(dump, "oredict.json", {})
        _write(dump, "names.json", {"minecraft:iron_axe": "Iron Axe"})
        _write(dump, "damageable.json", {"minecraft:iron_axe": {"d": 250, "s": False}})
        _write(dump, "emc.json", {"minecraft:iron_axe": 704})
        _write(dump, "machine_names.json", {
            "machines": {"modularmachinery:crucible": "Crucible"},
            "blueprints": {"modularmachinery:itemblueprint#aa": "modularmachinery:crucible"}})
        with open(os.path.join(dump, "icons-0.png"), "wb") as fh:
            fh.write(png.encode(32, 32, bytearray(b"\x10\x20\x30\xff" * 32 * 32)))
        _write(dump, "icons.json", {"icon": 16, "page": 32, "cols": 2,
                                    "pages": ["icons-0.png"],
                                    "keys": {"minecraft:iron_axe": [0, 0, 0]}})
        return root

    def test_a_built_graph_carries_all_five(self):
        from recipegraph import index
        root = self._instance()
        out = os.path.join(root, "data", "graph.json")
        g = index.build(root, quiet=True, out_path=out)
        self.assertEqual(g.dump_schema, 5)
        self.assertEqual(g.max_damage, {"minecraft:iron_axe": 250})
        self.assertEqual(g.emc, {"minecraft:iron_axe": 704})
        self.assertEqual(g.machine_names, {"modularmachinery:crucible": "Crucible"})
        self.assertEqual(g.blueprint_machines,
                         {"modularmachinery:itemblueprint#aa": "modularmachinery:crucible"})
        self.assertEqual(g.icons["keys"], {"minecraft:iron_axe": [0, 0, 0]})

    def test_the_atlas_pages_are_copied_beside_the_graph(self):
        """Otherwise the index points at files only the pack instance has.

        The server ships a graph and whatever sits next to it; the pack instance is on a
        different machine entirely. Left uncopied, every icon 404s on the one deployment
        that matters, and passes every test on the one that does not.
        """
        from recipegraph import index
        root = self._instance()
        out = os.path.join(root, "data", "graph.json")
        index.build(root, quiet=True, out_path=out)
        self.assertTrue(os.path.exists(os.path.join(root, "data", "icons-0.png")))

    def test_without_an_out_path_the_index_survives_but_nothing_is_copied(self):
        # `serve`'s rebuild and `build` both pass one; a caller that does not gets the index
        # and a sentence saying the pages were not copied, rather than a silent half-state.
        from recipegraph import index
        root = self._instance()
        g = index.build(root, quiet=True)
        self.assertEqual(g.icons["keys"], {"minecraft:iron_axe": [0, 0, 0]})
        self.assertFalse(os.path.exists(os.path.join(root, "data", "icons-0.png")))

    def test_a_dump_with_none_of_the_new_files_builds_exactly_as_before(self):
        from recipegraph import index
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root)
        dump = os.path.join(root, "mc-recipe-dump")
        os.makedirs(dump)
        _write(dump, "summary.json", {"mod_version": "0.8.0", "schema": 4, "recipes": 0,
                                      "skipped": 0, "categories": {}})
        g = index.build(root, quiet=True)
        self.assertEqual((g.max_damage, g.emc, g.machine_names, g.blueprint_machines,
                          g.icons), ({}, {}, {}, {}, {}))


class SweepVocabularyTest(unittest.TestCase):
    """#50's measurement is a sweep, so it has to be expressible as one.

    `/api/sweep?where=emc > 0 and producers == 0` names every item whose only route the
    graph knows is a drop and which the network could transmute instead. A question the
    server cannot answer is a missing FIELD, not a reason to write a script -- which is the
    standing rule this repo has for exactly this shape of question.
    """

    class _Ctx:
        def __init__(self, graph):
            self.graph = graph
            self.have = {}
            self.costs = {}

    def _graph(self):
        g = Graph()
        g.emc = {"erebus:materials": 2048}
        g.max_damage = {"minecraft:iron_axe": 250}
        g.icons = {"icon": 16, "cols": 2, "pages": ["icons-0.png"],
                   "keys": {"minecraft:iron_axe": [0, 0, 0]}}
        return g

    def test_the_new_facts_answer(self):
        from recipegraph import api
        ctx = self._Ctx(self._graph())
        self.assertEqual(api.FIELDS["emc"][0](ctx, "erebus:materials"), 2048)
        self.assertEqual(api.FIELDS["emc"][0](ctx, "minecraft:stone"), 0)
        self.assertTrue(api.FIELDS["icon"][0](ctx, "minecraft:iron_axe"))
        self.assertFalse(api.FIELDS["icon"][0](ctx, "minecraft:stone"))
        self.assertTrue(api.FIELDS["damaged"][0](ctx, "minecraft:iron_axe:187"))
        self.assertFalse(api.FIELDS["damaged"][0](ctx, "minecraft:iron_axe"))
        # A subtype meta is NOT a damage variant, which is the whole #110 distinction.
        self.assertFalse(api.FIELDS["damaged"][0](ctx, "chisel:lapis:3"))

    def test_every_field_carries_a_description(self):
        # `GET /api` prints the vocabulary and a field with no sentence is a field nobody
        # can discover, which is how a sweep ends up being written as a script instead.
        from recipegraph import api
        for name, (fn, doc) in api.FIELDS.items():
            self.assertTrue(callable(fn), name)
            self.assertTrue(doc and doc.strip(), name)


class IconsReachEveryRowTest(unittest.TestCase):
    """An icon on the card heading and none on the ingredients is half the feature.

    The first live render shipped exactly that: `named()` took an `icon` at the explore
    card's `<h3>`, in the plan tree and in the plan's tables, but `_ing_html`, `_makes_html`
    and `_used_html` built their rows without one -- so "Diamond" had a picture and the nine
    Diamond Nuggets that make it did not. The ingredient column is where an icon earns the
    most, because that is the list a player reads while gathering.
    """

    def _payload(self):
        return {"query": "diamond", "searched": 1, "named": 1, "hidden": 0, "collapsed": 0,
                "results": [{
                    "key": "minecraft:diamond", "name": "Diamond", "label": "Diamond",
                    "kind": "item", "stock": 3, "oredicts": [], "oredict_guessed": [],
                    "makes_total": 1, "used_in_total": 1,
                    "makes": [{"id": "r1", "category": "crafting", "machine": None,
                               "source": "test",
                               "outputs": [{"key": "minecraft:diamond", "name": "Diamond",
                                            "label": "Diamond", "kind": "item", "qty": 1}],
                               "inputs": [{"qty": 9, "alt_total": 1, "role": "item",
                                           "alts": [{"key": "minecraft:diamond_nugget",
                                                     "name": "Diamond Nugget",
                                                     "label": "Diamond Nugget",
                                                     "kind": "item", "stock": 0}]}]}],
                    "used_in": [{"id": "r2", "category": "crafting", "machine": None,
                                 "source": "test",
                                 "outputs": [{"key": "minecraft:diamond_block",
                                              "name": "Block of Diamond",
                                              "label": "Block of Diamond",
                                              "kind": "item", "qty": 1}]}],
                }]}

    def test_the_resolver_is_asked_for_the_ingredient_and_the_output_too(self):
        from recipegraph.render import render_explore_html
        asked = []

        def icon(key):
            asked.append(key)
            return '<span class="ico" data-k="%s"></span>' % key

        html = render_explore_html(self._payload(), icon=icon)
        for key in ("minecraft:diamond", "minecraft:diamond_nugget",
                    "minecraft:diamond_block"):
            self.assertIn(key, asked, "no icon was requested for %s" % key)
            self.assertIn('data-k="%s"' % key, html)

    def test_no_resolver_renders_exactly_as_before(self):
        # Every icon hook is opt-in: a caller that passes none gets the pre-#36 page rather
        # than an empty box where a picture would be.
        from recipegraph.render import render_explore_html
        self.assertNotIn('class="ico"', render_explore_html(self._payload()))


class ProseDoesNotBreakMidWordTest(unittest.TestCase):
    """`.id` carries BOTH a registry key and the search page's prose note.

    It used `word-break:break-all`, which breaks an unbreakable id -- what it is for -- and
    also chops ordinary sentences, which at 390px rendered "47 hidd/en" and "read fro/m
    display names". `overflow-wrap:anywhere` breaks only when there is no other opportunity,
    so the id still breaks and the sentence breaks at its spaces. `.nm` two rules away has
    always used the right one.
    """

    def test_no_id_rule_uses_word_break_all(self):
        """Checked across EVERY `.id` rule, not just the first.

        There are two -- the base one and the phone block's `margin-top` override -- and the
        cascade means either could reintroduce the chopping. Asserting only the first would
        pass while a later rule undid it.
        """
        from recipegraph.render import CSS
        rules = [ln for ln in CSS.splitlines() if ln.startswith(".id{")]
        self.assertGreaterEqual(len(rules), 1)
        for rule in rules:
            self.assertNotIn("word-break:break-all", rule, rule)
        self.assertEqual(sum("overflow-wrap:anywhere" in r for r in rules), 1, rules)
