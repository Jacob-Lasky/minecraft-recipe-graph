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


def structure_cost(entry, cost, blocked=None):
    """What placing this multiblock costs, given a cost table. `inf` when it cannot be placed.

    A position is charged its CHEAPEST alternative, matching how #86 prices a machine with
    several candidate items: if any acceptable block is affordable, that is what a player
    would use.

    AN UNREACHABLE COMPONENT MEANS THE MACHINE CANNOT BE BUILT, so the cost is `inf` rather
    than the sum of the parts that happen to price. Skipping those positions would report the
    Dyson Extruder as merely expensive when 6,456 of its blocks are galaxy conduits with no
    obtainable recipe, which is the same underpricing this module exists to fix. 155 of the
    reference pack's 259 machines are in that state, which matches the observation that these
    multiblocks are frequently unobtainable rather than merely dear.
    """
    total = 0.0
    for count, keys in entry.get("parts") or ():
        best = min([cost.get(k, float("inf")) for k in keys] or [float("inf")])
        if best == float("inf"):
            if blocked is not None:
                blocked.append(keys[0] if keys else None)
            return float("inf")
        total += best * count
    return total
