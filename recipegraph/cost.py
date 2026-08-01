"""Estimated cost to obtain each item, so recipe choice can stop being greedy.

WHY THIS EXISTS. Scoring a recipe by local properties alone -- do I own the machine, are
its inputs in stock, how few inputs does it have -- cannot see what a branch costs further
down. Two observed failures, both from real runs:

  * without machine gating, "64 Borax" routed through Chaos Fragments and Nether Stars;
  * with machine gating but still greedy, it preferred an enormous chain through machines
    it owned (11,000,000 mB of water, bacteria vectors, Pink Tulips) over a two-step
    chemical route through a Crystallizer it merely had to build.

Both are the same mistake: a locally attractive recipe whose subtree is ruinous. The fix
is to precompute, for every item, roughly what it costs to obtain, then let the solver
pick the recipe with the cheapest total. That turns choice from a guess into a comparison.

THE ESTIMATE IS A RANKING, NOT A PLAN. It ignores cycles, assumes inputs can be had
independently, and never double-counts shared intermediates. It exists to rank recipes,
not to be reported as a real cost. DO NOT surface these numbers to users as quantities.

It is deliberately NOT a lower bound, and that distinction is the fix for issue #29. A true
lower bound amortises everything over the output quantity, and the pack contains recipes
that output 1,024 iron ingots or 60,466,176 fruit at once; amortising the machine over
those makes it free and prices the output at nothing. Cost of ENTRY (the machine) is
charged per run and only the ingredients amortise, so a route cannot be made cheap by
being enormous. See the comment on `per_unit` in `estimate`.

Costs are relaxed iteratively (Bellman-Ford style) rather than solved exactly: the graph
has cycles and AND-nodes (a recipe needs *all* its inputs), so there is no simple
shortest-path formulation. A handful of passes converges well enough to rank by.
"""

import hashlib
import json
import math
import os

from . import multiblocks as multiblocks_mod
from .defaults import DEFAULT_COST_CACHE

# What a machine costs to route through. Owning it is nearly free; building one is a real
# but one-off expense; using one you cannot get should lose to almost anything.
#
# `unknown` sits between buildable and unavailable ON PURPOSE. It means the category's
# machine could not be identified, which is a gap in this tool, not a fact about the
# player's base, so it must not be priced as unusable -- doing that put 40% of the
# reference pack's recipes behind a 5,000 wall. It must also not undercut `buildable`,
# or an unidentified machine would beat one the player can demonstrably build.
MACHINE_COST = {"have": 1.0, "buildable": 40.0, "unknown": 120.0, "unavailable": 5000.0}

