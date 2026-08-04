"""Resolve a target item into a crafting tree, pruned against what you already have.

The three things that make this non-trivial, and how each is handled:

1. CYCLES. Recipe graphs are not DAGs (ingot -> block -> ingot). Guarded by an
   explicit ancestor set per path; a repeat is emitted as a `cycle` leaf rather
   than recursed into. DO NOT replace this with a global visited set -- that would
   wrongly prune legitimate diamond-shaped reuse of the same intermediate.

2. RECIPE CHOICE. Items have many recipes and the best one depends on inventory,
   so choice belongs here, not in the extractors. Scored by how much of the recipe
   is already satisfied, then by simplicity. Overridable per item.

3. INVENTORY IS CONSUMED, NOT JUST CHECKED. `have` is drawn down as the tree is
   built, so two sibling branches cannot both claim the same 5 redstone. This is
   why the walk is single-pass and ordered rather than a pure function per node.
"""

import collections
import math

from .cost import input_cost, recipe_cost
from .defaults import DEFAULT_MAX_NODES
from .machines import is_hand_crafting
from .model import base_key, merge_slots, split_key

STATUS_HAVE = "have"        # fully covered by inventory
STATUS_PARTIAL = "partial"  # some from inventory, remainder crafted
STATUS_CRAFT = "craft"      # crafted from sub-ingredients
STATUS_RAW = "raw"          # no recipe known and not in inventory -> shopping list
STATUS_SOURCE = "source"    # an infinite generator you own makes this; nothing to plan
STATUS_CYCLE = "cycle"      # recipe loops back on an ancestor
STATUS_DEPTH = "depth"      # hit the depth/size cap
# A pack placeholder standing in for an instruction: loot, a quest gate, a class of
# materials, or a mechanic. Not craftable and not shoppable, so it leaves the shopping list
# and is reported on its own. See tokens.py.
STATUS_TOKEN = "token"
# The ProjectE transmutation network can make this: the item has an EMC value AND somebody
# has learned it. A TERMINATOR IN THE `source` FAMILY RATHER THAN A RECIPE, because it is not
# a crafting step -- it is a thing you already effectively have, and the plan should say so
# and stop. See projecte and #50.
STATUS_EMC = "emc"

# How many INTERCHANGEABLE recipes must tie before a node admits the pick was arbitrary. #181.
#
# THREE IS A JUDGEMENT AND THE MEASUREMENT IS WHY, over 23,476 multi-producer keys on the
# reference graph. A bare score tie is useless as a trigger -- it fires on 33.5% of them, and
# a mark that fires on a third of a tree is the failure #136 measured for `producers == 0`.
# Requiring the tied recipes to be the SAME OFFER cuts that to 6.2%, and requiring three of
# them to 1.3%, which is 294 keys:
#
#     trigger at >=2   tied 7866 (33.5%)   interchangeable 1449 (6.2%)
#     trigger at >=3   tied  976 ( 4.2%)   interchangeable  294 (1.3%)
#     trigger at >=25  tied   47 ( 0.2%)   interchangeable   47 (0.2%)
#
# At >=25 the two columns are IDENTICAL: every large tie in this pack is structurally the
# same recipe repeated. Below it they diverge sharply, and 22,027 of the 23,476 keys have a
# largest interchangeable subset of exactly 1 -- so when the score ties, the tied recipes are
# usually genuinely different offers and the pick was not arbitrary at all. The tie is
# necessary and NOT sufficient, which is the whole reason this is not simply a tie badge.
#
# Two rather than three was rejected deliberately: it adds 1,155 keys whose honest wording is
# "either of these two", which is real but weak, and takes the mark from 1.3% to 6.2% of
# nodes. Lowering it later is a one-line change; recovering from a badge people have learned
# to ignore is not.
TIE_MIN = 3


def _count_cycles(node):
    """How many cycle leaves this subtree bottomed out on."""
    n = 1 if node.get("status") == STATUS_CYCLE else 0
    return n + sum(_count_cycles(c) for c in node.get("children") or ())


def _count_nodes(node):
    return 1 + sum(_count_nodes(c) for c in node.get("children") or ())


def _index_pool(pool):
    """`base key -> the pool keys sharing it`, so a wildcard lookup is not a pool scan.

    Built ONCE, in __init__, and never invalidated. That is safe because the pool's KEY
    SET is fixed for a solver's lifetime: `take` only ever decrements existing entries,
    nothing inserts, and `_restore` swaps in a copy of the same keys. If you ever add a
    key to `pool`, this index is what breaks -- update it here too.
    `TestPool.test_pool_key_set_is_fixed_for_a_solvers_lifetime` is the tripwire.

    Without it, `take` filtered all 3,389 stocked keys on every call in order to use one
    of them, which cost 23 million `split_key` calls and 20 seconds for a 4,000-node
    plan. It read as linear and was quadratic: more nodes means more takes, each O(pool).
    """
    index = {}
    for key in pool:
        index.setdefault(split_key(key)[0], []).append(key)
    return index


