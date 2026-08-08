package io.github.jacoblasky.recipedump.plan;

import java.util.Collections;
import java.util.List;

/**
 * One node of a plan tree. THIS IS THE WIRE FORMAT, field for field with `solve.py`.
 *
 * ONE CLASS FOR THE EMITTER AND THE RENDERER, and it was briefly two. `client.planner` carried
 * its own copy with the same 22 fields, built against the frozen plan shape while the Java
 * solver was still in flight -- a correct decision that expired the moment the solver landed.
 * What it left behind was worth removing: two ~175-line JSON layers over one format, and an
 * in-game path that would have serialised a `PlanResult` to a JSON STRING and parsed it back
 * into a second Java class, in the same JVM, for a 4,000 node plan, inside the one interaction
 * with a frame-rate gate on it.
 *
 * THE FIELDS ARE BOXED AND PACKAGE-PRIVATE; THE ACCESSORS ARE PUBLIC AND NORMALISE. The two
 * halves serve different callers and both are load-bearing:
 *
 *   * The EMITTER needs absence. Python omits a key rather than writing `false` or `0`, the
 *     golden fixtures freeze exactly which keys are present, and {@link PlanJson} must omit
 *     what is absent. A primitive field could not express that, and every leaf would start
 *     carrying `"from_stock": 0`.
 *   * The RENDERER needs never to null-check. A widget drawing a row has nothing to do
 *     differently for absent than for false, and a `null` inside a resize pass is swallowed
 *     by `WidgetTree.resizeInternal`, so the symptom is a blank panel and not a stack trace.
 *
 * `children()` RETURNING AN EMPTY LIST IS THE ONE TO BE MOST CAREFUL WITH. Every widget loop
 * and every tree walk goes through it. The FIELD stays null on a leaf, because Python emits no
 * `children` key there and the fixtures freeze that.
 *
 * Quantities are `long`. Python's ints do not overflow and this pack has a recipe yielding
 * 60,466,176 fruit, so an `int` node need is one multiplication away from wrapping into a
 * negative shopping list.
 *
 * Mutable WITHIN THIS PACKAGE on purpose: `Solver.expand` builds a node, decides its status,
 * and attaches children afterwards. Outside it the class is read-only, which is what the
 * widgets want; use {@link Builder} to construct one from outside.
 */
public final class PlanNode {

    // -- always present ----------------------------------------------------------------

    String key;
    String name;
    String kind;
    String label;
    long need;
    String status;

    // -- present only when they apply ---------------------------------------------------

    /** How much of `need` came out of stock. Absent when none did. */
    Long fromStock;
    /** Why this node terminated: the generator, the EMC value, "mined, not crafted". */
    String note;

    /** The chosen recipe's id, and what it is. Absent on every leaf. */
    String recipe;
    String category;
    /**
     * LONG, not Integer. `runs` is `ceil(need / perRun)` and `need` is a long for a reason --
     * this pack has a recipe yielding 60,466,176 fruit, so the quantities either side of that
     * division are already outside int range. Narrowing here would wrap a large plan's run
     * count negative, silently, at the last step.
     */
    Long runs;
    /**
     * What one run yields of the key this node is about, ON AVERAGE. #223.
     *
     * A DOUBLE AND NOT A LONG, WHICH IT WAS UNTIL #223 GAVE OUTPUTS A CHANCE. A recipe that
     * yields its product one run in ten produces 0.1 of it per run, and the whole correction
     * is that `runs` divides by that rather than by 1. Narrowing it here would floor the
     * divisor back to zero and the plan would be the pre-#223 plan again, silently.
     * `solve._build` writes `Recipe.expected_yield`, which is a float for the same reason.
     */
    Double perRun;
    /**
     * `perRun` over what the recipe NOMINALLY says it makes, so a renderer can say "this
     * works 10% of the time". #223.
     *
     * ABSENT WHEN THE TWO ARE EQUAL, which is every node of every plan built from a graph
     * older than schema 8, so the field's PRESENCE is the signal rather than its value.
     * Mirrors `solve._build`, which omits the key on the same condition.
     */
    Double yieldChance;
    /** How many recipes could have made this, so the UI can offer the choice. */
    Integer alternatives;
    /**
     * How many EQUALLY-SCORED recipes are the same offer as the one taken, when that is 3 or
     * more. #181: absent when the pick was not arbitrary, which is the overwhelming majority.
     *
     * NOT A SMALLER `alternatives`. That counts every real producer -- 65 for
     * `fluid:lifeessence` -- including three routes priced at infinity. This counts the ones
     * that tie AND are structurally interchangeable, which is 62, and it is the number that
     * makes "the plan named Blaze because Blaze sorts first" legible.
     */
    Integer interchangeable;
    /** Present and true only when the chosen recipe is one the player pinned. */
    Boolean pinned;

