#!/usr/bin/env python3
"""A graph that CARRIES `declared_provenance`, and what Python computes from it. #262.

WHY THIS EXISTS RATHER THAN A SECOND ORACLE FIXTURE. `tools/make-java-fixtures.py` writes the
golden plan set, and `PlanFixtureTest.everyFixturePlansExactlyAsThePythonOracleDoes` is the
proof that the Java port agrees with this implementation. It cannot prove anything about #171:
`declared_provenance` is written by `index.build` and is absent from every graph.json on disk,
INCLUDING the oracle, so both languages compute the same 285-key set and the gate compares like
with like. The gate is green and it is green for a reason that expires the next time anybody
redumps -- which is the whole of #262.

SO THE ONLY WAY TO TEST THE PORT IS TO SYNTHESISE A GRAPH THAT CARRIES THE FIELD, and this is
that graph. It is 8 declared keys and 10 recipes, hand-declared here for the reason
`make-java-fixtures.py` hardcodes its scenarios: a contract whose inputs cannot be reproduced by
the other language is not a contract. Everything DERIVED from it -- the two provenance sets,
the cost table, the plan -- is computed by the Python implementation and written out, so this
file is the oracle and the Java side has something to be held to.

AND IT NEEDS NO 121 MB GRAPH, which is the second thing it buys. The golden gate skips itself
into invisibility without `$RECIPEGRAPH_ORACLE`, so nothing about this feature would be checked
in CI or on a machine without a dump. This fixture runs everywhere.

    python3 tools/make-provenance-fixture.py            # write it
    python3 tools/make-provenance-fixture.py --check    # regenerate and diff

EVERY KEY IN THE GRAPH IS A CASE, and the controls are the point rather than the padding --
this rule is applied as an EXCLUSION from `Graph.pack_authored_unsourced`, so a wrong entry
lets a JEI tooltip out of that set and back to a cheap leaf, which is the defect #171 exists to
fix arriving through its own fix. The eight declared keys cover:

    from_a_puzzle            declared puzzle,  nothing makes it  -> GATE_COST, badged puzzle
    from_a_loot_table        declared loot,    nothing makes it  -> LOOT_COST
    from_a_quest             declared quest,   nothing makes it  -> GATE_COST
    from_a_future_mechanic   declared "some_future_kind"         -> UNSOURCED_COST, the fallback
    from_nowhere             DECLARED NOWHERE, nothing makes it  -> UNSOURCED_COST, badged
    declared_but_craftable   declared puzzle,  a recipe makes it -> its craft price, no badge
    declared_nugget          declared loot,    in an oredict group -> a raw leaf, no badge
    declared_tool:28         declared puzzle,  a damage variant  -> a raw leaf, no badge
    declared_dead_key        declared puzzle,  NO RECIPE TOUCHES IT -> in NEITHER set

THE LAST ONE IS THE CLAUSE THAT BIT. `live_keys` is a CLAUSE in `pack_authored_declared` and
not a loop bound: the unsourced half gets liveness by iterating the live set, while the declared
half iterates the PACK'S map, which names items no recipe touches. Python shipped without that
clause for one measurement and 54 dead keys went from infinity to a gate price. A key falling
out of BOTH sets is priced by NEITHER rule and nothing says so, which is why this file also
writes the sets computed with the declarations REMOVED: the two arms must partition exactly,
and `tests/test_provenance.py` and `ProvenanceFixtureTest.java` both assert that equality.

DETERMINISM IS THE WHOLE PROPERTY, as it is for the golden set: `--check` regenerates and
diffs, and `tests/test_provenance.py` does the same, so a fixture that varied between runs
would fail here rather than turn the Java test flaky. `sort_keys=True` everywhere, and the
costs come out of `cost.estimate` at full `repr` precision -- NOTHING IS ROUNDED FOR
READABILITY. Round one for the sake of a tidy diff and the fixture stops being able to detect
the drift it exists to detect; the Java side parses these as doubles and compares with a zero
delta, exactly as `JsonCompare` does for the golden set.

Dev tooling, alongside the other audits. Python 3 stdlib.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod             # noqa: E402
from recipegraph import dimensions as dimensions_mod  # noqa: E402
from recipegraph import generators as generators_mod  # noqa: E402
from recipegraph import machines as machines_mod      # noqa: E402
from recipegraph import projecte as projecte_mod      # noqa: E402
from recipegraph import provenance                    # noqa: E402
from recipegraph import tokens as tokens_mod          # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver                  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "tests", "fixtures", "provenance.json")

# THE PACK'S OWN NAMESPACE, because `tokens.TOKEN_NAMESPACES` is the first clause both
# predicates run and a key outside it is excluded before anything else is asked. Spelled here
# rather than imported so that a fixture reader can see what makes these keys eligible.
NS = "contenttweaker:"

PUZZLE_KEY = NS + "from_a_puzzle"
LOOT_KEY = NS + "from_a_loot_table"
QUEST_KEY = NS + "from_a_quest"
FUTURE_KEY = NS + "from_a_future_mechanic"
MARKER_KEY = NS + "from_nowhere"
CRAFTABLE_KEY = NS + "declared_but_craftable"
ORE_KEY = NS + "declared_nugget"
TOOL_STEM = NS + "declared_tool"
TOOL_KEY = TOOL_STEM + ":28"
DEAD_KEY = NS + "declared_dead_key"

# ONE TARGET THAT CONSUMES ALL OF THEM, WHICH IS SAFE HERE AND IS NOT SAFE IN GENERAL.
# `tests/test_provenance.py` gives each ingredient its own target because a plan picks the
# CHEAPEST route and reports only that one, so a shared target silently asserts nothing about
# the keys it did not pick. Every widget below has exactly ONE recipe, so there is no choice to
# make and one plan carries every case -- which is what makes a single frozen `result` block
# able to pin all eight.
TARGET = "mod:everything"

# key -> the widget whose only recipe consumes it. Sorted so the target's input order, and
# therefore the plan's child order, is decided here rather than by dict iteration.
CONSUMED = (
    (PUZZLE_KEY, "mod:puzzle_widget"),
    (LOOT_KEY, "mod:loot_widget"),
    (QUEST_KEY, "mod:quest_widget"),
    (FUTURE_KEY, "mod:future_widget"),
    (MARKER_KEY, "mod:marker_widget"),
    (CRAFTABLE_KEY, "mod:craftable_widget"),
    (ORE_KEY, "mod:nugget_widget"),
    (TOOL_KEY, "mod:tool_widget"),
)

DECLARED = {
    PUZZLE_KEY: provenance.PUZZLE,
    LOOT_KEY: provenance.LOOT_TABLE,
    QUEST_KEY: provenance.QUEST,
    # A KIND NEITHER LANGUAGE KNOWS, on purpose. Both must degrade to `UNSOURCED_COST` and the
    # generic wording rather than to a cheap leaf or a crash -- a `provenance` that grows a
    # fourth kind without teaching the price table about it has to land on today's answer.
    FUTURE_KEY: "some_future_kind",
    # The three controls: each satisfies the declaration and fails one of the other four
    # clauses, so each must keep the better answer it already had.
    CRAFTABLE_KEY: provenance.PUZZLE,
    ORE_KEY: provenance.LOOT_TABLE,
    TOOL_KEY: provenance.PUZZLE,
    # And the one no recipe touches. See the module docstring.
    DEAD_KEY: provenance.PUZZLE,
}


def build_graph(declared):
    """The synthetic pack, with `declared` as its `declared_provenance`.

    TAKEN AS AN ARGUMENT SO THE SAME GRAPH CAN BE BUILT BOTH WAYS. The partition assertion
    needs the sets computed with the declarations present and with them removed, and building
    two graphs from one function is what stops the second arm being a different pack.
    """
    g = Graph()
    g.dump_schema = 5
    g.names = {TARGET: "Everything"}
    for key, widget in CONSUMED:
        g.names[key] = key.split(":")[1].replace("_", " ").title()
        g.names[widget] = widget.split(":")[1].replace("_", " ").title()
        g.add(Recipe("make_" + widget.split(":")[1], "hei_dump", [(widget, 1)],
                     [Ingredient([key], 1)], category="minecraft.crafting"))
    g.names[DEAD_KEY] = "A Key No Recipe Touches"
    g.names["minecraft:stone"] = "Stone"
    # The target, which is what makes every widget reachable from one plan.
    g.add(Recipe("make_everything", "hei_dump", [(TARGET, 1)],
                 [Ingredient([widget], 1) for _key, widget in CONSUMED],
                 category="minecraft.crafting"))
    # THE PRODUCER CLAUSE'S CONTROL: a declared key the graph already makes. 843 of the
    # reference pack's 896 declarations are keys like this, and pricing them off the
    # declaration moves 301 of them off their real prices.
    g.add(Recipe("make_craftable", "hei_dump", [(CRAFTABLE_KEY, 1)],
                 [Ingredient(["minecraft:stone"], 1)], category="minecraft.crafting"))
    # THE OREDICT CLAUSE'S CONTROL. Any group, not just a world ore: only 11 of the 47 keys
    # this clause drops on the reference pack are ores, and among the other 36 is the Sednanite
    # Nugget #136 was filed about.
    g.ore_members = {"nuggetThing": [ORE_KEY]}
    # THE DAMAGE CLAUSE'S CONTROL. `declared_tool:28` is a worn tool whose UNDAMAGED key is the
    # one a recipe would make; 837 of 1,120 candidate keys on the reference pack are these.
    g.max_damage = {TOOL_STEM: 250}
    g.declared_provenance = dict(declared)
    return g


def sets_of(graph):
    return {
        "pack_authored_unsourced": sorted(graph.pack_authored_unsourced),
        "pack_authored_declared": dict(graph.pack_authored_declared),
    }


# EVERY SOLVER INPUT THAT IS NOT THE GRAPH, echoed into the fixture and RESOLVED BY BOTH SIDES.
#
# NOT `Solver(graph, costs=costs)` WITH THE DEFAULTS, WHICH IS THE OBVIOUS SPELLING AND IS NOT
# THE SAME INPUT. `ScenarioInputs.resolve` on the Java side runs `Machines.resolve`,
# `generators.resolve` and `tokens.resolve` over the document even when it is empty, and
# defaults `vanilla_water` to true; a Python arm that passed bare keyword defaults would be
# comparing two solvers configured differently and calling the difference a port bug. So this
# is `make-java-fixtures.BARE`, and the Java test resolves the same block through the same
# class the golden gate uses. `visited_dimensions` is the overworld alone rather than `{}` for
# that generator's stated reason: `gates_for` reads an empty map as "gate nothing".
SCENARIO = {
    "have": {},
    "craftables": [],
    "raw": [],
    "placed": {},
    "machine_overrides": {},
    "no_machine": [],
    "source_overrides": {},
    "token_overrides": {},
    "pins": {},
    "visited_dimensions": {".": 1},
    "emc_knowledge": {},
}


def derive(graph, sc):
    """`make-java-fixtures.derive_inputs`, for this one scenario.

    THE ARGUMENT LIST OF `cost.estimate` IS THE CHECKLIST, which is that function's own rule:
    an input added there and not resolved here silently prices this fixture for a
    configuration nobody is running, and the Java side WOULD resolve it.
    """
    info = machines_mod.describe(graph, dict(sc["placed"]), dict(sc["have"]),
                                 overrides=sc["machine_overrides"],
                                 no_machine=tuple(sc["no_machine"]))
    return {
        "have": dict(sc["have"]),
        "craftables": set(sc["craftables"]),
        "raw": set(sc["raw"]),
        "states": {uid: (i["state"], i["why"]) for uid, i in info.items()},
        "free": generators_mod.resolve(dict(sc["placed"]), dict(sc["have"]),
                                       sc["source_overrides"]),
        "tokens": tokens_mod.resolve(sc["token_overrides"], graph),
        "gates": dimensions_mod.gates_for(graph, sc["visited_dimensions"]),
        "targets": machines_mod.build_targets(info),
        "emc_available": projecte_mod.available(graph, sc["emc_knowledge"]),
        "pinned": dict(sc["pins"]),
    }


def generate():
    graph = build_graph(DECLARED)
    env = derive(graph, SCENARIO)
    costs = cost_mod.estimate(graph, have=env["have"], free_sources=env["free"],
                              token_kinds=env["tokens"], machine_states=env["states"],
                              machine_items=env["targets"],
                              dimension_gates=env["gates"],
                              emc_available=env["emc_available"],
                              craftables=env["craftables"], raw=env["raw"])
    plan = Solver(graph, have=env["have"], craftables=env["craftables"], raw=env["raw"],
                  machine_states=env["states"], costs=costs, free_sources=env["free"],
                  token_kinds=env["tokens"], pinned=env["pinned"],
                  dimension_gates=env["gates"],
                  emc_available=env["emc_available"]).solve(TARGET, 1)

    # THE PRICES FOR KEYS THE GRAPH HOLDS, and not the whole table. `cost._seed` writes an entry
    # for every curated token whether or not the graph knows the key, so the Python table
    # carries rows the Java array -- indexed by key id -- has no slot for. Restricting to the
    # graph's own keys compares the two implementations rather than an artefact of one's
    # representation.
    priced = dict((key, costs[key]) for key in sorted(costs) if key in graph.names
                  or key in graph.by_input or key in graph.by_output)

    return {
        "graph": graph.to_json(),
        "request": {"item": TARGET, "qty": 1},
        "scenario": SCENARIO,
        # WITH THE DECLARATIONS, AND WITHOUT THEM. The second arm is not a curiosity: the two
        # predicates PARTITION one five-clause rule, so `unsourced(without)` must equal
        # `unsourced(with) | declared(with)` exactly. A key in neither is priced by no rule and
        # nothing says so, which is the failure mode this pair exists to make loud.
        "sets": sets_of(graph),
        "sets_without_declarations": sets_of(build_graph({})),
        "costs": priced,
        "result": plan,
    }


def dump(doc):
    """The bytes the fixture holds. `sort_keys` because dict order is not decided here."""
    return json.dumps(doc, indent=2, sort_keys=True) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="regenerate and report whether the file on disk matches")
    args = parser.parse_args(argv)

    text = dump(generate())
    if args.check:
        with open(FIXTURE) as fh:
            current = fh.read()
        if current == text:
            print("provenance fixture is current")
            return 0
        print("provenance fixture is STALE: regenerate with "
              "python3 tools/make-provenance-fixture.py")
        return 1
    with open(FIXTURE, "w") as fh:
        fh.write(text)
    print("wrote %s" % FIXTURE)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
