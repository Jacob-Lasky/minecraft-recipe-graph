"""Make `python -m recipegraph` work, because that is what the README's `recipegraph` is.

Every example in the README reads `recipegraph plan ...`, and there is no such executable
anywhere in the repo: no `setup.py`, no `pyproject.toml`, no console_scripts entry point,
and deliberately none of those, since the project is stdlib-only and installs by being
cloned. Before this file, `python -m recipegraph` answered "'recipegraph' is a package and
cannot be directly executed", so the only invocation that worked was
`python -m recipegraph.cli`, which the README never mentions and only the Dockerfile knows.

DO NOT resolve this the other way by rewriting the README to say `python -m recipegraph.cli`
everywhere. `argparse` already reports itself as `prog="recipegraph"` (cli.py), so its own
usage strings and error messages name a command the docs must match; changing the docs would
leave `--help` disagreeing with them instead.

The Dockerfile keeps calling `recipegraph.cli` explicitly. That is not redundancy to tidy
away: the CMD is a fixed argv the image is expected to run without a shell, and naming the
module it actually executes means a broken `__main__` cannot take the container down with it.
"""

import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
