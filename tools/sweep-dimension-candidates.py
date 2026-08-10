#!/usr/bin/env python3
"""Enumerate every key that can reach a dimension gate, and score it against
`dimension-in-chain`'s claims.

WHY THIS IS AN ENUMERATION AND NOT A SAMPLE. The question is "does a key exist whose plan
holds all seven of the claims `dimension-in-chain` used to make, with the gate found BELOW
the root". That is a targeted search for a rare positive, not a prevalence estimate, and a
sample sized for the second answers the first wrongly: #248's sweep drew 3,000 of 29,349
candidates for 4 positives -- expected hits 0.41 -- found none, and reported that none
existed. All four were there. A sample too small to detect what it is looking for returns
exactly the output of a world without it, so a negative from one is not evidence.

The population is computable exactly. A plan can only show a `dimension` node if some node
in its tree is a gated ore, and a gated ore can only appear under a key that transitively
DEPENDS on it. So walk `Graph.consumers` outward from the gate set and the closure is the
whole population -- everything outside it is a proven negative without being planned.

THE LAYER NUMBERING IS OFF-BY-ONE BAIT AND IT HAS BITTEN HERE. An earlier sweep on this repo
started its walk at the first layer of consumers' consumers and silently skipped all 22
direct consumers, which is the layer richest in short chains. Layer 1 IS part of the
population; `--controls` asserts the four known positives are enumerated before anything is
planned, because a generator that drops them makes every downstream zero meaningless.

A ZERO FROM A SEARCH IS A CLAIM ABOUT THE SEARCH until the search has been made to fail on
purpose, so `--controls` also runs the claim counters against a plan that MUST light them.
`oredict` and `alternatives` are the two claims the swap dropped, and a sweep reporting that
nothing holds them is indistinguishable from a sweep whose counters never fire.
"""

import argparse
import collections
import json
import os
import signal
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod                      # noqa: E402
from recipegraph import dimensions as dimensions_mod          # noqa: E402
from recipegraph import generators as generators_mod          # noqa: E402
from recipegraph import machines as machines_mod              # noqa: E402
from recipegraph import projecte as projecte_mod              # noqa: E402
from recipegraph import tokens as tokens_mod                  # noqa: E402
from recipegraph.defaults import DEFAULT_MAX_NODES            # noqa: E402
from recipegraph.model import Graph                           # noqa: E402
from recipegraph.solve import Solver                          # noqa: E402

# The scenario `dimension-in-chain` runs in, spelled exactly as `make-java-fixtures.BARE`
# does. Overworld only, nothing in stock, no machines placed: the gate set is then the full
# ten and the plan has no stock to short-circuit a chain with.
OVERWORLD_ONLY = {".": 1}

# The seven claims the fixture made before #248 swapped its target. `oredict` and
# `alternatives` are the two it dropped, measured rather than assumed, and they are the two
# this sweep exists to find a home for.
SEVEN = ("craft", "raw", "dimension", "machine", "not_truncated", "oredict", "alternatives")

# The four keys #248 found by exhaustive hop-0/hop-1 enumeration, one per dimension, all at
# depth 3. THE POSITIVE CONTROL FOR THE POPULATION GENERATOR: if the walk does not enumerate
# these, its zeroes mean nothing.
KNOWN_POSITIVES = ("contenttweaker:material_part:77", "contenttweaker:material_part:55",
                   "contenttweaker:material_part:83", "contenttweaker:material_part:119")

# Puts its gate at depth 1 rather than 3 -- rejected as a fixture target, kept here because
# it is a second independent thing the walk must find.
KNOWN_SHALLOW = "nuclearcraft:dust:4"

# THE NEGATIVE CONTROL FOR THE POPULATION GENERATOR IS DERIVED, NOT NAMED, and the first
# attempt at naming one is why. `minecraft:cobblestone` was written here as the obvious
# thing that depends on nothing gated, and the walk enumerated it -- correctly. The closure
# of `consumers` is not a short chain of ore-to-ingot steps; at hop 4 it is 22,196 keys and
# by hop 21 it has swallowed 48,663, which is most of the pack. Naming a key by intuition
# tests the intuition, not the walk.
#
# So the control is computed: take a key the walk did NOT reach, and require that its plan
# shows no dimension node. That is the claim the closure actually makes -- everything
# outside is a proven negative without being planned -- and it is the one that has to be
# made to fail on purpose before any zero inside it means anything.
def outside_control(graph, reached):
    """The lexicographically first live key the walk did not reach, or None."""
    for key in sorted(graph.live_keys):
        if key not in reached:
            return key
    return None

