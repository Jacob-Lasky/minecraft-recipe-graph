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

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

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
        self.assertEqual(solver.score_recipe(by_id["upgrade"])[2], -1)
        self.assertEqual(solver.score_recipe(by_id["smelt"])[2], 0)

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
        self.assertEqual(solver.score_recipe(by_id["recycle"])[2], -1)
        self.assertEqual(solver.pick_recipe("mod:gem").rid, "forge")

    def test_stock_still_makes_an_upgrade_recipe_usable(self):
        # Gated on `available(alt) < qty`, the same as the ancestor case: an upgrade you can
        # feed from stock is a real route, not a cycle. Without the gate this would forbid
        # every repair and upgrade recipe in the pack.
        g, solver = self._solver(have={"mod:gem": 4, "mod:junk": 4})
        by_id = {r.rid: r for r in g.recipes}
        self.assertEqual(solver.score_recipe(by_id["upgrade"])[2], 0)

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
        self.assertGreater(score(by_id["uncraft"])[5], score(by_id["smelt"])[5])

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
        self.assertEqual([(k, q) for k, q, _o in slots], [("mod:panel", 9)])
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


if __name__ == "__main__":
    unittest.main()
