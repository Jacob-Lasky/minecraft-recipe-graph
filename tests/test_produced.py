"""What counts as PRODUCED, answered once. #193

THE REPORTED SHAPE. Three places in this codebase answered "is this key produced" and they
did not agree:

    Graph.real_producers   the authority: a container empty is not production
    cost._relax            a hand-rolled copy, whose own comment said it mirrored the above
    cost._seed             `graph.by_output`, raw, excluding nothing

So a fluid whose only route is emptying a can appeared in `by_output`, `_seed` did not treat
it as a leaf and gave it no price, and `_relax` then applied the exclusion and refused to
price it from that recipe. Nothing seeded it and nothing relaxed it, so it sat at infinity --
while `Solver.expand` reported it `raw` and put it on the shopping list, which is the honest
answer. The cost model said impossible and the plan said go and buy it, and every parent's
price inherited the cost model's version. Measured on the reference graph: 120 keys, all
fluids, `forestry.squeezer` in nearly all of them.

THREE PROPERTIES, and they are different in kind:

  * BEHAVIOUR. `_seed` and `_relax` are exact complements: a key the relaxation can price is
    not a leaf, and a key it cannot price is. `AWildcardOnlyKeyIsStillALeafTest` is the edge
    where the two predicates on `Graph` have to disagree for that to hold.
  * THE PRICE. Finite, because an infinity is the whole of what #193 reported, and
    `UNSOURCED_COST` rather than the cheapest number in the model, because these keys are ones
    the graph has PROVEN it cannot explain -- #176's argument, in the population #176's own set
    cannot reach. See `Graph.produced_in_name_only`.
    `TheCostModelStopsWhereThePlanStopsTest`.
  * STRUCTURE. There is ONE definition of the exclusion and everything reads it.
    `AllThreeReadersAgreeTest` here for the readers, and
    `test_unsourced.ThereIsOnlyOneSpellingOfThePredicateTest` for the "defined once" half.

THE CASES BELOW ARE ENUMERATED FROM THE DATA, not chosen. Every recipe of every container
graph the suite has, every output of every one of those recipes, and the relaxation's verdict
OBSERVED by running it rather than restated as an expression. Hand-chosen cases are how the
last agreement test in this repo certified a shared blind spot: `TheTwoPredicatesAgreeTest`
compared five keys, all five exercised the one branch nobody had changed, and two of four
shapes were silently wrong for two releases.
"""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# tests/ itself, so `import fixtures` works under `-m unittest tests.<mod>` and not only
# under `discover -s tests`, which inserts this directory for us.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fixtures                                              # noqa: E402
from recipegraph import cost as cost_mod                     # noqa: E402
from recipegraph import index as index_mod                   # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe      # noqa: E402
from recipegraph.solve import STATUS_RAW, Solver             # noqa: E402

FLUID = "fluid:uranium_fluoride"
CAN = "forestry:can"
FILLED = "forestry:can:1"
ENRICHED = "mod:enriched_billet"


def canned_fluid_graph():
    """The Borax edge, in miniature, WITH A CONSUMER for the fluid.

    The consumer is the load-bearing part and it is why this graph is not
    `test_sources.ContainerFluidTest._graph`. `cost._seed`'s leaf rule walks recipe INPUTS,
    so a fluid nothing consumes is never offered to the predicate at all and comes out
    absent from the table whatever the predicate says. A cost assertion on such a key
    reports the shape of the loop rather than the rule under test.
    """
    g = Graph()
    g.names = {CAN: "Can", FILLED: "Filled Can", FLUID: "[fluid] uranium_fluoride",
               ENRICHED: "Enriched Billet"}
    # The real, observed edge: the dump drops the NBT that says WHICH fluid a filled can
    # holds, so every filled can collapses to `forestry:can:1` and squeezing a can of water
    # appears to yield uranium fluoride.
    squeeze = Recipe("squeeze", "t", [(FLUID, 1000)],
                     [Ingredient([FILLED], 1)], category="forestry.squeezer")
    squeeze.transfer = True
    g.add(squeeze)
    # Filling one IS real work, so the same recipe class still produces the ITEM.
    fill = Recipe("fill", "t", [(FILLED, 1)],
                  [Ingredient([CAN], 1), Ingredient(["fluid:water"], 1000, "fluid")],
                  category="thermalexpansion.transposer_fill")
    fill.transfer = True
    g.add(fill)
    # And something that wants the fluid, so the seed's leaf rule is offered the key.
    g.add(Recipe("enrich", "t", [(ENRICHED, 1)],
                 [Ingredient([FLUID], 1000, "fluid")], category="minecraft.crafting"))
    return g


