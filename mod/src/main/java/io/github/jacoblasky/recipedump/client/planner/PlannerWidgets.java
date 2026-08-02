package io.github.jacoblasky.recipedump.client.planner;

import java.util.List;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ItemDisplayWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;

import io.github.jacoblasky.recipedump.common.PlanBook;

/**
 * Every widget the planner draws, as functions of plain data.
 *
 * NO SCREEN, NO CONTEXT, NO CLIENT STATE reaches any method here, and that is what makes the
 * whole panel testable: `PlannerLayoutTest` builds these against the frozen fixtures and runs
 * ModularUI's real sizer over them with no window. The screen in `PlannerScreen` is a dozen
 * lines that call these and open the result.
 *
 * TWO RULES THAT LOOK LIKE STYLE AND ARE NOT:
 *
 *   1. EVERY TEXT WIDGET STATES ITS SIZE. A `TextWidget` left to size itself measures the
 *      string, which needs the font renderer and therefore LWJGL; headless, that throws
 *      inside the resize pass, and because `WidgetTree.resizeInternal` swallows every
 *      Throwable the symptom is the WHOLE tree silently back at 0x0. Measured in #140.
 *   2. COLUMNS ARE FIXED WIDTHS, not shrink-to-fit. #125 measured that ModularUI neither
 *      clamps nor clips a child wider than its parent -- it just overflows, and nothing
 *      reports it. A registry id in a node row is exactly that case.
 */
public final class PlannerWidgets {

    /** The panel is sized for 854x480 at GUI scale 2, the smallest screen Minecraft allows. */
    public static final int PANEL_WIDTH = 400;
    public static final int PANEL_HEIGHT = 220;
    public static final int PADDING = 6;
    public static final int CONTENT_WIDTH = PANEL_WIDTH - PADDING * 2;

    public static final int LINE = 10;
    public static final int ROW_HEIGHT = 11;
    /** Per level of depth. Six pixels is legible and keeps a ten-deep chain on screen. */
    public static final int INDENT = 6;
    /**
     * Indentation stops here.
     *
     * `plan-fluid-chain` is 347 nodes and runs deeper than eight levels; without a cap the
     * label column would be pushed off the right edge and, per rule 2 above, nothing would
     * say so. The tree structure is still readable because the rows above are still indented.
     */
    public static final int MAX_INDENT_DEPTH = 8;

    /** Reserved for Phase 4's item icons. See {@link NodeActions#iconFor}. */
    public static final int ICON = 10;
    /** Wide enough for `934,400x`, which is a real quantity from a Borax plan. */
    public static final int QTY = 52;
    /**
     * The badge column, wide enough for the LONGEST word the vocabulary can produce.
     *
     * Derived rather than guessed, and `theBadgeColumnFitsEveryWordTheVocabularyCanProduce`
     * pins it. A badge is fixed vocabulary -- unlike a label, which is a registry name and may
     * legitimately be cut -- so a truncated one is always a bug. At 66 the screenshot of
     * `plan-variant-table` read "no known…", which is #139's mark saying half of itself.
     */
    public static final int BADGE = widestBadge();
    public static final int GAP = 3;

    /**
     * The tallest the TODO list may get before it scrolls instead of growing.
     *
     * The shopping list is as long as the plan is unfinished -- `plan-truncated` produces 20
     * outstanding rows -- so a panel sized to its contents runs off a 240-pixel screen. This
     * leaves room for the heading and the panel's own border inside that.
     */
    public static final int TODO_MAX_LIST_HEIGHT = 180;

    /**
     * Secondary panel widths, sized to their longest line at {@link NodeRowText#CHAR_WIDTH}.
     *
     * Fixed rather than fitted, because fitting means measuring, and measuring needs a font
     * renderer this build does not have. Both were found too narrow by a screenshot: the menu
     * cut "Choose another recipe (172)" and the picker cut its own explanation.
     */
    public static final int MENU_WIDTH = 200;
    public static final int PICKER_WIDTH = 330;

    private PlannerWidgets() {
    }

    /** The widest badge in {@link NodeStatus}'s vocabulary, in pixels. */
    private static int widestBadge() {
        int widest = NodeStatus.UNSOURCED_BADGE.length();
        for (String status : NodeStatus.all()) {
            widest = Math.max(widest, NodeStatus.badgeFor(status).length());
        }
        for (String kind : NodeStatus.tokenKinds()) {
            widest = Math.max(widest, NodeStatus.tokenBadge(kind).length());
        }
        return widest * NodeRowText.CHAR_WIDTH;
    }

