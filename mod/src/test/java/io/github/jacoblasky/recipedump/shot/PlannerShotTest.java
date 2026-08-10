package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.flow.FlowCanvas;
import io.github.jacoblasky.recipedump.client.flow.FlowCulling;
import io.github.jacoblasky.recipedump.client.flow.FlowLayout;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The screenshot harness's half of Phase 4, asserted where a screenshot cannot assert it.
 *
 * WHY A HARNESS FILE EARNS TESTS AT ALL. This harness is how every UI claim in #19 is
 * evidenced, so a harness that quietly stops exercising the real code produces pictures that
 * look right and prove nothing -- which is worse than no picture, because a reviewer believes
 * it. That is not hypothetical: the `planner-menu` screen was wired to
 * `PlannerActions.NONE`, whose `nodeActions()` returns `NodeActions.NONE`, so the screen
 * registered specifically to photograph the node menu was structurally incapable of ever
 * showing its two recipe-viewer entries no matter what Phase 4 installed. Both tests below
 * fail against that version.
 */
public class PlannerShotTest {

    /** Vanilla-rooted (`minecraft:hopper`) and deep enough to exercise the recursion. */
    private static final String FIXTURE = "plan-in-stock";

    @Before
    @After
    public void resetHolder() {
        NodeActionsHolder.install(null);
    }

    @Test
    public void theMenuShotAsksTheRealHolderRatherThanTheStubThatAlwaysSaysNo() {
        NodeActions installed = new JeiNodeActions(JeiNodeActions.NO_GRAPH);
        NodeActionsHolder.install(installed);
        assertSame(installed, PlannerShot.SHOT_ACTIONS.nodeActions());
    }

    @Test
    public void armingTheSeamReplacesTheNoOpSoTheShotPhotographsTheRealMenu() {
        PlannerShot.armNodeActions(PlanFixtureFiles.load(FIXTURE).tree());
        assertNotSame(NodeActions.NONE, NodeActionsHolder.actions());
        assertTrue(NodeActionsHolder.actions() instanceof JeiNodeActions);
    }

    @Test
    public void everyKeyInTheTreeIsInternedAndNotJustTheRoot() {
        // A graph holding only the root would give the picture one icon and one working menu,
        // with every row below it silently answering false -- and that reads as "the feature
        // only works for the target", which is a plausible-looking wrong conclusion.
        PlanView plan = PlanFixtureFiles.load(FIXTURE);
        GraphBuilder builder = new GraphBuilder();
        PlannerShot.intern(plan.tree(), builder);
        RecipeGraph graph = builder.build();

        assertTrue("the fixture must have children or the recursion is untested",
                   plan.tree().hasChildren());
        assertInterned(plan.tree(), graph);
    }

    private static void assertInterned(PlanNode node, RecipeGraph graph) {
        assertTrue("key was never interned: " + node.key(), graph.keyId(node.key()) >= 0);
        for (PlanNode child : node.children()) {
            assertInterned(child, graph);
        }
    }

    @Test
    public void syntheticBuildsExactlyTheNodeCountAskedFor() {
        // THE SUBJECT OF EVERY 60 FPS MEASUREMENT IN THIS PROJECT, and it was wrong. Before
        // 2026-08-03 `synthetic(4000)` returned a 2,006 node tree: the deepest level started
        // at `nodes / 3` and each level above was a third of the one below, so the total
        // converged to about half the request and the loop exited on `width == 1` with the
        // rest of the budget unspent. The comment above it said "the count is exact rather
        // than approached", which is why nobody counted.
        //
        // Nothing else can catch this. The screenshots look identical, the culling assertions
        // are proportional to what is on screen rather than to the plan, and the frame timings
        // are simply lower than they should be -- a gate that passes because the subject is
        // half the size it claims. So: count the tree.
        assertEquals(4000, count(PlannerShot.synthetic(4000)));
    }

    @Test
    public void syntheticIsExactAtEverySizeAndAlwaysHasOneRoot() {
        // SWEPT, because the trim that makes the total come out right acts on the deepest
        // level and its size depends on where the shrink happens to land -- so the sizes that
        // break it are the ones nobody would pick. A handful of round numbers would all be
        // comfortably in the middle of a level.
        for (int n = 1; n <= 600; n++) {
            PlanNode root = PlannerShot.synthetic(n);
            assertEquals("synthetic(" + n + ")", n, count(root));
        }
        for (int n = 1000; n <= 5000; n += 137) {
            assertEquals("synthetic(" + n + ")", n, count(PlannerShot.synthetic(n)));
        }
    }

