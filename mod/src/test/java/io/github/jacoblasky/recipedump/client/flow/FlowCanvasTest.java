package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * The canvas, as far as it can be asserted with no window.
 *
 * MOST OF THIS CLASS IS NOT TESTABLE HERE AND THAT IS THE POINT OF THE SPLIT: the geometry
 * lives in {@link FlowLayout} and the visibility in {@link FlowCulling}, both pure and both
 * covered by their own files. What is left is the widget wiring, and the parts of it worth
 * pinning are the ones a screenshot would not notice going wrong.
 */
public class FlowCanvasTest {

    @Test
    public void theLayoutsNodeWidthClearsWhatTheNodeActuallyNeeds() {
        // THE ONE COUPLING THE SPLIT CREATES. `FlowLayout` writes 209 out rather than
        // importing `PlannerWidgets`, because reaching for it would drag ModularUI into a
        // file whose whole value is being testable without it -- so the number can drift.
        //
        // It drifts SILENTLY and in the worse direction: `BADGE` is derived from the longest
        // word the status vocabulary can produce, so adding a status widens it, and a node
        // left at the old width starts dropping the badge instead of following. This fails
        // instead.
        //
        // `>=` AND NOT `==`, SINCE THE LABEL MOVED TO ITS OWN LINE. It was an equality while
        // the label shared a line with the badge, which made the minimum and the sensible
        // width the same number -- and made the label the loser, at eight characters. The
        // surplus over the minimum is now the label's, so the two are allowed to differ; the
        // direction that goes wrong quietly is still pinned, and
        // `aNodeHasRoomForAWholeItemName` pins what the surplus is spent on.
        assertTrue("FlowLayout.NODE_WIDTH is " + FlowLayout.NODE_WIDTH
                        + " and a node needs at least " + FlowCanvas.NARROWEST_NODE,
                FlowLayout.NODE_WIDTH >= FlowCanvas.NARROWEST_NODE);
    }

    @Test
    public void aNodeHasRoomForAWholeItemName() {
        // THE GAP THIS WHOLE SHAPE EXISTS TO CLOSE. On one line the label got
        // `NODE_WIDTH - ICON - QTY - 3*GAP - BADGE` = 48px = eight characters, so the first
        // screenshots of this canvas read "Iron ..." and "Block..." -- a dependency diagram
        // whose boxes did not say what they were. On its own line the label gets the node
        // minus the icon column.
        int labelRoom = FlowLayout.NODE_WIDTH - PlannerWidgets.NODE_ICON - PlannerWidgets.GAP;
        int characters = labelRoom / NodeRowText.CHAR_WIDTH;
        // 24 is "Sodium Fluoride Solution", the longest name in `plan-fluid-chain`'s upper
        // chain and a name the old node cut to "Sodium…". Asserted as a character count
        // rather than as a pixel width because that is the unit the failure was measured in.
        assertTrue("a node has " + characters + " characters for a name, which is not a name",
                characters >= 24);
        assertTrue("and it must beat the old one-line budget, or this changed nothing",
                labelRoom > PlannerWidgets.MIN_LABEL);
    }

    @Test
    public void aNodeIsTallEnoughForItsTwoLinesAndDoesNotSitOnTheFrame() {
        // 20 fitted both lines EXACTLY, and the box is a nine-slice whose border occupies the
        // outer pixel, so the top line was drawn over the frame. Two pixels of slack is one
        // above and one below the pair.
        assertTrue("NODE_HEIGHT " + FlowLayout.NODE_HEIGHT + " must hold two "
                        + PlannerWidgets.LINE + "px lines with a pixel either side",
                FlowLayout.NODE_HEIGHT >= PlannerWidgets.LINE * 2 + 2);
    }

    /**
     * The zoom at which the diagram stops drawing names, and that the range still has both
     * sides of it.
     *
     * A THRESHOLD INSIDE THE RANGE IS THE WHOLE CLAIM. A `LABEL_LEGIBLE` at or below
     * `FlowZoom.MIN` would make the level-of-detail dead code -- every reachable zoom would
     * draw labels -- and a value at or above `MAX` would mean the diagram never drew one. Both
     * read as a working feature and neither can be seen in a screenshot of a single zoom.
     */
    @Test
    public void labelsGoAwayBelowTheLegibleZoomAndComeBackAboveIt() {
        assertTrue("the threshold must be inside the zoom range to do anything",
                FlowZoom.LABEL_LEGIBLE > FlowZoom.MIN && FlowZoom.LABEL_LEGIBLE <= FlowZoom.MAX);

        FlowCanvas canvas = new FlowCanvas(PlanTrees.fan(20));
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(PlannerWidgets.flowPanel(canvas));

        canvas.setZoom(FlowZoom.MIN);
        assertFalse("at the floor a 6px font is three physical pixels; MIN's own comment says "
                        + "the reader is going by colour and quantity", canvas.showsLabels());
        canvas.setZoom(FlowZoom.LABEL_LEGIBLE);
        assertTrue("the threshold itself draws labels", canvas.showsLabels());
        canvas.setZoom(FlowZoom.DEFAULT);
        assertTrue(canvas.showsLabels());
        canvas.setZoom(FlowZoom.MAX);
        assertTrue(canvas.showsLabels());
    }

