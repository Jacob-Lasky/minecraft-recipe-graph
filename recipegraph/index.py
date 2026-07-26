"""Build a unified recipe Graph from whichever sources are available."""

import os
import sys

from .model import Graph
from .names import find_items_csv, load_items_csv
from .sources import hei_dump, jar_json, oredict


def build(instance_dir, hei_path=None, quiet=False, no_guess=False):
    def say(msg):
        if not quiet:
            print(msg, file=sys.stderr)

    g = Graph()

    csv_path = find_items_csv(instance_dir)
    if csv_path:
        g.names = load_items_csv(csv_path)
        say("names: %d items from %s" % (len(g.names), os.path.relpath(csv_path, instance_dir)))
    else:
        say("names: items.csv NOT FOUND -- output will show raw ids")

    # Ore dictionary: the mod's dump wins, /ct oredict log is the fallback.
    od_json = os.path.join(instance_dir, "mc-recipe-dump", "oredict.json")
    ct_log = os.path.join(instance_dir, "crafttweaker.log")
    if os.path.exists(od_json):
        g.ore_members = oredict.from_json(od_json)
        say("oredict: %d entries from mod dump" % len(g.ore_members))
    elif os.path.exists(ct_log):
        g.ore_members = oredict.from_crafttweaker_log(ct_log)
        if g.ore_members:
            say("oredict: %d entries from crafttweaker.log" % len(g.ore_members))
        else:
            say("oredict: crafttweaker.log has no '/ct oredict' output -- "
                "ore: inputs will stay unresolved (see ASK-JAKE.md)")
    else:
        say("oredict: no source found -- ore: inputs will stay unresolved")

    mods_dir = os.path.join(instance_dir, "mods")
    if os.path.isdir(mods_dir):
        n = 0
        for recipe in jar_json.extract(mods_dir):
            g.add(recipe)
            n += 1
        say("jar_json: %d crafting recipes from mod jars" % n)
    else:
        say("jar_json: no mods/ dir at %s" % mods_dir)

    hei_path = hei_path or os.path.join(instance_dir, "mc-recipe-dump", "recipes.ndjson")
    if os.path.exists(hei_path):
        n = 0
        for recipe in hei_dump.extract(hei_path):
            g.add(recipe)
            n += 1
        say("hei_dump: %d recipes (machine recipes included)" % n)
    else:
        say("hei_dump: %s not present -- machine recipes (NuclearCraft chemistry, "
            "Modular Machinery, ...) are MISSING from this graph" % hei_path)

    # Fill oredict gaps by inference, but only for ore names recipes actually use,
    # and never overwriting a real entry.
    referenced = g.referenced_ores()
    missing = sorted(referenced - set(g.ore_members))
    if missing and not no_guess:
        guessed = oredict.guess_from_names(missing, g.names)
        for ore, members in guessed.items():
            g.ore_members[ore] = members
            g.ore_guessed.add(ore)
        say("oredict: guessed %d of %d missing entries from display names "
            "(HEURISTIC -- run the dump mod or `/ct oredict` for exact membership)"
            % (len(guessed), len(missing)))
    elif missing:
        say("oredict: %d referenced ore names have no membership" % len(missing))

    say("graph: %d recipes, %d produced item keys, %d/%d oredict resolved"
        % (len(g.recipes), len(g.by_output),
           len(referenced & set(g.ore_members)), len(referenced)))
    return g


def coverage(g):
    """Quick stats to tell how complete the graph is."""
    by_source = {}
    by_cat = {}
    for r in g.recipes:
        by_source[r.source] = by_source.get(r.source, 0) + 1
        by_cat[r.category] = by_cat.get(r.category, 0) + 1
    return {
        "recipes": len(g.recipes),
        "produced_keys": len(g.by_output),
        "named_items": len(g.names),
        "oredict_entries": len(g.ore_members),
        "by_source": by_source,
        "by_category": dict(sorted(by_cat.items(), key=lambda t: -t[1])[:25]),
    }
