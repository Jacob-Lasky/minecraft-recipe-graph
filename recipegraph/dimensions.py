"""Which dimensions the pack declares, which you have been to, and what only grows there.

WHY THIS EXISTS. #105 priced the pack's gate placeholders -- an item that says "Chapter 2"
-- and could not price a PLACE, because travelling is not a recipe and nothing in the
graph is a trip. So a plan would route you to another planet without mentioning it:
`contenttweaker:sednanite_ore` is registered `oreSednanite`, so #106 correctly prices it as
something you go and mine, at exactly what a cobblestone costs. You mine it on Sedna. The
reference save has never been to Sedna.

PACK DATA AND WORLD STATE, AND KEEPING THEM APART IS THE DESIGN. A different axis from the
two PACK FILES below, which are both pack data and get unioned; do not conflate the two
splits.

  * WHAT ONLY GROWS THERE is pack data and belongs to the graph. Two pack files answer it,
    and `where_ores_generate` unions them; see WHY BOTH below.
  * WHETHER YOU HAVE BEEN THERE is world state and belongs to the have file, beside stock
    and placed blocks. It changes without the pack changing, and a graph is built once and
    serves every inventory.

Split that way the gate lifts BY ITSELF: fly to Sedna, rescan, and Sednanite stops being
gated with no edit to any curated list.

WHY BOTH PACK FILES, AND WHY NEITHER WINS. #248. The two answer DIFFERENT QUESTIONS and a
precedence rule between them would throw one of the two away:

  * `config/advRocketry/planetDefs.xml` states what Advanced Rocketry ADDS to a planet.
    Each `<planet>` carries a `DIMID` and an `<OreGen>` block naming the blocks generated
    there. It is a statement of intent by one mod about its own dimensions, and it is
    silent about everywhere else -- it has no reason to mention that a block it seeds on
    Diamerisma is also lying around the overworld.
  * `config/jeresources/world-gen.json` states what was OBSERVED, dimension by dimension,
    across 60 of them, in one uniform format: `"block": "modid:name:meta"` beside
    `"dim": "Dim -1: the_nether"`. It sees the Nether, the End and the Abyssal Wasteland,
    which planetDefs cannot, and it sees the overworld, which is what BOUNDS planetDefs.

Read back, never guessed, the same standing rule as `tokens.DEFAULT_TOKENS` and
`Graph.world_ores`. BUT THE TWO ARE NOT THE SAME KIND OF EVIDENCE AND THIS IS THE WHOLE
DESIGN. planetDefs is a DECLARATION: Advanced Rocketry states what it adds, and the file
means it whether or not anyone has looked. world-gen.json is a MEASUREMENT: it is the output
of JEResources' in-game profiler (`/jer_profile`, `ProfilingAdapter.write`), which is what
produces this schema including the `Dim -1: the_nether` spelling.

DO NOT CITE `diyData=true` AS PROVENANCE. An earlier version of this comment did, and it is
wrong: that flag is the CONSUMER side, telling the mod to skip its built-in compat plugins
and read the JSON, and it says nothing about who wrote the JSON. The mtime argument was
wrong too -- the file sits 22 seconds from its neighbour in a window shared by 784 files,
which is an archive unpack and not authorship. The file does ship with the pack; the pack
author generated it. That makes it good data and not a declaration.

WHAT THE PROFILER ACTUALLY DOES, because it decides which way the evidence runs. It
force-generates chunks at random coordinates across the world border and iterates
`DimensionManager.getStaticDimensionIDs()`, so it needs no visit and this is NOT a record of
where somebody walked. Sample sizes implied by the smallest non-zero `distrib` quantum are
roughly 10,000 chunks in the overworld and the Nether, 50,000 in the End, 250,000 in the
Abyssal Wasteland.

SO ITS POSITIVE ROWS ARE TRUSTWORTHY AND ITS ABSENCES ARE WEAKER THAN THEY LOOK. "No row"
has two indistinguishable causes: the ore does not generate there, or nothing sampled it.
Everything below turns on that asymmetry.

A POSITIVE OVERWORLD ROW BOUNDS A planetDefs EXCLUSIVITY CLAIM, and this is the strongest
thing the second source does. `nuclearcraft:ore:4` is the case to cite:

    nuclearcraft:ore:4 | Dim 0: overworld | y 0-32 | 9.6862 blocks per chunk
    nuclearcraft:ore:4 | Dim 152: Oi      | y 3-52 | 0.2706 blocks per chunk

Gated exclusive to Oi since #112, and Oi is unvisited. The overworld signal is 36x its own
Oi signal and unmistakably a vein rather than a stray block. planetDefs was not lying -- AR
really does seed it on Oi -- it simply has no reason to mention Earth.

DO NOT CITE `abyssalcraft:abyore` AS THE EXAMPLE, which an earlier version of this comment
did. It is the weakest row in the file: 0.013 blocks per chunk, the same trace magnitude in
all four dimensions it appears in, and `AbyssalCraftWorldGenerator` ships generators for
coralium, nitre, darklands structures and shoggoth lairs and NO abyssalnite ore generator at
all. That is structure-shaped, not vein-shaped. Diamerisma was never profiled, so on that
ore the two sources do not even disagree; one of them is silent.

A GATE IS NOT A TOLL, and #248 is about the second one. A gate says "you have never been
there" and it lifts the moment you go. A toll says a portal has to exist and you have to
walk through it, EVERY TIME, and it never lifts.

AND THEY ARE NOT COMPUTED FROM THE SAME INPUT, WHICH IS THE POINT. `offworld_keys` reads the
union; `exclusive_keys` reads planetDefs alone and `overworld_keys` may only subtract from
its result. Deriving both from one map makes the gate a subset of the toll BY CONSTRUCTION,
which is elegant and is exactly what makes it impossible to hold them to different evidence
standards. They are two claims, not two views. See `cost.OVERWORLD_TOLL` for the toll's
ordering and `overworld_keys` for why the asymmetry runs the way it does.

A PLANET IS NOT A SPECIAL KIND OF PLACE. Advanced Rocketry registers its planets as
ordinary dimensions with ordinary dimension ids -- Sedna is DIM147 -- and the save stores
them exactly as it stores the Nether. Anything here that treated "planet" as its own
concept would have to be undone the first time a non-planet dimension needed pricing, so
the vocabulary is `dimension` throughout, and each source is merely one that happens to
describe some of them: planetDefs the Advanced Rocketry planets, JEResources whatever the
profiling run walked into.

THE NETHER IS NOT INVISIBLE, and #112 said it was. The claim was that a vanilla nether
portal has no tile entity, so `ae2_inventory.scan` (which records tile entity ids) cannot
see one. That much is true and it is beside the point: you do not need to find the portal,
because entering a dimension GENERATES it, and generated terrain is a directory of `.mca`
files. On the reference save `DIM-1` holds 42 region files. Visited-ness reads the same way
for every dimension there is, vanilla or modded or planet, and needs no portal, no block
scan and no per-mod knowledge.
"""

