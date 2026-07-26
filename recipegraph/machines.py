"""Which machines you actually have, and what that means for recipe choice.

The problem this solves: on a 340k-recipe graph almost every item has dozens of routes,
most through machines the player has never built. Without this, the solver happily plans
a chain through a Recursive Processor you do not own, which is how "64 Borax" ended up
routed via Chaos Fragments.

Every recipe carries its JEI category, and a category IS a machine. So machine
availability is a constraint on categories, which turns recipe choice from a guess into
a filter.

FOUR STATES, not two. "Don't own it", "can't use it", and "couldn't tell" are three
different answers and collapsing any pair of them produces wrong plans:
  * have        -- the machine is placed in the world, or its item is in your stock
  * buildable   -- not present, but the machine item itself is craftable
  * unknown     -- the category's machine could not be identified at all
  * unavailable -- identified, and there is no route to it; or you disabled it by hand
A plan may legitimately route through a `buildable` machine; it just has to TELL you to
build it first. That is the "I only have a crafting table, so I will make a furnace"
case: the furnace is buildable, an alloy smelter is not.

`unknown` exists because folding it into `unavailable` was catastrophic: on the reference
pack 360 of 521 categories -- 48,814 of 121,186 recipes, 40% of the graph -- could not be
name-matched to a machine item, and every one of them was priced as unusable. Title
matching is a heuristic ("Casting" is a recipe type, the machine is a Casting Table), so
it will always miss some. An unidentified machine must cost more than one you can
demonstrably build and far less than one proven out of reach. Catalysts from the dump mod
are the real fix; this state is what keeps the failure honest until they arrive.

EVIDENCE, in order of directness:
  1. tile entities in the world save -- you built it, it is there. Direct.
  2. the machine item in your AE2 network -- you have one, unplaced.
  3. JEI recipe catalysts (`catalysts.json` from the dump mod) -- the exact
     category -> machine-item mapping, straight from what JEI shows as "made in".
Quest completion is deliberately NOT the primary signal: it is a proxy for progression,
whereas a placed block is proof. Quests are useful for gating what is *unlocked*, which
is a different question from what is *built*.
"""

import json
import os
import re

from .names import clean_label

HAVE = "have"
BUILDABLE = "buildable"
UNKNOWN = "unknown"
UNAVAILABLE = "unavailable"

STATES = (HAVE, BUILDABLE, UNKNOWN, UNAVAILABLE)

# Categories that need no machine at all. Never gate these; the player always has hands.
ALWAYS_AVAILABLE = {
    "minecraft.crafting", "minecraft.crafting.shaped", "minecraft.crafting.shapeless",
    "minecraft.anvil", "minecraft.brewing", "minecraft.fuel", "minecraft.smelting",
    "jei.information", "jei.description",
}

# The two sources name hand-crafting differently: the JEI dump says `minecraft.crafting`,
# the offline jar reader says `crafting_shaped` / `crafting_shapeless`. Both are a crafting
# table. Matching only one of them left 10,301 offline recipes gated behind a machine the
# player was told they did not have. DO NOT test either prefix directly; go through
# `is_hand_crafting` so the two conventions can never drift apart again.
_HAND_CRAFTING_PREFIXES = ("minecraft.crafting", "crafting_shaped", "crafting_shapeless")

_SPLIT = re.compile(r"[^a-z0-9]+")
# Category uids are frequently camelCase (`TechReborn.WireMill`, `botania.runicAltar`)
# while registry names are snake_case. Lowercasing alone gives `wiremill`, which matches
# nothing; the boundary has to be recovered before the case is thrown away.
_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")


def is_hand_crafting(category):
    """True for a crafting-table recipe under either source's naming convention."""
    cat = str(category or "")
    return any(cat.startswith(p) for p in _HAND_CRAFTING_PREFIXES)

# Machine blocks are commonly registered once per running state. NuclearCraft ships
# `alloy_furnace_idle` / `alloy_furnace_active` as separate items while the placed tile
# entity is the bare `alloy_furnace`, so a literal comparison reports a machine you are
# standing next to as merely "buildable". Strip these before matching.
_STATE_SUFFIX = re.compile(
    r"_(idle|active|on|off|lit|unlit|powered|unpowered|running|working)$")


def _tokens(text):
    return [t for t in _SPLIT.split(str(text).lower()) if t]


def _squash(text):
    return _NON_ALNUM.sub("", str(text)).lower()


def same_mod(uid, key):
    """Does `key` belong to the mod that owns category `uid`?

    Compares the candidate's modid against the SQUASHED uid rather than against its first
    token. A modid can itself contain an underscore -- `tinker_io:smart_output` tokenises
    to `tinker`, so a first-token comparison declares tinker_io's own machine to be from a
    different mod -- and a uid may separate modid from name with `.`, `_`, `:` or a
    camelCase boundary, none of which can be told apart from a separator inside the modid.
    Substring on the squashed form is the only comparison that survives all of those.
    """
    modid = _squash(str(key).split(":")[0])
    return bool(modid) and modid in _squash(uid)


