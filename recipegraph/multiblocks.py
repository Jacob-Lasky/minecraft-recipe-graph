"""What a Modular Machinery machine actually costs to stand up: its structure, not its item.

WHY THIS EXISTS. Every MM machine reaches the cost model as one craftable controller block,
and that controller's recipe is a blueprint plus a blank controller: two items. The machine
it stands for is a placed STRUCTURE of up to 8,813 blocks that appears in no recipe anywhere,
because a multiblock is pack config rather than a crafting output. So the ranker charged
roughly two items for the largest construction in the pack, and #86 made that worse rather
than better: replacing the flat `MACHINE_COST["buildable"]` with "what the machine's own
recipe costs" moved every MM machine toward the FLOOR of the band, so the pack's hardest
machines came out looking like its easiest. That is #93.

The requirements are readable offline, from `config/modularmachinery/machinery/*.json`, so
none of this needs the game running or a dump schema bump.

WHAT IS DELIBERATELY NOT COUNTED:

  * `modifiers` are optional upgrade blocks ("5X Speed"), not requirements. Counting them
    would overprice every machine by the cost of upgrades nobody has to place. The "5X Speed"
    text is genuinely useful and is genuinely a DISPLAY feature, so it is left on the table
    here rather than half-modelled as a cost.
  * The blueprint. It is one of the two items the controller recipe already covers, and it is
    the part of an MM machine that costs nothing to obtain.
"""

import collections
import glob
import json
import os

from .model import norm_key

INF = float("inf")

# Where the pack keeps them, relative to the instance's minecraft/ dir. `regex.txt` is MM's
# own alias table, mapping its abstract I/O port names to the blocks that satisfy them.
CONFIG_DIR = os.path.join("config", "modularmachinery", "machinery")
ALIAS_FILE = os.path.join("config", "modularmachinery", "regex.txt")

# A category id and a controller item id are both built from `registryname`, which is what
# lets a machine's structure be attached to the item the cost model already prices. Verified
# against the reference graph: all 189 MM categories carry a catalyst naming exactly this.
CATEGORY_PREFIX = "modularmachinery.recipes."
CONTROLLER = "modularmachinery:%s_controller"


def category_for(registryname):
    return CATEGORY_PREFIX + registryname


def controller_for(registryname):
    return CONTROLLER % registryname


def load_aliases(instance_dir):
    """MM's abstract port names to the block that satisfies each, from `regex.txt`.

    Two lines of defence against these leaking into item keys. This table resolves the four
    that matter (764 of the 937 abstract slots in the reference pack are an input bus, an
    output bus, or a fluid hatch), and `_element_key` drops anything still without a colon
    rather than letting `norm_key` invent `minecraft:generalized_input_item` -- the #16 ghost
    shape, where a phantom key becomes an unsatisfiable ingredient nobody can trace.

    The file is `block<tab>alias`, ALIAS SECOND, and in the reference pack it is CRLF with no
    trailing newline, so it is split on whitespace rather than on a literal tab.
    """
    path = os.path.join(instance_dir, ALIAS_FILE)
    out = {}
    if not os.path.exists(path):
        return out
    with open(path) as fh:
        for line in fh:
            parts = line.split()
            if len(parts) == 2:
                out[parts[1]] = parts[0]
    return out


def known_keys(graph):
    """The keys a cost table could plausibly hold a price for, for the blockstate fallback.

    NOT `graph.labels`, which is a display-name map: it is items.csv plus the fluid, essentia
    and ore names collected from recipes, so an item key that only ever appears inside a
    recipe is missing from it. Deciding "is `mod:block:9` a real item, or blockstate metadata
    on `mod:block`" against a set that omits half the real items would send resolvable slots
    down the fallback and price them as the wrong block.
    """
    return set(graph.names) | set(graph.by_output) | set(graph.by_input)


def _element_key(element, aliases, known=None):
    """One `elements` entry to an item key, or None when it is not an item at all.

    `@meta` in a machinery file is BLOCKSTATE metadata, not item metadata: `stone_slab@9` is
    a top-side slab whose item is `stone_slab:1`, and `twilight_log@5` is a rotated log. So
    when the exact key is not one this graph knows and the base one is, the base is used.
    That recovered 2,283 of the 2,456 unmatched slots on the reference pack, taking slot
    resolution from 95.1% to 99.8%. Without `known` there is nothing to check against, so
    the exact key is kept and the caller prices what it can.
    """
    element = aliases.get(element, element)
    if ":" not in element:
        return None
    if "@" not in element:
        return norm_key(element)
    base, meta = element.rsplit("@", 1)
    exact = norm_key(base, meta)
    if known is None or exact in known:
        return exact
    plain = norm_key(base)
    return plain if plain in known else exact