import json
import os
import re

from .model import is_world_ore_group, mod_of, norm_key

# The overworld is where you already are, so it can never gate anything. Named rather than
# filtered by id at each call site, because "0 is not a gate" is a fact about the world and
# not an implementation detail of any one caller.
HOME_DIMENSION = 0

_PLANET = re.compile(r'<planet\b[^>]*>')
_NAME = re.compile(r'name="([^"]+)"')
_DIMID = re.compile(r'DIMID="(-?\d+)"')
_ORE = re.compile(r'<ore\b[^>]*?block="([^"]+)"(?:[^>]*?meta="(\d+)")?[^>]*?/?>')

# JEResources writes its dimension as one display string rather than a field pair, so the
# id has to come back out of it. `Dim -1: the_nether`, `Dim 165: Pixonia`,
# `Dim 427: divinerpg:vethea`.
#
# THE NAME HALF IS FREE TEXT AND MAY ITSELF HOLD A COLON, which is why the id is bounded by
# the FIRST colon and the name is everything after it. Splitting on the last colon instead,
# or on any colon, truncates `divinerpg:vethea` to `divinerpg` -- a real row in the
# reference pack, and a dimension named after only half of itself.
_JER_DIM = re.compile(r'^Dim (-?\d+):\s*(.*)$')


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


