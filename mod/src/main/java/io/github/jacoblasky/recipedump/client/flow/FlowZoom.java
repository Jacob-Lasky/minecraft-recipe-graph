package io.github.jacoblasky.recipedump.client.flow;

/**
 * The arithmetic that relates screen pixels to layout pixels when the diagram is zoomed.
 *
 * PURE, like {@link FlowLayout} and {@link FlowCulling}, and separate from {@link FlowCanvas}
 * for the same reason: the canvas cannot be tested without a window, and this is the part of
 * zoom most likely to be subtly wrong. Rounding a viewport inward instead of outward hides a
 * strip of nodes along two edges, which looks like a culling bug and only appears at
 * non-integer zooms.
 *
 * SCROLL OFFSETS ARE SCREEN PIXELS, NOT LAYOUT PIXELS, and that is a decision rather than an
 * accident. ModularUI's `ScrollData` clamps the offset against `scrollSize` minus the widget
 * area, and it has no notion of a scale; if the offset were in layout units its clamp would
 * let the view run off the end of a zoomed-out diagram by a factor of the zoom. Scaling the
 * scroll SIZE instead keeps ModularUI's own clamping correct for free -- which is the same
 * argument {@link FlowCanvas} already makes for not hand-rolling the pan.
 *
 * So: the scroll size is {@link #scaledExtent}, the offset that comes back is screen pixels,
 * and {@link #layoutOrigin} / {@link #layoutExtent} convert it back for the culler.
 *
 * {@link #toScreen} IS THE FORWARD DIRECTION AND IT IS NOT OPTIONAL FOR ANYTHING THE CANVAS
 * PAINTS ITSELF. Only the canvas's CHILDREN get the scroll translate and the zoom scale; a
 * `draw` on the canvas runs under neither, so anything drawn there in layout coordinates sits
 * still while the diagram moves. That was #330 -- every edge detached from its nodes on the
 * first scroll -- and the two spaces coincide at scroll 0 and zoom 1, which is where every
 * screenshot had been taken.
 */
public final class FlowZoom {

    /**
     * Zoom limits.
     *
     * THE FLOOR IS SET BY LEGIBILITY, NOT BY ARITHMETIC. Minecraft's font is a bitmap; at 0.5
     * a node's label is drawn at half scale and is genuinely unreadable rather than merely
     * small, and a diagram you cannot read is not a smaller diagram. 0.5 is the point where a
     * node is still identifiable by its badge colour and quantity, which is what someone
     * zoomed out is actually reading.
     *
     * The ceiling is 2.0 because past it a single node fills the panel and the diagram stops
     * being a diagram. Neither is a hard technical limit; both are the range in which the
     * thing is useful.
     */
    public static final float MIN = 0.5f;
    public static final float MAX = 2.0f;
    /** Unzoomed. The value {@link FlowCanvas} starts at. */
    public static final float DEFAULT = 1.0f;

    /**
     * The zoom at which a node's name is still worth drawing.
     *
     * THE SAME ARGUMENT {@link #MIN} ALREADY MAKES, taken to its conclusion. Minecraft's font
     * is a bitmap: a 6px glyph at 0.5 is three physical pixels and is not a small name, it is
     * a smear, and MIN's own note says what a reader zoomed out is really reading -- the badge
     * colour and the quantity. So below this the diagram drops the label and the badge word
     * and keeps the icon and the coloured quantity. `FlowCanvas.preDraw` applies it.
     *
     * 0.75 is 4.5 physical pixels per glyph, which is the last step where a familiar item name
     * is guessable. Not a technical limit: nothing breaks at 0.6, it simply says nothing.
     */
    public static final float LABEL_LEGIBLE = 0.75f;

    private FlowZoom() {
    }

    /** `zoom` brought inside {@link #MIN}..{@link #MAX}. NaN becomes {@link #DEFAULT}. */
    public static float clamp(float zoom) {
        // NaN FIRST, because `Math.max(MIN, Math.min(MAX, NaN))` is NaN and NaN silently
        // poisons every division below into infinity -- at which point the culler is handed a
        // viewport of width 0 and the diagram goes blank with nothing logged.
        if (Float.isNaN(zoom)) {
            return DEFAULT;
        }
        return Math.max(MIN, Math.min(MAX, zoom));
    }

    /**
     * How large the layout is in screen pixels, for the scrollbar to size itself against.
     *
     * ROUNDED UP. A scroll size one pixel short of the content leaves the last column
     * permanently unreachable, and "the diagram will not scroll all the way right" is a
     * complaint nobody connects to a rounding mode.
     */
    public static int scaledExtent(int layoutExtent, float zoom) {
        return (int) Math.ceil(layoutExtent * (double) clamp(zoom));
    }