def _elements(part):
    """The alternatives for one block position. `elements` is a list 69,322 times and a bare
    string 32 times in the reference pack, and treating the string case as iterable would
    silently turn one block id into a set of single characters."""
    value = part.get("elements")
    if isinstance(value, str):
        return [value]
    if isinstance(value, (list, tuple)):
        return [v for v in value if isinstance(v, str)]
    return []


def parse_file(path, aliases, known=None):
    """One machinery file to `(registryname, entry)`, or None when it is not one.

    `entry["parts"]` groups identical block positions: `[[count, [key, ...]], ...]`, where the
    inner list is the ALTERNATIVES for that position, any one of which satisfies it. Grouping
    is what keeps this small enough to live in the graph: 69,354 positions collapse to 2,229
    groups. Sorted, so a rebuild of the same pack produces the same bytes.
    """
    with open(path) as fh:
        doc = json.load(fh)
    registry = doc.get("registryname")
    if not registry:
        return None
    groups = collections.Counter()
    blind = 0
    for part in doc.get("parts") or []:
        keys = {_element_key(e, aliases, known) for e in _elements(part)}
        keys.discard(None)
        if not keys:
            blind += 1
            continue
        groups[tuple(sorted(keys))] += 1
    return registry, {
        "name": doc.get("localizedname") or registry,
        "controller": controller_for(registry),
        "slots": len(doc.get("parts") or []),
        # Positions whose every alternative was an unresolvable alias. Recorded rather than
        # dropped, because "this machine is 0.1% unpriced" and "this machine did not parse"
        # have to be tellable apart by anyone auditing a price.
        "blind": blind,
        "parts": [[n, list(keys)] for keys, n in sorted(groups.items())],
    }


def parse(instance_dir, known=None, say=None):
    """{registryname: entry} for every machine the pack defines. Never raises on pack data.

    ONE BAD FILE MUST NOT COST THE OTHER 258. `induction_electrolyzer.json` in the reference
    pack is malformed JSON (`Expecting ',' delimiter: line 50 column 61`), which is a pack bug
    that MM presumably also trips over. Dying on it would take the whole graph build with it,
    and skipping it silently would leave one machine mispriced with no way to find out why.
    """
    out = {}
    root = os.path.join(instance_dir, CONFIG_DIR)
    if not os.path.isdir(root):
        if say:
            say("multiblocks: no %s -- MM machines will be priced by their controller "
                "recipe alone" % CONFIG_DIR)
        return out
    aliases = load_aliases(instance_dir)
    bad = []
    for path in sorted(glob.glob(os.path.join(root, "*.json"))):
        try:
            got = parse_file(path, aliases, known)
        except (ValueError, OSError) as exc:
            bad.append((os.path.basename(path), str(exc)))
            continue
        if got:
            out[got[0]] = got[1]
    if say:
        slots = sum(e["slots"] for e in out.values())
        say("multiblocks: %d machines, %d block positions, %d alias entries"
            % (len(out), slots, len(aliases)))
        for name, why in bad:
            say("multiblocks: SKIPPED %s -- malformed pack JSON: %s" % (name, why))
    return out


def position_cost(keys, cost):
    """What one block POSITION costs: its cheapest acceptable alternative, or `inf`.

    ONE definition, read by `structure_cost` and by `blocked_fraction`. They held a copy each
    for one commit, which is a standing invitation to disagree about which positions are
    blocked -- and the two are used together, on the same structure, to produce a price and
    the ordinal that ranks it. A position the sum thinks is affordable and the fraction thinks
    is missing would be a contradiction nothing reports.

    Cheapest alternative, matching how #86 prices a machine with several candidate items: if
    any acceptable block is affordable, that is what a player would use.
    """
    return min([cost.get(k, INF) for k in keys] or [INF])