# THE POSITIVE CONTROL FOR THE CLAIM COUNTERS, and it is a different check from the one
# above. `fluid-chain` is the fixture that still asserts `oredict` AND `alternatives`, so a
# run in which this plan does not light both has dead counters and cannot report their
# absence anywhere else.
COUNTER_CONTROL = "fluid:nethengeic_fluid"


def walk(node, depth=0):
    """`(depth, node)` over the whole tree. Depth 0 is the requested item itself."""
    stack = [(depth, node)]
    while stack:
        d, n = stack.pop()
        yield d, n
        for child in (n.get("children") or ()):
            stack.append((d + 1, child))


def statuses(result):
    return collections.Counter(n.get("status") for _, n in walk(result["tree"]))


# Byte-for-byte the semantics of `make-java-fixtures.CHECKS` for the seven claims in play.
# Spelled out rather than imported because that module runs a 6-minute generation at import
# scope of its target table; the definitions are small and `--controls` proves they agree
# with the shipped fixture's recorded `covers`.
CHECKS = {
    "craft": lambda r: statuses(r).get("craft"),
    "raw": lambda r: statuses(r).get("raw"),
    "oredict": lambda r: statuses(r).get("oredict"),
    "alternatives": lambda r: sum(1 for _, n in walk(r["tree"])
                                  if (n.get("alt_count") or 0) > 1),
    "dimension": lambda r: sum(1 for _, n in walk(r["tree"]) if n.get("dimension")),
    "machine": lambda r: sum(1 for _, n in walk(r["tree"]) if n.get("machine")),
    "not_truncated": lambda r: not r["truncated"],
}


def gate_depths(result):
    """Depths at which a `dimension` node sits. `[0]` means the gate is AT the root."""
    return sorted(d for d, n in walk(result["tree"]) if n.get("dimension"))


def score(result):
    held = {tag: bool(CHECKS[tag](result)) for tag in SEVEN}
    depths = gate_depths(result)
    return {
        "held": held,
        "n_held": sum(1 for v in held.values() if v),
        "gate_depths": depths,
        # BELOW THE ROOT is the whole point of the fixture: `dimension-gate` already covers
        # a gated ore planned directly, so a candidate whose only gate sits at depth 0 adds
        # no coverage however many other claims it holds.
        "gate_below_root": bool([d for d in depths if d > 0]),
        "nodes": result["nodes"],
        "work": result["work"],
        "truncated": result["truncated"],
        "exhausted": result["exhausted"],
    }


def build_env(graph):
    """The BARE priced environment, resolved exactly as `make-java-fixtures` resolves it."""
    info = machines_mod.describe(graph, {}, {}, overrides={}, no_machine=())
    derived = {
        "have": {},
        "craftables": set(),
        "raw": set(),
        "states": {uid: (i["state"], i["why"]) for uid, i in info.items()},
        "free": generators_mod.resolve({}, {}, {}),
        "tokens": tokens_mod.resolve({}, graph),
        "gates": dimensions_mod.gates_for(graph, OVERWORLD_ONLY),
        "targets": machines_mod.build_targets(info),
        "emc_available": projecte_mod.available(graph, {}),
    }
    derived["costs"] = cost_mod.estimate(
        graph, have=derived["have"], machine_states=derived["states"],
        free_sources=derived["free"], machine_items=derived["targets"],
        token_kinds=derived["tokens"], dimension_gates=derived["gates"],
        emc_available=derived["emc_available"], craftables=derived["craftables"],
        raw=derived["raw"])
    return derived


class Timeout(Exception):
    """A key that ran out of wall clock. UNMEASURED, and never a negative.

    THE CAP IS NOT A SMALLER BUDGET. Lowering `max_nodes` or `work_budget` would make slow
    keys finish, and it would finish them into a DIFFERENT answer: `not_truncated` is one of
    the seven claims, so a key capped into truncation reports 6 of 7 and looks like a
    measured near-miss. That is a fabricated finding. A wall-clock cap leaves the solver's
    parameters exactly as the fixture's and records that this key has no answer yet, which is
    a hole the report has to print rather than a result it gets to keep.
    """


def _alarm(signum, frame):                                     # noqa: ARG001
    raise Timeout()


def solve_one(graph, env, key, max_nodes, seconds=0):
    solver = Solver(graph, have=env["have"], craftables=env["craftables"],
                    raw=env["raw"], machine_states=env["states"], costs=env["costs"],
                    free_sources=env["free"], token_kinds=env["tokens"],
                    pinned={}, max_nodes=max_nodes,
                    dimension_gates=env["gates"], emc_available=env["emc_available"])
    if not seconds:
        return solver.solve(key, 1)
    signal.signal(signal.SIGALRM, _alarm)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        return solver.solve(key, 1)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)


