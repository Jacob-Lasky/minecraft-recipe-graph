"""The pack explains where some items come from, and the JEI dump cannot carry it. #171.

WHY THIS FILE EXISTS, IN ONE LINE OF THE PACK. `scripts/PuzzleUtil.zs` registers puzzle
rewards with `recipes.addHiddenShapeless`, which is a REAL recipe hidden from JEI on purpose,
so every graph in this repository is built from a source that cannot see them. The item then
looks exactly like a JEI tooltip to `Graph.pack_authored_unsourced` and gets priced
`UNSOURCED_COST` -- the model saying "I cannot explain this" about an item the pack explains
in a file the build already opens for `dimensions.load_planet_defs`.

NOT `tests/test_pack_provenance.py`, WHICH IS THE NEIGHBOUR. That file is about the SET --
`Graph.pack_authored_unsourced`, which keys the graph cannot explain -- and this one is about
the pack's own statement of where an item comes from, which takes 53 keys back out of it.

BOTH DIRECTIONS ARE ASSERTED, which is `test_pack_provenance`'s rule and matters more here
because this set is an EXCLUSION. A wrong entry lets a real marker OUT of the unsourced set
and back to a cheap leaf, which is the defect #171 exists to fix, arriving through the fix.
So the controls are the point: a curated token, a world ore, an ordinary craftable and a
quest TASK all have to come through untouched.

AND THE POPULATION IS THE HALF THAT MATTERS, NOT THE WHOLE DECLARATION. The pack declares 896
keys and 843 of them are items the graph already makes -- `minecraft:cobblestone` is a quest
reward and `ae2stuff:adv_wireless_kit` a 380,435-cost craft. Pricing every declaration was
built and measured: it moves 301 keys off their real prices and drags 20 more UP through the
fixpoint. `Graph.pack_authored_declared` is the 53 the graph could not explain at all, and the
control for that mistake is asserted here directly rather than left to the reader of the loop.
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph import provenance  # noqa: E402
from recipegraph import tokens as tokens_mod  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

PUZZLE_KEY = "contenttweaker:curious_bullet"      # #171's own failure-mode-1 example
LOOT_KEY = "contenttweaker:druden_horn"           # a CustomLoot.zs addItemEntry
QUEST_KEY = "contenttweaker:tier1_token"          # a BetterQuesting reward
MARKER = "contenttweaker:multiblock_preview"      # declared nowhere: stays unsourced
CURATED = "contenttweaker:boss_drop"              # a curated LOOT token
CHEAP = "minecraft:cobblestone"                   # a quest reward the graph already makes

# ONE TARGET PER INGREDIENT, rather than six recipes for one widget. A plan picks the
# CHEAPEST route and reports only that one, so a shared target would put whichever key
# happens to be cheapest in the tree and silently assert nothing about the other five.
TARGET_OF = {PUZZLE_KEY: "mod:from_puzzle", LOOT_KEY: "mod:from_loot",
             QUEST_KEY: "mod:from_quest", MARKER: "mod:from_marker",
             CURATED: "mod:from_curated", CHEAP: "mod:from_cheap"}
TARGET = TARGET_OF[PUZZLE_KEY]


def pack_graph():
    """One graph holding a declared key of each kind plus the controls that must not move."""
    g = Graph()
    g.names = {PUZZLE_KEY: "Curious Bullet", LOOT_KEY: "Druden Horn",
               QUEST_KEY: "Tier 1 Token", MARKER: "Multiblock Preview (Uncraftable)",
               CURATED: "Boss Drop", CHEAP: "Cobblestone"}
    for ingredient, target in TARGET_OF.items():
        g.names[target] = "Widget"
        g.add(Recipe("via_" + target, "t", [(target, 1)], [Ingredient([ingredient], 1)],
                     category="minecraft.crafting"))
    # Cobblestone IS made, which is what makes it the control for the `min`: the pack
    # declaring a quest reward must not take a priced item away from its route.
    g.add(Recipe("make_cobble", "t", [(CHEAP, 1)], [Ingredient(["mod:stone"], 1)],
                 category="minecraft.crafting"))
    g.declared_provenance = {PUZZLE_KEY: provenance.PUZZLE, LOOT_KEY: provenance.LOOT_TABLE,
                             QUEST_KEY: provenance.QUEST, CHEAP: provenance.QUEST}
    return g


class EveryKindIsFullyDeclaredTest(unittest.TestCase):
    """A kind added to `KINDS` and nowhere else degrades silently at three surfaces."""

    def test_every_kind_has_a_note_a_badge_and_a_price(self):
        # `note_for`, `badge_for` and `PROVENANCE_COST` all carry a fallback so that a
        # half-added kind cannot crash a plan. That is the right runtime behaviour and the
        # wrong thing to discover in production, so the omission is caught here instead.
        for kind in provenance.KINDS:
            self.assertIn(kind, provenance.KIND_NOTE, kind)
            self.assertIn(kind, provenance.KIND_BADGE, kind)
            self.assertIn(kind, cost_mod.PROVENANCE_COST, kind)

    def test_every_price_sits_below_the_unexplained_one(self):
        # THE ORDERING IS THE CLAIM. An item the pack explains must not cost more than one it
        # does not, and 41 puzzle rewards did: `UNSOURCED_COST` is 2,000 and `GATE_COST` is
        # 1,000, so the model ranked a documented item below an undocumented one.
        for kind, band in cost_mod.PROVENANCE_COST.items():
            self.assertLess(band, cost_mod.UNSOURCED_COST, kind)
            self.assertGreaterEqual(band, cost_mod.LOOT_COST, kind)

    def test_an_unknown_kind_still_produces_readable_text(self):
        self.assertTrue(provenance.note_for("nonsense"))
        self.assertTrue(provenance.badge_for("nonsense"))


class TheBuildLogSaysWhatItFoundTest(unittest.TestCase):
    """`index.build`'s one line. A count that moved is how a drifted rule gets noticed."""

    def test_it_names_the_three_sources_and_what_they_reached(self):
        line = provenance.report({"a:x": provenance.PUZZLE, "a:y": provenance.LOOT_TABLE,
                                  "a:z": provenance.QUEST}, 2)
        self.assertIn("3 keys declared", line)
        self.assertIn("1 puzzle", line)
        self.assertIn("1 loot table", line)
        self.assertIn("1 quest reward", line)
        self.assertIn("2 of them previously priced as unexplainable", line)

    def test_a_pack_that_declares_nothing_says_so_rather_than_reporting_zero(self):
        # "0 keys declared" reads as "the rule found nothing"; this has to read as "the rule
        # was not given its input", which is the same distinction `index.coverage` draws for
        # its token map.
        line = provenance.report({}, 0)
        self.assertIn("no scripts/", line)
        self.assertIn("pre-#171 behaviour", line)


