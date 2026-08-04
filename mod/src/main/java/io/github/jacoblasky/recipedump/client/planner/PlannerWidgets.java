package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Pins;

import java.util.List;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
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
     * `plan-fluid-chain` is 634 nodes and runs deeper than eight levels; without a cap the
     * label column would be pushed off the right edge and, per rule 2 above, nothing would
     * say so. The tree structure is still readable because the rows above are still indented.
     */
    public static final int MAX_INDENT_DEPTH = 8;

    /**
     * The item-icon column, filled by {@link NodeActions#iconFor}.
     *
     * THE ONE STATEMENT OF THE COLUMN RULE, for both builders below to point at rather than
     * restate. `x` ADVANCES BY THIS WHETHER OR NOT AN ICON IS DRAWN: a width that depended on
     * whether a stack came back would re-flow every row the moment a `NodeActions` was
     * installed, or a graph finished loading mid-session and `iconFor` began answering.
     *
     * THE WIDGET, unlike the width, is added only when there is a stack -- an
     * `ItemDisplayWidget` holding EMPTY draws its slot frame rather than nothing, which is the
     * sort of claim that reads identically either way in review and is only true one way
     * round. Measured: 49 empty boxes down the left edge of the first screenshot.
     */
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
     * The TODO panel's width. 400, WIDENED FROM 260 BY #190, for the reason
     * {@link #PICKER_WIDTH} went from 330: the rows grew a column that tells them apart and
     * 260 cut it.
     *
     * MEASURED, NOT ESTIMATED. The panel carries five sections now instead of one and their
     * rows are no longer "some quantity of a name": a shopping row whose label collides with
     * another carries its registry key, and a free row names the generator supplying it. The
     * longest key-bearing row across every fixture is
     * `1x Soul Vial -- enderio:item_soul_vial:1#40f3a0f3892d`, 52 characters. At 260 the inner
     * width is 248 pixels, which is 41 characters at {@link NodeRowText#CHAR_WIDTH}, so `fit`
     * cut off precisely the column added to tell the two Soul Vials apart. At 400 the inner
     * width is 388, which is 64, and it fits.
     *
     * 400 matches {@link #PANEL_WIDTH}, which the class header already sizes against the
     * 427-pixel smallest screen Minecraft allows, so this needs no separate measurement.
     *
     * IT DOES NOT MAKE EVERY ROW FIT, and nothing can: a machine row runs to 124 characters,
     * which is 744 pixels. Those are wrapped rather than cut; see {@link
     * NodeRowText#machineLines}.
     */
    public static final int TODO_WIDTH = PANEL_WIDTH;

    /**
     * Secondary panel widths, sized to their longest line at {@link NodeRowText#CHAR_WIDTH}.
     *
     * Fixed rather than fitted, because fitting means measuring, and measuring needs a font
     * renderer this build does not have. Both were found too narrow by a screenshot: the menu
     * cut "Choose another recipe (172)" and the picker cut its own explanation.
     */
    public static final int MENU_WIDTH = 200;
    /**
     * 400, WIDENED FROM 330 BY A SCREENSHOT. At 330 the picker for `minecraft:iron_ingot`
     * -- 172 candidates, the worst case in the fixture set -- cut the category off most of
     * its rows, which is the one column that tells them apart. 400 is the main panel's width
     * and still fits the 427-pixel smallest screen.
     */
    public static final int PICKER_WIDTH = 400;

    /**
     * The state column in the recipe picker, wide enough for every word it can hold.
     *
     * Derived from {@link #choiceState} rather than measured by eye; see
     * {@link #widestChoiceState}.
     */
    public static final int CHOICE_STATE = widestChoiceState();

    /**
     * The tallest the picker's list may get before it scrolls.
     *
     * {@link RecipeChoices#MAX_SHOWN} is 24 and a row is 11 pixels, so an uncapped list is
     * 264 -- taller than the 240-pixel screen the whole package is sized for, before the
     * heading and the two footer lines. The same failure the TODO panel had, found the same
     * way, so it is capped the same way rather than waiting to be photographed again.
     */
    public static final int PICKER_MAX_LIST_HEIGHT = 154;

    /**
     * The most of a category name the picker will show, in characters.
     *
     * A CAP AND NOT A FIXED WIDTH, because pack categories run from `smelting` to
     * `modularmachinery.recipes.ender_stone` and a column sized for the longest would spend
     * 36 characters on every picker to serve one. Sized to the longest actually shown; see
     * {@link #categoryWidth}.
     */
    public static final int MAX_CATEGORY_CHARS = 24;

    private PlannerWidgets() {
    }

    /**
     * How many warning lines the planner panel will show before it summarises the rest.
     *
     * A CAP BECAUSE THE TREE PAYS FOR THEM. There is one warning per pin the solver could not
     * honour and a player has as many of those as they made choices, so an uncapped list eats
     * the panel from the top and eventually leaves the tree no height at all -- and a
     * ListWidget sized to nothing is a plan that silently does not render. What is left out
     * is counted rather than dropped, for the reason the picker's cap is.
     */
    public static final int MAX_WARNINGS = 3;

    /**
     * How many lines the "planned without" caveat may wrap over.
     *
     * Two fits today's five unread inputs at 64 characters a line with room to spare; three
     * leaves headroom for the ones Phase 5 has not turned live yet without the tree noticing.
     *
     * MEASURED AGAIN AT #190, when the line grew a pointer at the caveats panel: the five
     * fields plus `-- click for what each costs you` is 109 characters, which is still two
     * lines. The headroom is what absorbed it.
     *
     * AND IT IS WHY THE NOTES THEMSELVES ARE NOT HERE. Three lines is 192 characters and the
     * five hand-written notes are around 470, so printing them on this panel would take a third
     * of the body off the tree. That constraint is the whole reason `PlanCaveats` splits the
     * disclosure in two; DO NOT raise this to fit them, raise it only if the FIELD LIST grows
     * past two lines.
     */
    public static final int MAX_CAVEAT_LINES = 3;

    /**
     * The narrowest label worth drawing, in pixels: eight characters.
     *
     * Below this the label is not shortened, it is GONE -- and a node showing a quantity, a
     * full badge and no item name is the least useful of the possibilities. See
     * {@link #badgeWidthFor}.
     */
    public static final int MIN_LABEL = 48;

    /**
     * How much room the badge gets: all of it, or none.
     *
     * A BADGE IS ALL-OR-NOTHING AND A LABEL MAY BE CUT, which is the rule the rest of this
     * class already implies -- if a truncated badge is always a bug because the vocabulary is
     * fixed, then the alternative to truncating is omitting, not shrinking.
     *
     * It took a second reader to notice that shrinking was also WRONGLY PRIORITISED. The badge
     * was sized first and the label took what was left, so the middle of the range was worse
     * than either end: measured by Phase 3b across the diagram's node widths, a 148px node
     * carried a perfect 15-character "no known source" and a label of zero characters. The
     * label is the item's name; it wins.
     *
     * Nothing is lost by dropping the badge on a small node. `NodeStatus.colour` is already
     * applied to the quantity, so status keeps a channel, and the diagram puts the word in a
     * tooltip.
     *
     * @param width      the whole node or row
     * @param labelStart where the label column begins, so an indent is already accounted for
     */
    public static int badgeWidthFor(int width, int labelStart) {
        return width - labelStart - GAP - BADGE >= MIN_LABEL ? BADGE : 0;
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

    /**
     * A panel wrapping the flow diagram, sized to the screen.
     *
     * Here rather than in `client.flow` so the diagram keeps its one dependency on this
     * package pointing the same way as everything else: `flow` calls `planner`, never the
     * reverse. A panel is chrome, and chrome lives with the rest of the chrome.
     */
    public static ModularPanel flowPanel(IWidget canvas) {
        // NEARLY THE WHOLE SCREEN, unlike the tree panel. A diagram is only useful at the
        // width it can show a couple of columns in, and a node is 214px because that is what
        // a full row needs -- a 360px panel shows one column and half of the next, which is a
        // list with extra steps. 620x380 fits a 1280x800 client at the default 2x GUI scale.
        ModularPanel panel = ModularPanel.defaultPanel("mcrecipedump_flow", 620, 380);
        panel.child(canvas);
        return panel;
    }

    /** A parent that positions its children absolutely. Concrete because `ParentWidget` is
     *  F-bounded and cannot be instantiated. Adds nothing. */
    public static final class Group extends ParentWidget<Group> {
    }

    /**
     * A row that can be clicked.
     *
     * `Interactable` on the ROW rather than a `ButtonWidget` around it, because a button
     * brings its own themed background and hover fill, and 634 of those down a tree panel is
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
                                            final PlannerActions actions) {
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);

        int y = 0;
        body.child(heading(NodeRowText.heading(plan), CONTENT_WIDTH).pos(0, y));
        y += LINE + 1;

        // RED, AND ABOVE THE TREE rather than under it. Both of these are reasons the tree
        // below is not what it appears to be -- a truncated plan is shaped like a complete
        // one, and an overruled pin produces the route the player just rejected -- so a
        // reader who has to scroll to find out has already been misled. `render.py` puts the
        // same two in one warnbar, in this order, and `PlanView.pinsOverruled` says why the
        // second of them went unsaid in game until a screenshot caught it.
        for (String warning : warnings(plan)) {
            body.child(line(warning, CONTENT_WIDTH, NodeStatus.INK_NEED).pos(0, y));
            y += LINE;
        }

        // THE CAVEAT IS ITS OWN LINE AND NOT PART OF THE FOOTER, because it is not a
        // statistic about the plan -- it is the reason the plan may be wrong. An unread
        // `have` is the claim "you own nothing", so a tree built on it can tell a player to
        // fetch iron they are standing beside. Folding it in next to "15 nodes" would read as
        // another count. See `common.ScenarioSource`.
        //
        // WRAPPED RATHER THAN CUT, which is the one place in this class that is true. The
        // caveat is a LIST that grows with the number of unread inputs -- five of them at the
        // time of writing, 74 characters, and a 64-character line -- so `fit` was dropping
        // `emc_knowledge` off the end of the sentence whose entire job is to be complete.
        // A truncated label is a name you can still guess at; a truncated list of what the
        // planner could not see is a shorter, wrong list.
        //
        // RESERVED BEFORE THE TREE IS SIZED, not appended after. The tree takes whatever is
        // left, so a line added below it without taking its height out first is drawn past
        // the bottom of the panel -- ModularUI neither clamps nor clips a child (#125).
        //
        // IT NAMES THE FIELDS AND POINTS AT THE SENTENCES, which is #190. The line came from
        // `ScenarioSource.summary()` alone, so a player read `planned without: have` and the
        // hand-written sentence saying what an unread `have` COSTS them -- collected by
        // `missingNotes()`, whose only callers were two tests -- never reached a screen. It
        // cannot be printed here: five notes is around 470 characters against a 64-character
        // line, which would spend half this panel on caveats. See `PlanCaveats` for the split.
        List<String> caveat = NodeRowText.wrap(PlanCaveats.summaryLine(), CONTENT_WIDTH,
                                               MAX_CAVEAT_LINES);
        int footerLines = 1 + caveat.size();
        int treeHeight = PANEL_HEIGHT - PADDING * 2 - y - LINE * footerLines - 2;
        body.child(tree(plan, CONTENT_WIDTH, treeHeight, actions).pos(0, y));
        y += treeHeight + 2;
        body.child(line(footer(plan, book), CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, y));
        if (!caveat.isEmpty()) {
            // ONE CLICK TARGET OVER THE WHOLE CAVEAT, not one per line: the lines are one
            // wrapped sentence, and a reader who clicked its second half and got nothing would
            // conclude the panel does not open rather than that they had missed.
            ClickableGroup rows = new ClickableGroup(new Runnable() {
                @Override
                public void run() {
                    actions.openCaveats();
                }
            });
            rows.size(CONTENT_WIDTH, LINE * caveat.size());
            for (int i = 0; i < caveat.size(); i++) {
                rows.child(line(caveat.get(i), CONTENT_WIDTH, NodeStatus.INK_NEED)
                                   .pos(0, i * LINE));
            }
            // The block starts one line below the footer, and `footerLines` above already took
            // its whole height out of the tree.
            body.child(rows.pos(0, y + LINE));
        }

        return ModularPanel.defaultPanel("mcrecipedump_planner", PANEL_WIDTH, PANEL_HEIGHT)
                .child(body);
    }

    /**
     * Every reason the tree below should not be taken at face value, in `render.py`'s order.
     *
     * NOT CAPPED, deliberately, unlike the picker's list. There is one entry per pin the
     * solver could not honour and a player has as many of those as they made choices -- but
     * dropping one means a click that appears to have worked and did not, which is the whole
     * failure this line exists to report. The panel reserves room for what this returns; see
     * {@link #plannerPanel}.
     */
    static List<String> warnings(PlanView plan) {
        List<String> all = new java.util.ArrayList<String>();
        String truncation = NodeRowText.truncationWarning(plan);
        if (!truncation.isEmpty()) {
            all.add(truncation);
        }
        all.addAll(plan.pinsOverruled());
        if (all.size() <= MAX_WARNINGS) {
            return all;
        }
        List<String> out = new java.util.ArrayList<String>(all.subList(0, MAX_WARNINGS - 1));
        out.add("+" + (all.size() - (MAX_WARNINGS - 1))
                + " more recipe choice(s) could not be used");
        return out;
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
     * CLICKABLE, LIKE {@link #row}, AND THIS COMMENT USED TO SAY THE OPPOSITE. It read "a
     * canvas of clickable rows would fight" the viewport's own hit-testing, which was an
     * assumption written as though it were a constraint somebody had paid for -- and it
     * contradicted `FlowCanvas`'s header, which says hit-testing THROUGH the transform comes
     * free from `AbstractScrollWidget` and that hand-rolling it is the trap. Both could not be
     * right, and the wrong one would have sent the next reader to hand-rolled coordinate maths:
     * a false justification does not merely fail to help, it routes people into the thing the
     * true one warns them off.
     *
     * MEASURED BY s1harness IN #185, once the harness had a cursor. `flow-hit` parks the real
     * mouse over each node and compares `IWidget.isHovering()` against the layout's own answer:
     * they AGREE at zoom 0.5, 1.0 and 2.0, so `getWidgetsAt` routes correctly through the
     * scroll offset and the zoom matrix alike. That is what the viewport transform is for.
     *
     * @param actions where a click goes. {@link PlannerActions#NONE} for a layout assertion or
     *                a screenshot, which is what the three-argument overload passes.
     *
     * The colours come from {@link NodeStatus} and must keep doing so: `present.py`'s own
     * docstring records that node statuses are drawn by four components which each kept their
     * own dict of bare strings, so adding a status drew silently wrong. `NodeStatusTest` reads
     * that file and asserts both directions, so there is one mapping and it is enforced.
     */
    public static ParentWidget<?> planNodeContent(final PlanNode node, int width, int height,
                                                  final PlannerActions actions) {
        ClickableGroup box = new ClickableGroup(new Runnable() {
            @Override
            public void run() {
                actions.openNodeMenu(node);
            }
        });
        box.size(width, height);
        int x = 0;
        // Width unconditional, widget conditional. Both halves and the measurement behind them
        // are stated once, on ICON; DO NOT restate them here or make either half depend on the
        // stack.
        net.minecraft.item.ItemStack stack = NodeActionsHolder.actions().iconFor(node);
        if (!stack.isEmpty()) {
            box.child(icon(stack).pos(x, 0));
        }
        x += ICON + GAP;
        int colour = NodeStatus.colour(node);
        box.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, 0));
        x += QTY + GAP;
        int badgeWidth = badgeWidthFor(width, x);
        int labelWidth = Math.max(GAP, width - x - (badgeWidth > 0 ? badgeWidth + GAP : 0));
        box.child(line(NodeRowText.label(node), labelWidth, NodeStatus.INK_MUTED).pos(x, 0));
        if (badgeWidth > 0) {
            box.child(line(NodeStatus.badge(node), badgeWidth, colour)
                              .pos(width - badgeWidth, 0));
        }
        return box;
    }

    /**
     * {@link #planNodeContent} with nothing behind the click. THE INERT ONE.
     *
     * Named as such rather than left as a quiet default, because the failure it enables is a
     * canvas of nodes that look right and do nothing when clicked -- and no test catches that,
     * since a layout assertion is exactly what this overload is FOR. If a diagram is meant to
     * open menus and does not, this is the overload it is on.
     */
    public static ParentWidget<?> planNodeContent(PlanNode node, int width, int height) {
        return planNodeContent(node, width, height, PlannerActions.NONE);
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

        // Width unconditional, widget conditional. Both halves and the measurement behind them
        // are stated once, on ICON; DO NOT restate them here or make either half depend on the
        // stack.
        net.minecraft.item.ItemStack stack = NodeActionsHolder.actions().iconFor(node);
        if (!stack.isEmpty()) {
            row.child(icon(stack).pos(x, 0));
        }
        x += ICON + GAP;

        int colour = NodeStatus.colour(node);
        row.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, 0));
        x += QTY + GAP;

        // The same all-or-nothing rule as the diagram node. A full-width tree row never hits
        // it, even at the indent cap -- but a row is not always full width, and the latent
        // version of this bug is the one that gets found by a screenshot rather than a test.
        int badgeWidth = badgeWidthFor(width, x);
        int labelWidth = Math.max(GAP, width - x - (badgeWidth > 0 ? badgeWidth + GAP : 0));
        row.child(line(labelAndMeta(node), labelWidth, NodeStatus.INK_MUTED).pos(x, 0));
        if (badgeWidth > 0) {
            row.child(line(NodeStatus.badge(node), badgeWidth, colour)
                              .pos(width - badgeWidth, 0));
        }
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
     * The recipe picker: every recipe that makes this node's key, and clicking one takes it.
     *
     * `alternatives` on the node is a COUNT, not a list -- the plan shape carries how many
     * candidates there are and which one was taken, not their contents, because a tree that
     * inlined every candidate would be enormous. So the list comes from {@link RecipeChoices},
     * which asks the graph once when the picker opens. This method still takes only data:
     * nothing here touches a graph, which is what keeps the layout assertions windowless.
     *
     * A ROW IS A COMMITMENT, SO IT SAYS WHICH ONE IT IS. Every row carries its state as a
     * WORD in a fixed column -- "in use", "pinned" -- and not only as a colour, for the
     * reason `NodeStatus` exists at all: a status drawn as a colour alone is a status a
     * colour-blind player cannot read, and `present.py` already lost that argument once.
     *
     * @param choices the candidates, or {@link RecipeChoices#none} carrying why there are
     *                none. The empty case prints that reason rather than an empty box.
     */
    public static ModularPanel recipePicker(final PlanNode node, RecipeChoices choices,
                                            final PlannerActions actions) {
        int width = PICKER_WIDTH;
        int inner = width - PADDING * 2;

        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.child(line("Recipes for " + NodeRowText.label(node), inner, NodeStatus.INK_MUTED)
                           .pos(0, 0));

        int y = LINE + 1;
        int listHeight = 0;
        if (choices.isEmpty()) {
            body.child(line(choices.why(), inner, NodeStatus.INK_NEED).pos(0, y));
            y += ROW_HEIGHT;
        } else {
            listHeight = Math.min(choices.shown().size() * ROW_HEIGHT, PICKER_MAX_LIST_HEIGHT);
            body.child(choiceList(node, choices, actions, inner, listHeight).pos(0, y));
            y += listHeight;
            for (String footer : pickerFooter(choices)) {
                body.child(line(footer, inner, NodeStatus.INK_MUTED).pos(0, y));
                y += ROW_HEIGHT;
            }
        }

        int height = y + PADDING * 2;
        body.size(inner, height - PADDING * 2);
        return ModularPanel.defaultPanel("mcrecipedump_recipe_picker", width, height)
                .child(body);
    }

    /**
     * The lines under the list: what a click does, and what the cap left out.
     *
     * THE CAP IS REPORTED RATHER THAN SILENT, for the same reason a truncated plan is. A list
     * of 24 out of 172 that does not say so is a player concluding the pack has 24 ways to
     * make a thing.
     */
    static List<String> pickerFooter(RecipeChoices choices) {
        List<String> out = new java.util.ArrayList<String>(2);
        out.add("click one to use it; click the pinned one to unpin");
        if (choices.more() > 0) {
            out.add("showing " + choices.shown().size() + " of " + choices.total()
                    + NodeRowText.SEPARATOR + choices.more() + " not shown");
        }
        return out;
    }

    /** One clickable row per candidate, scrolled. Same `ListWidget` reasoning as {@link #tree}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ListWidget<?, ?> choiceList(final PlanNode node, RecipeChoices choices,
                                               final PlannerActions actions, int width,
                                               int height) {
        ListWidget list = new ListWidget();
        list.size(width, height);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        // ONE WIDTH FOR THE WHOLE LIST, computed once from every row, so the columns line up
        // down the panel. Per-row would give a ragged right edge and make the categories
        // unscannable, which is the only thing they are there for.
        int categoryWidth = categoryWidth(choices, width);
        for (final RecipeChoice choice : choices.shown()) {
            list.child(choiceRow(node, choice, actions, width, categoryWidth));
        }
        return list;
    }

    /**
     * How wide the category column gets: the longest one shown, capped, and never at the
     * cost of the label falling under {@link #MIN_LABEL}.
     *
     * THE CATEGORY WINS HERE AND THE LABEL IS CUT, WHICH IS THE OPPOSITE OF {@link #row}, and
     * the inversion is the point rather than an inconsistency. On a tree row the label is the
     * item's name and is the whole reason the row exists. In the picker EVERY row is the same
     * item, so every label starts with the same words -- the screenshot of the 172-candidate
     * iron-ingot picker was fourteen rows all reading "Iron Ingot from ..." with the category
     * cut off the end, which is a list with no distinguishing column at all. Here the
     * category is what tells the rows apart.
     */
    static int categoryWidth(RecipeChoices choices, int rowWidth) {
        int longest = 0;
        for (RecipeChoice choice : choices.shown()) {
            longest = Math.max(longest, choice.category().length());
        }
        int wanted = Math.min(longest, MAX_CATEGORY_CHARS) * NodeRowText.CHAR_WIDTH;
        int room = rowWidth - CHOICE_STATE - GAP - GAP - MIN_LABEL;
        return Math.max(0, Math.min(wanted, room));
    }

    /** One candidate: its state, what it makes from what, and the category that names it. */
    static ParentWidget<?> choiceRow(final PlanNode node, final RecipeChoice choice,
                                     final PlannerActions actions, int width,
                                     int categoryWidth) {
        ClickableGroup row = new ClickableGroup(new Runnable() {
            @Override
            public void run() {
                actions.pinRecipe(node, choice);
            }
        });
        row.size(width, ROW_HEIGHT);
        int colour = choiceColour(choice);
        row.child(line(choiceState(choice), CHOICE_STATE, colour).pos(0, 0));
        int x = CHOICE_STATE + GAP;
        int labelWidth = Math.max(GAP, width - x
                - (categoryWidth > 0 ? categoryWidth + GAP : 0));
        row.child(line(choice.label(), labelWidth, NodeStatus.INK_MUTED).pos(x, 0));
        if (categoryWidth > 0) {
            row.child(line(choice.category(), categoryWidth, colour)
                              .pos(width - categoryWidth, 0));
        }
        return row;
    }

    /**
     * "in use", "pinned", "in use, pinned", or nothing.
     *
     * BOTH AT ONCE IS A REAL STATE and worth spelling out: pinning the recipe the solver
     * already chose is the whole point of a pin -- it stops the ranking moving off it when
     * the pack or the player's stock changes. A row that showed only one of the two would
     * make that click look like it had done nothing.
     */
    static String choiceState(RecipeChoice choice) {
        if (choice.inUse() && choice.pinned()) {
            return "both";
        }
        if (choice.inUse()) {
            return "in use";
        }
        return choice.pinned() ? "pinned" : "";
    }

    /** Pinned outranks in-use: a pin is the player's decision, the other is the solver's. */
    static int choiceColour(RecipeChoice choice) {
        if (choice.pinned()) {
            return NodeStatus.INK_OK;
        }
        return choice.inUse() ? NodeStatus.INK_CRAFT : NodeStatus.INK_MUTED;
    }

    /**
     * The widest word {@link #choiceState} can produce, in pixels. Derived, like {@link #BADGE}.
     *
     * BY ASKING THE METHOD, not by restating its vocabulary in a list beside it. The badge
     * column was sized from a list once and came out at 66 pixels, and the screenshot read
     * "no known..." -- a fixed vocabulary truncated is always a bug, and a second copy of the
     * vocabulary is how the first one gets a word added without the column noticing.
     */
    private static int widestChoiceState() {
        int widest = 0;
        Pins.Pin ignored = new Pins.Pin("", "", "");
        for (int combination = 0; combination < 4; combination++) {
            RecipeChoice choice = new RecipeChoice(-1, "", ignored,
                                                   (combination & 1) != 0,
                                                   (combination & 2) != 0);
            widest = Math.max(widest, choiceState(choice).length());
        }
        return widest * NodeRowText.CHAR_WIDTH;
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
        int width = TODO_WIDTH;
        int inner = width - PADDING * 2;

        List<String> lines = new java.util.ArrayList<String>();
        List<Integer> colours = new java.util.ArrayList<Integer>();
        for (String key : book.todoKeys()) {
            // THE SAME UNIT RULE AS THE SECTIONS BELOW, WHICH THE FIRST SCREENSHOT OF THE WIDER
            // PANEL IS WHAT CAUGHT. These rows used a bare `quantity`, so 934,400 mB of water
            // read as `934,400x fluid:water` four lines above a shopping row rendering the same
            // fluid as `934,400 mB Water`. One panel cannot measure the same fluid two ways.
            //
            // THE KEY STAYS RATHER THAN A LABEL, and that half is not a defect: the plan book
            // stores keys and has no display name to draw, which is the honest thing to show
            // for a row the player added by key. Only the unit was wrong.
            lines.add(NodeRowText.amount(book.todoQuantity(key), Keys.kind(key)) + " " + key);
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
        // THROUGH `entryLines` RATHER THAN COMPOSED HERE, and the reason is that two of these
        // rows can carry the same name. A row built as label plus quantity draws
        // `plan-fluid-chain`'s two Soul Vials as two lines a player cannot tell apart, and the
        // mark saying nothing in the graph makes a row was dropped entirely. Both need the
        // WHOLE list to decide -- which labels collide is not a property of one row -- so the
        // composition lives beside the other row text and this takes what it returns.
        addSection(lines, colours, null, NodeRowText.entryLines(plan.shoppingList(), inner),
                   NodeStatus.INK_NEED);
        // THE FOOTER'S "N machine(s) to build" IS A COUNT AND THESE ARE THE MACHINES. #190:
        // every `MachineRow` accessor but `size()` was called from a test and nowhere else, so
        // the player was told how many machines stood in the way and could never learn which.
        addSection(lines, colours, "machines to build:",
                   NodeRowText.machineLines(plan.machinesToBuild(), inner),
                   NodeStatus.INK_WARN);
        // THE FOUR SUMMARY LISTS, AS SECTIONS OF THIS LIST RATHER THAN FOUR PANELS. #190 asked
        // for the decision to be made explicitly rather than by Phase 6 deleting their only
        // reader, so: all four are read and all four are drawn HERE.
        //
        // NOT FOUR PANELS, because they are four instances of one shape -- a quantity, a name
        // and one optional decoration -- and the browser's four cards are affordable on a
        // browser page. This window is 400 pixels wide inside a 427-pixel minimum screen, and
        // two of the four are empty until #50 and #112 land, so four panels would be four
        // copies of one ListWidget of which two are permanently blank. A section header costs
        // one row and an empty section costs nothing, which is what `addSection` is for.
        //
        // AND HERE RATHER THAN ON THE PLAN PANEL, because this is the surface a player works
        // FROM while gathering. "You already own 377 of these" belongs next to "go and get
        // these", not under a tree.
        addSection(lines, colours, "used from your stock:",
                   NodeRowText.entryLines(plan.usedFromStock(), inner), NodeStatus.INK_OK);
        addSection(lines, colours, "drawn from infinite sources:",
                   NodeRowText.entryLines(plan.fromSources(), inner), NodeStatus.INK_OK);
        addSection(lines, colours, "go and get:",
                   NodeRowText.entryLines(plan.tokensNeeded(), inner), NodeStatus.INK_NEED);
        addSection(lines, colours, "transmuted from EMC:",
                   NodeRowText.entryLines(plan.fromEmc(), inner), NodeStatus.INK_OK);

        int listHeight = Math.min(lines.size() * ROW_HEIGHT, TODO_MAX_LIST_HEIGHT);
        int height = PADDING * 2 + LINE + 1 + listHeight;

        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(inner, height - PADDING * 2);
        body.child(line("TODO", inner, NodeStatus.INK_MUTED).pos(0, 0));
        body.child(rowList(lines, colours, inner, listHeight).pos(0, LINE + 1));
        return ModularPanel.defaultPanel("mcrecipedump_todo", width, height).child(body);
    }

    /**
     * A headed group of rows, or nothing at all when there are no rows.
     *
     * NOTHING AT ALL, WHICH IS THE POINT AND IS WHY THE HEADER IS THIS METHOD'S BUSINESS. Two
     * of the four summary lists are empty until #50 and #112 land, and a header standing over
     * no rows is a claim the panel cannot support -- "transmuted from EMC:" followed by blank
     * space reads as a list that failed to load rather than as one that is legitimately empty.
     * A caller that added the header itself would have to remember the guard four times.
     *
     * @param header null for a section whose caller already wrote its own heading, which is
     *               the shopping list: it has an explicit "nothing outstanding" row because it
     *               is the one section whose emptiness is worth stating.
     */
    static void addSection(List<String> lines, List<Integer> colours, String header,
                           List<String> rows, int colour) {
        if (rows.isEmpty()) {
            return;
        }
        if (header != null) {
            lines.add(header);
            colours.add(NodeStatus.INK_MUTED);
        }
        for (String row : rows) {
            lines.add(row);
            colours.add(Integer.valueOf(colour));
        }
    }

    /**
     * What the planner could not see, and what each missing input costs the player.
     *
     * THE PANEL BEHIND THE CAVEAT LINE (#190). `ScenarioSource.missingNotes` collected five
     * hand-written player-facing sentences whose only callers were `ScenarioSourceTest` and
     * `PinStoreTest`, so the planner named the missing FIELDS on its caveat line and never
     * said what a missing field costs. `PlanCaveats` carries the argument for why this is a
     * second panel rather than more lines on the first one.
     *
     * SIZED TO ITS CONTENT AND NOT CAPPED, unlike every other list in this class. The caps
     * elsewhere exist because a tree or a viewport pays for the height; nothing is underneath
     * this, and a cut-off list of what the planner could not see is a shorter, wrong list.
     * Five notes wrap to about eleven lines, which is 110 pixels of a 240-pixel screen.
     */
    public static ModularPanel caveatsPanel() {
        // `CONTENT_WIDTH` IS ALREADY `PANEL_WIDTH - PADDING * 2`; do not recompute it here.
        int inner = CONTENT_WIDTH;
        List<String> lines = PlanCaveats.detailLines(inner);

        int height = PADDING * 2 + LINE + 1 + lines.size() * LINE;
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(inner, height - PADDING * 2);
        // THE TITLE CARRIES THE ALARM AND THE NOTES DO NOT, which the first screenshot of this
        // panel is what settled. Every note was drawn in `INK_NEED` and eleven lines of red on
        // grey read as five errors rather than as five explanations -- and red is used nowhere
        // else in this class for prose, only for the one-to-three line warnings above the tree
        // and the caveat line under it. One alarm and five readable paragraphs is the same
        // hierarchy `badgeWidthFor` argues for between a badge and a label.
        //
        // THIS IS NOT THE "TIDY FILLER" `ScenarioSource.Status.unavailable` WARNS ABOUT. That
        // warning is about WORDING -- a reason phrased so blandly nobody reports it -- and the
        // wording here is the hand-written sentence itself. The player also arrived by clicking
        // a red line, so the panel does not have to shout to be understood as a caveat.
        body.child(line(PlanCaveats.TITLE, inner, NodeStatus.INK_NEED).pos(0, 0));
        int y = LINE + 1;
        for (String text : lines) {
            // ALREADY WRAPPED, so `line` has nothing left to cut. Passing the full width is
            // what keeps that true: a narrower box here would truncate text that was measured
            // against `inner`, and the ellipsis would land mid-sentence in the one place in
            // this class where a sentence has to arrive whole.
            body.child(line(text, inner, NodeStatus.INK_MUTED).pos(0, y));
            y += LINE;
        }
        return ModularPanel.defaultPanel("mcrecipedump_plan_caveats", PANEL_WIDTH, height)
                .child(body);
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

    /**
     * The icon column's widget. ONLY BUILT WHEN THERE IS SOMETHING TO DRAW.
     *
     * `ItemDisplayWidget` holding `ItemStack.EMPTY` paints its slot frame, not nothing. Both
     * callers guard on that and both say why, because "returns EMPTY and nothing draws" and
     * "returns EMPTY and a slot frame draws" are indistinguishable in a code review.
     */
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
