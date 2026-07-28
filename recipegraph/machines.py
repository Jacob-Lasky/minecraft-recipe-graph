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

# Categories with no machine BY NATURE, which is a different answer from "we could not
# work out which block this is". Bee and tree breeding happen in an Apiary that JEI
# registers no catalyst for; a chicken lays where it stands, so the chicken IS the
# machine. Reporting those as `unknown` reads as a tool failure and prices real, always
# available production at 120x the cost of hand-crafting.
#
# This IS a list, and it has to be: nothing in a recipe distinguishes "needs no machine"
# from "machine not identified". But it is a list of STRUCTURAL SITUATIONS -- production
# driven by a living thing, or by a structure JEI does not catalyse -- rather than a
# per-pack lookup, and a pack that needs another entry adds it to `machines.json` under
# `no_machine` instead of editing this. Substrings, matched case-insensitively.
#
# DO NOT collapse the three `chickens.` entries into a bare `chickens.` prefix. That mod
# also registers `chickens.drops` and `chickens.throws`, which are display categories
# rather than production; they are dropped by `index.NON_RECIPE_CATEGORY_PATTERNS` today,
# so a prefix would look harmless, but it would silently grant "no machine needed" to
# whatever the mod registers next. Each entry here is a claim about one category.
NO_MACHINE_PATTERNS = (
    "jeibees.mutation", "jeibees.produce",     # Forestry bees, trees, butterflies
    "beetree",                                 # Bee Better At Bees, same production
    # The chicken is the machine: it lays where it stands, breeds where it stands, and
    # the henhouse byproduct falls out of the same bird. All three of the Chickens mod's
    # production categories, so a fourth appearing as `unknown` is real news. See #33.
    "chickens.laying", "chickens.henhousing", "chickens.breeding",
)

# Every pattern above describes production by a CREATURE, and a creature's identity lives
# in NBT that the dump only began emitting at schema 3. Below that, all 437 bee mutations
# are the same four keys, `produce.rootBees` is one input claiming to make 323 unrelated
# items, and pricing that as free lets anything reach anything through a generic drone --
# measured: Americium-242 rerouted onto bee larvae, diamond and glass panes.
#
# So the verdict is gated on the data being able to support it. `unknown`'s higher cost is
# doing real work holding those edges back until then, and this self-heals on the next
# /recipedump rather than needing a flag anyone has to remember. See issue #20.
SPECIES_SCHEMA = 3

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


def needs_no_machine(category, extra=(), schema=0):
    """True when a category has no machine by nature rather than none we could find.

    `extra` is the user's own `no_machine` list and is never gated: an explicit human
    decision outranks what the dump can prove. The built-in patterns are gated on
    SPECIES_SCHEMA -- see the comment there for why.
    """
    cat = str(category or "").lower()
    if cat in {str(e).lower() for e in extra}:
        return True
    if (schema or 0) < SPECIES_SCHEMA:
        return False
    return any(pat in cat for pat in NO_MACHINE_PATTERNS)

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


def match_forms(key):
    """Every normalised id a placed block should be findable under, verbatim form first.

    A tile-entity id is NOT an item id. Mods that register a colon-less id the old way
    (`GameRegistry.registerTileEntity(..., "tconstruct.smeltery_controller")`) get it
    namespaced into `minecraft:` by Forge, so the world save literally records

        minecraft:tconstruct.smeltery_controller     what is placed
        tconstruct:smeltery_controller               what JEI calls the machine

    and the two can never compare equal: a Smeltery Controller you are standing next to
    reads as "buildable". So a dotted path in the MINECRAFT namespace also gets indexed
    with its first segment promoted to a namespace. No vanilla registry name contains a
    dot, which is what makes this unambiguous -- and `agricraft:tile.crop` is left alone,
    because an id that already names its mod is not guessing at anything.

    DO NOT extend this to invent a modid out of the remainder. `tile.woot_anvil` aliases
    to the useless `tile:woot_anvil` and that is deliberate: nothing in the id says Woot
    owns it, 9 of the 29 dotted ids in the reference save are that shape, and a guess
    here would be fabricated evidence rather than a sighting. Overrides in machines.json
    are the answer for those.
    """
    norm = normalise_block(key)
    ns, _, path = norm.partition(":")
    if not path:
        ns, path = "minecraft", norm     # a hand-written override may omit the namespace
    if ns != "minecraft" or "." not in path:
        return (norm,)
    modid, _, rest = path.partition(".")
    if not modid or not rest:
        return (norm,)
    # Normalise again: the state suffix sits on the END of the legacy path, so
    # `minecraft:mod.machine_idle` has to reach `mod:machine`, not `mod:machine_idle`.
    return (norm, normalise_block("%s:%s" % (modid, rest)))


