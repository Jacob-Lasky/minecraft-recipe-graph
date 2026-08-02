"""The python side of the cost-oracle diff: same inputs, same output shape as the java one.

Lives under `mod/tools/` rather than `tools/` on purpose. `tools/` is the python project's
own analysis kit; this exists ONLY to be diffed against `CostOracleHarness`, and the two have
to move together -- a change to what either dumps is meaningless unless the other changes with
it. See `mod/tools/cost-oracle.sh`, which drives both.

PRICES GO OUT AS THE HEX OF THEIR 64-BIT PATTERN. Never decimal text: 1,966 of 6,012 doubles
format differently between the two languages while being bit-identical, so a textual diff
would report a third of the pack as broken and be wrong every time.
"""

import importlib.util
import json
import os
import struct
import sys
import time

from recipegraph import cost, machines
from recipegraph.model import Graph


def bits(value):
    return format(struct.unpack(">Q", struct.pack(">d", value))[0], "x")


def load_fixture_generator():
    """`tools/make-java-fixtures.py`, imported despite the hyphens in its name.

    REUSED RATHER THAN REIMPLEMENTED. Its `derive_inputs` is the one place a scenario becomes
    the arguments `cost.estimate` takes, and it says so: "THE ARGUMENT LIST OF cost.estimate
    IS THE CHECKLIST". A second spelling of that here would be a third place to keep in step,
    and the drift would be invisible -- both sides would still produce a plausible price.
    """
    here = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(here, "tools", "make-java-fixtures.py")
    spec = importlib.util.spec_from_file_location("make_java_fixtures", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def scenario_from(path):
    """The `scenario` block of a plan fixture, or a bare scenario document."""
    with open(path) as fh:
        doc = json.load(fh)
    return doc.get("scenario", doc)


def main(graph_path, prefix, scenario_path=None):
    started = time.time()
    graph = Graph.load(graph_path)
    print("loaded          %.2f s" % (time.time() - started))

    # WITH NO SCENARIO, no world state at all: stock, placed blocks and dimension visits are
    # the half that differs between two machines, and the bare run compares the arithmetic
    # rather than reproducing one save. Every path that matters still runs -- identification
    # reads the graph's own catalysts, and both relaxation passes run because build targets
    # exist.
    #
    # WITH ONE, the seeding paths are covered too. Those are `have`, `freeSource`, `token`,
    # `dimensionGated` and `emcAvailable`, and until a scenario could be passed they were
    # unit-tested on both sides and never DIFFED against each other -- which is a different
    # and weaker claim, and one worth being able to close on demand.
    started = time.time()
    if scenario_path is None:
        info = machines.describe(graph)
        states = {uid: (rec["state"], rec["why"]) for uid, rec in info.items()}
        items = machines.build_targets(info)
        print("resolve         %.2f s" % (time.time() - started))
        started = time.time()
        table = cost.estimate(graph, machine_states=states, machine_items=items)
    else:
        generator = load_fixture_generator()
        derived = generator.derive_inputs(graph, scenario_from(scenario_path))
        info = derived["info"]
        states = derived["states"]
        print("resolve         %.2f s" % (time.time() - started))
        started = time.time()
        table = generator.priced_environment(graph, derived)["costs"]
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
    targets = machines.build_targets(info)
    with open(prefix + ".machines.tsv", "w") as fh:
        rows = ["%s\t%s\t%s\t%s" % (uid, rec["state"], rec["why"],
                                    ",".join(targets.get(uid, ())))
                for uid, rec in info.items()]
        for row in sorted(rows):
            fh.write(row + "\n")
    print("wrote          ", prefix + ".{cost,machines}.tsv")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit("usage: cost_oracle_dump.py <graph.json> <out-prefix> [scenario.json]")
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
