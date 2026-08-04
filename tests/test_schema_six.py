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


#: Stands in for "the whole pack" wherever a test needs a jar set that is not the six-mod dev
#: one. 367 is the MEASURED count of top-level jars in the pack's `mods/` (#119, #208); the
#: loaded-mod count `mod_count` actually holds has never been read off an FML log, so no test
#: here writes one down as though it had. Nothing asserts on the value, only that it differs
#: from the small set -- the older "410" this file used to carry was a figure that matched
#: nothing, which is the whole reason #208 pinned a denominator.
FULL_PACK = 367


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
                                   "schema": 6, "names_failed": 0,
                                   "mod_count": FULL_PACK})
        self.assertEqual(said, "dump: written by mod 0.9.11, schema 6, from %d mods"
                         % FULL_PACK)

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
        said = dump_meta.describe({"present": True, "mod_version": "0.10.0", "schema": 6,
                                   "names_failed": None, "mod_count": 7})
        self.assertEqual(said, "dump: written by mod 0.10.0, schema 6, from 7 mods")

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


class TheDumpSaysWhichJarsItSawTest(unittest.TestCase):

    def test_the_counts_are_read(self):
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 6, "mod_count": FULL_PACK,
                                               "mod_digest": "a1b2c3d4e5f6"}))
        self.assertEqual(meta["mod_count"], FULL_PACK)
        self.assertEqual(meta["mod_digest"], "a1b2c3d4e5f6")

    def test_a_schema_five_dump_declares_no_jar_set(self):
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 5, "mod_version": "0.9.11"}))
        self.assertIsNone(meta["mod_count"])
        self.assertIsNone(meta["mod_digest"])

    def test_an_empty_digest_is_no_digest(self):
        """`""` would compare equal to nothing and unequal to everything real."""
        with tempfile.TemporaryDirectory() as root:
            meta = dump_meta.read(_dump(root, {"schema": 6, "mod_digest": ""}))
        self.assertIsNone(meta["mod_digest"])

    def test_the_provenance_line_names_the_jar_count(self):
        """The sentence #194 exists to change.

        `dump: written by mod 0.9.11, schema 6` was IDENTICAL IN FORM whether five jars or
        the whole pack produced the dump, and the contents could not settle it either. This
        is the word that separates them, and it is in the line every surface already prints.
        """
        small = dump_meta.describe({"present": True, "mod_version": "0.9.11", "schema": 6,
                                    "mod_count": 6, "names_failed": 0})
        big = dump_meta.describe({"present": True, "mod_version": "0.9.11", "schema": 6,
                                  "mod_count": FULL_PACK, "names_failed": 0})
        self.assertIn("from 6 mods", small)
        self.assertIn("from %d mods" % FULL_PACK, big)
        self.assertNotEqual(small, big)

    def test_a_graph_still_names_its_pack_after_the_dump_is_gone(self):
        g = Graph()
        g.dump_schema = 6
        g.dump_version = "0.9.11"
        g.dump_mod_count = FULL_PACK
        g.dump_mod_digest = "a1b2c3d4e5f6"
        self.assertIn("from %d mods" % FULL_PACK,
                      dump_meta.describe(dump_meta.of_graph(g)))

    def test_the_jar_set_survives_a_graph_round_trip(self):
        g = Graph()
        g.dump_mod_count = FULL_PACK
        g.dump_mod_digest = "a1b2c3d4e5f6"
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            g.save(path)
            back = Graph.load(path)
            self.assertEqual(Graph.recorded_mod_set(path), (FULL_PACK, "a1b2c3d4e5f6"))
        self.assertEqual(back.dump_mod_count, FULL_PACK)
        self.assertEqual(back.dump_mod_digest, "a1b2c3d4e5f6")

    def test_a_pre_194_graph_records_no_jar_set(self):
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            with open(path, "w") as fh:
                json.dump({"recipes": [], "names": {}, "dump_schema": 5}, fh)
            self.assertEqual(Graph.recorded_mod_set(path), (None, None))

    def test_the_header_scan_falls_back_rather_than_reporting_an_absence(self):
        """A graph whose fields sit past `HEADER_BYTES` must not read as recording nothing.

        The prefix scan is a speed optimisation over `save`'s `sort_keys=True` ordering. If
        it ever silently returned (None, None) for a graph that DOES record a jar set, the
        guard would switch itself off on exactly the large graphs it exists to protect --
        which is the failure mode this project keeps finding, one level up.
        """
        g = Graph()
        g.dump_mod_count = FULL_PACK
        g.dump_mod_digest = "a1b2c3d4e5f6"
        # `catalysts` sorts before `dump_mod_*`, so padding it pushes them past the window.
        g.catalysts = {"pad%06d" % i: ["x" * 40] for i in range(24000)}
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            g.save(path)
            self.assertGreater(os.path.getsize(path), Graph.HEADER_BYTES)
            self.assertEqual(Graph.recorded_mod_set(path), (FULL_PACK, "a1b2c3d4e5f6"))


