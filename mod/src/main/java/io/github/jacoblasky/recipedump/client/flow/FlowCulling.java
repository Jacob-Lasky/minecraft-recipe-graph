package io.github.jacoblasky.recipedump.client.flow;

import java.util.ArrayList;
import java.util.List;

import io.github.jacoblasky.recipedump.graph.IntArray;

/**
 * Which laid-out boxes a viewport can actually see.
 *
 * REQUIRED, NOT AN OPTIMISATION. `DEFAULT_MAX_NODES` is 4,000 and the 60 fps panning gate on
 * #19 phase 3b is measured on a plan that size. Drawing four thousand node widgets to find
 * out that forty of them are on screen misses the frame every frame, and it misses it in the
 * one interaction -- dragging -- where a dropped frame is most visible.
 *
 * A COLUMN INDEX WITH A BINARY SEARCH, NOT BETTERQUESTING'S SPATIAL HASH GRID, and the
 * difference is earned rather than a preference. `CanvasCullingManager` buckets panels into
 * fixed square cells and sweeps every cell each frame, which is right for BetterQuesting
 * because a quest line puts nodes at arbitrary author-chosen positions. It is the wrong shape
 * here, and the first version of this class proved it by measurement rather than argument: a
 * hash grid over a 4,000-node layout tested 4,250 CELLS against 4,000 boxes -- the sweep cost
 * as much as the thing it was replacing, because cell count grows with the CANVAS and this
 * canvas is enormous and mostly empty.
 *
 * {@link FlowLayout} is not arbitrary. Depth decides the column, so a box's x is a known
 * multiple and the visible column range is arithmetic; within a column the boxes are sorted
 * by y, so the visible run is a binary search and a walk. Per frame that is O(visible columns
 * x log n + visible boxes), with no dependency on how big the canvas is. `solve.py` caps
 * depth at 24, so "visible columns" is at most a couple of dozen whatever the plan.
 *
 * PURE GEOMETRY, like {@link FlowLayout}: no widgets, no GL, so the algorithmic property the
 * gate depends on is asserted in a plain JUnit test rather than eyeballed at 60 fps.
 */
public final class FlowCulling {

    private final List<FlowLayout.Box> boxes;
    /** Column index -> that column's box indices, ascending by y. */
    private final int[][] columns;
    /**
     * The visible set, reused between frames.
     *
     * `IntArray` AND NOT `List<Integer>`, in a class whose entire purpose is the frame
     * budget. A hundred visible boxes at sixty frames a second is six thousand boxed
     * Integers a second, all of them outside the -128..127 cache, allocated and collected
     * during exactly the interaction the gate measures. The list would have been more
     * idiomatic and it would have been garbage in the hot path.
     */
    private final IntArray shown = new IntArray();

    /** Boxes examined by the last sweep. The per-frame cost, in one number. */
    private int boxesExamined;
    /** Columns examined by the last sweep. */
    private int columnsExamined;
    /**
     * Bands examined by the trunk pass since the last {@link #visibleIn}.
     *
     * RESET BY `visibleIn` AND NOT BY THE TRUNK QUERY ITSELF, because a frame is one
     * `visibleIn` followed by one sweep and the number wanted is the frame's total. The trunk
     * pass asks per column gap, so a counter each query reset would report only the last gap.
     */
    private int bandsExamined;

