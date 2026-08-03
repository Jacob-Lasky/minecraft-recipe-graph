#!/usr/bin/env python3
"""Golden fixtures that make the Java port prove it agrees with this implementation.

WHY THIS EXISTS. #19 replaces the web UI with an in-game GUI, which means porting the
planner core to Java. The Python suite is over a thousand tests and it is the reason
several subtle bugs stay fixed -- #29's molten-metal route, #61's microblock, #106's nugget
ladder, #110's chisel tables, #112/#117's dimension gates. Porting those tests line by
line would re-derive every one of those arguments in a second language and get some of
them wrong. Asserting that the two implementations produce the SAME OUTPUT is stronger and
cheaper, and it is a pattern this repo already runs: `tests/fixtures/nbt_digest.json` is
read by `tests/test_nbt_digest.py` and by `mod/.../DigestFixtureTest.java` alike.

So this writes `tests/fixtures/plan/*.json`, and the Java `PlanFixtureTest` is expected to
read them the same way `DigestFixtureTest` reads the digest file: from the repository root,
trying `../` then `./`, because gradle runs with `mod/` as the working directory.

    RECIPEGRAPH_ORACLE=/coding/.recipegraph-build/graph-oracle.json \
        python3 tools/make-java-fixtures.py
    python3 tools/make-java-fixtures.py --graph <path> --check   # regenerate and diff

THE ORACLE GRAPH IS NOT IN GIT AND MUST NOT BE. It is 125 MB and `data/` is gitignored
outright. Name it with $RECIPEGRAPH_ORACLE or `--graph`; `tests/test_plan_fixtures.py`
reads the same variable and SKIPS without it.

BUILD A DEDICATED ORACLE. DO NOT POINT THIS AT `data/graph.json`, even though that file is
current and would work today. It is the file the container SERVES, so it is replaced whenever
Jake redumps and rebuilds -- and a fixture set pinned to it would be silently invalidated by a
redeploy, turning the port's contract into a function of the last time somebody launched the
game. An oracle has to be a file that changes only when somebody decides it should. It is also
built on the desktop from ~410 jars while a Tower build reads 367, a measured 1.2% of produced
keys (#119), so the two are different graphs regardless of when either was made.

USE CURRENT CODE, THOUGH, AND CHECK. #110, #112 and #117 all do their work in `index.build`, so
an oracle built before them is a graph the fixtures cannot exercise: `dimension_ores` is empty
and three of the targets below would assert the pre-fix behaviour while looking exactly as
green. The generator REFUSES such a graph outright rather than trusting the operator. Every
fixture also records the oracle's sha256, because "the oracle moved" and "the solver changed"
produce the same diff and only one of them is a bug.

THE DUMP'S SCHEMA DOES NOT HAVE TO BE CURRENT, and it is worth knowing why before someone
blocks on it. The digest format decides key STRINGS: a pre-schema-4 graph is UNSERVABLE,
because no AE2 stock can match one of its discriminated keys, and it is a perfectly good
ORACLE, because the solver logic under test is identical and the fixtures carry their own
small synthetic stock rather than a `have` file. This set was first built that way, on a
schema-3 dump, and rebuilt on schema 5 when one arrived; the plan trees are the same shape
either way. THE EXCEPTION IS THE THREE EMC FIXTURES: #50's terminator reads `graph.emc`,
which only a schema-5 dump supplies, so on an older oracle they would freeze the ABSENCE of
the feature -- a fixture a port passes by implementing nothing. `generate` refuses an oracle
with no `emc`, for the same reason it refuses one with no `dimension_ores`.

WHY THE SCENARIOS ARE HARDCODED RATHER THAN READ FROM data/. A fixture is a contract, and a
contract whose inputs live in a gitignored 193 KB world scan cannot be reproduced by anyone
else -- including the Java suite, which has to construct the same solver. So every input
that is not the graph is declared here, echoed into each fixture under `scenario`, and kept
small enough to read. The Java side needs the graph plus one fixture file, nothing more.

COMPARE THESE FILES STRUCTURALLY, WITH NUMBERS PARSED. NEVER AS TEXT. Measured over 6,012
doubles fed in as exact 64-bit patterns, Python `repr` and Java `Double.toString` produce
DIFFERENT STRINGS for 1,966 of them (33%) and different VALUES for ZERO -- `1e-09` against
`1.0E-9`, `5.2985852125019304e+159` against `...E159`, identically on Java 8 and Java 25. A
Java test diffing these as bytes therefore fails on a third of the cost table while both
implementations are bit-for-bit correct, and that failure reads as a numerical bug in the
port, which is about the most expensive wrong diagnosis available. Parse, then compare
doubles with a zero delta. Python-to-Python byte comparison is fine and is what `--check`
and the guard test do.

NOTHING HERE IS ROUNDED FOR READABILITY, and it must stay that way. Every cost reaches the
file through `json.dumps`, which emits full `repr` precision -- the shortest string that
round-trips to the same double -- so a fixture value parses back bit-identical in either
language. Round one for the sake of a tidy diff and the fixture stops being able to detect
the drift it exists to detect. The only formatted number in the whole set is inside
`cost.json`'s digest, and `fmt_cost` explains why that one cannot be `repr`.

DETERMINISM IS THE WHOLE PROPERTY. A fixture that varies between runs makes the Java test
flaky and worthless, so `--check` regenerates and diffs, and `tests/test_plan_fixtures.py`
does the same. Two things are load-bearing for it: `sort_keys=True` everywhere, and
`cost.estimate` rather than `estimate_cached`, because a warm `.cost-cache.json` answers for
whichever formula was current when it was written (the same reason `tools/entry-census.py`
computes uncached).

Dev tooling, alongside the other audits. Python 3 stdlib.
"""

import argparse
import collections
import hashlib
import importlib.util
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import api as api_mod                        # noqa: E402
from recipegraph import cost as cost_mod                      # noqa: E402
from recipegraph import dimensions as dimensions_mod          # noqa: E402
from recipegraph import generators as generators_mod          # noqa: E402
from recipegraph import machines as machines_mod              # noqa: E402
from recipegraph import pins as pins_mod                      # noqa: E402
from recipegraph import projecte as projecte_mod              # noqa: E402
from recipegraph import tokens as tokens_mod                  # noqa: E402
from recipegraph.defaults import DEFAULT_MAX_NODES            # noqa: E402
from recipegraph.model import Graph                           # noqa: E402
from recipegraph.solve import Solver                          # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "tests", "fixtures", "plan")

# Read by this tool and by tests/test_plan_fixtures.py, so the two cannot disagree about
# where the oracle is. Named rather than defaulted to `data/graph.json` on purpose: that
# file is a DIFFERENT graph, and defaulting to it would regenerate every fixture against
# the wrong oracle without saying so.
ORACLE_ENV = "RECIPEGRAPH_ORACLE"

# ONE spelling of the instruction, read by the banner in every fixture and by the failure
# messages in tests/test_plan_fixtures.py. Two copies is two chances to tell a reader to run
# a command that no longer exists, and the reader hits it precisely when something is already
# wrong.
REGENERATE_HINT = ("regenerate with RECIPEGRAPH_ORACLE=<oracle graph> python3 "
                   "tools/make-java-fixtures.py and read the diff -- the diff IS the "
                   "behaviour change")

