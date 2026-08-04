package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;

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
 * A ROUND TRIP IS THE ASSERTION THAT MATTERS. Each direction on its own is a formula anyone
 * can talk themselves into; the pair has to compose back to where it started, and a sign
 * error in either one breaks that.
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
    }

    /** The scale is applied, so a GUI pixel is not a display pixel at any scale above 1. */
    @Test
    public void theScaleIsNotDroppedOnEitherAxis() {
        assertEquals(200, ScreenCursor.toDisplayX(100, 2));
        assertEquals(300, ScreenCursor.toDisplayX(100, 3));
        assertEquals(DISPLAY_HEIGHT - 200, ScreenCursor.toDisplayY(100, 2, DISPLAY_HEIGHT));
        assertEquals(100, ScreenCursor.toGuiX(400, 4));
    }
}
