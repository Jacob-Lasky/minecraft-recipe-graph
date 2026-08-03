package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import org.junit.Test;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Pin identity, held to the Python original digest for digest.
 *
 * EVERY EXPECTED FINGERPRINT BELOW CAME OUT OF `recipegraph.pins.fingerprint`, not out of
 * this implementation. That direction is the whole point: a pin file is written by whichever
 * side is running and read by both, so a Java fingerprint that merely agrees with itself
 * would lapse every pin the player set from the web UI while reporting it as the pack having
 * changed the recipe -- the exact failure `pins.py` exists to prevent, arriving through the
 * port. Regenerate with the snippet in {@link #regenerate()} if a value here ever has to
 * move, and change Python first.
 */
public class PinsTest {

    /**
     * How the expected values below were produced. Kept as runnable text rather than prose
     * so the next person does not have to reconstruct the recipe shapes by reading Java.
     *
     * <pre>
     * python3 - &lt;&lt;'PY'
     * import sys; sys.path.insert(0, '.')
     * from recipegraph.model import Recipe, Ingredient
     * from recipegraph import pins
     * r = Recipe("fixture:iron_block", "jar_json", [("minecraft:iron_block", 1)],
     *            [Ingredient(["ore:ingotIron"], 1, "item") for _ in range(9)],
     *            category="crafting_shaped", machine="Crafting (shaped)")
     * print(pins.fingerprint(r))
     * PY
     * </pre>
     */
    static void regenerate() {
    }

    /** A shaped 3x3 of one ingredient: nine slots, one output. */
    private static final String IRON_BLOCK = "86d35ece4a20";
    /** Two outputs of one key and two slots of one ingredient, at quantities 2 and 10. */
    private static final String QTY_SORT = "1e820beed55d";
    /** One slot accepting three alternatives. */
    private static final String THREE_ALTS = "a145ac12d994";
    /** Two slots, different ingredients and quantities. */
    private static final String TWO_SLOTS = "d1133fe4d6c5";
    /** The same shape with no category at all. */
    private static final String NO_CATEGORY = "54cf64e4f2c9";

    // -- the fingerprint itself ------------------------------------------------------

    @Test
    public void aShapedRecipeFingerprintsAsPythonFingerprintsIt() {
        GraphBuilder b = new GraphBuilder();
        ironBlock(b, "fixture:iron_block", "Crafting (shaped)", "jar_json");
        RecipeGraph g = b.build();
        assertEquals(IRON_BLOCK, Pins.fingerprint(g, 0));
    }

    @Test
    public void theIdSourceAndMachineAreDeliberatelyNotPartOfTheIdentity() {
        // The three fields `pins.py` names as excluded, all moved at once. A redump
        // renumbers every rid and a mod update renames the jar; neither changes what the
        // recipe does, and a pin that lapsed over either would be useless.
        GraphBuilder b = new GraphBuilder();
        ironBlock(b, "fixture:iron_block", "Crafting (shaped)", "jar_json");
        ironBlock(b, "totally:different:99", "Some Other Machine", "hei_dump");
        RecipeGraph g = b.build();
        assertEquals(IRON_BLOCK, Pins.fingerprint(g, 0));
        assertEquals("rid, machine and source must not reach the fingerprint",
                Pins.fingerprint(g, 0), Pins.fingerprint(g, 1));
    }

    @Test
    public void theCategoryIsPartOfTheIdentity() {
        // The counterpart to the test above, and the reason `category` is stored beside the
        // fingerprint: it is what the CATEGORY fallback matches on when the exact recipe is
        // gone, so it has to be inside the digest as well.
        GraphBuilder b = new GraphBuilder();
        ironBlock(b, "fixture:iron_block", "Crafting (shaped)", "jar_json");
        b.beginRecipe();
        for (int slot = 0; slot < 9; slot++) {
            b.beginSlot(1, "item");
            b.alternative(b.key("ore:ingotIron"));
            b.endSlot();
        }
        b.output(b.key("minecraft:iron_block"), 1);
        b.endRecipe("fixture:other", "compressor", "Crafting (shaped)", "jar_json",
                false, false);
        RecipeGraph g = b.build();
        assertNotEquals(Pins.fingerprint(g, 0), Pins.fingerprint(g, 1));
    }

    @Test
    public void quantitiesSortAsNumbersAndNotAsText() {
        // 2 before 10. Sorting the RENDERED strings puts "10" first and produces a digest
        // that is stable, plausible and not Python's. Nothing about a wrong answer here is
        // visible: the pin simply stops matching.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(2, "item");
        b.alternative(b.key("b:in"));
        b.endSlot();
        b.beginSlot(10, "item");
        b.alternative(b.key("b:in"));
        b.endSlot();
        b.output(b.key("a:thing"), 2);
        b.output(b.key("a:thing"), 10);
        b.endRecipe("x:1", "cat", null, "s", false, false);
        assertEquals(QTY_SORT, Pins.fingerprint(b.build(), 0));
    }

    @Test
    public void theOrderOfASlotsAlternativesDoesNotMatter() {
        // A slot accepting any of three ores is the same slot whichever one the extractor
        // listed first, and letting that reorder lapse a pin would make pins feel arbitrary.
        RecipeGraph forward = oneSlot("z:c", "a:a", "m:b");
        RecipeGraph reversed = oneSlot("a:a", "m:b", "z:c");
        assertEquals(THREE_ALTS, Pins.fingerprint(forward, 0));
        assertEquals(Pins.fingerprint(forward, 0), Pins.fingerprint(reversed, 0));
    }

    @Test
    public void theOrderOfTheSlotsThemselvesDoesNotMatter() {
        // Slot order is an artefact of how the extractor walked the recipe.
        GraphBuilder forward = new GraphBuilder();
        twoSlots(forward, true);
        GraphBuilder reversed = new GraphBuilder();
        twoSlots(reversed, false);
        assertEquals(TWO_SLOTS, Pins.fingerprint(forward.build(), 0));
        assertEquals(TWO_SLOTS, Pins.fingerprint(reversed.build(), 0));
    }

    @Test
    public void anEmptyCategoryRendersEmptyAndNotNull() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("p:one"));
        b.endSlot();
        b.output(b.key("o:out"), 1);
        b.endRecipe("x:4", "", null, "s", false, false);
        assertEquals(NO_CATEGORY, Pins.fingerprint(b.build(), 0));
    }

    // -- resolve ---------------------------------------------------------------------

    @Test
    public void anExactHitIsAcceptedAndReportedAsExact() {
        RecipeGraph g = ironBlockGraph();
        Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();
        pins.put("minecraft:iron_block",
                new Pins.Pin(IRON_BLOCK, "crafting_shaped", "Iron Block from ..."));
        Pins.Resolution r = Pins.resolve(g, pins);
        assertEquals(Pins.EXACT, r.notes.get("minecraft:iron_block").state);
        assertEquals("", r.notes.get("minecraft:iron_block").note);
        assertTrue(r.accepted.get("minecraft:iron_block").contains("fixture:iron_block"));
    }

    @Test
    public void aFingerprintThatMatchesSeveralIdenticalRecipesAcceptsAllOfThem() {
        // 437 of the pack's bee mutations are byte-identical recipes. A fingerprint is not
        // unique and is not meant to be: pinning one of them means any of them will do, and
        // the solver keeps its own ranking among them.
        GraphBuilder b = new GraphBuilder();
        ironBlock(b, "fixture:one", "Crafting (shaped)", "jar_json");
        ironBlock(b, "fixture:two", "Crafting (shaped)", "jar_json");
        Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();
        pins.put("minecraft:iron_block", new Pins.Pin(IRON_BLOCK, "crafting_shaped", ""));
        Pins.Resolution r = Pins.resolve(b.build(), pins);
        assertEquals(2, r.accepted.get("minecraft:iron_block").size());
        assertEquals(Pins.EXACT, r.notes.get("minecraft:iron_block").state);
    }

    @Test
    public void aChangedRecipeFallsBackToItsCategoryAndSaysSo() {
        // "Make iron by smelting" is usually what a pin MEANT, so a fingerprint that no
        // longer matches falls back to the category rather than silently reverting to the
        // ranking.
        RecipeGraph g = ironBlockGraph();
        Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();
        pins.put("minecraft:iron_block",
                new Pins.Pin("000000000000", "crafting_shaped", "Iron Block from ..."));
        Pins.Resolution r = Pins.resolve(g, pins);
        assertEquals(Pins.CATEGORY, r.notes.get("minecraft:iron_block").state);
        assertTrue(r.notes.get("minecraft:iron_block").note,
                r.notes.get("minecraft:iron_block").note.contains("crafting_shaped"));
        assertTrue(r.accepted.containsKey("minecraft:iron_block"));
    }

    @Test
    public void aPinWithNoRouteLeftIsDeadAndAcceptsNothing() {
        RecipeGraph g = ironBlockGraph();
        Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();
        pins.put("minecraft:iron_block", new Pins.Pin("000000000000", "smelting", ""));
        Pins.Resolution r = Pins.resolve(g, pins);
        assertEquals(Pins.DEAD, r.notes.get("minecraft:iron_block").state);
        // NOT in `accepted`, which is what makes the solver fall back to the ranking. An
        // empty set here instead would mean "nothing is acceptable" and plan nothing at all.
        assertFalse(r.accepted.containsKey("minecraft:iron_block"));
    }

    @Test
    public void aPinOnAnItemThePackNoLongerHasIsDeadRatherThanDropped() {
        // The reason `resolve` is keyed by item KEY and not by key id: an item the pack has
        // removed has no id, and it still has to come back naming what the player pinned.
        RecipeGraph g = ironBlockGraph();
        Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();
        pins.put("removedmod:gone", new Pins.Pin(IRON_BLOCK, "crafting_shaped", "Gone"));
        Pins.Resolution r = Pins.resolve(g, pins);
        assertEquals(Pins.DEAD, r.notes.get("removedmod:gone").state);
    }

    // -- label -----------------------------------------------------------------------

    @Test
    public void aLabelNamesTheOutputAndUpToFourInputs() {
        RecipeGraph g = ironBlockGraph();
        // Built from the graph's OWN `bareName` rather than a spelling typed in here: this
        // asserts what `Pins.label` does with those names -- four of nine slots, then an
        // ellipsis -- and stays true if the naming fallback chain is ever retuned, which is
        // a different file's contract and has its own tests.
        String ingot = g.bareName(g.keyId("ore:ingotIron"));
        assertEquals("Iron Block from " + ingot + ", " + ingot + ", " + ingot + ", "
                + ingot + ", ...", Pins.label(g, 0));
    }

    @Test
    public void theFourSlotWindowIsTakenBeforeEmptySlotsAreFilteredOut() {
        // Python slices `recipe.inputs[:4]` and THEN drops the empty ones, so a recipe whose
        // first slot is empty shows three ingredients, not four. Reading it the other way
        // round silently changes the stored label of every such pin.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.endSlot();                       // an empty slot: a spacer in the recipe grid
        for (String key : new String[] {"a:aa", "b:bb", "c:cc", "d:dd"}) {
            b.beginSlot(1, "item");
            b.alternative(b.key(key));
            b.endSlot();
        }
        b.output(b.key("o:out"), 1);
        b.endRecipe("x:5", "cat", null, "s", false, false);
        b.name("a:aa", "Ay");
        b.name("b:bb", "Bee");
        b.name("c:cc", "Cee");
        b.name("d:dd", "Dee");
        b.name("o:out", "Out");
        String label = Pins.label(b.build(), 0);
        assertEquals("Out from Ay, Bee, Cee, ...", label);
    }

    @Test
    public void aRecipeWithNoInputsSaysSoRatherThanTrailingAnEmptyList() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.output(b.key("o:out"), 1);
        b.endRecipe("x:6", "cat", null, "s", false, false);
        b.name("o:out", "Out");
        assertEquals("Out from nothing", Pins.label(b.build(), 0));
    }

    // -- the file both languages read ------------------------------------------------

    @Test
    public void aPinFileWrittenByPythonLoads() throws IOException {
        // Byte-for-byte what `pins.save` produced when run against the same pin. Both sides
        // are live until #19 phase 6 retires the Python UI, so a player who pins from the web
        // page has to see that choice in game -- and the failure mode if this drifts is not
        // an error, it is the pin quietly not being there.
        File file = write("{\n"
                + " \"_comment\": \"Recipe choices you made by hand. Keyed by item; ...\",\n"
                + " \"pins\": {\n"
                + "  \"minecraft:iron_block\": {\n"
                + "   \"category\": \"crafting_shaped\",\n"
                + "   \"fingerprint\": \"86d35ece4a20\",\n"
                + "   \"label\": \"Iron Block from Ingot\"\n"
                + "  }\n"
                + " }\n"
                + "}\n");
        Map<String, Pins.Pin> loaded = Pins.load(file);
        assertEquals(1, loaded.size());
        Pins.Pin pin = loaded.get("minecraft:iron_block");
        assertEquals("86d35ece4a20", pin.fingerprint);
        assertEquals("crafting_shaped", pin.category);
        assertEquals("Iron Block from Ingot", pin.label);
    }

    @Test
    public void savingAndLoadingRoundTrips() throws IOException {
        File file = new File(temporaryDirectory(), "pins.json");
        Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();
        pins.put("mod:zeta", new Pins.Pin("aaaaaaaaaaaa", "smelting", "Zeta from Ore"));
        pins.put("mod:alpha", new Pins.Pin("bbbbbbbbbbbb", "crafting_shaped", "Alpha"));
        Pins.save(file, pins);

        String written = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        // Sorted keys and a one-space indent, matching `json.dump(..., indent=1,
        // sort_keys=True)`. This file is hand-edited and lives beside a user's data; a side
        // that reformatted it on every write would make every save a whole-file diff.
        assertTrue(written, written.indexOf("mod:alpha") < written.indexOf("mod:zeta"));
        assertTrue(written, written.contains("\n  \"mod:alpha\": {"));

        Map<String, Pins.Pin> reloaded = Pins.load(file);
        assertEquals(2, reloaded.size());
        assertEquals("smelting", reloaded.get("mod:zeta").category);
    }

    @Test
    public void aBrokenPinFileCostsThePinsAndNotThePlan() throws IOException {
        // NEVER RAISES, as `pins.load` never raises: a plan the player asked for must not die
        // on a file they may not even know exists.
        assertTrue(Pins.load(new File(temporaryDirectory(), "absent.json")).isEmpty());
        assertTrue(Pins.load(write("not json at all")).isEmpty());
        assertTrue(Pins.load(write("[]")).isEmpty());
        assertTrue(Pins.load(write("{\"pins\": 7}")).isEmpty());
    }

    @Test
    public void oneMalformedEntryCostsThatEntryAndNotTheFile() {
        // Entry by entry, matching Python: a pin with no fingerprint identifies nothing and is
        // dropped, while the pins either side of it still load.
        File file;
        try {
            file = write("{\"pins\": {"
                    + "\"mod:good\": {\"fingerprint\": \"abc123abc123\"},"
                    + "\"mod:nofingerprint\": {\"category\": \"smelting\"},"
                    + "\"mod:notanobject\": 7}}");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        Map<String, Pins.Pin> loaded = Pins.load(file);
        assertEquals(1, loaded.size());
        // A missing category and label read as empty, never as the string "null".
        assertEquals("", loaded.get("mod:good").category);
        assertEquals("", loaded.get("mod:good").label);
    }

    // -- the cross-language contract -------------------------------------------------

    /**
     * The constants this port assumes about `recipegraph/pins.py`, asserted against that
     * file rather than remembered.
     *
     * The digests above would all still pass if Python changed its separators and this file
     * did not -- they were captured BEFORE such a change, so they pin the past rather than
     * the present. This is what notices. Same shape as
     * `test_nbt_digest.JavaSourceContractTest`, pointed the other way.
     */
    @Test
    public void pinsPyStillUsesTheSeparatorsAndDigestThisPortAssumes() throws IOException {
        String source = readPinsPy();
        assertContains(source, "_FIELD = \"\\x1f\"");
        assertContains(source, "_SLOT = \"\\x1e\"");
        assertContains(source, "_ALT = \"\\x1d\"");
        assertContains(source, "FINGERPRINT_DIGITS = " + Pins.FINGERPRINT_DIGITS);
        // The hash itself, and the digest size derived from FINGERPRINT_DIGITS. Blake2b is
        // hand-written here because the JDK has none; a Python side that moved to a
        // different hash would leave that file being maintained for nothing.
        assertContains(source, "hashlib.blake2b");
        assertContains(source, "digest_size=FINGERPRINT_DIGITS // 2");
        // The three outcome names travel into the plan as strings.
        assertContains(source, "EXACT = \"" + Pins.EXACT + "\"");
        assertContains(source, "CATEGORY = \"" + Pins.CATEGORY + "\"");
        assertContains(source, "DEAD = \"" + Pins.DEAD + "\"");
    }

    /**
     * The four field names of the stored form, against the Python that writes the same file.
     *
     * A DIFFERENT CONTRACT FROM THE SEPARATORS ABOVE, and a quieter one. A separator that
     * drifted changes every fingerprint and every pin lapses loudly enough to notice. A field
     * name that drifted makes one side write `"fp"` where the other reads `"fingerprint"`,
     * and the reader skips the entry -- so the player's choices simply stop applying, with no
     * error anywhere and a picker that still looks right. `pins.save` and `pins.load` spell
     * all four; so do {@link Pins#toJson} and {@link Pins#fromJson}.
     */
    @Test
    public void pinsPyStillSpellsTheStoredFieldsThisPortReadsAndWrites() throws IOException {
        String source = readPinsPy();
        // `pins.load` reads them off the document, so the names appear as literals there.
        assertContains(source, "doc.get(\"pins\")");
        assertContains(source, "pin.get(\"fingerprint\")");
        assertContains(source, "pin.get(\"category\")");
        assertContains(source, "pin.get(\"label\")");
        // And this side must be spelling the same four, not merely have a constant each.
        JsonObject written = Pins.toJson(java.util.Collections.singletonMap(
                "mod:plate", new Pins.Pin("abc", "cat", "a label")));
        JsonObject one = written.getAsJsonObject("mod:plate");
        assertEquals("abc", one.get("fingerprint").getAsString());
        assertEquals("cat", one.get("category").getAsString());
        assertEquals("a label", one.get("label").getAsString());
    }

    /**
     * {@link Pins#fromJson} is the one reader of the stored shape, and it tolerates rubbish.
     *
     * `ScenarioInputs.resolvePins` used to parse this shape a second time and had drifted: it
     * called `getAsJsonObject()` with no guard, so a hand-edited file whose entry is a string
     * threw an IllegalStateException from inside a plan, while `Pins.read` reading the same
     * file skipped it. One shape, one reader, and this is the case that told them apart.
     */
    @Test
    public void aPinEntryThatIsNotAnObjectIsSkippedRatherThanThrown() {
        JsonObject stored = new JsonObject();
        stored.addProperty("mod:plate", "somebody edited this by hand");
        JsonObject good = new JsonObject();
        good.addProperty("fingerprint", "abc");
        stored.add("mod:gear", good);

        Map<String, Pins.Pin> read = Pins.fromJson(stored);
        assertEquals(1, read.size());
        assertEquals("abc", read.get("mod:gear").fingerprint);
        // The whole reason it matters: a plan built on this document must not throw.
        assertNotNull(ScenarioInputs.resolve(ironBlockGraph(), scenarioWith(stored)));
    }

    /** The live scenario document's shape, with `pins` set to `stored`. */
    private static JsonObject scenarioWith(JsonObject stored) {
        JsonObject scenario = new JsonObject();
        scenario.add("pins", stored);
        return scenario;
    }

    // -- helpers ---------------------------------------------------------------------

    /** Nine slots of `ore:ingotIron` making one iron block, the shaped-3x3 shape. */
    private static void ironBlock(GraphBuilder b, String rid, String machine, String source) {
        b.beginRecipe();
        for (int slot = 0; slot < 9; slot++) {
            b.beginSlot(1, "item");
            b.alternative(b.key("ore:ingotIron"));
            b.endSlot();
        }
        b.output(b.key("minecraft:iron_block"), 1);
        b.endRecipe(rid, "crafting_shaped", machine, source, false, false);
    }

    private static RecipeGraph ironBlockGraph() {
        GraphBuilder b = new GraphBuilder();
        ironBlock(b, "fixture:iron_block", "Crafting (shaped)", "jar_json");
        b.name("minecraft:iron_block", "Iron Block");
        return b.build();
    }

    private static RecipeGraph oneSlot(String... alternatives) {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(3, "fluid");
        for (String alternative : alternatives) {
            b.alternative(b.key(alternative));
        }
        b.endSlot();
        b.output(b.key("o:out"), 1);
        b.endRecipe("x:2", "cat", null, "s", false, false);
        return b.build();
    }

    private static void twoSlots(GraphBuilder b, boolean oneFirst) {
        b.beginRecipe();
        if (oneFirst) {
            slot(b, "p:one", 1);
            slot(b, "q:two", 2);
        } else {
            slot(b, "q:two", 2);
            slot(b, "p:one", 1);
        }
        b.output(b.key("o:out"), 1);
        b.endRecipe("x:3", "cat", null, "s", false, false);
    }

    private static void slot(GraphBuilder b, String key, int qty) {
        b.beginSlot(qty, "item");
        b.alternative(b.key(key));
        b.endSlot();
    }

    private static void assertContains(String source, String wanted) {
        assertTrue("recipegraph/pins.py no longer contains: " + wanted,
                source.contains(wanted));
    }

    private static File temporaryDirectory() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "mcrecipedump-pins-test");
        dir.mkdirs();
        return dir;
    }

    private static File write(String json) throws IOException {
        File file = File.createTempFile("pins", ".json", temporaryDirectory());
        file.deleteOnExit();
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Gradle runs tests with the working directory set to `mod/`, but a direct run from the
     * repository root happens too. Try both, exactly as `DigestFixtureTest` does for the
     * shared digest fixture.
     */
    private static String readPinsPy() throws IOException {
        for (String candidate : new String[] {"../recipegraph/pins.py", "recipegraph/pins.py"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        fail("could not find recipegraph/pins.py from " + new File(".").getAbsolutePath());
        return null;
    }
}
