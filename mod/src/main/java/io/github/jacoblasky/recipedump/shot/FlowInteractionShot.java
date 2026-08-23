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
 * NOT GATED ON `IWidget.isHovering()`, which `PlannerShot.ClickViewButton` measured unreliable for
 * a widget in a plain `ParentWidget`: parked dead centre on a laid-out button box, that flag never
 * set across forty polls. The diagram's nodes sit inside a `FlowCanvas`, which IS an `IViewport`,
 * so `flow-hit` gets a true answer from the same flag -- but the verdicts below do not need it,
 * because a click that landed on the wrong thing opens the wrong panel and fails on the name.
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

    private static final class DragThenJump extends DriveTheDiagram {

        private int[] anchor;
        private int scrollXBeforeDrag;
        private int scrollYBeforeDrag;
        private int scrollXAfterDrag;
        private int scrollYAfterDrag;
        private int step;
        private int since;

        /** How far the probe drags. Well past `FlowCanvas`'s threshold, and inside the canvas. */
        private static final int DRAG = 60;

        @Override
        boolean drive(ModularScreen screen, FlowCanvas canvas, int poll) {
            if (step == 0) {
                return pressOnEmptyCanvas(screen, canvas);
            }
            if (step == 1) {
                return dragAndRelease(screen, canvas);
            }
            return jumpBackToTheRoot(screen, canvas);
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
            step = 1;
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
            step = 2;
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

            List<String> wrong = new ArrayList<String>();
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
                wrong.add("the drag left the root in frame, so the jump below proves nothing");
            }
            if (!rootShowingAfter) {
                wrong.add("'" + PlannerWidgets.JUMP_TO_ROOT + "' did not bring the root wholly"
                          + " into frame");
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
