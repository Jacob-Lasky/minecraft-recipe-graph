#!/usr/bin/env python3
"""Plan one key and print the evidence behind its claims, node by node.

A POSITIVE FINDING NEEDS THE SAME SCRUTINY AS A NEGATIVE, and it needs a different kind.
A negative can be produced by a broken instrument; a positive can be produced by a claim
counter that fires on the wrong thing -- `alternatives` counting a slot that never chose,
`dimension` counting a node the plan does not actually rest on. So this prints the node
that satisfies each claim, with its depth and its own fields, rather than a tick.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# The sweep already resolves the scenario, the cost table and the claim table; importing
# them keeps this from becoming a second spelling of the same thing, which is how two
# measurements of one question end up disagreeing.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib
sweep = importlib.import_module("sweep-dimension-candidates")           # noqa: E402

from recipegraph.model import Graph                                     # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--graph", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--max-nodes", type=int, default=4000)
    args = ap.parse_args()

    graph = Graph.load(args.graph)
    env = sweep.build_env(graph)
    result = sweep.solve_one(graph, env, args.key, args.max_nodes, 0)
    row = sweep.score(result)

    print("KEY        %s  (%s)" % (args.key, graph.display(args.key)))
    print("CLAIMS     %s" % json.dumps(row["held"], sort_keys=True))
    print("HELD       %d of 7" % row["n_held"])
    print("GATE DEPTH %s   below-root=%s" % (row["gate_depths"], row["gate_below_root"]))
    print("SEARCH     nodes=%d work=%d truncated=%s exhausted=%s"
          % (row["nodes"], row["work"], row["truncated"], row["exhausted"]))

    print("\nTHE NODE BEHIND EACH CLAIM")
    for depth, node in sweep.walk(result["tree"]):
        marks = []
        if node.get("dimension"):
            marks.append("dimension=%s" % node["dimension"])
        if (node.get("alt_count") or 0) > 1:
            marks.append("alt_count=%d" % node["alt_count"])
        if node.get("status") == "oredict":
            marks.append("status=oredict")
        if node.get("machine"):
            marks.append("machine=%s" % node["machine"])
        if marks:
            print("  d%-2d %-42s %s" % (depth, node.get("key"), "  ".join(marks)))

    print("\nTHE SPINE FROM ROOT TO THE GATE")
    target = None
    for depth, node in sweep.walk(result["tree"]):
        if node.get("dimension"):
            target = node
            break

    def spine(node, path):
        if node is target:
            return path + [node]
        for child in (node.get("children") or ()):
            found = spine(child, path + [node])
            if found:
                return found
        return None

    for i, node in enumerate(spine(result["tree"], []) or []):
        print("  d%-2d %-42s status=%-8s %s"
              % (i, node.get("key"), node.get("status"),
                 node.get("dimension") or node.get("machine") or ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
