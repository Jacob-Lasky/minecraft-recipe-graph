#!/usr/bin/env python3
"""What a cost-model tuning change actually does to recipe choice.

WHY THIS IS CHECKED IN. `skill/SKILL.md` says the cost model is load-bearing and fails
SILENTLY: a wrong constant does not raise, it just quietly reroutes plans, and the table
still looks populated. Until this existed there was no way to see the damage or the
benefit of moving one, so every constant in `cost.py` was either argued about or left
alone. #61 was left alone for exactly that reason.

    python3 tools/cost-probe.py                      # the default sweep
    python3 tools/cost-probe.py --raw 1 2.5 20       # your own values
    python3 tools/cost-probe.py --rank               # ranking only, ~50x faster
    python3 tools/cost-probe.py --item minecraft:diamond --explain

TWO MODES, AND THE DIFFERENCE MATTERS. `--rank` asks `pick_recipe` which route wins; the
default runs a real `solve`. They disagree, and the ranking is the one that lies: it does
not run the cycle guard, so a nugget route (9 nuggets -> ingot, ingot -> 9 nuggets) looks
like the best answer in the world and dies the moment the solver expands it. Measured on
the reference pack, raising BASE_RAW_COST to 20 reorders 1,962 of 21,468 items in the
RANKING and changes almost nothing in real plans. Draw conclusions from the slow mode.

Dev tooling only, alongside the other two audits. The tool itself is Python 3 stdlib.
"""

import argparse
import collections
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod                      # noqa: E402
from recipegraph import machines as machines_mod              # noqa: E402
from recipegraph.model import Graph                           # noqa: E402
from recipegraph.solve import Solver                          # noqa: E402

# Items chosen because each one has a real choice to get wrong, and because a human can
# judge the answer at a glance: everybody knows where a diamond comes from. Four of them
# are the routes that are currently RIGHT and are here as the control -- a tuning change
# that fixes Diamond and breaks Iron Ingot is not a fix.
PROBES = [
    ("minecraft:diamond", "Diamond"), ("minecraft:stick", "Stick"),
    ("minecraft:dye:4", "Lapis"), ("minecraft:coal", "Coal"),
    ("minecraft:emerald", "Emerald"), ("minecraft:gunpowder", "Gunpowder"),
    ("minecraft:slime_ball", "Slimeball"), ("minecraft:bone", "Bone"),
    ("minecraft:clay_ball", "Clay"), ("minecraft:blaze_rod", "Blaze Rod"),
    ("minecraft:redstone", "Redstone"), ("minecraft:string", "String"),
    ("minecraft:paper", "Paper"), ("minecraft:leather", "Leather"),
    # The control group: these four are correct today.
    ("minecraft:iron_ingot", "Iron Ingot"), ("minecraft:gold_ingot", "Gold Ingot"),
    ("minecraft:glass", "Glass"), ("nuclearcraft:compound:7", "Borax"),
]


def load(graph_path):
    graph = Graph.load(graph_path)
    return graph, machines_mod.resolve(graph)


def route(graph, produced, tree_or_recipe):
    """`category <- first few inputs`, marking any input NOTHING in the graph makes."""
    if tree_or_recipe is None:
        return "(no route)"
    if hasattr(tree_or_recipe, "inputs"):
        cat = tree_or_recipe.category or "-"
        keys = [i.alternatives[0] for i in tree_or_recipe.inputs[:3] if i.alternatives]
        names = [graph.bare_name(k) for k in keys]
    else:
        cat = tree_or_recipe.get("category") or "-"
        kids = tree_or_recipe.get("children", [])[:3]
        keys = [c["key"] for c in kids]
        names = [c.get("label") or c["key"] for c in kids]
    marked = ["%s%s" % (n, "" if k in produced else "*") for k, n in zip(keys, names)]
    return "%-28s <- %s" % (cat[:28], ", ".join(marked) or "(nothing)")


def sweep(graph, states, values, rank_only, items):
    produced = graph.by_output
    rows = collections.OrderedDict()
    for value in values:
        cost_mod.BASE_RAW_COST = value
        started = time.time()
        costs = cost_mod.estimate(graph, machine_states=states)
        solver = Solver(graph, machine_states=states, costs=costs)
        answers = {}
        for key, label in items:
            if rank_only:
                answers[label] = route(graph, produced, solver.pick_recipe(key))
            else:
                fresh = Solver(graph, machine_states=states, costs=costs)
                answers[label] = route(graph, produced, fresh.solve(key, 1)["tree"])
        rows[value] = (answers, time.time() - started)
    return rows


def report(rows, items):
    values = list(rows)
    base = rows[values[0]][0]
    for value in values:
        answers, seconds = rows[value]
        moved = sum(1 for _k, label in items if answers[label] != base[label])
        print("=== BASE_RAW_COST=%s  (%d of %d probes differ from %s, %.0fs) ==="
              % (value, moved, len(items), values[0], seconds))
        for _key, label in items:
            flag = " " if answers[label] == base[label] else ">"
            print(" %s %-11s %s" % (flag, label, answers[label]))
        print()
    print("'*' marks an input NOTHING in the graph produces: the plan's advice is to go "
          "and find one.")


def explain(graph, states, key, limit):
    """Every real producer of one item, best-ranked first, with the score that decided it.

    The view that made #61 legible. Three routes to a diamond TIE on cost at -2.0 -- a
    decorative panel, a loot token and a real ore -- because every recipe-less input is
    priced at BASE_RAW_COST, and a later component of the score picks between them.
    """
    costs = cost_mod.estimate(graph, machine_states=states)
    solver = Solver(graph, machine_states=states, costs=costs)
    produced = graph.by_output
    candidates = graph.real_producers(key)
    print("%s: %d real producers\n" % (graph.bare_name(key), len(candidates)))
    for recipe in sorted(candidates, key=solver.score_recipe, reverse=True)[:limit]:
        print("  %-40s %s" % (solver.score_recipe(recipe),
                              route(graph, produced, recipe)))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--graph", default="data/graph.json")
    ap.add_argument("--raw", nargs="+", type=float, default=[1.0, 2.5, 5.0, 20.0],
                    help="BASE_RAW_COST values to compare; the first is the baseline")
    ap.add_argument("--rank", action="store_true",
                    help="rank only, no solve: fast, and it LIES (see the module docstring)")
    ap.add_argument("--item", action="append", default=[],
                    help="probe this key instead of the built-in set; repeatable")
    ap.add_argument("--explain", action="store_true",
                    help="score every producer of --item rather than sweeping")
    ap.add_argument("--limit", type=int, default=12)
    args = ap.parse_args()

    graph, states = load(args.graph)
    if args.explain:
        if not args.item:
            ap.error("--explain needs --item")
        for key in args.item:
            explain(graph, states, key, args.limit)
        return
    items = [(k, graph.bare_name(k)) for k in args.item] or PROBES
    items = [(k, n) for k, n in items if graph.real_producers(k)]
    report(sweep(graph, states, args.raw, args.rank, items), items)


if __name__ == "__main__":
    main()
