package io.github.jacoblasky.recipedump.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The WORLD-STATE half of machine resolution: what is placed, what is held, what was
 * overridden by hand.
 *
 * Kept apart from the graph because the two change on completely different clocks. The graph
 * is pack data and moves when the pack does; this moves every time the player builds
 * something. Mixing them is how a cache ends up serving verdicts for a base nobody has any
 * more.
 *
 * EVIDENCE IS ORDERED BY HOW DIRECT IT IS, and that ordering is the whole design:
 *
 * <ol>
 * <li>a tile entity in the world save -- you built it, it is there;</li>
 * <li>the machine item in the network -- you have one, unplaced;</li>
 * <li>JEI recipe catalysts -- the exact category-to-machine mapping.</li>
 * </ol>
 *
 * Quest completion is deliberately NOT a signal here: it is a proxy for progression, whereas
 * a placed block is proof. What is UNLOCKED is a different question from what is BUILT.
 */
public final class Evidence {

    /** Tile-entity ids from the world save, verbatim, to the count seen. */
    private final Map<String, Integer> placed = new LinkedHashMap<String, Integer>();
    /** Item keys in the network, verbatim, to the quantity held. */
    private final Map<String, Long> stock = new LinkedHashMap<String, Long>();
    private final Map<String, Integer> overrides = new LinkedHashMap<String, Integer>();
    private final List<String> noMachine = new ArrayList<String>();

    /**
     * A block seen in the world. `count` of zero is NOT a sighting.
     *
     * A count of zero reaching the index would let the resolver report "placed: X" for a
     * block the scan counted none of, which is a false claim rather than a weak one.
     */
    public Evidence placed(String tileEntityId, int count) {
        if (count > 0) {
            placed.put(tileEntityId, Integer.valueOf(count));
        }
        return this;
    }

    /** An item held in the network. Zero is not a holding, for the same reason. */
    public Evidence stock(String itemKey, long qty) {
        if (qty > 0) {
            stock.put(itemKey, Long.valueOf(qty));
        }
        return this;
    }

    /** A hand-set state, which wins over everything automatic. */
    public Evidence override(String categoryUid, int state) {
        overrides.put(categoryUid, Integer.valueOf(state));
        return this;
    }

    /**
     * A category the user declares needs no machine at all.
     *
     * NEVER GATED on the dump schema, unlike the built-in patterns: an explicit human
     * decision outranks what the dump can prove.
     */
    public Evidence noMachine(String categoryUid) {
        noMachine.add(categoryUid);
        return this;
    }

    Map<String, Integer> placedBlocks() {
        return placed;
    }

    Map<String, Long> stockedItems() {
        return stock;
    }

    Map<String, Integer> overrideStates() {
        return overrides;
    }

    List<String> noMachineCategories() {
        return noMachine;
    }

    /**
     * The stock as key ids, for the cost seed. Keys the graph never saw are dropped.
     *
     * ONE definition of "the player holds this", shared with machine resolution. The two used
     * to filter separately -- resolution dropped a zero count and the cost seed took whatever
     * a caller passed -- which is two chances to disagree about whether owning nothing counts
     * as owning something.
     */
    public java.util.List<Integer> stockKeyIds(
            io.github.jacoblasky.recipedump.graph.RecipeGraph graph) {
        java.util.List<Integer> out = new ArrayList<Integer>();
        for (String key : stock.keySet()) {
            int id = graph.keyId(key);
            if (id >= 0) {
                out.add(Integer.valueOf(id));
            }
        }
        return out;
    }
}
