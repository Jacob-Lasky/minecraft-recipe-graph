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

THE ESTIMATE IS A LOWER BOUND, NOT A PLAN. It ignores cycles, assumes inputs can be had
independently, and never double-counts shared intermediates. It exists to rank recipes,
not to be reported as a real cost. DO NOT surface these numbers to users as quantities.

Costs are relaxed iteratively (Bellman-Ford style) rather than solved exactly: the graph
has cycles and AND-nodes (a recipe needs *all* its inputs), so there is no simple
shortest-path formulation. A handful of passes converges well enough to rank by.
"""

import json
import math
import os

# What a machine costs to route through. Owning it is nearly free; building one is a real
# but one-off expense; using one you cannot get should lose to almost anything.
#
# `unknown` sits between buildable and unavailable ON PURPOSE. It means the category's
# machine could not be identified, which is a gap in this tool, not a fact about the
# player's base, so it must not be priced as unusable -- doing that put 40% of the
# reference pack's recipes behind a 5,000 wall. It must also not undercut `buildable`,
# or an unidentified machine would beat one the player can demonstrably build.
MACHINE_COST = {"have": 1.0, "buildable": 40.0, "unknown": 120.0, "unavailable": 5000.0}

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

# Bellman-Ford needs one pass per edge in the longest useful path. MeatballCraft's chemistry
# runs 10+ hops deep (borax -> ... -> molten sugar), so 6 passes left the deep end of every
# chain unpriced. Measured on the reference pack: the last item gets a price at pass 12 and
# nothing new appears after 20, while relaxation over cycles keeps making sub-percent
# improvements forever -- hence a ceiling AND an early exit, not just one of them.
PASSES = 20
SETTLED_FRACTION = 0.002   # stop when a pass improves under 0.2% of recipes' outputs


def _scaled_qty(key, qty):
    """Quantity in normalised units: 1 item, or 1 bucket of fluid."""
    q = max(qty, 1) * (FLUID_SCALE if key.startswith("fluid:") else 1.0)
    # A sub-millibucket output would divide by ~0 and manufacture a free resource.
    return max(q, FLUID_SCALE)


def _input_cost(cost, key, qty, ore_members):
    """Cheapest cost of satisfying one ingredient slot."""
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


def estimate(graph, have=None, machine_states=None, passes=PASSES, free_sources=None):
    """{item key: estimated cost}. Lower is easier to get."""
    from .generators import SOURCE_COST

    have = have or {}
    machine_states = machine_states or {}

    cost = {}
    # Anything in stock is free at the margin; that is what makes the solver prefer using
    # what you already own without needing a separate rule for it.
    for key, qty in have.items():
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

    machine_cost = {}
    for r in graph.recipes:
        if r.category not in machine_cost:
            state = machine_states.get(r.category)
            machine_cost[r.category] = (
                MACHINE_COST.get(state[0], UNGATED_MACHINE_COST) if state
                else UNGATED_MACHINE_COST)

    ore_members = graph.ore_members
    recipes = graph.recipes

    settled = max(1, int(len(recipes) * SETTLED_FRACTION))
    for _ in range(passes):
        changed = 0
        for r in recipes:
            base = machine_cost[r.category]
            if r.transfer:
                base += TRANSFER_PENALTY
            total = base
            for ing in r.inputs:
                c = _input_cost(cost, ing.alternatives[0] if len(ing.alternatives) == 1
                                else min(ing.alternatives,
                                         key=lambda a: _input_cost(cost, a, 1, ore_members)),
                                ing.qty, ore_members)
                if math.isinf(c):
                    total = math.inf
                    break
                total += c
            if math.isinf(total):
                continue
            for key, qty in r.outputs:
                # A container transfer never makes its fluid cheaper: emptying a can you
                # own is not production. Mirrors Graph.real_producers, which is what the
                # solver walks -- if these disagree the ranker prices a route the solver
                # cannot take.
                if r.transfer and key.startswith("fluid:"):
                    continue
                per_unit = total / _scaled_qty(key, qty)
                if per_unit < cost.get(key, math.inf) - 1e-9:
                    cost[key] = per_unit
                    changed += 1
        if changed < settled:
            break
    return cost


def fingerprint(graph_path, have, machine_states, free_sources):
    """Stable digest of everything `estimate` reads, for cache validation.

    Deliberately hashes the machine states and stock CONTENTS rather than the file mtimes:
    a manual override changes machine state without touching the graph, and mtimes move
    when a file is rewritten with identical contents. Also folds in the tuning constants,
    so editing MACHINE_COST invalidates the cache instead of silently reusing prices
    computed under the old table.
    """
    import hashlib

    h = hashlib.sha256()
    try:
        stat = os.stat(graph_path)
        h.update(("%s|%d|%d" % (graph_path, stat.st_size, int(stat.st_mtime))).encode())
    except OSError:
        h.update(str(graph_path).encode())
    h.update(repr(sorted((MACHINE_COST.items()))).encode())
    h.update(("%r %r %r %r %r" % (UNGATED_MACHINE_COST, FLUID_SCALE, BASE_RAW_COST,
                                  TRANSFER_PENALTY, PASSES)).encode())
    for key, qty in sorted((have or {}).items()):
        h.update(("%s=%s;" % (key, qty)).encode())
    h.update(b"\x00")
    for uid, state in sorted((machine_states or {}).items()):
        h.update(("%s=%s;" % (uid, state[0])).encode())
    h.update(b"\x00")
    for key in sorted(free_sources or ()):
        h.update(("%s;" % key).encode())
    return h.hexdigest()


def estimate_cached(graph, graph_path, have=None, machine_states=None, free_sources=None,
                    cache_path="data/.cost-cache.json", passes=PASSES):
    """`estimate`, memoised on disk. Falls back to computing on any cache problem.

    The relaxation is ~8s on a 121k-recipe graph, which is fine once at server startup and
    tedious on every `plan` invocation. A stale cache would be far worse than a slow one,
    so validation is a content fingerprint and every failure path recomputes rather than
    guessing.
    """
    stamp = fingerprint(graph_path, have, machine_states, free_sources)
    if cache_path and os.path.exists(cache_path):
        try:
            with open(cache_path) as fh:
                doc = json.load(fh)
            if doc.get("fingerprint") == stamp and isinstance(doc.get("cost"), dict):
                return {k: (math.inf if v is None else v)
                        for k, v in doc["cost"].items()}
        except (ValueError, OSError):
            pass

    cost = estimate(graph, have=have, machine_states=machine_states, passes=passes,
                    free_sources=free_sources)
    if cache_path:
        try:
            os.makedirs(os.path.dirname(cache_path) or ".", exist_ok=True)
            tmp = cache_path + ".tmp"
            with open(tmp, "w") as fh:
                json.dump({"fingerprint": stamp,
                           "cost": {k: (None if math.isinf(v) else v)
                                    for k, v in cost.items()}}, fh)
            os.replace(tmp, cache_path)   # atomic, so a killed run cannot leave a torn file
        except OSError:
            pass
    return cost


def recipe_cost(cost, recipe, ore_members, machine_states=None):
    """Estimated cost of running one recipe once, given precomputed item costs."""
    machine_states = machine_states or {}
    state = machine_states.get(recipe.category)
    total = (MACHINE_COST.get(state[0], UNGATED_MACHINE_COST) if state
             else UNGATED_MACHINE_COST)
    if recipe.transfer:
        total += TRANSFER_PENALTY
    for ing in recipe.inputs:
        best = math.inf
        for alt in ing.alternatives:
            c = _input_cost(cost, alt, ing.qty, ore_members)
            if c < best:
                best = c
        if math.isinf(best):
            return math.inf
        total += best
    return total
