"""The cost probe's display layer, which is the whole product.

`tools/cost-probe.py` exists so a change to a `cost.py` constant can be measured instead of
argued about, and its output gets pasted into issues as evidence. It shipped misreading two
of the four probes that ARE the control group: `route` printed `ingredient.alternatives[0]`,
the dump's first option, rather than the one `Solver.pick_alternative` expands -- which is
issue #29's exact misreading, the one the tool is supposed to be able to check for.

Loaded through importlib because `tools/` is not a package and the filename is hyphenated.
Only the pure display layer is covered: a sweep needs the 115 MB graph and minutes of
relaxation, which does not belong in a suite CI runs on every push.
"""

import collections
import importlib.util
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph import machines  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     "tools", "cost-probe.py")
_spec = importlib.util.spec_from_file_location("cost_probe", _PATH)
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)


def graph_with_a_choice_of_alternatives():
    """One slot, two alternatives, and the CHEAP one is not the one listed first.

    Mirrors the real shape: `minecraft:iron_ingot` is smelted from an oredict slot whose
    first member is `abyssalcraft:abyore` and whose cheap member is `minecraft:iron_ore`.
    """
    g = Graph()
    g.names = {"mod:ingot": "Ingot", "mod:exotic_ore": "Exotic Ore",
               "mod:common_ore": "Common Ore", "mod:dust": "Dust"}
    # Exotic first in the list, and expensive: it takes 50 dust to get one.
    g.add(Recipe("smelt", "Furnace", [("mod:ingot", 1)],
                 [Ingredient(["mod:exotic_ore", "mod:common_ore"], 1)],
                 category="minecraft.smelting"))
    # A fluid whose ONLY producer is a container empty. `by_output` counts that as
    # production and `real_producers` does not, which is the pair the `*` marker has to
    # tell apart: to hold a can of brine you must already have had the brine.
    g.names["fluid:brine"] = "Brine"
    g.add(Recipe("empty_can", "Transposer", [("fluid:brine", 1000)],
                 [Ingredient(["mod:can"], 1)], category="mod.transposer", transfer=True))
    g.names["mod:brined"] = "Brined Thing"
    g.add(Recipe("brine_it", "t", [("mod:brined", 1)],
                 [Ingredient(["fluid:brine"], 1000)], category="minecraft.crafting"))
    g.add(Recipe("exotic", "t", [("mod:exotic_ore", 1)],
                 [Ingredient(["mod:dust"], 50)], category="minecraft.crafting"))
    g.add(Recipe("common", "t", [("mod:common_ore", 1)],
                 [Ingredient(["mod:dust"], 1)], category="minecraft.crafting"))
    return g


class RouteTest(unittest.TestCase):
    def setUp(self):
        self.g = graph_with_a_choice_of_alternatives()
        self.states = machines.resolve(self.g)
        costs = cost_mod.estimate(self.g, machine_states=self.states)
        self.solver = Solver(self.g, machine_states=self.states, costs=costs)

    def test_it_names_the_alternative_the_solver_would_expand(self):
        """#29's misreading, and the reason this test exists.

        `alternatives[0]` is Exotic Ore; the solver takes Common Ore because it is 50x
        cheaper. Printing the first one made the real tool report Iron Ingot as
        "Abyssal Iron Ore" and Gold Ingot as "Sandslash".
        """
        recipe = self.g.real_producers("mod:ingot")[0]
        self.assertEqual(recipe.inputs[0].alternatives[0], "mod:exotic_ore",
                         "fixture broken: the expensive option must be listed first")
        self.assertEqual(self.solver.pick_alternative(recipe.inputs[0]), "mod:common_ore")
        self.assertIn("Common Ore", probe.route(self.g, self.solver, recipe))
        self.assertNotIn("Exotic Ore", probe.route(self.g, self.solver, recipe))

    def test_the_star_means_nothing_REALLY_produces_it(self):
        """`real_producers`, not `by_output`, and the two must be told apart.

        `by_output` counts a container empty as production of the fluid, which is circular:
        to hold a can of brine you must already have had the brine. A `by_output` test
        prints Brine unstarred, i.e. it tells the reader a fluid is obtainable when the
        plan's own answer is NEED.
        """
        recipe = self.g.real_producers("mod:common_ore")[0]
        # Dust has no producer at all, so it is the plan's advice to go and find one.
        self.assertIn("Dust*", probe.route(self.g, self.solver, recipe))
        # Common Ore does, so the slot above must NOT be starred.
        top = self.g.real_producers("mod:ingot")[0]
        self.assertNotIn("*", probe.route(self.g, self.solver, top))
        # The discriminating case: in `by_output`, absent from `real_producers`.
        self.assertIn("fluid:brine", self.g.by_output)
        self.assertEqual(self.g.real_producers("fluid:brine"), [])
        brined = self.g.real_producers("mod:brined")[0]
        self.assertIn("Brine*", probe.route(self.g, self.solver, brined))

    def test_a_solved_tree_is_read_from_the_solver_s_own_verdict(self):
        # The tree branch shows the children the solver actually expanded, and takes `raw`
        # from their status rather than re-deriving it.
        tree = self.solver.solve("mod:ingot", 1)["tree"]
        line = probe.route(self.g, self.solver, tree)
        self.assertIn("Common Ore", line)
        self.assertIn("minecraft.smelting", line)

    def test_no_route_is_said_rather_than_crashed_on(self):
        self.assertEqual(probe.route(self.g, self.solver, None), "(no route)")


