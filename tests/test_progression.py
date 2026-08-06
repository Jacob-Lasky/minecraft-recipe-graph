"""What a progression gate costs. See #105.

Asked directly during the #103 investigation: *"is the dimensional gate added to the cost?"*
It was not. `cost.py` never mentioned tokens, so all eleven of the pack's GATE placeholders
fell through the generic leaf rule and a locked quest chapter cost exactly what a cobblestone
costs. Gating was a REPORTING concept only -- `solve` badged the node "locked" and listed it
under "locked behind progress", and nothing steered the planner away from the route.

The error ran one way, which is what made it worth fixing: a gated route was always at least
as cheap as the ungated one beside it, so gated routes were systematically preferred.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph import tokens as tokens_mod  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import STATUS_TOKEN, Solver  # noqa: E402

GATED = "contenttweaker:chapter_1"
LOOTED = "contenttweaker:boss_drop"
HINTED = "contenttweaker:good_sword_materials"
TARGET = "mod:widget"
PLAIN = "mod:cobblestone"

KINDS = {GATED: tokens_mod.GATE, LOOTED: tokens_mod.LOOT, HINTED: tokens_mod.HINT}


def two_routes():
    """One widget, three ways: behind a gate, behind loot, and out of ordinary rock.

    Every route is a single hand-crafting recipe taking one ingredient, so the ONLY thing
    that can separate them is what that ingredient costs. Any ranking difference below is
    the token price and nothing else.
    """
    g = Graph()
    g.names = {TARGET: "Widget", PLAIN: "Cobblestone", GATED: "Chapter 1",
               LOOTED: "Boss Drop", HINTED: "Good Sword Materials"}
    g.add(Recipe("via_gate", "t", [(TARGET, 1)], [Ingredient([GATED], 1)],
                 category="minecraft.crafting"))
    g.add(Recipe("via_loot", "t", [(TARGET, 1)], [Ingredient([LOOTED], 1)],
                 category="minecraft.crafting"))
    g.add(Recipe("via_rock", "t", [(TARGET, 1)], [Ingredient([PLAIN], 1)],
                 category="minecraft.crafting"))
    return g


def costs_for(graph, **kw):
    kw.setdefault("token_kinds", KINDS)
    return cost_mod.estimate(graph, **kw)


def chosen(graph, **kw):
    solver = Solver(graph, costs=costs_for(graph, **kw), token_kinds=KINDS)
    return solver.solve(TARGET, 1)["tree"]["recipe"]


class TheOrderingIsTheClaimTest(unittest.TestCase):
    """The four properties `cost.TOKEN_COST` states. Magnitudes may move; these may not."""

    def test_a_gate_costs_more_than_loot_costs_more_than_a_raw_leaf(self):
        self.assertGreater(cost_mod.GATE_COST, cost_mod.LOOT_COST)
        self.assertGreater(cost_mod.LOOT_COST, cost_mod.BASE_RAW_COST)

    def test_a_gate_outweighs_any_machine_so_an_ungated_route_always_wins(self):
        # Property 2. An ungated route through the worst machine the model will price must
        # still beat a gated one, or the fix does not actually steer anything.
        self.assertGreater(cost_mod.GATE_COST, cost_mod.MACHINE_COST["unknown"])

    def test_a_gate_is_not_an_impossibility(self):
        # Property 3, and the whole difference from the 5,000 wall: chapters unlock and
        # bosses die, so a gated route has to stay finite and still be chosen when it is the
        # only one there is.
        self.assertLess(cost_mod.GATE_COST, cost_mod.MACHINE_COST["unavailable"])
        self.assertLess(cost_mod.LOOT_COST, cost_mod.MACHINE_COST["unavailable"])

    def test_loot_and_gate_are_different_numbers(self):
        # Property 4. #95 is the lesson: one shared number for two unrelated statements
        # destroys the ordering among both.
        self.assertNotEqual(cost_mod.LOOT_COST, cost_mod.GATE_COST)

    def test_every_kind_has_a_price_and_no_price_is_orphaned(self):
        # A kind added to `tokens.KINDS` without a price here silently falls back to
        # BASE_RAW_COST, which is the exact bug this file exists about, arriving quietly.
        self.assertEqual(sorted(cost_mod.TOKEN_COST), sorted(tokens_mod.KINDS))


class WhatTheGraphCannotExplainCostsTest(unittest.TestCase):
    """`UNSOURCED_COST`, #176. The ordering is the claim; the magnitude is measured inert.

    #139 shipped the "no known source" badge and deliberately did not touch the price, so
    an item the graph had PROVEN it cannot account for still seeded at `BASE_RAW_COST` --
    the cheapest value in the model. The solver therefore preferred routes through exactly
    the items it was warning the reader about.
    """

    def test_it_outranks_a_locked_chapter(self):
        # The claims differ in KIND. A gate is a lock with a key somewhere in the story; an
        # unsourced item is one the tool cannot explain at all, on positive evidence rather
        # than silence. Below a gate would put a route the graph cannot account for ahead of
        # one it can, which is the defect.
        self.assertGreater(cost_mod.UNSOURCED_COST, cost_mod.GATE_COST)

    def test_it_is_not_an_impossibility(self):
        # Same property 3 as a gate. The item exists in the world -- a Fissile Drone comes
        # from somewhere -- so the route must stay finite and be chosen when it is the only
        # one. Measured: infinity strands 2,372 currently-priced keys; every finite
        # candidate strands zero.
        self.assertLess(cost_mod.UNSOURCED_COST, cost_mod.MACHINE_COST["unavailable"])

    def test_it_is_its_own_number(self):
        # #95. Two unrelated statements sharing a figure destroys the ordering among both.
        for other in (cost_mod.LOOT_COST, cost_mod.GATE_COST, cost_mod.DIMENSION_COST,
                      cost_mod.MACHINE_COST["unavailable"]):
            self.assertNotEqual(cost_mod.UNSOURCED_COST, other)

    def test_the_whole_chain_holds_in_one_assertion(self):
        # The band as `cost.py` states it, so a constant moved past its neighbour fails here
        # and not somewhere confusing three modules away.
        #
        # NON_PRODUCTION_PENALTY IS THE TOP OF IT, and that is #211's claim rather than a
        # bigger number for its own sake. Every other figure here prices an OBSTACLE on a route
        # that exists -- an afternoon of fighting, a trip, a locked chapter, a machine you
        # cannot have. A loot table or a JEI automation card is not a route at all, so it has to
        # lose to every claim the graph can account for, including the worst of them.
        chain = [cost_mod.BASE_RAW_COST, cost_mod.LOOT_COST, cost_mod.DIMENSION_COST,
                 cost_mod.GATE_COST, cost_mod.UNSOURCED_COST,
                 cost_mod.MACHINE_COST["unavailable"], cost_mod.NON_PRODUCTION_PENALTY]
        self.assertEqual(chain, sorted(chain))
        self.assertEqual(len(chain), len(set(chain)))

    def test_the_cache_notices_the_constant_moving(self):
        # The failure `fingerprint` exists to prevent, applied to the constant this class is
        # about: a warm `.cost-cache.json` serving pre-change prices reads as "the tuning
        # change did not work" rather than as a stale cache. THE SEED CONSTANTS WERE NOT
        # HASHED AT ALL before #176 -- GATE_COST is checked here too because that gap was
        # real and this is where it would come back.
        args = ("graph.json", {}, {}, ())
        before = cost_mod.fingerprint(*args)
        for name in ("UNSOURCED_COST", "GATE_COST", "LOOT_COST", "DIMENSION_COST",
                     "NON_PRODUCTION_PENALTY"):
            original = getattr(cost_mod, name)
            try:
                setattr(cost_mod, name, original + 7.0)
                self.assertNotEqual(cost_mod.fingerprint(*args), before,
                                    "moving %s must invalidate the cost cache" % name)
            finally:
                setattr(cost_mod, name, original)


class WhatAPlaceholderCostsTest(unittest.TestCase):
    def test_a_locked_chapter_no_longer_prices_like_a_cobblestone(self):
        table = costs_for(two_routes())
        self.assertEqual(table[PLAIN], cost_mod.BASE_RAW_COST)
        self.assertEqual(table[GATED], cost_mod.GATE_COST)
        self.assertNotEqual(table[GATED], table[PLAIN])

    def test_loot_sits_between_them(self):
        table = costs_for(two_routes())
        self.assertEqual(table[LOOTED], cost_mod.LOOT_COST)
        self.assertLess(table[PLAIN], table[LOOTED])
        self.assertLess(table[LOOTED], table[GATED])

    def test_a_hint_stays_at_a_raw_leaf_because_it_is_not_an_obstacle(self):
        # `good_sword_materials` says the recipe takes any member of a class. There is
        # nothing to obtain, so it stands in for one ordinary material.
        g = two_routes()
        g.add(Recipe("via_hint", "t", [("mod:sword", 1)], [Ingredient([HINTED], 1)],
                     category="minecraft.crafting"))
        self.assertEqual(costs_for(g)[HINTED], cost_mod.BASE_RAW_COST)

    def test_a_method_stays_at_a_raw_leaf_because_the_machine_is_already_charged(self):
        # Pricing METHOD as an obstacle would double-count: `category_entry_cost` has
        # already charged for the machine the note is telling you about.
        key = "contenttweaker:multiblock_preview"
        g = two_routes()
        g.add(Recipe("via_method", "t", [("mod:thing", 1)], [Ingredient([key], 1)],
                     category="compactmachines3.crafting"))
        table = costs_for(g, token_kinds={key: tokens_mod.METHOD})
        self.assertEqual(table[key], cost_mod.BASE_RAW_COST)

    def test_no_token_map_applies_no_token_price(self):
        # The parameter is optional everywhere, and a caller that does not pass it must get
        # an unapplied table rather than a half-applied one.
        #
        # THIS ASSERTED `BASE_RAW_COST` UNTIL #171, and the constant moved rather than the
        # claim. `contenttweaker:chapter_1` is pack-authored and nothing produces it, so
        # `Graph.pack_authored_unsourced` now prices it at `UNSOURCED_COST` through a rule
        # that knows nothing about tokens. What this test is for is that the TOKEN seed
        # stayed out of it, so it asserts the absence of `GATE_COST` -- the old spelling
        # would now fail for a reason that has nothing to do with what it guards.
        table = cost_mod.estimate(two_routes())
        self.assertNotEqual(table[GATED], cost_mod.GATE_COST)
        self.assertEqual(table[GATED], cost_mod.UNSOURCED_COST)

    def test_stock_still_outranks_a_placeholder(self):
        # The token seed is the one that RAISES a price, so it needs a guard: something you
        # hold is a stronger claim about this world than a curated list is.
        self.assertEqual(costs_for(two_routes(), have={GATED: 1})[GATED], 0.0)


class WhichRouteGetsPickedTest(unittest.TestCase):
    def test_the_ungated_route_wins(self):
        # The point of the whole change. All three routes are one hand-crafted ingredient,
        # so before the fix they tied at 1.0 and the winner was whichever `max` saw first.
        self.assertEqual(chosen(two_routes()), "via_rock")

    def test_loot_beats_a_gate_when_those_are_the_only_two(self):
        g = two_routes()
        g.recipes = [r for r in g.recipes if r.rid != "via_rock"]
        g._invalidate()
        self.assertEqual(chosen(g), "via_loot")

    def test_a_gated_route_is_still_taken_when_it_is_the_only_one(self):
        # Property 3 in a plan rather than in constants. A gate is expensive, not fatal.
        g = two_routes()
        g.recipes = [r for r in g.recipes if r.rid == "via_gate"]
        g._invalidate()
        solver = Solver(g, costs=costs_for(g), token_kinds=KINDS)
        result = solver.solve(TARGET, 1)
        self.assertEqual(result["tree"]["recipe"], "via_gate")
        # And it still reports as a gate rather than as a material to buy.
        self.assertEqual([e["key"] for e in result["tokens_needed"]], [GATED])
        self.assertEqual(result["shopping_list"], [])

    def test_the_node_is_still_badged_locked(self):
        # Pricing must not disturb the reporting half, which already worked.
        g = two_routes()
        g.recipes = [r for r in g.recipes if r.rid == "via_gate"]
        g._invalidate()
        leaf = Solver(g, costs=costs_for(g),
                      token_kinds=KINDS).solve(TARGET, 1)["tree"]["children"][0]
        self.assertEqual(leaf["status"], STATUS_TOKEN)
        self.assertEqual(leaf["token_kind"], tokens_mod.GATE)

    def test_a_second_rule_now_separates_them_even_with_no_token_map(self):
        # This stated #105's defect as an equality -- with no token map the three routes
        # priced identically, so the choice fell to dump order -- and #171 made the equality
        # false without touching tokens at all. Both placeholders are pack-authored keys
        # nothing produces, so they are repriced as "the tool cannot explain where this comes
        # from" whether or not anybody curated them.
        #
        # KEPT AS A SEPARATION TEST RATHER THAN DELETED, because the defect it recorded is
        # exactly what would come back if either rule were removed, and now two independent
        # rules have to fail for the routes to tie again. The token map is still what makes
        # the price SPECIFIC: `UNSOURCED_COST` says only that the graph cannot account for
        # the key, where `GATE_COST` says the player has a chapter to unlock.
        table = cost_mod.estimate(two_routes())
        self.assertNotEqual(table[GATED], table[PLAIN])
        self.assertNotEqual(table[LOOTED], table[PLAIN])
        self.assertEqual(cost_mod.BASE_RAW_COST, table[PLAIN])

        gated = costs_for(two_routes())
        self.assertEqual(cost_mod.GATE_COST, gated[GATED])
        self.assertEqual(cost_mod.LOOT_COST, gated[LOOTED])


class TheCacheNoticesTest(unittest.TestCase):
    """`data/tokens.json` is user-editable, so the fingerprint has to cover it."""

    def test_editing_the_token_map_changes_the_fingerprint(self):
        # Without this, adding or disabling a gate moves a key between 1.0 and 1,000 while
        # graph, stock and machine states all stay identical -- so the edit lands in the
        # badges, which read the map directly, and not in the prices.
        args = ("graph.json", {}, {}, {}, {}, {})
        self.assertNotEqual(cost_mod.fingerprint(*args, token_kinds=KINDS),
                            cost_mod.fingerprint(*args, token_kinds={}))

    def test_disabling_one_token_changes_it_too(self):
        args = ("graph.json", {}, {}, {}, {}, {})
        fewer = {k: v for k, v in KINDS.items() if k != GATED}
        self.assertNotEqual(cost_mod.fingerprint(*args, token_kinds=KINDS),
                            cost_mod.fingerprint(*args, token_kinds=fewer))

    def test_the_formula_version_moved(self):
        self.assertGreaterEqual(cost_mod.FORMULA_VERSION, 6)


class EveryCallerPricesTokensTest(unittest.TestCase):
    """A call site left un-threaded prices gates at 1.0 while the badges say "locked".

    Signature-level rather than behavioural on purpose: the failure is a caller that simply
    does not pass the argument, and that is invisible to any test of the function itself.
    """

    def test_estimate_and_estimate_cached_both_accept_it(self):
        import inspect
        for fn in (cost_mod.estimate, cost_mod.estimate_cached, cost_mod._seed,
                   cost_mod.fingerprint):
            self.assertIn("token_kinds", inspect.signature(fn).parameters, fn.__name__)

    def test_the_server_and_the_cli_pass_it(self):
        import inspect

        from recipegraph import cli, server
        for mod, needle in ((server, "token_kinds=self.token_kinds"),
                            (cli, "token_kinds=token_kinds")):
            self.assertIn(needle, inspect.getsource(mod),
                          "%s calls estimate without the token map" % mod.__name__)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
