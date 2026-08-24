package io.github.jacoblasky.recipedump.client.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import io.github.jacoblasky.recipedump.graph.IntArray;

import org.junit.Test;

/**
 * The screen-to-layout arithmetic zoom needs.
 *
 * THE CASES ARE GENERATED, NOT CHOSEN, for the reason `FlowCullingTest` sets out at length:
 * rounding bugs live at the offsets nobody writes down, and the offsets I would write down are
 * the ones I had in mind while writing the rounding. Every assertion below that could be a
 * spot check is a sweep instead.
 */
public class FlowZoomTest {

    /** Zooms with an exact binary representation and ones without, since rounding is the point. */
    private static final float[] ZOOMS = {0.5f, 0.625f, 0.75f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f,
            1.3333334f, 1.5f, 1.75f, 2.0f};

    @Test
    public void everyPixelOnScreenIsInsideTheLayoutRectangleHandedToTheCuller() {
        // THE WHOLE POINT OF THE ROUNDING, and the assertion that fails if either edge rounds
        // the wrong way. If a screen pixel maps to a layout pixel outside the rectangle the
        // culler is given, whatever is drawn there is culled -- a strip of nodes that vanishes
        // along one edge at some zooms and not others, which reads as a culling bug rather
        // than as arithmetic.
        int screenSize = 612;
        for (float zoom : ZOOMS) {
            for (int offset = 0; offset < 4000; offset += 7) {
                int origin = FlowZoom.layoutOrigin(offset, zoom);
                int extent = FlowZoom.layoutExtent(offset, screenSize, zoom);
                for (int px = 0; px < screenSize; px++) {
                    int layout = (int) Math.floor((offset + px) / (double) zoom);
                    assertTrue("zoom " + zoom + " offset " + offset + " pixel " + px
                                    + " maps to layout " + layout + ", outside ["
                                    + origin + "," + (origin + extent) + ")",
                            layout >= origin && layout < origin + extent);
                }
            }
        }
    }

    @Test
    public void theLayoutRectangleIsNotWastefullyLargerThanItHasToBe() {
        // The other side of the same coin. Covering every visible pixel is trivially satisfied
        // by returning a huge rectangle, which would quietly undo the culling this feature
        // sits on top of -- the extent feeds `FlowCulling.visibleIn`, so slack here is boxes
        // drawn off screen at exactly the node count where the frame budget is tight.
        int screenSize = 612;
        for (float zoom : ZOOMS) {
            for (int offset = 0; offset < 4000; offset += 7) {
                int extent = FlowZoom.layoutExtent(offset, screenSize, zoom);
                int ideal = (int) Math.ceil(screenSize / (double) zoom);
                assertTrue("zoom " + zoom + " offset " + offset + " extent " + extent
                                + " against an ideal of " + ideal,
                        extent <= ideal + 1);
            }
        }
    }

    @Test
    public void theExtentIsNotComputedFromTheSizeAlone() {
        // Pinning the reason `layoutExtent` takes the offset. `ceil(screenSize / zoom)` is the
        // obvious implementation and it is short by one at any offset that does not land on a
        // layout pixel boundary -- so this asserts that such an offset EXISTS and is handled,
        // which is what stops someone simplifying the signature back.
        int screenSize = 612;
        float zoom = 0.75f;
        int naive = (int) Math.ceil(screenSize / (double) zoom);
        boolean everWider = false;
        for (int offset = 0; offset < 400 && !everWider; offset++) {
            everWider = FlowZoom.layoutExtent(offset, screenSize, zoom) > naive;
        }
        assertTrue("no offset needed a wider extent than ceil(size/zoom), so the offset "
                + "argument would be dead -- if this ever fails, the rounding changed",
                everWider);
    }

