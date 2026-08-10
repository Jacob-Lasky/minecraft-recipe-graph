package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.Csr;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * "Nothing makes this key, and the graph makes another form of it" -- in ONE place.
 *
 * ONE SPELLING, AND THAT IS THE POINT. Two callers need this now: {@link Solver}, which
 * badges a leaf and names the other form, and {@link Cost}, which prices the whole set at
 * {@code UNSOURCED_COST} for #176. Python had exactly that pair and kept two copies -- the
 * solver's and `api._reachable_form` -- and they drifted: #136 added the processed-form
 * branch to one and #170 added the produced-variant branch to one, so `/api/sweep`
 * under-reported by 210 keys while a test comparing the two copies passed the whole time,
 * because its five hand-picked cases all exercised the branch nobody had changed. #178
 * unified them onto `Graph`. DO NOT reintroduce a second spelling here.
 *
 * STATIC, WITH THE SCRATCH PASSED IN, RATHER THAN A METHOD ON {@link RecipeGraph}. That is
 * where python put it, and the reason does not transfer: the predicate needs an
 * {@link IntArray} to receive producer lists, and {@code GraphService} hands one graph to
 * concurrent off-thread solves, so mutable scratch living on the graph would be a data race
 * -- the same hazard as #165's index race, one class over. A caller that already has a
 * scratch buffer passes it; {@link #keys} allocates one for its single sweep.
 *
 * See `Graph.reachable_form` in python for WHY the rule is this narrow, which is the part
 * worth reading before changing anything here. The short version: "no producer" alone is
 * true of cobblestone and would badge most of a shopping list, so the second clause -- that
 * there is a specific other form to NAME -- is what keeps it worth reading.
 */
final class Unsourced {

    private Unsourced() {
    }

    /**
     * The other form of {@code keyId} the graph CAN make, or -1.
     *
     * Mirrors `Graph.reachable_form` in python and is held to it by the golden gate.
     */
    static int reachableForm(RecipeGraph g, int keyId, IntArray scratch) {
        if (realProducerCount(g, keyId, scratch) > 0) {
            return -1;
        }
        // A KEY THE PACK CALLS AN ORE IS OBTAINABLE, which outranks anything this predicate can
        // say: {@link Cost#seed} reads the world-ore set as a CEILING on what mining costs, and
        // the unsourced seed runs later and only raises. {@link Solver} returns "mined, not
        // crafted" before ever consulting this mark, so a badged world ore would show up only
        // in a sweep. Mirrors `Graph.reachable_form` in python, which carries the measurement.
        // `isMineableOre` SINCE #270, AND THAT IS WHAT KEEPS THE SENTENCE ABOVE TRUE RATHER
        // THAN ACCIDENTALLY TRUE: both populations it names -- the ceiling `Cost.seed`
        // applies and the branch `Solver` returns at -- have moved to the wider set. Reading
        // the narrower one here would let a shadow ore whose `ore*` group the pack deleted be
        // badged by this predicate while the seed priced it as mining, which is the
        // sweep-only badge the sentence says this guard exists to prevent. Measured a no-op
        // on today's data: the one key in the gap already returns -1 from a later clause.
        if (g.isMineableOre(keyId)) {
            return -1;
        }
        // A WILDCARD META HAS NO PRODUCERS BY CONSTRUCTION, so its count is evidence of
        // nothing: `producers` widens a concrete meta to `base:*` and never the reverse.
        // Measured on the reference graph -- without this, `natura:sticks:*` is badged
        // "nothing makes this form" while its concrete metas are ordinary craftable sticks.
        if (Keys.metaOf(g.key(keyId)) == Keys.META_WILDCARD) {
            return -1;
        }
        String key = g.key(keyId);
        String stem = Keys.baseKey(key);
        if (!stem.equals(key)) {
            // A STATE of a producible item: #139's half.
            int stemId = g.keyId(stem);
            return stemId >= 0 && realProducerCount(g, stemId, scratch) > 0 ? stemId : -1;
        }
        // A BARE key nothing makes while a VARIANT of it IS made: #170's half, and the third
        // face of one subsumption rule.
        //
        // IT IS ALSO ROUTED NOW, AND THIS IS STILL WIDER THAN THE ROUTE. #170 shipped
        // `RecipeGraph.subsumedBareKey`, so `Solver` plans a bare demand through the variant's
        // recipe and `Cost` prices the bare key at what making the variant costs. What reaches
        // here is either a key the route could not be taken for -- every variant made FROM the
        // bare key or from a sibling, or none reachable -- or a caller that is not the planner.
        // Both still need the answer, and `Graph.unsourced_keys` in python records why the
        // price FLOOR this set feeds must not be narrowed to match the route: a key dropped
        // from it falls back to `BASE_RAW_COST`, which is #170's original report. `producers`
        // itself remains un-widened; the direction #28 refused, a demand for `X#d` satisfied
        // by bare `X`, is the reverse of the one that shipped.
        // Mirrors `Graph.reachable_form` in python.
        for (int variant : g.variantsOf(keyId)) {
            if (realProducerCount(g, variant, scratch) > 0) {
                // First produced variant, which is the one the dump saw first: `variantsOf`
                // is insertion-ordered, and this reaches a plan tree the fixtures freeze.
                return variant;
            }
        }
        return obtainableSibling(g, keyId, scratch);
    }

    /**
     * Every key {@link #reachableForm} names another form for, as a bitset over key ids.
     *
     * COMPUTED ONCE PER TABLE, NOT PER KEY IN THE SEEDING LOOP, which is a sweep of every
     * live key rather than a lookup. Mirrors `Graph.unsourced_keys` in python.
     *
     * NOT "BECAUSE `estimate` SEEDS TWICE", which this javadoc said until #176 checked it.
     * {@link Cost#estimate} calls {@link Cost#seed} ONCE and hands `seed.clone()` to each of
     * the two relaxations, exactly as python hands `dict(seed)` to each of its own. The
     * hoisting is still worth it for the per-key reason above; the twice-per-estimate reason
     * was simply not true in either language.
     *
     * NOT CACHED ON THE GRAPH, unlike python, and deliberately: `GraphService` hands one
     * graph to concurrent off-thread solves, so a lazily-populated field on it would be a
     * data race. One sweep per cost table is the price of that, and a cost table is already
     * two full relaxations.
     *
     * OVER EVERY LIVE KEY, matching python. Only a consumed key can change a recipe's price,
     * so sweeping consumed keys alone would price every route identically -- but the set
     * also reaches `/api/cost`, and pricing a key one way in the table and another way in a
     * report is the drift #178 removed. 39 keys on the reference graph are the difference.
     */
    static long[] keys(RecipeGraph g) {
        long[] out = Bits.ofSize(g.keyCount());
        IntArray scratch = new IntArray();
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            if (!g.isLive(keyId)) {
                continue;
            }
            // A KEY WITH ANY PRODUCER IS OUT, INCLUDING A CONTAINER EMPTY, which is what
            // python's `not self.by_output.get(key)` says. `hasProducers` also widens to the
            // wildcard-meta sibling and `by_output.get` does not, and the two still agree on
            // the SET: for a wildcard-produced key, python's second clause calls
            // `real_producers`, which widens, finds the producer and returns None. So this
            // skip is the same population either way.
            //
            // A key produced ONLY by a transfer is therefore excluded here -- and it is not
            // left unpriced, because {@link #producedInNameOnly} above collects exactly those
            // and {@link Cost#seed} charges them the same figure. Before #193 nothing did, and
            // the seed and the relaxation between them left 120 fluids at infinity.
            if (g.hasProducers(keyId)) {
                continue;
            }
            if (reachableForm(g, keyId, scratch) >= 0) {
                Bits.set(out, keyId);
            }
        }
        return out;
    }

    /**
     * Keys some recipe OUTPUTS while {@link RecipeGraph#realProduction} says none of them
     * makes it. 120 on the reference graph, every one a fluid. #193.
     *
     * THE SAME CLAIM AS {@link #keys} AND A DIFFERENT POPULATION, which is why it is a second
     * bitset rather than a widening of that one. Both mean "the graph has POSITIVE EVIDENCE it
     * cannot explain where this comes from", so {@link Cost#seed} charges both
     * {@code UNSOURCED_COST}. They cannot be merged: {@link #keys} requires byOutput EMPTY and
     * this requires it non-empty, so the intersection is zero by construction -- measured 0 on
     * the reference graph -- and {@link #keys}'s second clause needs another form the graph CAN
     * make, which is None for all 120 because a fluid has no meta sibling, no NBT variant and
     * no form group. Mirrors `Graph.produced_in_name_only` in python, whose docstring carries
     * the measurement and the argument for the price.
     */
    static long[] producedInNameOnly(RecipeGraph g) {
        long[] out = Bits.ofSize(g.keyCount());
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            if (g.byOutput().count(keyId) > 0 && !g.realOutput(keyId)) {
                Bits.set(out, keyId);
            }
        }
        return out;
    }

    /**
     * Pack-authored keys nothing makes and {@link #reachableForm} can name no other form for.
     *
     * A THIRD POPULATION UNDER THE CLAIM THE OTHER TWO SHARE. #171 and #242. All three mean
     * "the graph has POSITIVE EVIDENCE it cannot explain where this comes from", so
     * {@link Cost#seed} charges all three {@code UNSOURCED_COST}, and all three intersections
     * are empty by construction: {@link #keys} needs byOutput empty AND a reachable form,
     * {@link #producedInNameOnly} needs byOutput non-empty, this needs byOutput empty and NO
     * reachable form. Measured 0 and 0 against the other two on the reference graph.
     *
     * WHAT MAKES THE EVIDENCE POSITIVE, since "nothing produces it" is the rule
     * {@link #reachableForm} refuses by name: the PACK authored the item, in its CraftTweaker
     * namespace, then wrote no way to obtain it while writing recipes that consume it. A mod
     * shipping an ore does that because the ore is in the ground. 46 recipes routed through a
     * JEI tooltip priced at {@code BASE_RAW_COST}, the cheapest value in the model.
     *
     * THE TWO EXCLUSIONS ARE PACK-DECLARED DATA, read back rather than guessed from the shape
     * of a key, and both are load-bearing. Measured over the `contenttweaker` keys with no
     * producer and at least one consumer, 1,120 in all:
     *
     * <ul>
     * <li>{@code damageBase(key) != key} drops 837 (75%), armour durability variants whose
     *     UNDAMAGED key is produced perfectly well.</li>
     * <li>Any oredict membership drops a further 47, and it has to be ANY group rather than
     *     {@link RecipeGraph#isWorldOre}: only 11 of the 47 are ores, the other 36 being 21
     *     nuggets, 13 storage blocks and 5 foods. Among the nuggets is
     *     `contenttweaker:material_part:53`, the Sednanite Nugget #136 was filed about, so an
     *     ore-only rule demotes the material the earlier bug was about.</li>
     * </ul>
     *
     * A THIRD EXCLUSION SINCE #171, AND IT IS THE ONLY ONE THAT READS POSITIVE EVIDENCE. The
     * two above say "this key is not what it looks like"; `declared_provenance` says "the pack
     * states how you get this, and no recipe could have carried it". `provenance.py` reads the
     * pack's own scripts and quest book, and the load-bearing case is `recipes.addHidden*`:
     * the pack registers 82 puzzle rewards as REAL crafting recipes and hides them from JEI on
     * purpose, so a JEI dump cannot see them BY CONSTRUCTION. 53 of the 285 keys that survive
     * the two exclusions above are declared, leaving 232 here. See {@link #packAuthoredDeclared}
     * for the other half and {@link Cost#provenanceCost} for what it costs instead.
     *
     * Mirrors `Graph.pack_authored_unsourced` in python, whose docstring carries the same
     * measurements; the golden fixtures hold the two implementations equal.
     */
    static long[] packAuthored(RecipeGraph g) {
        long[] out = Bits.ofSize(g.keyCount());
        IntArray scratch = new IntArray();
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            if (isPackAuthoredUnsourced(g, keyId, scratch)) {
                Bits.set(out, keyId);
            }
        }
        return out;
    }

    /**
     * The keys {@link #packAuthored} WOULD hold if the pack had not explained them. 53 on the
     * reference oracle, against that set's 232. #171/#262.
     *
     * THE OTHER HALF OF ONE PREDICATE, WHICH IS WHY IT IS HERE AND NOT BESIDE THE READER. Both
     * sets run {@link #isPackAuthoredUnexplained}'s five clauses and differ only in which side
     * of `declaredProvenance` they take, exactly as python shares
     * `Graph._is_pack_authored_unexplained` between its two properties. Splitting them any
     * other way lets the two drift into disagreeing about a key that is in neither or both --
     * and a key in NEITHER is priced by no rule at all, which is silent.
     *
     * AND IT IS NOT "EVERY KEY THE PACK DECLARES", WHICH IS THE MISTAKE THAT LOOKS RIGHT. The
     * pack declares 896 keys and 843 of them are items the graph already makes: 397 price
     * below `LOOT_COST` and `ae2stuff:adv_wireless_kit` prices at 380,435. Pricing every
     * declaration would say a quest awarding a thing IS a route to it -- measured on the
     * oracle, that moves 301 keys off their real prices and drags 20 more up through the
     * fixpoint. A declared key with a producer is priced by its producer, a declared world ore
     * by mining, a declared damage variant by its undamaged base. THE CLAUSES ARE NOT
     * NEGOTIABLE HERE EITHER.
     *
     * A BITSET AND NOT A MAP, because the kind is already on the graph: {@link Cost#seed} walks
     * this and reads {@link RecipeGraph#declaredProvenance} for the word. A map would be a
     * second copy of an answer the graph holds.
     */
    static long[] packAuthoredDeclared(RecipeGraph g) {
        long[] out = Bits.ofSize(g.keyCount());
        IntArray scratch = new IntArray();
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            if (isPackAuthoredDeclared(g, keyId, scratch)) {
                Bits.set(out, keyId);
            }
        }
        return out;
    }

    /**
     * The membership test for {@link #packAuthored}, for ONE key.
     *
     * A PREDICATE AS WELL AS A BITSET BECAUSE TWO CALLERS NEED DIFFERENT SHAPES, and one
     * spelling of the rule is the point. {@link Cost#seed} wants the whole set once per cost
     * table and walks the bitset; {@link Solver} wants "is THIS leaf one of them" for the
     * badge, once per plan node, and building a 171,000-key bitset per solve to answer that
     * would be absurd. Python has the same split -- a cached `frozenset` on the graph that
     * `cost._seed` iterates and `solve.expand` does an `in` against -- and both languages get
     * the same answer because the rule lives in exactly one function on each side.
     *
     * DO NOT INLINE THIS INTO EITHER CALLER. `reachable_form` is in this file at all because
     * a second spelling of it drifted through two issues while a test that compared the two
     * kept passing; that is the mistake this shape exists to not repeat.
     */
    static boolean isPackAuthoredUnsourced(RecipeGraph g, int keyId, IntArray scratch) {
        // THE ONLY CLAUSE THAT READS POSITIVE EVIDENCE, and the only one that separates this
        // from {@link #isPackAuthoredDeclared}. #171/#262.
        if (g.declaredProvenance(keyId) != null) {
            return false;
        }
        return isPackAuthoredUnexplained(g, keyId, scratch);
    }

    /**
     * The membership test for {@link #packAuthoredDeclared}, for ONE key. #171/#262.
     *
     * THE EXACT COMPLEMENT OF {@link #isPackAuthoredUnsourced} over the same five clauses, and
     * {@link Solver} needs the per-key shape for the same reason it needs the other one: a
     * badge decision on one plan node must not build a 300,000-key bitset.
     */
    static boolean isPackAuthoredDeclared(RecipeGraph g, int keyId, IntArray scratch) {
        return g.declaredProvenance(keyId) != null
                && isPackAuthoredUnexplained(g, keyId, scratch);
    }

    /**
     * The five clauses {@link #isPackAuthoredUnsourced} and {@link #isPackAuthoredDeclared}
     * share. Mirrors `Graph._is_pack_authored_unexplained` in python.
     *
     * ONE SPELLING, because the two predicates PARTITION its result on `declaredProvenance`
     * and a key that fell out of both would be silently unpriced by either rule -- neither the
     * `UNSOURCED_COST` sweep nor the provenance band would reach it, and nothing would say so.
     * See {@link #packAuthored} for what each clause is for and the measurement that bought it.
     *
     * LIVENESS IS A CLAUSE HERE AND NOT A LOOP BOUND, WHICH IS THE DIFFERENCE THAT BIT. The
     * unsourced half could get it from iterating live keys, so it never needed to say so; the
     * declared half iterates the PACK'S map, which names items no recipe touches. Python
     * shipped without this clause for one measurement and 54 dead keys -- `a_smithys_tablet`,
     * `toy_sword` -- went from infinity to a gate price, inventing a number for items that
     * cannot appear in any plan. DO NOT hoist this out into the callers' loops.
     */
    private static boolean isPackAuthoredUnexplained(RecipeGraph g, int keyId,
                                                     IntArray scratch) {
        if (!g.isLive(keyId) || g.hasProducers(keyId)) {
            return false;
        }
        String key = g.key(keyId);
        if (!inPackNamespace(key)) {
            return false;
        }
        if (g.oresOf().count(keyId) > 0) {
            return false;
        }
        // AND THE GRAPH DOES NOT ALREADY KNOW WHERE IT COMES FROM, WHICH IS #270. A key with
        // a dimension record is one the pack declared worldgen for; the defining claim of
        // this predicate is that the pack authored the item and then said nothing about how
        // to obtain it, and a worldgen declaration IS saying so.
        //
        // THE `oresOf` CLAUSE ABOVE CANNOT COVER IT, which is why this is a sixth clause and
        // not a widening of the fifth. The one key that reaches here is
        // `contenttweaker:sub_block_holder_1:8`, whose `ore*` group the pack DELETED; an
        // empty `oresOf` is exactly why it survives the fifth clause, and it is also why it
        // is absent from `worldOres`. See `RecipeGraph.isMineableOre`, the same absence read
        // from the other side.
        //
        // IN THE SHARED PREDICATE, NOT IN THE UNSOURCED HALF ALONE, for the reason this
        // method's javadoc gives: the two callers PARTITION this result on
        // `declaredProvenance`, so excluding on one side only would move the key into the
        // other -- a dimension ore badged with a puzzle-reward note instead of a false
        // unsourced one, which is a different wrong answer rather than a fix.
        //
        // `dimensionName` AND NOT `dimensionOf`, which is the trap that method's own javadoc
        // warns about: -1 is the Nether's real id, so a membership test written on the number
        // would exclude 21 gated Nether ores from this predicate by accident.
        //
        // Mirrors the same clause in `Graph._is_pack_authored_unexplained`, which carries the
        // measurement: 1 key leaves the unsourced set on `graph-oracle-248.json`, 284 -> 283.
        if (g.dimensionName(keyId) != null) {
            return false;
        }
        if (!key.equals(g.damageBase(key))) {
            return false;
        }
        return reachableForm(g, keyId, scratch) < 0;
    }

    /**
     * Whether a key lives in a namespace the PACK writes script items into.
     *
     * ONE SPELLING OF THE NAMESPACE LIST PER LANGUAGE, mirroring `tokens.TOKEN_NAMESPACES`,
     * which python's `Graph.pack_authored_unsourced` imports rather than restating for the
     * same reason. If the pack ever adds a second scripting namespace, both languages change
     * here and the golden fixtures catch a miss on either side.
     */
    private static boolean inPackNamespace(String key) {
        return key.startsWith("contenttweaker:");
    }

    /**
     * Another form of this key's material that the graph CAN make, or -1.
     *
     * THE #136 HALF. `nuggetSednanite` and `ingotSednanite` are one material by Forge's own
     * oredict convention, so a nugget nothing makes has a specific other form to point a
     * reader at -- which is the second clause {@link #reachableForm} refuses to badge
     * without, and the thing that was missing when the plain-key case was first declared out
     * of scope.
     *
     * DETERMINISTIC, because this reaches a plan tree and `tests/fixtures/plan/*.json`
     * freezes those: most producers wins, then the key string. That also makes the answer
     * "the form the pack actually makes" rather than whichever came back first.
     *
     * A `block&lt;Material&gt;` KEY ASKS THE SAME QUESTION, which is #136's second half: a
     * storage block nobody can press is as unobtainable as a nugget nobody can split. Only
     * the question widens. The search below still matches groups through
     * {@link Keys#materialOfOreGroup}, which excludes `block`, so the form NAMED is never a
     * block. A processed group wins over a storage one when a key carries both.
     *
     * Mirrors `Graph.obtainable_sibling` in python and is held to it by the golden gate.
     */
    private static int obtainableSibling(RecipeGraph g, int keyId, IntArray scratch) {
        String material = null;
        Csr ores = g.oresOf();
        for (int p = ores.start(keyId); p < ores.end(keyId) && material == null; p++) {
            material = Keys.materialOfOreGroup(g.oreGroupName(ores.at(p)));
        }
        for (int p = ores.start(keyId); p < ores.end(keyId) && material == null; p++) {
            material = Keys.storageMaterialOfOreGroup(g.oreGroupName(ores.at(p)));
        }
        if (material == null) {
            return -1;
        }
        int best = -1;
        int bestMade = 0;
        Csr members = g.oreMembers();
        for (int group = 0; group < g.oreGroupCount(); group++) {
            if (!material.equals(Keys.materialOfOreGroup(g.oreGroupName(group)))) {
                continue;
            }
            for (int p = members.start(group); p < members.end(group); p++) {
                int member = members.at(p);
                if (member == keyId) {
                    continue;
                }
                int made = realProducerCount(g, member, scratch);
                if (made <= 0) {
                    continue;
                }
                if (best < 0 || made > bestMade
                        || (made == bestMade && g.key(member).compareTo(g.key(best)) < 0)) {
                    best = member;
                    bestMade = made;
                }
            }
        }
        return best;
    }

    private static int realProducerCount(RecipeGraph g, int keyId, IntArray scratch) {
        scratch.clear();
        g.realProducers(keyId, scratch);
        return scratch.size();
    }
}
