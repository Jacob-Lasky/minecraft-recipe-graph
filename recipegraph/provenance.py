"""How the PACK says you get an item, when the JEI dump cannot say it at all.

WHY THIS EXISTS. `cost.BASE_RAW_COST` assumes an item with no recipe can be obtained
somehow, and `Graph.pack_authored_unsourced` is the set with positive evidence that
assumption fails: the pack authored the item, wrote recipes that consume it, and wrote no
way to obtain it. #171 calls that "unknown provenance" and prices it `UNSOURCED_COST`.

FOR 53 OF THOSE KEYS THE PROVENANCE IS NOT UNKNOWN, IT IS UNREAD, and one line of the
pack's own script explains why the graph cannot see it:

    // scripts/PuzzleUtil.zs
    function addPuzzleShapeless(name as string, output as IItemStack,
                                ingredients as IIngredient[]) {
        val stage_name = name + "_stage";
        recipes.addHiddenShapeless(name, output, ingredients, ...

`addHidden*` registers a REAL crafting recipe and hides it from JEI ON PURPOSE. Every graph
in this repository is built from a JEI dump, so those 82 recipes are invisible BY
CONSTRUCTION rather than unwritten -- and the item they make is then indistinguishable, to
every rule in `model.py`, from a JEI tooltip that nothing will ever produce. #171's own
example of failure mode 1, `contenttweaker:curious_bullet`, is one of them: the issue says
it "is obtained from a puzzle", and the pack says so too, in machine-readable form, in a
file the build already has in hand for `dimensions.load_planet_defs`.

THE MEASUREMENT THAT MAKES THIS SAFE, on the reference oracle (`graph-oracle.json`, 124,467
recipes) against the pack at its own `instance_dir`. The first column is what `load` returns,
so it is AFTER the precedence below rather than a raw per-source count:

                       declared    live in     reaching
                       pack-wide   the graph   pack_authored_unsourced
    puzzle output           82         70            41
    loot-table entry       249        242            10
    quest reward           565        538             2
    ----------------------------------------------------------------
    all three              896        850            53   of 285, and
                                                      0   of the 37 DEFAULT_TOKENS

The quest book names 660 reward items and 95 of them are also declared by a script, which is
where the 565 comes from. That overlap is not noise: a quest handing you the output of a
puzzle is telling you where to hand the puzzle's result in, which is why `load` resolves it to
the puzzle.

**Zero curated false positives.** That is the direction that matters, because this set is
applied as an EXCLUSION from `pack_authored_unsourced`: a wrong entry here would tell the
cost model that a JEI tooltip is an obtainable item, which is the defect #171 exists to fix.
A curated placeholder has no provenance anywhere in the pack for the same reason it is a
placeholder, so the two populations are disjoint on real data and not merely by construction.
`tests/test_provenance.py` asserts that emptiness against the real pack when
`$RECIPEGRAPH_ORACLE` names a graph, and skips rather than faking it when it does not.

THE THIRD COLUMN IS THE ONE THAT MOVES PRICES, and the gap between it and the first is the
reason this reads three sources rather than one. 896 declarations reach 53 keys, because the
overwhelming majority name items the graph already makes perfectly well -- a quest awarding
an iron ingot tells the cost model nothing it did not know. What the quest source is worth is
those last 2; what makes the puzzle source worth reading is that its 82 declarations are 41.

READ BACK, NEVER CURATED, which is the standing rule `tokens.DEFAULT_TOKENS`,
`Graph.world_ores` and `dimensions.parse_planet_defs` all state. Nothing below names an item.
Every key comes from a pack file, and a source that fails to parse degrades to "this source
declares nothing" rather than raising -- exactly `parse_planet_defs`' bargain, and for the
same reason: a hand-edited pack config must not be able to take a build down over a section
that has nothing to do with provenance.

WHAT THIS IS NOT. It is not a claim that the item is CHEAP, and it is not a route the solver
can walk -- there is still no recipe in the graph, so `Solver.expand` still stops here and
still shopping-lists the item. It is a claim about WHICH BAND the stop belongs in, and
`cost.PROVENANCE_COST` carries that argument.

AND IT IS NOT A MARKER DETECTOR. Subtracting these 53 leaves 232 keys in
`pack_authored_unsourced` whose provenance really is unknown, some of them genuine puzzle
rewards this reader cannot see because the pack handed them out some other way. `#171`'s
"detection narrows, curation decides" still holds; this narrows it by pack-declared fact
rather than by a guess at the shape of a key.
"""

import json
import os
import re

# What the pack declared, which is NOT the same question as what the player must do. Kept
# as three values rather than collapsed to one "declared" flag because `cost.PROVENANCE_COST`
# prices them differently and #95 is the standing lesson: one figure carrying two unrelated
# statements destroys the ordering among both.
PUZZLE = "puzzle"
LOOT_TABLE = "loot_table"
QUEST = "quest"
KINDS = (PUZZLE, LOOT_TABLE, QUEST)

