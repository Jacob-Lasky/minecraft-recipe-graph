"""Infinite generators, container-transfer fluids, and cost-scale symmetry.

All three came out of one plan for 64 Borax that drew its water from 71 Snowballs and 12
Wet Sponges, then -- once water was free -- from a nuclear fission chain.
"""

import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import fixtures  # noqa: E402
from recipegraph import cost, generators, index  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import STATUS_SOURCE, Solver  # noqa: E402


def chain_graph():
    """`goo` from water, where water is either free or costs an absurd snowball chain."""
    g = Graph()
    g.names = {
        "mod:goo": "Goo", "minecraft:snowball": "Snowball",
        "nuclearcraft:water_source": "Infinite Water Source",
    }
    g.add(Recipe("goo", "t", [("mod:goo", 1)],
                 [Ingredient(["fluid:water"], 4000)], category="mod.reactor",
                 machine="Reactor"))
    # The absurd route: 64 snowballs melt into a bucket.
    g.add(Recipe("melt", "t", [("fluid:water", 1000)],
                 [Ingredient(["minecraft:snowball"], 64)], category="mod.melter",
                 machine="Melter"))
    return g


class DetectionTest(unittest.TestCase):
    def test_placed_generator_is_detected(self):
        free = generators.resolve(placed={"nuclearcraft:water_source": 6})
        self.assertIn("fluid:water", free)
        self.assertIn("placed", free["fluid:water"])

    def test_generator_in_stock_counts_too(self):
        free = generators.resolve(stock={"nuclearcraft:cobblestone_generator_dense": 1})
        self.assertIn("minecraft:cobblestone", free)

    def test_vanilla_water_is_free_by_default_and_switchable(self):
        self.assertIn("fluid:water", generators.resolve())
        off = generators.resolve(overrides={"vanilla_water": False, "generators": {},
                                            "disabled": set()})
        self.assertNotIn("fluid:water", off)

    def test_disabled_key_wins_over_evidence(self):
        free = generators.resolve(
            placed={"nuclearcraft:water_source": 1},
            overrides={"generators": {}, "disabled": {"fluid:water"},
                       "vanilla_water": False})
        self.assertEqual(free, {})

    def test_overrides_round_trip(self):
        path = os.path.join(tempfile.mkdtemp(), "sources.json")
        generators.save_overrides(path, {"mymod:well": ["fluid:water"]},
                                  ["minecraft:cobblestone"], False)
        ov = generators.load_overrides(path)
        self.assertEqual(ov["generators"], {"mymod:well": ["fluid:water"]})
        self.assertEqual(ov["disabled"], {"minecraft:cobblestone"})
        self.assertFalse(ov["vanilla_water"])

    def test_source_cost_is_never_zero(self):
        # Zero makes quantity invisible to the ranker; see generators.py.
        self.assertGreater(generators.SOURCE_COST, 0.0)