class AWrongPackIsRefusedTest(unittest.TestCase):

    SMALL = {"schema": 6, "mod_version": "0.9.11", "mod_count": 6, "mod_digest": "aaaa"}
    BIG = {"schema": 6, "mod_version": "0.9.11", "mod_count": FULL_PACK,
           "mod_digest": "bbbb"}

    def _graph(self, root, summary):
        path = os.path.join(root, "graph.json")
        g = Graph()
        g.dump_mod_count = summary["mod_count"]
        g.dump_mod_digest = summary["mod_digest"]
        g.save(path)
        return path

    def test_a_six_jar_dump_may_not_replace_a_full_pack_graph(self):
        with self.assertRaises(dump_meta.WrongPack) as caught:
            dump_meta.check_mod_set(self.SMALL, FULL_PACK, "bbbb")
        said = str(caught.exception)
        self.assertIn("%d mods" % FULL_PACK, said)
        self.assertIn("6 mods", said)
        self.assertIn("--allow-mod-set-change", said)

    def test_the_same_jar_set_passes(self):
        self.assertIsNone(dump_meta.check_mod_set(self.BIG, FULL_PACK, "bbbb"))

    def test_a_dump_that_cannot_say_is_not_accused(self):
        self.assertIsNone(dump_meta.check_mod_set({"mod_digest": None}, FULL_PACK, "bbbb"))

    def test_a_graph_that_cannot_say_is_not_accused(self):
        """Every graph on disk today, so refusing here would refuse the first build."""
        self.assertIsNone(dump_meta.check_mod_set(self.SMALL, None, None))

    def test_the_build_refuses_end_to_end(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), self.SMALL)
            out = self._graph(inst, self.BIG)
            with self.assertRaises(dump_meta.WrongPack):
                index.build(inst, quiet=True, out_path=out)

    def test_the_flag_lets_it_through(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), self.SMALL)
            out = self._graph(inst, self.BIG)
            g = index.build(inst, quiet=True, out_path=out, allow_mod_set_change=True)
        self.assertEqual(g.dump_mod_count, 6)

    def test_the_refusal_lands_before_the_graph_is_touched(self):
        """The guard is worth nothing if it fires after the file has been rewritten."""
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), self.SMALL)
            out = self._graph(inst, self.BIG)
            with open(out) as fh:
                before = fh.read()
            with self.assertRaises(dump_meta.WrongPack):
                index.build(inst, quiet=True, out_path=out)
            with open(out) as fh:
                self.assertEqual(fh.read(), before)

    def test_the_first_build_at_a_path_is_never_refused(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), self.SMALL)
            g = index.build(inst, quiet=True,
                            out_path=os.path.join(inst, "nothing-here.json"))
        self.assertEqual(g.dump_mod_digest, "aaaa")

    def test_the_cli_turns_the_refusal_into_an_exit_code(self):
        """A traceback is a stack, not an instruction, and the exit code is the artifact."""
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), self.SMALL)
            out = self._graph(inst, self.BIG)
            said = io.StringIO()
            with contextlib.redirect_stderr(said):
                code = cli.main(["build", "--instance", inst, "--out", out])
            self.assertEqual(code, 2)
            self.assertIn("refusing to build", said.getvalue())
            self.assertEqual(Graph.recorded_mod_set(out), (FULL_PACK, "bbbb"))


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