def index_ids(keys):
    """{normalised id: the id as recorded} for membership tests that ignore state suffixes.

    Shared with generators.py, which asks the same question of the same `placed` map.
    The value is always the VERBATIM id so evidence can quote what the save actually says
    rather than a form this module invented.
    """
    out = {}
    for k in keys:
        for form in match_forms(k):
            out.setdefault(form, k)
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


def mod_name(graph, uid):
    """Which mod owns a category, for grouping and display.

    JEI's own `getModName()` when the dump carried one, and only then the uid's first
    token. The guess is wrong whenever a uid does not begin with its modid, which produced
    one-category "mods" called `foregoing`, `safe` and `soulbinder` for Industrial
    Foregoing, Extreme Reactors and enderiomachines.

    DO NOT feed this to `same_mod`. That compares REGISTRY modids and this is a display
    name: "Industrial Foregoing" cannot match `industrialforegoing:plant_gatherer`, and
    swapping them would break machine identification, which is currently correct.
    """
    known = getattr(graph, "category_mods", None) or {}
    return known.get(uid) or (_tokens(uid) or [""])[0]


def resolve(graph, placed=None, stock=None, catalysts=None, overrides=None,
            no_machine=()):
    """Return {category_uid: (state, evidence)} for every category in the graph."""
    return {uid: (info["state"], info["why"])
            for uid, info in describe(graph, placed, stock, catalysts, overrides,
                                      no_machine).items()}


def describe(graph, placed=None, stock=None, catalysts=None, overrides=None,
             no_machine=()):
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
    # `if v` on both: a count of zero is not a sighting, and reporting "placed: X" for a
    # block the scan counted none of would be a false claim. generators.resolve has always
    # filtered; this did not.
    placed_index = index_ids(k for k, v in placed.items() if v)
    stock_index = index_ids(k for k, v in stock.items() if v)

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
            "mod": mod_name(graph, uid),
            "recipes": counts.get(uid, 0),
            "candidates": [],
            # Always present, even on the paths that never judge a candidate (manual
            # override, hand crafting, nothing identified): the machine page reads this
            # for every category and a missing key is a 500, not an empty list.
            "candidate_states": [],
            "manual": uid in overrides,
        }
        out[uid] = rec

        if uid in overrides:
            rec.update(state=overrides[uid], why="manual override")
            continue
        if uid in ALWAYS_AVAILABLE or is_hand_crafting(uid):
            rec.update(state=HAVE, why="no machine needed")
            continue
        if needs_no_machine(uid, no_machine, getattr(graph, "dump_schema", 0)):
            # Distinguished from the line above in the evidence, not the state: both are
            # ungated, but "no machine needed" on a bee category is a claim about how
            # breeding works and the reader should be able to check it.
            rec.update(state=HAVE, why="no machine needed (bred, grown or laid)")
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

        # Every candidate judged, not just the winner. "Smelting is done in more than
        # just the controller" is true of a lot of categories, and a page that shows one
        # verdict hides the three other blocks that would also do. See #27.
        verdicts = [(rank, state, why, c) for c, (rank, state, why)
                    in ((c, _candidate_verdict(graph, c, placed_index, stock_index))
                        for c in cands)]
        rec["candidate_states"] = [{"key": c, "state": s, "why": w}
                                   for _r, s, w, c in verdicts]
        # min() over (rank, position) keeps the old order of DIRECTNESS: a placed block
        # third in the list beats one merely in stock at the top, because standing next
        # to a machine is stronger evidence than owning its item.
        _rank, state, why, _c = min(
            (v + (i,) for i, v in enumerate(verdicts)),
            key=lambda v: (v[0], v[4]))[:4]
        rec.update(state=state, why="%s%s" % (why, caveat))
    return out