# What each one tells a reader they have to go and do. Phrased as the answer to "where does
# this come from", because that is the question `Solver` puts on a plan node.
KIND_NOTE = {
    PUZZLE: "the pack makes this with a hidden recipe JEI never publishes, unlocked by "
            "solving its puzzle",
    LOOT_TABLE: "the pack puts this in a loot table, so it is found by playing",
    QUEST: "the pack hands this out as a quest reward",
}

# What the badge says, for the row and the tree node. Short, because it sits at the end of a
# row that already carries a quantity and a name -- the same constraint `tokens.KIND_BADGE`
# is written to, and deliberately NOT the same table: a token is a placeholder standing in
# for an instruction, and these are real items whose route the dump could not carry.
KIND_BADGE = {
    PUZZLE: "puzzle",
    LOOT_TABLE: "go get",
    QUEST: "quest reward",
}


def note_for(kind):
    """The sentence for a declared kind, and the ONE place the fallback lives.

    A `.get(kind, "...")` at each call site is two spellings of the fallback and, worse, two
    places where an unrecognised kind reads as deliberate. `tests/test_provenance.py` asserts
    every member of `KINDS` has an entry here and in `KIND_BADGE`, so reaching the fallback
    means someone added a kind and stopped halfway -- which is a thing to notice, not to
    paper over silently at whichever surface happened to render first.
    """
    return KIND_NOTE.get(kind, "the pack declares where this comes from")


def badge_for(kind):
    """The badge word for a declared kind. Same bargain as `note_for`."""
    return KIND_BADGE.get(kind, "declared")

# `addPuzzleShaped("name", <output>, [...])` and its shapeless twin. The output is the
# SECOND argument; the first is the stage name the wrapper derives its gamestage from, which
# is why the quoted string is matched and discarded rather than skipped over with `.*`.
_PUZZLE = re.compile(r'addPuzzle(?:Shaped|Shapeless)\s*\(\s*"[^"]*"\s*,\s*(<[^>]+>)')

# `chest.addItemEntry(<item>, weight)` from CustomLoot.zs and friends. The weight is not
# read: this answers "is it in a table at all", and a rarity is not a price. Turning a
# weight into a cost would be `EMC_COST`'s rejected scaling argument in a second place.
_LOOT_ENTRY = re.compile(r'addItemEntry\s*\(\s*(<[^>]+>)')

# `<mod:name>`, `<mod:name:3>`, `<mod:name:*>`, and the same with a trailing `.reuse()`,
# `.withTag(...)` or `* 4`. Only the three leading fields are read, because a CraftTweaker
# bracket handle carries far more than the graph's key does and everything after the meta is
# state this cannot represent anyway.
_ITEM = re.compile(r"<([a-zA-Z0-9_]+):([A-Za-z0-9_./-]+)(?::([0-9*]+))?[^>]*>")

# BetterQuesting writes NBT-typed keys: `rewards:9` is a list, `id:8` a string. The suffix is
# the tag type and is not part of the name, so every lookup below splits it off rather than
# spelling both halves.
_BQ_REWARDS = "rewards"
_BQ_ID = "id:8"


def _key(handle):
    """A CraftTweaker item bracket -> the graph's key spelling, or None.

    `:0` is dropped because the graph spells a meta-0 item bare -- the same normalisation
    `dimensions._ore_key` performs for planetDefs' `meta` attribute, restated here only
    because the meta arrives inside a bracket rather than as an attribute.
    """
    m = _ITEM.match(handle)
    if not m:
        return None
    namespace, name, meta = m.group(1), m.group(2), m.group(3)
    if meta in (None, "0"):
        return "%s:%s" % (namespace, name)
    return "%s:%s:%s" % (namespace, name, meta)


def parse_scripts(text):
    """`{key: kind}` for the puzzle outputs and loot entries one script file declares.

    A file declaring neither returns `{}`, which is the common case: of the reference pack's
    479 `.zs` scripts, 25 declare a puzzle output and 2 declare loot entries.

    REGEXES RATHER THAN A ZENSCRIPT PARSER, for `parse_planet_defs`' reason and one more of
    its own. There is no ZenScript parser in this repository and writing one to read two call
    shapes would be a mod-loader's worth of code to answer a narrow question; and the calls
    themselves are what the pack author types, so a shape this misses is a declaration that
    silently does not exist, which is the pre-existing behaviour rather than a regression.

    PUZZLE OUTRANKS LOOT when a key is both, and the ordering is the claim rather than an
    artefact of dict insertion. A puzzle is a thing you can sit down and solve; a loot table
    is a thing you farm until it drops. `cost.PROVENANCE_COST` prices the puzzle HIGHER, so
    resolving the tie the other way would quietly discount an item behind a gamestage to what
    an afternoon of fighting costs.
    """
    out = {}
    for handle in _LOOT_ENTRY.findall(text):
        key = _key(handle)
        if key:
            out[key] = LOOT_TABLE
    for handle in _PUZZLE.findall(text):
        key = _key(handle)
        if key:
            out[key] = PUZZLE
    return out