    /** A parent that positions its children absolutely. Concrete because `ParentWidget` is
     *  F-bounded and cannot be instantiated. Adds nothing. */
    public static final class Group extends ParentWidget<Group> {
    }

    /**
     * A row that can be clicked.
     *
     * `Interactable` on the ROW rather than a `ButtonWidget` around it, because a button
     * brings its own themed background and hover fill, and 347 of those down a tree panel is
     * a wall of boxes. What is wanted is a click target the width of the row and no visual
     * change at all.
     */
    public static final class ClickableGroup extends ParentWidget<ClickableGroup>
            implements com.cleanroommc.modularui.api.widget.Interactable {

        private final Runnable onClick;

        ClickableGroup(Runnable onClick) {
            this.onClick = onClick;
        }

        /**
         * NO CLICK SOUND HERE, deliberately. `Interactable.playButtonClickSound` reaches the
         * sound handler and therefore LWJGL, so a widget that played it could not be clicked
         * in a headless test at all -- it threw `NoClassDefFoundError: org/lwjgl/LWJGLException`
         * the first time one tried. The noise belongs to the live action, which is a client
         * thing anyway; see `LivePlannerActions`.
         */
        @Override
        public com.cleanroommc.modularui.api.widget.Interactable.Result onMousePressed(
                int button) {
            onClick.run();
            return com.cleanroommc.modularui.api.widget.Interactable.Result.SUCCESS;
        }
    }

    /**
     * The whole planner window for a solved plan.
     *
     * @param book the player's plan book, for the "still needed" column; may be empty.
     */
    public static ModularPanel plannerPanel(PlanView plan, PlanBook book,
                                            PlannerActions actions) {
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);

        int y = 0;
        body.child(heading(NodeRowText.heading(plan), CONTENT_WIDTH).pos(0, y));
        y += LINE + 1;

        String warning = NodeRowText.truncationWarning(plan);
        if (!warning.isEmpty()) {
            // RED, and above the tree rather than under it. A truncated plan is shaped like a
            // complete one, so a reader who has to scroll to find out has already been misled.
            body.child(line(warning, CONTENT_WIDTH, NodeStatus.INK_NEED).pos(0, y));
            y += LINE;
        }

        int treeHeight = PANEL_HEIGHT - PADDING * 2 - y - LINE - 2;
        body.child(tree(plan, CONTENT_WIDTH, treeHeight, actions).pos(0, y));
        y += treeHeight + 2;
        body.child(line(footer(plan, book), CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, y));

