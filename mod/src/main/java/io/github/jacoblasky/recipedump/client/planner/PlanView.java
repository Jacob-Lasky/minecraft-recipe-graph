package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A whole solved plan, as the panel needs to read it.
 *
 * The same decoupling as {@link PlanNode}: this is the frozen `result` object from
 * `tests/fixtures/plan/*.json`, which is what the Java solver will hand back when #141
 * lands. Nothing here computes a plan; it reads one.
 */
public final class PlanView {

    private final String target;
    private final String targetName;
    private final long qty;
    private final PlanNode tree;
    private final boolean truncated;
    private final boolean exhausted;
    private final int nodes;
    private final int maxNodes;
    private final List<ShoppingRow> shoppingList;
    private final List<MachineRow> machinesToBuild;
    private final List<String> pinsOverruled;

    PlanView(String target, String targetName, long qty, PlanNode tree, boolean truncated,
             boolean exhausted, int nodes, int maxNodes, List<ShoppingRow> shoppingList,
             List<MachineRow> machinesToBuild, List<String> pinsOverruled) {
        this.target = target;
        this.targetName = targetName;
        this.qty = qty;
        this.tree = tree;
        this.truncated = truncated;
        this.exhausted = exhausted;
        this.nodes = nodes;
        this.maxNodes = maxNodes;
        this.shoppingList = Collections.unmodifiableList(shoppingList);
        this.machinesToBuild = Collections.unmodifiableList(machinesToBuild);
        this.pinsOverruled = Collections.unmodifiableList(pinsOverruled);
    }

    /**
     * A plan with nothing in it, for the panels that can be shown before one is solved.
     *
     * NOT NULL, which is the point. The TODO panel is real and usable today; handing it a
     * null plan would put a null check in every accessor for a case that is normal rather
     * than exceptional.
     */
    public static PlanView empty() {
        PlanNode root = new PlanNode.Builder()
                .key("")
                .label("no plan yet")
                .name("no plan yet")
                .kind("item")
                .status(NodeStatus.CRAFT)
                .build();
        return new PlanView("", "no plan yet", 0L, root, false, false, 1, 0,
                            java.util.Collections.<ShoppingRow>emptyList(),
                            java.util.Collections.<MachineRow>emptyList(),
                            java.util.Collections.<String>emptyList());
    }

    public String target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    public long qty() {
        return qty;
    }

    public PlanNode tree() {
        return tree;
    }

    /**
     * True when the node budget cut the tree short.
     *
     * THE PANEL MUST SAY SO. A truncated plan looks exactly like a complete one -- the
     * branches that were cut are simply not there -- so a reader who is not told will act on
     * a shopping list that is missing items. The web UI prints it and so does this.
     */
    public boolean truncated() {
        return truncated;
    }

    /** True when the search ran out of work budget rather than out of nodes. */
    public boolean exhausted() {
        return exhausted;
    }

    public int nodes() {
        return nodes;
    }

    public int maxNodes() {
        return maxNodes;
    }

    /** What you still have to obtain. */
    public List<ShoppingRow> shoppingList() {
        return shoppingList;
    }

    /** Machines the plan routes through that the player does not have yet. */
    public List<MachineRow> machinesToBuild() {
        return machinesToBuild;
    }

    /**
     * One sentence per recipe choice the solver could not honour, sorted.
     *
     * THE PANEL MUST SAY THESE, and until this field existed it silently did not. A pin the
     * cycle guard overrules -- `9 nuggets -> 1 ingot`, whose nuggets come from ingots -- is
     * the case where the picker's click appears to have worked and did not, and the plan
     * comes back using the route the player just rejected. `Solver.noteOverruledPin`'s own
     * comment says the plan says it; `render.py` puts it in the warnbar; the in-game panel
     * read past it. Found by a screenshot: pinning "Iron Ingot from Iron Nugget" against the
     * reference pack produced a byte-identical picture to not pinning anything at all.
     *
     * SORTED, matching `render.py` and `cmd_plan`, so the same plan reads the same way
     * wherever it is shown -- a map's iteration order is not a thing to expose to a reader.
     */
    public List<String> pinsOverruled() {
        return pinsOverruled;
    }

    /** Every node of the tree, parents before children. */
    public List<PlanNode> flatten() {
        List<PlanNode> all = new ArrayList<PlanNode>();
        collect(tree, all);
        return all;
    }

    private static void collect(PlanNode node, List<PlanNode> into) {
        into.add(node);
        for (PlanNode child : node.children()) {
            collect(child, into);
        }
    }

    /** One "you still need" row. */
    public static final class ShoppingRow {
        private final String key;
        private final String label;
        private final long need;

        ShoppingRow(String key, String label, long need) {
            this.key = key;
            this.label = label;
            this.need = need;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public long need() {
            return need;
        }
    }

    /** One machine the plan needs that is not simply available. */
    public static final class MachineRow {
        private final String category;
        private final String machine;
        private final String state;
        private final String why;

        MachineRow(String category, String machine, String state, String why) {
            this.category = category;
            this.machine = machine;
            this.state = state;
            this.why = why;
        }

        public String category() {
            return category;
        }

        public String machine() {
            return machine;
        }

        public String state() {
            return state;
        }

        public String why() {
            return why;
        }

        /** What the badge says, worded by {@link NodeStatus#machineStateLabel}. */
        public String stateLabel() {
            return NodeStatus.machineStateLabel(state);
        }
    }
}
