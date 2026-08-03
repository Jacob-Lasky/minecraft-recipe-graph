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
        // face of one subsumption rule. REPORTED, NOT REPRICED as far as `producers` goes --
        // whether the solver should ROUTE a bare demand through a produced variant is
        // contested (#28 refused it there) and stays open. #176 prices it; it does not route
        // it. Mirrors `Graph.reachable_form` in python.
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
     * COMPUTED ONCE PER TABLE, NOT PER KEY IN THE SEEDING LOOP, because `Cost.estimate`
     * seeds TWICE -- once per relaxation pass -- and the answer cannot change between them.
     * Mirrors `Graph.unsourced_keys` in python, which is a cached property for the same
     * reason.
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
            // `hasProducers`, not `realProducers`: python tests `graph.by_output.get(key)`,
            // which is every recipe that outputs it INCLUDING a container empty. The
            // predicate below then applies the narrower `real_producers` test itself, so a
            // key produced only by a transfer is excluded here and re-admitted there -- and
            // that asymmetry is python's, deliberately, because a transfer is a route the
            // relaxation may still lower this price through.
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
     * Mirrors `Graph.obtainable_sibling` in python and is held to it by the golden gate.
     */
    private static int obtainableSibling(RecipeGraph g, int keyId, IntArray scratch) {
        String material = null;
        Csr ores = g.oresOf();
        for (int p = ores.start(keyId); p < ores.end(keyId) && material == null; p++) {
            material = Keys.materialOfOreGroup(g.oreGroupName(ores.at(p)));
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
