package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.client.planner.PlanNode;
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
        PlannerShot.armNodeActions(PlanFixtureFiles.load(FIXTURE));
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
}
