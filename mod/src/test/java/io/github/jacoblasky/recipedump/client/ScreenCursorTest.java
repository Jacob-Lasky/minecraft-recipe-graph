package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * The cursor conversion, which has two independent ways to be wrong and no visible symptom.
 *
 * BOTH ERRORS LOOK LIKE A BUG SOMEWHERE ELSE. Forget the scale and the cursor lands at a
 * fraction of the intended position; forget the y flip and it lands at the vertical mirror.
 * Neither throws, and both put the cursor on a real part of a real screen -- so the probe
 * reads a widget, reports a disagreement, and the blame goes to the widget. `flow-hit`'s own
 * header records the version of this that produced six tidy AGREE lines agreeing about
 * nothing.
 *
 * THE FLIP IS ASSERTED AS A FACT AND NOT ONLY AS A ROUND TRIP. DO NOT reduce this file to the
 * round trip: an identity composes with itself, so
 * {@link #guiToDisplayAndBackIsTheIdentity} passes unchanged on a `toDisplayY` that has had
 * its flip "simplified" away to match the x. The round trip is still worth its lines, because
 * it is what catches a sign error in ONE direction, but it cannot catch the removal of both.
 */
public class ScreenCursorTest {

    /** 1280x800 at scale 2, which is what `prodshot.sh` boots. */
    private static final int DISPLAY_HEIGHT = 800;

    private static final int SCALE = 2;

    @Test
    public void guiToDisplayAndBackIsTheIdentity() {
        for (int guiY = 0; guiY <= DISPLAY_HEIGHT / SCALE; guiY += 7) {
            int raw = ScreenCursor.toDisplayY(guiY, SCALE, DISPLAY_HEIGHT);
            assertEquals("y " + guiY + " must survive the flip and the scale",
                         guiY, ScreenCursor.toGuiY(raw, SCALE, DISPLAY_HEIGHT));
        }
        for (int guiX = 0; guiX < 640; guiX += 7) {
            assertEquals(guiX, ScreenCursor.toGuiX(ScreenCursor.toDisplayX(guiX, SCALE), SCALE));
        }
    }

    /**
     * The flip, stated as a fact rather than as a round trip.
     *
     * A round trip is satisfied by NO flip at all -- an identity composes with itself. This
     * is the assertion that fails when someone "simplifies" the y to match the x.
     */
    @Test
    public void theTopOfTheGuiIsTheTopOfTheDisplayWhichIsLwjglsHighestY() {
        assertEquals(DISPLAY_HEIGHT, ScreenCursor.toDisplayY(0, SCALE, DISPLAY_HEIGHT));
        assertEquals(0, ScreenCursor.toDisplayY(DISPLAY_HEIGHT / SCALE, SCALE, DISPLAY_HEIGHT));
        assertEquals(0, ScreenCursor.toGuiY(DISPLAY_HEIGHT, SCALE, DISPLAY_HEIGHT));
        assertEquals(DISPLAY_HEIGHT / SCALE,
                     ScreenCursor.toGuiY(0, SCALE, DISPLAY_HEIGHT));
    }

    /**
     * The two axes are DIFFERENT functions, at a point where a transposition would show.
     *
     * The pair above pins the ends of the y axis, which a reader can still satisfy by
     * believing x is flipped too. This is the middle of the screen with x and y given the
     * same GUI value, where "the same conversion on both axes" is the only way to get the
     * same answer twice.
     */
    @Test
    public void theXAxisIsNotFlippedAndTheYAxisIs() {
        int gui = 150;
        assertEquals(300, ScreenCursor.toDisplayX(gui, SCALE));
        assertEquals(500, ScreenCursor.toDisplayY(gui, SCALE, DISPLAY_HEIGHT));
        assertNotEquals(ScreenCursor.toDisplayX(gui, SCALE),
                        ScreenCursor.toDisplayY(gui, SCALE, DISPLAY_HEIGHT));
    }

    /** The scale is applied, so a GUI pixel is not a display pixel at any scale above 1. */
    @Test
    public void theScaleIsNotDroppedOnEitherAxis() {
        assertEquals(200, ScreenCursor.toDisplayX(100, 2));
        assertEquals(300, ScreenCursor.toDisplayX(100, 3));
        assertEquals(DISPLAY_HEIGHT - 200, ScreenCursor.toDisplayY(100, 2, DISPLAY_HEIGHT));
        assertEquals(100, ScreenCursor.toGuiX(400, 4));
        assertEquals(100, ScreenCursor.toGuiY(DISPLAY_HEIGHT - 400, 4, DISPLAY_HEIGHT));
    }
}
