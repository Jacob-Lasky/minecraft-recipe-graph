"""ProjectE EMC values, from the dump mod's emc.json.

WHAT THIS IS FOR. Reported against `erebus:materials` (Exoskeleton Plate): "this is an item
that can only be obtained initially from a drop. it shows dungeon_drop or battle_tower but
it has EMC value and it SHOULD already be in my EMC network. If it does, then that means it
is 'available'." The graph had no concept of EMC at all, so every item whose only route is a
drop terminated on a pseudo-item like `contenttweaker:dungeon_drop` -- the literal truth of
the recipe, and the wrong answer for a player with a working transmutation network. (#50)

PACK DATA ONLY, AND THAT SPLIT IS THE DESIGN. An EMC value is a property of the pack: it
changes when the pack changes and not otherwise, so it belongs in the graph. What the player
has LEARNED and how much EMC they hold is world state, changes constantly, and belongs in the
have file beside stock and placed blocks -- `ae2_inventory` reads it out of playerdata. Bake
a player's knowledge into a graph and it is wrong the moment they learn something; keep them
apart and the route opens by itself on the next rescan. Same split #112 drew between
`graph.dimension_ores` and a visited dimension.

Format, written by DumpCommand.writeEmc, keyed exactly as recipes.ndjson and names.json key
a stack (`id[:meta][#digest]`) so the three join without a convention:

  {"erebus:materials:6": 2048, "minecraft:diamond": 8192}

ONLY POSITIVE VALUES ARE WRITTEN, and the reader keeps that property. ProjectE answers 0 for
"no EMC, cannot be transmuted", and the mod's bridge also answers 0 when the lookup throws --
so absence always means "no evidence of a transmutation route", never "there is probably
one". A route asserted through EMC that the pack has actually disabled would be worse than
the dead end it replaces, which is #50's own stated worst case.
"""

import json
import os

from ..model import canonical_item_key
from . import dump_meta


def load(path):
    """{discriminated key: EMC value}. Missing, malformed or non-positive entries drop."""
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
    for key, value in doc.items():
        # bool is an int in python and `True` would price something at 1 EMC. Nothing
        # writes one today; the check costs a clause and removes the question.
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            # CANONICALISED FOR THE SAME REASON `sources/dump_names` IS (#253), and with the
            # same measured caveat: ZERO of this file's 15,216 keys need it today, which is
            # a fact about the reference dump and not about the format. The mod writes what
            # the game stores, so the day a ProjectE value lands on an any-damage stack it
            # arrives as the literal `:32767` and this map stops joining to the graph --
            # silently, because a missing EMC value looks exactly like an item ProjectE
            # does not price. `min` over EMC feeds the cost table, so the failure would be
            # a wrong price rather than a visible gap.
            out[canonical_item_key(key)] = value
    return out


def find(instance_dir, dump_dir=None):
    path = os.path.join(dump_meta.dir_for(instance_dir, dump_dir), "emc.json")
    return path if os.path.exists(path) else None
