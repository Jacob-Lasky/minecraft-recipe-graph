"""Category -> machine mapping, read from the dump mod's catalysts.json.

This is JEI's own "made in" list, so it is authoritative in a way nothing else here is.
Without it, machine availability has to guess the machine from the category's display
title, and a JEI title is frequently the recipe TYPE rather than the machine: "Casting" is
made in a Casting Table, "Smelting" in a Smeltery Controller, "Cover Crafting" in nothing
at all. That guess failed on 343 of 521 categories in the reference pack.

Format, written by DumpCommand.writeCatalysts:

  {"tconstruct.casting_table": ["tinkersconstruct:casting_table"],
   "GrindingBall": ["enderio:block_sag_mill"]}

Ids arrive in JEI's order, primary machine first, and that order is preserved -- it decides
which name a "machines to build" list shows.
"""

import json
import os

from ..model import norm_key


def load(path):
    """{category uid: [item keys]}. Missing or malformed file yields {}."""
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    if not isinstance(doc, dict):
        return {}

    out = {}
    for uid, ids in doc.items():
        if isinstance(ids, str):
            ids = [ids]
        keys = []
        for raw in ids or ():
            key = _to_key(raw)
            if key and key not in keys:
                keys.append(key)
        if keys:
            out[str(uid)] = keys
    return out


def _to_key(raw):
    """`mod:item` or `mod:item:meta` -> a canonical key.

    The mod writes meta as a trailing segment, so the meta has to be split off before
    norm_key sees it -- otherwise `techreborn:foo:3` is treated as an id with no meta and
    never matches the `techreborn:foo:3` the rest of the graph uses.
    """
    raw = str(raw or "").strip()
    if not raw:
        return None
    parts = raw.split(":")
    if len(parts) >= 3 and (parts[-1].isdigit() or parts[-1] == "*"):
        tail = parts[-1]
        base = ":".join(parts[:-1])
        return norm_key(base, 32767 if tail == "*" else int(tail))
    return norm_key(raw)


def find(instance_dir):
    path = os.path.join(instance_dir, "mc-recipe-dump", "catalysts.json")
    return path if os.path.exists(path) else None