class TheUnrecordedCasesStillReadTest(unittest.TestCase):
    """Branches reachable only from a summary or graph that is malformed rather than old.

    Our own writer emits `mod_count` and `mod_digest` together or not at all, so a digest
    with no count beside it cannot come from `writeSummary`. It can come from a hand edit,
    and this whole change exists because artifacts get hand-edited, truncated and
    half-copied. The branch must not print `-1 mods`.
    """

    def test_a_graph_with_a_digest_but_no_count_is_described_honestly(self):
        with self.assertRaises(dump_meta.WrongPack) as caught:
            dump_meta.check_mod_set({"mod_digest": "aaaa", "mod_count": 6}, None, "bbbb")
        self.assertIn("an unrecorded number of mods there", str(caught.exception))

    def test_a_dump_with_a_digest_but_no_count_is_described_honestly(self):
        with self.assertRaises(dump_meta.WrongPack) as caught:
            dump_meta.check_mod_set({"mod_digest": "aaaa"}, FULL_PACK, "bbbb")
        self.assertIn("an unrecorded number of mods in this dump",
                      str(caught.exception))

    def test_a_pre_194_graph_is_answered_from_the_prefix_and_not_parsed_whole(self):
        """Every graph on disk the day this lands is in this case.

        Without it the fallback fires on all of them and pays a full 121 MB parse on every
        build until the graph is rebuilt. `json.load` is sabotaged rather than timed, so the
        assertion is "the slow path was not taken" rather than "it was quick today".
        """
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            with open(path, "w") as fh:
                json.dump({"dump_schema": 5, "names": {}, "recipes": []}, fh,
                          sort_keys=True, separators=(",", ":"))
            real = model.json.load
            model.json.load = lambda *a, **k: self.fail("parsed the whole graph")
            try:
                self.assertEqual(Graph.recorded_mod_set(path), (None, None))
            finally:
                model.json.load = real

    def test_the_sentinel_really_does_sort_after_the_pair(self):
        """The one assumption the fast absence answer rests on, pinned against `save`.

        If `to_json` ever renames a field such that `_PAST_MOD_SET` no longer follows the
        pair, the absence conclusion becomes a guess -- and a wrong one is a guard that has
        switched itself off. This fails the moment that ordering stops holding.
        """
        g = Graph()
        g.dump_mod_count, g.dump_mod_digest = FULL_PACK, "a1b2c3d4e5f6"
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            g.save(path)
            with open(path) as fh:
                text = fh.read()
        self.assertLess(text.index('"dump_mod_count"'), text.index(Graph._PAST_MOD_SET))
        self.assertLess(text.index('"dump_mod_digest"'), text.index(Graph._PAST_MOD_SET))

    def test_explicit_nulls_in_a_graph_read_as_unrecorded(self):
        """`save` writes `null` for an unrecorded jar set; the prefix scan must accept it."""
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "graph.json")
            Graph().save(path)
            with open(path) as fh:
                self.assertIn('"dump_mod_digest":null', fh.read())
            self.assertEqual(Graph.recorded_mod_set(path), (None, None))


