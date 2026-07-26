"""Ore dictionary membership.

Two accepted sources, in preference order:

1. `oredict.json` written by the mbc-recipe-dump mod (complete, straight from
   `OreDictionary.getOreNames()`).
2. `crafttweaker.log` after running `/ct oredict` in game (lines beginning
   "Ore entries for").

Without one of these, every `ore:` input stays unresolved and shows up as a raw
leaf. That is why the dump mod emits the oredict itself rather than relying on the
player remembering to run a chat command.
"""

import json
import re

ORE_LINE = re.compile(r"Ore entries for\s*<?(?:ore(?:Dict)?:)?([A-Za-z0-9_.\-]+)>?\s*[:=]\s*(.*)")
ENTRY = re.compile(r"<([^>]+)>")


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