class Solver:
    def __init__(self, graph, have=None, raw=None, pinned=None,
                 max_depth=24, max_nodes=DEFAULT_MAX_NODES, craftables=None,
                 branch_tries=4,
                 work_budget=None, machine_states=None, costs=None, free_sources=None,
                 token_kinds=None, dimension_gates=None, emc_available=None):
        self.g = graph
        self.pool = collections.Counter(have or {})
        self._by_base = _index_pool(self.pool)
        self.raw = set(raw or ())            # user-declared "stop here" items
        self.craftables = set(craftables or ())  # AE2 autocraftable -> treat as have
        # {key: why} for outputs of an infinite generator the player owns. These terminate
        # a branch like stock does, but are NOT added to `pool`: a pool is finite and would
        # report a made-up number as "drawn from stock". Draw is tallied separately so the
        # quantity stays visible. See generators.py.
        self.free_sources = dict(free_sources or {})
        self.from_sources = collections.Counter()
        # Keys the ProjectE network can transmute: learned AND carrying an EMC value. Held
        # apart from `free_sources` even though both terminate a branch, because they make
        # DIFFERENT claims and a plan has to be able to say which. A generator is infinite
        # and free; EMC is finite and fungible, and the row has to name its grounds
        # ("EMC 2,048, learned") so a reader can check it. See projecte.available and #50.
        self.emc_available = set(emc_available or ())
        self.from_emc = collections.Counter()
        # {item key: frozenset of acceptable recipe ids}, from pins.resolve. A SET, not
        # one id: a pin that lapsed onto its category accepts every recipe in it, and
        # this class keeps its own ranking among whatever is acceptable rather than being
        # handed a choice someone else made by dump order.
        #
        # Renamed from `overrides` when the value type changed, ON PURPOSE: `overrides`
        # also names the machine-availability map, and a caller left passing the old
        # `{key: rid}` shape now fails at the call rather than silently accepting no
        # recipe (a bare string is iterable, so `rid in "hei:x:1"` is a substring test).
        self.pinned = {k: frozenset(v) for k, v in (pinned or {}).items()}
        self.max_depth = max_depth
        self.max_nodes = max_nodes
        # `{ore key: dimension name}` for an ore only an unvisited dimension generates.
        # Reporting only: `cost` has already priced the trip, and this is what lets the
        # plan say which one. Empty gates behave exactly as before #112.
        self.dimension_gates = dimension_gates or {}
        self.branch_tries = branch_tries
        self.nodes = 0
        # Monotonic work counter. `nodes` is REWOUND when a backtrack discards a subtree,
        # so discarded work never counts toward max_nodes -- with branch_tries retries at
        # every level that makes the search effectively unbounded, and on a 340k-recipe
        # graph it simply never returns. This counter is never rewound, so it is the only
        # real termination guarantee. DO NOT add it to a snapshot/restore.
        self.work = 0
        self.work_budget = work_budget or max(50000, max_nodes * 20)
        self.exhausted = False
        # {category: (state, why)} from machines.resolve. Absent category means unknown,
        # which is treated as buildable rather than unavailable: refusing to plan through
        # a machine merely because it could not be identified would hide real routes.
        self.machine_states = machine_states or {}
        self.machines_needed = {}
        # {item key: why} for a pin the cycle guard had to ignore. See
        # `_note_overruled_pin`; reported on the plan rather than swallowed.
        self.pins_overruled = {}
        # {key: kind} from tokens.resolve. Empty by default so a bare Solver behaves exactly
        # as before; the CLI and the server supply it. See tokens.py.
        self.token_kinds = dict(token_kinds or {})
        self.tokens_needed = collections.Counter()
        # AND WHICH RECIPES THE TOKEN MAP MAKES NON-ROUTES, which has to happen here rather
        # than only in `cost.estimate`: `--no-cost` builds a Solver with `costs=None` and would
        # otherwise rank a scrapbox loot table as ordinary production. Memoised on the graph,
        # so when a cost table was built first this is a dict comparison. See notproduction.
        graph.mark_non_production(self.token_kinds)
        # Precomputed cost per item, from cost.estimate. Without it recipe choice is greedy
        # and local, which is how a two-step chemical route lost to an enormous chain
        # through machines that happened to be owned. A RANKING, not a lower bound and not
        # a quantity -- see the docstring in cost.py before doing arithmetic with these.
        self.costs = costs
        self.leaf_totals = collections.Counter()
        self.used_from_stock = collections.Counter()

    # ---- inventory ------------------------------------------------------

    def _equivalent(self, key):
        """The pool keys `key` may draw on, exact key first.

        Only a wildcard meta draws on anything but itself, so the common case never
        touches the index at all. `k != key` matters: a wildcard that is ITSELF stocked
        appears in its own base bucket, and counting it twice made `available` promise
        more than `take` could deliver.
        """
        base, meta = split_key(key)
        if meta != "*":
            return (key,)
        return (key,) + tuple(k for k in self._by_base.get(base, ()) if k != key)

    def available(self, key):
        """Stock for a key, counting a wildcard-meta variant as interchangeable."""
        return sum(self.pool.get(k, 0) for k in self._equivalent(key))

    def take(self, key, want):
        got = min(want, self.available(key))
        if got <= 0:
            return 0
        remaining = got
        # drain the exact key first, then wildcard-equivalent metas
        for k in self._equivalent(key):
            if remaining <= 0:
                break
            avail = self.pool.get(k, 0)
            if avail <= 0:
                continue
            used = min(avail, remaining)
            self.pool[k] -= used
            self.used_from_stock[k] += used
            remaining -= used
        return got - remaining

    # ---- choice ---------------------------------------------------------

    def slot_cost(self, key, qty=1):
        """What `qty` of `key` adds to a recipe's price, or 0.0 with no costs supplied.

        Delegates to `cost.input_cost` rather than reading `self.costs` directly, so an
        oredict key resolves to its cheapest member and a fluid is scaled to buckets by
        the same code that priced the recipe. A local copy of that arithmetic would be a
        second place for it to drift.

        Zero rather than infinity for the no-costs case ON PURPOSE: every candidate then
        ties and the cost tiebreaks below go inert, so a Solver built without `costs`
        behaves exactly as it did before cost became a factor in these choices.
        """
        if self.costs is None:
            return 0.0
        return input_cost(self.costs, key, qty, self.g.ore_members)

    def _alternative_rank(self, key, qty):
        """Sort key for one option in a slot: reachable-and-owned first, then cheapest."""
        score = 0.0
        if key.startswith("ore:"):
            members = self.g.ore_members.get(key[4:], [])
            score += max((self.available(m) for m in members), default=0) / 1e6
        else:
            score += min(self.available(key), 1e6) / 1e6
            if key in self.free_sources:
                score += 1.0   # an infinite source beats a finite pile of anything
            if key in self.craftables:
                score += 0.5
            # `_routable`, NOT `real_producers`, since #170: a bare key the graph makes only
            # under an NBT digest IS craftable, and scoring it as a dead end would rank it
            # level with a key nothing can reach while `expand` goes on to route it.
            if self._routable(key):
                score += 0.25
        return (score, -self.slot_cost(key, qty))

    def pick_alternative(self, ingredient):
        """Which of an input slot's alternatives to actually use.

        Availability first, then CHEAPEST. Without the cost tiebreak an unstocked slot had
        nothing to separate its options and fell through to whichever the dump happened to
        list first, which is how a plan for one Lapis picked Nether Lapis Ore and went off
        to compress Netherrack six times. See issue #29.

        THIS IS ALSO WHAT PRICES A RECIPE. `estimated_cost` passes this method to
        `cost.recipe_cost`, so whatever is chosen here is what the recipe is scored on. The
        two cannot drift apart, which is the point: they used to, and the ranker's price
        came from an option the expander never took.
        """
        alts = ingredient.alternatives
        if len(alts) == 1:
            return alts[0]
        return max(alts, key=lambda a: self._alternative_rank(a, ingredient.qty))

    def estimated_cost(self, recipe):
        if self.costs is None:
            return 0.0
        # `pick=self.pick_alternative` is load-bearing, not tidiness: it makes the recipe
        # score the cost of the branch `_build` will actually expand. DO NOT drop it back to
        # the default cheapest-alternative rule -- see the docstring on `recipe_cost`.
        return recipe_cost(self.costs, recipe, self.g.ore_members, self.machine_states,
                           pick=self.pick_alternative)

    def score_recipe(self, recipe, ancestors=frozenset()):
        """Higher is better: prefer recipes we can mostly satisfy from stock.

        A recipe that feeds back into an ANCESTOR is ranked below every recipe that does
        not, at any price. Without that, `ingot -> block -> 9 ingots` scores well (one
        simple input) and gets picked over a real production route, producing a plan that
        asks for the very thing being crafted. The cycle guard still catches it; this stops
        us choosing it. Feeding back into one of the recipe's OWN outputs is a weaker claim
        and is ranked as a tiebreak -- see the #172 block below for why the two cannot share
        one term.

        `own` catches two cases `ancestors` structurally cannot, both found by measuring #61:

          * A BYPRODUCT that feeds back. `_build` passes `ancestors | {key}`, so the key
            being planned is covered at every depth -- but a recipe emitting (Heart Fruit
            x12, Heart Fruit Seeds x1) while consuming Heart Fruit Seeds is cyclic through
            an output that is NOT the one being planned, and no ancestor set ever holds it.
          * `score_recipe` called with NO ancestors, which is what the recipe-chooser page
            does (`server.recipes_page`, so the order shown to someone about to pin). There
            a self-consuming recipe ranked top and was the tool's recommendation.

        THE TWO HALVES SIT ON OPPOSITE SIDES OF `cheap`, AND THAT IS THE WHOLE OF #172.
        They used to be one counter below it, with a note here saying it "only ever settles
        a cost TIE" -- true, and the defect: a route that consumes its own output wins
        OUTRIGHT whenever it is cheaper, and a cheap impossible route beats an expensive
        real one. Measured on the reference graph, 48 of 23,476 multi-producer keys had a
        cyclic winner while a clean route existed, and 17 of them produced a plan that
        bottoms out on a cycle leaf -- a plan for X whose shopping list contains X, which
        cannot be executed and does not look wrong.

        SO `-ancestor_cyclic` GOES ABOVE `cheap`: "this route consumes something already on
        the path to it" is a statement about whether the plan can be performed at all, and
        no price should outvote it. It is safe to promote, which was measured rather than
        assumed: 2,079 keys have a field that prices entirely at infinity, and promotion
        still moves only the keys the defect is about, because where the field ties at
        infinity this term already decides.

        AND `-own_cyclic` DELIBERATELY STAYS BELOW IT. Promoting the merged counter was
        built and measured and REGRESSES the case #61 added the `own` half for:

          * `minecraft:pumpkin` -- the Insolator takes Phyto-Gro, one Pumpkin Seed and
            water and gives back a Pumpkin AND the seed, at 129.90. Promoted, the pumpkin
            comes from transmuting a Melon at 164.18.
          * `integrateddynamics:menril_log` -- one Menril Sapling gives 6 wood and the
            sapling back, at 173.34, against 363.63 for crafting from Menril Essence.

        The seed comes back. Those are sustainable farms, not cycles, and telling a player
        to transmute melons rather than grow pumpkins is worse advice than the bug. So the
        soft claim settles ties, exactly where the merged counter used to sit, and the hard
        claim outranks price. Splitting moves 35 keys instead of 48 and keeps both farms.

        `own` catches what it always did, and the gate on `available(alt) < qty` still
        applies to both halves: an upgrade recipe you can feed from stock is a real route.
        """
        satisfied = 0
        # TWO COUNTERS, NOT ONE, AND THEY SIT ON OPPOSITE SIDES OF `cheap`. See the
        # docstring; the short version is that "consumes something already on the path to
        # it" and "returns its own seed" are opposite claims and one counter cannot rank
        # both. DO NOT merge them back.
        ancestor_cyclic = 0
        own_cyclic = 0
        # Scored on MERGED slots, the same view `_build` will expand. Per slot, nine cells
        # asking for one clump each read as nine satisfied ingredients when stock held a
        # single clump, and a 3x3 of one thing looked three times less simple than a
        # recipe taking three different things.
        slots = self._merge_slots(recipe)
        # A recipe's OWN outputs count as ancestors. See the docstring for the two cases
        # this catches and `ancestors` cannot. On the reference graph it moves 78 routes,
        # among them Heart Fruit Seeds off `Rich Phyto-Gro + Heart Fruit Seeds + Water` and
        # onto `Heart Fruit`, and it cuts a Quartz Sliver line in the Aedialite Fragment
        # plan from 815 to 30.
        own = {key for key, _qty in recipe.outputs}
        # `_chance` IS CORRECTLY IGNORED HERE. The question is "does what I have cover this
        # slot", and a retained slot's `qty` is already what you must hold; owning one chaos
        # shard satisfies the slot whether the run spends it or not.
        for alt, qty, _options, _chance in slots:
            if (self.available(alt) >= qty or alt in self.craftables
                    or alt in self.free_sources):
                satisfied += 1
            if self.available(alt) < qty:
                # ANCESTOR FIRST, so a slot that is both counts once and counts as the
                # worse of the two. `own` is the softer claim and must not shadow the hard
                # one; measured on the reference graph no winning recipe has both, so this
                # is a rule about what the numbers MEAN rather than one that moves a plan.
                if alt in ancestors:
                    ancestor_cyclic += 1
                elif alt in own:
                    own_cyclic += 1
        # simplicity tiebreak: fewer ingredients, and prefer plain crafting over machines
        simple = 1.0 / (1 + len(slots))
        plain = 0.1 if is_hand_crafting(recipe.category) else 0.0
        avail = self.availability_rank(recipe)
        # ORDER MATTERS, AND THE FIRST TERM IS "IS THIS PRODUCTION AT ALL". A container
        # fill/empty is not, and neither is a loot table or a JEI automation card, so all three
        # lose to any real recipe regardless of how well stocked they look. They share one term
        # rather than getting one each because the claim is identical -- whatever else is true
        # of it, this entry is not a way to obtain the thing -- and two terms would be a silent
        # ordering decision between statements that have no order.
        #
        # THE DEMOTION IS RANKED HERE AS WELL AS PRICED, NOT INSTEAD OF IT.
        # `NON_PRODUCTION_PENALTY` already puts these last on `cheap` in every case measured,
        # and this term is what makes that hold when the alternatives are themselves priced at
        # infinity -- where `cheap` ties at negative infinity and cannot decide.
        #
        # After that, the ESTIMATED TOTAL COST dominates: it already accounts for machine
        # availability (via cost.MACHINE_COST) and for how expensive the whole subtree is,
        # which local signals cannot see. `satisfied`/`simple` only break ties between
        # comparable routes. DO NOT promote `avail` above cost -- doing that is what made the
        # solver prefer a million-bucket chain through an owned machine.
        cost = self.estimated_cost(recipe)
        cheap = -cost if cost != float("inf") else float("-inf")
        # `ore_backed` sits BELOW cost and stock and ABOVE `simple + plain`, and both
        # halves of that are deliberate. Below cost, because it must never override a
        # real price difference -- it exists to settle exact ties, which 26.8% of produced
        # keys have. Above `simple + plain`, because that is the term it has to beat:
        # `plain` gives hand-crafting +0.1 and so prefers unpacking a decorative block
        # over smelting an ore. Moved below it, this goes inert. See `ore_backed`.
        return (0 if (recipe.transfer or recipe.not_production) else 1,
                -ancestor_cyclic, cheap, -own_cyclic,
                satisfied, self.ore_backed(recipe, slots), simple + plain, avail)

    def offer_shape(self, recipe, key):
        """What makes two recipes the SAME OFFER rather than merely equally scored. #181.

        The merged slot view, because that is what `_build` expands and what `score_recipe`
        counts; the per-run output of the key being planned, because two recipes yielding
        1000 and 1 are not the same offer however they score; the category, because a
        different machine is a different thing to go and build; and both production flags.

        BOTH FLAGS, not just `transfer`. A key with a real route and a loot-table route has two
        candidates whose merged slots, per-run output and category can all coincide, and
        calling those the same OFFER would report the pick as an arbitrary coin toss when it
        was the one decision in the ranking that is not arbitrary at all.

        SLOT IDENTITY IS DELIBERATELY EXCLUDED, and it is the crux. The 62 Digital Mob
        Agonizer recipes for `fluid:lifeessence` differ precisely in WHICH four data models
        they accept, and that difference is the entire reason a player might prefer one of
        them -- they will have some models and not others. Including the identities would
        make every shape unique, the interchangeable subset 1 everywhere, and the whole
        measurement vacuous. The shape is the OFFER (what it costs you structurally), not
        the ingredients.

        `options` IS ALREADY A COUNT and not a list. `model.merge_slots` stores the widest
        slot's `len(ing.alternatives)`, so `len(options)` raises rather than lying, which is
        the cheap version of this mistake; the expensive version is a field that happens to
        be sized and quietly measures something else.
        """
        slots = self._merge_slots(recipe)
        per_run = sum(qty for out, qty in recipe.outputs if out == key)
        # THE CONSUME CHANCE IS DELIBERATELY NOT PART OF THE SHAPE. A shape is what two
        # recipes have to share to count as the same OFFER for #181's tie reporting, and
        # folding `chance` in would split ties that a player would call identical. It is also
        # the conservative choice while `p` is new: every slot in every existing dump is 1.0,
        # so adding it would move no shape today and could quietly re-partition ties the day
        # a dump carries retained inputs. If they should change the offer, that is a ranking
        # decision with its own fixtures, not a side effect of #175.
        #
        # `not_production` IS PART OF IT, and the two rules do not contradict. #175's question
        # is whether a slot's chance changes what the offer COSTS, and today it cannot. #211's
        # is whether the entry is an offer at all: a loot table and a real recipe can agree on
        # every other term here, and reporting them as one interchangeable offer would call the
        # single non-arbitrary decision in the ranking a coin toss.
        return (tuple(sorted((options, qty) for _alt, qty, options, _chance in slots)),
                per_run, recipe.category, bool(recipe.transfer),
                recipe.not_production or "")

    def _interchangeable_count(self, scored, chosen, key, cache, variant_of):
        """How many recipes tied with `chosen` are the same offer as it. 1 when none are.

        `variant_of` maps a recipe id to the NBT VARIANT of `key` that recipe makes, for the
        #170 substitution where nothing makes `key` itself. `offer_shape` reads the per-run
        output of the key being planned, and a substituted recipe does not output `key` at
        all, so without this every such candidate would report a per-run of 0 and a recipe
        yielding 1 would read as the same offer as one yielding 64.

        READS `scored`, WHICH `expand` ALREADY BUILT. No `score_recipe` call happens here and
        none may be added; see the note at the ranking site. The only new work is
        `offer_shape`, and it is skipped entirely unless the tie is already big enough to
        reach `TIE_MIN`, so on the overwhelming majority of nodes this costs one dict lookup.

        THE SUBSET MUST CONTAIN THE CHOSEN RECIPE, which is narrower than "the largest
        interchangeable subset among the tied" and is deliberate. The mark says the pick was
        arbitrary; that is a claim about the recipe actually taken, so counting a larger
        group the winner is not part of would put a true number beside a false statement.
        On `fluid:lifeessence` the two readings coincide at 62.

        `cache` is per-`expand`, keyed by score, because backtracking can settle on a recipe
        from a different score band than the one it started with and each band is counted at
        most once.
        """
        score = next((s for s, r in scored if r is chosen), None)
        if score is None:                      # not one of the ranked candidates
            return 1
        if score not in cache:
            tied = [r for s, r in scored if s == score]
            # Cheap gate: the interchangeable subset is a SUBSET of the tied set, so a tie
            # too small to reach the threshold cannot produce a mark and needs no shapes.
            cache[score] = (collections.Counter(
                self.offer_shape(r, variant_of.get(r.rid, key)) for r in tied)
                if len(tied) >= TIE_MIN else None)
        counts = cache[score]
        if counts is None:
            return 1
        return counts.get(self.offer_shape(chosen, variant_of.get(chosen.rid, key)), 1)

    def ore_backed(self, recipe, slots=None):
        """1 when every raw leaf this recipe rests on is something you mine, else 0.

        Issue #61: with nothing in stock, a plan for one Diamond said "go and get a Block
        of Diamond Panel", a ForgeMultipart microblock consumed by two recipes. It was not
        mispriced. `cost.BASE_RAW_COST` is 1.0 for EVERY key no recipe produces, so the
        44 candidate recipes for Diamond all price at exactly the same number and the
        winner was whichever `max` saw first, which is dump order. Smelting an ore ties
        with unpacking a decorative panel because both rest on one raw leaf.

        Worse, `simple + plain` below actively prefers the panel: unpacking is
        hand-crafting and earns the `plain` bonus, while smelting an ore does not.

        It only bites on an item you do NOT have, which is why the reported plans all came
        from an empty pool. With the reference network's real stock this moves 3 routes;
        with the pool emptied it moves 14.

        So the tie needs a signal, and this is the only one the current dump carries: see
        `Graph.world_ores`. Measured against the real ranking on the reference graph with
        an empty pool, it moves 14 of 46,727 routes and every one of them reads better --
        Diamond off the microblock and onto Volcanic Diamond Ore, Lapis Lazuli off a Lapis
        Blue Chicken, Coal and Emerald and Gold Nugget off contenttweaker loot tokens.

        A route resting on NO raw leaf deliberately scores the same 0 as one resting on
        junk, rather than ranking above ore. DO NOT make this three-way. Returning 2 there
        was built and measured, and it costs 64 further routes on the reference graph to
        buy nothing: ranked above `simple + plain` it avoids a raw leaf at ANY price in
        complexity, so `Cherry Fence <- Cherry Wood Planks + Stick` becomes a nine-slot
        spelling of itself and `Tape Measure <- Iron Ingot + Tape` becomes `Iron Ingot +
        Iron Ingot + Tape Measure Reel + Iron Ingot`. Deciding between two routes on how
        many slots they have is what `simple` is for, and 2 overrides it.

        Not a classifier on the key itself. Demoting all NBT-discriminated raw leaves was
        rejected in #61 for a measured reason: `deepmoblearning:data_model_experiencedcori`
        is discriminated, raw, and a genuine item you obtain by playing.

        `slots` is `_merge_slots(recipe)` when the caller already has it. What counts as a
        dead end has to be what `_build` will ACTUALLY dead-end on, in the same order, or
        this ranks a route by a shape the expander does not produce -- the mistake issue
        #29 is about. So: an infinite source, a user-declared `raw` stop, and AE2
        autocraftability all terminate a branch before any recipe lookup, and stock counts
        only when it covers the whole slot. Judging stock by "is there any" made a slot
        needing 64 with 1 on the shelf read as satisfied.
        """
        if slots is None:
            slots = self._merge_slots(recipe)
        leaves = []
        # `_chance` IS CORRECTLY IGNORED HERE TOO: an unobtainable retained input is as
        # much a dead end as an unobtainable ingredient. You cannot run the recipe without it.
        for alt, qty, _options, _chance in slots:
            if alt.startswith("ore:"):
                # An unresolvable oredict slot is itself the dead end; a resolvable one
                # was already reduced to a concrete member by `pick_alternative`.
                if not self.g.ore_members.get(alt[4:]):
                    leaves.append(alt)
                continue
            if alt in self.free_sources or alt in self.craftables:
                continue
            if alt in self.raw:
                # Declared "stop here" by the user, so it is a dead end even though the
                # graph may know a recipe for it.
                leaves.append(alt)
                continue
            if self.available(alt) >= qty:
                continue
            # `_routable`, NOT `real_producers`, since #170. THIS LIST MUST MIRROR WHAT
            # `expand` ACTUALLY STOPS ON, which is the sentence above about `raw` and the
            # reason this method reads terminators rather than producer counts: a bare key
            # made only under an NBT digest is routed now, so counting it as a leaf would
            # have the ranker judge a recipe by a dead end the solver does not hit.
            if not self._routable(alt):
                leaves.append(alt)
        if not leaves:
            return 0
        world = self.g.world_ores
        return 1 if all(a in world for a in leaves) else 0

    def availability_rank(self, recipe):
        """2 = machine on hand, 1 = buildable or unidentified, 0 = proven unavailable."""
        state = self.machine_states.get(recipe.category)
        if state is None:
            return 1
        return {"have": 2, "buildable": 1, "unknown": 1}.get(state[0], 0)

    def pick_recipe(self, key, ancestors=frozenset()):
        candidates = self.g.real_producers(key)
        if not candidates:
            return None
        allowed = self.acceptable(key, candidates)
        return max(allowed or candidates,
                   key=lambda r: self.score_recipe(r, ancestors))

    def acceptable(self, key, candidates):
        """The candidates a pin permits, or [] when there is no pin or it matches none.

        Empty rather than the full list when a pin matches nothing, so the caller decides
        what an unsatisfiable pin means. Falling back silently is the right answer for
        planning -- a plan is better than an error -- but `pins.resolve` has already
        reported the lapse, so nothing is being hidden.
        """
        wanted = self.pinned.get(key)
        if not wanted:
            return []
        return [r for r in candidates if r.rid in wanted]

    # ---- expansion ------------------------------------------------------

    def resolve_ore(self, key, need, ancestors, depth):
        """An `ore:` node resolves to whichever concrete member suits us best."""
        members = self.g.ore_members.get(key[4:], [])
        if not members:
            self.leaf_totals[key] += need
            return {"key": key, "name": self.g.display(key), "kind": self.g.kind(key),
                    "label": self.g.bare_name(key), "need": need,
                    "status": STATUS_RAW, "note": "oredict members unknown"}
        # Same three-tier rule as `pick_alternative`, for the same reason: with nothing in
        # stock every member ties on availability and the choice used to fall to dump order.
        best = max(members, key=lambda m: (self.available(m), m in self.craftables,
                                           -self.slot_cost(m, need)))
        child = self.expand(best, need, ancestors, depth)
        return {"key": key, "name": self.g.display(key), "kind": self.g.kind(key),
                "label": self.g.bare_name(key), "need": need,
                "status": "oredict", "resolved_to": best, "children": [child]}

    def expand(self, key, need, ancestors=frozenset(), depth=0):
        self.nodes += 1
        self.work += 1
        # `kind` travels with every node so the renderers never need the graph to know a
        # water row is a fluid, and so `--json` output is self-describing.
        node = {"key": key, "name": self.g.display(key), "kind": self.g.kind(key),
                "label": self.g.bare_name(key), "need": need}

        if self.work > self.work_budget:
            self.exhausted = True
            node["status"] = STATUS_DEPTH
            self.leaf_totals[key] += need
            return node

        if self.nodes > self.max_nodes or depth > self.max_depth:
            node["status"] = STATUS_DEPTH
            self.leaf_totals[key] += need
            return node

        if key.startswith("ore:"):
            return self.resolve_ore(key, need, ancestors, depth)

        from_stock = self.take(key, need)
        if from_stock:
            node["from_stock"] = from_stock
        remainder = need - from_stock
        if remainder <= 0:
            node["status"] = STATUS_HAVE
            return node

        # Checked before `raw`/`craftables` and before any recipe lookup: if you own an
        # infinite source for this, there is nothing to plan and nothing to buy.
        if key in self.free_sources:
            node["status"] = STATUS_SOURCE
            node["note"] = self.free_sources[key]
            self.from_sources[key] += remainder
            return node

        # AFTER stock and free sources, BEFORE `raw`/`craftables` and any recipe lookup, and
        # each half of that placement is a claim.
        #
        # After stock, because spending what you already hold is strictly better than
        # spending EMC, and `take` has drawn the pool down so only the shortfall is charged.
        # After free sources, because those are genuinely free and this is not.
        #
        # Before recipes, because that is the whole point: `erebus:materials` has a recipe
        # in the graph -- it is "dropped by a dungeon", expressed as a pseudo-item -- and
        # descending into it produces the dead end #50 was reported for. A player with a
        # working transmutation network does not go and farm a dungeon for an item their
        # network already makes.
        if key in self.emc_available:
            node["status"] = STATUS_EMC
            node["note"] = "EMC %s, learned" % "{:,}".format(self.g.emc.get(key, 0))
            self.from_emc[key] += remainder
            return node

        if key in self.raw or key in self.craftables:
            node["status"] = STATUS_HAVE if key in self.craftables else STATUS_RAW
            if key in self.craftables:
                node["note"] = "AE2 can autocraft"
            else:
                self.leaf_totals[key] += remainder
            return node

        if key in ancestors:
            node["status"] = STATUS_CYCLE
            self.leaf_totals[key] += remainder
            return node

        # A WORLD ORE IS AN ACQUISITION UNIT: you go and hit it with a pick. Stopping here
        # rather than descending is what makes a plan bottom out at "18 Sednanite Ore"
        # instead of walking a denomination ladder to "18 Sednanite Nugget" -- #106, where
        # the nugget was the only rung with no producer and therefore the only place the
        # walk COULD stop, while the ore had two Plasmatic Condenser recipes and so looked
        # craftable.
        #
        # Checked AFTER stock, free sources, `raw` and `craftables`: each of those is a
        # better answer than "go mining" when it applies, and `take` has already drawn the
        # pool down, so only the shortfall is ever charged to a pickaxe.
        #
        # NOT CONDITIONAL ON THE CRAFTED ROUTE BEING WORSE, and that is provable rather than
        # merely measured. `cost._relax` prices an output at `machine_entry + inputs / qty`,
        # and the machine entry is NOT divided by the yield -- so no crafted route can price
        # below the cheapest possible entry, `MACHINE_COST["have"]`, which is 1.0, which is
        # `BASE_RAW_COST`, which is what mining now costs. A comparison here would be a
        # branch that can never take its other side.
        #
        # `AMachineCostsAtLeastAsMuchAsMiningTest` pins that equality, so lowering
        # `MACHINE_COST["have"]` below `BASE_RAW_COST` fails there rather than silently
        # making this unconditional stop the wrong call. That is the line to revisit if it
        # ever moves; meanwhile `/recipes?item=<ore>` still lists every way to make one, so
        # the routes are not lost from the tool, only from the plan tree.
        #
        # Consistent with the measurement, too: of 286 world ores on the reference graph, 88
        # price below `BASE_RAW_COST` and every one of those 88 does so because it is IN
        # STOCK, which `take` above has already spent.
        if key in self.g.world_ores:
            node["status"] = STATUS_RAW
            # WHERE, when the graph knows the ore only generates somewhere you have never
            # been (#112). The cost model already charges `DIMENSION_COST` for it, and a
            # route that got dearer without saying why is worse than one that never
            # mentioned the trip: the number is invisible and the plan just looks wrong.
            dim = self.dimension_gates.get(key)
            node["note"] = ("mined on %s, and you have not been there" % dim
                            if dim else "mined, not crafted")
            if dim:
                node["dimension"] = dim
            self.leaf_totals[key] += remainder
            return node

        candidates = self.g.real_producers(key)
        kind = self.token_kinds.get(key)
        variant_of = {}
        if not candidates and not kind:
            # NOTHING MAKES THIS EXACT KEY AND THE GRAPH MAKES AN NBT VARIANT OF IT, so a
            # demand for the bare item is satisfied by the variant's recipe. #170: four
            # recipes ask for `animus:kama_bound`, the Alchemy Array makes
            # `animus:kama_bound#fd1adc426e12`, and until now the two halves sat in one graph
            # never touching -- the plan said "no known source" for an item the graph knows a
            # 60.0 route to. See `Graph.variant_subsumption` for the relation and for the
            # direction, which is one way only.
            #
            # AFTER THE TOKEN CHECK, matching the order `cost._seed` seeds in. A placeholder
            # is already an instruction with its own price for what the player must go and DO,
            # so the price and the badge have to agree about which of the two answers a reader
            # gets; a token key with a produced variant would otherwise be routed here and
            # priced there.
            candidates, variant_of = self._variant_candidates(key)
        if not candidates:
            if kind:
                # Tallied apart from `leaf_totals`, which IS the shopping list. "1 Dungeon
                # Drop" on a list of materials to gather reads as a thing to acquire; it is
                # an instruction, and it belongs with the other instructions.
                node["status"] = STATUS_TOKEN
                node["token_kind"] = kind
                self.tokens_needed[key] += remainder
                return node
            node["status"] = STATUS_RAW
            other = self.g.reachable_form(key)
            if other:
                # SAY WHAT WE CANNOT DO, rather than pricing it. #136
                #
                # The reported case: a plan bottomed out on "Blaze Data Model (Superior)"
                # listed beside "128 Granite" as though it were a thing to go and fetch. It
                # is not. Deep Mob Learning levels a model by killing mobs in a Simulation
                # Chamber -- a kill counter, not a recipe -- so no dump can carry it and the
                # graph has no route to that tier at all. Measured: 374 data-model keys on
                # the reference graph, 125 with any producer, and those 125 are exactly two
                # tiers, the craftable fresh model and Self-Aware.
                #
                # THE MARK IS DISPLAY-ONLY AND MUST STAY THAT WAY, WHICH IS NOT THE SAME AS
                # THE SET BEING UNPRICED. The underlying defect was `cost._seed` giving an
                # unreachable leaf `BASE_RAW_COST`, which made the tier the CHEAPEST thing in
                # the plan and won it the route; #176 fixed that where it belongs, by seeding
                # `graph.unsourced_keys` at `UNSOURCED_COST`. So the price and the badge read
                # one predicate and agree by construction. DO NOT move a price from HERE
                # instead: this branch runs once per plan node, after the routing decision
                # that a price exists to inform, and no test in this module would notice.
                #
                # SINCE #170, REACHING HERE IS ITSELF NARROWER: a bare key whose variants are
                # routable is planned through one before the branch is taken, so what arrives
                # is a key neither #176 nor #170 could route -- every variant made from the
                # bare key or a sibling, or none of them reachable.
                node["unsourced"] = True
                # ONE WORDING PER CLAIM, because a reader has to be able to act on the
                # difference: an NBT STATE means "you have the item, this tier of it is out of
                # reach", while a processed FORM means "this shape of the material is not made
                # at all, use the other one". Collapsing them would make the second read as
                # though levelling were involved. `_unsourced_note` holds the set; this said
                # TWO before #170 added the produced-variant sentence, and there are three.
                node["note"] = self._unsourced_note(key, other)
            self.leaf_totals[key] += remainder
            return node

        nxt = ancestors | {key}
        # THE SCORES ARE KEPT, NOT DISCARDED, AND THAT IS #181's WHOLE COST. `sorted(key=...)`
        # calls `score_recipe` exactly once per candidate and then throws the values away, so
        # materialising them here is the SAME number of scoring calls -- measured, not
        # assumed: #181 left `plan-fluid-chain`'s `work` unchanged at 28,012, and `work` counts
        # every `expand` including the ones backtracking discards. That fixture reads 28,024
        # since #136 priced the storage blocks, which is a different branch being walked
        # rather than this loop scoring more often; #181's measured property is untouched.
        #
        # DO NOT RE-SCORE FROM A REPORTING PATH. A second `score_recipe` pass would be
        # correct, would produce identical output, and would be invisible in every test,
        # while roughly doubling the cost of the most expensive part of planning -- on a
        # graph where one fixture already spends 35% of its work budget. The scores are in
        # hand at the moment the winner is chosen; use those.
        scored = [(self.score_recipe(r, nxt), r) for r in candidates]
        # `key=` on the pair rather than sorting the pairs, because a Recipe is not orderable
        # and a full-tuple tie would otherwise compare the recipes themselves. Same stability
        # and same `reverse=True` as the `sorted` this replaces, so `ranked` is identical.
        scored.sort(key=lambda pair: pair[0], reverse=True)
        ranked = [recipe for _score, recipe in scored]
        # A stable sort, so a pin that accepts several recipes keeps them in score order
        # among themselves. The pinned ones move to the front rather than replacing the
        # list: if every one of them cycles, the backtracking below still has somewhere
        # to go, and a plan beats an error.
        pinned = self.pinned.get(key)
        if pinned:
            ranked.sort(key=lambda r: r.rid not in pinned)

        # Try recipes best-first and BACKTRACK out of any whose subtree loops back
        # on an ancestor. Uncrafting recipes (block -> 9 ingots) otherwise get
        # chosen for their single simple input and produce a plan that asks for the
        # item being crafted. Only accept a cycling recipe if every option cycles.
        best = None  # (rank_tuple, attempt, state_to_restore)
        tie_cache = {}   # score -> shape counts, per this expand. See _interchangeable_count.
        for rank, recipe in enumerate(ranked[: self.branch_tries]):
            if self.work > self.work_budget:
                break
            snapshot = self._snapshot()
            attempt = self._build(
                node, recipe, key, remainder, from_stock, nxt, depth,
                self._interchangeable_count(scored, recipe, key, tie_cache, variant_of),
                variant_of.get(recipe.rid))
            cycles = _count_cycles(attempt)
            if not cycles:
                self._note_overruled_pin(key, recipe)
                return attempt
            # All routes may cycle -- in this dataset smelting recipes are absent, so
            # ore -> ingot chains dead-end and every route eventually loops. Keep the
            # most informative attempt: fewest cycle leaves, then the largest
            # expansion, so the plan still shows the real ingredients it did resolve.
            score = (cycles, -_count_nodes(attempt), rank)
            if best is None or score < best[0]:
                best = (score, attempt, self._snapshot())
            self._restore(snapshot)

        if best is None:
            node["status"] = STATUS_RAW
            self.leaf_totals[key] += remainder
            return node
        _score, attempt, restore = best
        self._restore(restore)
        self._note_overruled_pin(key, attempt.get("recipe"))
        return attempt

    def _routable(self, key):
        """Whether `expand` will find a recipe for `key`, substitution included. #170.

        THE RANKER'S VERSION OF `expand`'s TERMINATORS, and it exists because there are now
        two ways to have a recipe. `ore_backed` decides what a candidate recipe bottoms out
        on and `_alternative_rank` decides whether a slot option is a dead end; both used to
        ask `real_producers`, which is the whole answer for a key that is not a bare
        NBT-digest stem and false for one that is. A ranker that believes the solver will stop
        somewhere it does not is the drift `real_producers`' own docstring warns about, one
        direction over.

        THROUGH `_variant_candidates` RATHER THAN A SECOND SPELLING of its two clauses. It
        returns empty on the first dict miss for all but 501 of the graph's live keys, so this
        costs one lookup on the hot path.
        """
        return bool(self.g.real_producers(key)) or bool(self._variant_candidates(key)[0])

    def _variant_candidates(self, key):
        """`(recipes, {recipe id: variant})` for a bare demand only a VARIANT of which is made.

        Empty for almost every key: `Graph.satisfying_variants` fires on 501 of the reference
        graph's 171,271 live keys, and 88 of those 501 are consumed by any recipe. #170.

        A FIELD OF ALTERNATIVES RATHER THAN ONE CHOSEN VARIANT, which is the point. Handing
        `expand` the producing recipes of EVERY satisfying variant means the substituted route
        is scored, backtracked out of and tie-flagged by the machinery that already does that
        for an ordinary key: `score_recipe` ranks a cheap cycle below a performable route
        (#172), the cycle guard rejects a subtree that loops, a pin overrules the ranking, and
        #181's interchangeable count discloses a pick that was arbitrary. Choosing a variant
        here and handing over its recipes alone would reimplement four of those badly, and the
        one #170 cannot afford to get wrong is disclosure -- the variants of one bare key
        differ by NBT the demanding slot never named, so the graph has no evidence that any of
        them is the right one.

        THE CHEAPEST VARIANT WINS, AND IT WINS THROUGH `score_recipe` RATHER THAN HERE. That
        is the same rule `cost._relax` prices the bare key by, so the route the plan takes is
        the route the price was computed from. The alternative orderings were considered and
        each breaks that: "the first the dump saw" is arbitrary, and "whichever variant is in
        stock" would have the plan craft what the cost table prices at 22.0 while claiming the
        0.0 of a stack it cannot draw on, because `take` is keyed on the exact stock key and
        stock widening is not part of this change.

        UNREACHABLE VARIANTS ARE DROPPED, and that is what keeps `reachable_form`'s badge
        alive for the keys that need it. 6 of the 88 have no variant the cost table can reach
        at all -- `astralsorcery:itemtunedcelestialcrystal` and all three Draconic tools among
        them, which are the same 6 the issue counted -- and offering those would trade a
        one-line "no known source" for a large subtree bottoming out on the same dead end,
        while the price stayed at `UNSOURCED_COST` because `_relax` cannot lower through an
        infinite route. A route the price does not reflect is the #176 defect, at a smaller
        magnitude.

        DROPPING THEM CANNOT HIDE A ROUTE THE PRICE USED, and that is provable rather than
        measured. `_relax` assigns a variant's `per_unit` to the variant BEFORE attributing it
        to the bare key, and it only ever lowers -- so if the bare key's price came through a
        variant, that variant's own cost is at most the same finite number and this filter
        keeps it.

        INERT WITHOUT A COST TABLE. `slot_cost` returns 0.0 for everything when `costs` is
        None, which is `--no-cost`, so every satisfying variant is offered and the ranking
        falls back to what it uses there anyway.
        """
        recipes, variant_of = [], {}
        for variant in self.g.satisfying_variants(key):
            if math.isinf(self.slot_cost(variant)):
                continue
            for recipe in self.g.real_producers(variant):
                # A recipe emitting two satisfying variants of one bare key is counted once,
                # under the variant the dump saw first. Listed twice it would be attempted
                # twice for the same offer and would inflate #181's tie count.
                if recipe.rid not in variant_of:
                    variant_of[recipe.rid] = variant
                    recipes.append(recipe)
        return recipes, variant_of

    def _note_overruled_pin(self, key, recipe):
        """Record a pin the backtracking had to ignore.

        The cycle guard outranks a pin, and it has to: a pinned uncrafting recipe
        (`block -> 9 ingots`) produces a plan that asks for the item being crafted, which
        is not a plan. But "your choice was not used" is exactly the silence #30 exists to
        end, and the chooser had already badged the node `pinned`. So the plan says it.
        """
        wanted = self.pinned.get(key)
        rid = getattr(recipe, "rid", recipe)
        if wanted and rid not in wanted:
            self.pins_overruled[key] = (
                "every recipe you pinned for %s loops back on its own ingredients, so "
                "the plan uses another route" % self.g.bare_name(key))

    def _unsourced_note(self, key, other):
        """Which of the three things the graph's `reachable_form` found, in actionable words.

        THREE WORDINGS FOR THREE CLAIMS, and collapsing them would lose the action. A STATE
        means "you have the item, this tier is out of reach"; a FORM means "this shape is
        not made, use the other one"; a VARIANT means "the thing IS made, just carrying
        NBT this row does not name".

        THE VARIANT WORDING IS NOW THE NARROW CASE, and #170 is why. `expand` routes a bare
        demand through its variants' recipes, so a key reaching here on the variant branch is
        one that route could not be taken for: every produced variant is made FROM the bare
        key or from a sibling variant (11 keys on the reference graph, the Ender IO Soul Binder
        and the Beer Mug shapes) or none of them is reachable at all (6 keys, the three
        Draconic tools among them). For those the graph genuinely does make the item and
        genuinely cannot get you one, so "go and look at the variant" is still the honest next
        move. A ROUTED substitution says so on its own node instead: see `_build`, which
        writes `resolved_to` and names the variant it took.
        """
        name = self.g.bare_name(other)
        if base_key(key) != key:
            return "no recipe reaches this state; the graph can only make %s" % name
        if base_key(other) == key:
            return "nothing makes this exact item; the graph makes %s" % name
        return "nothing makes this form; the graph can only make %s" % name

    def _snapshot(self):
        """Everything a discarded branch must not leave behind.

        DO NOT include `self.work` here. It is the monotonic budget counter and rewinding
        it removes the only guarantee the search terminates -- see the comment on it in
        __init__. Every OTHER accumulator must be listed, or a rejected attempt's draw is
        counted twice; `from_sources` was added for exactly that reason.

        `machines_needed` WAS THE ONE STILL MISSING, and the rule above is what it broke: it is
        written in `_build` before the children are expanded, so every attempt the cycle guard
        discarded left its machine behind. Symptom, from #211's reproduction after the fix: a
        three-node plan for a Chest -- eight planks and a log -- reported "machines you do not
        have yet: Chiseling, Alloy Furnace", neither of which appears anywhere in the tree. It
        reads as the plan asking for two machines it does not need. Found because demoting the
        loot tables makes backtracking common on exactly this plan; the leak predates that.
        """
        return (self.pool.copy(), self.used_from_stock.copy(),
                self.leaf_totals.copy(), self.from_sources.copy(),
                self.tokens_needed.copy(), self.from_emc.copy(),
                dict(self.machines_needed), self.nodes)

    def _restore(self, snap):
        (self.pool, self.used_from_stock, self.leaf_totals,
         self.from_sources, self.tokens_needed, self.from_emc,
         self.machines_needed, self.nodes) = snap

    def _build(self, base, recipe, key, remainder, from_stock, ancestors, depth,
               interchangeable, variant):
        """Expand one specific recipe choice for `key`.

        `interchangeable` is how many equally-scored recipes are the SAME OFFER as this one,
        computed by the caller from scores it already had. 1 means the pick was not arbitrary
        and nothing is rendered.

        `variant` is the NBT variant of `key` this recipe actually outputs, or None when it
        outputs `key` itself. Not None is the #170 substitution: nothing makes the bare key,
        so a demand for it is being satisfied by the variant's recipe. See
        `Graph.variant_subsumption`.

        NEITHER IS DEFAULTED, and this docstring used to claim two other call sites needed
        them to be. There is one caller, the ranking loop in `expand`. A default here is a
        silent wrong answer waiting for the second caller to arrive: `interchangeable=1`
        suppresses a mark that should have fired, and `variant=None` would price the runs of
        a substituted recipe off an output it does not have.
        """
        # THE VARIANT'S OUTPUT, not the bare key's, when this is a substitution: the recipe
        # makes `animus:kama_bound#fd1adc426e12` and yields per that key, so reading `key`
        # would silently fall back to a per-run of 1 and plan the wrong number of runs.
        made = variant or key
        per_run = next((q for k, q in recipe.outputs if k == made), 1) or 1
        runs = -(-remainder // per_run)  # ceil
        node = dict(base)
        node.update({
            "status": STATUS_PARTIAL if from_stock else STATUS_CRAFT,
            "recipe": recipe.rid,
            "category": recipe.category,
            "runs": runs,
            "per_run": per_run,
            # OF THE KEY THE RECIPE ACTUALLY MAKES. For a substitution that is the variant,
            # and counting the bare key's producers would report 0 -- "there was no other
            # way to do this" -- on a node that is one of several ways.
            "alternatives": len(self.g.real_producers(made)),
        })
        if variant:
            # SAY WHAT WAS SUBSTITUTED. `resolved_to` is the field `resolve_ore` already uses
            # for "this demand resolved to that key". The claim cannot be proved from the dump
            # -- see `Graph.variant_subsumption` on the 75 mod-machine slots whose NBT
            # sensitivity is unfalsifiable -- so a reader has to be able to see the swap and
            # pin another recipe if it is wrong. A silent identity would be the same defect
            # with no way to notice it.
            node["resolved_to"] = made
            # THE KEY, NOT THE LABEL, AND THAT IS NOT A STYLE CHOICE. items.csv names a
            # digest variant with the SAME label as its bare key more often than not --
            # `animus:kama_bound#fd1adc426e12` is "Bound Khopesh", exactly as the bare key is
            # -- so a note reading "planned as Bound Khopesh" on a row already labelled Bound
            # Khopesh names nothing and the substitution is invisible. The key is what
            # identifies the stack and what a reader can paste into search. `resolved_to`
            # carries it for a UI that wants to link it; only the in-game panel reads that
            # field today, so the note is what the browser has.
            node["note"] = "nothing makes this exact item; planned as %s" % made
            # AND THE VARIANT JOINS THE ANCESTOR SET, not just the bare key. No recipe making
            # it consumes the bare key or a sibling variant (`variant_subsumption` clause 4),
            # but a deeper node can demand the variant itself, and the guard on `key` alone
            # would not see that.
            ancestors = ancestors | {made}
        # Rendered as a badge, because a choice you cannot see is a choice you cannot
        # audit and this one changes every plan that touches the item.
        if recipe.rid in self.pinned.get(key, ()):
            node["pinned"] = True
        elif interchangeable >= TIE_MIN:
            # THE PICK WAS ARBITRARY AND THE PLAN SAYS SO. #181: `fluid:lifeessence` has 62
            # structurally identical Digital Mob Agonizer recipes, and the plan named Blaze
            # Data Model because Blaze sorts first, not because the model preferred it.
            #
            # NOT `alternatives`, WHICH IS A DIFFERENT AND MORE FLATTERING NUMBER. That is
            # `len(real_producers)` -- 65 here -- and it includes three Blood God Altar
            # routes that price at infinity. "There were 65 ways" is false comfort; "62 of
            # these were interchangeable" is the finding. They agree on this key by luck.
            #
            # SUPPRESSED UNDER A PIN, hence the `elif`. A pin is the player having already
            # answered "which of these", so a node carrying both badges contradicts itself.
            node["interchangeable"] = interchangeable
        if recipe.machine:
            node["machine"] = recipe.machine
        state = self.machine_states.get(recipe.category)
        if state is not None:
            node["machine_state"] = state[0]
            if state[0] != "have":
                node["machine_why"] = state[1]
                self.machines_needed[recipe.category] = (
                    recipe.machine or recipe.category, state[0], state[1])

        children = []
        for alt, qty, alts, chance in self._merge_slots(recipe):
            # `qty` AND NOT `qty * runs` FOR A CATALYST. #175: a slot the run never spends is
            # a thing you OWN, so a hundred runs need the same one. Multiplying it by `runs`
            # is what asked for 64 Blaze Data Models when the answer is one, and it is worse
            # than a mispriced leaf because the multiplier is unbounded and scales with the
            # quantity requested -- doubling the fluid doubled the shards, and under
            # `fluid:fractallite_taint` it built roughly 40 levels of phantom chaos-shard
            # subtree beneath a shard that is needed once, permanently.
            #
            # STILL EXPANDED, NOT DROPPED. You genuinely cannot run the forge without a chaos
            # shard, so the requirement is real and its subtree is how you get the one. It is
            # the QUANTITY that was wrong, not the presence of the row.
            child = self.expand(alt, qty if chance == 0.0 else qty * runs,
                                ancestors, depth + 1)
            if alts > 1:
                child["alt_count"] = alts
            if chance == 0.0:
                # Marked so the renderers and the shopping list can shelve it as "own one"
                # rather than "go and get this many". `not_consumed` and NOT `catalyst`: this
                # repository already means the JEI machine BLOCK by that word, in
                # `catalysts.json` and ten Java files, and it is not `reuse` either because the
                # pack expresses the same idea through two mod mechanisms (Modular Machinery's
                # `setChance(0.0)` and CraftTweaker's `.reuse()`). Name the consequence.
                child["not_consumed"] = True
            children.append(child)
        node["children"] = children
        return node

    def _merge_slots(self, recipe):
        """Input slots collapsed onto what each one RESOLVES to. See model.merge_slots.

        The one view of a recipe's ingredients this class has: `score_recipe` ranks the
        recipe by it and `_build` expands it, so the two cannot disagree about how many
        ingredients there are.

        Expanding a 3x3 of one ingredient per slot drew nine copies of an identical
        subtree, which is nine times the nodes at every such step -- the node cap was
        being spent on duplicates, so the tree that got truncated was mostly repeat.

        The consequence specific to the solver is that resolution now happens once, up
        front, rather than interleaved with expansion: `expand` draws stock down as it
        goes, so a later slot used to see a smaller pool. That makes plans BETTER rather
        than worse -- one `take` of 9 replaces nine takes of 1, and no slot can now pick a
        different route because an earlier COPY OF ITSELF emptied the shelf.
        """
        return [(key, qty, options, ing.consume_chance)
                for key, ing, qty, options in merge_slots(recipe.inputs,
                                                          self.pick_alternative)]

    def _entry(self, key, qty):
        return {"key": key, "name": self.g.display(key), "kind": self.g.kind(key),
                "label": self.g.bare_name(key), "qty": qty}

    def _need_entry(self, key, qty):
        """`_entry` for a SHOPPING-LIST row, which is the only list the #136 mark suits.

        `_entry` feeds five lists and decorating it there put "no known source" on rows in
        "Drawn from AE2 stock" -- a row that exists precisely BECAUSE you are holding the
        item -- and on infinite-source and token rows, each of which already carries its own
        and contradictory answer to "how do I get this".

        Recomputed from `Graph.reachable_form` rather than copied off the tree node, so the
        list and the tree cannot disagree about the same key. The tree is the diagnosis; this
        is what gets acted on while gathering.
        """
        row = self._entry(key, qty)
        if self.g.reachable_form(key):
            row["unsourced"] = True
        return row

    def solve(self, key, qty=1):
        """The whole result, as one dict. This IS the wire format.

        `unsourced` is OPTIONAL and appears only where it is true: on a tree node and on a
        `shopping_list` row whose key is a state the graph cannot reach (#136). Absent
        everywhere else, including on the other four `_entry` lists, where it would
        contradict the row it sat on. Readers must treat its absence as false rather than
        expecting the key.

        FIVE OF THESE LISTS ARE ORDERED BY `Counter.most_common()`, AND THAT ORDER IS PART OF
        THE CONTRACT, NOT AN ACCIDENT OF THE IMPLEMENTATION. `most_common` sorts by count
        descending and breaks ties by INSERTION order, which here is the order the solver
        first reached each key -- roughly the order a player would work through the tree. It
        is deliberately NOT alphabetical and NOT by key.

        Two things depend on that and would break silently if someone swapped a Counter for a
        dict-plus-`sorted`:

          - `tests/fixtures/plan/*.json` freeze this output so the Java port of the planner
            (#19) can be asserted against it. A reordered list is a failing fixture with no
            behavioural change to point at.
          - Reproducing it in Java needs a STABLE sort by count descending over an
            insertion-ordered map, which is what `plan/KeyCounter.java` is for. Both wrong
            maps give the SAME wrong order there, and it is not alphabetical: the port's
            counter is keyed by the int key id rather than by the string key, so `TreeMap`
            sorts by id, and `HashMap` iterates the port's small non-negative ids ascending
            too. Ids are issued in intern order, so ascending-by-id is often close enough to
            first-reached order to look right. See that file's javadoc; this docstring said
            "alphabetical" until #192 and was describing a port that does not exist.

        `machines_to_build` is the odd one out and is `sorted()` by category on purpose: it is
        a checklist rather than a worklist, so a stable alphabetical order is easier to scan
        than one that shuffles as the plan changes.
        """
        tree = self.expand(key, qty)
        return {
            "target": key,
            "target_name": self.g.display(key),
            "pins_overruled": dict(self.pins_overruled),
            "qty": qty,
            "tree": tree,
            "shopping_list": [self._need_entry(k, n)
                              for k, n in self.leaf_totals.most_common()],
            "used_from_stock": [self._entry(k, n)
                                for k, n in self.used_from_stock.most_common()],
            "from_sources": [dict(self._entry(k, n),
                                  why=self.free_sources.get(k, ""))
                             for k, n in self.from_sources.most_common()],
            "tokens_needed": [dict(self._entry(k, n),
                                   token_kind=self.token_kinds.get(k, ""))
                              for k, n in self.tokens_needed.most_common()],
            # Its own list rather than folded into `shopping_list`, for the same reason
            # `from_sources` is: these are not things to go and get. Carrying the EMC value
            # per row keeps the claim checkable -- "EMC 2,048" is something a player can
            # look up, where a bare "from EMC" is something they have to take on trust.
            "from_emc": [dict(self._entry(k, n), emc=self.g.emc.get(k, 0))
                         for k, n in self.from_emc.most_common()],
            "machines_to_build": [
                {"category": cat, "machine": m, "state": st, "why": why}
                for cat, (m, st, why) in sorted(self.machines_needed.items())
            ],
            "nodes": self.nodes,
            "work": self.work,
            "truncated": self.nodes > self.max_nodes or self.exhausted,
            # TWO CAUSES, and a reader who is about to wait for a bigger one needs to know
            # which. `exhausted` means the WORK budget went first: the search spent itself
            # on branches it backtracked out of, so the node count is far below the cap
            # and quoting the cap at that reader is simply wrong. Raising max_nodes still
            # helps, because work_budget derives from it.
            "exhausted": self.exhausted,
            # What the cap WAS, so a notice can say "4,000" rather than quoting the count
            # it happened to stop at as though that were the limit.
            "max_nodes": self.max_nodes,
            "work_budget": self.work_budget,
        }
