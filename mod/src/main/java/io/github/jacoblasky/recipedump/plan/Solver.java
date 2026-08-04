package io.github.jacoblasky.recipedump.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.Csr;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.graph.RecipeStore;

/**
 * Resolve a target item into a crafting tree, pruned against what you already have. Ported
 * from `recipegraph/solve.py`; the justifying comments are that file's and are carried across
 * because each one records a measurement someone paid for.
 *
 * The three things that make this non-trivial, and how each is handled:
 *
 * 1. CYCLES. Recipe graphs are not DAGs (ingot -&gt; block -&gt; ingot). Guarded by an explicit
 *    ancestor set per path; a repeat is emitted as a `cycle` leaf rather than recursed into.
 *    DO NOT replace this with a global visited set -- that would wrongly prune legitimate
 *    diamond-shaped reuse of the same intermediate.
 *
 * 2. RECIPE CHOICE. Items have many recipes and the best one depends on inventory, so choice
 *    belongs here, not in the extractors. Scored by how much of the recipe is already
 *    satisfied, then by simplicity. Overridable per item by a pin.
 *
 * 3. INVENTORY IS CONSUMED, NOT JUST CHECKED. `have` is drawn down as the tree is built, so
 *    two sibling branches cannot both claim the same 5 redstone. This is why the walk is
 *    single-pass and ordered rather than a pure function per node.
 *
 * WORKS IN KEY IDS, NOT KEY STRINGS. The graph this reads is columnar (#126) and a plan for
 * this pack touches thousands of nodes, so every String that can be deferred to the wire
 * format is. `solve` is where ids become names, and nowhere else.
 *
 * ITERATION ORDER IS PART OF THE ANSWER. `RecipeGraph.realProducers` returns candidates in a
 * fixed order and `rank` sorts them with a STABLE sort, so equally-scored candidates
 * keep that order and the first of them is the one taken -- which is what makes a plan
 * reproducible across processes and what matches Python's `max` returning the FIRST maximum.
 * Nothing here may iterate a `HashMap` and let the result reach the output.
 */
public final class Solver {

    /** `defaults.DEFAULT_MAX_NODES`. */
    public static final int DEFAULT_MAX_NODES = 4000;
    /** `solve.Solver.__init__`'s default `max_depth`. */
    public static final int DEFAULT_MAX_DEPTH = 24;
    /**
     * How many INTERCHANGEABLE recipes must tie before a node admits the pick was arbitrary.
     *
     * Mirrors `solve.TIE_MIN` in python and MUST stay equal to it -- the golden gate compares
     * plans byte for byte, so a divergence here shows up as every marked node differing. The
     * measurement behind the value of 3 lives beside the python constant; the short version
     * is that a bare score tie fires on 33.5% of multi-producer keys and is useless, the
     * same-offer requirement cuts it to 6.2%, and requiring three cuts it to 1.3%.
     */
    public static final int TIE_MIN = 3;

    /** How many ranked recipes to try before accepting a cycling one. */
    public static final int DEFAULT_BRANCH_TRIES = 4;

    private final RecipeGraph g;

    /** Inventory, drawn down as the tree is built. Its KEY SET is fixed for our lifetime. */
    private KeyCounter pool;
    /** `base key id -> the pool key ids sharing it`, so a wildcard lookup is not a scan. */
    private final Map<String, int[]> byBase;

    private final Set<Integer> raw;
    private final Set<Integer> craftables;
    private final Map<Integer, String> freeSources;
    private final Set<Integer> emcAvailable;
    private final Map<Integer, Set<String>> pinned;
    private final Map<Integer, Integer> tokenKinds;
    private final Map<Integer, String> dimensionGates;

    private final int maxDepth;
    private final int maxNodes;
    private final int workBudget;
    private final MachineStates machineStates;
    private final CostTable costs;

    private KeyCounter fromSources = new KeyCounter();
    private KeyCounter fromEmc = new KeyCounter();
    private KeyCounter tokensNeeded = new KeyCounter();
    private KeyCounter leafTotals = new KeyCounter();
    private KeyCounter usedFromStock = new KeyCounter();

    /** `{category name: [machine, state, why]}`, sorted only when it is emitted. */
    private Map<String, String[]> machinesNeeded = new LinkedHashMap<String, String[]>();
    /** `{key id: why the pin was not used}`. */
    private final Map<Integer, String> pinsOverruled = new LinkedHashMap<Integer, String>();

    private int nodes;
    /**
     * Monotonic work counter. `nodes` is REWOUND when a backtrack discards a subtree, so
     * discarded work never counts toward maxNodes -- with `branchTries` retries at every
     * level that makes the search effectively unbounded, and on a 340k-recipe graph it simply
     * never returns. This counter is never rewound, so it is the only real termination
     * guarantee. DO NOT add it to a snapshot/restore.
     */
    private int work;
    private boolean exhausted;
    private boolean solved;

    /** Scratch for `realProducers`, which appends into a caller-supplied buffer. */
    private final IntArray scratch = new IntArray();

    /**
     * The recipes {@link NonProduction} demoted: loot tables and JEI automation cards.
     *
     * COMPUTED HERE AS WELL AS IN {@link Cost#estimate}, and that is not a duplicated rule --
     * the rule has exactly one implementation, in {@link NonProduction}, and this is a second
     * CALL of it. Python's `Solver.__init__` calls `graph.mark_non_production` for the same
     * reason: a Solver built with no cost table must still refuse to route through a loot
     * table, and a table's copy is unavailable in that case. The two agree whenever they were
     * given the same token map, which every caller resolves once and passes to both.
     */
    private final long[] notProduction;

    private Solver(Builder b) {
        this.g = b.graph;
        this.pool = b.have == null ? new KeyCounter() : b.have;
        this.byBase = indexPool(this.pool);
        this.raw = b.raw;
        this.craftables = b.craftables;
        this.freeSources = b.freeSources;
        this.emcAvailable = b.emcAvailable;
        this.pinned = b.pinned;
        this.tokenKinds = b.tokenKinds;
        this.dimensionGates = b.dimensionGates;
        this.maxDepth = b.maxDepth;
        this.maxNodes = b.maxNodes;
        // DERIVED FROM `maxNodes` AND NOT SETTABLE, WHICH IS THE ONE ASYMMETRY WITH PYTHON, and
        // `branchTries` is gone from the builder for the same reason. `solve.Solver.__init__`
        // takes both `work_budget` and `branch_tries` because the browser exposes the first as
        // an editable URL parameter that `test_server` clamps. The in-game planner has no such
        // control and never had one, so the two `Builder` setters sat uncalled -- and the
        // `b.workBudget > 0 ? ... : ...` branch here had a first arm nothing could reach.
        // The knob a caller actually turns is `maxNodes`, and `PlanResult.exhausted`'s javadoc
        // is what says why that is enough: raising the node cap raises this with it.
        // DO NOT add a setter back without a control that drives it; the formula must keep
        // matching `solve.py`'s `work_budget or max(50000, max_nodes * 20)`.
        this.workBudget = Math.max(50000, b.maxNodes * 20);
        this.machineStates = b.machineStates;
        this.costs = b.costs;
        this.notProduction = NonProduction.recipes(b.graph, b.tokenKinds, null);
    }

    /**
     * `base key -> the pool keys sharing it`, so a wildcard lookup is not a pool scan.
     *
     * Built ONCE and never invalidated. That is safe because the pool's KEY SET is fixed for
     * a solver's lifetime: `take` only ever decrements existing entries, nothing inserts, and
     * a restore swaps in a copy of the same keys. If you ever add a key to `pool`, this index
     * is what breaks -- update it here too. `KeyCounter.subtractExisting` is the tripwire.
     *
     * Without it, `take` filtered all 3,389 stocked keys on every call in order to use one of
     * them, which cost 23 million key splits and 20 seconds for a 4,000-node plan. It read as
     * linear and was quadratic: more nodes means more takes, each O(pool).
     */
    private Map<String, int[]> indexPool(KeyCounter pool) {
        Map<String, List<Integer>> gathered = new HashMap<String, List<Integer>>();
        for (int keyId : pool.keys()) {
            // `withoutMeta`, NOT `baseKey`. They are one letter apart in intent and answer
            // different questions: `baseKey` strips the NBT `#digest` and KEEPS the meta,
            // while Python's `split_key(key)[0]` -- the thing being ported -- strips the
            // meta. Using `baseKey` here files every key under itself, so no wildcard ever
            // finds a sibling and the whole fallback goes silently inert. Caught by
            // `aWildcardMetaDrawsOnItsConcreteSiblings`, which is the only reason this
            // comment exists.
            String base = Keys.withoutMeta(g.key(keyId));
            List<Integer> bucket = gathered.get(base);
            if (bucket == null) {
                bucket = new ArrayList<Integer>(2);
                gathered.put(base, bucket);
            }
            bucket.add(keyId);
        }
        Map<String, int[]> index = new HashMap<String, int[]>(gathered.size() * 2);
        for (Map.Entry<String, List<Integer>> entry : gathered.entrySet()) {
            List<Integer> bucket = entry.getValue();
            int[] ids = new int[bucket.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = bucket.get(i);
            }
            index.put(entry.getKey(), ids);
        }
        return index;
    }

