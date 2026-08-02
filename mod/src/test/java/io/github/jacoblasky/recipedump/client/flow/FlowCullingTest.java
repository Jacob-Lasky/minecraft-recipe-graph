package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.jacoblasky.recipedump.graph.IntArray;

import org.junit.Test;


/**
 * The culler, which is the reason the 60 fps gate is reachable at all.
 *
 * THE PROPERTY UNDER TEST IS ALGORITHMIC, NOT VISUAL, and that distinction is what makes it
 * assertable here rather than only under the screenshot harness. "Panning is smooth" depends
 * on the host, the driver and the rasteriser; "a frame examines a bounded multiple of what is
 * on screen" depends on none of them, and it is the half of the 60 fps gate that a wall-clock
 * measurement on a software rasteriser cannot honestly settle.
 */
public class FlowCullingTest {

    @Test
    public void aFrameExaminesWhatIsOnScreenRatherThanTheWholePlan() {
        // THE WHOLE CLAIM, and the reason this class is not BetterQuesting's spatial hash
        // grid. The first version of it WAS one, and this assertion is what rejected it: over
        // a 4,000-node layout the grid tested 4,250 cells against 4,000 boxes, because cell
        // count grows with the canvas and this canvas is enormous and mostly empty. A column
        // index has no such term.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(4000));
        FlowCulling culling = new FlowCulling(laid);
        culling.visibleIn(0, 0, 400, 200);
        assertTrue("examined " + culling.boxesExamined() + " of " + laid.size(),
                culling.boxesExamined() * 20 < laid.size());
    }

    @Test
    public void aDeepPlanExaminesOneColumnPerVisibleDepthAndNoMore() {
        // The other axis. `solve.py` caps depth at 24, so a real plan has at most a couple of
        // dozen columns however many nodes it holds -- but the sweep must be bounded by the
        // VISIBLE column range rather than by the layout's, or a wide plan pays for columns
        // that are off screen.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.chain(400));
        FlowCulling culling = new FlowCulling(laid);
        int viewWidth = 400;
        culling.visibleIn(0, 0, viewWidth, 200);
        // DERIVED, not a number that looked about right: a viewport `viewWidth` wide spans
        // at most that many column pitches, plus the two it can partially overlap at each
        // edge. A magic constant here would silently absorb a regression that doubled the
        // range.
        int bound = viewWidth / (FlowLayout.NODE_WIDTH + FlowLayout.COLUMN_GAP) + 2;
        assertTrue("examined " + culling.columnsExamined() + " of " + culling.columnCount()
                + " columns, bound " + bound, culling.columnsExamined() <= bound);
    }

    @Test
    public void anOffGridLayoutIsRejectedRatherThanSilentlyMisfiled() {
        // The bucketing is arithmetic, so the column pitch is a PRECONDITION. A box nudged
        // off the grid would still be placed by the layout and would be filed under a
        // neighbouring column here, then culled from the viewport it is actually in -- a node
        // that vanishes when you pan, which reads as a rendering bug rather than as a broken
        // invariant.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(3));
        List<FlowLayout.Box> nudged = new ArrayList<FlowLayout.Box>(laid.boxes);
        FlowLayout.Box first = nudged.get(1);
        nudged.set(1, offGrid(first));
        try {
            new FlowCulling(new FlowLayout.Laid(nudged, laid.width, laid.height));
            org.junit.Assert.fail("an off-grid box must be rejected at construction");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("column pitch"));
        }
    }

    @Test
    public void onlyWhatTheViewportCoversIsReturned() {
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(200));
        FlowCulling culling = new FlowCulling(laid);
        IntArray shown = culling.visibleIn(0, 0, 400, 200);
        assertTrue("a 400x200 window should not show all 201 boxes: " + shown.size(),
                shown.size() < laid.size() / 2);
        assertTrue("and it should show something", shown.size() > 0);
    }

    @Test
    public void everyBoxIsFoundFromAViewportClippedToItsOwnBounds() {
        // The edge cases a search over a sorted run gets wrong are the first and last
        // element of a column and a viewport exactly touching a boundary. Asserting it for
        // EVERY box rather than a sampled one costs nothing here and catches an
        // off-by-one that a spot check would walk past.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.deepFan(6, 3));
        FlowCulling culling = new FlowCulling(laid);
        for (int i = 0; i < laid.size(); i++) {
            FlowLayout.Box box = laid.boxes.get(i);
            IntArray shown = culling.visibleIn(box.x, box.y,
                    FlowLayout.NODE_WIDTH, FlowLayout.NODE_HEIGHT);
            assertTrue("box " + i + " was culled from its own bounds",
                    contains(shown, i));
        }
    }

    @Test
    public void panningAwayHidesAndPanningBackShows() {
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(40));
        FlowCulling culling = new FlowCulling(laid);
        assertTrue(culling.visibleIn(0, 0, 200, 100).size() > 0);
        assertEquals("panned far off the layout, nothing is visible",
                0, culling.visibleIn(1_000_000, 1_000_000, 200, 100).size());
        assertTrue("panning back must restore the visible set",
                culling.visibleIn(0, 0, 200, 100).size() > 0);
    }

    @Test
    public void theVisibleSetNeverRepeatsABox() {
        // A box appears in exactly one column, so this cannot fail today -- it is a
        // regression guard on that invariant rather than on a live bug. Drawing a node twice
        // shows up only as a slightly bolder border, and as wasted frame budget at exactly
        // the node count where the budget is tight, so it is worth pinning cheaply.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(300));
        FlowCulling culling = new FlowCulling(laid);
        // A viewport the size of the whole layout, taken from the layout rather than typed
        // in: a 4000x4000 window sounds like "everything" and is less than a 300-leaf fan is
        // tall, so the first version of this test asserted 301 against a correct 159.
        IntArray shown = culling.visibleIn(0, 0, laid.width, laid.height);
        Set<Integer> unique = new HashSet<Integer>();
        for (int i = 0; i < shown.size(); i++) {
            unique.add(Integer.valueOf(shown.get(i)));
        }
        assertEquals("a box was returned twice", shown.size(), unique.size());
        assertEquals(laid.size(), unique.size());
    }

    @Test
    public void aViewportCoveringEverythingShowsEverything() {
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(120));
        FlowCulling culling = new FlowCulling(laid);
        assertEquals(laid.size(),
                culling.visibleIn(0, 0, laid.width, laid.height).size());
    }

    // -- helpers ---------------------------------------------------------------------------

    /** The same box, moved one pixel off the column pitch. */
    private static FlowLayout.Box offGrid(FlowLayout.Box box) {
        return FlowLayout.boxAt(box.node, box.parent, box.depth, box.x + 1, box.y);
    }

    private static boolean contains(IntArray values, int wanted) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == wanted) {
                return true;
            }
        }
        return false;
    }

}
