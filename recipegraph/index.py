"""Build a unified recipe Graph from whichever sources are available."""

import os
import sys

from .model import Graph, base_key, is_item_key
from .names import find_items_csv, load_items_csv
from .sources import catalysts as catalysts_src
from .sources import dump_meta, dump_names
from .sources import hei_dump, jar_json, oredict


def build(instance_dir, hei_path=None, quiet=False, no_guess=False,
          keep_categories=None):
    def say(msg):
        if not quiet:
            print(msg, file=sys.stderr)

    g = Graph()
    g.instance_dir = os.path.abspath(instance_dir)

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

    # Provenance first: everything else read from this directory is only as current as the
    # dump that produced it, so say which mod wrote it before reporting what it contained.
    dump_dir = os.path.join(instance_dir, "mc-recipe-dump")
    meta = dump_meta.read(dump_dir)
    say(dump_meta.describe(meta))
    g.dump_schema = meta["schema"] or 0

    # After items.csv, and with setdefault, so the pack's own export stays authoritative
    # for anything it covers. This only has to reach the keys items.csv cannot express.
    dumped_names = dump_names.load(dump_names.find(instance_dir))
    if dumped_names:
        added = 0
        for key, label in dumped_names.items():
            if key not in g.names:
                g.names[key] = label
                added += 1
        say("names: +%d from the dump that items.csv did not cover (%d discriminated "
            "by NBT)" % (added, sum(1 for k in dumped_names if "#" in k)))

    g.category_mods = dump_meta.category_mods(dump_dir)
    if g.category_mods:
        say("category mods: %d categories carry JEI's own mod name" % len(g.category_mods))
    else:
        say("category mods: summary.json has none -- the machines page will group by the "
            "first token of each category uid, which is a guess")

    cat_path = catalysts_src.find(instance_dir)
    if cat_path:
        g.catalysts = catalysts_src.load(cat_path)
        say("catalysts: %d categories have a known machine item (JEI's own \"made in\")"
            % len(g.catalysts))
    else:
        say("catalysts: catalysts.json not present -- machine identity falls back to "
            "matching category titles against item names, which misses ~2 in 3")

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

    keep = {k.lower() for k in (keep_categories or ())}
    before = len(g.recipes)
    dropped_by_cat = {}
    loops_by_cat = {}
    kept = []
    for r in g.recipes:
        if is_non_recipe(r.category, keep):
            dropped_by_cat[r.category] = dropped_by_cat.get(r.category, 0) + 1
        elif produces_nothing_new(r):
            loops_by_cat[r.category] = loops_by_cat.get(r.category, 0) + 1
        else:
            kept.append(r)
    if loops_by_cat:
        # Reported separately from the category drops above: the causes and the fixes are
        # different, and folding them together once made 8,051 wrongly-dropped crafting
        # recipes read as "info panels and anvil permutations".
        top = sorted(loops_by_cat.items(), key=lambda t: -t[1])[:4]
        say("no-ops: dropped %d recipes that consume as much of their output as they "
            "make (%s) -- charging, chisel variants, display entries"
            % (sum(loops_by_cat.values()),
               ", ".join("%s x%d" % (c, n) for c, n in top)))
    if len(kept) != before:
        g.recipes = kept
        g._by_output = None
        g._by_input = None
        g._producer_cache = {}
        top = sorted(dropped_by_cat.items(), key=lambda t: -t[1])[:4]
        say("non-recipes: dropped %d of %d entries (%s) -- info panels, anvil "
            "permutations, loot tables and container fills are not production"
            % (before - len(kept), before,
               ", ".join("%s x%d" % (c, n) for c, n in top)))

    flagged, containers = mark_container_transfers(g)
    if flagged:
        say("container transfers: %d recipes flagged as fluid moves, not production "
            "(%d container items detected)" % (flagged, len(containers)))

    say("graph: %d recipes, %d produced item keys, %d/%d oredict resolved"
        % (len(g.recipes), len(g.by_output),
           len(referenced & set(g.ore_members)), len(referenced)))
    return g