# `buildable` above is a FLOOR, not the whole answer. Two machines you have to build are
# not equally expensive, and on the reference pack they are not remotely equally expensive:
# measured over the 380 buildable categories whose machine item prices finitely, the build
# cost runs min 1.0, median 2.0, p90 67, max 9,288 -- a 4,644x spread between the median and
# an endgame NuclearCraft salt fission vessel. Priced by the flat constant alone, an AE2
# grindstone and a fusion reactor both charge 40.0, so the ranker cannot prefer the one the
# player can actually reach. That is issue #86.
#
# So a buildable machine's entry cost spans [MACHINE_COST["buildable"], + BUILD_SPREAD),
# ordered by what building it costs. The map is bounded and monotonic rather than the raw
# price, for two reasons:
#
#   * THE CEILING MUST STAY BELOW MACHINE_COST["unknown"]. That invariant is stated above
#     and is load-bearing: an unidentified machine outranking one the player can
#     demonstrably build is the failure the `unknown` figure was chosen to avoid. Feeding
#     the raw 9,288 in would sail past `unknown` AND past `unavailable`, making a buildable
#     machine read as worse than a proven-impossible one.
#   * The floor must stay AT MACHINE_COST["buildable"]. The have-vs-buildable gap is what
#     the Borax and Crystallizer cases in this module's header turn on, and cheapening
#     buildable machines across the board would relitigate a decision this change is not
#     about. #86 asks to tell two buildable machines apart, not to make building cheaper.
#
# BUILD_SCALE sets where the curve bends. At 64 it lands near p90 of the measured
# distribution, so the mass of the pack (median 2.0) spreads across the low end of the band
# instead of all compressing into the first percent of it.
#
# THE CURVE IS LOGARITHMIC IN THE BUILD COST, and #93 is why. It was `b / (b + BUILD_SCALE)`,
# which is calibrated for the range a machine ITEM's recipe spans (1.0 to 9,288) and
# saturates hard above it: everything past ~6,400 sits within 1% of the ceiling. Pricing a
# Modular Machinery machine by the structure it stands for widened the input range by three
# decades, to 279,861, and 42 of the 104 fully-priced multiblocks came out within 1.0 of the
# ceiling -- flattened against each other, which is exactly the defect #86 removed, only
# among the expensive machines instead of all of them.
#
# BUILD_SLOPE IS WHAT KEEPS THE LOW END WHERE #86 MEASURED IT, which is the floor bullet above
# read in the other direction: making buildable machines DEARER would relitigate the
# Crystallizer case just as surely as cheapening them, by letting an enormous chain through
# owned machines beat a two-step route through a machine merely to be built. Near b=0 the curve
# is `BUILD_SLOPE * b / BUILD_SCALE`, so the SLOPE is the calibrated quantity and the spread is
# free to move as long as the knee follows it. #93 measured the tolerance: a build cost of 1.0
# prices at 41.21 against the old 41.22, and the median 2.0 at 42.36 against 42.39, agreeing to
# within 0.05 across the mass of the pack. Any change to the SLOPE moves that whole low end, so
# re-measure with tools/cost-probe.py before touching it.
BUILD_SLOPE = 79.0
BUILD_SCALE = 64.0
BUILD_SPREAD = 70.0
BUILD_KNEE = BUILD_SPREAD / BUILD_SLOPE

# THE REGION BETWEEN THE PRICED BAND AND `unknown` IS NOT SPARE ROOM, IT IS THREE CLAIMS THAT
# USED TO SHARE ONE NUMBER. That is issue #95. Everything a price could not be computed for
# landed on the single band ceiling, and on the reference pack that was 140 of 403 categories,
# 35% of them, holding two unrelated statements and destroying the ordering among both:
#
#   * 117 Modular Machinery categories whose structure needs a block nothing in the graph
#     makes. Measured, these run from 0.14% of positions blocked (`the_cube`, 3 of 2,125) to
#     100% (`mythic_excavation_lattice`, 135 of 135), and all 117 charged 119.000.
#   * 23 categories whose machine ITEM never priced. `build_entry_cost` below already argues
#     this is a gap in the pricing rather than a fact about the base -- and then charged it the
#     same as an evidence-based impossibility.
#
# What that cost in practice: `aoa3:holly_top_petals` had a blocked Modular Machinery route beat
# a Phytogenic Insolator, both at 119.000, by 0.037 of an ingredient point. A tie between two
# different failures, broken by noise.
#
# So they get separate slices, ordered by how strong the claim is, every boundary derived from
# the two anchors rather than typed in:
#
#     have 1.0 < priced [40.0, 110.0) < unpriced item 111.0
#              < blocked structure [112.0, 119.0] < unknown 120.0 < unavailable 5000.0
#
# THE UNPRICED ITEM SITS BELOW THE BLOCKED STRUCTURE ON PURPOSE, and that ordering is the one
# thing here not to swap. "This model failed to compute a number" is a weaker claim than "the
# pack says this needs a block nothing makes", so the failure has to be the more optimistic of
# the two. Reversed, a machine we merely could not price would lose to one we know is
# unbuildable.
#
# AND THE WHOLE BLOCKED SLICE STAYS BELOW `unknown`, WHICH LOOKS BACKWARDS AND IS NOT. A
# structure proven to need an unobtainable block sounds like a stronger claim than "we could
# not identify this machine at all", so it is tempting to rank it worse. DO NOT: the blockage
# signal is known to be WRONG in a specific, unfixed way. Chisel and Unlimited Chisel Works
# variants have zero producers in the graph, because chisel recipes are dropped as
# non-recipes, so `chisel:concrete_brown:1` reads unobtainable when it is trivially
# obtainable. Any structure using one reads as blocked on a false negative. Ranking that above
# `unknown` -- let alone at the `unavailable` wall -- would put real recipes behind a verdict
# this tool cannot yet stand behind, which is the 40%-of-the-pack failure the `unknown` figure
# was chosen to avoid. The ordinal is safe BECAUSE the whole slice is bounded; the individual
# fractions inside it inherit that same unreliability and are a ranking, never a claim.
# Fixing this means answering the chisel question first, and that is what #95 left open.
PRICED_CEILING = MACHINE_COST["buildable"] + BUILD_SPREAD
UNPRICED_MACHINE_COST = PRICED_CEILING + 1.0
BLOCKED_FLOOR = UNPRICED_MACHINE_COST + 1.0
BLOCKED_CEILING = MACHINE_COST["unknown"] - 1.0