    /**
     * The frame the flow sweep STOPS on is fraction (0, 0), and (0, 0) of a real plan is
     * empty. That is the whole of #293.
     *
     * THE ARITHMETIC IS THE DEFECT, and it is worth writing out because it reads as harmless.
     * `openFlow` pans to `((frame * PASSES) % SWEEP) / SWEEP` across and `(frame % SWEEP) /
     * SWEEP` down, so both axes are periodic in `SWEEP` frames. A timing run is driven for
     * `-Dmcrecipedump.shotTimedFrames` frames and every flow run has used 300, which is
     * `SWEEP`. Both fractions are therefore 0 on the last frame, and the capture happens on
     * the frame after the last step. The camera was aimed at the origin every single run.
     *
     * AND THE ORIGIN IS THE ONE PLACE GUARANTEED TO HOLD NOTHING, which `FlowCanvas`'s own
     * header already said before this test existed: column zero holds one node, the root,
     * "centred on the pixel midpoint of the whole diagram and therefore nowhere near the top".
     * Five screenshots -- `flow-4000{,-final,-run3,-run4}.png` and the `peak-final.png` that
     * #183 cited as its artifact -- are all blank for this reason, and byte-identical in size
     * because a deterministic end state photographs the same nothing every time.
     *
     * ASSERTED AGAINST `synthetic(4000)` AT THE HARNESS'S OWN VIEWPORT, not against a
     * convenient tree. The first version of this used `PlanTrees.fan(400)`, whose leaf column
     * is column ONE and therefore inside the viewport at the origin -- so the corner it called
     * empty held four hundred nodes and the test failed. A case picked for convenience is
     * immune to the defect, which is the same lesson `tools/gate.sh`'s header records about a
     * probe run on the wrong filesystem.
     */
    @Test
    public void theFrameTheFlowSweepStopsOnShowsNothingAndTheRootBoxDoes() {
        PlanNode tree = PlannerShot.synthetic(4000);
        FlowCanvas canvas = new FlowCanvas(tree);
        // THE SIZE `openFlow` USES. A different viewport is a different question.
        canvas.pos(4, 4).size(612, 372);
        HeadlessLayout.layOut(PlannerWidgets.flowPanel(canvas));
        FlowLayout.Laid laid = FlowLayout.of(tree);

        // The stopping frame, derived rather than written as 0. If SWEEP, PASSES or the frame
        // budget ever stop coinciding this line changes with them and the test still asks the
        // real question, instead of asserting something about a hard-coded 0 nobody rechecks.
        int last = PlannerShot.SWEEP;
        double x = ((last * PlannerShot.PASSES) % PlannerShot.SWEEP) / (double) PlannerShot.SWEEP;
        double y = (last % PlannerShot.SWEEP) / (double) PlannerShot.SWEEP;
        canvas.panToFraction(x, y);
        assertEquals("the flow sweep stops where it started, and a timing run is driven for "
                + "exactly one period", 0, visibleCount(canvas, laid));

        // AND THE POSE FIXES IT. Same canvas, same plan, one call: this is the difference
        // between an artifact and a grey rectangle.
        canvas.panToBox(canvas.rootBox());
        assertTrue("centring on the root must photograph something",
                visibleCount(canvas, laid) > 0);
    }

    private static int visibleCount(FlowCanvas canvas, FlowLayout.Laid laid) {
        return new FlowCulling(laid).visibleIn(
                canvas.getScrollArea().getScrollX().getScroll(),
                canvas.getScrollArea().getScrollY().getScroll(),
                canvas.getArea().width, canvas.getArea().height).size();
    }

    /** Nodes in the tree. Iterative, since the trees under test are thousands deep-ish. */
    private static int count(PlanNode root) {
        int seen = 0;
        java.util.Deque<PlanNode> pending = new java.util.ArrayDeque<PlanNode>();
        pending.push(root);
        while (!pending.isEmpty()) {
            PlanNode node = pending.pop();
            seen++;
            for (PlanNode child : node.children()) {
                pending.push(child);
            }
        }
        return seen;
    }

    /**
     * The recipe-picker shot photographs the node with the MOST candidates, not the root.
     *
     * WHY IT MATTERS FOR A PICTURE. The fixtures' roots carry between 1 and 22 alternatives
     * and nodes deeper in reach 172, so shooting the root produced pickers with a single row
     * -- a screenshot of the feature not doing its job. It is also the only way to photograph
     * the capped list, which no fixture root can produce.
     *
     * DETERMINISTIC, because a shot of a given fixture has to be the same picture every time
     * or a reviewer cannot compare two of them. Ties go to the first in depth-first order.
     */
    @Test
    public void theRecipePickerShotPicksTheNodeWithTheMostCandidates() {
        PlanView plan = PlanFixtureFiles.load(FIXTURE);
        PlanNode picked = PlannerShot.mostAlternatives(plan.tree());

        int best = 0;
        for (PlanNode node : plan.flatten()) {
            best = Math.max(best, node.alternatives());
        }
        assertTrue("the fixture must contain a node with alternatives", best > 1);
        assertEquals(best, picked.alternatives());
        assertTrue("and it must not be the root, or this test proves nothing about "
                   + FIXTURE, picked.alternatives() > plan.tree().alternatives());
        assertSame("the same fixture must give the same picture every time",
                   picked, PlannerShot.mostAlternatives(plan.tree()));
    }
}
