package io.github.jacoblasky.recipedump.plan;

/**
 * `Solver.score_recipe`'s return value: a seven-element tuple, compared left to right.
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

    /** 0 for a container fill/empty, 1 for real production. A transfer is never production. */
    private final int production;
    /** `-cost`, or negative infinity when the recipe prices at infinity. */
    private final double cheap;
    /** Negated, so fewer cyclic slots ranks higher. */
    private final int negatedCyclic;
    /** How many merged slots stock, a free source or AE2 already covers. */
    private final int satisfied;
    /** 1 when every raw leaf the recipe rests on is something you mine. */
    private final int oreBacked;
    /** Fewer ingredients, plus 0.1 for hand crafting. */
    private final double simplePlain;
    /** 2 = machine on hand, 1 = buildable or unidentified, 0 = proven unavailable. */
    private final int availability;

    RecipeScore(int production, double cheap, int negatedCyclic, int satisfied,
                int oreBacked, double simplePlain, int availability) {
        this.production = production;
        this.cheap = cheap;
        this.negatedCyclic = negatedCyclic;
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
        cmp = compareDoubles(cheap, other.cheap);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(negatedCyclic, other.negatedCyclic);
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
