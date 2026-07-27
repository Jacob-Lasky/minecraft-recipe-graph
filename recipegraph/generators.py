"""Infinite resource generators, and what they make effectively free.

WHY THIS EXISTS. A plan for one Borax drew its water from 71 Snowballs and 12 Wet
Sponges. Every step of that chain is a real recipe, and the result is absurd: the base has
nine placed water sources. Once you own a generator, its output is not something to be
reconstructed from an exotic chain.

DETECTION IS A CURATED LIST, NOT AN INFERENCE, and that is deliberate. Nothing in a recipe
graph says "this block emits a resource from nothing" -- an infinite generator has no
recipe at all, which is precisely why it is invisible here. So the mapping from block to
output is written down by hand, matched against tile entities actually placed in the world,
and extended by the user in `data/sources.json`. A wrong guess would silently make a
resource free and hide a real cost, so honesty beats cleverness: if a generator is not on
the list, nothing happens, and the user can add it.

FREE IS NOT ZERO. Seeding a generator's output at cost 0 destroys the cost model's ability
to see quantity: 1,000 mB and 1,000,000 mB of water both price at nothing, so a plan will
cheerfully ask for a swimming pool. A small POSITIVE cost keeps the ordering intact -- any
generator output beats any crafted route, and among generator outputs less still beats
more. DO NOT set SOURCE_COST to 0.

Quantities are still reported. Generator draw goes into its own list on the plan
("drawn from infinite sources"), never silently folded into stock, so "64,000 buckets of
water" is visible rather than absent.
"""

import json
import os

# What one unit from an infinite generator costs the ranker. Small enough that any
# generator output beats any crafted alternative; non-zero so quantity still ranks.
# See the module docstring: zero here is a bug, not an optimisation.
SOURCE_COST = 0.02

# Blocks that emit a resource with no input, keyed by tile-entity / registry id.
# Ids are matched with machines.normalise_block, so running-state suffixes are handled.
#
# Every id here was checked against the reference pack's registry rather than recalled.
# The list is SHORT ON PURPOSE: a wrong entry makes a resource free and hides a real cost,
# which is worse than the absurd chain this feature exists to remove. Extend via
# data/sources.json; only promote an entry here when it is input-free for every player, not
# just conditionally (a Creative Drum, an Ex Nihilo barrel, and a filled tank are all
# "infinite" under conditions this tool cannot see).
DEFAULT_GENERATORS = {
    # NuclearCraft. All six variants exist; Jake's world has water_source x6,
    # water_source_dense x3 and cobblestone_generator_dense x3 placed.
    "nuclearcraft:water_source": ["fluid:water"],
    "nuclearcraft:water_source_dense": ["fluid:water"],
    "nuclearcraft:water_source_compact": ["fluid:water"],
    "nuclearcraft:cobblestone_generator": ["minecraft:cobblestone"],
    "nuclearcraft:cobblestone_generator_dense": ["minecraft:cobblestone"],
    "nuclearcraft:cobblestone_generator_compact": ["minecraft:cobblestone"],
    # Thermal Expansion Aqueous Accumulator and Industrial Foregoing Water Condensator:
    # both make water from nothing but adjacent water. Listed by ITEM id, which the
    # in-stock path matches; the placed path needs their tile-entity ids, which differ
    # from the item ids and are not verified here.
    "thermalexpansion:device": ["fluid:water"],
    "industrialforegoing:water_condensator": ["fluid:water"],
}

# True for any 1.12.2 world with default settings: two source blocks and a bucket give
# unlimited water. Kept as a named, switchable default rather than hardcoded, because a
# pack can disable water spreading and then this would be a lie.
VANILLA_FREE = {
    "fluid:water": "vanilla infinite water (two source blocks and a bucket)",
}


def load_overrides(path):
    """User additions and removals from data/sources.json.

    Shape: {"generators": {"<block id>": ["<output key>", ...]},
            "disabled": ["<output key>", ...],
            "vanilla_water": true|false}
    """
    doc = {}
    if path and os.path.exists(path):
        with open(path) as fh:
            try:
                doc = json.load(fh)
            except ValueError:
                doc = {}
    gens = {}
    for block, outputs in (doc.get("generators") or {}).items():
        if isinstance(outputs, str):
            outputs = [outputs]
        gens[str(block)] = [str(o) for o in outputs or ()]
    return {
        "generators": gens,
        "disabled": {str(k) for k in doc.get("disabled") or ()},
        "vanilla_water": bool(doc.get("vanilla_water", True)),
    }


# Substrings that make a placed block worth OFFERING as a source. A prompt, never a
# claim: nothing is added until the user names what it produces. Deliberately not "well"
# or "gen" -- those match jewellery and generic machinery, and a candidate list full of
# noise is one nobody reads.
GENERATOR_HINTS = ("source", "generator", "condensator", "accumulator", "spring",
                   "wellspring", "collector", "aggregator")


def candidates(placed, overrides=None):
    """Placed blocks that look like generators and are not on the list yet.

    Nobody knows the registry name of the block they built, which is why the CLI's
    `--add <block id>=<key>` was unusable in practice. The world scan already knows every
    tile entity, so the ids can be offered instead of recalled. Name-shaped, so it is a
    prompt and not a claim: the OUTPUT still has to be stated by hand, because nothing in
    the graph says what an input-free block emits -- that is the whole reason this file
    is a curated list.
    """
    ov = overrides if isinstance(overrides, dict) else load_overrides(overrides)
    known = set(DEFAULT_GENERATORS) | set(ov.get("generators") or {})
    out = []
    for block, n in sorted(placed.items()):
        if not n or block in known:
            continue
        if any(h in str(block).lower() for h in GENERATOR_HINTS):
            out.append(block)
    return out


def save_overrides(path, generators=None, disabled=(), vanilla_water=True):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as fh:
        json.dump({
            "_comment": "Infinite resource generators. `generators` maps a placed block id "
                        "to the item/fluid keys it produces without input; those keys "
                        "become effectively free when the block is in your world. "
                        "`disabled` removes a key regardless of evidence. Set "
                        "`vanilla_water` false if this pack disables infinite water.",
            "generators": generators or {},
            "disabled": sorted(disabled),
            "vanilla_water": vanilla_water,
        }, fh, indent=1, sort_keys=True)


def resolve(placed=None, stock=None, overrides=None):
    """{output key: human reason} for everything an owned generator makes free.

    Evidence is a placed tile entity first, then the generator item sitting in stock --
    the same order of directness machine availability uses. A generator in a drive is not
    producing anything yet, but it is one place-block away, so it counts.
    """
    from .machines import normalise_block

    placed = placed or {}
    stock = stock or {}
    ov = overrides if isinstance(overrides, dict) else load_overrides(overrides)
    disabled = ov.get("disabled") or set()

    table = dict(DEFAULT_GENERATORS)
    table.update(ov.get("generators") or {})

    placed_index = {}
    for block, n in placed.items():
        if n:
            placed_index.setdefault(normalise_block(block), block)
    stock_index = {}
    for block, n in stock.items():
        if n:
            stock_index.setdefault(normalise_block(block), block)

    free = {}
    for block, outputs in table.items():
        norm = normalise_block(block)
        if norm in placed_index:
            why = "placed: %s" % placed_index[norm]
        elif norm in stock_index:
            why = "in stock: %s" % stock_index[norm]
        else:
            continue
        for key in outputs:
            if key not in disabled:
                free.setdefault(key, why)

    if ov.get("vanilla_water", True):
        for key, why in VANILLA_FREE.items():
            if key not in disabled:
                free.setdefault(key, why)
    return free
