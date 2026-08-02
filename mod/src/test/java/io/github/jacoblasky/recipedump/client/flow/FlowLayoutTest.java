package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * The flow diagram's geometry, with no window and no GL.
 *
 * The whole point of keeping {@link FlowLayout} free of ModularUI is that its behaviour can be
 * asserted here in milliseconds instead of eyeballed at 60 fps through a screenshot. A change
 * to node placement that needs a running client to check is a change in the wrong file.
 */
public class FlowLayoutTest {

    @Test
    public void depthBecomesTheColumn() {
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.chain(4));
        for (int i = 0; i < laid.size(); i++) {
            FlowLayout.Box box = laid.boxes.get(i);
            assertEquals("depth " + box.depth + " is in the wrong column",
                    box.depth * (FlowLayout.NODE_WIDTH + FlowLayout.COLUMN_GAP), box.x);
        }
    }

    @Test
    public void siblingsKeepTheOrderTheTreeGivesThem() {
        // A diagram that silently reverses siblings against the tree view beside it is a
        // diagram nobody trusts. The DFS pushes children in reverse so the stack pops them
        // left to right, and this is what pins that.
        PlanNode root = PlanTrees.node("root", PlanTrees.node("a"), PlanTrees.node("b"), PlanTrees.node("c"));
        FlowLayout.Laid laid = FlowLayout.of(root);
        assertEquals(Arrays.asList("root", "a", "b", "c"), keysOf(laid));
        assertTrue(rowOf(laid, "a") < rowOf(laid, "b"));
        assertTrue(rowOf(laid, "b") < rowOf(laid, "c"));
    }

    @Test
    public void noTwoLeavesShareARow() {
        PlanNode root = PlanTrees.node("root",
                PlanTrees.node("left", PlanTrees.node("l1"), PlanTrees.node("l2")),
                PlanTrees.node("right", PlanTrees.node("r1"), PlanTrees.node("r2"), PlanTrees.node("r3")));
        FlowLayout.Laid laid = FlowLayout.of(root);
        Set<Integer> rows = new HashSet<Integer>();
        for (FlowLayout.Box box : laid.boxes) {
            if (!box.node.hasChildren()) {
                assertTrue("two leaves overlap at y=" + box.y, rows.add(box.y));
            }
        }
        assertEquals(5, rows.size());
    }

    @Test
    public void aParentSitsOnTheCentreOfItsOwnSubtreeBandNotBetweenItsChildren() {
        // THE CASE THE TWO RULES DISAGREE ON. `left` is a single leaf; `right` is three
        // leaves. Centring `root` between its two DIRECT children puts it midway between
        // `left`'s row and `right`'s row -- which is fine here only because `right` is itself
        // centred. The rule that actually matters is that `root` lands inside the band its
        // whole subtree spans, and dead centre of it.
        PlanNode root = PlanTrees.node("root",
                PlanTrees.node("left"),
                PlanTrees.node("right", PlanTrees.node("r1"), PlanTrees.node("r2"), PlanTrees.node("r3")));
        FlowLayout.Laid laid = FlowLayout.of(root);

        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (FlowLayout.Box box : laid.boxes) {
            if (!box.node.hasChildren()) {
                top = Math.min(top, box.y);
                bottom = Math.max(bottom, box.y);
            }
        }
        assertEquals("root is off the centre of its own band", (top + bottom) / 2,
                rowOf(laid, "root"));
    }

    @Test
    public void aSubtreeDoesNotCollideWithItsSibling() {
        // The bug the naive "first child + n" indexing produced: a DFS list puts a whole
        // subtree contiguously, so the second direct child is NOT the next entry, and reading
        // it that way banded the parent across the wrong rows. Two deep siblings is the
        // shape that catches it.
        PlanNode root = PlanTrees.node("root",
                PlanTrees.node("a", PlanTrees.node("a1", PlanTrees.node("a1x"))),
                PlanTrees.node("b", PlanTrees.node("b1", PlanTrees.node("b1x"))));
        FlowLayout.Laid laid = FlowLayout.of(root);
        assertTrue("the two branches overlap",
                rowOf(laid, "a1x") != rowOf(laid, "b1x"));
        assertTrue(rowOf(laid, "a") < rowOf(laid, "b"));
    }

    @Test
    public void aFourThousandNodePlanLaysOutWithoutRecursingIntoTheStack() {
        // `DEFAULT_MAX_NODES` is 4,000 and `plan-truncated` exists because a plan can be
        // pathological. A StackOverflowError inside a render pass takes the client down
        // rather than drawing a bad diagram, which is why the walk is iterative.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.chain(4000));
        assertEquals(4000, laid.size());
        assertEquals(3999 * (FlowLayout.NODE_WIDTH + FlowLayout.COLUMN_GAP)
                + FlowLayout.NODE_WIDTH, laid.width);
    }

    @Test
    public void aWideFanLaysOutWithoutCollapsing() {
        List<PlanNode> children = new ArrayList<PlanNode>();
        for (int i = 0; i < 2000; i++) {
            children.add(PlanTrees.node("leaf" + i));
        }
        PlanNode root = PlanTrees.node("root", children.toArray(new PlanNode[0]));
        FlowLayout.Laid laid = FlowLayout.of(root);
        assertEquals(2001, laid.size());
        assertEquals(1999 * (FlowLayout.NODE_HEIGHT + FlowLayout.ROW_GAP)
                + FlowLayout.NODE_HEIGHT, laid.height);
    }

    @Test
    public void theSameKeyTwiceIsTwoBoxes() {
        // A plan is a TREE, not a DAG: the solver expands each occurrence separately because
        // inventory is drawn down as it goes, so two branches asking for iron are two
        // different amounts of iron. Deduplicating here would answer a different question.
        PlanNode root = PlanTrees.node("root", PlanTrees.node("iron"), PlanTrees.node("iron"));
        assertEquals(3, FlowLayout.of(root).size());
    }

    @Test
    public void anEmptyPlanIsEmptyRatherThanAThrow() {
        FlowLayout.Laid laid = FlowLayout.of((PlanNode) null);
        assertEquals(0, laid.size());
        assertEquals(0, laid.width);
        assertEquals(0, laid.height);
    }

    @Test
    public void everyBoxKnowsItsParentSoEdgesCanBeDrawn() {
        PlanNode root = PlanTrees.node("root", PlanTrees.node("mid", PlanTrees.node("leaf")));
        FlowLayout.Laid laid = FlowLayout.of(root);
        assertEquals(-1, laid.boxes.get(0).parent);
        assertEquals(0, laid.boxes.get(1).parent);
        assertEquals(1, laid.boxes.get(2).parent);
    }

    // -- helpers ---------------------------------------------------------------------------

    private static List<String> keysOf(FlowLayout.Laid laid) {
        List<String> keys = new ArrayList<String>();
        for (FlowLayout.Box box : laid.boxes) {
            keys.add(box.node.key());
        }
        return keys;
    }

    private static int rowOf(FlowLayout.Laid laid, String key) {
        for (FlowLayout.Box box : laid.boxes) {
            if (key.equals(box.node.key())) {
                return box.y;
            }
        }
        throw new AssertionError("no box for " + key);
    }
}