class ASkippedCheckDoesNotLookLikeAPassedOneTest(unittest.TestCase):
    """The guard's own version of the defect it was written to fix.

    `check_mod_set` cannot compare a dump from before schema 6, or a graph from before #194,
    and must not block on either -- refusing would refuse the first build after this lands.
    But returning silently for BOTH "the jar sets match" and "I could not compare them"
    makes a check that ran and a check that was skipped identical from the outside, which is
    exactly the shape #194 is about. Each of these asserts the line, not just the outcome.
    """

    def _build(self, summary, graph=None, truncated=False):
        """`build`'s stderr, with the temp path replaced by a fixed token.

        THE PATH IS SUBSTITUTED OUT ON THE WAY BACK, and without that the
        distinguishability assertion below cannot fail: three of these lines name `out_path`,
        every case gets a fresh `TemporaryDirectory`, so comparing the raw strings would find
        five different lines even if all five carried identical wording. Normalising the one
        part that varies for reasons other than wording is what leaves the assertion able to
        catch what it is for.
        """
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), summary)
            out = os.path.join(inst, "graph.json")
            if truncated:
                with open(out, "w") as fh:
                    fh.write('{"catalysts":{},"dump_mod_c')
            elif graph is not None:
                g = Graph()
                g.dump_mod_count, g.dump_mod_digest = graph
                g.save(out)
            said = io.StringIO()
            with contextlib.redirect_stderr(said):
                # Kept so a caller can assert the build actually produced a graph rather
                # than only that it printed the right line.
                self.built = index.build(inst, out_path=out)
            return said.getvalue().replace(out, "<out>")

    SIX = {"schema": 6, "mod_version": "0.10.0", "mod_count": 6, "mod_digest": "aaaa"}
    FIVE = {"schema": 5, "mod_version": "0.9.11"}

    def test_a_matching_jar_set_says_it_checked(self):
        said = self._build(self.SIX, graph=(6, "aaaa"))
        self.assertIn("mod set: 6 mods, matching the graph being replaced", said)
        self.assertNotIn("NOT CHECKED", said)

    def test_a_dump_too_old_to_compare_says_it_was_not_checked(self):
        said = self._build(self.FIVE, graph=(FULL_PACK, "bbbb"))
        self.assertIn("NOT CHECKED", said)
        self.assertIn("predates schema 6", said)

    def test_a_graph_too_old_to_compare_says_it_was_not_checked(self):
        said = self._build(self.SIX, graph=(None, None))
        self.assertIn("NOT CHECKED", said)
        self.assertIn("predates #194", said)

    def test_a_first_build_says_there_was_nothing_to_disagree_with(self):
        said = self._build(self.SIX)
        self.assertIn("no graph to disagree with", said)
        self.assertNotIn("NOT CHECKED", said)

    def test_a_graph_too_damaged_to_read_does_not_take_the_build_down_with_it(self):
        """An interrupted save must not lock someone out of the command that repairs it.

        `recorded_mod_set` falls through to a full `json.load` when its prefix scan finds
        neither the pair nor the sentinel, so a truncated graph.json arrives as a ValueError.
        Letting it out would replace a working build with a traceback, and the file it names
        is the one this build was about to rewrite anyway. `DumpCommand.readModSet` makes the
        same call about an unreadable summary.json.
        """
        said = self._build(self.SIX, truncated=True)
        self.assertIn("NOT CHECKED", said)
        self.assertIn("could not be read", said)
        # And it BUILT. Asserting only the line would pass if the guard reported and then let
        # the ValueError out of the next frame.
        self.assertEqual(self.built.dump_mod_digest, "aaaa")

    def test_the_override_says_what_it_let_through(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"), self.SIX)
            out = os.path.join(inst, "graph.json")
            g = Graph()
            g.dump_mod_count, g.dump_mod_digest = FULL_PACK, "bbbb"
            g.save(out)
            said = io.StringIO()
            with contextlib.redirect_stderr(said):
                index.build(inst, out_path=out, allow_mod_set_change=True)
        self.assertIn("--allow-mod-set-change", said.getvalue())
        self.assertIn("%d mods there, 6 mods in this dump" % FULL_PACK, said.getvalue())

    def test_every_outcome_is_distinguishable_from_every_other(self):
        """Whatever the wording, no two of them may read the same.

        The count is not written down: the assertion is that the rendered lines are as many
        as the cases, so an outcome added later that borrows a neighbour's wording fails here
        rather than passing quietly.
        """
        lines = {
            "matched": self._build(self.SIX, graph=(6, "aaaa")),
            "dump too old": self._build(self.FIVE, graph=(FULL_PACK, "bbbb")),
            "graph too old": self._build(self.SIX, graph=(None, None)),
            "nothing there": self._build(self.SIX),
            "graph damaged": self._build(self.SIX, truncated=True),
        }
        said = {k: [ln for ln in v.splitlines() if ln.startswith("mod set:")]
                for k, v in lines.items()}
        for name, got in said.items():
            self.assertEqual(len(got), 1, "%s produced %r" % (name, got))
        rendered = [v[0] for v in said.values()]
        self.assertEqual(len(set(rendered)), len(lines), rendered)
        # And the token really was substituted, so the assertion above compared WORDING and
        # not five different temp paths. Without this the normalisation could silently stop
        # matching and the test would go back to being one that cannot fail.
        for name, got in said.items():
            self.assertNotIn(tempfile.gettempdir(), got[0], name)


class TheOverrideFlagIsSpelledOnceTest(unittest.TestCase):
    """The refusal names a flag; the parser has to accept the one it names.

    Three places say it -- the constant, the message, and the README -- and only two of them
    are code. `tests/test_dist_jar.py` already guards a README claim for exactly this reason:
    a wrong instruction in prose is worse than none, because the reader tries it, it fails,
    and they conclude the refusal has no override at all.
    """

    def test_the_parser_accepts_the_flag_the_refusal_names(self):
        with tempfile.TemporaryDirectory() as inst:
            _dump(os.path.join(inst, "mc-recipe-dump"),
                  {"schema": 6, "mod_version": "0.10.0", "mod_count": 6,
                   "mod_digest": "aaaa"})
            out = os.path.join(inst, "graph.json")
            g = Graph()
            g.dump_mod_count, g.dump_mod_digest = FULL_PACK, "bbbb"
            g.save(out)
            said = io.StringIO()
            with contextlib.redirect_stderr(said):
                refusal_code = cli.main(["build", "--instance", inst, "--out", out])
            self.assertEqual(refusal_code, 2)
            flag = re.search(r"`recipegraph build (--[a-z-]+)`", said.getvalue()).group(1)
            self.assertEqual(flag, dump_meta.OVERRIDE_FLAG)
            with contextlib.redirect_stderr(io.StringIO()), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    cli.main(["build", "--instance", inst, "--out", out, flag]), 0)

    def test_the_readme_names_the_flag_that_exists(self):
        with open(os.path.join(os.path.dirname(os.path.dirname(
                os.path.abspath(__file__))), "README.md")) as fh:
            readme = fh.read()
        self.assertIn(dump_meta.OVERRIDE_FLAG, readme)
        for named in set(re.findall(r"--allow-[a-z-]+", readme)):
            self.assertEqual(named, dump_meta.OVERRIDE_FLAG,
                             "README names %s, which no parser accepts" % named)


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
        # "1 entry", not "1 entries": the refusal is read by someone who has just been
        # stopped, and a message that cannot count is a message they trust less.
        self.assertIn("names.json holds 1 entry but", said.getvalue())
        self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
