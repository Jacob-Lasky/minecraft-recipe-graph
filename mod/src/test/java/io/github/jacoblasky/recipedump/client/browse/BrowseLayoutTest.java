package io.github.jacoblasky.recipedump.client.browse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.ListWidget;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.GraphFacts;
import io.github.jacoblasky.recipedump.plan.SourceTable;

import org.junit.Test;

/**
 * The two smaller browse screens' geometry, with no window (#125's harness).
 *
 * SAME ARGUMENT AS `MachinesLayoutTest`: a screenshot costs about six minutes of gate queue and
 * shows one state, while this runs every state in seconds and asserts what a picture cannot --
 * that nothing overflows a panel ModularUI would neither clamp nor clip, and that the chrome
 * around a scrolling list stays inside it.
 *
 * THE VERTICAL SWEEP EXCLUDES THE LIST'S OWN CHILDREN, which #254's review established the hard
 * way: a `ListWidget` lays its children out in CONTENT space, so rows below the fold
 * legitimately sit past the panel. Asserting otherwise fails on correct code.
 */
public class BrowseLayoutTest {

    /** Long enough to overflow every column that is derived from the data. */
    private static final String LONG_KEY =
            "mod:extremely_long_discriminated_key_variant_number_seventeen";

    /**
     * A graph with one recipe per key, all from `hei_dump`.
     *
     * EVERY IDENTIFIER IS DERIVED FROM THE INDEX rather than from a hash or a random, because a
     * fixture that differs between runs turns any failure here into a flake nobody can
     * reproduce -- and `GraphFacts` sorts by count and then by NAME, so an unstable name is
     * precisely what would make the ordering assertions intermittent.
     */
    private static RecipeGraph graph(String... keys) {
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < keys.length; i++) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:leaf"));
            b.endSlot();
            b.output(b.key(keys[i]), 1);
            b.endRecipe("r_" + i, "cat." + i, "Machine", "hei_dump", false, false);
        }
        return b.build();
    }

    private static SourceTable sources(int count, boolean longKeys) {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = longKeys && i == 0 ? LONG_KEY : "mod:free_" + i;
        }
        RecipeGraph g = graph(keys);
        Map<Integer, String> free = new LinkedHashMap<Integer, String>();
        for (String key : keys) {
            free.put(Integer.valueOf(g.keyId(key)),
                     "placed: mod:a_generator_with_a_fairly_long_block_id_" + key.length());
        }
        return SourceTable.of(g, free);
    }

    private static ModularPanel laidOutSources(SourceTable table) {
        ModularPanel panel = SourcesWidgets.sourcesPanel(table, BrowseActions.NONE);
        HeadlessLayout.layOut(panel);
        return panel;
    }

    private static ModularPanel laidOutGraph(GraphFacts facts, String path) {
        ModularPanel panel = GraphWidgets.graphPanel(facts, path, BrowseActions.NONE);
        HeadlessLayout.layOut(panel);
        return panel;
    }

    private static ListWidget<?, ?> findList(ModularPanel panel) {
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (widget instanceof ListWidget) {
                return (ListWidget<?, ?>) widget;
            }
        }
        throw new AssertionError("no ListWidget in the panel");
    }

    private static void assertNothingOverflowsSideways(ModularPanel panel) {
        Area bounds = panel.getArea();
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            Area area = widget.getArea();
            assertTrue(area + " overflows the right edge at " + bounds.ex(),
                       area.ex() <= bounds.ex());
            assertTrue(area + " overflows the left edge at " + bounds.x(),
                       area.x() >= bounds.x());
        }
    }

    private static void assertChromeStaysInside(ModularPanel panel) {
        Area bounds = panel.getArea();
        List<IWidget> scrolled = HeadlessLayout.flatten(findList(panel));
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (scrolled.contains(widget)) {
                continue;
            }
            Area area = widget.getArea();
            assertTrue(widget.getClass().getSimpleName() + " " + area
                       + " overflows the bottom edge at " + bounds.ey(), area.ey() <= bounds.ey());
        }
    }

    // -- free sources --------------------------------------------------------------------------

    @Test
    public void theSourcesPanelLaysOutWithEveryWidgetGettingARealBox() {
        // `WidgetTree.resizeInternal` swallows every Throwable, so a sizer that died leaves
        // boxes at their construction values and returns normally -- the whole tree at 0x0
        // while looking like a layout bug. #140.
        for (int count : new int[] {0, 1, 12, 60}) {
            ModularPanel panel = laidOutSources(sources(count, true));
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(count + " rows: " + widget.getClass().getSimpleName()
                           + " has no box: " + area, area.w() > 0 && area.h() > 0);
            }
        }
    }

    @Test
    public void theSourcesPanelIsTheDeclaredSizeAndFitsTheSmallestScreen() {
        Area area = laidOutSources(sources(12, false)).getArea();
        assertEquals(SourcesWidgets.PANEL_WIDTH, area.w());
        assertEquals(SourcesWidgets.PANEL_HEIGHT, area.h());
        assertTrue(area.w() <= HeadlessLayout.SCREEN_WIDTH
                   && area.h() <= HeadlessLayout.SCREEN_HEIGHT);
    }

    @Test
    public void nothingInTheSourcesPanelOverflowsEvenWithARunawayKey() {
        // The fixture carries a 61-character key precisely so this can fail. Per #125 the
        // overflow would otherwise be silent.
        assertNothingOverflowsSideways(laidOutSources(sources(12, true)));
        assertChromeStaysInside(laidOutSources(sources(12, true)));
    }

    @Test
    public void theSourcesListHasOneRowPerFreeThingAndKnowsItsContentHeight() {
        for (int count : new int[] {1, 12, 60}) {
            ListWidget<?, ?> list = findList(laidOutSources(sources(count, false)));
            assertEquals(count, list.getChildren().size());
            // A ListWidget publishes its content height; a plain ScrollWidget never would and
            // its scrollbar would be permanently dead. #125.
            assertEquals(count * SourcesWidgets.ROW, list.getScrollData().getScrollSize());
        }
    }

    @Test
    public void anEmptySourcesListSaysSoRatherThanDrawingNothing() {
        // With `placed` unread and no curated default matching the pack, nothing is free. That
        // is a real state, and a blank list reads as a screen that failed to load.
        ListWidget<?, ?> list = findList(laidOutSources(sources(0, false)));
        assertEquals(1, list.getChildren().size());
    }

    @Test
    public void aLongListOverflowsItsViewportSoThereIsSomethingToScroll() {
        ListWidget<?, ?> list = findList(laidOutSources(sources(60, false)));
        assertTrue("the content must exceed the viewport",
                   list.getScrollData().getScrollSize() > list.getArea().h());
        assertTrue("the viewport itself must stay inside the panel",
                   list.getArea().h() < SourcesWidgets.PANEL_HEIGHT);
    }

    @Test
    public void noTwoSourceRowsOverlap() {
        List<IWidget> rows = findList(laidOutSources(sources(12, true))).getChildren();
        for (int i = 1; i < rows.size(); i++) {
            Area above = rows.get(i - 1).getArea();
            Area below = rows.get(i).getArea();
            assertTrue("row " + i + " starts at " + below.y() + " but the one above ends at "
                       + above.ey(), below.y() >= above.ey());
        }
    }

    @Test
    public void theKeyColumnNeverEatsTheNameColumn() {
        // The cap that stops one runaway key squeezing out the column a reader scans.
        int width = SourcesWidgets.keyColumnWidth(sources(12, true).rows(),
                                                  SourcesWidgets.CONTENT_WIDTH);
        assertTrue("the key column took " + width,
                   width <= SourcesWidgets.CONTENT_WIDTH - SourcesWidgets.GAP
                           - SourcesWidgets.MIN_NAME);
        assertTrue(width >= 0);
    }

    @Test
    public void anEmptyListAsksForNoKeyColumnAtAll() {
        assertEquals(0, SourcesWidgets.keyColumnWidth(sources(0, false).rows(),
                                                      SourcesWidgets.CONTENT_WIDTH));
    }

    // -- the graph screen ----------------------------------------------------------------------

    @Test
    public void theGraphPanelLaysOutWithEveryWidgetGettingARealBox() {
        GraphFacts facts = GraphFacts.of(graph("mod:a", "mod:b", "mod:c"));
        for (String path : new String[] {"", "/home/x/graph.json", LONG_PATH}) {
            ModularPanel panel = laidOutGraph(facts, path);
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(widget.getClass().getSimpleName() + " has no box: " + area,
                           area.w() > 0 && area.h() > 0);
            }
        }
    }

    /** Longer than two lines of the panel, so the wrap cap is exercised rather than assumed. */
    private static final String LONG_PATH =
            "/mnt/user/misc/games/minecraft/instances/MeatballCraft-1.12.2-hardmode-v3/"
            + "minecraft/config/mcrecipedump/graph.json";

    @Test
    public void nothingInTheGraphPanelOverflowsEvenWithARunawayPath() {
        // The path is WRAPPED rather than cut, because a truncated path is the wrong path and
        // this screen exists to be compared against a folder the player knows. The wrap is
        // capped, and this is what proves the cap holds the panel.
        GraphFacts facts = GraphFacts.of(graph("mod:a", "mod:b"));
        ModularPanel panel = laidOutGraph(facts, LONG_PATH);
        assertNothingOverflowsSideways(panel);
        assertChromeStaysInside(panel);
    }

    @Test
    public void theGraphPanelIsTheDeclaredSizeAndFitsTheSmallestScreen() {
        Area area = laidOutGraph(GraphFacts.of(graph("mod:a")), "/x/graph.json").getArea();
        assertEquals(GraphWidgets.PANEL_WIDTH, area.w());
        assertEquals(GraphWidgets.PANEL_HEIGHT, area.h());
        assertTrue(area.w() <= HeadlessLayout.SCREEN_WIDTH
                   && area.h() <= HeadlessLayout.SCREEN_HEIGHT);
    }

    @Test
    public void theGraphPanelListsOneRowPerRecipeSource() {
        GraphBuilder b = new GraphBuilder();
        String[] sources = {"hei_dump", "hei_dump", "jar_json"};
        for (int i = 0; i < sources.length; i++) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:leaf"));
            b.endSlot();
            b.output(b.key("mod:out_" + i), 1);
            b.endRecipe("r_" + i, "cat.one", "Machine", sources[i], false, false);
        }
        RecipeGraph g = b.build();
        ModularPanel panel = laidOutGraph(GraphFacts.of(g), "/x/graph.json");
        assertEquals(2, findList(panel).getChildren().size());
    }

    @Test
    public void aGraphWithNoRecipesStillDrawsRatherThanCollapsing() {
        // A truncated or wrong-format file produces exactly this, and the screen's whole job is
        // to let the reader SEE it. A panel that came back at 0x0 here would hide the one case
        // it exists for.
        ModularPanel panel = laidOutGraph(GraphFacts.of(new GraphBuilder().build()),
                                          "/x/graph.json");
        assertEquals(GraphWidgets.PANEL_HEIGHT, panel.getArea().h());
        assertEquals(0, findList(panel).getChildren().size());
        assertNothingOverflowsSideways(panel);
    }

    @Test
    public void aMissingGraphFileIsNamedRatherThanLeftBlank() {
        assertTrue(GraphWidgets.pathLine("").contains("no file"));
        assertTrue(GraphWidgets.pathLine("/x/graph.json").contains("/x/graph.json"));
    }

    @Test
    public void anUnrecordedInstanceSaysWhyRatherThanShowingAnEmptyLine() {
        // A pre-schema-5 dump records no instance. Saying the check is unavailable beats a
        // blank, which reads as "nowhere".
        String line = GraphWidgets.instanceLine(GraphFacts.of(new GraphBuilder().build()));
        assertTrue(line, line.contains("not recorded"));
    }
}
