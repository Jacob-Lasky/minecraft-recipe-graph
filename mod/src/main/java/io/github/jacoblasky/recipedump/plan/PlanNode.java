package io.github.jacoblasky.recipedump.plan;

import java.util.List;

/**
 * One node of a plan tree. THIS IS THE WIRE FORMAT, field for field with `solve.py`.
 *
 * A NULL FIELD MEANS THE KEY IS ABSENT FROM THE JSON, not that it is present and null. The
 * Python original builds a dict and adds keys conditionally -- `node["from_stock"]` exists
 * only when something was drawn -- and `tests/fixtures/plan/*.json` freeze exactly that. A
 * Java side that emitted `"from_stock": null` would differ from every fixture while being
 * behaviourally identical, so {@link PlanJson} omits nulls and this class uses boxed types
 * wherever Python has a conditional key. That is the reason for the boxing; it is not
 * carelessness about allocation.
 *
 * Quantities are `long`. Python's ints do not overflow and this pack has a recipe yielding
 * 60,466,176 fruit, so an `int` node need is one multiplication away from wrapping into a
 * negative shopping list.
 *
 * Mutable on purpose. `Solver.expand` builds a node, decides its status, and may attach a
 * recipe and children afterwards; modelling that as a chain of immutable copies would be a
 * larger change to the port than the original file justifies.
 */
public final class PlanNode {

    // -- always present ----------------------------------------------------------------

    public String key;
    public String name;
    public String kind;
    public String label;
    public long need;
    public String status;

    // -- present only when they apply ---------------------------------------------------

    /** How much of `need` came out of stock. Absent when none did. */
    public Long fromStock;
    /** Why this node terminated: the generator, the EMC value, "mined, not crafted". */
    public String note;

    /** The chosen recipe's id, and what it is. Absent on every leaf. */
    public String recipe;
    public String category;
    /**
     * LONG, not Integer. `runs` is `ceil(need / perRun)` and `need` is a long for a reason --
     * this pack has a recipe yielding 60,466,176 fruit, so the quantities either side of that
     * division are already outside int range. Narrowing here would wrap a large plan's run
     * count negative, silently, at the last step.
     */
    public Long runs;
    public Long perRun;
    /** How many recipes could have made this, so the UI can offer the choice. */
    public Integer alternatives;
    /** Present and true only when the chosen recipe is one the player pinned. */
    public Boolean pinned;

    public String machine;
    public String machineState;
    public String machineWhy;

    /** `ore:` nodes only: which concrete member was chosen. */
    public String resolvedTo;
    /** How many alternatives this input slot offered. Set by the PARENT, not by the node. */
    public Integer altCount;
    /** A world ore whose dimension the player has never visited. */
    public String dimension;
    /** A pack placeholder standing in for an instruction rather than an item. */
    public String tokenKind;
    /**
     * Present and true only for a key that is an NBT state the graph cannot reach (#136).
     *
     * OPTIONAL, AND ITS ABSENCE MEANS FALSE. Never written as `false`, matching Python, so a
     * reader must not expect the key.
     */
    public Boolean unsourced;

    /** Absent on a leaf; never an empty list, because Python never writes one. */
    public List<PlanNode> children;

    /** How many nodes this subtree holds, itself included. Mirrors `solve._count_nodes`. */
    int countNodes() {
        int total = 1;
        if (children != null) {
            for (PlanNode child : children) {
                total += child.countNodes();
            }
        }
        return total;
    }

    /** How many cycle leaves this subtree bottomed out on. Mirrors `solve._count_cycles`. */
    int countCycles() {
        int total = PlanStatus.CYCLE.equals(status) ? 1 : 0;
        if (children != null) {
            for (PlanNode child : children) {
                total += child.countCycles();
            }
        }
        return total;
    }

    /** A shallow copy, matching `dict(base)` in `Solver._build`. */
    PlanNode copy() {
        PlanNode other = new PlanNode();
        other.key = key;
        other.name = name;
        other.kind = kind;
        other.label = label;
        other.need = need;
        other.status = status;
        other.fromStock = fromStock;
        other.note = note;
        other.recipe = recipe;
        other.category = category;
        other.runs = runs;
        other.perRun = perRun;
        other.alternatives = alternatives;
        other.pinned = pinned;
        other.machine = machine;
        other.machineState = machineState;
        other.machineWhy = machineWhy;
        other.resolvedTo = resolvedTo;
        other.altCount = altCount;
        other.dimension = dimension;
        other.tokenKind = tokenKind;
        other.unsourced = unsourced;
        other.children = children;
        return other;
    }
}
