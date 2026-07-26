"""Extract the contents of an AE2 network from world save region files.

Reads AE2/NAE2/ExtraCells/ThaumicEnergistics storage cells out of Anvil region
files and aggregates them into an item->count map. Offline and read-only: it
never writes to the save, so it is safe to run against a live server.

Counts come from the cell's own amount field -- `Cnt` for item and fluid cells,
`Amount` for essentia cells -- NOT the ItemStack `Count` byte. Count is capped at
127 and reads 0 in every cell format seen here, so preferring it silently drops
entire cells instead of erroring. DO NOT reorder those fields; see classify().

Usage:
  python3 ae2_inventory.py [--sample] [--json out.json] region/*.mca
"""

import argparse
import collections
import json
import sys

try:
    from .anvil_nbt import iter_region, tile_entities
except ImportError:  # run directly as a script, e.g. inside a server container
    from anvil_nbt import iter_region, tile_entities

# Tile entities that can physically hold a storage cell.
CELL_HOLDERS = (
    "appliedenergistics2:drive",
    "appliedenergistics2:chest",
    "appliedenergistics2:io_port",
    "appliedenergistics2:cell_workbench",
    # Interfaces really do hold cells in practice (found holding an essentia cell on
    # the reference network), so they are not just a pass-through block.
    "appliedenergistics2:interface",
    "ae2fc:dual_interface",
    "extracells:storage.casing",
    "nae2:exposer",
    "cellterminal:cell_terminal",
)

# Item-id substrings that identify a storage cell. Energy cells are excluded:
# `appliedenergistics2:energy_cell` stores power, not items, and has no #N keys.
CELL_MARKERS = ("storage_cell", "storage.cell", "essentia_cell", "storage.physical")


def is_cell(item_id):
    low = item_id.lower()
    if "energy_cell" in low:
        return False
    return any(m in low for m in CELL_MARKERS)


def walk_compounds(node):
    if isinstance(node, dict):
        yield node
        for v in node.values():
            yield from walk_compounds(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk_compounds(v)


def classify(entry):
    """Return (kind, key, count) for one `#N` cell entry, or None if unusable.

    The amount key differs by cell type and getting this wrong silently drops whole
    cells rather than erroring:
      * item and fluid cells  -> `Cnt`
      * essentia cells        -> `Amount`   (ThaumicEnergistics)
      * `Count` is the ItemStack byte and is 0 in every cell format seen; it is only a
        last resort and must never take priority.
    """
    if not isinstance(entry, dict):
        return None
    count = None
    for field in ("Cnt", "Amount", "Count"):
        value = entry.get(field)
        if isinstance(value, int) and value > 0:
            count = value
            break
    if count is None:
        return None

    # Aspect before id: an essentia entry has no `id`, but check explicitly so a future
    # cell format carrying both cannot be misfiled as an item.
    if "Aspect" in entry and isinstance(entry["Aspect"], str):
        return "essentia", entry["Aspect"], count
    if "FluidName" in entry:
        return "fluid", entry["FluidName"], count
    if "id" in entry and isinstance(entry["id"], str):
        dmg = entry.get("Damage", 0)
        key = entry["id"] if not dmg else "%s:%s" % (entry["id"], dmg)
        # An NBT-bearing stack is a distinct item for crafting purposes, so it must not
        # unify with the bare item. Where the NBT is a known, meaningful discriminator,
        # decode it into the key instead of an opaque marker: a Vis Pod of Perditio is a
        # genuinely different ingredient from one of Lux, and those feed the
        # item -> essentia multiblocks. Anything undecoded keeps the opaque flag so it
        # still cannot be mistaken for the base item.
        tag = entry.get("tag")
        if isinstance(tag, dict) and tag:
            aspect = tag.get("Aspect")
            if isinstance(aspect, str) and aspect:
                key += "#" + aspect.lower()
            else:
                key += " (+nbt)"
        elif tag:
            key += " (+nbt)"
        return "item", key, count
    return None


def scan(paths, sample=False):
    items = collections.Counter()
    fluids = collections.Counter()
    essentia = collections.Counter()
    stats = {"regions": 0, "chunks": 0, "holders": 0, "cells": 0, "entries": 0}
    samples = []

    for path in paths:
        stats["regions"] += 1
        for cx, cz, root in iter_region(path):
            stats["chunks"] += 1
            for te in tile_entities(root):
                tid = te.get("id")
                if not isinstance(tid, str) or tid not in CELL_HOLDERS:
                    continue
                stats["holders"] += 1
                for comp in walk_compounds(te):
                    iid = comp.get("id")
                    if not isinstance(iid, str) or not is_cell(iid):
                        continue
                    tag = comp.get("tag")
                    if not isinstance(tag, dict):
                        continue
                    stats["cells"] += 1
                    if sample and len(samples) < 3:
                        samples.append({
                            "cell": iid,
                            "pos": [te.get("x"), te.get("y"), te.get("z")],
                            "keys": sorted(tag.keys())[:12],
                            "first": {k: tag[k] for k in sorted(tag)[:4]},
                        })
                    for k, v in tag.items():
                        if not k.startswith("#"):
                            continue
                        got = classify(v)
                        if not got:
                            continue
                        kind, key, count = got
                        stats["entries"] += 1
                        {"item": items, "fluid": fluids, "essentia": essentia}[kind][key] += count

    return items, fluids, essentia, stats, samples


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("regions", nargs="+")
    ap.add_argument("--sample", action="store_true", help="dump raw cell samples")
    ap.add_argument("--json", help="write full result as JSON")
    ap.add_argument("--top", type=int, default=25)
    args = ap.parse_args()

    items, fluids, essentia, stats, samples = scan(args.regions, args.sample)

    print("== scan stats ==")
    for k, v in stats.items():
        print("  %-9s %d" % (k, v))
    print("  distinct items    %d" % len(items))
    print("  distinct fluids   %d" % len(fluids))
    print("  distinct essentia %d" % len(essentia))

    if samples:
        print("\n== raw cell samples ==")
        print(json.dumps(samples, indent=2, default=str)[:2500])

    for label, ctr in (("items", items), ("fluids", fluids), ("essentia", essentia)):
        if not ctr:
            continue
        print("\n== top %d %s ==" % (args.top, label))
        for key, n in ctr.most_common(args.top):
            print("  %14s  %s" % ("{:,}".format(n), key))

    if args.json:
        with open(args.json, "w") as fh:
            json.dump({
                "stats": stats,
                "items": dict(items),
                "fluids": dict(fluids),
                "essentia": dict(essentia),
            }, fh, indent=1, sort_keys=True)
        print("\nwrote %s" % args.json)


if __name__ == "__main__":
    main()