class TheCostModelStopsWhereThePlanStopsTest(unittest.TestCase):
    """A key the relaxation cannot price gets a FINITE price, and not the cheapest one.

    Every assertion here fails on the pre-#193 tree with an infinity, which is the cost model
    calling a route impossible while the plan shopping-lists it. What replaced the infinity is
    `UNSOURCED_COST` rather than the leaf price: the leaf price is the cheapest value in the
    model, so it would rank a fluid the tool cannot source above every route it can account
    for. The argument is #176's and it is recorded on `Graph.produced_in_name_only`.
    """

    def setUp(self):
        self.g = canned_fluid_graph()
        self.costs = cost_mod.estimate(self.g)

    def test_a_container_only_fluid_prices_FINITELY(self):
        """The whole of what #193 reported was an INFINITY, and this is the fix.

        Not a raw leaf. `BASE_RAW_COST` is the arithmetic that agrees with `expand`'s `raw`
        verdict most literally and it is also the cheapest value in the model, so it makes a
        route through a fluid the tool cannot source more attractive than any route the graph
        can account for -- verbatim #176's defect, in the one population #176's set cannot
        reach. See `Graph.produced_in_name_only`, which also records how little the magnitude
        moved when both arms were solved end to end.
        """
        self.assertTrue(math.isfinite(self.costs.get(FLUID, math.inf)))
        self.assertEqual(cost_mod.UNSOURCED_COST, self.costs.get(FLUID))

    def test_it_is_dearer_than_a_route_the_graph_CAN_account_for(self):
        # The ordering that matters, stated against a competitor rather than as a constant:
        # a fluid nothing really makes must not undercut an ordinary thing to go and get.
        self.assertGreater(self.costs.get(FLUID), self.costs.get(CAN))

    def test_its_consumer_is_priced_rather_than_unreachable(self):
        # The half that matters for ranking. One infinite ingredient makes a recipe
        # unreachable, so before the fix every route through this fluid was invisible to the
        # ranker while the solver was perfectly willing to walk it.
        self.assertTrue(math.isfinite(self.costs.get(ENRICHED, math.inf)))

    def test_the_plan_and_the_price_give_the_same_verdict(self):
        # Stated as the pair rather than as two facts, because the defect was never a wrong
        # number on its own -- it was two answers to one question.
        tree = Solver(self.g, costs=self.costs).solve(FLUID, 1000)["tree"]
        self.assertEqual(STATUS_RAW, tree["status"])
        self.assertTrue(math.isfinite(self.costs.get(FLUID, math.inf)))

    def test_the_transfer_route_is_still_not_what_prices_it(self):
        # THE CONSTRAINT `real_production` GUARDS. Whatever number the fluid ends up with, it
        # must not be one the container empty computed, or the uranium chain is back, priced
        # through a can of water. The squeezer route would land on
        # `UNGATED_MACHINE_COST + TRANSFER_PENALTY + the can`, which is neither of the two
        # figures this seed can produce.
        squeezer = (cost_mod.UNGATED_MACHINE_COST + cost_mod.TRANSFER_PENALTY
                    + self.costs.get(FILLED, 0.0))
        self.assertNotEqual(squeezer, self.costs.get(FLUID))
        # And filling one is still real work that still prices the ITEM.
        self.assertTrue(math.isfinite(self.costs.get(FILLED, math.inf)))

    def test_it_is_priced_even_when_NOTHING_CONSUMES_IT(self):
        """The raise INSERTS as well as raising, which is a second effect rather than a detail.

        The leaf rule walks recipe INPUTS, so a key nothing consumes is never offered to it and
        would come out absent from the table. The raise sweeps the whole population and reads a
        missing entry as `BASE_RAW_COST`, so it creates one. That is exactly the second effect
        `unsourced_keys` has for its 39 never-consumed keys, and it is deliberate for the same
        reason: these keys reach no plan and they DO reach `/api/sweep` and `/api/cost`, where
        pricing a key one way in the table and another way in a report is the drift #178 removed.

        Asserted because it is easy to lose. Restrict either loop to consumed keys and every
        test above still passes, while a cost report goes back to showing these as unpriced.
        """
        g = canned_fluid_graph()
        g.recipes = [r for r in g.recipes if r.rid != "enrich"]
        g._invalidate()
        self.assertNotIn(FLUID, g.by_input, "fixture broken: nothing may consume the fluid")
        self.assertIn(FLUID, g.produced_in_name_only)
        self.assertEqual(cost_mod.UNSOURCED_COST, cost_mod.estimate(g).get(FLUID))

    def test_a_fluid_with_an_honest_route_keeps_that_route_s_price(self):
        # NEITHER SEED MAY FIRE ON A KEY SOMETHING REALLY MAKES. One honest producer takes the
        # fluid out of `produced_in_name_only` and out of the leaf rule alike, so the price has
        # to be the one the relaxation earned through the salt: cheaper than the wall, and not
        # either of the two figures the seed can write.
        g = canned_fluid_graph()
        g.add(Recipe("synthesise", "t", [(FLUID, 1000)],
                     [Ingredient(["mod:salt"], 4)], category="minecraft.crafting"))
        self.assertNotIn(FLUID, g.produced_in_name_only)
        priced = cost_mod.estimate(g)
        self.assertLess(priced.get(FLUID, math.inf), cost_mod.UNSOURCED_COST)
        self.assertNotEqual(cost_mod.BASE_RAW_COST, priced.get(FLUID))
        # The salt route, arrived at rather than asserted as a literal: one ungated machine
        # plus four leaves, over a 1,000 mB output that `FLUID_SCALE` normalises to one bucket.
        self.assertEqual(cost_mod.UNGATED_MACHINE_COST + 4 * cost_mod.BASE_RAW_COST,
                         priced.get(FLUID))


