package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Opens a planner window against a frozen plan fixture, for `-Dmcrecipedump.shot=planner:...`.
 *
 * A FIXTURE FROM DISK RATHER THAN A BOOK MADE UP HERE. The harness shoots from the main menu,
 * so there is no player, no capability and no solver -- and #135's fixtures are 19 real solved
 * plans from the reference pack, which is far better subject matter than anything written to
 * flatter the renderer. `plan-fluid-chain` alone is 347 nodes.
 *
 * DEV-ONLY, and it is in `shot/` for that reason: it reads `tests/fixtures/` out of the
 * working tree, which does not exist beside a shipped jar. Nothing outside the harness calls
 * it, and `ShotScreens` loads this class only when its name is asked for.
 */
final class PlannerShot {

    /** `plan-truncated` shows the most in one picture: a deep tree AND the cut-off warning. */
    private static final String DEFAULT_FIXTURE = "plan-truncated";

    private PlannerShot() {
    }

    /** `planner`, or `planner:<fixture>`; `arg` is whatever followed the colon. */
    static void openTree(String arg) {
        PlanView plan = fixture(arg);
        armNodeActions(plan);
        PlannerScreen.openPlan(plan, book());
    }

    static void openMenu(String arg) {
        PlanView plan = fixture(arg);
        armNodeActions(plan);
        PlannerScreen.openPanel(PlannerWidgets.nodeMenu(plan.tree(), SHOT_ACTIONS));
    }

    static void openRecipePicker(String arg) {
        PlannerScreen.openPanel(PlannerWidgets.recipePicker(fixture(arg).tree()));
    }

    static void openTodo(String arg) {
        PlannerScreen.openPanel(PlannerWidgets.todoPanel(fixture(arg), book()));
    }

    /**
     * A book with something in it, since an empty TODO panel proves only that it opens.
     *
     * Real keys from the reference pack rather than "foo": the discriminated one is the shape
     * that overflows a row, and 934,400 mB of water is a real Borax draw.
     */
    private static PlanBook book() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.setTodo("nuclearcraft:borax", 64L);
        book.setTodo("fluid:water", 934_400L);
        book.setTodo("thaumadditions:vis_pod#0116bb2287a7", 3L);
        return book;
    }

    private static PlanView fixture(String name) {
        String wanted = name == null || name.trim().isEmpty() ? DEFAULT_FIXTURE : name.trim();
        return PlanFixtureFiles.load(wanted);
    }

    /**
     * {@link PlannerActions#NONE} in every respect EXCEPT that it asks the real
     * {@link NodeActionsHolder} what the recipe-viewer entries should do.
     *
     * WITHOUT THIS THE MENU SHOT CANNOT PHOTOGRAPH THE MENU. `PlannerActions.NONE` answers
     * with `NodeActions.NONE`, which says false to everything -- so the screen registered
     * specifically to show the node menu was structurally incapable of ever showing its two
     * recipe-viewer entries, no matter what Phase 4 installed, and the picture would have
     * looked correct while proving nothing. The CLICKING half stays inert for the reason
     * `PlannerActions.NONE` gives: the harness shoots from the main menu, where there is no
     * panel to hang a sub-panel off and no server to send a plan-book edit to.
     */
    static final PlannerActions SHOT_ACTIONS = new PlannerActions() {
        @Override
        public NodeActions nodeActions() {
            return NodeActionsHolder.actions();
        }

        @Override
        public void openNodeMenu(PlanNode node) {
        }

        @Override
        public void openRecipePicker(PlanNode node) {
        }

        @Override
        public void addToTodo(PlanNode node) {
        }

        @Override
        public void toggleFavourite(PlanNode node) {
        }
    };

    /**
     * Give the Phase 4 seam a graph to answer from, so the icons and the two recipe-viewer
     * entries appear in a shot instead of only in a description of one.
     *
     * THE KEY TABLE IS BUILT HERE RATHER THAN A GRAPH LOADED, and the distinction is what
     * keeps the picture honest. Nothing on the client loads a `RecipeGraph` yet, and a second
     * loader living in the harness would be a second thing to keep in step with the dump
     * format -- the exact duplication `NodeActions` was written to avoid. A key table is also
     * all {@link JeiNodeActions} reads: keying the item list, the digest and durability
     * weakenings, and the decision to draw an entry at all run through the production classes
     * over the client's REAL JEI item list. One thing is synthetic -- where the keys came from
     * -- and nothing else is.
     *
     * WHICH FIXTURE YOU SHOOT DECIDES WHETHER ANYTHING RESOLVES. The dev set is Forge,
     * MixinBooter, ModularUI, HEI and JEC, so a plan rooted in a modded item has no stack
     * behind it in this client and correctly draws no icon and no entries. Shoot
     * `plan-in-stock` (minecraft:hopper) or `plan-free-source` (minecraft:cobblestone) to see
     * the populated case.
     */
    static void armNodeActions(PlanView plan) {
        GraphBuilder builder = new GraphBuilder();
        intern(plan.tree(), builder);
        final RecipeGraph graph = builder.build();
        JeiBridge.indexFor(graph);
        JeiNodeActions.install(new JeiNodeActions.GraphSource() {
            @Override
            public RecipeGraph graph() {
                return graph;
            }
        });
    }

    /** Recursive for the reason `PlanJson.readNode` is: the deepest fixture is 347 nodes. */
    static void intern(PlanNode node, GraphBuilder builder) {
        builder.key(node.key());
        for (PlanNode child : node.children()) {
            intern(child, builder);
        }
    }
}