    /**
     * Where a layout coordinate lands on screen, relative to the canvas, at this zoom and offset.
     *
     * THE ARITHMETIC MODULARUI ITSELF APPLIES TO THE NODE WIDGETS, and it is written out here so
     * that the canvas's own painting can reach the same space its children land in. Measured in
     * the 3.1.5 bytecode: `AbstractScrollWidget.transformChildren` calls
     * `stack.translate(-scrollX, -scrollY)` and {@link FlowCanvas#transformChildren} then calls
     * `stack.scale(zoom, zoom)`. `GuiViewportStack` implements both with JOML's
     * `Matrix4f.translate` / `Matrix4f.scale`, which POST-multiply -- so the composed map is
     * `M * T(-scroll) * S(zoom)` and a child's point comes out at `layout * zoom - scroll`, with
     * the scale INSIDE the translate.
     *
     * THE OTHER ORDER IS THE TRAP, because it agrees everywhere the mistake is cheap.
     * `zoom * (layout - scroll)` is identical at zoom 1 and differs by `scroll * (zoom - 1)`
     * elsewhere -- correct in every unzoomed screenshot, wrong by 200 pixels at zoom 2 with the
     * view a screen along. Do NOT reorder these two operations without re-reading
     * `transformChildren`; the scroll offset is screen pixels precisely because it is applied
     * OUTSIDE the scale (see this class's own header).
     *
     * ROUNDED, WHICH IS NOT WHAT {@link #layoutOrigin} DOES, and the difference is what each is
     * for. That pair maps a screen RECTANGLE onto every layout pixel it touches, so it rounds
     * outward on purpose and covering is the property it wants. This maps a POINT to the pixel
     * it sits nearest: flooring would bias every edge endpoint up and to the left of the node it
     * is supposed to touch, by up to a pixel, in the same direction at both ends.
     *
     * A `double` COORDINATE BECAUSE HALF A NODE IS NOT AN INTEGER. `parkCursorOverBox` and the
     * edge sweep both want the centre of a 26px-high box, and rounding that to a layout pixel
     * before scaling it throws away half a pixel that then shows up as an edge meeting a node
     * off-centre at zoom 2.
     */
    public static int toScreen(double layoutCoordinate, float zoom, int scrollOffset) {
        return (int) Math.round(layoutCoordinate * clamp(zoom)) - scrollOffset;
    }

    /**
     * The layout coordinate at a screen scroll offset.
     *
     * FLOORED, so the viewport starts at or before the first visible layout pixel rather than
     * after it. See {@link #layoutExtent} for why the pair has to round in opposite
     * directions.
     */
    public static int layoutOrigin(int screenOffset, float zoom) {
        return (int) Math.floor(screenOffset / (double) clamp(zoom));
    }

    /**
     * How much layout a screen-sized viewport covers, given where it starts.
     *
     * IT TAKES THE OFFSET AS WELL AS THE SIZE, and that is not redundant. The extent depends
     * on where the viewport starts, because flooring the origin already moved the left edge
     * outward by up to a pixel of layout and the width has to absorb that as well as its own
     * rounding. Computing `ceil(screenSize / zoom)` alone is short by one whenever the origin
     * was not already on a layout pixel boundary, and the symptom is a column of nodes that
     * blinks out along the right edge at some zooms and not others.
     *
     * Derived from the two edges instead: ceil the right edge, floor the left, take the
     * difference. Then the layout rectangle always covers every layout pixel the screen
     * rectangle touches.
     */
    public static int layoutExtent(int screenOffset, int screenSize, float zoom) {
        double scale = clamp(zoom);
        int left = (int) Math.floor(screenOffset / scale);
        int right = (int) Math.ceil((screenOffset + (double) screenSize) / scale);
        return Math.max(0, right - left);
    }

    /**
     * The scroll offset that keeps the centre of the viewport over the same layout point when
     * the zoom changes.
     *
     * ANCHORED ON THE CENTRE, NOT THE ORIGIN, and this is the whole difference between zoom
     * that feels usable and zoom that feels broken. Scaling about (0,0) drags the diagram out
     * from under the pointer: zoom in on the node you are reading and it leaves the panel, so
     * you pan it back, every time. Nobody reports that as a bug, they just stop using zoom.
     *
     * HERE RATHER THAN IN {@link FlowCanvas}, because it is the only arithmetic in `setZoom`
     * and the rest of that method is ModularUI calls that cannot run without a window. Left
     * inline it would be the one part of zoom with no test, which is the part most worth
     * having one.
     *
     * The result is NOT clamped to the scroll range; `ScrollData` does that, and doing it
     * twice would mean two definitions of how far the diagram can scroll.
     */
    public static int reanchor(int screenOffset, int viewportSize, float from, float to) {
        double before = clamp(from);
        double after = clamp(to);
        double layoutCentre = (screenOffset + viewportSize / 2.0) / before;
        return (int) Math.round(layoutCentre * after - viewportSize / 2.0);
    }
}