# Evidence ordered by how direct it is; the numbers exist only to rank verdicts.
_PLACED, _IN_STOCK, _CRAFTABLE, _NO_ROUTE = range(4)


def _candidate_verdict(graph, key, placed_index, stock_index):
    """(rank, state, evidence) for ONE candidate machine item."""
    norm = normalise_block(key)
    if norm in placed_index:
        return _PLACED, HAVE, "placed: %s" % placed_index[norm]
    if norm in stock_index:
        return _IN_STOCK, HAVE, "in stock: %s" % stock_index[norm]
    # `producers_any_variant`, not `producers`. A catalyst is a claim about an ITEM, not
    # about one particular NBT state of it, and at schema 3 the recipes for a machine
    # that carries its level or augments in NBT all output a discriminated key while
    # catalysts.json still names the bare one. Asking the narrow question put 16 Thermal
    # Expansion categories and 3 Botania flowers into "no route" for machines that are
    # plainly craftable. See #28.
    if graph.producers_any_variant(key):
        # Name the variant that is actually craftable when it differs from the catalyst,
        # so the evidence stays checkable rather than asserting a route to a key that has
        # no producers of its own.
        shown = (key if graph.producers(key)
                 else "%s (as %s)" % (key, graph.variants_of(key)[0]))
        return _CRAFTABLE, BUILDABLE, "craftable: %s" % shown
    # Last resort: the same registry name at a different metadata value, and ONLY when the
    # pack registered no name for this meta. A tool that selects its mode with damage gets
    # catalogued by JEI under the mode's meta and crafted only at meta 0, and reporting
    # "no route" for a toolbox the player can make is a falsehood, not a caveat.
    #
    # DELIBERATELY BELOW the exact and NBT-variant checks, and there is a test for that
    # order: reordering to exact -> meta -> NBT keeps the suite green while making
    # `thermalexpansion.pulverizer` name a Redstone Furnace as its route, which is #28.
    # The evidence names the variant, because meta usually means a different item.
    # See `Graph.meta_sibling_made` for the unnamed-meta gate and what it measured.
    sibling = graph.meta_sibling_made(key)
    if sibling:
        return _CRAFTABLE, BUILDABLE, "craftable: %s (as %s)" % (key, sibling)
    return _NO_ROUTE, UNAVAILABLE, "no route to %s" % key


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


def load_document(path):
    """machines.json as a dict. Missing or corrupt reads as empty rather than raising."""
    if not path or not os.path.exists(path):
        return {}
    with open(path) as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    return doc if isinstance(doc, dict) else {}


def load_overrides(path):
    doc = load_document(path)
    raw = doc.get("overrides", doc)
    if not isinstance(raw, dict):
        return {}
    return {k: v for k, v in raw.items() if v in STATES}


def load_no_machine(path):
    """Extra category uids the user declares need no machine. See NO_MACHINE_PATTERNS."""
    extra = load_document(path).get("no_machine")
    return [str(u) for u in extra] if isinstance(extra, list) else []


