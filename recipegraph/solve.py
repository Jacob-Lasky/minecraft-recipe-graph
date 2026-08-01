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

from .cost import input_cost, recipe_cost
from .defaults import DEFAULT_MAX_NODES
from .machines import is_hand_crafting
from .model import merge_slots, split_key

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
                 token_kinds=None):
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
            if self.g.real_producers(key):
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

        Recipes that feed back into an ancestor, OR into one of their own outputs, are
        ranked LAST. Without this, `ingot -> block -> 9 ingots` scores well (one simple
        input) and gets picked over a real production route, producing a plan that asks for
        the very thing being crafted. The cycle guard still catches it; this stops us
        choosing it.

        `own` catches two cases `ancestors` structurally cannot, both found by measuring #61:

          * A BYPRODUCT that feeds back. `_build` passes `ancestors | {key}`, so the key
            being planned is covered at every depth -- but a recipe emitting (Heart Fruit
            x12, Heart Fruit Seeds x1) while consuming Heart Fruit Seeds is cyclic through
            an output that is NOT the one being planned, and no ancestor set ever holds it.
          * `score_recipe` called with NO ancestors, which is what the recipe-chooser page
            does (`server.recipes_page`, so the order shown to someone about to pin). There
            a self-consuming recipe ranked top and was the tool's recommendation.

        A note on where it can bite: `cheap` outranks this, so it only ever settles a cost
        TIE. In practice that is common, because every candidate for an unreachable item
        prices at infinity and the whole comparison falls through to these terms.
        """
        satisfied = 0
        cyclic = 0
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
        for alt, qty, _options in slots:
            if (self.available(alt) >= qty or alt in self.craftables
                    or alt in self.free_sources):
                satisfied += 1
            if (alt in ancestors or alt in own) and self.available(alt) < qty:
                cyclic += 1
        # simplicity tiebreak: fewer ingredients, and prefer plain crafting over machines
        simple = 1.0 / (1 + len(slots))
        plain = 0.1 if is_hand_crafting(recipe.category) else 0.0
        avail = self.availability_rank(recipe)
        # A container fill/empty never counts as production, so it loses to any real
        # recipe regardless of how well stocked it looks.
        # Order matters. A container transfer is never production. After that, the
        # ESTIMATED TOTAL COST dominates: it already accounts for machine availability
        # (via cost.MACHINE_COST) and for how expensive the whole subtree is, which local
        # signals cannot see. `satisfied`/`simple` only break ties between comparable
        # routes. DO NOT promote `avail` above cost -- doing that is what made the solver
        # prefer a million-bucket chain through an owned machine.
        cost = self.estimated_cost(recipe)
        cheap = -cost if cost != float("inf") else float("-inf")
        # `ore_backed` sits BELOW cost and stock and ABOVE `simple + plain`, and both
        # halves of that are deliberate. Below cost, because it must never override a
        # real price difference -- it exists to settle exact ties, which 26.8% of produced
        # keys have. Above `simple + plain`, because that is the term it has to beat:
        # `plain` gives hand-crafting +0.1 and so prefers unpacking a decorative block
        # over smelting an ore. Moved below it, this goes inert. See `ore_backed`.
        return (0 if recipe.transfer else 1, cheap, -cyclic, satisfied,
                self.ore_backed(recipe, slots), simple + plain, avail)

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
        for alt, qty, _options in slots:
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
            if not self.g.real_producers(alt):
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
            node["note"] = "mined, not crafted"
            self.leaf_totals[key] += remainder
            return node

        candidates = self.g.real_producers(key)
        if not candidates:
            kind = self.token_kinds.get(key)
            if kind:
                # Tallied apart from `leaf_totals`, which IS the shopping list. "1 Dungeon
                # Drop" on a list of materials to gather reads as a thing to acquire; it is
                # an instruction, and it belongs with the other instructions.
                node["status"] = STATUS_TOKEN
                node["token_kind"] = kind
                self.tokens_needed[key] += remainder
                return node
            node["status"] = STATUS_RAW
            self.leaf_totals[key] += remainder
            return node

        nxt = ancestors | {key}
        ranked = sorted(candidates, key=lambda r: self.score_recipe(r, nxt), reverse=True)
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
        for rank, recipe in enumerate(ranked[: self.branch_tries]):
            if self.work > self.work_budget:
                break
            snapshot = self._snapshot()
            attempt = self._build(node, recipe, key, remainder, from_stock, nxt, depth)
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

    def _snapshot(self):
        """Everything a discarded branch must not leave behind.

        DO NOT include `self.work` here. It is the monotonic budget counter and rewinding
        it removes the only guarantee the search terminates -- see the comment on it in
        __init__. Every OTHER accumulator must be listed, or a rejected attempt's draw is
        counted twice; `from_sources` was added for exactly that reason.
        """
        return (self.pool.copy(), self.used_from_stock.copy(),
                self.leaf_totals.copy(), self.from_sources.copy(),
                self.tokens_needed.copy(), self.nodes)

    def _restore(self, snap):
        (self.pool, self.used_from_stock, self.leaf_totals,
         self.from_sources, self.tokens_needed, self.nodes) = snap

    def _build(self, base, recipe, key, remainder, from_stock, ancestors, depth):
        """Expand one specific recipe choice for `key`."""
        per_run = next((q for k, q in recipe.outputs if k == key), 1) or 1
        runs = -(-remainder // per_run)  # ceil
        node = dict(base)
        node.update({
            "status": STATUS_PARTIAL if from_stock else STATUS_CRAFT,
            "recipe": recipe.rid,
            "category": recipe.category,
            "runs": runs,
            "per_run": per_run,
            "alternatives": len(self.g.real_producers(key)),
        })
        # Rendered as a badge, because a choice you cannot see is a choice you cannot
        # audit and this one changes every plan that touches the item.
        if recipe.rid in self.pinned.get(key, ()):
            node["pinned"] = True
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
        for alt, qty, alts in self._merge_slots(recipe):
            child = self.expand(alt, qty * runs, ancestors, depth + 1)
            if alts > 1:
                child["alt_count"] = alts
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
        return [(key, qty, options)
                for key, _ing, qty, options in merge_slots(recipe.inputs,
                                                           self.pick_alternative)]

    def _entry(self, key, qty):
        return {"key": key, "name": self.g.display(key), "kind": self.g.kind(key),
                "label": self.g.bare_name(key), "qty": qty}

    def solve(self, key, qty=1):
        tree = self.expand(key, qty)
        return {
            "target": key,
            "target_name": self.g.display(key),
            "pins_overruled": dict(self.pins_overruled),
            "qty": qty,
            "tree": tree,
            "shopping_list": [self._entry(k, n)
                              for k, n in self.leaf_totals.most_common()],
            "used_from_stock": [self._entry(k, n)
                                for k, n in self.used_from_stock.most_common()],
            "from_sources": [dict(self._entry(k, n),
                                  why=self.free_sources.get(k, ""))
                             for k, n in self.from_sources.most_common()],
            "tokens_needed": [dict(self._entry(k, n),
                                   token_kind=self.token_kinds.get(k, ""))
                              for k, n in self.tokens_needed.most_common()],
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
