package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ScrollWidget;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Column;
import org.junit.Before;
import org.junit.Test;

/**
 * The in-game planner's tree panel in miniature -- a scroll viewport over a column of node
 * rows -- laid out by ModularUI's real sizer with no window and no GL context (#125).
 *
 * Why this file exists: this machine cannot run the game, so the alternative way to answer
 * "does that row fit" is a manual launch of a 410-mod pack, minutes per iteration. Most GUI
 * mistakes are layout mistakes, and layout is arithmetic, so it is answerable here. Every
 * number below came out of `WidgetTree`/`StandardResizer`; the test computes none of them.
 *
 * The expected coordinates are written as literals rather than recomputed from the size
 * constants ON PURPOSE, and the derivation goes in a comment beside them. A test that
 * recomputes the stacking with the same formula the widget uses passes whenever both are
 * wrong; these were read off a run and checked by hand against what a tree panel should look
 * like, so a ModularUI update that changes the rules has to be looked at rather than silently
 * absorbed.
 */
public class ModularUiLayoutTest {

    // Deliberately not multiples of each other: with a height of 16 and a gap of 16 an
    // off-by-one in the gap handling is invisible in the resolved coordinates.
    private static final int ROW_WIDTH = 100;
    private static final int ROW_HEIGHT = 14;
    private static final int ROW_GAP = 3;
    private static final int ROW_COUNT = 5;

    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_HEIGHT = 120;
    private static final int VIEWPORT_LEFT = 10;
    private static final int VIEWPORT_TOP = 10;
    private static final int VIEWPORT_WIDTH = 120;
    /**
     * Shorter than the content it holds ON PURPOSE: a tree panel that never overflows is not
     * exercising the case the scroll area exists for.
     */
    private static final int VIEWPORT_HEIGHT = 60;

    /** Five rows of 14 with four gaps of 3 between them. */
    private static final int CONTENT_HEIGHT = 82;

    /**
     * `ScrollWidget`'s type parameter is self-referential (`W extends ScrollWidget<W>`), so it
     * cannot be instantiated without a named subclass. This adds nothing and overrides nothing.
     */
    private static final class Viewport extends ScrollWidget<Viewport> {
        Viewport(VerticalScrollData data) {
            super(data);
        }
    }

    /** Same reason, for `ListWidget`. */
    private static final class RowList extends ListWidget<IWidget, RowList> {
    }

    /** One laid-out fixture, so the parts of it stay addressable after the pass has run. */
    private static final class TreePanel {
        final ModularPanel panel;
        final Viewport viewport;
        final Column column;
        final List<HeadlessLayout.Leaf> rows;

        TreePanel(ModularPanel panel, Viewport viewport, Column column,
                  List<HeadlessLayout.Leaf> rows) {
            this.panel = panel;
            this.viewport = viewport;
            this.column = column;
            this.rows = rows;
        }
    }

    private TreePanel tree;