def _container_graphs():
    """Every container shape the suite has, with the flags set by the DETECTOR where it fires.

    TWO OF THE THREE GO THROUGH `index.mark_container_transfers` rather than setting
    `transfer` by hand, so those cases are whatever the pack's own signals identify and a
    third signal would add cases here without anybody remembering to. #34 is the precedent:
    detection collapsed from 7,016 recipes to 117 and no test noticed, because every suite
    set the flag itself and none asked the detector to find anything. `discriminated_graph`
    is signal 2 (one item yielding eight distinct fluids) and the tank is signal 1 (the
    input's base key among the outputs), which is both live signals.

    THE THIRD IS HAND-FLAGGED AND HAS TO BE. `canned_fluid_graph` is the reported uranium-can
    edge reduced to three recipes, which puts it BELOW signal 2's eight-fluid threshold by
    construction and outside signal 1, so the detector cannot produce it. Running the
    detector over it would silently leave every recipe unflagged and the assertions below
    would then be about a graph with no container in it -- which is what the coverage check
    immediately after this exists to catch.
    """
    detected = fixtures.discriminated_graph()
    index_mod.mark_container_transfers(detected)

    tank = Graph()
    tank.add(Recipe("fill", "t", [("mod:tank#0123456789ab", 1), ("fluid:lava", 1000)],
                    [Ingredient(["mod:tank"], 1)], category="mod.filler"))
    tank.add(Recipe("smelt", "t", [("mod:slag", 1)],
                    [Ingredient(["fluid:lava"], 1000, "fluid")], category="minecraft.crafting"))
    index_mod.mark_container_transfers(tank)

    return {"signal 2, eight fluids per can": detected,
            "signal 1, input base among the outputs": tank,
            "the Borax edge, with a consumer": canned_fluid_graph()}


def _relax_writes(graph, recipe, key):
    """Whether `cost._relax` will ever price `key` from `recipe`. OBSERVED, not restated.

    Runs the real relaxation over a graph holding this one recipe, with every input free and
    `key` absent, so the only thing that can put `key` in the table is `_relax` deciding this
    recipe produces it. An expression restating the condition would agree with the
    implementation by construction, which is exactly what a mirror does.
    """
    one = Graph()
    one.add(recipe)
    cost = {}
    for ing in recipe.inputs:
        for alt in ing.alternatives:
            cost[alt] = 0.0
    cost.pop(key, None)
    return key in cost_mod._relax(one, cost, 1, None, None)


