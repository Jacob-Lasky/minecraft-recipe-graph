"""Placeholder items that tell the player something, rather than items they can hold.

WHY THIS EXISTS. A plan for an Erebus Exoskeleton Plate asked for a "Dungeon Drop" and a
"From Battle Tower Loot" as if they were two materials to gather. They are neither
materials nor two: the pack's CraftTweaker scripts define one placeholder item per SOURCE
of loot and put it in the recipe, so the tool faithfully rendered N distinct shopping-list
lines for what is really one instruction, "go play the game". Reported as: "a broad 'drop'
category feels like a better rollup than having both dungeon drop and battletower".

DETECTION IS A CURATED LIST, NOT A NAME GUESS, and that is not caution for its own sake.
The obvious heuristic is a substring test, and it is actively wrong here:
`contenttweaker:vibranium_chest` is a CHESTPLATE, one piece of a four-part armour set
alongside `vibranium_legs`, `vibranium_head` and `vibranium_feet`, and
`contenttweaker:vox_ponds_token_legs` is armour too. Matching "chest" or "token" would
quietly rewrite real craftable gear into "go find it in a chest". Every id below was
checked against the reference graph: consumed by at least one recipe, produced by none.

FOUR KINDS, because one bucket would lie. "Boss Drop" and "Chapter 1" are both
unobtainable placeholders and they ask completely different things of the player: one is
an afternoon of fighting, the other is a quest you have not unlocked. `good_sword_materials`
is not a thing to obtain at all, it is a note that any of a class of materials will do, and
`multiblock_preview` says the recipe happens in a machine rather than on a bench.

The structural test in `candidates` OFFERS additions and never asserts them, the same split
`generators.py` uses: a wrong entry here silently turns a real item into "go play the game"
and hides a genuine crafting route, which is worse than the repetition this fixes.
"""

import json
import os

# The kinds, and what each one asks of the player. Ordered from "go do something in the
# world" to "this is not an ingredient at all", which is the order the plan panel lists
# them in: the actionable ones first.
LOOT = "loot"
GATE = "gate"
HINT = "hint"
METHOD = "method"
KINDS = (LOOT, GATE, HINT, METHOD)

KIND_LABEL = {
    LOOT: "found by playing",
    GATE: "locked behind progress",
    HINT: "any material of this class",
    METHOD: "made by a mechanic, not on a bench",
}

# What the tree badge says on a node of each kind. Short, because it sits at the end of a
# row that already carries a quantity and a name.
KIND_BADGE = {
    LOOT: "go get",
    GATE: "locked",
    HINT: "any of class",
    METHOD: "mechanic",
}

# The namespace packs put script-defined items in. Used ONLY to decide where to look for
# candidates; membership of the curated map below is what makes something a token.
TOKEN_NAMESPACES = ("contenttweaker",)

# key -> kind. Every entry verified against the reference graph rather than recalled: each
# is consumed by at least one recipe and produced by none. 37 ids covering 1,272 recipe
# slots. `chapter_3` and `chapter_7` are deliberately ABSENT: the pack defines the item but
# no recipe references it, and asserting an id nothing uses is how a list starts drifting
# from the pack it describes.
DEFAULT_TOKENS = {
    # Loot. Recipe counts are from the reference pack; the two Jake reported are the second
    # and fifth largest, which is why the repetition was so visible.
    "contenttweaker:boss_drop": LOOT,                # 224 recipes
    "contenttweaker:dungeon_drop": LOOT,             # 206
    "contenttweaker:hunter_mobs": LOOT,              # 72
    "contenttweaker:trader_drop": LOOT,              # 46
    "contenttweaker:battle_tower": LOOT,             # 36
    "contenttweaker:mineralis_ritual": LOOT,         # 32, ores from a ritual
    "contenttweaker:good_woot_drops": LOOT,          # 25
    "contenttweaker:orbital_laser_drops": LOOT,      # 23
    "contenttweaker:fisher_drop": LOOT,              # 20
    "contenttweaker:rare_loot_table": LOOT,          # 12
    "contenttweaker:foraging_loot_table": LOOT,      # 12
    "contenttweaker:found_in_overworld": LOOT,       # 10
    "contenttweaker:use_this_summon_item": LOOT,     # 6
    "contenttweaker:recycler_drop": LOOT,            # 1

    # Progression gates. NOT loot: no amount of fighting produces these, and folding them in
    # would tell a player to go hunt for "Chapter 1".
    "contenttweaker:chapter_1": GATE,                # 38
    "contenttweaker:space_station": GATE,            # 32
    "contenttweaker:hunter_level_20": GATE,          # 20
    "contenttweaker:hunter_level_1": GATE,           # 17
    "contenttweaker:chapter_2": GATE,                # 15
    "contenttweaker:gamestage_recipe": GATE,         # 14
    "contenttweaker:hunter_level_30": GATE,          # 12
    "contenttweaker:chapter_4": GATE,                # 12
    "contenttweaker:chapter_8": GATE,                # 8
    "contenttweaker:chapter_6": GATE,                # 7
    "contenttweaker:chapter_5": GATE,                # 6

    # Ingredient-class notes. There is nothing to obtain: the recipe accepts any member.
    "contenttweaker:good_sword_materials": HINT,     # 37
    "contenttweaker:good_shuriken_materials": HINT,  # 33
    "contenttweaker:good_tool_materials": HINT,      # 18
    "contenttweaker:coolant_great": HINT,            # 8

    # The recipe is performed by a mechanic rather than assembled from parts.
    "contenttweaker:multiblock_preview": METHOD,     # 101, all compactmachines3
    "contenttweaker:dream_infusion_crafting": METHOD,  # 93
    "contenttweaker:passive_crafting_subnets": METHOD,  # 25
    "contenttweaker:runic_altar_automation": METHOD,  # 16
    "contenttweaker:thaumatorium_automation": METHOD,  # 14
    "contenttweaker:infusion_pseudo_automation": METHOD,  # 7
    "contenttweaker:passive_packagedauto": METHOD,   # 6
    "contenttweaker:right_click_with_lots_of_infusionstones": METHOD,  # 8
}


