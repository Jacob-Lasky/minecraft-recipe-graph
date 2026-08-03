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
        // DERIVED AND TIGHT, not a number that looked about right.
        //
        // THE PREVIOUS BOUND WAS `viewWidth / PITCH + 2` AND IT WAS SLACK BY ONE, which is
        // how an off-by-one in `firstColumn` sat here unnoticed: the sweep started a column
        // early, and the bound had room for it. A loose bound in a performance assertion is
        // not a safe default, it is the assertion declining to assert.
        int bound = (viewWidth + FlowLayout.NODE_WIDTH - 1) / FlowLayout.COLUMN_PITCH + 1;
        // AND THE BOUND ITSELF IS CHECKED, rather than asserted in a comment saying I once
        // worked it out. If the formula is generous the test below still passes while having
        // stopped measuring anything, which is exactly the failure that let the off-by-one
        // live here -- so pin it against an exhaustive count over the same layout.
        assertEquals("the bound must be the real maximum, not a comfortable one",
                widestOverlap(laid, viewWidth), bound);
        // SWEPT, NOT TAKEN AT THE ORIGIN. `firstColumn` is clamped at 0, so viewX = 0 is
        // precisely the offset at which starting a column early costs nothing and the
        // assertion cannot fail. The single case the old version tested was the one case
        // immune to the bug it was there to catch.
        for (int viewX = -viewWidth; viewX < laid.width + viewWidth; viewX += 13) {
            culling.visibleIn(viewX, 0, viewWidth, 200);
            assertTrue("at viewX " + viewX + " examined " + culling.columnsExamined()
                            + " of " + culling.columnCount() + " columns, bound " + bound,
                    culling.columnsExamined() <= bound);
        }
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
        // ANCHORED ON A BOX RATHER THAN ON (0,0). The origin is not a place a node reliably
        // is: a parent is centred on its subtree's band, so the root of a 40-leaf fan sits
        // 500px down, and a viewport at the origin narrower than the column pitch sees
        // nothing at all. The first version of this test hardcoded 200x100 and passed only
        // because the node happened to be 96px wide; widening it to the size a real row needs
        // made the same assertion fail on unchanged, correct code.
        FlowLayout.Box root = laid.boxes.get(0);
        assertTrue(culling.visibleIn(root.x, root.y, 200, 100).size() > 0);
        assertEquals("panned far off the layout, nothing is visible",
                0, culling.visibleIn(1_000_000, 1_000_000, 200, 100).size());
        assertTrue("panning back must restore the visible set",
                culling.visibleIn(root.x, root.y, 200, 100).size() > 0);
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

    // -- agreement with a brute-force oracle ------------------------------------------------
    //
    // THE CASES BELOW ARE GENERATED, NOT CHOSEN, AND THAT IS THE POINT. The obvious way to
    // test a viewport query is a handful of offsets that look interesting, and it does not
    // work: the interesting offsets are the ones I thought of while writing the index, so they
    // exercise the branches I remembered and say nothing about the branch I forgot. `fixtures`
    // hit exactly this on 2026-08-02 -- a comparison test passed for the whole period the two
    // things it compared disagreed, because all five of its cases went down the one branch
    // nobody had touched.
    //
    // The off-by-one in `firstColumn` this change fixes is the same shape one level up. It
    // survived because the only assertion that could have caught it was taken at viewX = 0,
    // where `Math.max(0, ...)` clamps the error away.
    //
    // So this sweeps every offset over a fixed layout and compares against a linear scan. The
    // scan is not a second implementation to be kept in step; it is the definition of the
    // answer, four comparisons long, and it is obviously right in a way the index is not.

    @Test
    public void theVisibleSetAgreesWithABruteForceScanAtEveryScrollOffset() {
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.fan(30));
        FlowCulling culling = new FlowCulling(laid);
        int viewWidth = 400;
        int viewHeight = 200;
        // STARTS NEGATIVE AND RUNS PAST THE END. A viewport partly off the layout is the
        // normal state at both extremes of a pan, and it is the case a chosen-offset test
        // omits, because nobody writes down "scrolled to -37" as an interesting number.
        for (int x = -viewWidth; x < laid.width + viewWidth; x += 7) {
            for (int y = -viewHeight; y < laid.height + viewHeight; y += 5) {
                Set<Integer> expected = scanForVisible(laid, x, y, viewWidth, viewHeight);
                Set<Integer> actual = toSet(culling.visibleIn(x, y, viewWidth, viewHeight));
                assertEquals("visibleIn(" + x + "," + y + ")", expected, actual);
            }
        }
    }

    // -- helpers ---------------------------------------------------------------------------

    /**
     * The most columns a `viewWidth`-wide viewport can overlap, counted rather than derived.
     *
     * Swept one pixel at a time from before the layout to past its end, because the worst
     * offset is a boundary and a coarser step would report a smaller maximum and make a slack
     * bound look exact.
     */
    private static int widestOverlap(FlowLayout.Laid laid, int viewWidth) {
        int columns = laid.width / FlowLayout.COLUMN_PITCH + 1;
        int worst = 0;
        for (int viewX = -viewWidth; viewX < laid.width + viewWidth; viewX++) {
            int touched = 0;
            for (int c = 0; c < columns; c++) {
                int left = c * FlowLayout.COLUMN_PITCH;
                if (left + FlowLayout.NODE_WIDTH > viewX && left < viewX + viewWidth) {
                    touched++;
                }
            }
            worst = Math.max(worst, touched);
        }
        return worst;
    }

    /** The definition of visible: the box's rectangle overlaps the viewport's. */
    private static Set<Integer> scanForVisible(FlowLayout.Laid laid, int viewX, int viewY,
                                               int viewWidth, int viewHeight) {
        Set<Integer> found = new HashSet<Integer>();
        for (int i = 0; i < laid.size(); i++) {
            FlowLayout.Box box = laid.boxes.get(i);
            if (box.right() > viewX && box.x < viewX + viewWidth
                    && box.bottom() > viewY && box.y < viewY + viewHeight) {
                found.add(Integer.valueOf(i));
            }
        }
        return found;
    }

    private static Set<Integer> toSet(IntArray values) {
        Set<Integer> found = new HashSet<Integer>();
        for (int i = 0; i < values.size(); i++) {
            found.add(Integer.valueOf(values.get(i)));
        }
        return found;
    }


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
