package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.jacoblasky.recipedump.HeadlessLayout;
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
    public void theLayoutsNodeWidthIsTheSumTheRowActuallyNeeds() {
        // THE ONE COUPLING THE SPLIT CREATES. `FlowLayout` writes 214 out rather than
        // importing `PlannerWidgets`, because reaching for it would drag ModularUI into a
        // file whose whole value is being testable without it -- so the number can drift.
        //
        // It drifts SILENTLY and in the worse direction: `BADGE` is derived from the longest
        // word the status vocabulary can produce, so adding a status widens it, and a node
        // left at the old width starts dropping the badge instead of following. This fails
        // instead.
        assertEquals("FlowLayout.NODE_WIDTH must equal ICON + GAP + QTY + GAP + MIN_LABEL "
                        + "+ GAP + BADGE",
                FlowCanvas.NARROWEST_NODE, FlowLayout.NODE_WIDTH);
    }

    @Test
    public void aNodeIsWideEnoughForAFullBadgeAndAnEightCharacterName() {
        // The property the sum exists for, asserted against the row's own rule rather than
        // against the number: below this width `planNodeContent` drops the badge entirely.
        int labelRoom = FlowLayout.NODE_WIDTH - PlannerWidgets.ICON - PlannerWidgets.QTY
                - PlannerWidgets.GAP * 3 - PlannerWidgets.BADGE;
        assertTrue("a node has " + labelRoom + "px for a name, needs "
                        + PlannerWidgets.MIN_LABEL,
                labelRoom >= PlannerWidgets.MIN_LABEL);
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
    }

    @Test
    public void panningWrapsRatherThanParkingAtTheEdge() {
        // The timing harness drives a long diagonal pan; clamping would park it against the
        // corner after a second and time a stationary canvas while reporting it as panning.
        FlowCanvas canvas = new FlowCanvas(PlanTrees.fan(200));
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(PlannerWidgets.flowPanel(canvas));
        canvas.panTo(1_000_000, 1_000_000);
        assertTrue("a wrapped pan must stay inside the layout",
                canvas.getScrollArea().getScrollY().getScroll() < FlowLayout.of(
                        PlanTrees.fan(200)).height);
    }
}
