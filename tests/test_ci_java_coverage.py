"""`tools/ci-java.sh` states what fraction of the Java suite it ran, and states it truthfully.

WHY THIS FILE EXISTS. The job compiles two packages and then prints `classes: 24, tests: 324
run, 0 failed` and `== java core green ==`. Neither line said that 24 was a minority of the
test files in the tree, and the header that does say so is read once while the output is read
every time. #243 shipped four stale assertions past exactly that: it moved values four
`client/planner` tests read, this job could not see any of those tests, and the full gradle run
caught them afterwards by luck. #244 made the run print the fraction; this file is what stops
that line being deleted, or worse, being kept while quietly becoming false.

TESTED BY RUNNING THE SCRIPT'S OWN COUNTING, NOT BY GREPPING FOR IT. The block is extracted
from `tools/ci-java.sh` and executed, so what is under test is the shell that will run in CI
rather than a copy of it that can drift. `tools/check.sh`'s summary counts are guarded the same
way in `test_check_summary.py`, and for the same reason: an assertion that a script CONTAINS a
pattern passes against a pattern that is wrong in a new way.

The python job is the right home for this even though nothing here is python: it runs on every
pull request, and `tools/ci-java.sh` cannot check itself. That is the argument
`test_container_gate.py` already makes one file over.
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CI_JAVA = os.path.join(ROOT, "tools", "ci-java.sh")
RUNNER = os.path.join(ROOT, "tools", "ci", "JavaCoreSuite.java")

# The flags the script hands the runner so the run can say what it left out. Both halves have to
# hold: a script that computes the note and never passes it prints nothing, and a runner that
# stops parsing the flag swallows it. #244.
COVERAGE_FLAGS = ("--scope", "--not-run", "--expect-assertions")


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _assignment(name, body):
    """A `NAME=value` or `NAME="multi\nline"` assignment out of the script, as shell."""
    match = re.search(r'^(%s=(?:"[^"]*"|\S*))$' % re.escape(name), body, re.M)
    assert match, "tools/ci-java.sh no longer assigns %s; update this test" % name
    return match.group(1)


def _counting_block(body):
    """The measure-the-fraction block, from the script rather than restated.

    Ends at `not_run_note`, which is the product of the whole block and the thing the runner is
    handed. The note contains no double quote, which is what lets this stop at the closing one.
    """
    match = re.search(r'(find \$CORE_TEST -name .*?\nnot_run_note="[^"]*"\n)', body, re.S)
    assert match, "tools/ci-java.sh no longer computes not_run_note; update this test"
    return match.group(1)


def _assertions_in(path):
    """`@Test` methods in one file, counted the way the script counts them."""
    with open(path, encoding="utf-8") as handle:
        return len([line for line in handle if re.match(r"^\s*@Test\b", line)])


def _java_test_files(directory):
    """Every `*Test.java` under `directory`, as the script's `find` would see them."""
    found = []
    for base, _, names in os.walk(directory):
        for name in names:
            if name.endswith("Test.java"):
                found.append(os.path.join(base, name))
    return found


