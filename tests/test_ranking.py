"""Recipe choice: the price a route is ranked at has to be the price of the route taken.

All of this is issue #29, reported as "it shows that to make 'iron' I need a 'molten iron
can' even though iron ore is perfectly acceptable". Three separate defects produced that
one plan, and each has its own class below:

  * `BatchAmortisationTest` -- the machine was divided by the output quantity, so a recipe
    yielding 1,024 ingots priced them at nothing and 126 items in the reference pack,
    diamond and coal among them, came out under 0.1.
  * `RankedRouteIsTheTakenRouteTest` -- a slot accepting either a Block of Iron or a
    decorative Chisel block was PRICED at the decorative one (a raw leaf, so cheap) and
    EXPANDED at the real one (which is cast from 1,296 mB of molten iron).
  * `CostBlindChoiceTest` -- with nothing in stock, every alternative tied and the choice
    fell through to whatever the dump listed first.

The fixtures deliberately mirror those shapes rather than inventing tidier ones; a graph
that cannot express the bug cannot prove the fix.
"""

import json
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, graphview, present, render, solve  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

# WHERE THE TWO CYCLE TERMS SIT IN `score_recipe`'s tuple, named rather than spelled as
# index literals. #172 split one counter into two and moved one of them across `cheap`, and
# four assertions here were reading `[2]`: with literals that reordering is four silent index
# bumps, and with these it is a constant a reader has to look at and agree with.
ANCESTOR_CYCLIC = 1
OWN_CYCLIC = 3
SIMPLE_PLAIN = 6

# `minecraft.crafting` and `minecraft.smelting` are what `machines.is_hand_crafting`
# recognises, so using those names keeps the fixtures on the same code path as the pack.
STATES = {
    "minecraft.crafting": ("have", ""),
    "minecraft.smelting": ("have", ""),
    "mod.casting": ("buildable", ""),
    "mod.smeltery": ("buildable", ""),
    "mod.big": ("buildable", ""),
    "mod.gone": ("unavailable", ""),
}