class TheReaderReadsWhatThePackWroteTest(unittest.TestCase):
    """The parsers, against the call shapes the pack actually uses."""

    def test_a_puzzle_output_is_the_second_argument_not_the_first(self):
        # The first argument is the stage name the wrapper derives its gamestage from. A
        # regex that took the first `<...>` after the paren would be right by accident here
        # and wrong the moment a pack author reorders anything.
        got = provenance.parse_scripts(
            'scripts.PuzzleUtil.addPuzzleShapeless("secretcobblebranch",'
            '<contenttweaker:branch_of_life>,\n'
            '[<extrautils2:compressedcobblestone:0>, <minecraft:dirt>]);')
        self.assertEqual({"contenttweaker:branch_of_life": provenance.PUZZLE}, got)

    def test_a_shaped_puzzle_reads_the_same_way(self):
        got = provenance.parse_scripts(
            'addPuzzleShaped("x", <mod:thing>, [[<minecraft:dirt>]]);')
        self.assertEqual({"mod:thing": provenance.PUZZLE}, got)

    def test_a_loot_table_entry_is_read_and_its_weight_is_not(self):
        # A rarity is not a price. See `cost.PROVENANCE_COST` for why weighting it would be
        # `EMC_COST`'s rejected scaling argument arriving at a second door.
        got = provenance.parse_scripts(
            'leonardcustom.addItemEntry(<contenttweaker:hand_of_dominion>, 15);')
        self.assertEqual({"contenttweaker:hand_of_dominion": provenance.LOOT_TABLE}, got)

    def test_meta_zero_is_dropped_and_a_real_meta_is_kept(self):
        # The graph spells a meta-0 item bare. A reader that kept the `:0` would declare
        # provenance for a key nothing else in the codebase uses, which fails silently.
        got = provenance.parse_scripts(
            'addPuzzleShaped("a", <mod:thing:0>, []); addPuzzleShaped("b", <mod:other:3>, []);')
        self.assertEqual({"mod:thing": provenance.PUZZLE, "mod:other:3": provenance.PUZZLE},
                         got)

    def test_a_bracket_carrying_a_modifier_still_resolves_to_the_bare_key(self):
        # `.reuse()` marks a retained input and `* 4` a stack size. Neither is part of the
        # key, and a reader that choked on them would miss `infuser_fabrial`, the largest
        # puzzle reward in the pack at 16 consumers.
        got = provenance.parse_scripts('addPuzzleShapeless("a", <mod:thing>.reuse(), []);')
        self.assertEqual({"mod:thing": provenance.PUZZLE}, got)

    def test_a_puzzle_outranks_a_loot_entry_for_the_same_key(self):
        # The ordering is the claim: a puzzle is something you sit down and solve, a loot
        # table is something you farm. Resolving it the other way would discount an item
        # behind a gamestage to what an afternoon of fighting costs.
        got = provenance.parse_scripts(
            'x.addItemEntry(<mod:thing>, 1);\naddPuzzleShaped("a", <mod:thing>, []);')
        self.assertEqual({"mod:thing": provenance.PUZZLE}, got)

    def test_a_quest_reward_is_read_and_a_quest_task_is_not(self):
        # THE CONTROL THAT MATTERS MOST IN THIS FILE. A quest that REQUIRES an item is
        # evidence the item exists and none at all that the quest gives it -- and the quest
        # book asks for placeholders too, so reading tasks would declare provenance for
        # curated `DEFAULT_TOKENS` and hand a JEI tooltip an obtainable price.
        doc = {"questID:3": 1,
               "tasks:9": {"0:10": {"requiredItems:9": {"0:10": {"id:8": "mod:asked_for"}}}},
               "rewards:9": {"0:10": {"rewards:9": {"0:10": {"id:8": "mod:given"}}}}}
        self.assertEqual({"mod:given": provenance.QUEST},
                         provenance.parse_quest_rewards(doc))

    def test_a_reward_nested_deeper_than_expected_is_still_found(self):
        # Reward layout varies by reward type, so the walk tracks "am I inside a rewards
        # subtree" rather than indexing a fixed path. A reward this missed would be a key
        # that silently keeps the price it has today.
        doc = {"rewards:9": {"0:10": {"a:9": {"b:9": {"0:10": {"id:8": "mod:deep"}}}}}}
        self.assertEqual({"mod:deep": provenance.QUEST},
                         provenance.parse_quest_rewards(doc))

    def test_a_script_declaring_neither_returns_nothing(self):
        self.assertEqual({}, provenance.parse_scripts(
            "recipes.addShapeless(<mod:a>, [<mod:b>]);"))