# Used only when machine gating is off entirely (no states supplied), where every category
# gets the same figure and the value is arbitrary. NOT the cost of an unidentified machine
# -- that is MACHINE_COST["unknown"] above.
UNGATED_MACHINE_COST = 20.0

# A fluid quantity is in mB, so 1000 mB of water would otherwise look a thousand times
# dearer than one item. One normalised unit is one item OR one bucket.
#
# THIS MUST BE APPLIED TO OUTPUTS TOO. Scaling only the input side made every fluid→fluid
# hop divide the cost by 1000: `1000 mB A -> 1000 mB B` charged cost[A] for the inputs and
# then divided by an output quantity of 1000, so a chain ten hops deep priced at 1e-30.
# Every fluid in the reference pack converged to 0.0 and the cost model stopped
# discriminating between routes at all -- the greedy behaviour it exists to prevent, hidden
# behind a cost table that looked populated. Go through `_scaled_qty` for both directions.
FLUID_SCALE = 1.0 / 1000.0

BASE_RAW_COST = 1.0        # an item with no recipe: assume it can be obtained somehow
TRANSFER_PENALTY = 500.0   # container fill/empty is not production; never prefer it

# Bumped whenever the per-unit FORMULA in `estimate` changes, and folded into `fingerprint`.
# The cache is keyed on the inputs (graph, stock, machine states, tuning constants) and a
# formula change moves none of them, so without this a machine holding `.cost-cache.json`
# would keep serving prices computed by the old arithmetic forever -- the one failure this
# cache must never have, and one that looks like "the fix did not work" rather than like a
# stale cache.
FORMULA_VERSION = 4

# Bellman-Ford needs one pass per edge in the longest useful path. MeatballCraft's chemistry
# runs 10+ hops deep (borax -> ... -> molten sugar), so 6 passes left the deep end of every
# chain unpriced. Measured on the reference pack: the last item gets a price at pass 12 and
# nothing new appears after 20, while relaxation over cycles keeps making sub-percent
# improvements forever -- hence a ceiling AND an early exit, not just one of them.
PASSES = 20
SETTLED_FRACTION = 0.002   # stop when a pass improves under 0.2% of recipes' outputs


class CostTable(dict):
    """Item costs, plus the per-category machine entry costs they were computed with.

    A plain `dict` everywhere it is read, so the solver keeps indexing it by item key and
    nothing else has to know this type exists. The entry costs ride ALONGSIDE rather than in
    a second argument every caller must remember to thread, because `estimate` and
    `recipe_cost` deriving that number separately is exactly how the relaxation and the
    ranking came to disagree about which alternative a slot used -- see
    `_cheapest_alternative`, which exists to fix the same class of divergence. Carried on the
    table, the price the ranker charges CANNOT drift from the price the relaxation used.
    """

    def __init__(self, *args, **kwargs):
        entry = kwargs.pop("machine_entry", None)
        dict.__init__(self, *args, **kwargs)
        self.machine_entry = dict(entry or {})


