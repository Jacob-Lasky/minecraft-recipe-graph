package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.CostTable;
import io.github.jacoblasky.recipedump.plan.PlanResult;
import io.github.jacoblasky.recipedump.plan.Pins;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * The pin file, and the claim the recipe picker rests on: that a choice made in game reaches
 * the solver and changes the route.
 *
 * THE END-TO-END TEST IS THE POINT OF THIS FILE. Everything else here is a unit; the one that
 * matters is {@link #pinningARecipeMovesTheSolverOntoIt}, because every part of this feature
 * could be individually correct while the plan still ignored the pin -- a `liveScenario` that
 * forgot the field, a resolver keyed on the wrong map, a picker writing a file nothing reads.
 * That failure is invisible from the UI: the picker closes, the plan re-solves, and it comes
 * back with the recipe the player just rejected.
 */
public class PinStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File pins;

    @Before
    public void pointTheStoreAtATemporaryFile() throws IOException {
        pins = new File(folder.getRoot(), PinStore.FILE_NAME);
        System.setProperty(PinStore.PROPERTY, pins.getPath());
        PinStore.get().load(null);
    }

    @After
    public void restoreTheGlobals() {
        // BOTH, and the second is the one that bites. `PinStore.load` installs a reader on
        // `ScenarioSource.PINS`, which is a static enum shared by every test in this JVM --
        // a leftover reader pointing at a deleted temporary file makes an unrelated test see
        // a caveat it never set up.
        System.clearProperty(PinStore.PROPERTY);
        PinStore.get().reset();
        ScenarioSource.resetReaders();
    }

    // -- the file ----------------------------------------------------------------------

    @Test
    public void thePinFileIsTheOneThePythonSideAlreadyWrites() throws IOException {
        // A CROSS-LANGUAGE CONTRACT, asserted against the source rather than trusted to have
        // been copied. Both sides are live until #19 phase 6 retires the Python UI, and a
        // player who plans on the desktop and in game copies one file between them; a rename
        // on either side means the two halves of a live feature disagree about which file is
        // current, and nothing reports it -- the in-game planner simply has no pins.
        String defaults = new String(Files.readAllBytes(
                new File("../recipegraph/defaults.py").toPath()), StandardCharsets.UTF_8);
        String marker = "DEFAULT_PINS = \"";
        int at = defaults.indexOf(marker);
        assertTrue("recipegraph/defaults.py no longer declares DEFAULT_PINS", at >= 0);
        String path = defaults.substring(at + marker.length(), defaults.indexOf('"',
                at + marker.length()));
        assertEquals("the mod and the Python tool must name the same file",
                     new File(path).getName(), PinStore.FILE_NAME);
    }

    @Test
    public void aPinSurvivesBeingWrittenAndReadBack() {
        assertTrue(PinStore.get().pin("mod:plate", new Pins.Pin("abc123", "cat", "a label")));
        assertTrue("the store writes immediately rather than at shutdown", pins.isFile());

        PinStore.get().reset();
        System.setProperty(PinStore.PROPERTY, pins.getPath());
        PinStore.get().load(null);
        assertEquals(1, PinStore.get().pins().size());
        assertEquals("abc123", PinStore.get().pins().get("mod:plate").fingerprint);
        assertEquals("", PinStore.get().problem());
    }

    @Test
    public void unpinningRemovesItFromTheFileAndNotOnlyFromMemory() {
        PinStore.get().pin("mod:plate", new Pins.Pin("abc123", "cat", "a label"));
        PinStore.get().pin("mod:gear", new Pins.Pin("def456", "cat", "another"));
        assertTrue(PinStore.get().unpin("mod:plate"));

        assertEquals(1, Pins.load(pins).size());
        assertTrue(Pins.load(pins).containsKey("mod:gear"));
    }

    @Test
    public void noPinFileIsNormalAndNotAFault() {
        assertFalse("nothing has been pinned, so nothing has been written", pins.exists());
        assertTrue(PinStore.get().pins().isEmpty());
        assertEquals("", PinStore.get().problem());
        assertTrue("an absent pin file must not put a caveat on the planner",
                   ScenarioSource.PINS.live());
    }

    /**
     * A corrupt pin file is REPORTED, not read as having no pins.
     *
     * `Pins.load` returns `{}` for a broken file on purpose, so a bad file cannot break
     * planning -- but `{}` is also what "you have chosen nothing" looks like, and once pins
     * steer the solver those two produce visibly different plans from identical UI. The
     * symptom of getting this wrong is a plan taking a route the player ruled out months ago,
     * with nothing on screen to explain it.
     */
    @Test
    public void aCorruptPinFileIsReportedRatherThanReadAsHavingNoPins() throws IOException {
        Files.write(pins.toPath(), "{ this is not json".getBytes(StandardCharsets.UTF_8));
        PinStore.get().load(null);

        assertTrue(PinStore.get().pins().isEmpty());
        assertFalse("a file that cannot be read must not read as an empty one",
                    PinStore.get().problem().isEmpty());
        assertFalse("the planner must show a caveat for it", ScenarioSource.PINS.live());
        List<String> notes = ScenarioSource.missingNotes();
        assertTrue("the caveat must name the file: " + notes,
                   notes.contains(PinStore.get().problem()));
    }

    @Test
    public void anEntryWithNoFingerprintIsSkippedAndCountedRatherThanDropped() throws IOException {
        // A fingerprint is the only required field -- exactly as in `pins.load` -- but a pin
        // silently vanishing is the same failure one level down.
        Files.write(pins.toPath(), ("{\"pins\": {\"mod:plate\": {\"category\": \"cat\"},"
                + "\"mod:gear\": {\"fingerprint\": \"ok\"}}}").getBytes(StandardCharsets.UTF_8));
        PinStore.get().load(null);

        assertEquals(1, PinStore.get().pins().size());
        assertTrue(PinStore.get().problem(),
                   PinStore.get().problem().startsWith("1 recipe choice(s)"));
    }

    @Test
    public void withNowhereToSaveThePinStillAppliesAndTheStoreSaysItWillNotLast() {
        System.clearProperty(PinStore.PROPERTY);
        PinStore.get().load(null);
        assertFalse("no config directory means no file",
                    PinStore.get().pin("mod:plate", new Pins.Pin("abc", "cat", "l")));
        assertTrue("and yet the choice applies to this session's plans",
                   PinStore.get().pins().containsKey("mod:plate"));
        assertFalse(PinStore.get().problem().isEmpty());
    }

    // -- the route to the solver -------------------------------------------------------

    @Test
    public void theScenarioTheGameSolvesCarriesThePins() {
        PinStore.get().pin("mod:plate", new Pins.Pin("abc123", "cat", "a label"));
        JsonObject scenario = PlannerService.liveScenario();
        JsonObject inPins = scenario.getAsJsonObject(ScenarioSource.PINS.field());
        assertTrue("the picker would be writing a file nothing reads",
                   inPins.has("mod:plate"));
        assertEquals("abc123",
                     inPins.getAsJsonObject("mod:plate").get("fingerprint").getAsString());
    }

    /**
     * The whole claim: a pin made through the picker's data changes the recipe the plan takes.
     *
     * NOT "THE PIN IS IN THE MAP" AND NOT "THE SOLVER HAS A `pinned` SETTER". Both of those
     * were already true before the picker existed, and neither would have caught a
     * `liveScenario` that omitted the field. This drives the real chain end to end --
     * `PinStore` -> `PlannerService.liveScenario` -> `ScenarioInputs.resolve` -> `Solver` --
     * and asserts on the recipe the plan actually took.
     *
     * IT PINS WHICHEVER RECIPE THE SOLVER DID NOT CHOOSE, rather than naming one. A test that
     * pinned a fixed candidate would pass without doing anything the day the ranking happens
     * to prefer that one, and would then be asserting that the default equals the default.
     */
    @Test
    public void pinningARecipeMovesTheSolverOntoIt() {
        RecipeGraph graph = twoWaysToMakeAPlate();
        String chosen = solveAndReportTheRecipe(graph);
        assertTrue("the fixture must produce a plan that took SOME recipe", chosen != null);

        int other = recipeOtherThan(graph, chosen);
        assertTrue("the fixture must offer a second recipe", other >= 0);
        String otherRid = graph.recipes().rid(other);
        assertNotEquals(chosen, otherRid);

        PinStore.get().pin("mod:plate", Pins.make(graph, other));
        assertEquals("the plan must take the pinned recipe", otherRid,
                     solveAndReportTheRecipe(graph));
    }

    @Test
    public void unpinningPutsTheSolverBackOnItsOwnChoice() {
        RecipeGraph graph = twoWaysToMakeAPlate();
        String chosen = solveAndReportTheRecipe(graph);
        int other = recipeOtherThan(graph, chosen);

        PinStore.get().pin("mod:plate", Pins.make(graph, other));
        assertEquals(graph.recipes().rid(other), solveAndReportTheRecipe(graph));
        PinStore.get().unpin("mod:plate");
        assertEquals("clicking the pinned row must really give the choice back",
                     chosen, solveAndReportTheRecipe(graph));
    }

    /**
     * Two routes to `mod:plate`, one obviously dearer, so the solver has a preference to
     * override. Which one it prefers is deliberately not asserted -- see the test above.
     */
    private static RecipeGraph twoWaysToMakeAPlate() {
        GraphBuilder b = new GraphBuilder();
        plateRecipe(b, "hei:minecraft.crafting:1", "minecraft.crafting", 1);
        plateRecipe(b, "hei:techreborn.rolling:2", "techreborn.rolling", 8);
        return b.build();
    }

    private static void plateRecipe(GraphBuilder b, String rid, String category, int ingots) {
        b.beginRecipe();
        b.beginSlot(ingots, "item");
        b.alternative(b.key("mod:ingot"));
        b.endSlot();
        b.output(b.key("mod:plate"), 1);
        b.endRecipe(rid, category, category, "test", false, false);
    }

    /** The rid the plan took for `mod:plate`, through the whole live path. */
    private static String solveAndReportTheRecipe(RecipeGraph graph) {
        ScenarioInputs.Resolved resolved =
                ScenarioInputs.resolve(graph, PlannerService.liveScenario());
        CostTable costs = ScenarioInputs.price(graph, resolved);
        PlanResult plan = ScenarioInputs.solverFor(graph, resolved, costs,
                                                   Solver.DEFAULT_MAX_NODES)
                                        .solve(graph.keyId("mod:plate"), 1);
        return plan.tree.recipe();
    }

    private static int recipeOtherThan(RecipeGraph graph, String rid) {
        IntArray producers = new IntArray();
        int count = graph.producers(graph.keyId("mod:plate"), producers);
        for (int i = 0; i < count; i++) {
            if (!graph.recipes().rid(producers.get(i)).equals(rid)) {
                return producers.get(i);
            }
        }
        return -1;
    }
}