    String machine;
    String machineState;
    String machineWhy;

    /** `ore:` nodes only: which concrete member was chosen. */
    String resolvedTo;
    /** How many alternatives this input slot offered. Set by the PARENT, not by the node. */
    Integer altCount;
    /**
     * A run of the parent recipe never SPENDS this input, so owning one is the requirement.
     *
     * Set by the PARENT, like `altCount`, because consumption is a property of the SLOT rather
     * than of the item: the same item can be retained by one recipe and spent by another. #175.
     *
     * NOT CALLED `catalyst`, deliberately. This port already means the JEI machine BLOCK by
     * that word, in `RecipeGraph.catalysts`, `Machines`, `Evidence` and six other files, and one
     * word for two concepts would have a reader of a plan row conclude it names a machine.
     */
    Boolean notConsumed;
    /** A world ore whose dimension the player has never visited. */
    String dimension;
    /** A pack placeholder standing in for an instruction rather than an item. */
    String tokenKind;
    /**
     * Present and true only for a key that is an NBT state the graph cannot reach (#136).
     *
     * OPTIONAL, AND ITS ABSENCE MEANS FALSE. Never written as `false`, matching Python, so a
     * reader must not expect the key.
     */
    Boolean unsourced;
    /**
     * How the PACK says you get this key: `puzzle`, `loot_table` or `quest`. #171/#262.
     *
     * THE COMPLEMENT OF {@link #unsourced} AND NOT A SECOND FLAVOUR OF IT. The pack declares
     * how to get this, so the tool CAN say how and `unsourced` is deliberately absent on the
     * same node. THE TWO ARE MUTUALLY EXCLUSIVE BY CONSTRUCTION, because
     * `Unsourced.packAuthored` excludes everything `packAuthoredDeclared` holds; a reader
     * seeing both on one node is looking at a bug, not at a case to handle.
     *
     * OPTIONAL, AND ITS ABSENCE MEANS "THE PACK DECLARES NOTHING", which is almost every key.
     * Never written as an empty string, matching Python.
     */
    String provenance;

    /** Absent on a leaf; never an empty list, because Python never writes one. */
    List<PlanNode> children;

    PlanNode() {
    }

    // -- the read surface ----------------------------------------------------------------

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public String kind() {
        return kind;
    }

    public String label() {
        return label;
    }

    public long need() {
        return need;
    }

    public String status() {
        return status;
    }

    public String note() {
        return note;
    }

    public String recipe() {
        return recipe;
    }

    public String category() {
        return category;
    }

    public String machine() {
        return machine;
    }

    public String machineState() {
        return machineState;
    }

    public String machineWhy() {
        return machineWhy;
    }

    public String resolvedTo() {
        return resolvedTo;
    }

    public String dimension() {
        return dimension;
    }

    public String tokenKind() {
        return tokenKind;
    }

    /** How the pack says you get this, or null when it declares nothing. #171. */
    public String provenance() {
        return provenance;
    }

    /** 0 when nothing came from stock, which is what the absent key means. */
    public long fromStock() {
        return fromStock == null ? 0L : fromStock.longValue();
    }

    public long runs() {
        return runs == null ? 0L : runs.longValue();
    }

    /** 0.0 when the node names no recipe, which is what the absent key means. */
    public double perRun() {
        return perRun == null ? 0.0 : perRun.doubleValue();
    }

    /**
     * How often a run of this node's recipe actually yields the key, 0.0 to 1.0. #223.
     *
     * 1.0 WHEN THE FIELD IS ABSENT, because absent means certain. A caller must never read
     * this as 0.0-when-missing: that would render every ordinary craft node as a recipe that
     * never works.
     */
    public double yieldChance() {
        return yieldChance == null ? 1.0 : yieldChance.doubleValue();
    }

    public int alternatives() {
        return alternatives == null ? 0 : alternatives.intValue();
    }

    /** 0 when the pick was not arbitrary, so callers can treat absent and 1 alike. */
    public int interchangeable() {
        return interchangeable == null ? 0 : interchangeable.intValue();
    }

    public int altCount() {
        return altCount == null ? 0 : altCount.intValue();
    }

    public boolean pinned() {
        return pinned != null && pinned.booleanValue();
    }

    public boolean unsourced() {
        return unsourced != null && unsourced.booleanValue();
    }

