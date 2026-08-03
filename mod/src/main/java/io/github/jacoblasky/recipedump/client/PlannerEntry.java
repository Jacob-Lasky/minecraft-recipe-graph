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
        PlannerScreen.openPlanner(book);
        planWhenStockIsRead(book, firstTarget(book));
    }

    /**
     * The same, on a target the player named rather than the book's first entry.
     *
     * WHAT THE `=` KEYBIND CALLS, through {@link PlanTarget}. It goes through here rather than
     * straight to `PlannerService` so that the keybind and the calculator item reach the window
     * by one route: the quantity rule, the already-answered check and the wait for a stock read
     * are the same, and a second door would be a second place for them to diverge.
     */
    public static void openOn(PlanBook book, String key) {
        PlannerScreen.openPlanner(book);
        planWhenStockIsRead(book, key);
    }

    /**
     * Plan `target`, but not until the player's ME network has been read.
     *
     * WHY THE PLAN WAITS. A plan priced before the stock reply lands is the "you own nothing"
     * plan `ScenarioSource` exists to prevent, and re-solving when the reply arrives would pay
     * for a second cost table -- the dearest part of a plan -- on every open. `PlannerStock`
     * runs this immediately when the held read is still fresh or when there is no server to
     * ask, so the harness and a disconnected client behave exactly as before.
     *
     * THE ASK IS SKIPPED WHEN THERE IS NOTHING TO PLAN. Reading the network is a megabyte on
     * the wire; sending for it to then discover the graph is still loading is a round trip for
     * an answer nobody will use. {@link #startPlan} re-checks both, because it is also called
     * directly by the screenshot harness.
     */
    private static void planWhenStockIsRead(final PlanBook book, final String target) {
        if (target == null || GraphService.get().state() != GraphService.State.READY) {
            return;
        }
        PlannerStock.planWhenRead(new Runnable() {
            @Override
            public void run() {
                startPlan(book, target);
            }
        });
    }

    /**
     * Start planning the book's first entry, if there is one and nothing is already running.
     *
     * AFTER the window opens, never before: opening is instant and planning is not, so the
     * first frame shows the state and a later use of the item shows the tree. A player who
     * right-clicks and waits several seconds for a window has been given a slow tool, which
     * is the same argument that put the graph read on its own thread.
     *
     * THE FIRST TODO IS THE TARGET when nobody named one. The JEI keybind names one -- see
     * {@link #openOn} -- and reaches {@link #startPlan(PlanBook, String)} beneath this.
     */
    static void startPlan(PlanBook book) {
        startPlan(book, firstTarget(book));
    }

    /**
     * The same for a named target. Does nothing when there is no graph, no target, or the
     * service is already holding this exact answer.
     */
    static void startPlan(PlanBook book, String target) {
        GraphService graphs = GraphService.get();
        if (graphs.state() != GraphService.State.READY || target == null) {
            return;
        }
        long qty = quantityFor(book, target);
        PlannerService planner = PlannerService.get();
        if (alreadyAnswered(planner, target, qty)) {
            return;
        }
        planner.plan(target, qty, Solver.DEFAULT_MAX_NODES);
    }

    /**
     * How many to plan: what the TODO asks for, else one.
     *
     * ONE RULE FOR BOTH ENTRY POINTS. Pressing the keybind over an item already on the TODO
     * asks the same question the calculator would have asked about it, rather than a plan for
     * a single one beside a TODO saying 3,000 -- two answers to one question, differing by
     * which control the player happened to use.
     */
    static long quantityFor(PlanBook book, String target) {
        List<String> todo = book.todoKeys();
        return Math.max(1L, todo.contains(target) ? book.todoQuantity(target) : 1L);
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
