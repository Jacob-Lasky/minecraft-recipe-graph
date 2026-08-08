package io.github.jacoblasky.recipedump.client.machines;

import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.MachineTable;

import java.util.List;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ListWidget;

/**
 * The machines table and its two sub-panels, as functions of plain data.
 *
 * THE SAME CONTRACT AS `PlannerWidgets` AND FOR THE SAME REASON: no screen, no context and no
 * service reaches any method here, which is what lets `MachinesLayoutTest` build every panel
 * and run ModularUI's real sizer over it with no window. Its two rules are inherited whole and
 * are not style:
 *
 *   1. EVERY TEXT WIDGET STATES ITS SIZE. A self-sizing `TextWidget` measures the string,
 *      which needs the font renderer and therefore LWJGL; headless that throws inside the
 *      resize pass, and `WidgetTree.resizeInternal` swallows every Throwable, so the symptom
 *      is the WHOLE tree silently back at 0x0. Everything below goes through
 *      `PlannerWidgets.line`, which sizes and cuts.
 *   2. COLUMNS ARE FIXED WIDTHS. ModularUI neither clamps nor clips a child wider than its
 *      parent (#125) -- it overflows and nothing reports it.
 *
 * WHY THERE IS NO SEARCH BOX, WHICH IS A DECISION AND NOT AN OMISSION. The browser's machines
 * page has one, and this screen deliberately does not, because a text field would be the first
 * keyboard input anywhere in this mod -- `TextFieldWidget` appears nowhere in
 * `mod/src/main/java` today. That brings focus handling, the `e`-closes-the-inventory
 * collision, and a headless harness whose typing path has never been demonstrated (its README
 * claimed nothing could click either, and that claim was false but untested for a fortnight).
 * Building the mod's first table and the mod's first text input in one change means a failure
 * names neither. The state chips are the axis that answers the question this screen is for --
 * "what can I not use" -- and the mod picker handles the axis a player knows by name. Text
 * search is filed separately.
 */
public final class MachinesWidgets {

    public static final int PANEL_WIDTH = PlannerWidgets.PANEL_WIDTH;
    public static final int PANEL_HEIGHT = PlannerWidgets.PANEL_HEIGHT;
    public static final int PADDING = PlannerWidgets.PADDING;
    public static final int CONTENT_WIDTH = PlannerWidgets.CONTENT_WIDTH;
    public static final int LINE = PlannerWidgets.LINE;
    public static final int ROW_HEIGHT = PlannerWidgets.ROW_HEIGHT;
    public static final int GAP = PlannerWidgets.GAP;

    /**
     * A table row is TWO lines: the verdict and the name, then the evidence under it.
     *
     * THE EVIDENCE STAYS ON THE ROW rather than moving to the detail panel, and that costs
     * half the visible rows. `machines_page` calls the evidence column "the only reason anyone
     * opens this page after the first time", which is the whole argument: a table of 503
     * verdicts with no `why` is a table that answers "how many" and never "how come". Seven
     * rows a reader does not have to click through beats thirteen they do, because the filters
     * are what cut 503 down to the handful anyone actually reads.
     */
    public static final int TABLE_ROW_HEIGHT = ROW_HEIGHT * 2;

    /**
     * The verdict column, sized from the vocabulary rather than from a number typed here.
     *
     * See {@link MachineLabels#widestLabel} for what a hand-written width cost last time.
     */
    public static final int STATE_COLUMN = MachineLabels.widestLabel() * NodeRowText.CHAR_WIDTH;

    /** Four chips across the content width, with a gap between each pair. */
    public static final int CHIP_WIDTH =
            (CONTENT_WIDTH - GAP * (MachineInfo.STATE_COUNT - 1)) / MachineInfo.STATE_COUNT;

    /** The name column never falls below this, however wide the recipe count gets. */
    public static final int MIN_NAME = 60;

