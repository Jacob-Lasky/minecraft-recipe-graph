"""Infinite generators, container-transfer fluids, and cost-scale symmetry.

All three came out of one plan for 64 Borax that drew its water from 71 Snowballs and 12
Wet Sponges, then -- once water was free -- from a nuclear fission chain.
"""

import json
import math
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# tests/ itself, so `import fixtures` works under `-m unittest tests.<mod>` and
# not only under `discover -s tests`, which inserts this directory for us.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fixtures  # noqa: E402
from recipegraph import cost, generators, index  # noqa: E402
from recipegraph.sources import dump_meta  # noqa: E402
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
        """If these disagree the ranker prices a route the solver cannot walk.

        AGREEMENT IS A FINITE PRICE, NOT AN INFINITY, and this test asserted infinity until
        #193. The solver calls this fluid `raw` and shopping-lists it -- the assertion right
        above -- so a cost model saying "unobtainable" is the disagreement rather than the
        agreement, and every parent's price inherited it.

        WHAT IT COULD AND COULD NOT CATCH, because the difference is the point. It would fail
        if the container exclusion were dropped, since the squeezer route would then price the
        fluid and the assertion below is against exactly that number. It could NOT see #193's
        defect: nothing in `_graph` CONSUMED the fluid,
        `cost._seed`'s leaf rule walks recipe INPUTS, so the key was never offered to the
        predicate at all and came out absent from the table, which the `math.isinf` default
        answered for. A consumer is what makes the assertion about the rule.

        The figure is `UNSOURCED_COST` rather than a raw leaf, which was measured; see
        `Graph.produced_in_name_only`. The wider population and the enumerated agreement
        between the three readers are in `tests/test_produced.py`.
        """
        g = self._graph()
        g.add(Recipe("enrich", "t", [("mod:enriched", 1)],
                     [Ingredient(["fluid:uranium_fluoride"], 1000, "fluid")],
                     category="minecraft.crafting"))
        costs = cost.estimate(g)
        self.assertEqual(cost.UNSOURCED_COST, costs.get("fluid:uranium_fluoride"))
        # And the price is not one the CAN computed, which is the constraint the exclusion
        # guards: the squeezer route would land on machine + TRANSFER_PENALTY + the can.
        self.assertNotEqual(cost.UNGATED_MACHINE_COST + cost.TRANSFER_PENALTY
                            + costs.get("forestry:can:1", 0.0),
                            costs.get("fluid:uranium_fluoride"))


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

    def test_a_null_in_a_cache_file_reads_back_as_infinity(self):
        """The `None`/`inf` encoding, exercised through the branch a real cache reaches.

        THIS TEST USED TO ASSERT NOTHING. It primed a cache from `ContainerFluidTest._graph`
        and then checked `reloaded.get(key, math.inf)` was infinite -- but the key was never in
        the table at all, so the DEFAULT supplied the answer and the assertion held whatever
        the encoding did.

        The write half is unreachable from `estimate` today: every seed rule stores a finite
        number and `_relax` only ever lowers to a finite one, so no key comes out of `estimate`
        holding an infinity to encode. The READ half is reachable by anything on disk -- a file
        written by an older version, or by a future rule that does store one -- so that is the
        direction worth pinning, and it is pinned against a hand-written document rather than
        against one this code produced.
        """
        g = chain_graph()
        path = os.path.join(tempfile.mkdtemp(), "cost.json")
        stamp = cost.fingerprint("x.json", None, None, None, None,
                                 getattr(g, "multiblocks", None), None, None, None, None, None)
        with open(path, "w") as fh:
            json.dump({"fingerprint": stamp, "cost": {"mod:unreachable": None,
                                                      "fluid:water": 3.5}}, fh)
        table = cost.estimate_cached(g, "x.json", cache_path=path)
        self.assertIn("mod:unreachable", table)
        self.assertTrue(math.isinf(table["mod:unreachable"]))
        # And the hit really was served from the file rather than recomputed, or the assertion
        # above would be about a key `estimate` never wrote.
        self.assertEqual(3.5, table["fluid:water"])

    def test_no_cache_path_memoises_beside_the_graph_not_the_cwd(self):
        """The default must follow the GRAPH, because `plan` runs in a container too.

        `cli.cmd_plan` calls `estimate_cached` without a cache path, so with the old relative
        default a containerised plan memoised into the image's /app/data/ rather than the
        mounted /data beside the graph -- recomputing 26s of relaxation every invocation while
        a valid table sat one bind-mount away (the #92 family). Guarded as "the cwd stays
        clean" so it also catches a caller that reintroduces the relative constant.
        """
        g = chain_graph()
        graph_dir = tempfile.mkdtemp()
        graph_path = os.path.join(graph_dir, "graph.json")
        g.save(graph_path)

        cwd = tempfile.mkdtemp()
        os.makedirs(os.path.join(cwd, "data"))
        here = os.getcwd()
        os.chdir(cwd)
        self.addCleanup(os.chdir, here)

        cost.estimate_cached(g, graph_path)
        self.assertEqual(sorted(os.listdir(os.path.join(cwd, "data"))), [],
                         "estimate_cached wrote into the cwd instead of beside the graph")
        self.assertTrue(os.path.exists(cost.cache_beside(graph_path)))
        self.assertEqual(os.path.dirname(cost.cache_beside(graph_path)), graph_dir)

    def test_the_derived_cache_is_actually_READ_back(self):
        """A derived path that never hits would be a silent 26s tax rather than a cache.

        Proven by planting a sentinel price under the fingerprint the first run wrote and
        requiring it back. Comparing two runs' tables cannot show this: a recompute returns
        the same numbers and rewrites the same fingerprint, so it passes either way.
        """
        g = chain_graph()
        graph_path = os.path.join(tempfile.mkdtemp(), "graph.json")
        g.save(graph_path)
        cost.estimate_cached(g, graph_path)

        derived = cost.cache_beside(graph_path)
        with open(derived) as fh:
            doc = json.load(fh)
        doc["cost"]["fluid:water"] = 1234.5
        with open(derived, "w") as fh:
            json.dump(doc, fh)

        self.assertEqual(cost.estimate_cached(g, graph_path)["fluid:water"], 1234.5,
                         "the derived cache was written but never read back")


