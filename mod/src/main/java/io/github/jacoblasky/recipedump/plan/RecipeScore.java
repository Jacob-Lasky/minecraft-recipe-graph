package io.github.jacoblasky.recipedump.plan;

/**
 * `Solver.score_recipe`'s return value: an eight-element tuple, compared left to right.
 *
 * A CLASS RATHER THAN A `double`, because the terms are not commensurable and collapsing them
 * into one number needs weights that do not exist. Python compares the tuple lexicographically
 * and every element is a separate claim with its own justification; a weighted sum would let a
 * large `satisfied` outvote a real cost difference, which is precisely the failure
 * `score_recipe`'s ordering comment warns about.
 *
 * THE ORDER OF THE FIELDS IS THE ORDER OF THE CLAIMS. Do not reorder them to group the ints
 * together.
 */
final class RecipeScore implements Comparable<RecipeScore> {

    /**
     * 0 when the entry is not production at all, 1 when it is.
     *
     * THREE THINGS SHARE THIS TERM: a container fill/empty, a random loot table and a JEI card
     * explaining how to automate something. None of them turns its inputs into its outputs, so
     * the claim is identical and a separate term per kind would be a silent ordering decision
     * between statements that have no order. See {@link NonProduction} for the last two.
     */
    private final int production;
    /**
     * Negated slots that consume something already on the path to this recipe.
     *
     * ABOVE {@link #cheap} ON PURPOSE, and this is #172. Whether a plan can be performed at
     * all is not a thing a price should outvote: a route consuming an ancestor produces a
     * plan for X whose shopping list contains X.
     */
    private final int negatedAncestorCyclic;
    /** `-cost`, or negative infinity when the recipe prices at infinity. */
    private final double cheap;
    /**
     * Negated slots that consume one of this recipe's OWN outputs and no ancestor.
     *
     * BELOW {@link #cheap}, settling ties, and that is deliberate. An Insolator that eats a
     * seed and gives the seed back is a sustainable farm rather than a cycle; ranking it
     * above price was measured and sends the plan to a dearer route. See `Solver.score_recipe`
     * in python for the two measured cases.
     */
    private final int negatedOwnCyclic;
    /** How many merged slots stock, a free source or AE2 already covers. */
    private final int satisfied;
    /** 1 when every raw leaf the recipe rests on is something you mine. */
    private final int oreBacked;
    /** Fewer ingredients, plus 0.1 for hand crafting. */
    private final double simplePlain;
    /** 2 = machine on hand, 1 = buildable or unidentified, 0 = proven unavailable. */
    private final int availability;

    RecipeScore(int production, int negatedAncestorCyclic, double cheap, int negatedOwnCyclic,
                int satisfied, int oreBacked, double simplePlain, int availability) {
        this.production = production;
        this.negatedAncestorCyclic = negatedAncestorCyclic;
        this.cheap = cheap;
        this.negatedOwnCyclic = negatedOwnCyclic;
        this.satisfied = satisfied;
        this.oreBacked = oreBacked;
        this.simplePlain = simplePlain;
        this.availability = availability;
    }

    @Override
    public int compareTo(RecipeScore other) {
        int cmp = Integer.compare(production, other.production);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(negatedAncestorCyclic, other.negatedAncestorCyclic);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareDoubles(cheap, other.cheap);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(negatedOwnCyclic, other.negatedOwnCyclic);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(satisfied, other.satisfied);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(oreBacked, other.oreBacked);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareDoubles(simplePlain, other.simplePlain);
        return cmp != 0 ? cmp : Integer.compare(availability, other.availability);
    }

    /**
     * `<` and `>` rather than {@link Double#compare}, so this agrees with Python.
     *
     * `Double.compare` orders -0.0 BELOW 0.0 and both of those arise here: `cheap` is
     * `-cost`, so a recipe priced at exactly 0.0 yields -0.0. Python's `<` calls them equal
     * and falls through to the next term; `Double.compare` would silently rank one route
     * above an identically-priced one on a sign bit. Negative infinity is ordered correctly
     * by both, and no term here can be NaN -- an infinite cost is mapped to negative infinity
     * before it arrives.
     */
    private static int compareDoubles(double a, double b) {
        if (a < b) {
            return -1;
        }
        return a > b ? 1 : 0;
    }
}