    // -- inventory ---------------------------------------------------------------------

    /**
     * The pool keys `keyId` may draw on, exact key first.
     *
     * Only a wildcard meta draws on anything but itself, so the common case never touches the
     * index at all. Excluding the key itself from the widened part matters: a wildcard that
     * is ITSELF stocked appears in its own base bucket, and counting it twice made
     * {@link #available} promise more than {@link #take} could deliver.
     */
    private int[] equivalent(int keyId) {
        String key = g.key(keyId);
        if (Keys.metaOf(key) != Keys.META_WILDCARD) {
            return new int[] {keyId};
        }
        int[] siblings = byBase.get(Keys.withoutMeta(key));
        if (siblings == null) {
            return new int[] {keyId};
        }
        int[] out = new int[siblings.length + 1];
        out[0] = keyId;
        int at = 1;
        for (int sibling : siblings) {
            if (sibling != keyId) {
                out[at++] = sibling;
            }
        }
        return at == out.length ? out : java.util.Arrays.copyOf(out, at);
    }

    /**
     * Stock for a key, counting a wildcard-meta variant as interchangeable.
     *
     * The non-wildcard case returns without allocating. That matters because this is the
     * hottest method here -- `scoreRecipe` calls it twice per merged slot, `alternativeScore`
     * once per alternative and `oreBacked` once per slot, for every candidate of every node --
     * and `equivalent` otherwise hands back a one-element array each time.
     */
    public long available(int keyId) {
        if (!isWildcard(keyId)) {
            return pool.get(keyId);
        }
        long total = 0;
        for (int equivalentKey : equivalent(keyId)) {
            total += pool.get(equivalentKey);
        }
        return total;
    }

    private boolean isWildcard(int keyId) {
        return Keys.metaOf(g.key(keyId)) == Keys.META_WILDCARD;
    }

    /** Draw `want` out of stock, returning how much was actually there. */
    public long take(int keyId, long want) {
        long got = Math.min(want, available(keyId));
        if (got <= 0) {
            return 0;
        }
        long remaining = got;
        // Drain the exact key first, then wildcard-equivalent metas.
        for (int equivalentKey : equivalent(keyId)) {
            if (remaining <= 0) {
                break;
            }
            long avail = pool.get(equivalentKey);
            if (avail <= 0) {
                continue;
            }
            long used = Math.min(avail, remaining);
            pool.subtractExisting(equivalentKey, used);
            usedFromStock.add(equivalentKey, used);
            remaining -= used;
        }
        return got - remaining;
    }

    // -- choice ------------------------------------------------------------------------

    /**
     * What `qty` of `keyId` adds to a recipe's price, or 0.0 with no costs supplied.
     *
     * Delegates to {@link Cost#inputCost} rather than reading the table directly, so an
     * oredict key resolves to its cheapest member and a fluid is scaled to buckets by the
     * same code that priced the recipe. A local copy of that arithmetic would be a second
     * place for it to drift.
     *
     * Zero rather than infinity for the no-costs case ON PURPOSE: every candidate then ties
     * and the cost tiebreaks below go inert, so a Solver built without costs behaves exactly
     * as it did before cost became a factor in these choices.
     */
    double slotCost(int keyId, long qty) {
        if (costs == null) {
            return 0.0;
        }
        // PASSED AS A LONG, AND `Cost.inputCost` TAKES ONE. It briefly did not, and the
        // narrowing this method used to do was hiding a divergence rather than smoothing a
        // signature: `resolve_ore` calls this with a computed NEED, not a slot's own
        // quantity, so it multiplies down a chain -- and python has no int ceiling, so
        // saturating at Integer.MAX_VALUE agreed with the oracle only up to 2^31. A fixture
        // that ever crossed it would have failed with the cause invisible in the diff. DO NOT
        // reintroduce a cast here.
        return Cost.inputCost(costs, g, keyId, qty);
    }

    /**
     * Sort key for one option in a slot: reachable-and-owned first, then cheapest.
     *
     * Returned as two doubles compared in order, matching Python's `(score, -slot_cost)`
     * tuple.
     */
    private double alternativeScore(int keyId, long qty) {
        double score = 0.0;
        String key = g.key(keyId);
        if (key.startsWith(Keys.ORE_PREFIX)) {
            long best = 0;
            int groupId = g.oreGroupId(key.substring(Keys.ORE_PREFIX.length()));
            if (groupId >= 0) {
                Csr members = g.oreMembers();
                for (int p = members.start(groupId); p < members.end(groupId); p++) {
                    best = Math.max(best, available(members.at(p)));
                }
            }
            // NOT clamped, unlike the item branch below. Faithful to `_alternative_rank`.
            score += best / 1e6;
        } else {
            score += Math.min((double) available(keyId), 1e6) / 1e6;
            if (freeSources.containsKey(keyId)) {
                score += 1.0;   // an infinite source beats a finite pile of anything
            }
            if (craftables.contains(keyId)) {
                score += 0.5;
            }
            if (hasRealProducers(keyId)) {
                score += 0.25;
            }
        }
        return score;
    }

    /**
     * Which of an input slot's alternatives to actually use.
     *
     * Availability first, then CHEAPEST. Without the cost tiebreak an unstocked slot had
     * nothing to separate its options and fell through to whichever the dump happened to list
     * first, which is how a plan for one Lapis picked Nether Lapis Ore and went off to
     * compress Netherrack six times. See issue #29.
     *
     * THIS IS ALSO WHAT PRICES A RECIPE. {@link #estimatedCost} passes this to
     * {@link Cost#recipeCost}, so whatever is chosen here is what the recipe is scored on.
     * The two cannot drift apart, which is the point: they used to, and the ranker's price
     * came from an option the expander never took.
     */
    public int pickAlternative(int slot) {
        RecipeStore store = g.recipes();
        int start = store.altStart(slot);
        int end = store.altEnd(slot);
        if (start >= end) {
            return -1;
        }
        if (end - start == 1) {
            return store.altKeyAt(start);
        }
        long qty = store.slotQty(slot);
        int best = store.altKeyAt(start);
        double bestScore = alternativeScore(best, qty);
        double bestCost = -slotCost(best, qty);
        // STRICTLY greater, so the FIRST maximum wins, exactly as Python's `max` does.
        // Iteration order here is the authored alternative order, which is why a plan is
        // reproducible across processes.
        for (int p = start + 1; p < end; p++) {
            int candidate = store.altKeyAt(p);
            double score = alternativeScore(candidate, qty);
            double cost = -slotCost(candidate, qty);
            if (score > bestScore || (score == bestScore && cost > bestCost)) {
                best = candidate;
                bestScore = score;
                bestCost = cost;
            }
        }
        return best;
    }

    /**
     * Held as a field, not built per call. `estimatedCost` runs once per candidate recipe per
     * node, so on a 4,000-node plan with four candidates a fresh closure here is tens of
     * thousands of allocations for an object with no state.
     */
    private final Cost.AlternativePicker picker = new Cost.AlternativePicker() {
        @Override
        public int pick(int slot) {
            return pickAlternative(slot);
        }
    };

    private double estimatedCost(int recipeId) {
        if (costs == null) {
            return 0.0;
        }
        // Passing our own `pickAlternative` is load-bearing, not tidiness: it makes the recipe
        // score the cost of the branch the expansion will actually take. DO NOT drop it back
        // to a cheapest-alternative default -- see the javadoc on `Cost.recipeCost`.
        return Cost.recipeCost(costs, g, recipeId, machineStates, picker);
    }

