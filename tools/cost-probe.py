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

RUN THE ARMS IN SEPARATE PROCESSES, AND CLEAR `__pycache__` BETWEEN THEM IF YOU PATCH
SOURCE BY HAND. `/coding` is a FUSE shfs mount with one-second mtime granularity, so an
edit that lands within the same second as the previous one AND leaves the file the same
length is invisible to Python's bytecode cache -- the stale `.pyc` is served and the arm
silently measures the previous one. That is not hypothetical: swapping two terms in a
tuple is exactly such an edit (same characters, same length), and it produced an A/B result
where two different orderings reported identical failures. `find . -name __pycache__ -prune
-exec rm -rf {} +` before each arm. This tool's own `--raw` sweep is unaffected because it
mutates a module attribute in one process rather than editing a file.

Dev tooling only, alongside the other two audits. The tool itself is Python 3 stdlib.
"""

import argparse
import collections
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod                      # noqa: E402
from recipegraph import tokens as tokens_mod                  # noqa: E402
from recipegraph.defaults import DEFAULT_GRAPH                # noqa: E402
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
    """`(graph, machine states, build targets)`.

    THROUGH `describe`, NOT `resolve`, and the build targets are not optional. `resolve` is
    the two-value view that drops the machine ITEM, so `estimate` got no `machine_items`, so
    `machine_entry_costs` never ran and every buildable category was priced at the flat
    `MACHINE_COST["buildable"]`. This tool exists to check cost-model tuning, and it was
    structurally unable to see the two constants that price a machine you have to build
    (BUILD_SCALE, BUILD_KNEE) or the multiblock structures #93 added. A probe that cannot
    observe the thing being tuned reports "no change" and is believed.
    """
    graph = Graph.load(graph_path)
    info = machines_mod.describe(graph, {}, {})
    states = {uid: (i["state"], i["why"]) for uid, i in info.items()}
    return graph, states, machines_mod.build_targets(info)


def route(graph, solver, tree_or_recipe):
    """`category <- first few inputs`, marking any input NOTHING in the graph makes.

    THE ALTERNATIVE SHOWN IS THE ONE THE SOLVER WOULD EXPAND, via
    `Solver.pick_alternative`, not `alternatives[0]`. The dump's first alternative is not
    the chosen one -- that is issue #29's exact misreading, the one whose fix this tool is
    supposed to be able to check. Printing element 0 made the default table report Iron
    Ingot as "Abyssal Iron Ore" and Gold Ingot as "Sandslash", i.e. it misread two of the
    four probes that exist to be the control group.

    `real_producers`, not `by_output`, for the `*` marker: a key produced only by a
    wildcard-meta recipe is obtainable and was being starred, and a fluid whose only
    producer is a container transfer is NOT and was not.
    """
    if tree_or_recipe is None:
        return "(no route)"
    if hasattr(tree_or_recipe, "inputs"):
        cat = tree_or_recipe.category or "-"
        keys = [solver.pick_alternative(i) for i in tree_or_recipe.inputs[:3]
                if i.alternatives]
        names = [graph.bare_name(k) for k in keys]
        raw = [not graph.real_producers(k) for k in keys]
    else:
        cat = tree_or_recipe.get("category") or "-"
        kids = tree_or_recipe.get("children", [])[:3]
        keys = [c["key"] for c in kids]
        names = [c.get("label") or c["key"] for c in kids]
        # The solver already decided; `raw` is its own verdict rather than a re-derivation.
        raw = [c.get("status") == "raw" for c in kids]
    marked = ["%s%s" % (n, "*" if r else "") for r, n in zip(raw, names)]
    return "%-28s <- %s" % (cat[:28], ", ".join(marked) or "(nothing)")


def sweep(graph, states, values, rank_only, items, machine_items=None, token_kinds=None):
    """`{raw cost: ({label: route}, seconds)}`.

    Restores `cost.BASE_RAW_COST` on the way out. The tool is a one-shot CLI so the leak
    was harmless in practice, but a module constant left mutated is a trap for the next
    caller and there is no reason to leave it set.
    """
    was = cost_mod.BASE_RAW_COST
    rows = collections.OrderedDict()
    try:
        for value in values:
            cost_mod.BASE_RAW_COST = value
            started = time.time()
            costs = cost_mod.estimate(graph, machine_states=states,
                                      machine_items=machine_items,
                                      token_kinds=token_kinds)
            solver = Solver(graph, machine_states=states, costs=costs,
                            token_kinds=token_kinds)
            answers = {}
            for key, label in items:
                if rank_only:
                    answers[label] = route(graph, solver, solver.pick_recipe(key))
                else:
                    fresh = Solver(graph, machine_states=states, costs=costs,
                                   token_kinds=token_kinds)
                    answers[label] = route(graph, fresh, fresh.solve(key, 1)["tree"])
            rows[value] = (answers, time.time() - started)
    finally:
        cost_mod.BASE_RAW_COST = was
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


def explain(graph, states, key, limit, raw_cost=None, machine_items=None,
            token_kinds=None):
    """Every real producer of one item, best-ranked first, with the score that decided it.

    The view that made #61 legible. Three routes to a diamond TIE on cost at -2.0 -- a
    decorative panel, a loot token and a real ore -- because every recipe-less input is
    priced at BASE_RAW_COST, and a later component of the score picks between them.
    """
    # `--raw` applies here too. It used to be silently ignored, so `--explain --raw 20`
    # printed the DEFAULT constant's scores under a heading that implied otherwise.
    was = cost_mod.BASE_RAW_COST
    if raw_cost is not None:
        cost_mod.BASE_RAW_COST = raw_cost
    try:
        costs = cost_mod.estimate(graph, machine_states=states,
                                  machine_items=machine_items, token_kinds=token_kinds)
        solver = Solver(graph, machine_states=states, costs=costs,
                        token_kinds=token_kinds)
        candidates = graph.real_producers(key)
        print("%s: %d real producers, BASE_RAW_COST=%s\n"
              % (graph.bare_name(key), len(candidates), cost_mod.BASE_RAW_COST))
        for recipe in sorted(candidates, key=solver.score_recipe, reverse=True)[:limit]:
            print("  %-40s %s" % (solver.score_recipe(recipe),
                                  route(graph, solver, recipe)))
    finally:
        cost_mod.BASE_RAW_COST = was


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--graph", default=DEFAULT_GRAPH)
    ap.add_argument("--raw", nargs="+", type=float, default=[1.0, 2.5, 5.0, 20.0],
                    help="BASE_RAW_COST values to compare; the first is the baseline")
    ap.add_argument("--rank", action="store_true",
                    help="rank only, no solve: fast, and it LIES (see the module docstring)")
    ap.add_argument("--item", action="append", default=[],
                    help="probe this key instead of the built-in set; repeatable")
    ap.add_argument("--explain", action="store_true",
                    help="score every producer of --item rather than sweeping")
    ap.add_argument("--limit", type=int, default=12)
    # Priced with the token map, since #105. Without it the probe scores routes with
    # gates at 1.0 while the server charges 1,000, so the "control group" this tool is
    # used as would disagree with production on any route touching one.
    ap.add_argument("--tokens", help="tokens.json")
    args = ap.parse_args()

    graph, states, targets = load(args.graph)
    token_kinds = tokens_mod.for_path(args.tokens)
    if args.explain:
        if not args.item:
            ap.error("--explain needs --item")
        if len(args.raw) > 1:
            ap.error("--explain takes one --raw value, got %d" % len(args.raw))
        for key in args.item:
            explain(graph, states, key, args.limit, args.raw[0], targets, token_kinds)
        return
    items = [(k, graph.bare_name(k)) for k in args.item] or PROBES
    # SAY what was dropped. A mistyped --item, or a probe whose key a re-dump renamed, used
    # to vanish and leave the sweep reporting "0 of 0" after a fifteen-minute run -- and a
    # control-group probe could disappear from the control group without a word.
    kept, dropped = [], []
    for k, n in items:
        (kept if graph.real_producers(k) else dropped).append((k, n))
    for k, _n in dropped:
        print("skipping %s: no real producers" % k, file=sys.stderr)
    if not kept:
        ap.error("none of the %d requested items has a real producer" % len(items))
    report(sweep(graph, states, args.raw, args.rank, kept, targets, token_kinds), kept)


if __name__ == "__main__":
    main()