BANNER = ("GENERATED BY tools/make-java-fixtures.py -- DO NOT EDIT BY HAND. A golden "
          "fixture the Java port asserts against (#19 phase 1); editing an expected value "
          "to make a side pass is editing the contract. To change it, %s." % REGENERATE_HINT)


# ---------------------------------------------------------------------------
# Scenarios: every solver input that is not the graph.
# ---------------------------------------------------------------------------

# `visited` for `dimensions.gates_for`, spelled the way `dimensions.visited` returns it:
# folder name -> region file count. "." is the overworld, whose region/ sits at the save
# root. THE OVERWORLD ALONE IS DELIBERATE. `gates_for` reads an EMPTY visited map as "gate
# nothing" -- the safe reading of a stock file written before #112 -- so a scenario that
# left this out would exercise none of #112 and none of #117 while looking like it did.
OVERWORLD_ONLY = {".": 1}

# Nothing owned, nothing placed, nothing overridden, and nowhere visited but the overworld.
# The state most of the interesting behaviour lives in: with an empty pool every recipe
# alternative ties on availability, so cost, `ore_backed` and the cycle guard are the only
# things separating them, and that is exactly where #29 and #61 went wrong.
BARE = {
    "have": {},
    "craftables": [],
    "placed": {},
    "machine_overrides": {},
    "no_machine": [],
    "source_overrides": {},
    "token_overrides": {},
    "pins": {},
    "visited_dimensions": OVERWORLD_ONLY,
    # #50's world-state half, spelled the way `projecte.read_knowledge` returns it and the
    # have file stores it: what has been LEARNED, plus the banked balance and the creative
    # learn-everything flag. Empty means nothing is learned, which is the pre-#50 behaviour
    # and what every fixture but the three EMC ones runs under.
    "emc_knowledge": {},
}


def scenario(**kw):
    """A scenario is BARE plus the differences, so a reader sees only what is unusual.

    An unrecognised field is refused rather than carried: it would ride into the fixture,
    be echoed to the Java side, and be read by nothing. There is no priced/unpriced
    classification to keep in step here -- `cost_signature` derives that from what
    `cost.estimate` is actually handed. See its docstring for why that replaced a list.
    """
    unknown = sorted(set(kw) - set(BARE))
    if unknown:
        raise SystemExit("scenario names fields BARE does not declare: %s" % unknown)
    out = dict(BARE)
    out.update(kw)
    return out


# ONE SCENARIO FIELD IS DELIBERATELY UNEXERCISED, and it is a decision rather than a hole.
# `source_overrides` is a loader whose entire output is the `free_sources` dict, which the
# `free-source` target below already drives end to end -- and the solver cannot tell whether
# an entry in that dict came from `generators.DEFAULT_GENERATORS` or from the user's file, so
# there is nothing further for a plan to prove. Exercising it would mean inventing a
# generator: the only override that would show up in a plan is a placed block declared to emit
# something it does not emit, and a golden fixture asserting a false claim about the pack is
# worse than an unexercised field. `tests/test_sources.py` covers the loader itself.
#
# It stays DECLARED regardless, because a fixture's `scenario` block is the port's description
# of what a solver input IS, and an incomplete description is the worse failure.


def derive_inputs(graph, sc):
    """Everything `cost.estimate` reads, resolved from a scenario exactly as `State` does.

    ONE derivation, mirroring `server.State.refresh_machines`, because the fixtures claim to
    be what `/plan?fmt=json` returns. A second spelling of it here is a second place for the
    fixtures and the endpoint to drift, and the drift would be invisible: both sides would
    still produce a perfectly plausible plan. THE ARGUMENT LIST OF `cost.estimate` IS THE
    CHECKLIST -- an input added there and not resolved here silently prices every fixture
    for a configuration nobody is running.
    """
    have = dict(sc["have"])
    placed = dict(sc["placed"])
    info = machines_mod.describe(graph, placed, have,
                                 overrides=sc["machine_overrides"],
                                 no_machine=tuple(sc["no_machine"]))
    return {
        "info": info,
        "have": have,
        "states": {uid: (i["state"], i["why"]) for uid, i in info.items()},
        # The overrides DOCUMENT, not a path: `generators.resolve` takes either, and handing
        # it the dict is what keeps `vanilla_water` on its documented default of true. A
        # scenario spelling the defaults out by hand would stop tracking a change to them.
        "free": generators_mod.resolve(placed, have, sc["source_overrides"]),
        "tokens": tokens_mod.resolve(sc["token_overrides"]),
        "gates": dimensions_mod.gates_for(graph, sc["visited_dimensions"]),
        "targets": machines_mod.build_targets(info),
        "emc_available": projecte_mod.available(graph, sc["emc_knowledge"]),
    }


def cost_signature(derived):
    """What two scenarios must agree on to share one cost table.

    DERIVED, NOT A LIST OF SCENARIO FIELDS. This used to be a hand-maintained partition of
    the scenario keys into "prices" and "does not", which is a second thing to update and
    wrong in both directions: a new pricing field forgotten there makes two scenarios share
    a table computed for one of them, and a non-pricing field left in it prices the same
    table twice. Keying on what `cost.estimate` is actually handed cannot drift -- and it is
    strictly sharper, because `emc_knowledge` that resolves to no available keys genuinely
    IS the bare table, which a field-name key could never notice.
    """
    # `default=list` renders the tuples in `states` and `targets`; `emc_available` is a set
    # and is sorted rather than left to `default`, because a set has no order to render and
    # two equal sets must produce one signature. 3.8 is the floor CI tests, so no dict
    # merge operator here.
    signature = dict((k, derived[k]) for k in
                     ("have", "states", "free", "tokens", "gates", "targets"))
    signature["emc_available"] = sorted(derived["emc_available"])
    return json.dumps(signature, sort_keys=True, default=list)


def priced_environment(graph, derived):
    """`derived` plus the cost table it implies.

    UNCACHED. See the module docstring: a warm `.cost-cache.json` answers for the formula
    that was current when it was written, and this file's whole job is to answer for the
    formula in the working tree.
    """
    env = dict(derived)
    env["costs"] = cost_mod.estimate(
        graph, have=derived["have"], machine_states=derived["states"],
        free_sources=derived["free"], machine_items=derived["targets"],
        token_kinds=derived["tokens"], dimension_gates=derived["gates"],
        emc_available=derived["emc_available"])
    return env


def resolve_scenario(graph, sc, priced):
    """A priced environment plus the two inputs that do not reach the cost model."""
    pinned, pin_notes = pins_mod.resolve(graph, sc["pins"])
    env = dict(priced)
    env.update(pinned=pinned, pin_notes=pin_notes,
               craftables=set(sc["craftables"]))
    return env


def solver_for(graph, env, max_nodes):
    """The same Solver `server.State.solver` builds. Keep the argument list in step."""
    return Solver(graph, have=env["have"], craftables=env["craftables"],
                  machine_states=env["states"], costs=env["costs"],
                  free_sources=env["free"], token_kinds=env["tokens"],
                  pinned=env["pinned"], max_nodes=max_nodes,
                  dimension_gates=env["gates"], emc_available=env["emc_available"])


