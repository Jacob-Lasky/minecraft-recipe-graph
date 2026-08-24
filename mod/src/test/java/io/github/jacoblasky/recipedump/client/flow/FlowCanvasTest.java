package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.GuiViewportStack;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.graph.IntArray;
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

    // -- #330: the edges are painted in the space the nodes land in -----------------------------

    /**
     * EVERY EDGE MEETS THE TWO NODES IT JOINS, AT EVERY SCROLL AND EVERY ZOOM. The witness for
     * #330.
     *
     * WHAT WAS WRONG. `FlowCanvas.draw` painted each edge in raw LAYOUT coordinates, and the
     * javadoc on the method that drew them said "the viewport transform is already applied". It is
     * not: measured in the ModularUI 3.1.5 bytecode, `InternalWidgetTree.drawTree` calls
     * `IWidget.draw` at offset 135 and does not reach `pushViewport` / `transformChildren` until
     * 188 and 194, so the scroll translate and the zoom scale reach the canvas's CHILDREN -- the
     * node widgets -- and not the canvas's own painting. At scroll 0 and zoom 1 the two spaces are
     * the same numbers. Every prodshot of this diagram was taken there, and `flow-nav2.png`
     * PASSED while showing edge fragments stranded at the bottom of the canvas.
     *
     * <h2>The probe is two pieces of code that do not share the answer</h2>
     *
     * The rectangles come from {@link FlowCanvas#edgeSegments}, which is the call `draw` makes.
     * Where they LAND is asked of {@link FlowCanvas#boxAt} -- `FlowCulling`'s index -- through
     * {@link FlowCanvas#toLayoutX}, the production inverse that `flow-hit` compares against
     * ModularUI's own hit-testing on every harness run. So the question put here is the one that
     * matters to a reader: is the pixel this edge ends on inside the node it points at. Asserting
     * the endpoint against `toScreenX(child.x)` instead would be the conversion agreeing with
     * itself.
     *
     * <h2>The control is the part that makes it a test</h2>
     *
     * A zero from a screen is a claim about the screen until the screen has been made to fail. So
     * the UNTRANSFORMED coordinates -- the ones the broken sweep used -- are fed to the same
     * probe, and the run requires that they land somewhere other than the node for a good many of
     * the pairs checked. Without that, every assertion above would be equally satisfied by the
     * pre-#330 code at scroll 0, which is exactly where it was always measured.
     *
     * COUNTED AND NOT ASSERTED PAIR BY PAIR, because the two spaces really do coincide for one
     * box per offset -- the one whose `box.x * (zoom - 1)` happens to equal the scroll -- and a
     * per-pair control would be red against correct code there. That coincidence is not a
     * weakness of the control; it is the bug's own camouflage, and at scroll 0 it covers every
     * box at once.
     *
     * BOTH ENDS, because they fail differently. The elbow leaves the parent's RIGHT border and
     * arrives at the child's LEFT border, so a sweep that converted only the origin, or that
     * scaled the width as well as the position, gets one of the two right.
     *
     * A NON-INTEGER ZOOM IS IN THE SWEEP ON PURPOSE, AND ONLY ONE OF THE TWO EVER CONTRIBUTES A
     * CHECK, WHICH IS WORTH WRITING DOWN RATHER THAN LEAVING TO BE REDISCOVERED. A fractional
     * zoom is where a rounding mistake shows -- the culler rounds a screen RECTANGLE outward onto
     * layout pixels, which is right for covering and wrong for a point, and reusing it here would
     * put every endpoint a pixel short of the node in the same direction at both ends. The
     * fractional zoom this test actually measures at is `FlowZoom.LABEL_LEGIBLE`, 0.75.
     *
     * 1.37 AND `FlowZoom.MAX` CONTRIBUTE NOTHING HERE AND CANNOT, because a parent and a child
     * span `COLUMN_PITCH + NODE_WIDTH` = 458 layout pixels: above about 1.13 no pair fits inside
     * a 520px canvas and the both-wholly-in-frame filter rejects every one of them. They stay in
     * the list because the filter is what skips them and because the loop is cheap, not because
     * they are covered. What covers them instead is
     * `FlowZoomTest.theZoomScalesTheLayoutPointAndTheScrollIsSubtractedAfterIt`, which pins the
     * composition at zoom 2 with the view 400 along, {@link
     * #modularUisOwnViewportMatrixAgreesWithTheConversionTheEdgesUse}, which compares the
     * conversion with ModularUI's own matrix across the whole zoom range, and
     * {@link #theSweepPaintsEveryVisibleEdgeThroughTheSeamAndNothingOutsideTheCanvas}, which runs
     * the whole sweep at 1.37.
     *
     * SO THE ZOOMS ARE ASSERTED BY NAME AND NOT BY COUNT. `size() >= 3` was exactly the number
     * that is reachable, so it read as slack while having none, and one pair fewer anywhere would
     * have failed with a count instead of with the name of the zoom that stopped contributing.
     */
    @Test
    public void everyEdgeMeetsTheNodesItJoinsAtEveryScrollAndZoom() {
        PlanNode tree = PlanTrees.deepFan(3, 4);
        FlowCanvas canvas = inert(tree);
        // WIDE ENOUGH TO HOLD A PARENT AND A CHILD AT ONCE, which the 300x180 used elsewhere in
        // this file is not: a column pitch is 249px, so an edge needs 458 of layout across before
        // both of its ends can be in frame. Production gives the canvas 612x372.
        canvas.pos(0, 0).size(520, 300);
        HeadlessLayout.layOut(panelAround(canvas));
        FlowLayout.Laid laid = FlowLayout.of(tree);
        assertTrue("the fixture must be bigger than the viewport in both directions",
                   laid.width > 520 && laid.height > 300);

        float[] zooms = {FlowZoom.MIN, FlowZoom.LABEL_LEGIBLE, FlowZoom.DEFAULT, 1.37f,
                         FlowZoom.MAX};
        double[] fractions = {0.0, 0.29, 0.61, 1.0};
        // WHICH ZOOMS AND WHICH OFFSETS ACTUALLY PRODUCED A CHECK, rather than a bare count.
        // A total says nothing about coverage: forty checks all taken at zoom 0.5 with the view
        // at the origin is the exact blind spot #330 lived in, and it would satisfy a threshold.
        java.util.Set<Float> zoomsChecked = new java.util.HashSet<Float>();
        java.util.Set<String> offsetsChecked = new java.util.HashSet<String>();
        int checked = 0;
        int controlled = 0;
        for (float zoom : zooms) {
            canvas.setZoom(zoom);
            for (double fraction : fractions) {
                canvas.panToFraction(fraction, fraction);
                canvas.updateVisible();
                for (int child = 0; child < canvas.nodeCount(); child++) {
                    int parent = canvas.parentOf(child);
                    // BOTH BOXES WHOLLY IN FRAME, so every segment of the elbow is inside the
                    // canvas and none of them is clipped -- which keeps this test about the
                    // conversion and leaves the clip to
                    // `aSpanIsClippedToTheCanvasAndDroppedWhenNoneOfItIsInside`.
                    if (parent < 0 || !canvas.fullyShowing(parent)
                            || !canvas.fullyShowing(child)) {
                        continue;
                    }
                    checked++;
                    zoomsChecked.add(zoom);
                    offsetsChecked.add(zoom + "@" + canvas.getScrollX() + ","
                                       + canvas.getScrollY());
                    IntArray out = new IntArray();
                    canvas.edgeSegments(parent, child, out);
                    assertEquals("an unclipped elbow is three rectangles at zoom " + zoom
                                 + " scroll " + canvas.getScrollX() + "," + canvas.getScrollY(),
                                 12, out.size());

                    // The elbow's last run is horizontal and ends ON the child's left border, so
                    // the pixel the edge arrives at is its right-hand end.
                    int arrivalX = out.get(8) + out.get(10);
                    int arrivalY = out.get(9);
                    assertEquals("the edge must arrive inside the child it points at, at zoom "
                                 + zoom + " scroll " + canvas.getScrollX() + ","
                                 + canvas.getScrollY() + " (arrival " + arrivalX + ","
                                 + arrivalY + ", child rect "
                                 + java.util.Arrays.toString(canvas.boxRectOnScreen(child)) + ")",
                                 child, boxUnder(canvas, arrivalX + INSIDE, arrivalY));

                    // And the first run leaves the parent's right border.
                    int departureX = out.get(0);
                    int departureY = out.get(1);
                    assertEquals("the edge must leave from inside the parent, at zoom " + zoom
                                 + " scroll " + canvas.getScrollX() + "," + canvas.getScrollY()
                                 + " (departure " + departureX + "," + departureY + ", parent rect"
                                 + " " + java.util.Arrays.toString(canvas.boxRectOnScreen(parent))
                                 + ")",
                                 parent, boxUnder(canvas, departureX - INSIDE, departureY));

                    // THE MIDDLE OF THE NODE'S HEIGHT, not its top. An elbow drawn at `box.y`
                    // touches the border rather than the node and looks like a rounding error.
                    int[] rect = canvas.boxRectOnScreen(child);
                    assertTrue("the edge must arrive at the child's vertical centre, not its"
                               + " edge: arrival y " + arrivalY + " against rect "
                               + java.util.Arrays.toString(rect),
                               Math.abs(arrivalY - (rect[1] + rect[3] / 2)) <= 1);

                    // THE CONTROL, COUNTED RATHER THAN ASSERTED PER PAIR, and the reason is the
                    // bug's own shape. Where the pre-#330 sweep put this edge is fed to the same
                    // probe; for almost every box it lands on a different box or on none, which
                    // is what gives the assertions above teeth. It is not asserted here for each
                    // pair because the two spaces genuinely COINCIDE for one box per offset --
                    // the one where `scrollX = box.x * (zoom - 1)` -- and a control that claimed
                    // otherwise would be red against correct code. That coincidence is the whole
                    // reason this survived three releases: at scroll 0 it holds for every box.
                    FlowLayout.Box box = laid.boxes.get(child);
                    if (child != boxUnder(canvas, box.x + INSIDE,
                                          box.y + FlowLayout.NODE_HEIGHT / 2)) {
                        controlled++;
                    }
                }
            }
        }
        assertTrue("the sweep must actually have found edges to check; it checked " + checked,
                   checked > 12);
        for (float zoom : new float[] {FlowZoom.MIN, FlowZoom.LABEL_LEGIBLE, FlowZoom.DEFAULT}) {
            assertTrue("every zoom at which a parent and a child can both fit this canvas must"
                       + " have contributed a check, and " + zoom + " did not: " + zoomsChecked,
                       zoomsChecked.contains(Float.valueOf(zoom)));
        }
        assertTrue("at six or more distinct zoom-and-offset combinations, or the sweep only ever"
                   + " looked at one arrangement: " + offsetsChecked,
                   offsetsChecked.size() >= 6);
        assertTrue("the probe must be able to TELL the two spaces apart: at more than four of"
                   + " the " + checked + " checked pairs the untransformed coordinates must land"
                   + " somewhere other than the node, or every assertion above would pass on the"
                   + " code #330 is about; it was " + controlled, controlled > 4);
    }

    /**
     * How far inside a node's border the probe aims, in SCREEN pixels.
     *
     * TWO AND NOT ONE, because at `FlowZoom.MIN` one screen pixel is two layout pixels and the
     * conversion back rounds -- so a one-pixel nudge can land on the border it was meant to clear.
     * A node is 209 layout pixels wide, so four is nowhere near the far side.
     */
    private static final int INSIDE = 2;

    /**
     * Which box the canvas says is under a SCREEN point, through the production inverse.
     *
     * THE POINT OF ROUTING THROUGH `toLayoutX`/`boxAt` rather than comparing rectangles: those two
     * are `FlowCulling`'s index reached through the conversion `flow-hit` measures against
     * ModularUI's own hit-testing, so a pass here is two independent pieces of code agreeing about
     * where a pixel is. See {@link #everyEdgeMeetsTheNodesItJoinsAtEveryScrollAndZoom}.
     */
    private static int boxUnder(FlowCanvas canvas, int screenX, int screenY) {
        return canvas.boxAt(canvas.toLayoutX(screenX), canvas.toLayoutY(screenY));
    }

    /**
     * The sweep draws every visible edge, through the one conversion, and nothing outside the
     * canvas.
     *
     * DRIVES THE PRODUCTION SWEEP. `FlowCanvas.draw` is `sweepEdges(PAINT)` and one line of
     * `super`, so this is the frame's own walk over the culled set with the last hop -- a
     * rectangle to the screen -- swapped for a recorder. `updateVisible` before it is the other
     * half of the same frame.
     *
     * THE COUNT IS ASSERTED AGAINST `edgeSegments` PER PAIR, which is what makes this a statement
     * that the sweep uses the seam rather than a second copy of the arithmetic. A sweep that
     * painted in layout coordinates would not merely draw in the wrong place: at this offset most
     * of its rectangles fall outside the canvas and are dropped by the clip, so the count itself
     * moves.
     *
     * AND NOTHING LEAVES THE CANVAS, which is the defect the fix would otherwise have introduced.
     * The `Stencil` that keeps the node widgets inside the viewport is applied by
     * `AbstractScrollWidget.preDraw` at drawTree offset 171 and the draw happens at 135, so an
     * edge is not clipped by anything the framework does. This fixture is the case that proves
     * it: a 200-leaf fan centres its root on the middle of a 6,400 pixel column, so the visible
     * leaves' edges all come from a parent thousands of pixels off the top of the canvas, and
     * every one of those elbows would be painted straight over the panel's heading and off its
     * frame without the clip.
     */
    @Test
    public void theSweepPaintsEveryVisibleEdgeThroughTheSeamAndNothingOutsideTheCanvas() {
        // `fan` AND NOT `deepFan`, AND THE FIRST VERSION OF THIS TEST GOT IT WRONG. A deep fan's
        // middle columns are SPARSE: at depth 1 a 3x4 tree has three boxes spread over 2,592
        // pixels of height, so a 300px viewport at zoom 1.37 landed on TWO nodes and the
        // precondition below failed against perfectly good code. Measured, not guessed -- the
        // run reported `drawnLastFrame() = 2`. A one-deep fan puts every leaf in one column at
        // the row pitch, so a viewport of this size always holds several, and each of them has
        // an edge to the root whether or not the root itself is on screen.
        PlanNode tree = PlanTrees.fan(200);
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(520, 300);
        HeadlessLayout.layOut(panelAround(canvas));

        canvas.setZoom(1.37f);
        canvas.panToFraction(0.41, 0.53);
        canvas.updateVisible();
        assertTrue("the culler must have put something on screen, or the sweep has no work: "
                   + canvas.drawnLastFrame(), canvas.drawnLastFrame() > 3);
        assertTrue("and the view must really have scrolled, or layout and screen coincide",
                   canvas.getScrollX() > 0 && canvas.getScrollY() > 0);

        RectRecorder recorded = new RectRecorder();
        canvas.sweepEdges(recorded);
        assertTrue("the sweep must have drawn something: " + recorded.rects.size(),
                   recorded.rects.size() > 6);

        // EXPECTED FROM THE SEAM, pair by pair, over the same walk `sweepEdges` makes: each
        // visible box's parent edge and each of its children's.
        int expected = 0;
        IntArray out = new IntArray();
        for (int box = 0; box < canvas.nodeCount(); box++) {
            if (!canvas.isVisible(box)) {
                continue;
            }
            int parent = canvas.parentOf(box);
            if (parent >= 0) {
                canvas.edgeSegments(parent, box, out);
                expected += out.size() / 4;
            }
            for (int child = 0; child < canvas.nodeCount(); child++) {
                // ONCE PER EDGE, which is the rule `sweepEdges` follows and the reason this
                // count is worth asserting: a visible child draws the edge from its own side,
                // and `EDGE` is translucent, so painting it twice is 64% opacity where a line
                // with one culled end gets 40%.
                if (canvas.parentOf(child) == box && !canvas.isVisible(child)) {
                    canvas.edgeSegments(box, child, out);
                    expected += out.size() / 4;
                }
            }
        }
        assertEquals("the sweep must paint exactly what edgeSegments produces for the visible"
                     + " boxes, or it is doing its own arithmetic", expected,
                     recorded.rects.size());

        for (int[] rect : recorded.rects) {
            assertTrue("a painted rectangle must be inside the canvas, or the edge is drawn over"
                       + " the panel's own chrome: " + java.util.Arrays.toString(rect)
                       + " against 520x300",
                       rect[0] >= 0 && rect[1] >= 0 && rect[2] > 0 && rect[3] > 0
                       && rect[0] + rect[2] <= 520 && rect[1] + rect[3] <= 300);
        }
    }

    /**
     * Aiming at a parent and a child puts BOTH of them wholly in frame, and the canvas says no
     * when no offset could. The witness for #330's scenario fix.
     *
     * THE ARITHMETIC THE PROD SHOT GOT STUCK ON, and it is worth writing the numbers down. A child
     * sits exactly `COLUMN_PITCH` right of its parent, so a pair spans `COLUMN_PITCH +
     * NODE_WIDTH` = 458 layout pixels. Centre either END on the production 612px canvas and the
     * other overruns the panel by 48. `flow-nav` drags 60px, searched the boxes that happened to
     * be in frame, found every pair with one end clipped, and reported "no edge can be measured"
     * as a FAILURE -- while the PNG beside it showed the #330 fix working. Reaching a measurable
     * state is navigation; refusing to is a verdict about the scenario.
     *
     * AT THE PRODUCTION GEOMETRY, THROUGH `forPanel` AND `flowPanel`, because the numbers are the
     * whole claim: a test at the 300x180 used elsewhere in this file would report that nothing
     * fits and would be telling the truth about the wrong panel.
     *
     * THE PAIR IS PICKED AWAY FROM THE LAYOUT'S EDGES ON PURPOSE. `panToBox` CLAMPS, so centring
     * a parent at x=0 leaves the child in frame anyway -- which would make the "centring one end
     * is not enough" control below pass for a reason that has nothing to do with the aim.
     *
     * AND THE IMPOSSIBLE CASE IS ASSERTED TOO. At `FlowZoom.MAX` a pair needs 916 pixels and no
     * offset fits it into 612, so {@link FlowCanvas#pairFitsTheViewport} must answer NO rather
     * than let a caller pan and hope. That is the one case where the shot is right to fail, and a
     * screen that never says no turns it back into the silent skip this replaced.
     */
    @Test
    public void aimingAtAPairPutsBothOfItsBoxesWhollyInFrame() {
        PlanNode tree = PlanTrees.deepFan(3, 4);
        PlanView plan = planWithWarnings(tree, 0);
        FlowCanvas canvas = FlowCanvas.forPanel(plan, PlannerActions.NONE);
        HeadlessLayout.layOut(PlannerWidgets.flowPanel(plan, canvas, PlannerActions.NONE));
        FlowLayout.Laid laid = FlowLayout.of(tree);
        int width = canvas.getArea().width;
        int height = canvas.getArea().height;
        // ASSERTED AGAINST THE PANEL'S OWN CONSTANTS, not against 612 written out again: a
        // literal here would pass while disagreeing with the panel, and every number in this
        // test's reasoning is derived from the canvas being the production one.
        assertEquals("this must be the production canvas or the numbers below are about nothing",
                     PlannerWidgets.FLOW_CANVAS_WIDTH, width);
        assertEquals(PlannerWidgets.flowCanvasHeight(plan), height);

        int parent = -1;
        int child = -1;
        for (int c = 0; c < canvas.nodeCount() && child < 0; c++) {
            int p = canvas.parentOf(c);
            if (p < 0 || !canvas.pairFitsTheViewport(p, c)) {
                continue;
            }
            FlowLayout.Box box = laid.boxes.get(p);
            if (box.x < width || box.y < height) {
                continue;
            }
            parent = p;
            child = c;
        }
        assertTrue("the production canvas must hold SOME parent and child away from the layout's"
                   + " edges at zoom 1, or flow-nav's measurement is impossible by construction",
                   child >= 0);

        // THE NEGATIVE FIRST, because "the pair is in frame after aiming" is a claim only a
        // viewport that was NOT already showing it can make.
        canvas.panToFraction(0, 0);
        assertFalse("the origin must not already show a pair this deep, or the aim below proves"
                    + " nothing", canvas.fullyShowing(parent) && canvas.fullyShowing(child));

        canvas.panToPair(parent, child);
        assertTrue("aiming at the pair must put the parent wholly in frame: "
                   + java.util.Arrays.toString(canvas.boxRectOnScreen(parent)) + " against "
                   + width + "x" + height, canvas.fullyShowing(parent));
        assertTrue("and the child too: "
                   + java.util.Arrays.toString(canvas.boxRectOnScreen(child)) + " against "
                   + width + "x" + height, canvas.fullyShowing(child));

        // CENTRING ONE END IS NOT ENOUGH, which is the reason this method exists rather than a
        // second `panToBox` call. Without this the test would pass for a `panToPair` that simply
        // delegated to `panToBox`.
        canvas.panToBox(parent);
        assertFalse("centring the parent must leave the child past the panel edge, or panToPair"
                    + " is solving a problem that does not exist: child "
                    + java.util.Arrays.toString(canvas.boxRectOnScreen(child)) + " on a canvas "
                    + width + " wide", canvas.fullyShowing(child));

        canvas.setZoom(FlowZoom.MAX);
        assertFalse("a pair needs COLUMN_PITCH + NODE_WIDTH scaled -- 916 at MAX -- and that does"
                    + " not fit " + width + ", so this must answer no rather than let the caller"
                    + " pan and hope", canvas.pairFitsTheViewport(parent, child));
        canvas.setZoom(FlowZoom.DEFAULT);
        assertTrue("and it must say yes again at zoom 1, or it is answering no to everything",
                   canvas.pairFitsTheViewport(parent, child));
    }

    /** Every rectangle the sweep produced, in order. */
    private static final class RectRecorder implements FlowCanvas.RectSink {

        private final java.util.List<int[]> rects = new java.util.ArrayList<int[]>();

        @Override
        public void rect(int x, int y, int width, int height) {
            rects.add(new int[] {x, y, width, height});
        }
    }

    /**
     * A span is clipped to the canvas rather than drawn over the panel, and a span with nothing
     * inside it is DROPPED rather than squashed against the border.
     *
     * BOTH SIGNS OF "NOTHING VISIBLE" ARE REACHABLE AND THE TEST SAYS SO. A span wholly to the
     * left answers a negative length, and so does one wholly to the right; a caller testing
     * `== 0` instead of `<= 0` would take a negative length as "no clipping needed" and paint a
     * full-width rectangle for every edge whose far end is off the left of the canvas -- which on
     * a scrolled diagram is most of them, and would read as a solid band across the middle of the
     * diagram rather than as a missing line.
     */
    @Test
    public void aSpanIsClippedToTheCanvasAndDroppedWhenNoneOfItIsInside() {
        assertEquals("a span wholly inside is untouched", 10,
                     FlowCanvas.clippedLength(5, 10, 100));
        assertEquals("a span running off the right end keeps what fits", 5,
                     FlowCanvas.clippedLength(95, 10, 100));
        assertEquals("a span starting left of zero keeps what is inside", 4,
                     FlowCanvas.clippedLength(-6, 10, 100));
        assertEquals("a span longer than the canvas is the whole canvas", 100,
                     FlowCanvas.clippedLength(-50, 300, 100));
        assertTrue("a span wholly left of the canvas must answer a length no caller draws: "
                   + FlowCanvas.clippedLength(-40, 10, 100),
                   FlowCanvas.clippedLength(-40, 10, 100) <= 0);
        assertTrue("and so must one wholly right of it: "
                   + FlowCanvas.clippedLength(140, 10, 100),
                   FlowCanvas.clippedLength(140, 10, 100) <= 0);
        assertTrue("an empty canvas can hold nothing", FlowCanvas.clippedLength(0, 10, 0) <= 0);
    }

    /**
     * NOTHING BUT THE SINK DRAWS AN EDGE, so the coordinates are always ones a test can read.
     *
     * THE #191 GATE APPLIED TO A PAINT CALL. The two tests above drive `sweepEdges` and can say
     * everything about where an edge goes -- and they would go on passing if `draw` stopped
     * calling the sweep and painted in layout coordinates again, because a headless test cannot
     * see a `GuiDraw` call. That is not a hypothetical regression: it is the exact code #330
     * replaced, and it survived three releases with `flow-nav2.png` cited as its proof.
     *
     * SO THE POLICY IS WHAT IS PINNED, and it is the policy that makes the sweep observable at
     * all: every rectangle this package paints goes through `FlowCanvas.RectSink`, and the one
     * implementation that reaches `GuiDraw` is a nested class of `FlowCanvas` and not
     * `FlowCanvas` itself. Reintroducing the bug means painting from `draw`, which puts a
     * `GuiDraw.drawRect` reference into `FlowCanvas.class` and reddens this.
     *
     * THE HOLDER IS SEARCHED, NOT NAMED `FlowCanvas$1`. An anonymous class's number is
     * positional and shifts the moment anyone declares an earlier one, so a hardcoded index is
     * wrong today or wrong at the next edit. Asserting that EXACTLY ONE nested class holds the
     * call is also the stronger statement: a second painter is the thing being forbidden.
     *
     * AND THE CONVERSION IS ASSERTED AS A SECOND LINK, because a sweep that went through the
     * sink and dropped `FlowZoom.toScreen` would satisfy everything above and be the original
     * bug with more indirection.
     */
    @Test
    public void theOnlyThingThatPaintsAnEdgeIsTheSinkTheSweepHandsRectanglesTo() throws Exception {
        java.util.List<String> painters = new java.util.ArrayList<String>();
        boolean sawFlowCanvas = false;
        for (java.io.File classFile : io.github.jacoblasky.recipedump.ClassFiles.under(
                io.github.jacoblasky.recipedump.ClassFiles.ROOT_PACKAGE + "/client/flow")) {
            String name = io.github.jacoblasky.recipedump.ClassFiles.internalName(classFile);
            java.util.Set<String> refs = io.github.jacoblasky.recipedump.ClassFiles
                    .methodReferences(io.github.jacoblasky.recipedump.ClassFiles.read(classFile));
            if (name.endsWith("/FlowCanvas")) {
                sawFlowCanvas = true;
                assertTrue("FlowCanvas must convert layout coordinates into screen coordinates"
                           + " through FlowZoom.toScreen; without it every edge is painted where"
                           + " the diagram was at scroll 0 and zoom 1 (#330): " + refs,
                           references(refs, "FlowZoom.toScreen"));
                // THE CONTROL: this scan can see a call FlowCanvas certainly makes. Without it an
                // empty reference set -- a wrong path, a class that failed to parse -- would make
                // every assertion here vacuous, and the absence of `GuiDraw.drawRect` below would
                // be a statement about the scan rather than about the code.
                assertTrue("the scan must find a call FlowCanvas definitely makes, or an absence"
                           + " here says nothing: " + refs,
                           references(refs, "FlowCulling.visibleIn"));
            }
            if (references(refs, "GuiDraw.drawRect")) {
                painters.add(name);
            }
        }
        assertTrue("the scan must have found FlowCanvas itself", sawFlowCanvas);
        assertEquals("exactly one class in this package may paint an edge, and it is the sink"
                     + " sweepEdges hands its rectangles to: " + painters, 1, painters.size());
        assertTrue("the painter must be a nested class of FlowCanvas and NOT FlowCanvas itself;"
                   + " a paint call in the canvas is a coordinate no headless test can read,"
                   + " which is how #330 survived three releases: " + painters,
                   painters.get(0).contains("/FlowCanvas$"));
    }

    private static boolean references(java.util.Set<String> refs, String suffix) {
        for (String ref : refs) {
            if (ref.contains(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MODULARUI'S OWN VIEWPORT MATRIX AGREES WITH THE CONVERSION THE EDGES USE, at the zooms and
     * offsets where the two candidate composition orders differ. The claim #330's fix rests on,
     * executed rather than read.
     *
     * WHY NOTHING ELSE HERE COULD MAKE THIS CLAIM. Every other assertion about #330 compares
     * `FlowZoom.toScreen` with itself or with `FlowCanvas.toLayoutX`, which is this repository's
     * own inverse -- so a conversion that composed the scroll translate and the zoom scale in the
     * WRONG ORDER, consistently, would satisfy all of them. `zoom * (layout - scroll)` is identical
     * at zoom 1 and at scroll 0 and diverges by `scroll * (zoom - 1)` elsewhere, and until this
     * test the only thing that said which order was right was a reading of the ModularUI bytecode.
     * An instrument that is correct where it was checked and cannot be checked anywhere else is the
     * exact shape #330 spent three releases hiding behind.
     *
     * SO THE FRAMEWORK IS ASKED DIRECTLY, AND IT NEEDS NO WINDOW. `GuiViewportStack` is plain JOML
     * arithmetic: `pushMatrix` on an empty stack builds an identity `TransformationMatrix`, and
     * `translate`, `scale` and `transformX` reach neither GL nor Minecraft. So the REAL
     * {@link FlowCanvas#transformChildren} runs here over a real stack -- the method
     * `InternalWidgetTree.drawTree` calls at offset 194, where `super` translates by `-scroll` and
     * the override scales by `zoom` -- and `transformX` reads whatever matrix JOML composed from
     * those two calls. That is the space the node widgets land in, computed by code this repository
     * did not write.
     *
     * WITHIN ONE PIXEL, BECAUSE THE TWO ROUND DIFFERENTLY AND BOTH ROUNDINGS ARE DELIBERATE.
     * `TransformationMatrix.transformX` ends in `f2i`, which truncates toward zero;
     * {@link FlowZoom#toScreen} rounds to nearest, for the reason its own note gives. One pixel is
     * the whole of the permitted disagreement, and the control below requires the other order to
     * be outside it -- otherwise agreeing with ModularUI here would say nothing.
     */
    @Test
    public void modularUisOwnViewportMatrixAgreesWithTheConversionTheEdgesUse() {
        PlanNode tree = PlanTrees.deepFan(3, 4);
        FlowCanvas canvas = inert(tree);
        canvas.pos(0, 0).size(520, 300);
        HeadlessLayout.layOut(panelAround(canvas));
        FlowLayout.Laid laid = FlowLayout.of(tree);

        int compared = 0;
        int discriminating = 0;
        for (float zoom : new float[] {FlowZoom.MIN, FlowZoom.LABEL_LEGIBLE, FlowZoom.DEFAULT,
                                       1.37f, FlowZoom.MAX}) {
            canvas.setZoom(zoom);
            for (double fraction : new double[] {0.0, 0.29, 0.61, 1.0}) {
                canvas.panToFraction(fraction, fraction);
                GuiViewportStack stack = new GuiViewportStack();
                stack.pushMatrix();
                canvas.transformChildren(stack);
                // EVERY SEVENTH BOX AND NOT ALL 121, because the claim is about the map rather
                // than about the boxes -- one matrix answers for every one of them -- and the
                // coverage that matters is the twenty zoom-and-offset combinations around this.
                for (int i = 0; i < laid.size(); i += 7) {
                    FlowLayout.Box box = laid.boxes.get(i);
                    int x = box.x + FlowLayout.NODE_WIDTH / 2;
                    int y = box.y + FlowLayout.NODE_HEIGHT / 2;
                    int frameworkX = stack.transformX(x, y);
                    int frameworkY = stack.transformY(x, y);
                    compared++;
                    assertTrue("ModularUI puts layout " + x + "," + y + " at " + frameworkX + ","
                               + frameworkY + " and the conversion the edges use says "
                               + canvas.toScreenX(x) + "," + canvas.toScreenY(y) + ", at zoom "
                               + zoom + " scroll " + canvas.getScrollX() + ","
                               + canvas.getScrollY(),
                               Math.abs(frameworkX - canvas.toScreenX(x)) <= 1
                               && Math.abs(frameworkY - canvas.toScreenY(y)) <= 1);
                    // THE CONTROL: the other composition order, which is what this fix would have
                    // been if the bytecode had been read backwards. It has to sit further from
                    // ModularUI's answer than the pixel of slack allowed above, or that assertion
                    // is satisfied by both formulas and distinguishes nothing.
                    if (Math.abs(frameworkX - Math.round(zoom * (x - canvas.getScrollX()))) > 1
                            || Math.abs(frameworkY
                                        - Math.round(zoom * (y - canvas.getScrollY()))) > 1) {
                        discriminating++;
                    }
                }
            }
        }
        assertTrue("the sweep must have compared something: " + compared, compared > 100);
        // A MAJORITY AND NOT ALL OF THEM, because the two orders genuinely agree at zoom 1 and at
        // scroll 0 -- eight of the twenty offsets swept here -- and a control that demanded
        // otherwise would be red against correct code. That agreement is the bug's camouflage
        // rather than a gap in this test: it is why #330 survived every screenshot ever taken.
        assertTrue("the other composition order must be more than a pixel from ModularUI's answer"
                   + " at most of the points compared, or agreeing with it proves nothing: "
                   + discriminating + " of " + compared, discriminating * 2 > compared);
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
