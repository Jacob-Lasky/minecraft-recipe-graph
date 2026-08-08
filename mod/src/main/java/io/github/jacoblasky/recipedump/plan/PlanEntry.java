package io.github.jacoblasky.recipedump.plan;

/**
 * One row of a plan's summary lists, matching `Solver._entry` and the three places that
 * decorate it.
 *
 * `why`, `tokenKind` and `emc` are null on a row that does not carry them, and {@link
 * PlanJson} omits null fields -- see {@link PlanNode} for why absence rather than null is the
 * contract. Python builds these with `dict(self._entry(k, n), why=...)`, so each list has a
 * different shape and only one extra key ever appears on a given row.
 */
public final class PlanEntry {

    public final String key;
    public final String name;
    public final String kind;
    public final String label;
    public final long qty;

    /** `from_sources` only: which generator, in words a player can act on. */
    public String why;
    /** `tokens_needed` only: what kind of instruction this placeholder stands for. */
    public String tokenKind;
    /**
     * `from_emc` only. Carried per row so the claim stays checkable -- "EMC 2,048" is
     * something a player can look up, where a bare "from EMC" is something they have to take
     * on trust.
     */
    public Long emc;
    /**
     * `shopping_list` only: this key is an NBT state the graph cannot reach (#136).
     *
     * Deliberately absent from the other four lists. See `Solver.solve` for why putting it on
     * a "drawn from stock" row contradicts the row it sits on.
     */
    public Boolean unsourced;
    /**
     * `shopping_list` only: how the PACK says you get this key. #171/#262.
     *
     * THE COMPLEMENT OF {@link #unsourced}, not another flavour of it, and set on the same one
     * list for the same reason: this is what a player carries into the world, so it is the
     * best place in the tool to say "solve its puzzle" and the worst place to say nothing.
     */
    public String provenance;

    PlanEntry(String key, String name, String kind, String label, long qty) {
        this.key = key;
        this.name = name;
        this.kind = kind;
        this.label = label;
        this.qty = qty;
    }
}