    /**
     * Sub-panels size to their content, so these are widths only.
     *
     * The mod picker is the planner's picker width for the same reason that one is wide: a
     * list whose rows are names needs room for the longest name, and "AE2 Unofficial Extended
     * Life" is 30 characters before its count.
     */
    public static final int MOD_PICKER_WIDTH = PlannerWidgets.PICKER_WIDTH;
    public static final int MOD_PICKER_MAX_LIST_HEIGHT = PlannerWidgets.PICKER_MAX_LIST_HEIGHT;
    public static final int DETAIL_WIDTH = PlannerWidgets.PICKER_WIDTH;
    public static final int DETAIL_MAX_LIST_HEIGHT = PlannerWidgets.PICKER_MAX_LIST_HEIGHT;

    private MachinesWidgets() {
    }

    /**
     * What to draw before there is a table: still reading, or why there will not be one.
     *
     * THROUGH `PlannerWidgets.statePanel` RATHER THAN A COPY. The two screens have the same
     * four not-yet states for the same reason -- both are opened before an off-thread read
     * finishes -- and `PlannerState`'s class note is that telling those states apart is the
     * whole point. A second copy of this panel is a second place for one of them to be
     * dropped.
     */
    public static ModularPanel statePanel(PlannerState state) {
        return PlannerWidgets.statePanel(state, "Machines", "mcrecipedump_machines_state");
    }

    /**
     * The whole machines window for a table and the filter currently applied.
     *
     * THE FILTER IS PASSED IN ALREADY RECONCILED. `MachineTable.reconcile` drops a selection
     * that can no longer match anything, and doing it here would mean the panel disagreed with
     * whoever holds the filter about what is switched on -- the chips would draw one thing and
     * the next click would toggle from another.
     */
    public static ModularPanel machinesPanel(MachineTable table, MachineTable.Filter filter,
                                             MachinesActions actions) {
        MachineTable.Narrowed narrowed = table.narrowed(filter);
        List<MachineTable.Row> rows = table.rows(filter);

        PlannerWidgets.Group body = new PlannerWidgets.Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);

        int y = 0;
        body.child(PlannerWidgets.heading(heading(table), CONTENT_WIDTH).pos(0, y));
        y += LINE + 1;

        body.child(chips(filter, narrowed, actions).pos(0, y));
        y += ROW_HEIGHT + 1;

        body.child(modButton(filter, narrowed, actions).pos(0, y));
        y += ROW_HEIGHT + 2;

        // RED AND ABOVE THE TABLE, exactly where `plannerPanel` puts its warnings and for the
        // same reason: it is a reason the rows below are not what they appear to be, and a
        // reader who has to scroll to find that out has already been misled.
        //
        // RESERVED BEFORE THE LIST IS SIZED. The list takes whatever is left, so a line added
        // afterwards is drawn past the bottom of the panel -- ModularUI neither clamps nor
        // clips (#125).
        //
        // THE ONE PLACE THIS CLASS READS A GLOBAL, and it is the same exception `plannerPanel`
        // makes for `PlanCaveats`: whether an input was read is a fact about the SCENARIO, not
        // about the table, so there is nothing in the arguments to derive it from. The cost is
        // that the exact list height here depends on `ScenarioSource`'s installed readers, so
        // a layout test must not assert a hardcoded one -- `MachinesLayoutTest` asserts the
        // scroll extent and the panel bounds, both of which hold either way.
        String caveat = MachineCaveats.summaryLine();
        int footerLines = caveat.isEmpty() ? 1 : 2;
        int listHeight = PANEL_HEIGHT - PADDING * 2 - y - LINE * footerLines - 2;
        body.child(tableList(rows, CONTENT_WIDTH, listHeight, actions).pos(0, y));
        y += listHeight + 2;

        if (!caveat.isEmpty()) {
            body.child(PlannerWidgets.line(caveat, CONTENT_WIDTH, NodeStatus.INK_NEED).pos(0, y));
            y += LINE;
        }
        body.child(PlannerWidgets.line(footer(rows.size(), table.allRows().size()),
                                       CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, y));

