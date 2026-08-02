package io.github.jacoblasky.recipedump.graph;

/**
 * The retained-heap breakdown of one {@link RecipeGraph}, by component.
 *
 * FOUR BUCKETS PLUS A REMAINDER, and the split is chosen so the report answers the question
 * #126 actually asks -- which component dominates -- rather than merely totalling. Two
 * figures are broken out INSIDE those buckets because each is a design decision someone
 * might want to reverse and needs a price to argue about: the recipe-id blob, which nothing
 * on a hot path reads, and the key table's lookup index, which only a
 * string-to-id conversion needs.
 */
public final class GraphSizes {

    /** The interned key blob, its lookup index, and the per-key kind column. */
    public final long keyTable;
    /** Recipe CSR arrays, the recipe-id blob, and the small category/machine/source tables. */
    public final long recipes;
    /** Deduplicated display strings plus the per-key name column. */
    public final long names;
    /** by-output, by-input, oredict membership, wildcard siblings and the derived bitsets. */
    public final long adjacency;
    /**
     * Per-item facts that are not recipes: EMC, durability, blueprint identity, icon slots.
     *
     * Its OWN bucket rather than folded into `other` because these all arrived at once with
     * dump schema 5 and the dump format is still moving. A separate line makes "what did the
     * last schema bump cost us" answerable from the report instead of by diffing two runs.
     */
    public final long itemFacts;
    /** Ore group names, catalysts, category mods, dimension ores, multiblock structures. */
    public final long other;

    /** Part of {@link #recipes}: the dump's recipe ids. */
    public final long recipeIds;
    /** Part of {@link #keyTable}: the string-to-id lookup index. */
    public final long keyLookupIndex;

    GraphSizes(long keyTable, long recipes, long names, long adjacency, long itemFacts,
               long other, long recipeIds, long keyLookupIndex) {
        this.keyTable = keyTable;
        this.recipes = recipes;
        this.names = names;
        this.adjacency = adjacency;
        this.itemFacts = itemFacts;
        this.other = other;
        this.recipeIds = recipeIds;
        this.keyLookupIndex = keyLookupIndex;
    }

    public long total() {
        return keyTable + recipes + names + adjacency + itemFacts + other;
    }

    @Override
    public String toString() {
        return Sizes.row("keys (interned table)", keyTable)
                + Sizes.row("recipes", recipes)
                + Sizes.row("names", names)
                + Sizes.row("adjacency indexes", adjacency)
                + Sizes.row("item facts (emc, damage, blueprints, icons)", itemFacts)
                + Sizes.row("other (ores, catalysts, multiblocks)", other)
                + Sizes.row("TOTAL", total())
                + "\n"
                + Sizes.row("  of which recipe ids", recipeIds)
                + Sizes.row("  of which key lookup index", keyLookupIndex);
    }
}