class AMissDegradesRatherThanRaisingTest(unittest.TestCase):
    """`parse_planet_defs`' bargain: a hand-edited pack file cannot take a build down."""

    def setUp(self):
        import tempfile
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _write(self, rel, text):
        path = os.path.join(self.dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)

    def test_no_pack_at_all_declares_nothing(self):
        self.assertEqual({}, provenance.load(None))
        self.assertEqual({}, provenance.load(os.path.join(self.dir, "absent")))

    def test_a_pack_with_neither_source_declares_nothing(self):
        self.assertEqual({}, provenance.load(self.dir))

    def test_a_malformed_quest_file_loses_only_itself(self):
        self._write("config/betterquesting/DefaultQuests/Quests/0/1.json", "{not json")
        self._write("config/betterquesting/DefaultQuests/Quests/0/2.json",
                    json.dumps({"rewards:9": {"0:10": {"id:8": "mod:given"}}}))
        self.assertEqual({"mod:given": provenance.QUEST}, provenance.load(self.dir))

    def test_a_disabled_txt_script_is_not_read(self):
        # A declaration in a file CraftTweaker never loads is not a declaration. Measured on
        # the reference pack this costs nothing today -- its `.txt` scripts declare 0 puzzles
        # and 0 loot entries -- and it is here so that disabling a script disables its claims.
        self._write("scripts/Disabled.txt", 'addPuzzleShaped("a", <mod:thing>, []);')
        self._write("scripts/Live.zs", 'addPuzzleShaped("b", <mod:other>, []);')
        self.assertEqual({"mod:other": provenance.PUZZLE}, provenance.load(self.dir))