    /** True when a run of the parent recipe does not spend this input (#175). */
    public boolean notConsumed() {
        return notConsumed != null && notConsumed.booleanValue();
    }

    /**
     * The children, EMPTY rather than null on a leaf.
     *
     * The field stays null so {@link PlanJson} can omit the key; this is what everything that
     * walks a tree should call. See the class javadoc for why a null here is worse than it
     * looks.
     */
    public List<PlanNode> children() {
        return children == null ? Collections.<PlanNode>emptyList() : children;
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    @Override
    public String toString() {
        return "PlanNode[" + key + " x" + need + " " + status + "]";
    }

    /** How many nodes this subtree holds, itself included. Mirrors `solve._count_nodes`. */
    int countNodes() {
        int total = 1;
        for (PlanNode child : children()) {
            total += child.countNodes();
        }
        return total;
    }

    /** How many cycle leaves this subtree bottomed out on. Mirrors `solve._count_cycles`. */
    int countCycles() {
        int total = PlanStatus.CYCLE.equals(status) ? 1 : 0;
        for (PlanNode child : children()) {
            total += child.countCycles();
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
        other.yieldChance = yieldChance;
        other.alternatives = alternatives;
        other.interchangeable = interchangeable;
        other.pinned = pinned;
        other.machine = machine;
        other.machineState = machineState;
        other.machineWhy = machineWhy;
        other.resolvedTo = resolvedTo;
        other.altCount = altCount;
        other.notConsumed = notConsumed;
        other.dimension = dimension;
        other.tokenKind = tokenKind;
        other.unsourced = unsourced;
        other.provenance = provenance;
        other.children = children;
        return other;
    }

    /**
     * Builds a node from outside this package: the fixture reader, and tests.
     *
     * PUBLIC WHERE THE FIELDS ARE NOT, so `client.planner.PlanJson` can turn a fixture into
     * the same class the solver produces without opening the fields to every widget.
     *
     * PASSING NULL IS HOW YOU SAY "THE KEY WAS ABSENT", and the reader depends on it: it must
     * not substitute a zero for a key the JSON did not carry, or a round trip through it would
     * start emitting fields Python omits and every golden fixture would disagree.
     */
    public static final class Builder {

        private final PlanNode node = new PlanNode();

        public Builder key(String value) {
            node.key = value;
            return this;
        }

        public Builder name(String value) {
            node.name = value;
            return this;
        }

        public Builder kind(String value) {
            node.kind = value;
            return this;
        }

        public Builder label(String value) {
            node.label = value;
            return this;
        }

        public Builder need(long value) {
            node.need = value;
            return this;
        }

        public Builder status(String value) {
            node.status = value;
            return this;
        }

        public Builder note(String value) {
            node.note = value;
            return this;
        }

        public Builder recipe(String value) {
            node.recipe = value;
            return this;
        }

        public Builder category(String value) {
            node.category = value;
            return this;
        }

        public Builder machine(String value) {
            node.machine = value;
            return this;
        }

        public Builder machineState(String value) {
            node.machineState = value;
            return this;
        }

        public Builder machineWhy(String value) {
            node.machineWhy = value;
            return this;
        }

        public Builder resolvedTo(String value) {
            node.resolvedTo = value;
            return this;
        }

        public Builder dimension(String value) {
            node.dimension = value;
            return this;
        }

        public Builder tokenKind(String value) {
            node.tokenKind = value;
            return this;
        }

        public Builder fromStock(Long value) {
            node.fromStock = value;
            return this;
        }

        public Builder runs(Long value) {
            node.runs = value;
            return this;
        }

        public Builder perRun(Double value) {
            node.perRun = value;
            return this;
        }

        public Builder yieldChance(Double value) {
            node.yieldChance = value;
            return this;
        }

        public Builder alternatives(Integer value) {
            node.alternatives = value;
            return this;
        }

        public Builder interchangeable(Integer value) {
            node.interchangeable = value;
            return this;
        }

        public Builder altCount(Integer value) {
            node.altCount = value;
            return this;
        }

        public Builder pinned(Boolean value) {
            node.pinned = value;
            return this;
        }

        public Builder notConsumed(Boolean value) {
            node.notConsumed = value;
            return this;
        }

        public Builder unsourced(Boolean value) {
            node.unsourced = value;
            return this;
        }

        public Builder provenance(String value) {
            node.provenance = value;
            return this;
        }

        /** Null or empty both mean "no `children` key", which is what a leaf emits. */
        public Builder children(List<PlanNode> value) {
            node.children = value == null || value.isEmpty() ? null : value;
            return this;
        }

        public PlanNode build() {
            return node;
        }
    }
}
