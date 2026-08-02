package io.github.jacoblasky.recipedump.client.flow;

import java.util.ArrayList;
import java.util.List;

import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.PlanResult;

/**
 * A plan tree laid out left to right: depth becomes a column, siblings become rows.
 *
 * PURE GEOMETRY. Nothing here touches ModularUI, Minecraft or GL, so the whole layout is
 * asserted in a plain JUnit test with no window -- which is the only fast feedback loop this
 * diagram has, and the same argument #125 made for the widget layout pass. If you find
 * yourself needing a `GuiScreen` to test a change to this file, the change is in the wrong
 * file.
 *
 * WHY A TIDY-STYLE SECOND PASS RATHER THAN "STACK THE LEAVES AND CENTRE THE PARENTS". The
 * naive version is one line shorter and puts a parent at the midpoint of its children, which
 * is the same thing right up until a subtree is taller than its sibling -- then the parent
 * drifts off the visual centre of the branch it heads and the diagram reads as though the
 * wrong node produced it. Reserving a row band per subtree and centring within the BAND keeps
 * a parent on its own branch at any shape.
 *
 * THE TREE CAN CONTAIN THE SAME KEY TWICE and that is not a bug to deduplicate here. A plan
 * is a tree, not a DAG: `solve.py` expands each occurrence separately because inventory is
 * drawn down as it goes, so two branches asking for iron are two different amounts of iron.
 * Merging them would be a different diagram answering a different question.
 */
public final class FlowLayout {

    /**
     * Node box size, in GUI pixels.
     *
     * 209 IS NOT A ROUND NUMBER, IT IS A SUM: `ICON + GAP + QTY + GAP + MIN_LABEL + GAP +
     * BADGE` from `PlannerWidgets` -- 10 + 3 + 52 + 3 + 48 + 3 + 90 -- the narrowest node that
     * carries an icon, a quantity, at least eight characters of name and a full badge. Below
     * it the row drops the badge rather than truncating it, which is right for a diagram and
     * wrong for a default.
     *
     * IT WAS 214 FOR ONE COMMIT, from a hand-added figure quoted in a message, and the pin
     * below caught it the first time it ran. That is the whole argument for the pin: five
     * pixels is invisible in a screenshot and the number looks equally plausible either way.
     *
     * WRITTEN OUT RATHER THAN IMPORTED, because this class is pure geometry and reaching for
     * `PlannerWidgets` would drag ModularUI into a file whose whole value is being testable
     * without it. `FlowCanvasTest.theLayoutsNodeWidthIsTheSumTheRowActuallyNeeds` pins the two
     * together, so the badge vocabulary growing fails a test rather than silently truncating.
     */
    public static final int NODE_WIDTH = 209;
    public static final int NODE_HEIGHT = 20;
    /** Gap between a column and the next. The edge lines are drawn across this. */
    public static final int COLUMN_GAP = 40;
    /** Gap between two stacked nodes. */
    public static final int ROW_GAP = 6;

    private static final int COLUMN_PITCH = NODE_WIDTH + COLUMN_GAP;
    private static final int ROW_PITCH = NODE_HEIGHT + ROW_GAP;

    private FlowLayout() {
    }

    /** One laid-out node: the plan node it draws, and where it goes. */
    public static final class Box {
        public final PlanNode node;
        /** Index into {@link Laid#boxes}, or -1 for the root. Edges are drawn from this. */
        public final int parent;
        public final int depth;
        public final int x;
        public final int y;

        Box(PlanNode node, int parent, int depth, int x, int y) {
            this.node = node;
            this.parent = parent;
            this.depth = depth;
            this.x = x;
            this.y = y;
        }

        public int right() {
            return x + NODE_WIDTH;
        }

        public int bottom() {
            return y + NODE_HEIGHT;
        }
    }

    /**
     * A box at an arbitrary position, for tests that need a layout this class would not
     * produce -- specifically an off-grid one, to prove {@link FlowCulling} rejects it.
     * Nothing in production should build a box by hand.
     */
    static Box boxAt(PlanNode node, int parent, int depth, int x, int y) {
        return new Box(node, parent, depth, x, y);
    }

    /** A laid-out plan: every box, and the extent they occupy. */
    public static final class Laid {
        public final List<Box> boxes;
        public final int width;
        public final int height;

        public Laid(List<Box> boxes, int width, int height) {
            this.boxes = boxes;
            this.width = width;
            this.height = height;
        }

        public int size() {
            return boxes.size();
        }
    }

    public static Laid of(PlanResult plan) {
        return of(plan == null ? null : plan.tree);
    }