class ADeclaredKeyLeavesTheUnsourcedSetTest(unittest.TestCase):
    """Membership, before any price. A wrong set cannot be fixed by a constant."""

    def setUp(self):
        self.graph = pack_graph()

    def test_all_three_kinds_are_excluded_from_pack_authored_unsourced(self):
        for key in (PUZZLE_KEY, LOOT_KEY, QUEST_KEY):
            self.assertNotIn(key, self.graph.pack_authored_unsourced,
                             "%s is explained by the pack" % key)

    def test_an_undeclared_marker_is_still_in_it(self):
        # The control. Subtracting the explained keys must not empty the set: 232 of the
        # reference oracle's 285 have no declaration anywhere in the pack.
        self.assertIn(MARKER, self.graph.pack_authored_unsourced)

    def test_assigning_the_map_drops_the_memoised_set(self):
        # `index.build` assigns this after the property may already have been read, and a
        # plain field would leave the build serving the pre-exclusion set to the graph it
        # then writes out. Read it first, on purpose, so the cache is warm.
        graph = pack_graph()
        graph.declared_provenance = {}
        self.assertIn(PUZZLE_KEY, graph.pack_authored_unsourced)
        graph.declared_provenance = {PUZZLE_KEY: provenance.PUZZLE}
        self.assertNotIn(PUZZLE_KEY, graph.pack_authored_unsourced)

    def _round_trip(self, doc):
        import tempfile
        handle, path = tempfile.mkstemp(suffix=".json")
        try:
            with os.fdopen(handle, "w") as fh:
                json.dump(doc, fh)
            return Graph.load(path)
        finally:
            os.unlink(path)

    def test_it_survives_a_save_and_load(self):
        # It is PERSISTED rather than re-derived, for `shadow_ores`' reason plus one of its
        # own: `instance_dir` is a path recorded in the graph, so a `load` that read it would
        # answer differently depending on whether that directory exists on this machine.
        again = self._round_trip(self.graph.to_json())
        self.assertEqual(self.graph.declared_provenance, again.declared_provenance)
        self.assertNotIn(PUZZLE_KEY, again.pack_authored_unsourced)

    def test_a_graph_built_before_this_feature_loads_and_behaves_as_before(self):
        doc = self.graph.to_json()
        del doc["declared_provenance"]
        again = self._round_trip(doc)
        self.assertEqual({}, again.declared_provenance)
        self.assertEqual({}, again.pack_authored_declared)
        self.assertIn(PUZZLE_KEY, again.pack_authored_unsourced)


