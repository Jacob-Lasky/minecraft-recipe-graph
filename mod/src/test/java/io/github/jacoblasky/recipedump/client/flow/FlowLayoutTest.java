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
        // OVER `deepFan`, AND AGAINST AN INDEPENDENT DEPTH. Two things were wrong with
        // asserting `box.x == box.depth * PITCH` over `chain(4)`. The expected value is
        // derived from the object under test, so a walk that filed the wrong `depth` files
        // the matching `x` and agrees with itself; and on a chain `depth` equals the DFS
        // index anyway, so the one fixture that could have told the two apart did not.
        //
        // `deepFan(3, 2)` diverges: DFS order is n2, n1, leaf, leaf, leaf, n1, ... so index
        // 2 is at depth 2 and index 5 is back at depth 1. The expected depths are written
        // out as literals read off `PlanTrees.deepFan`'s own recursion rather than recomputed
        // from the layout, for the reason `ModularUiLayoutTest`'s header gives: a test that
        // rederives a number with the formula the code uses passes whenever both are wrong.
        int[] expected = {0, 1, 2, 2, 2, 1, 2, 2, 2, 1, 2, 2, 2};
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.deepFan(3, 2));
        assertEquals(expected.length, laid.size());
        for (int i = 0; i < laid.size(); i++) {
            FlowLayout.Box box = laid.boxes.get(i);
            assertEquals("box " + i + " is at the wrong depth", expected[i], box.depth);
            assertEquals("depth " + box.depth + " is in the wrong column",
                    expected[i] * (FlowLayout.NODE_WIDTH + FlowLayout.COLUMN_GAP), box.x);
        }
        // The chain kept as well, because it is the shape where depth and index coincide and
        // a layout that started indexing columns instead of reading `depth` still has to
        // agree on it.
        FlowLayout.Laid chain = FlowLayout.of(PlanTrees.chain(4));
        for (int i = 0; i < chain.size(); i++) {
            FlowLayout.Box box = chain.boxes.get(i);
            assertEquals("chain box " + i + " is at the wrong depth", i, box.depth);
            assertEquals("depth " + box.depth + " is in the wrong column",
                    i * (FlowLayout.NODE_WIDTH + FlowLayout.COLUMN_GAP), box.x);
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
        // `PlanTrees.fan`, not a hand-rolled loop building the identical shape. The helper
        // exists for exactly this and the copy here was a second thing to fix the day
        // `PlanNode` gains a required field -- which is the reason `PlanTrees` was extracted.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(2000));
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
        // A BRANCHING TREE, NOT A CHAIN. The fixture here was `root -> mid -> leaf`, on which
        // the correct parent of box `i` is always `i - 1` -- so `parent = index - 1`, the
        // obvious wrong implementation and the one that draws every edge to the box above,
        // passed it. The same two deep siblings `aSubtreeDoesNotCollideWithItsSibling` uses
        // are the discriminating shape: DFS order is root, a, a1, b, b1, whose parents are
        // -1, 0, 1, 0, 3 -- and box 3 (`b`) hangs off the ROOT rather than off box 2.
        PlanNode root = PlanTrees.node("root",
                PlanTrees.node("a", PlanTrees.node("a1")),
                PlanTrees.node("b", PlanTrees.node("b1")));
        FlowLayout.Laid laid = FlowLayout.of(root);
        assertEquals(Arrays.asList("root", "a", "a1", "b", "b1"), keysOf(laid));
        assertEquals(-1, laid.boxes.get(0).parent);
        assertEquals(0, laid.boxes.get(1).parent);
        assertEquals(1, laid.boxes.get(2).parent);
        assertEquals("`b` hangs off the root, not off `a1` above it", 0,
                laid.boxes.get(3).parent);
        assertEquals(3, laid.boxes.get(4).parent);
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
