"""Which build drew this page, and whether the checkout has moved on since.

#38. Four defects were reported in one hour that had all already been fixed and shipped;
the `serve` process was holding the new graph and the old code. Nothing on the page said
so, because the tool watched its DATA files and never itself.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import nbt_digest, version  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.sources import dump_meta  # noqa: E402


class BuildTest(unittest.TestCase):
    def test_the_running_build_names_itself(self):
        line = version.BUILD.describe()
        self.assertIn("recipegraph", line)
        self.assertIn(version.BUILD.version, line)
        self.assertTrue(version.BUILD.version.strip())

    @staticmethod
    def _from_a_checkout(case):
        """A `Build` that really consulted git, or a skip when there is no checkout to ask.

        SKIPS ON THE ABSENCE OF `.git`, NOT ON `from_git`, and the difference is the whole
        point. `from_git` is also False when `_git` TIMED OUT, and it does time out here:
        `git describe` costs 1.7-2.0s on this FUSE mount idle and blows the 5s ceiling
        during a `tools/check.sh` fixture regeneration. Skipping on it meant both assertions
        below stopped running during the pre-merge run and nothing said so -- a skip reads
        exactly like a pass, which is the failure `tools/check.sh` exists to surface and did
        (`python: 2 skipped`).

        So: no `.git` means genuinely no checkout, and the fallback tests cover that case.
        A `.git` that exists means git CAN answer, and one fresh `Build()` is built here
        rather than reusing the import-time singleton, because the singleton may have been
        sampled during exactly the load spike that caused the timeout.
        """
        if not version.in_a_checkout():
            case.skipTest("no .git at the package root; the fallback tests cover this")
        build = version.Build()
        case.assertTrue(build.from_git,
                        "a .git exists but `git describe` gave nothing -- this is a git "
                        "failure to investigate, NOT a reason to skip the assertions")
        return build

    def test_a_checkout_reports_its_commit_and_date(self):
        # Not asserted unconditionally: CI runs from a checkout, but the Docker image
        # ships no .git and the fallback branch below is what it takes.
        build = self._from_a_checkout(self)
        self.assertRegex(build.date, r"^\d{4}-\d{2}-\d{2}$")
        self.assertIn(build.date, build.describe())

    @staticmethod
    def _without_git(env=None):
        """A Build as it would come up outside a checkout, e.g. inside the Docker image."""
        real = version._describe_git
        saved = {k: os.environ.get(k) for k in (version.VERSION_ENV, version.DATE_ENV)}
        version._describe_git = lambda: (None, None)
        for key in saved:
            os.environ.pop(key, None)
        os.environ.update(env or {})
        try:
            return version.Build()
        finally:
            version._describe_git = real
            for key, was in saved.items():
                if was is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = was

    def test_without_git_it_falls_back_and_says_so(self):
        """A released archive must still name a version, and must not imply a commit."""
        build = self._without_git()
        self.assertFalse(build.from_git)
        self.assertEqual(build.version, version.FALLBACK_VERSION)
        self.assertIn("no build metadata", build.describe())

    def test_the_image_build_can_stamp_itself(self):
        """The Dockerfile ships no .git, so without this every container looks identical.

        That is #38's failure moved from the process to the image: two containers built a
        month apart would print the same footer, and the reader would trust it.
        """
        build = self._without_git({version.VERSION_ENV: "v0.6.0-2-gdeadbee",
                                   version.DATE_ENV: "2026-07-27"})
        self.assertEqual(build.describe(), "recipegraph v0.6.0-2-gdeadbee (2026-07-27)")

    def test_an_unstamped_image_says_unknown_rather_than_guessing(self):
        # An omitted build arg must not look like a confident answer.
        build = self._without_git({version.VERSION_ENV: "   "})
        self.assertEqual(build.version, version.FALLBACK_VERSION)
        self.assertIn("no build metadata", build.describe())

    def test_a_checkout_outranks_a_baked_in_string(self):
        """A stale env var on a dev box must not hide a `-dirty` working tree.

        The env var is right for the image and wrong for anyone editing the code, which is
        precisely the reader this whole module is for.
        """
        self._from_a_checkout(self)
        saved = os.environ.get(version.VERSION_ENV)
        os.environ[version.VERSION_ENV] = "v0.0.1-stale"
        try:
            self.assertNotIn("stale", version.Build().describe())
        finally:
            if saved is None:
                os.environ.pop(version.VERSION_ENV, None)
            else:
                os.environ[version.VERSION_ENV] = saved

    def test_git_failing_is_never_fatal(self):
        # It runs at import. A git binary blocked on a lock, or none at all, must not stop
        # the server from starting.
        self.assertIsNone(version._git("definitely-not-a-git-subcommand"))


class InACheckoutTest(unittest.TestCase):
    """"Is there a `.git`" is a different question from "did git answer", and both are asked.

    Conflating them is what let two assertions in `BuildTest` skip themselves out of the
    pre-merge run: `git describe` costs 1.7-2.0s on this FUSE mount and exceeds `_git`'s 5s
    ceiling under load, `from_git` went False, and the tests read that as "Docker image".
    """

    def test_a_worktree_counts_as_a_checkout(self):
        """A git WORKTREE's `.git` is a FILE, and every agent here works from one.

        This is the case an `isdir` test gets wrong, and getting it wrong would restore the
        original bug wearing a different cause: every worktree would report "no checkout"
        and skip the version assertions permanently rather than intermittently.
        """
        root = tempfile.mkdtemp()
        try:
            with open(os.path.join(root, ".git"), "w") as fh:
                fh.write("gitdir: /somewhere/.git/worktrees/wt\n")
            saved = version._ROOT
            version._ROOT = root
            try:
                self.assertTrue(version.in_a_checkout())
            finally:
                version._ROOT = saved
        finally:
            for name in os.listdir(root):
                os.unlink(os.path.join(root, name))
            os.rmdir(root)

    def test_no_dot_git_means_no_checkout(self):
        # The Docker image and a released archive. The fallback tests above cover what the
        # build reports in that state; this is only the detector.
        root = tempfile.mkdtemp()
        try:
            saved = version._ROOT
            version._ROOT = root
            try:
                self.assertFalse(version.in_a_checkout())
            finally:
                version._ROOT = saved
        finally:
            os.rmdir(root)

    def test_this_repository_is_one(self):
        # The tests are being run from a checkout right now, worktree or otherwise, so a
        # False here means the detector is broken rather than that the environment is odd.
        self.assertTrue(version.in_a_checkout())


class SourceStampTest(unittest.TestCase):
    def test_only_the_package_is_watched(self):
        """A README or a test edit must not tell someone to restart their server.

        Training the reader to ignore the banner costs more than the banner is worth.
        """
        files = version.source_files()
        self.assertTrue(files)
        for path in files:
            self.assertTrue(path.endswith(".py"), path)
            self.assertIn(os.sep + "recipegraph" + os.sep, path)
        names = {os.path.basename(f) for f in files}
        self.assertIn("server.py", names)
        self.assertIn("version.py", names)
        self.assertNotIn("test_version.py", names)

    def test_bytecode_is_not_a_source_change(self):
        # __pycache__ is written by the interpreter itself, so watching it would make
        # every process report itself stale the moment it imported anything.
        self.assertFalse([f for f in version.source_files() if "__pycache__" in f])

    def test_a_current_process_says_nothing(self):
        build = version.Build()
        self.assertFalse(build.code_stale())
        self.assertEqual(version.restart_note(build), "")

    def test_an_edited_source_is_noticed(self):
        build = version.Build()
        # The stamp is (newest mtime, total size, count). Faking any one of the three is
        # enough, and faking it beats writing into the live package mid-suite.
        build.stamp = (build.stamp[0] - 60, build.stamp[1], build.stamp[2])
        self.assertTrue(build.code_stale())

    def test_a_file_appearing_or_vanishing_is_noticed(self):
        """The reason the stamp is a triple and not just the newest mtime.

        Deleting the newest file lowers the maximum, which an `>` comparison against a
        remembered mtime reads as "older than me, nothing to see".
        """
        build = version.Build()
        newest, total, count = build.stamp
        build.stamp = (newest, total, count + 1)
        self.assertTrue(build.code_stale())
        build.stamp = (newest, total + 1, count)
        self.assertTrue(build.code_stale())

    def test_the_warning_says_restart_and_offers_nothing_to_click(self):
        """Stale DATA is re-readable and gets a button; stale CODE is not.

        Offering a control that cannot work is worse than saying nothing, because the
        reader clicks it and concludes the page is now current.
        """
        build = version.Build()
        build.stamp = (0.0, 0, 0)
        note = version.restart_note(build)
        self.assertIn("Restart", note)
        self.assertNotIn("<", note, "the note is prose; the caller wraps it")

    def test_a_real_edit_moves_the_stamp(self):
        """The mechanism end to end, against the filesystem rather than a faked tuple."""
        target = os.path.join(os.path.dirname(os.path.abspath(version.__file__)),
                              "version.py")
        build = version.Build()
        before = os.stat(target).st_mtime
        # Past the NEWEST file, not past its own mtime: version.py is not necessarily the
        # most recently written module, so +5 on an old file moves no maximum.
        bumped = build.stamp[0] + 5
        os.utime(target, (bumped, bumped))
        try:
            self.assertTrue(build.code_stale())
        finally:
            os.utime(target, (before, before))
        self.assertFalse(build.code_stale())


class DumpProvenanceTest(unittest.TestCase):
    """`summary.json`'s three facts have to reach the UI, which has no dump directory."""

    @staticmethod
    def _graph(schema=dump_meta.SCHEMA, mod_version="0.5.1"):
        # Defaulted to the CURRENT schema rather than a literal, so "a graph from a current
        # dump" keeps meaning that after a bump. Pinning 3 here made the schema-4 change
        # fail as a warning-about-a-stale-graph rather than as the constant moving.
        g = Graph()
        g.names = {"mod:widget": "Widget", "mod:part": "Part"}
        g.add(Recipe("r", "t", [("mod:widget", 1)], [Ingredient(["mod:part"], 1)],
                     category="minecraft.crafting"))
        g.dump_schema = schema
        g.dump_version = mod_version
        return g

    def test_the_version_survives_save_and_load(self):
        path = os.path.join(tempfile.mkdtemp(), "graph.json")
        self._graph().save(path)
        loaded = Graph.load(path)
        self.assertEqual(loaded.dump_version, "0.5.1")
        self.assertEqual(loaded.dump_schema, dump_meta.SCHEMA)

    def test_a_graph_built_before_this_field_loads_as_unknown(self):
        # graph.json on disk is 115 MB and rebuilding needs the game running, so the
        # field's absence has to be a normal state and not a KeyError.
        path = os.path.join(tempfile.mkdtemp(), "old.json")
        doc = json.loads(json.dumps(self._graph().to_json()))
        del doc["dump_version"]
        with open(path, "w") as fh:
            json.dump(doc, fh)
        self.assertIsNone(Graph.load(path).dump_version)

    def test_the_page_and_the_terminal_describe_one_dump_the_same_way(self):
        """The reason `of_graph` exists rather than the UI formatting its own sentence."""
        meta = {"mod_version": "0.5.1", "schema": dump_meta.SCHEMA, "present": True}
        self.assertEqual(dump_meta.describe(dump_meta.of_graph(self._graph())),
                         dump_meta.describe(meta))

    def test_an_older_schema_still_says_re_run_the_dump(self):
        line = dump_meta.describe(dump_meta.of_graph(self._graph(schema=2)))
        self.assertIn("re-run /recipedump", line)

    def test_a_graph_older_than_the_digest_format_says_stock_cannot_match(self):
        """Two severities must not wear one sentence.

        A dump missing a newer FIELD costs a feature and the graph is still correct. A dump
        older than the digest format means every discriminated key in it is one the current
        reader never computes, so AE2 stock silently reads as zero. Telling someone to
        "pick up newer fields" for the second case understates it into a chore.
        """
        line = dump_meta.describe(dump_meta.of_graph(
            self._graph(schema=nbt_digest.DIGEST_FORMAT_SCHEMA - 1)))
        self.assertIn("DIGEST FORMAT", line)
        self.assertIn("stock cannot match", line)
        self.assertIn("re-run /recipedump", line)

    def test_a_graph_at_the_digest_format_does_not_get_that_warning(self):
        # The warning has to be believed the once it matters, so it must not fire on a graph
        # whose digests are current.
        line = dump_meta.describe(dump_meta.of_graph(
            self._graph(schema=nbt_digest.DIGEST_FORMAT_SCHEMA)))
        self.assertNotIn("DIGEST FORMAT", line)

    def test_a_graph_with_no_dump_at_all_reports_unknown_provenance(self):
        line = dump_meta.describe(dump_meta.of_graph(self._graph(schema=0,
                                                                mod_version=None)))
        self.assertIn("unknown", line)

    def test_a_graph_that_never_recorded_a_version_does_not_guess_an_age(self):
        """Two different absences, and reusing one wording for both asserts a fact.

        From a DUMP directory, a missing `mod_version` means the mod predated 0.4.2 and
        did not stamp itself. From a GRAPH it means the graph was built before #38
        persisted the field, which is every graph.json on disk today, and those were
        written by whatever mod was current. "pre-0.4.2" there is a claim nobody measured.
        """
        line = dump_meta.describe(dump_meta.of_graph(self._graph(mod_version=None)))
        self.assertIn(dump_meta.UNRECORDED_VERSION, line)
        self.assertNotIn("pre-0.4.2", line)
        self.assertNotIn("None", line)

    def test_a_dump_directory_still_reports_an_unstamped_mod_as_such(self):
        # The other absence, unchanged: there, "no version field" really does date the mod.
        line = dump_meta.describe({"mod_version": None, "schema": dump_meta.SCHEMA,
                                   "present": True})
        self.assertIn("pre-0.4.2", line)
        self.assertNotIn("None", line)


if __name__ == "__main__":
    unittest.main()
