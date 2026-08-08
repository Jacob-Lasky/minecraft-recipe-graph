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

AND IT DECLARES LOOT-TABLE CATEGORIES TOO, which is a second subject in one module and is
deliberate. `DEFAULT_TOKENS` and `LOOT_TABLE_CATEGORIES` are the same kind of statement about
the same pack -- "JEI publishes this and it is not what it looks like" -- differing only in
whether the subject is an ITEM or a CATEGORY. #211 asked for them in one declared place for
exactly that reason: two curated lists of the same claim in two files drift, and the drift is
silent because each file's tests only read its own. What is DONE with either declaration lives
in `notproduction.py`, which is the one consumer of both.
"""

import json
import os
import re

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
#
# THIS IS NOT THE EFFECTIVE MAP AND HAS NOT BEEN SINCE #171. `complete_families` derives the
# other members of any numbered family below -- the pack uses `hunter_level_3/5/9/12/13/17/
# 19/40/50` and `chapter_9`, ten GATE ids nobody curated -- so `resolve` returns 47 entries
# on the reference pack against the 37 written here. DO NOT "fix" that by pasting the ten in:
# this list drifting is the defect, and a rule that completes a family a human already
# verified is what stops it drifting again. Read `complete_families` before adding an id that
# only differs from one above by its number.
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


# JEI category uids that publish a RANDOM LOOT TABLE: one entry per possible outcome, with
# the container as the input of every one. #211.
#
# A scrapbox yields ONE uniformly random item from a table of hundreds. JEI has no way to say
# that, so it files 343 separate entries each reading "Scrap Box -> this item", and a graph
# that trusts them believes one scrapbox obtains any of 343 things. That is how planning a
# vanilla Chest went through Chest Cart -> Scrap Box -> Matter Reprocessor and asked for four
# machines and 576 bee princesses when the answer is eight planks.
#
# A CURATED LIST OF UIDS RATHER THAN A NAME PATTERN, and `index.NON_RECIPE_CATEGORY_PATTERNS`
# is why: that list matches `loot` and `.drop` as SUBSTRINGS and it is the reason two of the
# three names below never reach a built graph at all. `TechReborn.Scrapbox` contains neither
# word, and no pattern that would catch it is safe -- "scrap" also names TechReborn's real
# Recycler recipes, which turn items INTO scrap and are genuine production.
#
# THE DECLARATION OUTRANKS THE PATTERN LIST, see `index.is_non_recipe`, so all three go
# through one mechanism. The pattern list DROPS a category and this one PRICES IT OUT, and
# price-out is strictly more informative: the JEI card stays visible in `used_in`, and an
# output whose only route is the loot table keeps that route instead of silently becoming a
# `cost._seed` leaf at `BASE_RAW_COST`, level with dirt.
#
# WHAT IS PRESENT IN A BUILT GRAPH, measured, and the two zeroes are the point rather than a gap:
#
#   TechReborn.Scrapbox     343 entries in the reference graph, every one of them
#   intestines_loot_table   0 in the reference graph -- the `loot` pattern drops them first
#   aoa_extraction_loot     0, same
#
# #211 counted 34 and 19 raw DUMP entries for the last two. That is the issue's figure and not
# one re-measured here: the pattern list has always removed them, so no graph on disk carries
# either name and there is nothing to count. They are declared anyway, because they are the same
# claim about the same pack and a list naming only the survivor teaches the next reader that the
# pattern list is the whole answer. They take effect on the next redump, when `is_non_recipe`
# defers to this list, and `tests/test_non_production.py` asserts that deferral directly rather
# than waiting for a pack that exercises it.
LOOT_TABLE_CATEGORIES = frozenset((
    "TechReborn.Scrapbox",
    "intestines_loot_table",
    "aoa_extraction_loot",
))


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


# The trailing counter that makes an id one of a FAMILY: `chapter_9`, `hunter_level_40`.
# The separator is captured rather than assumed, so `chapter_9` and a hypothetical `chapter9`
# are different families and neither completes the other.
_FAMILY = re.compile(r"^(.*?)([_-]?)(\d+)$")


def _family(key):
    """`(namespace, stem, separator)` for an id ending in a counter, else None."""
    namespace, _, ident = str(key).partition(":")
    m = _FAMILY.match(ident)
    return (namespace, m.group(1), m.group(2)) if m else None


def complete_families(known, graph):
    """`known`, plus the members of its own numbered families the pack also uses. #171.

    THE CURATED LIST HAS DRIFTED AND THIS IS THE PROOF, not a guess at what might be missing.
    `DEFAULT_TOKENS` declares `hunter_level_1`, `_20` and `_30` and `chapter_1/2/4/5/6/8`; the
    reference pack also uses `hunter_level_3/5/9/12/13/17/19/40/50` and `chapter_9`, every one
    of them a locked progression gate consumed by real recipes and priced at `BASE_RAW_COST`
    until #243, and at `UNSOURCED_COST` after it. Ten ids, all GATE, none curated.

    DERIVED RATHER THAN LISTED, WHICH IS THE WHOLE POINT. Writing those ten into
    `DEFAULT_TOKENS` fixes today's drift and guarantees tomorrow's, which is exactly the
    failure the list is currently demonstrating -- #171 asks for something that "detects the
    shape rather than enumerates the members", and a family whose other members are already
    hand-verified is the narrowest such shape available.

    THREE CLAUSES, AND EACH ONE DECLINES A WAY THIS COULD GO WRONG:

      * THE FAMILY MUST ALREADY BE CURATED. The stem comes from a hand-verified id, so this
        can only ever complete a claim a human already made. It never invents a family, which
        is what keeps it off `1_in_200`, `level15` and the rest of the pack's numbered ids --
        real markers all, and not this function's business to guess at.
      * THE FAMILY MUST AGREE WITH ITSELF. A stem whose curated members carry two different
        kinds is skipped rather than resolved by majority or by first-seen. Nothing in the
        pack does this today; a list that grew one is a list saying it does not know.
      * THE KEY MUST BE ONE THE GRAPH CANNOT EXPLAIN, via `pack_authored_unsourced`. This is
        the #117/#168 guard: an over-broad rule costs more real items than it fixes fake ones,
        and calling a produced item a placeholder hides a genuine crafting route. Measured on
        the reference oracle the guard changes nothing -- all 10 pass it either way -- so it
        is here for the pack that has a `chapter_10` you can actually craft.

    AND IT AGREES WITH THE CURATOR ON A CASE IT WAS NEVER SHOWN, which is the strongest
    evidence available that this rule is right rather than merely convenient. `DEFAULT_TOKENS`
    records `chapter_3` and `chapter_7` as "deliberately ABSENT: the pack defines the item but
    no recipe references it, and asserting an id nothing uses is how a list starts drifting".
    Both are named in items.csv and neither is live, so `pack_authored_unsourced` excludes them
    through `live_keys` and this function declines them for the curator's own stated reason
    without being told the reason. A heuristic reproducing a judgement call it never saw is
    worth more than one that reproduces the cases it was fitted to.

    `graph` of None returns `known` untouched: a caller with no graph in hand cannot apply the
    third clause, and widening without it is the over-broad rule above.
    """
    if graph is None:
        return known
    families = {}
    for key, kind in known.items():
        family = _family(key)
        if family:
            families.setdefault(family, set()).add(kind)
    if not families:
        return known
    out = dict(known)
    for key in graph.pack_authored_unsourced:
        if key in out:
            continue
        kinds = families.get(_family(key) or ())
        if kinds and len(kinds) == 1:
            out[key] = next(iter(kinds))
    return out


def resolve(overrides=None, graph=None):
    """{key: kind} for this world: the curated map plus the user's, minus their removals.

    The ONE place the effective map is assembled, so the solver, the CLI listing and the
    candidate scan cannot disagree about what counts as a token.

    FAMILY COMPLETION RUNS BETWEEN THE ADDITIONS AND THE REMOVALS, and both neighbours are
    deliberate. After the user's additions, so a family they declare completes exactly as a
    curated one does; before their removals, so `disabled` can take out a DERIVED id as well
    as a curated one. A user who has looked at `contenttweaker:hunter_level_9` and decided it
    is not a gate must be able to say so, and a rule that re-added it every load would be a
    curated list they cannot edit.
    """
    out = dict(DEFAULT_TOKENS)
    overrides = overrides or {}
    for key, kind in (overrides.get("tokens") or {}).items():
        if kind in KINDS:
            out[str(key)] = kind
    out = complete_families(out, graph)
    for key in overrides.get("disabled") or ():
        out.pop(str(key), None)
    return out


def for_path(path, graph=None):
    """The effective `{key: kind}` for an overrides file path. The one composition.

    Both the CLI and the server need "load the file, merge it over the defaults", and two
    copies of that is two places to update when resolution grows a step.

    PASS THE GRAPH WHENEVER THERE IS ONE. Without it `complete_families` cannot apply its
    third clause and returns the map unwidened, which is the pre-#171 answer and not a wrong
    one -- but two callers resolving the same overrides file with and without a graph would
    disagree about what a token is, which is the drift this function exists to prevent.
    `tests/test_tokens.py` pins the callers that have a graph to passing it.
    """
    return resolve(load_overrides(path), graph)


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

    IT DEFERS TO `Graph.pack_authored_unsourced` FOR THE POPULATION rather than restating
    the rule, because it used to restate a WEAKER one and the difference was 884 keys of
    noise in a list whose whole value is being short enough to read. On the reference graph
    the bare "pack namespace, consumed, unproduced" signal returns 1,120 keys, and 884 of
    them are things nobody would ever curate:

      * 837 armour durability variants, `bloodmaster_metal_chest:28400` and its siblings,
        whose undamaged key is produced perfectly well.
      * 47 keys carrying oredict membership -- 11 world ores, 21 nuggets, 13 storage blocks
        and 5 foods, including the Sednanite Nugget that #136 was filed about.

    Sorted by recipe count, enough of that noise outranks real placeholders to push them off
    a `limit=40` page, so the filter is what makes the offer readable rather than merely
    shorter. The retention check that says it is not TOO narrow: all 37 curated
    `DEFAULT_TOKENS` survive it, asserted against the real graph by
    `tests/test_pack_provenance.py` when `$RECIPEGRAPH_ORACLE` names one, and skipped rather
    than faked when it does not.

    Returns `[(key, display name, recipes needing it)]`.
    """
    # `graph=graph`, so the offer excludes what family completion already claimed. A
    # candidate list re-offering `hunter_level_9` after the map derived it as a GATE is
    # asking a human to curate an answer the tool already has. #171.
    known = resolve(graph=graph) if known is None else known
    eligible = graph.pack_authored_unsourced
    out = []
    for key, recipes in graph.by_input.items():
        if key in known or key not in eligible:
            continue
        out.append((key, graph.bare_name(key), len(recipes)))
    out.sort(key=lambda row: (-row[2], row[0]))
    return out[:limit]
