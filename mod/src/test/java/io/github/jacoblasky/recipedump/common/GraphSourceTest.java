package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Where the planner looks for its graph, which is a decision rather than a detail.
 *
 * The failure this guards is quiet in both directions. A search that silently falls back
 * loads a DIFFERENT graph than the one the player named, which is the same class of bug as
 * `data/graph.json` standing in for an oracle -- the plan is perfectly plausible and answers
 * a question nobody asked. A search that reports nothing when it finds nothing sends a player
 * who has the file on disk looking at their mod list instead of at the path.
 */
public class GraphSourceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String saved;

    @Before
    public void rememberProperty() {
        saved = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
    }

    @After
    public void restoreProperty() {
        if (saved == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, saved);
        }
    }

    private File writeGraph(File dir, String name) throws IOException {
        File file = new File(dir, name);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write("{}".getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    private File configWithGraph() throws IOException {
        File config = folder.newFolder("config");
        File dir = new File(config, GraphSource.CONFIG_SUBDIR);
        assertTrue(dir.mkdirs());
        writeGraph(dir, GraphSource.FILE_NAME);
        return config;
    }

    @Test
    public void itFindsTheGraphUnderTheConfigDirectory() throws IOException {
        File config = configWithGraph();
        File found = GraphSource.locate(config);
        assertEquals(new File(new File(config, GraphSource.CONFIG_SUBDIR),
                              GraphSource.FILE_NAME), found);
    }

    @Test
    public void aMissingFileIsNullRatherThanAnException() throws IOException {
        assertNull(GraphSource.locate(folder.newFolder("empty")));
    }

    @Test
    public void aNullConfigDirectoryIsSurvivable() {
        // The headless harness never runs preInit, so there is no config directory to hand
        // over. It supplies the property instead, and this must not throw on the way there.
        assertNull(GraphSource.locate(null));
        assertTrue(GraphSource.candidates(null).isEmpty());
    }

    @Test
    public void thePropertyWins() throws IOException {
        File config = configWithGraph();
        File elsewhere = writeGraph(folder.newFolder("elsewhere"), "other.json");
        System.setProperty(GraphSource.PROPERTY, elsewhere.getPath());
        assertEquals(elsewhere, GraphSource.locate(config));
    }

    @Test
    public void thePropertyIsTakenALONEEvenWhenItNamesNothing() throws IOException {
        // THE POINT OF THE WHOLE CLASS. Falling back to the config directory here would load
        // a graph the player did not ask for, after they told us exactly which one they
        // wanted and made a typo. A null answer sends them to the message; a fallback sends
        // them to a wrong plan.
        File config = configWithGraph();
        System.setProperty(GraphSource.PROPERTY, new File(folder.getRoot(), "nope.json")
                .getPath());
        assertNull(GraphSource.locate(config));
        List<File> candidates = GraphSource.candidates(config);
        assertEquals("the config directory must not be tried once a property is set",
                     1, candidates.size());
    }

    @Test
    public void aBlankPropertyIsNotAPath() throws IOException {
        // An empty -D is how a launcher renders an unset variable, and treating "" as a path
        // would turn "no override" into "look in the working directory".
        File config = configWithGraph();
        System.setProperty(GraphSource.PROPERTY, "   ");
        assertEquals(new File(new File(config, GraphSource.CONFIG_SUBDIR),
                              GraphSource.FILE_NAME),
                     GraphSource.locate(config));
    }

    @Test
    public void theSearchDescribesEveryPathItWouldTry() throws IOException {
        File config = folder.newFolder("config");
        String description = GraphSource.describeSearch(config);
        assertTrue(description, description.contains(GraphSource.CONFIG_SUBDIR));
        assertTrue(description, description.contains(GraphSource.FILE_NAME));
    }

    @Test
    public void theSearchSaysSoWhenThereIsNowhereToLook() {
        String description = GraphSource.describeSearch(null);
        assertTrue(description, description.contains(GraphSource.PROPERTY));
    }

    @Test
    public void theDescriptionListsTheSamePathsTheSearchUses() throws IOException {
        // A second copy of the search order in the message is free to disagree with the one
        // that ran, and the reader has no way to tell which is lying.
        File config = folder.newFolder("config");
        String description = GraphSource.describeSearch(config);
        for (File candidate : GraphSource.candidates(config)) {
            assertTrue(description + " omits " + candidate,
                       description.contains(candidate.getPath()));
        }
    }
}
