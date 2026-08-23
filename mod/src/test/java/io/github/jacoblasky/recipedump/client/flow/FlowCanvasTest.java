package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cleanroommc.modularui.screen.ModularPanel;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
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
        int labelRoom = FlowLayout.NODE_WIDTH - PlannerWidgets.NODE_PAD * 2
                - PlannerWidgets.NODE_ICON - PlannerWidgets.GAP;
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
    public void aNodeIsTallEnoughForItsTwoLines() {
        // 20 fitted both lines EXACTLY, and the box is a nine-slice whose bevel occupies the
        // outer two or three pixels, so `icons-flow.png` had every item name resting on the
        // bottom border. `NODE_PAD` above and below is what clears it.
        assertTrue("NODE_HEIGHT " + FlowLayout.NODE_HEIGHT + " must hold two "
                        + PlannerWidgets.LINE + "px lines inside NODE_PAD, which is "
                        + FlowCanvas.SHORTEST_NODE,
                FlowLayout.NODE_HEIGHT >= FlowCanvas.SHORTEST_NODE);
        assertTrue("and the pad must be real, or this asserts nothing about the frame",
                PlannerWidgets.NODE_PAD > 0);
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

        FlowCanvas canvas = inert(PlanTrees.fan(20));
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));

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

    /**
     * `emptyPointOnScreen` honours the caller's drag budget. The sweep used to start at (2,2),
     * which after `panToBox(rootBox())` is the layout's guaranteed-empty top-left corner -- so
     * `flow-nav`'s 60px up-left drag aimed the cursor OFF-SCREEN, the OS clamped it, and the
     * measured pan was the distance to the edge rather than the drag. The inset is that budget:
     * every point returned can move `inset` in ANY direction and stay inside the canvas. The
     * method had no coverage of any kind before this test.
     */
    @Test
    public void anEmptyPointHonoursTheCallersDragBudget() {
        FlowCanvas canvas = inert(PlanTrees.fan(20));
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));

        int inset = 60;
        int[] p = canvas.emptyPointOnScreen(inset);
        assertTrue("a 300x180 canvas with 40px column gaps must hold an empty point 60px "
                   + "inside its edges", p != null);
        assertTrue("the point must be at least the inset from EVERY edge, or a drag of that "
                   + "size aims the cursor off-screen: " + p[0] + "," + p[1],
                   p[0] >= inset && p[1] >= inset
                   && p[0] < canvas.getArea().width - inset
                   && p[1] < canvas.getArea().height - inset);
        assertTrue("and it must actually be EMPTY, or the press drives a node menu instead of "
                   + "the pan", canvas.boxAt(canvas.toLayoutX(p[0]), canvas.toLayoutY(p[1])) < 0);

        assertNull("an inset the canvas cannot satisfy answers null -- a distinguishable "
                   + "outcome, not a guess", canvas.emptyPointOnScreen(200));
    }

    @Test
    public void everyBoxBecomesAChildSoNothingIsLaidOutLate() {
        // The culling toggles `enabled`; it must NOT change the child set. A node that never
        // became a child would never be resized, and would arrive at zero size the first time
        // it was scrolled into view -- invisible until someone pans, which is the failure the
        // enabled-toggle design exists to avoid.
        FlowCanvas canvas = inert(PlanTrees.fan(50));
        assertEquals(51, canvas.nodeCount());
        assertEquals(51, canvas.getChildren().size());
    }

    @Test
    public void theCanvasLaysOutInsideItsPanelWithNoWindow() {
        FlowCanvas canvas = inert(PlanTrees.fan(20));
        canvas.pos(4, 4).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));
        assertEquals(300, canvas.getArea().width);
        assertEquals(180, canvas.getArea().height);
    }

    /**
     * THE SECOND HALF OF #323: a canvas the production opener can hand over already sized.
     *
     * WHAT A ZERO HERE MEANS, which is why this asserts a number rather than "not zero". A widget
     * added to a ModularUI panel with no size is not stretched to fit it -- it arrives at nothing,
     * and `WidgetTree.resizeInternal` catches Throwable and only logs it, so the panel opens,
     * lays out and draws an empty grey rectangle. Every one of the three callers that existed
     * before #323 was
     * in `shot/` and carried its own copy of the two numbers, so the FIRST player-reachable
     * opener would have had nothing to read them off -- and getting it wrong produces exactly the
     * blank picture that five flow screenshots turned out to be across two PRs.
     *
     * ASSERTED AFTER A REAL LAYOUT PASS AND NOT ON THE CONSTRUCTED VALUES, because that is the
     * question: `HeadlessLayout.layOut` runs ModularUI's own sizer over the panel, so a canvas
     * that asked for 612x372 and was then given something else by the sizer fails here.
     *
     * AND AGAINST THE PANEL'S OWN CONSTANTS, not against 612 and 372 written out again. A literal
     * here would pass while disagreeing with the panel, which is the drift the constants were
     * introduced to end.
     *
     * SWEPT ACROSS WARNING COUNTS SINCE #327, and that is the half a single fixture cannot reach.
     * The chrome above the diagram is one line per warning, so the boundary between chrome and
     * canvas MOVES with the plan -- and a canvas sized from a constant while the chrome grew would
     * be drawn over its own heading, silently, because ModularUI neither clamps nor clips a child
     * (#125). Zero and three warnings must both come out flush inside the panel.
     */
    @Test
    public void forPanelSizesTheCanvasToWhatTheChromeLeavesAtEveryWarningCount() {
        int[] warningCounts = {0, 1, PlannerWidgets.MAX_WARNINGS};
        java.util.Set<Integer> heights = new java.util.HashSet<Integer>();
        for (int warnings : warningCounts) {
            PlanView plan = planWithWarnings(PlanTrees.fan(20), warnings);
            assertEquals("the fixture must really carry the warnings this case is about",
                         warnings, PlannerWidgets.warningCount(plan));
            FlowCanvas canvas = FlowCanvas.forPanel(plan, PlannerActions.NONE);
            HeadlessLayout.layOut(
                    PlannerWidgets.flowPanel(plan, canvas, PlannerActions.NONE));

            assertEquals(PlannerWidgets.FLOW_CANVAS_WIDTH, canvas.getArea().width);
            assertEquals(PlannerWidgets.flowCanvasHeight(plan), canvas.getArea().height);
            // THE INSET IS ON BOTH SIDES, which is what makes the canvas clear the nine-slice's
            // frame. A canvas at 0,0 the full inner width would be the same size and drawn over
            // the border -- the failure `PlannerWidgets.NODE_PAD` records one level down.
            assertEquals("the canvas must clear the panel's own frame",
                         PlannerWidgets.FLOW_WIDTH,
                         canvas.getArea().width + PlannerWidgets.FLOW_PAD * 2);
            // AND IT MUST END WHERE THE PANEL DOES, less the inset. This is the assertion that
            // catches a chrome band and a canvas height that disagree: either overlap or a gap
            // leaves this number wrong, and neither is visible in a layout dump.
            assertEquals("canvas top + height + inset must be the panel height",
                         PlannerWidgets.FLOW_HEIGHT,
                         PlannerWidgets.flowCanvasTop(plan) + canvas.getArea().height
                                 + PlannerWidgets.FLOW_PAD);
            assertTrue("a warned plan still has to leave a diagram worth looking at",
                       canvas.getArea().height > PlannerWidgets.FLOW_HEIGHT / 2);
            heights.add(canvas.getArea().height);
        }
        assertEquals("the three cases must produce three different canvas heights, or the sweep "
                     + "is measuring one arrangement three times", 3, heights.size());
    }

    /**
     * A plan carrying exactly `warnings` warning lines, so the chrome's height can be varied.
     *
     * PINS OVERRULED RATHER THAN TRUNCATION, because truncation is ONE line and this needs to
     * reach `MAX_WARNINGS`. `PlannerWidgets.warnings` rolls the overflow up, so asking for more
     * than the cap would silently give the cap back -- which is why the caller asserts the count
     * it got rather than trusting this.
     */
    private static PlanView planWithWarnings(PlanNode tree, int warnings) {
        java.util.List<String> pins = new java.util.ArrayList<String>();
        for (int i = 0; i < warnings; i++) {
            pins.add("every recipe you pinned for Thing " + i + " loops back");
        }
        return new PlanView.Builder().targetName("Thing").qty(1L).tree(tree)
                .nodes(21).maxNodes(21).pinsOverruled(pins).build();
    }

    @Test
    public void theScrollExtentIsTheWholeLayoutSoEveryNodeCanBeReached() {
        // A scroll size left at the viewport's own size is the shape that makes a diagram
        // look complete and quietly unreachable past the first screen.
        PlanNode tree = PlanTrees.fan(200);
        FlowLayout.Laid laid = FlowLayout.of(tree);
        FlowCanvas canvas = inert(tree);
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
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));

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

    /**
     * Centring on a box puts that box on screen, at a depth the canvas found rather than
     * guessed (#293).
     *
     * WHAT THIS DELIBERATELY DOES NOT ASSERT: that the corner the timing sweep stops in is
     * empty. That is a fact about `PlannerShot`'s sweep against `PlannerShot.synthetic`, not
     * about this class, and asserting it here would need a tree shaped like the harness's --
     * which is how the first version of this test got it wrong. It used `fan`, whose leaf
     * column is column ONE and therefore sits inside the viewport at scroll (0, 0), so the
     * corner it claimed was empty held 400 nodes. The claim moved to `PlannerShotTest`, where
     * it can be made against the real generator; what is left here is the mechanism.
     *
     * A DEEP FAN AND NOT A WIDE ONE, so `rootBox` has more than one depth to be wrong about.
     */
    @Test
    public void centringOnABoxPutsItOnScreenAndTheRootIsFoundNotAssumed() {
        PlanNode tree = PlanTrees.deepFan(3, 7);
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));

        FlowLayout.Laid laid = FlowLayout.of(tree);
        assertTrue("the fixture must be taller than the viewport or a centred box would be on "
                + "screen whatever this method did", laid.height > canvas.getArea().height * 4);

        // THE INDEX IS SEARCHED, AND WHAT IS ASSERTED IS THE DEPTH. `rootBox` returning 0
        // today is not the property worth pinning; that whatever it returns is the shallowest
        // box is, because that is what survives the layout walking in another order.
        int root = canvas.rootBox();
        assertEquals("rootBox must return a box at depth 0", 0, laid.boxes.get(root).depth);

        // THE NEGATIVE FIRST, because "a box is visible after centring on it" is a claim only
        // a viewport that was NOT already showing it can make. Park somewhere the root is not,
        // confirm it is not, and only then centre. Without this the assertion below passes on
        // a `panToBox` that does nothing at all.
        canvas.panToFraction(1, 1);
        assertFalse("the far corner must not already be showing the root, or the pan below "
                + "proves nothing", visible(canvas, laid).contains(root));

        canvas.panToBox(root);
        assertTrue("centring on a box must put that box on screen",
                visible(canvas, laid).contains(root));

        // WHOLLY, NOT PARTLY, AND THIS IS THE ASSERTION THAT WOULD HAVE CAUGHT THE FIRST
        // VERSION. `panToBox` originally went through `panTo`, which WRAPS: centring on the
        // root asks for a negative offset, because the root is at x=0 and half a viewport to
        // its left is off the layout, and `Math.abs(-202 % range)` is +202. The box was then
        // clipped to a sliver at the left edge -- still "visible" to the culler, which is why
        // the line above passed, and still an unreadable photograph. Partial visibility is the
        // exact state both the bug and the fix produce, so only the stronger question separates
        // them.
        assertTrue("a centred box must be WHOLLY in frame; a clipped sliver satisfies the "
                + "culler and photographs as nothing", canvas.fullyShowing(root));
    }

    /**
     * Aiming at a box near an edge clamps, and does not wrap to the far side (#293).
     *
     * SEPARATE FROM THE TEST ABOVE BECAUSE IT PINS THE MECHANISM RATHER THAN THE OUTCOME. The
     * root is the case that bit, but any box within half a viewport of an edge asks for an
     * out-of-range offset, and `panTo`'s wrap answers with a position on the OPPOSITE side --
     * the furthest possible point from what was asked for. A future edit that routes this back
     * through `panTo` to save the four lines fails here with the direction named, rather than
     * failing somewhere downstream as a screenshot nobody opens.
     */
    @Test
    public void aimingAtABoxNearAnEdgeClampsRatherThanWrappingToTheFarSide() {
        PlanNode tree = PlanTrees.deepFan(3, 7);
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));
        FlowLayout.Laid laid = FlowLayout.of(tree);

        int root = canvas.rootBox();
        assertEquals("the fixture's root must sit at x=0 or this is not the edge case",
                0, laid.boxes.get(root).x);

        canvas.panToBox(root);
        assertEquals("an offset left of the layout must clamp to the start, not wrap past it",
                0, canvas.getScrollArea().getScrollX().getScroll());

        // AND THE OTHER EDGE, so the clamp is shown to hold at both ends rather than just
        // happening to return 0 for everything.
        int deepest = 0;
        for (int i = 1; i < laid.size(); i++) {
            if (laid.boxes.get(i).x > laid.boxes.get(deepest).x) {
                deepest = i;
            }
        }
        canvas.panToBox(deepest);
        int range = FlowZoom.scaledExtent(laid.width, canvas.zoom()) - canvas.getArea().width;
        assertTrue("the far edge must clamp inside the range, not past it",
                canvas.getScrollArea().getScrollX().getScroll() <= range);
        assertTrue("and a box at the far edge must still be reachable",
                canvas.fullyShowing(deepest));
    }


    /**
     * EVERY DIAGRAM NODE OPENS THE NODE MENU FOR ITS OWN NODE. The witness for #325.
     *
     * IT ASKS THE CANVAS WHAT IT BUILT, which is the whole repair. `PlannerLayoutTest`'s
     * `aDiagramNodeOpensTheNodeMenuLikeATreeRowDoes` called `planNodeContent(node, 214, 26,
     * recorder)` itself and clicked the result -- so it asserted that the FACTORY wires a node,
     * which was true, and said nothing about what `FlowCanvas` passed it. `FlowCanvas` passed
     * `PlannerActions.NONE`, and that test was green for the whole three releases the diagram was
     * inert. It is the #191 family exactly: the test performed the wiring production omitted.
     *
     * EVERY NODE AND NOT THE FIRST, and the difference is the defect one step over. A canvas that
     * wired only the root -- or wired every node to the ROOT's menu -- passes a one-node assertion.
     * `PlanTrees.fan` gives distinct keys per box, so the recorded key has to be the clicked box's
     * key for all of them, which is the property `LivePlannerActions.open`'s stale-panel bug (#315)
     * is about on the live side.
     *
     * THROUGH THE CANVAS'S OWN CHILD LIST rather than through a click at a coordinate, because
     * only a real cursor can prove hit-testing through the viewport transform and that is
     * `flow-hit`'s job. What is provable here is that the widget the canvas built is an
     * `Interactable` wired to the actions the canvas was handed -- the part that was absent.
     */
    @Test
    public void everyDiagramNodeOpensTheMenuForTheNodeThatWasClicked() {
        PlanNode tree = PlanTrees.fan(6);
        Recorder recorder = new Recorder();
        FlowCanvas canvas = new FlowCanvas(tree, recorder);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(
                PlannerWidgets.flowPanel(PlanView.empty(), canvas, PlannerActions.NONE));

        java.util.List<PlannerWidgets.NodeContent> nodes =
                new java.util.ArrayList<PlannerWidgets.NodeContent>();
        for (com.cleanroommc.modularui.api.widget.IWidget child : canvas.getChildren()) {
            if (child instanceof PlannerWidgets.NodeContent) {
                nodes.add((PlannerWidgets.NodeContent) child);
            }
        }
        assertEquals("every box must be a clickable node widget", canvas.nodeCount(),
                     nodes.size());
        assertTrue("the fixture must have several boxes, or 'every node' says nothing",
                   nodes.size() > 3);

        // THE KEYS ARE READ OFF THE LAYOUT AND NOT OFF THE WIDGETS, so the expected value comes
        // from a different object than the one being clicked. Asking the widget which node it is
        // and then asserting it opened that node's menu is a tautology.
        FlowLayout.Laid laid = FlowLayout.of(tree);
        java.util.List<String> expected = new java.util.ArrayList<String>();
        for (int i = 0; i < laid.size(); i++) {
            expected.add("menu:" + laid.boxes.get(i).node.key());
        }
        assertEquals("the fixture must give every box a distinct key, or a canvas that wired "
                     + "them all to one node would pass",
                     expected.size(), new java.util.HashSet<String>(expected).size());

        for (PlannerWidgets.NodeContent node : nodes) {
            node.onMousePressed(0);
        }
        assertEquals(expected, recorder.calls);
    }

    /**
     * Records which node's menu was asked for, in order. See {@link PlannerActions}.
     *
     * ONLY `openNodeMenu` IS RECORDED because it is the only entry a node click reaches; the rest
     * throw rather than returning quietly, so a canvas that started calling something else here
     * fails loudly instead of recording nothing and passing.
     */
    private static final class Recorder implements PlannerActions {

        private final java.util.List<String> calls = new java.util.ArrayList<String>();

        @Override
        public io.github.jacoblasky.recipedump.client.planner.NodeActions nodeActions() {
            return io.github.jacoblasky.recipedump.client.planner.NodeActions.NONE;
        }

        @Override
        public void openNodeMenu(PlanNode node) {
            calls.add("menu:" + node.key());
        }

        @Override
        public void selectNode(PlanNode node) {
            calls.add("select:" + node.key());
        }

        @Override
        public void openRecipePicker(PlanNode node) {
            throw new AssertionError("a node click must not open the picker directly");
        }

        @Override
        public void openFlow(PlanView plan) {
            throw new AssertionError("a diagram node must not open another diagram");
        }

        @Override
        public void openTodo(PlanView plan,
                             io.github.jacoblasky.recipedump.common.PlanBook book) {
            throw new AssertionError("a node click must not open the TODO panel");
        }

        @Override
        public void openCaveats() {
            throw new AssertionError("a node click must not open the caveats panel");
        }

        @Override
        public void pinRecipe(PlanNode node,
                              io.github.jacoblasky.recipedump.client.planner.RecipeChoice choice) {
            throw new AssertionError("a node click must not pin anything");
        }

        @Override
        public void addToTodo(PlanNode node) {
            throw new AssertionError("a node click must not add to the TODO list");
        }

        @Override
        public void toggleFavourite(PlanNode node) {
            throw new AssertionError("a node click must not favourite anything");
        }

        @Override
        public void openRowMenu(PlanView.EntryRow row) {
            throw new AssertionError("a node is not a shopping row");
        }

        @Override
        public void addRowToTodo(PlanView.EntryRow row) {
            throw new AssertionError("a node is not a shopping row");
        }

        @Override
        public void favouriteRow(PlanView.EntryRow row) {
            throw new AssertionError("a node is not a shopping row");
        }
    }

    /**
     * DRAG THE CANVAS BODY AND THE VIEW MOVES, past a threshold and not before.
     *
     * THE THRESHOLD IS ASSERTED IN BOTH DIRECTIONS, which is what makes it a threshold rather than
     * a number in a comment. A two-pixel drag must move nothing -- otherwise a click on the gap
     * between nodes shifts the whole diagram under a reader who thought they had clicked nothing --
     * and a thirty-pixel drag must move exactly thirty, because the delta is measured against the
     * PRESS position rather than the previous frame.
     *
     * THE SIGN IS PART OF THE CLAIM. Dragging the content left reveals what is to the RIGHT, so
     * the scroll offset goes UP: grab-and-move, which is the opposite of a scrollbar. A canvas that
     * got this backwards would pan smoothly in the wrong direction, and no assertion about
     * magnitude alone would notice.
     */
    @Test
    public void draggingTheCanvasBodyPansItOnceThePressHasMovedFarEnoughToMeanIt() {
        PlanNode tree = PlanTrees.fan(200);
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));

        FlowLayout.Laid laid = FlowLayout.of(tree);
        assertTrue("the fixture must have somewhere to pan to",
                   FlowZoom.scaledExtent(laid.height, canvas.zoom())
                           - canvas.getArea().height > 100);

        canvas.getContext().updateState(200, 120, 0f);
        canvas.onMousePressed(0);
        assertFalse("a press alone is not a pan", canvas.isPanning());

        // BELOW THE THRESHOLD: nothing moves.
        canvas.getContext().updateState(198, 120, 0f);
        canvas.onMouseDrag(0, 0L);
        assertFalse("a two-pixel tremor must not be a pan", canvas.isPanning());
        assertEquals(0, canvas.getScrollX());
        assertEquals(0, canvas.getScrollY());

        // ABOVE IT: the view follows the hand, anchored on the press.
        canvas.getContext().updateState(170, 80, 0f);
        canvas.onMouseDrag(0, 0L);
        assertTrue("past the threshold this is a pan", canvas.isPanning());
        assertEquals("dragging content left reveals what is to the right", 30,
                     canvas.getScrollX());
        assertEquals("and dragging it up reveals what is below", 40, canvas.getScrollY());

        // AND THE RELEASE ENDS IT, so a later mouse move with no button down cannot pan.
        canvas.onMouseRelease(0);
        assertFalse(canvas.isPanning());
        canvas.getContext().updateState(0, 0, 0f);
        canvas.onMouseDrag(0, 0L);
        assertEquals("a drag after the release must not move anything", 30, canvas.getScrollX());
    }

    /**
     * The two jump buttons put something in frame, and say so when there is nothing to put.
     *
     * `jumpToSelected` IS ASSERTED IN BOTH DIRECTIONS BECAUSE ITS FALSE CASE IS REACHABLE. A
     * selection made in a tree survives into a diagram of a re-solved plan, and a button that
     * silently did nothing there would read as broken. The negative arm is also what proves the
     * positive one is doing the work: a method that returned true unconditionally and panned
     * nowhere would satisfy a one-armed test.
     *
     * `fan` AND NOT `deepFan`, AND THE CHOICE IS THE DEFECT'S OWN. `deepFan` names its nodes by
     * DEPTH, so every leaf of a seven-deep tree is keyed "leaf" -- 2,187 boxes sharing one key.
     * `jumpToSelected` aims at the nearest occurrence, so asserting that a NAMED box came into
     * frame would fail against correct code whenever a closer sibling won. `fan` gives every box
     * its own key, which makes "the box that came into frame is the box that was selected" a claim
     * the method can actually be held to. This is `PlanSelectionTest`'s recorded lesson about
     * picking a fixture for convenience rather than for the property under test.
     */
    @Test
    public void theJumpButtonsPutTheirTargetInFrameAndRefuseWhenThereIsNoTarget() {
        PlanNode tree = PlanTrees.fan(200);
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(300, 180);
        HeadlessLayout.layOut(panelAround(canvas));
        FlowLayout.Laid laid = FlowLayout.of(tree);

        io.github.jacoblasky.recipedump.client.planner.PlanSelection.clear();
        canvas.panToFraction(1, 1);
        int parkedX = canvas.getScrollX();
        int parkedY = canvas.getScrollY();
        assertFalse("with nothing selected there is nothing to jump to",
                    canvas.jumpToSelected());
        assertEquals("and a refusal must not move the viewport", parkedX, canvas.getScrollX());
        assertEquals(parkedY, canvas.getScrollY());

        int root = canvas.rootBox();
        assertFalse("the far corner must not already show the root, or the jump proves nothing",
                    canvas.fullyShowing(root));
        canvas.jumpToRoot();
        assertTrue("jump-to-root must put the root wholly in frame",
                   canvas.fullyShowing(root));

        // THE LOWEST BOX, so the jump has somewhere to go that the root's own jump did not reach.
        int lowest = 0;
        for (int i = 1; i < laid.size(); i++) {
            if (laid.boxes.get(i).y > laid.boxes.get(lowest).y) {
                lowest = i;
            }
        }
        int occurrences = 0;
        for (int i = 0; i < laid.size(); i++) {
            if (laid.boxes.get(i).node.key().equals(laid.boxes.get(lowest).node.key())) {
                occurrences++;
            }
        }
        assertEquals("the target's key must be unique in this diagram, or 'nearest occurrence' "
                     + "makes the assertion below untrue of correct code", 1, occurrences);
        assertFalse("the target must not already be in frame", canvas.fullyShowing(lowest));
        io.github.jacoblasky.recipedump.client.planner.PlanSelection
                .select(laid.boxes.get(lowest).node);
        assertTrue("a selected key that IS in the diagram must be found",
                   canvas.jumpToSelected());
        assertTrue("and put wholly in frame", canvas.fullyShowing(lowest));

        // A KEY THE DIAGRAM DOES NOT HOLD is the other false case, and it is a different branch
        // from "nothing selected": the loop runs and finds no match.
        io.github.jacoblasky.recipedump.client.planner.PlanSelection.select(
                new io.github.jacoblasky.recipedump.plan.PlanNode.Builder()
                        .key("nothing:in_this_diagram").name("Elsewhere").label("Elsewhere")
                        .kind("item").build());
        int beforeX = canvas.getScrollX();
        assertFalse("a key this diagram does not hold cannot be jumped to",
                    canvas.jumpToSelected());
        assertEquals(beforeX, canvas.getScrollX());
        io.github.jacoblasky.recipedump.client.planner.PlanSelection.clear();
    }

    /**
     * A canvas whose nodes do nothing when clicked, said out loud.
     *
     * NAMED RATHER THAN DEFAULTED, which is the whole of #325 in one word. `FlowCanvas` took no
     * actions at all and reached for `PlannerWidgets.planNodeContent`'s inert overload, so every
     * diagram in three releases had unclickable nodes and nothing said so. Every test below is
     * about GEOMETRY -- culling, scroll extents, aiming -- so inert is the right subject here, and
     * `everyDiagramNodeOpensTheMenuForTheNodeThatWasClicked` is where the wiring is asserted.
     */
    private static FlowCanvas inert(PlanNode tree) {
        return new FlowCanvas(tree, PlannerActions.NONE);
    }

    /**
     * The flow panel around a HAND-SIZED canvas, for the tests whose subject is the viewport.
     *
     * `PlanView.empty()` BECAUSE THE PLAN ONLY DECIDES THE CHROME. Since #327 the panel draws the
     * plan's heading and warnings above the diagram and the canvas takes what is left -- but these
     * tests set `pos` and `size` on the canvas themselves, deliberately small, so that
     * `FlowCulling` has something to drop. An empty plan keeps that arrangement untouched;
     * `forPanelSizesTheCanvasToWhatTheChromeLeavesAtEveryWarningCount` asserts the real split.
     */
    private static ModularPanel panelAround(FlowCanvas canvas) {
        return PlannerWidgets.flowPanel(PlanView.empty(), canvas, PlannerActions.NONE);
    }

    /** Which boxes the culler says are on screen right now. */
    private static java.util.List<Integer> visible(FlowCanvas canvas, FlowLayout.Laid laid) {
        io.github.jacoblasky.recipedump.graph.IntArray shown = new FlowCulling(laid).visibleIn(
                canvas.getScrollArea().getScrollX().getScroll(),
                canvas.getScrollArea().getScrollY().getScroll(),
                canvas.getArea().width, canvas.getArea().height);
        java.util.List<Integer> out = new java.util.ArrayList<Integer>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            out.add(shown.get(i));
        }
        return out;
    }
}