class _CountingBlock(object):
    """The extracted block, runnable against any tree."""

    def __init__(self, body):
        self.block = _counting_block(body)
        self.core_test = _assignment("CORE_TEST", body)
        self.all_test = _assignment("ALL_TEST", body)

    def _preamble(self, work, core_test, all_test):
        """The script's own declarations, in the script's own order.

        ALL_TEST FIRST, because CORE_TEST is defined in terms of it. Restating them in the other
        order here would work against a copy that had drifted back to two independent literals
        and fail against the file as written, which is the wrong way round for a test.
        """
        return [
            "set -eu",
            # The script's own `die`, restated because the block calls it and the rest of
            # ci-java.sh cannot be sourced without a JDK. Same contract: a message and a
            # non-zero exit.
            'die() { echo "!! $*" >&2; exit 1; }',
            'WORK="%s"' % work,
            all_test if all_test is not None else self.all_test,
            core_test if core_test is not None else self.core_test,
        ]

    def roots(self, cwd):
        """`ALL_TEST` and the `CORE_TEST` directories, expanded by the shell that defines them.

        Asking `sh` rather than parsing the assignments: CORE_TEST is written in terms of
        ALL_TEST, so a python-side reimplementation would be a second expander to keep correct.
        """
        script = "\n".join(self._preamble("/nonexistent", None, None)
                           + ['printf "%s\\n---\\n%s\\n" "$ALL_TEST" "$CORE_TEST"'])
        done = subprocess.run(["sh", "-c", script], cwd=cwd, capture_output=True, text=True,
                              timeout=60)
        assert done.returncode == 0, done.stdout + done.stderr
        every, core = done.stdout.split("---\n", 1)
        return every.strip(), [line.strip() for line in core.split() if line.strip()]

    def run(self, cwd, core_test=None, all_test=None):
        """Run the block in `cwd` and return (returncode, stdout+stderr, the composed note)."""
        work = tempfile.mkdtemp(prefix="ci-java-coverage-")
        try:
            script = "\n".join(self._preamble(work, core_test, all_test) + [
                self.block,
                # The note is the block's whole output, so print it on a marked line rather than
                # trying to read it out of the shell.
                'printf "NOTE %s\\n" "$not_run_note"',
            ])
            done = subprocess.run(["sh", "-c", script], cwd=cwd, capture_output=True, text=True,
                                  timeout=60)
            output = done.stdout + done.stderr
            note = ""
            if "NOTE " in output:
                note = output.split("NOTE ", 1)[1]
            return done.returncode, output, note
        finally:
            shutil.rmtree(work, ignore_errors=True)


# A file that counts as one assertion. NOT EMPTY: the block counts `@Test` and refuses a core
# with none, so a fixture of empty files exercises the refusal rather than the counting. That
# guard caught these fixtures the day it was written, which is the correct outcome.
STUB_TEST = "public class %s {\n    @Test public void anAssertion() { }\n}\n"