# ---------------------------------------------------------------------------
# What each fixture is FOR, asserted rather than asserted-in-a-comment.
# ---------------------------------------------------------------------------

def _walk(node):
    stack = [node]
    while stack:
        n = stack.pop()
        yield n
        stack.extend(n.get("children") or ())


def _statuses(result):
    return collections.Counter(n.get("status") for n in _walk(result["tree"]))


# A tag is a claim about what the fixture exercises, and every claim is CHECKED at
# generation time. WHY THIS IS NOT A COMMENT: a fixture's value is entirely in the code path
# it covers, and that path can stop being covered without the file looking any different --
# a repricing moves the chosen route, the cyclic recipe stops being chosen, and the fixture
# goes on passing while asserting nothing anybody wanted. The pack's data moves under this
# tool, so the claims are re-proved on every run and a lapsed one is an error, not a note.
CHECKS = {
    "have": lambda r: _statuses(r).get("have"),
    "partial": lambda r: _statuses(r).get("partial"),
    "craft": lambda r: _statuses(r).get("craft"),
    "raw": lambda r: _statuses(r).get("raw"),
    "source": lambda r: _statuses(r).get("source"),
    "cycle": lambda r: _statuses(r).get("cycle"),
    "depth": lambda r: _statuses(r).get("depth"),
    "token": lambda r: _statuses(r).get("token"),
    "oredict": lambda r: _statuses(r).get("oredict"),
    # A slot that had a real choice to make. `alt_count` is only written when the merged
    # slot held more than one alternative, so this is `pick_alternative` having actually
    # decided something rather than a one-option slot passing through.
    "alternatives": lambda r: sum(1 for n in _walk(r["tree"])
                                  if (n.get("alt_count") or 0) > 1),
    "fluid": lambda r: sum(1 for n in _walk(r["tree"]) if n.get("kind") == "fluid"),
    "dimension": lambda r: sum(1 for n in _walk(r["tree"]) if n.get("dimension")),
    "machine": lambda r: sum(1 for n in _walk(r["tree"]) if n.get("machine")),
    "machine_to_build": lambda r: len(r["machines_to_build"]),
    "from_stock": lambda r: len(r["used_from_stock"]),
    "from_sources": lambda r: len(r["from_sources"]),
    "tokens_needed": lambda r: len(r["tokens_needed"]),
    "shopping_list": lambda r: len(r["shopping_list"]),
    "truncated": lambda r: r["truncated"],
    "not_truncated": lambda r: not r["truncated"],
    "pinned": lambda r: sum(1 for n in _walk(r["tree"]) if n.get("pinned")),
    # `note` rather than `status`, because AE2 autocraftability terminates a branch as
    # STATUS_HAVE -- indistinguishable from stock by status alone, which is the whole reason
    # `expand` writes the note.
    "craftable": lambda r: sum(1 for n in _walk(r["tree"])
                               if n.get("note") == "AE2 can autocraft"),
    "machine_have": lambda r: sum(1 for n in _walk(r["tree"])
                                  if n.get("machine_state") == "have"),
    # #139's display-only mark: a node resting on an NBT STATE the graph has no route to,
    # naming the plain item it CAN make. A field on the node rather than a status, so a port
    # can drop it and still produce a structurally valid tree -- which is exactly why it
    # needs a claim of its own.
    "unsourced": lambda r: sum(1 for n in _walk(r["tree"]) if n.get("unsourced")),
    "emc": lambda r: _statuses(r).get("emc"),
    "from_emc": lambda r: len(r["from_emc"]),
}


def holds(tag, result):
    """Whether one coverage claim is true of a result. `!tag` asserts the ABSENCE of one.

    NEGATIVE CLAIMS ARE THE POINT OF THE CONTROL FIXTURES, and without them a control
    asserts nothing. `emc-not-learned` is only interesting because the plan does NOT
    terminate on EMC; a fixture that merely claimed `craft` would go on passing after a port
    started terminating on every valued item, which is exactly the bug the pair exists to
    catch. Written once here and used by both the generator and
    `tests/test_plan_fixtures.py`, so the two cannot disagree about what a claim means.
    """
    if tag.startswith("!"):
        return not CHECKS[tag[1:]](result)
    return bool(CHECKS[tag](result))


def check_name(tag):
    """The CHECKS key a claim refers to, with any negation stripped."""
    return tag[1:] if tag.startswith("!") else tag


class Target(object):
    """One fixture: a request, the scenario it runs in, and the claims it makes."""

    def __init__(self, name, item, qty=1, max_nodes=DEFAULT_MAX_NODES, sc=None,
                 expect=(), why=""):
        self.name = name
        self.item = item
        self.qty = qty
        self.max_nodes = max_nodes
        self.scenario = sc or BARE
        self.expect = tuple(expect)
        self.why = why


# ---------------------------------------------------------------------------
# The scenarios and the targets.
# ---------------------------------------------------------------------------

# A hopper is five iron ingots round a chest. Two chests and five ingots against a request
# for TWO hoppers is deliberately lopsided: the chests cover the whole requirement and come
# back `have`, the ingots cover half of it and come back `partial`. One chest would give two
# `partial` nodes and no `have` at all, which is what the coverage check caught -- an
# `in-stock` fixture in which nothing is ever fully in stock.
STOCKED = scenario(have={"minecraft:iron_ingot": 5, "minecraft:chest": 2},
                   craftables=["railcraft:ore_metal_poor"])

# A placed cobblestone generator, spelled as `ae2_inventory.scan` records a tile entity:
# registry id -> how many were seen. Proves the PLACED half of `generators.resolve`; the
# vanilla-water half is on in every scenario because `vanilla_water` defaults to true.
GENERATOR = scenario(placed={"nuclearcraft:cobblestone_generator": 3})

# A manual machine override, which the tool documents as winning over anything auto-detected,
# plus a `no_machine` declaration. Both categories are `buildable` from evidence in every
# other scenario -- `craftable: nuclearcraft:crystallizer_idle` and
# `craftable: modularmachinery:ender_stone_crucible_controller` -- so a fixture where they
# read `have` can only be the overrides being honoured. They are separate mechanisms with the
# same effect on the state and DIFFERENT evidence strings, which is the part a port is liable
# to collapse: `machines.describe` distinguishes "manual override" from "no machine needed
# (bred, grown or laid)" in the evidence rather than in the state, precisely so a reader can
# check the claim.
OVERRIDDEN = scenario(machine_overrides={"nuclearcraft_crystallizer": "have"},
                      no_machine=["modularmachinery.recipes.ender_stone_crucible"])

# #50's terminator, and its two failure clauses. `projecte.available` is "LEARNED AND
# CARRYING A POSITIVE EMC VALUE", and both halves need a fixture because a port that drops
# either one still produces a plausible plan.
#
# Exoskeleton Plate is #50's own worked example and the value comes from the pack, not from
# here: emc 2,048, and its ONLY real producer is `minecraft.crafting` from a Dungeon Drop
# plus a Battle Tower token -- i.e. "dropped by a dungeon", the dead end #50 exists to
# replace. So the learned and unlearned plans for one key differ by a whole subtree, which is
# the sharpest contrast available.
EMC_LEARNED = scenario(emc_knowledge={"learned": ["erebus:materials"],
                                      "emc": 1000000, "full": False, "players": 1})

