"""Extract the contents of an AE2 network from world save region files.

Reads AE2/NAE2/ExtraCells/ThaumicEnergistics storage cells out of Anvil region
files and aggregates them into an item->count map. Offline and read-only: it
never writes to the save, so it is safe to run against a live server.

Counts come from the cell's `Cnt` long, NOT the ItemStack `Count` byte -- Count
is capped at 127 and is meaningless for cell contents. DO NOT switch to Count.

Usage:
  python3 ae2_inventory.py [--sample] [--json out.json] region/*.mca
"""

import argparse
import collections
import json
import sys

from anvil_nbt import iter_region, tile_entities

# Tile entities that can physically hold a storage cell.
CELL_HOLDERS = (
    "appliedenergistics2:drive",
    "appliedenergistics2:chest",
    "appliedenergistics2:io_port",
    "appliedenergistics2:cell_workbench",
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
    """Return (kind, key, count) for one `#N` cell entry, or None if unusable."""
    if not isinstance(entry, dict):
        return None
    # `Cnt` is the authoritative amount; fall back to Count only if absent.
    count = entry.get("Cnt")
    if count is None:
        count = entry.get("Count", 0)
    if not isinstance(count, int) or count <= 0:
        return None

    if "FluidName" in entry:
        return "fluid", entry["FluidName"], count
    if "id" in entry and isinstance(entry["id"], str):
        dmg = entry.get("Damage", 0)
        key = entry["id"] if not dmg else "%s:%s" % (entry["id"], dmg)
        # An NBT-bearing stack is a distinct item for crafting purposes; flag it
        # so the graph solver does not treat it as interchangeable with the base.
        if entry.get("tag"):
            key += " (+nbt)"
        return "item", key, count
    if "Aspect" in entry:
        return "essentia", str(entry["Aspect"]), count
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
