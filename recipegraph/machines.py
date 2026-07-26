"""Which machines you actually have, and what that means for recipe choice.

The problem this solves: on a 340k-recipe graph almost every item has dozens of routes,
most through machines the player has never built. Without this, the solver happily plans
a chain through a Recursive Processor you do not own, which is how "64 Borax" ended up
routed via Chaos Fragments.

Every recipe carries its JEI category, and a category IS a machine. So machine
availability is a constraint on categories, which turns recipe choice from a guess into
a filter.

THREE STATES, not two. "Don't own it" is not the same as "can't use it":
  * have        -- the machine is placed in the world, or its item is in your stock
  * buildable   -- not present, but the machine item itself is craftable
  * unavailable -- neither, or you disabled it by hand
A plan may legitimately route through a `buildable` machine; it just has to TELL you to
build it first. That is the "I only have a crafting table, so I will make a furnace"
case: the furnace is buildable, an alloy smelter is not.

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

HAVE = "have"
BUILDABLE = "buildable"
UNAVAILABLE = "unavailable"

# Categories that need no machine at all. Never gate these; the player always has hands.
ALWAYS_AVAILABLE = {
    "minecraft.crafting", "minecraft.crafting.shaped", "minecraft.crafting.shapeless",
    "minecraft.anvil", "minecraft.brewing", "minecraft.fuel", "minecraft.smelting",
    "jei.information", "jei.description",
}

_SPLIT = re.compile(r"[^a-z0-9]+")

# Machine blocks are commonly registered once per running state. NuclearCraft ships
# `alloy_furnace_idle` / `alloy_furnace_active` as separate items while the placed tile
# entity is the bare `alloy_furnace`, so a literal comparison reports a machine you are
# standing next to as merely "buildable". Strip these before matching.
_STATE_SUFFIX = re.compile(
    r"_(idle|active|on|off|lit|unlit|powered|unpowered|running|working)$")


def _tokens(text):
    return [t for t in _SPLIT.split(str(text).lower()) if t]


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


def scan_world_machines(region_paths):
    """Tile-entity ids present in the given region files -- machines actually built."""
    from .anvil_nbt import iter_region, tile_entities

    placed = {}
    for path in region_paths:
        for _cx, _cz, root in iter_region(path):
            for te in tile_entities(root):
                tid = te.get("id")
                if isinstance(tid, str) and tid:
                    placed[tid] = placed.get(tid, 0) + 1
    return placed


def candidate_items(graph, uid, machine_title, reverse_names):
    """Item keys that could BE the machine for a category.

    A machine block's item and block registry names are the same in 1.12.2, so a
    candidate can be checked against both placed tile entities and inventory with no
    extra mapping. Candidates come from the category's display title, which is what JEI
    labels the machine, then filtered by the category's modid where one is recoverable --
    without that filter a title like "Furnace" matches a dozen unrelated mods.
    """
    cands = []
    title = (machine_title or "").strip()
    if title:
        cands.extend(reverse_names.get(title.lower(), []))

    uid_tokens = _tokens(uid)
    modid = uid_tokens[0] if uid_tokens else ""

    if modid:
        same_mod = [c for c in cands if c.split(":")[0].lower() == modid]
        if same_mod:
            return same_mod
        # Fall back to constructing the id directly from the uid: `nuclearcraft_crystallizer`
        # -> `nuclearcraft:crystallizer`, which is how most machine blocks are registered.
        rest = "_".join(uid_tokens[1:])
        if rest:
            guess = "%s:%s" % (modid, rest)
            if guess in graph.names:
                cands.append(guess)
    return cands


def resolve(graph, placed=None, stock=None, catalysts=None, overrides=None):
    """Return {category_uid: (state, evidence)} for every category in the graph."""
    from .names import build_reverse

    placed = placed or {}
    stock = stock or {}
    catalysts = catalysts or {}
    overrides = overrides or {}
    reverse_names = build_reverse(graph.names)
    placed_index = _index(placed)
    stock_index = _index(k for k, v in stock.items() if v)

    categories = {}
    for r in graph.recipes:
        categories.setdefault(r.category, r.machine)

    out = {}
    for uid, title in categories.items():
        if uid in overrides:
            out[uid] = (overrides[uid], "manual override")
            continue
        if uid in ALWAYS_AVAILABLE or uid.startswith("minecraft.crafting"):
            out[uid] = (HAVE, "no machine needed")
            continue

        # Catalysts from the dump mod are exact; fall back to name matching without them.
        cands = catalysts.get(uid) or candidate_items(graph, uid, title, reverse_names)
        if not cands:
            out[uid] = (UNAVAILABLE, "machine item unknown")
            continue

        built = [placed_index[normalise_block(c)] for c in cands
                 if normalise_block(c) in placed_index]
        if built:
            out[uid] = (HAVE, "placed: %s" % built[0])
            continue
        held = [stock_index[normalise_block(c)] for c in cands
                if normalise_block(c) in stock_index]
        if held:
            out[uid] = (HAVE, "in stock: %s" % held[0])
            continue
        makeable = [c for c in cands if graph.producers(c)]
        if makeable:
            out[uid] = (BUILDABLE, "craftable: %s" % makeable[0])
            continue
        out[uid] = (UNAVAILABLE, "no route to %s" % cands[0])
    return out


def load_overrides(path):
    if not path or not os.path.exists(path):
        return {}
    with open(path) as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    raw = doc.get("overrides", doc)
    return {k: v for k, v in raw.items() if v in (HAVE, BUILDABLE, UNAVAILABLE)}


def save_overrides(path, overrides):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as fh:
        json.dump({
            "_comment": "Manual machine availability. Values: have | buildable | "
                        "unavailable. These win over anything auto-detected.",
            "overrides": overrides,
        }, fh, indent=1, sort_keys=True)


def summarise(states):
    counts = {HAVE: 0, BUILDABLE: 0, UNAVAILABLE: 0}
    for state, _why in states.values():
        counts[state] = counts.get(state, 0) + 1
    return counts


def available_categories(states, include_buildable=True):
    ok = {HAVE} | ({BUILDABLE} if include_buildable else set())
    return {uid for uid, (state, _why) in states.items() if state in ok}