class AllThreeReadersAgreeTest(unittest.TestCase):
    """One exclusion, read by `real_producers`, by `_relax` and by `_seed`.

    EXHAUSTIVE OVER THE DATA. Every recipe of every container graph, every output of every
    recipe, every key any of them mentions. Nothing here names a key.
    """

    def test_every_case_graph_really_holds_a_container(self):
        # A case set that quietly stopped containing a container would make every assertion
        # below vacuous, and it would look exactly as green. Same failure mode as a lapsed
        # coverage claim in the plan fixtures, so it gets the same treatment: re-proved. It is
        # the DETECTOR that sets the flag on two of the three, so this also fails if a change
        # to `mark_container_transfers` stops recognising either live signal.
        for label, graph in _container_graphs().items():
            self.assertTrue(any(r.transfer for r in graph.recipes),
                            "%s carries no container transfer" % label)
            # And a container has to actually suppress something, or the predicate under test
            # has nothing to say about this graph.
            self.assertTrue(any(not graph.real_output(k) for k in graph.by_output), label)

    def test_real_producers_is_exactly_the_filter_over_producers(self):
        """The non-fluid short circuit must not drift away from the predicate.

        `real_producers` hands back the memoised list untouched for a non-fluid key, which
        is an optimisation valid only while `real_production` excludes nothing but fluids.
        Compared over every key rather than reasoned about, because the reasoning is what
        goes stale.
        """
        for label, graph in _container_graphs().items():
            keys = set(graph.by_output)
            for r in graph.recipes:
                for ing in r.inputs:
                    keys.update(ing.alternatives)
            self.assertTrue(keys, label)
            for key in sorted(keys):
                self.assertEqual(
                    [r.rid for r in graph.producers(key)
                     if graph.real_production(r, key)],
                    [r.rid for r in graph.real_producers(key)],
                    "%s: %s" % (label, key))

    def test_the_relaxation_prices_exactly_what_the_predicate_allows(self):
        """`_relax`'s reader against the authority, over every recipe and output there is.

        This is the assertion the hand-rolled mirror in `cost._relax` was one edit away from
        failing, and it could not have been made against the mirror: comparing a copy with
        the original tells you the copy is a copy.
        """
        pairs = 0
        for label, graph in _container_graphs().items():
            for r in graph.recipes:
                inputs = set(a for ing in r.inputs for a in ing.alternatives)
                for key, _qty in r.outputs:
                    # A recipe consuming its own output cannot be observed this way: with
                    # `key` removed from the table the recipe is unreachable and nothing is
                    # written, whatever the predicate says. None of the container shapes has
                    # one, and the count below is what would notice if that changed.
                    if key in inputs:
                        continue
                    pairs += 1
                    self.assertEqual(graph.real_production(r, key),
                                     _relax_writes(graph, r, key),
                                     "%s: %s from %s" % (label, key, r.rid))
        self.assertGreater(pairs, 10, "the enumeration found almost nothing to compare")

    def test_the_seed_and_the_relaxation_are_exact_complements(self):
        """The #193 defect itself, stated as the property rather than as a symptom.

        A key is a leaf to `_seed` if and only if no recipe in its OWN output list will ever
        be allowed to price it. Before the fix `_seed` read `by_output` and answered "not a
        leaf" for keys the relaxation would never write, so the two together left them at
        infinity.
        """
        for label, graph in _container_graphs().items():
            for key in sorted(graph.by_output):
                writable = any(_relax_writes(graph, r, key)
                               for r in graph.by_output[key]
                               if key not in set(a for ing in r.inputs
                                                 for a in ing.alternatives))
                self.assertEqual(writable, graph.real_output(key),
                                 "%s: %s" % (label, key))


