"""Display names as JEI itself renders them, from the dump mod's names.json.

WHY THIS EXISTS, having been written by the mod and read by nothing for four versions.

`items.csv` is the pack's own export and covers 32,861 items, which was enough while
every key was `mod:item` or `mod:item:meta`. It cannot cover an NBT-DISCRIMINATED key: a
Forest drone and a Meadows drone are one row there, because the file has no idea the
distinction exists. names.json is written from `ItemStack.getDisplayName()` on the exact
stack the recipe used, keyed by the discriminated id, so it is the only source that can
name one. That is what turns `forestry:bee_drone_ge#a3f19c02b8d1` back into
"Forest Drone".

It does NOT replace items.csv. items.csv knows every item in the registry; this knows
only the ones some recipe mentioned. So it is a supplement, and it LOSES to nothing --
it is applied with setdefault, leaving any name already loaded in place.
"""

import json
import os


def find(instance_dir):
    path = os.path.join(instance_dir, "mc-recipe-dump", "names.json")
    return path if os.path.exists(path) else None


def load(path):
    """{key: display name}. Missing or malformed reads as empty rather than raising."""
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    if not isinstance(doc, dict):
        return {}
    return {str(k): str(v) for k, v in doc.items()
            if isinstance(v, str) and v.strip()}
