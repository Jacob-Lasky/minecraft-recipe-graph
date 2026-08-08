"""Everything the python side learned from dump schema 8: how often a run yields its output.

#223. Schema 7 and earlier had no field for output chance, so every output read as guaranteed
and the solver divided the work by a yield that could be a thousand times too high. Modular
Machinery's `setChance` applies to outputs as well as inputs, and over the reference pack's 479
`scripts/*.zs` the two sides look nothing alike:

    addItemInput    1102 calls   1078 at p=0.0   24 fractional   0 at 1.0
    addItemOutput    835 calls      0 at p=0.0  834 fractional   1 at 1.0

The input side is overwhelmingly the binary catalyst case with a thin fractional tail. The
OUTPUT side is almost entirely fractional, spanning 0.99 down to 0.001, which is why `q` is a
float and why a boolean encoding would have lost essentially the whole signal.

THE MIRROR OF SCHEMA 7 AND NOT A COPY OF IT. An input chance scales a cost the recipe consumes;
an output chance scales the YIELD, which is a DIVISOR. `cost._relax` divides the ingredient term
by `_scaled_qty`, and `Solver._build` divides the remaining demand by the per-run yield. Both
had to move, and a fix to one alone would have reintroduced the ranker-versus-solver divergence
of #29 rather than curing anything.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph.model import Ingredient, Recipe  # noqa: E402
from recipegraph.sources import dump_meta, hei_dump  # noqa: E402


class SchemaStampTest(unittest.TestCase):
    """The literal, and it is a tripwire rather than a restatement.

    Moving `dump_meta.SCHEMA` has to be a decision: the number tells a reader whether its own
    recomputation still agrees with the dump, so a bump arriving as a side effect of an
    unrelated edit is a lie told to every downstream consumer. `tests/test_catalysts.py` pins
    it against `DumpCommand.java`, so the two languages cannot drift; this pins that either
    moving alone was intentional.

    INHERITED FROM `test_schema_seven.py`, which inherited it from six, which inherited it from
    five. The newest schema's file owns this assertion. When 9 arrives, move it again.
    """

    def test_the_python_side_expects_eight(self):
        self.assertEqual(dump_meta.SCHEMA, 8)


class TheCurrentSchemaReadsCleanTest(unittest.TestCase):

    def test_an_eight_dump_is_reported_as_current(self):
        said = dump_meta.describe({"present": True, "mod_version": "0.11.0",
                                   "schema": 8, "mod_count": 367})
        self.assertIn("schema 8", said)
        self.assertNotIn("newer fields", said)

    def test_a_nine_dump_is_reported_as_newer_than_this_reader(self):
        # The direction that matters most for a field like `q`: a dump that knows something
        # this reader does not must say so rather than being read as merely fine.
        said = dump_meta.describe({"present": True, "mod_version": "9.9.9", "schema": 9})
        self.assertIn("NEWER", said)


class QIsReadAndIsNotPTest(unittest.TestCase):
    """The two fields are separate, and every assertion here is about keeping them separate.

    `DumpCommand.stack`'s javadoc refuses to write a yield into `p` for this reason, and the
    reader has to refuse the mirror error. A consumer of `p` that saw a yield would treat the
    output as a catalyst and charge it once per plan; a consumer of `q` that saw a consume
    chance would divide the recipe's work by it.
    """

    def test_an_absent_q_is_certainty(self):
        self.assertEqual(hei_dump._yield_chance({"i": "minecraft:iron_ingot", "m": 0}), 1.0)

    def test_a_present_q_is_read(self):
        self.assertEqual(
            hei_dump._yield_chance({"i": "minecraft:iron_ingot", "m": 0, "q": 0.25}), 0.25)

    def test_p_on_a_stack_does_not_become_a_yield(self):
        # The exact confusion the separate field exists to prevent. A schema-7 catalyst input
        # carries `p: 0.0`; read as a yield it would say "this recipe never makes its output".
        self.assertEqual(hei_dump._yield_chance({"i": "tconstruct:cast_custom", "p": 0.0}), 1.0)

    def test_q_on_a_stack_does_not_become_a_consume_chance(self):
        self.assertEqual(hei_dump._consume_chance({"i": "minecraft:iron_ingot", "q": 0.1}), 1.0)

    def test_the_malformed_shapes_are_refused_the_same_way_for_both(self):
        # `_chance` is one validator for both fields precisely so this list cannot diverge.
        # `float(False)` is 0.0, `float("0.0")` parses, and `float(None)` raises: all three
        # reached the reader before the validation existed. See `hei_dump._chance`.
        for bad in (False, True, "0.5", None, [], {}, -0.1, 1.5, float("nan")):
            self.assertEqual(hei_dump._yield_chance({"i": "x", "q": bad}), 1.0,
                             "a %r must degrade to certainty, not to a number" % (bad,))


class ARecipeCarriesItsYieldsTest(unittest.TestCase):
    """`Recipe.yield_chance` is a list aligned to `outputs`, and the alignment is the point."""

    @staticmethod
    def _extract(doc):
        with tempfile.NamedTemporaryFile("w", suffix=".ndjson", delete=False) as fh:
            fh.write(json.dumps(doc) + "\n")
            path = fh.name
        try:
            return list(hei_dump.extract(path))
        finally:
            os.unlink(path)

    def test_a_certain_recipe_records_no_chances_at_all(self):
        # None rather than a list of ones, so `yield_of` short-circuits and a graph built from
        # a pre-#223 dump carries no extra list per recipe across 124,522 of them.
        got = self._extract({"cat": "c", "title": "t",
                             "in": [[{"i": "mod:a", "m": 0, "c": 1}]],
                             "out": [{"i": "mod:b", "m": 0, "c": 1}]})
        self.assertIsNone(got[0].yield_chance)

    def test_a_chance_output_records_the_chance_at_its_own_index(self):
        got = self._extract({"cat": "c", "title": "t",
                             "in": [[{"i": "mod:a", "m": 0, "c": 1}]],
                             "out": [{"i": "mod:b", "m": 0, "c": 1},
                                     {"i": "mod:d", "m": 0, "c": 4, "q": 0.25}]})
        recipe = got[0]
        self.assertEqual(recipe.yield_of(0), 1.0)
        self.assertEqual(recipe.yield_of(1), 0.25)

    def test_two_slots_of_one_key_keep_two_chances(self):
        # The reason `yield_chance` is a list and not a dict keyed by output key. 618 recipes
        # in the reference graph name one output key more than once -- TechReborn's Industrial
        # Grinder is the population, with four secondary slots of the same chunk -- and a
        # mapping would silently keep one of them.
        recipe = self._extract({"cat": "c", "title": "t",
                                "in": [[{"i": "mod:a", "m": 0, "c": 1}]],
                                "out": [{"i": "mod:b", "m": 0, "c": 10, "q": 0.5},
                                        {"i": "mod:b", "m": 0, "c": 4, "q": 0.25}]})[0]
        self.assertEqual([recipe.yield_of(0), recipe.yield_of(1)], [0.5, 0.25])
        self.assertEqual(recipe.expected_yield("mod:b"), 10 * 0.5 + 4 * 0.25)

    def test_a_fluid_output_can_carry_one_too(self):
        # The emitter does not write `q` onto `fout` yet. The reader accepts it anyway so that
        # an emitter which later learns to mark a chance fluid output needs no reader change
        # to be believed. Inert until then, because absent is 1.0.
        recipe = self._extract({"cat": "c", "title": "t",
                                "in": [[{"i": "mod:a", "m": 0, "c": 1}]],
                                "out": [{"i": "mod:b", "m": 0, "c": 1}],
                                "fout": [{"f": "lava", "a": 1000, "q": 0.2}]})[0]
        self.assertEqual(recipe.expected_yield("fluid:lava"), 200.0)

    def test_the_chances_survive_a_graph_round_trip(self):
        recipe = Recipe("r", "s", [("b", 10), ("c", 1)], [Ingredient(["a"], 1)],
                        yield_chance=[0.5, 1.0])
        back = Recipe.from_json(json.loads(json.dumps(recipe.to_json())))
        self.assertEqual(back.yield_of(0), 0.5)
        self.assertEqual(back.yield_of(1), 1.0)

    def test_a_certain_recipe_writes_no_q_into_the_graph(self):
        # Omitted at certainty, so a graph built from a schema-7 dump is byte-identical here
        # and the 123 MB file does not grow to say nothing.
        doc = Recipe("r", "s", [("b", 1)], [Ingredient(["a"], 1)]).to_json()
        self.assertNotIn("q", doc["out"][0])


class TheYieldIsTheDivisorTest(unittest.TestCase):
    """The arithmetic, asserted at both sites, because moving one alone is #29 again."""

    def test_a_tenth_chance_output_costs_ten_times_as_much_per_unit(self):
        # `_scaled_qty` is where the chance lands, so this is the whole cost-side claim in one
        # assertion: half the yield, twice the divisor.
        self.assertEqual(cost_mod._scaled_qty("minecraft:iron_ingot", 4, 0.5),
                         cost_mod._scaled_qty("minecraft:iron_ingot", 2))

    def test_a_zero_chance_output_does_not_divide_by_zero(self):
        # A recipe that never yields its output would otherwise manufacture an infinitely
        # cheap resource. The floor that already existed for sub-millibucket fluids catches
        # this by construction, which is why the chance belongs inside `_scaled_qty`.
        self.assertGreater(cost_mod._scaled_qty("minecraft:iron_ingot", 64, 0.0), 0.0)

    def test_the_default_chance_leaves_every_existing_price_where_it_was(self):
        for key, qty in (("minecraft:iron_ingot", 1), ("minecraft:iron_ingot", 64),
                         ("fluid:lava", 1000), ("fluid:lava", 1)):
            self.assertEqual(cost_mod._scaled_qty(key, qty, 1.0),
                             cost_mod._scaled_qty(key, qty),
                             "%s x%d must be untouched when nothing is uncertain" % (key, qty))

    def test_expected_yield_is_zero_for_a_key_the_recipe_does_not_make(self):
        # Callers must handle this rather than dividing by it. `Solver._build` does, and says
        # so; this pins the contract that makes its guard meaningful.
        recipe = Recipe("r", "s", [("b", 1)], [Ingredient(["a"], 1)])
        self.assertEqual(recipe.expected_yield("nothing:like_it"), 0.0)