def _tree(root, paths, assertions=1):
    """Create minimal `*Test.java` files at `paths`, making directories as needed."""
    for path in paths:
        full = os.path.join(root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        name = os.path.basename(path)[:-len(".java")]
        with open(full, "w", encoding="utf-8") as handle:
            if path.endswith(".java"):
                handle.write(STUB_TEST % name)
                for extra in range(assertions - 1):
                    handle.write("    @Test public void more%d() { }\n" % extra)


class TheExtractionFindsSomethingToTestTest(unittest.TestCase):
    """The control, and it runs first on purpose.

    Every assertion below is of the form "the block behaved correctly", which is also what an
    empty block does when the regex above has stopped matching. This is the test that fails when
    this file has quietly stopped inspecting anything.
    """

    def test_the_block_and_both_tree_variables_are_still_there(self):
        body = _read(CI_JAVA)
        block = _counting_block(body)
        self.assertIn("not_run=", block)
        self.assertIn("groups=", block)
        self.assertIn("CORE_TEST", _assignment("CORE_TEST", body))
        self.assertIn("mod/src/test/java", _assignment("ALL_TEST", body))

    def test_the_block_really_runs_and_is_not_a_no_op(self):
        code, output, note = _CountingBlock(_read(CI_JAVA)).run(ROOT)
        self.assertEqual(code, 0, output)
        self.assertTrue(note.strip(), "the block produced no note at all:\n" + output)


class TheRunSaysWhatFractionItCoversTest(unittest.TestCase):

    def setUp(self):
        self.block = _CountingBlock(_read(CI_JAVA))

    def test_the_counts_in_the_note_match_the_tree_counted_independently(self):
        """Recounted here in python rather than trusting the shell's own arithmetic.

        This is the assertion that goes red the day the two `find` expressions start reading
        different trees while still agreeing with each other.
        """
        code, output, note = self.block.run(ROOT)
        self.assertEqual(code, 0, output)
        all_test, core_dirs = self.block.roots(ROOT)
        core = set()
        for directory in core_dirs:
            core.update(_java_test_files(os.path.join(ROOT, directory)))
        every = _java_test_files(os.path.join(ROOT, all_test))
        expected = "%d of %d *Test.java files" % (len(every) - len(core), len(every))
        self.assertIn(expected, note,
                      "the note disagrees with an independent count of the same tree; "
                      "expected %r in:\n%s" % (expected, note))
        self.assertLess(len(core), len(every),
                        "this job runs every test file in the tree, which cannot be true while "
                        "the pack jars are unavailable to CI")

    def test_the_assertion_figures_match_the_tree_counted_independently(self):
        """Assertions, not just files, because a file is not a constant amount of checking.

        24 of 68 files and 324 of 826 assertions are the same defect at two resolutions, and the
        second is the one that counts the things which could have caught #243.
        """
        code, output, note = self.block.run(ROOT)
        self.assertEqual(code, 0, output)
        all_test, core_dirs = self.block.roots(ROOT)
        core = set()
        for directory in core_dirs:
            core.update(_java_test_files(os.path.join(ROOT, directory)))
        every = _java_test_files(os.path.join(ROOT, all_test))
        total = sum(_assertions_in(p) for p in every)
        ran = sum(_assertions_in(p) for p in core)
        self.assertIn("%d of %d assertions" % (total - ran, total), note, note)

    def test_the_core_assertion_count_is_handed_to_the_runner_to_be_checked(self):
        # The figures above are a `@Test` grep, which is a proxy. It is only allowed into the
        # output because the runner re-derives it from what JUnit actually ran; see
        # TheNoteReachesTheRunnerTest for the other half of that contract.
        body = _read(CI_JAVA)
        self.assertIn("core_assertions", body)
        self.assertIn("--expect-assertions", body.split("exec java", 1)[1])

    def test_the_note_sends_the_reader_to_the_gate_that_does_cover_them(self):
        # A count with no instruction is a fact nobody acts on. The whole point of #244 is that
        # the reader is about to merge on the strength of this line.
        _, output, note = self.block.run(ROOT)
        self.assertIn("tools/check.sh", note, output)

    def test_the_excluded_packages_are_named_and_counted_from_the_tree(self):
        root = tempfile.mkdtemp(prefix="ci-java-tree-")
        try:
            pkg = "mod/src/test/java/io/github/jacoblasky/recipedump"
            _tree(root, [
                "%s/graph/AlphaTest.java" % pkg,
                "%s/plan/BetaTest.java" % pkg,
                "%s/client/GammaTest.java" % pkg,
                "%s/client/planner/DeltaTest.java" % pkg,
                "%s/shot/EpsilonTest.java" % pkg,
                "%s/RootTest.java" % pkg,
            ])
            code, output, note = self.block.run(root)
            self.assertEqual(code, 0, output)
            self.assertIn("4 of 6 *Test.java files", note, output)
            # `client/planner` rolls up into `client/`: the summary groups by TOP-LEVEL package
            # so it stays one readable line, and the counts still have to add up.
            self.assertIn("client/ 2", note, output)
            self.assertIn("shot/ 1", note, output)
            self.assertIn("(the root package) 1", note, output)
        finally:
            shutil.rmtree(root, ignore_errors=True)


class ASearchThatStoppedMatchingIsRefusedTest(unittest.TestCase):
    """The measurement's own controls, which are what this has instead of a pinned number.

    #244 asked whether the excluded count should be asserted. It is not, because "44 excluded"
    fails on every legitimate test file anyone adds under `client/`, and a check that fires on
    correct work gets edited until it stops rather than read. These two guards cost nothing on a
    correct tree and catch the failure a pinned number cannot: a `find` that has stopped
    matching, which reports perfect coverage in exactly the voice of a job that has none.
    """

    def setUp(self):
        self.block = _CountingBlock(_read(CI_JAVA))

    def test_a_tree_this_job_appears_to_cover_entirely_is_refused(self):
        root = tempfile.mkdtemp(prefix="ci-java-tree-")
        try:
            pkg = "mod/src/test/java/io/github/jacoblasky/recipedump"
            _tree(root, ["%s/graph/AlphaTest.java" % pkg, "%s/plan/BetaTest.java" % pkg])
            code, output, _ = self.block.run(root)
            self.assertNotEqual(code, 0,
                                "a tree with nothing excluded must fail rather than print a "
                                "banner claiming coverage this job cannot have:\n" + output)
            self.assertIn("appears to run all", output)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_two_searches_reading_different_trees_are_refused(self):
        """CORE_TEST is written in terms of ALL_TEST, so this is the ONE route left.

        Moving ALL_TEST now drags CORE_TEST with it and the two cannot disagree that way. What
        the guard still has to catch is someone writing a literal path back into CORE_TEST,
        which is how it was written before #244 and is what a renamed package invites.
        """
        root = tempfile.mkdtemp(prefix="ci-java-tree-")
        try:
            pkg = "mod/src/test/java/io/github/jacoblasky/recipedump"
            _tree(root, ["%s/client/GammaTest.java" % pkg, "elsewhere/graph/AlphaTest.java"])
            code, output, _ = self.block.run(root, core_test="CORE_TEST=elsewhere/graph")
            self.assertNotEqual(code, 0,
                                "the arithmetic must not reconcile across two different "
                                "trees:\n" + output)
            # The lead phrase, not a fragment from the middle: `die` wraps its message at the
            # newlines it was written with, so an interior phrase can be split by a line break.
            self.assertIn("the two searches disagree", output)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_a_core_with_no_countable_assertions_is_refused(self):
        """The control on the assertion counter itself.

        The figures in the note come from grepping `@Test`. If that grep stops matching, it
        reports zero, and zero read as a real number turns "502 of 826" into "0 of 0" while the
        run stays green. That is the same shape as the exclusion search breaking, one unit down.
        """
        root = tempfile.mkdtemp(prefix="ci-java-tree-")
        try:
            pkg = "mod/src/test/java/io/github/jacoblasky/recipedump"
            _tree(root, ["%s/client/GammaTest.java" % pkg])
            # Core files that exist but carry nothing the counter can see.
            for name in ("graph/AlphaTest.java", "plan/BetaTest.java"):
                full = os.path.join(root, pkg, name)
                os.makedirs(os.path.dirname(full), exist_ok=True)
                with open(full, "w", encoding="utf-8") as handle:
                    handle.write("public class X { public void notAnnotated() { } }\n")
            code, output, _ = self.block.run(root)
            self.assertNotEqual(code, 0, output)
            self.assertIn("assertion counter has stopped matching", output)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_an_empty_core_is_refused_rather_than_divided_by(self):
        root = tempfile.mkdtemp(prefix="ci-java-tree-")
        try:
            pkg = "mod/src/test/java/io/github/jacoblasky/recipedump"
            _tree(root, ["%s/graph/.keep" % pkg, "%s/plan/.keep" % pkg,
                         "%s/client/GammaTest.java" % pkg])
            code, output, _ = self.block.run(root)
            self.assertNotEqual(code, 0, output)
            self.assertIn("would compile nothing", output)
        finally:
            shutil.rmtree(root, ignore_errors=True)


class TheNoteReachesTheRunnerTest(unittest.TestCase):
    """Computing the sentence and printing it are two different things, and both can rot.

    Static, because the runner needs a JDK the python job does not have. It is still the useful
    half: the block above is proven to produce a note by execution, and this proves the note is
    handed to something that parses it.
    """

    def test_the_invocation_passes_both_flags(self):
        body = _read(CI_JAVA)
        invocation = body.split("exec java", 1)
        self.assertEqual(len(invocation), 2, "tools/ci-java.sh no longer execs the runner")
        for flag in COVERAGE_FLAGS:
            self.assertIn(flag, invocation[1],
                          "the run no longer passes %s, so its verdict has gone back to "
                          "overstating its scope (#244)" % flag)
        self.assertIn('"$not_run_note"', invocation[1],
                      "the note is computed and then not passed, which prints nothing")

    def test_the_runner_parses_every_flag_it_is_handed(self):
        runner = _read(RUNNER)
        # THE CONTROL. `--allow-skip` predates #244 and is unquestionably parsed, so a search
        # that cannot find it is broken rather than reporting a real omission.
        self.assertIn('"--allow-skip".equals', runner,
                      "the search for a parsed flag no longer works, so the assertion below "
                      "means nothing")
        for flag in COVERAGE_FLAGS:
            self.assertIn('"%s".equals' % flag, runner,
                          "tools/ci-java.sh passes %s and the runner does not parse it, so it "
                          "would be read as a test class name" % flag)

    def test_the_verdict_carries_the_scope_and_the_note_sits_above_it(self):
        runner = _read(RUNNER)
        self.assertIn("NOT RUN: ", runner, "the runner no longer labels what it did not run")
        note_at = runner.index("NOT RUN: ")
        verdict_at = runner.index('"== java core green"')
        self.assertLess(note_at, verdict_at,
                        "the note must print ABOVE the verdict. A caveat under the line the "
                        "reader stops at is a caveat nobody reads (#244).")


if __name__ == "__main__":
    unittest.main()
