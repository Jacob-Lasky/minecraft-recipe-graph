package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The off-thread graph load, and the four things it can be doing.
 *
 * WHY EVERY OUTCOME IS TESTED AND NOT JUST THE HAPPY ONE. Three of the four states are how a
 * player finds out something is wrong, and each of them replaces a worse experience: MISSING
 * replaces an empty planner that looks like data loss, FAILED replaces a progress bar that
 * never finishes, and LOADING replaces five seconds of frozen client. A load that threw on
 * its own thread would leave the state on LOADING for ever with nothing said, which is the
 * worst of the three -- so the failure paths get more attention here than the success.
 *
 * The measured load is 5.47 s for the real 115.8 MB graph, which is why any of this exists;
 * these use a few hundred bytes and finish immediately.
 */
public class GraphServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String saved;

    @Before
    public void isolate() {
        saved = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
        GraphService.get().reset();
    }

    @After
    public void restore() {
        if (saved == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, saved);
        }
        GraphService.get().reset();
    }

    private File write(String name, String body) throws IOException {
        File file = new File(folder.getRoot(), name);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    /** Waits for the loader to leave LOADING. Fails rather than hanging the suite for ever. */
    private GraphService.State settle() throws InterruptedException {
        GraphService service = GraphService.get();
        long deadline = System.currentTimeMillis() + 30_000L;
        while (service.state() == GraphService.State.LOADING
                || service.state() == GraphService.State.IDLE) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the loader never left " + service.state()
                        + "; an unhandled throw on the daemon thread looks exactly like this");
            }
            Thread.sleep(5L);
        }
        return service.state();
    }

    @Test
    public void withNoFileItIsMissingAndSaysWhereItLooked() {
        GraphService service = GraphService.get();
        service.startLoad(folder.getRoot());
        assertEquals(GraphService.State.MISSING, service.state());
        assertNull(service.graph());
        assertTrue(service.describe(), service.describe().contains("looked in"));
    }

    @Test
    public void aRealGraphLoadsAndReports() throws Exception {
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        assertNotNull(service.graph());
        assertTrue(service.describe(), service.describe().startsWith("graph ready"));
    }

    @Test
    public void amalformedFileFailsRatherThanLoadingForEver() throws Exception {
        // GSON throws a RuntimeException here, not an IOException. Catching only IOException
        // would leave the daemon thread dead and the state on LOADING, which renders as a
        // progress bar that never moves and never explains itself.
        System.setProperty(GraphSource.PROPERTY,
                write("broken.json", "{\"recipes\": [ {\"oops\"").getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.FAILED, settle());
        assertNull(service.graph());
        assertTrue(service.describe(), service.describe().contains("could not read"));
    }

    @Test
    public void anEmptyFileFailsTheSameWay() throws Exception {
        System.setProperty(GraphSource.PROPERTY, write("empty.json", "").getPath());
        GraphService.get().startLoad(null);
        assertEquals(GraphService.State.FAILED, settle());
    }

    @Test
    public void aSecondStartDoesNotLoadTwice() throws Exception {
        // Two 45 MB graphs, one of which is immediately dropped, and 90 MB of transient
        // garbage. More than one caller wants a graph and none of them knows if it is first.
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        Object first = service.graph();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, service.state());
        assertTrue("a second startLoad must not replace the graph", first == service.graph());
    }

    @Test
    public void resetLetsItLoadAgain() throws Exception {
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        service.reset();
        assertEquals(GraphService.State.IDLE, service.state());
        assertNull(service.graph());
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
    }

    @Test
    public void progressIsUnavailableWhenNothingIsLoading() {
        assertTrue(GraphService.get().progress() < 0.0f);
    }

    @Test
    public void describeNeverNarratesTheAbsenceOfAProblem() {
        // The skill is explicit: state what is happening, never that nothing is going wrong.
        // An in-game "the game stays playable" was removed for exactly this, so the wording
        // is pinned rather than left to the next edit.
        GraphService service = GraphService.get();
        service.startLoad(folder.getRoot());
        String said = service.describe().toLowerCase();
        assertTrue(said, !said.contains("playable"));
        assertTrue(said, !said.contains("don't worry") && !said.contains("do not worry"));
    }

    // -- the two properties JeiBridge depends on -----------------------------------------

    @Test
    public void graphIdentityIsStableWhileTheGraphIsUnchanged() throws Exception {
        // THE ONE THAT COSTS PERFORMANCE SILENTLY IF IT BREAKS. `JeiBridge.indexOf` rebuilds
        // a key-to-ItemStack index over JEI's ~35,000 stacks whenever `graph != indexed`, so
        // an accessor returning a fresh wrapper or a defensive copy per call rebuilds it
        // every frame and the planner drops to single-digit fps. Nothing about a getter says
        // "must be identical", which is exactly why it is pinned here.
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        Object first = service.graph();
        for (int i = 0; i < 100; i++) {
            assertTrue("graph() must return the same object every call",
                       first == service.graph());
        }
    }

    @Test
    public void aReloadDoesHandBackADifferentGraph() throws Exception {
        // The other half: identity changing when the graph genuinely changes is CORRECT and
        // wanted, because that is how the index knows to rebuild. A cached instance surviving
        // a reload would leave JEI resolving keys against a graph nobody is planning with.
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        Object first = service.graph();
        service.reset();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        assertTrue("a reload must hand back a different object", first != service.graph());
    }

    @Test
    public void nothingLoadedIsNullRatherThanAnEmptyGraph() {
        // An empty `RecipeGraph` would answer `keyId(...) == -1` for everything, which is
        // indistinguishable from "loaded, item absent". graphmodel needs those apart: one
        // hides a menu entry, the other is a missing feature.
        GraphService.get().startLoad(folder.getRoot());
        assertEquals(GraphService.State.MISSING, GraphService.get().state());
        assertNull(GraphService.get().graph());
    }

    @Test
    public void aListenerIsToldBeforeTheGraphIsPublishedAsReady() throws Exception {
        // The index walk has to be off the render thread and has to happen once. Firing
        // after READY would race the first frame that reads the graph -- the frame this is
        // meant to keep fast.
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        final java.util.concurrent.atomic.AtomicReference<GraphService.State> seen =
                new java.util.concurrent.atomic.AtomicReference<GraphService.State>();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        GraphService service = GraphService.get();
        service.onLoad(new GraphService.Listener() {
            @Override
            public void graphLoaded(io.github.jacoblasky.recipedump.graph.RecipeGraph graph) {
                seen.set(service.state());
                calls.incrementAndGet();
            }
        });
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        assertEquals(1, calls.get());
        assertTrue("the listener must run before READY is published, saw " + seen.get(),
                   seen.get() != GraphService.State.READY);
        service.onLoad(null);
    }

    @Test
    public void aListenerRegisteredAfterTheLoadStillGetsTheGraph() throws Exception {
        // `ClientProxy.init` runs after `CommonProxy.preInit` starts the load, so on a fast
        // disk the graph can be READY before anything subscribes. A listener that silently
        // missed its one event is a stack index that never gets built.
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        service.onLoad(new GraphService.Listener() {
            @Override
            public void graphLoaded(io.github.jacoblasky.recipedump.graph.RecipeGraph graph) {
                calls.incrementAndGet();
            }
        });
        assertEquals(1, calls.get());
        service.onLoad(null);
    }

    @Test
    public void aThrowingListenerDoesNotLoseTheGraph() throws Exception {
        // The listener is client code doing something optional -- an index for a context
        // menu. Letting it turn a successful 5 s load into FAILED trades a missing menu
        // entry for no planner at all.
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        GraphService service = GraphService.get();
        service.onLoad(new GraphService.Listener() {
            @Override
            public void graphLoaded(io.github.jacoblasky.recipedump.graph.RecipeGraph graph) {
                throw new IllegalStateException("JEI is not here");
            }
        });
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        assertNotNull(service.graph());
        service.onLoad(null);
    }

    // -- the counter an open window watches (#201) ----------------------------------------

    /**
     * Every outcome of a load moves {@link GraphService#generation}, and none of them moves it
     * back.
     *
     * WHY THE COUNTER IS PART OF THIS CLASS'S CONTRACT AND NOT AN IMPLEMENTATION DETAIL.
     * `PlannerScreen.stamp` sums it with two other counters and rebuilds the planner window
     * when the sum changes, so a transition that fails to bump this is a window that never
     * notices the transition -- which is #201 exactly: a planner opened during the 5.47 s read
     * showed "reading graph.json" until the player closed it, because nothing it watched said
     * anything about the graph.
     *
     * EVERY OUTCOME, and FAILED is the one worth naming: MISSING is decided synchronously,
     * before any window can be opened against it, while a parse error arrives seconds later
     * under a window that is already showing a progress bar.
     */
    @Test
    public void everyLoadOutcomeMovesTheCounterAndNoneMovesItBack() throws Exception {
        GraphService service = GraphService.get();
        long start = service.generation();

        service.startLoad(folder.getRoot());
        assertEquals(GraphService.State.MISSING, service.state());
        long missing = service.generation();
        assertTrue("MISSING must be noticeable; " + start + " -> " + missing,
                   missing > start);

        service.reset();
        long afterReset = service.generation();
        assertTrue("reset must move FORWARD -- a counter that returns to a value an open"
                   + " window has already recorded as drawn leaves that window frozen on a"
                   + " graph that has been dropped; " + missing + " -> " + afterReset,
                   afterReset > missing);

        System.setProperty(GraphSource.PROPERTY,
                write("broken.json", "{\"dump_schema\":5,\"names\":{").getPath());
        service.startLoad(null);
        long loading = service.generation();
        assertTrue("starting a load is itself something new to say; " + afterReset + " -> "
                   + loading, loading > afterReset);
        assertEquals(GraphService.State.FAILED, settle());
        assertTrue("a load that stops five seconds in must move the window off a progress"
                   + " bar that is never going to finish; " + loading + " -> "
                   + service.generation(), service.generation() > loading);

        service.reset();
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        long beforeReady = service.generation();
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        assertTrue("READY is the transition #201 is about; " + beforeReady + " -> "
                   + service.generation(), service.generation() > beforeReady);
    }

    /**
     * The counter is bumped LAST, after the graph and after the state it describes.
     *
     * Same discipline as the `graph`-before-`state` note on the class, extended by one field:
     * a reader that sees a new number must see everything behind it. The listener runs before
     * READY is published, so it is the one place in the process that can observe the ordering
     * from the inside.
     */
    @Test
    public void theCounterIsBumpedAfterTheStateItDescribes() throws Exception {
        System.setProperty(GraphSource.PROPERTY,
                write("graph.json", GraphDocuments.TINY).getPath());
        final GraphService service = GraphService.get();
        final long before = service.generation();
        final java.util.concurrent.atomic.AtomicLong duringListener =
                new java.util.concurrent.atomic.AtomicLong(-1L);
        service.onLoad(new GraphService.Listener() {
            @Override
            public void graphLoaded(io.github.jacoblasky.recipedump.graph.RecipeGraph graph) {
                duringListener.set(service.generation());
            }
        });
        service.startLoad(null);
        assertEquals(GraphService.State.READY, settle());
        service.onLoad(null);

        assertEquals("the READY bump must not be visible to the listener, which runs before"
                     + " READY is published", service.generation() - 1L,
                     duringListener.get());
    }
}
