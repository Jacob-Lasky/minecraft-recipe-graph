package io.github.jacoblasky.recipedump.client.flow;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;
import com.cleanroommc.modularui.widget.scroll.HorizontalScrollData;
import com.cleanroommc.modularui.widget.scroll.VerticalScrollData;

import io.github.jacoblasky.recipedump.client.ScreenCursor;
import io.github.jacoblasky.recipedump.client.planner.Diagram;
import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * The plan as a pannable left-to-right flow diagram.
 *
 * Geometry comes from {@link FlowLayout} and visibility from {@link FlowCulling}, both of
 * which are pure and tested with no window. This class is the part that cannot be: it owns the
 * widgets, the edges and the viewport.
 *
 * PANNING IS `AbstractScrollWidget`'S, NOT HAND-ROLLED. It already implements `IViewport`, so
 * `onMouseDrag`, the scroll offsets and -- the part that is genuinely hard -- `getWidgetsAt`
 * hit-testing THROUGH the viewport transform all come for free. A hand-rolled canvas gets the
 * first two right and the third wrong.
 *
 * NODE CONTENT IS `PlannerWidgets.planNodeContent`, NOT A SECOND RENDERER. `NodeStatus` ports
 * `present.py`'s palette with a test that reads the Python in both directions, and
 * `present.py`'s own docstring records what happens when four components each keep their own
 * dict of status strings: adding a status draws silently wrong. This diagram is component
 * five and calls theirs.
 */
public class FlowCanvas extends AbstractScrollWidget<IWidget, FlowCanvas> implements Diagram {

    /**
     * The narrowest node that carries everything a two-line node can draw.
     *
     * DERIVED FROM THE NODE'S OWN COLUMNS, so it follows when the status vocabulary grows and
     * widens `BADGE`. `FlowLayout.NODE_WIDTH` must be at least this and a test says so; the
     * layout writes its number out instead of importing it, because importing `PlannerWidgets`
     * would drag ModularUI into a file whose value is being testable without it.
     *
     * THE WIDER OF THE TWO LINES, WHICH IS LINE ONE. Since #213's companion work put the label
     * on its own line, the two are measured separately: line one is the quantity and the badge
     * beside the icon, line two is the label beside the icon, and both sit inside
     * `NODE_PAD` of the box's border. It used to be a single sum because everything shared one
     * line, and the label was what lost -- eight characters of item name against a 90px badge.
     *
     * AT LEAST, NOT EQUAL, and the change of relation is deliberate. What the pin protects is
     * the direction that goes wrong silently: `BADGE` grows, a node left at the old width
     * starts DROPPING the badge instead of following, and nothing says so. A node wider than
     * the minimum spends the surplus on the label, which is the thing there is never enough of.
     */
    public static final int NARROWEST_NODE = PlannerWidgets.NODE_PAD * 2 + Math.max(
            PlannerWidgets.NODE_ICON + PlannerWidgets.GAP + PlannerWidgets.QTY
                    + PlannerWidgets.GAP + PlannerWidgets.BADGE,
            PlannerWidgets.NODE_ICON + PlannerWidgets.GAP + PlannerWidgets.MIN_LABEL);

    /**
     * The shortest node that holds two text lines clear of its own frame.
     *
     * `FlowLayout.NODE_HEIGHT` must be at least this. It was 20 -- exactly two lines and no
     * slack -- while the node drew ONE of them, so the shortfall could not show; the moment the
     * label moved to a second line the top one was drawn on the nine-slice's bevel.
     */
    public static final int SHORTEST_NODE =
            PlannerWidgets.LINE * 2 + PlannerWidgets.NODE_PAD * 2;

    /** Edge colour, muted so the nodes stay the thing you read. */
    private static final int EDGE = 0x66FFFFFF;

    private final FlowLayout.Laid laid;
    private final FlowCulling culling;
    /**
     * One widget per box, built once and positioned absolutely. Parallel to `laid.boxes`.
     *
     * TYPED AS `NodeContent` AND NOT AS `IWidget`, so {@link #preDraw} can tell a node to drop
     * its label at a zoom where the label is a smear. See `FlowZoom.LABEL_LEGIBLE`.
     */
    private final List<PlannerWidgets.NodeContent> boxWidgets;
    /** What the last {@link #preDraw} decided was on screen. */
    private final List<IWidget> visible = new ArrayList<IWidget>();
    /**
     * The same set as box indices, so the edge sweep can reach the geometry.
     *
     * `IntArray`, for the reason `FlowCulling` gives: this is rebuilt every frame while the
     * viewport moves, and a `List<Integer>` there is a few hundred boxed Integers a frame
     * during exactly the gesture the 60 fps gate measures.
     */
    private final IntArray visibleBoxes = new IntArray();

    /** Screen pixels per layout pixel. See {@link FlowZoom} for what depends on it. */
    private float zoom = FlowZoom.DEFAULT;

    /**
     * A canvas sized to the panel `PlannerWidgets.flowPanel` is about to put it in. #323.
     *
     * THE SIZE IS NOT OPTIONAL AND NOTHING IN PRODUCTION STATED IT UNTIL NOW. A widget added to
     * a ModularUI panel with no size is not stretched to fit -- it comes back at nothing, and
     * `WidgetTree.resizeInternal` catches Throwable around the whole resize and logs it through
     * `ModularUI.LOGGER.catching` rather than rethrowing -- verified against the 3.1.5 bytecode,
     * one exception-table entry spanning the resize and all three position passes -- so what a
     * caller who forgot gets is a panel that opens, lays out, hit-tests and draws an empty grey
     * rectangle, with the only complaint in the game log.
     * That is indistinguishable by eye from a broken renderer, an unarmed seam or a bad zoom,
     * which is exactly the confusion `ShotScreens`' verdict machinery was written for after five
     * blank flow screenshots survived two PRs.
     *
     * SO IT WAS THE SECOND HALF OF #323 AND NOT A TIDY-UP. That issue is that no player could
     * open the diagram; the three callers that could were all in `shot/`, and each carried its
     * own `pos(4, 4).size(612, 372)`. A first production opener would have had to know two
     * numbers nothing had ever told it, and getting them wrong fails silently.
     *
     * A FACTORY IN THIS PACKAGE RATHER THAN INSIDE `flowPanel`, AND THE DIRECTION DECIDES IT.
     * This package may name `PlannerWidgets` and that one may not name this, so the constants can
     * be read here and cannot be applied there. It is also the honest split: `flowPanel` takes
     * whatever widget it is handed BECAUSE `FlowCanvasTest` hands it deliberately small
     * viewports -- 300x180 -- to give `FlowCulling` something to drop. Sizing inside the panel
     * would overwrite every one of those and turn assertions about culling into assertions about
     * a viewport nothing is outside, without failing.
     *
     * A PLAN AND NOT A TREE SINCE #327, because the panel's chrome is sized from the plan's
     * WARNINGS and the canvas takes what is left. `PlannerWidgets.flowCanvasTop` and
     * `flowCanvasHeight` are the one place that split is decided, so a truncated plan's diagram
     * gets a shorter viewport and a warning line above it rather than a full-height viewport
     * drawn over the words -- which is what would happen if either side kept its own number.
     */
    public static FlowCanvas forPanel(PlanView plan, PlannerActions actions) {
        FlowCanvas canvas = new FlowCanvas(plan.tree(), actions);
        canvas.pos(PlannerWidgets.FLOW_PAD, PlannerWidgets.flowCanvasTop(plan));
        canvas.size(PlannerWidgets.FLOW_CANVAS_WIDTH, PlannerWidgets.flowCanvasHeight(plan));
        return canvas;
    }

    /**
     * @param actions where a click on a node goes. REQUIRED, and `PlannerActions.NONE` has to be
     *                written out by a caller that wants an inert diagram. #325: this argument did
     *                not exist, the constructor reached for the inert overload of
     *                `PlannerWidgets.planNodeContent`, and every node on every diagram in three
     *                releases did nothing when clicked.
     */
    public FlowCanvas(PlanNode tree, PlannerActions actions) {
        super(new HorizontalScrollData(), new VerticalScrollData());
        this.laid = FlowLayout.of(tree);
        this.culling = new FlowCulling(laid);
        this.boxWidgets = new ArrayList<PlannerWidgets.NodeContent>(laid.size());
        for (FlowLayout.Box box : laid.boxes) {
            // A BACKGROUND, unlike the tree's rows: on a canvas a row is text floating on a
            // panel rather than something bounded by its neighbours.
            //
            // DO NOT CHANGE THIS TO `MC_BUTTON`, OR TO ANY OTHER DARK BACKGROUND. The
            // background here is load-bearing for a decision made in a file this one does not
            // own, and getting it wrong costs a word rather than a crash.
            //
            // `NodeStatus`'s inks are the web UI's LIGHT theme, chosen because a ModularUI
            // panel is vanilla's light grey -- that is documented on the constants, and it
            // makes the surface underneath the shared row part of the row's contract. On the
            // button's dark face the quantity and the badge stayed legible and the item NAME
            // disappeared entirely, because it is drawn in `INK_MUTED`. A diagram of boxes
            // that will not tell you what is in them, and nothing fails: the widget is there,
            // sized, hit-testable and the right colour for a panel it is not on.
            //
            // Found by screenshot, and only by screenshot. If a dark canvas is ever actually
            // wanted, the fix is a dark-theme ink set in `NodeStatus` -- s2layout owns it --
            // and not a background swap here.
            //
            // SELECTION HIGHLIGHTING COMPOSES WITH THIS, and #213 settled how: a translucent
            // wash plus a frame, drawn by `PlannerWidgets.ClickableGroup.draw` over this
            // background and under the node's own text. This comment used to predict a
            // BACKGROUND SWAP, which would have violated the paragraph above it -- swapping
            // the nine-slice for a selected variant means choosing a second surface, and the
            // inks are only correct on this one. A wash keeps the surface and tints it.
            //
            // The node keeps the panel's own surface and is delimited by the nine-slice's
            // border. That border is also what makes this read as a graph at all: in a list a
            // row is bounded by the rows above and below it, and the first screenshot of this
            // canvas was three nodes and an elbow that read as unrelated captions.
            //
            // THE ACTIONS ARE THE CONSTRUCTOR'S AND NOT `PlannerActions.NONE` (#325). This line
            // called the three-argument overload, which hard-coded the inert actions, so the
            // diagram drew nodes that looked exactly like clickable tree rows and were not: no
            // node menu, no "Add to TODO", no recipe picker, no selection from the diagram. That
            // overload is deleted, so the choice now has to be made here and can be read here.
            PlannerWidgets.NodeContent widget = PlannerWidgets.planNodeContent(
                    box.node, FlowLayout.NODE_WIDTH, FlowLayout.NODE_HEIGHT, actions);
            widget.background(GuiTextures.MC_BACKGROUND);
            widget.pos(box.x, box.y);
            widget.size(FlowLayout.NODE_WIDTH, FlowLayout.NODE_HEIGHT);
            boxWidgets.add(widget);
            // Added to the real child list so ModularUI initialises and RESIZES every one of
            // them exactly once. `getChildren` culls what is DRAWN; it must not cull what is
            // laid out, or a node scrolled into view for the first time would arrive unsized.
            addChild(widget, -1);
        }
        int[] counts = new int[laid.size()];
        for (FlowLayout.Box box : laid.boxes) {
            if (box.parent >= 0) {
                counts[box.parent]++;
            }
        }
        this.onScreen = new boolean[laid.size()];
        this.childrenOf = new int[laid.size()][];
        for (int i = 0; i < laid.size(); i++) {
            childrenOf[i] = new int[counts[i]];
        }
        int[] at = new int[laid.size()];
        for (int i = 0; i < laid.size(); i++) {
            int parent = laid.boxes.get(i).parent;
            if (parent >= 0) {
                childrenOf[parent][at[parent]++] = i;
            }
        }
        applyScrollSize();
    }

