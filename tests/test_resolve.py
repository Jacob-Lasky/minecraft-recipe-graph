"""Turning what someone typed into a key. See #107.

Reported as `plan fluid:nethengeic_fluid` answering "no item matched" for a key the running
server plans in 48ms. `names.resolve` was handed `graph.names`, which is items only -- 1,198
fluids live in `labels` and zero in `names` -- so the exact-key branch could never fire for a
fluid, an essentia aspect or an oredict entry.

The worse half is that it did not fail, it succeeded at the wrong thing: asked for the fluid
BY NAME it planned a container, because substring hits were ordered by label length and
"Strong Mythic Essence Can" sorted first. One line of preamble, then a confident plan for an
item nobody asked about.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import explore  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402

FLUID = "fluid:nethengeic_fluid"
CAN = "forestry:can:1#049547d397a6"
EMPTY_CAN = "forestry:can:1"


def bottled():
    """The reported graph: a fluid the pack renamed, and the can it is bottled in.

    Both wear "Strong Mythic Essence" -- the can as a suffix of it -- which is exactly the
    ambiguity `names.resolve` broke on label length. See fluidnames.py and #103 for how the
    fluid got that name in the first place.
    """
    g = Graph()
    g.names = {EMPTY_CAN: "Can", CAN: "Strong Mythic Essence Can",
               "mod:sludge": "Sludge"}
    # Emptying the can, which is what names the fluid.
    g.add(Recipe("empty", "t", [(EMPTY_CAN, 1), (FLUID, 1000)],
                 [Ingredient([CAN], 1)], category="transposer", transfer=True))
    # And something that consumes the fluid, so it is live and plannable.
    g.add(Recipe("use", "t", [("mod:sludge", 1)],
                 [Ingredient([FLUID], 500, "fluid")], category="minecraft.crafting"))
    return g


class AnExactKeyResolvesToItselfTest(unittest.TestCase):
    def test_the_reported_fluid_key(self):
        # Was: "no item matched 'fluid:nethengeic_fluid' -- try `find`".
        self.assertEqual(explore.resolve_query(bottled(), FLUID), [FLUID])

    def test_and_returns_it_ALONE_so_nothing_can_outrank_it(self):
        # The whole point of trying the key first. A key you named exactly is a key you
        # meant, and a list would let `plan` print "3 other candidates" about a certainty.
        self.assertEqual(len(explore.resolve_query(bottled(), FLUID)), 1)

    def test_every_namespace_the_graph_has(self):
        g = bottled()
        g.ore_members = {"ingotIron": ["minecraft:iron_ingot"]}
        g.add(Recipe("ore_in", "t", [("mod:thing", 1)],
                     [Ingredient(["ore:ingotIron"], 1)], category="minecraft.crafting"))
        g.add(Recipe("ess_in", "t", [("mod:other", 1)],
                     [Ingredient(["essentia:ignis"], 1, "essentia")],
                     category="thaumcraft.infusion"))
        for key in ("ore:ingotIron", "essentia:ignis", EMPTY_CAN):
            self.assertEqual(explore.resolve_query(g, key), [key], key)

    def test_a_key_needing_normalisation_still_matches(self):
        # `norm_key` fills in the modid and drops a zero meta, so the second attempt is not
        # redundant with the first.
        g = bottled()
        g.names["minecraft:stone"] = "Stone"
        g.add(Recipe("s", "t", [("mod:x", 1)], [Ingredient(["minecraft:stone"], 1)]))
        self.assertEqual(explore.resolve_query(g, "stone"), ["minecraft:stone"])

    def test_whitespace_around_a_key_is_forgiven(self):
        self.assertEqual(explore.resolve_query(bottled(), "  %s  " % FLUID), [FLUID])

    def test_a_key_that_is_dead_still_resolves(self):
        # `rank_matches` filters to live keys, and an exact key must bypass that: answering
        # "no match" for a key the graph demonstrably holds is worse than planning a dead
        # one, which at least says "no recipe".
        g = bottled()
        g.names["mod:vestigial"] = "Vestigial Thing"
        self.assertEqual(explore.resolve_query(g, "mod:vestigial"), ["mod:vestigial"])

    def test_a_key_only_the_inventory_knows_resolves(self):
        # An NBT-discriminated stack the dump never saw is in neither `names` nor `labels`,
        # and holding one is a fact about the world.
        held = "thaumadditions:vis_pod#03c878f080d5"
        self.assertEqual(explore.resolve_query(bottled(), held, have={held: 4}), [held])


class ANameResolvesTheWayTheUIRanksTest(unittest.TestCase):
    def test_the_fluid_wins_over_the_can_that_holds_it(self):
        # THE REPORTED MIS-PLAN. `names.resolve` ordered substring hits by label length, so
        # the can came first and `plan "strong mythic essence"` planned a container.
        keys = explore.resolve_query(bottled(), "strong mythic essence")
        self.assertEqual(keys[0], FLUID)

    def test_the_cli_and_the_web_ui_now_agree(self):
        # They did not, and that is the defect underneath the defect: two rankings for one
        # question. Asserted against `rank_matches` itself so they cannot drift apart again.
        g = bottled()
        self.assertEqual(explore.resolve_query(g, "strong mythic essence"),
                         explore.rank_matches(g, "strong mythic essence").results)

    def test_a_name_nothing_wears_resolves_to_nothing(self):
        self.assertEqual(explore.resolve_query(bottled(), "nonexistent widget"), [])

    def test_an_empty_query_is_empty_rather_than_everything(self):
        for query in ("", "   "):
            self.assertEqual(explore.resolve_query(bottled(), query), [], repr(query))

    def test_stock_breaks_a_tie_among_same_named_keys(self):
        # #101's rule, now reaching the CLI: among keys wearing one label, a stack in the
        # network is the pack telling you which one you actually use.
        g = Graph()
        g.names = {"mod:a": "Iron Plate", "mod:b": "Iron Plate"}
        g.add(Recipe("r", "t", [("mod:out", 1)],
                     [Ingredient(["mod:a"], 1), Ingredient(["mod:b"], 1)]))
        self.assertEqual(explore.resolve_query(g, "iron plate",
                                               have={"mod:b": 640})[0], "mod:b")

    def test_the_limit_is_honoured(self):
        g = Graph()
        g.names = {"mod:p%d" % i: "Plate %d" % i for i in range(10)}
        g.add(Recipe("r", "t", [("mod:out", 1)],
                     [Ingredient(list(g.names), 1)]))
        self.assertEqual(len(explore.resolve_query(g, "plate", limit=3)), 3)


class AMissingStockFileDoesNotKillTheCommandTest(unittest.TestCase):
    """`--have` defaults to a PATH, so a checkout that has not run `have` yet crashed.

    Latent in `plan` since the default was added, and surfaced by #107 giving `find` the
    same default: `_load_have` guarded `if not path` and then opened it regardless.
    """

    def load(self, path):
        import io
        from contextlib import redirect_stderr

        from recipegraph import cli
        err = io.StringIO()
        with redirect_stderr(err):
            result = cli._load_have(path)
        return result, err.getvalue()

    def test_a_missing_file_reads_as_an_empty_network(self):
        (have, _stats, craftables, names, dims), _err = self.load(
            "/nonexistent/ae2_have.json")
        # `dims` empty is load-bearing, not incidental: it is what makes a missing stock
        # file gate no dimension rather than every one. See dimensions.gates_for.
        self.assertEqual((have, craftables, names, dims), ({}, set(), {}, {}))

    def test_and_says_so_on_stderr(self):
        # Going quiet would be worse than the crash: a mistyped --have would plan against
        # nothing and "you still need everything" reads as an answer.
        _result, err = self.load("/nonexistent/ae2_have.json")
        self.assertIn("/nonexistent/ae2_have.json", err)
        self.assertIn("empty network", err)

    def test_no_path_at_all_stays_silent(self):
        # `--have ""` is the caller saying "I have nothing", which needs no warning.
        _result, err = self.load("")
        self.assertEqual(err, "")


class TheDeadResolverIsGoneTest(unittest.TestCase):
    def test_names_no_longer_ships_a_second_resolver(self):
        # `names.resolve` was the one with the blind spot, and leaving it importable is how
        # a future call site reintroduces #107 by picking the wrong one out of two.
        from recipegraph import names
        self.assertFalse(hasattr(names, "resolve"),
                         "names.resolve is back; the CLI must use explore.resolve_query")

    def test_the_cli_resolves_through_explore(self):
        import inspect

        from recipegraph import cli
        source = inspect.getsource(cli)
        self.assertEqual(source.count("explore_mod.resolve_query"), 2,
                         "both `find` and `plan` must resolve the same way")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
