"""A pack-authored item nothing makes is not a raw material. See #171 and #242.

WHY THIS FILE EXISTS. `BASE_RAW_COST` says "an item with no recipe: assume it can be
obtained somehow", which is right for cobblestone and wrong for a JEI tooltip. The pack
writes one placeholder item per mechanic it cannot express as a recipe -- a boss drop, a
locked chapter, "any good sword material", a multiblock preview -- and every one of them had
no producer, so all of them priced at the CHEAPEST value in the model and 46 recipes routed
through `nuclearcraft_fission_interior` for free. `contenttweaker:multiblock_preview` is the
biggest instance at 101 consumers, and its own display label contains the word *Uncraftable*.

BOTH DIRECTIONS ARE ASSERTED, WHICH IS THE POINT OF THE FILE. #117 and #168 are the lesson
being obeyed: a rule keyed on "zero producers inside the pack namespace" demotes 19 real ores
to fix 4 problems, so a test that only checks the markers move would pass on a rule that
wrecks the graph. Every case below is one of the four populations measured on the reference
graph, and the three CONTROL populations outnumber the marker one 884 to 283.

NOT `tests/test_provenance.py`, WHICH IS THE NEIGHBOUR AND A DIFFERENT SUBJECT. This file is
about the SET -- which keys the graph cannot explain -- and that one is about the pack's own
statement of where an item comes from, which is what takes 53 keys back out of this set. The
names are close because the subjects are adjacent; read the first line of each.

THE EXCLUSIONS ARE PACK-DECLARED DATA, NOT KEY-SHAPE GUESSES. `damage_base` reads
`max_damage` back from the item registry and `ores_of` reads oredict membership; neither
infers anything from what a key looks like. That is what `reachable_form`'s docstring means
by refusing `base_key(key) != key` on its own.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph import tokens as tokens_mod  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

# One key per population the rule has to tell apart. The names mirror the real ids they
# stand for so a failure here reads against the measurement in the issues.
MARKER = "contenttweaker:multiblock_preview"      # 101 consumers, produced by nothing
CURATED = "contenttweaker:boss_drop"              # already a LOOT token, 224 consumers
WORLD_ORE = "contenttweaker:sub_block_holder_0:1"  # Candyte Ore, oredict oreCandyte
NUGGET = "contenttweaker:material_part:53"        # Sednanite Nugget, #136's own item
WORN = "contenttweaker:bloodmaster_metal_chest:28400"   # a durability variant
WORN_BASE = "contenttweaker:bloodmaster_metal_chest"
OUTSIDER = "somemod:gravel"                       # not pack-authored: a mod's own raw leaf
TARGET = "mod:widget"


def pack_graph():
    """One graph holding all six populations, each consumed so it is live.

    Every placeholder is an input to a recipe and the output of none, which is the
    structural signal. What separates them is only the pack-declared data attached below.
    """
    g = Graph()
    g.names = {TARGET: "Widget", MARKER: "Multiblock Preview (Uncraftable)",
               CURATED: "Boss Drop", WORLD_ORE: "Candyte Ore", NUGGET: "Sednanite Nugget",
               WORN: "Bloodmaster Metal Chestplate", WORN_BASE: "Bloodmaster Metal Chestplate",
               OUTSIDER: "Gravel"}
    for name, ingredient in (("via_marker", MARKER), ("via_curated", CURATED),
                             ("via_ore", WORLD_ORE), ("via_nugget", NUGGET),
                             ("via_worn", WORN), ("via_outsider", OUTSIDER)):
        g.add(Recipe(name, "t", [(TARGET, 1)], [Ingredient([ingredient], 1)],
                     category="minecraft.crafting"))
    # The undamaged chestplate IS produced, which is what makes its worn variant an ordinary
    # item rather than an unsourced one.
    g.add(Recipe("make_chest", "t", [(WORN_BASE, 1)], [Ingredient(["mod:plate"], 4)],
                 category="minecraft.crafting"))
    g.ore_members = {"oreCandyte": [WORLD_ORE], "nuggetSednanite": [NUGGET]}
    g.max_damage = {"contenttweaker:bloodmaster_metal_chest": 32767}
    return g


class TheRuleSeparatesFourPopulationsTest(unittest.TestCase):
    """Membership, before any price is involved. A wrong set cannot be fixed by a constant."""

    def setUp(self):
        self.graph = pack_graph()
        self.population = self.graph.pack_authored_unsourced

    def test_a_pack_marker_nothing_produces_is_in_the_set(self):
        self.assertIn(MARKER, self.population)

    def test_a_world_ore_is_excluded_by_its_oredict_membership(self):
        # The control #242 names. Worldgen is not a recipe, so zero producers is CORRECT
        # here and the key must keep pricing as an ordinary raw leaf.
        self.assertNotIn(WORLD_ORE, self.population)

    def test_a_nugget_is_excluded_too_and_that_is_why_the_clause_is_any_oredict(self):
        # THE REASON THE RULE IS NOT `world_ores`. Measured on the reference graph, 47 keys
        # in this pool carry oredict membership and only 11 are ores; the other 36 are 21
        # nuggets, 13 storage blocks and 5 foods. `material_part:53` is the Sednanite
        # Nugget, the item #136 was filed about, so an `ore*`-only rule demotes the very
        # material the earlier bug was about.
        self.assertNotIn(NUGGET, self.population)

    def test_a_durability_variant_is_excluded_because_its_base_key_is_produced(self):
        # 837 of the 1,120 raw candidates, 75%, are these. Nothing about a worn chestplate
        # is unsourced: the undamaged key is made on a bench.
        self.assertNotIn(WORN, self.population)
        self.assertEqual(WORN_BASE, self.graph.damage_base(WORN))

    def test_a_mods_own_raw_leaf_is_not_pack_authored_and_stays_out(self):
        # The clause that keeps this off 117,350 keys. A mod shipping gravel with no recipe
        # is the ordinary case `BASE_RAW_COST` exists for; a PACK SCRIPT doing it is not.
        self.assertNotIn(OUTSIDER, self.population)

    def test_the_three_unsourced_sets_are_disjoint_by_construction(self):
        # `cost._seed` walks all three and they are genuine frozensets whose iteration order
        # varies between processes, so overlap would make the result order-dependent.
        self.assertEqual(set(), self.population & self.graph.unsourced_keys)
        self.assertEqual(set(), self.population & self.graph.produced_in_name_only)


class ThePriceMovesOneWayTest(unittest.TestCase):
    """What the membership buys, and what it must not cost the controls."""

    def setUp(self):
        self.graph = pack_graph()
        self.costs = cost_mod.estimate(self.graph)

    def test_a_marker_no_longer_prices_as_the_cheapest_thing_in_the_graph(self):
        # The defect in one assertion: 46 recipes routed through a tooltip because it cost
        # the same as cobblestone.
        self.assertEqual(cost_mod.UNSOURCED_COST, self.costs[MARKER])

    def test_the_controls_all_keep_the_raw_leaf_price(self):
        for key in (WORLD_ORE, NUGGET, OUTSIDER):
            self.assertEqual(cost_mod.BASE_RAW_COST, self.costs[key],
                             "%s must still price as an ordinary raw leaf" % key)

    def test_a_curated_token_keeps_its_own_kind_rather_than_the_generic_price(self):
        # The token seed runs LAST precisely so it wins. A placeholder the pack curated is
        # already an instruction with a price for what the player must go and DO, and the
        # display agrees with it, so the generic "cannot explain this" price must not
        # override the specific one.
        costs = cost_mod.estimate(self.graph, token_kinds={CURATED: tokens_mod.LOOT})
        self.assertEqual(cost_mod.LOOT_COST, costs[CURATED])

    def test_the_marker_route_stops_being_preferred_over_a_real_one(self):
        # The behaviour the price exists for, rather than the number. Before this, every
        # route through a placeholder tied with or beat the ordinary one beside it.
        self.assertLess(self.costs[OUTSIDER], self.costs[MARKER])

    def test_nothing_is_stranded_at_infinity_by_the_raise(self):
        # `_seed` only ever raises here, and cost.py's fifth finding is that a raise must be
        # an INPUT to relaxation rather than a patch over a settled table. Every key that
        # had a finite price must still have one.
        for key, value in self.costs.items():
            self.assertNotEqual(float("inf"), value, "%s was stranded" % key)


class ThePlanSaysSoOutLoudTest(unittest.TestCase):
    """The badge half. #171 asks for a distinct cost AND a distinct badge in one breath.

    `solve.py` carries the invariant in a comment -- "the price and the badge read one
    predicate and agree by construction" -- and a price change that skipped the badge would
    put a 2,000-priced tooltip on a shopping list rendered as an ordinary raw material.
    """

    def setUp(self):
        self.graph = pack_graph()
        # Only the marker route exists, so the plan is forced through it and the leaf is
        # reached rather than priced away. That is the case a player actually hits.
        self.graph.recipes = [r for r in self.graph.recipes if r.rid == "via_marker"]
        self.graph._invalidate()
        self.plan = Solver(self.graph,
                           costs=cost_mod.estimate(self.graph)).solve(TARGET, 1)

    def test_the_leaf_is_badged_rather_than_shown_as_an_ordinary_raw_material(self):
        leaf = self.plan["tree"]["children"][0]
        self.assertEqual(MARKER, leaf["key"])
        self.assertTrue(leaf.get("unsourced"),
                        "a pack marker priced at UNSOURCED_COST must not render as raw")

    def test_the_note_names_no_alternative_form_because_there_is_none(self):
        # The three `_unsourced_note` wordings all end by pointing at a form the graph CAN
        # make. This population is defined by there being nothing to point at, so reusing one
        # of them would invent an alternative that does not exist.
        note = self.plan["tree"]["children"][0]["note"]
        self.assertIn("nothing in the dump makes it", note)
        self.assertNotIn("the graph can only make", note)
        self.assertNotIn("the graph makes", note)

    def test_the_shopping_row_carries_the_mark_too(self):
        # The list a player takes into the world. An unmarked row here is the evening they
        # spend failing to find a JEI tooltip.
        rows = [r for r in self.plan["shopping_list"] if r["key"] == MARKER]
        self.assertEqual(1, len(rows))
        self.assertTrue(rows[0].get("unsourced"))

    def test_a_control_on_the_same_list_is_not_badged(self):
        # The mark has to discriminate, or it says nothing. An ordinary raw leaf reached the
        # same way carries no badge.
        g = pack_graph()
        g.recipes = [r for r in g.recipes if r.rid == "via_ore"]
        g._invalidate()
        plan = Solver(g, costs=cost_mod.estimate(g)).solve(TARGET, 1)
        leaf = plan["tree"]["children"][0]
        self.assertEqual(WORLD_ORE, leaf["key"])
        self.assertFalse(leaf.get("unsourced"))


class TheOfferedCandidatesAreReadableTest(unittest.TestCase):
    """`tokens.candidates` is a list for a human, so the noise is the whole problem."""

    def setUp(self):
        self.graph = pack_graph()

    def test_it_offers_the_marker(self):
        offered = [row[0] for row in tokens_mod.candidates(self.graph, known={})]
        self.assertIn(MARKER, offered)

    def test_it_offers_neither_worn_gear_nor_oredict_materials(self):
        # 884 of 1,120 on the reference graph. Sorted by recipe count, enough of them
        # outrank real placeholders to push them off a `limit=40` page.
        offered = [row[0] for row in tokens_mod.candidates(self.graph, known={})]
        for key in (WORN, WORLD_ORE, NUGGET):
            self.assertNotIn(key, offered)

    def test_it_does_not_re_offer_something_already_curated(self):
        offered = [row[0] for row in tokens_mod.candidates(
            self.graph, known={CURATED: tokens_mod.LOOT})]
        self.assertNotIn(CURATED, offered)

    def test_every_curated_token_survives_the_filter(self):
        # THE RETENTION CHECK, and the reason the filter is trustworthy rather than merely
        # narrow. All 37 hand-verified `DEFAULT_TOKENS` sit inside the population on the
        # reference graph; a filter that dropped one would be quietly hiding a placeholder
        # someone already checked by hand. Asserted here on the ids' SHAPE, since this
        # graph holds only one of them: nothing in the rule may key on a curated id.
        self.assertIn(CURATED, self.graph.pack_authored_unsourced)
        for key in tokens_mod.DEFAULT_TOKENS:
            self.assertTrue(key.startswith(tuple("%s:" % ns
                                                 for ns in tokens_mod.TOKEN_NAMESPACES)),
                            "%s is curated but outside the pack namespaces the rule "
                            "searches, so the rule could never reach it" % key)


@unittest.skipUnless(os.environ.get("RECIPEGRAPH_ORACLE")
                     and os.path.exists(os.environ.get("RECIPEGRAPH_ORACLE", "")),
                     "$RECIPEGRAPH_ORACLE does not name a readable oracle graph")
class TheRealGraphAgreesWithTheMeasurementTest(unittest.TestCase):
    """The numbers in every docstring above, re-derived from the pack rather than recalled.

    GATED ON THE ORACLE, not on a default path, matching `test_plan_fixtures`: whether this
    runs is a decision rather than a property of which machine the suite is on. Skipped is
    honest here; a synthetic graph that reproduced these counts would be asserting its own
    construction, which is the failure `DigestFixtureTest` warns about.

    WHY IT IS WORTH THE MINUTE IT COSTS. Every claim this change rests on is a count over the
    real pack -- 285 in the set, 37 of 37 curated tokens retained, the controls untouched --
    and until this existed all three lived in a comment and an ad-hoc script. A comment cannot
    fail.
    """

    @classmethod
    def setUpClass(cls):
        from recipegraph.model import Graph as RealGraph
        cls.graph = RealGraph.load(os.environ["RECIPEGRAPH_ORACLE"])

    def test_every_curated_token_is_retained_by_the_two_exclusions(self):
        # THE RETENTION CHECK. A filter that dropped one of these would be hiding a
        # placeholder somebody already verified by hand, and it would do it silently.
        missing = sorted(set(tokens_mod.DEFAULT_TOKENS) - self.graph.pack_authored_unsourced)
        self.assertEqual([], missing,
                         "the exclusions dropped curated tokens: %s" % missing)

    def test_the_three_populations_are_disjoint_on_real_data(self):
        # Disjoint by construction is an argument; this is the measurement. `cost._seed`
        # walks all three as frozensets whose iteration order varies between processes.
        self.assertEqual(set(),
                         self.graph.pack_authored_unsourced & self.graph.unsourced_keys)
        self.assertEqual(set(),
                         self.graph.pack_authored_unsourced & self.graph.produced_in_name_only)

    def test_no_key_carrying_oredict_membership_is_in_the_set(self):
        # 47 keys on the reference graph, of which only 11 are ores. The Sednanite Nugget is
        # among the other 36, so an `ore*`-only rule would demote #136's own item.
        wrong = [k for k in self.graph.pack_authored_unsourced if self.graph.ores_of(k)]
        self.assertEqual([], wrong[:10], "oredict members must be excluded")

    def test_no_durability_variant_is_in_the_set(self):
        # 837 of the 1,120 raw candidates.
        wrong = [k for k in self.graph.pack_authored_unsourced
                 if self.graph.damage_base(k) != k]
        self.assertEqual([], wrong[:10], "durability variants must be excluded")

    def test_nothing_with_a_producer_is_in_the_set(self):
        # Including a wildcard-meta producer, which is why the clause reads `producers` and
        # not `by_output`. See the comment on the property.
        wrong = [k for k in self.graph.pack_authored_unsourced if self.graph.producers(k)]
        self.assertEqual([], wrong[:10], "a key something makes is not unsourced")

    def test_the_reported_markers_are_caught(self):
        for key in ("contenttweaker:multiblock_preview",
                    "contenttweaker:dream_infusion_crafting",
                    "contenttweaker:nuclearcraft_fission_interior"):
            self.assertIn(key, self.graph.pack_authored_unsourced)


if __name__ == "__main__":
    unittest.main()