class SweepHygieneTest(unittest.TestCase):
    def test_the_sweep_restores_the_constant_it_mutated(self):
        """A module constant left mutated is a trap for the next caller.

        Harmless in a one-shot CLI, and the tool is importable -- this file imports it.
        """
        g = graph_with_a_choice_of_alternatives()
        was = cost_mod.BASE_RAW_COST
        try:
            probe.sweep(g, machines.resolve(g), [7.5], True, [("mod:ingot", "Ingot")])
            self.assertEqual(cost_mod.BASE_RAW_COST, was)
        finally:
            cost_mod.BASE_RAW_COST = was

    def test_the_sweep_actually_applies_each_value(self):
        # Otherwise the whole tool reports one table under several headings.
        g = graph_with_a_choice_of_alternatives()
        seen = []
        real = cost_mod.estimate

        def spy(*a, **k):
            seen.append(cost_mod.BASE_RAW_COST)
            return real(*a, **k)

        cost_mod.estimate = spy
        try:
            probe.sweep(g, machines.resolve(g), [1.0, 9.0], True, [("mod:ingot", "Ingot")])
        finally:
            cost_mod.estimate = real
        self.assertEqual(seen, [1.0, 9.0])


class ItCanSweepMoreThanOneConstantTest(unittest.TestCase):
    """`sweep` takes the constant's NAME, because #176 added one it could not see.

    The tool swept `BASE_RAW_COST` and nothing else, so the cost audit mandated for a
    change that introduces a new constant would have reported "no probe moved" and been
    believed. That is the failure this tool's own docstring describes -- "a wrong constant
    does not raise, it just quietly reroutes plans" -- arriving inside the tool written to
    catch it.
    """

    def test_it_applies_and_restores_the_named_constant(self):
        g = graph_with_a_choice_of_alternatives()
        seen = []
        real = cost_mod.estimate
        was = cost_mod.UNSOURCED_COST

        def spy(*a, **k):
            seen.append(cost_mod.UNSOURCED_COST)
            return real(*a, **k)

        cost_mod.estimate = spy
        try:
            probe.sweep(g, machines.resolve(g), [11.0, 22.0], True,
                        [("mod:ingot", "Ingot")], constant="UNSOURCED_COST")
        finally:
            cost_mod.estimate = real
            cost_mod.UNSOURCED_COST = was
        self.assertEqual(seen, [11.0, 22.0])
        self.assertEqual(cost_mod.UNSOURCED_COST, was)

    def test_sweeping_one_constant_leaves_the_other_alone(self):
        # The reason `sweep` takes a name rather than a flag choosing between two
        # hard-coded branches: a run that moved both would produce a grid whose cells
        # cannot be attributed to either constant.
        g = graph_with_a_choice_of_alternatives()
        raw_was = cost_mod.BASE_RAW_COST
        try:
            probe.sweep(g, machines.resolve(g), [11.0], True, [("mod:ingot", "Ingot")],
                        constant="UNSOURCED_COST")
            self.assertEqual(cost_mod.BASE_RAW_COST, raw_was)
        finally:
            cost_mod.BASE_RAW_COST = raw_was
            cost_mod.UNSOURCED_COST = cost_mod.UNSOURCED_COST

    def test_the_heading_names_the_constant_that_moved(self):
        # A report headed BASE_RAW_COST while UNSOURCED_COST was swept is worse than no
        # report: it is a wrong answer in the shape of a right one.
        rows = collections.OrderedDict([(11.0, ({"Ingot": "x"}, 0.0))])
        buffer = io.StringIO()
        stdout = sys.stdout
        sys.stdout = buffer
        try:
            probe.report(rows, [("mod:ingot", "Ingot")], constant="UNSOURCED_COST")
        finally:
            sys.stdout = stdout
        self.assertIn("UNSOURCED_COST=11.0", buffer.getvalue())
        self.assertNotIn("BASE_RAW_COST", buffer.getvalue())


