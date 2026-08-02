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
        getScrollArea().getScrollX().setScrollSize(laid.width);
        getScrollArea().getScrollY().setScrollSize(laid.height);
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
        getScrollArea().getScrollX().scrollTo(getScrollArea(),
                wrap(x, laid.width, getArea().width));
        getScrollArea().getScrollY().scrollTo(getScrollArea(),
                wrap(y, laid.height, getArea().height));
    }

    /** Wrap rather than clamp, so a long timing run keeps moving instead of parking. */
    private static int wrap(int value, int extent, int viewport) {
        int range = Math.max(1, extent - viewport);
        return Math.abs(value % range);
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
        IntArray shown = culling.visibleIn(getScrollX(), getScrollY(),
                getArea().width, getArea().height);
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
