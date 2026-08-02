package io.github.jacoblasky.recipedump.plan;

/**
 * Item costs, plus the per-category machine entry costs they were computed with.
 *
 * WHY THE ENTRY COSTS RIDE ALONGSIDE rather than being a second argument every caller must
 * remember to thread: the relaxation and the ranking deriving that number separately is
 * exactly how they came to disagree about what a route costs. Carried on the table, the price
 * the ranker charges CANNOT drift from the price the relaxation used.
 *
 * <h2>A dense array, and why that is exactly equivalent to python's dict</h2>
 *
 * Python holds `{key: cost}` and reads it with `cost.get(key, default)`, where the default is
 * +inf almost everywhere and `BASE_RAW_COST` in two places. Here it is a
 * {@code double[]} over key ids, pre-filled with +inf.
 *
 * That substitution is only sound because NOTHING EVER STORES +inf IN THE PYTHON DICT. The
 * seed writes 0.0, the generator cost, a raw-leaf price, an EMC price or a token price; the
 * relaxation only ever writes a finite `per_unit`; the reshaped-only pass writes
 * `BASE_RAW_COST`. So "absent" and "+inf" are the same statement, and the two lookups that
 * default to `BASE_RAW_COST` instead are written to test for infinity explicitly. DO NOT add
 * a writer that stores +inf without revisiting those two, because at that point "absent" and
 * "priced at infinity" stop being the same thing and the ore fallback silently changes
 * meaning.
 */
public final class CostTable {

    private final double[] cost;
    /** Per category: the entry cost, or NaN when this table carries none for it. */
    private final double[] machineEntry;

    CostTable(double[] cost, double[] machineEntry) {
        this.cost = cost;
        this.machineEntry = machineEntry;
    }

    /** What this key costs to obtain. +Infinity when nothing priced it. Never null. */
    public double cost(int keyId) {
        return keyId < 0 || keyId >= cost.length ? Double.POSITIVE_INFINITY : cost[keyId];
    }

    /**
     * True when this table was computed with a derived entry cost for the category.
     *
     * Absence is meaningful: it sends {@code Cost.categoryEntryCost} to the flat
     * {@code MACHINE_COST} figure, which is the right answer for a category that is `have`,
     * `unknown` or `unavailable`.
     */
    public boolean hasMachineEntry(int categoryId) {
        return machineEntry != null && categoryId >= 0 && categoryId < machineEntry.length
                && !Double.isNaN(machineEntry[categoryId]);
    }

    /** Only meaningful when {@link #hasMachineEntry}; NaN otherwise. */
    public double machineEntry(int categoryId) {
        return hasMachineEntry(categoryId) ? machineEntry[categoryId] : Double.NaN;
    }

    /** The raw entry column, so the relaxation can thread it without wrapping a table. */
    double[] machineEntries() {
        return machineEntry;
    }

    /** How many keys have a finite price. The figure a coverage report quotes. */
    public int pricedCount() {
        int total = 0;
        for (double value : cost) {
            if (!Double.isInfinite(value)) {
                total++;
            }
        }
        return total;
    }
}
