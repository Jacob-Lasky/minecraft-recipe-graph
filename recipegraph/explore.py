"""Search the graph: what is this item, how is it made, and what is it used in.

The planner answers "what do I need for X". This answers the browsing question that
comes first -- "which of the twelve things called Ultimate <something> did I mean,
do I have any, and is it even craftable in this pack".

Result sets are capped per item (see MAX_*) because a common intermediate like an
iron ingot is consumed by thousands of recipes; rendering all of them would produce
a useless page. Caps are reported in the output rather than silently applied.
"""

from .model import split_key
from .names import build_reverse

MAX_RESULTS = 60
MAX_PRODUCERS = 8
MAX_CONSUMERS = 12


def search(graph, query, have=None, limit=MAX_RESULTS):
    """Rank items by how well their display name matches `query`."""
    have = have or {}
    q = query.strip().lower()
    if not q:
        return []
    terms = q.split()
    scored = []

    for key, label in graph.names.items():
        low = label.lower()
        if not all(t in low or t in key.lower() for t in terms):
            continue
        # exact > prefix > word-boundary > substring; shorter names win ties
        if low == q:
            rank = 0
        elif low.startswith(q):
            rank = 1
        elif (" " + q) in low:
            rank = 2
        elif q in low:
            rank = 3
        else:
            rank = 4
        scored.append((rank, len(label), label, key))

    # Keys that exist only in the inventory, never in items.csv: NBT-discriminated
    # stacks like `thaumadditions:vis_pod#perditio`, plus fluids and essentia. Without
    # this, everything the aspect decoder produces is unsearchable.
    for key in have:
        if key in graph.names:
            continue
        label = graph.display(key)
        low = label.lower()
        if not all(t in low or t in key.lower() for t in terms):
            continue
        scored.append((2 if low.startswith(q) else 3, len(label), label, key))

    scored.sort()
    seen_keys = set()
    out = []
    for _rank, _len, _label, key in scored:
        if key in seen_keys:
            continue
        seen_keys.add(key)
        out.append(describe(graph, key, have))
        if len(out) >= limit:
            break
    return out


def _stack(graph, key, have):
    return {
        "key": key,
        "name": graph.display(key),
        "stock": _stock_of(key, have),
        "makeable": bool(graph.producers(key)),
    }


def _stock_of(key, have):
    n = have.get(key, 0)
    base, meta = split_key(key)
    if meta == "*":
        for k, v in have.items():
            if split_key(k)[0] == base:
                n += v
    return n


def _recipe_brief(graph, recipe, have, direction):
    d = {
        "id": recipe.rid,
        "category": recipe.category,
        "machine": recipe.machine,
        "source": recipe.source,
    }
    if direction == "makes":
        d["inputs"] = [
            {"qty": ing.qty, "role": ing.role,
             "alts": [_stack(graph, a, have) for a in ing.alternatives[:4]],
             "alt_total": len(ing.alternatives)}
            for ing in recipe.inputs
        ]
        d["outputs"] = [{"key": k, "name": graph.display(k), "qty": q}
                        for k, q in recipe.outputs]
    else:
        d["outputs"] = [{"key": k, "name": graph.display(k), "qty": q}
                        for k, q in recipe.outputs]
        d["input_count"] = len(recipe.inputs)
    return d


def describe(graph, key, have=None):
    """Full picture of one item: stock, how to make it, what it feeds."""
    have = have or {}
    producers = graph.producers(key)
    consumers = graph.consumers(key)

    ores = sorted(ore for ore, members in graph.ore_members.items() if key in members)

    return {
        "key": key,
        "name": graph.display(key),
        "stock": _stock_of(key, have),
        "oredicts": ores,
        "oredict_guessed": [o for o in ores if o in graph.ore_guessed],
        "makes": [_recipe_brief(graph, r, have, "makes") for r in producers[:MAX_PRODUCERS]],
        "makes_total": len(producers),
        "used_in": [_recipe_brief(graph, r, have, "used") for r in consumers[:MAX_CONSUMERS]],
        "used_in_total": len(consumers),
    }


def resolve_one(graph, query):
    """Best single key for a query, or None."""
    hits = search(graph, query, limit=1)
    return hits[0]["key"] if hits else None


def suggest(graph, query, limit=10):
    """Cheap name-only suggestions, for a 'did you mean' line."""
    rev = build_reverse(graph.names)
    q = query.strip().lower()
    hits = [label for label in rev if q in label]
    hits.sort(key=len)
    return hits[:limit]