def save_overrides(path, overrides):
    """Rewrite the file, carrying the hand-edited `no_machine` list across.

    Read-modify-write rather than a fresh document, because a blind rewrite would delete
    that list the first time anyone clicked a state button. But only the KNOWN keys are
    carried: the earliest format let the whole document be the overrides map, and
    preserving unrecognised top-level keys would leave those legacy entries in the file
    forever, read by nothing and contradicting what the UI shows.
    """
    # Read BEFORE opening for write: `open(path, "w")` truncates, so reading inside the
    # block would carry an empty list across every time.
    keep = load_no_machine(path)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as fh:
        json.dump({
            "_comment": "Manual machine availability. `overrides` values: have | "
                        "buildable | unknown | unavailable, and win over anything "
                        "auto-detected. `no_machine` lists category uids that need no "
                        "machine at all, for production driven by a creature or by a "
                        "structure JEI does not catalyse.",
            "overrides": overrides,
            "no_machine": keep,
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


def mod_state_counts(info):
    """{mod display name: {state: how many of its categories are in that state}}.

    THE CROSS-TAB THE MACHINES PAGE FILTERS ON, computed here rather than in the browser.
    `MACHINES_JS` used to walk all 503 rendered rows and build this itself on every
    keystroke, which put a domain fact in a place the Python suite cannot reach: #16 and
    #32 were both found by hand after shipping, and #32 had to be covered by a checked-in
    browser audit because there was nothing here to unit-test.

    A cross-tab and not a list of orderings: the page's state chips are a multi-select, so
    the count a mod shows is the sum over whichever states are switched on, and 77 mods by
    4 states is 2,609 bytes of JSON -- 0.37% of the 713 KB page -- against 16 orderings for
    every subset of the states.
    """
    counts = {}
    for rec in info.values():
        counts.setdefault(rec["mod"], {})
        by_state = counts[rec["mod"]]
        by_state[rec["state"]] = by_state.get(rec["state"], 0) + 1
    return counts


def state_totals(counts):
    """`{state: how many categories}` summed out of a `mod_state_counts` cross-tab.

    So the machines page has ONE source for its per-state numbers. The chip labels were
    rendered from `summarise` while the browser recomputed the same figures off the
    cross-tab, which is the shape of bug this whole change exists to remove: two
    derivations of one number, free to disagree the moment you interact with the page.

    `summarise` still exists for the CLI, which holds `resolve`'s two-tuples rather than
    `describe`'s records. `tests/test_machines` asserts the two agree on a real graph.
    """
    totals = dict.fromkeys(STATES, 0)
    for by_state in counts.values():
        for state, n in by_state.items():
            totals[state] = totals.get(state, 0) + n
    return totals


def mod_order(counts):
    """Mods in the order the dropdown lists them: most categories first, then by name.

    THE ONE PLACE ALPHABETICAL ORDER IS DECIDED, and it has to be one place because the
    two languages do not agree about it. Measured on the reference pack: Python's `sorted`
    is codepoint order and JavaScript's `localeCompare` is locale-aware, and applied to the
    77 mod names they disagree at every position -- `AE2 Unofficial Extended Life` leads in
    one and `AbyssalCraft` in the other, and every lowercase modid (`aether_legacy`,
    `agricraft`) sinks to the bottom in Python and sorts inline in JS.

    WHERE THAT SHOWED, precisely: the count term dominates, so the two only diverged inside
    a TIE -- and once a state filter narrows, most of the list is one tie. Filtering to
    `no route` leaves 74 of 77 mods at zero, and the browser re-alphabetised all 74 by a
    rule written down nowhere in Python. They now keep this order, which is the same rule
    the live group above them follows.

    `casefold`, not the raw string: `aether_legacy` belongs next to `Advent of Ascension`,
    not after `Woot`. The client sorts by the RANK this order assigns and never compares a
    name, so there is nothing left to disagree about. See `server.machines_page`.
    """
    return sorted(counts, key=lambda mod: (-sum(counts[mod].values()), mod.casefold()))
