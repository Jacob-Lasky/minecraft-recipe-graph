"""Which dimensions the pack declares, which you have been to, and what only grows there.

WHY THIS EXISTS. #105 priced the pack's gate placeholders -- an item that says "Chapter 2"
-- and could not price a PLACE, because travelling is not a recipe and nothing in the
graph is a trip. So a plan would route you to another planet without mentioning it:
`contenttweaker:sednanite_ore` is registered `oreSednanite`, so #106 correctly prices it as
something you go and mine, at exactly what a cobblestone costs. You mine it on Sedna. The
reference save has never been to Sedna.

TWO SOURCES, AND KEEPING THEM APART IS THE DESIGN.

  * WHAT ONLY GROWS THERE is pack data and belongs to the graph. `config/advRocketry/
    planetDefs.xml` is the pack's own declaration of every dimension it adds, each with a
    `DIMID` and an `<OreGen>` block naming the blocks that generate in it. Read back, never
    guessed -- the same standing rule as `tokens.DEFAULT_TOKENS` and `Graph.world_ores`.
  * WHETHER YOU HAVE BEEN THERE is world state and belongs to the have file, beside stock
    and placed blocks. It changes without the pack changing, and a graph is built once and
    serves every inventory.

Split that way the gate lifts BY ITSELF: fly to Sedna, rescan, and Sednanite stops being
gated with no edit to any curated list.

A PLANET IS NOT A SPECIAL KIND OF PLACE. Advanced Rocketry registers its planets as
ordinary dimensions with ordinary dimension ids -- Sedna is DIM147 -- and the save stores
them exactly as it stores the Nether. Anything here that treated "planet" as its own
concept would have to be undone the first time a non-planet dimension needed pricing, so
the vocabulary is `dimension` throughout and planetDefs is merely the one source that
happens to describe some of them.

THE NETHER IS NOT INVISIBLE, and #112 said it was. The claim was that a vanilla nether
portal has no tile entity, so `ae2_inventory.scan` (which records tile entity ids) cannot
see one. That much is true and it is beside the point: you do not need to find the portal,
because entering a dimension GENERATES it, and generated terrain is a directory of `.mca`
files. On the reference save `DIM-1` holds 42 region files. Visited-ness reads the same way
for every dimension there is, vanilla or modded or planet, and needs no portal, no block
scan and no per-mod knowledge.
"""

import os
import re

from .model import is_world_ore_group

# The overworld is where you already are, so it can never gate anything. Named rather than
# filtered by id at each call site, because "0 is not a gate" is a fact about the world and
# not an implementation detail of any one caller.
HOME_DIMENSION = 0

_PLANET = re.compile(r'<planet\b[^>]*>')
_NAME = re.compile(r'name="([^"]+)"')
_DIMID = re.compile(r'DIMID="(-?\d+)"')
_ORE = re.compile(r'<ore\b[^>]*?block="([^"]+)"(?:[^>]*?meta="(\d+)")?[^>]*?/?>')


def _ore_key(block, meta):
    """planetDefs speaks `block` + `meta`; the graph speaks one string. Same rule as
    `model.norm_key`, reimplemented here only because the meta arrives as a string
    attribute and the absent case means 0."""
    if not meta or meta == "0":
        return block
    return "%s:%s" % (block, meta)


def parse_planet_defs(text):
    """`{dimension id: (name, [block keys that generate there])}` from planetDefs.xml.

    Parsed with regexes rather than an XML parser ON PURPOSE. The file is a hand-edited
    pack config, one of the reference pack's is not well-formed enough for `ElementTree`
    to accept in full, and a parse error here must not take the whole build down over a
    section that has nothing to do with ores. What is needed is narrow -- the id, the name
    and the ore blocks -- and a miss degrades to "this dimension declares no ores", which
    is exactly how an unknown dimension already behaves.

    Nested `<planet>` elements are moons, and they are dimensions in their own right with
    their own DIMID, so nesting is deliberately ignored: each opening tag is one dimension
    and its ores are whatever `<ore>` tags appear before the next one.
    """
    out = {}
    opens = list(_PLANET.finditer(text))
    for i, m in enumerate(opens):
        tag = m.group(0)
        dim = _DIMID.search(tag)
        if not dim:
            # A gas giant or a star: it groups its moons and cannot be landed on, so it
            # has no dimension of its own and nothing can be locked to it.
            continue
        end = opens[i + 1].start() if i + 1 < len(opens) else len(text)
        name = _NAME.search(tag)
        ores = [_ore_key(b, meta) for b, meta in _ORE.findall(text[m.end():end])]
        out[int(dim.group(1))] = (name.group(1) if name else "DIM%s" % dim.group(1), ores)
    return out


