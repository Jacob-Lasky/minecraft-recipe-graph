package io.github.jacoblasky.recipedump.client.planner;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.sizer.Area;

import io.github.jacoblasky.recipedump.client.jei.PlannerHooks;

/**
 * Tells JEI where the planner is, so it lays its item list out around the panel instead of
 * over it.
 *
 * This is the planner's half of #145's `PlannerHooks.AreaSource`. JEI asks EVERY FRAME on
 * every screen, so the two things that matter are that the answer is cheap and that it is
 * empty rather than stale when the planner is not open.
 *
 * IT ASKS THE PANEL WHETHER IT IS OPEN RATHER THAN BEING DEREGISTERED. A screen can go away
 * for reasons the planner never hears about -- escape, a death, another mod opening a GUI --
 * so an explicit "clear the hook on close" has a path that misses, and the symptom would be
 * JEI permanently avoiding a rectangle where nothing is drawn. `ModularPanel.isOpen()` is the
 * authority, and it cannot be wrong.
 */
public final class PlannerAreaSource implements PlannerHooks.AreaSource {

    private final ModularPanel panel;

    private PlannerAreaSource(ModularPanel panel) {
        this.panel = panel;
    }

    /**
     * Install this panel as the one JEI should avoid.
     *
     * REPLACES rather than accumulates: only one planner is open at a time, and a list of
     * sources would need the same is-it-still-open check per entry plus a way to forget the
     * dead ones.
     */
    public static void install(ModularPanel panel) {
        PlannerHooks.setAreaSource(new PlannerAreaSource(panel));
    }

    @Override
    public List<Rectangle> occupiedAreas() {
        if (panel == null || !panel.isOpen()) {
            return Collections.emptyList();
        }
        Area area = panel.getArea();
        // A panel that has not been laid out yet is 0x0 -- there is a frame between opening
        // and the first resize. Reporting that rectangle would ask JEI to avoid a point.
        if (area.w() <= 0 || area.h() <= 0) {
            return Collections.emptyList();
        }
        // `Area` IS a `java.awt.Rectangle`, but a copy rather than the live object: JEI holds
        // what it is given, and the panel mutates its own area on every resize.
        return Collections.singletonList(
                new Rectangle(area.x(), area.y(), area.w(), area.h()));
    }
}
