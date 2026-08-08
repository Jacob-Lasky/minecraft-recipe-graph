package io.github.jacoblasky.recipedump.client.browse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.sizer.Area;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;

import org.junit.Test;

/**
 * The tab strip: that it fits, that it says where you are, and that clicking goes somewhere.
 *
 * WHY A STRIP GETS ITS OWN TEST WHEN IT IS THREE WORDS. It is the only thing on any browse
 * screen that makes the other two reachable, so a strip that silently dropped a tab -- or drew
 * one off the right edge, which per #125 nothing reports -- would leave a screen shipped and
 * unreachable. That is the failure #255 exists to prevent, one level down.
 */
public class BrowseTabsTest {

    /** Records what was asked for, so a click is assertable with no window anywhere. */
    private static final class Recorder implements BrowseActions {
        private final List<BrowseTabs.Tab> opened = new ArrayList<BrowseTabs.Tab>();

        @Override
        public void openTab(BrowseTabs.Tab tab) {
            opened.add(tab);
        }
    }

    private static ModularPanel laidOut(BrowseTabs.Tab current, BrowseActions actions) {
        PlannerWidgets.Group strip =
                BrowseTabs.strip(current, actions, PlannerWidgets.CONTENT_WIDTH);
        return HeadlessLayout.layOutPanel("tabs", PlannerWidgets.PANEL_WIDTH,
                                          PlannerWidgets.PANEL_HEIGHT, strip);
    }

    @Test
    public void everyTabHasALabelAndTheyAreAllDifferent() {
        // A duplicate label is a strip with two identical-looking destinations, which reads as
        // a rendering bug rather than as the navigation error it is.
        List<String> seen = new ArrayList<String>();
        for (BrowseTabs.Tab tab : BrowseTabs.Tab.values()) {
            assertFalse(tab.name(), tab.label().isEmpty());
            assertFalse("duplicate label " + tab.label(), seen.contains(tab.label()));
            seen.add(tab.label());
        }
        assertEquals("three screens today; update this when a fourth lands",
                     3, BrowseTabs.Tab.values().length);
    }

    @Test
    public void theWholeStripFitsTheContentWidth() {
        // THE ASSERTION THAT HAS TO FAIL WHEN A FOURTH TAB IS ADDED. `TAB_WIDTH` is derived
        // from the longest label, so a long fourth name widens EVERY tab and the row overflows
        // -- and ModularUI neither clamps nor clips (#125), so the last tab would simply be
        // drawn off the panel with nothing reporting it.
        int used = BrowseTabs.TAB_WIDTH * BrowseTabs.Tab.values().length
                + PlannerWidgets.GAP * (BrowseTabs.Tab.values().length - 1);
        assertTrue("the strip takes " + used + " of " + PlannerWidgets.CONTENT_WIDTH,
                   used <= PlannerWidgets.CONTENT_WIDTH);
    }

    @Test
    public void aTabIsWideEnoughForItsLabelPlusTheBracketsTheCurrentOneWears() {
        // The current tab is the one always on screen, so leaving the brackets out of the width
        // would make it the one that gets cut.
        int longest = 0;
        for (BrowseTabs.Tab tab : BrowseTabs.Tab.values()) {
            longest = Math.max(longest, BrowseTabs.label(tab, true).length());
        }
        assertTrue("a tab is " + BrowseTabs.TAB_WIDTH + "px for " + longest + " characters",
                   BrowseTabs.TAB_WIDTH >= longest * NodeRowText.CHAR_WIDTH);
    }

    @Test
    public void theCurrentTabIsMarkedWithBracketsAndNotOnlyWithColour() {
        // The same redundant-channel rule the state chips follow: a strip that changed only hue
        // is unreadable in greyscale, with a red-green deficiency, or in a thumbnail.
        String here = BrowseTabs.label(BrowseTabs.Tab.SOURCES, true);
        String away = BrowseTabs.label(BrowseTabs.Tab.SOURCES, false);
        assertNotEquals(here, away);
        assertTrue(here, here.startsWith("[") && here.endsWith("]"));
        assertFalse(away, away.startsWith("["));
    }

    @Test
    public void everyTabLaysOutInsideTheStripWhicheverOneIsCurrent() {
        // The geometry must not depend on which tab is lit -- otherwise a layout assertion
        // taken on one screen says nothing about the other two.
        List<String> geometries = new ArrayList<String>();
        for (BrowseTabs.Tab current : BrowseTabs.Tab.values()) {
            ModularPanel panel = laidOut(current, BrowseActions.NONE);
            StringBuilder shape = new StringBuilder();
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(widget.getClass().getSimpleName() + " has no box: " + area,
                           area.w() > 0 && area.h() > 0);
                shape.append(area.x()).append(',').append(area.w()).append(';');
            }
            geometries.add(shape.toString());
        }
        // EVERY LAYOUT IS THE SAME ONE. Collecting the boxes and comparing them is the point --
        // an earlier version of this test collected them and compared nothing, which asserted
        // only that three panels laid out at all and would have passed a strip whose tabs moved
        // depending on which was lit.
        for (String shape : geometries) {
            assertEquals("the strip's geometry must not depend on which tab is current",
                         geometries.get(0), shape);
        }
    }

    @Test
    public void clickingATabAsksForThatTab() {
        // The strip is a pure function of `current`; refusing the current tab is the LIVE
        // actions' job, so every cell here must report, including the one already showing.
        for (BrowseTabs.Tab current : BrowseTabs.Tab.values()) {
            Recorder recorder = new Recorder();
            PlannerWidgets.Group strip =
                    BrowseTabs.strip(current, recorder, PlannerWidgets.CONTENT_WIDTH);
            List<IWidget> cells = strip.getChildren();
            assertEquals(BrowseTabs.Tab.values().length, cells.size());
            for (int i = 0; i < cells.size(); i++) {
                ((PlannerWidgets.ClickableGroup) cells.get(i)).onMousePressed(0);
            }
            assertEquals("every cell reports, whichever is current",
                         BrowseTabs.Tab.values().length, recorder.opened.size());
            for (int i = 0; i < BrowseTabs.Tab.values().length; i++) {
                assertEquals(BrowseTabs.Tab.values()[i], recorder.opened.get(i));
            }
        }
    }

    @Test
    public void theLiveActionsRefuseToReopenTheTabAlreadyShowing() {
        // Reopening would throw away the reader's scroll position and, on the machines tab, the
        // filter -- for a click that visibly did nothing.
        final List<BrowseTabs.Tab> opened = new ArrayList<BrowseTabs.Tab>();
        LiveBrowseActions actions = new LiveBrowseActions(BrowseTabs.Tab.SOURCES,
                new LiveBrowseActions.Opener() {
                    @Override
                    public void open(BrowseTabs.Tab tab) {
                        opened.add(tab);
                    }
                });
        actions.openTab(BrowseTabs.Tab.SOURCES);
        assertTrue("the current tab must not reopen", opened.isEmpty());
    }
}
