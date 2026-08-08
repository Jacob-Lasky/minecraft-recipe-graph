"""Everything the python side learned from dump schema 7: which inputs a run does not spend.

#175. Schema 6 and earlier had no field for consumption, so every input read as spent. The
reader half (`Ingredient.consume_chance`, `hei_dump._consume_chance`, the cost and solver
handling) landed long before any dump wrote a `p`, and `tests/test_consumption.py` covers it
thoroughly. What is new at 7 is that a dump can now CARRY one, so this file is about the
number and the boundary rather than about the arithmetic.

THE ONLY BUMP SO FAR WHOSE COST IS A WRONG PRICE RATHER THAN A MISPARSE. Every earlier schema
either moved a file's shape or changed the meaning of `n`, and both are things a reader can
detect for itself. A schema-6 dump parses perfectly under this reader and produces a graph
that charges an Ingot Cast into all 14,409 recipes that use one. Nothing about its shape says
so, which is exactly why the number has to.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph.sources import dump_meta, hei_dump  # noqa: E402


class SchemaStampTest(unittest.TestCase):
    """The literal, and it is a tripwire rather than a restatement.

    Moving `dump_meta.SCHEMA` has to be a decision: the number tells a reader whether its own
    recomputation still agrees with the dump, so a bump arriving as a side effect of an
    unrelated edit is a lie told to every downstream consumer. `tests/test_catalysts.py` pins
    it against `DumpCommand.java`, so the two languages cannot drift; this pins that either
    moving alone was intentional.

    INHERITED FROM `test_schema_six.py`, which inherited it from `test_schema_five.py`. The
    newest schema's file owns this assertion. When 8 arrives, move it again.
    """

    def test_the_python_side_expects_seven(self):
        self.assertEqual(dump_meta.SCHEMA, 7)


class TheCurrentSchemaReadsCleanTest(unittest.TestCase):

    def test_a_seven_dump_is_reported_as_current(self):
        said = dump_meta.describe({"present": True, "mod_version": "0.10.0",
                                   "schema": 7, "mod_count": 367})
        self.assertIn("schema 7", said)
        self.assertNotIn("newer fields", said)

    def test_an_eight_dump_is_reported_as_newer_than_this_reader(self):
        # The direction that matters most for a field like `p`: a dump that knows something
        # this reader does not must say so rather than being read as merely fine.
        said = dump_meta.describe({"present": True, "mod_version": "9.9.9", "schema": 8})
        self.assertIn("NEWER", said)


class ABackLevelDumpIsPricedWrongAndNotMisparsedTest(unittest.TestCase):
    """The distinction the bump exists to record, asserted rather than only written down."""

    def test_a_schema_six_line_still_parses_identically(self):
        # `p` is omitted wherever it would be 1.0, so a schema-6 line and a schema-7
        # non-catalyst line are the same bytes and must read the same way.
        self.assertEqual(hei_dump._consume_chance({"i": "minecraft:iron_ingot", "m": 0}), 1.0)

    def test_and_a_schema_seven_catalyst_line_reads_as_permanent(self):
        self.assertEqual(
            hei_dump._consume_chance({"i": "tconstruct:cast_custom", "m": 0, "p": 0.0}), 0.0)

    def test_the_two_are_distinguishable_which_is_the_whole_point(self):
        # If these ever compare equal the field has stopped carrying information and the
        # schema bump bought nothing.
        absent = hei_dump._consume_chance({"i": "tconstruct:cast_custom", "m": 0})
        marked = hei_dump._consume_chance({"i": "tconstruct:cast_custom", "m": 0, "p": 0.0})
        self.assertNotEqual(absent, marked)


class TheEmitterWritesWhatTheReaderExpectsTest(unittest.TestCase):
    """The cross-language half, read out of the Java source rather than out of a jar.

    `tests/test_catalysts.py` already pins the two SCHEMA numbers together. This pins the
    FIELD, because a bump whose emitter writes `chance` while the reader looks for `p` would
    satisfy every number check in the repository and produce a dump in which nothing is ever
    a catalyst -- silently, and indistinguishably from a pack that has none.
    """

    @staticmethod
    def _dump_command():
        here = os.path.dirname(os.path.abspath(__file__))
        path = os.path.join(here, os.pardir, "mod", "src", "main", "java", "io", "github",
                            "jacoblasky", "recipedump", "DumpCommand.java")
        with open(path, encoding="utf-8") as fh:
            return fh.read()

    def test_the_java_emitter_spells_the_field_p(self):
        self.assertIn('",\\"p\\":"', self._dump_command(),
                      "the emitter must write the same key `hei_dump._consume_chance` reads")

    def test_the_emitter_omits_the_field_at_total_consumption(self):
        # Not cosmetic. 335,000 recipe lines carrying a constant field is the size cost, and
        # the omission is what makes a schema-7 dump byte-identical to a schema-6 one
        # wherever nothing is a catalyst -- so a diff between two dumps shows exactly the
        # recipes this issue changed and nothing else.
        self.assertIn("chance == 1.0f ? \"\"", self._dump_command())

    def test_both_catalyst_sources_are_reachable_from_the_encoder(self):
        # A bridge nothing calls is the seam defect `SeamInstallationTest` exists for, one
        # layer down: both of these are pure static readers, so nothing would be null and
        # nothing would log if the call site were dropped.
        src = self._dump_command()
        self.assertIn("TinkersCastingBridge.itemInputChance", src)
        self.assertIn("ModularMachineryBridge.itemInputChances", src)

    def test_the_chance_never_reaches_a_fluid_slot(self):
        # The over-correction guard, asserted at the seam rather than trusted to a comment.
        # Every catalyst source here describes something that SITS in the machine; the fluid
        # is the material being worked, and marking it permanent would make its recipe free.
        src = self._dump_command()
        self.assertIn("fluidSlots(collected.rawInputs(FluidStack.class))", src,
                      "fluidSlots must keep its single-argument form: giving it a chance "
                      "array is how molten metal becomes free")


class TheCatalystCountIsReadBackTest(unittest.TestCase):
    """A field nothing reads is a seam nothing calls, one file over."""

    @staticmethod
    def _dump(root, doc):
        import json
        d = os.path.join(root, dump_meta.DIR_NAME)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, dump_meta.SUMMARY_NAME), "w", encoding="utf-8") as fh:
            json.dump(doc, fh)
        return d

    def test_a_seven_dump_reports_what_it_counted(self):
        import tempfile
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(self._dump(root, {"schema": 7, "catalyst_slots": 14354}))
            self.assertEqual(meta["catalyst_slots"], 14354)

    def test_zero_is_kept_as_zero_and_not_flattened_to_absent(self):
        # The whole reason the field is written unconditionally. A schema-7 dump saying zero
        # has looked; conflating it with None would throw away the only signal that a bridge
        # stopped resolving.
        import tempfile
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(self._dump(root, {"schema": 7, "catalyst_slots": 0}))
            self.assertEqual(meta["catalyst_slots"], 0)
            self.assertIsNotNone(meta["catalyst_slots"])

    def test_a_pre_seven_dump_reports_none_rather_than_zero(self):
        import tempfile
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(self._dump(root, {"schema": 6}))
            self.assertIsNone(meta["catalyst_slots"],
                              "a dump that could not look must not claim it found nothing")