    public FlowCulling(FlowLayout.Laid laid) {
        this.boxes = laid.boxes;
        int pitchX = FlowLayout.COLUMN_PITCH;

        int columnCount = 0;
        for (int i = 0; i < boxes.size(); i++) {
            FlowLayout.Box box = boxes.get(i);
            // THE BUCKETING IS ARITHMETIC, SO THE LAYOUT'S GRID IS A PRECONDITION. Everything
            // below -- the column range from x, the binary search within it -- assumes a box
            // sits exactly on the column pitch. A layout that offsets one column by a few
            // pixels would still place it, and this class would file it under a neighbouring
            // column and cull it from the viewport it is actually in. That failure is a node
            // that vanishes when you pan, which reads as a rendering bug. Fail here instead.
            if (box.x % pitchX != 0) {
                throw new IllegalArgumentException(
                        "box " + i + " is at x=" + box.x + ", which is not on the "
                                + pitchX + "px column pitch. FlowCulling buckets by "
                                + "arithmetic and cannot index an off-grid layout.");
            }
            columnCount = Math.max(columnCount, box.x / pitchX + 1);
        }

        int[] sizes = new int[columnCount];
        for (int i = 0; i < boxes.size(); i++) {
            sizes[boxes.get(i).x / pitchX]++;
        }
        this.columns = new int[columnCount][];
        for (int c = 0; c < columnCount; c++) {
            columns[c] = new int[sizes[c]];
        }
        int[] at = new int[columnCount];
        // ASCENDING BY Y BY CONSTRUCTION, not by a sort. `FlowLayout` emits boxes in
        // depth-first order and sibling bands do not overlap, so within one column y only
        // increases -- and the invariant check below turns that from an assumption into a
        // failure. A comparator over boxed Integers was the first version; it was correct and
        // it was a sort nobody needed.
        for (int i = 0; i < boxes.size(); i++) {
            int column = boxes.get(i).x / pitchX;
            int slot = at[column]++;
            if (slot > 0 && boxes.get(columns[column][slot - 1]).y > boxes.get(i).y) {
                throw new IllegalArgumentException(
                        "column " + column + " is not ascending in y at box " + i
                                + ". FlowCulling binary-searches each column and would "
                                + "return the wrong run.");
            }
            // AND THE SUBTREE BANDS ASCEND AND DO NOT OVERLAP, which is a SECOND precondition
            // and not a restatement of the one above. Rows ascending is what
            // `firstVisible` needs; `firstBandReaching` binary-searches the BANDS, and two
            // boxes at one depth can only have ordered rows because their bands are ordered.
            // `FlowLayout.Box.bandTop` records why that holds -- disjoint leaf ranges per
            // subtree -- so a layout that broke it would be a layout this class cannot index,
            // and the failure it would otherwise produce is a trunk that is silently not
            // drawn: the search would stop short and #334 would be back with no symptom but a
            // missing line.
            if (slot > 0
                    && boxes.get(columns[column][slot - 1]).bandBottom
                            >= boxes.get(i).bandTop) {
                throw new IllegalArgumentException(
                        "column " + column + " has overlapping subtree bands at box " + i
                                + ": the previous box's band ends at "
                                + boxes.get(columns[column][slot - 1]).bandBottom
                                + " and this one's starts at " + boxes.get(i).bandTop
                                + ". FlowCulling binary-searches the bands and would return "
                                + "the wrong run.");
            }
            columns[column][slot] = i;
        }
    }

    /**
     * Recompute what is on screen for a viewport in LAYOUT coordinates.
     *
     * The caller converts from screen space, because that is where the pan offset and the
     * zoom scale live and this class deliberately knows about neither.
     *
     * @return the visible box indices. The same list instance every call; do not retain it.
     */
    public IntArray visibleIn(int viewX, int viewY, int viewWidth, int viewHeight) {
        shown.clear();
        boxesExamined = 0;
        columnsExamined = 0;
        bandsExamined = 0;
        if (viewWidth <= 0 || viewHeight <= 0) {
            return shown;
        }

        int pitchX = FlowLayout.COLUMN_PITCH;
        // A column at index c spans x in [c*pitch, c*pitch + NODE_WIDTH). The first column
        // that can touch the viewport is therefore the smallest c with c*pitch + NODE_WIDTH
        // past viewX, which is floorDiv(viewX - NODE_WIDTH, pitch) + 1.
        //
        // THE +1 GOES OUTSIDE THE DIVISION. It was inside, as `viewX - NODE_WIDTH + 1`, which
        // starts the sweep one column early for every viewX not sitting in a column gap. That
        // is conservative rather than wrong -- the extra column's boxes all fail the x test
        // below and never reach `shown` -- so no output ever differs, and no amount of
        // comparing this against a reference implementation can see it. Found by mutating it
        // and watching the suite stay green.
        //
        // MEASURED BEFORE AND AFTER, because the obvious justification for touching this was
        // "it examines a whole extra column per sweep" and that turns out to overstate it.
        // Over a 7,381-node layout at 124,889 viewport positions, 612x372:
        //
        //             mean columns examined    mean boxes examined
        //   before            3.34                    11.80
        //   after             2.52                    11.43
        //
        // A quarter off the column count and three percent off the box count, because the
        // spurious column is the one to the LEFT of the viewport and few of its boxes fall in
        // the visible y-band. So this is not a frame-time win worth quoting. What it is worth
        // is that `columnsExamined()` -- the number the 60 fps claim rests on and the reason
        // the counter is public at all -- now reports the work the design implies instead of
        // one column more, and the bound in `aDeepPlanExaminesOneColumnPerVisibleDepthAndNoMore`
        // can be exact rather than slack enough to hide this.
        int firstColumn = Math.max(0, Math.floorDiv(viewX - FlowLayout.NODE_WIDTH, pitchX) + 1);
        int lastColumn = Math.min(columns.length - 1,
                Math.floorDiv(viewX + viewWidth - 1, pitchX));

        int viewBottom = viewY + viewHeight;
        for (int c = firstColumn; c <= lastColumn; c++) {
            int[] column = columns[c];
            if (column.length == 0) {
                continue;
            }
            columnsExamined++;
            // First box whose BOTTOM edge is below the viewport's top. Everything before it
            // is entirely above and can never be visible, which is what makes this a search
            // rather than a scan.
            int at = firstVisible(column, viewY);
            for (; at < column.length; at++) {
                int index = column[at];
                FlowLayout.Box box = boxes.get(index);
                if (box.y >= viewBottom) {
                    break;   // ascending in y, so everything after is below too
                }
                boxesExamined++;
                if (box.right() > viewX && box.x < viewX + viewWidth) {
                    shown.add(index);
                }
            }
        }
        return shown;
    }