def build_entry_cost(build_cost):
    """Entry cost for a machine you must build, ordered by what building it costs.

    Bounded into `[MACHINE_COST["buildable"], PRICED_CEILING)` and monotonic in `build_cost`;
    see the BUILD_SPREAD comment for why the bound is not optional.

    An UNREACHABLE machine item (inf, which 23 buildable categories on the reference pack
    have: a producer exists but its own inputs never price) charges UNPRICED_MACHINE_COST. Not
    `unavailable`: the state was decided by `machines._candidate_verdict` on evidence, and a
    price this model failed to compute is a gap in the pricing, not a fact about the base.
    Charging 5,000 here would override an evidence-based verdict with a numerical failure. Nor
    the top of the reserved region, which is #95: that is where a structure proven unbuildable
    goes, and this is only a number we could not work out.
    """
    floor = MACHINE_COST["buildable"]
    if build_cost is None or math.isinf(build_cost) or math.isnan(build_cost):
        return UNPRICED_MACHINE_COST
    b = max(0.0, build_cost)
    span = math.log1p(b / BUILD_SCALE)
    return floor + BUILD_SPREAD * (span / (span + BUILD_KNEE))


def blocked_entry_cost(fraction):
    """Entry cost for a multiblock the pack says needs a block nothing in the graph makes.

    `fraction` is `multiblocks.blocked_fraction`: the share of block POSITIONS with no
    obtainable candidate. Mapped linearly onto `[BLOCKED_FLOOR, BLOCKED_CEILING]`, so the whole
    slice stays above every priced machine and above an unpriced machine item, while the 117
    categories inside it stop being one number.

    LINEAR, not the logarithmic curve `build_entry_cost` uses, because this is not a cost. A
    fraction is already bounded and already uniform over its range; there is no long tail to
    compress and nothing to calibrate against, so a curve here would be decoration implying a
    precision the ordinal does not have.

    An out-of-range or missing fraction clamps rather than raises: this decides a ranking, and
    a machine vanishing from the band because its structure parsed oddly would be a worse
    failure than one ranked at the wrong end of a slice every member of which is unbuildable.
    """
    if fraction is None or math.isnan(fraction):
        return BLOCKED_CEILING
    f = min(1.0, max(0.0, fraction))
    return BLOCKED_FLOOR + (BLOCKED_CEILING - BLOCKED_FLOOR) * f


def machine_entry_costs(machine_items, cost, multiblocks=None):
    """{category: entry cost} for the categories whose machine has to be built.

    `machine_items` is `{category: (candidate key, ...)}` from `machines.build_targets`. The
    CHEAPEST candidate sets the price: several blocks can open one category (smelting is not
    only the furnace), and a player building one would build the cheapest that works, so
    pricing the first listed would charge for a machine nobody would choose.

    `multiblocks` is `graph.multiblocks`. A candidate that is a Modular Machinery controller
    is charged its recipe PLUS the structure it stands for, because the recipe alone is a
    blueprint and a blank controller while the machine is up to 8,813 placed blocks (#93).
    The two are added rather than one replacing the other: you need the controller AND the
    structure, and the controller is not among the machinery file's own parts.

    THREE OUTCOMES PER CANDIDATE, not one number that might be infinite (#95): a price, a
    structure the pack proves unbuildable, or a machine item this model could not price. They
    land in three ordered regions, and which one a candidate reaches is decided here because
    this is the only place that has both the structure and the item's price.
    """
    by_controller = {}
    for entry in (multiblocks or {}).values():
        by_controller[entry.get("controller")] = entry
    out = {}
    for category, keys in (machine_items or {}).items():
        # THE MINIMUM IS TAKEN OVER ENTRY COSTS, NOT OVER RAW BUILD COSTS, because since #95 two
        # candidates for one category can fail in different ways and a raw `inf` no longer says
        # which. For the all-priced case this is the same answer -- `build_entry_cost` is
        # monotonic, so the cheapest raw cost is still the cheapest entry cost -- and where they
        # differ it is the case the old form could not express at all.
        best = math.inf
        for key in keys:
            c = cost.get(key, math.inf)
            structure = by_controller.get(key)
            if math.isinf(c):
                priced = UNPRICED_MACHINE_COST
            elif structure is None:
                priced = build_entry_cost(c)
            else:
                placed = multiblocks_mod.structure_cost(structure, cost)
                priced = (blocked_entry_cost(
                              multiblocks_mod.blocked_fraction(structure, cost))
                          if math.isinf(placed) else build_entry_cost(c + placed))
            if priced < best:
                best = priced
        out[category] = best if best < math.inf else UNPRICED_MACHINE_COST
    return out


