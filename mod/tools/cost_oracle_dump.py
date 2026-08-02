"""The python side of the cost-oracle diff: same inputs, same output shape as the java one.

Lives under `mod/tools/` rather than `tools/` on purpose. `tools/` is the python project's
own analysis kit; this exists ONLY to be diffed against `CostOracleHarness`, and the two have
to move together -- a change to what either dumps is meaningless unless the other changes with
it. See `mod/tools/cost-oracle.sh`, which drives both.

PRICES GO OUT AS THE HEX OF THEIR 64-BIT PATTERN. Never decimal text: 1,966 of 6,012 doubles
format differently between the two languages while being bit-identical, so a textual diff
would report a third of the pack as broken and be wrong every time.
"""

import struct
import sys
import time

from recipegraph import cost, machines
from recipegraph.model import Graph


def bits(value):
    return format(struct.unpack(">Q", struct.pack(">d", value))[0], "x")


def main(graph_path, prefix):
    started = time.time()
    graph = Graph.load(graph_path)
    print("loaded          %.2f s" % (time.time() - started))

    # NO WORLD STATE AT ALL, matching the java harness. Stock, placed blocks and dimension
    # visits are the half that differs between two machines, and the point is to compare the
    # arithmetic rather than to reproduce one save. Every path that matters still runs:
    # identification reads the graph's own catalysts, and both relaxation passes run because
    # build targets exist.
    started = time.time()
    info = machines.describe(graph)
    print("resolve         %.2f s" % (time.time() - started))
    states = {uid: (rec["state"], rec["why"]) for uid, rec in info.items()}
    items = machines.build_targets(info)

    started = time.time()
    table = cost.estimate(graph, machine_states=states, machine_items=items)
    print("estimate        %.2f s" % (time.time() - started))

    priced = {key: value for key, value in table.items() if value != float("inf")}
    print("priced keys    ", len(priced))
    counts = {}
    for _uid, (state, _why) in states.items():
        counts[state] = counts.get(state, 0) + 1
    for state in machines.STATES:
        print("machines %-11s %d" % (state, counts.get(state, 0)))

    with open(prefix + ".cost.tsv", "w") as fh:
        for key in sorted(priced):
            fh.write("%s\t%s\n" % (key, bits(priced[key])))
    with open(prefix + ".machines.tsv", "w") as fh:
        rows = ["%s\t%s\t%s\t%s" % (uid, rec["state"], rec["why"],
                                    ",".join(items.get(uid, ())))
                for uid, rec in info.items()]
        for row in sorted(rows):
            fh.write(row + "\n")
    print("wrote          ", prefix + ".{cost,machines}.tsv")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("usage: cost_oracle_dump.py <graph.json> <out-prefix>")
    main(sys.argv[1], sys.argv[2])