    /**
     * The box containing a point in LAYOUT coordinates, or -1 for a gap or off the layout.
     *
     * Reuses the column index the culler already builds, so the hit-test and the cull answer
     * out of one structure. See {@link FlowCanvas#boxAt} for why this exists as a cross-check
     * on ModularUI's own hit-testing rather than as the click path.
     */
    public int boxAt(int x, int y) {
        if (x < 0 || y < 0) {
            return -1;
        }
        int column = x / FlowLayout.COLUMN_PITCH;
        if (column >= columns.length) {
            return -1;
        }
        if (x - column * FlowLayout.COLUMN_PITCH >= FlowLayout.NODE_WIDTH) {
            return -1;   // in the gap after that column, where the edges are drawn
        }
        int[] boxesInColumn = columns[column];
        // The first box not entirely above the point. Because the column is ascending in y and
        // boxes within one do not overlap, that is the only candidate there can be.
        int at = firstVisible(boxesInColumn, y);
        if (at >= boxesInColumn.length) {
            return -1;
        }
        int index = boxesInColumn[at];
        return boxes.get(index).y <= y ? index : -1;   // else the point is in the row gap
    }

    /**
     * The first box in `column` whose SUBTREE BAND reaches `top` or below it, as an offset into
     * that column. `columnLength(column)` when the whole column's work is above `top`.
     *
     * THE QUERY `FlowCanvas.sweepTrunks` NEEDS AND THE ONE {@link #visibleIn} CANNOT
     * ANSWER, and #334 is the difference. `visibleIn` answers "which boxes are in this
     * viewport"; a diagram's edges are elbows between a parent and a child that can be
     * thousands of pixels apart, so a viewport can be crossed by an edge while holding neither
     * of its boxes -- and a sweep that starts from visible boxes draws nothing there. A box's
     * band contains every one of its descendants' rows, so a parent whose band reaches into the
     * viewport is the only candidate an edge crossing it can belong to, and a parent whose band
     * does not is provably not one.
     *
     * O(log n) AND THE ANSWER IS O(1) LONG, which is what keeps the trunk pass off the O(plan)
     * shape this class exists to avoid. `FlowLayout.Box.bandTop` has the proof: bands at one
     * depth are disjoint and at least a row pitch apart, so a viewport of height H overlaps at
     * most `H / ROW_PITCH + 2` of them however large the plan is. The constructor ENFORCES the
     * disjointness rather than trusting it, because the failure mode of a mis-ordered column
     * here is a search that stops short and a trunk that is silently not drawn.
     *
     * ACCESSORS RATHER THAN A RETURNED LIST, and not merely to save an allocation on the 60 fps
     * path. {@link #shown} is the one reused buffer here and {@link #visibleIn} hands it out; a
     * second query returning that same instance would alias the visible set a caller is
     * mid-walk over, and the caller of this method is walking exactly that. A cursor cannot.
     */
    public int firstBandReaching(int column, int top) {
        int[] boxesInColumn = columns[column];
        int low = 0;
        int high = boxesInColumn.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (boxes.get(boxesInColumn[mid]).bandBottom < top) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        bandsExamined += boxesInColumn.length == 0 ? 0 : 1;
        return low;
    }

    /** How many boxes are in `column`. See {@link #firstBandReaching}. */
    public int columnLength(int column) {
        return columns[column].length;
    }

    /**
     * The box at offset `at` in `column`, ascending by band. See {@link #firstBandReaching}.
     *
     * COUNTED, because this is the trunk pass's per-frame cost and the same argument
     * {@link #boxesExamined} makes applies to it: "the trunk sweep is cheap" is a claim about a
     * bounded multiple of what is painted, and a counter is what lets a test say so instead of
     * a screenshot.
     */
    public int boxInColumn(int column, int at) {
        bandsExamined++;
        return columns[column][at];
    }

    /** Bands the trunk pass looked at since {@link #visibleIn} last reset the counters. */
    public int bandsExamined() {
        return bandsExamined;
    }

    /** Binary search for the first box in `column` whose bottom edge is below `top`. */
    private int firstVisible(int[] column, int top) {
        int low = 0;
        int high = column.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (boxes.get(column[mid]).bottom() <= top) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Boxes the last sweep looked at.
     *
     * The number the performance claim rests on, and the reason it is exposed: "panning is
     * smooth" depends on the host and the rasteriser, while "a frame examines a bounded
     * multiple of what is on screen" does not depend on either and is the property that
     * decides whether the frame is made.
     */
    public int boxesExamined() {
        return boxesExamined;
    }

    /** Columns the last sweep looked at. At most one per plan depth. */
    public int columnsExamined() {
        return columnsExamined;
    }

    /** How many columns the layout produced. */
    public int columnCount() {
        return columns.length;
    }
}