    /**
     * Higher is better: prefer recipes we can mostly satisfy from stock.
     *
     * A recipe that feeds back into an ANCESTOR is ranked below every recipe that does not,
     * at any price. Without that, `ingot -&gt; block -&gt; 9 ingots` scores well (one simple
     * input) and gets picked over a real production route, producing a plan that asks for the
     * very thing being crafted. The cycle guard still catches it; this stops us choosing it.
     * Feeding back into one of the recipe's OWN outputs is a weaker claim and is ranked as a
     * tiebreak -- see the #172 block below for why the two cannot share one term.
     *
     * `own` catches two cases `ancestors` structurally cannot, both found by measuring #61:
     *
     *   * A BYPRODUCT that feeds back. The key being planned is covered at every depth, but
     *     a recipe emitting (Heart Fruit x12, Heart Fruit Seeds x1) while consuming Heart
     *     Fruit Seeds is cyclic through an output that is NOT the one being planned, and no
     *     ancestor set ever holds it.
     *   * `scoreRecipe` called with NO ancestors, which is what {@link #solve} does at the
     *     root: it opens the walk with `Collections.emptySet()`, so the target's own producers
     *     are ranked against an empty ancestor set and a self-consuming recipe there has
     *     nothing to trip the hard term. Without `own` such a recipe ranked top and became the
     *     plan for the very thing the player asked for.
     *
     * THE TWO HALVES SIT ON OPPOSITE SIDES OF `cheap`, AND THAT IS THE WHOLE OF #172. They
     * used to be one counter below it, with a note here saying it "only ever settles a cost
     * TIE" -- true, and the defect: a route that consumes its own output wins OUTRIGHT
     * whenever it is cheaper, and a cheap impossible route beats an expensive real one.
     * Measured on the reference graph, 48 of 23,476 multi-producer keys had a cyclic winner
     * while a clean route existed, and 17 of them produced a plan that bottoms out on a cycle
     * leaf -- a plan for X whose shopping list contains X, which cannot be executed and does
     * not look wrong.
     *
     * SO `-ancestorCyclic` GOES ABOVE `cheap`: whether the plan can be performed at all is
     * not a thing a price should outvote. AND `-ownCyclic` DELIBERATELY STAYS BELOW IT,
     * because promoting the merged counter was measured and REGRESSES the case the `own` half
     * was added for -- `minecraft:pumpkin` moves off an Insolator that gives the seed back at
     * 129.90 and onto transmuting a Melon at 164.18. The seed comes back; that is a
     * sustainable farm, not a cycle.
     *
     * MIRRORS `Solver.score_recipe` IN PYTHON AND IS HELD TO IT BY THE GOLDEN GATE. The field
     * ORDER of {@link RecipeScore} is the tuple order there; do not reorder one side only.
     */
    RecipeScore scoreRecipe(int recipeId, Set<Integer> ancestors) {
        RecipeStore store = g.recipes();
        int satisfied = 0;
        // TWO COUNTERS, NOT ONE, AND THEY SIT ON OPPOSITE SIDES OF `cheap`. See the javadoc.
        // DO NOT merge them back.
        int ancestorCyclic = 0;
        int ownCyclic = 0;
        // Scored on MERGED slots, the same view the expansion will use. Per slot, nine cells
        // asking for one clump each read as nine satisfied ingredients when stock held a
        // single clump, and a 3x3 of one thing looked three times less simple than a recipe
        // taking three different things.
        List<MergedSlot> slots = mergeSlots(recipeId);
        // A recipe's OWN outputs count as ancestors. See above for the two cases this catches
        // and `ancestors` cannot.
        Set<Integer> own = new HashSet<Integer>();
        for (int p = store.outputStart(recipeId); p < store.outputEnd(recipeId); p++) {
            own.add(store.outputKeyAt(p));
        }
        for (MergedSlot slot : slots) {
            if (available(slot.keyId) >= slot.qty || craftables.contains(slot.keyId)
                    || freeSources.containsKey(slot.keyId)) {
                satisfied++;
            }
            if (available(slot.keyId) < slot.qty) {
                // ANCESTOR FIRST, so a slot that is both counts once and counts as the worse
                // of the two. `own` is the softer claim and must not shadow the hard one.
                if (ancestors.contains(slot.keyId)) {
                    ancestorCyclic++;
                } else if (own.contains(slot.keyId)) {
                    ownCyclic++;
                }
            }
        }
        // Simplicity tiebreak: fewer ingredients, and prefer plain crafting over machines.
        double simple = 1.0 / (1 + slots.size());
        double plain = Machines.isHandCrafting(g, store.categoryId(recipeId)) ? 0.1 : 0.0;
        int avail = availabilityRank(recipeId);
        // ORDER MATTERS, AND THE FIRST TERM IS "IS THIS PRODUCTION AT ALL". A container
        // fill/empty is not, and neither is a loot table or a JEI automation card, so all three
        // lose to any real recipe regardless of how well stocked they look. They share one term
        // rather than getting one each because the claim is identical -- whatever else is true
        // of it, this entry is not a way to obtain the thing -- and two terms would be a silent
        // ordering decision between statements that have no order.
        //
        // After that, the ESTIMATED TOTAL COST dominates: it already accounts for machine
        // availability and for how expensive the whole subtree is, which local signals cannot
        // see. `satisfied`/`simple` only break ties between comparable routes. DO NOT promote
        // `avail` above cost -- doing that is what made the solver prefer a million-bucket
        // chain through an owned machine.
        double cost = estimatedCost(recipeId);
        double cheap = Double.isInfinite(cost) ? Double.NEGATIVE_INFINITY : -cost;
        // `oreBacked` sits BELOW cost and stock and ABOVE `simple + plain`, and both halves
        // of that are deliberate. Below cost, because it must never override a real price
        // difference -- it exists to settle exact ties, which 26.8% of produced keys have.
        // Above `simple + plain`, because that is the term it has to beat: `plain` gives
        // hand-crafting +0.1 and so prefers unpacking a decorative block over smelting an
        // ore. Moved below it, this goes inert.
        int production = (store.isTransfer(recipeId)
                || Bits.get(notProduction, recipeId)) ? 0 : 1;
        return new RecipeScore(production, -ancestorCyclic, cheap,
                -ownCyclic, satisfied, oreBacked(slots), simple + plain, avail);
    }

    /**
     * 1 when every raw leaf this recipe rests on is something you mine, else 0.
     *
     * Issue #61: with nothing in stock, a plan for one Diamond said "go and get a Block of
     * Diamond Panel", a ForgeMultipart microblock consumed by two recipes. It was not
     * mispriced. `BASE_RAW_COST` is 1.0 for EVERY key no recipe produces, so the 44 candidate
     * recipes for Diamond all price at exactly the same number and the winner was whichever
     * maximum was seen first, which is dump order. Smelting an ore ties with unpacking a
     * decorative panel because both rest on one raw leaf. Worse, `simple + plain` actively
     * prefers the panel: unpacking is hand-crafting and earns the `plain` bonus.
     *
     * A route resting on NO raw leaf deliberately scores the same 0 as one resting on junk,
     * rather than ranking above ore. DO NOT make this three-way. Returning 2 there was built
     * and measured, and it costs 64 further routes on the reference graph to buy nothing:
     * ranked above `simple + plain` it avoids a raw leaf at ANY price in complexity, so
     * `Cherry Fence &lt;- Planks + Stick` becomes a nine-slot spelling of itself.
     *
     * Not a classifier on the key itself. Demoting all NBT-discriminated raw leaves was
     * rejected in #61 for a measured reason: `deepmoblearning:data_model_experiencedcori` is
     * discriminated, raw, and a genuine item you obtain by playing.
     *
     * What counts as a dead end has to be what the expansion will ACTUALLY dead-end on, in
     * the same order, or this ranks a route by a shape the expander does not produce -- the
     * mistake issue #29 is about. So: an infinite source, a user-declared `raw` stop, and AE2
     * autocraftability all terminate a branch before any recipe lookup, and stock counts only
     * when it covers the whole slot. Judging stock by "is there any" made a slot needing 64
     * with 1 on the shelf read as satisfied.
     */
    int oreBacked(List<MergedSlot> slots) {
        List<Integer> leaves = new ArrayList<Integer>();
        for (MergedSlot slot : slots) {
            String key = g.key(slot.keyId);
            if (key.startsWith(Keys.ORE_PREFIX)) {
                // An unresolvable oredict slot is itself the dead end; a resolvable one was
                // already reduced to a concrete member by `pickAlternative`.
                int groupId = g.oreGroupId(key.substring(Keys.ORE_PREFIX.length()));
                if (groupId < 0 || g.oreMembers().count(groupId) == 0) {
                    leaves.add(slot.keyId);
                }
                continue;
            }
            if (freeSources.containsKey(slot.keyId) || craftables.contains(slot.keyId)) {
                continue;
            }
            if (raw.contains(slot.keyId)) {
                // Declared "stop here" by the user, so it is a dead end even though the
                // graph may know a recipe for it.
                leaves.add(slot.keyId);
                continue;
            }
            if (available(slot.keyId) >= slot.qty) {
                continue;
            }
            if (!hasRealProducers(slot.keyId)) {
                leaves.add(slot.keyId);
            }
        }
        if (leaves.isEmpty()) {
            return 0;
        }
        for (int leaf : leaves) {
            if (!g.isWorldOre(leaf)) {
                return 0;
            }
        }
        return 1;
    }

