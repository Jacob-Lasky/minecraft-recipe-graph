"""Everything the python side learned from dump schema 6: a dump that declares itself. #194

WHAT THE DEFECT WAS, because it decides what is worth asserting here. A dump could lose
forty thousand display names and say nothing: `getDisplayName()` throwing was caught, the
count discarded, and names.json simply came out shorter. No downstream assertion could have
caught it either, because the length names.json SHOULD have had was not recorded anywhere.
Success and failure produced artifacts identical in form.

So the two things under test are not the parse. They are (1) that a schema-5 dump reads as
"cannot say" rather than as "nothing wrong" -- the absence must not be rounded down to zero
-- and (2) that the mismatch, when it exists, stops the build rather than scrolling past in
it. Every case below is one of those two.
"""

import contextlib
import io
import json
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cli, gaps, index  # noqa: E402
from recipegraph import model  # noqa: E402
from recipegraph.model import Graph  # noqa: E402
from recipegraph.sources import dump_meta, dump_names  # noqa: E402


def _dump(root, summary, names=None):
    """A dump directory holding a summary.json and, optionally, a names.json."""
    os.makedirs(root, exist_ok=True)
    with open(os.path.join(root, "summary.json"), "w") as fh:
        json.dump(summary, fh)
    if names is not None:
        with open(os.path.join(root, "names.json"), "w") as fh:
            json.dump(names, fh)
    return root


class SchemaStampTest(unittest.TestCase):
    """The literal, and it is a tripwire rather than a restatement.

    Moving `dump_meta.SCHEMA` has to be a decision: the number tells a reader whether their
    own recomputation still agrees with the dump, so a bump that arrives as a side effect of
    an unrelated edit is a lie told to every downstream consumer. `tests/test_catalysts.py`
    pins it against `DumpCommand.java`; this pins that either moving alone was intentional.
    """

    def test_the_python_side_expects_six(self):
        self.assertEqual(dump_meta.SCHEMA, 6)


class CountsAreReadAsDeclaredTest(unittest.TestCase):

    def test_a_schema_six_dump_reports_both_counts(self):
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 6, "mod_version": "0.9.11",
                                               "names": 41, "names_failed": 3}))
        self.assertEqual(meta["names"], 41)
        self.assertEqual(meta["names_failed"], 3)

    def test_a_clean_schema_six_dump_reports_zero_not_none(self):
        """Zero is a measurement. It is the whole difference from a schema-5 dump."""
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 6, "names": 41,
                                               "names_failed": 0}))
        self.assertEqual(meta["names_failed"], 0)
        self.assertIsNotNone(meta["names_failed"])

    def test_a_schema_five_dump_reports_none_not_zero(self):
        """A dump that never counted must not read as a dump that counted and found none.

        This is the case every dump on disk today is in, so getting it wrong would hand a
        clean bill of health to precisely the artifacts #194 was filed about.
        """
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 5, "mod_version": "0.9.11"}))
        self.assertIsNone(meta["names"])
        self.assertIsNone(meta["names_failed"])

    def test_a_malformed_count_is_no_count(self):
        """A string, a negative or a bool is not a measurement, and must not become one.

        `true` matters specifically: `isinstance(True, int)` is True in python, so a naive
        int check turns a malformed field into a count of one.
        """
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 6, "names": "lots",
                                               "names_failed": True}))
        self.assertIsNone(meta["names"])
        self.assertIsNone(meta["names_failed"])
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 6, "names_failed": -1}))
        self.assertIsNone(meta["names_failed"])


