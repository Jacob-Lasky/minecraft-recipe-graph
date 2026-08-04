"""Recipes JEI publishes that are DOCUMENTATION, not routes. #211 and #169, one mechanism.

WHY THIS EXISTS. Two plans, one shape of lie.

  * Planning a vanilla Chest went `Chest -> Chest Cart -> Scrap Box -> Matter Reprocessor`
    and asked for four machines the player does not own and 576 bee princesses. The correct
    answer is eight planks. `TechReborn.Scrapbox` is a random loot table: one uniformly
    random item out of hundreds, published by JEI as one entry per outcome. See #211.
  * Planning a Mithrillium Ingot asked for an Item Router, a Distributor Module and a Bee
    Sample, none of which are used to make Mithrillium. `recipes.addShapeless(<output>,
    [<marker>, <rig parts...>])` is how this pack writes a JEI card EXPLAINING how to
    AUTOMATE something; seven of them share one byte-identical input list and produce seven
    different outputs. One shapeless recipe cannot produce seven different items. See #169.

Both trees were drawn correctly from the graph. The graph had read a JEI category of prose as
a set of deterministic recipes, and the cost model then priced the prose as the cheap route.

PRICED OUT, NEVER DROPPED, on `cost.TRANSFER_PENALTY`'s precedent, and the reason is measured
rather than tidy. Delete an entry that is its output's ONLY producer and `producers` falls to 0,
`cost._seed` treats the key as a leaf and gives it `BASE_RAW_COST`, and the item prices at 1.0,
level with dirt. The plan stops lying about HOW and starts lying about HOW MUCH, which is worse
because the first is visible in the tree and the second is not. #211's report has the reference
case: `contenttweaker:imp_skin` goes from 248.35 to 1.0, a 248x collapse. Measured here, 3 of
`TechReborn.Scrapbox`'s 343 entries are in exactly that position, which is what the guard in
`verdicts` exists for.

Dropping also costs `used_in` and `makes` (`explore.output_rows`), and it empties
`tokens.candidates`, whose whole structural test is "consumed by some recipe, produced by none"
-- remove the annotation recipes and the curated markers stop being consumed by anything, so the
mechanism that would discover the NEXT drifted marker stops working.

DERIVED AT PLAN TIME, NOT STORED IN THE GRAPH, which was the harder call. `Recipe.transfer`
and `Recipe.variant` are both build-time fields and this deliberately is not one, for three
reasons:

  1. IT DEPENDS ON THE RESOLVED TOKEN MAP, which is per-world user config
     (`tokens.for_path`, `--tokens`, `data/tokens.json`) and is not known at build time. A
     baked flag would keep demoting a marker's recipes after the user disabled that marker.
  2. IT WOULD BE A SCHEMA BUMP ON 124,467 RECIPE RECORDS for a value every consumer can
     recompute in well under a second from indexes the graph already has.
  3. A BUILD-TIME FLAG DOES NOTHING UNTIL THE PACK IS RE-DUMPED, and re-dumping needs the
     game instance. Every graph already on disk would keep planning a chest through a
     scrapbox until someone with the 400-mod install rebuilt it.

`plan.Unsourced` in the Java port is the same shape of decision made the same way: a derived
predicate, ported rather than persisted, held to Python by the golden fixture gate.

TWO GROUNDS, and they are separate because the EVIDENCE is separate. A loot table is declared
by CATEGORY (`tokens.LOOT_TABLE_CATEGORIES`); an annotation card is inferred from the MARKER
ITEM it carries. Folding them into one predicate would mean one of the two had to be inferred
from evidence that does not exist for it.
"""

from . import tokens as tokens_mod

# The grounds on which a recipe is not a production route. A recipe carries at most one; when
# both apply, the declared one wins, because a declaration outranks an inference.
LOOT_TABLE = "loot_table"
ANNOTATION = "annotation"
GROUNDS = (LOOT_TABLE, ANNOTATION)

GROUND_REASON = {
    LOOT_TABLE: "a random loot table, one JEI entry per possible outcome",
    ANNOTATION: "a JEI card explaining how to automate this, not a recipe",
}