def category_entry_cost(category, machine_states=None, machine_entry=None):
    """What running a recipe in `category` costs before any ingredient is counted.

    ONE definition, read by both the relaxation in `estimate` and the ranking in
    `recipe_cost`. Do NOT inline the MACHINE_COST lookup into either of them again: they held
    separate copies, so a change to how a machine is priced silently applied to one and not
    the other, and the symptom is a solver that expands a route the ranker did not price.
    """
    if machine_entry and category in machine_entry:
        return machine_entry[category]
    state = (machine_states or {}).get(category)
    return (MACHINE_COST.get(state[0], UNGATED_MACHINE_COST) if state
            else UNGATED_MACHINE_COST)


def _scaled_qty(key, qty):
    """Quantity in normalised units: 1 item, or 1 bucket of fluid."""
    q = max(qty, 1) * (FLUID_SCALE if key.startswith("fluid:") else 1.0)
    # A sub-millibucket output would divide by ~0 and manufacture a free resource.
    return max(q, FLUID_SCALE)


def input_cost(cost, key, qty, ore_members):
    """Cheapest cost of satisfying one ingredient slot.

    Public because the solver needs it too: `Solver.slot_cost` breaks its alternative and
    oredict-member ties on exactly this number, and a second implementation over there
    would be a second place for the normalisation (oredict members, fluid scale) to drift.
    """
    if key.startswith("ore:"):
        members = ore_members.get(key[4:]) or ()
        best = math.inf
        for m in members:
            c = cost.get(m, math.inf)
            if c < best:
                best = c
        if math.isinf(best):
            best = cost.get(key, BASE_RAW_COST)
    else:
        best = cost.get(key, math.inf)
    if math.isinf(best):
        return math.inf
    return best * _scaled_qty(key, qty)


def _cheapest_alternative(cost, ingredient, ore_members):
    """Which of an input slot's alternatives the ranker assumes you would use.

    One rule, shared by `estimate` and `recipe_cost`, which used to hold two: `estimate`
    compared alternatives at qty 1 and `recipe_cost` at the slot's real qty. Those happen
    to agree, because `_scaled_qty` is linear in qty and the item/fluid ratio is therefore
    the same at any quantity -- but they agreed by arithmetic accident, not by design, and
    "the relaxation priced one alternative and the ranking priced another" is a bug nobody
    would find by reading either function alone. Priced at the real qty, which is the one
    that does not depend on that accident.
    """
    alts = ingredient.alternatives
    if len(alts) == 1:
        return alts[0]
    return min(alts, key=lambda a: input_cost(cost, a, ingredient.qty, ore_members))


def estimate(graph, have=None, machine_states=None, passes=PASSES, free_sources=None,
             machine_items=None):
    """{item key: estimated cost}. Lower is easier to get.

    With `machine_items` (`{category: (machine item key, ...)}` from
    `machines.build_targets`) this runs the relaxation TWICE, and the second run is issue
    #86's fix. A buildable machine's entry cost is what building it costs, which is itself a
    number this function computes, so it cannot be known before the first run.

    TWO PASSES RATHER THAN RECOMPUTING ENTRY COSTS INSIDE THE LOOP, deliberately. The
    relaxation only ever LOWERS a cost, so an entry price that rises between passes -- which
    is exactly what happens when a machine's real cost replaces the optimistic flat 40 --
    never propagates: the cheap prices computed in pass 1 stick, and the result silently
    depends on pass order. Seeding a second clean relaxation with entry costs derived from
    the first is deterministic and says what it does.

    Returns a `CostTable` carrying those entry costs, so `recipe_cost` charges the same
    machine price this relaxation used instead of re-deriving a flat one.
    """
    seed = _seed(graph, have, free_sources)
    cost = _relax(graph, dict(seed), passes, machine_states, None)
    if not machine_items:
        return CostTable(cost)
    entry = machine_entry_costs(machine_items, cost, getattr(graph, "multiblocks", None))
    return CostTable(_relax(graph, dict(seed), passes, machine_states, entry),
                     machine_entry=entry)