    /**
     * 2 = machine on hand, 1 = buildable or unidentified, 0 = proven unavailable.
     *
     * DELEGATED, NOT DERIVED FROM THE STATE CONSTANT, and graphmodel added the method for
     * exactly this reason. The constants are ordered by COST -- HAVE 0, BUILDABLE 1, UNKNOWN
     * 2, UNAVAILABLE 3 -- so `unknown` sits ABOVE `buildable` there, while this rank has to
     * put BOTH at 1. Any arithmetic inversion of the constants separates precisely the pair
     * that must stay together, which is the collapse-`unknown` failure in a new place, and it
     * would pass every type check. An undescribed category is 1, not 0.
     */
    int availabilityRank(int recipeId) {
        int categoryId = g.recipes().categoryId(recipeId);
        return machineStates == null ? 1 : machineStates.availabilityRank(categoryId);
    }

    /** The category's state, or -1 when nothing describes it. */
    private int machineState(int categoryId) {
        return machineStates == null ? -1 : machineStates.state(categoryId);
    }

    // A PIN NARROWS NOTHING; IT REORDERS. `acceptable(keyId, candidates)` used to live here
    // and returned only the recipes a pin permits, for `pickRecipe` to plan from -- and the
    // two went together, so removing that method left this one with no caller. `rank` is the
    // surviving way a pin reaches a decision and it MOVES the pinned candidates to the front
    // instead of replacing the list, which is the stronger version of the same constraint:
    // an unsatisfiable pin still leaves the backtracking somewhere to go. DO NOT reintroduce
    // a filtering variant; see `rank` for why a plan beats an error here.
    //
    // `pickRecipe` CLAIMED THE RECIPE-CHOOSER PAGE RANKED WITH IT, AND THE PAGE NOW EXISTS AND
    // DOES NOT. #188 built the picker; `RecipeChoices.forNode` is its data source and its own
    // javadoc says why it declines to rank -- "any cost-based order here would be a second
    // opinion about ranking that the solver has already formed". So the javadoc was not merely
    // ahead of its time, it was wrong about a design decision that has since been made the
    // other way. DO NOT add a public ranking entry point back for that page; it does not want
    // one.

    // -- expansion ---------------------------------------------------------------------

    /** An `ore:` node resolves to whichever concrete member suits us best. */
    private PlanNode resolveOre(int keyId, long need, Set<Integer> ancestors, int depth) {
        String key = g.key(keyId);
        int groupId = g.oreGroupId(key.substring(Keys.ORE_PREFIX.length()));
        Csr members = g.oreMembers();
        int count = groupId < 0 ? 0 : members.count(groupId);
        if (count == 0) {
            leafTotals.add(keyId, need);
            PlanNode node = newNode(keyId, need);
            node.status = PlanStatus.RAW;
            node.note = "oredict members unknown";
            return node;
        }
        // Same three-tier rule as `pickAlternative`, for the same reason: with nothing in
        // stock every member ties on availability and the choice used to fall to dump order.
        int best = members.at(members.start(groupId));
        long bestAvail = available(best);
        boolean bestCraftable = craftables.contains(best);
        double bestCost = -slotCost(best, need);
        for (int p = members.start(groupId) + 1; p < members.end(groupId); p++) {
            int member = members.at(p);
            long avail = available(member);
            boolean craftable = craftables.contains(member);
            double cost = -slotCost(member, need);
            if (avail > bestAvail
                    || (avail == bestAvail && !bestCraftable && craftable)
                    || (avail == bestAvail && bestCraftable == craftable && cost > bestCost)) {
                best = member;
                bestAvail = avail;
                bestCraftable = craftable;
                bestCost = cost;
            }
        }
        PlanNode child = expand(best, need, ancestors, depth);
        PlanNode node = newNode(keyId, need);
        node.status = PlanStatus.OREDICT;
        node.resolvedTo = g.key(best);
        node.children = Collections.singletonList(child);
        return node;
    }

    PlanNode expand(int keyId, long need, Set<Integer> ancestors, int depth) {
        nodes++;
        work++;
        PlanNode node = newNode(keyId, need);

        if (work > workBudget) {
            exhausted = true;
            node.status = PlanStatus.DEPTH;
            leafTotals.add(keyId, need);
            return node;
        }

        if (nodes > maxNodes || depth > maxDepth) {
            node.status = PlanStatus.DEPTH;
            leafTotals.add(keyId, need);
            return node;
        }

        if (g.key(keyId).startsWith(Keys.ORE_PREFIX)) {
            return resolveOre(keyId, need, ancestors, depth);
        }

        long fromStock = take(keyId, need);
        if (fromStock != 0) {
            node.fromStock = fromStock;
        }
        long remainder = need - fromStock;
        if (remainder <= 0) {
            node.status = PlanStatus.HAVE;
            return node;
        }

        // Checked before `raw`/`craftables` and before any recipe lookup: if you own an
        // infinite source for this, there is nothing to plan and nothing to buy.
        String source = freeSources.get(keyId);
        if (source != null) {
            node.status = PlanStatus.SOURCE;
            node.note = source;
            fromSources.add(keyId, remainder);
            return node;
        }

        // AFTER stock and free sources, BEFORE `raw`/`craftables` and any recipe lookup, and
        // each half of that placement is a claim.
        //
        // After stock, because spending what you already hold is strictly better than
        // spending EMC, and `take` has drawn the pool down so only the shortfall is charged.
        // After free sources, because those are genuinely free and this is not.
        //
        // Before recipes, because that is the whole point: `erebus:materials` has a recipe in
        // the graph -- it is "dropped by a dungeon", expressed as a pseudo-item -- and
        // descending into it produces the dead end #50 was reported for. A player with a
        // working transmutation network does not go and farm a dungeon for an item their
        // network already makes.
        if (emcAvailable.contains(keyId)) {
            node.status = PlanStatus.EMC;
            node.note = "EMC " + String.format(java.util.Locale.ROOT, "%,d", g.emc(keyId))
                    + ", learned";
            fromEmc.add(keyId, remainder);
            return node;
        }

        boolean isCraftable = craftables.contains(keyId);
        if (raw.contains(keyId) || isCraftable) {
            node.status = isCraftable ? PlanStatus.HAVE : PlanStatus.RAW;
            if (isCraftable) {
                node.note = "AE2 can autocraft";
            } else {
                leafTotals.add(keyId, remainder);
            }
            return node;
        }

        if (ancestors.contains(keyId)) {
            node.status = PlanStatus.CYCLE;
            leafTotals.add(keyId, remainder);
            return node;
        }

        // A WORLD ORE IS AN ACQUISITION UNIT: you go and hit it with a pick. Stopping here
        // rather than descending is what makes a plan bottom out at "18 Sednanite Ore"
        // instead of walking a denomination ladder to "18 Sednanite Nugget" -- #106, where
        // the nugget was the only rung with no producer and therefore the only place the walk
        // COULD stop, while the ore had two Plasmatic Condenser recipes and so looked
        // craftable.
        //
        // Checked AFTER stock, free sources, `raw` and `craftables`: each of those is a
        // better answer than "go mining" when it applies, and `take` has already drawn the
        // pool down, so only the shortfall is ever charged to a pickaxe.
        //
        // NOT CONDITIONAL ON THE CRAFTED ROUTE BEING WORSE, and that is provable rather than
        // merely measured. The relaxation prices an output at `machine_entry + inputs / qty`
        // and does NOT divide the entry by the yield -- so no crafted route can price below
        // the cheapest possible entry, `MACHINE_COST["have"]`, which is 1.0, which is
        // `BASE_RAW_COST`, which is what mining now costs. A comparison here would be a
        // branch that can never take its other side.
        if (g.isWorldOre(keyId)) {
            node.status = PlanStatus.RAW;
            // WHERE, when the graph knows the ore only generates somewhere you have never
            // been (#112). The cost model already charges for the trip, and a route that got
            // dearer without saying why is worse than one that never mentioned it: the number
            // is invisible and the plan just looks wrong.
            String dimension = dimensionGates.get(keyId);
            node.note = dimension != null
                    ? "mined on " + dimension + ", and you have not been there"
                    : "mined, not crafted";
            if (dimension != null) {
                node.dimension = dimension;
            }
            leafTotals.add(keyId, remainder);
            return node;
        }

        List<Integer> candidates = realProducers(keyId);
        if (candidates.isEmpty()) {
            Integer kind = tokenKinds.get(keyId);
            if (kind != null) {
                // Tallied apart from `leafTotals`, which IS the shopping list. "1 Dungeon
                // Drop" on a list of materials to gather reads as a thing to acquire; it is
                // an instruction, and it belongs with the other instructions.
                node.status = PlanStatus.TOKEN;
                // The NAME on the wire. `Tokens` numbers the kinds so the cost table can
                // index them; `tokens.py` writes "GATE" and the fixtures freeze that.
                node.tokenKind = Tokens.kindName(kind);
                tokensNeeded.add(keyId, remainder);
                return node;
            }
            node.status = PlanStatus.RAW;
            int other = reachableForm(keyId);
            if (other >= 0) {
                // SAY WHAT WE CANNOT DO, rather than pricing it. #136
                //
                // The reported case: a plan bottomed out on "Blaze Data Model (Superior)"
                // listed beside "128 Granite" as though it were a thing to go and fetch. It
                // is not. Deep Mob Learning levels a model by killing mobs in a Simulation
                // Chamber -- a kill counter, not a recipe -- so no dump can carry it and the
                // graph has no route to that tier at all. Measured: 374 data-model keys on
                // the reference graph, 125 with any producer, and those 125 are exactly two
                // tiers, the craftable fresh model and Self-Aware.
                //
                // THE MARK IS DISPLAY-ONLY AND MUST STAY THAT WAY. The underlying defect is
                // the relaxation giving an unreachable leaf `BASE_RAW_COST`, which is what
                // made the tier the CHEAPEST thing in the plan and won it the route. Fixing
                // that is #136 and needs both cost audits; moving a price from here would
                // change routing with none of that scrutiny, and no test here would notice.
                node.unsourced = Boolean.TRUE;
                node.note = unsourcedNote(keyId, other) + g.bareName(other);
            }
            leafTotals.add(keyId, remainder);
            return node;
        }

        Set<Integer> next = new HashSet<Integer>(ancestors);
        next.add(keyId);
        Ranking ranking = rank(candidates, next, keyId);
        List<Integer> ranked = ranking.order;
        // score -> shape counts, per this expand. See interchangeableCount.
        Map<RecipeScore, Map<Object, Integer>> tieCache =
                new HashMap<RecipeScore, Map<Object, Integer>>();

        // Try recipes best-first and BACKTRACK out of any whose subtree loops back on an
        // ancestor. Uncrafting recipes (block -> 9 ingots) otherwise get chosen for their
        // single simple input and produce a plan that asks for the item being crafted. Only
        // accept a cycling recipe if every option cycles.
        int bestCycles = 0;
        int bestNodes = 0;
        PlanNode bestAttempt = null;
        Snapshot bestRestore = null;
        // THE CONSTANT DIRECTLY, because there is nothing to vary it. This read a
        // `branchTries` field that only ever held `DEFAULT_BRANCH_TRIES`; see the constructor
        // for why its `Builder` setter went. Must stay equal to `solve.py`'s `branch_tries`
        // default of 4 -- the golden gate compares plans byte for byte, so a divergence here
        // shows up as every backtracked branch differing.
        int tries = Math.min(DEFAULT_BRANCH_TRIES, ranked.size());
        for (int rank = 0; rank < tries; rank++) {
            if (work > workBudget) {
                break;
            }
            Snapshot snapshot = snapshot();
            PlanNode attempt = build(node, ranked.get(rank), keyId, remainder, fromStock,
                    next, depth,
                    interchangeableCount(ranking, ranked.get(rank), keyId, tieCache));
            int cycles = attempt.countCycles();
            if (cycles == 0) {
                noteOverruledPin(keyId, ranked.get(rank));
                return attempt;
            }
            // All routes may cycle -- in this dataset smelting recipes are absent, so
            // ore -> ingot chains dead-end and every route eventually loops. Keep the most
            // informative attempt: fewest cycle leaves, then the largest expansion, so the
            // plan still shows the real ingredients it did resolve.
            int attemptNodes = -attempt.countNodes();
            // Python's key is `(cycles, -nodes, rank)`. The third element is dropped here
            // because it cannot decide anything: `rank` is this loop's counter, so a later
            // attempt always has a HIGHER rank and can never win a tie on it. Keeping it
            // would be a comparison whose other side is unreachable.
            if (bestAttempt == null || cycles < bestCycles
                    || (cycles == bestCycles && attemptNodes < bestNodes)) {
                bestCycles = cycles;
                bestNodes = attemptNodes;
                bestAttempt = attempt;
                bestRestore = snapshot();
            }
            restore(snapshot);
        }

        if (bestAttempt == null) {
            node.status = PlanStatus.RAW;
            leafTotals.add(keyId, remainder);
            return node;
        }
        restore(bestRestore);
        noteOverruledPin(keyId, bestAttempt.recipe);
        return bestAttempt;
    }

