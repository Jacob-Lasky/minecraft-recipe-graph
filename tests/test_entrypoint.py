"""The command the README tells you to type has to be a command that exists.

Every example in the README is `recipegraph <verb>`, and there is no such executable in the
repo by design: stdlib only, no `setup.py`, no console_scripts, you clone it and alias it.
That leaves three things that can silently disagree with each other, none of which any other
test looks at:

  * whether `python -m recipegraph` runs at all (it did not, until `__main__.py`),
  * whether `argparse` still calls itself `recipegraph`, since the README's alias and its
    own usage strings both depend on that one string,
  * whether the Dockerfile's CMD still names a module that exists.

All three are runtime string matches across file boundaries, so they fail in front of a new
user rather than in CI unless something asserts them.
"""

import os
import re
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from recipegraph import cli  # noqa: E402

# What the README documents and what `argparse` prints. One string, two files.
COMMAND = "recipegraph"


def _run(args):
    return subprocess.run([sys.executable] + args, cwd=ROOT, capture_output=True, text=True)


class ModuleIsExecutableTest(unittest.TestCase):
    def test_python_m_recipegraph_runs(self):
        # The exact invocation the Quickstart gives. Before `__main__.py` this exited 1 with
        # "'recipegraph' is a package and cannot be directly executed".
        got = _run(["-m", "recipegraph", "--help"])
        self.assertEqual(got.returncode, 0, got.stderr)
        self.assertIn("usage: %s" % COMMAND, got.stdout)

    def test_a_subcommand_actually_dispatches(self):
        # `--help` alone would pass even if `__main__` never reached `cli.main`, because
        # argparse exits during parsing.
        got = _run(["-m", "recipegraph", "plan", "--help"])
        self.assertEqual(got.returncode, 0, got.stderr)
        self.assertIn("--ignore-stock", got.stdout)

    def test_the_module_the_dockerfile_names_still_runs(self):
        # The CMD is a fixed argv with no shell, so a rename here takes the container down
        # at start rather than at build.
        got = _run(["-m", "recipegraph.cli", "--help"])
        self.assertEqual(got.returncode, 0, got.stderr)


class DocumentedNameMatchesTest(unittest.TestCase):
    def test_argparse_calls_itself_what_the_readme_calls_it(self):
        parser_prog = re.search(r'ArgumentParser\(prog="([^"]+)"', _source(cli))
        self.assertIsNotNone(parser_prog, "cli no longer sets an explicit prog")
        self.assertEqual(parser_prog.group(1), COMMAND)

    def test_the_readme_documents_the_alias(self):
        # Without it every command in the README is one a reader cannot run. This is the
        # assertion that fails if someone trims the Quickstart.
        readme = _read("README.md")
        self.assertIn('alias %s="python3 -m recipegraph"' % COMMAND, readme)
        self.assertIn("git clone", readme)

    def test_the_dockerfile_cmd_names_a_real_module(self):
        dockerfile = _read("Dockerfile")
        self.assertRegex(dockerfile, r'"-m",\s*"recipegraph\.cli"')
        self.assertTrue(os.path.exists(os.path.join(ROOT, "recipegraph", "cli.py")))


def _read(name):
    with open(os.path.join(ROOT, name)) as fh:
        return fh.read()


def _source(module):
    with open(module.__file__) as fh:
        return fh.read()


if __name__ == "__main__":
    unittest.main()
