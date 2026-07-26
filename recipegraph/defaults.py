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
DEFAULT_METRICS_DB = "data/metrics.db"
DEFAULT_COST_CACHE = "data/.cost-cache.json"
