package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.common.PlanBook;

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
        PlannerScreen.openPlan(fixture(arg), book());
    }

    static void openMenu(String arg) {
        PlannerScreen.openPanel(
                PlannerWidgets.nodeMenu(fixture(arg).tree(), PlannerActions.NONE));
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
}
