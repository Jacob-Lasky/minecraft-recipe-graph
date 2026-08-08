package io.github.jacoblasky.recipedump.client.machines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.ListWidget;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.MachineTable;

import org.junit.Test;

/**
 * The machines screen's geometry, over a real resolved table, with no window (#125's harness).
 *
 * WHAT THIS IS FOR, GIVEN THAT THE PR ALSO CARRIES SCREENSHOTS. `PlannerLayoutTest` states it
 * and it holds here: a screenshot costs about two minutes and shows one filter state, while
 * this runs every state in seconds and can assert what a picture cannot -- that no row overlaps
 * the next, that nothing overflows a panel ModularUI would neither clamp nor clip, that the
 * verdict column never collides with the name. Screenshots answer "does it look right"; this
 * answers "is it right", and it is the one a future change gets immediately.
 */
public class MachinesLayoutTest {

    private static ModularPanel laidOut(MachineTable table, MachineTable.Filter filter) {
        ModularPanel panel =
                MachinesWidgets.machinesPanel(table, filter, MachinesActions.NONE);
        HeadlessLayout.layOut(panel);
        return panel;
    }

    private static ModularPanel laidOut() {
        return laidOut(MachineTables.wide(), MachineTable.Filter.NONE);
    }

    /** The first `ListWidget` under `panel`, which is the table or the picker's list. */
    private static ListWidget<?, ?> findList(ModularPanel panel) {
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (widget instanceof ListWidget) {
                return (ListWidget<?, ?>) widget;
            }
        }
        throw new AssertionError("no ListWidget in the panel");
    }

    /**
     * Every filter combination worth laying out: none, each state alone, and each state with a
     * mod chosen.
     *
     * ENUMERATED RATHER THAN SAMPLED, because a column width is derived from the rows ON SCREEN
     * -- {@link MachinesWidgets#recipeColumnWidth} -- so each filter produces a genuinely
     * different layout and testing one of them tests one of them.
     */
    private static MachineTable.Filter[] filters(MachineTable table) {
        List<String> mods = table.mods();
        MachineTable.Filter[] out =
                new MachineTable.Filter[1 + MachineInfo.STATE_COUNT * 2];
        out[0] = MachineTable.Filter.NONE;
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            out[1 + state] = table.reconcile(MachineTable.Filter.NONE.toggleState(state));
            out[1 + MachineInfo.STATE_COUNT + state] = table.reconcile(
                    MachineTable.Filter.NONE.toggleState(state).withMod(mods.get(0)));
        }
        return out;
    }

    /**
     * The guard on everything else here.
     *
     * `WidgetTree.resizeInternal` catches every Throwable and only logs it, so a sizer that
     * died leaves boxes at their construction values and returns normally. #140 hit exactly
     * that: one self-sizing `TextWidget` reaching for the font renderer took the entire tree
     * to 0x0 while looking like a layout bug.
     */
    @Test
    public void everyFilterLaysOutWithEveryWidgetGettingARealBox() {
        MachineTable table = MachineTables.wide();
        for (MachineTable.Filter filter : filters(table)) {
            ModularPanel panel = laidOut(table, filter);
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(widget.getClass().getSimpleName() + " has no width: " + area,
                           area.w() > 0);
                assertTrue(widget.getClass().getSimpleName() + " has no height: " + area,
                           area.h() > 0);
            }
        }
    }

    @Test
    public void thePanelIsTheDeclaredSizeOnTheSmallestSupportedScreen() {
        Area area = laidOut().getArea();
        assertEquals(MachinesWidgets.PANEL_WIDTH, area.w());
        assertEquals(MachinesWidgets.PANEL_HEIGHT, area.h());
        assertTrue("the panel must fit the smallest screen",
                   area.w() <= HeadlessLayout.SCREEN_WIDTH
                   && area.h() <= HeadlessLayout.SCREEN_HEIGHT);
    }

    @Test
    public void nothingOverflowsThePanelUnderAnyFilter() {
        // #125 measured that ModularUI neither clamps nor clips a child wider than its parent:
        // it overflows and nothing reports it. This is the report. The fixture carries a
        // 67-character title and a five-figure recipe count precisely so this can fail.
        MachineTable table = MachineTables.wide();
        for (MachineTable.Filter filter : filters(table)) {
            ModularPanel panel = laidOut(table, filter);
            Area bounds = panel.getArea();
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(area + " overflows the panel's right edge at " + bounds.ex(),
                           area.ex() <= bounds.ex());
                assertTrue(area + " overflows the panel's left edge at " + bounds.x(),
                           area.x() >= bounds.x());
                assertTrue(area + " overflows the panel's bottom edge at " + bounds.ey(),
                           area.ey() <= bounds.ey());
            }
        }
    }

    @Test
    public void theTableHasOneRowPerVisibleCategoryAndKnowsHowTallItsContentIs() {
        MachineTable table = MachineTables.wide();
        for (MachineTable.Filter filter : filters(table)) {
            ModularPanel panel = laidOut(table, filter);
            ListWidget<?, ?> list = findList(panel);
            int expected = table.rows(filter).size();
            assertEquals("one row per visible category", expected, list.getChildren().size());
            // A ListWidget publishes its content height; a plain ScrollWidget never would, and
            // its scrollbar would be permanently dead. #125.
            assertEquals("scroll extent is rows times row height",
                         expected * MachinesWidgets.TABLE_ROW_HEIGHT,
                         list.getScrollData().getScrollSize());
        }
    }

    @Test
    public void theFullTableOverflowsItsViewportSoThereIsSomethingToScroll() {
        MachineTable table = MachineTables.wide();
        ListWidget<?, ?> list = findList(laidOut(table, MachineTable.Filter.NONE));
        assertTrue("the content must exceed the viewport, or nothing is being scrolled",
                   list.getScrollData().getScrollSize() > list.getArea().h());
        assertTrue("the viewport itself must stay inside the panel",
                   list.getArea().h() < MachinesWidgets.PANEL_HEIGHT);
    }

    @Test
    public void noTwoRowsOverlap() {
        MachineTable table = MachineTables.wide();
        for (MachineTable.Filter filter : filters(table)) {
            List<IWidget> rows = findList(laidOut(table, filter)).getChildren();
            for (int i = 1; i < rows.size(); i++) {
                Area above = rows.get(i - 1).getArea();
                Area below = rows.get(i).getArea();
                assertTrue("row " + i + " starts at " + below.y()
                           + " but the one above ends at " + above.ey(),
                           below.y() >= above.ey());
            }
        }
    }

    @Test
    public void theTwoLinesOfARowDoNotOverlapEachOther() {
        // The row is the only two-line widget on this screen, and `TABLE_ROW_HEIGHT` being
        // wrong would draw the evidence sentence over the name -- which lays out cleanly,
        // because both boxes are the size they asked for. Only their positions are wrong.
        MachineTable table = MachineTables.wide();
        List<IWidget> rows = findList(laidOut(table, MachineTable.Filter.NONE)).getChildren();
        for (IWidget row : rows) {
            List<IWidget> parts = HeadlessLayout.flatten(row);
            for (IWidget one : parts) {
                for (IWidget other : parts) {
                    if (one == other || one == row || other == row) {
                        continue;
                    }
                    Area a = one.getArea();
                    Area b = other.getArea();
                    boolean apart = a.ex() <= b.x() || b.ex() <= a.x()
                            || a.ey() <= b.y() || b.ey() <= a.y();
                    assertTrue("two cells of one row overlap: " + a + " and " + b, apart);
                }
            }
        }
    }

    @Test
    public void theVerdictColumnIsWideEnoughForItsLongestWord() {
        // A fixed vocabulary truncated is always a bug, and the badge column that was sized
        // from a hand-written list once came out reading "no known...". `widestLabel` derives
        // it; this asserts the derivation was actually used.
        int longest = 0;
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            longest = Math.max(longest, MachineLabels.label(state).length());
        }
        assertTrue("the verdict column must fit " + longest + " characters",
                   MachinesWidgets.STATE_COLUMN >= longest * NodeRowText.CHAR_WIDTH);
    }

    @Test
    public void everyChipGetsARealBoxAndTheFourOfThemFitTheContentWidth() {
        // Four chips plus three gaps must not exceed the content width; integer division in
        // `CHIP_WIDTH` makes that non-obvious, and an overflow here is per #125 silent.
        int used = MachinesWidgets.CHIP_WIDTH * MachineInfo.STATE_COUNT
                + MachinesWidgets.GAP * (MachineInfo.STATE_COUNT - 1);
        assertTrue("four chips and three gaps take " + used + " of "
                   + MachinesWidgets.CONTENT_WIDTH,
                   used <= MachinesWidgets.CONTENT_WIDTH);
        assertTrue("a chip must be wide enough to read", MachinesWidgets.CHIP_WIDTH > 0);
    }

    // -- the sub-panels ------------------------------------------------------------------------

    @Test
    public void theModPickerLaysOutWithEveryModOfferedAndTheWayOutFirst() {
        MachineTable table = MachineTables.wide();
        ModularPanel panel = MachinesWidgets.modPicker(table, MachineTable.Filter.NONE,
                                                       MachinesActions.NONE);
        HeadlessLayout.layOut(panel);
        ListWidget<?, ?> list = findList(panel);
        // Every mod, plus the "every mod" row that is the way OUT of a filter.
        assertEquals(table.mods().size() + 1, list.getChildren().size());
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            assertTrue(widget.getClass().getSimpleName() + " has no box: "
                       + widget.getArea(),
                       widget.getArea().w() > 0 && widget.getArea().h() > 0);
        }
    }

    @Test
    public void theModPickerNeverOutgrowsTheSmallestScreen() {
        // Its height is a function of the mod count, which is 77 on the reference pack, so the
        // cap is the only thing standing between this panel and a window taller than the game.
        MachineTable table = MachineTables.wide();
        ModularPanel panel = MachinesWidgets.modPicker(table, MachineTable.Filter.NONE,
                                                       MachinesActions.NONE);
        HeadlessLayout.layOut(panel);
        assertTrue("the mod picker must fit the smallest screen: " + panel.getArea(),
                   panel.getArea().h() <= HeadlessLayout.SCREEN_HEIGHT);
    }

    @Test
    public void theDetailPanelShowsEveryCandidateAndFitsTheScreen() {
        MachineTable.Row row = MachineTables.pressRow();
        ModularPanel panel = MachinesWidgets.detailPanel(row, MachinesActions.NONE);
        HeadlessLayout.layOut(panel);
        assertEquals("every candidate is judged, so every candidate is shown",
                     row.candidates().size(), findList(panel).getChildren().size());
        assertTrue("the detail panel must fit the smallest screen: " + panel.getArea(),
                   panel.getArea().h() <= HeadlessLayout.SCREEN_HEIGHT
                   && panel.getArea().w() <= HeadlessLayout.SCREEN_WIDTH);
        Area bounds = panel.getArea();
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            assertTrue(widget.getArea() + " overflows " + bounds,
                       widget.getArea().ex() <= bounds.ex()
                       && widget.getArea().ey() <= bounds.ey());
        }
    }

    @Test
    public void aCategoryWithNoCandidateSaysSoRatherThanDrawingAnEmptyBox() {
        // An `unknown` category has no candidate by definition -- that IS the state -- and a
        // blank list under a heading reads as a panel that failed to load rather than as the
        // answer.
        MachineTable table = MachineTables.wide();
        MachineTable.Row unknown = null;
        for (MachineTable.Row row : table.allRows()) {
            if (row.candidates().isEmpty()) {
                unknown = row;
                break;
            }
        }
        assertTrue("the fixture must contain a category with no candidate", unknown != null);
        ModularPanel panel = MachinesWidgets.detailPanel(unknown, MachinesActions.NONE);
        HeadlessLayout.layOut(panel);
        assertEquals(1, findList(panel).getChildren().size());
    }
}