def normalise_block(key):
    """Drop a machine's running-state suffix so variants compare equal."""
    key = str(key).lower()
    prev = None
    while prev != key:
        prev = key
        key = _STATE_SUFFIX.sub("", key)
    return key


def _index(keys):
    """{normalised id: original} for membership tests that ignore state suffixes."""
    out = {}
    for k in keys:
        out.setdefault(normalise_block(k), k)
    return out


def _id_guesses(uid):
    """Registry-name guesses built from a category uid, best first.

    `TechReborn.WireMill` has to reach `techreborn:wire_mill`, so the camelCase boundary
    is recovered before lowercasing. The un-split form is tried too, because plenty of
    mods really do register `nuclearcraft:cobblestone_generator` style ids from an
    already-underscored uid, and a few register the squashed form.
    """
    parts = [p for p in _NON_ALNUM.split(str(uid)) if p]
    if len(parts) < 2:
        return []
    modid = parts[0].lower()
    words = []
    for part in parts[1:]:
        words.extend(w for w in _CAMEL.split(part) if w)
    if not words:
        return []
    snake = "_".join(w.lower() for w in words)
    squashed = "".join(w.lower() for w in words)
    guesses = ["%s:%s" % (modid, snake)]
    if squashed != snake:
        guesses.append("%s:%s" % (modid, squashed))
    return guesses


def candidate_items(graph, uid, machine_title, reverse_names, catalysts=None):
    """Item keys that could BE the machine for a category.

    A machine block's item and block registry names are the same in 1.12.2, so a
    candidate can be checked against both placed tile entities and inventory with no
    extra mapping. Candidates come from the category's display title, which is what JEI
    labels the machine, then filtered by the category's modid where one is recoverable --
    without that filter a title like "Furnace" matches a dozen unrelated mods.

    Title matching is a HEURISTIC and misses roughly 40% of categories, because a JEI
    category title is often the recipe type rather than the machine ("Casting", "Smelting",
    "Cover Crafting"). `catalysts` from the dump mod is the authoritative mapping and wins
    outright when present; everything here is the fallback for a graph built without it.
    """
    if catalysts:
        return list(catalysts)
    # (catalyst ordering is handled by `describe`, which can see every category at once)

    cands = []
    title = clean_label(machine_title)
    if title:
        cands.extend(reverse_names.get(title.lower(), []))

    uid_tokens = _tokens(uid)
    modid = uid_tokens[0] if uid_tokens else ""

    if modid:
        same = [c for c in cands if same_mod(uid, c)]
        if same:
            return same
        for guess in _id_guesses(uid):
            if guess in graph.names and guess not in cands:
                cands.append(guess)
    return cands


def resolve(graph, placed=None, stock=None, catalysts=None, overrides=None):
    """Return {category_uid: (state, evidence)} for every category in the graph."""
    return {uid: (info["state"], info["why"])
            for uid, info in describe(graph, placed, stock, catalysts, overrides).items()}


def describe(graph, placed=None, stock=None, catalysts=None, overrides=None):
    """Full per-category detail: state, evidence, candidate machine items, recipe count.

    `resolve` is the two-value view the solver and cost model consume; this is the view the
    machines page needs, so that "why does it think I do not have this" is answerable
    without re-deriving the candidates by hand.
    """
    from .names import build_reverse

    placed = placed or {}
    stock = stock or {}
    # A graph built with the dump mod carries JEI's own category->machine mapping. Prefer it
    # over anything a caller passes only if the caller passed nothing: an explicit argument
    # is how tests and one-off overrides work.
    if catalysts is None:
        catalysts = getattr(graph, "catalysts", None) or {}
    # Must run over the WHOLE mapping: "is this item specific to one machine" is only
    # answerable by looking at every category at once.
    catalysts = order_by_specificity(catalysts) if catalysts else {}
    overrides = overrides or {}
    reverse_names = build_reverse(graph.names)
    placed_index = _index(placed)
    stock_index = _index(k for k, v in stock.items() if v)

    categories = {}
    counts = {}
    for r in graph.recipes:
        categories.setdefault(r.category, r.machine)
        counts[r.category] = counts.get(r.category, 0) + 1

    out = {}
    for uid, title in categories.items():
        rec = {
            "uid": uid,
            "title": clean_label(title),
            "mod": (_tokens(uid) or [""])[0],
            "recipes": counts.get(uid, 0),
            "candidates": [],
            "manual": uid in overrides,
        }
        out[uid] = rec

        if uid in overrides:
            rec.update(state=overrides[uid], why="manual override")
            continue
        if uid in ALWAYS_AVAILABLE or is_hand_crafting(uid):
            rec.update(state=HAVE, why="no machine needed")
            continue

        # Catalysts from the dump mod are exact; fall back to name matching without them.
        cands = candidate_items(graph, uid, title, reverse_names, catalysts.get(uid))
        rec["candidates"] = cands
        rec["from_catalyst"] = bool(catalysts.get(uid))
        if not cands:
            rec.update(state=UNKNOWN, why="machine item unknown")
            continue

        # A cross-mod name match is a much weaker claim than a same-mod one: the Extra
        # Utilities furnace category is titled "Furnace" and matches `minecraft:furnace`,
        # so "placed" would otherwise assert you own a machine you have never built. Say
        # so in the evidence rather than presenting a guess as a sighting.
        caveat = "" if rec["from_catalyst"] else _cross_mod_note(uid, cands)

        built = [placed_index[normalise_block(c)] for c in cands
                 if normalise_block(c) in placed_index]
        if built:
            rec.update(state=HAVE, why="placed: %s%s" % (built[0], caveat))
            continue
        held = [stock_index[normalise_block(c)] for c in cands
                if normalise_block(c) in stock_index]
        if held:
            rec.update(state=HAVE, why="in stock: %s%s" % (held[0], caveat))
            continue
        makeable = [c for c in cands if graph.producers(c)]
        if makeable:
            rec.update(state=BUILDABLE, why="craftable: %s%s" % (makeable[0], caveat))
            continue
        rec.update(state=UNAVAILABLE, why="no route to %s%s" % (cands[0], caveat))
    return out