class ThePriceOnlyEverFallsTest(unittest.TestCase):
    """What the exclusion buys, and the direction it must never move."""

    def setUp(self):
        self.graph = pack_graph()
        self.costs = cost_mod.estimate(self.graph)

    # LITERALS, NOT THE CONSTANTS THEY TEST, AND THAT IS THE WHOLE POINT OF THESE THREE.
    # `PROVENANCE_COST[PUZZLE]` IS `GATE_COST`, so `assertEqual(GATE_COST, costs[key])` has the
    # same symbol on both sides: collapse every band to one value and the assertion still
    # holds, green over a model that no longer discriminates at all. Measured with exactly
    # that probe -- these three and the fallback below stayed green with `GATE_COST`,
    # `LOOT_COST` and `UNSOURCED_COST` all set to 777.0, and only the two ORDERING assertions
    # caught it. DO NOT "tidy" these back into named constants.
    GATE_BAND = 1000.0        # cost.GATE_COST
    LOOT_BAND = 200.0         # cost.LOOT_COST
    UNEXPLAINED = 2000.0      # cost.UNSOURCED_COST

    def test_a_puzzle_reward_lands_in_the_gate_band(self):
        # A gamestage lock is "a lock with a key somewhere in the story", which is
        # `GATE_COST`'s own stated definition.
        self.assertEqual(self.GATE_BAND, self.costs[PUZZLE_KEY])

    def test_a_quest_reward_shares_that_band(self):
        self.assertEqual(self.GATE_BAND, self.costs[QUEST_KEY])

    def test_a_loot_table_entry_lands_in_the_loot_band(self):
        # `addItemEntry` puts the item in a table you farm, which is `LOOT_COST`'s own
        # definition, "found by playing".
        self.assertEqual(self.LOOT_BAND, self.costs[LOOT_KEY])

    def test_the_literals_above_are_the_constants_they_stand_for(self):
        # The one place the two are tied together, so a deliberate move of a constant fails
        # HERE -- one legible failure naming the number that moved -- instead of failing as
        # three band assertions that read like a pricing bug.
        self.assertEqual(cost_mod.GATE_COST, self.GATE_BAND)
        self.assertEqual(cost_mod.LOOT_COST, self.LOOT_BAND)
        self.assertEqual(cost_mod.UNSOURCED_COST, self.UNEXPLAINED)

    def test_every_declared_key_costs_less_than_an_unexplained_one(self):
        # THE WHOLE FEATURE IN ONE ASSERTION. Before this, 41 puzzle rewards sat at 2,000
        # while a locked chapter cost 1,000 -- the model ranked an item the pack tells you
        # how to get BELOW one it merely gates.
        for key in (PUZZLE_KEY, LOOT_KEY, QUEST_KEY):
            self.assertLess(self.costs[key], self.costs[MARKER])
            self.assertLess(self.costs[key], self.UNEXPLAINED)

    def test_a_declared_key_the_graph_already_makes_keeps_its_cheaper_price(self):
        # THE `min`, AND IT IS NOT HYPOTHETICAL. 397 of the pack's 896 declarations are keys
        # already priced below `LOOT_COST`; `minecraft:cobblestone` is a quest reward. An
        # assignment here would raise it to `GATE_COST` on 397 keys at once.
        self.assertLess(self.costs[CHEAP], self.LOOT_BAND)

    def test_a_curated_token_outranks_its_declaration(self):
        # The token seed runs LAST precisely so it wins, and `Solver.expand` returns at the
        # token branch, so the price has to agree with the display. Measured empty on the
        # reference pack -- 0 of the 37 tokens are declared -- so this pins the ordering that
        # stays right if the pack ever changes.
        graph = pack_graph()
        graph.declared_provenance = dict(graph.declared_provenance,
                                         **{CURATED: provenance.LOOT_TABLE})
        costs = cost_mod.estimate(graph, token_kinds={CURATED: tokens_mod.GATE})
        self.assertEqual(cost_mod.GATE_COST, costs[CURATED])

    def test_an_unknown_kind_falls_back_to_the_price_it_had_before(self):
        # A `provenance` that grows a fourth kind without teaching `PROVENANCE_COST` about it
        # must degrade to today's answer rather than to a cheap one.
        graph = pack_graph()
        graph.declared_provenance = {MARKER: "some_future_kind"}
        self.assertEqual(self.UNEXPLAINED, cost_mod.estimate(graph)[MARKER])