# JEI categories that are NOT production recipes. They dominate the dump by volume --
# ~222k of 344k entries on the reference pack -- and every one of them is a false edge the
# solver will happily plan through. Observed damage: a plan wanted 2,000,000 mB of water
# and an item "Dropped by Fishing Methods" for a single Borax.
#
# What these actually are:
#   anvil            repair/combine permutations, not crafting
#   EIOTank/bottler  fluid container fill and empty
#   information      JEI info panels (drop sources, usage notes)
#   jeresources.*    world generation, villager trades, plant drops
#   loot/drops       mob and chest loot tables
#   enchant*         enchanting permutations
#   _stats           material stat tables (Tinkers' harvest/ranged/projectile), listing
#                    every part a material yields against every form of that material
#   preview          Modular Machinery structure previews: the whole multiblock presented
#                    as if it crafts a blueprint, so a plan could "craft" a blueprint by
#                    building a 200-block structure
#   package_contents Packaged Auto showing what is inside a package
#   machine_produce  a list of everything a machine CAN output, not a recipe for any of it
#   throws           Chickens' colour-egg throwing: an interaction with the world
#   right_click      likewise, a use action presented in the recipe browser
#   puzzle           a puzzle display
# Squeezers, smelteries and centrifuges are REAL production and must not be added here.
# Neither is bee or chicken breeding, which IS production and has no machine -- see
# machines.NO_MACHINE_PATTERNS, a different answer to a different question.
# Override per-pack with `--keep-category` if a pack uses one of these names for real work.
NON_RECIPE_CATEGORY_PATTERNS = (
    "minecraft.anvil", "anvil",
    "eiotank", "forestry.bottler",
    "jei.information", "jei.description", "information", "description",
    "jeresources.", "villager_trade", "villager",
    "loot", ".drop", "drops",
    "enchanter", "enchantment", "superenchant",
    "_stats", ".stats", ":stats",
    "preview",
    "package_contents", "machine_produce", "throws", "right_click", "puzzle",
)


def is_non_recipe(category, keep=()):
    lc = str(category).lower()
    if lc in keep:
        return False
    return any(pat in lc for pat in NON_RECIPE_CATEGORY_PATTERNS)


def produces_nothing_new(recipe):
    """True when a recipe consumes at least as much of every output as it produces.

    These are display entries and no-ops, not production edges: `Empty Cell -> Empty Cell`
    in a TechReborn Extractor, `Flux Capacitor -> Flux Capacitor` for charging, the chisel
    variant tables, EnderIO's `GrindingBall` category showing a ball's SAG Mill bonus. Left
    in, the item looks craftable from itself and the solver spends backtracking budget
    rediscovering the cycle at every visit.

    TWO CONDITIONS, both learned from real false positives, and the reason this is not the
    obvious one-line set test:

    1. THE INPUT SLOT MUST BE UNAMBIGUOUS. A slot listing many interchangeable stacks does
       not require any particular one, so an output that merely appears among 200 oredict
       alternatives is not being consumed. Unioning all alternatives dropped
       `Chest + Tripwire Hook -> Trapped Chest`, because a trapped chest is one of the
       three things the chest slot accepts, and the real Angel Ring upgrade with it.
    2. QUANTITY MUST NOT INCREASE. `1 Spectral Fern -> 3 Spectral Fern` in a Phytogenic
       Insolator is genuine multiplication and the whole point of the machine.

    A recipe that returns a catalyst alongside new output (a mold, an empty bucket) is
    unaffected: the new output is not among the inputs at all.
    """
    if not recipe.outputs:
        return True
    required = {}
    for ing in recipe.inputs:
        if len(ing.alternatives) != 1:
            continue
        key = ing.alternatives[0]
        required[key] = required.get(key, 0) + max(ing.qty, 1)
    for key, qty in recipe.outputs:
        if required.get(key, 0) < max(qty, 1):
            return False
    return True


