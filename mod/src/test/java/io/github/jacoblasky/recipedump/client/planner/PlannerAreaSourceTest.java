package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.cleanroommc.modularui.screen.ModularPanel;

import io.github.jacoblasky.recipedump.ClassFiles;
import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.jei.PlannerGuiHandler;
import io.github.jacoblasky.recipedump.client.jei.PlannerHooks;

/**
 * What JEI is told to lay out around. #328.
 *
 * READ THROUGH `PlannerGuiHandler.getGuiExtraAreas()` WHERE IT CAN BE, because that is the surface
 * JEI actually calls. `install` returns void on purpose -- the seam is a static on `PlannerHooks`
 * -- and widening it so a test could keep a reference would be testing a different object than the
 * one JEI reaches.
 *
 * TWO PANELS ON ONE SCREEN IS NOT REACHABLE HEADLESSLY, AND THAT IS MEASURED RATHER THAN ASSUMED.
 * `PanelManager.openPanel` calls `ModularPanel.onOpen`, which calls `shouldAnimate()`, which asks
 * `Loader.isModLoaded` about NeverEnoughAnimations -- and with no Forge `Loader` that throws
 * NullPointerException out of `ModularUI$Mods.isLoaded`. So this file splits the question the way
 * the boundary really falls: the MAPPING from panels to rectangles is driven with two real,
 * separately laid-out panels, and the ENUMERATION -- that production asks the screen for its open
 * panels rather than reporting only the one it holds -- is asserted from the class's own constant
 * pool. Neither half is worth much alone; the pair is what #328 needs.
 */
public class PlannerAreaSourceTest {

    @After
    public void clearTheSeam() {
        // A static seam left installed leaks into every other test in this JVM, and JeiBridgeTest
        // asserts the UNWIRED default -- which would then be whatever this test left behind.
        PlannerHooks.setAreaSource(null);
    }

    /**
     * THE WITNESS: with the diagram open, JEI is told to avoid the diagram as well as the planner.
     *
     * WHAT MASTER DOES, measured by reverting `occupiedAreas` to `singletonList(panel.getArea())`:
     * one rectangle, the planner's, and the 620x380 flow panel is not in the list. JEI then lays
     * out around the 400-wide planner and draws its item list over roughly the rightmost 110 GUI
     * pixels of the diagram -- which is where `FlowLayout`'s deepest columns land -- and the player
     * who opens the diagram almost always has JEI open, because `=` on a JEI item is how the
     * planner is reached.
     *
     * THE ASSERTION IS THE AREA AND NOT THE COUNT. Two rectangles is satisfied by two copies of
     * the planner's, so the flow panel's own 620x380 box has to be found; and the planner's has to
     * still be there, which is the half a "report the top-most panel" fix would have got wrong -- a
     * 200-wide node menu does not cover a 400-wide tree.
     *
     * LAID OUT ON SEPARATE SCREENS, which is the concession to the headless limit above and is
     * harmless to the question: `rectanglesFor` reads each panel's own resolved `Area` and knows
     * nothing about which screen produced it.
     */
    @Test
    public void everyOpenPanelIsReportedAndNotJustThePlannersOwn() {
        ModularPanel planner = ModularPanel.defaultPanel("mcrecipedump_planner",
                                                         PlannerWidgets.PANEL_WIDTH,
                                                         PlannerWidgets.PANEL_HEIGHT);
        ModularPanel flow = ModularPanel.defaultPanel("mcrecipedump_flow",
                                                      PlannerWidgets.FLOW_WIDTH,
                                                      PlannerWidgets.FLOW_HEIGHT);
        HeadlessLayout.layOut(planner);
        HeadlessLayout.layOut(flow);

        List<Rectangle> both = PlannerAreaSource.rectanglesFor(Arrays.asList(planner, flow));
        assertTrue("the diagram must be reported: it is " + PlannerWidgets.FLOW_WIDTH + "x"
                   + PlannerWidgets.FLOW_HEIGHT + ", nearly the whole GUI, and it was the one "
                   + "panel JEI was never told about -- got " + both,
                   hasSize(both, PlannerWidgets.FLOW_WIDTH, PlannerWidgets.FLOW_HEIGHT));
        assertTrue("and the planner's own registration must not have been clobbered -- a "
                   + "sub-panel does not cover the tree beneath it: got " + both,
                   hasSize(both, PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT));
        assertEquals("two panels, two rectangles", 2, both.size());
    }