# Jade is learned and carries NO EMC value at all -- `sources/emc` keeps positive values
# only, so an item the pack has disabled is absent rather than zero. It must still dead-end.
# This is #50's stated worst case: asserting a route the pack has actually disabled would be
# worse than the dead end it replaces.
EMC_UNVALUED = scenario(emc_knowledge={"learned": ["erebus:materials:1"],
                                       "emc": 1000000, "full": False, "players": 1})

# A pin whose fingerprint matches nothing, so `pins.resolve` falls back to the CATEGORY and
# the solver is left to rank among that category's recipes. A DELIBERATELY DEAD FINGERPRINT,
# because a live one is a hash of a recipe's shape and would have to be re-derived by hand
# every time the pack or the dump changes -- which is a fixture that rots into a lapsed pin
# without saying so. The lapse is the thing under test here, so pinning it that way is
# honest rather than lazy.
PINNED = scenario(pins={
    "nuclearcraft:compound:7": {"fingerprint": "0" * 16,
                                "category": "nuclearcraft_crystallizer",
                                "label": "Borax from Boric Acid"},
})

TARGETS = [
    Target(
        "in-stock", "minecraft:hopper", qty=2, sc=STOCKED,
        expect=("have", "partial", "from_stock", "craft", "craftable"),
        why="Inventory is CONSUMED, not just checked: one hopper's worth of stock against "
            "a request for two has to cover the first and part of the second, so two "
            "sibling branches cannot both claim the same five ingots. The single-pass "
            "ordered walk in `Solver.take` is the only thing that gets this right, and "
            "nothing else in this set exercises `used_from_stock` at all. The poor iron "
            "ore at the bottom is declared AE2-autocraftable, which is a fourth path "
            "again: it comes back `have` with a note and contributes NOTHING to "
            "`used_from_stock`, because a craftable is not a finite pile and adding it to "
            "the pool would report a made-up number as drawn from stock. `craftables` is "
            "read by `expand`, `score_recipe`, `ore_backed`, `_alternative_rank` and "
            "`resolve_ore`, so a port that dropped it would still pass every other "
            "fixture in this set."),
    Target(
        "fluid-chain", "fluid:nethengeic_fluid",
        expect=("fluid", "craft", "raw", "oredict", "alternatives", "token",
                "tokens_needed", "machine", "source", "not_truncated"),
        why="Strong Mythic Essence, and this is BOTH the fluid fixture and the deep one. A "
            "fluid is not a small variation on an item: `cost.FLUID_SCALE` divides both "
            "sides of every recipe it touches, and scaling one side alone makes every "
            "fluid-to-fluid hop 1000x cheaper while the table still looks populated (see "
            "cost.py). It is also 347 nodes finishing inside the default budget, which is "
            "the only fixture here that pins the whole walk rather than the first few "
            "hops: recipe choice at every level, the ancestor set, 27 oredict slots, 7 "
            "token leaves reported apart from the shopping list, machines and free "
            "sources. A port that gets one scoring term wrong diverges somewhere in here "
            "even when every small fixture agrees.\n\nIt took over the deep-chain role "
            "from `extendedcrafting:singularity_custom:1012`, which completed on the "
            "schema-3 oracle and EXHAUSTS ITS WORK BUDGET on the schema-5 one after 135 "
            "seconds. A 135-second fixture on a tool that reruns whenever a cost constant "
            "moves is a fixture that stops being regenerated."),
    Target(
        "truncated", "fluid:nethengeic_fluid", max_nodes=40,
        expect=("depth", "truncated", "craft"),
        why="The node budget, on a tree known to be an order of magnitude larger than the "
            "cap. A SMALL CAP RATHER THAN A HUGE ITEM ON PURPOSE: `avaritia:resource:3` "
            "does truncate at the default 4,000, but by exhausting the WORK budget after "
            "63 seconds, which is a different exit (`exhausted`) and a fixture nobody "
            "will wait for. This one is 40 nodes and instant, and it pins the `depth` "
            "leaves, `truncated`, and `max_nodes` being reported as the cap rather than "
            "as the count it stopped at."),
    Target(
        "dimension-gate", "contenttweaker:sednanite_ore",
        expect=("raw", "dimension", "shopping_list", "not_truncated"),
        why="#112 at the root: an ore registered `oreSednanite` is something you mine, so "
            "#106 stops the plan there -- and you mine it on Sedna, which the reference "
            "save has never visited. One node, and the whole point is the `dimension` "
            "field and the note beside it. Without this the oracle cannot show a port "
            "that the trip is priced and named."),
    Target(
        "dimension-in-chain", "contenttweaker:material_part:77",
        expect=("craft", "raw", "dimension", "oredict", "alternatives", "machine",
                "not_truncated"),
        why="Rhenium Dust, which is #112 reached BELOW the root rather than at it. The "
            "fixture above plans the gated ore directly, so a port could pass it while "
            "gating only the item asked for; here the trip to Rhenia is discovered part "
            "way down a chain, through an oredict slot and a machine, which is how a "
            "player actually meets one. `cost` has already priced the trip and this is "
            "what lets the plan say WHICH -- a route that got dearer without saying why "
            "is worse than one that never mentioned the trip."),
    Target(
        "dimension-shadow", "contenttweaker:sub_block_holder_1:2",
        expect=("raw", "dimension", "not_truncated"),
        why="#117, which is NOT the same assertion as the fixture above. The pack "
            "registers this rock twice -- a hand-made block and a ContentTweaker "
            "MaterialSystem part packed into a shared holder -- and 26 recipes consume "
            "this second id literally. Gating only the declared key left the node saying "
            "'mined on Sedna' above a price that had not moved. Same dimension, different "
            "key, and a port that folds only one of them is wrong in a way the other "
            "fixture cannot see."),
    Target(
        "multiblock", "fluid:liquid_void",
        expect=("craft", "raw", "machine", "machine_to_build", "not_truncated"),
        why="Weak Mythic Essence comes out of an Ender Stone Crucible, a Modular Machinery "
            "multiblock. #93 prices those by their STRUCTURE rather than by the blueprint "
            "and blank controller their recipe asks for, which is the difference between "
            "two items and up to 8,813 placed blocks. Three nodes, and it is the only "
            "fixture here that reaches `multiblocks.structure_cost` and puts a category "
            "into `machines_to_build`."),
    Target(
        "cycle", "aoa3:heart_fruit_seeds",
        expect=("cycle", "craft", "oredict", "not_truncated"),
        why="#61's byproduct cycle, the shape an ancestor set structurally cannot see: an "
            "insolator emitting 12 Heart Fruit plus 1 Heart Fruit Seed while eating a "
            "seed loops through the OTHER output, so `_build` passing `ancestors | {key}` "
            "never covers it and `score_recipe` has to count a recipe's own outputs. The "
            "tree still bottoms out on one `cycle` leaf, which is the backtracking in "
            "`expand` keeping the most informative attempt when every route loops."),
    Target(
        "token-gate", "contenttweaker:dungeon_drop", qty=3,
        expect=("token", "tokens_needed", "not_truncated"),
        why="#105: a pack placeholder standing in for an instruction. It leaves the "
            "shopping list -- '3 Dungeon Drop' among things to gather reads as an item -- "
            "and is reported on its own. Until #105 all 11 GATE placeholders fell through "
            "`BASE_RAW_COST`, so a locked quest chapter cost what a cobblestone costs and "
            "the error ran one way: a gated route was always at least as cheap as the "
            "ungated one beside it."),
    Target(
        "free-source", "minecraft:cobblestone", qty=64, sc=GENERATOR,
        expect=("source", "from_sources", "not_truncated"),
        why="An input-free block has no recipe, which is exactly why a recipe graph cannot "
            "find it -- so `generators.py` matches placed tile entities against a curated "
            "list. FREE IS NOT ZERO: seeding the output at 0 blinds the ranker to quantity "
            "and a plan asks for a swimming pool, so 64 rather than 1 makes the reported "
            "draw visible. It is reported in its own list and never folded into stock, "
            "because a pool is finite and this is not."),
    Target(
        "machine-choice", "nuclearcraft:compound:7",
        expect=("craft", "raw", "machine", "not_truncated"),
        why="Borax, the canary for the whole low-end calibration: `tools/cost-probe.py` "
            "treats it resolving to `nuclearcraft_crystallizer` as the signal that "
            "`BUILD_SCALE` and `BUILD_KNEE` are still right. Its candidates span a "
            "chemical reactor, a crystallizer, a world transmutation and plain crafting, "
            "so the chosen category IS the cost model's answer, and a port with the band "
            "ordering wrong picks a different one here before it fails anywhere else."),
    Target(
        "pin-lapsed", "nuclearcraft:compound:7", sc=PINNED,
        expect=("craft", "pinned", "not_truncated"),
        why="A pin identifies a recipe by a fingerprint over its shape so it survives a "
            "redump renumbering every id. When the pack changes the recipe the fingerprint "
            "stops matching and the pin falls back to its CATEGORY, which is the path this "
            "pins the port to -- the solver keeps its own ranking among whatever the "
            "category permits rather than being handed a choice made by dump order. The "
            "`pinned` badge on the node is part of the contract: a choice you cannot see "
            "is a choice you cannot audit."),
    Target(
        "machine-override", "nuclearcraft:compound:7", sc=OVERRIDDEN,
        expect=("craft", "machine", "machine_have", "not_truncated"),
        why="Machine availability is EVIDENCE, not configuration -- placed tile entities "
            "read out of the world save -- with exactly one exception: a manual override "
            "always wins. Against `machine-choice`, which plans the same item with the same "
            "budget and no overrides, this fixture is a controlled experiment: the only "
            "difference is that the Crystallizer is declared owned, so the two trees must "
            "differ in exactly one node's `machine_state` and nowhere else. A port that "
            "ignores overrides produces two identical trees, which is a failure that no "
            "single fixture could show."),
    Target(
        "no-machine-declared", "fluid:liquid_void", sc=OVERRIDDEN,
        expect=("craft", "machine", "machine_have", "not_truncated"),
        why="The `no_machine` half of the override scenario, against `multiblock` as its "
            "control: the same Ender Stone Crucible, the same fluid, and the category "
            "declared to need no machine at all. It should stop appearing in "
            "`machines_to_build` and its node should read `have`. Paired with "
            "machines-overridden.json, which is the only file where this and a manual "
            "override can be told apart -- a plan node drops the evidence string once the "
            "state is `have`."),
    Target(
        "emc-terminator", "erebus:materials", sc=EMC_LEARNED,
        expect=("emc", "from_emc", "not_truncated"),
        why="#50. Exoskeleton Plate is learned and worth 2,048 EMC, so the plan STOPS on it "
            "rather than descending -- a new terminal status in the `source` family, "
            "evaluated after stock and free sources and before any recipe lookup. The node "
            "carries the value and the grounds (\"EMC 2,048, learned\") because a bare "
            "\"from EMC\" is something a reader has to take on trust, and it is reported in "
            "`from_emc` rather than on the shopping list, since EMC is not a thing to go and "
            "get. Nothing else in this set reaches the status at all, so a port could "
            "implement none of #50 and pass every other fixture."),
    Target(
        "emc-not-learned", "erebus:materials",
        expect=("craft", "token", "tokens_needed", "!emc", "!from_emc", "not_truncated"),
        why="The FIRST clause of `projecte.available`, as the control for the fixture above. "
            "Same key, same qty, same everything except that nothing has been learned -- and "
            "the plan descends into the only route the graph knows, a Dungeon Drop plus a "
            "Battle Tower, which is exactly the dead end #50 exists to replace. The pair "
            "differs by a whole subtree, so a port that ignores knowledge and terminates on "
            "any valued item produces two identical trees."),
    Target(
        "emc-unvalued", "erebus:materials:1", sc=EMC_UNVALUED,
        expect=("craft", "raw", "!emc", "!from_emc", "not_truncated"),
        why="The SECOND clause, and the one that matters most. Jade is LEARNED and carries "
            "no EMC value, because `sources/emc` keeps positive values only so that absence "
            "never reads as a route the pack has disabled. It must dead-end normally. #50's "
            "stated worst case is asserting a route the pack turned off, which would be "
            "worse than the dead end it replaces -- a port checking only `learned` passes "
            "the two fixtures above and fails here, which is the whole reason this one "
            "exists."),
    Target(
        "unsourced-variant", "animus:kama_bound",
        expect=("raw", "unsourced", "shopping_list", "not_truncated"),
        why="#170's reported case, and the only fixture that reaches the THIRD face of "
            "`reachable_form`. The Alchemy Array makes `animus:kama_bound#fd1adc426e12` and "
            "four recipes ask for the bare key, so the graph holds a 53.35 route and "
            "`cost._seed` still prices the bare key at BASE_RAW_COST -- which reads to a "
            "player as \"you already have this\". 96 bare keys are in that state with "
            "4,193 produced variants behind them.\n\nIT EXISTS BECAUSE NOTHING ELSE "
            "REACHED IT. The first regeneration after the mark landed moved no fixture at "
            "all, so the Java port of that branch had unit tests on one side and no golden "
            "gate across the two. One node and a note is the cheapest thing that makes the "
            "port prove it agrees.\n\nThe mark is REPORTING ONLY: `status` is still `raw` "
            "and the key is still on the shopping list, because whether the solver should "
            "ROUTE through a produced variant is contested -- #28 refused that widening in "
            "`producers` and its test still passes. A fixture asserting the route would be "
            "asserting the contested half."),
    Target(
        "unsourced-price", "contenttweaker:heuf_fuel",
        expect=("craft", "raw", "!unsourced", "shopping_list", "not_truncated"),
        why="#176's reported case, and the fixture that exists BECAUSE it hits the "
            "population rather than because it happens to. `UNSOURCED_COST` moves 47,674 "
            "keys, and a fixture chosen for any other reason is coverage by luck.\n\n"
            "Reported as \"HEUF Fuel requires a fissile drone. this is true, but how do i "
            "get a fissile drone?\" Both routes to this fluid need a fissile bee; the "
            "drone is unsourced and the graph had already badged it so, while `cost._seed` "
            "priced it at BASE_RAW_COST -- the CHEAPEST value in the model -- so the "
            "solver actively preferred the one route it could not explain.\n\nTHE "
            "ASSERTION IS `!unsourced`, WHICH IS THE POINT. Before #176 this plan carried "
            "one unsourced node, `forestry:bee_drone_ge#531347dffc8e`. After, the same "
            "recipe takes a different slot alternative -- `bee_princess_ge#531347dffc8e` "
            "-- and the mark is gone. A fixture asserting the mark PRESENT would have "
            "passed before and after; asserting its ABSENCE is what makes the port prove "
            "the price reached the routing.\n\nWhat it deliberately does not claim: "
            "whether a Fissile Princess is obtainable in the pack is a progression "
            "question the graph cannot answer and #176 raises separately. The plan is no "
            "longer self-contradictory, which is all a price can buy."),
    Target(
        "variant-table", "chisel:concrete_brown:1",
        expect=("craft", "raw", "!unsourced", "not_truncated"),
        why="#110. Chisel publishes one entry per material listing all 37 variants in BOTH "
            "columns, which flattens to 'all 37 in, all 37 out' and scored as a no-op, so "
            "all 341 tables were dropped and 6,856 variant keys were left with no producer "
            "at `BASE_RAW_COST` -- `chisel:lapis:1` priced BELOW the lapis block it is "
            "chiselled from. `index.expand_interconversion` and `cost._settle_reshaped` "
            "are both build-time and both invisible except in a plan like this one.\n\n"
            "IT USED TO CARRY A #139 `unsourced` MARK AND #176 PRICED IT AWAY, which is "
            "why the claim is now negated rather than deleted. The plan rested on a chicken "
            "spawn egg -- an NBT state the graph has no route to -- and `UNSOURCED_COST` "
            "made that route lose to one the graph can account for, taking the plan from 10 "
            "nodes to 15. The generator REFUSED to write this fixture until the claim was "
            "corrected, which is the coverage check doing its job: a fixture that no longer "
            "reaches the path it names passes forever while asserting nothing.\n\n"
            "`!unsourced` rather than dropping the claim, because the absence is now the "
            "interesting fact about this plan. `plan-unsourced-variant` is the sole fixture "
            "carrying a live mark, and `plan-unsourced-price` is the one asserting that a "
            "mark the price removed stays removed."),
    Target(
        "same-name", "thermalfoundation:material:32",
        expect=("craft", "raw", "oredict", "not_truncated"),
        why="#101. Six keys on this pack are called 'Iron Plate' and the pack routes its "
            "recipes through exactly one of them; this is the one with eight producers, "
            "against `abyssalcraft:ironp` and `immersiveengineering:metal:39`, which have "
            "only crafting. A plan is keyed by KEY and never by name, and pinning the "
            "canonical one here is what makes a port's answer comparable at all -- 5,095 "
            "display names are shared across 21,888 keys."),
]


