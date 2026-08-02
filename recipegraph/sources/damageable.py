"""Which items use their metadata as DURABILITY, from the dump mod's damageable.json.

WHAT THIS IS FOR. On the reference graph 46 keys carry the display name "Iron Axe" --
`minecraft:iron_axe`, `:1`, `:2` ... `:250`, plus the 32767 wildcard -- because a damageable
item's meta is its damage, so every durability value the axe has ever been seen at becomes
its own key with its own row and its own stock count. 804 families do that, covering 9,579
keys, and almost every one of them has stock on exactly ONE meta. Reported as: "there are
like 25 iron axes and it shows i have 1. shouldn't they be combined?" (#118)

THE STRUCTURAL DETECTOR IS WRONG AND #110 IS THE PROOF. "Same base id, same display name,
different meta, therefore one item" also matches `chisel:lapis:0` through `:8` -- nine
decorative blocks that are genuinely nine different blocks, all called Lapis Lazuli Block --
and `ebwizardry:spell_book`, whose 286 metas are 286 different spells. Both are the largest
families in the measurement, so a rule that got them wrong would do most of its damage
where it was most confident.

What separates them is whether the ITEM is damageable, which lives in the item registry and
in nothing a dump could previously see. That is why this needed a mod change and a launch of
the game rather than a cleverer heuristic.

Format, written by DumpCommand.writeDamageable -- only items with a positive maxDamage
appear, so absence means "meta is a subtype", which is the conservative default:

  {"minecraft:iron_axe": {"d": 250, "s": false}}

BOTH FIELDS ARE READ, not just `d`. An item with durability AND subtypes cannot have its
metas collapsed, because some of them are subtypes; `s` is what lets this decline, and
carrying it means the reason is legible rather than pre-filtered away upstream.
"""

import json
import os

from ..model import norm_key
from . import dump_meta


def load(path):
    """{item key: maxDamage} for items whose meta is durability. Empty if absent.

    Keys are normalised through `norm_key` so they are spelled the way every other key in
    the graph is -- the file's own ids carry no meta, being registry names.
    """
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
    for raw, info in doc.items():
        if not isinstance(info, dict):
            continue
        max_damage = info.get("d")
        if not isinstance(max_damage, int) or max_damage <= 0:
            continue
        # An item that is both damageable and subtyped is REFUSED rather than guessed at.
        # Some of its metas are damage and some are variants and nothing here can say
        # which, so leaving the family uncollapsed keeps today's behaviour, which is noisy
        # and correct, instead of trading it for quiet and wrong.
        if info.get("s"):
            continue
        key = norm_key(str(raw).strip())
        if key:
            out[key] = max_damage
    return out


def find(instance_dir, dump_dir=None):
    path = os.path.join(dump_meta.dir_for(instance_dir, dump_dir), "damageable.json")
    return path if os.path.exists(path) else None
