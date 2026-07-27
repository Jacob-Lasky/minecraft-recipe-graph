"""Search the graph: what is this item, how is it made, and what is it used in.

The planner answers "what do I need for X". This answers the browsing question that
comes first -- "which of the twelve things called Ultimate <something> did I mean,
do I have any, and is it even craftable in this pack".

Result sets are capped per item (see MAX_*) because a common intermediate like an
iron ingot is consumed by thousands of recipes; rendering all of them would produce
a useless page. Caps are reported in the output rather than silently applied.
"""

from .model import merge_slots, split_key
from .names import build_reverse

MAX_RESULTS = 60
MAX_PRODUCERS = 8
MAX_CONSUMERS = 12


def rank_keys(graph, query, have=None, limit=MAX_RESULTS):
    """Item keys matching `query`, best first. The shared ranking for search and suggest.

    Split out from `search` so search-as-you-type does not pay for the full `describe` of
    every hit: `describe` walks producers and consumers per item, which is the right cost
    for a page and far too much for a keystroke.
    """
    have = have or {}
    q = query.strip().lower()
    if not q:
        return []
    terms = q.split()
    scored = []

    # graph.labels, not graph.names: names covers items only, and a fluid the chemistry
    # chains need must be findable by name too.
    for key, label in graph.labels.items():
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
        if key in graph.labels:
            continue
        label = graph.display(key)
        low = label.lower()
        if not all(t in low or t in key.lower() for t in terms):
            continue
        scored.append((2 if low.startswith(q) else 3, len(label), label, key))

    scored.sort()
    seen, out = set(), []
    for _rank, _len, _label, key in scored:
        if key in seen:
            continue
        seen.add(key)
        out.append(key)
        if len(out) >= limit:
            break
    return out


def suggest(graph, query, have=None, limit=25):
    """Cheap rows for a typeahead: name, type, stock, and how connected the item is.

    `makes` and `uses` are the counts Jake asked to see in place -- where an item sits in
    the graph is often the whole answer ("no recipe" or "used by 400 things" tells you what
    kind of thing you are looking at without opening anything).
    """
    have = have or {}
    out = []
    for key in rank_keys(graph, query, have, limit):
        out.append({
            "key": key,
            "name": graph.display(key),
            "kind": graph.kind(key),
            "label": graph.bare_name(key),
            "stock": _stock_of(key, have),
            "makes": len(graph.real_producers(key)),
            "uses": len(graph.consumers(key)),
        })
    return out


def search(graph, query, have=None, limit=MAX_RESULTS):
    """Full detail for every item matching `query`."""
    have = have or {}
    return [describe(graph, key, have)
            for key in rank_keys(graph, query, have, limit)]


def _stack(graph, key, have):
    return {
        "key": key,
        "name": graph.display(key),
        "kind": graph.kind(key),
        "label": graph.bare_name(key),
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
        # Merged the same way the plan tree merges, for the same reason: a 3x3 of one
        # ingredient listed nine rows of "1x Tiny Clump" here too. Merged on the
        # alternatives AS AUTHORED -- this view describes the recipe and has no inventory
        # to pick a slot's alternative with. See model.merge_slots and #24.
        d["inputs"] = [
            {"qty": qty, "role": ing.role,
             "alts": [_stack(graph, a, have) for a in ing.alternatives[:4]],
             "alt_total": options}
            for _key, ing, qty, options in merge_slots(
                recipe.inputs, lambda i: tuple(i.alternatives))
        ]
        d["outputs"] = [{"key": k, "name": graph.display(k), "kind": graph.kind(k),
                        "label": graph.bare_name(k), "qty": q}
                        for k, q in recipe.outputs]
    else:
        d["outputs"] = [{"key": k, "name": graph.display(k), "kind": graph.kind(k),
                        "label": graph.bare_name(k), "qty": q}
                        for k, q in recipe.outputs]
    return d


def describe(graph, key, have=None):
    """Full picture of one item: stock, how to make it, what it feeds."""
    have = have or {}
    producers = graph.producers(key)
    consumers = graph.consumers(key)

    ores = sorted(graph.ores_of(key))

    return {
        "key": key,
        "name": graph.display(key),
        "kind": graph.kind(key),
        "label": graph.bare_name(key),
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


def name_hints(graph, query, limit=10):
    """Cheap name-only suggestions, for a CLI 'did you mean' line.

    Distinct from `suggest`, which returns rows for the web typeahead. The two had the same
    name once and the later definition silently shadowed the earlier.
    """
    rev = build_reverse(graph.names)
    q = query.strip().lower()
    hits = [label for label in rev if q in label]
    hits.sort(key=len)
    return hits[:limit]