    @Test
    public void everyBoxBecomesAChildSoNothingIsLaidOutLate() {
        // The culling toggles `enabled`; it must NOT change the child set. A node that never
        // became a child would never be resized, and would arrive at zero size the first time
        // it was scrolled into view -- invisible until someone pans, which is the failure the
        // enabled-toggle design exists to avoid.
        FlowCanvas canvas = new FlowCanvas(PlanTrees.fan(50));
        assertEquals(51, canvas.nodeCount());
        assertEquals(51, canvas.getChildren().size());
    }

    @Test
    public void theCanvasLaysOutInsideItsPanelWithNoWindow() {
        FlowCanvas canvas = new FlowCanvas(PlanTrees.fan(20));
        canvas.pos(4, 4).size(300, 180);
        HeadlessLayout.layOut(PlannerWidgets.flowPanel(canvas));
        assertEquals(300, canvas.getArea().width);
        assertEquals(180, canvas.getArea().height);
    }

    @Test
    public void theScrollExtentIsTheWholeLayoutSoEveryNodeCanBeReached() {
        // A scroll size left at the viewport's own size is the shape that makes a diagram
        // look complete and quietly unreachable past the first screen.
        PlanNode tree = PlanTrees.fan(200);
        FlowLayout.Laid laid = FlowLayout.of(tree);
        FlowCanvas canvas = new FlowCanvas(tree);
        assertEquals(laid.width, canvas.getScrollArea().getScrollX().getScrollSize());
        assertEquals(laid.height, canvas.getScrollArea().getScrollY().getScrollSize());

        // AND AT A ZOOM THAT IS NOT 1, because at the default the two are the same number:
        // `applyScrollSize` sets `scaledExtent(laid.width, zoom)`, so the assertions above
        // hold just as well for a canvas that dropped the scaling entirely, which is the bug
        // `FlowCanvas.applyScrollSize`'s own comment says lets the view run off the end of a
        // zoomed diagram by a factor of the zoom.
        canvas.setZoom(2.0f);
        assertEquals(2.0f, canvas.zoom(), 0.0f);
        assertEquals(FlowZoom.scaledExtent(laid.width, 2.0f),
                canvas.getScrollArea().getScrollX().getScrollSize());
        assertEquals(FlowZoom.scaledExtent(laid.height, 2.0f),
                canvas.getScrollArea().getScrollY().getScrollSize());
        assertTrue("a zoomed extent must actually differ, or this adds nothing",
                FlowZoom.scaledExtent(laid.height, 2.0f) > laid.height);
    }

    @Test
    public void panningWrapsRatherThanParkingAtTheEdge() {
        // The timing harness drives a long diagonal pan; clamping would park it against the
        // corner after a second and time a stationary canvas while reporting it as panning.
        //
        // ASSERTED AS THE MODULUS, NOT AS "SMALLER THAN THE LAYOUT". The old assertion was
        // `getScroll() < laid.height`, which a CLAMPING `panTo` satisfies just as well --
        // clamping parks the offset at `extent - viewport`, comfortably under the layout
        // height -- so the exact defect this test names passed it. Landing on a specific
        // remainder is a property only wrapping has.
        //
        // IT DOES NOT GUARD `scaledExtent`. `range` below is computed with the same function
        // `panTo` wraps against, so a wrong scale moves both and this still passes. That is
        // deliberate: the extent arithmetic is `FlowZoomTest`'s job and it sweeps the zoom
        // range, while the only property asserted here is wrap-versus-clamp.
        PlanNode tree = PlanTrees.fan(200);
        FlowCanvas canvas = new FlowCanvas(tree);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(PlannerWidgets.flowPanel(canvas));

        FlowLayout.Laid laid = FlowLayout.of(tree);
        int range = FlowZoom.scaledExtent(laid.height, canvas.zoom()) - canvas.getArea().height;
        assertTrue("the fixture must be tall enough to have somewhere to wrap to", range > 37);

        canvas.panTo(0, range + 37);
        assertEquals("one range past the end must come back to 37, not park at the end",
                37, canvas.getScrollArea().getScrollY().getScroll());

        // AND THAT THE TWO DIFFER, which is the harness's actual complaint: a clamped pan
        // returns the same offset for every value past the end, so the timed canvas is
        // stationary while the run reports it as panning.
        canvas.panTo(0, 1_000_000);
        int first = canvas.getScrollArea().getScrollY().getScroll();
        canvas.panTo(0, 1_000_037);
        assertTrue("two pans past the end that differ by 37 must land in different places",
                first != canvas.getScrollArea().getScrollY().getScroll());
    }
}
