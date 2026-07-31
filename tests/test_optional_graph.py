"""The commands that use the graph OPTIONALLY must say when they did not find one.

`have` and `track` both enrich their output from `data/graph.json` when it happens to be
there, and both were guarded by a bare `os.path.exists(args.graph)` against a RELATIVE
default. Inside the documented container, where data/ is mounted at /data and the working
directory has no data/ at all, neither guard ever passed: `have` skipped the reconciliation
that catches a stranded stock file, `track` recorded a snapshot labelled with raw item keys,
and both printed a success line with no hint that anything was missing (#92).

The scans themselves are covered by test_inventory and test_metrics; what is covered here is
the REPORTING half, which had no test at all. `scan` is stubbed rather than driven, because a
real region file is megabytes and the seam under test is between "what the scan returned" and
"what got printed".
"""

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from recipegraph import ae2_inventory, cli  # noqa: E402
from recipegraph.defaults import DEFAULT_GRAPH  # noqa: E402

import fixtures  # noqa: E402


class Args(object):
    """Just enough of an argparse namespace for cmd_have."""

    def __init__(self, **kw):
        self.regions = kw.pop("regions", [])
        self.out = kw.pop("out")
        self.graph = kw.pop("graph", DEFAULT_GRAPH)


class CoverageGraphResolutionTest(unittest.TestCase):
    """`_coverage_graph` decides which graph gets read, and it is pure, so pin it directly."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        # RUN FROM A DIRECTORY WITH NO data/. The repo root has a real data/graph.json, so a
        # test of "the relative default resolves to nothing" passes there for the wrong
        # reason and asserts the opposite of #92.
        self._cwd = os.getcwd()
        os.chdir(self.dir)

    def tearDown(self):
        os.chdir(self._cwd)

    def _touch(self, *parts):
        path = os.path.join(self.dir, *parts)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as fh:
            fh.write("{}")
        return path

    def test_an_existing_graph_is_used_with_nothing_to_report(self):
        graph = self._touch("graph.json")
        got, note = cli._coverage_graph(graph, os.path.join(self.dir, "have.json"))
        self.assertEqual(got, graph)
        self.assertEqual(note, "")

    def test_a_relative_graph_is_found_beside_out(self):
        # The container case: cwd has no data/, but /data is mounted and holds both files.
        graph = self._touch("mounted", "graph.json")
        got, note = cli._coverage_graph(
            "data/graph.json", os.path.join(self.dir, "mounted", "have.json"))
        self.assertEqual(got, graph)
        self.assertIn("beside --out", note)

    def test_the_basename_carries_over_rather_than_assuming_graph_json(self):
        # Falling back to a hardcoded "graph.json" would read a DIFFERENT file than the one
        # named, which is worse than skipping the check.
        wanted = self._touch("mounted", "other.json")
        self._touch("mounted", "graph.json")
        got, _note = cli._coverage_graph(
            "data/other.json", os.path.join(self.dir, "mounted", "have.json"))
        self.assertEqual(got, wanted)

    def test_an_absolute_graph_is_taken_literally(self):
        # The caller named a path. Reading a different one would be second-guessing them.
        self._touch("mounted", "graph.json")
        missing = os.path.join(self.dir, "nowhere", "graph.json")
        got, note = cli._coverage_graph(
            missing, os.path.join(self.dir, "mounted", "have.json"))
        self.assertIsNone(got)
        self.assertIn(missing, note)
        self.assertNotIn("beside --out", note)

    def test_both_paths_tried_are_named_when_neither_exists(self):
        got, note = cli._coverage_graph(
            "data/graph.json", os.path.join(self.dir, "mounted", "have.json"))
        self.assertIsNone(got)
        self.assertIn("data/graph.json", note)
        self.assertIn(os.path.join(self.dir, "mounted", "graph.json"), note)

    def test_a_path_that_resolves_to_the_same_file_is_reported_once(self):
        # `--out data/have.json` with the default `--graph`: the fallback IS the original, so
        # naming it twice would read as two separate attempts.
        note = cli._coverage_graph("data/graph.json", "data/have.json")[1]
        self.assertEqual(note.count("graph.json"), 1)

    def test_the_skip_notice_says_the_check_did_not_run(self):
        # The whole of #92: the failure mode is a reader who cannot tell the difference
        # between "reconciled and clean" and "never reconciled".
        note = cli._coverage_graph("data/graph.json", "data/have.json")[1]
        self.assertIn("NOT reconciled", note)
        self.assertIn("--graph", note)


class HaveAlwaysReportsWhetherItReconciledTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.mount = os.path.join(self.dir, "mounted")
        os.makedirs(self.mount)
        region = os.path.join(self.dir, "r.0.0.mca")
        with open(region, "w") as fh:
            fh.write("")
        self.regions = [region]
        # One key the fixture graph knows and one it does not, so a real reconciliation has
        # something to say and a skipped one is unmistakably different.
        self.stock = {fixtures.NAMED_CAN: 4, "mod:nonexistent": 1_473_740}
        self._real_scan = ae2_inventory.scan
        ae2_inventory.scan = self._fake_scan
        # Several tests reproduce #92 by running from a directory with no data/, and a cwd
        # left moved would silently retarget every relative path in the rest of the suite.
        self._cwd = os.getcwd()

    def tearDown(self):
        ae2_inventory.scan = self._real_scan
        os.chdir(self._cwd)

    def _fake_scan(self, *_a, **_kw):
        return (dict(self.stock), {}, {}, {"cells": 1}, None, {})

    def _graph_at(self, path):
        fixtures.discriminated_graph().save(path)
        return path

    def _run(self, **kw):
        kw.setdefault("regions", self.regions)
        args = Args(**kw)
        buf, err = io.StringIO(), io.StringIO()
        # stderr is swallowed rather than left to print: the no-regions case writes there,
        # and a suite that spits diagnostics mid-run teaches the reader to ignore them.
        with redirect_stdout(buf), redirect_stderr(err):
            rc = cli.cmd_have(args)
        return rc, buf.getvalue()

    def test_it_reconciles_when_the_graph_is_where_it_says(self):
        graph = self._graph_at(os.path.join(self.mount, "graph.json"))
        rc, out = self._run(out=os.path.join(self.mount, "have.json"), graph=graph)
        self.assertEqual(rc, 0)
        self.assertIn("match nothing in the graph", out)
        self.assertIn("1,473,740", out)

    def test_it_says_so_when_it_cannot_reconcile(self):
        # The exact #92 shape: a relative default --graph, a working directory with no data/.
        os.chdir(self.dir)
        rc, out = self._run(out=os.path.join(self.mount, "have.json"))
        self.assertEqual(rc, 0)
        self.assertIn("wrote ", out)
        self.assertIn("NOT reconciled", out)
        # Silence is the bug. Before the fix this was the entire output.
        self.assertNotEqual(out.strip().count("\n"), 0)

    def test_the_container_recipe_reconciles_with_no_extra_flag(self):
        # `--out /data/ae2_have.new.json` from a cwd with no data/, graph mounted alongside.
        os.chdir(self.dir)
        self._graph_at(os.path.join(self.mount, "graph.json"))
        rc, out = self._run(out=os.path.join(self.mount, "ae2_have.new.json"))
        self.assertEqual(rc, 0)
        self.assertIn("beside --out", out)
        self.assertIn("match nothing in the graph", out)

    def test_the_stock_file_is_written_either_way(self):
        # #92 is a reporting bug, not a data bug. The artefact must be identical.
        os.chdir(self.dir)
        a = os.path.join(self.mount, "a.json")
        b = os.path.join(self.mount, "b.json")
        self._run(out=a)
        self._graph_at(os.path.join(self.mount, "graph.json"))
        self._run(out=b)
        with open(a) as fh:
            first = json.load(fh)
        with open(b) as fh:
            second = json.load(fh)
        self.assertEqual(first, second)
        self.assertEqual(first["items"], self.stock)

    def test_no_regions_matched_still_fails_loudly(self):
        got = self._run(regions=[os.path.join(self.dir, "nope.*.mca")],
                        out=os.path.join(self.mount, "have.json"))
        self.assertEqual(got[0], 2)


class TrackArgs(object):
    """Just enough of an argparse namespace for cmd_track."""

    def __init__(self, **kw):
        self.have = kw.pop("have", None)
        self.regions = kw.pop("regions", None)
        self.db = kw.pop("db")
        self.no_prune = kw.pop("no_prune", True)
        self.graph = kw.pop("graph", DEFAULT_GRAPH)


class TrackSaysWhenItCannotLabelASnapshotTest(unittest.TestCase):
    """`track` shares the resolver, and it runs unattended, so its silence lasts longer."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.mount = os.path.join(self.dir, "mounted")
        os.makedirs(self.mount)
        self.have = os.path.join(self.mount, "ae2_have.json")
        with open(self.have, "w") as fh:
            # No `names`, which is what the offline reader writes, so the graph is the only
            # way this snapshot gets English labels.
            json.dump({"items": {fixtures.NAMED_CAN: 4}, "stats": {}}, fh)
        self._cwd = os.getcwd()
        os.chdir(self.dir)

    def tearDown(self):
        os.chdir(self._cwd)

    def _run(self, **kw):
        kw.setdefault("have", self.have)
        kw.setdefault("db", os.path.join(self.mount, "metrics.db"))
        buf, err = io.StringIO(), io.StringIO()
        with redirect_stdout(buf), redirect_stderr(err):
            rc = cli.cmd_track(TrackArgs(**kw))
        return rc, buf.getvalue(), err.getvalue()

    def test_it_says_the_chart_will_show_raw_keys(self):
        rc, out, err = self._run()
        self.assertEqual(rc, 0)
        self.assertIn("recorded 1 items", out)
        self.assertIn("raw item keys", err)

    def test_it_finds_the_graph_beside_the_db_and_then_stays_quiet(self):
        # The container case again: --db in the mount, the graph alongside it.
        fixtures.discriminated_graph().save(os.path.join(self.mount, "graph.json"))
        rc, _out, err = self._run()
        self.assertEqual(rc, 0)
        self.assertNotIn("raw item keys", err)
        # Proof it actually labelled rather than merely staying quiet.
        from recipegraph import metrics
        conn = metrics.connect(os.path.join(self.mount, "metrics.db"))
        self.addCleanup(conn.close)
        names = [r[0] for r in conn.execute("SELECT label FROM name").fetchall()]
        self.assertIn("Brine Can", names)


if __name__ == "__main__":
    unittest.main()