def _other_inputs(recipe, marker):
    """The recipe's input slots with `marker`'s slots removed, canonicalised for comparison.

    BY MEMBERSHIP IN `alternatives`, NOT BY POSITION: `battle_tower`'s recipes carry two
    markers, so "drop the first slot" removes the wrong one on half of them.

    CANONICALISED AS `(sorted alternatives, qty)` per slot and then sorted, because the
    question is whether two recipes ask for THE SAME THINGS. Comparing `Ingredient` objects
    compares identities and makes every recipe look distinct, which is the version of this
    that silently reports zero families.
    """
    return tuple(sorted(
        (tuple(sorted(ing.alternatives)), ing.qty)
        for ing in recipe.inputs if marker not in ing.alternatives))


def annotation_markers(graph, token_kinds):
    """The curated markers whose recipes are documentation cards. Three conditions.

    Each condition has a counterexample measured on the reference graph that the other two do
    not catch, so none of the three is decoration. Add a fourth only with the same evidence.

    1. THE MARKER'S KIND IS `METHOD`. A METHOD marker says "the work happens in a mechanic";
       a LOOT one says "go and find it", which is a real way to get a thing and is priced for
       that at `cost.LOOT_COST`. Without this condition six LOOT markers fire, including
       `good_woot_drops` -- and that is the `imp_skin` regression this module's header is
       about -- plus four GATE and three HINT markers.

    2. EVERY FAMILY THE MARKER APPEARS IN YIELDS MORE THAN ONE DISTINCT OUTPUT, where a
       FAMILY is the marker paired with one exact other-input set. This is #169's structural
       discriminator: an annotation card shares one input list across N different outputs,
       and one recipe cannot produce N different items. A genuine marker recipe varies its
       other inputs per output -- `dungeon_drop` plus `trinity_callstone` gives Hator Spinel,
       plus `pharos_callstone` gives Ptah Spinel, one to one.

       PER FAMILY, NOT PER MARKER, and that is the correction #169's issue body needs. Its
       rule was "the marker has ONE distinct other-input set", and measured at full count
       `passive_crafting_subnets` has THREE: a Berserker Forge card with 13 outputs, a
       Honeysmelter Oven card with 10, a Combination Package Crafter card with 2. Three
       annotation cards that happen to share a marker. A per-marker test calls it genuine and
       misses all 25 recipes; the per-family test admits it and independently reproduces the
       same five markers the pack's own `JEIdescriptions.zs` declares as documentation.

       AND IT IS WHAT KEEPS `multiblock_preview` AND `dream_infusion_crafting`, both METHOD
       and both genuine: 12 of `multiblock_preview`'s 101 recipes and 30 of
       `dream_infusion_crafting`'s 93 sit in a multi-output family, so a per-recipe version of
       this test would demote 42 real routes. Requiring EVERY family to have the shape is
       what separates "a marker used for documentation" from "a marker used for real work
       that occasionally has a byproduct".

    3. (in `verdicts`, not here) every output has another producer that is not itself a
       candidate. Applied per RECIPE, which is why it cannot live in this per-marker function.
    """
    method = [key for key, kind in (token_kinds or {}).items()
              if kind == tokens_mod.METHOD]
    out = {}
    for marker in method:
        recipes = graph.by_input.get(marker) or ()
        if not recipes:
            continue
        families = {}
        for recipe in recipes:
            outputs = families.setdefault(_other_inputs(recipe, marker), set())
            outputs.add(tuple(sorted(key for key, _qty in recipe.outputs)))
        if all(len(outputs) > 1 for outputs in families.values()):
            out[marker] = len(recipes)
    return out


def candidates(graph, token_kinds, loot_categories=None):
    """`[(recipe, ground)]` for every recipe a declaration or the marker rule implicates.

    BEFORE THE GUARD, deliberately exposed. The difference between this and `demoted` is the
    only thing that says how much of the claim the guard withheld, and #211's report asked for
    the exclusion to be countable rather than for it to be silent.
    """
    declared = tokens_mod.LOOT_TABLE_CATEGORIES if loot_categories is None else loot_categories
    markers = frozenset(annotation_markers(graph, token_kinds))
    out = []
    for recipe in graph.recipes:
        if recipe.category in declared:
            out.append((recipe, LOOT_TABLE))
        elif any(not markers.isdisjoint(ing.alternatives) for ing in recipe.inputs):
            out.append((recipe, ANNOTATION))
    return out