class TheTwoUnexplainedPopulationsAreDisjointTest(unittest.TestCase):
    """`unsourced_keys` and `produced_in_name_only` cannot overlap, and `_seed` relies on it.

    One requires `by_output` EMPTY and the other requires it non-empty, so the intersection is
    zero by construction rather than by luck -- which is what makes a single loop over both
    equivalent to two loops, and what makes the order they are walked in irrelevant. Measured
    zero on the reference oracle too, and pinned here so a widening of either set has to
    confront the claim.
    """

    def test_the_definitions_cannot_both_hold(self):
        for label, graph in _container_graphs().items():
            self.assertEqual(frozenset(),
                             graph.unsourced_keys & graph.produced_in_name_only, label)

    def test_the_container_only_fluid_is_in_exactly_one_of_them(self):
        g = canned_fluid_graph()
        self.assertIn(FLUID, g.produced_in_name_only)
        self.assertNotIn(FLUID, g.unsourced_keys)

    def test_a_key_nothing_outputs_at_all_is_in_neither_by_this_set(self):
        # The boundary that keeps `produced_in_name_only` narrow: it is about keys the graph
        # DOES list a producer for. An ordinary leaf is not one, and pricing it at
        # `UNSOURCED_COST` would put most of a shopping list behind the wall.
        g = canned_fluid_graph()
        self.assertNotIn(CAN, g.produced_in_name_only)


class AWildcardOnlyKeyIsStillALeafTest(unittest.TestCase):
    """`real_output` must not widen to the wildcard sibling, or 478 keys strand at infinity.

    `producers` gathers `mod:item:*` for a concrete meta, so `real_producers` finds a route to
    a damaged Electroblob wand. `_relax` cannot USE that route: it lowers `cost[k]` only for
    keys a recipe literally outputs, and the recipe outputs the wildcard. So the seed's
    predicate has to answer "no producer" for such a key and give it the leaf price, which is
    the difference between `real_output` and `real_producers` and the reason they are two
    methods. Measured: 478 input alternatives on the reference graph are in this position, and
    calling them produced takes every one of them, plus every route through them, to infinity.
    """

    DAMAGED = "mod:wand:1500"

    def _graph(self):
        g = Graph()
        # The pack's shape: the recipe outputs the wildcard meta, and a slot elsewhere asks for
        # a specific damage value.
        g.add(Recipe("imbue", "t", [("mod:wand:*", 1)],
                     [Ingredient(["mod:crystal"], 4)], category="mod.imbuement_altar"))
        g.add(Recipe("use", "t", [("mod:spell", 1)],
                     [Ingredient([self.DAMAGED], 1)], category="minecraft.crafting"))
        return g

    def test_the_two_predicates_disagree_here_and_that_is_the_point(self):
        g = self._graph()
        self.assertTrue(g.real_producers(self.DAMAGED))
        self.assertFalse(g.real_output(self.DAMAGED))

    def test_the_damaged_key_keeps_its_leaf_price(self):
        table = cost_mod.estimate(self._graph())
        self.assertEqual(cost_mod.BASE_RAW_COST, table.get(self.DAMAGED))

    def test_and_so_does_everything_that_consumes_it(self):
        table = cost_mod.estimate(self._graph())
        self.assertTrue(math.isfinite(table.get("mod:spell", math.inf)))


