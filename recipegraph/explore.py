"""Search the graph: what is this item, how is it made, and what is it used in.

The planner answers "what do I need for X". This answers the browsing question that
comes first -- "which of the twelve things called Ultimate <something> did I mean,
do I have any, and is it even craftable in this pack".

Result sets are capped per item (see MAX_*) because a common intermediate like an
iron ingot is consumed by thousands of recipes; rendering all of them would produce
a useless page. Caps are reported in the output rather than silently applied, and so is
the dead-key filter below -- a search that quietly drops half the graph is worse than one
that admits to it.
"""

import collections

from .model import merge_slots, split_key
from .names import build_reverse

MAX_RESULTS = 60
MAX_PRODUCERS = 8
MAX_CONSUMERS = 12

# What every entry point returns: the rows, and how many matches were suppressed as dead.
# A plain list was the old shape and it had nowhere to put the count, which is how the
# suppression would have become silent. Named fields so `hits.results` reads at the call
# site and a stale `for x in hits` cannot quietly iterate a two-tuple.
Matches = collections.namedtuple("Matches", "results hidden")


def rank_matches(graph, query, have=None, limit=MAX_RESULTS):
    """Item keys matching `query`, best first, plus the count of dead matches dropped.

    Split out from `search` so search-as-you-type does not pay for the full `describe` of
    every hit: `describe` walks producers and consumers per item, which is the right cost
    for a page and far too much for a keystroke.

    Renamed from `rank_keys` when the return type changed from a list to `Matches`. A
    rename rather than a quiet signature change ON PURPOSE: a call site left un-updated now
    fails at import instead of iterating a two-tuple and rendering the word "results".
    """
    have = have or {}
    q = query.strip().lower()
    if not q:
        return Matches([], 0)
    terms = q.split()
    scored = []
    hidden = 0
    live = graph.live_keys

    # graph.labels, not graph.names: names covers items only, and a fluid the chemistry
    # chains need must be findable by name too.
    for key, label in graph.labels.items():
        low = label.lower()
        if not all(t in low or t in key.lower() for t in terms):
            continue
        # Checked AFTER the term match, not before: `_stock_of` splits the key and may scan
        # the pool, and paying that for all 342,070 labels on every keystroke would cost
        # far more than the filter saves. Only matches reach here.
        #
        # Anything you actually hold stays findable however dead the graph thinks it is.
        # A stack in an AE2 system is a fact about the world; "no recipe touches it" is a
        # fact about the dump, and the dump does not get to overrule the world.
        if key not in live and not _stock_of(key, have):
            hidden += 1
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
    return Matches(out, hidden)


def suggest(graph, query, have=None, limit=25):
    """Cheap rows for a typeahead: name, type, stock, and how connected the item is.

    `makes` and `uses` are the counts Jake asked to see in place -- where an item sits in
    the graph is often the whole answer ("no recipe" or "used by 400 things" tells you what
    kind of thing you are looking at without opening anything).
    """
    have = have or {}
    hits = rank_matches(graph, query, have, limit)
    out = []
    for key in hits.results:
        out.append({
            "key": key,
            "name": graph.display(key),
            "kind": graph.kind(key),
            "label": graph.bare_name(key),
            "stock": _stock_of(key, have),
            "makes": len(graph.real_producers(key)),
            "uses": len(graph.consumers(key)),
        })
    return Matches(out, hits.hidden)


def search(graph, query, have=None, limit=MAX_RESULTS):
    """Full detail for every item matching `query`."""
    have = have or {}
    hits = rank_matches(graph, query, have, limit)
    return Matches([describe(graph, key, have) for key in hits.results], hits.hidden)


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


def name_hints(graph, query, limit=10):
    """Cheap name-only suggestions, for a CLI 'did you mean' line.

    Distinct from `suggest`, which returns rows for the web typeahead. The two had the same
    name once and the later definition silently shadowed the earlier.

    Filtered to live keys and to labels that are not the query itself, because this line
    only ever appears when the search found nothing. Unfiltered it answered "no item name
    matched 'pluton scythe' / did you mean: pluton scythe", which reads as a malfunction:
    the name does exist, every key wearing it is dead, and `hidden_note` has already said
    so on the line above.
    """
    rev = build_reverse(graph.names)
    q = query.strip().lower()
    live = graph.live_keys
    hits = [label for label in rev
            if q in label and label != q and any(k in live for k in rev[label])]
    hits.sort(key=len)
    return hits[:limit]
