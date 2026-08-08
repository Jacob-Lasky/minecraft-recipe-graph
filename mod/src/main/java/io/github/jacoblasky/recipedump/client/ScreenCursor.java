package io.github.jacoblasky.recipedump.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Mouse;

/**
 * The conversion between GUI coordinates and the real cursor, in one place.
 *
 * TWO INDEPENDENT WAYS TO GET IT WRONG, WHICH IS WHY IT IS NOT INLINE ANY MORE. LWJGL's origin
 * is the BOTTOM left of the display and a GUI y of 0 is the TOP, so the y needs flipping; and
 * LWJGL counts real display pixels while a GUI counts scaled ones, so both axes need the scale
 * factor. Get the flip wrong and the cursor lands on the vertical mirror of the intended spot,
 * which is a plausible position -- the probe reads a real widget, just not the one it named.
 * `FlowCanvas` had this written out three times, once per direction it needed (#240).
 *
 * THE MATHS IS SEPARATED FROM THE MOUSE SO IT CAN BE TESTED. Everything a test can be wrong
 * about is in the four static conversions, which take the scale and the display height as
 * arguments and touch nothing; {@link #park} and {@link #guiX()} are the two-line wrappers
 * that read them off the live client, and they are what needs a window. Merely loading
 * `Minecraft` needs LWJGL, which is not on a JUnit classpath, so a test that reached those
 * would throw `NoClassDefFoundError` before its first line -- the same wall `PlanTarget`
 * documents around `bookOfLocalPlayer`.
 */
public final class ScreenCursor {

    private ScreenCursor() {
    }

    /** Display pixels from a GUI x. */
    public static int toDisplayX(int guiX, int scale) {
        return guiX * scale;
    }

    /** Display pixels from a GUI y, flipped: LWJGL measures up from the bottom. */
    public static int toDisplayY(int guiY, int scale, int displayHeight) {
        return displayHeight - guiY * scale;
    }

    /** GUI x from a raw LWJGL x. */
    public static int toGuiX(int rawX, int scale) {
        return rawX / scale;
    }

    /** GUI y from a raw LWJGL y, flipped. The inverse of {@link #toDisplayY}. */
    public static int toGuiY(int rawY, int scale, int displayHeight) {
        return (displayHeight - rawY) / scale;
    }

    /** The current GUI scale factor. */
    public static int scale() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
    }

    /** The screen width in GUI pixels, which is not the display width. */
    public static int guiWidth() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth();
    }

    /** The screen height in GUI pixels, which is not the display height. */
    public static int guiHeight() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight();
    }

    /** The display height in real pixels, which is what LWJGL measures its y from. */
    public static int displayHeight() {
        return Minecraft.getMinecraft().displayHeight;
    }

    /** Move the real cursor to a GUI position. */
    public static void park(int guiX, int guiY) {
        int scale = scale();
        Mouse.setCursorPosition(toDisplayX(guiX, scale),
                                toDisplayY(guiY, scale, displayHeight()));
    }

    /** Where the cursor is now, in GUI coordinates. */
    public static int guiX() {
        return toGuiX(Mouse.getX(), scale());
    }

    /** Where the cursor is now, in GUI coordinates. */
    public static int guiY() {
        return toGuiY(Mouse.getY(), scale(), displayHeight());
    }
}
