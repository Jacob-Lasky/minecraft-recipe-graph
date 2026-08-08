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

    /**
     * The target an open planner window is waiting for a GRAPH to arrive for, or null.
     *
     * NOT `PlannerStock.waiting`, AND THE TWO MUST NOT BE MERGED however alike they look. That
     * one is "waiting for this player's ME network to be read", which is a round trip to a
     * server that is guaranteed to answer, whose absence is checkable up front
     * (`PlannerStock.canAsk`), and which can only end in a snapshot or a stated refusal. This
     * one is "waiting for 116 MB of graph.json to be parsed off a daemon thread", which
     * answers nobody, has no request to be the reply to, and ends in READY, MISSING or FAILED.
     *
     * They also RESOLVE IN A FIXED ORDER, which is the part a shared holder would get wrong.
     * The stock ask is deliberately NOT sent until there is a graph -- see
     * {@link #planWhenStockIsRead}, "reading the network is a megabyte on the wire; sending
     * for it to then discover the graph is still loading is a round trip for an answer nobody
     * will use". One holder for both waits would have to encode that ordering as a state
     * machine, and the first thing it would get wrong is asking the server during the load.
     *
     * A NEWER QUESTION REPLACES AN OLDER ONE, which is the one rule it does share with
     * `PlannerStock.waiting`, and for the same reason: two held targets means the player used
     * the keybind and then the item (or the other way round) while the graph was still
     * loading, and planning the one they have moved on from would throw away the one they are
     * looking at.
     *
     * NOT CLEARED WHEN THE WINDOW CLOSES, deliberately. Only {@link #resumeWhenTheGraphLands}
     * consumes this and only an open window calls it, so a target held past a close is inert
     * -- and the next open re-derives its own target and overwrites this one before anything
     * reads it. A close hook would be a second place for the two to disagree.
     */
    private static String waitingTarget;

    /** The book {@link #waitingTarget} came from. Null exactly when that is. */
    private static PlanBook waitingBook;

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
     *
     * SKIPPED IS NOT DROPPED, WHICH IS #201. Until that issue this method RETURNED when there
     * was no graph yet, so a planner opened inside the 5.47 s read never planned anything and
     * never would: no plan means no plan-generation bump, and the window rebuilds on a
     * counter. {@link #holdUntilTheGraphLands} keeps the target instead, and
     * {@link #resumeWhenTheGraphLands} asks the question the moment there is something to ask
     * it against. DO NOT put the bare `return` back.
     *
     * PACKAGE-VISIBLE SO A TEST CAN ENTER BY THE DOOR {@link #open} USES. Its no-graph path
     * reaches no `Minecraft` at all -- {@link #holdUntilTheGraphLands} answers null and
     * {@link #askTheServerAndPlan} returns on it -- so the half of #201 that starts the wait is
     * exercisable headlessly through the production call rather than through a method written
     * for the test.
     */
    static void planWhenStockIsRead(PlanBook book, String target) {
        askTheServerAndPlan(book, holdUntilTheGraphLands(book, target, graphIsReady()));
    }

    /**
     * Record `target` as the thing to plan when a graph arrives, or hand it straight back.
     *
     * THE HALF OF #201 THAT IS NOT THE COUNTER. Bumping `GraphService.generation` alone makes
     * the window redraw when the graph lands, and what it redraws as is "nothing planned yet"
     * -- because the plan that would have been started was dropped on the floor five seconds
     * earlier by the `state() != READY` return this replaces. Recovering means REPLAYING the
     * target the window was opened for, so the target has to be kept rather than recomputed:
     * {@link #openOn} is handed one by the `=` keybind that is nowhere in the book.
     *
     * SPLIT FROM THE ASK SO THE DECISION CAN BE EXERCISED WITHOUT A CLIENT, exactly as
     * {@link PlannerStock#hold} is split from {@code PlannerStock.planWhenRead} and for the
     * same measured reason: everything past {@link #askTheServerAndPlan} reaches `Minecraft`,
     * which a JUnit classpath cannot load at all.
     *
     * @param graphReady whether there is a graph to plan against right now.
     * @return the target to plan immediately, or null when it is now waiting for a graph or
     *         when there was nothing to plan in the first place.
     */
    static String holdUntilTheGraphLands(PlanBook book, String target, boolean graphReady) {
        waitingBook = null;
        waitingTarget = null;
        if (target == null || graphReady) {
            return target;
        }
        waitingBook = book;
        waitingTarget = target;
        return null;
    }

    /**
     * A graph landed while a planner window was open: ask the question it was opened for.
     *
     * CALLED FROM `PlannerScreen.PlannerWindow.onUpdate`, which is ModularUI's per-client-tick
     * hook, and NOT from {@link GraphService#onLoad}. That listener runs on the LOADER thread
     * and it runs BEFORE READY is published -- both of which are deliberate and documented
     * there -- so a plan started from it would be started off the client thread against a
     * service that still reports LOADING, which is to say it would not be started at all.
     * Polling a counter from the tick is the shape this window already uses for the plan
     * generation, for the identical thread-safety reason; see `PlannerScreen`.
     *
     * BEFORE THE STAMP IS READ, so the rebuild this tick draws "planning 4x mod:plate" rather
     * than flashing "nothing planned yet" for one frame on its way there.
     *
     * IT REACHES NO `Minecraft` ON THE TICK THAT IS NOT A RECOVERY, which is every tick but
     * one: the two cheap reads come first and the connection check is only made once there is
     * genuinely a target to release. That ordering is load-bearing rather than tidy -- see
     * {@code PlannerStock.canAsk}, which cannot even be entered outside a client.
     *
     * @return whether a wait ended here. False is the overwhelmingly common case -- every tick
     *         of every window that is not waiting for anything.
     */
    public static boolean resumeWhenTheGraphLands() {
        if (waitingTarget == null || !graphIsReady()) {
            return false;
        }
        return resumeWhenTheGraphLands(true, PlannerStock.canAsk());
    }

    /**
     * The same, told the two facts it needs rather than reading them.
     *
     * SPLIT SO THE WHOLE RECOVERY CAN BE EXERCISED AND NOT MERELY DECIDED. Every other split in
     * this family stops at a boolean, and for #201 that is not enough: the claim worth pinning
     * is "a plan for the target the window was opened for finished", which needs the released
     * target to travel all the way to {@link #startPlan}. The one line in the way is
     * `PlannerStock.canAsk`, measured to be unenterable on a JUnit classpath -- so it is passed
     * in, and what a test then exercises is the production path with one boolean supplied
     * instead of read.
     *
     * WHAT REMAINS UNTESTED IS STILL EXACTLY ONE LINE, and it is stated rather than implied:
     * that `canAsk` is fed by the real connection. `PlannerStockTest` makes the same admission
     * about the same line.
     *
     * @param graphReady whether there is a graph to plan against.
     * @param canAsk whether there is a server to ask for the player's stock.
     */
    static boolean resumeWhenTheGraphLands(boolean graphReady, boolean canAsk) {
        if (waitingTarget == null || !graphReady) {
            return false;
        }
        PlanBook book = waitingBook;
        String target = waitingTarget;
        waitingBook = null;
        waitingTarget = null;
        PlannerStock.planWhenRead(planTask(book, target), canAsk);
        return true;
    }

    /**
     * EVERYTHING BELOW THIS LINE REACHES `Minecraft`, through {@code PlannerStock.canAsk}.
     * Above it is the decision, which {@code PlannerEntryTest} walks; see
     * {@link #holdUntilTheGraphLands}.
     */
    private static void askTheServerAndPlan(PlanBook book, String target) {
        if (target == null) {
            return;
        }
        PlannerStock.planWhenRead(planTask(book, target));
    }

    /**
     * The plan itself, as the continuation the stock wait holds.
     *
     * ONE FACTORY FOR BOTH DOORS -- the open path and the recovery -- so the two cannot drift
     * into asking subtly different questions about the same target, which is the failure
     * {@link #openOn}'s own note is about one level up.
     */
    private static Runnable planTask(final PlanBook book, final String target) {
        return new Runnable() {
            @Override
            public void run() {
                startPlan(book, target);
            }
        };
    }

    private static boolean graphIsReady() {
        return GraphService.get().state() == GraphService.State.READY;
    }

    /** Test seam: drop the held target, so one test cannot leak into the next. */
    static void forgetTheWaitingTarget() {
        waitingBook = null;
        waitingTarget = null;
    }

    /** Test seam: what is being held, or null. */
    static String waitingTarget() {
        return waitingTarget;
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
