"""What a fluid is actually CALLED, recovered from the containers it is bottled in.

WHY THIS EXISTS. The dump records a fluid ingredient as its registry name and nothing else
-- `{"f":"nethengeic_fluid","a":1000}` -- so `names.json` carries 337,434 entries and zero
of them are `fluid:` keys. `Graph.bare_name` therefore prettified the registry string, which
is fine right up until the pack renames a fluid in lang: MeatballCraft reuses another mod's
fluid constantly, and then the registry name is the PRE-RENAME identity, a different
substance as far as anyone reading the screen is concerned.

Measured on the reference graph: 789 of 1,198 fluid labels were wrong that way.
`fluid:nethengeic_fluid` is "Strong Mythic Essence", `fluid:liquid_void` is "Weak Mythic
Essence", `fluid:sednanite` is "Molten Sednanite". The renamed ones are the damaging class
because the real name is UNREACHABLE BY SEARCH -- nothing typed as "mythic essence" reaches
`fluid:nethengeic_fluid` by label or by key. Reported as "strong mythic essence doesn't
actually exist as a fluid, I just see the cell or bucket versions". See #103.

The names were on disk the whole time, one indirection away. A filled container is an ITEM,
items.csv names it, and Forestry names a can of anything "<the fluid> Can". So a recipe that
pairs one container with one fluid states the fluid's real name:

    Recursive Processor: Transposer
      IN  Strong Mythic Essence Cell    OUT  Empty Cell + fluid:nethengeic_fluid x1000
      IN  Empty Cell + fluid:sednanite  OUT  Molten Sednanite Cell

No mod change and no re-dump: this reads recipes the graph already holds.
"""

import collections
import re

from .model import FLUID_PREFIX

# base key -> the word its label ends in. A CURATED LIST, NOT A SUFFIX GUESS, for the reason
# `tokens.py` spells out at length: the guess is actively wrong here and it was measured.
# Accepting any "<something> Cell" adds `plustic:battery_cell`, whose "Manyullyn Battery
# Cell" outvoted the truth 5 to 2 and renamed `fluid:manyullyn` to "Manyullyn Battery"; the
# same pass renamed `fluid:stone` to "Seared Stone" and `fluid:copper` to "Copper Battery".
# Those items are named after a fluid without BEING a container for it, and no amount of
# vote-counting distinguishes them, because their labels are perfectly well-formed.
#
# Only bases that actually vote on the reference pack are listed. An id nothing uses is how
# a curated list starts drifting from the pack it describes -- `extracells:certustank`,
# `randomthings:enderbucket` and `thermalexpansion:florb` all exist and all wear fluid names,
# and none of them appears in a qualifying recipe, so none of them is here.
CONTAINERS = {
    "forestry:can:1": "Can",                # 1,200 votes -- effectively total coverage alone
    "forestry:refractory:1": "Capsule",     # 1,200
    "forestry:capsule:1": "Capsule",        # 725
    "techreborn:dynamiccell": "Cell",       # 29
    "forge:bucketfilled": "Bucket",         # 20
    "openblocks:tank": "Tank",              # 8
}

# `(.+?)` non-greedy so "Molten Sednanite Cell" splits at the LAST " Cell", not the first
# word: a fluid whose own name contains a container word ("Cell Culture Cell") still reads.
_PATTERNS = {base: re.compile(r"^(.+?)\s+%s$" % re.escape(word))
             for base, word in CONTAINERS.items()}

def _container_holds(key, label):
    """The fluid name `label` advertises, or None if `key` is not a curated container."""
    if not label:
        return None
    pattern = _PATTERNS.get(key.split("#")[0])
    if pattern is None:
        return None
    found = pattern.match(label)
    return found.group(1).strip() if found else None


def _pairings(recipe):
    """`[(fluid_key, item_key)]` this recipe states are the same substance, or [].

    ONE fluid and UNAMBIGUOUS item slots, in both directions, and every clause of that is
    load-bearing. The pack's generic "Fluid Transposer - Empty" entries list 1,198 filled
    containers in a single slot against an output of water; without the single-alternative
    test every fluid in the game would cast a vote for "Water".
    """
    fluid_out = [k for k, _q in recipe.outputs if k.startswith(FLUID_PREFIX)]
    fluid_in = [i for i in recipe.inputs if i.role == "fluid"]

    # Emptying: one container in, its contents out.
    if len(fluid_out) == 1 and not fluid_in:
        return [(fluid_out[0], i.alternatives[0]) for i in recipe.inputs
                if i.role != "fluid" and len(i.alternatives) == 1]

    # Filling: one fluid in, the filled container out.
    if len(fluid_in) == 1 and len(fluid_in[0].alternatives) == 1 and not fluid_out:
        return [(fluid_in[0].alternatives[0], k) for k, _q in recipe.outputs
                if not k.startswith(FLUID_PREFIX)]

    return []


def tally(recipes, label_of):
    """`{fluid_key: Counter({name: votes})}` -- the raw evidence, before it is decided.

    Kept as a separate step of `derive` rather than folded into it so the evidence is
    inspectable: a one-vote name and a four-vote unanimous one are very different claims,
    and the verdict alone cannot tell them apart. `decide` is what collapses them, and the
    tests assert on both halves.
    """
    votes = collections.defaultdict(collections.Counter)
    for recipe in recipes:
        for fluid, item in _pairings(recipe):
            held = _container_holds(item, label_of(item))
            if held:
                votes[fluid][held] += 1
    return dict(votes)


def decide(counter):
    """The winning name from one fluid's votes: most votes, then alphabetical.

    ALPHABETICAL IS A REAL TIE-BREAK, not a formality -- exactly one fluid ties on the
    reference pack (`fluid:eternal_dragon_fire`, "Eternal Dragon Fire" and "Niddhog
    Dragonfire" at four votes each) and the two names have nothing in common, so the choice
    has to be deterministic or the label flickers between graph loads.
    """
    return min(counter.items(), key=lambda item: (-item[1], item[0]))[0]


def derive(recipes, label_of):
    """`{fluid_key: display_name}` for every fluid the recipes bottle.

    `label_of` names an ITEM key. Pass `Graph.bare_name`: it is the only thing that knows how
    items.csv, the dump's NBT-discriminated names and the meta fallbacks compose, and a
    second opinion here would name containers differently from the rest of the UI.

    Fluids with no pairing are simply absent, so the caller keeps its own fallback rather
    than being handed a guess. There are none on the reference pack -- 1,198 of 1,198 fluids
    are named, none of them on a single vote -- but a smaller pack has every right to hold a
    fluid no container ever touches.
    """
    return {fluid: decide(counter) for fluid, counter in tally(recipes, label_of).items()}