def iron_graph():
    """The reported plan's shape: an honest smelt losing to an unpacking step.

    `mod:decor` is the decorative block. It is an input alternative that no recipe
    produces, which is the whole point -- it lands on BASE_RAW_COST and makes the
    unpacking recipe look like the cheapest thing in the graph.
    """
    g = Graph()
    g.names = {
        "mod:ingot": "Iron Ingot", "mod:ore": "Iron Ore", "mod:rock": "Ore Chunk",
        "mod:block": "Block of Iron", "mod:decor": "Chiselled Iron Panel",
    }
    # The honest route, and a recipe for the ore so the smelt is not the cheapest thing
    # by default. Without this the two routes TIE and the test would only be measuring
    # which one the dump happens to list first.
    g.add(Recipe("smelt", "t", [("mod:ingot", 1)],
                 [Ingredient(["mod:ore"], 1)], category="minecraft.smelting"))
    g.add(Recipe("crush_rock", "t", [("mod:ore", 1)],
                 [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
    # The decoy: unpack a block into nine ingots. The slot takes the real block OR the
    # decorative one, exactly as the pack's does.
    g.add(Recipe("uncraft", "t", [("mod:ingot", 9)],
                 [Ingredient(["mod:block", "mod:decor"], 1)],
                 category="minecraft.crafting"))
    # ...and the only way to a real block is casting molten metal, which is expensive.
    g.add(Recipe("cast", "t", [("mod:block", 1)],
                 [Ingredient(["fluid:molten"], 1296, "fluid")], category="mod.casting"))
    g.add(Recipe("melt", "t", [("fluid:molten", 288)],
                 [Ingredient(["mod:ore"], 1)], category="mod.smeltery"))
    return g


class BatchAmortisationTest(unittest.TestCase):
    """Cost of entry is charged per run; only the ingredients divide by the batch."""

    @staticmethod
    def _graph(qty, category):
        g = Graph()
        g.names = {}
        g.add(Recipe("batch", "t", [("mod:out", qty)],
                     [Ingredient(["mod:seed"], 1)], category=category))
        return g

    def test_a_huge_batch_does_not_make_its_machine_free(self):
        costs = cost.estimate(self._graph(1024, "mod.big"), machine_states=STATES)
        self.assertGreater(costs["mod:out"], cost.MACHINE_COST["buildable"])

    def test_the_ingredients_still_amortise(self):
        # The fix must not turn into "batching buys you nothing". One seed spread over
        # 1,024 outputs has to cost less per output than one seed spread over one.
        big = cost.estimate(self._graph(1024, "mod.big"), machine_states=STATES)
        one = cost.estimate(self._graph(1, "mod.big"), machine_states=STATES)
        self.assertLess(big["mod:out"], one["mod:out"])

    def test_an_unavailable_machine_cannot_be_diluted_away(self):
        # MACHINE_COST puts a 5,000 wall in front of a machine that cannot be obtained.
        # The pack has a recipe yielding 60,466,176 of one item, which divided that wall
        # down to 8e-5 -- so the wall existed only for recipes that did not need it.
        costs = cost.estimate(self._graph(60466176, "mod.gone"), machine_states=STATES)
        self.assertGreater(costs["mod:out"], cost.MACHINE_COST["unavailable"])

    def test_the_formula_version_invalidates_a_cached_table(self):
        # Every other input to the estimate is unchanged by an arithmetic change, so
        # without this a machine holding a cost cache serves pre-fix prices forever.
        args = ("graph.json", {}, {}, ())
        before = cost.fingerprint(*args)
        original = cost.FORMULA_VERSION
        try:
            cost.FORMULA_VERSION = original + 1
            self.assertNotEqual(cost.fingerprint(*args), before)
        finally:
            cost.FORMULA_VERSION = original


class RankedRouteIsTheTakenRouteTest(unittest.TestCase):
    """`score_recipe` must price the branch `_build` will expand, slot by slot."""

    @staticmethod
    def _solved():
        g = iron_graph()
        costs = cost.estimate(g, machine_states=STATES)
        return Solver(g, machine_states=STATES, costs=costs).solve("mod:ingot", 1)

    def test_one_ingot_comes_from_smelting_an_ore(self):
        result = self._solved()
        self.assertEqual(result["tree"]["recipe"], "smelt")
        self.assertEqual([c["key"] for c in result["tree"]["children"]], ["mod:ore"])

    def test_the_plan_never_reaches_the_molten_metal(self):
        # The reported symptom in the reporter's own words. Asserting on the chosen recipe
        # alone would still pass if a later slot wandered into the casting chain.
        result = self._solved()

        def keys(node):
            return [node["key"]] + [k for c in node.get("children") or () for k in keys(c)]

        self.assertNotIn("fluid:molten", keys(result["tree"]))

    def test_the_recipe_is_priced_at_the_alternative_it_would_expand(self):
        # The mechanism, stated directly: `mod:decor` is the cheaper option in that slot
        # and `mod:block` is the one `pick_alternative` returns, so the score has to come
        # from `mod:block`.
        g = iron_graph()
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs)
        uncraft = [r for r in g.recipes if r.rid == "uncraft"][0]
        self.assertEqual(solver.pick_alternative(uncraft.inputs[0]), "mod:block")
        self.assertLess(costs["mod:decor"], costs["mod:block"])
        self.assertAlmostEqual(
            solver.estimated_cost(uncraft),
            cost.MACHINE_COST["have"] + costs["mod:block"], places=6)

    def test_stock_still_makes_unpacking_the_right_answer(self):
        # The fix demotes unpacking because the block is expensive to MAKE, not because
        # unpacking is forbidden. Hand the solver a block and it should use it.
        #
        # `have` goes to BOTH the estimate and the Solver, which is what cli.py and
        # server.py do. Passing it to only the Solver leaves the ranker pricing the block
        # at what it costs to manufacture, and a stocked block loses to smelting an ore
        # it does not have -- the same class of disagreement this whole module is about.
        g = iron_graph()
        stock = {"mod:block": 4}
        costs = cost.estimate(g, have=stock, machine_states=STATES)
        result = Solver(g, have=dict(stock), machine_states=STATES,
                        costs=costs).solve("mod:ingot", 9)
        self.assertEqual(result["tree"]["recipe"], "uncraft")
        self.assertEqual(result["tree"]["children"][0]["status"], "have")


class CostBlindChoiceTest(unittest.TestCase):
    """With an empty pool, availability ties and cost is the only real signal left."""

    @staticmethod
    def _graph():
        """Two routes to the same material, the ruinous one listed first."""
        g = Graph()
        g.names = {}
        g.ore_members = {"stuff": ["mod:pricey", "mod:cheap"]}
        g.add(Recipe("use_alt", "t", [("mod:thing", 1)],
                     [Ingredient(["mod:pricey", "mod:cheap"], 1)],
                     category="minecraft.crafting"))
        g.add(Recipe("use_ore", "t", [("mod:widget", 1)],
                     [Ingredient(["ore:stuff"], 1)], category="minecraft.crafting"))
        # Both alternatives are producible, so the has-a-recipe bonus cannot separate them.
        g.add(Recipe("make_pricey", "t", [("mod:pricey", 1)],
                     [Ingredient(["mod:rock"], 64)], category="mod.big"))
        g.add(Recipe("make_cheap", "t", [("mod:cheap", 1)],
                     [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
        return g

    def _solve(self, target):
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        self.assertLess(costs["mod:cheap"], costs["mod:pricey"])
        return Solver(g, machine_states=STATES, costs=costs).solve(target, 1)

    def test_an_input_slot_takes_the_cheaper_alternative(self):
        result = self._solve("mod:thing")
        self.assertEqual([c["key"] for c in result["tree"]["children"]], ["mod:cheap"])

    def test_an_oredict_resolves_to_the_cheaper_member(self):
        result = self._solve("mod:widget")
        self.assertEqual(result["tree"]["children"][0]["resolved_to"], "mod:cheap")

    def test_stock_still_outranks_cost(self):
        # Cost is a TIEBREAK. Something already on the shelf ends the branch, which is
        # worth more than a cheaper thing that still has to be made.
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        result = Solver(g, have={"mod:pricey": 1}, machine_states=STATES,
                        costs=costs).solve("mod:thing", 1)
        self.assertEqual([c["key"] for c in result["tree"]["children"]], ["mod:pricey"])

    def test_slot_cost_is_the_same_arithmetic_the_ranker_uses(self):
        # `slot_cost` claims to be `cost.input_cost` rather than a private lookup, which is
        # what makes the tiebreak comparable to the price. Two things only that shared
        # normalisation gets right: an oredict key resolves to its cheapest MEMBER, and a
        # fluid is measured in buckets, so 1,000 mB does not read as a thousand items.
        g = self._graph()
        g.add(Recipe("boil", "t", [("fluid:brine", 1000)],
                     [Ingredient(["mod:rock"], 1)], category="minecraft.crafting"))
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs)
        self.assertEqual(solver.slot_cost("ore:stuff"), costs["mod:cheap"])
        self.assertAlmostEqual(solver.slot_cost("fluid:brine", 1000),
                               costs["fluid:brine"], places=6)

    def test_a_solver_without_costs_is_unaffected(self):
        # `slot_cost` returns 0.0 rather than infinity precisely so this stays true, and
        # a bare `Solver(graph)` is what most of the other suites build.
        g = self._graph()
        solver = Solver(g)
        self.assertEqual(solver.slot_cost("mod:cheap"), 0.0)
        self.assertEqual(solver.slot_cost("ore:stuff"), 0.0)
        self.assertEqual(solver.solve("mod:thing", 1)["tree"]["children"][0]["key"],
                         "mod:pricey")


def tied_ore_graph():
    """Issue #61's shape: an honest smelt TIED with unpacking a decorative panel.

    Deliberately the case `iron_graph` avoids. There it gives the ore its own recipe so
    the smelt is genuinely cheaper; here both routes rest on one raw leaf apiece and land
    on the same `BASE_RAW_COST`, which is what the reference pack does 44 ways for Diamond.
    `uncraft` is added FIRST so that without a tiebreak it wins on dump order as well as
    on the `plain` hand-crafting bonus.
    """
    g = Graph()
    g.names = {
        "mod:gem": "Gem", "mod:gem_ore": "Gem Ore", "mod:panel": "Gem Panel",
        "mod:dust": "Gem Dust",
    }
    g.ore_members = {
        # What the pack registers. The ore is an `ore*` group and the panel is not, which
        # is the entire signal: both are blocks, only one is something you mine.
        "oreGem": ["mod:gem_ore"],
        "blockGem": ["mod:panel"],
        "empty": [],
    }
    g.add(Recipe("uncraft", "t", [("mod:gem", 1)],
                 [Ingredient(["mod:panel"], 1)], category="minecraft.crafting"))
    g.add(Recipe("smelt", "t", [("mod:gem", 1)],
                 [Ingredient(["mod:gem_ore"], 1)], category="minecraft.smelting"))
    return g


class SelfConsumingRecipeTest(unittest.TestCase):
    """A recipe that eats its own output must never be the recommended route to it.

    `score_recipe` has always claimed to rank these last -- "producing a plan that asks for
    the very thing being crafted" is its own docstring -- but it counted only `ancestors`,
    which misses two shapes. Both were found by measuring #61 on the reference graph, and
    each has its own test below:

      * a BYPRODUCT that feeds back, which no ancestor set ever holds, so the miss is at
        every depth rather than only at the top;
      * `score_recipe` called with no ancestors at all, which is what the recipe-chooser
        page does, so a self-consumer was the tool's printed recommendation for pinning.

    `_build` itself was never blind: it passes `ancestors | {key}`. That is why the tests
    here assert on `score_recipe` and on the byproduct, not on a plain plan.
    """

    @staticmethod
    def _graph():
        """The chooser page's shape: two routes to `mod:gem`, one self-consuming.

        Priced level ON PURPOSE. `cheap` outranks the cycle term, so a self-consumer that
        is genuinely dearer is already demoted and would make the test pass for the wrong
        reason. Here neither route is reachable (`mod:ore` has no recipe and `mod:junk` has
        none either), so every candidate prices at infinity, the comparison falls through
        to the cycle term, and that is exactly what happens on the real graph.
        """
        g = Graph()
        g.names = {"mod:gem": "Gem", "mod:junk": "Junk", "mod:ore": "Gem Ore"}
        # The self-consumer is added FIRST so it wins on dump order if nothing stops it,
        # and both routes have two slots so `simple` cannot decide it either.
        g.add(Recipe("upgrade", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:gem"], 1), Ingredient(["mod:junk"], 1)],
                     category="minecraft.crafting"))
        g.add(Recipe("smelt", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:ore"], 1), Ingredient(["mod:junk"], 1)],
                     category="minecraft.crafting"))
        return g

    def _solver(self, graph=None, **kw):
        g = graph or self._graph()
        costs = cost.estimate(g, have=kw.get("have"), machine_states=STATES)
        return g, Solver(g, machine_states=STATES, costs=costs, **kw)

    def test_the_recipe_chooser_does_not_recommend_a_self_consumer(self):
        # `server.recipes_page` ranks with `solver.score_recipe(r)`, no ancestors. That is
        # the order someone reads while deciding what to pin, so a self-consumer at the top
        # is the tool recommending a recipe that needs its own output.
        g, solver = self._solver()
        by_id = {r.rid: r for r in g.recipes}
        ranked = sorted(g.real_producers("mod:gem"),
                        key=lambda r: solver.score_recipe(r), reverse=True)
        self.assertEqual(ranked[0].rid, "smelt")
        self.assertEqual(solver.score_recipe(by_id["upgrade"])[OWN_CYCLIC], -1)
        self.assertEqual(solver.score_recipe(by_id["smelt"])[OWN_CYCLIC], 0)

    def test_a_byproduct_that_feeds_back_is_still_a_cycle(self):
        # `_build` passes `ancestors | {key}`, so the key being planned is always covered.
        # A recipe emitting (gem, slag) while consuming slag is cyclic through the OTHER
        # output, which no ancestor set holds at any depth. This is the Heart Fruit Seeds
        # shape: an insolator emitting 12 fruit plus 1 seed while eating a seed.
        g = Graph()
        g.names = {"mod:gem": "Gem", "mod:slag": "Slag", "mod:junk": "Junk"}
        g.add(Recipe("recycle", "t", [("mod:gem", 1), ("mod:slag", 1)],
                     [Ingredient(["mod:slag"], 1), Ingredient(["mod:junk"], 1)],
                     category="minecraft.crafting"))
        g.add(Recipe("forge", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:junk"], 1), Ingredient(["mod:junk2"], 1)],
                     category="minecraft.crafting"))
        _g, solver = self._solver(graph=g)
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.score_recipe(by_id["recycle"])[OWN_CYCLIC], -1)
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "forge")

    def test_stock_still_makes_an_upgrade_recipe_usable(self):
        # Gated on `available(alt) < qty`, the same as the ancestor case: an upgrade you can
        # feed from stock is a real route, not a cycle. Without the gate this would forbid
        # every repair and upgrade recipe in the pack.
        g, solver = self._solver(have={"mod:gem": 4, "mod:junk": 4})
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.score_recipe(by_id["upgrade"])[OWN_CYCLIC], 0)

    def test_it_is_still_chosen_when_it_is_the_only_producer(self):
        # Ranked last is not forbidden. `pick_recipe` takes the best of what exists, and a
        # plan through a self-consumer is more use than no plan; `_build`'s cycle guard is
        # what stops it recursing.
        g = Graph()
        g.names = {"mod:gem": "Gem", "mod:junk": "Junk"}
        g.add(Recipe("upgrade", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:gem"], 1), Ingredient(["mod:junk"], 1)],
                     category="minecraft.crafting"))
        _g, solver = self._solver(graph=g)
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "upgrade")
        self.assertEqual(solver.solve("mod:gem", 1)["tree"]["recipe"], "upgrade")


