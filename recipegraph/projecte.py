"""What the players' ProjectE transmutation networks can actually produce.

THE WORLD-STATE HALF OF #50, and the split is the whole design. An item's EMC VALUE is pack
data: it changes when the pack changes, it is the same for everyone, and it lives in the
graph (see `sources/emc`). Whether you have LEARNED that item is world state: it changes
every time somebody drops something into a transmutation table, it differs per player, and
it lives in the have file beside stock and placed blocks. Bake knowledge into a graph and the
graph is wrong the moment anyone learns anything; keep them apart and the route opens by
itself on the next `have`.

Same split #112 drew between `graph.dimension_ores` (what only grows on Sedna) and the have
file's `dimensions` (whether you have been there).

WHERE IT LIVES. ProjectE stores knowledge as a Forge capability on the player, so it
serialises into `<save>/playerdata/<uuid>.dat` under `ForgeCaps` -> `projecte:knowledge`:

    knowledge         a list of ItemStack compounds, the things this player can make
    transmutationEmc  a long, the EMC currently banked
    fullknowledge     a byte; set by creative "learn everything"

READ FROM THE SAVE, NOT FROM THE CLIENT. The dump mod is standing in a running game with a
synced copy of this and could have written it out, which would have been one fewer reader.
It would also have baked one player's inventory state into a file whose whole job is to
describe the pack, and it would have tied re-reading it to a launch of the game -- the one
step in this project nobody can do on demand. This reader needs no launch, so knowledge
refreshes on the same schedule stock does.

AVAILABILITY IS "LEARNED", NOT "LEARNED AND AFFORDABLE", which is #50's first open question.
Two reasons. A banked EMC balance is a snapshot that a running collector invalidates within
a minute, so gating a shopping list on it would produce a plan that was wrong by the time it
was read. And a plan is a list of what to obtain, not a transaction: telling someone they
cannot have an item they have learned, because of a number that will be larger shortly, is a
false negative of the kind the dead end it replaces at least made obvious. The balance is
still recorded and still reported, as evidence rather than as a gate.
"""

import glob
import gzip
import os
import struct

try:
    from .ae2_inventory import stack_key
    from .anvil_nbt import parse_nbt
except ImportError:  # run directly as a script; see ae2_inventory's module docstring
    from ae2_inventory import stack_key
    from anvil_nbt import parse_nbt

#: Where the capability lands in a player's NBT. Verified against ProjectE-1.12.2-PE1.4.1
#: by reading the string constants out of `KnowledgeImpl` and `KnowledgeImpl$Provider`.
CAPS_TAG = "ForgeCaps"
KNOWLEDGE_CAP = "projecte:knowledge"


def read_knowledge(world_dir):
    """`{"learned": [keys], "emc": int, "full": bool, "players": n}` for a save.

    The union across every player file, because a shared network is shared: on a server the
    question a plan asks is "can this base produce the item", and knowledge held by one
    member answers it. `emc` is the LARGEST single balance rather than the sum, since one
    player pays for one transmutation and adding the balances together would report buying
    power that no single account has.

    Never raises. A save with no ProjectE, no playerdata directory or an unreadable player
    file reports zero learned items, which is exactly the pre-#50 behaviour.
    """
    out = {"learned": [], "emc": 0, "full": False, "players": 0}
    if not world_dir:
        return out
    learned = set()
    for path in sorted(glob.glob(os.path.join(world_dir, "playerdata", "*.dat"))):
        one = _read_player(path)
        if one is None:
            continue
        out["players"] += 1
        learned.update(one["learned"])
        out["emc"] = max(out["emc"], one["emc"])
        out["full"] = out["full"] or one["full"]
    out["learned"] = sorted(learned)
    return out


def _read_player(path):
    """One player's knowledge, or None when the file has none to give."""
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
        root = parse_nbt(gzip.decompress(raw))
    except (IOError, OSError, ValueError, IndexError, struct.error):
        # A player file mid-write, truncated, or in a format this reader does not know.
        # `struct.error` is in the list because `anvil_nbt` unpacks binary and raises it on
        # a short read; it inherits from Exception and from nothing else here.
        #
        # Skipping one player costs that player's knowledge. Raising would cost the whole
        # `have` run, including a stock scan that takes seven minutes over 1,536 region
        # files -- and this reader is the cheap optional half of it.
        return None
    caps = root.get(CAPS_TAG)
    if not isinstance(caps, dict):
        return None
    cap = caps.get(KNOWLEDGE_CAP)
    if not isinstance(cap, dict):
        return None

    learned = []
    for entry in cap.get("knowledge") or ():
        if isinstance(entry, dict) and isinstance(entry.get("id"), str):
            learned.append(stack_key(entry))
    emc = cap.get("transmutationEmc")
    return {
        "learned": learned,
        "emc": int(emc) if isinstance(emc, int) else 0,
        "full": bool(cap.get("fullknowledge")),
    }


def available(graph, knowledge):
    """Keys the EMC network can produce: learned AND carrying a positive EMC value.

    Takes the KNOWLEDGE MAP, not the whole have document -- what `read_knowledge` returns
    and what the have file stores under `emc_knowledge`. The server holds the parsed field
    and not the document it came from, so a whole-document signature made it build a
    one-key wrapper to satisfy a parameter it did not have; taking the thing both callers
    actually hold removes that.

    BOTH HALVES ARE REQUIRED AND THE `emc` HALF IS THE SAFETY ONE. ProjectE's own value of 0
    means "cannot be transmuted", and a pack this heavily scripted disables plenty of items
    by setting exactly that -- so learning alone is not evidence of a route. #50's stated
    worst case is asserting a route the pack has actually disabled, which would be worse than
    the dead end it replaces, and this is the clause that prevents it.

    `fullknowledge` is honoured, since a player who has it can make anything with a value.
    """
    values = getattr(graph, "emc", None) or {}
    if not values:
        return set()
    knowledge = knowledge or {}
    if knowledge.get("full"):
        return set(values)
    return {key for key in knowledge.get("learned") or () if key in values}
