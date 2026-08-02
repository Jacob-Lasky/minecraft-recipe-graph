package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.ListWidget;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.common.PlanBook;
import org.junit.Test;

/**
 * The placeholder planner window, laid out with no game, through the #125 harness.
 *
 * WHY THIS IS NOT JUST A SCREENSHOT'S JOB. `harness/shot.sh` boots a real client and takes
 * about two minutes; this takes seconds and gives a stack trace rather than a missing PNG.
 * It earned that immediately: the screen threw an NPE inside the client, and all the harness
 * could report was `opening 'planner' threw java.lang.NullPointerException` with the stack
 * swallowed by `ShotScreens`. This test reproduced it in seconds with a full stack, and named
 * the cause -- `ModularScreen`'s constructor calling `buildUI` before the subclass fields
 * existed. The screenshot is still the proof that it RENDERS; this is the proof that it is
 * BUILT correctly, and it is the one a future change gets fast.
 *
 * `buildPanel` is a plain static function of the book, which is what makes this possible at
 * all: no screen, no context, no client. That shape is not an accident -- see the comment on
 * `PlannerScreen.open` for the constructor-ordering trap that forced it.
 */
public class PlannerScreenLayoutTest {

    private static PlanBook populated() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.addFavourite("thaumadditions:vis_pod#0116bb2287a7");
        book.setTodo("fluid:water", 934_400L);
        return book;
    }

    /**
     * The panel builds at all.
     *
     * The cheapest possible assertion and the one that would have caught the NPE that cost a
     * client boot, so it is worth stating on its own rather than folding into a bigger test.
     */
    @Test
    public void theScreenBuildsAPanelForAPopulatedBook() {
        ModularPanel panel = PlannerScreen.buildPanel(populated());
        assertTrue("the panel should hold the column", panel.hasChildren());
    }

    @Test
    public void theScreenBuildsAPanelForAnEmptyBookToo() {
        // The common case on a first login, and the one where a row loop over nothing is
        // most likely to have been written carelessly.
        ModularPanel panel = PlannerScreen.buildPanel(new PlanBook());
        assertTrue(panel.hasChildren());
    }

    @Test
    public void everyWidgetInTheScreenComesOutOfTheResizePassWithARealBox() {
        // `WidgetTree.resizeInternal` catches every Throwable and only logs it, so a sizer
        // that died leaves the boxes at their construction values and returns normally.
        ModularPanel panel = laidOut(populated());
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            Area area = widget.getArea();
            assertTrue(widget.getClass().getSimpleName() + " has no width: " + area,
                       area.w() > 0);
            assertTrue(widget.getClass().getSimpleName() + " has no height: " + area,
                       area.h() > 0);
        }
    }

    @Test
    public void thePanelIsTheSizeTheScreenAsksFor() {
        Area area = laidOut(populated()).getArea();
        assertEquals(PlannerScreen.PANEL_WIDTH, area.w());
        assertEquals(PlannerScreen.PANEL_HEIGHT, area.h());
    }

    /**
     * The rows list knows how tall its content is.
     *
     * #125 measured that a plain `ScrollWidget` never calls `setScrollSize`, so it lays out
     * perfectly and its scrollbar can never activate. This screen uses `ListWidget` for that
     * reason, and this is the assertion that stops a later edit quietly swapping it back.
     */
    @Test
    public void theRowsListPublishesAScrollableContentSize() {
        ModularPanel panel = laidOut(populated());
        ListWidget<?, ?> rows = findList(panel);
        assertTrue("the list should have one row per favourite and per todo entry",
                   rows.getChildren().size() >= 3);
        assertTrue("a ListWidget must publish its content height, or the scrollbar is dead",
                   rows.getScrollData().getScrollSize() > 0);
    }

    @Test
    public void theRowsStackWithoutOverlapping() {
        List<IWidget> rows = findList(laidOut(populated())).getChildren();
        for (int i = 1; i < rows.size(); i++) {
            Area above = rows.get(i - 1).getArea();
            Area below = rows.get(i).getArea();
            assertTrue("row " + i + " starts at " + below.y() + ", above ends at " + above.ey(),
                       below.y() >= above.ey());
        }
    }

    /** Builds the tree and runs the real resize pass over it. */
    private static ModularPanel laidOut(PlanBook book) {
        ModularPanel panel = PlannerScreen.buildPanel(book);
        HeadlessLayout.layOut(panel);
        System.out.println("planner:\n" + HeadlessLayout.dump(panel));
        return panel;
    }

    private static ListWidget<?, ?> findList(IWidget root) {
        for (IWidget widget : HeadlessLayout.flatten(root)) {
            if (widget instanceof ListWidget) {
                return (ListWidget<?, ?>) widget;
            }
        }
        throw new AssertionError("no ListWidget in the planner tree");
    }
}