# ---------------------------------------------------------------------------
# Emitting
# ---------------------------------------------------------------------------

def graph_identity(graph, path):
    """What oracle these fixtures were generated against, in a form a reader can check.

    THE SHA IS THE POINT AND THE COUNTS ARE THE EXPLANATION. "The solver changed" and "the
    oracle moved" produce exactly the same fixture diff, and only one of them is a bug, so
    `--check` and `tests/test_plan_fixtures.py` both report this comparison before the
    content one. The counts are here because a bare hash mismatch says nothing about WHAT
    moved, and `dimension_ores` in particular is the tell for an oracle built before #112 and
    #117 -- three of the targets below would assert the pre-fix behaviour against such a
    graph while looking exactly as green, so `generate` refuses one.
    """
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return {
        "sha256": h.hexdigest(),
        "bytes": os.path.getsize(path),
        "dump_schema": graph.dump_schema,
        "recipes": len(graph.recipes),
        "names": len(graph.names),
        "ore_members": len(graph.ore_members),
        "multiblocks": len(graph.multiblocks or {}),
        "dimension_ores": len(graph.dimension_ores or {}),
    }


def dump(doc):
    """The bytes a fixture file holds.

    `sort_keys` because dict order is the one thing about this output that is not decided
    by the solver, and `allow_nan=False` because `json.dumps` writes a bare `Infinity` for
    an unpriced key, which is Python-specific and not valid JSON -- gson rejects it, so the
    Java side would fail to parse rather than fail to match. Costs are flattened to null
    before they get here by `api.jsonable`, the same flattening `/api/cost` uses.

    Indented, unlike `api.dumps`, so a fixture diff is readable line by line. That is the
    ONLY difference from what `/plan?fmt=json` puts on the wire.
    """
    return json.dumps(api_mod.jsonable(doc), sort_keys=True, indent=1,
                      allow_nan=False) + "\n"


