package io.github.jacoblasky.recipedump.client.browse;

import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.plan.SourceTable;

import java.util.List;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ListWidget;

/**
 * What the planner will not charge you for, and why.
 *
 * THE SAME CONTRACT AS `MachinesWidgets` AND `PlannerWidgets`: no screen, no context and no
 * service reaches any method here, which is what lets the layout test build the panel and run
 * ModularUI's real sizer over it with no window. Both inherited rules still bind -- every text
 * widget states its size (a self-sizing one reaches the font renderer and takes the whole tree
 * to 0x0 headless), and columns are fixed widths because ModularUI neither clamps nor clips
 * (#125).
 *
 * READ-ONLY, AND THE MISSING HALF IS STATED ON THE SCREEN RATHER THAN OMITTED. `sources_page`
 * is mostly a write surface: add a generator, switch one off, toggle the vanilla-water
 * assumption. Those edit `source_overrides`, a persistence path the mod does not have -- the
 * same one #254 left out for machine overrides, for the same reason. The footer says where the
 * list is edited instead of offering buttons that would do nothing, because a control that
 * silently fails is worse than an absent one.
 */
public final class SourcesWidgets {

    public static final int PANEL_WIDTH = PlannerWidgets.PANEL_WIDTH;
    public static final int PANEL_HEIGHT = PlannerWidgets.PANEL_HEIGHT;
    public static final int PADDING = PlannerWidgets.PADDING;
    public static final int CONTENT_WIDTH = PlannerWidgets.CONTENT_WIDTH;
    public static final int LINE = PlannerWidgets.LINE;
    public static final int ROW_HEIGHT = PlannerWidgets.ROW_HEIGHT;
    public static final int GAP = PlannerWidgets.GAP;

    /** Two lines: the item and its key, then the evidence under it. */
    public static final int ROW = ROW_HEIGHT * 2;

    /** The evidence line is indented under the name, so the name column has one left edge. */
    public static final int INDENT = PlannerWidgets.INDENT;

    /** The name column never falls below this, however long the keys are. */
    public static final int MIN_NAME = 60;

    /** The key column stops here however long a key is; the name is what a reader scans. */
    public static final int MAX_KEY_CHARS = 28;

    /**
     * The scenario inputs a free-source list is built from.
     *
     * TWO, NOT THE PLANNER'S SEVEN AND NOT THE MACHINES SCREEN'S FOUR. `generators.resolve`
     * takes placed tile entities and the user's source overrides, and nothing else reaches it.
     * `PLACED` is the one that matters in practice: with no world scan the list is the curated
     * defaults only, so a player who built a cobblestone generator will not see it here and
     * will not see it priced free in a plan either.
     */
    static final ScenarioSource[] FEEDS_THE_LIST = {
        ScenarioSource.PLACED,
        ScenarioSource.SOURCE_OVERRIDES,
    };

    private SourcesWidgets() {
    }