        return ModularPanel.defaultPanel("mcrecipedump_planner", PANEL_WIDTH, PANEL_HEIGHT)
                .child(body);
    }

    /**
     * The tree, flattened depth-first into scrollable rows.
     *
     * FLATTENED RATHER THAN NESTED, which is the opposite of the browser's `<details>` tree
     * and is deliberate. ModularUI has no collapsing container, so a nested build would be a
     * column of columns whose height is the sum of its children -- and the scroll viewport
     * would then have to size against a parent that sizes against it. A flat list of rows,
     * each carrying its own indent, gives the same picture with a content height the
     * `ListWidget` can actually publish.
     *
     * A `ListWidget`, NOT a `ScrollWidget` over a `Column`: #125 measured that a plain
     * `ScrollWidget` never calls `setScrollSize`, so it lays out perfectly and its scrollbar
     * can never activate.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ListWidget<?, ?> tree(PlanView plan, int width, int height,
                                        PlannerActions actions) {
        ListWidget list = new ListWidget();
        list.size(width, height);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        appendRows(plan.tree(), 0, width, list, actions);
        return list;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void appendRows(PlanNode node, int depth, int width, ListWidget into,
                                   PlannerActions actions) {
        into.child(row(node, depth, width, actions));
        for (PlanNode child : node.children()) {
            appendRows(child, depth + 1, width, into, actions);
        }
    }

    /**
     * ONE NODE'S CONTENT, at a size the caller chooses. The seam #19 Phase 3b asked for.
     *
     * The tree and the flow diagram want the same CONTENT -- icon, quantity, label, status
     * badge -- and different GEOMETRY: a full-width row in a scrolling list, or a fixed box at
     * an absolute position on a canvas with a couple of hundred visible at once. So the
     * content is shared and the container is not, which is why this takes both dimensions and
     * assumes nothing about a panel.
     *
     * NOT CLICKABLE, unlike {@link #row}. A diagram node has its own hit-testing through the
     * viewport transform, and a canvas of clickable rows would fight it.
     *
     * The colours come from {@link NodeStatus} and must keep doing so: `present.py`'s own
     * docstring records that node statuses are drawn by four components which each kept their
     * own dict of bare strings, so adding a status drew silently wrong. `NodeStatusTest` reads
     * that file and asserts both directions, so there is one mapping and it is enforced.
     */
    public static ParentWidget<?> planNodeContent(PlanNode node, int width, int height) {
        Group box = new Group();
        box.size(width, height);
        int badgeWidth = Math.min(BADGE, Math.max(0, width - QTY - GAP * 2));
        int x = 0;
        net.minecraft.item.ItemStack stack = NodeActionsHolder.actions().iconFor(node);
        if (!stack.isEmpty()) {
            box.child(icon(stack).pos(x, 0));
        }
        x += ICON + GAP;
        int colour = NodeStatus.colour(node);
        box.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, 0));
        x += QTY + GAP;
        int labelWidth = Math.max(GAP, width - badgeWidth - GAP - x);
        box.child(line(NodeRowText.label(node), labelWidth, NodeStatus.INK_MUTED).pos(x, 0));
        if (badgeWidth > 0) {
            box.child(line(NodeStatus.badge(node), badgeWidth, colour)
                              .pos(width - badgeWidth, 0));
        }
        return box;
    }

    /**
     * One tree row: indent, icon, quantity, label, meta, badge, and clickable.
     *
     * The column order is the browser's, so a reader moving between the two finds the same
     * fact in the same place. Kept separate from {@link #planNodeContent} rather than built on
     * it, because a row indents, carries the meta detail and opens a menu, and none of those
     * are true of a diagram node.
     */
    public static ParentWidget<?> row(final PlanNode node, int depth, int width,
                                      final PlannerActions actions) {
        ClickableGroup row = new ClickableGroup(new Runnable() {
            @Override
            public void run() {
                actions.openNodeMenu(node);
            }
        });
        row.size(width, ROW_HEIGHT);

        int indent = Math.min(depth, MAX_INDENT_DEPTH) * INDENT;
        int x = indent;

        // The icon column is RESERVED whether or not anything fills it, so installing
        // NodeActions in Phase 4 does not re-flow every row. The widget is only added when
        // there is a stack, because an `ItemDisplayWidget` holding EMPTY still draws its slot
        // frame -- 49 empty boxes down the left edge of the first real screenshot.
        net.minecraft.item.ItemStack stack = NodeActionsHolder.actions().iconFor(node);
        if (!stack.isEmpty()) {
            row.child(icon(stack).pos(x, 0));
        }
        x += ICON + GAP;

        int colour = NodeStatus.colour(node);
        row.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, 0));
        x += QTY + GAP;

        int badgeX = width - BADGE;
        int labelWidth = Math.max(GAP, badgeX - GAP - x);
        row.child(line(labelAndMeta(node), labelWidth, NodeStatus.INK_MUTED).pos(x, 0));

        row.child(line(NodeStatus.badge(node), BADGE, colour).pos(badgeX, 0));
        return row;
    }

    /**
     * The label, with the dimmed detail appended after a separator.
     *
     * ONE WIDGET RATHER THAN TWO, because two would need the label's rendered width to place
     * the second -- and measuring text is the thing that cannot be done headlessly. The
     * separator carries the distinction that a colour would otherwise have carried.
     */
    static String labelAndMeta(PlanNode node) {
        String meta = NodeRowText.meta(node);
        return meta.isEmpty() ? NodeRowText.label(node)
                              : NodeRowText.label(node) + NodeRowText.SEPARATOR + meta;
    }

    /**
     * The node menu.
     *
     * The two recipe-viewer entries appear only when {@link NodeActions} says they would work
     * -- see that interface for why a greyed-out entry is worse than none.
     */
    public static ModularPanel nodeMenu(final PlanNode node, final PlannerActions actions) {
        List<Entry> entries = new java.util.ArrayList<Entry>();
        if (actions.nodeActions().canShowInRecipeViewer(node)) {
            entries.add(new Entry("Show recipes", new Runnable() {
                @Override
                public void run() {
                    actions.nodeActions().showRecipes(node);
                }
            }));
            entries.add(new Entry("Show uses", new Runnable() {
                @Override
                public void run() {
                    actions.nodeActions().showUses(node);
                }
            }));
        }
        if (node.alternatives() > 1) {
            entries.add(new Entry("Choose another recipe (" + node.alternatives() + ")",
                                  new Runnable() {
                                      @Override
                                      public void run() {
                                          actions.openRecipePicker(node);
                                      }
                                  }));
        }
        entries.add(new Entry("Add to TODO", new Runnable() {
            @Override
            public void run() {
                actions.addToTodo(node);
            }
        }));
        entries.add(new Entry("Favourite", new Runnable() {
            @Override
            public void run() {
                actions.toggleFavourite(node);
            }
        }));

        int height = PADDING * 2 + LINE + 1 + entries.size() * ROW_HEIGHT;
        // WIDE ENOUGH FOR THE LONGEST ENTRY, measured in the character budget the whole panel
        // uses. "Choose another recipe (172)" came out as "Choose another recip..." at 150.
        int width = MENU_WIDTH;
        int inner = width - PADDING * 2;
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(inner, height - PADDING * 2);
        // Muted, so the title does not read as a fifth thing you can click.
        body.child(line(NodeRowText.label(node), inner, NodeStatus.INK_MUTED).pos(0, 0));
        int y = LINE + 1;
        for (Entry entry : entries) {
            ClickableGroup row = new ClickableGroup(entry.action);
            row.size(inner, ROW_HEIGHT);
            row.child(line(entry.text, inner, NodeStatus.INK_CRAFT).pos(0, 0));
            body.child(row.pos(0, y));
            y += ROW_HEIGHT;
        }
        return ModularPanel.defaultPanel("mcrecipedump_node_menu", width, height).child(body);
    }

    /** One menu line and what it does. */
    private static final class Entry {
        final String text;
        final Runnable action;

        Entry(String text, Runnable action) {
            this.text = text;
            this.action = action;
        }
    }

    /**
     * The recipe picker: every recipe that makes this node's key.
     *
     * `alternatives` is a COUNT, not a list -- the plan shape carries how many there are and
     * which one was taken, not their contents, because the tree would be enormous if it
     * inlined every candidate. So the picker lists what it can say truthfully today: the
     * chosen recipe and its category, and how many others there are. Filling the rest needs
     * the graph, which arrives with the Java solver in #141.
     */
    public static ModularPanel recipePicker(PlanNode node) {
        int width = PICKER_WIDTH;
        int rows = 3;
        int height = PADDING * 2 + LINE + 1 + rows * ROW_HEIGHT;
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(width - PADDING * 2, height - PADDING * 2);
        int inner = width - PADDING * 2;
        body.child(line("Recipes for " + NodeRowText.label(node), inner,
                        NodeStatus.INK_MUTED).pos(0, 0));
        int y = LINE + 1;
        body.child(line("in use: " + orDash(node.category()), inner, NodeStatus.INK_CRAFT)
                           .pos(0, y));
        y += ROW_HEIGHT;
        body.child(line("id: " + orDash(node.recipe()), inner, NodeStatus.INK_MUTED).pos(0, y));
        y += ROW_HEIGHT;
        body.child(line(alternativesLine(node), inner, NodeStatus.INK_MUTED).pos(0, y));
        return ModularPanel.defaultPanel("mcrecipedump_recipe_picker", width, height)
                .child(body);
    }

    static String alternativesLine(PlanNode node) {
        if (node.alternatives() <= 1) {
            return "no other recipe makes this";
        }
        return (node.alternatives() - 1) + " other recipe(s) -- pinning arrives with the solver";
    }

    /**
     * The TODO panel: the plan book's list, with what the plan still needs.
     *
     * "Still needed" is recomputed from the plan rather than stored, because the book holds
     * what the player ASKED for and the plan holds what is left after stock. Storing the
     * difference would make it a number that goes stale the moment anything is crafted.
     *
     * SCROLLED AND CAPPED, not sized to its contents. The first version grew a row per line
     * and the screenshot of `plan-truncated` was a panel 23 rows tall running off the top and
     * bottom of the screen -- the shopping list is as long as the plan is unfinished, and
     * there is no bound on that. The layout tests had not caught it because they asserted
     * every widget HAD a box, not that the box was on screen; `everyPanelFitsTheSmallestScreen`
     * is the assertion that does.
     */
    public static ModularPanel todoPanel(PlanView plan, PlanBook book) {
        int width = 260;
        int inner = width - PADDING * 2;

        List<String> lines = new java.util.ArrayList<String>();
        List<Integer> colours = new java.util.ArrayList<Integer>();
        for (String key : book.todoKeys()) {
            lines.add(NodeRowText.quantity(book.todoQuantity(key)) + " " + key);
            colours.add(NodeStatus.INK_CRAFT);
        }
        if (lines.isEmpty()) {
            lines.add("nothing on the list");
            colours.add(NodeStatus.INK_MUTED);
        }
        lines.add("still needed for this plan:");
        colours.add(NodeStatus.INK_MUTED);
        if (plan.shoppingList().isEmpty()) {
            lines.add("nothing outstanding");
            colours.add(NodeStatus.INK_MUTED);
        }
        for (PlanView.ShoppingRow row : plan.shoppingList()) {
            lines.add(NodeRowText.quantity(row.need()) + " " + row.label());
            colours.add(NodeStatus.INK_NEED);
        }

        int listHeight = Math.min(lines.size() * ROW_HEIGHT, TODO_MAX_LIST_HEIGHT);
        int height = PADDING * 2 + LINE + 1 + listHeight;

        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(inner, height - PADDING * 2);
        body.child(line("TODO", inner, NodeStatus.INK_MUTED).pos(0, 0));
        body.child(rowList(lines, colours, inner, listHeight).pos(0, LINE + 1));
        return ModularPanel.defaultPanel("mcrecipedump_todo", width, height).child(body);
    }

    /** A scrollable column of one-line rows. The same `ListWidget` reasoning as {@link #tree}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ListWidget<?, ?> rowList(List<String> lines, List<Integer> colours,
                                            int width, int height) {
        ListWidget list = new ListWidget();
        list.size(width, height);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        for (int i = 0; i < lines.size(); i++) {
            Group row = new Group();
            row.size(width, ROW_HEIGHT);
            row.child(line(lines.get(i), width, colours.get(i).intValue()).pos(0, 0));
            list.child(row);
        }
        return list;
    }

    /**
     * The panel shown when there is no plan: loading, solving, failed, or simply idle.
     *
     * Same size as the real planner, so opening one and then the other does not make the
     * window jump.
     */
    public static ModularPanel statePanel(PlannerState state) {
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);
        body.child(line("Planner", CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, 0));
        body.child(line(state.message(), CONTENT_WIDTH, state.colour()).pos(0, LINE + 1));
        return ModularPanel.defaultPanel("mcrecipedump_planner_state",
                                         PANEL_WIDTH, PANEL_HEIGHT).child(body);
    }

    private static String footer(PlanView plan, PlanBook book) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.nodes()).append(" nodes");
        if (!plan.shoppingList().isEmpty()) {
            sb.append(NodeRowText.SEPARATOR).append(plan.shoppingList().size())
              .append(" still needed");
        }
        if (!plan.machinesToBuild().isEmpty()) {
            sb.append(NodeRowText.SEPARATOR).append(plan.machinesToBuild().size())
              .append(" machine(s) to build");
        }
        if (!book.todoKeys().isEmpty()) {
            sb.append(NodeRowText.SEPARATOR).append(book.todoKeys().size()).append(" on TODO");
        }
        return sb.toString();
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "--" : value;
    }

    /** The icon column's widget. Only built when there is something to draw. */
    private static ItemDisplayWidget icon(net.minecraft.item.ItemStack stack) {
        ItemDisplayWidget widget = new ItemDisplayWidget();
        widget.size(ICON, ROW_HEIGHT);
        widget.item(stack);
        return widget;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static TextWidget<?> heading(String text, int width) {
        return line(text, width, NodeStatus.INK_CRAFT);
    }

    /**
     * One line of text, coloured, and CUT TO FIT.
     *
     * The cut is the load-bearing part. A `TextWidget` handed more text than its box wraps
     * onto a second line and draws over whatever is beneath it; it does not clip, and the
     * sizer reports nothing wrong because every row is still exactly `ROW_HEIGHT` tall. See
     * {@link NodeRowText#fit}.
     *
     * `TextWidget.color(int)` rather than `IKey.color(int)`: the key's colour is a style on
     * the drawable, and the widget's own colour is what its theme lookup actually consults.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static TextWidget<?> line(String text, int width, int colour) {
        TextWidget widget = new TextWidget(IKey.str(NodeRowText.fit(text, width)));
        widget.color(colour);
        widget.size(width, LINE);
        return widget;
    }
}
