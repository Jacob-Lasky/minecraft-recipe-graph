package io.github.jacoblasky.recipedump.client.machines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphDocuments;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import io.github.jacoblasky.recipedump.common.MachinesService;
import io.github.jacoblasky.recipedump.common.ScenarioSource;

/**
 * Which window the machines screen opens, for every state the services can be in.
 *
 * THE SAME ARGUMENT AS `PlannerEntryTest` WITH ONE STATE MORE. Every not-yet case here is a
 * plausible window rather than an error, so nothing but this would catch one being shown for
 * another -- and this screen adds a case the planner does not have: the graph can be fully
 * READY while the machine verdicts are still resolving, because resolving them is a second
 * pass started on first open. A screen that said "nothing yet" during that pass would look
 * broken for exactly the seconds it is most likely to be looked at.
 */
public class MachinesEntryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String saved;

    @Before
    public void isolate() {
        saved = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
        GraphService.get().reset();
        MachinesService.get().reset();
        ScenarioSource.resetReaders();
    }

    @After
    public void restore() {
        if (saved == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, saved);
        }
        GraphService.get().reset();
        MachinesService.get().reset();
        ScenarioSource.resetReaders();
    }

    private void loadGraph() throws Exception {
        GraphDocuments.loadTinyGraphFrom(folder.getRoot());
    }

    private static PlannerState state() {
        return MachinesEntry.stateFor(GraphService.get(), MachinesService.get());
    }

    private static void awaitTable() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (MachinesService.get().state() == MachinesService.State.BUILDING
                || MachinesService.get().state() == MachinesService.State.IDLE) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the table never resolved: "
                        + MachinesService.get().describe());
            }
            Thread.sleep(5L);
        }
    }

    @Test
    public void withNoGraphFileTheScreenSaysWhereItLooked() {
        // The sentence a new player sees. It has to name the path, or the only actionable
        // thing about the failure is missing.
        System.setProperty(GraphSource.PROPERTY,
                           new File(folder.getRoot(), "absent.json").getPath());
        GraphService.get().startLoad(null);
        PlannerState state = state();
        assertNotNull(state);
        assertEquals(PlannerState.Kind.FAILED, state.kind());
        assertTrue(state.message(), state.message().contains("absent.json"));
    }

    @Test
    public void theGraphProblemOutranksTheMachinesOne() {
        // A player whose `graph.json` is missing has one thing to fix. Reporting "reading
        // machines failed" underneath would send them to the item instead of to the file.
        System.setProperty(GraphSource.PROPERTY,
                           new File(folder.getRoot(), "absent.json").getPath());
        GraphService.get().startLoad(null);
        MachinesService.get().ensure();
        assertEquals(PlannerState.Kind.FAILED, state().kind());
        assertTrue(state().message(), state().message().contains("absent.json"));
    }

    @Test
    public void anIdleServiceReadsAsLoadingRatherThanAsNothingYet() throws Exception {
        // IDLE means `ensure` has not run, which the screen does on open -- so this state is
        // real for a moment. "reading machines..." is true a moment early; "nothing yet" would
        // be false, and it is the sentence a player reads as "this tool has no answer".
        loadGraph();
        assertEquals(MachinesService.State.IDLE, MachinesService.get().state());
        PlannerState state = state();
        assertNotNull(state);
        assertEquals(PlannerState.Kind.LOADING, state.kind());
    }

    @Test
    public void onceTheTableIsBuiltThereIsNoStatePanelAtAll() throws Exception {
        loadGraph();
        MachinesService.get().ensure();
        awaitTable();
        assertEquals(MachinesService.State.DONE, MachinesService.get().state());
        assertNull("a usable table must draw the table, not a message", state());
        assertNotNull(MachinesService.get().table());
    }

    @Test
    public void aRebuiltTableIsNotAskedForTwiceWhileTheGraphIsUnchanged() throws Exception {
        // `ensure` is called on every window build, so it has to be cheap and idempotent. A
        // version that re-resolved would spend a worker thread per redraw, and the redraws
        // happen on every filter click.
        loadGraph();
        MachinesService.get().ensure();
        awaitTable();
        long generation = MachinesService.get().generation();
        MachinesService.get().ensure();
        assertEquals("ensure must not restart a resolve for the same graph",
                     generation, MachinesService.get().generation());
    }

    @Test
    public void rebuildAsksAgainBecauseThePlayerMayHaveBuiltTheMachine() throws Exception {
        // The inputs move while the player plays: a table held from before they placed the
        // machine they were looking up still says they cannot use it.
        loadGraph();
        MachinesService.get().ensure();
        awaitTable();
        long generation = MachinesService.get().generation();
        MachinesService.get().rebuild();
        awaitTable();
        assertTrue("rebuild must produce a new answer",
                   MachinesService.get().generation() > generation);
        assertEquals(MachinesService.State.DONE, MachinesService.get().state());
    }

    @Test
    public void aResolveThatThrowsIsReportedRatherThanLeavingAnEmptyTable() throws Exception {
        // THE FAILURE PATH, DRIVEN THROUGH A REAL SEAM. `PlannerService.liveScenario` asks
        // every `ScenarioSource` for its status and `liveDocument` does not catch, so a reader
        // that throws -- which is what a broken AE2 read looks like -- propagates into
        // `MachinesService.build`. That is the only way this state is reachable in production
        // and therefore the only honest way to test it.
        //
        // WITHOUT THE CATCH the worker thread dies with the service still BUILDING, and the
        // screen sits on "reading machines..." forever with nothing anywhere saying why.
        loadGraph();
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                throw new IllegalStateException("the grid exploded");
            }
        });
        MachinesService.get().rebuild();
        awaitTable();

        assertEquals(MachinesService.State.FAILED, MachinesService.get().state());
        assertNull("a failed resolve holds no table", MachinesService.get().table());
        PlannerState state = state();
        assertNotNull(state);
        assertEquals(PlannerState.Kind.FAILED, state.kind());
        // IT NAMES THE EXCEPTION. "reading machines failed" alone sends a player looking at
        // their graph file; the class and message are what say it was the stock read.
        assertTrue(state.message(), state.message().contains("IllegalStateException"));
        assertTrue(state.message(), state.message().contains("the grid exploded"));
    }

    @Test
    public void aFailedResolveRecoversOnceTheReaderStopsThrowing() throws Exception {
        // A FAILED service must not be a dead one. The reader that threw was a grid out of
        // range or a mod misbehaving for one call, and a player who walks back into range and
        // reopens the screen must get a table rather than the old error.
        loadGraph();
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                throw new IllegalStateException("the grid exploded");
            }
        });
        MachinesService.get().rebuild();
        awaitTable();
        assertEquals(MachinesService.State.FAILED, MachinesService.get().state());

        ScenarioSource.resetReaders();
        // `ensure` AND NOT `rebuild`, because `ensure` is what the screen calls on open and it
        // must not treat FAILED as "already answered". A version that returned early on any
        // non-BUILDING state would strand the player on the error permanently.
        MachinesService.get().ensure();
        awaitTable();
        assertEquals(MachinesService.State.DONE, MachinesService.get().state());
        assertNotNull(MachinesService.get().table());
        assertNull(state());
    }

    @Test
    public void ensureWithNoGraphLeavesTheServiceIdleSoItCanStartWhenOneArrives() {
        // THE REGRESSION FOR A BUG THIS BRANCH SHIPPED AND CAUGHT IN REVIEW, and it is #201's
        // shape: the window is routinely opened during the 5.47 s graph read, `open()` calls
        // `ensure` while there is no graph, and `ensure` used to answer that by setting FAILED
        // with the GRAPH's message. That looked harmless -- `MachinesEntry.stateFor` reports
        // the graph problem either way -- and it moved the service out of IDLE, which is the
        // state `MachinesScreen`'s tick hook watches to know the resolve was never started. The
        // graph then landed and nothing ever asked for a table.
        //
        // NO GRAPH HAS BEEN LOADED IN THIS TEST AT ALL, which is the case under test.
        assertEquals(MachinesService.State.IDLE, MachinesService.get().state());
        assertFalse("there is no graph, so there is no table", MachinesService.get().ensure());
        assertEquals("and the service must still be startable",
                     MachinesService.State.IDLE, MachinesService.get().state());
    }

    @Test
    public void aGraphArrivingAfterAFruitlessEnsureStillProducesATable() throws Exception {
        // The other half, in the order the player actually hits it: ask before the graph is
        // there, then let it arrive. Without the fix above this ends on FAILED forever.
        assertFalse(MachinesService.get().ensure());
        loadGraph();
        assertEquals(MachinesService.State.IDLE, MachinesService.get().state());
        assertTrue(MachinesService.get().ensure());
        awaitTable();
        assertEquals(MachinesService.State.DONE, MachinesService.get().state());
        assertNotNull(MachinesService.get().table());
        assertNull("and the screen stops showing a not-yet panel", state());
    }
}
