package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.plan.GraphFacts;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Pins;

import java.util.List;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ParentWidget;
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
     * The item-icon column, filled by {@link NodeActions#iconFor} or
     * {@link NodeActions#iconForKey}.
     *
     * THE ONE STATEMENT OF THE COLUMN RULE, for every builder below to point at rather than
     * restate. `x` ADVANCES BY THIS WHETHER OR NOT AN ICON IS DRAWN: a width that depended on
     * whether a stack came back would re-flow every row the moment a `NodeActions` was
     * installed, or a graph finished loading mid-session and `iconFor` began answering.
     *
     * THE WIDGET, unlike the width, is added only when there is a stack, and that half lives in
     * exactly one place -- {@link #iconIfAny} -- so the four surfaces that draw an icon cannot
     * disagree about it. See that method for what the guard is worth now that {@link Icon}
     * replaced `ItemDisplayWidget`; the "49 empty boxes down the left edge" it was written for
     * was that widget painting its slot frame for an EMPTY stack.
     */
    public static final int ICON = 10;
    /**
     * The icon on a node that got a SECOND LINE for its label; see {@link #planNodeContent}.
     *
     * 16 IS THE NATIVE SIZE OF A 1.12.2 ITEM TEXTURE, so this is the one place in the planner
     * that draws a stack at 1:1 rather than scaled down. {@link Icon} scales the stack to the
     * box it is given, so the tree's 10px column is a downscale and this is not, which is worth
     * having on the surface where a node is the only thing identifying itself.
     *
     * THE COLUMN RULE IS {@link #ICON}'S AND IS NOT RESTATED HERE: the width is charged
     * whether or not a stack comes back, and the widget is added only when one does.
     */
    public static final int NODE_ICON = 16;
    /**
     * The inset between a diagram node's box and its contents, on all four sides.
     *
     * THE BOX HAS A BORDER AND THE CONTENT WAS DRAWN ON IT. `FlowCanvas` gives every node
     * `GuiTextures.MC_BACKGROUND`, a nine-slice whose frame occupies the outer two or three
     * pixels, and content positioned from 0 lands underneath it: `icons-flow.png` at 11:06 has
     * the hopper's icon sliced down its left edge by the bevel and every item name resting on
     * the bottom one. Nothing failed, and nothing could -- the widgets were exactly where the
     * sizer put them.
     *
     * ONLY THE DIAGRAM NEEDS IT. A tree row has no border of its own; the panel's own
     * {@link #PADDING} is what keeps those off the window frame.
     */
    public static final int NODE_PAD = 3;
    /**
     * What a TOKEN node draws in the icon column instead of a sprite.
     *
     * A TOKEN IS AN INSTRUCTION AND NOT AN ITEM, and the trap is that it is a REGISTERED item, so
     * `iconFor` succeeds and returns a perfectly good picture of a thing that does not exist.
     * `contenttweaker:dungeon_drop` is the case #174 was reported on: "the osiris spinel shows it
     * requires a Dungeon Drop which implies it is an item". A sprite plus a red quantity plus a
     * count is three signals all saying "item", so the sprite is the one to drop.
     *
     * A GLYPH RATHER THAN A BLANK, and that is the half worth arguing. Suppressing the sprite
     * alone leaves the token distinguishable from a genuine RAW row only by the absence of an
     * icon -- which is also true of every fluid and every oredict group, so it separates nothing.
     * A mark is a POSITIVE carrier.
     *
     * IN THE ICON COLUMN BECAUSE THE ICON COLUMN SURVIVES THE ZOOM. `NodeContent.showDetail`
     * drops the label and the badge word below `FlowZoom.LABEL_LEGIBLE`, and without this the
     * zoomed-out diagram would separate a token from a NEED by hue alone -- which is what
     * `NodeStatus`'s inks and `present.STATUS_STYLE` both deliberately refuse to do, and what a
     * colour-blind player cannot read. The mark is not `detail`, so it stays at every zoom, and
     * it survives greyscale.
     *
     * `!` AND NOT AN INVENTED GLYPH: it matches the web renderer's `graphview.TOKEN_MARK` on
     * #174, agreed with that agent directly. A rule taught in the browser and broken in game is
     * worse than neither, which is the whole reason that conversation happened.
     */
    public static final String TOKEN_MARK = "!";
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

    /**
     * The badge column on a line the LABEL DOES NOT SHARE. All or nothing, as above.
     *
     * SAME RULE, DIFFERENT COMPETITOR, and it needs its own method rather than a call to
     * {@link #badgeWidthFor} with a fudged `labelStart`. On the two-line diagram node the
     * badge sits beside the quantity and the label has a line of its own, so asking
     * `badgeWidthFor` would reserve {@link #MIN_LABEL} on a line no label is drawn on and
     * would drop the badge off nodes with room for it.
     *
     * @param room the pixels left on the line after the quantity and its gap
     */
    public static int badgeWidthBeside(int room) {
        return room >= BADGE ? BADGE : 0;
    }

    /**
     * The wash drawn behind a selected row or node, and the frame around it.
     *
     * A TINT AND NOT AN INK SWAP, which is what `FlowCanvas`'s draw-path comment predicted
     * and is the only option that composes with both surfaces. `NodeStatus`'s inks are the
     * web UI's LIGHT theme because a ModularUI panel is vanilla's light grey, and the diagram
     * node's background is load-bearing for exactly that -- recolouring the text to say
     * "selected" would spend the one channel that already carries the node's STATUS.
     *
     * TRANSLUCENT, at 25%, so `INK_MUTED` text stays legible over it. Opaque blue swallowed
     * the item name, which is the same failure the diagram's background comment records from
     * the other direction.
     *
     * {@link NodeStatus#INK_CRAFT}'s hue, deliberately reused rather than a sixth colour
     * invented: selection is not a status, and a hue the palette does not already contain
     * would read as one more thing the row is telling you about the item.
     */
    private static final int SELECTION_FILL = 0x402F5F96;

    /**
     * The frame around a selected row. Opaque, one pixel.
     *
     * THE FRAME IS WHAT CARRIES THE SELECTION IN THE TREE, where a row has no border of its
     * own and a 10px band of pale blue reads as a status colour rather than as a cursor. On
     * the diagram it sits just inside the nine-slice's border and doubles it, which is the
     * price of one implementation serving both surfaces and is the cheaper half of the trade:
     * a doubled edge on a busy canvas is legible, a missing one in the tree is not.
     */
    private static final int SELECTION_EDGE = 0xCC2F5F96;

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
        // width it can show a couple of columns in, and `FlowLayout.NODE_WIDTH` is 209 -- a
        // 360px panel shows one column and half of the next, which is a list with extra steps.
        // 620x380 fits a 1280x800 client at the default 2x GUI scale.
        //
        // 214 UNTIL NOW, IN THIS COMMENT ONLY: the node has been 209 since `FlowLayout`'s own
        // pin caught the hand-added figure, and this sentence kept quoting the wrong one while
        // reading as the reason for the panel size.
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
    public static class ClickableGroup extends ParentWidget<ClickableGroup>
            implements com.cleanroommc.modularui.api.widget.Interactable {

        private final Runnable onClick;

        /**
         * The key this row draws, or "" for a row that is not about one item.
         *
         * `isSelected` TAKES A KEY AND NOT A NODE, per {@link PlanSelection}'s design note:
         * the same item appears once per parent that needs it, and lighting up every
         * occurrence is the whole value of highlighting. A menu row or a picker row passes
         * "", and {@link PlanSelection#isSelected} answers false for it -- asserted, because
         * a node whose key failed to parse would otherwise read as permanently selected.
         */
        private final String selectionKey;

        /**
         * PUBLIC FOR `client.machines`, which draws clickable rows that are not plan nodes.
         *
         * The machines table (#254) needs exactly this widget -- a click target the width of
         * the row with no themed background -- and re-deriving it there would be a second
         * answer to "how does a row get clicked" that the first bug found in either would
         * only be fixed in one of. It passes no selection key, which is the "" case this
         * class already documents and {@link PlanSelection#isSelected} already answers false
         * for.
         */
        public ClickableGroup(Runnable onClick) {
            this(onClick, "");
        }

        public ClickableGroup(Runnable onClick, String selectionKey) {
            this.onClick = onClick;
            this.selectionKey = selectionKey == null ? "" : selectionKey;
        }

        /**
         * The selection wash, under this row's own children.
         *
         * DRAWN PER FRAME RATHER THAN BAKED IN AT BUILD TIME, and that is the half #213 was
         * missing rather than a performance note. The tree panel is built once when the
         * window opens and a click does not rebuild it, so a highlight decided in the
         * constructor would be correct only for rows built AFTER the click -- which is never.
         *
         * `draw` RATHER THAN `drawBackground`: ModularUI runs a widget's own `draw` before
         * its children and after its background, so a fill here lands over the diagram node's
         * `MC_BACKGROUND` nine-slice and under the text. That is what "composes with this"
         * meant on `FlowCanvas`'s draw path.
         *
         * THE COST IS A STRING COMPARE PER ROW DRAWN, and only the diagram bounds that by the
         * viewport -- `FlowCanvas.preDraw` disables what is off screen, about forty of four
         * thousand. A `ListWidget` makes no such claim, so the tree pays 634 of these a frame on
         * `plan-fluid-chain`, which is fine and is stated rather than assumed: nothing is
         * selected most of the time and `PlanSelection.isSelected` answers on an `isEmpty`
         * check, and when something IS selected a failing `String.equals` returns on the length.
         * `PlanSelection`'s own note settles the same question for the canvas.
         */
        /**
         * Whether this row would draw itself selected right now.
         *
         * PUBLIC BECAUSE IT IS THE ONLY HEADLESS VIEW OF THE READ #213 WAS MISSING. Everything
         * past this point is `GuiDraw`, which needs a GL context, so a test that could only
         * reach `draw` could assert that a selection was WRITTEN and never that anything read
         * it -- which is exactly the state the issue describes. `PlannerLayoutTest`'s
         * `everyOccurrenceOfTheSelectedItemHighlightsInTheTree` asserts against this and goes
         * red on the pre-#213 tree.
         */
        public boolean drawsAsSelected() {
            return PlanSelection.isSelected(selectionKey);
        }

        @Override
        public void draw(com.cleanroommc.modularui.screen.viewport.ModularGuiContext context,
                         com.cleanroommc.modularui.theme.WidgetThemeEntry<?> widgetTheme) {
            super.draw(context, widgetTheme);
            if (!drawsAsSelected()) {
                return;
            }
            int width = getArea().width;
            int height = getArea().height;
            com.cleanroommc.modularui.drawable.GuiDraw
                    .drawRect(0, 0, width, height, SELECTION_FILL);
            com.cleanroommc.modularui.drawable.GuiDraw
                    .drawRect(0, 0, width, 1, SELECTION_EDGE);
            com.cleanroommc.modularui.drawable.GuiDraw
                    .drawRect(0, height - 1, width, 1, SELECTION_EDGE);
            com.cleanroommc.modularui.drawable.GuiDraw
                    .drawRect(0, 0, 1, height, SELECTION_EDGE);
            com.cleanroommc.modularui.drawable.GuiDraw
                    .drawRect(width - 1, 0, 1, height, SELECTION_EDGE);
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
     * What {@link #planNodeContent} returns: a clickable node that can shed its text.
     *
     * A TYPE RATHER THAN A `ParentWidget<?>` SO THE DIAGRAM CAN DIM ITS OWN LABELS. At zoom
     * 0.5 Minecraft's bitmap font is drawn at half scale and is unreadable rather than merely
     * small -- `FlowZoom.MIN`'s own comment says so, and says what a reader zoomed out is
     * actually reading: the badge colour and the quantity. So the diagram drops the label and
     * the badge WORD below `FlowZoom.LABEL_LEGIBLE` and keeps the icon and the coloured
     * quantity. See `FlowCanvas.preDraw` for the two alternatives and why they lose.
     *
     * NAMED IN BACKTICKS AND NOT IN A `{@link}`, because `flow` calls `planner` and never the
     * reverse. A javadoc reference does not compile to a dependency, which is exactly why it is
     * the one that gets written without anybody noticing the direction.
     *
     * {@link ClickableGroup} IS THE BASE AND IS THEREFORE NOT FINAL, which is worth saying out
     * loud: the click target has to stay one widget per node, because
     * `aDiagramNodeOpensTheNodeMenuLikeATreeRowDoes` asserts exactly one `ClickableGroup` in a
     * node and a nested second one would make a click ambiguous.
     */
    public static final class NodeContent extends ClickableGroup {

        /**
         * The widgets a low zoom hides. NOT the icon and NOT the quantity.
         *
         * Collected as they are built rather than found afterwards by type, because "every
         * `TextWidget` in here" would also catch the quantity -- and the quantity is half of
         * what `FlowZoom.MIN` says a reader zoomed out is reading.
         */
        private final List<IWidget> detail = new java.util.ArrayList<IWidget>();

        NodeContent(Runnable onClick, String selectionKey) {
            super(onClick, selectionKey);
        }

        /**
         * Add a child that only appears when the node is drawn large enough to read.
         *
         * VOID RATHER THAN FLUENT. Both callers use it as a statement, and a returned `this`
         * nobody reads is an invitation to chain onto a builder that has no other steps.
         */
        void detail(IWidget widget) {
            detail.add(widget);
            child(widget);
        }

        /**
         * Show or hide the label and the badge word.
         *
         * CALLED FROM A DRAW PASS AND NEVER FROM CONSTRUCTION, deliberately. A widget built
         * disabled is a widget the sizer may skip, and one that came back at 0x0 would stay
         * invisible after the zoom went up again -- the same shape as the culling bug
         * `FlowCanvas.preDraw` avoids by toggling `enabled` instead of overriding
         * `getChildren`. Everything here is built enabled and sized once.
         */
        public void showDetail(boolean visible) {
            for (IWidget widget : detail) {
                widget.setEnabled(visible);
            }
        }
    }

    /**
     * The whole planner window for a solved plan.
     *
     * @param book   the player's plan book, for the "still needed" column; may be empty.
     * @param schema how the graph's dump format compares with this build's, or null when the
     *               plan did not come from a graph this process loaded. See
     *               {@link #staleGraphWarning}.
     */
    public static ModularPanel plannerPanel(PlanView plan, PlanBook book,
                                            GraphFacts.SchemaCheck schema,
                                            final PlannerActions actions) {
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);

        int y = 0;
        body.child(heading(NodeRowText.heading(plan), CONTENT_WIDTH).pos(0, y));
        y += LINE + 1;

        // ABOVE THE OTHER WARNINGS, AND NOT INSIDE THEM (#285). `warnings` is capped at
        // MAX_WARNINGS and rolls the overflow up as "+N more recipe choice(s)", so a stale-graph
        // line handed to it could be dropped by a pin the player made -- and dropped under a
        // sentence that would then be describing the wrong thing. It also outranks them: a
        // truncated plan is wrong about how MUCH it found, and a graph in the wrong format is a
        // reason the whole tree may be answering from data this build cannot read.
        String stale = staleGraphWarning(schema);
        if (!stale.isEmpty()) {
            body.child(line(stale, CONTENT_WIDTH, NodeStatus.INK_NEED).pos(0, y));
            y += LINE;
        }

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
     * The one line that says this plan may be answering from the wrong graph, or "".
     *
     * WHY THIS IS ON THE PLANNER AND NOT ONLY ON THE GRAPH TAB (#285). The Graph tab shows both
     * verdicts in full and is the right place to read them -- but it is a screen a player has to
     * go and open, and the player who needs it is the one who does not yet suspect anything.
     * That is the same defect `PlanCaveats` names about a detail panel nobody knows exists, and
     * the same shape as the swallowed refusal #279 found in `stage-instance.sh`: a check whose
     * output never reaches the caller is not a check. So the verdict is repeated where the wrong
     * answer is actually read.
     *
     * IT DOES NOT REPEAT THE FIX, which the Graph tab's `detail()` carries. Both fixes -- redump,
     * or update the mod -- are several minutes of work a player is not going to start from a
     * planner window mid-session, and the line has 64 characters to establish the far more
     * urgent fact: the tree under it may be wrong. Naming the schema numbers is what makes that
     * checkable rather than a bare scare.
     *
     * EMPTY FOR `MATCHES`, AND EMPTY FOR NULL, which are the only two silences here and they are
     * different things. MATCHES is a check that ran and passed, and a green line on every plan
     * for the rest of the game is noise that gets tuned out, taking the red one with it -- the
     * Graph tab is where a reader goes to confirm a pass. NULL is a plan that never came from a
     * loaded graph: the screenshot harness and `PlannerLayoutTest` draw stored fixtures, and
     * inventing a verdict for them would put a warning on every shot that says something about
     * a comparison nobody made. In game a plan cannot exist without the graph it was solved
     * from, so null does not reach a player; `PlannerScreen.planPanel` is where that is decided
     * and it is decided ONCE.
     *
     * AND IT IS AN ARGUMENT RATHER THAN A GLOBAL READ, which is not the free choice it looks
     * like. This class states that no client state reaches any method here -- that is what lets
     * `PlannerLayoutTest` run ModularUI's real sizer over the whole panel with no window -- and
     * `plannerPanel` already makes ONE exception, for `PlanCaveats`, on the grounds
     * `MachinesWidgets` spells out: whether an input was read is a fact about the SCENARIO and
     * there is nothing in the arguments to derive it from. That reasoning does NOT extend here.
     * The graph is a thing the caller has in hand, so `PlannerScreen` measures it and passes it,
     * and the four verdicts stay reachable from a test. DO NOT make this reach for
     * `GraphService` instead; it would buy one shorter call site and cost the only assertion
     * that the warning ever renders.
     */
    public static String staleGraphWarning(GraphFacts.SchemaCheck schema) {
        if (schema == null) {
            return "";
        }
        switch (schema.verdict()) {
            case BEHIND:
                // THE GRAPH'S NUMBER LEADS, and on AHEAD the jar's does, so the first thing
                // named is the artifact that is behind. `GraphFacts.detail()` cannot do this --
                // it has room for the fix and reads in one fixed order -- and 64 characters
                // here is not enough for both the order and the instruction. Counted at 6px a
                // character with two-digit schema numbers, which is one bump away.
                return "graph is schema " + schema.graphSchema() + ", this build reads "
                        + schema.jarSchema() + " -- plans may be wrong";
            case AHEAD:
                return "this build reads schema " + schema.jarSchema() + ", graph is "
                        + schema.graphSchema() + " -- plans may be wrong";
            case UNRECORDED:
                // NOT SILENT, even though it is the rarest state. `model.Graph.save` always
                // writes `dump_schema`, so a graph without one has been truncated or edited by
                // hand -- which is a stronger reason to distrust the tree than either mismatch,
                // not a weaker one.
                return "this graph records no schema -- plans cannot be checked";
            default:
                return "";
        }
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
        // ASKED ONCE FOR THE WHOLE TREE, mirroring `entryLines`: which labels collide is a
        // property of the list and not of a row, and a row cannot see its siblings. #232.
        java.util.Map<String, String> fragments = NodeRowText.disambiguators(plan.tree());
        appendRows(plan.tree(), 0, width, list, actions, fragments);
        return list;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void appendRows(PlanNode node, int depth, int width, ListWidget into,
                                   PlannerActions actions,
                                   java.util.Map<String, String> fragments) {
        // `get` RETURNS NULL FOR A ROW WITH NOTHING TO DISAMBIGUATE, which is the signal `row`
        // wants, so there is no second lookup and no way to ask the wrong question.
        into.child(row(node, depth, width, actions, fragments.get(node.key())));
        for (PlanNode child : node.children()) {
            appendRows(child, depth + 1, width, into, actions, fragments);
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
     * THE HEIGHT DECIDES THE SHAPE, and that is the seam keeping its promise rather than a
     * special case. A box tall enough for two lines gets the label on the second one, where it
     * is not competing with a 90px badge for the same pixels; a row-height box gets the
     * columns side by side, as it always did. See {@link #twoLineNode} for what the second
     * line bought and {@link #oneLineNode} for who still asks for one.
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
    public static NodeContent planNodeContent(final PlanNode node, int width, int height,
                                              final PlannerActions actions) {
        NodeContent box = new NodeContent(new Runnable() {
            @Override
            public void run() {
                actions.openNodeMenu(node);
            }
        }, node.key());
        box.size(width, height);
        int colour = NodeStatus.colour(node);
        if (height >= LINE * 2) {
            twoLineNode(box, node, width, height, colour);
        } else {
            oneLineNode(box, node, width, colour);
        }
        return box;
    }

    /**
     * The node as an icon with a quantity above its name. THE DIAGRAM'S SHAPE.
     *
     * WHY TWO LINES AT ALL: on one line the label competed with the badge for the same
     * pixels, and the badge is 90 of them. A 209px node gave the item's NAME 48px -- eight
     * characters -- so `plan-fluid-chain`'s "Sodium Fluoride Solution" rendered as
     * "Sodium…" and the first screenshot of this canvas read as a column of quantities. The
     * node box was already 20px tall and drew one 10px line, so the second line was free:
     * it costs no diagram area and takes the name from 8 characters to 31.
     *
     * THE ICON SPANS BOTH LINES at {@link #NODE_ICON}, which is what makes the box read as
     * being about an item rather than as a caption with a sprite stuck on it.
     *
     * The badge keeps line one beside the quantity and is still ALL OR NOTHING -- see
     * {@link #badgeWidthBeside}, which is the same rule against a different competitor.
     *
     * THE LABEL AND NOT {@link #labelAndMeta}: the diagram's job is to say what the box is.
     * The meta run ("pinned", "172 recipes", "-> ...") is a second sentence about the same
     * node, it is what the tree row is for, and appending it here would push the name back
     * off the end of the line this method exists to give it.
     */
    private static void twoLineNode(NodeContent box, PlanNode node, int width, int height,
                                    int colour) {
        int x = NODE_PAD + NODE_ICON + GAP;
        int right = width - NODE_PAD;
        // A TOKEN GETS THE MARK AND NOT ITS SPRITE; see TOKEN_MARK. On the mark's line rather
        // than the icon's, so it sits beside the quantity and reads as part of the row.
        int top = (height - LINE * 2) / 2;
        if (!tokenMark(box, node, NODE_ICON, NODE_PAD, top)) {
            iconIfAny(box, NodeActionsHolder.actions().iconFor(node), NODE_ICON, NODE_PAD,
                      (height - NODE_ICON) / 2);
        }
        // The width is charged whether or not that drew anything; see ICON.
        // `top` is CENTRED VERTICALLY rather than pinned to the top, so a caller that hands
        // over a taller box than two lines need gets a node that looks deliberate instead of one
        // whose text has slid to the ceiling. At `FlowLayout.NODE_HEIGHT` it comes out at exactly
        // NODE_PAD, which is the inset the sides get.
        box.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, top));
        int badgeWidth = badgeWidthBeside(right - x - QTY - GAP);
        if (badgeWidth > 0) {
            box.detail(line(NodeStatus.badge(node), badgeWidth, colour)
                               .pos(right - badgeWidth, top));
        }
        box.detail(line(NodeRowText.label(node), right - x, NodeStatus.INK_MUTED)
                           .pos(x, top + LINE));
    }

    /**
     * The node on one line: the tree row's columns, without its indent or its meta run.
     *
     * KEPT FOR A CALLER THAT HANDS OVER A ROW-HEIGHT BOX, and both remaining ones are
     * assertions: `aNodeTooNarrowForBothDropsTheBadgeAndKeepsTheLabel` sweeps widths at
     * {@link #ROW_HEIGHT}, which is where the all-or-nothing badge rule is pinned. The
     * geometry a caller asks for decides the shape, which is what the seam promised; nothing
     * in production draws a node this short.
     */
    private static void oneLineNode(NodeContent box, PlanNode node, int width, int colour) {
        int x = 0;
        if (!tokenMark(box, node, ICON, x, 0)) {
            iconIfAny(box, NodeActionsHolder.actions().iconFor(node), ICON, x, 0);
        }
        // The width is charged whether or not that drew anything; see ICON.
        x += ICON + GAP;
        box.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, 0));
        x += QTY + GAP;
        int badgeWidth = badgeWidthFor(width, x);
        int labelWidth = Math.max(GAP, width - x - (badgeWidth > 0 ? badgeWidth + GAP : 0));
        box.detail(line(NodeRowText.label(node), labelWidth, NodeStatus.INK_MUTED).pos(x, 0));
        if (badgeWidth > 0) {
            box.detail(line(NodeStatus.badge(node), badgeWidth, colour)
                               .pos(width - badgeWidth, 0));
        }
    }

    /**
     * {@link #planNodeContent} with nothing behind the click. THE INERT ONE.
     *
     * Named as such rather than left as a quiet default, because the failure it enables is a
     * canvas of nodes that look right and do nothing when clicked -- and no test catches that,
     * since a layout assertion is exactly what this overload is FOR. If a diagram is meant to
     * open menus and does not, this is the overload it is on.
     */
    public static NodeContent planNodeContent(PlanNode node, int width, int height) {
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
        return row(node, depth, width, actions, null);
    }

    /**
     * {@link #row(PlanNode, int, int, PlannerActions)}, told whether this row's label collides
     * with another in the same tree. #232.
     *
     * A CALLER PASSING NULL FOR A COLLIDING ROW DRAWS TWO ROWS A PLAYER CANNOT TELL APART,
     * which is the defect this exists to prevent -- the same warning {@link
     * NodeRowText#entryLine} carries for the summary lists. Prefer {@link #tree}, which asks
     * {@link NodeRowText#disambiguators(PlanNode)} once and answers it per row. The four-argument
     * form above keeps null because a caller holding ONE node cannot know: ambiguity is a
     * property of the list it sits in, so a lone node is unambiguous as far as anyone can tell.
     */
    public static ParentWidget<?> row(final PlanNode node, int depth, int width,
                                      final PlannerActions actions, String fragment) {
        ClickableGroup row = new ClickableGroup(new Runnable() {
            @Override
            public void run() {
                actions.openNodeMenu(node);
            }
        }, node.key());
        row.size(width, ROW_HEIGHT);

        int indent = Math.min(depth, MAX_INDENT_DEPTH) * INDENT;
        int x = indent;

        if (!tokenMark(row, node, ICON, x, 0)) {
            iconIfAny(row, NodeActionsHolder.actions().iconFor(node), ICON, x, 0);
        }
        // The width is charged whether or not that drew anything; see ICON.
        x += ICON + GAP;

        int colour = NodeStatus.colour(node);
        row.child(line(NodeRowText.quantity(node.need()), QTY, colour).pos(x, 0));
        x += QTY + GAP;

        // The same all-or-nothing rule as the diagram node. A full-width tree row never hits
        // it, even at the indent cap -- but a row is not always full width, and the latent
        // version of this bug is the one that gets found by a screenshot rather than a test.
        int badgeWidth = badgeWidthFor(width, x);
        int labelWidth = Math.max(GAP, width - x - (badgeWidth > 0 ? badgeWidth + GAP : 0));
        row.child(line(labelAndMeta(node, fragment, labelWidth), labelWidth,
                       NodeStatus.INK_MUTED).pos(x, 0));
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
        return labelAndMeta(node, null, Integer.MAX_VALUE);
    }

    /**
     * {@link #labelAndMeta(PlanNode)}, disambiguated ONLY IF THE WHOLE LINE STILL FITS. #232.
     *
     * THE WIDTH IS THE WHOLE DECISION, and it is made here because this is the only place that
     * knows it. Measured on UNMODIFIED master across all 21 fixtures: of the 61 colliding tree
     * rows, 33 draw a line that is ALREADY over its column -- `Ender Pearl · Crafting · 5...`
     * in 29 characters -- so on those rows there is no spare width at all, and adding a
     * disambiguator of ANY length removes the machine name that renders on master today. A
     * collision fixed by deleting information the row already showed is a worse row.
     *
     * AND BEING UNTRUNCATED IS NOT THE SAME AS HAVING ROOM, which is why this tests the whole
     * candidate rather than the length of what is there now. `Iron Ore · mined, not crafted` is
     * 29 characters into a 29-column row: it renders complete, it has nothing spare, and it is
     * the reason `Iron Ore` stays ambiguous after this change at any width. #273 carries those
     * rows: they need the name to arrive already disambiguated from `RecipeGraph.bareName`,
     * where the characters sit ahead of the cut instead of competing with what is behind it.
     *
     * So the test is whether the disambiguated line survives `fit` UNTRUNCATED. If it does,
     * nothing was evicted, by construction rather than by measurement. If it does not, the row
     * draws exactly what it drew before and stays ambiguous, which is the honest trade.
     */
    static String labelAndMeta(PlanNode node, String fragment, int labelWidth) {
        String meta = NodeRowText.meta(node);
        String plain = NodeRowText.label(node);
        if (fragment != null) {
            String marked = NodeRowText.labelWith(node, fragment);
            String candidate = meta.isEmpty() ? marked : marked + NodeRowText.SEPARATOR + meta;
            if (NodeRowText.fit(candidate, labelWidth).equals(candidate)) {
                return candidate;
            }
        }
        return meta.isEmpty() ? plain : plain + NodeRowText.SEPARATOR + meta;
    }

    /**
     * The node menu.
     *
     * The two recipe-viewer entries appear only when {@link NodeActions} says they would work
     * -- see that interface for why a greyed-out entry is worse than none.
     */
    public static ModularPanel nodeMenu(final PlanNode node, final PlannerActions actions) {
        List<Entry> entries = new java.util.ArrayList<Entry>();
        // ASKED SEPARATELY, because a token answers differently to the two (#174). It is a
        // registered item, so JEI will open on it -- and what "Show recipes" opens is the recipes
        // that MAKE a Dungeon Drop, of which there are none. "Show uses" is a real question with
        // real answers and is the honest form of what a reader wanted when they clicked it.
        Entry recipes = new Entry("Show recipes", new Runnable() {
            @Override
            public void run() {
                actions.nodeActions().showRecipes(node);
            }
        });
        Entry uses = new Entry("Show uses", new Runnable() {
            @Override
            public void run() {
                actions.nodeActions().showUses(node);
            }
        });
        // RECIPES FIRST WHEN IT IS THERE, because on an ordinary item "what makes this" is the
        // question the menu was opened for. On a token it is absent, so "Show uses" is promoted by
        // simply being the only one left rather than by a second ordering rule.
        if (actions.nodeActions().canShowRecipes(node)) {
            entries.add(recipes);
        }
        if (actions.nodeActions().canShowUses(node)) {
            entries.add(uses);
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

        return menuPanel("mcrecipedump_node_menu", NodeRowText.label(node), entries);
    }

    /**
     * A menu panel: a muted title and one clickable row per entry.
     *
     * SHARED BY BOTH MENUS RATHER THAN COPIED (#251). The node menu and the row menu differ in
     * what they are ABOUT -- an occurrence against an aggregate, which is the whole distinction
     * this issue exists to keep -- and in nothing about how a menu is assembled. A second copy
     * of the assembly would agree with this one on every input anyone tried, which is exactly
     * what would stop anybody noticing when they later diverged.
     */
    private static ModularPanel menuPanel(String id, String title, List<Entry> entries) {
        int height = PADDING * 2 + LINE + 1 + entries.size() * ROW_HEIGHT;
        // WIDE ENOUGH FOR THE LONGEST ENTRY, measured in the character budget the whole panel
        // uses. "Choose another recipe (172)" came out as "Choose another recip..." at 150.
        int width = MENU_WIDTH;
        int inner = width - PADDING * 2;
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(inner, height - PADDING * 2);
        // Muted, so the title does not read as one more thing you can click.
        body.child(line(title, inner, NodeStatus.INK_MUTED).pos(0, 0));
        int y = LINE + 1;
        for (Entry entry : entries) {
            ClickableGroup row = new ClickableGroup(entry.action);
            row.size(inner, ROW_HEIGHT);
            row.child(line(entry.text, inner, NodeStatus.INK_CRAFT).pos(0, 0));
            body.child(row.pos(0, y));
            y += ROW_HEIGHT;
        }
        return ModularPanel.defaultPanel(id, width, height).child(body);
    }

    /**
     * The menu for a SHOPPING ROW: two entries, and deliberately not the node menu's five.
     *
     * TWO ENTRIES BECAUSE ONLY TWO ARE EXPRESSIBLE FROM A ROW. "Show recipes", "Show uses" and
     * "Choose another recipe" all need a node and a recipe -- an occurrence -- and a shopping
     * row is the aggregate across every occurrence. Reaching for one would mean picking an
     * arbitrary parent, which is #251's rejected option 1. A menu offering three entries where
     * one silently acts on the wrong quantity is worse than one offering two that are right.
     *
     * THE TITLE IS THE ROW'S LABEL AND THE NEED IS ON IT, because the number is the whole
     * reason this menu is separate. A player who opened it from a wrapped row should be able to
     * see which total they are about to act on without closing it again.
     */
    public static ModularPanel rowMenu(final PlanView.EntryRow row, final PlannerActions actions) {
        List<Entry> entries = new java.util.ArrayList<Entry>();
        entries.add(new Entry("Add to TODO", new Runnable() {
            @Override
            public void run() {
                actions.addRowToTodo(row);
            }
        }));
        entries.add(new Entry("Favourite", new Runnable() {
            @Override
            public void run() {
                actions.favouriteRow(row);
            }
        }));

        return menuPanel("mcrecipedump_row_menu", row.need() + "x " + row.label(), entries);
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
     *
     * IT TAKES `PlannerActions` RATHER THAN REACHING A HOLDER, and that was a decision rather
     * than a default (#251). `NodeActionsHolder` is static and `rowList` already calls it for
     * icons, so the cheap shape was an overload with a no-op default -- and that is precisely
     * what `SeamInstallationTest.everySettableSeamIsInstalledSomewhereInProduction` gates
     * against since #205: a seam present, tested, and absent in the running game. Threading it
     * costs six call sites, five of them tests, and is how `PlannerActions` already travels in
     * this file. The other rejected shape was hanging the row menu off `NodeActions`, which
     * needs no plumbing and puts a panel action on the JEI-facing interface -- the two menus
     * answer different questions and blurring them is how the aggregate-versus-occurrence
     * confusion finds a fourth place to live.
     */
    public static ModularPanel todoPanel(PlanView plan, PlanBook book, PlannerActions actions) {
        int width = TODO_WIDTH;
        int inner = width - PADDING * 2;

        List<Line> lines = todoLines(plan, book, inner);

        int listHeight = Math.min(lines.size() * ROW_HEIGHT, TODO_MAX_LIST_HEIGHT);
        int height = PADDING * 2 + LINE + 1 + listHeight;

        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(inner, height - PADDING * 2);
        body.child(line("TODO", inner, NodeStatus.INK_MUTED).pos(0, 0));
        body.child(rowList(lines, inner, listHeight, actions).pos(0, LINE + 1));
        return ModularPanel.defaultPanel("mcrecipedump_todo", width, height).child(body);
    }

    /**
     * WHAT THE TODO PANEL SAYS, as rows, with no widget anywhere near it.
     *
     * SEPARATE SO THE WORDS CAN BE ASSERTED, the same split {@link #warnings} has and for the
     * same reason: a layout assertion is perfectly happy with a row that says
     * `thaumadditions:vis_pod#0116bb2287a7`, and that is what this panel said for as long as it
     * existed. `TextWidget` holds an `IKey` whose rendered string cannot be read back headlessly,
     * so a test that could only reach the widgets could check that a row EXISTS and never what is
     * in it.
     *
     * @param widthPx how wide a row is, because {@link NodeRowText#entryLine} wraps to it and
     *                hands back lines that already fit.
     */
    static List<Line> todoLines(PlanView plan, PlanBook book, int widthPx) {
        // THE NAMES COME FROM THE PLAN, WHICH IS WHERE THEY ALREADY ARE. See `PlanNames`.
        //
        // THIS DOES NOT CONTRADICT #190's "THE KEY STAYS RATHER THAN A LABEL", IT SATISFIES IT.
        // That rule was written because the plan book stores keys and has no display name of its
        // own, so inventing one would be dishonest. `PlanNames` invents nothing: it reads the name
        // off the plan the key came from, and "Add to TODO" sends `node.key()` from a node the
        // player was looking at while nothing else writes this list. A key the CURRENT plan does
        // not mention still renders as the key, which is exactly the row #190 was protecting.
        PlanNames names = PlanNames.of(plan);
        List<Line> lines = new java.util.ArrayList<Line>();
        for (String key : book.todoKeys()) {
            // #190's UNIT RULE, KEPT: `amount` and not `quantity`, so 934,400 mB of water does not
            // read as `934,400x` four lines above a shopping row rendering the same fluid as
            // `934,400 mB Water`. One panel cannot measure the same fluid two ways.
            lines.add(new Line(key,
                               NodeRowText.amount(book.todoQuantity(key), Keys.kind(key))
                                       + " " + names.labelFor(key),
                               NodeStatus.INK_CRAFT));
        }
        if (lines.isEmpty()) {
            lines.add(new Line("", "nothing on the list", NodeStatus.INK_MUTED));
        }
        lines.add(new Line("", "still needed for this plan:", NodeStatus.INK_MUTED));
        if (plan.shoppingList().isEmpty()) {
            lines.add(new Line("", "nothing outstanding", NodeStatus.INK_MUTED));
        }
        addEntries(lines, null, plan.shoppingList(), NodeStatus.INK_NEED, widthPx);
        // THE FOOTER'S "N machine(s) to build" IS A COUNT AND THESE ARE THE MACHINES. #190: every
        // `MachineRow` accessor but `size()` was called from a test and nowhere else, so the
        // player was told how many machines stood in the way and could never learn which.
        //
        // NO KEY ON THESE, and that is right rather than an omission: a machine row names a
        // CATEGORY and a machine, not an item, so there is nothing for an icon to resolve.
        addSection(lines, "machines to build:",
                   NodeRowText.machineLines(plan.machinesToBuild(), widthPx), NodeStatus.INK_WARN);
        // THE FOUR SUMMARY LISTS, AS SECTIONS OF THIS LIST RATHER THAN FOUR PANELS. #190 asked for
        // the decision to be made explicitly rather than by Phase 6 deleting their only reader, so
        // all four are read and all four are drawn HERE. Not four panels, because they are four
        // instances of one shape, this window is 400 pixels wide inside a 427-pixel minimum
        // screen, and two of the four are empty until #50 and #112 land.
        addEntries(lines, "used from your stock:", plan.usedFromStock(), NodeStatus.INK_OK,
                   widthPx);
        addEntries(lines, "drawn from infinite sources:", plan.fromSources(), NodeStatus.INK_OK,
                   widthPx);
        addEntries(lines, "go and get:", plan.tokensNeeded(), NodeStatus.INK_NEED, widthPx);
        addEntries(lines, "transmuted from EMC:", plan.fromEmc(), NodeStatus.INK_OK, widthPx);
        return lines;
    }

    /**
     * A headed group of ENTRY rows, each keeping the key its icon is looked up by.
     *
     * ROW BY ROW THROUGH {@link NodeRowText#entryLine} RATHER THAN {@link
     * NodeRowText#entryLines}, which is the seam #190 split out for exactly this caller. The flat
     * `List<String>` that wrapper returns has already thrown away which `EntryRow` produced which
     * line, and a row can wrap over two of them, so the key cannot be recovered by index.
     *
     * {@link NodeRowText#ambiguousLabels} IS ASKED ONCE FOR THE WHOLE LIST, because which labels
     * collide is not a property of one row. Passing `false` for a colliding row draws two rows a
     * player cannot tell apart, which is the defect #190 exists to fix.
     *
     * THE ICON GOES ON THE FIRST LINE ONLY. A wrapped row is one entry and `CONTINUATION` indents
     * its tail so it reads as one; a second icon down the left edge would undo that and claim the
     * continuation was another item.
     */
    static void addEntries(List<Line> lines, String header, List<PlanView.EntryRow> rows,
                           int colour, int widthPx) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (header != null) {
            lines.add(new Line("", header, NodeStatus.INK_MUTED));
        }
        java.util.Set<String> ambiguous = NodeRowText.ambiguousLabels(rows);
        for (PlanView.EntryRow row : rows) {
            boolean first = true;
            for (String text : NodeRowText.entryLine(row, ambiguous.contains(row.label()),
                                                     widthPx)) {
                // THE ROW GOES ON THE FIRST LINE ONLY, beside the key, and for the same reason
                // the key does: a row that wraps is one row, and hanging an action off its
                // continuation would give the player two clickable things that mean one thing.
                lines.add(new Line(first ? row.key() : "", text, colour, first ? row : null));
                first = false;
            }
        }
    }

    /**
     * A headed group of rows that are NOT about one item, or nothing at all when there are none.
     *
     * NOTHING AT ALL, WHICH IS THE POINT AND IS WHY THE HEADER IS THIS METHOD'S BUSINESS. A header
     * standing over no rows is a claim the panel cannot support: "machines to build:" followed by
     * blank space reads as a list that failed to load rather than as one that is legitimately
     * empty. A caller that added the header itself would have to remember the guard every time.
     *
     * NO KEY, unlike {@link #addEntries}, and the split is the point rather than duplication: the
     * one caller draws machines, and a machine is not an item. A shared method taking an optional
     * key would let a future caller pass one for a row that cannot have an icon.
     *
     * @param header null for a section whose caller already wrote its own heading.
     */
    static void addSection(List<Line> lines, String header, List<String> rows, int colour) {
        if (rows.isEmpty()) {
            return;
        }
        if (header != null) {
            lines.add(new Line("", header, NodeStatus.INK_MUTED));
        }
        for (String row : rows) {
            lines.add(new Line("", row, colour));
        }
    }

    /**
     * One line of a list that may carry an icon: the key it is about, its words, its colour.
     *
     * THE KEY IS HELD SEPARATELY FROM THE WORDS, which is the shape #213's `PlanSelection` note
     * argues for in the other direction: what is DRAWN is a display name and what IDENTIFIES the
     * row is the key, and folding the two together is how this panel came to print
     * `thaumadditions:vis_pod#0116bb2287a7` at a player. "" for a line that is not about one item
     * -- a heading, a machine, or the continuation of a wrapped entry.
     *
     * INTRODUCED HERE AND NOT ON #190's BRANCH, deliberately. It was offered to that branch and
     * declined for the right reason: nothing there draws an icon, so `key` would have landed as a
     * field whose only consumer was another branch -- the write-only pattern, inside the change
     * that deletes seven instances of it. It arrives with the column that reads it.
     */
    static final class Line {
        final String key;
        final String text;
        final int colour;

        /**
         * The shopping row this line came from, or null for a header or a machine line. #251.
         *
         * THE KEY SURVIVED TO THE ROW AND THE QUANTITY DID NOT, which is the whole reason this
         * field exists. `rowList` already had `key` and used it to resolve an icon, so a row was
         * IDENTIFIABLE (#236) while the number a click would have to act on was dropped one
         * layer up in `addEntries`. That is the same aggregate-versus-occurrence confusion #251
         * is about, one layer above the place it was filed.
         *
         * IT IS THE ROW AND NOT A `need` LONG, deliberately. A bare quantity would be usable by
         * a caller that had lost track of which surface it came from, and the point of option 3
         * is that this quantity is only correct ON THIS SURFACE: a shopping row's `need` is the
         * total the plan wants, where a tree node's is one parent's share. Carrying the row
         * keeps the number attached to the thing that makes it right.
         */
        final PlanView.EntryRow row;

        Line(String key, String text, int colour) {
            this(key, text, colour, null);
        }

        Line(String key, String text, int colour, PlanView.EntryRow row) {
            this.row = row;
            // "" AND NEVER NULL, because `rowList` asks `isEmpty()` once per row per frame and a
            // `EntryRow` key comes out of gson. The same asymmetry `ClickableGroup`'s own key
            // guard names: a null there is an NPE inside a draw, not a missing icon.
            this.key = key == null ? "" : key;
            this.text = text;
            this.colour = colour;
        }

        @Override
        public String toString() {
            return (key.isEmpty() ? "-" : key) + " => " + text;
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


    /**
     * A scrollable column of one-line rows, each with the icon column charged.
     *
     * THE COLUMN IS CHARGED ON EVERY ROW INCLUDING THE HEADINGS, so the text down the panel
     * lines up whether or not a given key resolved to a stack. That is {@link #ICON}'s rule
     * applied one level further out: a width that depended on what came back would step the
     * left margin in and out down the list.
     *
     * The same `ListWidget` reasoning as {@link #tree}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ListWidget<?, ?> rowList(List<Line> lines, int width, int height,
                                            final PlannerActions actions) {
        ListWidget list = new ListWidget();
        list.size(width, height);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        for (final Line entry : lines) {
            // CLICKABLE ONLY WHERE THERE IS A ROW TO ACT ON (#251). A header, a machine line
            // and every continuation of a wrapped row carry no `EntryRow` -- see `addEntries`
            // -- so they stay plain `Group`s and a player gets exactly one click target per
            // shopping row. Making every line clickable would give a wrapped row two.
            //
            // THE SELECTION KEY IS DELIBERATELY NOT PASSED. `ClickableGroup`'s second argument
            // draws the selection wash for one occurrence, and a shopping row is all of them;
            // washing it on a per-occurrence selection would say the row IS that occurrence,
            // which is the confusion this issue exists to remove.
            // `ParentWidget<?>` AND NOT `Group`, because `ClickableGroup` is not one --
            // both extend `ParentWidget` with their own self-type, so they share no assignable
            // type below it. `iconIfAny` already takes the wildcard for the same reason.
            ParentWidget<?> row;
            if (entry.row == null) {
                row = new Group();
            } else {
                final PlanView.EntryRow clicked = entry.row;
                row = new ClickableGroup(new Runnable() {
                    @Override
                    public void run() {
                        actions.openRowMenu(clicked);
                    }
                });
            }
            row.size(width, ROW_HEIGHT);
            if (!entry.key.isEmpty()) {
                // A `fluid:` key has no item form at all -- 1,198 of this pack's fluids -- so
                // an absent icon here is the ordinary case rather than a lookup that failed.
                iconIfAny(row, NodeActionsHolder.actions().iconForKey(entry.key), ICON, 0, 0);
            }
            row.child(line(entry.text, width - ICON - GAP, entry.colour)
                              .pos(ICON + GAP, 0));
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
        return statePanel(state, "Planner", "mcrecipedump_planner_state");
    }

    /**
     * The same not-yet panel under another name, for a second screen with the same four states.
     *
     * PARAMETERISED RATHER THAN COPIED (#254). The machines table is opened before its own
     * resolve finishes exactly as the planner is opened before the graph is read, and
     * `PlannerState`'s class note is the reason both need this: "still loading" and "no graph
     * found, here is why" are different sentences a reader has to be able to tell apart, and a
     * second copy of this panel is a second place for one of them to go missing.
     *
     * THE EYEBROW IS AN ARGUMENT BECAUSE IT IS THE ONLY THING THAT DIFFERS, and it has to
     * differ: a machines window whose one legible word is "Planner" tells the player they
     * opened the wrong thing. The panel NAME differs too -- ModularUI keys panels by it, and
     * two live panels sharing a name is not something this code should risk to save an
     * argument.
     */
    public static ModularPanel statePanel(PlannerState state, String eyebrow, String panelName) {
        Group body = new Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);
        body.child(line(eyebrow, CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, 0));
        body.child(line(state.message(), CONTENT_WIDTH, state.colour()).pos(0, LINE + 1));
        return ModularPanel.defaultPanel(panelName, PANEL_WIDTH, PANEL_HEIGHT).child(body);
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
     * Put a square icon for `stack` at (x, y), or nothing at all when there is no stack.
     *
     * THE ONE GUARD, so the four surfaces that draw an icon cannot each get it right and a
     * fifth get it wrong. It used to be four copies of `if (!stack.isEmpty())` with three
     * copies of the comment explaining why. The reason has CHANGED with {@link Icon} and the
     * old one is no longer true of this code: `ItemDisplayWidget` holding EMPTY painted its
     * SLOT FRAME rather than nothing, which is what put 49 empty boxes down the left edge of
     * the first screenshot. `GuiDraw.drawItem` returns on an empty stack, so an unguarded
     * `Icon` would draw nothing -- the guard now buys not building 634 widgets that do nothing,
     * which is a smaller reason and is the honest one.
     *
     * THE WIDTH IS STILL THE CALLER'S, and deliberately so. {@link #ICON} states why the column
     * is charged whether or not this draws: a width that depended on whether a stack came back
     * would re-flow every row the moment a `NodeActions` was installed, or a graph finished
     * loading mid-session and `iconFor` began answering. A helper that also advanced the cursor
     * would make that conditional again.
     */
    /**
     * Draw {@link #TOKEN_MARK} in the icon column when `node` is a token, and say whether it did.
     *
     * A BOOLEAN SO THE CALLER SKIPS THE SPRITE, rather than this suppressing it: the three call
     * sites already own the geometry of their own icon column, and a method that drew a mark AND
     * silently swallowed the sprite lookup would hide which of the two happened.
     *
     * IT MUST BE ASKED BEFORE `iconIfAny` AND NOT AFTER. A token's key resolves -- that is the
     * whole trap -- so an icon drawn first is a sprite the mark would then be painted on top of.
     */
    private static boolean tokenMark(ParentWidget<?> box, PlanNode node, int width, int x, int y) {
        if (!NodeStatus.isToken(node)) {
            return false;
        }
        box.child(line(TOKEN_MARK, width, NodeStatus.colour(node)).pos(x, y));
        return true;
    }

    private static void iconIfAny(ParentWidget<?> box, net.minecraft.item.ItemStack stack,
                                  int size, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        box.child(icon(stack, size, size).pos(x, y));
    }

    /**
     * The icon column's widget. ONLY REACHED WHEN THERE IS SOMETHING TO DRAW; see
     * {@link #iconIfAny}, which is the only caller and holds the guard.
     *
     * IT DRAWS THE REAL STACK THROUGH THE GAME'S OWN RENDERER, which is the whole argument for
     * doing this in game rather than reading the atlas `IconAtlas` writes for the browser.
     * That class's header records the three reasons an offline extractor cannot get this right
     * -- the texture name is not the registry name, metadata variants have separate models, and
     * a TESR or a runtime-tinted item has no static texture at all -- and all three are free
     * here because the model system is loaded and this is asking it the same question the
     * player's inventory asks.
     *
     * NOT `ItemDisplayWidget`, WHICH WAS THE FIRST CHOICE AND CANNOT DO THIS. Its `draw` is
     * `GuiDraw.drawItem(stack, 1, 1, 16f, 16f, z)` -- the offset and BOTH dimensions are
     * constants in the bytecode, and its own area is read only for the stack-size text. So it
     * draws a 16x16 item one pixel inside whatever box it is given and a `size(10, 11)` on it
     * does nothing.
     *
     * FOUND BY THE SCREENSHOT, and it is the reason that instruction exists. The first
     * `icons-planner` shot had the hopper, the ingot and the iron block spilling out of their
     * 10px column across the quantity beside them and into the rows above and below -- five
     * overlapping sprites down the left edge. Every layout assertion in `PlannerLayoutTest` was
     * green through it, and correctly so: the WIDGET was 10x11 and in the right place. What it
     * put on the screen was not.
     */
    private static Icon icon(net.minecraft.item.ItemStack stack, int width, int height) {
        reportIfModelless(stack);
        Icon widget = new Icon(stack);
        widget.size(width, height);
        return widget;
    }

    /**
     * Registry names already reported by {@link #reportIfModelless}, so it says each one once.
     *
     * STATIC MUTABLE STATE IN THIS PACKAGE, WHICH THE HEADER OTHERWISE ARGUES AGAINST, and it is
     * here on purpose with a bound: one entry per distinct item whose model is missing, which on
     * the reference pack is a handful and on a healthy pack is zero. Without it the line would be
     * printed once per row per panel build.
     */
    private static final java.util.Set<String> REPORTED_MISSING_MODELS =
            new java.util.HashSet<String>();

    /**
     * Log, once per item, when a resolved stack has no model and will therefore draw the
     * missing-texture checkerboard.
     *
     * WHY LOGGING AND NOT A FALLBACK. `packplanner.png` has the magenta-and-black checkerboard on
     * its top row, and there are two possible causes that want OPPOSITE fixes: a genuinely
     * modelless item (a TESR-drawn or runtime-tinted thing, which wants a text fallback) or an
     * artifact of rendering headlessly with no resource packs bound (which wants nothing at all,
     * and where a fallback would suppress an icon no player is missing). Guessing means shipping a
     * guard against a case that may not exist, so this converts the question into something the
     * next pack run answers for free instead of costing a 22-minute slot of its own.
     *
     * IT NAMES THE REGISTRY NAME, because that is the one thing the picture cannot tell you and
     * the only thing that distinguishes the two causes.
     *
     * AT BUILD TIME AND NOT IN `draw`. A model does not change under a running client, so asking
     * once per widget rather than once per frame costs nothing on the render path -- and the
     * render path is where this file's other comments are careful about cost.
     *
     * IT CANNOT THROW. Every call site is inside a GUI build, and `getItemModelMesher` is client
     * state that a test JVM does not have, so the whole body is guarded: a diagnostic that takes
     * the screen down is worse than the checkerboard it is diagnosing.
     */
    private static void reportIfModelless(net.minecraft.item.ItemStack stack) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.getRenderItem() == null) {
                return;
            }
            net.minecraft.client.renderer.block.model.IBakedModel model =
                    mc.getRenderItem().getItemModelMesher().getItemModel(stack);
            if (model == null || model != mc.getRenderItem().getItemModelMesher()
                    .getModelManager().getMissingModel()) {
                return;
            }
            String name = stack.getItem().getRegistryName() == null
                    ? stack.toString() : stack.getItem().getRegistryName().toString();
            String id = name + "#" + stack.getItemDamage();
            if (REPORTED_MISSING_MODELS.add(id)) {
                org.apache.logging.log4j.LogManager
                        .getLogger(io.github.jacoblasky.recipedump.RecipeDumpMod.MODID)
                        .warn("planner icon for " + id + " has no baked model, so it draws the"
                              + " missing-texture checkerboard. Either the item is genuinely"
                              + " modelless (a TESR or runtime-tinted item) or this client bound"
                              + " no resource pack for it; those want opposite fixes, so the"
                              + " registry name is the thing to report.");
            }
        } catch (Throwable ignored) {
            // A DIAGNOSTIC MUST NOT BE THE THING THAT BREAKS A GUI BUILD. `Minecraft` is absent in
            // a test JVM and the mesher is absent before the model system loads, and neither is a
            // reason for a row not to draw.
        }
    }

    /**
     * One item, drawn at exactly the size its column reserves.
     *
     * THE SCALE IS THE BOX. `GuiDraw.drawItem`'s two float arguments are a WIDTH and a HEIGHT
     * in GUI pixels, not a scale factor: it translates by (x, y), scales by `width / 16` and
     * `height / 16`, and hands the stack to `RenderItem.renderItemAndEffectIntoGUI`. Reading
     * them off `getArea()` is the whole of this class, and it is what `ItemDisplayWidget` does
     * not do.
     *
     * SIZED BY THE CALLER AND SQUARE BY CONVENTION, not by construction: a 1.12.2 item texture
     * is 16x16, so a box that is not square draws the item stretched. Both callers pass a
     * square, {@link #ICON} in a tree or TODO row and {@link #NODE_ICON} on a diagram node.
     *
     * NO GL STATE HANDLING HERE. `drawItem` is already wrapped in `Platform.setupDrawItem` /
     * `endDrawItem` and a push/pop of the matrix, so an item drawn from a widget leaves the
     * lighting and the depth range as it found them. Doing it again outside would be a second
     * owner of state that has exactly one.
     */
    private static final class Icon extends com.cleanroommc.modularui.widget.Widget<Icon> {

        private final net.minecraft.item.ItemStack stack;

        Icon(net.minecraft.item.ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public void draw(com.cleanroommc.modularui.screen.viewport.ModularGuiContext context,
                         com.cleanroommc.modularui.theme.WidgetThemeEntry<?> widgetTheme) {
            com.cleanroommc.modularui.drawable.GuiDraw.drawItem(
                    stack, 0, 0, getArea().width, getArea().height,
                    context.getCurrentDrawingZ());
        }
    }

    /** PUBLIC FOR `client.machines`, for the reason on {@link #line}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static TextWidget<?> heading(String text, int width) {
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
     *
     * PUBLIC FOR `client.machines` (#254), AND THE CUT IS WHY IT IS SHARED RATHER THAN COPIED.
     * A second "one line of coloured text" helper next door would be a second place for the
     * `fit` call to be forgotten, and forgetting it does not fail a layout test -- every row
     * is still exactly `LINE` tall. It draws over the row beneath instead, which is only
     * visible in a screenshot.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static TextWidget<?> line(String text, int width, int colour) {
        TextWidget widget = new TextWidget(IKey.str(NodeRowText.fit(text, width)));
        widget.color(colour);
        widget.size(width, LINE);
        return widget;
    }
}