class ACertainRecipePlansExactlyAsItAlwaysDidTest(unittest.TestCase):
    """The regression guard for the port of `runs` from integer to float arithmetic.

    Every graph built before schema 8 has a chance of 1.0 everywhere, so #223 must be a no-op
    on all of them. It nearly was not: `math.ceil(remainder / per_run)` is not the same
    function as `-(-remainder // per_run)` when the quotient lands one bit above an exact
    integer, and the difference is one extra run on a recipe this issue does not touch.
    """

    def test_the_two_ceilings_agree_on_every_certain_case_in_a_wide_sweep(self):
        import math
        for nominal in range(1, 65):
            for remainder in range(1, 400):
                self.assertEqual(-(-remainder // nominal),
                                 math.ceil(remainder / float(nominal)),
                                 "runs must not move for a certain recipe: %d / %d"
                                 % (remainder, nominal))

    def test_and_the_float_path_is_only_taken_when_something_is_uncertain(self):
        # The condition that routes between them, asserted directly: expected equals nominal
        # exactly when nothing is uncertain, because the multiply is by exactly 1.0.
        certain = Recipe("r", "s", [("mod:b", 7), ("mod:b", 5)], [Ingredient(["mod:a"], 1)])
        self.assertEqual(certain.expected_yield("mod:b"), 12)
        uncertain = Recipe("r", "s", [("mod:b", 7), ("mod:b", 5)], [Ingredient(["mod:a"], 1)],
                           yield_chance=[1.0, 0.5])
        self.assertNotEqual(uncertain.expected_yield("mod:b"), 12)
