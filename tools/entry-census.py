#!/usr/bin/env python3
"""Where every machine's entry cost lands, and WHY it landed there.

WHY THIS IS CHECKED IN. `tools/cost-probe.py` is the tool the repo requires before moving a
cost constant, and it answers "did any of these 18 routes change". It cannot see the thing
#95 turned out to be about: 140 of 403 buildable categories sharing one number, because
everything a price could not be computed for collapsed onto the band ceiling. A census is
invisible to a route probe -- every one of those 140 had a perfectly stable route, and the
defect was that no two of them could be told apart. Run BOTH when touching the band.

    python3 tools/entry-census.py                       # the graph's own machine states
    python3 tools/entry-census.py --have data/ae2_have.json --machines data/machines.json
    python3 tools/entry-census.py --blocked 12          # the least-blocked structures
    python3 tools/entry-census.py --blocking-keys 30    # and WHICH blocks did the blocking

USE --have AND --machines TO REPRODUCE A SERVER. Machine states differ between a bare graph
and a running instance -- a placed machine is `have` and drops out of the census entirely --
so a prediction made without them describes a configuration nobody is running.

Dev tooling only, alongside the other audits. Python 3 stdlib.
"""

import argparse
import collections
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod                      # noqa: E402
from recipegraph import generators as generators_mod          # noqa: E402
from recipegraph import machines as machines_mod              # noqa: E402
from recipegraph import multiblocks as multiblocks_mod        # noqa: E402
from recipegraph import tokens as tokens_mod                  # noqa: E402
from recipegraph.defaults import DEFAULT_GRAPH                # noqa: E402
from recipegraph.model import Graph                           # noqa: E402

INF = float("inf")


def load_stock(path):
    """`(have, placed)` from a `have` output, spelled the way `server.State` spells it.

    Through the same three namespaces the server uses (items, fluids, essentia). Reading only
    `items` would silently drop every fluid from the stock and reprice the chemistry chains,
    which is the sort of divergence that makes a tool's answer disagree with the running
    server for no visible reason.
    """
    if not path or not os.path.exists(path):
        return {}, {}
    with open(path) as fh:
        doc = json.load(fh)
    have = dict(doc.get("items") or {})
    for name, amount in (doc.get("fluids") or {}).items():
        have["fluid:%s" % name] = amount
    for aspect, amount in (doc.get("essentia") or {}).items():
        have["essentia:%s" % str(aspect).lower()] = amount
    return have, doc.get("placed") or {}


def regions():
    """`[(label, lo, hi)]`, the bands `machine_entry_costs` can put a category in."""
    return [("priced", cost_mod.MACHINE_COST["buildable"], cost_mod.PRICED_CEILING),
            ("unpriced item", cost_mod.UNPRICED_MACHINE_COST,
             cost_mod.UNPRICED_MACHINE_COST),
            ("blocked structure", cost_mod.BLOCKED_FLOOR, cost_mod.BLOCKED_CEILING)]


def region_of(value):
    for label, lo, hi in regions():
        if lo - 1e-9 <= value <= hi + 1e-9:
            return label
    return "OUTSIDE THE BAND"