def write(path, doc):
    with open(path, "w") as fh:
        fh.write(dump(doc))
    return os.path.getsize(path)


def plan_fixture(graph, env, target, identity):
    """`(document, [complaint])`. THE COMPLAINTS ARE COLLECTED, NOT RAISED.

    Every relaxation this run needed has already been paid for by the time the first claim
    fails, and dying there means paying for it again to find out whether anything else is
    wrong too. On a two-minute-per-scenario tool that is the difference between one
    diagnosis and four.
    """
    solver = solver_for(graph, env, target.max_nodes)
    result = solver.solve(target.item, target.qty)
    failed = [tag for tag in target.expect if not holds(tag, result)]
    complaints = ["%s: claims %s and the plan shows none of it -- statuses %s"
                  % (target.name, failed, dict(_statuses(result)))] if failed else []
    return {
        "_": BANNER,
        "why": target.why,
        "covers": sorted(target.expect),
        "graph": identity,
        "request": {"item": target.item, "qty": target.qty,
                    "max_nodes": target.max_nodes},
        "scenario": target.scenario,
        "result": result,
    }, complaints


def fmt_cost(value):
    """A cost as text both languages spell the same way.

    ONLY FOR THE DIGEST, never for a value a reader or a test compares. The sampled prices
    below are plain JSON numbers, because `repr` is the shortest round-tripping form and
    survives JSON exactly in both languages -- but `repr` DISAGREES AS TEXT across the two,
    measured at 1,966 of 6,012 doubles (33%) with ZERO differing in value: Python writes
    `1e-09` where Java's `Double.toString` writes `1.0E-9`. Hashing that would produce a
    digest that can never match however right the port is.

    17 SIGNIFICANT DIGITS, NOT FEWER, AND THAT IS THE WHOLE POINT OF THE NUMBER. Any two
    distinct binary64 values differ within 17 significant decimal digits, so `%.17e` is
    injective over doubles and the digest detects a drift of one ULP. This was `%.12e`,
    which is NOT injective: two prices differing below 1e-13 relative hash the same, so a
    subtly drifting port would have passed the one check that covers the whole table.
    `%.17e` is byte-identical in Python and Java, including the two-digit exponent.
    """
    return "inf" if math.isinf(value) else "%.17e" % value


def cost_digest(costs):
    h = hashlib.sha256()
    for key in sorted(costs):
        h.update(("%s %s\n" % (key, fmt_cost(costs[key]))).encode("utf-8"))
    return h.hexdigest()