class ThePriceAndTheBadgeAgreeTest(unittest.TestCase):
    """`solve.py`'s standing invariant, extended to the population that is NOT unsourced."""

    def setUp(self):
        self.graph = pack_graph()
        self.costs = cost_mod.estimate(self.graph)

    def _plan(self, key):
        return Solver(self.graph, costs=self.costs).solve(TARGET_OF[key], 1)

    def _node(self, key):
        found = []

        def walk(node):
            if node.get("key") == key:
                found.append(node)
            for child in node.get("children", ()):
                walk(child)

        walk(self._plan(key)["tree"])
        self.assertTrue(found, "%s is not in the plan" % key)
        return found[0]

    def test_a_declared_leaf_is_not_badged_unsourced(self):
        # The badge follows the price. `cost._seed` charges these `PROVENANCE_COST` rather
        # than `UNSOURCED_COST`, so leaving the mark on would tell a reader the tool cannot
        # explain an item it just explained.
        node = self._node(PUZZLE_KEY)
        self.assertNotIn("unsourced", node)

    def test_it_says_where_the_item_comes_from_instead(self):
        # Dropping the badge without replacing the note would be a REGRESSION on the one
        # surface where "no known source" was doing useful work.
        node = self._node(PUZZLE_KEY)
        self.assertEqual(provenance.PUZZLE, node["provenance"])
        self.assertIn("puzzle", node["note"])

    def test_an_undeclared_marker_keeps_the_badge_it_had(self):
        node = self._node(MARKER)
        self.assertTrue(node.get("unsourced"))
        self.assertNotIn("provenance", node)

    def test_the_shopping_row_and_the_tree_agree(self):
        # The list and the tree are recomputed from the graph separately so they cannot
        # disagree about one key; this is the assertion that holds them together.
        rows = {row["key"]: row for row in self._plan(PUZZLE_KEY)["shopping_list"]}
        self.assertEqual(provenance.PUZZLE, rows[PUZZLE_KEY]["provenance"])
        self.assertNotIn("unsourced", rows[PUZZLE_KEY])
        marker_rows = {row["key"]: row for row in self._plan(MARKER)["shopping_list"]}
        self.assertTrue(marker_rows[MARKER].get("unsourced"))
        self.assertNotIn("provenance", marker_rows[MARKER])


class TheBadgeSurvivesIntoTheHtmlTest(unittest.TestCase):
    """Both surfaces a reader actually looks at, which is where the first cut of this failed.

    `Solver.shopping_row` set `provenance` and `render._rows` read only `unsourced`, so the
    tree node named the puzzle and the list a player carries into the world said nothing --
    the exact tree-versus-list split `test_unsourced.RenderedSurfacesTest` exists to pin, one
    field along. A plan dict assertion would not have caught it; this renders the HTML.
    """

    def _html(self):
        from recipegraph.render import render_html
        graph = pack_graph()
        plan = Solver(graph, costs=cost_mod.estimate(graph)).solve(TARGET_OF[PUZZLE_KEY], 2)
        return render_html(plan, graph)

    def test_the_tree_node_and_the_shopping_row_both_carry_the_badge(self):
        # Counted before the footer, which documents the badges and would otherwise inflate
        # the count and make this pass for the wrong reason.
        #
        # THE MARKUP AND NOT THE WORD. "puzzle" also appears in the note sentence and inside
        # `?item=mod%3Afrom_puzzle` in two diagram links, so a bare `count` of the word reads
        # 6 and would pass with the row badge missing -- which is the state this test was
        # written against and caught.
        body = self._html().split('class="foot"')[0]
        badge = '<span class="badge need">%s</span>' % provenance.KIND_BADGE[provenance.PUZZLE]
        self.assertEqual(2, body.count(badge),
                         "expected the badge on the tree node and on the shopping-list row")

    def test_the_note_reaches_the_tree(self):
        self.assertIn("solving its puzzle", self._html())

    def test_it_does_not_also_claim_the_source_is_unknown(self):
        # The two marks are mutually exclusive by construction, and rendering both would be
        # the tool contradicting itself inside one row.
        from recipegraph import present
        body = self._html().split('class="foot"')[0]
        self.assertNotIn(present.UNSOURCED_BADGE, body)