def structure_cost(entry, cost, blocked=None):
    """What placing this multiblock costs, given a cost table. `inf` when it cannot be placed.

    AN UNREACHABLE COMPONENT MEANS THE MACHINE CANNOT BE BUILT, so the cost is `inf` rather
    than the sum of the parts that happen to price. Skipping those positions would report the
    Dyson Extruder as merely expensive when 6,456 of its blocks are galaxy conduits with no
    obtainable recipe, which is the same underpricing this module exists to fix. 155 of the
    reference pack's 259 machines are in that state, which matches the observation that these
    multiblocks are frequently unobtainable rather than merely dear.

    `blocked_fraction` below is how #95 tells those 155 apart without weakening this.
    """
    total = 0.0
    for count, keys in entry.get("parts") or ():
        best = position_cost(keys, cost)
        if best == INF:
            if blocked is not None:
                blocked.append(keys[0] if keys else None)
            return INF
        total += best * count
    return total


BLOCKED_REASONS = ("produced, never priced", "nothing makes it")


def blocking_keys(entry, cost):
    """`{key: positions}` for the keys that made a position unsatisfiable.

    `blocked_fraction` says HOW MUCH of a structure cannot be placed and deliberately says
    nothing about what did it, which is the gap #100 was filed over: the ordinal ranks 166
    structures against each other and no output anywhere names a single blocking block, so
    the claim behind the ranking could not be audited. Every acceptable candidate for a
    blocked position is counted, because any one of them would have satisfied it.
    """
    out = {}
    for count, keys in entry.get("parts") or ():
        if position_cost(keys, cost) != INF:
            continue
        for key in keys:
            out[key] = out.get(key, 0) + count
    return out


def blocked_reason(graph, key, cost):
    """WHY this key is unobtainable, in the terms the cost model can actually justify.

    THE TWO ARE NOT THE SAME CLAIM AND THE SPLIT IS THE POINT. "Nothing in the pack makes
    this" is a fact about the pack. "This has a recipe and the model never reached a finite
    price for it" is a fact about the model's own coverage, and dressing it up as the first
    is what #100 means by evidence that reads like a proof. Measured on the reference graph
    after #110: of 26,236 blocked positions, **25,109 (95.7%) are the second kind**, across
    190 of the 250 distinct blocking keys. `contenttweaker:galaxy_conduit` (6,456 positions)
    has a 7x7 Extended Crafting recipe whose own inputs are unpriced; `biomesoplenty:flesh`
    (844) is four flesh chunks, and a flesh chunk is a mob drop.

    An oredict sibling is deliberately NOT accepted as a way out, though the position would
    price if it were. Modular Machinery matches the BLOCK at a position, not an oredict
    group, so a sibling that shares `blockIron` is not a thing you could place there. The
    pack's own alias table (`regex.txt`, already read by `parse`) is the only substitution
    that is real, and it is applied before a position ever reaches here.
    """
    if graph.by_output.get(key):
        return BLOCKED_REASONS[0]
    return BLOCKED_REASONS[1]


def blocked_fraction(entry, cost):
    """Share of this structure's block POSITIONS with no PRICED candidate, in [0, 1].

    `structure_cost` above answers "can this be placed at all" and deliberately collapses to
    `inf` the moment one position is unsatisfiable -- which is right, and #95 is what it costs:
    every blocked machine reached the ranker as one indistinguishable number, so a structure
    missing 3 of its 2,125 positions (`the_cube`) priced identically to one where all 135 are
    missing (`mythic_excavation_lattice`). This is the ORDINAL that separates them, and it is
    deliberately NOT a cost: a partial sum would say "merely expensive" about a machine that
    cannot be built, which is the underpricing `structure_cost`'s own docstring exists to
    refuse. Callers must keep both on the unbuildable side of every price.

    POSITIONS, not part GROUPS. `parts` collapses 69,354 positions into 2,229 groups, so
    counting groups would weigh one missing galaxy conduit the same as 6,456 of them -- the
    grouping is a storage optimisation and must not become the unit of judgement.

    A structure with no parts at all is 0.0: nothing is missing from nothing. That agrees with
    `structure_cost`, which prices an empty structure at 0.0 rather than at `inf`.

    "PRICED", NOT "OBTAINABLE", and the wording is a correction rather than a nicety. This
    reads `cost`, so a position is blocked when no candidate has a finite entry in the cost
    table -- which is a weaker statement than the pack proving the block unobtainable, and
    `blocked_reason` above measures how much weaker. Callers that need the stronger claim do
    not have it, which is why the whole slice `blocked_entry_cost` maps onto stays below
    `MACHINE_COST["unknown"]`.
    """
    total = 0
    bad = 0
    for count, keys in entry.get("parts") or ():
        total += count
        if position_cost(keys, cost) == INF:
            bad += count
    if not total:
        return 0.0
    return bad / float(total)