    /**
     * The whole free-sources window.
     *
     * THE CAVEAT IS RESERVED BEFORE THE LIST IS SIZED, exactly as `machinesPanel` does it: the
     * list takes whatever height is left, so a line added afterwards is drawn past the bottom
     * of the panel and nothing reports it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ModularPanel sourcesPanel(SourceTable table, BrowseActions nav) {
        List<SourceTable.Row> rows = table.rows();

        PlannerWidgets.Group body = new PlannerWidgets.Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);

        int y = 0;
        body.child(BrowseTabs.strip(BrowseTabs.Tab.SOURCES, nav, CONTENT_WIDTH).pos(0, y));
        y += ROW_HEIGHT + 1;
        body.child(PlannerWidgets.heading(heading(table.size()), CONTENT_WIDTH).pos(0, y));
        y += LINE + 1;

        String caveat = InputCaveats.summaryLine("list built", FEEDS_THE_LIST);
        int footerLines = caveat.isEmpty() ? 1 : 2;
        int listHeight = PANEL_HEIGHT - PADDING * 2 - y - LINE * footerLines - 2;

        ListWidget list = new ListWidget();
        list.size(CONTENT_WIDTH, listHeight);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        int keyWidth = keyColumnWidth(rows, CONTENT_WIDTH);
        if (rows.isEmpty()) {
            // SAYS SO RATHER THAN DRAWING AN EMPTY BOX. With `placed` unread and no curated
            // default matching this pack, an empty list is a real and confusing state -- it
            // reads as a screen that failed to load rather than as the answer.
            list.child(PlannerWidgets.line("nothing is being treated as free", CONTENT_WIDTH,
                                           NodeStatus.INK_MUTED));
        }
        for (SourceTable.Row row : rows) {
            list.child(row(row, CONTENT_WIDTH, keyWidth));
        }
        // POSITIONED AS A STATEMENT, NOT CHAINED. `list` is a RAW `ListWidget` and on a raw
        // receiver javac erases the self-type `pos` returns, handing back `IPositioned`, which
        // `child(IWidget)` will not take. Measured in #254, where the chained form failed to
        // compile in exactly two places and compiled everywhere the list was a `ListWidget<?,?>`.
        list.pos(0, y);
        body.child(list);
        y += listHeight + 2;

        if (!caveat.isEmpty()) {
            body.child(PlannerWidgets.line(caveat, CONTENT_WIDTH, NodeStatus.INK_NEED).pos(0, y));
            y += LINE;
        }
        body.child(PlannerWidgets.line(FOOTER, CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, y));

        return ModularPanel.defaultPanel("mcrecipedump_sources", PANEL_WIDTH, PANEL_HEIGHT)
                .child(body);
    }

    /**
     * Where the list is edited, since this screen cannot.
     *
     * NAMES THE COMMAND RATHER THAN SAYING "NOT SUPPORTED". A player who wants a generator on
     * the list can have one today; they just cannot add it from here. Telling them where is the
     * difference between a limitation and a dead end, and it is the same rule
     * `ScenarioSource.Status.unavailable` states for its refusals -- "MAKE IT SAY WHAT TO DO".
     */
    static final String FOOTER = "edit with: recipegraph sources";

    /** `Free -- 12 things cost nothing`, or the singular. */
    static String heading(int count) {
        if (count == 1) {
            return "Free -- 1 thing costs nothing";
        }
        return "Free -- " + NodeRowText.grouped(count) + " things cost nothing";
    }

    /**
     * How wide the key column gets: the longest key shown, capped, never at the cost of
     * {@link #MIN_NAME}.
     *
     * THE SAME DERIVATION `MachinesWidgets.recipeColumnWidth` USES, and for the same reason: a
     * width typed in here is a width that is wrong for every pack but the one it was measured
     * on. The cap exists because `fluid:` keys are short and a discriminated item key can run
     * past forty characters, and one outlier must not squeeze the column a reader actually
     * scans.
     */
    static int keyColumnWidth(List<SourceTable.Row> rows, int rowWidth) {
        int longest = 0;
        for (SourceTable.Row row : rows) {
            longest = Math.max(longest, row.key().length());
        }
        int wanted = Math.min(longest, MAX_KEY_CHARS) * NodeRowText.CHAR_WIDTH;
        int room = rowWidth - GAP - MIN_NAME;
        return Math.max(0, Math.min(wanted, room));
    }

    /** One free thing: its name and key, then why it is free. */
    static PlannerWidgets.Group row(SourceTable.Row row, int width, int keyWidth) {
        PlannerWidgets.Group group = new PlannerWidgets.Group();
        group.size(width, ROW);
        int nameWidth = Math.max(GAP, width - (keyWidth > 0 ? keyWidth + GAP : 0));
        group.child(PlannerWidgets.line(row.name(), nameWidth, NodeStatus.INK_CRAFT).pos(0, 0));
        if (keyWidth > 0) {
            group.child(PlannerWidgets.line(row.key(), keyWidth, NodeStatus.INK_MUTED)
                                .pos(width - keyWidth, 0));
        }
        // THE EVIDENCE IS GREEN, WHICH IS THE ONE PLACE THIS SCREEN SPENDS COLOUR. `INK_OK` is
        // what the planner uses for a thing you already have, and "free" is the same kind of
        // good news; drawing it muted like the machines screen's evidence would make the only
        // sentence on the row that carries the point look like a footnote.
        group.child(PlannerWidgets.line(row.why(), width - INDENT, NodeStatus.INK_OK)
                            .pos(INDENT, ROW_HEIGHT));
        return group;
    }
}