class TheNamedScorePositionsMatchTheTupleTest(unittest.TestCase):
    """The three constants above must point at the terms they are named for.

    THEY ARE INDEX LITERALS WEARING A NAME, AND THAT IS ALL. Naming them stopped four
    assertions being four silent index bumps when #172 reordered the tuple, but it does not
    stop the NEXT reordering leaving a constant pointing at the wrong element -- and then
    `score[OWN_CYCLIC]` reads some other term, the assertion still compares two numbers, and
    it can quite easily still pass. Same shape as the drift #178 found, where a comparison
    test agreed with itself for two releases.

    EVERY NAMED POSITION HOLDS A DISTINCT VALUE IN THIS FIXTURE, AND THAT IS THE WHOLE
    DESIGN. The first version of this class used a one-slot recipe, so `own_cyclic` was 0 --
    and so was `satisfied` next door. Moving `OWN_CYCLIC` from 3 to 4 read `satisfied`, got
    0, and the pin PASSED. Measured, not imagined: the drift arm was run and came back OK.
    A pin whose neighbours share its value is not a pin.

    So: one ancestor slot, TWO own-output slots, nothing satisfied. `-1` and `-2` cannot be
    confused with each other or with anything else in the tuple, and the whole tuple is
    asserted rather than three positions out of eight.
    """

    @staticmethod
    def _scored():
        """A score whose every term is known by construction. `(score, expected)`."""
        g = Graph()
        g.names = {"mod:gem": "Gem", "mod:slag": "Slag", "mod:dross": "Dross"}
        # Consumes the key being planned (ANCESTOR, and the elif puts a both-slot here),
        # plus two of its own other outputs (OWN, twice). Nothing is in stock, so all three
        # count and `satisfied` is 0.
        g.add(Recipe("loop", "t",
                     [("mod:gem", 1), ("mod:slag", 1), ("mod:dross", 1)],
                     [Ingredient(["mod:gem"], 1), Ingredient(["mod:slag"], 1),
                      Ingredient(["mod:dross"], 1)],
                     category="minecraft.crafting"))
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs)
        recipe = g.real_producers("mod:gem")[0]
        return solver.score_recipe(recipe, frozenset(["mod:gem"]))

    def test_the_whole_tuple_is_what_the_constants_assume(self):
        """Every position, not only the named three.

        Asserting the whole thing is what makes ANY reordering fail here rather than
        somewhere confusing. `cheap` is the one computed term and is checked for shape.
        """
        scored = self._scored()
        self.assertEqual(8, len(scored))
        self.assertEqual(1, scored[0], "real production, not a container transfer")
        self.assertEqual(-1, scored[ANCESTOR_CYCLIC], "one ancestor slot")
        self.assertIsInstance(scored[2], float)
        self.assertLess(scored[2], 0.0, "`cheap` is -cost and the cost here is positive")
        self.assertEqual(-2, scored[OWN_CYCLIC], "two own-output slots")
        self.assertEqual(0, scored[4], "nothing is in stock")
        self.assertEqual(0, scored[5], "no raw leaf, so not ore-backed")
        # Three merged slots: 1/(1+3) plus the 0.1 hand-crafting bonus.
        self.assertAlmostEqual(0.35, scored[SIMPLE_PLAIN], places=9)
        self.assertEqual(2, scored[7], "minecraft.crafting is a machine you have")

    def test_no_named_position_shares_a_value_with_another(self):
        """The property that makes the assertions above a pin rather than a coincidence.

        If two positions held the same number, a constant could drift onto its neighbour and
        every assertion would still pass. Checked here so that a future edit to the fixture
        cannot quietly remove the thing that makes this class work.
        """
        scored = self._scored()
        named = [scored[ANCESTOR_CYCLIC], scored[OWN_CYCLIC], scored[SIMPLE_PLAIN]]
        for position, value in enumerate(scored):
            for name_value in named:
                if value == name_value:
                    self.assertIn(position, (ANCESTOR_CYCLIC, OWN_CYCLIC, SIMPLE_PLAIN),
                                  "position %d holds %r, which a named constant also "
                                  "reads -- a drift onto it would go unnoticed"
                                  % (position, value))

    def test_the_ancestor_term_outranks_price_and_the_own_term_does_not(self):
        """The ordering claim itself, read off the tuple rather than off the docstring.

        This is what #172 changed and the one thing a future reordering must not undo
        quietly. `cheap` sits between the two cycle terms; if either constant crosses it,
        the split is gone and only this assertion says so.
        """
        self.assertLess(ANCESTOR_CYCLIC, OWN_CYCLIC)
        cheap_position = ANCESTOR_CYCLIC + 1
        self.assertLess(cheap_position, OWN_CYCLIC)
        self.assertIsInstance(self._scored()[cheap_position], float)


class CheapCycleBeatsExpensiveRealRouteTest(unittest.TestCase):
    """Issue #172: a route that cannot be performed must not win on price.

    THE SHAPE, reduced from the reported one. Planning a Sednanite Ingot produced

        craft  Sednanite Ingot    <- casting table
          craft  Molten Sednanite <- smeltery
            craft  Sednanite Dust <- crafting
              cycle Sednanite Ingot            <- the TARGET

    and a shopping list containing Sednanite Ingot. A plan for X whose shopping list
    contains X cannot be executed, and unlike an expensive answer it does not look wrong.
    The cheap route scored 110 against the real one's 802, so `cheap` decided outright and
    the cycle term never voted.

    EVERY TEST HERE WAS RUN AGAINST THE TWO ORDERINGS IT EXISTS TO REJECT, because a test
    that has only ever been observed passing is worth less than none when the change is an
    ordering nobody can eyeball. Measured by patching `score_recipe`'s tuple and running
    this class:

      * the OLD ordering, `(transfer, cheap, -cyclic, ...)` with one merged counter below
        the price: 5 of 7 fail, including all three behavioural claims.
      * the NAIVE FIX, the merged counter promoted whole to `(transfer, -cyclic, cheap,
        ...)`: 3 of 7 fail, and the one that matters is
        `test_a_returned_seed_is_NOT_demoted_by_this` -- the naive fix passes every
        cycle assertion in this class and breaks the farm.
      * the shipped split ordering: all 7 pass.

    So the class separates three designs rather than merely agreeing with the one that
    shipped.

    Measured on the reference graph: 48 of 23,476 multi-producer keys have a cyclic winner
    with a clean route available, 17 of them bottom out on a cycle leaf today, and the
    split ordering moves 35 while keeping both measured byproduct farms.
    """

    @staticmethod
    def _graph():
        """One cheap self-consuming route and one expensive real one, NOT priced level.

        Deliberately unlike `SelfConsumingRecipeTest._graph`, which prices both at infinity
        so the comparison falls through to the cycle term. That is the case the old ordering
        already handled. This is the case it did not: a REAL price difference, where `cheap`
        decides before the cycle term is ever consulted.

        THE LOOP IS ONE STEP, NOT TWO, AND THAT IS A LIMIT OF THE TERM RATHER THAN A
        SIMPLIFICATION OF THE FIXTURE. `score_recipe` sees one recipe and one ancestor set;
        it cannot see `ingot -> molten -> dust -> ingot` from the ingot's own candidate
        list, because at that level the casting recipe's only slot is molten metal and
        looks clean. What it CAN see is a recipe for X that consumes X, which is what
        `expand` ranks it against -- it passes `ancestors | {key}`, so the key being planned
        is always in the set. Every one of the 35 keys the reference measurement moves has
        that shape. A deeper loop is caught by `_build`'s backtracking instead, and where
        backtracking also fails there is nothing in a one-recipe score that could have
        helped.

        THE FOUR IS WHAT MAKES THE CYCLE CHEAP, and it is the mechanism rather than a
        convenience. Amortising a recipe over its output count is correct and is
        `BatchAmortisationTest` above; the consequence is that a self-consuming recipe with
        a yield converges DOWNWARDS under relaxation. Here the gem settles at 0.667 against
        the smelter's 9, so `cheap` decides outright and the cycle term never votes. With
        `polish` yielding one gem the loop settles above the smelter and there is no bug.
        """
        g = Graph()
        g.names = {"mod:gem": "Gem", "mod:dust": "Dust", "mod:ore": "Gem Ore"}
        # The cheap cycle: four polished gems out of one gem and some dust. Realistic --
        # this is the shape of every upgrade, repair and exchange recipe in the pack.
        g.add(Recipe("polish", "t", [("mod:gem", 4)],
                     [Ingredient(["mod:gem"], 1), Ingredient(["mod:dust"], 1)],
                     category="minecraft.crafting"))
        # The expensive real route: smelting eight ore.
        g.add(Recipe("smelt", "t", [("mod:gem", 1)], [Ingredient(["mod:ore"], 8)],
                     category="minecraft.smelting"))
        return g

    def _solver(self):
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        return g, Solver(g, machine_states=STATES, costs=costs)

    def test_the_cheap_route_really_is_cheaper(self):
        # Without this the other assertions could pass because the cycle happened to be
        # dearer, which is the bug not reproducing rather than the fix working.
        g, solver = self._solver()
        by_id = {r.rid: r for r in g.recipes}
        self.assertLess(solver.estimated_cost(by_id["polish"]),
                        solver.estimated_cost(by_id["smelt"]))

    def test_the_expensive_real_route_wins(self):
        # THE ASSERTION THAT FAILS BEFORE THE FIX. `expand` ranks with `ancestors | {key}`,
        # so planning an ingot makes `cast`'s dust slot cyclic one level down.
        g, solver = self._solver()
        ranked = sorted(g.real_producers("mod:gem"),
                        key=lambda r: solver.score_recipe(r, frozenset(["mod:gem"])),
                        reverse=True)
        self.assertEqual(ranked[0].rid, "smelt")

    def test_the_plan_does_not_bottom_out_on_the_target(self):
        """The reported symptom end to end, with the safety net removed.

        `branch_tries=1` ON PURPOSE, and the test is worth much less without it. At the
        default of 4 the backtracker rescues this fixture even on the OLD ordering -- it
        enters the cheap cycle, finds the cycle leaf, discards the attempt and takes the
        smelt -- so an assertion here would pass before and after and prove nothing about
        the change. That is not hypothetical: it is what this assertion did when it was
        first written, and it is why the ranking-level tests above are the real proof.

        One try models the 17 keys on the reference graph whose plans bottom out on a
        cycle leaf TODAY, where backtracking is not the safety net it looks like -- every
        alternative it tries also cycles, or the work budget runs out first.
        """
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs, branch_tries=1)
        result = solver.solve("mod:gem", 1)
        self.assertEqual(result["tree"]["recipe"], "smelt")
        self.assertNotIn("mod:gem", [row["key"] for row in result["shopping_list"]])

    def test_price_cannot_outvote_the_ancestor_term(self):
        # The promotion stated directly: make the cycle absurdly cheap and the real route
        # absurdly dear, and the answer must not change. `cheap` sits BELOW this term now.
        g = self._graph()
        costs = cost.estimate(g, machine_states=STATES)
        costs["mod:gem"] = 0.001
        costs["mod:dust"] = 0.001
        costs["mod:ore"] = 9999.0
        solver = Solver(g, machine_states=STATES, costs=costs)
        ranked = sorted(g.real_producers("mod:gem"),
                        key=lambda r: solver.score_recipe(r, frozenset(["mod:gem"])),
                        reverse=True)
        self.assertEqual(ranked[0].rid, "smelt")

    @staticmethod
    def _farm():
        """The Insolator shape: a recipe that eats a seed and gives the seed back.

        `(graph, solver, {rid: recipe})`. Costs are pinned by hand rather than relaxed so
        the farm is unambiguously the CHEAPER route -- if it were dearer the assertions
        below would pass under any ordering, which is the fixture failing to express the
        case rather than the code getting it right.
        """
        g = Graph()
        g.names = {"mod:fruit": "Fruit", "mod:seed": "Seed", "mod:fert": "Fertiliser",
                   "mod:crystal": "Crystal"}
        # The farm: eats a seed, gives the seed back. `own`-cyclic, and cheaper.
        g.add(Recipe("grow", "t", [("mod:fruit", 4), ("mod:seed", 1)],
                     [Ingredient(["mod:seed"], 1), Ingredient(["mod:fert"], 1)],
                     category="minecraft.crafting"))
        # The alternative: no cycle at all, and dearer.
        g.add(Recipe("transmute", "t", [("mod:fruit", 1)],
                     [Ingredient(["mod:crystal"], 4)],
                     category="minecraft.crafting"))
        costs = cost.estimate(g, machine_states=STATES)
        costs["mod:seed"] = 1.0
        costs["mod:fert"] = 1.0
        costs["mod:crystal"] = 50.0
        return (g, Solver(g, machine_states=STATES, costs=costs),
                {r.rid: r for r in g.recipes})

    def test_a_returned_seed_is_NOT_demoted_by_this(self):
        """The reason the counter is SPLIT rather than promoted whole.

        Promoting the merged counter was measured and it regresses #61's case: on the
        reference graph `minecraft:pumpkin` moves off the Insolator (Phyto-Gro + 1 Pumpkin
        Seed + water -> 1 Pumpkin + 1 Pumpkin Seed, 129.90) and onto transmuting a Melon at
        164.18, and `integrateddynamics:menril_log` off the Insolator tree at 173.34 and
        onto Menril Essence at 363.63. The seed comes back; those are sustainable farms, and
        telling a player to transmute melons rather than grow pumpkins is worse advice than
        the bug being fixed.
        """
        g, solver, by_id = self._farm()
        # THE BEHAVIOURAL CLAIM FIRST, and alone, because it is the one that fails under
        # the naive fix. Asserting the tuple positions ahead of it means a merged-counter
        # regression trips the index assertion and the run never reaches the claim that
        # actually matters -- which is how a test comes to look like proof of something it
        # never evaluated.
        self.assertLess(solver.estimated_cost(by_id["grow"]),
                        solver.estimated_cost(by_id["transmute"]))
        self.assertEqual(solver.pick_recipe("mod:fruit").rid, "grow")

    def test_the_returned_seed_counts_as_own_and_not_as_an_ancestor(self):
        # The mechanism behind the test above, kept separate so a failure says which of
        # the two broke: the counting or the ordering.
        g, solver, by_id = self._farm()
        self.assertEqual(solver.score_recipe(by_id["grow"])[OWN_CYCLIC], -1)
        self.assertEqual(solver.score_recipe(by_id["grow"])[ANCESTOR_CYCLIC], 0)

    def test_the_two_halves_are_counted_apart(self):
        # A slot that is BOTH an ancestor and an own output counts once, as the ancestor.
        # `own` is the softer claim and must not shadow the hard one.
        g = Graph()
        g.names = {"mod:gem": "Gem", "mod:junk": "Junk"}
        g.add(Recipe("loop", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:gem"], 1), Ingredient(["mod:junk"], 1)],
                     category="minecraft.crafting"))
        costs = cost.estimate(g, machine_states=STATES)
        solver = Solver(g, machine_states=STATES, costs=costs)
        recipe = g.real_producers("mod:gem")[0]
        scored = solver.score_recipe(recipe, frozenset(["mod:gem"]))
        self.assertEqual(scored[ANCESTOR_CYCLIC], -1)
        self.assertEqual(scored[OWN_CYCLIC], 0)


