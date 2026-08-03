"""Build a unified recipe Graph from whichever sources are available."""

import os
import sys

from . import dimensions, multiblocks
from .model import FLUID_PREFIX, Graph, Ingredient, Recipe, base_key, is_item_key
from .names import find_items_csv, load_items_csv
from .sources import catalysts as catalysts_src
from .sources import damageable as damageable_src
from .sources import dump_meta, dump_names
from .sources import emc as emc_src
from .sources import hei_dump, icons as icons_src, jar_json, machine_names, oredict


def build(instance_dir, hei_path=None, quiet=False, no_guess=False,
          keep_categories=None, dump_dir=None, out_path=None):
    def say(msg):
        if not quiet:
            print(msg, file=sys.stderr)

    g = Graph()
    g.instance_dir = os.path.abspath(instance_dir)

    # Resolved ONCE and used for every file read out of the dump, so a graph cannot end up
    # holding recipes from one dump and names from another. See dump_meta.DIR_NAME.
    dump_root = dump_meta.dir_for(instance_dir, dump_dir)

    csv_path = find_items_csv(instance_dir)
    if csv_path:
        g.names = load_items_csv(csv_path)
        say("names: %d items from %s" % (len(g.names), os.path.relpath(csv_path, instance_dir)))
    else:
        say("names: items.csv NOT FOUND -- output will show raw ids")

    # Ore dictionary: the mod's dump wins, /ct oredict log is the fallback.
    od_json = os.path.join(dump_root, "oredict.json")
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
    meta = dump_meta.read(dump_root)
    say(dump_meta.describe(meta))
    g.dump_schema = meta["schema"] or 0
    g.dump_version = meta["mod_version"] or None
    g.dump_names_failed = meta["names_failed"]

    # After items.csv, and with setdefault, so the pack's own export stays authoritative
    # for anything it covers. This only has to reach the keys items.csv cannot express.
    dumped_names, on_disk = dump_names.load_with_count(
        dump_names.find(instance_dir, dump_dir))
    # BEFORE the names are merged, so a damaged dump cannot get a single label into the
    # graph on its way to being refused. `check_names` raises rather than returning a
    # verdict; see its docstring for why this one absence is not stepped over. #194
    dump_meta.check_names(meta, on_disk)
    if dumped_names:
        added = 0
        for key, label in dumped_names.items():
            if key not in g.names:
                g.names[key] = label
                added += 1
        say("names: +%d from the dump that items.csv did not cover (%d discriminated "
            "by NBT)" % (added, sum(1 for k in dumped_names if "#" in k)))

    # After BOTH name sources are merged and before anything reads a name. `oredict.
    # guess_from_names` below infers ore membership from display names, and an unlocalized
    # key is not a display name.
    relabelled = g.relabel_unlocalized()
    if relabelled:
        say("names: %d were an unlocalized lang key ('tile.null.name'), relabelled from "
            "the registry path" % relabelled)

    g.category_mods = dump_meta.category_mods(dump_root)
    if g.category_mods:
        say("category mods: %d categories carry JEI's own mod name" % len(g.category_mods))
    else:
        say("category mods: summary.json has none -- the machines page will group by the "
            "first token of each category uid, which is a guess")

    cat_path = catalysts_src.find(instance_dir, dump_dir)
    if cat_path:
        g.catalysts = catalysts_src.load(cat_path)
        say("catalysts: %d categories have a known machine item (JEI's own \"made in\")"
            % len(g.catalysts))
    else:
        say("catalysts: catalysts.json not present -- machine identity falls back to "
            "matching category titles against item names, which misses ~2 in 3")

    hei_path = hei_path or os.path.join(dump_root, "recipes.ndjson")
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

    apply_recipe_filters(g, keep_categories, say)

    flagged, containers = mark_container_transfers(g)
    if flagged:
        say("container transfers: %d recipes flagged as fluid moves, not production "
            "(%d container items detected)" % (flagged, len(containers)))

    # LAST, and after the drop pass on purpose: `known` is derived from the finished recipe
    # set, so parsing earlier would resolve blockstate metas against keys this graph no longer
    # has.
    g.multiblocks = multiblocks.parse(instance_dir, known=multiblocks.known_keys(g), say=say)

    # AFTER the oredict is settled, because `world_ores` is what makes this sound. The
    # pack's planetDefs also names blocks the overworld plainly has -- `minecraft:iron_block`
    # on Osiris, `minecraft:bone_block` on Hator -- and generating somewhere is not the same
    # as generating ONLY there. Intersecting with the pack's own `ore*` registration is the
    # same structural filter #61 and #106 already rely on, and it removes every one of those
    # by construction: they are `block*` entries, not `ore*`. Measured on the reference pack,
    # 17 exclusive declarations become 8 ores.
    defs = dimensions.load_planet_defs(instance_dir)
    if defs:
        exclusive = dimensions.exclusive_keys(defs)
        by_name = {name: dim for dim, (name, _ores) in defs.items()}
        g.dimension_ores = {key: [by_name[name], name] for key, name in exclusive.items()
                            if key in g.world_ores and name in by_name}
        # And the same ores again under the pack's OTHER id for them, which is where #112's
        # price went missing: the key planetDefs names is not the key the recipes consume.
        shadows = dimensions.shadow_ores(g, g.dimension_ores)
        g.dimension_ores.update(shadows)
        say("dimensions: %d declared, %d ores generate in exactly one of them "
            "(%d after the ore* filter, +%d duplicate registrations of those)"
            % (len(defs), len(exclusive), len(g.dimension_ores) - len(shadows),
               len(shadows)))
    else:
        say("dimensions: no config/advRocketry/planetDefs.xml -- a trip to another "
            "dimension is not priced, which is the pre-#112 behaviour")

    _read_schema_five(g, instance_dir, dump_dir, dump_root, out_path, say)


    say("graph: %d recipes, %d produced item keys, %d/%d oredict resolved"
        % (len(g.recipes), len(g.by_output),
           len(referenced & set(g.ore_members)), len(referenced)))
    return g


