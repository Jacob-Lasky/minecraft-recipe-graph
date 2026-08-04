package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