    /**
     * The scroll extents, in SCREEN pixels at the current zoom.
     *
     * SCALED HERE RATHER THAN THE OFFSET BEING SCALED LATER, per {@link FlowZoom}'s note:
     * `ScrollData` clamps the offset against this size minus the widget area and knows nothing
     * about a scale, so telling it the size in screen pixels is what keeps its own clamp
     * correct. The alternative -- layout-unit offsets -- lets the view run off the end of a
     * zoomed-out diagram by a factor of the zoom, and the scrollbar thumb sizes itself wrong
     * as well.
     */
    private void applyScrollSize() {
        getScrollArea().getScrollX().setScrollSize(FlowZoom.scaledExtent(laid.width, zoom));
        getScrollArea().getScrollY().setScrollSize(FlowZoom.scaledExtent(laid.height, zoom));
    }

    /**
     * Move the viewport, WRAPPED around the layout, without a mouse.
     *
     * THIS LINE USED TO SAY "clamped", WHICH IS THE OPPOSITE OF WHAT IT DOES and is the exact
     * misreading that produced #293's blank flow shot: the first `panToBox` routed through here
     * on the strength of this summary, and {@link #wrap} turned its negative centring offset
     * into a positive one that panned AWAY from the target. A summary contradicting the body is
     * worse than none, because it is the line someone reads INSTEAD of the body. See
     * {@link #panToBox}, which aims and therefore clamps, and says why it must not come back
     * here.
     *
     * FOR THE TIMING HARNESS, which drives a pan with no input device. It goes through the
     * same scroll data a drag does rather than a private offset, so the frames it measures
     * are the frames a drag produces; a second code path would be measuring something the
     * player never sees.
     */
    public void panTo(int x, int y) {
        // AGAINST THE SCALED EXTENT, not `laid.width`. The offset is screen pixels, so at zoom
        // 2 the diagram is twice as many pixels wide and wrapping at the layout width would
        // park the timing run halfway along, quietly measuring only the left half of the plan.
        getScrollArea().getScrollX().scrollTo(getScrollArea(),
                wrap(x, FlowZoom.scaledExtent(laid.width, zoom), getArea().width));
        getScrollArea().getScrollY().scrollTo(getScrollArea(),
                wrap(y, FlowZoom.scaledExtent(laid.height, zoom), getArea().height));
    }

    /**
     * Set the zoom, keeping the point at the centre of the viewport where it is.
     *
     * ANCHORED ON THE CENTRE, NOT THE ORIGIN. Scaling around (0,0) drags the diagram out from
     * under the pointer -- zoom in on a node you are reading and it leaves the panel, so you
     * pan it back, which is the interaction feeling broken rather than being broken. Keeping
     * the centre fixed is what makes repeated zooming usable, and it is two lines: convert the
     * centre to layout coordinates at the old zoom, then back at the new one.
     */
    public FlowCanvas setZoom(float wanted) {
        float next = FlowZoom.clamp(wanted);
        if (next == zoom) {
            return this;
        }
        int x = FlowZoom.reanchor(getScrollX(), getArea().width, zoom, next);
        int y = FlowZoom.reanchor(getScrollY(), getArea().height, zoom, next);
        zoom = next;
        // SIZE BEFORE OFFSET. `scrollTo` clamps against the scroll size, so setting the offset
        // first clamps it against the OLD extent -- which on a zoom in silently parks the view
        // at the old right-hand limit, a third of the way along the diagram it just enlarged.
        applyScrollSize();
        getScrollArea().getScrollX().scrollTo(getScrollArea(), x);
        getScrollArea().getScrollY().scrollTo(getScrollArea(), y);
        return this;
    }

    /** Screen pixels per layout pixel. */
    public float zoom() {
        return zoom;
    }

    /**
     * Control-scroll zooms; a bare scroll still pans, which is what `AbstractScrollWidget`
     * already does and what every other scrollable panel in this GUI does.
     *
     * CONTROL RATHER THAN A BARE WHEEL. A canvas that zooms on an unmodified wheel is a canvas
     * you cannot scroll down, and this one is taller than it is wide on any real plan -- a
     * 4,000 node fan is a hundred screens tall and two columns across, so vertical scrolling
     * is the common gesture by a wide margin and it keeps the wheel.
     */
    @Override
    public boolean onMouseScroll(com.cleanroommc.modularui.api.UpOrDown scroll, int amount) {
        if (!com.cleanroommc.modularui.api.widget.Interactable.hasControlDown()) {
            return super.onMouseScroll(scroll, amount);
        }
        // A RATIO PER NOTCH, NOT AN INCREMENT. Adding 0.1 per notch takes five notches to go
        // from 0.5 to 1.0 and ten to go from 1.0 to 2.0, so zooming out feels twice as fast as
        // zooming in over the same visual change. Multiplying is symmetric by construction.
        setZoom(scroll.isUp() ? zoom * ZOOM_PER_NOTCH : zoom / ZOOM_PER_NOTCH);
        return true;
    }

    /** Ratio per wheel notch. Six notches cover the whole 0.5..2.0 range, which is two flicks. */
    private static final float ZOOM_PER_NOTCH = 1.25f;

    /**
     * Scale the children, on top of the scroll translate `super` applies.
     *
     * THROUGH THE VIEWPORT STACK RATHER THAN A GL MATRIX IN `draw`. This is the same hook
     * `getWidgetsAt` walks, so hit-testing goes through the inverse automatically -- which is
     * exactly the "hand-rolled canvases get panning right and hit-testing wrong" trap this
     * class's header already names, one step further along. A `GlStateManager.scale` in the
     * draw call would look identical and would leave every click landing on the wrong node.
     */
    @Override
    public void transformChildren(com.cleanroommc.modularui.api.layout.IViewportStack stack) {
        super.transformChildren(stack);
        if (zoom != 1.0f) {
            stack.scale(zoom, zoom);
        }
    }

    /** Wrap rather than clamp, so a long timing run keeps moving instead of parking. */
    private static int wrap(int value, int extent, int viewport) {
        int range = Math.max(1, extent - viewport);
        return Math.abs(value % range);
    }

    /**
     * Move the viewport to a FRACTION of each scroll range, 0 to 1.
     *
     * FOR THE TIMING HARNESS, and it exists because {@link #panTo} in pixels was measuring the
     * wrong thing. A fixed pixel stride over a few hundred frames covers a few thousand pixels,
     * and a 4,000 node plan is around seventy THOUSAND pixels tall -- so the sweep never left
     * the top-left corner. Worse, the corner it never left is column zero, which on this layout
     * holds one node: the root, centred on the pixel midpoint of the whole diagram and
     * therefore nowhere near the top. Instrumenting `drawnLastFrame` showed the run drawing
     * 0, 1 and 4 nodes at the frames it sampled. Every frame timing quoted for this diagram
     * before 2026-08-03 was measured on a mostly empty viewport.
     *
     * A fraction cannot do that: 0 to 1 is the whole diagram whatever its size and whatever
     * the zoom, because the ranges come from the scroll data the zoom already scaled.
     */
    public void panToFraction(double fractionX, double fractionY) {
        getScrollArea().getScrollX().scrollTo(getScrollArea(),
                atFraction(fractionX, FlowZoom.scaledExtent(laid.width, zoom),
                        getArea().width));
        getScrollArea().getScrollY().scrollTo(getScrollArea(),
                atFraction(fractionY, FlowZoom.scaledExtent(laid.height, zoom),
                        getArea().height));
    }

    private static int atFraction(double fraction, int extent, int viewport) {
        int range = Math.max(0, extent - viewport);
        return (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * range);
    }

    /**
     * Which box, if any, is under a point in LAYOUT coordinates. -1 for a gap or off the end.
     *
     * THE SAME INDEX AS THE CULLER, not a scan over `boxes`. A click is one event rather than
     * sixty a second, so speed is not the argument -- the argument is that a hit-test
     * disagreeing with the cull is a bug you cannot see: a node you can click but cannot see,
     * or see but cannot click, in some places and not others.
     *
     * KEPT AS A CROSS-CHECK ON MODULARUI RATHER THAN AS THE CLICK PATH. `getWidgetsAt` already
     * routes a click through the viewport transform AND the zoom matrix, and reimplementing
     * that by hand is the exact trap this class's header names. `flow-hit` compares the two so
     * the claim that the framework gets it right is measured rather than assumed.
     *
     * A POINT IN A GAP IS -1 RATHER THAN THE NEAREST BOX: the column gap is 40px, it is where
     * the edges are drawn, and "nearest" would silently claim a 20px strip either side of
     * every node.
     */
    public int boxAt(int layoutX, int layoutY) {
        return culling.boxAt(layoutX, layoutY);
    }

    /** Screen point (relative to this widget's area) to layout coordinates, at this zoom. */
    public int toLayoutX(int screenX) {
        return (int) Math.floor((screenX + getScrollX()) / (double) zoom);
    }

    /** Screen point (relative to this widget's area) to layout coordinates, at this zoom. */
    public int toLayoutY(int screenY) {
        return (int) Math.floor((screenY + getScrollY()) / (double) zoom);
    }

    /**
     * Layout coordinate to a screen point relative to this widget's area, at this zoom.
     *
     * THE PAIR ABOVE, RUN THE OTHER WAY, AND THE ONE PLACE THAT KNOWS THE VIEWPORT'S MAP.
     * {@link FlowZoom#toScreen} says which order ModularUI composes the scroll translate and the
     * zoom scale in, and why the other order is right at zoom 1 and wrong everywhere else.
     *
     * FIVE THINGS NEED IT, AND FOUR OF THEM HAD IT WRITTEN OUT BY HAND, which is how the fifth
     * came to be written without it at all: {@link #fullyShowing} (through
     * {@link #boxRectOnScreen}), {@link #parkCursorOverBox}, {@link #jumpToSelected}, the edge
     * sweep and the harness. All five go through here now.
     *
     * {@link #panToBox} DELIBERATELY DOES NOT, and it is the one caller that must not be
     * "tidied" into this. It PRODUCES a scroll offset rather than consuming one, so it wants
     * `round(layout * zoom)` with no scroll term at all; routing it through here would subtract
     * the current offset from the offset being computed and pan by the difference.
     */
    public int toScreenX(double layoutX) {
        return FlowZoom.toScreen(layoutX, zoom, getScrollX());
    }