def load_planet_defs(instance_dir):
    """`parse_planet_defs` against the pack's config, or `{}` when it is not there."""
    path = os.path.join(instance_dir, "config", "advRocketry", "planetDefs.xml")
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return parse_planet_defs(fh.read())
    except OSError:
        return {}


def exclusive_keys(defs):
    """`{block key: dimension name}` for blocks exactly ONE dimension generates.

    A block two dimensions both generate is not locked to either, and a block the
    overworld generates is not locked at all. That second case is not hypothetical and is
    why this filter exists rather than a straight inversion of the map: the reference
    pack's planetDefs puts `minecraft:iron_block` on Osiris and `minecraft:bone_block` on
    Hator, and pricing a trip to Osiris into every iron block in the pack would be a far
    worse error than the one being fixed.

    THIS ONLY KNOWS WHAT THE FILE SAYS, and on its own it is not sound. A block that also
    generates in the overworld through ordinary worldgen, which planetDefs has no reason to
    mention, still looks exclusive here. Two things downstream bound that:

      * `index.build` intersects this with `Graph.world_ores`, the pack's own `ore*`
        registration. On the reference pack that alone takes 17 exclusive declarations down
        to 8, dropping every `block*` entry -- iron, bone, nickel -- by construction.
      * `cost._seed` applies the result as a FLOOR under `min`, so an ore with any crafted
        route keeps that route's price. Every one of the 8 has between 1 and 6 producers,
        so the worst a leftover misclassification does is decline to discount an ore that
        had another way in anyway.
    """
    seen = {}
    for dim, (name, ores) in defs.items():
        for key in ores:
            seen.setdefault(key, []).append((dim, name))
    return {key: where[0][1] for key, where in seen.items()
            if len(where) == 1 and where[0][0] != HOME_DIMENSION}


def shadow_ores(graph, dimension_ores):
    """The same gated ores again, under whatever OTHER key the pack registered them as.

    WHY A SECOND KEY EXISTS AT ALL. MeatballCraft builds its custom ores twice. A material
    declared to ContentTweaker's MaterialSystem gets its whole part set generated, and
    `registerPart("ore")` lands in a shared holder block -- `contenttweaker:sub_block_holder_1:2`
    is the ore part of Sednanite (`scripts/CustomMaterials.zs:105`). The block that actually
    generates in the ground is a separate, hand-made one:
    `VanillaFactory.createBlock("sednanite_ore", ...)` (`scripts/CustomBlocks.zs:251`), and it
    is the one `planetDefs.xml` names. Both carry the display name "Sednanite Ore" and both
    are registered `oreSednanite`, so the pack means them as one ore -- it just has two ids
    for it, and only ever declared worldgen against one.

    THE HARM IS THAT ONLY ONE OF THEM GETS PRICED. #112 raised the floor under the key
    planetDefs names; the holder key kept the plain `BASE_RAW_COST` of a leaf, and 26 recipes
    consume the holder key LITERALLY rather than through `ore:oreSednanite`, so there is no
    alternative for the solver to prefer. The gate computed, the plan node said "mined on
    Sedna, and you have not been there", and the number the planner used never moved. See
    #117.

    THE TEST IS DISPLAY NAME **AND** A SHARED `ore*` GROUP, and needing both is the whole
    reason this is sound where the obvious version is not. Spreading a gate across the
    oredict group alone was built and rejected: `oreUranium` also holds `tardis:power_cell`,
    a Trionic Power Cell, which is not an ore you mine on Oi. Requiring the display name to
    match declines it by construction, because it is not called Uranium Ore. Requiring the
    group declines the reverse error, two unrelated blocks that happen to share a label --
    the `chisel:lapis:0..8` family in #118 is nine genuinely distinct blocks all called
    "Lapis Lazuli Block", and nothing would gate them because `blockLapis` is not an `ore*`
    group. Measured on the reference graph: of the 5 keys the group-only rule would have
    touched, this takes 3 and leaves 2.

    WHAT IT DELIBERATELY DOES NOT DO is claim the two keys are one node. They stay separate
    in the graph, with separate recipes and separate stock, because merging them is a change
    to everything that walks a key and the canonical direction is genuinely unsettled: 26
    recipes consume the holder and 30 the block. All this says is that a trip to Sedna is
    priced into both, which is true of any id you give the same rock.

    AND MERGING IS NOT NEEDED, WHICH IS THE MEASUREMENT THAT SETTLED IT. #117 suspected this
    was the #61 shape at large -- a producerless duplicate winning every slot that accepts
    the group, gate or no gate. It is not. The reference graph holds 129 same-name pairs
    inside a shared `ore*` group, and with the gate spread applied all 129 price IDENTICALLY:
    outside a dimension gate both halves are `BASE_RAW_COST` leaves anyway, so there is no
    cheaper half for a plan to prefer. A gate was the only thing that could tell them apart,
    which is why this lives here and not in a general de-duplication pass.

    Returns only the NEW entries, in `Graph.dimension_ores`' own `[dim id, name]` shape, so
    a shadow inherits the whole gate: fly to Sedna and both keys stop being gated together.
    """
    if not dimension_ores:
        return {}
    by_name = {}
    for key in graph.world_ores:
        name = graph.names.get(key)
        if name:
            by_name.setdefault(name, []).append(key)

    def ore_groups(key):
        return {g for g in graph.ores_of(key) if is_world_ore_group(g)}

    out = {}
    for key, entry in dimension_ores.items():
        groups = ore_groups(key)
        if not groups:
            continue
        for sibling in by_name.get(graph.names.get(key)) or ():
            if sibling == key or sibling in dimension_ores:
                continue
            if groups & ore_groups(sibling):
                out[sibling] = list(entry)
    return out