def _seed(graph, have, free_sources):
    """Starting costs, before any recipe is considered. Shared by both relaxation passes."""
    from .generators import SOURCE_COST

    cost = {}
    # Anything in stock is free at the margin; that is what makes the solver prefer using
    # what you already own without needing a separate rule for it.
    for key, qty in (have or {}).items():
        if qty:
            cost[key] = 0.0

    # An infinite generator's output is near-free but NOT free. At zero the ranker cannot
    # see quantity and will happily plan a swimming pool of water; at SOURCE_COST it still
    # beats every crafted route while preferring less over more. See generators.py.
    for key in free_sources or ():
        cost[key] = min(cost.get(key, math.inf), SOURCE_COST)

    # Seed every produced key as unreachable, and every leaf (no recipe) as cheap-ish.
    produced = graph.by_output
    for r in graph.recipes:
        for ing in r.inputs:
            for alt in ing.alternatives:
                if alt not in cost and alt not in produced:
                    cost[alt] = BASE_RAW_COST
    return cost


def _relax(graph, cost, passes, machine_states, machine_entry):
    """One Bellman-Ford style relaxation over every recipe, mutating and returning `cost`."""
    machine_cost = {}
    for r in graph.recipes:
        if r.category not in machine_cost:
            machine_cost[r.category] = category_entry_cost(
                r.category, machine_states, machine_entry)

    ore_members = graph.ore_members
    recipes = graph.recipes

    settled = max(1, int(len(recipes) * SETTLED_FRACTION))
    for _ in range(passes):
        changed = 0
        for r in recipes:
            base = machine_cost[r.category]
            if r.transfer:
                base += TRANSFER_PENALTY
            ingredients = 0.0
            for ing in r.inputs:
                c = input_cost(cost, _cheapest_alternative(cost, ing, ore_members),
                                ing.qty, ore_members)
                if math.isinf(c):
                    ingredients = math.inf
                    break
                ingredients += c
            if math.isinf(ingredients):
                continue
            for key, qty in r.outputs:
                # A container transfer never makes its fluid cheaper: emptying a can you
                # own is not production. Mirrors Graph.real_producers, which is what the
                # solver walks -- if these disagree the ranker prices a route the solver
                # cannot take.
                if r.transfer and key.startswith("fluid:"):
                    continue
                # ONLY THE INGREDIENTS AMORTISE. `base` is what running this recipe costs
                # you at all -- overwhelmingly the machine -- and dividing it by the batch
                # says a big enough output makes the machine free. It is not a small error:
                # the reference pack has a Hostile Computing Unit recipe yielding 1,024 iron
                # ingots and an Enchanted Greenhouse one yielding 60,466,176 fruit, so the
                # 5,000 wall MACHINE_COST puts in front of an unavailable machine collapsed
                # to 8e-5 and 126 items (diamond, coal, string, redstone) priced under 0.1.
                # That is how "one iron ingot" came out as "smelt a Spawner Shard", which
                # needs a Pristine Matter run, which needs bee drones. See issue #29.
                per_unit = base + ingredients / _scaled_qty(key, qty)
                if per_unit < cost.get(key, math.inf) - 1e-9:
                    cost[key] = per_unit
                    changed += 1
        if changed < settled:
            break
    return cost


