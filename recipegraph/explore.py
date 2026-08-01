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

# When several items share a display name, how much of one you must hold before stock is
# allowed to outrank how connected the item is. One stack.
#
# A THRESHOLD RATHER THAN `stock > 0`, and the difference is measured. On the reference pack
# 5,095 display names are shared by two or more plain item keys, covering 21,888 keys -- six
# things called "Iron Plate", 286 called "Spell Book" -- so this tie-break runs constantly and
# being trigger-happy about stock is expensive. Ranking any held item first disagrees with
# ranking by consumer count on 85 of those clusters; requiring a stack cuts it to 26, and the
# disagreements it removes are the ones stock got wrong:
#
#   * Abyssal Stone -- ONE `railcraft:abyssal_stone` in the network beat `aoa3:abyss_stone`,
#     which 165 recipes consume. One of something is a thing you picked up.
#   * Sulfur -- 1,482 `thermalfoundation:material:771` is the pack telling you which sulfur you
#     actually use, and it must beat a Betweenlands item with more consumers and none held.
#
# One boolean, not a curve: past a stack, more of it says nothing further about which item was
# meant, and a magnitude term would let a chest of some vestigial duplicate win outright.
STOCK_IS_DECISIVE = 64

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
        # Checked AFTER the term match, not before: `stock_of` splits the key and may scan
        # the pool, and paying that for all 262,841 labels on every keystroke would cost
        # far more than the filter saves. Only matches reach here.
        #
        # Anything you actually hold stays findable however dead the graph thinks it is.
        # A stack in an AE2 system is a fact about the world; "no recipe touches it" is a
        # fact about the dump, and the dump does not get to overrule the world.
        if key not in live and not stock_of(key, have):
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
    # stacks like `thaumadditions:vis_pod#03c878f080d5`, plus fluids and essentia.
    # Without this, a stack whose digest the dump never saw is unsearchable even though
    # you are holding it.
    for key in have:
        if key in graph.labels:
            continue
        label = graph.display(key)
        low = label.lower()
        if not all(t in low or t in key.lower() for t in terms):
            continue
        scored.append((2 if low.startswith(q) else 3, len(label), label, key))

    scored.sort()
    # Deduplicated BEFORE the tie-break, not after, so a row's index is its output position
    # and `_break_name_ties` can use `limit` to decide what cannot matter. The whole list is
    # deduplicated rather than stopping at `limit`, because a tie group straddling the cut can
    # still reorder across it.
    seen, uniq = set(), []
    for row in scored:
        if row[3] in seen:
            continue
        seen.add(row[3])
        uniq.append(row)
    _break_name_ties(graph, uniq, have, limit)
    return Matches([row[3] for row in uniq[:limit]], hidden)


def _canonical_first(graph, key, have):
    """Sort key putting the item the PACK actually uses ahead of its vestigial duplicates.

    Negated because the caller sorts ascending and every signal here is better when larger.

    Only reached for keys whose label is character-identical to another hit's, so the cost is
    paid per duplicate cluster rather than per keystroke -- `consumers` walks the oredict
    groups and would be far too much to run over all 262,841 labels while someone types.
    """
    return (-(stock_of(key, have) >= STOCK_IS_DECISIVE),
            -len(graph.consumers(key)),
            -len(graph.real_producers(key)),
            key)