def _read_schema_five(g, instance_dir, dump_dir, dump_root, out_path, say):
    """The four files schema 5 added, each optional and each inert when absent.

    ONE FUNCTION RATHER THAN FOUR BLOCKS INLINE, because they share a property worth
    stating once: every one of them turns a feature ON, and every one of them missing means
    the graph behaves exactly as a schema-4 graph does. None of them can make a plan wrong,
    so none of them warrants the "this graph is degraded" warning `describe` gives for a
    stale digest format -- what they warrant is a line saying which feature is off.
    """
    g.max_damage = damageable_src.load(damageable_src.find(instance_dir, dump_dir))
    if g.max_damage:
        say("damageable: %d item types use their meta as durability, so their damage "
            "values collapse in search (#118)" % len(g.max_damage))
    else:
        say("damageable: damageable.json not present -- every damage value of a tool stays "
            "its own search row, which is the pre-schema-5 behaviour")

    g.machine_names, g.blueprint_machines = machine_names.load(
        machine_names.find(instance_dir, dump_dir))
    named = sum(1 for m in g.blueprint_machines.values() if m in g.machine_names)
    if g.blueprint_machines:
        say("machine names: %d Modular Machinery machines, %d of %d blueprints named "
            "after the machine they build (#55)"
            % (len(g.machine_names), named, len(g.blueprint_machines)))
    else:
        say("machine names: machine_names.json not present -- every Modular Machinery "
            "blueprint stays 1 of 261 items called \"Machine Blueprint\"")

    g.emc = emc_src.load(emc_src.find(instance_dir, dump_dir))
    if g.emc:
        # The count that decides whether #50 is worth anything on this pack: an EMC value
        # only helps for a key the graph could not otherwise reach.
        dead_ends = sum(1 for key in g.emc if not g.real_producers(key))
        say("emc: %d items carry a ProjectE EMC value, %d of which nothing in the graph "
            "produces (#50)" % (len(g.emc), dead_ends))
    else:
        say("emc: emc.json not present -- a drop-only item still dead-ends on its loot "
            "token, which is the pre-#50 behaviour")

    index = icons_src.load(icons_src.find(instance_dir, dump_dir))
    if index and out_path:
        # `dump_root`, not a second `dump_meta.dir_for` call. They agree today and the
        # DIR_NAME docstring records what happens when two resolutions of the same directory
        # drift: a graph holding recipes from one dump and names from another.
        index = icons_src.copy_pages(index, dump_root,
                                     os.path.dirname(os.path.abspath(out_path)), say)
    g.icons = index
    if g.icons:
        say("icons: %d sprites across %d atlas page(s)%s (#36)"
            % (len(g.icons["keys"]), len(g.icons["pages"]),
               "" if out_path else " -- NOT copied, no --out to copy them beside"))
    else:
        say("icons: icons.json not present -- rows keep the per-mod hue chip instead of a "
            "picture, which is the pre-#36 behaviour")


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
#   booklet/manual   an in-game guide book's pages, rendered in the recipe browser. Same
#                    thing as `information`, under two names this list did not have.
#   blockpatterns    ExtraUtils2 showing which blocks are the same multiblock part
#                    (`quarry` and `quarryproxy`), not a way to make either.
# The last two entries were caught only by accident until #110: every booklet and manual
# page in the reference pack happens to list its subject on BOTH sides, so the no-op test
# below swallowed all 386 of them. That is not a filter, it is a coincidence -- and #110
# made it load-bearing, because `expand_interconversion` reads a two-sided entry as a
# conversion. A manual page listing six crystal colours would have become a recipe for
# turning a black crystal into a red one.
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
    "booklet", "manual", "blockpattern",
)