def verdicts(graph, token_kinds, loot_categories=None):
    """`([(recipe, ground)], {recipe id(): ground})`: every candidate, and those demoted.

    BOTH HALVES FROM ONE WALK, because `mark` needs both and the guard needs the candidate set
    entire before it can answer for any single member. Two calls would be two walks and, worse,
    two chances for the second to see a different set than the first guarded against.

    THE GUARD: demote a recipe only when EVERY output of it has some OTHER producer that is
    not itself a candidate. It is the safety property of this whole module, and it is what
    makes "priced out" honest rather than a slower way of deleting things.

    WITHOUT IT, PRICING OUT IS THE SAME REGRESSION AS DROPPING. `_relax` only ever lowers a
    price, so a recipe priced above every alternative stops lowering its output -- and if it
    was the ONLY producer, the output falls back to whatever `_seed` gave it, which for a key
    nothing else makes is `BASE_RAW_COST`. That is the reported `imp_skin` 248.35 -> 1.0, and
    the mechanism is not the safety property: dropping and pricing out reach it identically.

    EVALUATED PER RECIPE, NOT PER MARKER, which is the granularity the claim actually has.
    Measured on the reference graph: 340 of 343 `TechReborn.Scrapbox` entries demote and 3 do
    not, because `ebwizardry:crystal_silver_plating`, `ebwizardry:ethereal_crystalweave` and
    `contenttweaker:alumite_fork` have no other producer in this pack. A category-level
    verdict cannot express that and would strand all three at 1.0.

    "NOT ITSELF A CANDIDATE" RATHER THAN "NOT DEMOTED", so the answer does not depend on the
    order recipes are visited. Two annotation cards covering each other's outputs would
    otherwise each see the other as a real alternative and both demote, orphaning the key.
    """
    pool = candidates(graph, token_kinds, loot_categories)
    implicated = {id(recipe) for recipe, _ground in pool}
    out = {}
    for recipe, ground in pool:
        covered = True
        for key, _qty in recipe.outputs:
            if not any(other is not recipe and id(other) not in implicated
                       for other in graph.producers(key)):
                covered = False
                break
        if covered:
            out[id(recipe)] = ground
    return pool, out


def demoted(graph, token_kinds, loot_categories=None):
    """`{recipe id(): ground}` alone, for a caller that does not need the withheld count.

    The rule and the reasoning are in `verdicts`, which is the one implementation.
    """
    return verdicts(graph, token_kinds, loot_categories)[1]


def mark(graph, token_kinds, loot_categories=None):
    """Set `Recipe.not_production` across `graph`, and report what was done and withheld.

    IDEMPOTENT AND TOTAL: every recipe is assigned, so calling this with a different token map
    CLEARS marks the previous map made rather than leaving them behind. A server that reloads
    `data/tokens.json` gets the new answer and not the union of two.

    Returns `{"demoted": {ground: n}, "withheld": {ground: n}, "markers": {key: recipes}}`.
    `withheld` is the guard's count, and it is returned rather than logged because it is the
    number that says whether the rule is drifting: a ground whose candidates are increasingly
    withheld is a ground whose evidence has stopped matching the pack.
    """
    pool, marks = verdicts(graph, token_kinds, loot_categories)
    counts = {ground: 0 for ground in GROUNDS}
    withheld = {ground: 0 for ground in GROUNDS}
    # TOTAL, not just the candidates: a recipe this token map does not implicate must come out
    # of a mark an earlier map made, or a server reloading `data/tokens.json` holds the union.
    for recipe in graph.recipes:
        recipe.not_production = marks.get(id(recipe))
    for recipe, ground in pool:
        tally = counts if recipe.not_production else withheld
        tally[ground] += 1
    return {"demoted": counts, "withheld": withheld,
            "markers": annotation_markers(graph, token_kinds)}


def report(counts):
    """One line per ground, for the build log and the `stats` command. Never empty.

    A GROUND WITH NOTHING DEMOTED STILL PRINTS. "No line" and "zero" read identically in a log
    and mean opposite things: the first is a rule that did not run, the second is a rule that
    ran and found nothing. #211's report is that silent truncation reads as "we considered
    everything" when it did not, and a missing line is the same failure one level up.
    """
    lines = []
    for ground in GROUNDS:
        lines.append("%s: %d recipes priced out, %d withheld by the "
                     "alternative-producer guard -- %s"
                     % (ground, counts["demoted"].get(ground, 0),
                        counts["withheld"].get(ground, 0), GROUND_REASON[ground]))
    return lines
