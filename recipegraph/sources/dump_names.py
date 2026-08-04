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

from ..names import clean_label
from . import dump_meta


def find(instance_dir, dump_dir=None):
    path = os.path.join(dump_meta.dir_for(instance_dir, dump_dir), "names.json")
    return path if os.path.exists(path) else None


def load_with_count(path):
    """{key: display name}, plus how many entries the FILE held before any were dropped.

    THERE IS NO `load(path)` WRAPPER RETURNING ONLY THE MAP. There was, it became the map's
    sole remaining spelling once #194 moved `index.build` here, and a function whose only
    callers are its own tests is a function nobody can tell is dead. Callers that want just
    the map write `load_with_count(path)[0]`, which says at the call site that a count was
    available and declined.

    TWO NUMBERS BECAUSE THE MAP'S LENGTH IS NOT THE FILE'S LENGTH, and the difference is
    exactly what would make #194's completeness check lie. `clean_label` returns None for a
    name that was only formatting codes, so the MAP legitimately holds fewer entries than the
    mod wrote -- 14,425 of the reference pack's names arrive with section signs and some
    are nothing else. Comparing summary.json's declared count against the cleaned map would
    then report a truncated file on every healthy dump, which is a check that gets switched
    off in a week. The RAW count is the one summary.json's `names` is comparable with.

    Returned together, from ONE parse, rather than offered as a second `count(path)`
    helper: names.json is ~30 MB on the reference pack, and a caller that wants both would
    otherwise read and parse it twice.

    FORMAT CODES ARE STRIPPED, exactly as `load_items_csv` does for the pack's own
    export. `getDisplayName()` returns what the game DRAWS, section signs and all, so
    14,425 of the 340,324 names on the reference pack arrived as `§3Abyssalnite Axe`
    or `Borax Solution Cell§r`. Rendered outside Minecraft those are literal
    characters: they show in search results, they sort ahead of every letter, and a
    leading code hides the first word of the name behind punctuation.

    @return (names, raw entry count) -- the count is None when there was no file to count,
            which is not the same as a file holding zero entries.
    """
    if not path or not os.path.exists(path):
        return {}, None
    with open(path, encoding="utf-8", errors="replace") as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}, None
    if not isinstance(doc, dict):
        return {}, None
    out = {}
    for key, value in doc.items():
        if not isinstance(value, str):
            continue
        # clean_label returns None for a name that was ONLY formatting, which is not a
        # name; dropping it lets the usual fallbacks render the key instead.
        #
        # DO NOT also drop unlocalized lang keys here (`tile.null.name`, 1,429 of them).
        # It looks like the same cleanup and it is not: a dropped key is absent from
        # `graph.names`, `Graph.labels` is built from `names`, and search is built from
        # `labels`, so the item stops being findable at all. `model.is_unlocalized` plus
        # `Graph.relabel_unlocalized` handle those by REPLACING the label and keeping the
        # key. A format-only label can be dropped precisely because that path has other
        # fallbacks; an item losing its only index entry has none. See #52.
        label = clean_label(value)
        if label:
            out[str(key)] = label
    return out, len(doc)