    /**
     * A panel that has not been laid out is skipped rather than reported as a point.
     *
     * THERE IS A FRAME BETWEEN OPENING AND THE FIRST RESIZE, so a 0x0 rectangle is a real state
     * rather than an impossible one -- and asking JEI to avoid a point is asking it to reflow for
     * nothing. Pre-#328 behaviour, kept, and asserted here because the rewrite moved the check into
     * a loop where dropping it would have been invisible.
     */
    @Test
    public void aPanelWithNoLayoutYetIsSkippedRatherThanReportedAsAPoint() {
        ModularPanel laidOut = ModularPanel.defaultPanel("mcrecipedump_planner", 400, 220);
        HeadlessLayout.layOut(laidOut);
        ModularPanel notYet = ModularPanel.defaultPanel("mcrecipedump_flow", 620, 380);

        List<Rectangle> areas =
                PlannerAreaSource.rectanglesFor(Arrays.asList(laidOut, notYet));
        assertEquals("the unsized panel must be dropped, not reported at 0x0: " + areas,
                     1, areas.size());
        assertTrue(hasSize(areas, 400, 220));
        assertTrue("the control: an unsized panel really is 0x0, so this test is exercising the "
                   + "skip rather than a panel that happened to be sized",
                   notYet.getArea().w() <= 0 || notYet.getArea().h() <= 0);
    }

    /**
     * The end-to-end path, as far as one panel takes it: install, and JEI is told about it.
     *
     * THROUGH `PlannerGuiHandler` BECAUSE THAT IS WHAT JEI CALLS. The tests above drive
     * `rectanglesFor` directly, which says nothing about whether anything reaches it; this one goes
     * `install` -> `PlannerHooks` -> `PlannerGuiHandler.getGuiExtraAreas()`, which is the whole
     * chain minus the multi-panel enumeration that no headless test can reach.
     */
    @Test
    public void installingThePlannerIsWhatJeiEndsUpAvoiding() {
        ModularPanel planner = ModularPanel.defaultPanel("mcrecipedump_planner",
                                                         PlannerWidgets.PANEL_WIDTH,
                                                         PlannerWidgets.PANEL_HEIGHT);
        HeadlessLayout.layOut(planner);
        PlannerAreaSource.install(planner);

        Collection<Rectangle> areas = new PlannerGuiHandler().getGuiExtraAreas();
        assertEquals("one panel open, one rectangle", 1, areas.size());
        assertTrue("and it is the planner's", hasSize(areas, PlannerWidgets.PANEL_WIDTH,
                                                     PlannerWidgets.PANEL_HEIGHT));
    }

    /**
     * A closed planner is avoided by nothing, which is what makes the whole design safe.
     *
     * JEI ASKS EVERY FRAME ON EVERY SCREEN, so a source that kept answering after its panel went
     * away would have JEI permanently avoiding a rectangle where nothing is drawn -- and a screen
     * can go away for reasons the planner never hears about, which is why this asks the panel
     * rather than being deregistered. Unchanged by #328 and asserted here because #328 added a
     * second way to answer: reaching for `panel.getScreen()` before the open check would report
     * every panel of a screen the planner is no longer on.
     */
    @Test
    public void aClosedPlannerIsAvoidedByNothing() {
        ModularPanel planner = ModularPanel.defaultPanel("mcrecipedump_planner", 400, 220);
        PlannerAreaSource.install(planner);
        assertTrue(new PlannerGuiHandler().getGuiExtraAreas().isEmpty());
    }

    /**
     * THE OTHER HALF: production asks the SCREEN what is open, not just the panel it holds.
     *
     * WHY THIS CANNOT BE A BEHAVIOURAL TEST, in one line: two panels on one screen needs
     * `PanelManager.openPanel`, which NPEs headlessly through `shouldAnimate` -> `Loader`. So the
     * enumeration is asserted from the constant pool, the same instrument `SeamInstallationTest`
     * uses for the same reason -- and it is the assertion that would go red if someone "simplified"
     * `occupiedAreas` back to reporting the single stored panel, which is exactly the defect.
     *
     * THE CONTROL IS A CALL THIS CLASS DEFINITELY MAKES. An empty or wrongly-read reference set
     * would satisfy no `assertTrue`, so `getArea` is checked too: a scan that cannot see that is
     * looking in the wrong place and says nothing about the two absences.
     */
    @Test
    public void theSourceAsksTheScreenForEveryOpenPanel() throws Exception {
        Set<String> refs = ClassFiles.methodReferences(ClassFiles.read(
                new File(ClassFiles.classesRoot(),
                         ClassFiles.ROOT_PACKAGE + "/client/planner/PlannerAreaSource.class")));
        assertTrue("PlannerAreaSource must ask the panel for its screen, or it can only ever "
                   + "report the one panel it was installed with: " + refs,
                   references(refs, "ModularPanel.getScreen"));
        assertTrue("and it must enumerate that screen's open panels, which is #328: " + refs,
                   references(refs, "PanelManager.getOpenPanels"));
        assertTrue("the control: this scan can see a call the class certainly makes",
                   references(refs, "ModularPanel.getArea"));
    }

    private static boolean references(Set<String> refs, String suffix) {
        for (String ref : refs) {
            if (ref.contains(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSize(Collection<Rectangle> areas, int width, int height) {
        for (Rectangle area : areas) {
            if (area.width == width && area.height == height) {
                return true;
            }
        }
        return false;
    }
}
