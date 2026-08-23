package io.github.jacoblasky.recipedump.client.planner;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
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
     *
     * ONE INSTALL STILL COVERS EVERY PANEL, WHICH IS #328'S FIX. The obvious reading of "every
     * panel this opens" was a second `install` call per sub-panel -- and because this replaces,
     * that would clobber the planner's own registration and lose avoidance the moment the
     * sub-panel closed. The panel installed here is the MAIN one, and {@link #occupiedAreas} asks
     * its screen what else is open, so a sub-panel added later is covered without anyone
     * remembering to register it. That is the same argument `PlannerScreen.aSubPanelIsOpen` makes
     * for reading `PanelManager`'s count rather than keeping a list of names.
     */
    public static void install(ModularPanel panel) {
        PlannerHooks.setAreaSource(new PlannerAreaSource(panel));
    }

    /**
     * Every open panel's rectangle, not just the planner's. #328.
     *
     * WHAT WAS WRONG. This reported ONE rectangle -- the panel `install` was handed -- and
     * `PlannerScreen.avoidedByJei`'s javadoc already said the rule was "EVERY PANEL THIS OPENS,
     * not only the one with a tree in it". Every panel opened off `LivePlannerActions` is a
     * ModularUI secondary panel and reached none of that: the node menu, the row menu, the recipe
     * picker, the caveats panel, and #323's flow and TODO panels. Harmless while only the
     * screenshot harness (which has no JEI) could open the big ones; #323 made them player-
     * reachable, and the flow panel is 620x380 against every other panel's 400x220 or less --
     * about 96% of a 640x400 GUI at the default 2x scale on a 1280x800 client. So JEI laid out
     * around the 400-wide planner and drew its item list over roughly the rightmost 110 GUI pixels
     * of the diagram, which is exactly where `FlowLayout`'s deepest columns land. And the player
     * who opens the diagram almost always HAS JEI open, because `=` on a JEI item is how the
     * planner is reached.
     *
     * THE UNION AND NOT THE TOP-MOST, which the issue offered as the alternative. A sub-panel does
     * not cover the planner -- the node menu is 200 wide and the planner is 400 -- so reporting
     * only the top one would hand the tree back to JEI the instant a menu opened. Both are open
     * and both are being read.
     *
     * ASKED PER FRAME AND THEREFORE CHEAP BY CONSTRUCTION. `getOpenPanels` returns a cached view
     * that `PanelManager` only rebuilds when its panel list has actually changed (`checkDirty`), so
     * this is a walk over one to three entries with no allocation beyond the rectangles JEI is
     * given. The old version allocated one of those too.
     *
     * NO PANEL IN THIS MOD IS SCREEN-SIZED, and that matters here rather than being trivia: a
     * `fullScreenInvisible()` panel -- which is what ModularUI's own `MenuPanel` is -- would tell
     * JEI to avoid the whole screen and blank its item list.
     * `PanelReachabilityTest.noPlannerPanelIsScreenSizedBecauseJeiIsToldToAvoidThem` asserts that
     * nothing in production calls it, because the failure would be a JEI overlay that silently
     * disappears whenever the planner is open.
     */
    @Override
    public List<Rectangle> occupiedAreas() {
        if (panel == null || !panel.isOpen()) {
            return Collections.emptyList();
        }
        // `getScreen()` cannot return null here: `ModularPanel.onOpen` assigns the screen
        // before it publishes OPEN (bytecode offsets 0-2 against 85-89), so an `isOpen()` panel
        // always has one. The hazard that CAN occur is different -- `getScreen()` throws
        // IllegalStateException for a panel that is no longer valid -- and the `isOpen()` guard
        // above is what keeps this call on the safe side of it, since `valid` is set in
        // `onOpen` before OPEN too. An earlier version carried a null-screen fallback here,
        // justified by a sequence the bytecode rules out; it was dead code aimed at the wrong
        // hazard.
        return rectanglesFor(panel.getScreen().getPanelManager().getOpenPanels());
    }

    /**
     * The rectangles for a set of panels, skipping any that has not been laid out.
     *
     * SPLIT OUT SO IT CAN BE TESTED, and the split is where the headless boundary actually falls
     * rather than where it looks tidy. A JUnit JVM cannot put two panels on one screen at all:
     * `PanelManager.openPanel` calls `ModularPanel.onOpen`, which calls `shouldAnimate()`, which
     * asks `Loader.isModLoaded` about NeverEnoughAnimations and NPEs with no Forge `Loader`
     * (measured). So the ENUMERATION above is unreachable from a test and the MAPPING here is not,
     * and pretending otherwise would mean a test that could not exist. `PlannerAreaSourceTest`
     * drives this with two real, separately laid-out panels and scans this class's bytecode for the
     * `getOpenPanels` call, which is the only way to assert the half it cannot run.
     *
     * A PANEL THAT HAS NOT BEEN LAID OUT YET IS 0x0 -- there is a frame between opening and the
     * first resize. Reporting that rectangle would ask JEI to avoid a point.
     */
    static List<Rectangle> rectanglesFor(Iterable<ModularPanel> panels) {
        List<Rectangle> areas = new ArrayList<Rectangle>();
        for (ModularPanel open : panels) {
            Area area = open.getArea();
            if (area.w() <= 0 || area.h() <= 0) {
                continue;
            }
            // `Area` IS a `java.awt.Rectangle`, but a copy rather than the live object: JEI holds
            // what it is given, and the panel mutates its own area on every resize.
            areas.add(new Rectangle(area.x(), area.y(), area.w(), area.h()));
        }
        return areas;
    }
}
