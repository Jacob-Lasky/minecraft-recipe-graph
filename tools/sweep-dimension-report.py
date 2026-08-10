#!/usr/bin/env python3
"""Read the sweep's JSONL and say what it covers and what it found.

COVERAGE IS REPORTED PER HOP AND NEVER ROUNDED UP. A partial sweep labelled as one is a
result; the same numbers presented as "no candidate exists" is the failure #248 made. The
denominator comes from the enumeration, not from the rows present, so a run that stopped
early reports the fraction it stopped at rather than 100% of what it happened to write.
"""

import argparse
import collections
import json
import sys

SEVEN = ("craft", "raw", "dimension", "machine", "not_truncated", "oredict", "alternatives")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", required=True)
    ap.add_argument("--enum", required=True, help="the --enumerate-only JSON, for denominators")
    args = ap.parse_args()

    enum = json.load(open(args.enum))
    layers = enum["layers"]
    hop_of = enum["hop_of"]

    rows = []
    with open(args.rows) as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))

    by_hop = collections.Counter(r["hop"] for r in rows)
    print("POPULATION AND COVERAGE")
    print("  hop   swept /  total   pct")
    total_pop = sum(layers)
    for hop, size in enumerate(layers):
        got = by_hop.get(hop, 0)
        print("  %3d %7d / %6d  %5.1f%%" % (hop, got, size, 100.0 * got / size))
    print("  ALL %7d / %6d  %5.1f%%" % (len(rows), total_pop, 100.0 * len(rows) / total_pop))
    print("  (the population is the transitive closure of Graph.consumers over the 10 gated")
    print("   ores; #259 calls hops 1-2 alone -- %d keys here -- 'the transitive population')"
          % (layers[1] + layers[2]))

    errors = [r for r in rows if r.get("error")]
    print("\nERRORS: %d" % len(errors))
    for r in errors[:10]:
        print("  %s  %s" % (r["key"], r["error"]))
    if errors:
        print("  !! A RAISED PLAN IS NOT A NEGATIVE. These keys are unmeasured, not clean.")

    ok = [r for r in rows if not r.get("error")]
    gated = [r for r in ok if r["held"]["dimension"]]
    below = [r for r in gated if r["gate_below_root"]]
    print("\nPLANS THAT REACH A GATE AT ALL: %d of %d swept" % (len(gated), len(ok)))
    print("  of those, gate BELOW the root:  %d" % len(below))

    dist = collections.Counter(r["n_held"] for r in below)
    print("\nCLAIMS HELD, among the %d with a gate below the root:" % len(below))
    for n in sorted(dist, reverse=True):
        print("  %d of 7: %4d" % (n, dist[n]))

    print("\nPER-CLAIM, among those %d:" % len(below))
    for tag in SEVEN:
        print("  %-14s %4d" % (tag, sum(1 for r in below if r["held"][tag])))

    sevens = [r for r in below if r["n_held"] == 7]
    print("\n7-OF-7 CANDIDATES WITH A GATE BELOW THE ROOT: %d" % len(sevens))
    for r in sorted(sevens, key=lambda r: (min(r["gate_depths"] or [99]), r["nodes"], r["key"])):
        print("  %-45s hop=%d depths=%s nodes=%d work=%d"
              % (r["key"], r["hop"], r["gate_depths"], r["nodes"], r["work"]))

    six = [r for r in below if r["n_held"] == 6]
    if six and not sevens:
        print("\nNEAREST MISSES (6 of 7), and WHICH claim is missing:")
        miss = collections.Counter(tag for r in six for tag in SEVEN if not r["held"][tag])
        for tag, n in miss.most_common():
            print("  missing %-14s %4d" % (tag, n))
        for r in sorted(six, key=lambda r: (r["nodes"], r["key"]))[:15]:
            gone = [t for t in SEVEN if not r["held"][t]]
            print("  %-45s hop=%d depths=%s nodes=%d missing=%s"
                  % (r["key"], r["hop"], r["gate_depths"], r["nodes"], gone))

    unswept = total_pop - len(rows)
    if unswept:
        print("\nUNSWEPT: %d keys (%.1f%% of the population). The conclusion below covers the"
              % (unswept, 100.0 * unswept / total_pop))
        print("swept fraction ONLY and is not a statement about the pack.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