def parse_quest_rewards(doc):
    """`{key: QUEST}` for every item a BetterQuesting quest file hands out.

    REWARDS ONLY, NOT TASKS, and the distinction is the whole value of the source. A quest
    that REQUIRES an item is evidence the item exists and none at all that the quest gives
    it -- reading tasks would declare provenance for 5,008 keys on the reference pack against
    660 real rewards, and most of those 5,008 are ordinary craftables the quest book merely
    asks you to hand in. It would also be actively wrong in the one direction that matters:
    the quest book asks for placeholders too, so tasks would declare provenance for curated
    `DEFAULT_TOKENS` and hand a JEI tooltip a price that says it is obtainable.

    WALKED RATHER THAN INDEXED BY PATH. Quest files nest rewards under a numbered task map
    whose depth varies by reward type, so a fixed path would read some layouts and silently
    miss others -- and a reward this misses is a key that keeps the `UNSOURCED_COST` it has
    today, so the failure is quiet. Tracking "am I inside a `rewards` subtree" survives any
    nesting the format grows.
    """
    out = {}

    def walk(node, in_rewards):
        if isinstance(node, dict):
            for name, value in node.items():
                walk(value, in_rewards or name.split(":")[0] == _BQ_REWARDS)
            ident = node.get(_BQ_ID)
            if in_rewards and isinstance(ident, str) and ":" in ident:
                out[ident] = QUEST
        elif isinstance(node, list):
            for value in node:
                walk(value, in_rewards)

    walk(doc, False)
    return out


def _scan_scripts(instance_dir):
    out = {}
    root = os.path.join(instance_dir, "scripts")
    if not os.path.isdir(root):
        return out
    for base, _dirs, files in os.walk(root):
        for name in files:
            # `.zs` ONLY. The pack keeps several scripts under a `.txt` extension to stop
            # CraftTweaker loading them while leaving them readable -- `AdventTech.txt`,
            # `OreProcessing1.txt` -- and a declaration in a file the game never loads is not
            # a declaration. Measured on the reference pack: the `.txt` files contribute 0
            # puzzle outputs and 0 loot entries, so this costs nothing today and is here so
            # that a pack author disabling a script also disables what it claims.
            if not name.endswith(".zs"):
                continue
            try:
                with open(os.path.join(base, name), encoding="utf-8",
                          errors="replace") as fh:
                    out.update(parse_scripts(fh.read()))
            except OSError:
                continue
    return out


def _scan_quests(instance_dir):
    out = {}
    root = os.path.join(instance_dir, "config", "betterquesting", "DefaultQuests")
    if not os.path.isdir(root):
        return out
    for base, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".json"):
                continue
            try:
                with open(os.path.join(base, name), encoding="utf-8",
                          errors="replace") as fh:
                    out.update(parse_quest_rewards(json.load(fh)))
            except (OSError, ValueError):
                # One malformed quest file declares nothing and the other 4,656 still do.
                continue
    return out


def load(instance_dir):
    """`{item key: kind}` for everything the pack declares it hands out. `{}` for no pack.

    THE THREE SOURCES ARE MERGED HERE AND NOWHERE ELSE, so a caller cannot see two of them
    and believe it has the answer. Precedence is puzzle > loot > quest, applied by scanning
    the weakest claim first: a quest that awards an item the pack also puts behind a puzzle
    is telling you where to hand the puzzle's output in, not offering a second route.
    """
    if not instance_dir or not os.path.isdir(instance_dir):
        return {}
    out = _scan_quests(instance_dir)
    out.update(_scan_scripts(instance_dir))
    return out


def report(declared, reached):
    """One `say()` line for `index.build`: what was declared and what it reaches.

    `reached` is the second number and the one worth reading. A pack can declare thousands of
    rewards for items the graph already makes perfectly well, and those change nothing; what
    this feature does is take keys OUT of `Graph.pack_authored_unsourced`, so a count that
    moved is a count of keys whose price changed.

    IT IS PASSED IN RATHER THAN COMPUTED HERE, and that is not a style choice. This is called
    AFTER `g.declared_provenance` is assigned, and `pack_authored_unsourced` reads that field
    -- so asking the graph at this point would subtract the set from itself and report 0 for
    every pack. The caller measures before assigning.
    """
    if not declared:
        return ("provenance: no scripts/ or betterquesting quests -- an item the pack hands "
                "out by a hidden recipe stays unexplainable, which is the pre-#171 behaviour")
    kinds = {kind: sum(1 for k in declared.values() if k == kind) for kind in KINDS}
    return ("provenance: %d keys declared by the pack (%d puzzle, %d loot table, %d quest "
            "reward), %d of them previously priced as unexplainable"
            % (len(declared), kinds[PUZZLE], kinds[LOOT_TABLE], kinds[QUEST], reached))
