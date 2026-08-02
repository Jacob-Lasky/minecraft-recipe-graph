package io.github.jacoblasky.recipedump.client;

import java.util.List;

import io.github.jacoblasky.recipedump.client.planner.PlanJson;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * Chooses which planner window to open, from the graph and plan the services are holding.
 *
 * SEPARATE FROM `ClientProxy` SO IT CAN BE TESTED. The proxy's method takes an
 * `EntityPlayer` and reaches for the capability, so nothing about the decision below can be
 * exercised without a running client. This is the decision on its own: four service states
 * in, one of four windows out, no Minecraft type in the signature. `PlannerEntryTest` walks
 * every branch in milliseconds.
 *
 * WHY THE STATE MATTERS MORE THAN IT LOOKS. The graph takes 5.47 s to read, so the window is
 * routinely opened before a plan exists, and the three not-yet cases are NOT
 * interchangeable: "still loading" is a wait, "no graph.json, looked in ..." is a thing to go
 * and fix, and "nothing planned yet" is neither. Showing one for another is how a player
 * concludes the mod is broken and files a bug for a file they never installed.
 */
public final class PlannerEntry {

    private PlannerEntry() {
    }

    /**
     * What the window should show right now, or null when a solved plan should be drawn.
     *
     * `null` for the plan case rather than a fifth `PlannerState`, because a plan is a
     * different KIND of answer -- `PlannerState` is the four ways there is nothing to draw,
     * and adding "there is something" to that enum would make every reader of it check.
     */
    public static PlannerState stateFor(GraphService graphs, PlannerService planner) {
        switch (graphs.state()) {
            case MISSING:
            case FAILED:
                // The graph problem outranks the plan one. A player whose `graph.json` is
                // missing has one thing to fix, and reporting "planning failed" underneath
                // would send them to the item instead of to the file.
                return PlannerState.failed(graphs.describe());
            case IDLE:
            case LOADING:
                return PlannerState.loading(graphs.describe());
            default:
                break;
        }
        switch (planner.state()) {
            case PLANNING:
                return PlannerState.solving(planner.describe());
            case FAILED:
                return PlannerState.failed(planner.describe());
            case DONE:
                return planner.resultJson() == null
                        ? PlannerState.failed(planner.describe()) : null;
            default:
                return PlannerState.IDLE;
        }
    }

    /** Open whichever window suits the current state. */
    public static void open(PlanBook book) {
        GraphService graphs = GraphService.get();
        PlannerService planner = PlannerService.get();
        PlannerState state = stateFor(graphs, planner);
        if (state == null) {
            // THE JSON ROUND TRIP IS THE SEAM, NOT A SHORTCUT. `PlanJson.readResult` parses
            // what `plan.PlanJson.toJson` writes, and that is the exact text
            // `PlanFixtureTest` compares against `tests/fixtures/plan/*.json` -- so the panel
            // draws from bytes provably identical to the ones proven to match Python, and
            // there is no fourth place for the plan shape to live. An adapter mapping
            // `PlanResult` to `PlanView` field by field fails by dropping a field and
            // rendering a blank row rather than by erroring.
            //
            // #158 CONVERGED `PlanNode` AND THIS ROUND TRIP SURVIVED IT, which is worth
            // recording because the plan was that it would not. One `PlanNode` removed the
            // duplicated node class; `PlanView` still has no constructor over a
            // `PlanResult`, so `readResult` remains the only door and the serialise/parse is
            // still here. Removing it needs a `PlanView.of(PlanResult)`, and the property to
            // preserve when someone adds one is the one the round trip is standing in for:
            // the object the golden gate tested must be the object the panel draws, with no
            // second representation in between. Do NOT replace this with a field-by-field
            // adapter, which is the third thing and fails by rendering a blank row.
            PlannerScreen.openPlan(PlanJson.readResult(planner.resultJson()), book);
        } else {
            PlannerScreen.openState(state);
        }
        startPlan(book);
    }

    /**
     * Start planning the book's first entry, if there is one and nothing is already running.
     *
     * AFTER the window opens, never before: opening is instant and planning is not, so the
     * first frame shows the state and a later use of the item shows the tree. A player who
     * right-clicks and waits several seconds for a window has been given a slow tool, which
     * is the same argument that put the graph read on its own thread.
     *
     * THE FIRST TODO IS THE TARGET, which is a placeholder rule rather than a design. #148's
     * panel and #145's JEI keybind both have a better answer and wiring those together is the
     * next piece. What this buys today is that the whole path -- graph, scenario, cost table,
     * solver, JSON, panel -- runs against real pack data on a real client rather than only in
     * a JUnit gate.
     */
    static void startPlan(PlanBook book) {
        GraphService graphs = GraphService.get();
        if (graphs.state() != GraphService.State.READY) {
            return;
        }
        String target = firstTarget(book);
        if (target == null) {
            return;
        }
        List<String> todo = book.todoKeys();
        long qty = todo.contains(target) ? book.todoQuantity(target) : 1L;
        PlannerService.get().plan(target, Math.max(1L, qty), Solver.DEFAULT_MAX_NODES);
    }

    /** The book's first TODO, else its first favourite, else null. */
    static String firstTarget(PlanBook book) {
        List<String> todo = book.todoKeys();
        if (!todo.isEmpty()) {
            return todo.get(0);
        }
        return book.favourites().isEmpty() ? null : book.favourites().get(0);
    }
}
