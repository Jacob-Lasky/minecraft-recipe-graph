"""Ore dictionary membership.

Two accepted sources, in preference order:

1. `oredict.json` written by the mc-recipe-dump mod (complete, straight from
   `OreDictionary.getOreNames()`).
2. `crafttweaker.log` after running `/ct oredict` in game (lines beginning
   "Ore entries for").

Without one of these, every `ore:` input stays unresolved and shows up as a raw
leaf. That is why the dump mod emits the oredict itself rather than relying on the
player remembering to run a chat command.

Both sources describe the FINAL registry, after every script has run. What a script
TOOK OUT along the way is a separate reading of the same log -- see
`removals_from_crafttweaker_log`, which #168 needs because a membership the pack
deleted is still evidence about what the pack thinks a block is.
"""

import json
import re

ORE_LINE = re.compile(r"Ore entries for\s*<?(?:ore(?:Dict)?:)?([A-Za-z0-9_.\-]+)>?\s*[:=]\s*(.*)")
ENTRY = re.compile(r"<([^>]+)>")

# CraftTweaker announces every `<ore:X>.remove(...)` a script runs. The subject is the
# item's DISPLAY NAME rather than its key -- "Removing Rhenium Ore from ore dictionary
# entry oreRhenium" -- which is the whole reason `removals_from_crafttweaker_log` returns
# `(label, group)` and not `(key, group)`. See that function for what the pair can and
# cannot be asked.
REMOVAL_LINE = re.compile(r"Removing (.+?) from ore dictionary entry (\S+)\s*$")


def _norm_entry(raw):
    """`<nuclearcraft:compound:7>` / `nuclearcraft:compound:7` -> canonical key."""
    from ..model import norm_key

    raw = raw.strip().strip("<>")
    if not raw:
        return None
    raw = raw.split("*")[0].strip()  # drop `* 4` stack sizes
    parts = raw.split(":")
    meta = 0
    if len(parts) >= 3 and (parts[-1].isdigit() or parts[-1] == "*"):
        meta = 32767 if parts[-1] == "*" else int(parts[-1])
        raw = ":".join(parts[:-1])
    return norm_key(raw, meta)


def from_json(path):
    with open(path) as fh:
        doc = json.load(fh)
    out = {}
    for ore, members in doc.items():
        keys = [k for k in (_norm_entry(m) for m in members) if k]
        if keys:
            out[ore] = keys
    return out


# Ore-name prefix -> the English word it becomes in a display name. Used ONLY by
# the heuristic guesser below, never for exact resolution.
_PREFIX_WORD = {
    "ingot": "Ingot", "dust": "Dust", "nugget": "Nugget", "plate": "Plate",
    "gear": "Gear", "rod": "Rod", "stick": "Rod", "ore": "Ore", "block": "Block",
    "gem": "Gem", "crystal": "Crystal", "shard": "Shard", "chunk": "Chunk",
    "log": "Log", "plank": "Planks", "slab": "Slab", "dye": "Dye", "wire": "Wire",
    "foil": "Foil", "cluster": "Cluster", "clump": "Clump", "coin": "Coin",
}

_SPLIT = __import__("re").compile(r"(?<=[a-z0-9])(?=[A-Z])")


def guess_from_names(ore_names, names):
    """Best-effort oredict membership inferred from display names.

    THIS IS A FALLBACK, NOT GROUND TRUTH. It exists so the tool is usable before
    anyone runs the dump mod or `/ct oredict`; the real oredict must override it.
    Report it as guessed wherever it surfaces -- silently mixing guesses with real
    membership would make wrong shopping lists look authoritative.

    Works because oredict members genuinely share a display name across mods
    (three mods' "Copper Ingot" really are interchangeable), so matching on the
    localized name recovers most of the common metal/dust/gem entries.
    """
    by_label = {}
    for key, label in names.items():
        by_label.setdefault(label.lower(), []).append(key)

    out = {}
    for ore in ore_names:
        prefix = None
        for p in sorted(_PREFIX_WORD, key=len, reverse=True):
            if ore.startswith(p) and len(ore) > len(p):
                prefix = p
                break
        if not prefix:
            continue
        rest = ore[len(prefix):]
        words = " ".join(w for w in _SPLIT.split(rest) if w).strip()
        if not words:
            continue
        word = _PREFIX_WORD[prefix]
        candidates = [
            "%s %s" % (words, word),      # Iron Ingot
            words,                        # Borax  (NuclearCraft names it bare)
            "%s of %s" % (word, words),   # Block of Iron
            "%s %s" % (word, words),      # Block Iron
        ]
        if word == "Planks":
            candidates.insert(0, "%s Wood Planks" % words)
        for cand in candidates:
            hit = by_label.get(cand.lower())
            if hit:
                out[ore] = list(hit)
                break
    return out


def removals_from_crafttweaker_log(path):
    """`{(display name, ore group)}` the pack's scripts DELETED from the oredict.

    WHY A DELETED MEMBERSHIP IS WORTH READING. `from_crafttweaker_log` above reports the
    registry as it ends up, which is the right answer for "what satisfies `<ore:oreX>`" and
    the wrong one for "what does the pack think this block IS". MeatballCraft registers
    `contenttweaker:sub_block_holder_1:8` into `oreRhenium` and then removes it again
    (`scripts/OreDictionary.zs:63`), so the finished registry shows a Rhenium Ore in no ore
    group at all -- and #117's test, display name AND a shared `ore*` group, cannot see that
    it is the same rock as `contenttweaker:rhenium_ore`. The removal line is the pack SAYING
    they were the same rock. Read back, never guessed, the same standing rule as
    `Graph.world_ores` and `dimensions.load_planet_defs`.

    THE SUBJECT IS A LABEL, NOT A KEY, AND THAT BOUNDS WHAT THIS CAN ANSWER. CraftTweaker
    logs "Removing Rhenium Ore from ore dictionary entry oreRhenium" -- the display name,
    which is precisely the thing that does not discriminate between the twin and the real
    block. So a pair here supports exactly one claim: SOME key called <label> was once in
    <group>. It can never name which. `dimensions.shadow_ores` is the only caller and it is
    sound there only because it already knows the anchor is still in the group, so the
    removed one has to be a different key wearing the same name. DO NOT use a pair from
    here as though it identified a key.

    NARROW BY MEASUREMENT, NOT BY HOPE. The reference pack logs 134 removals, of which just
    3 name an `ore*` group: oreRhenium, oreTartarite, oreUranium. The other 131 are the
    ingot/dust/plate unification in `scripts/TheGreatOreCleanse.zs` and cannot reach an ore
    gate. Callers still filter with `is_world_ore_group`; this returns the lot because
    filtering is the caller's question, not the reader's.
    """
    out = set()
    try:
        fh = open(path, encoding="utf-8", errors="replace")
    except OSError:
        # Same degradation as a missing planetDefs: no removals means the #117 test runs on
        # the finished registry alone, which is the pre-#168 behaviour rather than a break.
        return out
    with fh:
        for line in fh:
            m = REMOVAL_LINE.search(line.rstrip("\n"))
            if m:
                out.add((m.group(1).strip(), m.group(2)))
    return out


def from_crafttweaker_log(path):
    out = {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if "Ore entries for" not in line:
                continue
            m = ORE_LINE.search(line)
            if not m:
                continue
            ore = m.group(1)
            body = m.group(2)
            members = ENTRY.findall(body) or [p for p in body.split(",")]
            keys = [k for k in (_norm_entry(x) for x in members) if k]
            if keys:
                out.setdefault(ore, [])
                for k in keys:
                    if k not in out[ore]:
                        out[ore].append(k)
    return out
