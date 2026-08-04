package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Set;

import io.github.jacoblasky.recipedump.ClassFiles;

import org.junit.Test;

/**
 * The planner draws an item at the size its column reserves, and asserting that needs bytes.
 *
 * <h2>The defect this is the gate for</h2>
 *
 * Every icon in the planner was built as ModularUI's `ItemDisplayWidget` with a `size(10, 11)`
 * on it. Its `draw` is one call: {@code GuiDraw.drawItem(stack, 1, 1, 16f, 16f, z)} -- the
 * offset and BOTH dimensions are constants in the bytecode, and it reads `getArea()` only for
 * the stack-size text. So it draws a 16x16 sprite one pixel inside whatever box it is handed,
 * and `size` on it does nothing at all.
 *
 * The first `icons-planner` screenshot is what found it: the hopper, the iron ingot and the iron
 * block spilled out of their 10px column, across the quantity beside them and into the rows
 * above and below. Five overlapping sprites down the left edge of the tree.
 *
 * <h2>Why this test is bytecode and not geometry</h2>
 *
 * EVERY LAYOUT ASSERTION IN `PlannerLayoutTest` WAS GREEN THROUGH IT, and correctly so. The
 * widget WAS 10x11 and it WAS in the right place; `HeadlessLayout` runs ModularUI's sizer and
 * the sizer was not the thing lying. What a widget puts on the screen is decided in `draw`,
 * which needs a GL context, so no headless assertion can reach it -- which is exactly why
 * `/sr-dev-review` Q5 wants a screenshot for a UI claim and why "the layout test passes" is
 * explicitly not the artifact.
 *
 * What a headless test CAN pin is the choice: this package must not reach for a widget whose
 * `draw` ignores its own area. That is a constant-pool question, and `ClassFiles` already
 * answers constant-pool questions for two other gates here.
 */
public class IconWidgetTest {

    private static final String PLANNER = ClassFiles.ROOT_PACKAGE + "/client/planner";
    private static final String ITEM_DISPLAY_WIDGET =
            "com/cleanroommc/modularui/widgets/ItemDisplayWidget";
    private static final String DRAW_ITEM = "com/cleanroommc/modularui/drawable/GuiDraw.drawItem";

    /**
     * DO NOT PUT `ItemDisplayWidget` BACK. It is the obvious widget for the job, it is what this
     * package used, and it cannot honour a 10x11 column.
     *
     * Asserted across the whole package rather than on `PlannerWidgets` alone, because the next
     * surface that wants an icon is as likely to be a new file as an edit to that one.
     */
    @Test
    public void noPlannerWidgetDrawsAnItemThroughAWidgetThatIgnoresItsOwnSize() throws IOException {
        for (File classFile : ClassFiles.under(PLANNER)) {
            assertFalse(ClassFiles.internalName(classFile) + " references "
                        + ITEM_DISPLAY_WIDGET + ", whose draw hardcodes 16x16 at (1,1) and "
                        + "reads its area only for the stack-size text",
                        ClassFiles.contains(ClassFiles.read(classFile), ITEM_DISPLAY_WIDGET));
        }
    }

    /**
     * And something in the package still draws an item.
     *
     * THE OTHER HALF, without which the test above passes on a planner that shows no icons at
     * all -- which is the state every screenshot before today was taken in, and it is not the
     * one being asserted. `GuiDraw.drawItem` is the game's own `RenderItem` path, so this also
     * pins the claim `IconAtlas`'s header makes: in game the model system is loaded and the
     * icon is whatever the player's inventory would show, which no offline texture extractor
     * can promise.
     */
    @Test
    public void somethingInThePlannerStillDrawsAnItem() throws IOException {
        boolean found = false;
        for (File classFile : ClassFiles.under(PLANNER)) {
            Set<String> called = ClassFiles.methodReferences(ClassFiles.read(classFile));
            for (String reference : called) {
                if (reference.startsWith(DRAW_ITEM)) {
                    found = true;
                }
            }
        }
        assertTrue("nothing in " + PLANNER + " calls " + DRAW_ITEM + ", so the icon column is "
                   + "charged on every row and never filled", found);
    }
}
