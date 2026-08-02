"""Which Modular Machinery machine a blueprint builds, from the dump mod's
machine_names.json.

WHAT THIS IS FOR. All 261 `modularmachinery:itemblueprint` variants share one display name,
"Machine Blueprint", because that is genuinely what the game returns for the item. Only the
NBT says which machine it builds, and the dump reduces NBT to an opaque digest on purpose.
So a plan for any multiblock reads

    -- you still need --
                   1  Machine Blueprint

which is 1 of 261 possibilities, and the blueprint is exactly the roadblock the player
needs named. (#55)

DERIVING IT FROM THE RECIPE THE BLUEPRINT FEEDS WAS MEASURED AND REJECTED. On the current
graph 255 of the 261 blueprints are consumed by a recipe with exactly one distinct output,
so the mapping looks 1:1 and looks safe. It is not: across the whole graph 1,536 keys sharing
a display name with an NBT sibling have exactly one distinct output, and a reagent container
(`bloodmagic:blood_tank:12` -> "Rite of Super-Enchanting"), a gene template and an enchanted
book are all in that set while designating nothing. "Consumed by a recipe that makes exactly
one thing" does not mean "designates that thing", and no structural signal separates them,
because the distinction is Modular Machinery's semantics rather than the graph's shape.

Format, written by DumpCommand.writeMachineNames:

  {"machines":   {"modularmachinery:dragonfire_crucible": "Dragonfire Crucible"},
   "blueprints": {"modularmachinery:itemblueprint#010c58f252c0":
                  "modularmachinery:dragonfire_crucible"}}

TWO MAPS, KEPT APART, because they go stale on different clocks. A blueprint key holds an
NBT digest and so changes with every dump that moves the digest; a machine's registry name
does not, and `multiblocks` already links `modularmachinery.recipes.<reg>` and
`modularmachinery:<reg>_controller` by exactly that string. Flattening them to
{blueprint key: display name} would throw the durable half away to save a dict.
"""

import json
import os

from . import dump_meta


def load(path):
    """`({machine id: name}, {blueprint key: machine id})`. Both empty if absent."""
    if not path or not os.path.exists(path):
        return {}, {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}, {}
    if not isinstance(doc, dict):
        return {}, {}
    return _strings(doc.get("machines")), _strings(doc.get("blueprints"))


def _strings(raw):
    """A {str: non-empty str} view of `raw`, or {}."""
    if not isinstance(raw, dict):
        return {}
    return {str(k): v.strip() for k, v in raw.items()
            if isinstance(v, str) and v.strip()}


def find(instance_dir, dump_dir=None):
    path = os.path.join(dump_meta.dir_for(instance_dir, dump_dir), "machine_names.json")
    return path if os.path.exists(path) else None