    /**
     * Lay out the tree rooted at `root`.
     *
     * ITERATIVE, NOT RECURSIVE, and that is a requirement rather than a preference.
     * `DEFAULT_MAX_NODES` is 4,000 and `max_depth` is 24, so a recursive walk is fine on the
     * shapes we have today -- but `plan-truncated` exists precisely because a plan can be
     * pathological, and a StackOverflowError inside a render pass takes the client down
     * rather than drawing a bad diagram. An explicit stack costs a dozen lines.
     */
    public static Laid of(PlanNode root) {
        List<Box> boxes = new ArrayList<Box>();
        if (root == null) {
            return new Laid(boxes, 0, 0);
        }

        // Pass one: every node in depth-first order, with its parent and depth recorded.
        List<PlanNode> nodes = new ArrayList<PlanNode>();
        List<Integer> parents = new ArrayList<Integer>();
        List<Integer> depths = new ArrayList<Integer>();
        walk(root, nodes, parents, depths);

        // Direct children per node, derived from `parents` rather than recorded during the
        // walk. A DFS list puts a whole SUBTREE contiguously, so a node's second direct child
        // is NOT at `firstChild + 1` -- the first child's own descendants sit between them.
        // Deriving the lists is O(n) and cannot get that wrong; arithmetic on offsets can,
        // and did.
        List<List<Integer>> childrenOf = new ArrayList<List<Integer>>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            childrenOf.add(new ArrayList<Integer>(2));
        }
        for (int i = 0; i < nodes.size(); i++) {
            int parent = parents.get(i);
            if (parent >= 0) {
                childrenOf.get(parent).add(i);
            }
        }

        // Pass two: a row per LEAF in depth-first order, so the diagram reads top to bottom
        // in the same order as the tree view beside it.
        //
        // IN PIXELS, NOT IN ROW INDICES. A parent is centred on the band its subtree spans,
        // and a band of an even number of rows has no centre ROW -- integer division there
        // silently biases every such parent half a row upward, which on a deep tree
        // accumulates into edges that visibly miss the box they point at. The pixel midpoint
        // has no such gap.
        int[] y = new int[nodes.size()];
        int[] bandTop = new int[nodes.size()];
        int[] bandBottom = new int[nodes.size()];
        int nextLeafRow = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (childrenOf.get(i).isEmpty()) {
                y[i] = nextLeafRow * ROW_PITCH;
                bandTop[i] = y[i];
                bandBottom[i] = y[i];
                nextLeafRow++;
            }
        }
        // Descending, because every child of `i` has an index GREATER than `i` in a
        // depth-first list -- so one backward sweep sees every child before its parent and
        // needs no recursion and no second traversal.
        for (int i = nodes.size() - 1; i >= 0; i--) {
            List<Integer> children = childrenOf.get(i);
            if (children.isEmpty()) {
                continue;
            }
            bandTop[i] = bandTop[children.get(0)];
            bandBottom[i] = bandBottom[children.get(children.size() - 1)];
            // Centred on the BAND its whole subtree spans, not on the midpoint of its direct
            // children. The two agree until one subtree is taller than its sibling, and then
            // the parent drifts off the visual centre of the branch it heads.
            y[i] = (bandTop[i] + bandBottom[i]) / 2;
        }

        int maxY = 0;
        int maxDepth = 0;
        for (int i = 0; i < nodes.size(); i++) {
            boxes.add(new Box(nodes.get(i), parents.get(i), depths.get(i),
                    depths.get(i) * COLUMN_PITCH, y[i]));
            maxY = Math.max(maxY, y[i]);
            maxDepth = Math.max(maxDepth, depths.get(i));
        }
        return new Laid(boxes, maxDepth * COLUMN_PITCH + NODE_WIDTH, maxY + NODE_HEIGHT);
    }

    /** Depth-first flatten. See {@link #of} for why the ordering matters to pass two. */
    private static void walk(PlanNode root, List<PlanNode> nodes, List<Integer> parents,
                             List<Integer> depths) {
        List<PlanNode> pending = new ArrayList<PlanNode>();
        List<Integer> pendingParent = new ArrayList<Integer>();
        List<Integer> pendingDepth = new ArrayList<Integer>();
        pending.add(root);
        pendingParent.add(-1);
        pendingDepth.add(0);

        while (!pending.isEmpty()) {
            int at = pending.size() - 1;
            PlanNode node = pending.remove(at);
            int parent = pendingParent.remove(at);
            int depth = pendingDepth.remove(at);

            int index = nodes.size();
            nodes.add(node);
            parents.add(parent);
            depths.add(depth);
            List<PlanNode> children = node.children();

            // Pushed in REVERSE so the stack pops them left to right, which keeps the
            // diagram's row order the same as the tree's child order. A diagram that
            // silently reverses siblings against the list beside it is a diagram nobody
            // trusts.
            for (int c = children.size() - 1; c >= 0; c--) {
                pending.add(children.get(c));
                pendingParent.add(index);
                pendingDepth.add(depth + 1);
            }
        }
    }
}