class DumpDirOverrideTest(unittest.TestCase):
    """`build --dump-dir` must move EVERY dump file, not just recipes.ndjson.

    #80's churn proof requires keeping a dump under another name, because a second
    `/recipedump` rewrites the directory in place. Before this existed the only redirect was
    `--hei`, which moves recipes.ndjson alone -- so names, oredict, catalysts and the schema
    stamp kept coming from whatever sat at the canonical path, and a graph could hold recipes
    from one dump and names from another with nothing saying so. That is the failure this
    class exists to prevent, so the assertions are about the canonical directory NOT leaking
    rather than only about the override being read.
    """

    @staticmethod
    def _write_dump(path, tag, version):
        os.makedirs(path, exist_ok=True)
        def put(name, doc):
            with open(os.path.join(path, name), "w") as fh:
                json.dump(doc, fh)
        put("names.json", {"mod:%s_item" % tag: "%s Item" % tag.upper()})
        put("oredict.json", {"ore%s" % tag.capitalize(): ["mod:%s_item" % tag]})
        put("catalysts.json", {"mod.%s_machine" % tag: ["mod:%s_machine" % tag]})
        put("summary.json", {"mod_version": version, "schema": 4, "recipes": 1,
                             "skipped": 0, "categories": {"mod.%s_machine" % tag:
                                                          {"dumped": 1, "threw": 0,
                                                           "empty": 0, "mod": tag}}})
        with open(os.path.join(path, "recipes.ndjson"), "w") as fh:
            fh.write(json.dumps({"cat": "mod.%s_machine" % tag,
                                 "in": [[{"i": "mod:%s_in" % tag, "c": 1}]],
                                 "out": [{"i": "mod:%s_out" % tag, "c": 1}]}) + "\n")

    def setUp(self):
        self.inst = tempfile.mkdtemp()
        self.canon = os.path.join(self.inst, "mc-recipe-dump")
        self.preserved = os.path.join(self.inst, "mc-recipe-dump.run1")
        self._write_dump(self.canon, "canon", "0.1.0")
        self._write_dump(self.preserved, "run1", "0.8.0")

    def test_default_reads_the_canonical_directory(self):
        g = index.build(self.inst, quiet=True)
        self.assertEqual(g.dump_version, "0.1.0")
        self.assertIn("mod:canon_item", g.names)

    def test_override_moves_every_file_off_the_canonical_directory(self):
        g = index.build(self.inst, quiet=True, dump_dir=self.preserved)
        # Provenance, names, oredict, catalysts and recipes -- all five readers.
        self.assertEqual(g.dump_version, "0.8.0")
        self.assertIn("mod:run1_item", g.names)
        self.assertIn("oreRun1", g.ore_members)
        self.assertIn("mod.run1_machine", g.catalysts)
        self.assertIn("mod:run1_out", g.by_output)

    def test_nothing_leaks_from_the_canonical_directory(self):
        """The regression itself: this is what silently mixing two dumps looks like."""
        g = index.build(self.inst, quiet=True, dump_dir=self.preserved)
        self.assertNotIn("mod:canon_item", g.names)
        self.assertNotIn("oreCanon", g.ore_members)
        self.assertNotIn("mod.canon_machine", g.catalysts)
        self.assertNotIn("mod:canon_out", g.by_output)
        self.assertNotEqual(g.dump_version, "0.1.0")

    def test_category_mods_follow_the_override_too(self):
        """`category_mods` read the resolved path via a local that was easy to drop.

        It is the one reader whose argument is the directory rather than a file found under
        it, so a refactor can leave it pointing at the parameter instead of the resolved
        path and it fails silently -- every category simply loses JEI's mod name and the
        machines page falls back to guessing from the uid.
        """
        g = index.build(self.inst, quiet=True, dump_dir=self.preserved)
        self.assertEqual(g.category_mods.get("mod.run1_machine"), "run1")
        self.assertNotIn("mod.canon_machine", g.category_mods)

    def test_an_absent_override_directory_does_not_silently_fall_back(self):
        missing = os.path.join(self.inst, "no-such-dump")
        g = index.build(self.inst, quiet=True, dump_dir=missing)
        # Empty rather than the canonical dump's contents: falling back would resurrect the
        # mixing bug in its worst form, where the graph looks complete and is wrong.
        self.assertNotIn("mod:canon_item", g.names)
        self.assertNotIn("mod:canon_out", g.by_output)
        self.assertEqual(g.catalysts, {})

    def test_dir_for_is_the_single_definition_of_the_name(self):
        self.assertEqual(dump_meta.dir_for("/i"), os.path.join("/i", dump_meta.DIR_NAME))
        self.assertEqual(dump_meta.dir_for("/i", "/elsewhere"), "/elsewhere")


class NoHardcodedDumpDirTest(unittest.TestCase):
    """The literal belongs in `dump_meta.DIR_NAME` and nowhere else under `recipegraph/`.

    A property rather than a list of call sites, so it also covers readers added later --
    which is the point, since every one of them is a chance to reintroduce the mix.
    """

    def test_only_dump_meta_spells_the_directory_name(self):
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph")
        offenders = []
        for dirpath, _dirs, files in os.walk(root):
            for name in files:
                if not name.endswith(".py"):
                    continue
                path = os.path.join(dirpath, name)
                if os.path.basename(path) == "dump_meta.py":
                    continue
                with open(path, encoding="utf-8") as fh:
                    for lineno, line in enumerate(fh, 1):
                        if '"mc-recipe-dump"' in line or "'mc-recipe-dump'" in line:
                            offenders.append("%s:%d" % (os.path.relpath(path, root), lineno))
        self.assertEqual(offenders, [],
                         "hardcoded dump dir name outside dump_meta.DIR_NAME: %s"
                         % ", ".join(offenders))


if __name__ == "__main__":
    unittest.main()