class WorldOreTiebreakTest(unittest.TestCase):
    """Issue #61: an exact tie must go to the thing you can mine.

    Reported as a plan for one Diamond whose advice was to go and find a Block of Diamond
    Panel. Nothing was mispriced -- `cost.BASE_RAW_COST` is the same 1.0 for every key no
    recipe produces, so the panel and the ore tie exactly and the winner was dump order.
    """

    @staticmethod
    def _solver(graph=None, states=None, **kw):
        g = graph or tied_ore_graph()
        states = STATES if states is None else states
        costs = cost.estimate(g, have=kw.get("have"), machine_states=states,
                              free_sources=kw.get("free_sources"))
        return g, Solver(g, machine_states=states, costs=costs, **kw)

    def test_the_two_routes_really_do_tie_on_price(self):
        # If they did not, this suite would be testing the cost model rather than the
        # tiebreak, and would keep passing after the tiebreak was deleted.
        g, solver = self._solver()
        by_id = {r.rid: r for r in g.recipes}
        self.assertAlmostEqual(solver.estimated_cost(by_id["smelt"]),
                               solver.estimated_cost(by_id["uncraft"]), places=6)

    def test_a_tie_goes_to_the_ore_not_the_decorative_panel(self):
        g, solver = self._solver()
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "smelt")
        self.assertEqual(solver.solve("mod:gem", 1)["tree"]["recipe"], "smelt")

    def test_the_hand_crafting_bonus_is_what_it_has_to_beat(self):
        # `simple + plain` gives unpacking +0.1 for being hand-crafting, so a tiebreak
        # ranked below it would go inert. This pins the ordering of the score tuple.
        g, solver = self._solver()
        by_id = {r.rid: r for r in g.recipes}
        score = solver.score_recipe
        self.assertGreater(score(by_id["smelt"]), score(by_id["uncraft"]))
        # ...and the panel really does win everything below the new term.
        self.assertGreater(score(by_id["uncraft"])[SIMPLE_PLAIN],
                           score(by_id["smelt"])[SIMPLE_PLAIN])

    def test_a_real_price_difference_still_outranks_the_ore(self):
        # The tiebreak settles ties and must never override cost. Price the smelter out
        # and unpacking is the right answer again.
        states = dict(STATES, **{"minecraft.smelting": ("unavailable", "")})
        g, solver = self._solver(states=states)
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "uncraft")

    def test_stock_still_outranks_the_ore(self):
        # `satisfied` sits above the new term, so a panel you already own beats an ore
        # you would have to go and mine.
        g, solver = self._solver(have={"mod:panel": 8})
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "uncraft")

    def test_only_the_ore_prefix_counts(self):
        # `chisel:diamond` is a member of `blockDiamond`. Accepting every oredict group
        # would readmit the decorative blocks this exists to demote.
        g = tied_ore_graph()
        self.assertIn("mod:gem_ore", g.world_ores)
        self.assertNotIn("mod:panel", g.world_ores)

    def test_registering_the_panel_as_an_ore_gives_the_tie_back(self):
        # Stated as a test because it is the failure mode of the signal rather than of the
        # code: this reads pack data, so a pack that calls a panel an ore gets a panel.
        g = tied_ore_graph()
        g.ore_members["oreGem"] = ["mod:gem_ore", "mod:panel"]
        _g, solver = self._solver(graph=g)
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.ore_backed(by_id["uncraft"]), 1)
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "uncraft")

    def test_a_route_resting_on_no_raw_leaf_does_not_outrank_ore(self):
        # DO NOT make `ore_backed` three-way. A recipe with no raw leaf at all scores 0,
        # the same as one resting on junk. Returning 2 there ranks above `simple + plain`
        # and so avoids a raw leaf at any price in complexity: measured on the reference
        # graph it rewrote Cherry Fence as a nine-slot spelling of itself and Tape Measure
        # from `Iron Ingot + Tape` to `Iron Ingot + Iron Ingot + Tape Measure Reel`.
        g = tied_ore_graph()
        g.add(Recipe("polish", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:dust"], 1)], category="minecraft.crafting"))
        g.add(Recipe("grind", "t", [("mod:dust", 1)],
                     [Ingredient(["mod:gem_ore"], 1)], category="minecraft.crafting"))
        _g, solver = self._solver(graph=g)
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.ore_backed(by_id["polish"]), 0)
        self.assertEqual(solver.ore_backed(by_id["smelt"]), 1)

    def test_partial_stock_does_not_satisfy_a_slot(self):
        # `>= qty`, not "is there any". A slot needing 64 with 1 on the shelf still
        # dead-ends for the other 63, and reading it as satisfied would let a junk leaf
        # disappear from the count and make the route look ore-backed.
        g = tied_ore_graph()
        g.add(Recipe("bulk", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:panel"], 64)], category="minecraft.smelting"))
        _g, solver = self._solver(graph=g, have={"mod:panel": 1})
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.ore_backed(by_id["bulk"]), 0)
        # ...and covering the whole slot does satisfy it.
        _g2, full = self._solver(graph=g, have={"mod:panel": 64})
        self.assertEqual(full.ore_backed(by_id["bulk"]), 0)
        self.assertEqual(full.available("mod:panel"), 64)

    def test_a_user_declared_raw_stop_is_a_dead_end(self):
        # `_build` honours `self.raw` before looking for a recipe, so a route through one
        # ends there. Ignoring it here would call a route ore-backed on the strength of an
        # ore the plan is never going to reach.
        g = tied_ore_graph()
        g.add(Recipe("refine", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:dust"], 1)], category="minecraft.smelting"))
        g.add(Recipe("grind", "t", [("mod:dust", 1)],
                     [Ingredient(["mod:gem_ore"], 1)], category="minecraft.crafting"))
        _g, solver = self._solver(graph=g, raw={"mod:dust"})
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.ore_backed(by_id["refine"]), 0)
        self.assertEqual(solver.ore_backed(by_id["smelt"]), 1)

    def test_an_autocraftable_or_infinite_input_is_not_a_dead_end(self):
        # Both terminate a branch in `_build` with something you effectively have, so
        # neither is a leaf and neither can disqualify a route.
        g = tied_ore_graph()
        g.add(Recipe("brew", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:dust"], 1)], category="minecraft.smelting"))
        _g, solver = self._solver(graph=g, craftables={"mod:dust"})
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.ore_backed(by_id["brew"]), 0)
        _g2, srcs = self._solver(graph=g, free_sources={"mod:dust": "placed: mod:well"})
        self.assertEqual(srcs.ore_backed(by_id["brew"]), 0)

    def test_the_merged_slot_view_is_the_one_used(self):
        # `score_recipe` hands its own `_merge_slots` result over rather than letting this
        # recompute one. A 3x3 of the same ingredient is ONE slot of nine, so passing the
        # raw input list would count nine leaves and could disagree with `satisfied` and
        # `cyclic`, which are scored on the merged view.
        g = tied_ore_graph()
        g.add(Recipe("compress", "t", [("mod:gem", 1)],
                     [Ingredient(["mod:panel"], 1) for _ in range(9)],
                     category="minecraft.smelting"))
        _g, solver = self._solver(graph=g)
        by_id = {r.rid: r for r in g.recipes}
        slots = solver._merge_slots(by_id["compress"])
        # The fourth member is the slot's `consume_chance` (#175). Asserted rather than
        # discarded: these nine slots MUST still collapse to one row, because they share a
        # chance, and `merge_slots` now buckets on the chance as well as the key.
        self.assertEqual([(k, q, c) for k, q, _o, c in slots], [("mod:panel", 9, 1.0)])
        self.assertEqual(solver.ore_backed(by_id["compress"], slots),
                         solver.ore_backed(by_id["compress"]))

    def test_an_unresolvable_oredict_slot_is_a_dead_end_not_an_ore(self):
        # An `ore:` slot whose group has no members is the dead end itself, so it must
        # count as a raw leaf. Left uncounted it would make any recipe taking one look
        # ore-backed for free.
        g = tied_ore_graph()
        g.add(Recipe("ritual", "t", [("mod:gem", 1)],
                     [Ingredient(["ore:empty"], 1)], category="minecraft.smelting"))
        _g, solver = self._solver(graph=g)
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.ore_backed(by_id["ritual"]), 0)

    def test_the_index_is_dropped_with_the_others(self):
        # `_invalidate` documents itself as the only place the index list is written down,
        # and warns that adding one means it gets missed. This is that check.
        g = tied_ore_graph()
        self.assertNotIn("mod:extra", g.world_ores)
        g.ore_members["oreExtra"] = ["mod:extra"]
        g.add(Recipe("noop", "t", [("mod:nothing", 1)],
                     [Ingredient(["mod:gem_ore"], 1)], category="minecraft.smelting"))
        self.assertIn("mod:extra", g.world_ores)

    def test_a_solver_without_costs_is_unaffected(self):
        # Same guarantee `CostBlindChoiceTest` makes: the tiebreak is part of the ranking,
        # and a bare `Solver(graph)` must still behave as it did before cost existed.
        g = tied_ore_graph()
        self.assertEqual(Solver(g).solve("mod:gem", 1)["tree"]["recipe"], "smelt")


def two_machine_graph():
    """One item, two buildable machines, one of which is far dearer to build.

    The shape #86 is about. `mod:cheap_machine` is one stick; `mod:dear_machine` needs 64 of
    them, so the graph itself decides which machine is expensive and no test has to assert a
    number it picked.

    THE DEAR MACHINE'S ROUTE IS DELIBERATELY THE CHEAPER ONE ON INGREDIENTS: one stick
    against two. Under the flat constant both machines cost 40.0, so that one stick decides
    it and the pre-fix ranker prefers `via_dear` -- which is the defect, stated as a fixture.
    A symmetric graph cannot express it: with identical ingredients the two routes tie, some
    unrelated tiebreak picks one, and a test asserting the outcome passes whether the fix is
    present or not. That version of this fixture was written first and did exactly that.
    """
    g = Graph()
    g.names = {"mod:widget": "Widget", "mod:stick": "Stick",
               "mod:cheap_machine": "Cheap Machine", "mod:dear_machine": "Dear Machine"}
    # `machine=` is what `machines.candidate_items` name-matches to find the machine ITEM,
    # so without it both categories resolve to `unknown` ("machine item unknown") and never
    # reach the buildable path this class is about.
    g.add(Recipe("via_cheap", "t", [("mod:widget", 1)], [Ingredient(["mod:stick"], 2)],
                 category="mod.cheap", machine="Cheap Machine"))
    g.add(Recipe("via_dear", "t", [("mod:widget", 1)], [Ingredient(["mod:stick"], 1)],
                 category="mod.dear", machine="Dear Machine"))
    # How each machine is made. Hand-crafted, so the machines' own prices do not depend on
    # the categories under test.
    g.add(Recipe("mk_cheap", "t", [("mod:cheap_machine", 1)],
                 [Ingredient(["mod:stick"], 1)], category="minecraft.crafting"))
    g.add(Recipe("mk_dear", "t", [("mod:dear_machine", 1)],
                 [Ingredient(["mod:stick"], 64)], category="minecraft.crafting"))
    return g


TWO_MACHINE_STATES = {
    "minecraft.crafting": ("have", ""),
    "mod.cheap": ("buildable", "craftable: mod:cheap_machine"),
    "mod.dear": ("buildable", "craftable: mod:dear_machine"),
}
TWO_MACHINE_ITEMS = {"mod.cheap": ("mod:cheap_machine",),
                     "mod.dear": ("mod:dear_machine",)}


class BuildableMachineIsPricedByWhatBuildingItCostsTest(unittest.TestCase):
    """Issue #86. `MACHINE_COST["buildable"]` was one flat figure for every machine.

    Measured on the reference pack before the fix: over the 380 buildable categories whose
    machine item prices finitely, build cost ran from 1.0 (an AE2 grindstone) to 9,288 (a
    NuclearCraft salt fission vessel), a 4,644x spread that the ranker charged 40.0 for
    either way. So it could not prefer the machine the player can actually reach, which is
    the whole complaint: a Mythical Recursive Processor needing unobtainable parts ranked
    level with a machine sitting one craft away.
    """

    def entries(self):
        g = two_machine_graph()
        table = cost.estimate(g, machine_states=TWO_MACHINE_STATES,
                              machine_items=TWO_MACHINE_ITEMS)
        return g, table, table.machine_entry

    def test_the_dear_machine_costs_more_to_enter_than_the_cheap_one(self):
        _g, _t, entry = self.entries()
        self.assertGreater(entry["mod.dear"], entry["mod.cheap"],
                           "the whole point of #86: two buildable machines must differ")

    def test_without_machine_items_both_price_identically(self):
        """The old behaviour, kept reachable and pinned: this is what the bug looked like.

        Also the contract for every caller that does not supply build targets, which is any
        hand-built table in the rest of this suite.
        """
        g = two_machine_graph()
        table = cost.estimate(g, machine_states=TWO_MACHINE_STATES)
        self.assertEqual(table.machine_entry, {})
        self.assertEqual(
            cost.category_entry_cost("mod.cheap", TWO_MACHINE_STATES, table.machine_entry),
            cost.category_entry_cost("mod.dear", TWO_MACHINE_STATES, table.machine_entry))

    def test_the_solver_picks_the_cheaper_machine(self):
        """The behaviour, not just the number. Measured on the reference pack: of the 1,500
        items with the widest entry spread, 211 moved to a cheaper machine and NONE moved to
        a dearer one."""
        g, table, _e = self.entries()
        chosen = Solver(g, machine_states=TWO_MACHINE_STATES, costs=table).pick_recipe(
            "mod:widget")
        self.assertEqual(chosen.rid, "via_cheap")

    def test_the_flat_table_picks_the_dear_machine(self):
        """The defect itself, so the test above cannot pass for an unrelated reason.

        Under the flat constant the two machines cost the same, so the one-stick route wins
        and the ranker sends the player to a machine costing 65 to build in order to save one
        stick. Neutering `build_entry_cost` back to the constant must flip
        `test_the_solver_picks_the_cheaper_machine`, and this is the assertion that says so
        from the other side.
        """
        g = two_machine_graph()
        flat = cost.estimate(g, machine_states=TWO_MACHINE_STATES)
        solver = Solver(g, machine_states=TWO_MACHINE_STATES, costs=flat)
        by_rid = {r.rid: solver.estimated_cost(r) for r in g.real_producers("mod:widget")}
        self.assertLess(by_rid["via_dear"], by_rid["via_cheap"])
        self.assertEqual(solver.pick_recipe("mod:widget").rid, "via_dear")

    def test_a_have_machine_still_beats_every_buildable_one(self):
        # The gap this change must not close. `have` is 1.0 and the band floor is 40.0, and
        # the Borax and Crystallizer cases in cost.py's header turn on that ordering.
        _g, _t, entry = self.entries()
        for uid in ("mod.cheap", "mod.dear"):
            self.assertGreater(entry[uid], cost.MACHINE_COST["have"])

    def test_the_band_never_reaches_unknown(self):
        """MACHINE_COST's comment states `unknown` must not undercut `buildable`.

        That invariant predates this change and survives it, which is why the entry cost is a
        bounded curve rather than the raw build price: the raw price would sail past `unknown`
        AND past `unavailable`, making a machine you can build read as worse than one proven
        impossible.
        """
        self.assertLess(cost.PRICED_CEILING, cost.MACHINE_COST["unknown"])
        self.assertLess(cost.build_entry_cost(1e12), cost.MACHINE_COST["unknown"])
        self.assertLess(cost.build_entry_cost(1e12), cost.MACHINE_COST["unavailable"])
        # #95 put two more slices between the priced band and `unknown`. Neither may cross it.
        self.assertLess(cost.UNPRICED_MACHINE_COST, cost.MACHINE_COST["unknown"])
        self.assertLess(cost.blocked_entry_cost(1.0), cost.MACHINE_COST["unknown"])

    def test_the_band_floor_is_the_buildable_constant(self):
        # A free machine still costs the flat figure to route through. Lowering the floor
        # would make every buildable machine cheaper, which is a different change.
        self.assertEqual(cost.build_entry_cost(0.0), cost.MACHINE_COST["buildable"])
        self.assertGreaterEqual(cost.build_entry_cost(1.0), cost.MACHINE_COST["buildable"])

    def test_entry_cost_is_monotonic_in_build_cost(self):
        # Verified on the reference pack too: 380 finite pairs, 0 violations.
        seen = [cost.build_entry_cost(b)
                for b in (0.0, 1.0, 2.0, 12.0, 67.0, 676.0, 9288.0, 1e9)]
        self.assertEqual(seen, sorted(seen))
        self.assertEqual(len(set(seen)), len(seen), "distinct build costs must not collide")

    def test_an_unreachable_machine_charges_the_pricing_gap_not_unavailable(self):
        """23 buildable categories on the reference pack have an unpriced machine item.

        A price this model failed to compute is a gap in the pricing, not evidence about the
        base, and `machines._candidate_verdict` already decided `buildable` from a real
        producer. Charging `unavailable` here would let a numerical failure overrule that --
        and since #95, so would charging what a structure proven unbuildable charges.
        """
        self.assertEqual(cost.build_entry_cost(float("inf")), cost.UNPRICED_MACHINE_COST)
        self.assertEqual(cost.build_entry_cost(None), cost.UNPRICED_MACHINE_COST)
        self.assertLess(cost.build_entry_cost(float("inf")),
                        cost.MACHINE_COST["unavailable"])
        self.assertLess(cost.build_entry_cost(float("inf")), cost.blocked_entry_cost(0.0))

    def test_the_cheapest_candidate_sets_the_price(self):
        """More than one block opens a lot of categories, and a player builds the cheap one.

        Pricing the first listed would charge for a machine nobody would choose.
        """
        table = {"mod:dear_machine": 500.0, "mod:cheap_machine": 1.0}
        entry = cost.machine_entry_costs(
            {"mod.both": ("mod:dear_machine", "mod:cheap_machine")}, table)
        self.assertEqual(entry["mod.both"], cost.build_entry_cost(1.0))
        # And that it is genuinely the cheaper of the two, not a figure both agree on.
        self.assertLess(entry["mod.both"], cost.build_entry_cost(500.0))

    def test_the_machine_price_the_ranker_charges_is_the_one_the_relaxation_used(self):
        """The divergence `CostTable` exists to make impossible.

        `estimate` and `recipe_cost` each used to look up MACHINE_COST themselves, so a
        change to how a machine is priced could apply to one and not the other. The symptom
        is a solver expanding a route the ranker did not price, which is exactly issue #29's
        shape one level up. Carried on the table, they cannot disagree.
        """
        g, table, entry = self.entries()
        dear = [r for r in g.recipes if r.rid == "via_dear"][0]
        priced = cost.recipe_cost(table, dear, g.ore_members,
                                  machine_states=TWO_MACHINE_STATES)
        self.assertAlmostEqual(priced - entry["mod.dear"],
                               cost.input_cost(table, "mod:stick", 1, g.ore_members))

    def test_a_plain_dict_still_gets_the_flat_constants(self):
        # Every hand-built table in this suite passes a plain dict, and `recipe_cost` has to
        # keep working for them rather than requiring a CostTable.
        g = two_machine_graph()
        dear = [r for r in g.recipes if r.rid == "via_dear"][0]
        priced = cost.recipe_cost({"mod:stick": 1.0}, dear, g.ore_members,
                                  machine_states=TWO_MACHINE_STATES)
        self.assertAlmostEqual(priced, cost.MACHINE_COST["buildable"] + 1.0)

    def test_a_cost_table_is_a_plain_dict_everywhere_else(self):
        table = cost.CostTable({"a": 1.0}, machine_entry={"c": 2.0})
        self.assertEqual(table["a"], 1.0)
        self.assertEqual(dict(table), {"a": 1.0})
        self.assertEqual(cost.CostTable({"a": 1.0}).machine_entry, {})

    def test_the_entry_cost_is_still_not_amortised_over_a_batch(self):
        """#29's fix, re-asserted against the new figure rather than assumed to survive.

        `BatchAmortisationTest` proves this for the flat constants. A derived entry cost is a
        new number flowing through the same arithmetic, and if it were divided by the output
        quantity then a big enough batch would make an expensive machine free again, which is
        the failure that priced 126 reference-pack items under 0.1.
        """
        g = two_machine_graph()
        g.add(Recipe("bulk", "t", [("mod:widget", 1024)], [Ingredient(["mod:stick"], 1)],
                     category="mod.dear"))
        table = cost.estimate(g, machine_states=TWO_MACHINE_STATES,
                              machine_items=TWO_MACHINE_ITEMS)
        self.assertGreaterEqual(table["mod:widget"], table.machine_entry["mod.cheap"])

    def test_the_cache_round_trips_the_entry_costs(self):
        """A cache HIT must not silently revert the ranker to the flat constants.

        Serving only the item prices would hand back a plain table, `recipe_cost` would fall
        back to MACHINE_COST, and the ranking would disagree with the relaxation the cache is
        serving. That divergence appears only on a hit, which is the hard way to find it.
        """
        g = two_machine_graph()
        path = os.path.join(tempfile.mkdtemp(), "cache.json")
        first = cost.estimate_cached(g, "no-such-graph.json",
                                     machine_states=TWO_MACHINE_STATES,
                                     machine_items=TWO_MACHINE_ITEMS, cache_path=path)
        self.assertTrue(first.machine_entry)
        again = cost.estimate_cached(g, "no-such-graph.json",
                                     machine_states=TWO_MACHINE_STATES,
                                     machine_items=TWO_MACHINE_ITEMS, cache_path=path)
        self.assertEqual(again.machine_entry, first.machine_entry)
        self.assertEqual(dict(again), dict(first))

    def test_changing_which_item_is_the_machine_invalidates_the_cache(self):
        """A catalyst change can move the machine ITEM without moving the STATE.

        Hashing states alone would then serve prices derived from the old machine, and the
        state fingerprint could not tell.
        """
        one = cost.fingerprint("g.json", None, TWO_MACHINE_STATES, None, TWO_MACHINE_ITEMS)
        two = cost.fingerprint("g.json", None, TWO_MACHINE_STATES, None,
                               {"mod.cheap": ("mod:something_else",),
                                "mod.dear": ("mod:dear_machine",)})
        self.assertNotEqual(one, two)

    def test_no_build_targets_is_not_an_error(self):
        # Every graph where nothing is buildable, plus the `--ignore-machines` path, arrives
        # here with nothing to price.
        self.assertEqual(cost.machine_entry_costs(None, {}), {})
        self.assertEqual(cost.machine_entry_costs({}, {"a": 1.0}), {})

    def test_the_cli_hands_the_cost_model_its_build_targets(self):
        """`_machine_states` grew a third return value, and the reason is worth a test.

        It went through `machines.resolve`, which returns (state, why) and drops the machine
        item, so the cost model had nothing to price. A caller left on the two-value unpack
        would raise, which is the safe failure; the unsafe one is this returning empty targets
        and the ranking quietly reverting to the flat constant.
        """
        import tempfile
        from recipegraph import cli
        g = two_machine_graph()
        d = tempfile.mkdtemp()
        have = os.path.join(d, "have.json")
        with open(have, "w") as fh:
            json.dump({"items": {}, "placed": {}}, fh)
        states, _overrides, targets = cli._machine_states(
            g, have, os.path.join(d, "machines.json"))
        self.assertEqual(states["mod.cheap"][0], "buildable")
        self.assertEqual(targets["mod.cheap"], ("mod:cheap_machine",))
        self.assertEqual(targets["mod.dear"], ("mod:dear_machine",))

    def test_the_two_passes_do_not_depend_on_recipe_order(self):
        """Why this is two clean relaxations rather than entry costs recomputed in the loop.

        The relaxation only ever LOWERS a cost, so an entry price that RISES between passes
        never propagates: pass one's optimistic prices stick and the answer depends on the
        order recipes happen to sit in. Shuffling them must not move the result.
        """
        g = two_machine_graph()
        a = cost.estimate(g, machine_states=TWO_MACHINE_STATES,
                          machine_items=TWO_MACHINE_ITEMS)
        h = two_machine_graph()
        h.recipes.reverse()
        h._invalidate() if hasattr(h, "_invalidate") else None
        b = cost.estimate(h, machine_states=TWO_MACHINE_STATES,
                          machine_items=TWO_MACHINE_ITEMS)
        self.assertEqual(a.machine_entry, b.machine_entry)
        self.assertEqual(a["mod:widget"], b["mod:widget"])


if __name__ == "__main__":
    unittest.main()


class ATieAmongIDENTICALOFFERSIsAdmittedTest(unittest.TestCase):
    """#181: the plan says when its pick was arbitrary, and says nothing when it was not.

    Reported on Life Essence, whose 62 Digital Mob Agonizer recipes are structurally
    identical and differ only in WHICH four data models they accept. The plan named Blaze
    because Blaze sorts first, and `alternatives` said 65 -- a number that reads as choice
    and was, at the level the model can see, no choice at all.

    THE TRIGGER IS NOT A TIE. Measured over 23,476 multi-producer keys on the reference
    graph, a bare score tie fires on 33.5% of them, which is the mark-fires-on-everything
    failure #136 recorded. Requiring the tied recipes to be the SAME OFFER cuts it to 6.2%
    and requiring three of them to 1.3%. So these tests pin the DIFFERENCE between a tie and
    an interchangeable tie, because that difference is the whole feature.
    """

    def solver_for(self, graph, **kw):
        costs = cost.estimate(graph)
        return Solver(graph, costs=costs, **kw)

    def three_identical_offers(self):
        """Three recipes for one key that differ ONLY in which item they consume.

        One slot, one input, same quantity, same category, same output. That is exactly the
        Agonizer shape at the smallest size that can reach `TIE_MIN`.
        """
        g = Graph()
        g.names = {"mod:goo": "Goo", "mod:a": "A", "mod:b": "B", "mod:c": "C"}
        for rid, src in (("ra", "mod:a"), ("rb", "mod:b"), ("rc", "mod:c")):
            g.add(Recipe(rid, "Machine", [("mod:goo", 1)], [Ingredient([src], 1)],
                         category="mod.machine"))
        return g

    def test_three_interchangeable_recipes_are_admitted_as_arbitrary(self):
        g = self.three_identical_offers()
        node = self.solver_for(g).solve("mod:goo", 1)["tree"]
        self.assertEqual(3, node.get("interchangeable"))
        # And the OTHER count stays what it always was, because they answer different
        # questions and #181 exists because the plan was showing the flattering one.
        self.assertEqual(3, node.get("alternatives"))

    def test_two_is_below_the_threshold_and_says_nothing(self):
        # TIE_MIN is 3 rather than 2 deliberately: ">= 2" adds 1,155 keys whose honest
        # wording is "either of these two", and takes the mark from 1.3% to 6.2% of nodes.
        g = Graph()
        g.names = {"mod:goo": "Goo", "mod:a": "A", "mod:b": "B"}
        for rid, src in (("ra", "mod:a"), ("rb", "mod:b")):
            g.add(Recipe(rid, "Machine", [("mod:goo", 1)], [Ingredient([src], 1)],
                         category="mod.machine"))
        node = self.solver_for(g).solve("mod:goo", 1)["tree"]
        self.assertIsNone(node.get("interchangeable"))

    def test_a_tie_between_DIFFERENT_offers_is_not_arbitrary(self):
        """The negative case the whole design rests on, and the one a tie-only trigger fails.

        Three recipes that SCORE the same and are not the same offer: one takes a single
        input, one takes two, one is a different machine. A reader picking between them is
        making a real choice, so the plan must not call it arbitrary.
        """
        g = Graph()
        g.names = {"mod:goo": "Goo", "mod:a": "A", "mod:b": "B", "mod:c": "C",
                   "mod:d": "D"}
        g.add(Recipe("one_slot", "M", [("mod:goo", 1)], [Ingredient(["mod:a"], 1)],
                     category="mod.machine"))
        g.add(Recipe("two_slots", "M", [("mod:goo", 1)],
                     [Ingredient(["mod:b"], 1), Ingredient(["mod:c"], 1)],
                     category="mod.machine"))
        g.add(Recipe("other_machine", "N", [("mod:goo", 1)], [Ingredient(["mod:d"], 1)],
                     category="mod.other"))
        node = self.solver_for(g).solve("mod:goo", 1)["tree"]
        # Whatever it picked, it is not interchangeable with three of anything: the three
        # shapes are distinct, so the largest same-offer group has size 1.
        self.assertIsNone(node.get("interchangeable"))

    def test_a_pin_suppresses_the_mark_because_the_player_already_chose(self):
        g = self.three_identical_offers()
        node = self.solver_for(g, pinned={"mod:goo": ["rb"]}).solve("mod:goo", 1)["tree"]
        self.assertTrue(node.get("pinned"))
        self.assertIsNone(node.get("interchangeable"))
        # The pin really did move the choice, so this is not passing because nothing changed.
        self.assertEqual("rb", node.get("recipe"))

    def test_the_count_is_the_offer_group_and_not_the_whole_tied_set(self):
        """Four recipes tie; THREE are one offer and the winner is the odd one out.

        `max(shape counts)` -- what the issue originally specified, and what the population
        sweep correctly uses -- would render 3 here, on a node whose chosen recipe is
        interchangeable with nothing. Measured over the reference graph this shape occurs on
        4 keys, and on every one of them the larger reading produces a mark that is false.
        """
        g = Graph()
        g.names = {"mod:goo": "Goo", "mod:a": "A", "mod:b": "B", "mod:c": "C",
                   "mod:d": "D"}
        # DIFFERING BY CATEGORY, NOT BY SLOT COUNT, and that is forced rather than chosen.
        # `offer_shape` reads (slots, per_run, category, transfer); `score_recipe` reads
        # none of those directly except through `simple`, which counts SLOTS. So a shape
        # difference that is invisible to the score has to come from the category, and a
        # first draft using a two-slot winner could not tie at all -- `simple` separated
        # them and the test skipped itself, which reads exactly like a pass.
        #
        # Listed FIRST so the stable sort leaves it at rank 0 on a full tie.
        g.add(Recipe("odd", "M", [("mod:goo", 1)], [Ingredient(["mod:a"], 1)],
                     category="mod.solo"))
        for rid, src in (("t1", "mod:b"), ("t2", "mod:c"), ("t3", "mod:d")):
            g.add(Recipe(rid, "M", [("mod:goo", 1)], [Ingredient([src], 1)],
                         category="mod.machine"))
        solver = self.solver_for(g)
        node = solver.solve("mod:goo", 1)["tree"]
        # ASSERTED, NOT SKIPPED. If the winner is not the odd one the premise is absent and
        # the test proves nothing, so it must fail rather than quietly opt out.
        self.assertEqual("odd", node.get("recipe"),
                         "premise absent: the odd-shaped recipe must be the one chosen for "
                         "this test to distinguish the two readings")
        # And the premise's other half: three of the OTHERS really are one shape, so the
        # `max` reading would have had 3 to report.
        shapes = [solver.offer_shape(r, "mod:goo") for r in g.real_producers("mod:goo")]
        self.assertEqual(3, max(shapes.count(s) for s in shapes),
                         "premise absent: there must be a larger group for the rejected "
                         "reading to have found")
        self.assertIsNone(node.get("interchangeable"),
                          "the chosen recipe is its own shape, so nothing is interchangeable "
                          "with it -- reporting the largest OTHER group would be a true "
                          "number beside a false statement")


class TheMarkIsStatedOncePerKeyTest(unittest.TestCase):
    """A repeated key carries the same true finding at 24 nodes. #206

    SPLIT OUT OF #181 SO THE TRIGGER IS NOT WEAKENED TO FIX SOMETHING IT CANNOT REACH.
    Measured across the 21 plan fixtures once #181 landed: 462 craft nodes, 76 marked,
    16.5%. That decomposes into 8 distinct keys, five of them bee drones accounting for 73 of
    the 76, and every mark is true where it sits -- `bee_drone_ge#e1904b1a26ba` really does
    have a 67-way interchangeable field at all of its nodes.

    The tie-size distribution is bimodal with a gap: 19 keys at 3, 10 at 4, nothing between 5
    and 13, then 14, 62 and 67. Raising `TIE_MIN` to 5 takes 16.5% to 10.2% while deleting
    four genuinely tied keys and leaving the drone marked at every one of its nodes. The
    trigger measures tie SIZE; the density comes from tie RECURRENCE. Different axis.

    So the renderer states it once, the way a footnote does, and `_arbitrary_html` is where
    the other occurrences remain findable. These tests pin both halves: a suppressed mark
    that could not be found again would be a deletion wearing a footnote's clothes.
    """

    def _tree(self, occurrences=3, count=67):
        """One key reached `occurrences` times down a chain, each node carrying the mark.

        Built as a literal rather than solved, because what is under test is the RENDERER:
        the solver's job is to write `interchangeable` on every node where it is true, which
        `ATieAmongIDENTICALOFFERSIsAdmittedTest` above already pins, and it is true at every
        one of these.
        """
        leaf = None
        for _ in range(occurrences):
            leaf = {"key": "mod:drone", "label": "Common Drone", "kind": "item", "need": 1,
                    "status": solve.STATUS_CRAFT, "alternatives": count + 3,
                    "interchangeable": count,
                    "children": [leaf] if leaf else []}
        return {"key": "mod:out", "label": "Out", "kind": "item", "need": 1,
                "status": solve.STATUS_CRAFT, "children": [leaf]}

    def _rendered(self, tree, name="Out"):
        """`render_html` over a minimal result. ONE copy of the envelope, so a renderer that
        grows a required key does not have to be chased through five near-identical dicts."""
        return render.render_html({
            "target": tree["key"], "target_name": name, "qty": 1,
            "nodes": 0, "tree": tree, "shopping_list": [], "used_from_stock": [],
            "from_sources": [], "machines_to_build": [], "truncated": False})

    def _page(self, **kw):
        return self._rendered(self._tree(**kw))

    def _rows(self, page):
        """The tree's meta bits, one string per row that has any.

        Cut at the next card's heading, because the summary panel uses the same `.meta` span
        for its own wording -- so a slice that ran to the end of the page would count the
        footnote as one of the rows it is standing in for, and every count here would be one
        too high while the suppression worked.
        """
        tree = page.split('class="tree', 1)[1].split("You still need", 1)[0]
        return re.findall(r'<span class="meta">(.*?)</span>', tree)

    def test_the_first_occurrence_says_it_and_the_rest_do_not(self):
        rows = [r for r in self._rows(self._page()) if "interchangeable" in r]
        self.assertEqual(len(rows), 1,
                         "three nodes, one finding: the mark is a footnote, not a per-node "
                         "badge. Got %r" % (rows,))

    def test_the_suppressed_rows_keep_the_recipe_count_they_had_before(self):
        """Not silence. Those rows showed a linked recipe count before #181 and the link is
        the way into the picker, so suppressing the mark must not cost them it."""
        rows = self._rows(self._page())
        counts = [r for r in rows if "70 recipes" in r]
        self.assertEqual(len(counts), 2, rows)

    def test_the_mark_replaces_the_recipe_count_rather_than_joining_it(self):
        """`present.INTERCHANGEABLE_NOTE`'s own argument, and `NodeRowText.meta`'s in Java:
        "70 recipes, 67 interchangeable" leads with the flattering number."""
        marked = [r for r in self._rows(self._page()) if "interchangeable" in r][0]
        self.assertNotIn("recipes", marked)

    def test_the_words_are_present_py_own(self):
        page = self._page()
        self.assertIn(present.INTERCHANGEABLE_NOTE % 67, page)

    def test_the_panel_lists_the_key_once_and_says_how_many_steps_it_covers(self):
        """Where a reader who met occurrence three finds the finding.

        The step count is the load-bearing number: it is what says the tree is being quiet on
        purpose rather than having lost something.
        """
        page = self._page(occurrences=3)
        panel = page.split("Picked from equal recipes", 1)[1]
        self.assertEqual(panel.count("Common Drone"), 1)
        self.assertIn("3 steps", panel)
        self.assertIn(present.INTERCHANGEABLE_NOTE % 67, panel)
        # And it says WHY the tree is quiet elsewhere, rather than leaving the reader to
        # notice that 2 of the 3 rows stopped mentioning it.
        self.assertIn("FIRST appears", panel)
        self.assertIn("3 separate steps", panel)

    def test_a_key_reached_once_is_not_described_as_recurring(self):
        panel = self._page(occurrences=1).split("Picked from equal recipes", 1)[1]
        self.assertIn("1 step", panel)
        self.assertNotIn("1 steps", panel)
        self.assertNotIn("FIRST appears", panel,
                         "nothing was suppressed here, so there is nothing to explain")

    def test_a_plan_with_no_arbitrary_pick_has_no_panel(self):
        page = self._rendered({"key": "mod:out", "label": "Out", "kind": "item", "need": 1,
                               "status": solve.STATUS_CRAFT})
        self.assertNotIn("Picked from equal recipes", page)

    def test_suppression_does_not_leak_between_two_plans(self):
        """The set is per render call. A module-level one would mark the first plan of a
        server's life and no other, which is the worst possible bug shape: correct once."""
        for _ in range(2):
            rows = [r for r in self._rows(self._page()) if "interchangeable" in r]
            self.assertEqual(len(rows), 1)

    def test_the_panel_and_the_tree_are_counted_from_the_same_tree(self):
        """Five nodes of one key at two tie sizes: the panel keeps the size the tree showed.

        The two walk the tree independently, so this is the contract between them. A panel
        reporting 14 beside a tree row saying 67 would be a footnote pointing at a different
        finding, and `_is_arbitrary` is the shared predicate that keeps the SET of marked
        nodes the same in both.
        """
        deep = self._tree(occurrences=2, count=14)
        other = self._tree(occurrences=3, count=67)
        other["children"][0]["children"].append(deep["children"][0])
        page = self._rendered(other)
        panel = page.split("Picked from equal recipes", 1)[1]
        self.assertIn("5 steps", panel)
        marks = [r for r in self._rows(page) if "interchangeable" in r]
        self.assertEqual(len(marks), 1)
        # The SAME number in both places, read out of each rather than assumed equal.
        number = re.search(r"any of (\d+) interchangeable", marks[0]).group(1)
        self.assertIn("any of %s interchangeable" % number, panel)
        self.assertEqual(number, "67", "the tree reaches the 67-way node first")

    def test_a_node_the_panel_counts_is_a_node_the_tree_marks(self):
        """The predicate is shared, so a payload missing `alternatives` cannot split them.

        The solver always writes both fields, and a renderer whose mark was reachable only
        through the recipe count would drop the finding from the tree while the panel still
        listed it -- a footnote with no anchor.
        """
        page = self._rendered(
            {"key": "mod:drone", "label": "Common Drone", "kind": "item", "need": 1,
             "status": solve.STATUS_CRAFT, "interchangeable": 67}, name="Common Drone")
        self.assertEqual(len([r for r in self._rows(page) if "interchangeable" in r]), 1)
        self.assertIn("Picked from equal recipes", page)


class CrossLanguageWordingTest(unittest.TestCase):
    """`NodeRowText.meta` and `present.INTERCHANGEABLE_NOTE` have to say the same thing.

    The mirror of `NodeStatusTest`, which reads `present.py` out of Java for the status words.
    #181 shipped this mark in Java only, so the browser and the client were already saying
    two different things about one node -- one of them nothing at all. A comment asking for
    parity is a rule with nothing enforcing it, which is this repository's recurring defect.
    """

    JAVA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "mod", "src", "main", "java", "io", "github", "jacoblasky",
                        "recipedump", "client", "planner", "NodeRowText.java")

    PLANNER_WIDGETS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                   "mod", "src", "main", "java", "io", "github", "jacoblasky",
                                   "recipedump", "client", "planner", "PlannerWidgets.java")

    def test_a_token_is_marked_with_the_same_glyph_in_both_renderers(self):
        """`graphview.TOKEN_MARK` and `PlannerWidgets.TOKEN_MARK` have to be one character.

        #174 was reported on a reader concluding a pack placeholder was an item, and both
        renderers now answer it by marking the box rather than by a colour. A glyph taught in
        the browser and a different one in game is worse than neither: it teaches a visual rule
        and then breaks it. The two constants are in two languages and nothing but this joins
        them.

        THE MARK LIVES IN THE ICON COLUMN IN GAME, which is what makes it survive
        `FlowZoom.LABEL_LEGIBLE` dropping the label and the badge word. That is why it is a
        glyph at all rather than a word.
        """
        for path in (self.PLANNER_WIDGETS,):
            if not os.path.exists(path):
                self.fail("the Java copy of this glyph lives here: %s" % path)
        with open(self.PLANNER_WIDGETS, encoding="utf-8") as fh:
            source = fh.read()
        # FAIL RATHER THAN SKIP when the constant is absent, which is the half that matters: a
        # skip in a parity test reads exactly like a pass, and this file's own docstring is
        # about a rule with nothing enforcing it.
        found = re.search(r'TOKEN_MARK\s*=\s*"((?:[^"\\]|\\.)*)"', source)
        self.assertTrue(found, "PlannerWidgets.java declares no TOKEN_MARK; python says %r"
                        % (graphview.TOKEN_MARK,))
        self.assertEqual(graphview.TOKEN_MARK, found.group(1),
                         "the browser marks a token %r and the client marks it %r"
                         % (graphview.TOKEN_MARK, found.group(1)))

    def test_the_java_row_builds_the_same_sentence(self):
        if not os.path.exists(self.JAVA):
            self.fail("NodeRowText.java is where the other copy of these words lives: %s"
                      % self.JAVA)
        with open(self.JAVA, encoding="utf-8") as fh:
            source = fh.read()
        # The Java builds it by concatenation, so compare the two literal halves either side
        # of the number rather than the formatted string.
        head, tail = present.INTERCHANGEABLE_NOTE.split("%d")
        self.assertIn('"%s"' % head, source,
                      "python says %r" % (present.INTERCHANGEABLE_NOTE,))
        self.assertIn('"%s"' % tail, source,
                      "python says %r" % (present.INTERCHANGEABLE_NOTE,))