def _region_count(path):
    try:
        return sum(1 for n in os.listdir(path) if n.endswith(".mca"))
    except OSError:
        return 0


def visited(world_dir):
    """Every dimension folder in the save that holds generated terrain.

    Returns the folder names, not ids: the save names vanilla and most modded dimensions
    `DIM<id>` but several mods use a word (`Dim-Aether`, `Iceika`, `woot_tartarus`), and
    inventing an id for those would be a guess. Callers that need to match an id go
    through `is_visited`.

    A DIRECTORY IS NOT ENOUGH, THE REGION FILES ARE. Mods register their dimensions at
    load and several of the folders exist with an empty `region/`, so presence alone would
    report every dimension in the pack as visited. On the reference save that is the
    difference between 13 dimensions and 30-odd.
    """
    out = {}
    try:
        entries = sorted(os.listdir(world_dir))
    except OSError:
        return out
    for name in entries:
        region = os.path.join(world_dir, name, "region")
        if not os.path.isdir(region):
            continue
        count = _region_count(region)
        if count:
            out[name] = count
    # The overworld's region/ sits at the save root rather than in a subfolder, and it is
    # the one dimension you are definitionally in.
    root = _region_count(os.path.join(world_dir, "region"))
    if root:
        out["."] = root
    return out


def is_visited(dim_id, folders):
    """Has `dim_id` been entered, given the folder names `visited` returned?"""
    if dim_id == HOME_DIMENSION:
        return True
    return ("DIM%d" % dim_id) in folders


def gates_for(graph, visited):
    """`{ore key: dimension name}` for what a plan would need a trip to reach.

    The join between the graph's static half of #112 -- what only grows there -- and the
    have file's live half -- where you have been. One place, because `plan`, `find` and the
    server all need the same answer and three copies would drift.

    A STOCK FILE WITH NO `dimensions` RECORD GATES NOTHING. Every file written before #112
    is in that state, and so is one written by `tools/ae2_dump.lua`, which runs inside the
    game and can see no other dimension. Reading "no record" as "never been anywhere" would
    surcharge eight ores the moment an old stock file was loaded -- a silent repricing of
    the pack triggered by a missing field, and the pre-#112 answer is the safe one.
    """
    if not graph.dimension_ores or not visited:
        return {}
    return {key: name for key, (dim, name) in graph.dimension_ores.items()
            if not is_visited(int(dim), visited)}