def cost_fixture(graph, env, identity, sampled):
    """The cost table: every machine entry cost, a curated sample, and a digest over the lot.

    THREE LAYERS, EACH DOING SOMETHING THE OTHERS CANNOT. The digest covers all 162,743
    prices, which is the only way a small file can pin the whole table -- but a digest
    mismatch says only "these differ", so the sample says WHERE, and it is `cost-probe.py`'s
    own eighteen items plus every fixture target, i.e. prices a human can judge. The machine
    entry costs are all 485 of them because that band is where #86, #93, #95 and #96 all
    live: the failure it keeps having is a CLUSTER on one value, and 140 categories sharing
    a number is invisible in any sample and obvious in the census below.

    `region_census` is the shape of the whole band rather than any one price, which is the
    thing `tools/entry-census.py` exists to see and a route probe is structurally blind to.
    """
    costs = env["costs"]
    entry = dict(costs.machine_entry or {})
    census = collections.Counter(cost_mod.region_of(v) for v in entry.values())
    return {
        "_": BANNER,
        "why": "cost.py is ported before solve.py and every plan above depends on it. The "
               "digest pins all of it; the sample says where a mismatch is; the entry "
               "costs and their census pin the machine band, whose failure mode is a "
               "cluster on one value rather than a wrong number.",
        "graph": identity,
        "scenario": BARE,
        "formula_version": cost_mod.FORMULA_VERSION,
        "constants": {name: getattr(cost_mod, name)
                      for name in sorted(PINNED_CONSTANTS)},
        "machine_cost": dict(cost_mod.MACHINE_COST),
        "entries": len(costs),
        "digest": cost_digest(costs),
        "digest_format": "sha256 over '<key> <%.17e of the cost>\\n' for every key in "
                         "sorted order, with a non-finite cost written as the literal "
                         "'inf'. NOT repr: 33% of doubles render differently as text in "
                         "Python and Java while differing in value in 0% of cases. 17 "
                         "significant digits is injective over binary64, so this detects a "
                         "one-ULP drift.",
        "sample": {key: costs.get(key, math.inf) for key in sorted(sampled)},
        "machine_entry": dict(sorted(entry.items())),
        "region_census": dict(sorted(census.items())),
    }


def machines_fixture(graph, env, identity, sc, why):
    """Machine availability, which is what actually picks recipes.

    EVERY CATEGORY, not a sample. `unknown` is not a synonym for `unavailable` and
    collapsing them walls off 40% of the graph, so the interesting content of this file is
    the STATE SPREAD across all 504 categories -- a port whose title matcher is slightly
    different moves a handful of categories and changes plans nowhere near them. The
    evidence string is included because "why does it think I do not have this" has to stay
    answerable, and it is the part a heuristic gets subtly wrong.
    """
    info = env["info"]
    return {
        "_": BANNER,
        "why": why,
        "graph": identity,
        "scenario": sc,
        "summary": machines_mod.summarise(env["states"]),
        "build_targets": {uid: list(keys)
                          for uid, keys in sorted(env["targets"].items())},
        "categories": {uid: _category(rec) for uid, rec in sorted(info.items())},
    }


def _category(rec):
    """One `describe` record, in the shape both machine fixtures record.

    ONE spelling, because the delta above compares records written by this function against
    records read back out of machines.json; a field present in one and not the other would
    report every category as changed.
    """
    return {"title": rec["title"], "mod": rec["mod"], "recipes": rec["recipes"],
            "state": rec["state"], "why": rec["why"], "manual": rec["manual"],
            "from_catalyst": rec.get("from_catalyst", False),
            "candidates": list(rec["candidates"]),
            "candidate_states": rec["candidate_states"]}


def machines_delta(graph, base_env, env, identity, sc):
    """What a manual override and a `no_machine` declaration change, and nothing else.

    A DELTA RATHER THAN A SECOND FULL TABLE, and the reason is not file size alone. The whole
    claim being made is "these two categories move and 502 do not", and a second 372 KB copy
    of machines.json states that claim only by being diffed against the first -- so the claim
    lives in whatever test happens to do the diffing, and a fixture that quietly started
    moving a third category would still look right. Written this way the claim IS the file.

    IT IS ALSO THE ONLY PLACE THE TWO MECHANISMS CAN BE TOLD APART. A plan node carries a
    machine STATE and, only when that state is not `have`, an evidence string -- so once an
    override lands, a plan tree cannot distinguish a machine you own, one you overrode, and
    one declared to need no machine. `machines.describe` keeps them apart in the evidence on
    purpose, so that "why does it think I have this" stays answerable, and a port is free to
    collapse all three into one boolean unless something holds it to the difference.
    """
    before = base_env["info"]
    after = env["info"]
    changed = {uid: {"from": _category(before[uid]), "to": _category(rec)}
               for uid, rec in sorted(after.items())
               if _category(rec) != _category(before[uid])}
    base_targets, targets = base_env["targets"], env["targets"]
    return {
        "_": BANNER,
        "why": "A manual override and a `no_machine` declaration, against machines.json as "
               "the control. Both reach state `have` and they are NOT the same claim: one "
               "is the user overruling the evidence, the other is production driven by a "
               "creature or by a structure JEI does not catalyse. Everything not listed "
               "here is asserted to be byte-identical to machines.json.",
        "graph": identity,
        "scenario": sc,
        "summary": machines_mod.summarise(env["states"]),
        "categories": len(after),
        "changed": changed,
        # A category that stops being buildable stops being priced from a machine item, so
        # its entry cost leaves the band entirely. Dropping out of `build_targets` is that
        # consequence, and it is not visible in the record above.
        "build_targets_removed": sorted(set(base_targets) - set(targets)),
        "build_targets_added": sorted(set(targets) - set(base_targets)),
    }


