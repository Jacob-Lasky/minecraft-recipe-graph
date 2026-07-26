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

import math

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

# A fluid input's quantity is in mB, so 1000 mB of water would otherwise look a thousand
# times dearer than one item. Normalise a bucket to roughly one item.
FLUID_SCALE = 1.0 / 1000.0

BASE_RAW_COST = 1.0        # an item with no recipe: assume it can be obtained somehow
TRANSFER_PENALTY = 500.0   # container fill/empty is not production; never prefer it
PASSES = 6


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
    scale = FLUID_SCALE if key.startswith("fluid:") else 1.0
    return best * max(qty, 1) * scale


def estimate(graph, have=None, machine_states=None, passes=PASSES):
    """{item key: estimated cost}. Lower is easier to get."""
    have = have or {}
    machine_states = machine_states or {}

    cost = {}
    # Anything in stock is free at the margin; that is what makes the solver prefer using
    # what you already own without needing a separate rule for it.
    for key, qty in have.items():
        if qty:
            cost[key] = 0.0

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
                per_unit = total / max(qty, 1)
                if per_unit < cost.get(key, math.inf) - 1e-9:
                    cost[key] = per_unit
                    changed += 1
        if not changed:
            break
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