def fingerprint(graph_path, have, machine_states, free_sources, machine_items=None,
                multiblocks=None):
    """Stable digest of everything `estimate` reads, for cache validation.

    Deliberately hashes the machine states and stock CONTENTS rather than the file mtimes:
    a manual override changes machine state without touching the graph, and mtimes move
    when a file is rewritten with identical contents. Also folds in the tuning constants,
    so editing MACHINE_COST invalidates the cache instead of silently reusing prices
    computed under the old table. FORMULA_VERSION covers the other half of that: a change
    to the arithmetic rather than to a constant, which moves no other input at all.
    """
    h = hashlib.sha256()
    try:
        stat = os.stat(graph_path)
        h.update(("%s|%d|%d" % (graph_path, stat.st_size, int(stat.st_mtime))).encode())
    except OSError:
        h.update(str(graph_path).encode())
    h.update(repr(sorted((MACHINE_COST.items()))).encode())
    # Every tuning constant `machine_entry_costs` and `_relax` read. The #95 slice boundaries
    # are derived from BUILD_SPREAD and MACHINE_COST rather than typed in, so hashing those two
    # would in fact cover them today -- they are listed anyway, because "the cache is correct
    # because of how a constant happens to be defined" is the kind of reasoning that goes stale
    # the moment someone gives one of them a literal value.
    h.update(("%r %r %r %r %r %r %r %r %r %r %r %r %r"
              % (UNGATED_MACHINE_COST, FLUID_SCALE, BASE_RAW_COST, TRANSFER_PENALTY, PASSES,
                 FORMULA_VERSION, BUILD_SPREAD, BUILD_SCALE, BUILD_KNEE, BUILD_SLOPE,
                 UNPRICED_MACHINE_COST, BLOCKED_FLOOR, BLOCKED_CEILING)).encode())
    for key, qty in sorted((have or {}).items()):
        h.update(("%s=%s;" % (key, qty)).encode())
    h.update(b"\x00")
    for uid, state in sorted((machine_states or {}).items()):
        h.update(("%s=%s;" % (uid, state[0])).encode())
    h.update(b"\x00")
    for key in sorted(free_sources or ()):
        h.update(("%s;" % key).encode())
    # The machine ITEMS, not just the states. A catalyst change can move which block a
    # category's machine is without moving the state, and the entry cost is derived from that
    # item's price -- so hashing states alone would serve prices for the old machine.
    h.update(b"\x00")
    for uid, keys in sorted((machine_items or {}).items()):
        h.update(("%s=%s;" % (uid, ",".join(keys))).encode())
    # The multiblock structures, in full. The graph file's size and mtime above already move
    # when a rebuild changes them, so this is belt and braces for the one case that skips the
    # file: a caller that supplies or edits `graph.multiblocks` in process, where every other
    # input is identical and a hit would serve prices computed for a different structure. That
    # is the shape of trap #86 hit, where patching a function moved no fingerprint input.
    h.update(b"\x00")
    h.update(json.dumps(multiblocks or {}, sort_keys=True).encode())
    return h.hexdigest()


def cache_beside(graph_path):
    """Where to memoise the cost table for `graph_path`: beside the GRAPH, not the cwd.

    DO NOT restore `DEFAULT_COST_CACHE` as the default for `cache_path`. That constant is the
    RELATIVE "data/.cost-cache.json", and a relative default plus a container is the #92 bug
    in a second place -- once here and once in `server.State`, the two entry points that
    memoise. The image's WORKDIR is /app while the graph is the mounted /data/graph.json, so
    both the server and a containerised `plan` memoised into /app/data/ inside the container
    layer and threw the table away on every recreation, paying the 26s relaxation again with a
    valid cache one bind-mount away.

    It also let the TEST SUITE write into the developer's real data/ dir. `tests/test_server`
    puts the graph, stock, machines, sources, tokens and pins in a tempdir, and the cost cache
    was the one path `State` gave it no way to redirect -- so `unittest discover` from the repo
    root replaced data/.cost-cache.json with four fixture prices, and on Tower, where the repo
    checkout's data/ IS the serving bind mount, a test run discarded the live server's warm
    table. Resolved HERE rather than in each caller so a new caller cannot reintroduce it by
    forgetting to pass a path; only the BASENAME comes from the constant, so the name of the
    file stays in one place.
    """
    return os.path.join(os.path.dirname(os.path.abspath(graph_path)),
                        os.path.basename(DEFAULT_COST_CACHE))