def parse_world_gen(text):
    """`{block key: {dim id: dim name}}` from JEResources' `world-gen.json`. #248.

    The file is a flat JSON array, one object per (block, dimension) pair, so a block that
    generates in five dimensions appears five times. ONLY `block` AND `dim` ARE READ; the
    `distrib` histogram says how much of it there is per height, and `dropsList` what it
    drops, and the graph already knows the second by better means and has no use for the
    first. They are most of the file's 5.8 MB, which is why this returns a small dict from
    a large document.

        {"block": "cyclicmagic:nether_iron_ore:0",
         "distrib": "0,0.0;1,0.0;2,1.6875E-4;...",
         "silktouch": false,
         "dim": "Dim -1: the_nether"}

    PARSED WITH `json` RATHER THAN REGEXES, which is the opposite call from
    `parse_planet_defs` beside it and deliberately so. That file is hand-edited and one
    pack's copy is not well-formed enough for a strict parser; this one is written by a mod
    and has never been anything but valid JSON. A malformed entry here is a real signal
    that the file is not what we think it is, and the caller degrades the whole file to
    "declares nothing" rather than silently reading half of it.

    An entry whose `dim` does not match `Dim <id>: <name>` is DROPPED rather than guessed
    at. Nothing in the reference pack's 6,646 entries fails to match, so this is a guard
    against a future format change, not a live filter -- and dropping is what makes such a
    change show up as coverage falling to zero instead of as ids invented from a string.
    """
    try:
        entries = json.loads(text)
    except ValueError:
        return {}
    if not isinstance(entries, list):
        return {}
    out = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        block, dim = entry.get("block"), entry.get("dim")
        if not isinstance(block, str) or not isinstance(dim, str):
            continue
        matched = _JER_DIM.match(dim)
        if not matched:
            continue
        # JEResources always writes the meta, including `:0`; the graph omits a zero meta.
        # `norm_key` is the one definition of that rule, so the id is split off its last
        # colon-separated field only when that field is actually a number -- `minecraft:air`
        # with no meta at all would otherwise lose its path.
        head, _, tail = block.rpartition(":")
        key = norm_key(head, tail) if head and tail.isdigit() else norm_key(block)
        if key:
            out.setdefault(key, {})[int(matched.group(1))] = matched.group(2)
    return out