        return ModularPanel.defaultPanel("mcrecipedump_machines", PANEL_WIDTH, PANEL_HEIGHT)
                .child(body);
    }

    /**
     * `Machines -- 503 categories, 117,681 recipes`.
     *
     * BOTH TOTALS, because either alone flatters. 503 categories sounds small next to a pack
     * this size until the recipe figure says what they carry, and the recipe figure alone says
     * nothing about how many separate machines a player has to care about.
     */
    static String heading(MachineTable table) {
        long recipes = 0;
        for (int count : table.recipeTotals()) {
            recipes += count;
        }
        return "Machines -- " + NodeRowText.grouped(table.allRows().size()) + " categories, "
                + NodeRowText.grouped(recipes) + " recipes";
    }

    /** `12 of 503 shown`, or `503 categories` when nothing is filtered out. */
    static String footer(int shown, int total) {
        if (shown == total) {
            return NodeRowText.grouped(total) + " categories";
        }
        return NodeRowText.grouped(shown) + " of " + NodeRowText.grouped(total) + " shown";
    }

    /**
     * The four state chips, which are a multi-select exactly as the browser's are.
     *
     * NO CHIP ON MEANS EVERY STATE, not none. That is `MACHINES_JS`'s rule
     * (`!states.length||active[...]`) and it is the only sane reading of an empty selection --
     * a table that went blank when you switched the last chip off would be a table you could
     * break by clicking twice.
     *
     * ON IS SHOWN BY BRACKETS AND BY COLOUR, NOT BY COLOUR ALONE. A chip that only changed
     * hue is unreadable in a screenshot converted to greyscale, unreadable to the roughly one
     * player in twelve with a red-green deficiency, and -- the reason that decided it here --
     * indistinguishable from the state colours the chips already carry, since a lit `no route`
     * chip and an unlit one would differ only in how red they are. The bracket is a second,
     * redundant channel and it costs two characters.
     *
     * A CHIP WITH NOTHING BEHIND IT IS STILL DRAWN, greyed by its own zero rather than
     * removed. #16 chose disable-in-place over removal because a list that changes length
     * under the cursor loses the reader's place, and a visible zero is an answer.
     */
    static PlannerWidgets.Group chips(MachineTable.Filter filter,
                                      MachineTable.Narrowed narrowed,
                                      final MachinesActions actions) {
        PlannerWidgets.Group group = new PlannerWidgets.Group();
        group.size(CONTENT_WIDTH, ROW_HEIGHT);
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            final int which = state;
            int count = narrowed.state(state);
            boolean on = filter.state(state);
            PlannerWidgets.ClickableGroup chip =
                    new PlannerWidgets.ClickableGroup(new Runnable() {
                        @Override
                        public void run() {
                            actions.toggleState(which);
                        }
                    });
            chip.size(CHIP_WIDTH, ROW_HEIGHT);
            chip.child(PlannerWidgets.line(chipText(state, count, on), CHIP_WIDTH,
                                           chipColour(state, count, on)).pos(0, 0));
            group.child(chip.pos(state * (CHIP_WIDTH + GAP), 0));
        }
        return group;
    }

    static String chipText(int state, int count, boolean on) {
        String body = MachineLabels.label(state) + " " + NodeRowText.grouped(count);
        return on ? "[" + body + "]" : body;
    }

    /**
     * A chip with nothing behind it is muted whether or not it is lit.
     *
     * THE ZERO WINS OVER THE SELECTION, and the ordering matters: a lit chip that matches
     * nothing is exactly the state {@link MachineTable#reconcile} is about to clear, so
     * drawing it in its full state colour would advertise a selection that is one frame from
     * disappearing.
     */
    static int chipColour(int state, int count, boolean on) {
        if (count == 0) {
            return NodeStatus.INK_MUTED;
        }
        return on ? MachineLabels.colour(state) : NodeStatus.INK_MUTED;
    }

    /** `mod: every (503)`, or the chosen one with its narrowed count. */
    static PlannerWidgets.ClickableGroup modButton(MachineTable.Filter filter,
                                                   MachineTable.Narrowed narrowed,
                                                   final MachinesActions actions) {
        PlannerWidgets.ClickableGroup button =
                new PlannerWidgets.ClickableGroup(new Runnable() {
                    @Override
                    public void run() {
                        actions.openModPicker();
                    }
                });
        button.size(CONTENT_WIDTH, ROW_HEIGHT);
        button.child(PlannerWidgets.line(modButtonText(filter, narrowed), CONTENT_WIDTH,
                                         filter.mod() == null ? NodeStatus.INK_MUTED
                                                 : NodeStatus.INK_CRAFT).pos(0, 0));
        return button;
    }

    static String modButtonText(MachineTable.Filter filter, MachineTable.Narrowed narrowed) {
        if (filter.mod() == null) {
            return "mod: every";
        }
        return "mod: " + filter.mod() + " (" + NodeRowText.grouped(narrowed.mod(filter.mod()))
                + ")";
    }

    /**
     * The scrolling table.
     *
     * ONE RECIPE-COLUMN WIDTH FOR THE WHOLE LIST, computed from every visible row, so the
     * numbers line up down the panel. `choiceList` makes the same choice for the same reason:
     * per-row widths give a ragged edge and make a column unscannable, which is the only thing
     * a column of counts is for.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static ListWidget<?, ?> tableList(List<MachineTable.Row> rows, int width, int height,
                                      MachinesActions actions) {
        ListWidget list = new ListWidget();
        list.size(width, height);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        int recipeWidth = recipeColumnWidth(rows, width);
        for (MachineTable.Row row : rows) {
            list.child(tableRow(row, width, recipeWidth, actions));
        }
        return list;
    }

    /**
     * How wide the recipe column gets: the longest count shown, never at the cost of
     * {@link #MIN_NAME}.
     *
     * FROM THE ROWS ON SCREEN AND NOT FROM THE WHOLE TABLE. Filtering to `no route` leaves
     * three categories carrying two recipes each, and a column sized for the pack's 1,441
     * would spend 30 pixels of a 388-pixel panel drawing space beside `2`.
     */
    static int recipeColumnWidth(List<MachineTable.Row> rows, int rowWidth) {
        int longest = 0;
        for (MachineTable.Row row : rows) {
            longest = Math.max(longest, NodeRowText.grouped(row.recipes()).length());
        }
        int wanted = longest * NodeRowText.CHAR_WIDTH;
        int room = rowWidth - STATE_COLUMN - GAP - GAP - MIN_NAME;
        return Math.max(0, Math.min(wanted, room));
    }

    /**
     * One category: verdict and name on the first line, evidence on the second.
     *
     * THE EVIDENCE IS INDENTED UNDER THE NAME AND NOT UNDER THE VERDICT, so the eye has one
     * left edge for "what this is" and the verdict column stays a clean strip that can be
     * scanned on its own. It is drawn muted because it is a supporting sentence; the row's
     * colour signal is the verdict word.
     */
    static PlannerWidgets.ClickableGroup tableRow(final MachineTable.Row row, int width,
                                                  int recipeWidth,
                                                  final MachinesActions actions) {
        PlannerWidgets.ClickableGroup group =
                new PlannerWidgets.ClickableGroup(new Runnable() {
                    @Override
                    public void run() {
                        actions.openDetail(row);
                    }
                });
        group.size(width, TABLE_ROW_HEIGHT);

        int colour = MachineLabels.colour(row.state());
        group.child(PlannerWidgets.line(MachineLabels.label(row.state()), STATE_COLUMN, colour)
                            .pos(0, 0));
        int x = STATE_COLUMN + GAP;
        int nameWidth = Math.max(GAP, width - x - (recipeWidth > 0 ? recipeWidth + GAP : 0));
        group.child(PlannerWidgets.line(rowName(row), nameWidth, NodeStatus.INK_CRAFT).pos(x, 0));
        if (recipeWidth > 0) {
            group.child(PlannerWidgets.line(NodeRowText.grouped(row.recipes()), recipeWidth,
                                            NodeStatus.INK_MUTED).pos(width - recipeWidth, 0));
        }
        group.child(PlannerWidgets.line(row.why(), width - x, NodeStatus.INK_MUTED)
                            .pos(x, ROW_HEIGHT));
        return group;
    }

    /**
     * The name, marked when a human set the verdict by hand.
     *
     * `(manual)` IS NOT DECORATION. A hand-set state outranks every automatic verdict, so it
     * is the one row whose evidence sentence does not explain the colour beside it -- without
     * the mark a reader looking at a `have` row whose `why` says nothing convincing has no way
     * to know the answer came from them rather than from the graph. `machines_page` bolds it
     * for the same reason.
     */
    static String rowName(MachineTable.Row row) {
        return row.manual() ? row.name() + " (manual)" : row.name();
    }

    /**
     * Choose a mod, or clear the choice.
     *
     * "every mod" IS THE FIRST ROW AND NEVER MOVES, which is the browser's rule for its own
     * empty option: it is the way OUT of a filter, so it has to stay where the cursor expects
     * it rather than sorting among the mods by a count it does not have.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ModularPanel modPicker(MachineTable table, MachineTable.Filter filter,
                                         final MachinesActions actions) {
        MachineTable.Narrowed narrowed = table.narrowed(filter);
        List<String> mods = table.modsInOfferOrder(narrowed);

        int rowWidth = MOD_PICKER_WIDTH - PADDING * 2;
        ListWidget list = new ListWidget();
        int listHeight = Math.min(MOD_PICKER_MAX_LIST_HEIGHT, (mods.size() + 1) * ROW_HEIGHT);
        list.size(rowWidth, listHeight);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        list.child(modRow(null, table.allRows().size(), rowWidth, actions));
        for (String mod : mods) {
            list.child(modRow(mod, narrowed.mod(mod), rowWidth, actions));
        }

        PlannerWidgets.Group body = new PlannerWidgets.Group();
        body.pos(PADDING, PADDING);
        body.size(rowWidth, LINE + 1 + listHeight);
        body.child(PlannerWidgets.heading("Filter by mod", rowWidth).pos(0, 0));
        body.child(list.pos(0, LINE + 1));

        return ModularPanel.defaultPanel("mcrecipedump_machines_mods", MOD_PICKER_WIDTH,
                                         LINE + 1 + listHeight + PADDING * 2)
                .child(body);
    }

    /** @param mod null for the "every mod" row. */
    static PlannerWidgets.ClickableGroup modRow(final String mod, int count, int width,
                                                final MachinesActions actions) {
        PlannerWidgets.ClickableGroup row =
                new PlannerWidgets.ClickableGroup(new Runnable() {
                    @Override
                    public void run() {
                        actions.chooseMod(mod);
                    }
                });
        row.size(width, ROW_HEIGHT);
        // A ZERO-COUNT MOD IS MUTED AND STILL CLICKABLE. Disabling the click would be the
        // browser's `o.disabled=!c`, and it does not translate: there the option sits in a
        // native `<select>` a reader can still see and skip past, whereas a dead row in a
        // scrolling list gives no feedback at all when clicked. Choosing an empty mod here is
        // harmless -- `reconcile` clears it on the next build -- and a click that visibly does
        // the honest thing beats one that does nothing.
        int colour = count == 0 ? NodeStatus.INK_MUTED : NodeStatus.INK_CRAFT;
        row.child(PlannerWidgets.line(modRowText(mod, count), width, colour).pos(0, 0));
        return row;
    }

    static String modRowText(String mod, int count) {
        if (mod == null) {
            return "every mod (" + NodeRowText.grouped(count) + ")";
        }
        return mod + " (" + NodeRowText.grouped(count) + ")";
    }

    /**
     * Everything known about one category, which is the screen `machine_page` was.
     *
     * THIS IS WHAT MAKES "3 machines to build" ACTIONABLE. #190's finding, quoted on #254, is
     * that a count with no way to learn WHICH is the defect rather than the feature; a table
     * that can only say `buildable` has the same shape one level down, because the row does
     * not say what to build. Every candidate is listed with its own verdict, per
     * {@link MachineTable.Row#candidates}, and each is a click that plans it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ModularPanel detailPanel(MachineTable.Row row, final MachinesActions actions) {
        int rowWidth = DETAIL_WIDTH - PADDING * 2;
        List<MachineTable.Candidate> candidates = row.candidates();

        ListWidget list = new ListWidget();
        int listHeight = Math.min(DETAIL_MAX_LIST_HEIGHT,
                                  Math.max(1, candidates.size()) * ROW_HEIGHT);
        list.size(rowWidth, listHeight);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        if (candidates.isEmpty()) {
            // SAYS SO RATHER THAN DRAWING AN EMPTY BOX. An `unknown` category has no candidate
            // by definition -- that IS the state -- and a blank list under a heading reads as
            // a panel that failed to load rather than as the answer.
            list.child(PlannerWidgets.line("no block was matched to this category", rowWidth,
                                           NodeStatus.INK_MUTED));
        }
        for (final MachineTable.Candidate candidate : candidates) {
            list.child(candidateRow(candidate, rowWidth, actions));
        }

        PlannerWidgets.Group body = new PlannerWidgets.Group();
        body.pos(PADDING, PADDING);

        int y = 0;
        body.child(PlannerWidgets.heading(row.name(), rowWidth).pos(0, y));
        y += LINE;
        body.child(PlannerWidgets.line(row.uid(), rowWidth, NodeStatus.INK_MUTED).pos(0, y));
        y += LINE;
        body.child(PlannerWidgets.line(detailVerdict(row), rowWidth,
                                       MachineLabels.colour(row.state())).pos(0, y));
        y += LINE;
        body.child(PlannerWidgets.line(row.why(), rowWidth, NodeStatus.INK_MUTED).pos(0, y));
        y += LINE + 1;
        body.child(PlannerWidgets.heading(candidatesHeading(candidates.size()), rowWidth)
                           .pos(0, y));
        y += LINE;
        body.child(list.pos(0, y));
        y += listHeight;
        body.size(rowWidth, y);

        return ModularPanel.defaultPanel("mcrecipedump_machine_detail", DETAIL_WIDTH,
                                         y + PADDING * 2)
                .child(body);
    }

    /** `buildable -- 1,441 recipes`, or with `(manual)` when a human set it. */
    static String detailVerdict(MachineTable.Row row) {
        String verdict = MachineLabels.label(row.state()) + " -- "
                + NodeRowText.grouped(row.recipes()) + " recipes";
        return row.manual() ? verdict + " (manual)" : verdict;
    }

    static String candidatesHeading(int count) {
        if (count == 1) {
            return "1 candidate block";
        }
        return NodeRowText.grouped(count) + " candidate blocks";
    }

    /**
     * One candidate: its own verdict, its key, and the evidence for it.
     *
     * THE KEY AND NOT A DISPLAY NAME, and this is the one place in the mod where that is the
     * right way round. A candidate is a registry entry the reader may need to search for in
     * JEI or hand to a command, and the six-items-called-Iron-Plate problem (#232) is at its
     * worst here -- several candidates for one category are routinely NBT variants of one
     * block, which share a display name exactly.
     */
    static PlannerWidgets.ClickableGroup candidateRow(final MachineTable.Candidate candidate,
                                                      int width,
                                                      final MachinesActions actions) {
        PlannerWidgets.ClickableGroup row =
                new PlannerWidgets.ClickableGroup(new Runnable() {
                    @Override
                    public void run() {
                        actions.planMachine(candidate.key());
                    }
                });
        row.size(width, ROW_HEIGHT);
        int colour = MachineLabels.colour(candidate.state());
        row.child(PlannerWidgets.line(MachineLabels.label(candidate.state()), STATE_COLUMN,
                                      colour).pos(0, 0));
        int x = STATE_COLUMN + GAP;
        row.child(PlannerWidgets.line(candidate.key(), width - x, NodeStatus.INK_CRAFT)
                          .pos(x, 0));
        return row;
    }
}
