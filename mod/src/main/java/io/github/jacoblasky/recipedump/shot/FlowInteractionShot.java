package io.github.jacoblasky.recipedump.shot;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.sizer.Area;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.client.ScreenCursor;
import io.github.jacoblasky.recipedump.client.flow.FlowCanvas;
import io.github.jacoblasky.recipedump.client.jei.PlannerGuiHandler;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * The diagram driven as a SURFACE rather than photographed as a picture. #325, #326, #327, #328.
 *
 * <h2>Why these are not screens on `PlannerShot`</h2>
 *
 * `PlannerShot`'s flow entries photograph a canvas: `flow` sweeps the viewport for frame timings,
 * `flow-hit` compares two hover answers, `flow-selected` shows the wash. None of them presses a
 * mouse button on the canvas, and #325 shipped for three releases underneath all three of them --
 * every node inert, every screenshot correct. So the gesture needs its own screens, and putting
 * them beside the ones that could not see the defect is worth the file.
 *
 * <h2>What each one owes, and why a photograph is not it</h2>
 *
 * A SCREENSHOT OF AN OPEN NODE MENU IS EQUALLY CONSISTENT WITH THE MENU FOR THE WRONG NODE, which
 * is `jei-keybind`'s recorded lesson about the hovered stack and #315's live shape exactly: the
 * click reached the second node, the highlight moved, and the menu went on describing the first.
 * So {@link #openNodeMenu} asserts the menu's TITLE against the label of the box the LAYOUT says
 * was under the cursor -- two answers from two pieces of code -- and `ShotScreens.expectReport`
 * makes silence a failed run.
 *
 * THE CURSOR IS REAL AND THE CLICK GOES THROUGH `ModularScreen`. `Mouse.setCursorPosition` moves
 * the cursor on the Xvfb display and `screen.onMousePressed(0)` is the call
 * `GuiScreenWrapper.mouseClicked` makes, so ModularUI does its own hit-testing from the live
 * cursor and nothing here names the widget that receives the press. That is the difference between
 * this and `PlannerLayoutTest`, which calls `onMousePressed` on a widget it found by walking the
 * tree -- and which passes whether or not the widget is anywhere a mouse can reach.
 *
 * NOT GATED ON `IWidget.isHovering()`, which `PlannerShot.ClickViewButton` measured PERMANENTLY
 * FALSE for a bare `ClickableGroup`: parked dead centre on a laid-out button box, that flag never
 * set across forty polls. The reason is `AbstractParentWidget.canHover()` -- a `ParentWidget` with
 * no visible background is skipped by `ModularGuiContext.getHoveredWidgets` and never gets
 * `onMouseStartHover` -- and NOT the older story about the parent not being an `IViewport`, which
 * `planner-hover` disproved on 2026-08-23 by measuring a tree row inside a `ListWidget` failing
 * the same way. `flow-hit` gets a true answer from the same flag because `FlowCanvas` gives every
 * node `MC_BACKGROUND`; see `PlannerWidgets.ClickableGroup.drawsAsHovered` for the bytecode. The
 * verdicts below do not need the flag either way, because a click that landed on the wrong thing
 * opens the wrong panel and fails on the name.
 */
final class FlowInteractionShot {

    /** A truncated plan, so the chrome under test has a warning line to draw. #327. */
    private static final String DEFAULT_FIXTURE = "plan-truncated";

    /** Polls between parking the cursor and clicking. `flow-hit` measured 5. */
    private static final int SETTLE_POLLS = 5;

    /** Polls between a click and asking what it did. `PlannerShot` uses 4 for the same reason. */
    private static final int OPEN_POLLS = 4;

    private FlowInteractionShot() {
    }

    /**
     * `flow-node-menu`: open the planner, click "Show diagram", then click a node in it.
     *
     * THREE ISSUES IN ONE RUN, and they are only provable together. #325 is that the click opens
     * the node's menu at all. #326 is that the flow panel is STILL OPEN afterwards -- "Add to
     * TODO" from a diagram node bumps the book revision, and before #326 the window rebuilt and
     * the diagram vanished under the click that used it, so a menu on top of a CLOSED diagram is
     * the pre-#326 outcome and this fails on it. #328 is that JEI has been told to avoid the
     * diagram's rectangle, which is read off `PlannerGuiHandler` -- the surface JEI itself calls.
     */
    static void openNodeMenu(String arg) {
        PlanView plan = PlannerShot.fixtureFor(arg, DEFAULT_FIXTURE);
        PlannerShot.armNodeActions(plan.tree());
        PlannerScreen.openPlan(plan, PlannerShot.bookFor(plan));
        ShotScreens.expectReport("mcrecipedump_node_menu");
        ShotScreens.holdCapture(new ClickNodeInDiagram());
    }

    /**
     * `flow-nav`: drag the empty canvas to pan it, then put the root back with the Root button.
     *
     * TWO GESTURES IN ONE RUN BECAUSE THE SECOND NEEDS THE FIRST. "Jump to root works" is only a
     * claim if the root was not already in frame, and the honest way to get it out of frame is the
     * other new gesture rather than a `panToFraction` call the player has no access to. The pan
     * therefore doubles as the negative arm, which is what `centringOnABoxPutsItOnScreen`'s
     * headless version does with a `panToFraction(1, 1)` it cannot justify to a player.
     *
     * BOTH OFFSETS ARE LOGGED, before and after, because the verdict is a boolean and the
     * MAGNITUDE is what says the pan followed the hand rather than merely moving. A drag of 60
     * pixels that moved the view by 4 is a broken pan that passes a "did it move" check.
     *
     * AND SINCE #330, THE PAN IS FOLLOWED BY AN EDGE-TO-NODE MEASUREMENT, because a scrolled
     * canvas is the state in which the edges were wrong for three releases and this screen was
     * already standing in it. `flow-nav2.png` shows craft nodes mid-canvas with orphaned edge
     * fragments near the bottom edge, and it PASSED: the verdict asserted scroll offsets and node
     * names and never asked whether a line touched a box. See {@link DragThenJump#findTheEdge}.
     *
     * THE MEASUREMENT NAVIGATES TO A STATE IT CAN BE TAKEN IN, which is the correction to the
     * first version of it: on this fixture the drag leaves every parent/child pair with one end
     * clipped by the panel, and "nothing measurable" was being reported as a failure of the code.
     * See {@link DragThenJump#navigateToAMeasurablePair}.
     *
     * SO THIS SCREEN MAKES TWO CLAIMS THAT WANT THE VIEW IN DIFFERENT PLACES, AND THE ORDER IS
     * WHAT KEEPS THEM FROM CANCELLING. The Root-jump proof needs the root OFF frame before the
     * click; the adjacency measurement needs a pair panned wholly INTO frame, and on this layout
     * the nearest such pair is the root's own edge. Run the navigation first and the pan puts the
     * root back, so the jump proves nothing -- measured on rerun 3. The drag and the jump are
     * therefore asserted where they happen, the measurement follows, and one verdict at the end
     * reports both. `DragThenJump`'s step constants carry the invariant and
     * `FlowInteractionShotTest` pins it.
     *
     * WHICH MEANS THE PNG SHOWS THE MEASURED-PAIR VIEW. The capture lands when the hold releases,
     * which is now after the adjacency block -- so the screenshot is the diagram panned to the
     * parent and child the numbers in the verdict are about, rather than the drag state or the
     * root. That is the right pairing of picture and claim; `w-edges-nav.png` from rerun 2 remains
     * the artifact for the drag state.
     */
    static void openNav(String arg) {
        PlanView plan = PlannerShot.fixtureFor(arg, DEFAULT_FIXTURE);
        PlannerShot.armNodeActions(plan.tree());
        PlannerScreen.openPlan(plan, PlannerShot.bookFor(plan));
        ShotScreens.expectReport("a drag-pan and a jump back to the root");
        ShotScreens.holdCapture(new DragThenJump());
    }

    // -- the shared half: get the diagram open through its own button ---------------------------

    /**
     * Clicks "Show diagram" on the live planner and hands control on once the canvas is up.
     *
     * THE PANEL IS FOUND BACK RATHER THAN BUILT, which is `PlannerShot.openView`'s argument and
     * the whole reason #323 needed a second screen: a panel built here would carry a `FlowSource`
     * and a `PlannerActions` the harness chose, and the question is whether the ones PRODUCTION
     * chooses work. Everything below drives the panel `PlannerScreen.planPanel` built.
     */
    private abstract static class DriveTheDiagram implements ShotScreens.Hold {

        /**
         * Polls this screen may spend before it must answer, whatever it is waiting for.
         *
         * BECAUSE A `Hold` THAT WAITS FOR EVER COSTS FIFTY-TWO MINUTES TO LEARN ABOUT, and the
         * first run of this file did exactly that: `prodshot.sh: FAILED after 3126s (exit 3)`,
         * "the screen never reported 'mcrecipedump_node_menu'". The verdict machinery caught it --
         * silence is a failed run, which is the whole point of `expectReport` -- but the run's own
         * `-Dmcrecipedump.shotTimeoutSeconds` is a very expensive backstop, and it says only that
         * nothing was reported, not what the screen was waiting for.
         *
         * SO A STALL BECOMES A NAMED VERDICT rather than a timeout. `ScrollToRow` is content to
         * lean on the run's timeout, and it can be: it polls for a laid-out list on a panel that
         * is already open. This class drives four gestures across two panels and has four
         * different ways to be stuck, so "which one" is worth a number. 900 polls is roughly a
         * minute of render ticks -- far past any settle window here, far short of the run timeout.
         */
        private static final int POLL_BUDGET = 900;

        /** 0 = opening the diagram, 1 = the subclass's own gesture, 2 = done. */
        private int stage;
        private int poll;
        private int spent;
        private boolean clickedTheButton;

        /** The planner panel, kept once seen: the diagram opens OVER it. */
        private ModularPanel planner;

        /**
         * The diagram, KEPT ONCE FOUND, and this field is the whole of the first run's failure.
         *
         * It read `canvasIn(screen)` fresh on every poll, which walks the TOP-MOST panel -- so the
         * moment the node click succeeded and put the node menu on top, the canvas became
         * unfindable, `busy()` returned at the not-laid-out guard for ever, and `drive` -- which
         * holds the verdict -- was never called again. The log is unambiguous that everything up to
         * that point worked: the diagram opened, the click was taken, and ModularUI agreed the
         * cursor was on box 0.
         *
         * AND `canvasIn`'S OWN COMMENT ARGUED FOR THE WRONG SIDE. It said reading the top panel
         * "cannot find a canvas belonging to a panel that is no longer the one being driven" and
         * cited `PlannerShot.livePanel`, which reads the MAIN panel precisely so a sub-panel
         * opening over it cannot steal the lookup. That hazard is real for the BUTTON lookup and
         * inverted for the canvas: the canvas has to survive the panel that opens over it, because
         * a panel opening over it is the thing being tested. The same class already caches
         * {@link #planner} for exactly this reason, one field up, and the pattern was not applied
         * here.
         */
        private FlowCanvas canvas;

        @Override
        public final boolean busy() {
            ModularScreen screen = ModularScreen.getCurrent();
            if (screen == null) {
                // NULL IS THE NORMAL FIRST ANSWER, not an error: `openPlan` hands `ClientGUI` a
                // BUILDER, so nothing is current until the screen builds its UI. Bounded rather
                // than trusted, which is the difference between this and the first version.
                return spent++ > POLL_BUDGET
                        ? spend("no ModularScreen ever became current")
                        : true;
            }
            if (stage == 0) {
                if (spent++ > POLL_BUDGET) {
                    return spend("waiting for the '" + PlannerWidgets.SHOW_DIAGRAM
                                 + "' button, or for the diagram to open after clicking it");
                }
                return openTheDiagram(screen);
            }
            if (stage == 1) {
                if (canvas == null) {
                    canvas = canvasIn(screen);
                }
                if (canvas == null || canvas.getArea().width <= 0) {
                    // NOT LAID OUT YET, or the panel is not there at all. Both are "come back
                    // next poll" -- `ScrollToRow`'s shape rather than a frame count nobody can
                    // justify -- but bounded, per POLL_BUDGET.
                    return spent++ > POLL_BUDGET
                            ? spend("waiting for a laid-out FlowCanvas on the open diagram")
                            : true;
                }
                if (spent++ > POLL_BUDGET) {
                    return spend("the gesture never finished; last poll " + poll);
                }
                if (drive(screen, canvas, poll++)) {
                    return true;
                }
                stage = 2;
                return false;
            }
            return false;
        }

        /** Report what this screen was stuck on and stop, rather than holding until the clock. */
        private boolean spend(String waitingFor) {
            ShotScreens.reportFail("gave up after " + spent + " polls at stage " + stage + ": "
                                   + waitingFor);
            stage = 2;
            return false;
        }

        /**
         * @param poll polls since this stage began, starting at 0
         * @return true while this gesture needs more polls
         */
        abstract boolean drive(ModularScreen screen, FlowCanvas canvas, int poll);

        private boolean openTheDiagram(ModularScreen screen) {
            if (planner == null) {
                planner = screen.getPanelManager().getMainPanel();
            }
            List<PlannerWidgets.ClickableGroup> buttons = viewButtons(planner);
            int index = PlannerWidgets.VIEW_LABELS.indexOf(PlannerWidgets.SHOW_DIAGRAM);
            if (buttons.size() <= index || buttons.get(index).getArea().height <= 0) {
                return true;
            }
            if (clickedTheButton) {
                if (poll < OPEN_POLLS) {
                    poll++;
                    return true;
                }
                ModularPanel top = screen.getPanelManager().getTopMostPanel();
                ShotHarness.log("flow-drive: the diagram is "
                                + (top == null ? "nowhere" : "'" + top.getName() + "'")
                                + ", " + screen.getPanelManager().getOpenPanelCount()
                                + " panel(s) open");
                stage = 1;
                poll = 0;
                return true;
            }
            PlannerWidgets.ClickableGroup button = buttons.get(index);
            Area box = button.getArea();
            // RE-PARKED EVERY POLL, which is `ScrollToRow`'s fix one axis over: holding and
            // re-applying is what makes the last poll before the click a parked one, whatever
            // swallowed the first attempt. Idempotent, so the read-then-move rule stays satisfied.
            ScreenCursor.park(box.x + box.width / 2, box.y + box.height / 2);
            if (poll++ < SETTLE_POLLS) {
                return true;
            }
            boolean taken = screen.onMousePressed(0);
            ShotHarness.log("flow-drive: clicked '" + PlannerWidgets.SHOW_DIAGRAM + "' at "
                            + (box.x + box.width / 2) + "," + (box.y + box.height / 2)
                            + "; the screen " + (taken ? "took" : "did NOT take") + " it");
            clickedTheButton = true;
            poll = 0;
            return true;
        }
    }

    // -- flow-node-menu ------------------------------------------------------------------------

    private static final class ClickNodeInDiagram extends DriveTheDiagram {

        /** The box the layout said was under the cursor on the poll the click was dispatched. */
        private int clickedBox = -1;
        private PlanNode clickedNode;
        private boolean clicked;
        private int since;

        @Override
        boolean drive(ModularScreen screen, FlowCanvas canvas, int poll) {
            if (!clicked) {
                // AIMED AT A BOX THE CANVAS PUT IN FRAME ITSELF. `panToBox(rootBox())` is the
                // production `Root` button's own call, so the cursor is sent somewhere the diagram
                // agrees is occupied rather than to a coordinate this file guessed -- which is
                // #293's blank-screenshot mistake, and `parkCursorOverBox` refuses rather than
                // probing an off-screen box for the same reason.
                if (poll == 0) {
                    canvas.panToBox(canvas.rootBox());
                }
                if (!canvas.parkCursorOverBox(canvas.rootBox())) {
                    ShotScreens.reportFail("the root box is not on screen after panning to it, so"
                                           + " there is nothing to click: "
                                           + canvas.cursorDiagnostic());
                    return false;
                }
                if (poll < SETTLE_POLLS) {
                    return true;
                }
                // READ BEFORE THE CLICK AND FROM THE LAYOUT, NOT FROM THE MENU. This is the whole
                // discrimination: the expected answer comes from `boxAtCursor`, which is the
                // culler's index, while the actual answer comes from the panel ModularUI's own
                // hit-testing opened. Reading the node off the menu afterwards would compare the
                // menu with itself.
                clickedBox = canvas.boxAtCursor();
                if (clickedBox < 0) {
                    ShotScreens.reportFail("the layout says no box is under the cursor: "
                                           + canvas.cursorDiagnostic());
                    return false;
                }
                clickedNode = canvas.boxNode(clickedBox);
                boolean taken = screen.onMousePressed(0);
                ShotHarness.log("flow-node-menu: clicked box " + clickedBox + " ('"
                                + NodeRowText.label(clickedNode) + "', key " + clickedNode.key()
                                + "); the screen " + (taken ? "took" : "did NOT take") + " it; "
                                + "ModularUI says hoveredBox=" + canvas.hoveredBox()
                                + " [" + canvas.cursorDiagnostic() + "]");
                clicked = true;
                return true;
            }
            if (since++ < OPEN_POLLS) {
                return true;
            }
            verdict(screen);
            return false;
        }

        private void verdict(ModularScreen screen) {
            ModularPanel top = screen.getPanelManager().getTopMostPanel();
            String name = top == null ? "none" : top.getName();
            boolean flowStillOpen = screen.isPanelOpen("mcrecipedump_flow");
            String title = firstText(top);
            String wanted = NodeRowText.fit(NodeRowText.label(clickedNode),
                                            PlannerWidgets.MENU_WIDTH
                                                    - PlannerWidgets.PADDING * 2);
            Collection<Rectangle> avoided = new PlannerGuiHandler().getGuiExtraAreas();
            boolean diagramAvoided = containsSize(avoided, PlannerWidgets.FLOW_WIDTH,
                                                  PlannerWidgets.FLOW_HEIGHT);

            ShotHarness.log("flow-node-menu: top panel '" + name + "', title '" + title
                            + "' (wanted '" + wanted + "'), the diagram is "
                            + (flowStillOpen ? "STILL OPEN" : "CLOSED") + ", "
                            + screen.getPanelManager().getOpenPanelCount() + " panel(s) open,"
                            + " JEI is told to avoid " + avoided);

            List<String> wrong = new ArrayList<String>();
            if (!"mcrecipedump_node_menu".equals(name)) {
                // #325: before this, a diagram node was built through the inert overload and a
                // click on it opened nothing at all, so the top panel would still be the diagram.
                wrong.add("the top panel is '" + name + "' and not the node menu");
            }
            if (!wanted.equals(title)) {
                // #315's live shape: a menu describing a DIFFERENT node is the failure a
                // photograph cannot tell from a success.
                wrong.add("the menu is titled '" + title + "' and the clicked box is '"
                          + wanted + "'");
            }
            if (!flowStillOpen) {
                // #326: the node menu's own opening does not bump the stamp, but the window used
                // to rebuild on any change while a sub-panel was up. A closed diagram here is that
                // defect, and it is the state in which the next click acts on a panel nobody sees.
                wrong.add("the diagram closed under the click that used it (#326)");
            }
            if (!diagramAvoided) {
                // #328: the flow panel is 620x380 and was the one panel never reported, so JEI
                // drew its item list over the diagram's deepest columns.
                wrong.add("JEI was not told to avoid the " + PlannerWidgets.FLOW_WIDTH + "x"
                          + PlannerWidgets.FLOW_HEIGHT + " diagram (#328): " + avoided);
            }
            if (wrong.isEmpty()) {
                ShotScreens.reportPass();
            } else {
                ShotScreens.reportFail(wrong.toString());
            }
        }
    }

    // -- flow-nav ------------------------------------------------------------------------------

    static final class DragThenJump extends DriveTheDiagram {

        /**
         * The gesture's steps, in the order they run.
         *
         * `DRAG_AND_RELEASE` RATHER THAN `DRAG`, and `ROOT_JUMP` rather than `JUMP_TO_ROOT`: this
         * class already has a `DRAG` (the gesture's 60px distance) and reads
         * `PlannerWidgets.JUMP_TO_ROOT` (the button's label), and a step sharing either name
         * would compile while reading as the other thing.
         *
         * NAMED AND NOT LITERAL, BECAUSE THE ORDER IS A CONTRACT BETWEEN TWO PROOFS THAT SHARE ONE
         * VIEWPORT. `flow-nav` makes two independent claims about this canvas and each needs the
         * view in a DIFFERENT state:
         *
         *   - `ROOT_JUMP` needs the root OFF frame before the click, or "the button
         *     brought it into frame" is satisfied by a root that never left;
         *   - the #330 adjacency measurement needs a parent and child BOTH wholly in frame, which
         *     on this layout means panning somewhere the drag did not land.
         *
         * Those two states are incompatible, so the order is what keeps them from cancelling. It
         * has been wrong once in each direction: the measurement first, and it had nothing
         * measurable to look at; the navigation before the jump, and the pan put the root back so
         * the jump proved nothing (`rootShowingBefore` was true, measured on rerun 3 at scroll
         * 0,132 with the nearest pair being box 0 -> 1, the root's own edge).
         *
         * DO NOT REORDER `ROOT_JUMP` AFTER `NAVIGATE`. `FlowInteractionShotTest` pins it,
         * because the failure is a passing-looking verdict about the wrong thing rather than a
         * crash.
         */
        static final int PRESS = 0;
        static final int DRAG_AND_RELEASE = 1;
        static final int ROOT_JUMP = 2;
        static final int NAVIGATE = 3;
        static final int MEASURE = 4;
        static final int ARRIVAL = 5;
        static final int DEPARTURE = 6;
        static final int VERDICT = 7;

        private int[] anchor;
        private int scrollXBeforeDrag;
        private int scrollYBeforeDrag;
        private int scrollXAfterDrag;
        private int scrollYAfterDrag;
        private int step;
        private int since;
        /**
         * Everything found wrong, accumulated across the steps that measure it.
         *
         * A FIELD BECAUSE THE PROOFS NO LONGER SHARE A STEP. The drag and the Root jump are
         * asserted where they happen -- they have to be, the viewport moves afterwards -- and the
         * #330 findings are added at the end, so the single pass/fail report needs somewhere to
         * collect them both.
         */
        private final List<String> wrong = new ArrayList<String>();

        /** How far the probe drags. Well past `FlowCanvas`'s threshold, and inside the canvas. */
        private static final int DRAG = 60;

        /**
         * How far inside a node's border the edge probe aims, in screen pixels.
         *
         * THE EDGE STOPS ON THE BORDER, WHICH IS THE POINT: the elbow's last run ends at the
         * child's left edge, so the pixel to ask about is just inside it. Two rather than one
         * because a node's own nine-slice frame occupies the outermost pixel and the answer
         * there would be about the border rather than about the node.
         */
        private static final int INSIDE = 2;

        // -- #330: the edge-to-node measurement, taken while the view is scrolled --------------

        /** The parent/child pair being measured, or -1 when none was found. */
        private int edgeParent = -1;
        private int edgeChild = -1;
        /** The rectangles `FlowCanvas.draw` paints for that pair, as {x,y,w,h} quads. */
        private final IntArray segments = new IntArray();
        private int arrivalX;
        private int arrivalY;
        private int departureX;
        private int departureY;
        private int[] childRect;
        private int[] parentRect;
        /** -2 = not probed yet, -1 = nothing there, otherwise a box index. */
        private int arrivalHovered = -2;
        private int arrivalLayoutBox = -2;
        private int departureHovered = -2;
        private int departureLayoutBox = -2;

        @Override
        boolean drive(ModularScreen screen, FlowCanvas canvas, int poll) {
            if (step == PRESS) {
                return pressOnEmptyCanvas(screen, canvas);
            }
            if (step == DRAG_AND_RELEASE) {
                return dragAndRelease(screen, canvas);
            }
            // THE ROOT JUMP BEFORE THE NAVIGATION, and see the step constants for why the order
            // is load-bearing rather than incidental.
            if (step == ROOT_JUMP) {
                return jumpBackToTheRoot(screen, canvas);
            }
            if (step == NAVIGATE) {
                return navigateToAMeasurablePair(canvas);
            }
            if (step == MEASURE) {
                return findTheEdge(canvas);
            }
            if (step == ARRIVAL) {
                return probeTheEdgeEnd(canvas, true);
            }
            if (step == DEPARTURE) {
                return probeTheEdgeEnd(canvas, false);
            }
            return reportTheVerdict();
        }

        /**
         * Pan until some parent and child are both wholly in frame, and say which. #330.
         *
         * "NOTHING MEASURABLE HERE" IS A NAVIGATION STEP, NOT A VERDICT, AND THE FIRST VERSION
         * GOT THAT WRONG. It searched the boxes that happened to be in frame after the drag, and
         * on `plan-truncated` every pair had at least one end clipped by the panel edge -- so the
         * shot exited on its own "no edge can be measured" report while the PNG beside it showed
         * the #330 fix working. A probe that can reach a measurable state and declines to is
         * reporting on the scenario, not on the code.
         *
         * THE PAIR IS CHOSEN FROM THE LAYOUT AND NOT FROM WHAT IS VISIBLE, because visibility is
         * the thing about to be arranged. What disqualifies a pair is that it CANNOT fit -- a
         * child sits `COLUMN_PITCH` right of its parent, so the pair spans 458 layout pixels and
         * needs 916 at `FlowZoom.MAX`, which no pan fits into a 612px canvas. That is the only
         * case left that fails, and it fails loudly with the numbers.
         *
         * NEAREST TO THE CENTRE OF THE DRAGGED VIEW, so the navigation is the smallest one that
         * works and the measurement still happens near where the drag left off. Through
         * `FlowCanvas.panToPair`, which is the production aim: centring either END leaves the
         * other 48 pixels past the panel, which is exactly how this scenario got stuck.
         */
        private boolean navigateToAMeasurablePair(FlowCanvas canvas) {
            Area area = canvas.getArea();
            int fitting = 0;
            int atTheOrigin = 0;
            for (int child = 0; child < canvas.nodeCount(); child++) {
                int parent = canvas.parentOf(child);
                if (parent < 0 || !canvas.pairFitsTheViewport(parent, child)) {
                    continue;
                }
                fitting++;
                canvas.panToPair(parent, child);
                // BOTH OFFSETS MUST BE NON-ZERO, AND THIS IS THE POINT OF MEASURING AT ALL. At
                // scroll 0 the pre-#330 sweep painted in the RIGHT PLACE, because layout and
                // screen coordinates are the same numbers there -- so a measurement taken at an
                // offset of zero on an axis cannot tell the fix from the bug on that axis. Rerun 3
                // measured at scroll 0,132: correct, and blind on x. `panToPair` clamps, so aiming
                // at a pair within half a viewport of the layout's origin lands exactly there,
                // which is also why the nearest pair to a just-jumped-to root is the worst
                // possible choice.
                if (canvas.getScrollX() <= 0 || canvas.getScrollY() <= 0) {
                    atTheOrigin++;
                    continue;
                }
                // Belt and braces against the clamp: with no clamping both ends are in frame by
                // construction, so this only fires at the far edges of the layout.
                if (!canvas.fullyShowing(parent) || !canvas.fullyShowing(child)) {
                    continue;
                }
                edgeParent = parent;
                edgeChild = child;
                ShotHarness.log("flow-nav #330: " + fitting + " of this plan's edges fit a "
                                + area.width + "x" + area.height + " canvas at zoom "
                                + canvas.zoom() + " and " + atTheOrigin + " of those aim at an"
                                + " offset of zero on an axis; navigated to box " + edgeParent
                                + " -> " + edgeChild + "; scroll " + canvas.getScrollX() + ","
                                + canvas.getScrollY());
                step = MEASURE;
                since = 0;
                return true;
            }
            ShotScreens.reportFail("no parent and child pair can be measured on a " + area.width
                                   + "x" + area.height + " canvas at zoom " + canvas.zoom()
                                   + ": " + fitting + " fit at all and " + atTheOrigin + " of"
                                   + " those aim at an offset of zero on an axis, where layout"
                                   + " and screen coordinates coincide and the measurement would"
                                   + " pass against the bug (#330): " + canvas.cursorDiagnostic());
            return false;
        }

        /**
         * Read the rectangles the canvas paints for the pair {@link #navigateToAMeasurablePair}
         * aimed at. #330.
         *
         * PROGRAMMATIC BECAUSE THE PICTURE ALREADY LIED ONCE. `flow-nav2.png` was produced by this
         * very screen with every edge in the wrong place, and it was cited as the artifact that
         * proved the diagram worked. So the numbers are read here and asserted, and the screenshot
         * is what a human looks at afterwards rather than the evidence.
         *
         * THE RECTANGLES COME FROM `FlowCanvas.edgeSegments`, WHICH IS THE CALL `draw` MAKES. Not
         * a re-derivation of it: the sweep loops over the culled boxes and hands each pair to that
         * method, so what is measured below is the geometry that was painted this frame.
         *
         * THE AIM IS VERIFIED RATHER THAN ASSUMED. `panToPair` clamps, so a pair within half a
         * viewport of an edge of the layout can be asked for and not delivered; this is where that
         * shows up, with both rectangles in the message.
         *
         * AND BOTH BOXES WHOLLY IN FRAME IS WHAT KEEPS THIS A MEASUREMENT OF THE CONVERSION
         * RATHER THAN OF THE CLIP. Every run of the elbow is then inside the canvas and none is
         * dropped, so the arrival pixel is a pixel the cursor can be parked on. It is every run
         * and not "all three": since #335 a same-row pair is two rectangles, and `panToPair` can
         * pick one -- a parent centred on its subtree band shares a row with its middle child.
         */
        private boolean findTheEdge(FlowCanvas canvas) {
            if (!canvas.fullyShowing(edgeParent) || !canvas.fullyShowing(edgeChild)) {
                ShotScreens.reportFail("panToPair aimed at boxes " + edgeParent + " -> "
                                       + edgeChild + " and they are still not both wholly in"
                                       + " frame: parent "
                                       + java.util.Arrays.toString(
                                               canvas.boxRectOnScreen(edgeParent))
                                       + ", child "
                                       + java.util.Arrays.toString(
                                               canvas.boxRectOnScreen(edgeChild))
                                       + " against " + canvas.getArea().width + "x"
                                       + canvas.getArea().height + " (#330)");
                return false;
            }
            canvas.edgeSegments(edgeParent, edgeChild, segments);
            parentRect = canvas.boxRectOnScreen(edgeParent);
            childRect = canvas.boxRectOnScreen(edgeChild);
            // THREE RECTANGLES, OR TWO WHEN THE PAIR SHARES A ROW, AND `!= 12` WAS #335. A
            // straight edge has no vertical run to draw; emitting a 1x1 one at the corner put it
            // inside the arrival horizontal, and `EDGE` is translucent so the doubled pixel showed
            // as a brighter dot. A same-row pair is not exotic here -- `panToPair` aims at the
            // nearest measurable pair and a parent centred on its subtree band shares a row with
            // its middle child -- so this screen could pick one and would have failed on it.
            if (segments.size() != 12 && segments.size() != 8) {
                ShotScreens.reportFail("an unclipped elbow must be three rectangles, or two when"
                                       + " its boxes share a row, and this one is "
                                       + (segments.size() / 4) + " (#330, #335)");
                return false;
            }
            // The elbow leaves the parent's right border with its first run and arrives at the
            // child's left border with its last, so the two ends are the first quad's left edge
            // and the LAST quad's right edge.
            //
            // BY POSITION, NOT BY THE OFFSET 8, which is the other half of #335: the arrival quad
            // is the third for a bent elbow and the second for a straight one, so a fixed index is
            // wrong for one of them whichever number is written.
            int arrival = segments.size() - 4;
            departureX = segments.get(0);
            departureY = segments.get(1);
            arrivalX = segments.get(arrival) + segments.get(arrival + 2);
            arrivalY = segments.get(arrival + 1);
            ShotHarness.log("flow-nav #330: box " + edgeParent + " -> " + edgeChild
                            + " at zoom " + canvas.zoom() + " scroll " + canvas.getScrollX()
                            + "," + canvas.getScrollY()
                            + "; painted " + quads(segments)
                            + "; parent rect " + java.util.Arrays.toString(parentRect)
                            + ", child rect " + java.util.Arrays.toString(childRect)
                            + "; departure " + departureX + "," + departureY
                            + ", arrival " + arrivalX + "," + arrivalY);
            step = ARRIVAL;
            since = 0;
            return true;
        }

        /**
         * Park the real cursor just inside the node an edge end touches, and ask TWO independent
         * sources what is there. #330.
         *
         * THE POINT OF DRIVING A REAL CURSOR IS THAT MODULARUI ANSWERS. `FlowCanvas.hoveredBox`
         * reads `IWidget.isHovering`, which is the verdict `getWidgetsAt` produced this frame by
         * walking the viewport stack's INVERSE -- the framework's own opinion about where it put
         * that node widget on screen, computed by code this repository did not write. That is the
         * half a self-consistent conversion cannot fake: an edge painted in layout coordinates
         * lands the cursor wherever the diagram was before it scrolled, and ModularUI reports the
         * node that is really there, or none.
         *
         * `boxAtCursor` IS ASKED AS WELL AND IT IS A DIFFERENT QUESTION. That one is the layout's
         * answer through `toLayoutX`, so the two together are `flow-hit`'s comparison narrowed to
         * one pixel that matters. Reported separately, because "the layout disagrees" and
         * "ModularUI disagrees" have different causes and a single boolean would hide which.
         *
         * FIVE SETTLE POLLS, which is what `flow-hit` measured a cursor move to need before the
         * hover flags catch up. Re-parked every poll, per `ScrollToRow`'s fix: whatever swallowed
         * the first attempt, the last poll before the read is a parked one.
         */
        private boolean probeTheEdgeEnd(FlowCanvas canvas, boolean arrival) {
            Area area = canvas.getArea();
            int x = arrival ? arrivalX + INSIDE : departureX - INSIDE;
            int y = arrival ? arrivalY : departureY;
            ScreenCursor.park(area.x + x, area.y + y);
            if (since++ < SETTLE_POLLS) {
                return true;
            }
            int hovered = canvas.hoveredBox();
            int layoutBox = canvas.boxAtCursor();
            if (arrival) {
                arrivalHovered = hovered;
                arrivalLayoutBox = layoutBox;
            } else {
                departureHovered = hovered;
                departureLayoutBox = layoutBox;
            }
            ShotHarness.log("flow-nav #330: parked " + INSIDE + "px inside the "
                            + (arrival ? "child" : "parent") + " at the "
                            + (arrival ? "arrival" : "departure") + " end (" + x + "," + y
                            + " on the canvas, screen " + (area.x + x) + "," + (area.y + y)
                            + "); ModularUI says hoveredBox=" + hovered
                            + ", the layout says boxAt=" + layoutBox
                            + ", wanted " + (arrival ? edgeChild : edgeParent)
                            + " [" + canvas.cursorDiagnostic() + "]");
            step = arrival ? DEPARTURE : VERDICT;
            since = 0;
            return true;
        }

        /** The quads in `out` as `x,y wxh` triples, for the log. */
        private static String quads(IntArray out) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i + 3 < out.size(); i += 4) {
                text.append(i == 0 ? "" : " | ").append(out.get(i)).append(',')
                        .append(out.get(i + 1)).append(' ').append(out.get(i + 2))
                        .append('x').append(out.get(i + 3));
            }
            return text.toString();
        }

        private boolean pressOnEmptyCanvas(ModularScreen screen, FlowCanvas canvas) {
            // AWAY FROM THE ROOT FIRST, so the diagram has somewhere to be dragged from and the
            // jump at the end has something to undo. Through the canvas's own aiming call rather
            // than a fraction, for `panToBox`'s stated reason.
            if (anchor == null) {
                canvas.panToBox(canvas.rootBox());
                // DRAG as the inset, so the up-left drag below cannot aim the cursor off-screen --
                // the sweep's old (2,2) answer did exactly that, and the OS clamp turned a
                // 60px drag into a measured ~16,49 pan. See `emptyPointOnScreen`'s javadoc.
                anchor = canvas.emptyPointOnScreen(DRAG);
                if (anchor == null) {
                    ShotScreens.reportFail("no point on this canvas is outside a box, so a press"
                                           + " on the body cannot be aimed: "
                                           + canvas.cursorDiagnostic());
                    return false;
                }
            }
            Area area = canvas.getArea();
            ScreenCursor.park(area.x + anchor[0], area.y + anchor[1]);
            if (since++ < SETTLE_POLLS) {
                return true;
            }
            scrollXBeforeDrag = canvas.getScrollX();
            scrollYBeforeDrag = canvas.getScrollY();
            boolean taken = screen.onMousePressed(0);
            ShotHarness.log("flow-nav: pressed empty canvas at " + anchor[0] + "," + anchor[1]
                            + " (screen " + (area.x + anchor[0]) + "," + (area.y + anchor[1])
                            + "); the screen " + (taken ? "took" : "did NOT take") + " it;"
                            + " scroll before the drag = " + scrollXBeforeDrag + ","
                            + scrollYBeforeDrag);
            step = DRAG_AND_RELEASE;
            since = 0;
            return true;
        }

        private boolean dragAndRelease(ModularScreen screen, FlowCanvas canvas) {
            Area area = canvas.getArea();
            // MOVED THEN DRAGGED, one poll apart, because the cursor position a drag reads is
            // sampled at the start of a tick -- `flow-hit`'s read-then-move rule. Dragging on the
            // poll that moved the mouse would drag by the PREVIOUS delta, which on the first poll
            // is zero and reads as a canvas that will not pan.
            ScreenCursor.park(area.x + anchor[0] - DRAG, area.y + anchor[1] - DRAG);
            if (since++ < 2) {
                return true;
            }
            screen.onMouseDrag(0, 0L);
            scrollXAfterDrag = canvas.getScrollX();
            scrollYAfterDrag = canvas.getScrollY();
            screen.onMouseRelease(0);
            ShotHarness.log("flow-nav: dragged " + DRAG + " px up and left; scroll "
                            + scrollXBeforeDrag + "," + scrollYBeforeDrag + " -> "
                            + scrollXAfterDrag + "," + scrollYAfterDrag
                            + "  (moved " + (scrollXAfterDrag - scrollXBeforeDrag) + ","
                            + (scrollYAfterDrag - scrollYBeforeDrag) + ")");
            step = ROOT_JUMP;
            since = 0;
            return true;
        }

        private boolean jumpBackToTheRoot(ModularScreen screen, FlowCanvas canvas) {
            // THE CANVAS'S OWN PANEL AND NOT THE TOP-MOST ONE, which is the second instance of the
            // bug that cost the first run: a lookup keyed on "whatever is on top" stops finding the
            // thing it wants the moment anything opens over it. Nothing opens over the diagram in
            // THIS screen today, so `getTopMostPanel()` happened to be right -- which is exactly
            // the kind of accident that turns into a fifty-minute timeout later. `getPanel()` walks
            // up from the widget, so it cannot be wrong about which panel the canvas is in.
            ModularPanel flow = canvas.getPanel();
            List<PlannerWidgets.ClickableGroup> buttons = viewButtons(flow);
            int index = PlannerWidgets.FLOW_BUTTON_LABELS.indexOf(PlannerWidgets.JUMP_TO_ROOT);
            if (buttons.size() <= index || buttons.get(index).getArea().height <= 0) {
                return true;
            }
            Area box = buttons.get(index).getArea();
            ScreenCursor.park(box.x + box.width / 2, box.y + box.height / 2);
            if (since++ < SETTLE_POLLS) {
                return true;
            }
            boolean rootShowingBefore = canvas.fullyShowing(canvas.rootBox());
            boolean taken = screen.onMousePressed(0);
            boolean rootShowingAfter = canvas.fullyShowing(canvas.rootBox());
            ShotHarness.log("flow-nav: clicked '" + PlannerWidgets.JUMP_TO_ROOT + "'; the screen "
                            + (taken ? "took" : "did NOT take") + " it; the root was "
                            + (rootShowingBefore ? "already in frame" : "off frame")
                            + " and is now " + (rootShowingAfter ? "in frame" : "STILL off frame")
                            + "; scroll " + canvas.getScrollX() + "," + canvas.getScrollY());

            int movedX = scrollXAfterDrag - scrollXBeforeDrag;
            int movedY = scrollYAfterDrag - scrollYBeforeDrag;
            if (movedX != DRAG || movedY != DRAG) {
                // THE MAGNITUDE AND THE SIGN, not "it moved". Dragging the content up and left
                // must reveal what is down and right, which is a LARGER offset by exactly the
                // drag: an inverted or partial pan is a smooth, plausible, wrong gesture.
                wrong.add("a " + DRAG + "px drag moved the view by " + movedX + "," + movedY
                          + " and must move it by " + DRAG + "," + DRAG);
            }
            if (rootShowingBefore) {
                // ASSERTED HERE AND NOT AT THE END, because by the end the view has moved twice.
                // This is the reason `ROOT_JUMP` runs before `NAVIGATE`: the pan that finds a
                // measurable pair can put the root back in frame, and then this reads as a drag
                // that never left it. Measured on rerun 3.
                wrong.add("the drag left the root in frame, so the jump proves nothing");
            }
            if (!rootShowingAfter) {
                wrong.add("'" + PlannerWidgets.JUMP_TO_ROOT + "' did not bring the root wholly"
                          + " into frame");
            }
            step = NAVIGATE;
            since = 0;
            return true;
        }

        /**
         * One pass or fail for the whole gesture, after every measurement has been taken. #330.
         *
         * ITS OWN STEP BECAUSE THE TWO PROOFS NO LONGER SHARE A VIEWPORT. The drag and the Root
         * jump are asserted where they happen -- they have to be, the view moves afterwards -- and
         * the #330 findings are added here, so this is the one place that reports. A hold that
         * reported twice would have the second report overwrite the first.
         *
         * THE CAPTURE LANDS AFTER THIS, so the PNG shows the MEASURED-PAIR view: the diagram
         * panned to the parent and child the adjacency probe measured, not the drag state and
         * not the root. That is a deliberate consequence of the ordering -- it shows the state the
         * numbers in this verdict are about. `w-edges-nav.png` from rerun 2 remains the artifact
         * for the drag state.
         */
        private boolean reportTheVerdict() {
            if (arrivalHovered != edgeChild) {
                // THE FRAMEWORK'S OWN ANSWER. An edge painted in LAYOUT coordinates puts this
                // pixel where the diagram would be at scroll 0, which at the non-zero offset
                // `navigateToAMeasurablePair` insists on is somewhere else entirely -- so
                // ModularUI reports the node that is really there, a different one or none.
                wrong.add("the edge into box " + edgeChild + " arrives at " + arrivalX + ","
                          + arrivalY + ", where ModularUI says box " + arrivalHovered + " is"
                          + " (child rect " + java.util.Arrays.toString(childRect) + ") (#330)");
            }
            if (arrivalLayoutBox != edgeChild) {
                wrong.add("and the layout puts box " + arrivalLayoutBox + " under that pixel"
                          + " rather than " + edgeChild + " (#330)");
            }
            if (departureHovered != edgeParent) {
                // BOTH ENDS, because they fail differently: a sweep that converted the origin
                // and not the extent, or scaled the width as well as the position, gets one of
                // the two right and would pass a one-ended check.
                wrong.add("the edge out of box " + edgeParent + " leaves " + departureX + ","
                          + departureY + ", where ModularUI says box " + departureHovered + " is"
                          + " (parent rect " + java.util.Arrays.toString(parentRect) + ") (#330)");
            }
            if (departureLayoutBox != edgeParent) {
                wrong.add("and the layout puts box " + departureLayoutBox + " under that pixel"
                          + " rather than " + edgeParent + " (#330)");
            }
            if (childRect != null && Math.abs(arrivalY - (childRect[1] + childRect[3] / 2)) > 1) {
                // THE MIDDLE OF THE NODE, NOT ITS TOP. An elbow drawn at `box.y` touches the
                // frame rather than the node and reads as a rounding error rather than as a
                // missing conversion.
                wrong.add("the edge arrives at y " + arrivalY + " and the child's centre is "
                          + (childRect[1] + childRect[3] / 2) + " (#330)");
            }
            if (wrong.isEmpty()) {
                ShotScreens.reportPass();
            } else {
                ShotScreens.reportFail(wrong.toString());
            }
            return false;
        }
    }

    // -- shared lookups -------------------------------------------------------------------------

    /**
     * The `FlowCanvas` on the top-most panel, or null. CALLED ONCE AND THE RESULT KEPT.
     *
     * THE TOP PANEL IS THE RIGHT PLACE TO LOOK EXACTLY ONCE -- when the diagram has just opened
     * and is therefore on top -- AND THE WRONG PLACE FROM THEN ON. This javadoc used to argue the
     * opposite, citing `PlannerShot.livePanel` as precedent, and the citation was backwards:
     * `livePanel` reads the MAIN panel so that a sub-panel opening over the planner cannot steal
     * the BUTTON lookup. For the canvas the polarity flips, because a panel opening over the
     * diagram is the thing under test -- so a fresh top-panel read on every poll stops finding the
     * canvas the instant the node menu appears. That is what stalled the first run of
     * `flow-node-menu` for fifty-two minutes. See `DriveTheDiagram.canvas`, which caches it.
     */
    private static FlowCanvas canvasIn(ModularScreen screen) {
        ModularPanel top = screen.getPanelManager().getTopMostPanel();
        return top == null ? null : firstCanvas(top);
    }

    private static FlowCanvas firstCanvas(IWidget parent) {
        if (parent instanceof FlowCanvas) {
            return (FlowCanvas) parent;
        }
        for (IWidget child : parent.getChildren()) {
            FlowCanvas found = firstCanvas(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The buttons of the first `ViewBar` under `parent`, in draw order.
     *
     * FOUND BY TYPE, which is what `PlannerWidgets.ViewBar` exists for: an index into a panel's
     * click targets is right until a third view is added, and then it starts parking the cursor on
     * a tree row while still reporting a button. The flow panel's own control row is the same type
     * for the same reason.
     */
    private static List<PlannerWidgets.ClickableGroup> viewButtons(IWidget parent) {
        List<PlannerWidgets.ClickableGroup> found =
                new ArrayList<PlannerWidgets.ClickableGroup>();
        if (parent == null) {
            return found;
        }
        PlannerWidgets.ViewBar bar = firstViewBar(parent);
        if (bar == null) {
            return found;
        }
        for (IWidget child : bar.getChildren()) {
            if (child instanceof PlannerWidgets.ClickableGroup) {
                found.add((PlannerWidgets.ClickableGroup) child);
            }
        }
        return found;
    }

    private static PlannerWidgets.ViewBar firstViewBar(IWidget parent) {
        if (parent instanceof PlannerWidgets.ViewBar) {
            return (PlannerWidgets.ViewBar) parent;
        }
        for (IWidget child : parent.getChildren()) {
            PlannerWidgets.ViewBar found = firstViewBar(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first string a panel draws, which for a menu panel is its title. "" for none. */
    private static String firstText(IWidget parent) {
        if (parent == null) {
            return "";
        }
        if (parent instanceof com.cleanroommc.modularui.widgets.TextWidget) {
            return ((com.cleanroommc.modularui.widgets.TextWidget<?>) parent)
                    .getKey().getFormatted();
        }
        for (IWidget child : parent.getChildren()) {
            String found = firstText(child);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return "";
    }

    private static boolean containsSize(Collection<Rectangle> areas, int width, int height) {
        for (Rectangle area : areas) {
            if (area.width == width && area.height == height) {
                return true;
            }
        }
        return false;
    }
}
