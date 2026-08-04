package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
import io.github.jacoblasky.recipedump.plan.PlanNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #213's second design question: does a selection survive a re-solve?
 *
 * ONLY THE POLICY IS ASSERTED HERE, because only the policy can be. The window that applies it
 * is a `ModularScreen` that reopens itself through `ClientGUI` and reads `PlannerService`, none
 * of which exists in this JVM -- so `PlannerScreen.clearSelectionIfPlanChanged` is a method
 * rather than two lines inside `onUpdate`, and this is the file that made it worth being one.
 *
 * The assertion that earns its keep is {@link #aBookEditDoesNotClearTheSelection}. Clearing on a
 * NEW PLAN is the obvious half and reads correct either way; clearing on the COMBINED counter
 * that `PlannerScreen.stamp` produces looks equally correct in review and drops the highlight
 * whenever the player stars the node they are looking at.
 */
public class PlannerScreenTest {

    private static final PlanNode NODE = new PlanNode.Builder()
            .key("minecraft:iron_ingot")
            .name("Iron Ingot")
            .label("Iron Ingot")
            .kind("item")
            .need(4L)
            .status("craft")
            .build();

    @Before
    @After
    public void clearTheHolder() {
        // A STATIC HOLDER IS SHARED BY EVERY TEST IN THIS JVM. A leftover selection makes an
        // unrelated layout assertion draw a highlight it never set up.
        PlanSelection.clear();
    }

    @Test
    public void aNewPlanClearsTheSelection() {
        PlanSelection.select(NODE);
        assertTrue(PlannerScreen.clearSelectionIfPlanChanged(7L, 8L));
        assertEquals("", PlanSelection.selectedKey());
        assertNull("and the NODE goes too, or an action reads a need from a dead plan",
                   PlanSelection.selectedNode());
    }

    /**
     * A book edit is not a new plan.
     *
     * "Favourite" and "Add to TODO" both bump `PlanBook.revision`, which `PlannerScreen.stamp`
     * folds into the counter the window watches -- so a version that compared the STAMP would
     * clear the highlight the moment the player used either entry on the menu of the node they
     * had just selected. The click would look like it had cancelled itself.
     */
    @Test
    public void aBookEditDoesNotClearTheSelection() {
        PlanSelection.select(NODE);
        assertFalse("the same plan generation must leave the selection alone",
                    PlannerScreen.clearSelectionIfPlanChanged(7L, 7L));
        assertTrue(PlanSelection.isSelected(NODE.key()));
        assertSame(NODE, PlanSelection.selectedNode());
    }

    @Test
    public void clearingWhenNothingIsSelectedIsNotAnError() {
        // The usual case by a wide margin: every tick of every open window with no selection.
        assertTrue(PlannerScreen.clearSelectionIfPlanChanged(1L, 2L));
        assertEquals("", PlanSelection.selectedKey());
    }
}