class TheDeclaredTerminalsReachThePriceTest(unittest.TestCase):
    """`craftables` and `raw` stop the solver, so they have to stop the ranker. #193

    Before this, `grep -cE 'craftables|[^_]raw\\b' recipegraph/cost.py` returned 0 while both
    were read by `expand`, `score_recipe`, `ore_backed`, `_alternative_rank` and
    `resolve_ore`. So the cost model ranked routes as though nothing were autocraftable and
    nothing declared raw, and then the solver planned with a different set of terminals than
    the prices were computed for. The error ran ONE WAY: a route through an autocraftable
    item was priced at its full subtree while the real cost is one request, so it lost to
    worse ones.
    """

    LADDER = "mod:widget"
    DEEP = "mod:deep_part"

    def _graph(self):
        """A widget from a part that costs a fortune to make from scratch."""
        g = Graph()
        g.add(Recipe("assemble", "t", [(self.LADDER, 1)],
                     [Ingredient([self.DEEP], 1)], category="minecraft.crafting"))
        g.add(Recipe("deep", "t", [(self.DEEP, 1)],
                     [Ingredient(["mod:grit"], 64)], category="minecraft.crafting"))
        return g

    def test_an_autocraftable_item_prices_as_one_request(self):
        g = self._graph()
        self.assertEqual(cost_mod.CRAFTABLE_COST,
                         cost_mod.estimate(g, craftables={self.DEEP}).get(self.DEEP))

    def test_the_route_through_it_gets_cheaper_by_the_whole_subtree(self):
        # The consequence, which is the reason the price matters at all: the parent has to
        # see the request rather than the 64 grit.
        g = self._graph()
        blind = cost_mod.estimate(g)
        told = cost_mod.estimate(g, craftables={self.DEEP})
        self.assertLess(told.get(self.LADDER), blind.get(self.LADDER))

    def test_a_declared_stop_prices_as_a_thing_to_go_and_get(self):
        # `raw` is "stop here, I will get this myself", which is exactly a leaf.
        g = self._graph()
        self.assertEqual(cost_mod.BASE_RAW_COST,
                         cost_mod.estimate(g, raw={self.DEEP}).get(self.DEEP))

    def test_stock_still_wins_over_both(self):
        # `expand` returns at the stock branch before it reaches either, so the price has to
        # as well. This is the guard rather than the magnitudes doing the work.
        g = self._graph()
        for kw in ({"craftables": {self.DEEP}}, {"raw": {self.DEEP}}):
            table = cost_mod.estimate(g, have={self.DEEP: 4}, **kw)
            self.assertEqual(0.0, table.get(self.DEEP), kw)

    def test_a_learned_transmutation_still_wins_over_a_craftable(self):
        # `expand` checks EMC before `raw`/`craftables`, so a key that is both must keep the
        # EMC price. Reproduced by the seed's ordering and its under-a-raw-leaf guard, not by
        # the relative size of the two constants.
        g = self._graph()
        table = cost_mod.estimate(g, craftables={self.DEEP}, emc_available={self.DEEP})
        self.assertEqual(cost_mod.EMC_COST, table.get(self.DEEP))

    def test_a_craftable_outranks_a_declared_stop_for_the_same_key(self):
        # `expand` reports `have` with the autocraft note rather than shopping-listing it.
        g = self._graph()
        table = cost_mod.estimate(g, craftables={self.DEEP}, raw={self.DEEP})
        self.assertEqual(cost_mod.CRAFTABLE_COST, table.get(self.DEEP))

    def test_a_declared_stop_beats_a_dimension_surcharge(self):
        # `expand`'s `raw` branch is above the world-ore stop, so a player saying "I will get
        # this myself" outranks the graph's inference that they have never been to Sedna.
        #
        # A DEDICATED GRAPH, because the ore must have no crafted route at all: with one, the
        # relaxation lowers it below the surcharge and the pair below would compare two
        # numbers neither of which is the gate.
        ore = "mod:sedna_ore"
        g = Graph()
        g.ore_members = {"oreSedna": [ore]}
        g.add(Recipe("smelt", "t", [("mod:sedna_ingot", 1)],
                     [Ingredient([ore], 1)], category="minecraft.smelting"))
        gated = {ore: "Sedna"}
        self.assertEqual(cost_mod.BASE_RAW_COST + cost_mod.DIMENSION_COST,
                         cost_mod.estimate(g, dimension_gates=gated).get(ore))
        self.assertEqual(cost_mod.BASE_RAW_COST,
                         cost_mod.estimate(g, dimension_gates=gated, raw={ore}).get(ore))

    def test_no_relaxation_can_undercut_a_craftable(self):
        # What makes seeding a terminal below a raw leaf sound at all: `_relax` prices an
        # output at `base + ingredients / qty` with `base` undivided, and the cheapest `base`
        # there is equals `BASE_RAW_COST`. Asserted on a recipe with everything free and a
        # yield of 64, which is the most a relaxation can possibly discount.
        g = Graph()
        g.add(Recipe("bulk", "t", [(self.DEEP, 64)],
                     [Ingredient(["mod:grit"], 1)], category="minecraft.crafting"))
        table = cost_mod.estimate(g, have={"mod:grit": 99}, craftables={self.DEEP},
                                  machine_states={"minecraft.crafting": ("have", "")})
        self.assertEqual(cost_mod.CRAFTABLE_COST, table.get(self.DEEP))


