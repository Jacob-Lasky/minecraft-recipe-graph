package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Put a graph document on disk and wait for `GraphService` to finish reading it.
 *
 * ONE COPY, FOR THE REASON `GraphDocuments` IS PUBLIC. That class's header says a hand-copy of
 * the document appeared in this package "duplicated only because packages differ", and calls
 * three spellings of one schema three chances to update two of them. The fifteen lines that
 * WRITE the document and poll the service are the same hazard one level out: they encode the
 * property name, the load trigger and a timeout, and a second copy is a second place for the
 * timeout to be wrong.
 *
 * A TIMEOUT AND NOT A `while (true)`. The load runs on `GraphService`'s own thread, so a
 * document it rejects leaves the state at FAILED forever and a bare poll hangs the suite with
 * no output. Failing at thirty seconds with the service's own description is what turns that
 * into a readable test failure.
 */
final class TestGraphs {

    private static final long TIMEOUT_MILLIS = 30_000L;

    private TestGraphs() {
    }

    /**
     * @param dir      a temporary folder to write into
     * @param document a `GraphDocuments` string
     */
    static void load(File dir, String document) throws Exception {
        File file = new File(dir, "graph.json");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(document.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        System.setProperty(GraphSource.PROPERTY, file.getPath());
        GraphService.get().startLoad(null);
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (GraphService.get().state() != GraphService.State.READY) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("graph never loaded: " + GraphService.get().describe());
            }
            Thread.sleep(5L);
        }
    }
}