def population(graph, gated):
    """`(layers, key -> hop)`. Layer 0 is the gate set; layer n is what layer n-1 feeds.

    A key enters at the SHORTEST hop that reaches it, so the layers partition the closure
    and `hop` is a real distance rather than an artefact of visit order.
    """
    layers = [sorted(gated)]
    seen = set(gated)
    hop_of = {k: 0 for k in gated}
    frontier = set(gated)
    while frontier:
        nxt = set()
        for key in frontier:
            for recipe in graph.consumers(key):
                for out in recipe.outputs:
                    okey = out[0] if isinstance(out, (list, tuple)) else out
                    if okey not in seen:
                        nxt.add(okey)
        if not nxt:
            break
        seen |= nxt
        hop = len(layers)
        for k in nxt:
            hop_of[k] = hop
        layers.append(sorted(nxt))
        frontier = nxt
    return layers, hop_of


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--graph", required=True)
    ap.add_argument("--out", required=True, help="JSONL, one line per key, flushed per key")
    ap.add_argument("--max-hop", type=int, default=None,
                    help="stop the walk after this many hops; default is the full closure")
    ap.add_argument("--max-nodes", type=int, default=DEFAULT_MAX_NODES)
    ap.add_argument("--seconds", type=int, default=60,
                    help="wall-clock cap per key; 0 disables. A capped key is recorded "
                         "UNMEASURED, never negative. See the Timeout docstring.")
    ap.add_argument("--only", default=None,
                    help="a file of keys, one per line: sweep ONLY these. For closing the "
                         "wall-clock holes with --seconds 0 once the capped pass has run.")
    ap.add_argument("--controls", action="store_true",
                    help="run the controls and exit without sweeping")
    ap.add_argument("--enumerate-only", action="store_true")
    args = ap.parse_args()

    t0 = time.time()
    graph = Graph.load(args.graph)
    sys.stderr.write("graph loaded in %.0fs: %d recipes, %d dimension_ores, %d offworld_ores\n"
                     % (time.time() - t0, len(graph.recipes),
                        len(graph.dimension_ores or {}), len(getattr(graph, "offworld_ores", None) or {})))
    sys.stderr.flush()

    gated = dimensions_mod.gates_for(graph, OVERWORLD_ONLY)
    sys.stderr.write("gate set: %d -> %s\n" % (len(gated), json.dumps(gated, sort_keys=True)))

    layers, hop_of = population(graph, set(gated))
    for i, layer in enumerate(layers):
        sys.stderr.write("layer %d: %d keys (cumulative %d)\n"
                         % (i, len(layer), sum(len(l) for l in layers[:i + 1])))
    sys.stderr.flush()

    # --- population controls, before a single plan is solved -------------------
    missing = [k for k in KNOWN_POSITIVES if k not in hop_of]
    shallow_in = KNOWN_SHALLOW in hop_of
    live = len(graph.live_keys)
    outside_key = outside_control(graph, hop_of)
    sys.stderr.write("CONTROL population positive: %s\n"
                     % json.dumps({k: hop_of.get(k, "MISSING") for k in KNOWN_POSITIVES}))
    sys.stderr.write("CONTROL population shallow  : %s hop=%s\n"
                     % (KNOWN_SHALLOW, hop_of.get(KNOWN_SHALLOW, "MISSING")))
    sys.stderr.write("closure %d keys, %d of them live, of %d live keys\n"
                     % (len(hop_of), len(set(hop_of) & set(graph.live_keys)), live))
    sys.stderr.write("CONTROL outside key         : %s\n" % outside_key)
    if missing or not shallow_in or outside_key is None:
        sys.stderr.write("!! POPULATION CONTROL FAILED -- every count below would be "
                         "meaningless, so nothing is swept.\n")
        return 2

    if args.enumerate_only:
        json.dump({"layers": [len(l) for l in layers],
                   "hop_of": hop_of, "outside_control": outside_key},
                  open(args.out, "w"), sort_keys=True)
        return 0

    sys.stderr.write("pricing the BARE environment (cost.estimate, minutes)...\n")
    sys.stderr.flush()
    t1 = time.time()
    env = build_env(graph)
    sys.stderr.write("priced in %.0fs, %d keys in the cost table\n"
                     % (time.time() - t1, len(env["costs"])))
    sys.stderr.flush()

    # --- claim-counter controls ------------------------------------------------
    ctl = score(solve_one(graph, env, COUNTER_CONTROL, args.max_nodes))
    sys.stderr.write("CONTROL counters on %s: %s\n" % (COUNTER_CONTROL, json.dumps(ctl["held"])))
    if not (ctl["held"]["oredict"] and ctl["held"]["alternatives"]):
        sys.stderr.write("!! CLAIM-COUNTER CONTROL FAILED: `oredict`/`alternatives` never "
                         "fired on the plan that is known to hold them, so their absence "
                         "elsewhere measures nothing.\n")
        return 3

    # THE CLOSURE'S OWN CLAIM, MADE TO FAIL ON PURPOSE. Everything the walk did not reach is
    # asserted negative without being planned, so plan one of them and require the assertion
    # to hold. A closure that is wrong here is not a bound and the unswept remainder is not
    # covered by anything.
    out = score(solve_one(graph, env, outside_key, args.max_nodes))
    sys.stderr.write("CONTROL outside %s: dimension=%s depths=%s (must be False/[])\n"
                     % (outside_key, out["held"]["dimension"], out["gate_depths"]))
    if out["held"]["dimension"]:
        sys.stderr.write("!! CLOSURE CONTROL FAILED: a key the walk excluded reaches a gate, "
                         "so the closure does not bound the population and nothing outside "
                         "it is proven negative.\n")
        return 5

    fix = score(solve_one(graph, env, KNOWN_POSITIVES[0], args.max_nodes))
    sys.stderr.write("CONTROL fixture %s: held=%s depths=%s\n"
                     % (KNOWN_POSITIVES[0], json.dumps(fix["held"]), fix["gate_depths"]))
    if not (fix["gate_below_root"] and fix["n_held"] == 5):
        sys.stderr.write("!! FIXTURE CONTROL FAILED: the shipped fixture's own target does "
                         "not reproduce its recorded 5-of-7 with a gate below the root.\n")
        return 4

    if args.controls:
        sys.stderr.write("controls only; not sweeping.\n")
        return 0

    # --- the sweep --------------------------------------------------------------
    if args.only:
        with open(args.only) as fh:
            wanted = [line.strip() for line in fh if line.strip()]
        # STILL CHECKED AGAINST THE ENUMERATION. A key list is a convenience for re-running
        # holes, not a second population: anything not in the closure would be a key this
        # sweep never claimed to cover, and silently accepting it would put a row in the
        # output that the coverage denominator cannot account for.
        stray = [k for k in wanted if k not in hop_of]
        if stray:
            sys.stderr.write("!! --only names %d keys outside the enumerated closure: %s\n"
                             % (len(stray), stray[:5]))
            return 6
        keys = wanted
        sys.stderr.write("re-sweeping %d named keys, cap=%ss\n" % (len(keys), args.seconds))
    else:
        keys = [k for i, layer in enumerate(layers) for k in layer
                if args.max_hop is None or i <= args.max_hop]
        sys.stderr.write("sweeping %d keys (hops 0..%s)\n"
                         % (len(keys), args.max_hop if args.max_hop is not None else len(layers) - 1))
    sys.stderr.flush()

    done = set()
    if os.path.exists(args.out):
        with open(args.out) as fh:
            for line in fh:
                line = line.strip()
                if line:
                    done.add(json.loads(line)["key"])
        sys.stderr.write("resuming: %d keys already recorded\n" % len(done))

    with open(args.out, "a") as fh:
        for i, key in enumerate(keys):
            if key in done:
                continue
            t = time.time()
            try:
                row = score(solve_one(graph, env, key, args.max_nodes, args.seconds))
                row["error"] = None
                row["timeout"] = False
            except Timeout:
                row = {"held": None, "n_held": None, "gate_depths": None,
                       "gate_below_root": None, "nodes": None, "work": None,
                       "truncated": None, "exhausted": None, "timeout": True,
                       "error": "TIMEOUT after %ss" % args.seconds}
            except Exception as exc:                       # noqa: BLE001
                # A RAISED PLAN IS NOT A NEGATIVE. #248's probe printed a confident `0 of 10`
                # because every plan raised and the exception was swallowed; recorded here so
                # a broken probe and a real absence cannot look the same in the output.
                row = {"held": None, "n_held": None, "gate_depths": None,
                       "gate_below_root": None, "nodes": None, "work": None,
                       "truncated": None, "exhausted": None, "timeout": False,
                       "error": "%s: %s" % (type(exc).__name__, exc)}
            row["key"] = key
            row["hop"] = hop_of[key]
            row["seconds"] = round(time.time() - t, 3)
            fh.write(json.dumps(row, sort_keys=True) + "\n")
            fh.flush()
            if (i + 1) % 25 == 0:
                sys.stderr.write("  %d/%d  %.0fs elapsed\n" % (i + 1, len(keys), time.time() - t0))
                sys.stderr.flush()

    sys.stderr.write("SWEEP COMPLETE in %.0fs\n" % (time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
