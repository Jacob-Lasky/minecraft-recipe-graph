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
public class FlowCanvas extends AbstractScrollWidget<IWidget, FlowCanvas> {

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

    public FlowCanvas(PlanNode tree) {
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
            PlannerWidgets.NodeContent widget = PlannerWidgets
                    .planNodeContent(box.node, FlowLayout.NODE_WIDTH, FlowLayout.NODE_HEIGHT);
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
     * Move the viewport, clamped to the layout, without a mouse.
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
     * The box index ModularUI thinks is under the mouse, or -1.
     *
     * Asks the widgets themselves rather than walking a viewport stack, because
     * `IWidget.isHovering()` is already the answer `getWidgetsAt` produced this frame -- so
     * this reads the framework's real verdict rather than a re-derivation of it.
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
        // CLAMPED, AND EXPLICITLY NOT THROUGH `panTo`, WHICH WRAPS. This was the first version
        // and it produced a screenshot that passed the drawn check and was still unreadable.
        // Centring on the ROOT asks for a negative offset -- the root sits at x=0 and half a
        // viewport to its left is off the layout -- and `wrap`'s `Math.abs(value % range)`
        // turns -202 into +202, which pans AWAY from the box by almost its own width. What
        // landed was a two-pixel sliver of one node against the left edge, on a panel that
        // still read as empty.
        //
        // `wrap` is right for `panTo`, whose caller is a sweep that must keep moving rather
        // than park at an edge, and its comment says so. It is wrong for aiming, where running
        // off the end means "as far as it goes", not "reappear on the other side". DO NOT
        // route this back through `panTo` to save four lines.
        int x = (int) Math.round((box.x + FlowLayout.NODE_WIDTH / 2.0) * zoom)
                - getArea().width / 2;
        int y = (int) Math.round((box.y + FlowLayout.NODE_HEIGHT / 2.0) * zoom)
                - getArea().height / 2;
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
        FlowLayout.Box box = laid.boxes.get(index);
        int left = (int) Math.round(box.x * zoom) - getScrollX();
        int top = (int) Math.round(box.y * zoom) - getScrollY();
        int right = (int) Math.round(box.right() * zoom) - getScrollX();
        int bottom = (int) Math.round(box.bottom() * zoom) - getScrollY();
        return left >= 0 && top >= 0
                && right <= getArea().width && bottom <= getArea().height;
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
        int screenX = (int) Math.round((box.x + FlowLayout.NODE_WIDTH / 2.0) * zoom)
                - getScrollX();
        int screenY = (int) Math.round((box.y + FlowLayout.NODE_HEIGHT / 2.0) * zoom)
                - getScrollY();
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

    /** Direct children per box, so an edge sweep can start from what is visible. */
    private final int[][] childrenOf;

    /** How many were drawn last frame. The culling claim, readable from a test or a log. */
    public int drawnLastFrame() {
        return visible.size();
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
        // EVERY VISIBLE NODE EVERY FRAME, not only the ones that just entered the viewport.
        // Zoom changes without the visible set changing at all -- a control-scroll on a small
        // plan moves no node in or out -- so detail applied only on entry would leave the
        // labels showing at 0.5 until the next pan. It is a boolean write per drawn node.
        boolean detail = zoom >= FlowZoom.LABEL_LEGIBLE;
        // CONVERTED, because the scroll offset is screen pixels and the culler works in layout
        // pixels. `FlowZoom` rounds the two edges outward; passing `getScrollX() / zoom` and
        // `width / zoom` directly rounds both inward and hides a strip of nodes along the
        // right and bottom edges at any zoom that is not a whole number.
        IntArray shown = culling.visibleIn(
                FlowZoom.layoutOrigin(getScrollX(), zoom),
                FlowZoom.layoutOrigin(getScrollY(), zoom),
                FlowZoom.layoutExtent(getScrollX(), getArea().width, zoom),
                FlowZoom.layoutExtent(getScrollY(), getArea().height, zoom));
        for (IWidget widget : visible) {
            widget.setEnabled(false);
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
     * Only edges with a VISIBLE endpoint are drawn. A line between two off-screen nodes cannot
     * cross the viewport, because the layout is columnar and monotonic in x -- a parent is
     * always exactly one column left of its child, so the segment spans one column gap and
     * nothing more.
     */
    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        super.draw(context, widgetTheme);
        // FROM WHAT IS VISIBLE, not over every box. Sweeping all of them to ask which edges
        // to draw is O(plan) per frame -- the same shape as the spatial hash grid this
        // package already rejected by measurement, and it would have undone the culling it
        // sits next to. An edge spans exactly one column gap, because the layout is columnar
        // and a parent is always one column left of its child, so an edge can only cross the
        // viewport if one of its two ends is inside it: walking each visible box's parent AND
        // its children finds every such edge and no others.
        for (int i = 0; i < visibleBoxes.size(); i++) {
            int index = visibleBoxes.get(i);
            FlowLayout.Box box = laid.boxes.get(index);
            if (box.parent >= 0) {
                edge(laid.boxes.get(box.parent), box);
            }
            for (int child : childrenOf[index]) {
                edge(box, laid.boxes.get(child));
            }
        }
    }

    /** An elbow from a parent's right edge to a child's left edge. */
    private void edge(FlowLayout.Box parent, FlowLayout.Box child) {
        int fromX = parent.right();
        int fromY = parent.y + FlowLayout.NODE_HEIGHT / 2;
        int toX = child.x;
        int toY = child.y + FlowLayout.NODE_HEIGHT / 2;
        // An elbow rather than a diagonal: a straight line between two rows crosses the nodes
        // between them at any real fan-out, and this pack fans out a lot.
        int mid = (fromX + toX) / 2;
        line(fromX, fromY, mid, fromY);
        line(mid, Math.min(fromY, toY), mid, Math.max(fromY, toY));
        line(mid, toY, toX, toY);
    }

    /** A one-pixel line in layout coordinates; the viewport transform is already applied. */
    private void line(int x1, int y1, int x2, int y2) {
        int left = Math.min(x1, x2);
        int top = Math.min(y1, y2);
        int width = Math.max(1, Math.abs(x2 - x1));
        int height = Math.max(1, Math.abs(y2 - y1));
        com.cleanroommc.modularui.drawable.GuiDraw.drawRect(left, top, width, height, EDGE);
    }
}