def is_non_recipe(category, keep=()):
    lc = str(category).lower()
    if lc in keep:
        return False
    return any(pat in lc for pat in NON_RECIPE_CATEGORY_PATTERNS)


def produces_nothing_new(recipe):
    """True when a recipe consumes at least as much of every output as it produces.

    These are display entries and no-ops, not production edges: `Empty Cell -> Empty Cell`
    in a TechReborn Extractor, `Flux Capacitor -> Flux Capacitor` for charging, EnderIO's
    `GrindingBall` category showing a ball's SAG Mill bonus. Left in, the item looks
    craftable from itself and the solver spends backtracking budget rediscovering the cycle
    at every visit.

    ANSWERING TRUE IS NOT THE SAME AS "DROP IT". A chisel variant table scores true here
    and is a real conversion; `apply_recipe_filters` asks `expand_interconversion` before
    dropping anything this flags. Do not fold that test into this one -- this answers "does
    this entry produce anything new AS WRITTEN", and the two questions have different
    answers on exactly the entries #110 is about.

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
    required, _unambiguous = required_items(recipe)
    for key, qty in recipe.outputs:
        if required.get(key, 0) < max(qty, 1):
            return False
    return True


def required_items(recipe):
    """`({key: qty this recipe definitely needs}, every slot named exactly one thing)`.

    Shared by `produces_nothing_new` and `expand_interconversion`, which ask different
    questions of the same tally. A slot offering a choice contributes NOTHING to the tally
    -- it does not require any particular alternative, which is condition 1 in
    `produces_nothing_new` and cost a real recipe (`Chest + Tripwire Hook -> Trapped
    Chest`) when it was got wrong. The second return value is how a caller that
    additionally needs "and there were no choices at all" asks, without re-walking the
    slots to find out.

    ROLE IS NOT CONSULTED HERE, deliberately. A fluid slot naming one fluid genuinely is
    required, and a fluid appears on both sides of the container fill/empty entries the
    no-op test exists to catch, so skipping it would readmit them. `expand_interconversion`
    wants item-only slots and asks that separately, because it is its own question.
    """
    required = {}
    unambiguous = True
    for ing in recipe.inputs:
        if len(ing.alternatives) != 1:
            unambiguous = False
            continue
        key = ing.alternatives[0]
        required[key] = required.get(key, 0) + max(ing.qty, 1)
    return required, unambiguous


def apply_recipe_filters(g, keep_categories=(), say=None):
    """Drop what is not production, expand what only looks like it is not, in one pass.

    Separate from `build` so it can be exercised without an instance directory: every
    judgement about what counts as a production edge lives here, and #110's fix could not
    be reproduced against a 400-mod install on the machine that has no game.

    Returns the counts it reported, keyed by category, so a caller can assert on them.
    """
    if say is None:
        def say(_msg):
            pass
    keep = {k.lower() for k in (keep_categories or ())}
    before = len(g.recipes)
    dropped_by_cat = {}
    loops_by_cat = {}
    tables_by_cat = {}
    expanded_total = 0
    kept = []
    tables = []
    for r in g.recipes:
        if is_non_recipe(r.category, keep):
            dropped_by_cat[r.category] = dropped_by_cat.get(r.category, 0) + 1
        elif produces_nothing_new(r):
            # Before writing it off as a no-op, ask whether it is a variant TABLE, which
            # only looks like one because of how JEI flattens it. See
            # expand_interconversion.
            expanded = expand_interconversion(r)
            if expanded:
                tables.append((r, expanded))
            else:
                loops_by_cat[r.category] = loops_by_cat.get(r.category, 0) + 1
        else:
            kept.append(r)
    for recipe, expanded in tables:
        kept.extend(expanded)
        tables_by_cat[recipe.category] = tables_by_cat.get(recipe.category, 0) + 1
        expanded_total += len(expanded)
    if tables_by_cat:
        top = sorted(tables_by_cat.items(), key=lambda t: -t[1])[:4]
        say("variant tables: expanded %d interconversion tables into %d conversions (%s) "
            "-- any one member chisels into any other"
            % (sum(tables_by_cat.values()), expanded_total,
               ", ".join("%s x%d" % (c, n) for c, n in top)))
    if loops_by_cat:
        # Reported separately from the category drops above: the causes and the fixes are
        # different, and folding them together once made 8,051 wrongly-dropped crafting
        # recipes read as "info panels and anvil permutations".
        top = sorted(loops_by_cat.items(), key=lambda t: -t[1])[:4]
        say("no-ops: dropped %d recipes that consume as much of their output as they "
            "make (%s) -- charging, display entries"
            % (sum(loops_by_cat.values()),
               ", ".join("%s x%d" % (c, n) for c, n in top)))
    # COMPARE THE LISTS, NOT THE TWO LENGTHS. This branch is where `kept` is INSTALLED, so
    # whatever guards it decides whether the whole pass had any effect. `len(kept) !=
    # before` was that guard and it is wrong once expansion exists: expansion adds recipes
    # while the drops remove them, so the counts can tie while the contents differ in
    # thousands of places, silently discarding every drop AND every expansion at once.
    #
    # A list comparison rather than a chain of "did this counter fill up" booleans, which
    # is the same bug one category later: that chain has to be extended by hand every time
    # this pass learns a new verdict, and the first time it was written one verdict was
    # already missing from it. `Recipe` defines no `__eq__`, so this compares identity
    # element by element and cannot disagree with what actually happened.
    if kept != g.recipes:
        g.recipes = kept
        g._invalidate()
        dropped = sum(dropped_by_cat.values())
        top = sorted(dropped_by_cat.items(), key=lambda t: -t[1])[:4]
        say("non-recipes: dropped %d of %d entries (%s) -- info panels, anvil "
            "permutations, loot tables and container fills are not production"
            % (dropped, before,
               ", ".join("%s x%d" % (c, n) for c, n in top)))
    return {"dropped": dropped_by_cat, "loops": loops_by_cat, "tables": tables_by_cat,
            "expanded": expanded_total}


def expand_interconversion(recipe):
    """A JEI variant table, expanded into the conversions it actually offers.

    Chisel and Unlimited Chisel Works publish one entry per material listing every variant
    in BOTH columns: 37 input slots, and the same 37 stacks as outputs. The table means
    "any ONE of these becomes any other one", but flattened like that it reads as "all 37
    in, all 37 out", which `produces_nothing_new` correctly scores as a no-op and drops.

    Dropping it is the whole of #110. `chisel:lapis:1` ends up with zero producers, so
    `cost._seed` gives it BASE_RAW_COST and a decorative block prices BELOW the
    `minecraft:lapis_block` it is chiselled from -- 33 recipes could reach Lapis Lazuli
    through it. The same shape #61 fixed for Diamond, surviving in the keys `ore_backed`
    cannot rank because they are not ores. Measured on the reference pack: 341 tables,
    6,856 members, 61 of them a `<M> Block` that something else consumes.

    So expand rather than drop: one recipe per member, taking any OTHER member as a single
    slot. THE SLOT'S AMBIGUITY IS LOAD-BEARING TWICE. It is what the table actually says --
    any one of the others will do -- and it is what stops `produces_nothing_new` dropping
    the expansion on a later pass, by condition 1: a slot listing interchangeable stacks
    does not require any particular one.

    This cannot make a variant cheaper than its honest source. The member's price becomes
    the chisel's entry cost plus the cheapest OTHER member, and an entry cost is never
    below `BASE_RAW_COST`, so the group is anchored to whatever real block is in it. Same
    argument `solve` makes for world ore in #106.

    EVERY ARM IS FLAGGED `variant`, and that flag is not decoration. Expanding a table makes
    its members produced keys, and `cost._seed` gives `BASE_RAW_COST` only to keys nothing
    produces, so a group with no way in would become a closed cycle priced at infinity --
    measured at 327 keys on the reference dump, previously finite. `cost._settle_reshaped`
    reads the flag to give those keys their leaf price back. Expand without setting it and
    #110 trades a cheap lie for an expensive one.

    THE SHAPE ALONE IS NOT ENOUGH TO IDENTIFY ONE, which is why the documentation
    categories had to go into NON_RECIPE_CATEGORY_PATTERNS first. An Actually Additions
    manual page listing all six crystal colours side by side is bit-for-bit this shape and
    is not a conversion: you cannot chisel a black crystal into a red one. Measured before
    that filter went in, the structural test matched 354 entries, 13 of them documentation.

    Returns None for anything that is not this shape, so the caller falls through to the
    existing drop.
    """
    if recipe.transfer or not recipe.outputs:
        return None
    # A slot offering a choice already survives `produces_nothing_new`, so it never reaches
    # here; requiring unambiguous slots is what makes "the columns are equal" meaningful.
    # Item-only on top of that: nothing chisels a fluid, and a table carrying one is a
    # shape nobody has measured.
    if any(ing.role != "item" for ing in recipe.inputs):
        return None
    required, unambiguous = required_items(recipe)
    if not unambiguous:
        return None
    produced = {}
    for key, qty in recipe.outputs:
        produced[key] = produced.get(key, 0) + max(qty, 1)
    # Equal columns, and more than one thing in them. One key on both sides is a charging
    # or display no-op (`Flux Capacitor -> Flux Capacitor`) and must stay dropped.
    if len(produced) < 2 or required != produced:
        return None
    members = list(produced)
    out = []
    for i, key in enumerate(members):
        others = [m for m in members if m != key]
        out.append(Recipe(
            "%s#%d" % (recipe.rid, i), recipe.source, [(key, 1)],
            [Ingredient(others, 1, "item")],
            category=recipe.category, machine=recipe.machine, variant=True,
        ))
    return out


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
        if not any(k.startswith(FLUID_PREFIX) for k, _q in r.outputs):
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
        fluid_outs = {k for k, _q in r.outputs if k.startswith(FLUID_PREFIX)}
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
            if not any(k.startswith(FLUID_PREFIX) for k, _q in r.outputs):
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