def estimate_cached(graph, graph_path, have=None, machine_states=None, free_sources=None,
                    cache_path=None, passes=PASSES, machine_items=None):
    """`estimate`, memoised on disk. Falls back to computing on any cache problem.

    `cache_path` defaults to `cache_beside(graph_path)`; see there for why it is not the
    relative constant. Pass one explicitly to override, which is what an A/B of a cost change
    must do -- the fingerprint covers the constants but NOT the code, so two arms sharing a
    cache serve arm one's table to arm two and agree by construction.

    Measured on the 117.7k-recipe reference graph: 15.6s for one relaxation, 26.1s for the
    two `estimate` runs when build targets are supplied (#86). Fine once at server startup
    and tedious on every `plan` invocation, which is what this is for. A stale cache would be
    far worse than a slow one, so validation is a content fingerprint and every failure path
    recomputes rather than guessing.

    The cached document carries the machine entry costs alongside the item costs. It has to:
    a hit that returned only the item prices would hand back a plain table, `recipe_cost`
    would fall back to the flat constants, and the ranking would disagree with the very
    relaxation the cache is serving -- a divergence visible only on a cache HIT, which is the
    hard way to find it.
    """
    cache_path = cache_path or cache_beside(graph_path)
    stamp = fingerprint(graph_path, have, machine_states, free_sources, machine_items,
                        getattr(graph, "multiblocks", None))
    if cache_path and os.path.exists(cache_path):
        try:
            with open(cache_path) as fh:
                doc = json.load(fh)
            if doc.get("fingerprint") == stamp and isinstance(doc.get("cost"), dict):
                entry = doc.get("machine_entry")
                return CostTable(
                    {k: (math.inf if v is None else v) for k, v in doc["cost"].items()},
                    machine_entry=entry if isinstance(entry, dict) else None)
        except (ValueError, OSError):
            pass

    cost = estimate(graph, have=have, machine_states=machine_states, passes=passes,
                    free_sources=free_sources, machine_items=machine_items)
    if cache_path:
        try:
            os.makedirs(os.path.dirname(cache_path) or ".", exist_ok=True)
            tmp = cache_path + ".tmp"
            with open(tmp, "w") as fh:
                json.dump({"fingerprint": stamp,
                           "cost": {k: (None if math.isinf(v) else v)
                                    for k, v in cost.items()},
                           "machine_entry": getattr(cost, "machine_entry", None) or {}}, fh)
            os.replace(tmp, cache_path)   # atomic, so a killed run cannot leave a torn file
        except OSError:
            pass
    return cost


def recipe_cost(cost, recipe, ore_members, machine_states=None, pick=None):
    """Estimated cost of running one recipe once, given precomputed item costs.

    `pick(ingredient) -> alternative` names, per slot, the option whoever is asking will
    ACTUALLY use; it defaults to the cheapest. The solver passes its own
    `Solver.pick_alternative`, and it has to: pricing a slot at its cheapest option and
    then expanding a different one is how "1 Iron Ingot" became "cast 1,296 mB of molten
    iron". That recipe's slot accepts a Block of Iron or a decorative Chisel block, the
    Chisel block is a raw leaf costing BASE_RAW_COST, so the recipe priced at 2.0 and beat
    smelting an ore -- and then the solver expanded the Block of Iron instead, because it
    is the one with a recipe. Nothing was mispriced; the price was simply for a route
    nobody took. See issue #29.
    """
    # Off the table when it is a `CostTable`, so the machine price charged here is the one
    # the relaxation actually used (#86). A plain dict still works and still gets the flat
    # constants, which is what every caller passing a hand-built table wants.
    total = category_entry_cost(recipe.category, machine_states,
                                getattr(cost, "machine_entry", None))
    if recipe.transfer:
        total += TRANSFER_PENALTY
    for ing in recipe.inputs:
        alt = pick(ing) if pick else _cheapest_alternative(cost, ing, ore_members)
        best = input_cost(cost, alt, ing.qty, ore_members)
        if math.isinf(best):
            return math.inf
        total += best
    return total
