package io.github.jacoblasky.recipedump.client.planner;

import com.cleanroommc.modularui.api.widget.IWidget;

/**
 * The flow diagram, as the one package that may not name it sees it. #325/#327.
 *
 * WHY IT IS A WIDGET PLUS TWO VERBS RATHER THAN A BARE `IWidget`. {@link PlannerWidgets#flowPanel}
 * draws the chrome around the diagram, and two of the controls in that chrome -- jump to the root,
 * jump to the selected item -- are commands to the diagram itself. Handing the panel an `IWidget`
 * and casting it back to `client.flow.FlowCanvas` would invert the dependency this package spends
 * three comments defending: `flow` names `planner`, never the reverse. See {@link FlowSource},
 * which is the same argument about construction rather than about control.
 *
 * IT EXTENDS `IWidget` SO THE PANEL CAN STILL ADD IT AS A CHILD without a second parameter for
 * the same object. A `flowPanel(plan, widget, diagram, actions)` taking the canvas twice is a
 * signature in which the two arguments can disagree, and nothing would notice.
 *
 * NAVIGATION IS ABOUT THE KEY AND NOT ABOUT THE OCCURRENCE, which is the distinction
 * {@link PlanSelection}'s class note draws and the reason {@link #jumpToSelected} answers a
 * boolean rather than a node. Moving a viewport is not acting on a quantity, so the wrong-
 * occurrence hazard that note is about does not arise here -- and the implementation must not
 * quietly become a way to reach one, because `PlanSelection.selectedNode` is the only thing that
 * knows which occurrence was clicked.
 */
public interface Diagram extends IWidget {

    /**
     * Put the plan's root box in frame.
     *
     * THE ANSWER TO A PANNED-AWAY CANVAS, which on a real plan is most of it: `plan-fluid-chain`
     * is 634 nodes and around seventy thousand layout pixels tall, so a reader who has scrolled
     * has no way back short of closing the window. `panToBox(rootBox())` has existed, tested,
     * since #293 and had no caller outside the harness.
     */
    void jumpToRoot();

    /**
     * Put the selected item in frame. False when nothing is selected, or when the selected key
     * is not in this diagram at all.
     *
     * THE FALSE CASE IS REAL RATHER THAN DEFENSIVE. A selection survives the panel it was made
     * on -- `PlanSelection` is a static holder and the diagram is opened from a plan the tree was
     * already showing -- so a key selected in a tree and then re-solved away is exactly the state
     * `PlannerScreen.clearSelectionIfPlanChanged` exists to bound. A button that silently did
     * nothing there would read as a broken button.
     */
    boolean jumpToSelected();
}