    @Test
    public void nothingVisibleIsCulledAtAnyZoomOrOffset() {
        // END TO END THROUGH THE REAL CULLER, against a brute-force scan of what a screen-space
        // viewport actually overlaps. The two tests above check the rectangle in isolation;
        // this one checks the thing the rectangle exists for, which is the assertion that
        // would still fail if `FlowCulling` and `FlowZoom` were each self-consistent and
        // disagreed with one another.
        FlowLayout.Laid laid = FlowLayout.of(PlanTrees.deepFan(4, 3));
        FlowCulling culling = new FlowCulling(laid);
        int screenWidth = 400;
        int screenHeight = 240;
        for (float zoom : ZOOMS) {
            int scaledWidth = FlowZoom.scaledExtent(laid.width, zoom);
            int scaledHeight = FlowZoom.scaledExtent(laid.height, zoom);
            for (int sx = 0; sx < scaledWidth; sx += 37) {
                for (int sy = 0; sy < scaledHeight; sy += 41) {
                    IntArray shown = culling.visibleIn(
                            FlowZoom.layoutOrigin(sx, zoom),
                            FlowZoom.layoutOrigin(sy, zoom),
                            FlowZoom.layoutExtent(sx, screenWidth, zoom),
                            FlowZoom.layoutExtent(sy, screenHeight, zoom));
                    Set<Integer> drawn = new HashSet<Integer>();
                    for (int i = 0; i < shown.size(); i++) {
                        drawn.add(Integer.valueOf(shown.get(i)));
                    }
                    for (int i = 0; i < laid.size(); i++) {
                        FlowLayout.Box box = laid.boxes.get(i);
                        // The box's own rectangle, in screen pixels at this zoom.
                        double left = box.x * (double) zoom;
                        double top = box.y * (double) zoom;
                        double right = box.right() * (double) zoom;
                        double bottom = box.bottom() * (double) zoom;
                        boolean onScreen = right > sx && left < sx + screenWidth
                                && bottom > sy && top < sy + screenHeight;
                        if (onScreen) {
                            assertTrue("zoom " + zoom + " at " + sx + "," + sy + ": box " + i
                                            + " overlaps the screen and was culled",
                                    drawn.contains(Integer.valueOf(i)));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void theScrollSizeCoversTheWholeDiagramAtEveryZoom() {
        // A scroll size one pixel short leaves the last column permanently unreachable, and
        // "it will not scroll all the way right" is a complaint nobody traces to a rounding
        // mode. Swept over widths as well as zooms, since the shortfall depends on both.
        for (float zoom : ZOOMS) {
            for (int extent = 0; extent < 5000; extent += 13) {
                int scaled = FlowZoom.scaledExtent(extent, zoom);
                assertTrue("zoom " + zoom + " extent " + extent + " scaled to " + scaled,
                        scaled >= extent * (double) zoom);
            }
        }
    }

    @Test
    public void reanchoringHoldsTheCentreOfTheViewportStill() {
        // THE PROPERTY, not a table of expected offsets: whatever layout point was under the
        // middle of the panel before the zoom is under it afterwards. Stated that way it is
        // swept over every pair of zooms and a range of offsets, and it cannot be satisfied by
        // a formula that happens to agree at the two zooms I would have written down.
        int viewport = 612;
        for (float from : ZOOMS) {
            for (float to : ZOOMS) {
                for (int offset = 0; offset < 3000; offset += 23) {
                    double centreBefore = (offset + viewport / 2.0) / from;
                    int moved = FlowZoom.reanchor(offset, viewport, from, to);
                    double centreAfter = (moved + viewport / 2.0) / to;
                    // Within a layout pixel: the offset is an integer, so at zoom 2 the finest
                    // representable step is half a layout pixel and rounding has to land
                    // somewhere.
                    assertTrue("from " + from + " to " + to + " at " + offset + ": centre moved "
                                    + centreBefore + " -> " + centreAfter,
                            Math.abs(centreBefore - centreAfter) <= 1.0);
                }
            }
        }
    }

    @Test
    public void reanchoringAtTheSameZoomChangesNothing() {
        // The identity case, which a formula with a stray rounding step gets wrong by a pixel
        // per call -- invisible once, and a diagram that creeps sideways every time someone
        // scrolls with control held and the zoom is already at a limit.
        for (float zoom : ZOOMS) {
            for (int offset = 0; offset < 3000; offset += 23) {
                assertEquals("zoom " + zoom + " offset " + offset,
                        offset, FlowZoom.reanchor(offset, 612, zoom, zoom));
            }
        }
    }

    /**
     * The forward map lands every layout point inside the box the inverse maps it back to (#330).
     *
     * THE ROUND TRIP IS THE ASSERTION, and it is the property the edges actually need: an edge
     * endpoint converted with {@link FlowZoom#toScreen} must, when put back through the inverse
     * the canvas hit-tests with, come back to the layout pixel it started from -- or near enough
     * that it is still inside the 209x26 node. `FlowCanvas.toLayoutX` is
     * `floor((screen + scroll) / zoom)`, which is the inverse ModularUI is measured to agree with
     * by `flow-hit` on every harness run.
     *
     * SWEPT RATHER THAN SPOT-CHECKED, per this file's header. The error is bounded by the
     * rounding, so one pixel of layout at zoom 1 and two at 0.5 -- what must not happen is a
     * drift that GROWS with the offset, which is what the wrong composition order produces.
     */
    @Test
    public void aLayoutPointConvertedToScreenComesBackToWhereItStarted() {
        for (float zoom : ZOOMS) {
            for (int scroll = 0; scroll < 4000; scroll += 37) {
                for (int layout = 0; layout < 20000; layout += 211) {
                    int screen = FlowZoom.toScreen(layout, zoom, scroll);
                    int back = (int) Math.floor((screen + scroll) / (double) zoom);
                    int slack = (int) Math.ceil(1.0 / zoom);
                    assertTrue("zoom " + zoom + " scroll " + scroll + " layout " + layout
                            + " -> screen " + screen + " -> layout " + back,
                            Math.abs(back - layout) <= slack);
                }
            }
        }
    }

    /**
     * The scale is INSIDE the translate, which is the one thing about this that can be silently
     * wrong (#330).
     *
     * MEASURED FROM THE BYTECODE, NOT ASSUMED. `AbstractScrollWidget.transformChildren` calls
     * `IViewportStack.translate(-scrollX, -scrollY)`, `FlowCanvas.transformChildren` then calls
     * `stack.scale(zoom, zoom)`, and `GuiViewportStack` implements both with JOML's
     * post-multiplying `Matrix4f.translate` and `Matrix4f.scale` -- so a child's point comes out
     * at `layout * zoom - scroll`.
     *
     * THE OTHER ORDER AGREES AT ZOOM 1 AND AT SCROLL 0, which is the whole reason it is worth a
     * test: `zoom * (layout - scroll)` is identical everywhere a screenshot of an unzoomed
     * diagram at the origin could look, and diverges by `scroll * (zoom - 1)` -- 400 pixels at
     * zoom 2 with the view 400 along. This asserts the agreement AND the divergence, because
     * only the second one distinguishes the two formulas.
     */
    @Test
    public void theZoomScalesTheLayoutPointAndTheScrollIsSubtractedAfterIt() {
        assertEquals("at zoom 1 the conversion is a plain translate", 300 - 120,
                FlowZoom.toScreen(300, 1.0f, 120));
        assertEquals("and at scroll 0 it is a plain scale", 600,
                FlowZoom.toScreen(300, 2.0f, 0));
        assertEquals("a layout point at zoom 2 with the view 400 along", 600 - 400,
                FlowZoom.toScreen(300, 2.0f, 400));
        assertEquals("the other order would give " + (2.0f * (300 - 400)) + " here, which is the"
                + " same answer only when the zoom is 1 or the scroll is 0", 200,
                FlowZoom.toScreen(300, 2.0f, 400));
        assertTrue("the two formulas must really differ at this offset, or the assertion above"
                + " pins nothing",
                FlowZoom.toScreen(300, 2.0f, 400) != Math.round(2.0f * (300 - 400)));
        // AND THE CLAMP APPLIES, so a NaN or an out-of-range zoom cannot put an edge at
        // `Integer.MIN_VALUE` instead of drawing nothing.
        assertEquals(FlowZoom.toScreen(300, FlowZoom.DEFAULT, 10),
                FlowZoom.toScreen(300, Float.NaN, 10));
        assertEquals(FlowZoom.toScreen(300, FlowZoom.MAX, 10),
                FlowZoom.toScreen(300, 99f, 10));
    }

    /**
     * Half a node is not an integer, and rounding it before scaling loses the half.
     *
     * WHY THE PARAMETER IS A `double`. An edge meets a node at the middle of its 26px height,
     * which is 13 exactly -- but `parkCursorOverBox` centres on half of a 209px WIDTH, and 104.5
     * scaled by 2 is 209 while 104 scaled by 2 is 208. A pixel is not much on its own; it is
     * enough for the cursor probe and the drawn edge to disagree about which pixel is the centre,
     * which is the kind of one-off that reads as a broken probe.
     */
    @Test
    public void aFractionalLayoutCoordinateKeepsItsHalfPixelUntilAfterTheScale() {
        assertEquals(209, FlowZoom.toScreen(104.5, 2.0f, 0));
        assertEquals(208, FlowZoom.toScreen(104, 2.0f, 0));
        assertEquals("13 at zoom 1 is still 13", 13, FlowZoom.toScreen(13, 1.0f, 0));
    }

    @Test
    public void zoomIsClampedAndNaNDoesNotBecomeABlankDiagram() {
        assertEquals(FlowZoom.MIN, FlowZoom.clamp(0.01f), 0.0f);
        assertEquals(FlowZoom.MAX, FlowZoom.clamp(99f), 0.0f);
        assertEquals(1.25f, FlowZoom.clamp(1.25f), 0.0f);
        // NaN SPECIFICALLY, because `Math.max(MIN, Math.min(MAX, NaN))` is NaN, and a NaN zoom
        // divides every viewport into nothing: the culler gets a zero-width rectangle and the
        // panel goes blank with nothing logged. A clamp that passes NaN through is not a clamp.
        assertEquals(FlowZoom.DEFAULT, FlowZoom.clamp(Float.NaN), 0.0f);
        assertEquals(FlowZoom.MAX, FlowZoom.clamp(Float.POSITIVE_INFINITY), 0.0f);
        assertEquals(FlowZoom.MIN, FlowZoom.clamp(Float.NEGATIVE_INFINITY), 0.0f);
    }
}