class PlanningTest(unittest.TestCase):
    def test_a_placed_source_replaces_the_absurd_chain(self):
        g = chain_graph()
        free = {"fluid:water": "placed: nuclearcraft:water_source"}
        costs = cost.estimate(g, free_sources=free)
        result = Solver(g, costs=costs, free_sources=free).solve("mod:goo", 1)
        self.assertEqual(result["shopping_list"], [])
        self.assertEqual([r["key"] for r in result["from_sources"]], ["fluid:water"])
        self.assertEqual(result["from_sources"][0]["qty"], 4000)

    def test_without_a_source_the_chain_is_still_planned(self):
        g = chain_graph()
        result = Solver(g, costs=cost.estimate(g)).solve("mod:goo", 1)
        self.assertEqual([r["key"] for r in result["shopping_list"]],
                         ["minecraft:snowball"])

    def test_source_draw_is_reported_not_hidden(self):
        g = chain_graph()
        free = {"fluid:water": "placed: x"}
        result = Solver(g, costs=cost.estimate(g, free_sources=free),
                        free_sources=free).solve("mod:goo", 3)
        # 3 x 4,000 mB. A free resource still has a quantity and it must be visible.
        self.assertEqual(result["from_sources"][0]["qty"], 12000)
        self.assertEqual(result["tree"]["children"][0]["status"], STATUS_SOURCE)

    def test_source_draw_is_not_double_counted_across_backtracks(self):
        """A discarded branch must not leave its draw behind.

        `from_sources` has to be in the solver's snapshot/restore alongside the other
        accumulators. It was not, at first.
        """
        g = Graph()
        g.names = {"mod:target": "Target", "mod:loop": "Loop"}
        # First-choice recipe cycles, so the solver tries it, backtracks, and takes the
        # second. Both draw water.
        g.add(Recipe("cyclic", "t", [("mod:target", 1)],
                     [Ingredient(["mod:loop"], 1), Ingredient(["fluid:water"], 1000)],
                     category="c1"))
        g.add(Recipe("clean", "t", [("mod:target", 1)],
                     [Ingredient(["fluid:water"], 1000)], category="c2"))
        g.add(Recipe("loopback", "t", [("mod:loop", 1)],
                     [Ingredient(["mod:target"], 1)], category="c3"))
        free = {"fluid:water": "placed: x"}
        result = Solver(g, free_sources=free).solve("mod:target", 1)
        drawn = {r["key"]: r["qty"] for r in result["from_sources"]}
        self.assertEqual(drawn.get("fluid:water"), 1000)


class ContainerFluidTest(unittest.TestCase):
    """A container transfer must never be selected to CREATE a fluid."""

    @staticmethod
    def _graph():
        g = Graph()
        g.names = {"forestry:can": "Can", "forestry:can:1": "Filled Can",
                   "fluid:uranium_fluoride": "[fluid] uranium_fluoride"}
        # The real, observed edge: the dump drops the NBT that says WHICH fluid a filled
        # can holds, so every filled can collapses to `forestry:can:1` and squeezing a can
        # of water appears to yield uranium fluoride.
        squeeze = Recipe("squeeze", "t",
                         [("fluid:uranium_fluoride", 1000)],
                         [Ingredient(["forestry:can:1"], 1)], category="forestry.squeezer")
        squeeze.transfer = True
        g.add(squeeze)
        fill = Recipe("fill", "t", [("forestry:can:1", 1)],
                      [Ingredient(["forestry:can"], 1),
                       Ingredient(["fluid:water"], 1000, "fluid")],
                      category="thermalexpansion.transposer_fill")
        fill.transfer = True
        g.add(fill)
        return g

    def test_transfer_is_not_a_fluid_producer(self):
        g = self._graph()
        self.assertEqual(g.producers("fluid:uranium_fluoride"), [g.recipes[0]])
        self.assertEqual(g.real_producers("fluid:uranium_fluoride"), [])

    def test_transfer_may_still_produce_an_item(self):
        # Filling a can IS real work, so only the fluid direction is suppressed.
        g = self._graph()
        self.assertEqual(len(g.real_producers("forestry:can:1")), 1)

    def test_a_fluid_reachable_only_by_emptying_a_container_reads_as_needed(self):
        g = self._graph()
        result = Solver(g).solve("fluid:uranium_fluoride", 1000)
        self.assertEqual(result["tree"]["status"], "raw")

    def test_cost_model_agrees_with_the_solver(self):
        # If these disagree the ranker prices a route the solver cannot walk.
        g = self._graph()
        costs = cost.estimate(g)
        self.assertTrue(math.isinf(costs.get("fluid:uranium_fluoride", math.inf)))