def census(graph, have, placed, machines_path, sources_path, tokens_path=None):
    info = machines_mod.describe(
        graph, placed, have, overrides=machines_mod.load_overrides(machines_path),
        no_machine=machines_mod.load_no_machine(machines_path))
    states = {uid: (i["state"], i["why"]) for uid, i in info.items()}
    free = generators_mod.resolve(placed, have,
                                  generators_mod.load_overrides(sources_path))
    targets = machines_mod.build_targets(info)
    # Uncached on purpose: a census exists to describe the constants in this working tree, and
    # a warm .cost-cache.json would answer for whichever ones were current when it was written.
    costs = cost_mod.estimate(graph, have=have, machine_states=states,
                              free_sources=free, machine_items=targets,
                              token_kinds=tokens_mod.for_path(tokens_path))
    return states, targets, costs, dict(getattr(costs, "machine_entry", None) or {})


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--graph", default=DEFAULT_GRAPH)
    ap.add_argument("--have", help="a `have` output, to price against real stock")
    ap.add_argument("--machines", help="machines.json, for manual state overrides")
    ap.add_argument("--sources", help="sources.json")
    # Priced with the token map too, since #105: a census that left it out would report
    # entry costs the running server does not charge.
    ap.add_argument("--tokens", help="tokens.json")
    ap.add_argument("--blocked", type=int, default=8,
                    help="how many least-blocked structures to list")
    ap.add_argument("--blocking-keys", type=int, default=12,
                    help="how many blocking BLOCKS to list, worst first (0 to skip)")
    args = ap.parse_args()

    graph = Graph.load(args.graph)
    have, placed = load_stock(args.have)
    states, targets, costs, entry = census(graph, have, placed, args.machines,
                                           args.sources, args.tokens)

    by_state = collections.Counter(s[0] for s in states.values())
    print("machine states: %s" % dict(by_state.most_common()))
    print("categories with build targets: %d\n" % len(targets))

    controllers = {}
    for reg, e in (graph.multiblocks or {}).items():
        controllers[e.get("controller")] = (reg, e)
    mm = {c for c, keys in targets.items() if any(k in controllers for k in keys)}

    print("%-20s %-6s %-6s %-9s %-9s %s" % ("region", "all", "MM", "min", "max", "distinct"))
    for label, _lo, _hi in regions() + [("OUTSIDE THE BAND", 0, 0)]:
        vals = [v for c, v in entry.items() if region_of(v) == label]
        if not vals:
            continue
        n_mm = sum(1 for c, v in entry.items() if region_of(v) == label and c in mm)
        print("%-20s %-6d %-6d %-9.3f %-9.3f %d"
              % (label, len(vals), n_mm, min(vals), max(vals),
                 len(set(round(v, 6) for v in vals))))

    shared = [(n, v) for v, n in
              collections.Counter(round(v, 3) for v in entry.values()).most_common(5)]
    print("\nmost-shared values (a big cluster is where discrimination is being lost):")
    for n, v in shared:
        print("  %8.3f  %4d categories   [%s]" % (v, n, region_of(v)))

    rows = []
    for category, keys in targets.items():
        for key in keys:
            hit = controllers.get(key)
            if hit is None or math.isinf(costs.get(key, INF)):
                continue
            reg, e = hit
            if not math.isinf(multiblocks_mod.structure_cost(e, costs)):
                continue
            parts = e.get("parts") or []
            total = sum(n for n, _ in parts)
            bad = sum(n for n, ks in parts
                      if multiblocks_mod.position_cost(ks, costs) == INF)
            rows.append((bad / float(total or 1), bad, total, reg, entry.get(category)))
            break
    rows.sort()
    if rows:
        print("\nblocked structures, least blocked first (%d of them):" % len(rows))
        for f, bad, total, reg, e in rows[:args.blocked]:
            print("  %-42s %6.2f%% blocked (%6d/%6d)  entry %7.3f"
                  % (reg[:42], f * 100, bad, total, e if e is not None else -1))
        print("  ...")
        for f, bad, total, reg, e in rows[-2:]:
            print("  %-42s %6.2f%% blocked (%6d/%6d)  entry %7.3f"
                  % (reg[:42], f * 100, bad, total, e if e is not None else -1))

    if args.blocking_keys:
        blocking_keys(graph, costs, args.blocking_keys)


def blocking_keys(graph, costs, limit):
    """WHICH blocks did the blocking, and on what grounds.

    #100: the census could rank 166 structures by how blocked they are and could not name a
    single blocking block, so nothing downstream could check whether the ordinal rested on
    anything. The reason split is the part that matters -- see `multiblocks.blocked_reason`
    for why "produced, never priced" is a claim about this model rather than about the pack.
    """
    positions = collections.Counter()
    structures = collections.Counter()
    for entry in (graph.multiblocks or {}).values():
        for key, count in multiblocks_mod.blocking_keys(entry, costs).items():
            positions[key] += count
            structures[key] += 1
    if not positions:
        print("\nno blocked positions: every structure can be placed")
        return

    reasons = {k: multiblocks_mod.blocked_reason(graph, k, costs) for k in positions}
    total = sum(positions.values())
    print("\nblocking blocks: %d distinct over %d positions" % (len(positions), total))
    for reason in multiblocks_mod.BLOCKED_REASONS:
        keys = [k for k, r in reasons.items() if r == reason]
        pos = sum(positions[k] for k in keys)
        print("  %-24s %4d keys  %7d positions  %5.1f%%"
              % (reason, len(keys), pos, 100.0 * pos / total))

    print("\n  %-46s %8s %7s  %s" % ("block", "positions", "structs", "why"))
    for key, count in positions.most_common(limit):
        print("  %-46s %8d %7d  %s" % (key[:46], count, structures[key], reasons[key]))


if __name__ == "__main__":
    main()