def order_by_specificity(catalysts):
    """Reorder each category's catalysts so the most specific machine comes first.

    JEI lists whatever opens a category's recipes, and for Modular Machinery that is the
    generic Machine Blueprint -- one item that catalyses 226 unrelated categories. Taken in
    JEI's order it becomes the answer to "what machine is this", so a plan read
    "Mythic Processor: Melter -- craftable: modularmachinery:itemblueprint". You can craft a
    blueprint; that does not give you the machine.

    Ordering by how many categories an item catalyses, fewest first, fixes it with no
    threshold and no per-mod list: a purpose-built controller catalyses exactly one category
    and wins, while a generic block that is genuinely the only candidate (Extra Utilities
    registers 19 machine types under `extrautils2:machine`) is DEMOTED rather than dropped,
    so it still answers when nothing better exists.
    """
    breadth = {}
    for ids in catalysts.values():
        for key in ids:
            breadth[key] = breadth.get(key, 0) + 1
    return {uid: sorted(ids, key=lambda k: (breadth.get(k, 1), ids.index(k)))
            for uid, ids in catalysts.items()}


def _cross_mod_note(uid, candidates):
    """" (name match, other mod)" when no candidate belongs to the category's own mod."""
    if not candidates:
        return ""
    if any(same_mod(uid, c) for c in candidates):
        return ""
    return " (name match, other mod)"


def responsibilities(graph, uid, limit=40):
    """What a category is actually for: the items it makes and the ones it consumes.

    Answers "what is this machine responsible for" without the caller walking 100k
    recipes. Sorted by how many of the category's recipes touch each key, so the head of
    each list is what the machine is *characteristically* for rather than an arbitrary
    sample -- a Melter that appears once for Borax and 600 times for metals should read as
    a metal melter.
    """
    makes, uses = {}, {}
    total = 0
    for r in graph.recipes:
        if r.category != uid:
            continue
        total += 1
        for key, _qty in r.outputs:
            makes[key] = makes.get(key, 0) + 1
        seen = set()
        for ing in r.inputs:
            # Count a slot once however many alternatives it lists, or a 200-member oredict
            # slot would dominate purely by width.
            for alt in ing.alternatives[:1]:
                if alt not in seen:
                    seen.add(alt)
                    uses[alt] = uses.get(alt, 0) + 1
    rank = lambda d: sorted(d.items(), key=lambda kv: (-kv[1], graph.display(kv[0])))
    return {
        "uid": uid,
        "recipes": total,
        "makes": rank(makes)[:limit],
        "makes_total": len(makes),
        "uses": rank(uses)[:limit],
        "uses_total": len(uses),
    }


def load_overrides(path):
    if not path or not os.path.exists(path):
        return {}
    with open(path) as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    raw = doc.get("overrides", doc)
    return {k: v for k, v in raw.items() if v in STATES}


def save_overrides(path, overrides):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as fh:
        json.dump({
            "_comment": "Manual machine availability. Values: have | buildable | "
                        "unknown | unavailable. These win over anything auto-detected.",
            "overrides": overrides,
        }, fh, indent=1, sort_keys=True)


def summarise(states):
    counts = dict.fromkeys(STATES, 0)
    for state, _why in states.values():
        counts[state] = counts.get(state, 0) + 1
    return counts


def available_categories(states, include_buildable=True):
    """Categories a plan may route through.

    `unknown` is included alongside `buildable`: an unidentified machine is not evidence
    that the player cannot use it, and excluding it would hide 40% of the graph.
    """
    ok = {HAVE} | ({BUILDABLE, UNKNOWN} if include_buildable else set())
    return {uid for uid, (state, _why) in states.items() if state in ok}