CONTAINER_FLUID_THRESHOLD = 8


def mark_container_transfers(g, quiet=True):
    """Flag fluid-container fill/empty pseudo-recipes so they cannot fake production.

    JEI exposes container operations as ordinary recipes. A Forestry Bottler entry reads
    `Tank -> Tank + 16,000 mB borax_solution`, and an Ender IO tank the same. Left alone,
    the solver concludes borax_solution is free to anyone holding a tank -- which is how
    "64 Borax" first resolved to "3 Tank, fully covered by stock".

    Two structural signals, no per-mod blacklist:

    1. SAME ITEM IN AND OUT. If an item key appears on both sides, it is a container or
       catalyst rather than an ingredient, and any fluid alongside it is being moved.
    2. ONE ITEM, MANY FLUIDS. A bucket/can/capsule appears as the sole item input of
       recipes yielding dozens of different fluids. No real process turns one item into
       80 unrelated liquids, so past a threshold the item is a container.

    BOTH SIGNALS COMPARE BASE KEYS, discriminator stripped, and that is load-bearing.
    A schema-3 dump gives a filled can its own key per contained fluid, which silently
    removed the premise of each signal at once and took detection from 7,016 recipes to
    117 (see #34): signal 1 stopped matching because `forestry:can:1` and
    `forestry:can:1#48a337d94489` are different strings, and signal 2 stopped reaching
    the threshold because each filled can became its own key producing exactly one fluid.
    Asking the question about the ITEM rather than about one NBT state of it restores
    both, and does so identically at every schema, because `base_key` is the identity
    function on a dump that carries no discriminators.

    DO NOT "simplify" this to a direct `X -> X#something` fill/empty test. It looks
    exact, and it misses the case that motivated the feature: Forestry's squeezer returns
    the can's MATERIAL, so the real shape is
    `forestry:can:1#48a337d94489 -> forestry:ingot_tin + 1,000 mB borax_solution`, with
    the empty can nowhere in the outputs. Measured against the real graph, that test
    catches 368 of the 7,016.
    """
    flagged = 0

    def item_input_bases(recipe):
        """Base keys of every item alternative across all input slots.

        A list, not a set: signal 2 needs to know the slot was unambiguous, and
        deduplicating first would make a two-alternative slot look like one item.
        """
        return [base_key(a) for ing in recipe.inputs for a in ing.alternatives
                if is_item_key(a)]

    # signal 1
    for r in g.recipes:
        if r.transfer:
            continue
        if not any(k.startswith("fluid:") for k, _q in r.outputs):
            continue
        out_bases = {base_key(k) for k, _q in r.outputs}
        if set(item_input_bases(r)) & out_bases:
            r.transfer = True
            flagged += 1

    # signal 2: count distinct fluids each sole-item-input produces
    fluids_per_item = {}
    for r in g.recipes:
        if r.transfer:
            continue
        fluid_outs = {k for k, _q in r.outputs if k.startswith("fluid:")}
        if not fluid_outs:
            continue
        item_ins = item_input_bases(r)
        if len(item_ins) != 1:
            continue
        fluids_per_item.setdefault(item_ins[0], set()).update(fluid_outs)

    containers = {k for k, fl in fluids_per_item.items()
                  if len(fl) >= CONTAINER_FLUID_THRESHOLD}
    if containers:
        for r in g.recipes:
            if r.transfer:
                continue
            if not any(k.startswith("fluid:") for k, _q in r.outputs):
                continue
            item_ins = item_input_bases(r)
            if len(item_ins) == 1 and item_ins[0] in containers:
                r.transfer = True
                flagged += 1

    if not quiet:
        print("container transfers: flagged %d recipes across %d container items"
              % (flagged, len(containers)), file=sys.stderr)
    return flagged, containers


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