class BuildWiresItInTest(unittest.TestCase):
    """One block in `index.build`, and dropping it is silent.

    Nothing raises and no unit test above fails: every one of them sets
    `graph.declared_provenance` directly, so the pack could stop being read altogether and
    the only symptom would be 53 keys quietly going back to `UNSOURCED_COST`. Same shape as
    the wiring tests `test_dimensions` and `test_multiblocks` keep for their own one-liners.
    """

    def setUp(self):
        import tempfile
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.dir, ignore_errors=True)

    def _instance(self):
        scripts = os.path.join(self.dir, "scripts")
        os.makedirs(scripts, exist_ok=True)
        with open(os.path.join(scripts, "Puzzles.zs"), "w") as fh:
            fh.write('addPuzzleShapeless("p", <contenttweaker:from_a_puzzle>, '
                     '[<minecraft:dirt>]);\n')
        dump = os.path.join(self.dir, "mc-recipe-dump")
        os.makedirs(dump, exist_ok=True)
        with open(os.path.join(dump, "recipes.ndjson"), "w") as fh:
            for ingredient in ("contenttweaker:from_a_puzzle", "contenttweaker:from_nowhere"):
                fh.write(json.dumps({"cat": "minecraft.crafting",
                                     "in": [[{"i": ingredient, "c": 1}]],
                                     "out": [{"i": "mod:widget", "c": 1}]}) + "\n")
        return self.dir

    def test_a_built_graph_carries_what_the_pack_declared(self):
        from recipegraph import index
        g = index.build(self._instance(), quiet=True)
        self.assertEqual({"contenttweaker:from_a_puzzle": provenance.PUZZLE},
                         g.declared_provenance)

    def test_the_built_graph_splits_the_two_populations(self):
        # The end-to-end claim: two keys identical to every structural rule, separated by
        # what the pack says about one of them.
        from recipegraph import index
        g = index.build(self._instance(), quiet=True)
        self.assertEqual({"contenttweaker:from_a_puzzle": provenance.PUZZLE},
                         g.pack_authored_declared)
        self.assertIn("contenttweaker:from_nowhere", g.pack_authored_unsourced)
        self.assertNotIn("contenttweaker:from_a_puzzle", g.pack_authored_unsourced)

    def test_it_survives_the_write_and_read_a_build_actually_performs(self):
        # `to_json`/`load` and not just the in-memory object, because `index.build` is
        # followed by `save` and everything downstream reads the file.
        from recipegraph import index
        g = index.build(self._instance(), quiet=True)
        out = os.path.join(self.dir, "graph.json")
        g.save(out)
        self.assertEqual(g.declared_provenance, Graph.load(out).declared_provenance)