class LostNamesRideInTheProvenanceLineTest(unittest.TestCase):

    def test_the_line_says_how_many_names_were_lost(self):
        said = dump_meta.describe({"present": True, "mod_version": "0.9.11",
                                   "schema": 6, "names_failed": 3})
        self.assertIn("could NOT be named", said)
        self.assertIn("3 items in it", said)

    def test_a_clean_dump_says_nothing_about_lost_names(self):
        said = dump_meta.describe({"present": True, "mod_version": "0.9.11",
                                   "schema": 6, "names_failed": 0})
        self.assertEqual(said, "dump: written by mod 0.9.11, schema 6")

    def test_one_lost_name_is_not_pluralised(self):
        said = dump_meta.describe({"present": True, "mod_version": "0.9.11",
                                   "schema": 6, "names_failed": 1})
        self.assertIn("1 item in it could NOT be named", said)
        self.assertIn("shows as raw ids", said)

    def test_a_graph_says_it_too_long_after_the_dump_is_gone(self):
        """The server has a graph.json and no dump directory, which is the point of #194.

        A loss reported only by the build that noticed it is a loss nobody sees twice, and
        the build that noticed it may have been weeks ago on another machine.
        """
        g = Graph()
        g.dump_schema = 6
        g.dump_version = "0.9.11"
        g.dump_names_failed = 12
        self.assertIn("could NOT be named", dump_meta.describe(dump_meta.of_graph(g)))

    def test_a_schema_six_dump_with_no_usable_failure_count_claims_nothing(self):
        """A malformed `names_failed` must not become a claim in either direction."""
        said = dump_meta.describe({"present": True, "mod_version": "0.10.0",
                                   "schema": 6, "names_failed": None})
        self.assertEqual(said, "dump: written by mod 0.10.0, schema 6")

    def test_a_pre_194_graph_claims_nothing(self):
        g = Graph()
        g.dump_schema = 5
        g.dump_version = "0.9.11"
        said = dump_meta.describe(dump_meta.of_graph(g))
        self.assertNotIn("could NOT be named", said)
        self.assertIn("newer fields", said)


class TheGraphCarriesTheLossTest(unittest.TestCase):

    def test_zero_survives_a_round_trip_as_zero(self):
        """`or None` here would turn every clean graph back into "cannot say"."""
        g = Graph()
        g.dump_names_failed = 0
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            g.save(path)
            back = Graph.load(path)
        self.assertEqual(back.dump_names_failed, 0)

    def test_a_count_survives_a_round_trip(self):
        g = Graph()
        g.dump_names_failed = 12
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            g.save(path)
            back = Graph.load(path)
        self.assertEqual(back.dump_names_failed, 12)

    def test_a_graph_written_before_194_loads_as_unknown(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            with open(path, "w") as fh:
                json.dump({"recipes": [], "names": {}, "dump_schema": 5}, fh)
            self.assertIsNone(Graph.load(path).dump_names_failed)


class TheRawCountIsNotTheCleanedCountTest(unittest.TestCase):

    def test_a_format_only_label_is_dropped_from_the_map_and_kept_in_the_count(self):
        """The reason `load_with_count` exists at all.

        `clean_label` legitimately drops a name that was only formatting codes, so the map
        is shorter than the file on a perfectly healthy dump. Comparing summary.json's
        declared count against the MAP would report a truncated file every time, and a check
        that fires on healthy input is a check that gets switched off.
        """
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "names.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"mod:a": "Anvil", "mod:b": "§r"}, fh)
            names, raw = dump_names.load_with_count(path)
        self.assertEqual(names, {"mod:a": "Anvil"})
        self.assertEqual(raw, 2)

    def test_no_file_counts_to_none_rather_than_zero(self):
        names, raw = dump_names.load_with_count("/nonexistent/names.json")
        self.assertEqual(names, {})
        self.assertIsNone(raw)

    def test_load_still_returns_just_the_map(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "names.json")
            with open(path, "w") as fh:
                json.dump({"mod:a": "Anvil"}, fh)
            self.assertEqual(dump_names.load(path), {"mod:a": "Anvil"})


class ATruncatedNamesFileIsRefusedTest(unittest.TestCase):

    def test_a_short_names_file_raises(self):
        meta = {"schema": 6, "names": 41, "names_failed": 0}
        with self.assertRaises(dump_meta.DamagedDump) as caught:
            dump_meta.check_names(meta, 12)
        said = str(caught.exception)
        self.assertIn("12", said)
        self.assertIn("41", said)

    def test_a_long_names_file_raises_too(self):
        """More entries than declared is the same evidence: not the file the dump wrote."""
        with self.assertRaises(dump_meta.DamagedDump):
            dump_meta.check_names({"schema": 6, "names": 41}, 900)

    def test_a_matching_count_passes(self):
        self.assertIsNone(dump_meta.check_names({"schema": 6, "names": 41}, 41))

    def test_a_schema_five_dump_is_not_accused(self):
        """Nothing to compare is not evidence of damage, and every dump on disk is here."""
        self.assertIsNone(dump_meta.check_names({"schema": 5, "names": None}, 12))

    def test_a_missing_names_file_is_not_accused(self):
        """Deleting names.json is the documented way to build without a damaged one."""
        self.assertIsNone(dump_meta.check_names({"schema": 6, "names": 41}, None))

    def test_the_build_refuses_rather_than_reporting(self):
        """End to end through `index.build`, because the guard's value is where it sits.

        A verdict returned to a caller that prints it is the arrangement this project keeps
        finding does not work -- the line scrolls past and the run ends in a success
        message. The assertion is that `build` does not return.
        """
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"),
                  {"schema": 6, "mod_version": "0.9.11", "names": 41, "names_failed": 0},
                  names={"mod:a": "Anvil"})
            with self.assertRaises(dump_meta.DamagedDump):
                index.build(inst, quiet=True)

    def test_an_intact_dump_builds(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"),
                  {"schema": 6, "mod_version": "0.9.11", "names": 1, "names_failed": 2},
                  names={"mod:a": "Anvil"})
            g = index.build(inst, quiet=True)
        self.assertEqual(g.names.get("mod:a"), "Anvil")
        self.assertEqual(g.dump_names_failed, 2)

    def test_not_one_label_is_merged_before_the_refusal(self):
        """The check runs BEFORE the merge, and the build's own log is the evidence.

        Asserting only that `build` raises would pass just as well if it raised AFTER
        merging every label from a file it had already decided not to trust. `build`
        announces the merge on the line beginning `names: +`, so the absence of that line is
        an observable statement about the ORDER, which is the thing this pins.
        """
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"),
                  {"schema": 6, "mod_version": "0.9.11", "names": 99, "names_failed": 0},
                  names={"mod:a": "Anvil"})
            said = io.StringIO()
            with contextlib.redirect_stderr(said):
                with self.assertRaises(dump_meta.DamagedDump):
                    index.build(inst)
        self.assertIn("dump: written by mod", said.getvalue())
        self.assertNotIn("names: +", said.getvalue())


