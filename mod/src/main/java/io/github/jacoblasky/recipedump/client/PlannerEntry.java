package io.github.jacoblasky.recipedump.client;

import java.util.List;

import io.github.jacoblasky.recipedump.client.planner.PlanJson;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
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

    /**
     * The plan to draw. Only meaningful when {@link #stateFor} returned null.
     *
     * THE JSON ROUND TRIP IS THE SEAM, NOT A SHORTCUT. `PlanJson.readResult` parses what
     * `plan.PlanJson.toJson` writes, and that is the exact text `PlanFixtureTest` compares
     * against `tests/fixtures/plan/*.json` -- so the panel draws from bytes provably
     * identical to the ones proven to match Python, and there is no fourth place for the plan
     * shape to live. An adapter mapping `PlanResult` to `PlanView` field by field fails by
     * dropping a field and rendering a blank row rather than by erroring.
     *
     * #158 CONVERGED `PlanNode` AND THIS ROUND TRIP SURVIVED IT, which is worth recording
     * because the plan was that it would not. One `PlanNode` removed the duplicated node
     * class; `PlanView` still has no constructor over a `PlanResult`, so `readResult` remains
     * the only door and the serialise/parse is still here. Removing it needs a
     * `PlanView.of(PlanResult)`, and the property to preserve when someone adds one is the
     * one the round trip is standing in for: the object the golden gate tested must be the
     * object the panel draws, with no second representation in between. Do NOT replace this
     * with a field-by-field adapter, which is the third thing and fails by rendering a blank
     * row.
     */
    public static PlanView planFor(PlannerService planner) {
        return PlanJson.readResult(planner.resultJson());
    }

    /**
     * Open the planner and, if there is nothing to draw yet, set it going.
     *
     * WHICH OF THE FIVE THINGS TO SHOW IS DECIDED BY THE WINDOW, not here, because it is
     * decided AGAIN every time the window rebuilds itself -- see `PlannerScreen`. Choosing
     * once at open time is what made a plan arriving a second later invisible.
     */
    public static void open(PlanBook book) {
        openFor(book, firstTarget(book));
    }

    /**
     * Open the planner on a target the player just NAMED, rather than on the book's first.
     *
     * THIS IS WHAT THE JEI KEYBIND NEEDS AND {@link #open} CANNOT GIVE IT. `open` plans
     * `firstTarget`, which its own note calls "a placeholder rule rather than a design"; a
     * player who points at an item and presses the key has said which item, and planning a
     * different one because it happens to sit earlier in their TODO list is not a defensible
     * reading of that gesture. `null` falls back to the book's first, so `open` is this with
     * nothing named.
     */
    public static void openFor(PlanBook book, String target) {
        PlannerScreen.openPlanner(book);
        startPlan(book, target);
    }

    /**
     * Start planning `named`, or the book's first entry, if nothing is already running.
     *
     * ONE METHOD, NOT TWO. There was a no-target overload for a while and its only callers
     * were tests -- the exact shape this codebase keeps paying for, a seam whose halves are
     * both exercised and which production reaches by a different route. `null` means "nobody
     * named one", which is what `open` passes.
     *
     * AFTER the window opens, never before: opening is instant and planning is not, so the
     * first frame shows the state and a later use of the item shows the tree. A player who
     * right-clicks and waits several seconds for a window has been given a slow tool, which
     * is the same argument that put the graph read on its own thread.
     *
     * THE FIRST TODO IS THE TARGET WHEN NOBODY NAMED ONE, and that remains a placeholder
     * rather than a design -- it is what opening the planner cold has to guess. A caller who
     * knows better says so through {@link #openFor}, which is how the JEI keybind plans the
     * item the player was pointing at.
     */
    static void startPlan(PlanBook book, String named) {
        GraphService graphs = GraphService.get();
        if (graphs.state() != GraphService.State.READY) {
            return;
        }
        String target = named != null ? named : firstTarget(book);
        if (target == null) {
            return;
        }
        List<String> todo = book.todoKeys();
        long qty = Math.max(1L, todo.contains(target) ? book.todoQuantity(target) : 1L);
        PlannerService planner = PlannerService.get();
        if (alreadyAnswered(planner, target, qty)) {
            return;
        }
        planner.plan(target, qty, Solver.DEFAULT_MAX_NODES);
    }

    /**
     * True when the service is already holding the answer to this exact question.
     *
     * NOT AN OPTIMISATION -- it stops the window flickering. Opening the planner used to be
     * "draw once, then start a solve", which was harmless while the window never redrew:
     * the second solve of an already-solved target finished behind a picture nobody was
     * updating. Now that the window follows the service, the same second solve throws the
     * tree away, shows "planning 1x minecraft:hopper" for as long as it takes, and puts back
     * a plan identical to the one it replaced. Caught by a screenshot of the harness, which
     * plans and then opens and so hits it every time.
     *
     * KEYED ON THE TARGET AND THE QUANTITY, not on "a plan exists". Changing the TODO and
     * reopening is a different question and must be asked again; the pins path does not come
     * through here at all, because {@link PlannerService#replan} is explicit about wanting
     * the same question re-answered.
     */
    static boolean alreadyAnswered(PlannerService planner, String target, long qty) {
        return planner.state() == PlannerService.State.DONE
                && target.equals(planner.targetKey())
                && qty == planner.targetQty();
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