def load_overrides(path):
    """User additions and removals from `data/tokens.json`, or {} when there is none.

    Shape mirrors `generators.load_overrides`: `{"tokens": {key: kind}, "disabled": [key]}`.
    A malformed file reads as empty rather than raising, because a typo in a hand-edited
    override should not take the whole plan down.
    """
    if not path or not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (ValueError, OSError):
        return {}
    return doc if isinstance(doc, dict) else {}


def save_overrides(path, added=None, disabled=()):
    """Write `data/tokens.json`. Only the user's edits, never the curated defaults.

    Writing the merged map would freeze today's defaults into the user's file, so a later
    correction to DEFAULT_TOKENS would be silently overridden by a copy of the old one.
    """
    doc = {"tokens": dict(added or {}), "disabled": sorted(set(disabled))}
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, sort_keys=True)
    return doc


def resolve(overrides=None):
    """{key: kind} for this world: the curated map plus the user's, minus their removals.

    The ONE place the effective map is assembled, so the solver, the CLI listing and the
    candidate scan cannot disagree about what counts as a token.
    """
    out = dict(DEFAULT_TOKENS)
    overrides = overrides or {}
    for key, kind in (overrides.get("tokens") or {}).items():
        if kind in KINDS:
            out[str(key)] = kind
    for key in overrides.get("disabled") or ():
        out.pop(str(key), None)
    return out


def for_path(path):
    """The effective `{key: kind}` for an overrides file path. The one composition.

    Both the CLI and the server need "load the file, merge it over the defaults", and two
    copies of that is two places to update when resolution grows a step.
    """
    return resolve(load_overrides(path))


def group(entries):
    """`[(kind, label, [entry, ...])]` in KINDS order, skipping kinds with nothing in them.

    The rollup itself. Jake asked for one "drop" heading instead of a line per source, and
    for the sources to stay legible underneath it, because "Battle Tower" and "go fishing"
    are genuinely different afternoons. So the kind is the heading and the tokens are still
    named under it, rather than being collapsed into a single count.
    """
    by_kind = {}
    for entry in entries:
        by_kind.setdefault(entry.get("token_kind"), []).append(entry)
    return [(kind, KIND_LABEL[kind], by_kind[kind]) for kind in KINDS if kind in by_kind]


def candidates(graph, known=None, limit=40):
    """Placeholder-shaped keys that are NOT curated yet, most-used first.

    Offers, never asserts. The structural signal is a key some recipe consumes that no
    recipe produces, living in a pack's script namespace. That is necessary and nowhere
    near sufficient: most keys it finds are ordinary items the dump simply has no recipe
    for, which is why the result is a list for a human to read rather than an input to the
    solver.

    Returns `[(key, display name, recipes needing it)]`.
    """
    known = resolve() if known is None else known
    prefixes = tuple("%s:" % ns for ns in TOKEN_NAMESPACES)
    out = []
    for key, recipes in graph.by_input.items():
        if key in known or not str(key).startswith(prefixes):
            continue
        if graph.producers(key):
            continue
        out.append((key, graph.bare_name(key), len(recipes)))
    out.sort(key=lambda row: (-row[2], row[0]))
    return out[:limit]
