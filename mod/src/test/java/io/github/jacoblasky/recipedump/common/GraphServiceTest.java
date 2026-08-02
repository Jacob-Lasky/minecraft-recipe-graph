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
}
