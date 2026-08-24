package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The part of `flow-nav`'s gesture a headless test can hold onto: the ORDER of its steps.
 *
 * WHY THERE IS ANYTHING TO TEST HERE AT ALL. Nothing else in `FlowInteractionShot` runs without a
 * client -- it drives a real cursor over a real ModularUI screen -- so this file is deliberately
 * not an attempt to test the gesture. It pins the one property that is a fact about the CODE
 * rather than about a running game, and that has already been wrong twice in production runs.
 *
 * THE INVARIANT. `flow-nav` makes two independent claims about one canvas, and each needs the
 * viewport somewhere the other does not:
 *
 *   - the Root-jump proof needs the root OFF frame before the click, or "the button brought it
 *     into frame" is satisfied by a root that never left;
 *   - the #330 adjacency measurement needs a parent and a child BOTH wholly in frame, which on
 *     this layout means panning somewhere the 60px drag did not land -- and the nearest such pair
 *     is the root's own edge, so that pan puts the root back.
 *
 * Both orderings have failed a real run. Measurement first: nothing measurable was in frame, and
 * the scenario reported that as a defect while its own screenshot showed the fix working. Navigate
 * before the jump: the pan brought the root back and the jump proof reported "the drag left the
 * root in frame, so the jump proves nothing" -- rerun 3, at scroll 0,132, with the nearest pair
 * being box 0 -> 1.
 *
 * WHAT MAKES THIS WORTH A TEST RATHER THAN A COMMENT is the shape of the failure. Neither ordering
 * crashes. Both produce a run that completes and reports a confident verdict about the wrong
 * thing, which is the same class of failure as #330 itself.
 */
public class FlowInteractionShotTest {

    /**
     * The Root-jump proof runs before anything that could pan the root back into frame.
     *
     * CONTIGUITY IS ASSERTED AS WELL AS ORDER, and it is not pedantry: `drive` dispatches with a
     * chain of `step ==` tests and each step sets the next by name, so two constants that collide
     * or a gap in the sequence does not fail -- it SKIPS a step and the gesture reports on
     * measurements it never took. Asserting each step is exactly one past the last catches a
     * renumber that a reader would not.
     */
    @Test
    public void theRootJumpIsProvedBeforeAnythingPansTheRootBackIntoFrame() {
        assertTrue("the Root-jump proof must run BEFORE the navigation that finds a measurable"
                   + " pair: that pan can put the root back in frame, and then the jump is being"
                   + " asked to prove something about a root that never left",
                   FlowInteractionShot.DragThenJump.ROOT_JUMP
                           < FlowInteractionShot.DragThenJump.NAVIGATE);

        // AND THE MEASUREMENT AFTER THE JUMP, which is the other direction this has been wrong in.
        assertTrue("the adjacency measurement must run after the Root jump, or it has nothing"
                   + " measurable in frame to look at",
                   FlowInteractionShot.DragThenJump.ROOT_JUMP
                           < FlowInteractionShot.DragThenJump.MEASURE);

        int[] order = {
            FlowInteractionShot.DragThenJump.PRESS,
            FlowInteractionShot.DragThenJump.DRAG_AND_RELEASE,
            FlowInteractionShot.DragThenJump.ROOT_JUMP,
            FlowInteractionShot.DragThenJump.NAVIGATE,
            FlowInteractionShot.DragThenJump.MEASURE,
            FlowInteractionShot.DragThenJump.ARRIVAL,
            FlowInteractionShot.DragThenJump.DEPARTURE,
            FlowInteractionShot.DragThenJump.VERDICT,
        };
        assertEquals("the first step must be 0, which is what `step` initialises to; anything else"
                     + " and the gesture never starts", 0, order[0]);
        for (int i = 1; i < order.length; i++) {
            assertEquals("step " + i + " must be exactly one past step " + (i - 1) + ", or drive()"
                         + "'s chain of == tests skips one and the gesture reports on a"
                         + " measurement it never took", order[i - 1] + 1, order[i]);
        }
    }
}
