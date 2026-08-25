package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;

import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;

/**
 * Every click target under a laid-out panel, and the one structural rule all of them obey.
 *
 * <h2>WHY THIS IS SHARED RATHER THAN A PRIVATE HELPER IN ONE TEST</h2>
 *
 * The rule below is a property of the WHOLE UI and not of the planner, because
 * {@link PlannerWidgets.ClickableGroup} is the single click target class this mod has: the
 * planner tree's rows, the view bar's buttons, the node menu, the recipe picker, the caveat
 * block, the machines table's rows, its state chips, its mod picker, its candidate rows and the
 * browse tab strip are all instances of it. #333 made that fact load-bearing -- the hover band
 * is drawn on this class, so it now lights on every one of those surfaces -- and an invariant
 * checked on three planner panels while six others are built the same way is an invariant that
 * only looks guarded. `PlannerLayoutTest`, `MachinesLayoutTest` and `BrowseLayoutTest` each own
 * the fixtures for their own panels and nothing else can build them, so the SWEEP has to be
 * callable from all three and the ASSERTION has to be written once.
 */
public final class ClickTargets {

    private ClickTargets() {
    }

    /** Every {@link PlannerWidgets.ClickableGroup} under `root`, itself included. */
    public static List<PlannerWidgets.ClickableGroup> in(IWidget root) {
        List<PlannerWidgets.ClickableGroup> found =
                new ArrayList<PlannerWidgets.ClickableGroup>();
        for (IWidget widget : HeadlessLayout.flatten(root)) {
            if (widget instanceof PlannerWidgets.ClickableGroup) {
                found.add((PlannerWidgets.ClickableGroup) widget);
            }
        }
        return found;
    }

    /**
     * No click target under `panel` sits inside another one.
     *
     * <h2>WHAT BREAKS IF ONE DOES, AND WHY #333 IS WHAT MADE IT MATTER</h2>
     *
     * The hover band is drawn from {@link PlannerWidgets.ClickableGroup#drawsAsHovered}, which
     * is `isBelowMouse()` -- and ModularUI's raw below-mouse stack contains EVERY enabled widget
     * whose box contains the cursor, ancestors included, because `IViewport.getChildrenAt` adds
     * a parent and then recurses into it. So a click target nested inside another paints the
     * wash TWICE over the same pixels and that row is visibly darker than its neighbours, with
     * every layout assertion still green. The click is ambiguous as well, which
     * {@link PlannerWidgets.ClickableGroup}'s own javadoc already forbade for its own reasons --
     * this is the same rule with a second consequence attached to it.
     *
     * THE WALK STOPS AT `panel` rather than running to the root, because a panel opened inside
     * another panel is not nesting in the sense that matters: ModularUI resolves the TOP hovered
     * panel only, so a target in a covered panel is not below the mouse at all.
     *
     * @return how many targets were examined, so a caller can prove it examined any. A panel with
     *         none is not a failure by itself -- several have none -- but a whole SUITE finding
     *         none is a sweep that has stopped reaching the widgets, which is why the count comes
     *         back rather than being asserted here.
     */
    public static int assertNoneNested(String where, IWidget panel) {
        int checked = 0;
        for (PlannerWidgets.ClickableGroup target : in(panel)) {
            checked++;
            for (IWidget up = target.getParent(); up != null; up = up.getParent()) {
                assertFalse("in " + where + ": a click target sits inside another one, so a"
                            + " cursor on the inner one paints the hover band twice over the"
                            + " same pixels and makes that row visibly darker than its"
                            + " neighbours -- and a click on it is ambiguous, which"
                            + " ClickableGroup's javadoc already forbade. Outer: " + up
                            + " inner: " + target,
                            up instanceof PlannerWidgets.ClickableGroup);
                if (up == panel) {
                    break;
                }
            }
        }
        return checked;
    }
}