class TheProbeCanSeeWhatItIsTuningTest(unittest.TestCase):
    """It could not, and that is worse than a wrong answer: it reported "no change".

    `load` went through `machines.resolve`, which drops the machine ITEM, so `estimate` was
    called with no `machine_items` and `machine_entry_costs` never ran. Every buildable
    category priced at the flat `MACHINE_COST["buildable"]`, which means the tool the repo
    requires before moving a cost constant was structurally blind to BUILD_SCALE, BUILD_KNEE
    and the multiblock structures of #93.
    """

    def test_load_returns_build_targets_for_a_machine_that_must_be_built(self):
        g = graph_with_a_choice_of_alternatives()
        # A category whose machine is a real, craftable block. The shared fixture has none:
        # its categories need no machine or have an unidentifiable one, so neither is a build
        # target and neither can show whether the entry price was applied.
        g.names["mod:press"] = "Press"
        g.catalysts["mod.press"] = ["mod:press"]
        g.add(Recipe("mk_press", "t", [("mod:press", 1)],
                     [Ingredient(["mod:dust"], 4)], category="minecraft.crafting"))
        g.add(Recipe("pressed", "Press", [("mod:plate", 1)],
                     [Ingredient(["mod:ingot"], 1)], category="mod.press"))
        path = os.path.join(tempfile.mkdtemp(), "g.json")
        g.save(path)
        graph, states, targets = probe.load(path)
        self.assertEqual(len(graph.recipes), len(g.recipes))
        self.assertEqual(states["mod.press"][0], "buildable")
        self.assertEqual(targets.get("mod.press"), ("mod:press",))

    def test_sweep_passes_the_build_targets_into_estimate(self):
        g = graph_with_a_choice_of_alternatives()
        seen = []
        real = cost_mod.estimate

        def spy(graph, **kw):
            seen.append(kw.get("machine_items"))
            return real(graph, **kw)

        cost_mod.estimate = spy
        try:
            probe.sweep(g, {}, [1.0], True, [("mod:ingot", "Ingot")],
                        {"mod.cat": ("mod:machine",)})
        finally:
            cost_mod.estimate = real
        self.assertEqual(seen, [{"mod.cat": ("mod:machine",)}])


class DroppedProbeTest(unittest.TestCase):
    def test_a_probe_with_no_producer_is_reported_not_silently_dropped(self):
        """It used to vanish, so a mistyped key gave "0 of 0" after a fifteen-minute run,
        and a control-group probe could leave the control group without a word."""
        g = graph_with_a_choice_of_alternatives()
        err = io.StringIO()
        argv = sys.argv
        sys.argv = ["cost-probe", "--rank", "--raw", "1",
                    "--item", "mod:ingot", "--item", "mod:not_a_real_key"]
        try:
            # stdout too: the report is the tool's normal output and has no place in a
            # test run's log.
            with redirect_stderr(err), redirect_stdout(io.StringIO()):
                # RESTORED, because this writes into the imported module's globals and the
                # replacement outlived the test: every later test that called `load` got this
                # stub's graph instead of its own, which reads as a save/load bug.
                self.addCleanup(setattr, probe, "load", probe.load)
                probe.main.__globals__["load"] = lambda _p: (
                    g, machines.resolve(g), {})
                probe.main()
        finally:
            sys.argv = argv
        self.assertIn("skipping mod:not_a_real_key", err.getvalue())


if __name__ == "__main__":
    unittest.main()
