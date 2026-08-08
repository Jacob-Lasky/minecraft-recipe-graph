package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphDocuments;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * Which window the calculator item opens, for every state the services can be in.
 *
 * WHY THIS IS WORTH ITS OWN TEST. The graph takes 5.47 s to read, so the window is routinely
 * opened before a plan exists, and the not-yet cases are NOT interchangeable: "still loading"
 * is a wait, "no graph.json, looked in ..." is a thing to go and fix, and "nothing planned
 * yet" is neither. Showing one for another is how a player concludes the mod is broken and
 * files a bug about a file they never installed -- and every one of those is a plausible
 * window rather than an error, so nothing else would catch it.
 *
 * `PlannerEntry.stateFor` is a pure function of two service states precisely so this can be
 * walked without a client. The proxy method it backs takes an `EntityPlayer`.
 */
public class PlannerEntryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String saved;

    @Before
    public void isolate() {
        saved = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
        GraphService.get().reset();
        PlannerService.get().reset();
    }

    @After
    public void restore() {
        if (saved == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, saved);
        }
        GraphService.get().reset();
        PlannerService.get().reset();
    }

    private void loadGraph() throws Exception {
        GraphDocuments.loadTinyGraphFrom(folder.getRoot());
    }

    private static PlannerState state() {
        return PlannerEntry.stateFor(GraphService.get(), PlannerService.get());
    }

    /**
     * Reopening the planner does not re-solve a question the service has already answered.
     *
     * NOT AN OPTIMISATION -- see {@link PlannerEntry#alreadyAnswered}. The window follows
     * the service now, so a redundant solve visibly throws away the tree, shows "planning
     * ..." and puts back an identical plan. Found by a harness screenshot, which plans and
     * then opens and so hits this every single time.
     */
    @Test
    public void reopeningDoesNotReSolveTheSameQuestion() throws Exception {
        loadGraph();
        PlanBook book = new PlanBook();
        book.setTodo("mod:plate", 1L);
        PlannerEntry.startPlan(book);
        awaitPlan();

        long generation = PlannerService.get().generation();
        PlannerEntry.startPlan(book);
        assertEquals("the same question must not be asked again",
                     generation, PlannerService.get().generation());
        assertEquals(PlannerService.State.DONE, PlannerService.get().state());
    }

    @Test
    public void aDifferentQuantityIsADifferentQuestionAndIsAskedAgain() throws Exception {
        // The guard keys on the target AND the amount, because 1 hopper and 64 hoppers are
        // different plans. Keying on "a plan exists" would pin the window to the first one.
        loadGraph();
        PlanBook book = new PlanBook();
        book.setTodo("mod:plate", 1L);
        PlannerEntry.startPlan(book);
        awaitPlan();

        long generation = PlannerService.get().generation();
        PlanBook more = new PlanBook();
        more.setTodo("mod:plate", 64L);
        PlannerEntry.startPlan(more);
        awaitPlan();
        assertTrue("a new quantity must produce a new answer",
                   PlannerService.get().generation() > generation);
        assertEquals(64L, PlannerService.get().targetQty());
    }

    private static void awaitPlan() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (PlannerService.get().state() == PlannerService.State.PLANNING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the plan never finished");
            }
            Thread.sleep(5L);
        }
    }

    @Test
    public void noGraphIsAFailureThatNamesThePath() {
        GraphService.get().startLoad(folder.getRoot());
        PlannerState state = state();
        assertEquals(PlannerState.Kind.FAILED, state.kind());
        assertTrue(state.message(), state.message().contains("looked in"));
    }

    @Test
    public void aGraphProblemOutranksAPlanProblem() {
        // A player whose graph.json is missing has ONE thing to fix. Reporting "planning
        // failed" underneath would send them to the item instead of to the file.
        GraphService.get().startLoad(folder.getRoot());
        PlannerService.get().plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES);
        assertEquals(PlannerState.Kind.FAILED, PlannerEntry.stateFor(
                GraphService.get(), PlannerService.get()).kind());
        assertTrue(state().message(), state().message().contains("no graph.json"));
    }

    @Test
    public void beforeAnyLoadItReadsAsLoadingRatherThanFailed() {
        // IDLE means preInit has not run yet, not that anything is wrong. Rendering that in
        // red would be an error message for a mod that is merely still starting.
        assertEquals(PlannerState.Kind.LOADING, state().kind());
    }

    @Test
    public void aLoadedGraphWithNoPlanIsIdleAndNotAFailure() throws Exception {
        loadGraph();
        assertEquals(PlannerState.Kind.IDLE, state().kind());
    }

    @Test
    public void aFinishedPlanIsDrawnRatherThanDescribed() throws Exception {
        loadGraph();
        PlannerService.get().plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES);
        awaitPlan();
        // null is "draw the tree", which is the one case that is not a PlannerState.
        assertNull(state());
    }

    @Test
    public void anUnknownTargetIsAFailureThatNamesTheKey() throws Exception {
        loadGraph();
        PlannerService.get().plan("mod:nope", 1L, Solver.DEFAULT_MAX_NODES);
        PlannerState state = state();
        assertEquals(PlannerState.Kind.FAILED, state.kind());
        assertTrue(state.message(), state.message().contains("mod:nope"));
    }

    @Test
    public void thefourNotYetStatesSayFourDifferentThings() throws Exception {
        // The whole point. If two of these read the same, a player cannot tell a wait from a
        // thing to fix, and the state machine may as well not exist.
        GraphService.get().startLoad(folder.getRoot());
        String missing = state().message();
        GraphService.get().reset();
        String idleGraph = state().message();
        loadGraph();
        String noPlan = state().message();
        PlannerService.get().plan("mod:nope", 1L, Solver.DEFAULT_MAX_NODES);
        String badTarget = state().message();

        assertNotEquals(missing, idleGraph);
        assertNotEquals(missing, noPlan);
        assertNotEquals(idleGraph, noPlan);
        assertNotEquals(noPlan, badTarget);
        assertNotEquals(missing, badTarget);
    }

    @Test
    public void theTargetIsTheFirstTodoThenTheFirstFavourite() {
        PlanBook book = new PlanBook();
        assertNull(PlannerEntry.firstTarget(book));
        book.addFavourite("mod:fav");
        assertEquals("mod:fav", PlannerEntry.firstTarget(book));
        book.setTodo("mod:todo", 4L);
        assertEquals("a TODO outranks a favourite", "mod:todo",
                     PlannerEntry.firstTarget(book));
    }

    @Test
    public void theKeybindAndTheCalculatorAgreeOnHowManyToPlan() {
        // ONE RULE FOR BOTH DOORS. Pressing `=` over an item already on the TODO must ask the
        // same question the calculator would have asked about it -- otherwise the player gets
        // a plan for one beside a TODO saying 3,000, and which answer they get depends on
        // which control they happened to use.
        PlanBook book = new PlanBook();
        book.setTodo("mod:plate", 3000L);
        assertEquals(3000L, PlannerEntry.quantityFor(book, "mod:plate"));
        assertEquals("an item that is not on the TODO is planned singly",
                     1L, PlannerEntry.quantityFor(book, "mod:gear"));
    }

    @Test
    public void aFavouriteIsPlannedSinglyBecauseItNamesNoQuantity() {
        // A favourite is "I make this often", not "I want this many", so there is nothing to
        // read a count from and one is the only honest answer.
        PlanBook book = new PlanBook();
        book.addFavourite("mod:fav");
        assertEquals("mod:fav", PlannerEntry.firstTarget(book));
        assertEquals(1L, PlannerEntry.quantityFor(book, "mod:fav"));
    }

    @Test
    public void nothingIsPlannedUntilTheGraphIsReady() {
        // `plan` would refuse anyway and set FAILED, which would then be rendered in red
        // over a graph that is merely still loading -- a wait reported as an error.
        PlanBook book = new PlanBook();
        book.setTodo("mod:plate", 1L);
        PlannerEntry.startPlan(book);
        assertEquals(PlannerService.State.IDLE, PlannerService.get().state());
    }
}
