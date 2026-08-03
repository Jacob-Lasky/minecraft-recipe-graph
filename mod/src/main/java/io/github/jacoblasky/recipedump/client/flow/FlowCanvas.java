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
     * The narrowest node that carries everything the row can draw.
     *
     * DERIVED FROM THE ROW'S OWN COLUMNS, so it follows when the status vocabulary grows and
     * widens `BADGE`. `FlowLayout.NODE_WIDTH` must equal this and a test says so; the layout
     * writes the number out instead of importing it, because importing `PlannerWidgets` would
     * drag ModularUI into a file whose value is being testable without it.
     */
    public static final int NARROWEST_NODE = PlannerWidgets.ICON + PlannerWidgets.GAP
            + PlannerWidgets.QTY + PlannerWidgets.GAP + PlannerWidgets.MIN_LABEL
            + PlannerWidgets.GAP + PlannerWidgets.BADGE;

    /** Edge colour, muted so the nodes stay the thing you read. */
    private static final int EDGE = 0x66FFFFFF;

    private final FlowLayout.Laid laid;
    private final FlowCulling culling;
    /** One widget per box, built once and positioned absolutely. Parallel to `laid.boxes`. */
    private final List<IWidget> boxWidgets;
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
        this.boxWidgets = new ArrayList<IWidget>(laid.size());
        for (FlowLayout.Box box : laid.boxes) {
            // A BACKGROUND, unlike the tree's rows. In a list a row is bounded by the rows
            // above and below it; on a canvas it is text floating on a panel, and the first
            // screenshot of this showed exactly that -- three nodes and an elbow that read as
            // unrelated captions. The box is what makes it a graph.
            //
            // `MC_BACKGROUND` AND NOT `MC_BUTTON`, which was the second screenshot's lesson.
            // The row colours its label with `NodeStatus.INK_MUTED`, chosen against the light
            // panel the tree sits on; on the button's dark face the quantity and badge stayed
            // legible and the item NAME disappeared entirely -- a diagram of boxes that will
            // not tell you what is in them. The node keeps the panel's own surface and is
            // delimited by the nine-slice's border instead.
            IWidget widget = PlannerWidgets
                    .planNodeContent(box.node, FlowLayout.NODE_WIDTH, FlowLayout.NODE_HEIGHT)
                    .background(GuiTextures.MC_BACKGROUND)
                    .pos(box.x, box.y)
                    .size(FlowLayout.NODE_WIDTH, FlowLayout.NODE_HEIGHT);
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
     */
    @Override
    public void preDraw(ModularGuiContext context, boolean transformed) {
        super.preDraw(context, transformed);
        if (transformed) {
            return;
        }
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
            IWidget widget = boxWidgets.get(index);
            widget.setEnabled(true);
            visible.add(widget);
            visibleBoxes.add(index);
        }
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