    /**
     * Candidates in score order, best first, with any pinned ones moved to the front.
     *
     * BOTH SORTS ARE STABLE AND THAT IS THE CONTRACT. The first keeps equally-scored
     * candidates in `realProducers` order, which is what makes a plan reproducible. The
     * second keeps the pinned ones in score order among themselves, and MOVES them rather
     * than replacing the list: if every one of them cycles, the backtracking above still has
     * somewhere to go, and a plan beats an error.
     */
    private Ranking rank(List<Integer> candidates, Set<Integer> ancestors, int keyId) {
        final Map<Integer, RecipeScore> scores =
                new HashMap<Integer, RecipeScore>(candidates.size() * 2);
        for (int recipeId : candidates) {
            scores.put(recipeId, scoreRecipe(recipeId, ancestors));
        }
        List<Integer> ranked = new ArrayList<Integer>(candidates);
        Collections.sort(ranked, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return scores.get(b).compareTo(scores.get(a));
            }
        });
        final Set<String> wanted = pinned.get(keyId);
        if (wanted != null && !wanted.isEmpty()) {
            Collections.sort(ranked, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    int left = wanted.contains(g.recipes().rid(a)) ? 0 : 1;
                    int right = wanted.contains(g.recipes().rid(b)) ? 0 : 1;
                    return Integer.compare(left, right);
                }
            });
        }
        // THE SCORES GO BACK WITH THE ORDER, because #181 needs them and recomputing would
        // double the cost of the hottest path. This map is exactly what the sort already
        // built; nothing extra is scored. Mirrors python keeping its `scored` list.
        return new Ranking(ranked, scores);
    }

    /**
     * A ranked candidate list AND the scores that produced it.
     *
     * `rank` used to return the list alone and throw the scores away, which meant #181's tie
     * count would have had to call `scoreRecipe` a second time -- correct, invisible in every
     * test, and roughly double the cost of the most expensive part of planning. Handing them
     * back costs one object per `expand`.
     */
    private static final class Ranking {
        final List<Integer> order;
        final Map<Integer, RecipeScore> scores;

        Ranking(List<Integer> order, Map<Integer, RecipeScore> scores) {
            this.order = order;
            this.scores = scores;
        }
    }

    /**
     * What makes two recipes the SAME OFFER rather than merely equally scored. #181.
     *
     * The merged slot view, because that is what `build` expands and what `scoreRecipe`
     * counts; the per-run output of the key being planned, because two recipes yielding 1000
     * and 1 are not the same offer however they score; the category, because a different
     * machine is a different thing to go and build; and the transfer flag.
     *
     * SLOT IDENTITY IS DELIBERATELY EXCLUDED, and it is the crux. The 62 Digital Mob Agonizer
     * recipes for `fluid:lifeessence` differ precisely in WHICH four data models they accept,
     * and that is the entire reason a player might prefer one. Including the identities would
     * make every shape unique and the whole measurement vacuous. The shape is the OFFER, not
     * the ingredients. Mirrors `Solver.offer_shape` in python.
     *
     * A STRING KEY rather than a value class, because it only ever feeds a HashMap and a
     * record type would need equals/hashCode maintained in step with python's tuple.
     */
    private String offerShape(int recipeId, int keyId) {
        RecipeStore store = g.recipes();
        List<MergedSlot> slots = mergeSlots(recipeId);
        // Sorted, matching python's `tuple(sorted(...))`: slot ORDER must not make two
        // otherwise identical offers look different.
        List<String> parts = new ArrayList<String>(slots.size());
        for (MergedSlot slot : slots) {
            parts.add(slot.options + "x" + slot.qty);
        }
        Collections.sort(parts);
        long perRun = 0;
        for (int p = store.outputStart(recipeId); p < store.outputEnd(recipeId); p++) {
            if (store.outputKeyAt(p) == keyId) {
                perRun += store.outputQtyAt(p);
            }
        }
        return parts + "|" + perRun + "|" + store.categoryId(recipeId) + "|"
                + store.isTransfer(recipeId) + "|" + Bits.get(notProduction, recipeId);
    }

    /**
     * How many recipes tied with `chosen` are the same offer as it. 1 when none are.
     *
     * READS THE SCORES `rank` ALREADY BUILT. No `scoreRecipe` call happens here and none may
     * be added: a second scoring pass would be correct, invisible in every test, and would
     * roughly double the cost of the most expensive part of planning.
     *
     * THE SUBSET MUST CONTAIN THE CHOSEN RECIPE, which is narrower than "the largest
     * interchangeable subset among the tied". The mark claims the pick was arbitrary, which
     * is a claim about the recipe actually taken, so a larger group the winner is not part of
     * would be a true number beside a false statement. Mirrors python.
     */
    private int interchangeableCount(Ranking ranking, int chosen, int keyId,
                                     Map<RecipeScore, Map<Object, Integer>> cache) {
        RecipeScore score = ranking.scores.get(chosen);
        if (score == null) {
            return 1;
        }
        Map<Object, Integer> counts = cache.get(score);
        if (counts == null) {
            List<Integer> tied = new ArrayList<Integer>();
            for (int recipeId : ranking.order) {
                RecipeScore other = ranking.scores.get(recipeId);
                // `compareTo == 0` rather than equals: RecipeScore has no equals, and its
                // comparator already handles -0.0 the way python's `==` does.
                if (other != null && other.compareTo(score) == 0) {
                    tied.add(recipeId);
                }
            }
            counts = new HashMap<Object, Integer>();
            // Cheap gate: the interchangeable subset is a SUBSET of the tied set, so a tie
            // too small to reach the threshold cannot produce a mark and needs no shapes.
            if (tied.size() >= TIE_MIN) {
                for (int recipeId : tied) {
                    Object shape = offerShape(recipeId, keyId);
                    Integer seen = counts.get(shape);
                    counts.put(shape, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
                }
            }
            cache.put(score, counts);
        }
        Integer n = counts.get(offerShape(chosen, keyId));
        return n == null ? 1 : n.intValue();
    }

    /**
     * The base item, when `keyId` is an NBT STATE of something the graph CAN make. -1
     * otherwise, which is the common case and the whole reason this is narrow.
     *
     * WHY NOT "NOTHING PRODUCES IT", WHICH IS THE OBVIOUS RULE. Cobblestone has no producer
     * either. Marking every producerless leaf would badge most of a shopping list, and a
     * mark that fires on almost everything carries no information -- which is the failure
     * #136 measured for every rule keying on `producers == 0` alone.
     *
     * WHY NOT `baseKey(key) != key` ON ITS OWN. That matches 47,417 keys on the reference
     * graph, and the top of that list is every Forestry bee species. A Forest Drone with no
     * producer is CORRECT and unremarkable: you get one out of a hive. What makes the
     * data-model tier different is the second clause, that the graph demonstrably CAN make
     * the plain item -- so the plan is resting on a state it has no route to, and there is a
     * specific other form to point the reader at.
     *
     * DELIBERATELY DOES NOT COVER A PLAIN KEY NOTHING MAKES -- the Sednanite Nugget that
     * opened #136. It carries no NBT, so there is no other form to name, and #136's
     * measurement found no signal separating it from an ordinary mob drop.
     *
     * `Keys.baseKey` and NOT `Keys.withoutMeta` here, which is the opposite of the choice the
     * inventory index makes: the question is "is this one NBT STATE of an item", and meta
     * separates genuinely different items. Python calls `model.base_key` for the same reason.
     */
    /**
     * Which of the three things {@link #reachableForm} found, in words a player can act on.
     *
     * THREE WORDINGS FOR THREE CLAIMS, and collapsing them loses the action. A STATE means
     * "you have the item, this tier is out of reach"; a FORM means "this shape is not made,
     * use the other one"; a VARIANT means "the thing IS made, just carrying NBT this row
     * does not name" -- where the next move is to go and look at the variant rather than to
     * substitute anything. Mirrors `Solver._unsourced_note` in python and is held to it by
     * the golden gate.
     */
    private String unsourcedNote(int keyId, int other) {
        String key = g.key(keyId);
        if (!Keys.baseKey(key).equals(key)) {
            return "no recipe reaches this state; the graph can only make ";
        }
        if (Keys.baseKey(g.key(other)).equals(key)) {
            return "nothing makes this exact item; the graph makes ";
        }
        return "nothing makes this form; the graph can only make ";
    }

    /**
     * The other form of this key the graph CAN make, or -1. See {@link Unsourced}.
     *
     * A DELEGATE, NOT A COPY, and the javadoc for the rule lives there. #176 gave {@link
     * Cost} a second reason to ask the same question, and python already showed what two
     * spellings of it cost: `api._reachable_form` and the solver's copy drifted for two
     * releases, so `/api/sweep` under-reported by 210 keys while the test comparing them
     * passed. The scratch buffer is this solver's own, so nothing mutable is shared with a
     * concurrent solve.
     */
    int reachableForm(int keyId) {
        return Unsourced.reachableForm(g, keyId, scratch);
    }

    /**
     * Record a pin the backtracking had to ignore.
     *
     * The cycle guard outranks a pin, and it has to: a pinned uncrafting recipe
     * (`block -&gt; 9 ingots`) produces a plan that asks for the item being crafted, which is
     * not a plan. But "your choice was not used" is exactly the silence #30 exists to end,
     * and the chooser had already badged the node `pinned`. So the plan says it.
     */
    private void noteOverruledPin(int keyId, int recipeId) {
        noteOverruledPin(keyId, recipeId < 0 ? null : g.recipes().rid(recipeId));
    }

    private void noteOverruledPin(int keyId, String rid) {
        Set<String> wanted = pinned.get(keyId);
        if (wanted != null && !wanted.isEmpty() && !wanted.contains(rid)) {
            pinsOverruled.put(keyId, "every recipe you pinned for " + g.bareName(keyId)
                    + " loops back on its own ingredients, so the plan uses another route");
        }
    }

    /** Everything a discarded branch must not leave behind. */
    private static final class Snapshot {
        final KeyCounter pool;
        final KeyCounter usedFromStock;
        final KeyCounter leafTotals;
        final KeyCounter fromSources;
        final KeyCounter tokensNeeded;
        final KeyCounter fromEmc;
        final Map<String, String[]> machinesNeeded;
        final int nodes;

        Snapshot(KeyCounter pool, KeyCounter usedFromStock, KeyCounter leafTotals,
                 KeyCounter fromSources, KeyCounter tokensNeeded, KeyCounter fromEmc,
                 Map<String, String[]> machinesNeeded, int nodes) {
            this.pool = pool;
            this.usedFromStock = usedFromStock;
            this.leafTotals = leafTotals;
            this.fromSources = fromSources;
            this.tokensNeeded = tokensNeeded;
            this.fromEmc = fromEmc;
            this.machinesNeeded = machinesNeeded;
            this.nodes = nodes;
        }
    }

    /**
     * DO NOT include `work` here. It is the monotonic budget counter and rewinding it removes
     * the only guarantee the search terminates. Every OTHER accumulator must be listed, or a
     * rejected attempt's draw is counted twice; `fromSources` was added for exactly that
     * reason.
     *
     * `machinesNeeded` WAS THE ONE STILL MISSING, on both sides. It is written in {@link #build}
     * before the children are expanded, so every attempt the cycle guard discarded left its
     * machine behind, and a three-node plan for a Chest reported two machines that appear
     * nowhere in its tree. See `solve.Solver._snapshot` in python, which carries the measured
     * case, and keep the two lists identical -- the golden plan fixtures compare
     * `machines_to_build` field for field.
     */
    private Snapshot snapshot() {
        return new Snapshot(pool.copy(), usedFromStock.copy(), leafTotals.copy(),
                fromSources.copy(), tokensNeeded.copy(), fromEmc.copy(),
                new LinkedHashMap<String, String[]>(machinesNeeded), nodes);
    }

    private void restore(Snapshot snap) {
        pool = snap.pool;
        usedFromStock = snap.usedFromStock;
        leafTotals = snap.leafTotals;
        fromSources = snap.fromSources;
        tokensNeeded = snap.tokensNeeded;
        fromEmc = snap.fromEmc;
        machinesNeeded = snap.machinesNeeded;
        nodes = snap.nodes;
    }

    /** Expand one specific recipe choice for `keyId`. */
    private PlanNode build(PlanNode base, int recipeId, int keyId, long remainder,
                           long fromStock, Set<Integer> ancestors, int depth,
                           int interchangeable) {
        RecipeStore store = g.recipes();
        long perRun = 1;
        for (int p = store.outputStart(recipeId); p < store.outputEnd(recipeId); p++) {
            if (store.outputKeyAt(p) == keyId) {
                perRun = store.outputQtyAt(p);
                break;
            }
        }
        if (perRun == 0) {
            perRun = 1;   // `or 1` on the Python side: a zero-yield output is not a divisor
        }
        // Python's `-(-remainder // per_run)`, which is ceiling division. Written through
        // floorDiv rather than as `(a + b - 1) / b`, because Java's `/` truncates toward zero
        // and the shorthand overflows on a large remainder.
        long runs = -Math.floorDiv(-remainder, perRun);

        PlanNode node = base.copy();
        node.status = fromStock != 0 ? PlanStatus.PARTIAL : PlanStatus.CRAFT;
        node.recipe = store.rid(recipeId);
        node.category = g.categoryName(store.categoryId(recipeId));
        node.runs = runs;
        node.perRun = perRun;
        node.alternatives = realProducerCount(keyId);

        // Rendered as a badge, because a choice you cannot see is a choice you cannot audit
        // and this one changes every plan that touches the item.
        Set<String> wanted = pinned.get(keyId);
        if (wanted != null && wanted.contains(store.rid(recipeId))) {
            node.pinned = Boolean.TRUE;
        } else if (interchangeable >= TIE_MIN) {
            // THE PICK WAS ARBITRARY AND THE PLAN SAYS SO. #181. Suppressed under a pin --
            // hence the `else` -- because a pin is the player having already answered "which
            // of these", and a node carrying both badges contradicts itself.
            // Mirrors `solve._build` in python and is held to it by the golden gate.
            node.interchangeable = Integer.valueOf(interchangeable);
        }
        int machineId = store.machineId(recipeId);
        if (machineId >= 0) {
            node.machine = g.machineName(machineId);
        }
        int categoryId = store.categoryId(recipeId);
        int state = machineState(categoryId);
        if (state >= 0) {
            // The NAME on the wire, not the int. The state constants are an index into
            // `Cost.MACHINE_COST` and a ranking; `machines.py` writes the word, and the
            // fixtures freeze the word.
            node.machineState = MachineInfo.stateName(state);
            if (state != MachineInfo.HAVE) {
                MachineInfo info = machineStates.info(categoryId);
                node.machineWhy = info == null ? "" : info.why();
                machinesNeeded.put(node.category, new String[] {
                    node.machine != null ? node.machine : node.category,
                    node.machineState, node.machineWhy,
                });
            }
        }

        List<PlanNode> children = new ArrayList<PlanNode>();
        for (MergedSlot slot : mergeSlots(recipeId)) {
            // `slot.qty` AND NOT `slot.qty * runs` FOR A RETAINED SLOT, mirroring
            // `solve.py:_build`. #175: a run that never spends the slot needs the same one
            // however many times you run it, and multiplying by `runs` is what asked for 64
            // Blaze Data Models when the answer is one. Still expanded, because you genuinely
            // cannot run the recipe without it; the quantity was the defect, not the row.
            PlanNode child = expand(slot.keyId,
                    slot.survivesRun() ? slot.qty : slot.qty * runs, ancestors, depth + 1);
            if (slot.options > 1) {
                child.altCount = slot.options;
            }
            if (slot.survivesRun()) {
                child.notConsumed = Boolean.TRUE;
            }
            children.add(child);
        }
        node.children = children;
        return node;
    }

    /** One input slot after merging: what it resolves to, how much, and how wide it was. */
    static final class MergedSlot {
        final int keyId;
        long qty;
        int options;
        /** Probability a run consumes this slot. 1.0 on every graph predating #175. */
        final float consumeChance;

        MergedSlot(int keyId, long qty, int options, float consumeChance) {
            this.keyId = keyId;
            this.qty = qty;
            this.options = options;
            this.consumeChance = consumeChance;
        }

        boolean survivesRun() {
            return consumeChance == 0.0f;
        }
    }

    /**
     * Input slots collapsed onto what each one RESOLVES to. `model.merge_slots`, with this
     * class's own `pickAlternative` as the notion of "the same".
     *
     * The one view of a recipe's ingredients this class has: {@link #scoreRecipe} ranks the
     * recipe by it and {@link #build} expands it, so the two cannot disagree about how many
     * ingredients there are.
     *
     * Expanding a 3x3 of one ingredient per slot drew nine copies of an identical subtree,
     * which is nine times the nodes at every such step -- the node cap was being spent on
     * duplicates, so the tree that got truncated was mostly repeat. 21,417 of the reference
     * pack's 117,685 recipes have at least two slots that collapse.
     *
     * `options` is the WIDEST slot's alternative count, not the first's: a merged row that
     * reported 1 option while standing in for a slot that accepted 3 would misstate the
     * choice that was made.
     *
     * INSERTION-ORDERED, because the children of a plan node come out in this order and the
     * fixtures freeze it.
     */
    List<MergedSlot> mergeSlots(int recipeId) {
        RecipeStore store = g.recipes();
        Map<Long, MergedSlot> merged = new LinkedHashMap<Long, MergedSlot>();
        for (int slot = store.slotStart(recipeId); slot < store.slotEnd(recipeId); slot++) {
            int chosen = pickAlternative(slot);
            if (chosen < 0) {
                continue;   // an empty slot: a spacer in the recipe grid
            }
            int options = store.altEnd(slot) - store.altStart(slot);
            float chance = store.slotConsumeChance(slot);
            Long bucket = Long.valueOf(mergeBucket(chosen, chance));
            MergedSlot row = merged.get(bucket);
            if (row == null) {
                merged.put(bucket,
                        new MergedSlot(chosen, store.slotQty(slot), options, chance));
            } else {
                row.qty += store.slotQty(slot);
                row.options = Math.max(row.options, options);
            }
        }
        return new ArrayList<MergedSlot>(merged.values());
    }

    /**
     * The key two slots must share to merge: the resolved item AND its consume chance (#175).
     *
     * SO THE SAME ITEM CAN PRODUCE TWO ROWS, and that is the point. A recipe may hold one item
     * permanently and spend the same item as an ingredient, which are two different
     * requirements: own one, and spend N per run. Bucketing on the item alone fused them and
     * SUMMED their quantities, turning the retained one into stock you spend. `model.merge_slots`
     * buckets identically, and the two must agree or the golden fixtures diverge.
     *
     * The 3x3-of-one-ingredient collapse this method exists for is unaffected, because those
     * nine slots share a chance.
     *
     * PACKED INTO A `long` RATHER THAN A STRING KEY: this runs once per slot of every candidate
     * recipe, and a `String.format` there would allocate on the hot path. `+ 0.0f` normalises
     * `-0.0f` to `0.0f` before taking the bits, because the two compare equal and would
     * otherwise land in different buckets, splitting a row on a distinction nothing else makes.
     */
    private static long mergeBucket(int keyId, float chance) {
        return ((long) keyId << 32) | (Float.floatToIntBits(chance + 0.0f) & 0xffffffffL);
    }

    // -- the wire format ----------------------------------------------------------------

    private PlanNode newNode(int keyId, long need) {
        PlanNode node = new PlanNode();
        // `kind` travels with every node so the renderers never need the graph to know a
        // water row is a fluid, and so the JSON output is self-describing.
        node.key = g.key(keyId);
        node.name = g.display(keyId);
        node.kind = g.kind(keyId);
        node.label = g.bareName(keyId);
        node.need = need;
        return node;
    }

    private PlanEntry entry(int keyId, long qty) {
        return new PlanEntry(g.key(keyId), g.display(keyId), g.kind(keyId),
                g.bareName(keyId), qty);
    }

    private List<PlanEntry> entries(KeyCounter counter) {
        List<PlanEntry> out = new ArrayList<PlanEntry>();
        for (KeyCounter.Entry e : counter.mostCommon()) {
            out.add(entry(e.keyId, e.count));
        }
        return out;
    }

    /**
     * The whole result. THIS IS THE WIRE FORMAT; see {@link PlanResult}.
     *
     * ONE SOLVE PER SOLVER. The accumulators are instance state -- the pool is drawn down, the
     * shopping list adds up -- so a second call would return the first plan's totals with the
     * second plan's tree bolted on. Python has the same property and simply does not do it;
     * here it throws, because a Java caller holding an object with a `solve` method will
     * eventually call it twice and the wrong answer is entirely plausible-looking.
     */
    public PlanResult solve(int keyId, long qty) {
        if (solved) {
            throw new IllegalStateException(
                    "this Solver has already produced a plan; build another one. Its stock "
                            + "pool and totals are spent state, not inputs.");
        }
        solved = true;
        PlanResult result = new PlanResult();
        result.tree = expand(keyId, qty, Collections.<Integer>emptySet(), 0);
        result.target = g.key(keyId);
        result.targetName = g.display(keyId);
        for (Map.Entry<Integer, String> e : pinsOverruled.entrySet()) {
            result.pinsOverruled.put(g.key(e.getKey()), e.getValue());
        }
        result.qty = qty;
        result.shoppingList = entries(leafTotals);
        // ONLY THIS LIST. `entry` feeds five, and decorating it there put "no known source"
        // on rows in "drawn from AE2 stock" -- a row that exists precisely BECAUSE you are
        // holding the item -- and on infinite-source and token rows, each of which already
        // carries its own and contradictory answer to "how do I get this".
        //
        // Recomputed from `reachableForm` rather than copied off the tree node, so the list
        // and the tree cannot disagree about the same key. The tree is the diagnosis; this is
        // what gets acted on while gathering.
        for (PlanEntry row : result.shoppingList) {
            if (reachableForm(g.keyId(row.key)) >= 0) {
                row.unsourced = Boolean.TRUE;
            }
        }
        result.usedFromStock = entries(usedFromStock);

        result.fromSources = entries(fromSources);
        for (PlanEntry row : result.fromSources) {
            String why = freeSources.get(g.keyId(row.key));
            row.why = why == null ? "" : why;
        }
        result.tokensNeeded = entries(tokensNeeded);
        for (PlanEntry row : result.tokensNeeded) {
            Integer kind = tokenKinds.get(g.keyId(row.key));
            row.tokenKind = kind == null ? "" : Tokens.kindName(kind);
        }
        result.fromEmc = entries(fromEmc);
        for (PlanEntry row : result.fromEmc) {
            row.emc = g.emc(g.keyId(row.key));
        }

        List<String> categories = new ArrayList<String>(machinesNeeded.keySet());
        Collections.sort(categories);
        result.machinesToBuild = new ArrayList<PlanResult.MachineToBuild>();
        for (String category : categories) {
            String[] row = machinesNeeded.get(category);
            result.machinesToBuild.add(
                    new PlanResult.MachineToBuild(category, row[0], row[1], row[2]));
        }

        result.nodes = nodes;
        result.work = work;
        result.truncated = nodes > maxNodes || exhausted;
        result.exhausted = exhausted;
        result.maxNodes = maxNodes;
        result.workBudget = workBudget;
        return result;
    }

    // -- helpers ------------------------------------------------------------------------

    /**
     * `graph.real_producers`, materialised. Order is the graph's and must stay so.
     *
     * THE DEMOTED FILTER IS HERE AND NOT ON THE GRAPH, which is where python puts it, and the
     * reason does not transfer: {@code GraphService} hands one graph to concurrent off-thread
     * solves, so the verdict cannot live on shared state. Same split as {@link Unsourced}.
     */
    private List<Integer> realProducers(int keyId) {
        scratch.clear();
        g.realProducers(keyId, scratch);
        List<Integer> out = new ArrayList<Integer>(scratch.size());
        for (int i = 0; i < scratch.size(); i++) {
            int recipe = scratch.get(i);
            if (!Bits.get(notProduction, recipe)) {
                out.add(Integer.valueOf(recipe));
            }
        }
        return out;
    }

    private boolean hasRealProducers(int keyId) {
        return realProducerCount(keyId) > 0;
    }

    /** How many, without materialising the list. `alternatives` only ever wants the count. */
    private int realProducerCount(int keyId) {
        scratch.clear();
        g.realProducers(keyId, scratch);
        int total = 0;
        for (int i = 0; i < scratch.size(); i++) {
            if (!Bits.get(notProduction, scratch.get(i))) {
                total++;
            }
        }
        return total;
    }

    // -- construction ---------------------------------------------------------------------

    /**
     * A builder rather than a fourteen-argument constructor.
     *
     * `solve.Solver.__init__` takes seventeen keyword arguments and almost every caller
     * passes a handful. Positional Java would make every call site a row of nulls whose
     * meaning is its index, and adding an argument would silently renumber them.
     */
    public static final class Builder {
        private final RecipeGraph graph;
        private KeyCounter have;
        private Set<Integer> raw = new HashSet<Integer>();
        private Set<Integer> craftables = new HashSet<Integer>();
        private Map<Integer, String> freeSources = new LinkedHashMap<Integer, String>();
        private Set<Integer> emcAvailable = new HashSet<Integer>();
        private Map<Integer, Set<String>> pinned = new LinkedHashMap<Integer, Set<String>>();
        private Map<Integer, Integer> tokenKinds = new LinkedHashMap<Integer, Integer>();
        private Map<Integer, String> dimensionGates = new LinkedHashMap<Integer, String>();
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int maxNodes = DEFAULT_MAX_NODES;
        private MachineStates machineStates;
        private CostTable costs;

        public Builder(RecipeGraph graph) {
            this.graph = graph;
        }

        /**
         * Stock, as `{key id: count}`. Its key set is fixed once the Solver is built.
         *
         * A NEGATIVE KEY ID IS DROPPED RATHER THAN STORED, because it names a key this graph
         * has never seen and no branch can ever draw on it. That is the normal case and not
         * an error: most of a real AE2 network is enchanted gear and NBT species no recipe
         * touches -- #62 counted 174,705 of them -- and Python's pool simply carries those
         * along unmatched. Dropping them here is the same behaviour, and it has to happen
         * BEFORE the base-key index is built: `RecipeGraph.key(-1)` throws
         * `IndexOutOfBoundsException: string id -1`, which says nothing whatever about a
         * stock file holding an item the pack has removed.
         */
        public Builder have(Map<Integer, Long> stock) {
            KeyCounter counter = new KeyCounter();
            for (Map.Entry<Integer, Long> e : stock.entrySet()) {
                if (e.getKey() != null && e.getKey() >= 0) {
                    counter.add(e.getKey(), e.getValue());
                }
            }
            this.have = counter;
            return this;
        }

        /** User-declared "stop here" items. */
        public Builder raw(Set<Integer> keys) {
            this.raw = new HashSet<Integer>(keys);
            return this;
        }

        /** AE2 autocraftable, so treated as had. */
        public Builder craftables(Set<Integer> keys) {
            this.craftables = new HashSet<Integer>(keys);
            return this;
        }

        /**
         * `{key id: why}` for outputs of an infinite generator the player owns.
         *
         * These terminate a branch like stock does but are NOT added to the pool: a pool is
         * finite and would report a made-up number as "drawn from stock". Draw is tallied
         * separately so the quantity stays visible.
         */
        public Builder freeSources(Map<Integer, String> sources) {
            this.freeSources = new LinkedHashMap<Integer, String>(sources);
            return this;
        }

        /**
         * Keys the ProjectE network can transmute: learned AND carrying an EMC value.
         *
         * Held apart from `freeSources` even though both terminate a branch, because they
         * make DIFFERENT claims and a plan has to be able to say which. A generator is
         * infinite and free; EMC is finite and fungible, and the row has to name its grounds
         * so a reader can check it. See #50.
         */
        public Builder emcAvailable(Set<Integer> keys) {
            this.emcAvailable = new HashSet<Integer>(keys);
            return this;
        }

        /**
         * `{key id: acceptable recipe ids}`, from {@link Pins#resolve}.
         *
         * A SET, not one id: a pin that lapsed onto its category accepts every recipe in it,
         * and this class keeps its own ranking among whatever is acceptable rather than being
         * handed a choice someone else made by dump order.
         */
        public Builder pinned(Map<Integer, Set<String>> pins) {
            Map<Integer, Set<String>> copy = new LinkedHashMap<Integer, Set<String>>();
            for (Map.Entry<Integer, Set<String>> e : pins.entrySet()) {
                copy.put(e.getKey(), new LinkedHashSet<String>(e.getValue()));
            }
            this.pinned = copy;
            return this;
        }

        /** `{key id: Tokens kind}` from the token resolver. */
        public Builder tokenKinds(Map<Integer, Integer> kinds) {
            this.tokenKinds = new LinkedHashMap<Integer, Integer>(kinds);
            return this;
        }

        /**
         * `{ore key id: dimension name}` for an ore only an unvisited dimension generates.
         *
         * Reporting only: the cost model has already priced the trip, and this is what lets
         * the plan say which one.
         */
        public Builder dimensionGates(Map<Integer, String> gates) {
            this.dimensionGates = new LinkedHashMap<Integer, String>(gates);
            return this;
        }

        public Builder maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        public Builder maxNodes(int nodeCap) {
            this.maxNodes = nodeCap;
            return this;
        }

        public Builder machineStates(MachineStates states) {
            this.machineStates = states;
            return this;
        }

        public Builder costs(CostTable table) {
            this.costs = table;
            return this;
        }

        public Solver build() {
            return new Solver(this);
        }
    }
}
