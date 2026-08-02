package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.IntArray;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything {@link Cost#estimate} reads besides the graph itself.
 *
 * A parameter object because python's `estimate` takes eight optional arguments and a Java
 * signature with eight is a call site nobody can read or check. Every one is genuinely
 * optional and every one means "this influence is off" when absent -- which is also why they
 * are set rather than defaulted: a caller that forgets to pass stock gets a plan that assumes
 * an empty base, not a crash.
 *
 * SET-SEMANTICS, NOT QUANTITIES, for stock. Python tests `if qty:` and then writes 0.0, so
 * only the presence of a nonzero amount matters here; how much you have is the solver's
 * question, not the ranker's.
 */
public final class CostInputs {

    private final IntArray have = new IntArray();
    private final IntArray freeSources = new IntArray();
    private final IntArray emcAvailable = new IntArray();
    private final IntArray dimensionGated = new IntArray();
    private final Map<Integer, Integer> tokenKinds = new LinkedHashMap<Integer, Integer>();
    private Map<Integer, int[]> machineItems;
    private MachineStates machineStates;
    private int passes = Cost.PASSES;

    /** An item the player holds. Free at the margin, which is what makes stock preferred. */
    public CostInputs have(int keyId) {
        if (keyId >= 0) {
            have.add(keyId);
        }
        return this;
    }

    /** An item an infinite generator produces. Near-free, deliberately NOT free. */
    public CostInputs freeSource(int keyId) {
        if (keyId >= 0) {
            freeSources.add(keyId);
        }
        return this;
    }

    /**
     * An item the ProjectE network has LEARNED and the pack gives a positive EMC value.
     *
     * THE MEMBERSHIP TEST IS THE CALLER'S, and that is the safety property. What arrives here
     * is already "learned AND carrying a positive EMC value". An item the pack has DISABLED
     * has an EMC of 0, so it never reaches this list, and #50's stated worst case -- asserting
     * a route the pack blocked -- cannot happen here even if the knowledge file is wrong.
     */
    public CostInputs emcAvailable(int keyId) {
        if (keyId >= 0) {
            emcAvailable.add(keyId);
        }
        return this;
    }

    /**
     * An ore that only generates in a dimension the player has never visited.
     *
     * Raises a FLOOR rather than setting a price, which is why a wrong entry here is
     * survivable: a gated ore with any crafted route keeps that route's price, and all this
     * can do is stop MINING being the cheap answer.
     */
    public CostInputs dimensionGated(int keyId) {
        if (keyId >= 0) {
            dimensionGated.add(keyId);
        }
        return this;
    }

    /** A placeholder item and what it asks of the player. See {@link Tokens}. */
    public CostInputs token(int keyId, int kind) {
        if (keyId >= 0) {
            tokenKinds.put(Integer.valueOf(keyId), Integer.valueOf(kind));
        }
        return this;
    }

    public CostInputs machineStates(MachineStates states) {
        this.machineStates = states;
        return this;
    }

    /**
     * The machine items whose price sets each category's entry cost.
     *
     * SUPPLYING THIS IS WHAT TURNS ON THE SECOND RELAXATION PASS. Without it every buildable
     * machine charges the flat floor and two machines you have to build are priced
     * identically -- an AE2 grindstone and a fusion reactor both at 40.0, which is #86.
     */
    public CostInputs machineItems(Map<Integer, int[]> byCategory) {
        this.machineItems = new LinkedHashMap<Integer, int[]>(byCategory);
        return this;
    }

    /** The usual source of {@link #machineItems}: whatever the resolver said is buildable. */
    public CostInputs machineItemsFrom(MachineStates states) {
        Map<Integer, int[]> targets = new LinkedHashMap<Integer, int[]>();
        for (int category : states.categoriesWithBuildTargets()) {
            targets.put(Integer.valueOf(category), states.buildTargets(category));
        }
        return machineItems(targets);
    }

    public CostInputs passes(int passes) {
        this.passes = passes;
        return this;
    }

    int[] stockKeys() {
        return have.trimmed();
    }

    int[] freeSourceKeys() {
        return freeSources.trimmed();
    }

    int[] emcKeys() {
        return emcAvailable.trimmed();
    }

    int[] dimensionGatedKeys() {
        return dimensionGated.trimmed();
    }

    Map<Integer, Integer> tokens() {
        return tokenKinds;
    }

    Map<Integer, int[]> machineItems() {
        return machineItems;
    }

    MachineStates states() {
        return machineStates;
    }

    int passes() {
        return passes;
    }
}
