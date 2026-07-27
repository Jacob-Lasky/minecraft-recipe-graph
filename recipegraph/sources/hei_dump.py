"""Recipe source: the NDJSON dump produced by the `mc-recipe-dump` mod.

This is the only COMPLETE source. It reads HEI/JEI's own recipe registry, so it
contains every recipe type any of the ~366 mods registered -- NuclearCraft
chemistry, Modular Machinery, inscribers, centrifuges, everything the player sees
in the recipe viewer. The jar_json source cannot see any of that.

Format: one JSON object per line (NDJSON, so a 100k-recipe dump streams instead of
loading whole). Written by RecipeDumpMod.java; keep the two in sync.

  {"cat":"nuclearcraft.chemical_reactor","title":"Chemical Reactor",
   "in":[[{"i":"minecraft:water_bucket","m":0,"c":1}], ...],
   "out":[{"i":"nuclearcraft:compound","m":7,"c":1}],
   "fin":[[{"f":"water","a":1000}]], "fout":[]}

`in` is a list of SLOTS, each slot a list of interchangeable stacks. That nesting
is deliberate and must be preserved -- it is where oredict/multi-input choice
lives, and flattening it would destroy the solver's ability to pick what you own.

An item stack may carry `"n"`, a digest of the NBT that decides what it IS (schema 3
and up). It becomes a `#suffix` on the key, so a Forest drone and a Meadows drone are
different ingredients -- see DumpCommand.discriminator. Absent on older dumps, which
then behave exactly as before.
"""

import json

from ..model import Ingredient, Recipe, fluid_key, norm_key
from ..names import clean_label


def _stack_key(entry):
    if "f" in entry:
        return fluid_key(entry["f"])
    if "i" not in entry:
        return None
    key = norm_key(entry["i"], entry.get("m", 0))
    # `#suffix` rather than a separate field: it is the convention the AE2 reader already
    # uses for vis pods, `model.bare_name` already renders it, and it keeps a
    # discriminated stack from ever unifying with the bare item by accident.
    nbt = entry.get("n")
    return "%s#%s" % (key, nbt) if nbt else key


def _slot_to_ingredient(slot, role="item"):
    if isinstance(slot, dict):
        slot = [slot]
    alts, qty = [], 1
    for entry in slot or []:
        key = _stack_key(entry)
        if not key:
            continue
        alts.append(key)
        qty = max(qty, int(entry.get("c", entry.get("a", 1)) or 1))
    if not alts:
        return None
    return Ingredient(alts, qty, role)


def extract(path, on_progress=None):
    stats = {"lines": 0, "recipes": 0, "skipped": 0}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            stats["lines"] += 1
            try:
                doc = json.loads(line)
            except ValueError:
                stats["skipped"] += 1
                continue

            outputs = []
            for entry in doc.get("out") or []:
                key = _stack_key(entry)
                if key:
                    outputs.append((key, max(1, int(entry.get("c", 1) or 1))))
            for entry in doc.get("fout") or []:
                key = _stack_key(entry)
                if key:
                    outputs.append((key, max(1, int(entry.get("a", 1) or 1))))
            if not outputs:
                stats["skipped"] += 1
                continue

            slots = []
            for slot in doc.get("in") or []:
                ing = _slot_to_ingredient(slot, "item")
                if ing:
                    slots.append(ing)
            for slot in doc.get("fin") or []:
                ing = _slot_to_ingredient(slot, "fluid")
                if ing:
                    slots.append(ing)
            if not slots:
                stats["skipped"] += 1
                continue

            cat = doc.get("cat", "unknown")
            stats["recipes"] += 1
            yield Recipe(
                "hei:%s:%d" % (cat, lineno), "hei_dump", outputs, slots,
                # Titles carry colour/format codes verbatim ("Wire Mill§r"), and 22
                # categories in the reference pack do. Strip them HERE so no downstream
                # consumer has to: an uncleaned title fails the machine name lookup
                # silently, which reads as "you do not own this machine".
                category=cat, machine=clean_label(doc.get("title")),
            )
            if on_progress and stats["recipes"] % 5000 == 0:
                on_progress(cat, stats)
    if on_progress:
        on_progress("done", stats)
