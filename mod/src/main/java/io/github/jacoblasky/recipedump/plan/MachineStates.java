package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Every category's machine verdict, and the two views the rest of the planner takes of it.
 *
 * The cost model wants "what does routing through this category cost", the solver wants "may
 * a plan use this category at all", and the machines page wants the evidence. All three read
 * this one resolution, because deriving any of them separately is how the ranker came to
 * price a route the solver would not take.
 *
 * CATEGORY ORDER IS FIRST-APPEARANCE-IN-RECIPES, not ascending id. Python builds its map by
 * walking the recipe list, and catalyst categories are interned before any recipe is read, so
 * the two orders genuinely differ. Anything that reports a list of categories has to use
 * {@link #describedCategories}.
 */
public final class MachineStates {

    private final RecipeGraph graph;
    /** Category ids in first-appearance order. */
    private final int[] categories;
    /** Parallel to {@link #categories}. */
    private final MachineInfo[] infos;
    /** category id -&gt; index into {@link #infos}, or -1. Dense, because categories are few. */
    private final int[] slotOf;

    MachineStates(RecipeGraph graph, int[] categories, MachineInfo[] infos) {
        this.graph = graph;
        this.categories = categories;
        this.infos = infos;
        this.slotOf = new int[graph.categoryCount()];
        java.util.Arrays.fill(this.slotOf, -1);
        for (int i = 0; i < categories.length; i++) {
            this.slotOf[categories[i]] = i;
        }
    }

    /** Null when the category has no recipes, which is the state python leaves undescribed. */
    public MachineInfo info(int categoryId) {
        if (categoryId < 0 || categoryId >= slotOf.length || slotOf[categoryId] < 0) {
            return null;
        }
        return infos[slotOf[categoryId]];
    }

    /** The state constant, or -1 when the category was never described. */
    public int state(int categoryId) {
        MachineInfo info = info(categoryId);
        return info == null ? -1 : info.state();
    }

    /**
     * Categories a plan may route through.
     *
     * `unknown` is included alongside `buildable`. An unidentified machine is not evidence
     * that the player cannot use it, and excluding it would hide 40% of the graph -- which is
     * the whole reason `unknown` is a separate state.
     *
     * A category with no verdict at all is NOT available: it has no recipes, so nothing can
     * route through it anyway, and answering true would be a claim about a machine nobody
     * described.
     */
    public boolean isAvailable(int categoryId, boolean includeBuildable) {
        int state = state(categoryId);
        if (state < 0) {
            return false;
        }
        if (state == MachineInfo.HAVE) {
            return true;
        }
        return includeBuildable
                && (state == MachineInfo.BUILDABLE || state == MachineInfo.UNKNOWN);
    }

    /**
     * 2 = machine on hand, 1 = buildable OR unidentified, 0 = proven unavailable.
     *
     * NOT the inverse of the state constant, and that is the whole reason this exists rather
     * than being left for a caller to derive. `buildable` and `unknown` BOTH rank 1: an
     * unidentified machine is not evidence the player cannot use it, and ranking it with
     * `unavailable` walls off 40% of the graph. The state constants are ordered by COST,
     * where `unknown` sits above `buildable`, so a caller inverting them would separate
     * exactly the pair that must stay together.
     *
     * AN UNDESCRIBED CATEGORY RANKS 1, not 0. Same argument: silence is not evidence of
     * absence, and a category with no verdict must not be treated as proven out of reach.
     */
    public int availabilityRank(int categoryId) {
        switch (state(categoryId)) {
            case MachineInfo.HAVE:
                return 2;
            case MachineInfo.BUILDABLE:
            case MachineInfo.UNKNOWN:
                return 1;
            case MachineInfo.UNAVAILABLE:
                return 0;
            default:
                return 1;
        }
    }

    /** Category ids in first-appearance-in-recipes order. */
    public int[] describedCategories() {
        return categories.clone();
    }

    public int describedCount() {
        return categories.length;
    }

    public RecipeGraph graph() {
        return graph;
    }

    /**
     * The machine item keys whose price sets this category's entry cost, or null.
     *
     * EVERY CANDIDATE THAT EARNED A `buildable` VERDICT, not just the winner: more than one
     * block opens a lot of categories, and which is CHEAPEST is a question about prices this
     * class has none of. Ordering by cost here would mean importing the cost model into the
     * machine model, so the caller with the price table picks.
     *
     * Categories that are `have`, `unknown` or `unavailable` answer null rather than an empty
     * array. Their entry cost is the flat figure, and a present-but-empty answer would mean
     * "priced from a machine item", which for those three is a false claim: `have` is nearly
     * free by evidence, and the other two must keep the figures whose reasoning is recorded
     * on {@code Cost.MACHINE_COST}.
     *
     * THE GENERIC BLUEPRINT IS EXCLUDED, and it has to be excluded HERE rather than left for
     * the caller to notice. Specificity ordering demotes it because it is not the machine,
     * but demoting is not enough for PRICING: the caller takes the cheapest candidate, and a
     * blueprint is cheap, so one non-machine candidate would set the price for all 188
     * Modular Machinery categories and every multiblock would read as trivial again (#93).
     * Today it happens not to, because the bare `modularmachinery:itemblueprint` key has no
     * producer and prices at infinity while the real blueprints are NBT-discriminated
     * variants of it. That is luck, not a rule.
     */
    public int[] buildTargets(int categoryId) {
        MachineInfo info = info(categoryId);
        if (info == null || info.state() != MachineInfo.BUILDABLE) {
            return null;
        }
        IntArray buildable = new IntArray(4);
        IntArray machinesOnly = new IntArray(4);
        int[] candidates = info.candidates();
        int[] states = info.candidateStates();
        for (int i = 0; i < candidates.length; i++) {
            if (states[i] != MachineInfo.BUILDABLE) {
                continue;
            }
            buildable.add(candidates[i]);
            if (!Machines.NOT_A_MACHINE.contains(graph.key(candidates[i]))) {
                machinesOnly.add(candidates[i]);
            }
        }
        // Only when something is left. A category whose ONLY identified candidate is a
        // blueprint is no better identified than an `unknown` one, and inventing an empty
        // answer would charge the top of the band on this module's silence rather than on
        // evidence.
        if (machinesOnly.size() > 0) {
            return machinesOnly.trimmed();
        }
        return buildable.size() > 0 ? buildable.trimmed() : null;
    }

    /** Categories with a build target, in first-appearance order. */
    public int[] categoriesWithBuildTargets() {
        IntArray out = new IntArray();
        for (int category : categories) {
            if (buildTargets(category) != null) {
                out.add(category);
            }
        }
        return out.trimmed();
    }

    /** How many categories are in each state, indexed by the state constants. */
    public int[] summarise() {
        int[] counts = new int[MachineInfo.STATE_COUNT];
        for (MachineInfo info : infos) {
            counts[info.state()]++;
        }
        return counts;
    }
}