def _break_name_ties(graph, scored, have, limit):
    """Reorder, in place, runs of hits whose `(rank, length, label)` are identical.

    WHY THIS IS NOT JUST A LONGER SORT KEY. `scored` holds every match, and for a one-letter
    query that is most of the graph; computing consumer and producer counts for all of them on
    every keystroke is the cost `rank_matches` is split out from `search` to avoid. Ties are
    exactly the duplicate-name clusters, the largest of which is 286, so refining only those
    keeps the work proportional to the ambiguity rather than to the result set.

    STOPS AT `limit`, WHICH IS THE DIFFERENCE BETWEEN THIS BEING FREE AND DOUBLING THE COST OF
    A KEYSTROKE. Measured on the reference graph before this cutoff existed, the query "a" went
    from 397ms to 767ms: 60 rows are returned and every duplicate cluster in a 200,000-row
    match list was being refined to produce them. `scored` is sorted, so a group starting at or
    after `limit` permutes only within itself and can never reach the output. A group that
    STRADDLES the cut is refined in full, because its members can still cross it.

    Sorting the whole list by the long key would ALSO be wrong, not merely slow: it would let
    a well-connected item outrank a better NAME match, and name relevance is the thing the
    first three components exist to express.

    The tie used to fall through to the registry id, so ordering among same-named items was
    decided by its first letter. `thermalfoundation:material:32` -- 42 held, 152 recipes
    consuming it, eight ways to make it including five machines -- came last of the six items
    called "Iron Plate", behind `abyssalcraft:ironp`, which only crafting makes. The report was
    "the only way I can find to craft an iron plate is shaped crafting".
    """
    start = 0
    for i in range(1, len(scored) + 1):
        if start >= limit:
            return
        if i < len(scored) and scored[i][:3] == scored[start][:3]:
            continue
        if i - start > 1:
            head = scored[start][:3]
            keys = sorted((row[3] for row in scored[start:i]),
                          key=lambda k: _canonical_first(graph, k, have))
            scored[start:i] = [head + (k,) for k in keys]
        start = i


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
        row = stack(graph, key, have)
        row["makes"] = len(graph.real_producers(key))
        row["uses"] = len(graph.consumers(key))
        out.append(row)
    return Matches(out, hits.hidden)


def search(graph, query, have=None, limit=MAX_RESULTS):
    """Full detail for every item matching `query`."""
    have = have or {}
    hits = rank_matches(graph, query, have, limit)
    return Matches([describe(graph, key, have) for key in hits.results], hits.hidden)


def identity(graph, key):
    """The four fields that say WHICH thing a row is about.

    One definition, because every payload in this module and in `api` carries them and they
    were written out five times: a renderer reading `label` off a row built by the fifth copy
    that still spelled it something else fails at display time, on that row only.
    """
    return {"key": key, "name": graph.display(key), "kind": graph.kind(key),
            "label": graph.bare_name(key)}


def stack(graph, key, have):
    """`identity` plus how much of it you hold. What a row about an item carries."""
    row = identity(graph, key)
    row["stock"] = stock_of(key, have)
    return row


def output_rows(graph, outputs):
    """`identity` plus a quantity, for a recipe's `[(key, qty)]` output list."""
    rows = []
    for key, qty in outputs:
        row = identity(graph, key)
        row["qty"] = qty
        rows.append(row)
    return rows


def _stack(graph, key, have):
    row = stack(graph, key, have)
    # Whether, not how many: this feeds a badge on the explore page. `api` wants the count
    # and adds its own, which is why the shared part stops at `stack`.
    row["makeable"] = bool(graph.producers(key))
    return row


def stock_of(key, have):
    """How much of `key` the network holds, following a wildcard-meta key to its variants.

    Public because `api.FIELDS` needs the same answer the search rows show: a sweep that
    counted stock its own way would disagree with the page describing the same key.
    """
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
    # Outputs unconditionally: the two branches built the same list, so the "used" branch was
    # a second copy of it kept in step by hand.
    d["outputs"] = output_rows(graph, recipe.outputs)
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
    return d


def describe(graph, key, have=None):
    """Full picture of one item: stock, how to make it, what it feeds."""
    have = have or {}
    producers = graph.producers(key)
    consumers = graph.consumers(key)

    ores = sorted(graph.ores_of(key))

    return dict(stack(graph, key, have), **{
        "oredicts": ores,
        "oredict_guessed": [o for o in ores if o in graph.ore_guessed],
        "makes": [_recipe_brief(graph, r, have, "makes") for r in producers[:MAX_PRODUCERS]],
        "makes_total": len(producers),
        "used_in": [_recipe_brief(graph, r, have, "used") for r in consumers[:MAX_CONSUMERS]],
        "used_in_total": len(consumers),
    })


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
