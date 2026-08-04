"""`tools/check.sh`'s java summary counts tests, and gradle prints lines that are not tests.

WHY THIS FILE EXISTS. The summary line is the only thing anybody reads off a fifteen-minute
containerised run, and it was over-counting failures: `grep -c ' FAILED$'` matched the per-class
roll-up and gradle's own `> Task :test FAILED` alongside the real method lines, so two failing
tests reported as four. That is not cosmetic in two ways. The inflation grows with the number of
failing CLASSES, so a genuinely broken change reports a number nobody can reason about; and a run
that never COMPILES prints only `> Task :compileJava FAILED`, which the old count reported as one
failure while naming no test, so a build that never ran read as one flaky test.

It stayed hidden because it cannot fire on a green run. `passed` uses the same shape and is safe
only by accident, since gradle prints no class-level `PASSED`, so the arithmetic reconciles
exactly while nothing is failing and diverges the moment something does.

DRIVEN AGAINST A REAL GRADLE LOG SHAPE, not asserted about the regex. A test that checked the
script contains a particular pattern would pass on a pattern that is wrong in a new way. These
extract the counting expressions from the script and run them, with `grep` doing the matching, so
what is under test is what the script will do.
"""

import os
import re
import subprocess
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECK = os.path.join(ROOT, "tools", "check.sh")

# The lines gradle really emits, in the order it emits them, for a run with two failing methods
# in one class. Every line after the first two is a line the count must NOT treat as a test.
GRADLE_LOG = """\
PlannerLayoutTest > aTreeRowDrawsAnIconColumn PASSED
PlannerLayoutTest > aTokenNodeMarksTheIconColumnInsteadOfDrawingASprite FAILED
PlannerLayoutTest > aTreeRowMarksATokenToo FAILED
PlanFixtureTest > everyFixturePlansExactlyAsThePythonOracleDoes SKIPPED
PlannerLayoutTest FAILED
> Task :test FAILED
FAILURE: Build failed with an exception.
"""

# A run that never reached a test at all. The whole log, in practice.
COMPILE_FAILURE_LOG = """\
> Task :compileJava FAILED
FAILURE: Build failed with an exception.
"""


def _counting_lines():
    """The three `grep -cE` expressions out of `check.sh`, as shell to run.

    Read from the script rather than restated, so this cannot drift into testing a copy. If the
    script stops using this shape the extraction fails loudly rather than silently testing
    nothing, which is the failure mode the file it guards actually had.
    """
    with open(CHECK, encoding="utf-8") as handle:
        body = handle.read()
    block = re.search(r"(tests_re=.*?skipped=\$\(grep[^\n]*\n)", body, re.S)
    assert block, "tools/check.sh no longer counts with an extracted tests_re; update this test"
    return block.group(1)


def _counts(log_text):
    script = 'log=$1\n' + _counting_lines() + '\necho "$passed $failed $skipped"\n'
    with open("/tmp/check-summary-log.txt", "w", encoding="utf-8") as handle:
        handle.write(log_text)
    out = subprocess.run(["sh", "-c", script, "sh", "/tmp/check-summary-log.txt"],
                         capture_output=True, text=True, timeout=30)
    passed, failed, skipped = out.stdout.split()
    return int(passed), int(failed), int(skipped)


class TheJavaSummaryCountsTestsAndNotGradleTest(unittest.TestCase):

    def test_a_class_rollup_and_the_task_line_are_not_failures(self):
        """Two failing methods are two, not four.

        The old count returned 4 here: the two methods, `PlannerLayoutTest FAILED`, and
        `> Task :test FAILED`. The arithmetic then did not reconcile against the collected total,
        which is the symptom that led to this being found.
        """
        passed, failed, skipped = _counts(GRADLE_LOG)
        self.assertEqual(2, failed,
                         "the class roll-up and `> Task :test FAILED` must not be counted as "
                         "tests; the old ' FAILED$' pattern made this 4")
        self.assertEqual(1, passed)
        self.assertEqual(1, skipped)

    def test_a_build_that_never_compiled_reports_no_tests_rather_than_one_failure(self):
        """The case worth fixing for, and the reason this is not cosmetic.

        `> Task :compileJava FAILED` is the entire log. The old count called that one failure
        while the display named no test, so a build that never ran was indistinguishable in the
        summary from a single flaky test. It now reports nothing, and `check.sh` still fails the
        run from gradle's exit status, so the verdict is unchanged and the summary stops
        inventing a test that does not exist.
        """
        passed, failed, skipped = _counts(COMPILE_FAILURE_LOG)
        self.assertEqual((0, 0, 0), (passed, failed, skipped),
                         "a compile failure names no test, so the summary must count none")

    def test_the_three_counts_and_the_display_use_one_pattern(self):
        """The structural half, because the bug was an asymmetry rather than a typo.

        `skipped` was anchored and `failed` was not, in adjacent lines, and that is what let the
        two disagree for as long as they did. Whatever the shape becomes, the count and the
        list a reader compares it against have to be the same shape.
        """
        with open(CHECK, encoding="utf-8") as handle:
            body = handle.read()
        # `assertTrue(re.search(...))` and NOT `assertRegex`, per the note in
        # `test_prodclient_harness.py`: `assertRegex` prints the whole haystack, and `check.sh` is
        # 300 lines of load-bearing comments, so a failure message carrying the entire script
        # buries the one sentence saying what is wrong. Demonstrated while writing this file --
        # the first version used `assertRegex` and its failure output was the script.
        for name in ("passed", "failed", "skipped"):
            found = re.search(name + r'=\$\(grep -cE "\$\{tests_re\}', body)
            self.assertTrue(found,
                            name + " must count with the extracted tests_re, like its siblings")
        self.assertIn('grep -E "${tests_re}(FAILED|SKIPPED)$"', body,
                      "the list of failures a reader checks the count against must use the same "
                      "pattern as the count, or the two can disagree again")


if __name__ == "__main__":
    unittest.main()