class OneNameForSummaryJsonTest(unittest.TestCase):
    """`gaps.load` spelled `summary.json` itself, so two modules owned the same filename.

    NOT AN EXCEPTION IF THEY DRIFT, which is why it is worth a test rather than a glance:
    `gaps.load` checks `os.path.exists` and returns `{}` when the file is not where it
    looked, and an empty summary is exactly what a legitimately old dump produces. A
    renamed file would read as a dump that predates schema 2, silently.

    `gaps.load` had no test at all before this. It has one now because #194 touched it.
    """

    def test_gaps_reads_the_summary_the_reader_names(self):
        with tempfile.TemporaryDirectory() as root:
            _dump(root, {"schema": 6, "recipes": 1663, "names_failed": 3})
            with open(os.path.join(root, "skipped.ndjson"), "w") as fh:
                fh.write('{"uid":"minecraft.crafting","why":"threw"}\n\n')
            summary, skips = gaps.load(root)
        self.assertEqual(summary["recipes"], 1663)
        self.assertEqual(skips, [{"uid": "minecraft.crafting", "why": "threw"}])

    def test_gaps_and_dump_meta_open_the_same_path(self):
        with tempfile.TemporaryDirectory() as root:
            _dump(root, {"schema": 6, "recipes": 7})
            self.assertEqual(gaps.load(root)[0]["recipes"], 7)
            self.assertEqual(dump_meta.read(root)["schema"], 6)

    def test_an_empty_directory_reads_as_an_old_dump_rather_than_raising(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(gaps.load(root), ({}, []))


class BothRefusalsReachTheExitCodeTest(unittest.TestCase):
    """`RefusedBuild` exists so one catch covers both; only one of them was exercised."""

    def test_a_damaged_names_file_also_exits_two(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"),
                  {"schema": 6, "mod_version": "0.10.0", "names": 99, "names_failed": 0},
                  names={"mod:a": "Anvil"})
            out = os.path.join(inst, "graph.json")
            said = io.StringIO()
            with contextlib.redirect_stderr(said):
                code = cli.main(["build", "--instance", inst, "--out", out])
        self.assertEqual(code, 2)
        self.assertIn("names.json holds 1 entries", said.getvalue())
        self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
