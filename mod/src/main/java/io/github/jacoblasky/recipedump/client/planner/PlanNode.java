package io.github.jacoblasky.recipedump.client.planner;

import java.util.Collections;
import java.util.List;

/**
 * One node of a solved plan, in the shape the Python oracle froze.
 *
 * THIS IS THE DECOUPLING THE WHOLE PANEL RESTS ON. The Java `Solver` is still in flight, so
 * the renderer is built against the PLAN SHAPE rather than against a solver: the 22 fixtures
 * under `tests/fixtures/plan/` each carry a full result under `result`, and #135 froze that
 * shape as the contract both implementations answer to. When the Java solver lands it hands
 * back these same fields and no widget code changes.
 *
 * Which is also why this class is a plain immutable value with no behaviour beyond reading
 * itself: the moment it starts deciding anything, the two implementations have somewhere to
 * disagree.
 *
 * NULLABILITY IS THE SHAPE, NOT AN OVERSIGHT. `key`, `kind`, `label`, `name`, `need` and
 * `status` are on all 571 nodes across the fixtures; `category`, `machine`, `recipe`, `runs`
 * and `perRun` only on the 312 that are crafted; the rest appear where they mean something.
 * A getter returning null is the node saying "this does not apply to me".
 */
public final class PlanNode {

    private final String key;
    private final String kind;
    private final String label;
    private final String name;
    private final long need;
    private final String status;

    private final String category;
    private final String machine;
    private final String machineState;
    private final String machineWhy;
    private final String recipe;
    private final long runs;
    private final long perRun;

    private final int alternatives;
    private final int altCount;
    private final String note;
    private final String resolvedTo;
    private final String dimension;
    private final String tokenKind;
    private final long fromStock;
    private final boolean pinned;

    private final List<PlanNode> children;

    PlanNode(Builder builder) {
        this.key = builder.key;
        this.kind = builder.kind;
        this.label = builder.label;
        this.name = builder.name;
        this.need = builder.need;
        this.status = builder.status;
        this.category = builder.category;
        this.machine = builder.machine;
        this.machineState = builder.machineState;
        this.machineWhy = builder.machineWhy;
        this.recipe = builder.recipe;
        this.runs = builder.runs;
        this.perRun = builder.perRun;
        this.alternatives = builder.alternatives;
        this.altCount = builder.altCount;
        this.note = builder.note;
        this.resolvedTo = builder.resolvedTo;
        this.dimension = builder.dimension;
        this.tokenKind = builder.tokenKind;
        this.fromStock = builder.fromStock;
        this.pinned = builder.pinned;
        this.children = Collections.unmodifiableList(builder.children);
    }

    /** The graph key. `minecraft:iron_ingot`, `fluid:water`, `ore:ingotIron`, `id#digest`. */
    public String key() {
        return key;
    }

    /** `item`, `fluid`, `ore` or `essentia`. */
    public String kind() {
        return kind;
    }

    /** The display name, already resolved by the planner. Never a raw key. */
    public String label() {
        return label;
    }

    /** The label with its kind prefix, as the web UI shows it: `[fluid] Molten Iron`. */
    public String name() {
        return name;
    }

    /** How many are needed. A LONG: one Borax plan asks for 934,400 mB of water. */
    public long need() {
        return need;
    }

    /** See {@link NodeStatus}. `craft`, `raw`, `have`, `oredict`, `cycle`, ... */
    public String status() {
        return status;
    }

    public String category() {
        return category;
    }

    public String machine() {
        return machine;
    }

    /** `have`, `buildable`, `unknown` or `unavailable`; null when no machine was resolved. */
    public String machineState() {
        return machineState;
    }

    public String machineWhy() {
        return machineWhy;
    }

    /** The dump's recipe id, or null. `hei:minecraft.crafting:25760`, or a jar path. */
    public String recipe() {
        return recipe;
    }

    public long runs() {
        return runs;
    }

    public long perRun() {
        return perRun;
    }

    /** How many recipes MAKE this, including the one chosen. 0 when it is not crafted. */
    public int alternatives() {
        return alternatives;
    }

    /**
     * How many things the SLOT would have accepted, as opposed to how many recipes make what
     * is in it. Different question from {@link #alternatives}, and the web UI renders both.
     */
    public int altCount() {
        return altCount;
    }

    /** A sentence the planner attached: "mined, not crafted", "AE2 can autocraft". */
    public String note() {
        return note;
    }

    /** On an `ore` node, the concrete key the solver picked out of the group. */
    public String resolvedTo() {
        return resolvedTo;
    }

    /** The dimension this only generates in, when that is why it is gated. */
    public String dimension() {
        return dimension;
    }

    /** `loot`, `gate`, `hint` or `method`; refines a `token` status. */
    public String tokenKind() {
        return tokenKind;
    }

    /** How many of {@link #need} are already in stock. */
    public long fromStock() {
        return fromStock;
    }

    /** True when a pin, rather than the cost model, chose this node's recipe. */
    public boolean pinned() {
        return pinned;
    }

    public List<PlanNode> children() {
        return children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    @Override
    public String toString() {
        return need + "x " + label + " [" + status + "]";
    }

    static final class Builder {
        String key = "";
        String kind = "item";
        String label = "";
        String name = "";
        long need = 1L;
        String status = "craft";
        String category;
        String machine;
        String machineState;
        String machineWhy;
        String recipe;
        long runs;
        long perRun;
        int alternatives;
        int altCount;
        String note;
        String resolvedTo;
        String dimension;
        String tokenKind;
        long fromStock;
        boolean pinned;
        List<PlanNode> children = Collections.emptyList();

        PlanNode build() {
            return new PlanNode(this);
        }
    }
}