    /**
     * A scroll viewport over a column of rows, laid out on a screen of the given size.
     *
     * A widget belongs to exactly one panel, so a test that wants a second screen size builds
     * a second tree rather than re-laying-out this one.
     */
    private static TreePanel treePanel(String name, int screenWidth, int screenHeight) {
        Column column = new Column();
        column.childPadding(ROW_GAP);
        // START, not the CENTER a Flow defaults to: node rows in a tree line up on their left
        // edge, and centring them would also hide the overflow case below behind an offset
        // that happens to look symmetric.
        column.crossAxisAlignment(Alignment.CrossAxis.START);
        column.width(VIEWPORT_WIDTH);
        // Without this a Flow is `sizeRel(1, 1)` -- it fills its parent, and the column would
        // simply be the viewport's height rather than its content's.
        column.coverChildrenHeight();

        List<HeadlessLayout.Leaf> rows = new ArrayList<>();
        for (int i = 0; i < ROW_COUNT; i++) {
            HeadlessLayout.Leaf row = HeadlessLayout.leaf(name + "-row" + i, ROW_WIDTH, ROW_HEIGHT);
            rows.add(row);
            column.child(row);
        }

        Viewport viewport = new Viewport(new VerticalScrollData());
        viewport.name(name + "-viewport");
        viewport.pos(VIEWPORT_LEFT, VIEWPORT_TOP);
        viewport.size(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        viewport.child(column);

        ModularPanel panel = HeadlessLayout.layOutPanelOnScreen(
                name, PANEL_WIDTH, PANEL_HEIGHT, screenWidth, screenHeight, viewport);
        return new TreePanel(panel, viewport, column, rows);
    }

    @Before
    public void layOutATreePanelInMiniature() {
        tree = treePanel("planner", HeadlessLayout.SCREEN_WIDTH, HeadlessLayout.SCREEN_HEIGHT);
    }

    /**
     * The guard on every other test in this file, and on the harness itself.
     *
     * `WidgetTree.resizeInternal` catches every Throwable and only logs it, so a sizer that
     * died leaves the boxes at their construction values and the pass returns normally. A
     * widget still sitting at zero size is exactly what that looks like.
     *
     * Not hypothetical: with the raw pack jar instead of the deobfuscated one that
     * `mod/build.gradle` now puts on the test classpath, the scroll subtree came out
     * `[0,0 0x0]` from top to bottom while the panel above it laid out perfectly. This is the
     * test that caught it.
     */
    @Test
    public void everyWidgetInTheTreeCameOutOfTheResizePassWithARealBox() {
        List<IWidget> all = HeadlessLayout.flatten(tree.panel);
        // THE COUNT FIRST, because `flatten` always yields at least the root it was handed.
        // A harness that stopped walking children -- or a `treePanel` that stopped attaching
        // them -- reduces the loop below to "the panel itself has a non-zero size", which is
        // the one widget whose size the panel constructor sets directly, so the assertion
        // this test exists for would never run and the test would still be green.
        //
        // Panel, viewport, column and ROW_COUNT rows.
        assertEquals("the flattened tree is not the shape this test walks: " + all,
                     3 + ROW_COUNT, all.size());
        for (IWidget widget : all) {
            Area area = widget.getArea();
            assertTrue(widget.getName() + " has no width: " + area, area.w() > 0);
            assertTrue(widget.getName() + " has no height: " + area, area.h() > 0);
        }
    }

    @Test
    public void thePanelIsItsDeclaredSizeAndCentredOnTheScreen() {
        Area area = tree.panel.getArea();
        assertEquals(PANEL_WIDTH, area.w());
        assertEquals(PANEL_HEIGHT, area.h());
        // A ModularPanel centres itself by default: (427 - 200) / 2 and (240 - 120) / 2. So
        // this also proves the sizer measured against the screen rectangle the harness handed
        // it rather than some default of its own.
        assertEquals(113, area.x());
        assertEquals(60, area.y());
    }

    /**
     * The screen size reaches the layout, and reaches only the panel.
     *
     * Worth pinning because it is the assumption that makes every other test in this file
     * cheap: if the contents re-flowed with the window, one screen size would prove nothing
     * about another and the planner would need measuring at several.
     */
    @Test
    public void aLargerScreenMovesThePanelAndLeavesItsContentsAlone() {
        TreePanel bigger = treePanel("bigScreen", 640, 360);
        // (640 - 200) / 2 and (360 - 120) / 2.
        assertEquals(220, bigger.panel.getArea().x());
        assertEquals(120, bigger.panel.getArea().y());
        for (int i = 0; i < ROW_COUNT; i++) {
            assertEquals("row" + i + " top", tree.rows.get(i).getArea().ry,
                         bigger.rows.get(i).getArea().ry);
            assertEquals("row" + i + " left", tree.rows.get(i).getArea().rx,
                         bigger.rows.get(i).getArea().rx);
        }
        assertEquals(CONTENT_HEIGHT, bigger.column.getArea().h());
        assertEquals(VIEWPORT_HEIGHT, bigger.viewport.getArea().h());
    }

    @Test
    public void theRowsStackTopToBottomInOrderSeparatedByTheChildPadding() {
        // 0, then each 14 high with a 3 gap after it.
        int[] expectedTops = {0, 17, 34, 51, 68};
        for (int i = 0; i < ROW_COUNT; i++) {
            Area area = tree.rows.get(i).getArea();
            assertEquals("row" + i + " top", expectedTops[i], area.ry);
            assertEquals("row" + i + " left", 0, area.rx);
            assertEquals("row" + i + " height", ROW_HEIGHT, area.h());
            // The row asked for 100 in a 120-wide column and kept 100: a Flow does not stretch
            // its children across the cross axis unless they ask to be stretched.
            assertEquals("row" + i + " width", ROW_WIDTH, area.w());
        }
    }

    @Test
    public void noTwoRowsOverlapVertically() {
        // Stated apart from the stacking test because it is the property that actually matters
        // and it survives a change of gap: each row starts at or after the previous one's
        // bottom edge.
        for (int i = 1; i < ROW_COUNT; i++) {
            Area above = tree.rows.get(i - 1).getArea();
            Area below = tree.rows.get(i).getArea();
            assertTrue("row" + i + " starts at " + below.y() + ", above ends at " + above.ey(),
                       below.y() >= above.ey());
        }
    }

    @Test
    public void theColumnGrowsToTheSummedHeightOfItsRowsIncludingTheGapsBetweenThem() {
        // The "scroll content height is the sum of the rows" claim, measured on the thing that
        // computes it: `coverChildrenHeight` on the column.
        assertEquals(CONTENT_HEIGHT, tree.column.getArea().h());
        // Cross-checked against a different pair of ModularUI outputs rather than against the
        // same sum again: top of the first row to the bottom of the last.
        assertEquals(tree.rows.get(ROW_COUNT - 1).getArea().ey() - tree.rows.get(0).getArea().y(),
                     tree.column.getArea().h());
    }

    @Test
    public void theScrollViewportKeepsItsDeclaredHeightWhileItsContentOverflows() {
        assertEquals(VIEWPORT_HEIGHT, tree.viewport.getArea().h());
        assertEquals(VIEWPORT_WIDTH, tree.viewport.getArea().w());
        // The point of the widget: content taller than the box it is shown in. If this ever
        // stops holding, the fixture has gone soft and the tests above stop covering overflow.
        assertTrue("content " + tree.column.getArea().h() + " should exceed viewport "
                   + tree.viewport.getArea().h(),
                   tree.column.getArea().h() > tree.viewport.getArea().h());
    }

    /**
     * Rows past the bottom of the viewport are still given real boxes.
     *
     * Worth pinning because Phase 3b plans to cull off-screen nodes when drawing: culling has
     * to be a DRAW-time decision, since the layout pass makes no such distinction and the
     * scroll offset is what decides which rows are on screen at any moment.
     */
    @Test
    public void rowsBelowTheFoldAreStillLaidOutRatherThanCollapsedOrSkipped() {
        Area last = tree.rows.get(ROW_COUNT - 1).getArea();
        assertTrue("last row top " + last.y() + " should be past the viewport bottom "
                   + tree.viewport.getArea().ey(), last.y() > tree.viewport.getArea().ey());
        assertEquals(ROW_HEIGHT, last.h());
    }

    @Test
    public void anAbsoluteBoxIsAlwaysItsParentsOriginPlusItsOwnRelativeOffset() {
        // `Area` carries both, and everything that hit-tests or draws reads the absolute pair,
        // so a relative offset that never got applied is a real and otherwise invisible bug.
        assertEquals(tree.panel.getArea().x() + VIEWPORT_LEFT, tree.viewport.getArea().x());
        assertEquals(tree.panel.getArea().y() + VIEWPORT_TOP, tree.viewport.getArea().y());
        for (HeadlessLayout.Leaf row : tree.rows) {
            assertEquals(tree.column.getArea().x() + row.getArea().rx, row.getArea().x());
            assertEquals(tree.column.getArea().y() + row.getArea().ry, row.getArea().y());
        }
    }

    /**
     * A child wider than the column it sits in.
     *
     * ModularUI neither clamps nor clips during layout: the row keeps the width it asked for
     * and extends past its parent's right edge. That matters for the planner because a long
     * registry id in a node row will do exactly this, and NOTHING in the layout pass reports
     * it -- the overflow is only visible as a box that sticks out, which is what this reads.
     * So a node row has to be given a width it can honour, or wrapped in something that
     * truncates; hoping the parent will contain it is not a strategy.
     */
    @Test
    public void aRowWiderThanItsColumnKeepsItsOwnWidthAndOverflowsToTheRight() {
        Column narrow = new Column();
        narrow.crossAxisAlignment(Alignment.CrossAxis.START);
        narrow.width(VIEWPORT_WIDTH);
        narrow.coverChildrenHeight();
        HeadlessLayout.Leaf tooWide =
                HeadlessLayout.leaf("tooWide", VIEWPORT_WIDTH * 2, ROW_HEIGHT);
        narrow.child(tooWide);
        HeadlessLayout.layOutPanel("overflow", PANEL_WIDTH, PANEL_HEIGHT, narrow);

        assertEquals(VIEWPORT_WIDTH, narrow.getArea().w());
        assertEquals(VIEWPORT_WIDTH * 2, tooWide.getArea().w());
        assertEquals(narrow.getArea().ex() + VIEWPORT_WIDTH, tooWide.getArea().ex());
    }

    /**
     * `ScrollWidget` is a viewport and nothing more: it never tells its `ScrollData` how tall
     * the content is, so its scrollbar has no extent and cannot activate. `ListWidget` is the
     * widget that does -- it is `AbstractScrollWidget` plus its own column layout, and its
     * `layoutWidgets` is the only caller of `setScrollSize` in the whole jar bar `Grid` and
     * the text field.
     *
     * Recorded as a test rather than a comment because #19's Phase 3 says "a `ScrollWidget`
     * over a `Column` of node rows", and that tree lays out correctly while silently having a
     * dead scrollbar. The tree panel wants `ListWidget`.
     */
    @Test
    public void onlyAListWidgetPublishesTheSummedRowHeightAsItsScrollableContentSize() {
        RowList list = new RowList();
        list.name("rowList");
        list.pos(VIEWPORT_LEFT, VIEWPORT_TOP);
        list.size(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        List<HeadlessLayout.Leaf> listRows = new ArrayList<>();
        for (int i = 0; i < ROW_COUNT; i++) {
            HeadlessLayout.Leaf row = HeadlessLayout.leaf("listRow" + i, ROW_WIDTH, ROW_HEIGHT);
            listRows.add(row);
            list.child(row);
        }
        HeadlessLayout.layOutPanel("list", PANEL_WIDTH, PANEL_HEIGHT, list);

        assertNotNull("a ListWidget creates its own vertical scroll data during init",
                      list.getScrollData());
        // No child padding was asked for here, so the content is five rows of 14 and nothing
        // between them.
        assertEquals(70, list.getScrollData().getScrollSize());
        int[] expectedTops = {0, 14, 28, 42, 56};
        for (int i = 0; i < ROW_COUNT; i++) {
            assertEquals("listRow" + i + " top", expectedTops[i], listRows.get(i).getArea().ry);
        }
        // The viewport itself does not grow to swallow the content.
        assertEquals(VIEWPORT_HEIGHT, list.getArea().h());
        // And the plain ScrollWidget from the main fixture, with the same overflow, reports
        // nothing at all. This is the half of the claim that is easy to miss.
        assertEquals(0, tree.viewport.getScrollArea().getScrollY().getScrollSize());
    }
}