class EveryCallSiteThreadsTheDeclaredTerminalsTest(unittest.TestCase):
    """The defect was a call site, not a function, so the guard has to be at that level.

    `grep -cE 'craftables|[^_]raw\\b' recipegraph/cost.py` returned 0 before #193 while both
    were first-class solver inputs. A behavioural test of `estimate` cannot see an entry point
    that never passes the argument, which is the same shape `test_progression` guards for the
    token map -- and for the same reason, since #105 shipped with exactly that hole.
    """

    def test_the_four_functions_accept_both(self):
        import inspect
        for fn in (cost_mod.estimate, cost_mod.estimate_cached, cost_mod._seed,
                   cost_mod.fingerprint):
            for name in ("craftables", "raw"):
                self.assertIn(name, inspect.signature(fn).parameters,
                              "%s(%s)" % (fn.__name__, name))

    def test_the_server_and_the_cli_hand_over_what_ae2_can_autocraft(self):
        import inspect

        from recipegraph import cli, server
        for mod, needle in ((server, "craftables=self.craftables"),
                            (cli, "craftables=craftables")):
            self.assertIn(needle, inspect.getsource(mod),
                          "%s prices without the craftables the solver stops at"
                          % mod.__name__)

    def test_the_cost_table_and_the_solver_read_the_SAME_name(self):
        # Two resolutions of one input is how a plan comes to disagree with its own prices --
        # the argument `cli.cmd_plan` already makes for the token map and the dimension gates.
        # Asserted as "the price call and the Solver call name the same thing", so a caller that
        # re-derived the set for one of them fails here. It also means `--ignore-craftable`,
        # which empties that one name, still turns the feature off for both at once.
        import inspect

        from recipegraph import cli, server
        for mod, needle in ((server, "craftables=self.craftables"),
                            (cli, "craftables=craftables")):
            self.assertEqual(2, inspect.getsource(mod).count(needle),
                             "%s should hand `%s` to the cost table and to the Solver, and to "
                             "nothing else" % (mod.__name__, needle))


class TheCacheCannotServeOneInventoryToAnotherTest(unittest.TestCase):
    """`fingerprint` covers the two new inputs, or a warm table is a wrong answer. #193

    The failure this prevents is silent and only happens on a cache HIT: two players, or one
    player before and after an AE2 pattern is added, share a `.cost-cache.json` and the
    second gets prices computed for the first. Every plan out of it looks reasonable.
    """

    ARGS = ("graph.json", {"mod:a": 1}, {}, {})

    def _stamp(self, **kw):
        return cost_mod.fingerprint(*self.ARGS, **kw)

    def test_the_craftable_set_moves_the_digest(self):
        self.assertNotEqual(self._stamp(), self._stamp(craftables={"mod:widget"}))

    def test_the_declared_stops_move_the_digest(self):
        self.assertNotEqual(self._stamp(), self._stamp(raw={"mod:widget"}))

    def test_the_two_sets_are_not_interchangeable(self):
        # They price differently, so one merged digest field would serve the craftable table
        # to a scenario that declared a stop.
        self.assertNotEqual(self._stamp(craftables={"mod:widget"}),
                            self._stamp(raw={"mod:widget"}))

    def test_the_price_of_a_request_moves_the_digest(self):
        # The other half, and the half `fingerprint`'s docstring has always claimed: editing
        # a tuning constant must invalidate the cache rather than silently reusing prices
        # computed under the old one. Nothing else in `estimate`'s inputs moves when
        # CRAFTABLE_COST does.
        before = self._stamp(craftables={"mod:widget"})
        original = cost_mod.CRAFTABLE_COST
        try:
            cost_mod.CRAFTABLE_COST = original / 2.0
            self.assertNotEqual(before, self._stamp(craftables={"mod:widget"}))
        finally:
            cost_mod.CRAFTABLE_COST = original

    def test_an_empty_set_is_the_same_as_none(self):
        # Otherwise every caller that resolves to no craftables gets a cache miss against
        # one that passed nothing, and the two are the same table.
        self.assertEqual(self._stamp(), self._stamp(craftables=set(), raw=set()))


if __name__ == "__main__":
    unittest.main()