def load_world_gen(instance_dir):
    """`parse_world_gen` against the pack's config, or `{}` when it is not there."""
    path = os.path.join(instance_dir, "config", "jeresources", "world-gen.json")
    if not os.path.exists(path):
        return {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return parse_world_gen(fh.read())
    except OSError:
        return {}


def where_ores_generate(defs, observed):
    """`{block key: {dim id: dim name}}` unioned across both pack sources. #248.

    NEITHER SOURCE WINS, because they answer different questions -- see the module
    docstring. planetDefs says what Advanced Rocketry adds to a planet, JEResources says
    what was observed in a dimension, and a block can perfectly well be both. Taking either
    as authoritative discards true rows from the other: precedence for planetDefs keeps
    gating `abyssalcraft:abyore` behind Diamerisma when it is also in the overworld, and
    precedence for JEResources drops the five ContentTweaker ores no profiling run reached.

    A COLLISION KEEPS THE FIRST NAME AND THAT IS DELIBERATE. The two files spell a
    dimension's name differently -- planetDefs says `Sedna`, JEResources says the same id
    as `Sedna` but writes `the_nether` where a person would write the Nether -- and the
    name is only ever shown to a reader. The ID is the identity and the two agree on it,
    which is the thing that has to be right.
    """
    out = {key: dict(dims) for key, dims in observed.items()}
    for dim, (name, ores) in defs.items():
        for key in ores:
            out.setdefault(key, {}).setdefault(dim, name)
    return out


def exclusive_keys(where):
    """`{block key: (dimension id, dimension name)}` for blocks exactly ONE dimension makes.

    A block two dimensions both generate is not locked to either, and a block the
    overworld generates is not locked at all. That second case is not hypothetical and is
    why this filter exists rather than a straight inversion of the map: the reference
    pack's planetDefs puts `minecraft:iron_block` on Osiris and `minecraft:bone_block` on
    Hator, and pricing a trip to Osiris into every iron block in the pack would be a far
    worse error than the one being fixed.

    FEED THIS planetDefs ALONE. #248 added a second source and deliberately did NOT route it
    through here: `index.build` calls this with `where_ores_generate(defs, {})` and then
    SUBTRACTS `overworld_keys(observed)` from the result. A declaration may create a gate; an
    observation may only withdraw one. The full argument is on `overworld_keys`, and the one
    sentence of it is that we are willing to be wrong by a toll and not by a gate.

    THE HOLE THIS DOCSTRING USED TO RECORD IS NOW HALF CLOSED, and only half. It said a block
    that also generates in the overworld through ordinary worldgen, which planetDefs has no
    reason to mention, still looked exclusive. The withdrawal closes that where JEResources
    positively SAW the block in the overworld: on the reference pack it takes two ores off the
    gate, `abyssalcraft:abyore` (a rocket to Diamerisma since #112) and `nuclearcraft:ore:4`
    (one to Oi), plus three `block*` entries the `ore*` filter was already dropping. 8 gated
    ores become 6.

    WHAT STILL GETS THROUGH, because a withdrawal needs a positive sighting. JEResources is a
    profiling snapshot: an ore in a dimension it never profiled, or an ore whose overworld
    generation is confined to a biome the sample never hit, produces no row and cannot
    exonerate anything. If planetDefs names such an ore and ordinary overworld worldgen also
    generates it, this still calls it exclusive. That is the same hole, narrowed, and it is
    narrowed only in the direction the evidence can carry.

    Two things bound what is left, and THE SECOND WAS ALREADY OVERSTATED BEFORE #248:

      * `index.build` intersects this with `Graph.world_ores`, the pack's own `ore*`
        registration, dropping every `block*` entry -- iron, bone, nickel -- by construction.
        17 exclusive declarations become 8 ores, plus 4 shadow registrations of those.
      * `cost._seed` applies the result as a FLOOR under `min`, so an ore with any crafted
        route keeps that route's price. THIS IS A FREQUENT MERCY AND NOT A GUARANTEE, and the
        docstring here used to claim otherwise: "every one of the 8 has between 1 and 6
        producers". Measured on the reference graph, 3 of the 12 gated keys have NO producer
        at all -- `contenttweaker:sub_block_holder_0:6`, `1:1` and `1:2`, the shadow
        registrations #117 added -- and for those the floor IS the price with nothing
        underneath it. The claim was written when the set was 8 and was not revisited when
        #117 made it 12.

    WHY THAT MATTERS FOR WHAT #248 DID NOT DO. The producer count was never evidence that a
    gate was CORRECT, only that being wrong was cheap, and it is cheap for 9 of 12 rather
    than all of them. That is the safety budget this function is running on, and it is why
    the observational source is not allowed to CREATE a gate: see `overworld_keys`.
    """
    out = {}
    for key, dims in where.items():
        if len(dims) != 1:
            continue
        dim, name = next(iter(dims.items()))
        if dim != HOME_DIMENSION:
            out[key] = (dim, name)
    return out


def overworld_keys(observed):
    """Keys JEResources positively recorded generating in the OVERWORLD. #248.

    THE ONLY THING THE OBSERVATIONAL SOURCE IS ALLOWED TO DO TO A GATE, and it may only
    ever WITHDRAW one. A positive overworld row says "this ore is not exclusive to
    anywhere", which is a claim the source can carry: its rows are a worldgen sample and a
    row exists because a block was seen. Its SILENCES cannot carry the opposite claim,
    because "does not generate there" and "was never sampled" are indistinguishable in it.

    WE ARE WILLING TO BE WRONG BY A TOLL AND NOT BY A GATE, and that sentence is the whole
    design. #259 is the defensible version of the other direction -- gate where JEResources
    saw the ore ELSEWHERE at vein level, so the overworld absence is a measurement rather
    than a silence -- and it is a separate change with its own blast radius, because it
    moves 15,433 keys where the toll moves a deep plan by one node. A wrong toll costs `OVERWORLD_TOLL`, deliberately tiny and bounded by the
    ordering. A wrong gate costs `DIMENSION_COST` = 800, and for 3 of the 12 keys the
    mechanism currently gates there is no producer underneath it, so the gate IS the price
    with nothing to fall back on. Those are not the same bet and they must not rest on the
    same evidence.

    So: `exclusive_keys` is fed planetDefs ALONE and this set subtracts from its result.
    A DECLARATION may create a gate; an OBSERVATION may only take one away.

    Takes the raw `parse_world_gen` map rather than a union, on purpose. Handed the union
    it could not tell an overworld row from a planetDefs entry that happens to mention
    dimension 0, and the whole point is that only one of the two sources may speak here.
    """
    return {key for key, dims in observed.items() if HOME_DIMENSION in dims}


def offworld_keys(where):
    """`{block key: (dimension id, dimension name)}` for ores never seen in the overworld.

    THE TOLL SET, and it is not the gate set. A gate asks "can you get there at all"; this
    asks "is there a portal on the route", which stays true forever. `exclusive_keys` is a
    SUBSET of this by construction -- exactly one dimension, and not the overworld, implies
    no overworld -- and a proper one on the reference pack, 95 against 98. The three that
    differ are ores in the Nether AND the End: behind a portal either way and so tolled,
    locked to neither and so not gated.

    SILENCE IS NOT EVIDENCE OF ABSENCE, which is the whole reason this returns keys rather
    than a predicate over every ore. An ore NO source places anywhere gets no toll, because
    "nobody profiled the Erebus" and "this ore is not behind a portal" are different
    statements and only one of them is knowable here. The coverage limit that buys, stated
    so #248 can record it rather than a later reader rediscovering it:

      * If a dimension appears in the JEResources profile, an ore's absence from it IS
        evidence the ore does not generate there. 60 dimensions are in it.
      * If a dimension is absent from the profile, nothing here knows anything about it.
        The Erebus (66), the Betweenlands (20) and the NuclearCraft Wasteland (4598) are
        the notable ones, and their ores are untolled for that reason and no other.
      * The 35 ContentTweaker ores beyond the twelve planetDefs names have NO SOURCE AT
        ALL. They are pack-authored blocks whose worldgen is registered in code, and
        nothing in the pack's config states where they generate.

    Under-tolling is the safe direction and that is why silence reads this way: an untolled
    off-world ore ties with the overworld exactly as it did before #248, which is the bug
    unfixed rather than a new one introduced. Over-tolling would surcharge a rock you can
    pick up in the overworld, which is worse and is not reachable from here.

    The dimension returned is one of the several it may generate in, for the reader. It is
    the LOWEST id rather than the first seen, so a rebuild from the same pack files produces
    the same graph.json whatever order the dicts iterated in; no caller may depend on which
    one it is, because the toll is a flat per-ore term that does not vary by destination.
    See `cost.OVERWORLD_TOLL`.
    """
    out = {}
    for key, dims in where.items():
        if dims and HOME_DIMENSION not in dims:
            dim = min(dims)
            out[key] = (dim, dims[dim])
    return out


def shadow_ores(graph, dimension_ores, ore_removals=None):
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

    A GROUP THE PACK DELETED STILL COUNTS, WHICH IS #168 AND IS NOT A LOOSENING. The 4th
    twin, `contenttweaker:sub_block_holder_1:8` (Rhenium Ore), is in NO `ore*` group,
    because `scripts/OreDictionary.zs:63` registers it into `oreRhenium` and then removes it
    again. The finished registry -- all `Graph.ore_members` has ever seen -- shows a Rhenium
    Ore belonging to nothing, so the group clause could not fire and the twin kept a bare
    `BASE_RAW_COST` of 1.0 against the real block's 2.0. A plan for `fluid:rhenium` named it.
    `oredict.removals_from_crafttweaker_log` reads the deletion back, and a group the pack
    took this key out of satisfies the group clause exactly as a group it left the key in
    does. Both clauses still hold; one of them is now being asked of a source that had not
    been read yet.

    DO NOT REPLACE THAT WITH "HAS NO `ore*` GROUP AT ALL", which is the obvious cheaper
    spelling and was measured wrong. Over the whole reference graph it flags 6 keys for 4
    correct: `immersiveengineering:ore:5` (a real Uranium Ore, gated behind a trip to Oi it
    has nothing to do with) and `abyssalcraft:coraliumstone:32767`. It is absence of
    evidence standing in for evidence, and it also cannot be squared with the two tests
    below -- `blockSednanite` is not an `ore*` group, so #118's and #61's fixtures have an
    EMPTY ore-group set and that rule gates them both.

    AND THE MOD HAS TO MATCH, WHICH IS THE OTHER HALF OF #168. Reading removals alone still
    flags `immersiveengineering:ore:5`, whose removal from `oreUranium`
    (`scripts/TheGreatOreCleanse.zs:649`) sits in a 134-line unification pass that prunes
    duplicate ingots, dusts and plates across mods so recipes resolve to one canonical item.
    Removal is a statement about which item satisfies a group, NOT about which block
    generates -- read the other way it gates one mod's ore behind the dimension declared for
    another mod's, a provenance no pack source states. What this function is for is ONE
    registrant giving ONE rock two ids; two mods each shipping a Uranium Ore is a different
    phenomenon that happens to look the same in the graph. Measured with the mod clause: 4
    flagged, 4 correct, nothing else in 266,728 keys. THE COVERAGE LIMIT that buys: a decoy
    registered by a different mod from its anchor is invisible here. All four in this pack
    are same-mod, so recall is 4 of 4 -- do not read that as "finds every decoy".

    The mod clause is applied to BOTH arms rather than only the new one, because "one
    registrant, one rock, two ids" is what the whole function means and a cross-mod match
    would be just as wrong through a surviving group as through a deleted one. Measured a
    no-op on the three that already shipped: all three are ContentTweaker on both sides.

    THE CANDIDATE POOL IS EVERY NAMED KEY, not `world_ores`, and it has to be: the Rhenium
    twin's whole problem is that it is NOT in `world_ores`, which is membership of the
    finished registry. That widening cannot reach the surviving-group arm, which still
    requires the sibling to hold an `ore*` group and so to be a `world_ores` member anyway.
    Measured: run over the reference graph with no removals, the widened pool returns the
    same three keys the narrow one did.

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
    # Coerced rather than trusted: `removals_from_crafttweaker_log` returns a set, but this
    # is a membership test inside a loop over every same-named sibling, and a caller handing
    # in the 134-element list it read from somewhere else would make it quietly quadratic.
    removals = frozenset(ore_removals or ())

    # `world_ores` is membership of the FINISHED registry, so it cannot see the Rhenium twin
    # -- the pack took it back out. Candidates are drawn from every named key instead, and
    # the clauses below carry the whole burden of declining the rest.
    by_name = {}
    for key, name in graph.names.items():
        if name:
            by_name.setdefault(name, []).append(key)

    def ore_groups(key):
        return {g for g in graph.ores_of(key) if is_world_ore_group(g)}

    out = {}
    for key, entry in dimension_ores.items():
        groups = ore_groups(key)
        if not groups:
            continue
        name = graph.names.get(key)
        # Which of the anchor's groups the pack is on record as having deleted a key of this
        # name from. The anchor is still IN those groups, so the deleted one is necessarily
        # some OTHER key wearing the same name -- which is the only inference the label-keyed
        # removal record can support. See `oredict.removals_from_crafttweaker_log`.
        deleted = {g for g in groups if (name, g) in removals}
        for sibling in by_name.get(name) or ():
            if sibling == key or sibling in dimension_ores:
                continue
            if not (groups & ore_groups(sibling) or deleted):
                continue
            if mod_of(sibling) != mod_of(key):
                continue
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
    surcharge every gated ore the moment an old stock file was loaded -- a silent repricing
    of the pack triggered by a missing field, and the pre-#112 answer is the safe one. That
    was 8 ores when #112 shipped and is 95 since #248 widened the source, which makes the
    safe reading more important rather than less.
    """
    if not graph.dimension_ores or not visited:
        return {}
    return {key: name for key, (dim, name) in graph.dimension_ores.items()
            if not is_visited(int(dim), visited)}
