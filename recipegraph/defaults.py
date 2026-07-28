"""Defaults shared between the CLI and the things it launches.

A leaf module on purpose: it imports nothing, so reading a default costs nothing. Putting
the port on `server` instead meant `recipegraph find` imported the web server, the renderers
and the solver -- 16 of its 33ms of import time -- to look up one integer.

The port is part of the USER-FACING contract, not just a default: `/recipedump` tells the
player in chat to open http://localhost:8765, so changing it means changing the string in
mod/src/main/java/io/github/jacoblasky/recipedump/DumpCommand.java, the README and the skill
as well. Those cannot import this. Grep 8765.
"""

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765

# Where the CLI reads and writes by default. Repeated as argparse defaults across several
# subcommands, which is exactly how `plan` and `serve` end up disagreeing about which stock
# file is current.
DEFAULT_GRAPH = "data/graph.json"
DEFAULT_HAVE = "data/ae2_have.json"
DEFAULT_MACHINES = "data/machines.json"
DEFAULT_SOURCES = "data/sources.json"
# Recipe choices made by hand. Named for what it holds rather than for the
# feature, matching machines.json and sources.json beside it.
DEFAULT_PINS = "data/recipes.json"
DEFAULT_TOKENS = "data/tokens.json"
DEFAULT_METRICS_DB = "data/metrics.db"
DEFAULT_COST_CACHE = "data/.cost-cache.json"

# How many tree nodes a plan expands before it stops and says so. The default is the CLI's
# and the server's alike: two copies of 4000 is how `plan` and `serve` end up truncating at
# different depths and only one of them being the number a warning quotes.
DEFAULT_MAX_NODES = 4000

# The ceiling the "go deeper" control cannot pass. `Solver.work_budget` derives from
# max_nodes and IS the termination guarantee, so an unbounded control would let a reader
# hang their own server from a link.
#
# 4x, AND THAT NUMBER IS MEASURED. A typical plan is 0.4s at the default, but the worst
# case is not typical: on the reference pack `avaritia:resource:3` spends its whole work
# budget backtracking and takes 26s at 4,000 nodes, ~110s at 16,000 and 417s at 32,000.
# Cost is roughly linear in the budget, so the ceiling is not bounding an absolute time --
# nothing can, when the FIRST page already takes two minutes on the worst item. What it
# bounds is the MULTIPLE of a wait the reader has already sat through: at most four times
# the page they just loaded, having watched how long that took. 64x, which this was before
# anyone timed it, was over two hours for one click.
#
# Past the ceiling the notice says so rather than offering a button, and points at the
# terminal, which is the right place for a search that big.
MAX_NODES_CEILING = DEFAULT_MAX_NODES * 4