class ContainerDetectionTest(unittest.TestCase):
    """The DETECTOR, not the flag.

    Every other suite sets `transfer = True` by hand and then checks the solver honours
    it. That is why #34 shipped: detection collapsed from 7,016 recipes to 117 and no
    test noticed, because no test asked `mark_container_transfers` to find anything.
    """

    def test_variants_of_one_container_are_detected_together(self):
        # Signal 2 counts fluids per BASE key. Each filled can is its own key at schema 3
        # and makes exactly one fluid, so counting per full key reaches a threshold of 8
        # only if the variants are grouped back together.
        g = fixtures.discriminated_graph()
        flagged, containers = index.mark_container_transfers(g)
        self.assertIn(fixtures.CAN_BASE, containers)
        self.assertEqual(flagged, len(fixtures.CANNED_FLUIDS))

    def test_the_honest_recipe_is_left_alone(self):
        # Salt -> brine is production and shares a fluid with the container route, so a
        # detector that flagged by fluid rather than by container would catch it.
        g = fixtures.discriminated_graph()
        index.mark_container_transfers(g)
        boil = [r for r in g.recipes if r.rid == "boil"][0]
        self.assertFalse(boil.transfer)
        self.assertEqual(g.real_producers("fluid:brine"), [boil])

    def test_emptying_a_container_is_not_a_fluid_producer(self):
        g = fixtures.discriminated_graph()
        index.mark_container_transfers(g)
        # `acid` has no route but the can, so it must come out as NEED rather than free.
        self.assertEqual(len(g.producers("fluid:acid")), 1)
        self.assertEqual(g.real_producers("fluid:acid"), [])
        result = Solver(g).solve("fluid:acid", 1000)
        self.assertEqual(result["tree"]["status"], "raw")

    def test_detection_is_unchanged_on_a_dump_with_no_discriminators(self):
        # base_key is the identity function below schema 3, so an older dump must flag
        # exactly what it always did. This is what lets the fix be ungated.
        g = fixtures.discriminated_graph()
        for r in g.recipes:
            for ing in r.inputs:
                ing.alternatives = [a.split("#")[0] for a in ing.alternatives]
            r.outputs = [(k.split("#")[0], q) for k, q in r.outputs]
        g._by_output = g._by_input = None
        g._producer_cache = {}
        flagged, containers = index.mark_container_transfers(g)
        self.assertIn(fixtures.CAN_BASE, containers)
        self.assertEqual(flagged, len(fixtures.CANNED_FLUIDS))

    def test_a_container_that_holds_few_fluids_is_not_flagged(self):
        # The threshold is what separates a container from a machine that happens to take
        # one item and emit a fluid. Drop below it and nothing should be flagged.
        g = fixtures.discriminated_graph()
        g.recipes = [r for r in g.recipes if not r.rid.startswith("squeeze_")][:]
        keep = fixtures.CANNED_FLUIDS[:3]
        for fluid in keep:
            g.add(Recipe("squeeze_%s" % fluid, "t",
                         [("mod:ingot_tin", 1), ("fluid:%s" % fluid, 1000)],
                         [Ingredient(["%s#%s" % (fixtures.CAN_BASE,
                                                 fixtures.CAN_DIGESTS[fluid])], 1)],
                         category="mod.squeezer"))
        flagged, containers = index.mark_container_transfers(g)
        self.assertEqual(containers, set())
        self.assertEqual(flagged, 0)

    def test_signal_one_matches_across_a_discriminator(self):
        # `X -> X#d` and `X#d -> X` are the same container seen from either end. Before
        # #34 these were different strings and signal 1 stopped firing entirely.
        g = Graph()
        g.add(Recipe("fill", "t",
                     [("mod:tank#0123456789ab", 1), ("fluid:lava", 1000)],
                     [Ingredient(["mod:tank"], 1)], category="mod.filler"))
        flagged, _containers = index.mark_container_transfers(g)
        self.assertEqual(flagged, 1)
        self.assertTrue(g.recipes[0].transfer)

    def test_meta_is_never_collapsed_into_the_base_key(self):
        # Stripping meta as well as the discriminator would merge every metadata variant
        # of an item into one pseudo-container. `tconstruct:ingots` would then appear to
        # melt into every molten metal in the pack and the smeltery would be suppressed.
        g = Graph()
        for i, fluid in enumerate(fixtures.CANNED_FLUIDS):
            g.add(Recipe("melt_%d" % i, "t", [("fluid:%s" % fluid, 144)],
                         [Ingredient(["mod:ingots:%d" % i], 1)], category="mod.smeltery"))
        flagged, containers = index.mark_container_transfers(g)
        self.assertEqual(containers, set())
        self.assertEqual(flagged, 0)