# The prices a human can judge at a glance, so a digest mismatch can be localised.
# IMPORTED FROM `tools/cost-probe.py` RATHER THAN RESTATED. Those eighteen were chosen
# because each has a real choice to get wrong and four are a control group that is correct
# today; a second list here would be a second answer to "which items matter", and the two
# would drift the first time the audit's list was extended. Loaded through importlib for the
# same reason `tests/test_entry_census.py` does it -- `tools/` is not a package and the
# filename is hyphenated.
def _cost_probe():
    path = os.path.join(ROOT, "tools", "cost-probe.py")
    spec = importlib.util.spec_from_file_location("cost_probe", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


COST_PROBE_ITEMS = [key for key, _label in _cost_probe().PROBES]


# Every constant the relaxation reads, recorded beside the prices it produced. A port that
# copies the arithmetic and mistypes one of these produces a table that is wrong everywhere
# and a digest mismatch that says nothing; with these in the file the first thing to compare
# is the inputs. `FORMULA_VERSION` is separate because it is the cache's tripwire rather
# than an input to the arithmetic.
PINNED_CONSTANTS = (
    "BASE_RAW_COST", "BLOCKED_CEILING", "BLOCKED_FLOOR", "BUILD_KNEE", "BUILD_SCALE",
    "BUILD_SLOPE", "BUILD_SPREAD", "DIMENSION_COST", "FLUID_SCALE", "GATE_COST",
    "LOOT_COST", "PASSES", "PRICED_CEILING", "SETTLED_FRACTION", "TRANSFER_PENALTY",
    "UNGATED_MACHINE_COST", "UNPRICED_MACHINE_COST",
)


def generate(graph_path):
    """`{filename: document}` for the whole set. Pure enough to diff against what is on disk.

    ONE COST TABLE PER PRICED ENVIRONMENT, memoised. `cost.estimate` is two full relaxations
    over 124,471 recipes and measures about two minutes on the reference oracle, so pricing
    per target would make this a twenty-minute tool and would tempt the next person into
    `estimate_cached`, which is the one thing that must not happen here.
    """
    graph = Graph.load(graph_path)
    identity = graph_identity(graph, graph_path)
    if not graph.dimension_ores:
        raise SystemExit(
            "%s has no dimension_ores, so it was built before #112/#117 and cannot exercise "
            "the dimension gates at all -- three targets would assert the pre-fix behaviour "
            "and look green doing it. Build an oracle with current code first."
            % graph_path)
    if not getattr(graph, "emc", None):
        raise SystemExit(
            "%s carries no emc, so it predates schema 5 and #50's terminator can never fire "
            "-- the EMC fixtures would freeze the ABSENCE of the feature, which a port "
            "passes by implementing nothing. Rebuild from a schema-5 dump." % graph_path)

    priced = {}

    def env_for(sc):
        _check_scenario(graph, sc)
        derived = derive_inputs(graph, sc)
        seen = cost_signature(derived)
        if seen not in priced:
            priced[seen] = priced_environment(graph, derived)
        return resolve_scenario(graph, sc, priced[seen])

    out = collections.OrderedDict()
    complaints = []
    for target in TARGETS:
        if target.item not in graph.names and not graph.producers(target.item):
            raise SystemExit("%s: no such key in the oracle: %s"
                             % (target.name, target.item))
        doc, said = plan_fixture(graph, env_for(target.scenario), target, identity)
        out["plan-%s.json" % target.name] = doc
        complaints.extend(said)
    if complaints:
        raise SystemExit(
            "Either the pack's data moved under these targets or the claims were wrong. "
            "Fix the target; DO NOT drop the check -- a fixture that no longer reaches the "
            "path it names is one that passes forever while asserting nothing.\n  %s"
            % "\n  ".join(complaints))

    bare = env_for(BARE)
    sampled = set(COST_PROBE_ITEMS) | {t.item for t in TARGETS}
    out["cost.json"] = cost_fixture(graph, bare, identity, sampled)
    out["machines.json"] = machines_fixture(
        graph, bare, identity, BARE,
        "machines.py decides which recipes a plan prefers, from JEI's own catalyst map "
        "where the dump has one and from a title heuristic where it does not. Every "
        "category, with its evidence, because the failure is a few categories moving "
        "rather than the table being wrong.")
    out["machines-overridden.json"] = machines_delta(
        graph, bare, env_for(OVERRIDDEN), identity, OVERRIDDEN)
    return out


def _check_scenario(graph, sc):
    """A scenario input that names nothing is a fixture that quietly asserts less.

    Stock for a key the graph has never heard of is not an error anywhere in the tool -- the
    pool simply never matches -- so an `in-stock` fixture built on a typo'd key produces a
    perfectly valid plan in which nothing is in stock, and the fixture goes green forever
    while covering the opposite of what it claims. A placed generator id that names no known
    source, and a craftable the graph does not have, fail exactly the same silent way. So
    every one of them is checked here, where the claim is made rather than where it lapses.
    """
    for field in ("have", "craftables"):
        for key in sc[field]:
            if key not in graph.names and not graph.producers(key):
                raise SystemExit("scenario %s names a key the oracle does not have: %s"
                                 % (field, key))
    known = set(generators_mod.DEFAULT_GENERATORS) | set(
        (sc["source_overrides"].get("generators") or {}))
    for block in sc["placed"]:
        if block not in known:
            raise SystemExit(
                "scenario places %s, which generators.py does not know as a source, so it "
                "would make nothing free" % block)
    categories = set(r.category for r in graph.recipes)
    for uid, state in sc["machine_overrides"].items():
        if state not in machines_mod.STATES:
            raise SystemExit("machine override %s=%s is not one of %s"
                             % (uid, state, sorted(machines_mod.STATES)))
        if uid not in categories:
            raise SystemExit("machine override names no category in the oracle: %s" % uid)
    for uid in sc["no_machine"]:
        if uid not in categories:
            raise SystemExit("no_machine names no category in the oracle: %s" % uid)
    # A learned key the graph has never heard of resolves to nothing available, so an EMC
    # fixture built on a typo terminates nowhere and quietly asserts the pre-#50 behaviour.
    # The VALUE is deliberately not checked here: `emc-unvalued` learns a key with no EMC on
    # purpose, because that is the clause under test.
    for key in (sc["emc_knowledge"].get("learned") or ()):
        if key not in graph.names and not graph.producers(key):
            raise SystemExit("emc_knowledge learns a key the oracle does not have: %s" % key)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--graph", default=os.environ.get(ORACLE_ENV),
                    help="the oracle graph; defaults to $%s" % ORACLE_ENV)
    ap.add_argument("--out", default=OUT_DIR)
    ap.add_argument("--check", action="store_true",
                    help="regenerate and diff against what is on disk, writing nothing")
    args = ap.parse_args()
    if not args.graph:
        ap.error("no oracle graph: pass --graph or set $%s. NOT data/graph.json -- see the "
                 "module docstring." % ORACLE_ENV)

    docs = generate(args.graph)
    if args.check:
        return _check(docs, args.out)

    os.makedirs(args.out, exist_ok=True)
    # Written before anything is removed, so a crash mid-run leaves a complete older set
    # rather than a hole. Stale files ARE removed, because a renamed target otherwise
    # leaves its old fixture behind for the Java suite to keep asserting.
    total = 0
    for name, doc in docs.items():
        total += write(os.path.join(args.out, name), doc)
    for name in sorted(os.listdir(args.out)):
        if name.endswith(".json") and name not in docs:
            os.remove(os.path.join(args.out, name))
            print("removed stale %s" % name)
    for name in docs:
        print("%-34s %7d bytes" % (name, os.path.getsize(os.path.join(args.out, name))))
    print("%d files, %.1f KB total" % (len(docs), total / 1024.0))
    return 0


def _check(docs, out_dir):
    """Non-zero when the fixtures on disk are not what this run produced."""
    bad = []
    for name, doc in docs.items():
        path = os.path.join(out_dir, name)
        want = dump(doc)
        try:
            with open(path) as fh:
                got = fh.read()
        except OSError:
            bad.append("%s: missing" % name)
            continue
        if got != want:
            bad.append("%s: differs" % name)
    # `os.listdir` on a checkout that has never generated would raise, which reads as a
    # broken tool rather than as "there is nothing here yet".
    present = sorted(os.listdir(out_dir)) if os.path.isdir(out_dir) else []
    extra = [n for n in present if n.endswith(".json") and n not in docs]
    bad.extend("%s: not produced by this tool any more" % n for n in extra)
    for line in bad:
        print(line)
    print("%d of %d fixtures differ" % (len(bad), len(docs)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