class AgainstTheRealPackTest(unittest.TestCase):
    """The measurements the module docstring claims, read off the pack rather than restated.

    SKIPPED RATHER THAN FAKED when `$RECIPEGRAPH_ORACLE` names no graph, which is the same
    bargain `test_pack_provenance` makes. A synthetic fixture cannot check that a real pack's
    declarations miss every curated token, and that emptiness is the whole safety argument.
    """

    @classmethod
    def setUpClass(cls):
        path = os.environ.get("RECIPEGRAPH_ORACLE")
        if not path or not os.path.exists(path):
            raise unittest.SkipTest("no $RECIPEGRAPH_ORACLE")
        cls.graph = Graph.load(path)
        if not cls.graph.instance_dir or not os.path.isdir(cls.graph.instance_dir):
            raise unittest.SkipTest("the oracle names no readable instance_dir")
        cls.declared = provenance.load(cls.graph.instance_dir)

    def test_the_pack_declares_something(self):
        # A reader that silently returned {} would pass every assertion below.
        self.assertGreater(len(self.declared), 500)

    def test_no_curated_token_is_ever_declared(self):
        # THE SAFETY PROPERTY. This set is applied as an exclusion, so a curated placeholder
        # appearing here would let a JEI tooltip out of `pack_authored_unsourced` and back to
        # a cheap leaf -- the defect #171 exists to fix, arriving through its own fix.
        overlap = sorted(set(self.declared) & set(tokens_mod.DEFAULT_TOKENS))
        self.assertEqual([], overlap)

    def test_it_reaches_the_keys_the_module_says_it_reaches(self):
        # Not a round number for its own sake: this is the count that moves prices, and a
        # drift in it means the pack changed or a parser stopped matching.
        graph = Graph.load(os.environ["RECIPEGRAPH_ORACLE"])
        graph.declared_provenance = {}
        before = len(graph.pack_authored_unsourced)
        graph.declared_provenance = self.declared
        after = len(graph.pack_authored_unsourced)
        self.assertEqual(285, before)
        self.assertEqual(232, after)

    def test_the_java_port_does_not_yet_know_about_this(self):
        """THE ONE THING THAT COULD MAKE A GREEN GOLDEN GATE A LIE, said out loud. #171.

        `Unsourced.isPackAuthoredUnsourced` in the Java port mirrors
        `Graph.pack_authored_unsourced` clause for clause, and it has not been taught the
        `declared_provenance` exclusion or `Cost.PROVENANCE_COST`. Today that costs nothing,
        because no graph on disk carries the field: `index.build` writes it and no oracle has
        been rebuilt since, so both languages compute the same 285-key set and
        `everyFixturePlansExactlyAsThePythonOracleDoes` is comparing like with like.

        THE MOMENT SOMEBODY REDUMPS, THAT STOPS BEING TRUE, and the failure would arrive as an
        opaque fixture diff on a branch that has nothing to do with this one -- the Java reader
        skips unknown top-level sections by design, so the port will load the new field, ignore
        it, and quietly price 53 keys differently from python. This assertion exists to fail
        FIRST and say why.

        WHEN THIS FAILS: do #262, which is the port -- the exclusion and the band into
        `Unsourced` and `Cost` in `mod/src/main/java/.../plan/`, plus the reader and the
        badge -- or drop `declared_provenance` from the oracle. #262 is where the file list
        and the acceptance criteria live, so the failure message names it rather than the
        umbrella; landing on #171 would mean re-deriving the writeup.

        DELETING THIS TEST IS PART OF #262 AND NOT A WAY TO GO GREEN. It goes when the port
        lands, so that the tripwire and the thing it guards are removed together; deleting it
        to quiet a red suite is the silent-death shape `check.sh` exists to prevent, one level
        up.
        """
        self.assertEqual(
            {}, self.graph.declared_provenance,
            "this oracle carries declared_provenance, so the Java port is now behind python "
            "and the golden plan gate is comparing two different cost models. See #262.")

    def test_the_example_the_issue_names_is_one_of_them(self):
        # #171 says `contenttweaker:curious_bullet` "is priced 1.0 and is obtained from a
        # puzzle". The pack says so too, in machine-readable form.
        self.assertEqual(provenance.PUZZLE, self.declared.get("contenttweaker:curious_bullet"))


if __name__ == "__main__":
    unittest.main()