class FluidScaleTest(unittest.TestCase):
    """Fluid quantity must scale on BOTH sides of a recipe."""

    def test_a_long_fluid_chain_does_not_decay_to_zero(self):
        # Each hop is 1,000 mB in and 1,000 mB out, so cost must RISE by the machine cost
        # per hop. Scaling only inputs divided by 1,000 per hop and every fluid in the
        # reference pack converged to 0.0, silently disabling the cost model.
        g = Graph()
        g.names = {"mod:seed": "Seed"}
        g.add(Recipe("r0", "t", [("fluid:f0", 1000)],
                     [Ingredient(["mod:seed"], 1)], category="c"))
        for i in range(8):
            g.add(Recipe("r%d" % (i + 1), "t", [("fluid:f%d" % (i + 1), 1000)],
                         [Ingredient(["fluid:f%d" % i], 1000, "fluid")], category="c"))
        costs = cost.estimate(g, passes=20)
        for i in range(8):
            self.assertLess(costs["fluid:f%d" % i], costs["fluid:f%d" % (i + 1)],
                            "hop %d got cheaper than its input" % (i + 1))
        self.assertGreater(costs["fluid:f8"], 1.0)

    def test_a_bucket_and_an_item_cost_about_the_same_to_carry(self):
        g = Graph()
        g.names = {}
        g.add(Recipe("item", "t", [("mod:item_out", 1)],
                     [Ingredient(["mod:leaf"], 1)], category="c"))
        g.add(Recipe("fluid", "t", [("fluid:out", 1000)],
                     [Ingredient(["mod:leaf"], 1)], category="c"))
        costs = cost.estimate(g)
        self.assertAlmostEqual(costs["mod:item_out"], costs["fluid:out"], places=6)

    def test_a_tiny_output_cannot_manufacture_a_free_resource(self):
        g = Graph()
        g.names = {}
        g.add(Recipe("drip", "t", [("fluid:rare", 1)],
                     [Ingredient(["mod:expensive"], 1000)], category="c"))
        costs = cost.estimate(g)
        self.assertGreater(costs["fluid:rare"], 0.0)


class CacheTest(unittest.TestCase):
    def test_cache_hit_returns_the_same_table(self):
        g = chain_graph()
        path = os.path.join(tempfile.mkdtemp(), "cost.json")
        first = cost.estimate_cached(g, "nonexistent.json", cache_path=path)
        self.assertTrue(os.path.exists(path))
        second = cost.estimate_cached(g, "nonexistent.json", cache_path=path)
        self.assertEqual(first, second)

    def test_changed_machine_state_invalidates_the_cache(self):
        g = chain_graph()
        path = os.path.join(tempfile.mkdtemp(), "cost.json")
        a = cost.estimate_cached(g, "nonexistent.json", cache_path=path,
                                 machine_states={"mod.melter": ("have", "")})
        b = cost.estimate_cached(g, "nonexistent.json", cache_path=path,
                                 machine_states={"mod.melter": ("unavailable", "")})
        self.assertNotEqual(a["fluid:water"], b["fluid:water"])

    def test_a_corrupt_cache_recomputes_instead_of_failing(self):
        g = chain_graph()
        path = os.path.join(tempfile.mkdtemp(), "cost.json")
        with open(path, "w") as fh:
            fh.write("{not json")
        self.assertIn("fluid:water", cost.estimate_cached(g, "x.json", cache_path=path))

    def test_infinite_costs_survive_a_cache_round_trip(self):
        g = ContainerFluidTest._graph()
        path = os.path.join(tempfile.mkdtemp(), "cost.json")
        cost.estimate_cached(g, "x.json", cache_path=path)
        reloaded = cost.estimate_cached(g, "x.json", cache_path=path)
        self.assertTrue(math.isinf(reloaded.get("fluid:uranium_fluoride", math.inf)))


if __name__ == "__main__":
    unittest.main()