    /** Layout coordinate to a screen point relative to this widget's area, at this zoom. */
    public int toScreenY(double layoutY) {
        return FlowZoom.toScreen(layoutY, zoom, getScrollY());
    }

    /**
     * The box index ModularUI thinks is under the mouse, or -1.
     *
     * Asks the widgets themselves rather than walking a viewport stack, because
     * `IWidget.isHovering()` is already the answer `getWidgetsAt` produced this frame -- so
     * this reads the framework's real verdict rather than a re-derivation of it.
     *
     * THIS FLAG ONLY ANSWERS BECAUSE THE CONSTRUCTOR GIVES EVERY NODE `MC_BACKGROUND`, and
     * that is a dependency rather than a coincidence: `ModularGuiContext.getHoveredWidgets`
     * skips any widget whose `canHover()` is false, and for a `ParentWidget` that means one
     * with no visible background, hover background, hover overlay or tooltip. A `NodeContent`
     * stripped of its background would leave this method returning -1 forever, with nothing
     * failing anywhere -- which is exactly what happened to the tree's hover band in #333; see
     * `PlannerWidgets.ClickableGroup.drawsAsHovered` for the bytecode and the measurement.
     * `isBelowMouse()` is NOT the substitute here: it does not break ties between overlapping
     * boxes, and this method must name exactly one.
     */
    public int hoveredBox() {
        for (int i = 0; i < visibleBoxes.size(); i++) {
            int index = visibleBoxes.get(i);
            if (boxWidgets.get(index).isHovering()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Centre the viewport on box `index`, so a screenshot is of something rather than of the
     * gap between things.
     *
     * THE OPPOSITE DIRECTION TO {@link #parkCursorOverBox}, and both are correct for their own
     * job. The hit probe must NOT scroll, because scrolling a node to a fixed cursor is what
     * made every probe land on the same empty middle and agree about nothing. A photograph has
     * no such constraint and the reverse problem: nothing is under the camera unless something
     * is put there.
     *
     * A BOX INDEX RATHER THAN A FRACTION, because a fraction is a claim about where the
     * content is and a box is the content. `panToFraction(0, 0)` is the whole of #293: it is a
     * position nobody checked was occupied, on a layout whose top-left corner is guaranteed
     * empty, and it produced five identical blank screenshots that were cited as artifacts.
     */
    public void panToBox(int index) {
        FlowLayout.Box box = laid.boxes.get(index);
        aimAt(box.x + FlowLayout.NODE_WIDTH / 2.0, box.y + FlowLayout.NODE_HEIGHT / 2.0);
    }

    /**
     * Put a parent and one of its children BOTH wholly in frame, by centring their union.
     *
     * NOT {@link #panToBox} ON EITHER END, AND THE ARITHMETIC IS THE WHOLE REASON THIS EXISTS.
     * A child sits exactly `COLUMN_PITCH` right of its parent, so the pair spans
     * `COLUMN_PITCH + NODE_WIDTH` = 458 layout pixels. Centre the PARENT on the production 612px
     * canvas and its left edge lands at 202, which puts the child's right edge at 660 -- 48 past
     * the panel. Centring the union leaves 77 pixels of margin either side.
     *
     * WHY THE HARNESS NEEDS IT. `flow-nav` drags 60px and then measures whether an edge meets the
     * nodes it joins, and the drag lands wherever the layout puts it: on `plan-truncated` every
     * pair had at least one box clipped by the panel edge, so the measurement had nothing to
     * measure and the shot exited on its own "no measurable pair" verdict -- while the PNG beside
     * it showed the fix working. A probe that cannot reach a measurable state must NAVIGATE to
     * one; giving up is a verdict about the scenario, not about the code.
     *
     * PAIRED WITH {@link #pairFitsTheViewport}, which is the question this cannot answer for
     * itself: at zoom 2 the pair needs 916 pixels and no pan puts both ends in a 612px canvas.
     * Ask first, aim second, and let the caller say so loudly when nothing fits.
     */
    public void panToPair(int parent, int child) {
        FlowLayout.Box first = laid.boxes.get(parent);
        FlowLayout.Box second = laid.boxes.get(child);
        aimAt((first.x + second.right()) / 2.0,
              (Math.min(first.y, second.y) + Math.max(first.bottom(), second.bottom())) / 2.0);
    }

    /**
     * Could box `parent` and box `child` both be wholly in frame at this zoom, at any offset?
     *
     * A QUESTION ABOUT THE ZOOM AND THE PANEL, NOT ABOUT WHERE THE VIEW IS. It compares the
     * pair's layout extent, scaled, against the canvas -- so a false here means no pan can help
     * and the caller should say that rather than pan and hope. {@link #panToPair} has the
     * numbers; the pair is 458 layout pixels wide, which fits a 612px canvas up to about zoom
     * 1.33 and never at {@link FlowZoom#MAX}.
     */
    public boolean pairFitsTheViewport(int parent, int child) {
        FlowLayout.Box first = laid.boxes.get(parent);
        FlowLayout.Box second = laid.boxes.get(child);
        int width = second.right() - first.x;
        int height = Math.max(first.bottom(), second.bottom()) - Math.min(first.y, second.y);
        return FlowZoom.scaledExtent(width, zoom) <= getArea().width
                && FlowZoom.scaledExtent(height, zoom) <= getArea().height;
    }

    /**
     * Centre the viewport on a layout point, clamped to the scrollable range.
     *
     * CLAMPED, AND EXPLICITLY NOT THROUGH `panTo`, WHICH WRAPS. This was the first version of
     * {@link #panToBox} and it produced a screenshot that passed the drawn check and was still
     * unreadable. Centring on the ROOT asks for a negative offset -- the root sits at x=0 and
     * half a viewport to its left is off the layout -- and `wrap`'s `Math.abs(value % range)`
     * turns -202 into +202, which pans AWAY from the box by almost its own width. What landed
     * was a two-pixel sliver of one node against the left edge, on a panel that still read as
     * empty.
     *
     * `wrap` is right for `panTo`, whose caller is a sweep that must keep moving rather than park
     * at an edge, and its comment says so. It is wrong for aiming, where running off the end
     * means "as far as it goes", not "reappear on the other side". DO NOT route this back through
     * `panTo` to save four lines.
     *
     * SHARED BY {@link #panToBox} AND {@link #panToPair} so the clamp has one definition. Two
     * aims with two copies of that reasoning is two chances for one of them to become a wrap
     * again, and the failure it produces is a screenshot nobody can read rather than an error.
     *
     * `FlowZoom.toScreen` WITH A ZERO OFFSET, because this PRODUCES a scroll offset rather than
     * consuming one -- see {@link #toScreenX}'s note on why `panToBox` must not go through the
     * canvas-relative pair.
     */
    private void aimAt(double layoutX, double layoutY) {
        int x = FlowZoom.toScreen(layoutX, zoom, 0) - getArea().width / 2;
        int y = FlowZoom.toScreen(layoutY, zoom, 0) - getArea().height / 2;
        getScrollArea().getScrollX().scrollTo(getScrollArea(),
                clampToRange(x, FlowZoom.scaledExtent(laid.width, zoom), getArea().width));
        getScrollArea().getScrollY().scrollTo(getScrollArea(),
                clampToRange(y, FlowZoom.scaledExtent(laid.height, zoom), getArea().height));
    }

    /** An offset held inside the scrollable range, rather than wrapped around it. */
    private static int clampToRange(int value, int extent, int viewport) {
        return Math.max(0, Math.min(value, Math.max(0, extent - viewport)));
    }

    /** Is box `index` wholly inside the viewport right now? */
    public boolean fullyShowing(int index) {
        int[] rect = boxRectOnScreen(index);
        return rect[0] >= 0 && rect[1] >= 0
                && rect[0] + rect[2] <= getArea().width
                && rect[1] + rect[3] <= getArea().height;
    }

    /**
     * Box `index`'s rectangle in screen pixels relative to this canvas, as {x, y, width, height}.
     *
     * WHERE MODULARUI IS ABOUT TO DRAW THAT NODE, expressed by the same map the framework
     * applies to it -- so this is the rectangle an edge has to meet, and the one the harness
     * measures adjacency against. {@link #fullyShowing} used to compute its four edges inline
     * and is now one caller of this rather than a second definition of it.
     *
     * ALLOCATES, AND IS THEREFORE NOT ON THE PER-FRAME PATH. Four ints a call is nothing at
     * click or probe rates and is a garbage generator at sixty frames a second across a
     * thousand nodes, which is why the edge sweep uses {@link #edgeSegments} and its reused
     * buffer instead of building rectangles.
     */
    public int[] boxRectOnScreen(int index) {
        FlowLayout.Box box = laid.boxes.get(index);
        int left = toScreenX(box.x);
        int top = toScreenY(box.y);
        return new int[] {left, top, toScreenX(box.right()) - left,
                          toScreenY(box.bottom()) - top};
    }

    /**
     * The index of the shallowest box, which is the root.
     *
     * SEARCHED RATHER THAN ASSUMED TO BE ZERO. It is zero today, and a caller that hard-codes
     * that is a caller which keeps compiling and starts photographing the wrong thing the day
     * the layout walks in another order -- the silent-wrong-picture failure this whole area
     * has already paid for once.
     */
    public int rootBox() {
        int best = 0;
        for (int i = 1; i < laid.size(); i++) {
            if (laid.boxes.get(i).depth < laid.boxes.get(best).depth) {
                best = i;
            }
        }
        return best;
    }

    /**
     * {@inheritDoc}
     *
     * THE THREE METHODS THIS DELEGATES TO WERE ALL WRITTEN, TESTED AND HARNESS-ONLY.
     * `panToBox`, `rootBox` and `fullyShowing` landed with #293 and their only callers outside
     * `FlowCanvasTest` were in `shot/`. That is the #191 shape at the level of a feature rather
     * than of a seam: nothing was broken, and a reader panned into the middle of a 634-node
     * diagram had no way back short of closing the window.
     */
    @Override
    public void jumpToRoot() {
        panToBox(rootBox());
    }

    /**
     * {@inheritDoc}
     *
     * THE NEAREST OCCURRENCE TO THE CURRENT CENTRE, NOT THE FIRST, and the choice is about what a
     * jump is for. The same key appears once per parent that needs it -- `PlanSelection`'s class
     * note is about exactly this -- so "the first" is an arbitrary one of six, and pressing the
     * button twice from two different places would send the reader to the same distant box both
     * times. Nearest makes the button mean "show me this item" from wherever you are.
     *
     * AND IT IS EXPLICITLY NOT A WAY TO REACH THE CLICKED OCCURRENCE. `PlanSelection.selectedNode`
     * is the only thing that knows which one that was, and re-deriving it from the key by walking
     * is the wrong-quantity bug that note forbids. Nothing here returns a node, so nothing here
     * can be mistaken for that.
     */
    @Override
    public boolean jumpToSelected() {
        String key = PlanSelection.selectedKey();
        if (key.isEmpty()) {
            return false;
        }
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < laid.size(); i++) {
            FlowLayout.Box box = laid.boxes.get(i);
            if (!key.equals(box.node.key())) {
                continue;
            }
            // THROUGH THE ONE CONVERSION rather than `round(centre * zoom) - scroll` written
            // out again. This was the fifth open-coding of the viewport map and the edge sweep
            // was the fourth, which is the one that turned out to be missing (#330). A box's
            // distance from the viewport's centre is its SCREEN position less half the area.
            long dx = toScreenX(box.x + FlowLayout.NODE_WIDTH / 2.0) - getArea().width / 2;
            long dy = toScreenY(box.y + FlowLayout.NODE_HEIGHT / 2.0) - getArea().height / 2;
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0) {
            return false;
        }
        panToBox(best);
        return true;
    }

    /**
     * How far the mouse has to move before a press on empty canvas becomes a pan.
     *
     * WHY A THRESHOLD IS NEEDED AT ALL, since a press on a NODE never reaches this widget:
     * ModularUI consults the hovered widgets innermost first, so a `NodeContent` takes its own
     * press and returns SUCCESS before the canvas is asked. What is left is a press on the gaps
     * BETWEEN nodes -- which is most of the canvas -- and there a one-pixel tremor between press
     * and release would shift the whole diagram under a reader who thought they had clicked
     * nothing. Anchoring on the press position means crossing the threshold does not jump either.
     */
    private static final int DRAG_THRESHOLD = 3;

    /** Where the mouse and the viewport were when the press landed. Client thread only. */
    private int anchorMouseX;
    private int anchorMouseY;
    private int anchorScrollX;
    private int anchorScrollY;

    /** A press landed on the canvas body and no scrollbar claimed it. */
    private boolean armed;

    /** The armed press has crossed {@link #DRAG_THRESHOLD} and is now a pan. */
    private boolean panning;

    /**
     * Arm a drag-pan, unless the scrollbar wanted this press.
     *
     * THE SCROLLBAR GETS FIRST REFUSAL, which is what `super` answers: measured in the 3.1.5
     * bytecode, `AbstractScrollWidget.onMousePressed` is `scroll.mouseClicked(context) ? SUCCESS :
     * IGNORE` and nothing else -- so it takes a press on the thumb and ignores every press on the
     * canvas body. Panning the content on a press the thumb accepted would move the view twice
     * per drag, once from each mechanism.
     *
     * SUCCESS ON THE BODY, WHICH IS WHAT ROUTES `onMouseDrag` HERE. ModularUI hands drags to the
     * widget that accepted the press, so returning IGNORE -- which is what this widget did until
     * now, and why the diagram could only be panned by its scrollbars -- means no drag is ever
     * delivered.
     */
    @Override
    public com.cleanroommc.modularui.api.widget.Interactable.Result onMousePressed(int button) {
        com.cleanroommc.modularui.api.widget.Interactable.Result result =
                super.onMousePressed(button);
        if (result != null && result.accepts) {
            armed = false;
            panning = false;
            return result;
        }
        anchorMouseX = getContext().getAbsMouseX();
        anchorMouseY = getContext().getAbsMouseY();
        anchorScrollX = getScrollX();
        anchorScrollY = getScrollY();
        armed = true;
        panning = false;
        return com.cleanroommc.modularui.api.widget.Interactable.Result.SUCCESS;
    }

    /**
     * Drag the canvas body to pan, once the press has moved far enough to mean it.
     *
     * THE DELTA IS AGAINST THE ANCHOR AND NOT AGAINST THE LAST FRAME, so the accumulated offset
     * cannot drift: a per-frame delta loses whatever the clamp swallowed at an edge, and the
     * diagram then no longer returns to where it started when the mouse does.
     *
     * THE SIGN IS INVERTED ON PURPOSE. Dragging the content left must reveal what is to the
     * RIGHT, which is a larger scroll offset -- this is grab-and-move, the gesture every map and
     * every node-link diagram in the pack uses, and the opposite of what a scrollbar does.
     */
    @Override
    public void onMouseDrag(int button, long timeSinceClick) {
        super.onMouseDrag(button, timeSinceClick);
        if (!armed) {
            return;
        }
        int dx = anchorMouseX - getContext().getAbsMouseX();
        int dy = anchorMouseY - getContext().getAbsMouseY();
        if (!panning && Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) {
            return;
        }
        panning = true;
        getScrollArea().getScrollX().scrollTo(getScrollArea(),
                clampToRange(anchorScrollX + dx, FlowZoom.scaledExtent(laid.width, zoom),
                             getArea().width));
        getScrollArea().getScrollY().scrollTo(getScrollArea(),
                clampToRange(anchorScrollY + dy, FlowZoom.scaledExtent(laid.height, zoom),
                             getArea().height));
    }

    @Override
    public boolean onMouseRelease(int button) {
        armed = false;
        panning = false;
        return super.onMouseRelease(button);
    }

    /** Whether a drag-pan is in progress. For a test or a log. */
    public boolean isPanning() {
        return panning;
    }

    /**
     * Put the real cursor over the centre of box `index`. Returns false if it is off screen.
     *
     * FOR THE HIT-TEST PROBE, and it is the first thing in this project to move a mouse.
     *
     * IT DOES NOT SCROLL THE NODE TO THE CURSOR, which was the first version and was wrong in
     * a way that produced a clean-looking pass. It centred the cursor in the canvas and
     * scrolled the wanted node under it -- but a layout SMALLER than the viewport cannot
     * scroll at all, so every probe landed on the same empty middle of the canvas and both
     * answers came back -1. Six lines of "AGREE", agreeing about nothing. The cursor goes to
     * where the node already is instead, and a node that is not on screen is skipped loudly
     * rather than probed at the wrong place.
     *
     * THE GUI-TO-DISPLAY CONVERSION IS {@link ScreenCursor}'S AND MUST STAY THERE (#240).
     * `Mouse.setCursorPosition` takes DISPLAY pixels with the origin at the BOTTOM left, while
     * everything here is GUI pixels from the top left. Two conversions, either of which puts
     * the cursor somewhere plausible and blames the canvas for the probe's own error -- and
     * this file had them written out three times, in the three methods below, where nothing
     * could assert them. DO NOT inline the arithmetic back: `ScreenCursorTest` asserts the y
     * flip as a fact with no window, which is the only place in this project that can.
     */
    public boolean parkCursorOverBox(int index) {
        FlowLayout.Box box = laid.boxes.get(index);
        int screenX = toScreenX(box.x + FlowLayout.NODE_WIDTH / 2.0);
        int screenY = toScreenY(box.y + FlowLayout.NODE_HEIGHT / 2.0);
        if (screenX < 0 || screenY < 0
                || screenX >= getArea().width || screenY >= getArea().height) {
            return false;
        }
        ScreenCursor.park(getArea().x + screenX, getArea().y + screenY);
        return true;
    }

    /**
     * Where the probe thinks everything is, for when the probe itself is the thing wrong.
     *
     * THE RAW LWJGL PAIR IS PART OF THE LINE, not a redundant echo of `gui=`. This exists to
     * be read when the two answers disagree, and "the raw y is what I set and the gui y is
     * the mirror of it" is the reading that names the conversion rather than the canvas.
     */
    public String cursorDiagnostic() {
        int scale = ScreenCursor.scale();
        int displayHeight = ScreenCursor.displayHeight();
        int rawX = org.lwjgl.input.Mouse.getX();
        int rawY = org.lwjgl.input.Mouse.getY();
        int guiX = ScreenCursor.toGuiX(rawX, scale);
        int guiY = ScreenCursor.toGuiY(rawY, scale, displayHeight);
        return "mouseRaw=" + rawX + "," + rawY
                + " scale=" + scale
                + " gui=" + guiX + "," + guiY
                + " area=" + getArea().x + "," + getArea().y
                + " " + getArea().width + "x" + getArea().height
                + " scroll=" + getScrollX() + "," + getScrollY()
                + " layout=" + toLayoutX(guiX - getArea().x) + ","
                + toLayoutY(guiY - getArea().y)
                + " drawn=" + visibleBoxes.size();
    }

    /** What the layout says is under the cursor right now, as a box index or -1. */
    public int boxAtCursor() {
        int guiX = ScreenCursor.guiX();
        int guiY = ScreenCursor.guiY();
        return boxAt(toLayoutX(guiX - getArea().x), toLayoutY(guiY - getArea().y));
    }

    /** How many boxes the layout produced. */
    public int nodeCount() {
        return laid.size();
    }

    /**
     * The plan node box `index` draws.
     *
     * FOR THE HARNESS, and specifically so a probe can assert that the menu a click OPENED is the
     * menu for the box that was CLICKED. `flow-node-menu` reads the box under the real cursor
     * from {@link #boxAtCursor} -- the layout's own answer -- and this turns it into the node
     * whose label the menu panel must be titled with. Without it the strongest available
     * verdict would be "a node menu is on top", which is equally true of the menu for the wrong
     * node, and a screenshot of it is equally convincing. Same discrimination `jei-keybind`
     * makes about the hovered stack.
     */
    public PlanNode boxNode(int index) {
        return laid.boxes.get(index).node;
    }

    /**
     * A point in the canvas that is inside no box, in SCREEN pixels relative to the canvas, or
     * null when the diagram is dense enough that there is none on screen.
     *
     * FOR THE DRAG-PAN PROBE, and the reason it searches rather than computing is
     * `parkCursorOverBox`'s one level over: a press on a NODE is taken by the node -- ModularUI
     * consults hovered widgets innermost first -- so a probe that guessed at an empty point and
     * hit a box would drive the node menu and report the canvas as unable to pan. The column gap
     * is 40px and the row pitch leaves gaps, so a coarse sweep finds one; returning null rather
     * than a guess is what makes "no empty point" a distinguishable outcome from "panning is
     * broken".
     *
     * THE INSET IS THE CALLER'S DRAG BUDGET, NOT PADDING. The sweep used to start at (2,2) --
     * and after `panToBox(rootBox())` the layout's top-left corner is guaranteed empty, so
     * (2,2) is what it returned. A probe that then drags UP-LEFT from there aims the cursor
     * OFF-SCREEN; `ScreenCursor.park` does not clamp, the OS does, and the measured pan comes
     * out as the distance to the screen edge rather than the drag. So a caller that intends to
     * drag by N passes N, and every point returned can move N in ANY direction while staying
     * inside the canvas.
     */
    public int[] emptyPointOnScreen(int inset) {
        int lo = Math.max(2, inset);
        for (int y = lo; y < getArea().height - inset; y += 4) {
            for (int x = lo; x < getArea().width - inset; x += 4) {
                if (boxAt(toLayoutX(x), toLayoutY(y)) < 0) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    /** Direct children per box, so an edge sweep can start from what is visible. */
    private final int[][] childrenOf;

    /**
     * Which boxes {@link #updateVisible} last put on screen, indexed by box.
     *
     * THE SAME INFORMATION {@link #visibleBoxes} CARRIES, INDEXED INSTEAD OF SCANNED, and it
     * exists because {@link #sweepEdges} has to ask "is this child on screen" once per child
     * per frame. Off the list that is a linear search inside a loop over the list, which is
     * quadratic in what is drawn on exactly the path this package has a frame-time gate on.
     *
     * A BOOLEAN PER BOX IS THE WHOLE COST, allocated once with the layout, and it is cleared
     * from the PREVIOUS visible set rather than filled over every box -- so a frame pays for
     * what the last one drew, not for what the plan contains.
     */
    private final boolean[] onScreen;

    /**
     * The viewport {@link #updateVisible} last culled against, in LAYOUT pixels.
     *
     * THE CULLER'S OWN RECTANGLE, ROUNDED OUTWARD, and the rounding is what makes
     * {@link #sweepTrunks}'s reasoning sound rather than nearly sound.
     * {@link FlowZoom#layoutOrigin} floors and {@link FlowZoom#layoutExtent} ceils, so this covers
     * every layout pixel the screen viewport touches -- which means anything with a pixel on this
     * canvas is inside it, and a trunk pass that skips an edge outside it cannot be skipping an
     * edge that would have painted.
     * Rounding inward instead would drop edges along two borders at non-integer zooms only, which
     * is the failure shape `FlowZoom`'s own header was written about.
     */
    private int viewLayoutX;
    private int viewLayoutY;
    private int viewLayoutWidth;
    private int viewLayoutHeight;

    /** How many were drawn last frame. The culling claim, readable from a test or a log. */
    public int drawnLastFrame() {
        return visible.size();
    }

    /**
     * Subtree bands {@link #sweepTrunks} looked at this frame. The trunk pass's cost, in one
     * number, and the same argument {@link #drawnLastFrame} makes.
     *
     * THE CLAIM #334 HAD TO KEEP IS ALGORITHMIC AND THIS IS HOW IT IS OBSERVED. "The frame is
     * still fast" depends on the host and the rasteriser; "a frame SEARCHES a bounded multiple of
     * what a viewport can hold, whatever the plan contains" does not. `FlowCanvasTest` reads this
     * against a budget derived from the viewport rather than calibrated to a measurement.
     *
     * <h2>IT IS THE SEARCH THAT IS BOUNDED BY THE VIEWPORT, NOT THE PAINTING, AND THIS NOTE USED
     * TO SAY THIS NUMBER DECIDES THE 60 FPS GATE. IT DOES NOT.</h2>
     *
     * What a frame paints is proportional to what CROSSES the viewport, and on a wide fan that is
     * a share of the plan rather than of the viewport -- because every child of one parent shares
     * a corner x, so a fan whose parent is off frame owes the canvas one trunk per child and they
     * all land in the same one-pixel column. Measured on a replica of this layout and this
     * conversion, 612x372, viewport parked where no box is
     * (`evidence-334-335/review/worstcase.py`):
     *
     * <pre>
     *   fixture      zoom  scrollY   boxes   bands  considered  painted
     *   fan(4000)     1.0    31905    4001       2         997      997
     *   fan(4000)     2.0    63904    4001       2         998      998
     *   fan(200)      1.0     1505     201       2          47       47
     *   deepFan(3,7)  1.0    17401    3280       5           2        2
     * </pre>
     *
     * `bandsExamined` is 2 in the 4,000-box row: the search really is bounded, and it is what this
     * counter is for. `painted` is 997, a quarter of the plan, and NOTHING here bounds it -- every
     * one of those edges genuinely has a pixel on this canvas, so drawing them is correct and #334
     * is the defect of not drawing them.
     *
     * DO NOT READ THAT AS A REGRESSION THIS PASS INTRODUCED, because master already had frames of
     * that shape: with the fan's parent ON screen, {@link #sweepEdges}'s first pass draws all
     * 4,000 edges from the parent's side, and it always did. What #334 changed is that the same
     * cost now also occurs at offsets where the parent is off frame. Same order, more positions.
     *
     * WHAT IS ACTUALLY UNTESTED IS THE PAINT COUNT AT THE SIZE THE GATE IS MEASURED AT.
     * `FlowCulling`'s header pins the 60 fps panning gate to `DEFAULT_MAX_NODES` = 4,000, and the
     * widest fixture any cost test here uses is `fan(200)`. If panning ever misses the frame on a
     * wide fan, the fix is to coalesce the trunks of one parent into a single run per column gap
     * -- they are collinear by construction -- and NOT to narrow this search, which is already
     * bounded and is not what costs.
     */
    public int bandsExamined() {
        return culling.bandsExamined();
    }

    /**
     * Enable only what the viewport covers, just before the children are drawn.
     *
     * BY TOGGLING `enabled`, NOT BY OVERRIDING `getChildren`. Overriding it culls the draw
     * -- `drawTree` walks exactly that list -- and it also culls every OTHER lifecycle call
     * that walks children, including `addChild`'s own duplicate check, which consults
     * `getChildren()` and would have been comparing against a viewport-dependent list. A node
     * dropped from initialisation or disposal because it happened to be off screen is a bug
     * with no symptom until someone pans to it.
     *
     * `drawTree` checks `isEnabled()` per child, so a disabled node costs a boolean read
     * instead of a draw. The per-frame work here is proportional to what ENTERED or LEFT the
     * viewport, not to the plan: panning a few pixels touches a handful of flags, and a plan
     * of four thousand nodes with forty on screen draws forty.
     *
     * <h2>And decide how much of each node is worth drawing at this zoom</h2>
     *
     * LEVEL OF DETAIL, and the two alternatives lose for different reasons.
     *
     * TRUNCATION does not answer the question at all: the label is already cut to its column by
     * `NodeRowText.fit`, and zooming out does not overflow it -- it scales it. A label that
     * fits perfectly and is three physical pixels tall is not a truncation problem. Cutting it
     * FURTHER by screen width would make one node say different words at different zooms, which
     * costs the diagram its reproducibility: a screenshot of `flow:plan-in-stock` would no
     * longer be a picture of a plan, it would be a picture of a plan at a zoom.
     *
     * AN ICON-ONLY NODE loses on the keys that have no icon. 1,198 of this pack's fluids exist
     * only as `fluid:<name>` and an `ore:` node is a group rather than an item, so
     * `NodeActions.iconFor` correctly answers EMPTY for them -- and a diagram whose zoomed-out
     * form is empty boxes for every fluid in a fluid chain is worse than one with no zoom.
     * `plan-fluid-chain` is 634 nodes and is exactly that case.
     *
     * So the icon and the QUANTITY stay at every zoom, and the label and the badge word go
     * below `FlowZoom.LABEL_LEGIBLE`. That is what `FlowZoom.MIN`'s own comment says a reader
     * zoomed out is reading, and the quantity carries `NodeStatus.colour`, so status keeps a
     * channel with no text at all.
     */
    @Override
    public void preDraw(ModularGuiContext context, boolean transformed) {
        super.preDraw(context, transformed);
        if (transformed) {
            return;
        }
        updateVisible();
    }

    /**
     * The culling and the level-of-detail decision, with no `context` and therefore no GL.
     *
     * SPLIT OUT OF {@link #preDraw} SO A TEST CAN DRIVE IT. `super.preDraw` applies the
     * `Stencil` that clips the children, which is a GL call and cannot run headlessly -- so
     * before #330 nothing but a screenshot had ever observed which boxes this decides are on
     * screen, and {@link #sweepEdges} walks exactly that set. `FlowCanvasTest` now calls this
     * and then the sweep, which is the pair of steps a frame takes.
     *
     * NOT A CONVENIENCE FOR THE TEST: the same split is what lets an assertion about culling be
     * made at all. `drawnLastFrame` was readable only after a real draw, so the only instrument
     * pointed at this was the frame-timing harness, which counts nodes and cannot say WHICH.
     */
    void updateVisible() {
        // EVERY VISIBLE NODE EVERY FRAME, not only the ones that just entered the viewport.
        // Zoom changes without the visible set changing at all -- a control-scroll on a small
        // plan moves no node in or out -- so detail applied only on entry would leave the
        // labels showing at 0.5 until the next pan. It is a boolean write per drawn node.
        boolean detail = zoom >= FlowZoom.LABEL_LEGIBLE;
        // CONVERTED, because the scroll offset is screen pixels and the culler works in layout
        // pixels. `FlowZoom` rounds the two edges outward; passing `getScrollX() / zoom` and
        // `width / zoom` directly rounds both inward and hides a strip of nodes along the
        // right and bottom edges at any zoom that is not a whole number.
        //
        // AND KEPT, because {@link #sweepTrunks} asks a second question of the same rectangle
        // and the two must be the same rectangle. Recomputing it there would be four more calls
        // that agree today and are one edit away from a sweep culling against a viewport the
        // culler never saw -- the #191 two-definitions shape, and here it would show up as an
        // edge drawn or dropped for a viewport nothing is looking at.
        viewLayoutX = FlowZoom.layoutOrigin(getScrollX(), zoom);
        viewLayoutY = FlowZoom.layoutOrigin(getScrollY(), zoom);
        viewLayoutWidth = FlowZoom.layoutExtent(getScrollX(), getArea().width, zoom);
        viewLayoutHeight = FlowZoom.layoutExtent(getScrollY(), getArea().height, zoom);
        IntArray shown = culling.visibleIn(
                viewLayoutX, viewLayoutY, viewLayoutWidth, viewLayoutHeight);
        for (IWidget widget : visible) {
            widget.setEnabled(false);
        }
        for (int i = 0; i < visibleBoxes.size(); i++) {
            onScreen[visibleBoxes.get(i)] = false;
        }
        visible.clear();
        visibleBoxes.clear();
        for (int i = 0; i < shown.size(); i++) {
            int index = shown.get(i);
            PlannerWidgets.NodeContent widget = boxWidgets.get(index);
            widget.setEnabled(true);
            widget.showDetail(detail);
            visible.add(widget);
            visibleBoxes.add(index);
            onScreen[index] = true;
        }
    }

    /** Whether the current zoom is one at which a node's name is drawn. For a test or a log. */
    public boolean showsLabels() {
        return zoom >= FlowZoom.LABEL_LEGIBLE;
    }

    /**
     * The edges, drawn beneath the nodes.
     *
     * ON THE CANVAS RATHER THAN AS WIDGETS. An edge is two points and a line; making each one
     * a widget would double the child count for something that never needs to be hit-tested,
     * sized or themed. BetterQuesting's `PanelLine` makes the same call.
     *
     * WHICH IS ALSO WHY THE COORDINATES HAVE TO BE CONVERTED BY HAND, AND #330 IS WHAT IT COST
     * WHEN THEY WERE NOT. A widget child gets the scroll translate and the zoom scale from the
     * viewport stack; this method gets neither, so an edge drawn in layout coordinates stays
     * where the diagram was at scroll 0 and zoom 1. See {@link #edgeSegments}.
     *
     * EVERY EDGE THAT CROSSES THE VIEWPORT IS DRAWN, WHICH IS NOT THE SAME THING AS every edge
     * with a VISIBLE ENDPOINT -- and it took two goes to get this note right. It first claimed the
     * two sets were the same; #334 measured that they are not, and this replaced that with an
     * accurate description of a defect that was still open. Both sets are now covered, by
     * {@link #sweepEdges} for the edges with a box on screen and by {@link #sweepTrunks} for the
     * ones with neither, and that pair is where the argument lives.
     */
    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        super.draw(context, widgetTheme);
        sweepEdges(PAINT);
    }

    /**
     * Every edge with a visible endpoint, as rectangles handed to `sink`.
     *
     * A SINK RATHER THAN A DRAW CALL, AND IT IS WHAT MAKES THE SWEEP TESTABLE AT ALL. `draw`
     * cannot run without a GL context, so before #330 the only thing that had ever looked at
     * these coordinates was a screenshot -- and `flow-nav2.png` shows the bug and was cited as a
     * pass. With a sink, `FlowCanvasTest` drives THIS method, at any scroll and zoom, and reads
     * the exact rectangles production would paint. Nothing about the sweep is mocked; only the
     * last hop, from a rectangle to the screen, is swapped.
     *
     * FROM WHAT IS VISIBLE, not over every box. Sweeping all of them to ask which edges to draw
     * is O(plan) per frame -- the same shape as the spatial hash grid this package already
     * rejected by measurement, and it would have undone the culling it sits next to.
     *
     * THE X ARGUMENT FOR THAT HOLDS AND THE Y ARGUMENT DOES NOT, AND THE NOTE THIS REPLACES
     * ASSERTED BOTH. An edge spans exactly one column gap, because the layout is columnar and a
     * parent is always one column left of its child -- so horizontally it cannot reach a viewport
     * neither of its ends is near. VERTICALLY IT CAN: {@link FlowLayout} centres a parent on the
     * band its whole SUBTREE spans, so a parent and one of its own children can be thousands of
     * pixels apart, and a viewport between them -- inside that child's band, below the parent's
     * row and above the child's -- holds neither box while the elbow's vertical run crosses it.
     * Measured: `deepFan(3,5)` at zoom 1 scrolled to (0,554) has NO box on screen and two trunks
     * that should cross it, and before #334 it drew nothing at all there.
     *
     * IT WAS USUALLY MASKED RATHER THAN ABSENT, which is why it took until #330's review to see:
     * every child of one parent shares the same corner x, so a visible sibling's trunk paints
     * the same pixels. Nothing guarantees a visible sibling.
     *
     * SO THIS IS TWO PASSES, AND WIDENING THE FIRST ONE COULD NOT HAVE DONE IT -- the visible set
     * is EMPTY in the measured case, so no walk starting from it reaches the edge at all. The
     * second pass is {@link #sweepTrunks} and it starts from the subtree bands instead; the
     * culling philosophy is intact and the bound is stated and tested there.
     *
     * WHAT IS DRAWN IS EXACTLY ONCE PER EDGE, which the walk this replaces was not, and the two
     * passes partition the edges rather than overlapping. An edge belongs to its CHILD, so a
     * child that is on screen draws it and the parent's side skips it; see the note in the loop
     * for what the second copy of a translucent line looked like. {@link #sweepTrunks} then takes
     * only edges with NEITHER box on screen, which is exactly what this pass cannot reach.
     */
    void sweepEdges(RectSink sink) {
        for (int i = 0; i < visibleBoxes.size(); i++) {
            int index = visibleBoxes.get(i);
            int parent = parentOf(index);
            if (parent >= 0) {
                edge(parent, index, sink);
            }
            for (int child : childrenOf[index]) {
                // ONCE PER EDGE, AND THE ALPHA IS WHY IT MATTERS. An edge belongs to its
                // CHILD -- the branch above draws it for every visible box -- so drawing it
                // again from the parent's side is not a harmless repeat: `EDGE` is 0x66FFFFFF
                // and `GuiDraw.drawRect` goes through `Platform.setupDrawColor`, which calls
                // `GlStateManager.enableBlend`, so the same rectangle painted twice composites
                // to about 64% opacity against 40% for one painted once. Before this the
                // brightness of a line said whether both its nodes happened to be culled in,
                // and it CHANGED as the far end scrolled into frame.
                if (!onScreen[child]) {
                    edge(index, child, sink);
                }
            }
        }
        sweepTrunks(sink);
    }

    /**
     * Layout pixels of slack the trunk pass allows itself around the viewport, on both axes.
     *
     * BECAUSE {@link FlowZoom#toScreen} ROUNDS AND {@link #viewLayoutX} FLOORS, AND ONE PIXEL IS
     * ENOUGH. A layout point maps to `round(y * zoom) - scroll`, so a point up to `0.5 / zoom`
     * ABOVE the viewport still lands on screen row 0; `FlowZoom.MIN` is 0.5, so that overshoot is
     * at most one layout pixel and this covers it at every zoom the canvas allows.
     *
     * INSURANCE AGAINST THAT ARITHMETIC, NOT A FIX FOR AN OBSERVED DROP, and it is worth being
     * exact about which. A replica of this layout and this conversion was swept over 420 viewport
     * positions across five zooms and four fixtures and found NO edge that the exact bound would
     * have missed -- so nothing here was measured going wrong. What is measured is that the
     * rounding makes the exact bound depend on the zoom, and a boundary that is correct at four
     * zooms and one pixel short at `FlowZoom.MIN` is the failure shape `FlowZoom`'s own header was
     * written about: right in every screenshot anybody would think to take.
     *
     * IT CAN ONLY ADD WORK, NEVER WRONG PIXELS. An edge admitted by the slack and genuinely off
     * canvas is clipped away by {@link #edgeSegments} and contributes no rectangle, so the cost of
     * being generous here is a few comparisons against a dropped line if the exact bound is ever
     * one pixel wrong. `FlowCanvasTest.theTrunkPassCostsABoundedMultipleOfWhatItPaints` charges
     * the slack to the budget rather than letting it hide there.
     */
    private static final int SLACK = 1;

    /**
     * Edges {@link #sweepTrunks} looked at, and how many of them it painted, this frame.
     *
     * THE HALF OF THE TRUNK PASS'S COST THAT `bandsExamined` DOES NOT SEE, and the omission was
     * real: the band walk is bounded by the viewport whatever the plan, but a band-overlapping
     * parent can have four thousand children and the per-parent binary search is the only thing
     * stopping the near-side half of them being walked in order to reject them. A counter on the
     * bands alone is satisfied by a pass that then does O(plan) work one level down, which is
     * exactly the shape being avoided.
     *
     * SO THE CLAIM IS `considered` MINUS `painted` IS SMALL, not that either is. Every child the
     * search hands back has an edge that crosses this viewport, so it is either painted or skipped
     * for being on screen -- and the on-screen ones are bounded by {@link #drawnLastFrame}.
     * `FlowCanvasTest.theWideFanIsNotWalkedToRejectIt` asserts that difference on `fan(200)`,
     * which is the wide-fan case the binary search exists for. NOT
     * `theTrunkPassCostsABoundedMultipleOfWhatItPaints`, which is the BAND budget and is satisfied
     * by a pass that then does O(plan) work one level down -- the two claims are separate tests
     * because a failure has to name the bound it broke.
     *
     * <h2>`painted` COUNTS EDGES THAT PRODUCED A RECTANGLE, NOT CALLS TO {@link #edge}, AND THE
     * DIFFERENCE IS WHY THE FIRST VERSION OF THIS COUNTER WAS USELESS</h2>
     *
     * MEASURED, BY MUTATION, AND THE MUTATION PASSED. With the per-parent binary search deleted
     * the pass considered all 200 children instead of 59 -- and it also HANDED all 188 off-screen
     * ones to `edge`, where `edgeSegments` clipped 141 of them away to nothing. Counting calls,
     * `painted` rose in lockstep with `considered` (200 <= 188 + 12) and the assertion held while
     * the pass did three times the work. Counting rectangles it does not: 200 against 47 + 12.
     *
     * A counter that grows with the waste it is supposed to detect measures nothing, and the only
     * reason this was found is that the mutation was actually run rather than reasoned about.
     */
    private int trunkEdgesConsidered;
    private int trunkEdgesPainted;

    /** Edges the trunk pass looked at this frame. See {@link #trunkEdgesConsidered}. */
    public int trunkEdgesConsidered() {
        return trunkEdgesConsidered;
    }

    /**
     * Edges the trunk pass put at least one rectangle on the canvas for this frame. NOT the
     * number of edges it looked at, and not the number it called {@link #edge} for. See
     * {@link #trunkEdgesConsidered}.
     */
    public int trunkEdgesPainted() {
        return trunkEdgesPainted;
    }

    /**
     * The edges that cross the viewport with NEITHER of their boxes on screen. #334.
     *
     * THE SECOND HALF OF THE SWEEP, AND THE FIRST HALF PROVABLY CANNOT REACH THESE.
     * {@link #sweepEdges} walks out from the culled-visible boxes, and on `deepFan(3,5)` at zoom 1
     * scrolled to (0,554) that set is EMPTY while two trunks cross the canvas -- so the reader
     * gets a blank panel where the line telling them the branch continues should be. That is
     * close to the symptom that opened #330 ("stuck on the background") from a different cause,
     * and it is an ordinary scroll position on a large plan rather than a corner case.
     *
     * <h2>STILL BOUNDED, AND THE BOUND IS THE WHOLE DESIGN CONSTRAINT</h2>
     *
     * THE CULLING PHILOSOPHY DOES NOT MOVE: no O(plan) sweep, and the work a frame does is
     * proportional to what it paints. Enumerating every box in the two columns the viewport
     * straddles WOULD have been O(plan) -- that is the shape {@link FlowCulling}'s header rejected
     * by measurement when it declined a spatial hash grid -- so this does not do that either. It
     * costs, per column gap the viewport straddles:
     *
     * <ul>
     * <li>one binary search over that column's bands, plus a walk of the bands that overlap the
     *     viewport. `FlowLayout.Box.bandTop` proves that walk is O(1) in the plan: bands at one
     *     depth are disjoint and a row pitch apart, so a viewport of height H overlaps at most
     *     `H / ROW_PITCH + 2` of them. At the production 372px canvas that is about a dozen.
     * <li>one binary search per such parent over its children, and then a walk of ONLY the
     *     children whose edge really does cross -- every one of which is a line this frame has to
     *     paint anyway. The binary search is what keeps a wide fan honest: a 200-child root whose
     *     band overlaps has half its children on the near side of the viewport, and walking those
     *     to reject them would put the plan's own breadth back into the frame.
     * </ul>
     *
     * `FlowCulling.bandsExamined` counts the first part and
     * `FlowCanvasTest.theTrunkPassCostsABoundedMultipleOfWhatItPaints` reads it, because "this is
     * cheap" is a claim a counter can settle and a screenshot cannot.
     *
     * <h2>WHY THE BANDS ARE THE RIGHT QUERY, and it is a proof rather than an intuition</h2>
     *
     * A box's band contains every row in its subtree, so for any child C of P the whole elbow's
     * y-range `[min(Py, Cy), max(Py, Cy)]` lies inside P's band. Contrapositive: if P's band
     * misses the viewport, NO edge out of P can cross it. And if an edge out of P does cross,
     * P's band overlaps and this walk finds P. Together with the x filter -- an edge spans
     * exactly one column gap, which is the half of the old comment's argument that was true --
     * the two passes cover every edge with a pixel on this canvas.
     *
     * ITS CHILDREN ARE ASCENDING IN Y, which is what makes the per-parent search possible and is
     * a property of {@link FlowLayout}'s depth-first walk rather than of a sort: children are
     * emitted in child order, sibling bands do not overlap, and {@link #childrenOf} is filled by
     * one ascending pass over the boxes. So the crossing children are a contiguous PREFIX or
     * SUFFIX of that list and the boundary is a binary search.
     *
     * <h2>The three cases, and the third is not dead code</h2>
     *
     * With the parent's centre line above the viewport, an edge crosses exactly when the child's
     * is at or below the viewport's top -- a suffix. Below the viewport, exactly when the child's
     * is above its bottom -- a prefix. INSIDE the viewport, every child's edge crosses, and that
     * case is reachable: the parent's own column can be off screen horizontally while the column
     * GAP is on screen, which is a viewport narrower than a column pitch. Production gives the
     * canvas 612px and a pitch is 249, so it takes a zoom past 2.4 or a smaller panel -- but a
     * filter that assumed it away would be an assumption about a caller rather than about the
     * geometry, and this class already has one comment about what that costs.
     */
    private void sweepTrunks(RectSink sink) {
        trunkEdgesConsidered = 0;
        trunkEdgesPainted = 0;
        if (viewLayoutWidth <= 0 || viewLayoutHeight <= 0) {
            return;
        }
        int pitch = FlowLayout.COLUMN_PITCH;
        // A gap `c` carries the edges from column `c` to column `c + 1`, and the elbow occupies
        // layout x in [c*pitch + NODE_WIDTH, (c+1)*pitch): the departure horizontal starts at the
        // parent's right border and the arrival one ends at the child's left. So the gaps that can
        // matter are the ones whose span meets the viewport, and there are at most as many as
        // there are visible columns.
        int firstGap = Math.max(0, Math.floorDiv(viewLayoutX - SLACK, pitch));
        int lastGap = Math.min(culling.columnCount() - 2, Math.floorDiv(
                viewLayoutX + viewLayoutWidth - 1 + SLACK - FlowLayout.NODE_WIDTH, pitch));
        int viewTop = viewLayoutY - SLACK;
        int viewBottom = viewLayoutY + viewLayoutHeight + SLACK;
        // THE BAND IS MEASURED TO A NODE'S TOP AND AN EDGE LEAVES ITS CENTRE, so the range asked
        // of the band index is the viewport shifted back by half a node. Inflating the band
        // instead would work and would be a fact about boxes stated in `FlowCulling`; the shift
        // is the same arithmetic on the side that owns the reason for it.
        int half = FlowLayout.NODE_HEIGHT / 2;
        for (int gap = firstGap; gap <= lastGap; gap++) {
            int at = culling.firstBandReaching(gap, viewTop - half);
            for (int length = culling.columnLength(gap); at < length; at++) {
                int parent = culling.boxInColumn(gap, at);
                FlowLayout.Box box = laid.boxes.get(parent);
                if (box.bandTop + half >= viewBottom) {
                    break;   // bands ascend, so every band after this one is below too
                }
                // ON SCREEN MEANS THE FIRST PASS ALREADY DREW EVERY EDGE OUT OF THIS BOX, from
                // one side or the other, so taking it again would be the doubled translucent
                // line `sweepEdges` draws each edge exactly once to avoid.
                if (onScreen[parent]) {
                    continue;
                }
                emitCrossingChildren(parent, box.y + half, viewTop, viewBottom, sink);
            }
        }
    }

    /**
     * The edges from `parent` whose vertical run crosses `[viewTop, viewBottom)` and whose child
     * is off screen, as rectangles handed to `sink`. `parentCentre` is the parent's centre line.
     *
     * See {@link #sweepTrunks} for why the answer is a prefix or a suffix of {@link #childrenOf}
     * and why finding the boundary rather than testing every child is what keeps the frame bounded.
     */
    private void emitCrossingChildren(int parent, int parentCentre, int viewTop, int viewBottom,
                                      RectSink sink) {
        int[] children = childrenOf[parent];
        int from = 0;
        int to = children.length;
        if (parentCentre < viewTop) {
            from = firstChildCentreAtOrBelow(children, viewTop);
        } else if (parentCentre >= viewBottom) {
            to = firstChildCentreAtOrBelow(children, viewBottom);
        }
        for (int i = from; i < to; i++) {
            int child = children[i];
            trunkEdgesConsidered++;
            // THE OTHER HALF OF THE PARTITION. A visible child draws this edge from its own side
            // in the first pass; only an edge with neither box on screen belongs to this one.
            if (!onScreen[child] && edge(parent, child, sink) > 0) {
                trunkEdgesPainted++;
            }
        }
    }

    /**
     * The offset in `children` of the first one whose centre line is at or below `y`, or
     * `children.length` when every one of them is above it.
     *
     * ASCENDING BY CONSTRUCTION, NOT BY A SORT -- {@link #sweepTrunks} has the argument. A
     * mis-ordered list here would return a boundary in the middle of the crossing run and drop
     * trunks silently, which is #334 again with no symptom, so {@link FlowCulling}'s constructor
     * checks the band ordering the same property rests on.
     */
    private int firstChildCentreAtOrBelow(int[] children, int y) {
        int low = 0;
        int high = children.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (laid.boxes.get(children[mid]).y + FlowLayout.NODE_HEIGHT / 2 < y) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * One edge, through the same {@link #edgeSegments} call the harness and the tests make.
     *
     * ONE DEFINITION OF WHERE AN EDGE GOES rather than a drawn one and an asserted one. The #191
     * shape this package has already paid for twice is a seam that is correct and unused; a
     * second copy of this arithmetic here would be the version that is correct, used, and
     * disagrees with what any test could see.
     */
    private int edge(int parentIndex, int childIndex, RectSink sink) {
        edgeSegments(parentIndex, childIndex, segments);
        for (int i = 0; i + 3 < segments.size(); i += 4) {
            sink.rect(segments.get(i), segments.get(i + 1), segments.get(i + 2),
                      segments.get(i + 3));
        }
        // HOW MANY RECTANGLES REACHED THE SINK, which is a different question from whether this
        // was called: `edgeSegments` clips, so an edge with nothing on the canvas goes through
        // here and paints nothing. {@link #trunkEdgesPainted} needs the second answer and
        // measuring it with the first is what let a mutation of the trunk pass go undetected.
        return segments.size() / 4;
    }

    /** Where {@link #sweepEdges} puts its rectangles. Screen pixels relative to this canvas. */
    interface RectSink {
        void rect(int x, int y, int width, int height);
    }

    /**
     * The production sink, and THE ONLY PLACE IN THIS PACKAGE THAT DRAWS AN EDGE.
     *
     * DELIBERATELY THE ONLY ONE, AND `FlowCanvasTest` ASSERTS THAT. A `GuiDraw` call anywhere
     * else in the sweep is a coordinate no headless test can read, which is the state #330 was
     * found in: the arithmetic was wrong for three releases and every instrument pointed at it
     * was a photograph. Keeping the draw in one leaf means the mutation that reintroduces the
     * bug -- painting from `draw` in layout coordinates -- moves a `GuiDraw.drawRect` reference
     * into `FlowCanvas` itself and fails a test.
     *
     * A CONSTANT, so the sweep allocates nothing per frame. See {@link #segments}.
     */
    private static final RectSink PAINT = new RectSink() {
        @Override
        public void rect(int x, int y, int width, int height) {
            com.cleanroommc.modularui.drawable.GuiDraw.drawRect(x, y, width, height, EDGE);
        }
    };

    /**
     * The rectangles one parent-to-child edge is drawn as, in SCREEN pixels relative to this
     * canvas, appended to `out` as {x, y, width, height} quads. Cleared first.
     *
     * <h2>SCREEN AND NOT LAYOUT, AND THE COMMENT HERE USED TO SAY THE OPPOSITE</h2>
     *
     * The method this replaces was `line(int, int, int, int)` and its javadoc read "a one-pixel
     * line in layout coordinates; the viewport transform is already applied." The second half was
     * false and it is the whole of #330. Measured in the ModularUI 3.1.5 bytecode,
     * `InternalWidgetTree.drawTree` calls `IWidget.draw` at offset 135 and does not reach
     * `pushViewport` / `IViewport.transformChildren` until 188 and 194 -- so by the time this
     * canvas paints, the stack carries the widget's own position (`IWidget.transform`, a
     * translate by `area.rx, area.ry`) and NOTHING ELSE. The scroll translate and the zoom scale
     * arrive afterwards and apply to the CHILDREN, which is where the nodes are.
     *
     * At scroll 0 and zoom 1 the two spaces are the same numbers, which is why every prodshot
     * passed: `flow-nav2.png` shows orphaned edge fragments and its verdict asserted scroll
     * offsets and node names. Jake found it in the pack instead -- "it is stuck on the background
     * instead of connecting the nodes and scrolling as i scroll."
     *
     * DO NOT "SIMPLIFY" THIS BACK TO LAYOUT COORDINATES BY PUTTING A `GlStateManager.scale` OR A
     * `stack` CALL AROUND THE SWEEP. That is the trap this class's header already names one level
     * over: the viewport stack is the hook `getWidgetsAt` walks, so a transform pushed by hand
     * for drawing only would look identical on screen and leave hit-testing reading a different
     * space. The conversion is {@link FlowZoom#toScreen}, it is the same one
     * {@link #boxRectOnScreen} and {@link #parkCursorOverBox} use, and `flow-hit` measures its
     * inverse against ModularUI's own answer every run.
     *
     * <h2>Clipped here, because nothing else clips this call</h2>
     *
     * `AbstractScrollWidget.preDraw` is what keeps the children inside the viewport -- it applies
     * a `Stencil` to the scroll area -- and it runs at drawTree offset 171, AFTER the draw at 135.
     * `ModularPanel` adds no stencil of its own (it overrides neither `preDraw` nor
     * `transformChildren`, checked in the same jar). So an unclipped edge is painted over the
     * panel's heading and warning lines, and off the panel entirely: an elbow's vertical segment
     * spans the gap between two rows that can be hundreds of pixels outside the canvas. The
     * untransformed version could not show that, because its coordinates were wrong anyway.
     *
     * Every segment is axis-aligned, so the clip is a min and a max per axis rather than a
     * Cohen-Sutherland; a segment with nothing inside the canvas is DROPPED rather than squashed
     * against the border, which is also several hundred fewer draw calls on a wide fan.
     *
     * <h2>The elbow's corner is a LAYOUT midpoint</h2>
     *
     * Converted, not averaged -- and NOT because averaging would disagree between siblings. It
     * would not, and the first version of this note claimed it would: `fromX` and `toX` depend
     * only on the two COLUMNS, so every elbow crossing a given column gap has the same two ends
     * however the corner is derived, whichever sibling it runs to.
     *
     * THE REASON IS THAT AVERAGING RECOVERS A LAYOUT FACT FROM TWO ALREADY-ROUNDED NUMBERS.
     * `(round(a * z) + round(b * z)) / 2` can sit a pixel off the true centre of the gap, and
     * unlike the two ENDS -- which are pinned to a node border, so a probe can say when they are
     * wrong -- nothing pins the corner and nothing would report that pixel. The midpoint of the
     * column gap is a fact about the layout, so it is stated in layout units and converted once.
     *
     * <h2>TWO RECTANGLES OR THREE, AND NO TWO OF THEM EVER OVERLAP</h2>
     *
     * THE COUNT IS NOT FIXED, and a caller that indexes the arrival quad as `out.get(8)` is
     * wrong on a same-row edge. Take the LAST quad: the elbow's runs are appended in order and
     * the arrival horizontal is always the final one. #335 is what the fixed index cost.
     *
     * `EDGE` IS TRANSLUCENT, SO AN OVERLAP IS VISIBLE AND THIS USED TO HAVE TWO. `GuiDraw.drawRect`
     * goes through `Platform.setupDrawColor`, which calls `GlStateManager.enableBlend`, so a
     * pixel painted twice composites to about 64% opacity against 40% for one painted once --
     * the same arithmetic {@link #sweepEdges} draws each edge exactly once for. Both overlaps
     * were at the corner and both are gone:
     *
     * <ul>
     * <li>A SAME-ROW EDGE emitted a 1x1 vertical run at the corner that was a strict subset of
     *     the arrival horizontal, so every straight edge had a brighter dot at the midpoint of
     *     its column gap. Those are not exotic: a parent is centred on its subtree band, so the
     *     middle child of any odd fan and every step of a single-child chain is a straight edge,
     *     and a recipe tree is mostly chains.
     * <li>A CHILD ABOVE ITS PARENT doubled the corner pixel, because the vertical run covered
     *     `[toY, fromY)` -- which includes `toY`, where the arrival horizontal starts. A child
     *     BELOW covered `[fromY, toY)` and did not. So the artifact was asymmetric with
     *     direction, which is the kind of thing a screenshot comparison never explains.
     * </ul>
     *
     * THE RULE THAT REPLACES BOTH: THE VERTICAL RUN OWNS THE DEPARTURE ROW AND YIELDS THE
     * ARRIVAL ROW. It always covers `fromY` and never covers `toY`, whichever way the elbow
     * goes, so it meets the departure horizontal (which ends one pixel left of the corner) and
     * stops one row short of the arrival horizontal (which starts at the corner). On a same-row
     * edge that leaves it empty and the two horizontals abut. That is one statement rather than
     * a case per direction, and `FlowCanvasTest.noTwoRectanglesOfOneEdgeOverlapAtAnyZoom` reads
     * it back off the rectangles at every zoom.
     */
    public void edgeSegments(int parentIndex, int childIndex, IntArray out) {
        out.clear();
        FlowLayout.Box parent = laid.boxes.get(parentIndex);
        FlowLayout.Box child = laid.boxes.get(childIndex);
        int fromX = toScreenX(parent.right());
        int fromY = toScreenY(parent.y + FlowLayout.NODE_HEIGHT / 2.0);
        int toX = toScreenX(child.x);
        int toY = toScreenY(child.y + FlowLayout.NODE_HEIGHT / 2.0);
        int mid = toScreenX((parent.right() + child.x) / 2.0);
        // An elbow rather than a diagonal: a straight line between two rows crosses the nodes
        // between them at any real fan-out, and this pack fans out a lot.
        segment(fromX, fromY, mid, fromY, out);
        verticalRun(mid, fromY, toY, out);
        segment(mid, toY, toX, toY, out);
    }

    /**
     * The elbow's vertical run at screen column `x`, from the departure row TOWARDS the arrival
     * row and stopping one row short of it. Nothing is appended when the two rows are the same.
     *
     * NOT {@link #segment}, AND THE DIFFERENCE IS THE WHOLE OF #335. That method spans two points
     * inclusively and floors a zero-length span to one pixel, which is right for a horizontal run
     * -- at `FlowZoom.MIN` a short one rounds to no length and would vanish -- and wrong for this
     * one, where a zero-length span means there is nothing to draw and the one-pixel floor put a
     * rectangle inside the arrival horizontal. See {@link #edgeSegments} for the rule and for what
     * a doubled translucent pixel looks like.
     *
     * `min(fromY, toY + 1)` IS THE HALF-OPEN RANGE IN BOTH DIRECTIONS and it is worth checking
     * rather than trusting: with the child below, `toY > fromY` so this is `fromY` and the run
     * covers `fromY .. toY - 1`; with the child above, `toY < fromY` so it is `toY + 1` and the run
     * covers `toY + 1 .. fromY`. Either way `fromY` is in and `toY` is out.
     */
    private void verticalRun(int x, int fromY, int toY, IntArray out) {
        quad(x, Math.min(fromY, toY + 1), 1, Math.abs(toY - fromY), out);
    }

    /** The parent box of `index`, or -1 when it is a root. */
    public int parentOf(int index) {
        return laid.boxes.get(index).parent;
    }

    /**
     * Was box `index` on screen at the last {@link #updateVisible}?
     *
     * THE CULLER'S ANSWER AS THE SWEEP SEES IT, which is a different question from
     * {@link #fullyShowing}: this one is "would an edge to it be drawn", that one is "is all of
     * it in frame". A probe that conflated them would look for edges to boxes half off the
     * canvas and read the clip as a missing line.
     *
     * PACKAGE-PRIVATE, like {@link #updateVisible} and {@link #sweepEdges}, because the only
     * caller outside this class is `FlowCanvasTest` and it is in this package. A public method
     * with no production caller is the seam-nobody-uses shape #191 already cost this repository
     * twice; the sweep itself reads {@link #onScreen} directly.
     */
    boolean isVisible(int index) {
        return onScreen[index];
    }

    /**
     * One one-pixel segment between two SCREEN points, clipped to this canvas and appended to
     * `out` as an {x, y, width, height} quad. Nothing is appended when none of it is visible.
     *
     * See {@link #edgeSegments} for what space these coordinates are in and why the clip lives
     * here. A ONE-PIXEL MINIMUM ON BOTH AXES: a horizontal segment has no height of its own, and
     * at zoom 0.5 a segment shorter than two layout pixels rounds to no length either, so
     * without this an edge would be a rectangle of zero area and simply not appear.
     */
    private void segment(int x1, int y1, int x2, int y2, IntArray out) {
        quad(Math.min(x1, x2), Math.min(y1, y2),
             Math.max(1, Math.abs(x2 - x1)), Math.max(1, Math.abs(y2 - y1)), out);
    }

    /**
     * One rectangle in SCREEN pixels, clipped to this canvas and appended to `out` as an
     * {x, y, width, height} quad. Nothing is appended when none of it is visible.
     *
     * THE ONE PLACE THE CLIP HAPPENS, and it is split out of {@link #segment} rather than
     * duplicated because {@link #verticalRun} needs the clip without that method's one-pixel
     * floor. Two copies of a clip is the shape where an edge is dropped on one axis and not the
     * other and no test says which copy was wrong; see {@link #edgeSegments} on why the floor is
     * right for a horizontal run and wrong for a vertical one.
     */
    private void quad(int left, int top, int width, int height, IntArray out) {
        int visibleWidth = clippedLength(left, width, getArea().width);
        int visibleHeight = clippedLength(top, height, getArea().height);
        // A ZERO-SIZED INPUT IS ALSO CAUGHT HERE, which is what `verticalRun` relies on for a
        // same-row edge: `clippedLength(top, 0, limit)` is at most zero for any `top`, so the
        // run drops out on the same test an off-canvas one does.
        if (width <= 0 || height <= 0 || visibleWidth <= 0 || visibleHeight <= 0) {
            return;
        }
        out.add(Math.max(0, left));
        out.add(Math.max(0, top));
        out.add(visibleWidth);
        out.add(visibleHeight);
    }

    /**
     * How much of the span [`from`, `from + length`) survives being clipped into [0, `limit`).
     *
     * ZERO OR NEGATIVE MEANS NOTHING IS VISIBLE, and both signs are reachable: a span wholly left
     * of the canvas gives `min(limit, from + length) - 0`, which is negative, and one wholly right
     * of it gives `limit - from`, which is also negative. Callers test `<= 0` rather than `== 0`
     * for that reason -- an `== 0` test would draw a full-width rectangle for every edge whose
     * far end is off the left of the canvas, which on a scrolled diagram is most of them.
     */
    static int clippedLength(int from, int length, int limit) {
        return Math.min(limit, from + length) - Math.max(0, from);
    }

    /**
     * The reused buffer {@link #edge} reads its rectangles out of.
     *
     * A FIELD BECAUSE THIS IS THE 60 FPS PATH. Three quads per edge and several hundred edges a
     * frame is a few thousand ints; allocating a fresh array for each would put the diagram's
     * pan gesture -- the one thing this package has a frame-time gate on -- into the allocator.
     * Same argument {@link #visibleBoxes} makes about boxed Integers, one call deeper.
     *
     * CLIENT THREAD ONLY, like {@link #anchorMouseX} and for the same reason: nothing else draws.
     */
    private final IntArray segments = new IntArray(12);
}
