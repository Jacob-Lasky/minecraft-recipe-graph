package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Schema 6: a dump that says what it could not read. #194
 *
 * WHAT THIS IS GUARDING. Before schema 6 the catch around `getDisplayName()` discarded the
 * fact along with the exception, so a dump that lost forty thousand display names wrote a
 * shorter names.json and reported nothing -- and nothing downstream could have caught it,
 * because the count it should have held was not recorded anywhere either. Both halves are
 * asserted here: the counting, and that the count reaches summary.json.
 *
 * THE SECOND HALF OF #194 IS THE JAR SET, and it is guarded lower down. A dump could not say
 * which mods were loaded when it ran, so a five-jar dump and a full-pack one produced
 * provenance lines identical in form -- and `execute` writes to a hardcoded
 * `<gamedir>/mc-recipe-dump`, so the small one lands on top of the large one. `refuseToClobber`
 * is the guard, and the cases below are mostly about when it must NOT fire.
 *
 * THE THROWING ITEM IS A REAL `Item`, NOT A MOCK, because the behaviour under test is
 * `KeySink.record`'s catch and the only interesting question about it is which throwables it
 * catches. A stub sink that recorded a failure when told to would assert the test's own
 * arithmetic. `Bootstrap.register()` is what makes an unregistered `Item` usable here --
 * `getDisplayName` reaches the item directly and never consults the registry.
 */
public class SchemaSixTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    /** An item whose display name is unavailable until told otherwise, as outside a render pass. */
    private static final class Moody extends Item {
        private boolean throwing = true;

        @Override
        public String getItemStackDisplayName(ItemStack stack) {
            if (throwing) {
                throw new IllegalStateException("not in a render pass");
            }
            return "Moody Thing";
        }
    }

    @Test
    public void sixIsNoLongerTheCurrentSchema() {
        // THE LITERAL MOVED TO `SchemaSevenTest`, mirroring the python side where the newest
        // schema's file owns the tripwire. What stays in this file is schema 6's own FIELDS,
        // `names` / `names_failed` / `mod_count` / `mod_digest`, none of which moved at 7.
        //
        // Asserted as an inequality rather than deleted, so this file still fails if someone
        // walks the number back to 6 without touching `SchemaSevenTest`.
        assertTrue("schema 6 is behind the current dump format",
                   DumpCommand.SCHEMA > 6);
    }

    @Test
    public void anItemWhoseNameThrowsIsCountedRatherThanForgotten() {
        DumpCommand.KeySink sink = new DumpCommand.KeySink(false);
        sink.record("mod:ok", new ItemStack(Items.STICK));
        sink.record("mod:moody", new ItemStack(new Moody()));

        assertEquals(1, sink.names().size());
        assertEquals(1, sink.namesFailed());
    }

    @Test
    public void aNameThatThrewAndThenArrivedIsNotStillCountedAsLost() {
        // The reason `namesFailed` subtracts at the end instead of counting as it goes.
        // `record` retries every occurrence of a key it has no name for, and the documented
        // cause of the throw is being outside a render pass -- a property of WHEN, not of
        // the item. Counting throws would report a loss for an item whose name the dump
        // went on to write, and the number has to be comparable with names.json's length.
        Moody moody = new Moody();
        DumpCommand.KeySink sink = new DumpCommand.KeySink(false);
        sink.record("mod:moody", new ItemStack(moody));
        assertEquals(1, sink.namesFailed());

        moody.throwing = false;
        sink.record("mod:moody", new ItemStack(moody));

        assertEquals(0, sink.namesFailed());
        assertEquals("Moody Thing", sink.names().get("mod:moody"));
    }

    @Test
    public void oneKeyThatThrowsRepeatedlyIsOneLostName() {
        // Deduplicated, because the same key is re-tried on every occurrence and the
        // reference pack has keys appearing in thousands of recipes. A per-throw counter
        // would report a five-figure loss for a handful of items.
        DumpCommand.KeySink sink = new DumpCommand.KeySink(false);
        Moody moody = new Moody();
        for (int i = 0; i < 5; i++) {
            sink.record("mod:moody", new ItemStack(moody));
        }
        assertEquals(1, sink.namesFailed());
    }

    @Test
    public void theSummaryDeclaresBothNameCounts() throws Exception {
        File file = File.createTempFile("summary", ".json");
        file.deleteOnExit();
        Map<String, int[]> perCategory = new LinkedHashMap<String, int[]>();
        perCategory.put("minecraft.crafting", new int[] {7, 0, 0});
        Map<String, String> categoryMod = new LinkedHashMap<String, String>();
        categoryMod.put("minecraft.crafting", "Minecraft");

        DumpCommand.writeSummary(file, perCategory, categoryMod, 7, 0, 2, 41, 3, 0,
                                 mods("jei", "mcrecipedump"));
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"names\": 41"));
        assertTrue(json, json.contains("\"names_failed\": 3"));
        assertTrue(json, json.contains("\"schema\": " + DumpCommand.SCHEMA));
    }

    @Test
    public void aCleanDumpStillDeclaresTheCountsAsZero() throws Exception {
        // The whole point of #194: silence and success must not look the same. A summary
        // that omitted the fields when nothing failed would leave a schema-6 dump that lost
        // names indistinguishable from a schema-5 dump that could not say.
        File file = File.createTempFile("summary-clean", ".json");
        file.deleteOnExit();
        DumpCommand.writeSummary(file, new LinkedHashMap<String, int[]>(),
                                 new LinkedHashMap<String, String>(), 0, 0, 0, 0, 0, 0,
                                 mods("jei"));
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"names\": 0"));
        assertTrue(json, json.contains("\"names_failed\": 0"));
    }

    // ------------------------------------------------------------------ the jar set

    private static List<String> mods(String... ids) {
        return Arrays.asList(ids);
    }

    @Test
    public void theSummaryRecordsWhichJarsTheDumpSaw() throws Exception {
        File file = File.createTempFile("summary-mods", ".json");
        file.deleteOnExit();
        List<String> ids = mods("appliedenergistics2", "jei", "mcrecipedump");

        DumpCommand.writeSummary(file, new LinkedHashMap<String, int[]>(),
                                 new LinkedHashMap<String, String>(), 0, 0, 0, 0, 0, 0, ids);
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"mod_count\": 3"));
        assertTrue(json, json.contains("\"mod_digest\": \"" + DumpCommand.modDigest(ids)
                + "\""));
    }

    @Test
    public void aDumpThatCouldNotAskOmitsTheFieldsRatherThanWritingZero() throws Exception {
        // `Loader` refusing to answer is not a jar set of zero, and a reader that saw
        // `mod_count: 0` would have to believe it. Absence is what "not measured" looks
        // like everywhere else in this file, so it is what it looks like here.
        File file = File.createTempFile("summary-nomods", ".json");
        file.deleteOnExit();

        DumpCommand.writeSummary(file, new LinkedHashMap<String, int[]>(),
                                 new LinkedHashMap<String, String>(), 0, 0, 0, 0, 0, 0, null);
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertFalse(json, json.contains("mod_count"));
        assertFalse(json, json.contains("mod_digest"));
    }

    @Test
    public void theDigestIsOfTheSetAndNotOfTheOrderItWasListedIn() {
        assertEquals(DumpCommand.modDigest(mods("a", "b", "c")),
                     DumpCommand.modDigest(mods("a", "b", "c")));
        // `activeModIds` sorts, so this is belt and braces on the property that matters:
        // the same pack must digest the same however Forge happened to enumerate it.
        assertEquals(DumpCommand.modDigest(mods("a", "b", "c")),
                     DumpCommand.modDigest(mods("c", "b", "a")));
    }

    @Test
    public void aDifferentJarSetIsADifferentDigest() {
        assertNotEquals(DumpCommand.modDigest(mods("a", "b")),
                        DumpCommand.modDigest(mods("a", "b", "c")));
    }

    @Test
    public void aMissingJarListDigestsToNothingRatherThanToTheEmptyString() {
        // Null has to survive the hop, because `refuseToClobber` reads a null digest as
        // "cannot compare" and a digest of "" would compare unequal to every real one --
        // turning "Forge would not say" into a refusal of every dump.
        assertNull(DumpCommand.modDigest(null));
    }

    @Test
    public void aSummaryFromBeforeSchemaSixRecordsNoJarSet() throws Exception {
        File file = File.createTempFile("summary-old", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(),
                    "{\"mod_version\":\"0.9.11\",\"schema\":5,\"recipes\":1663}"
                            .getBytes(StandardCharsets.UTF_8));

        DumpCommand.ModSet set = DumpCommand.readModSet(file);

        assertEquals(-1, set.count);
        assertNull(set.digest);
    }

    @Test
    public void aSummaryRoundTripsThroughItsOwnReader() throws Exception {
        // The writer and the reader are held to each other rather than each to a hand-typed
        // literal, which is the pair that can silently disagree: the writer hand-rolls JSON
        // and the reader is gson.
        File file = File.createTempFile("summary-round", ".json");
        file.deleteOnExit();
        List<String> ids = mods("appliedenergistics2", "jei", "mcrecipedump");
        DumpCommand.writeSummary(file, new LinkedHashMap<String, int[]>(),
                                 new LinkedHashMap<String, String>(), 0, 0, 0, 0, 0, 0, ids);

        DumpCommand.ModSet set = DumpCommand.readModSet(file);

        assertEquals(3, set.count);
        assertEquals(DumpCommand.modDigest(ids), set.digest);
    }

    // ------------------------------------------- the refusal that protects a real dump

    /** A dump directory holding a summary.json written for `ids`. */
    private static File dumpDir(String name, List<String> ids) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            name + "-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        dir.deleteOnExit();
        File summary = new File(dir, DumpCommand.SUMMARY_FILE);
        summary.deleteOnExit();
        DumpCommand.writeSummary(summary, new LinkedHashMap<String, int[]>(),
                                 new LinkedHashMap<String, String>(), 0, 0, 0, 0, 0, 0, ids);
        return dir;
    }

    @Test
    public void aSmallerJarSetMayNotOverwriteTheRealDump() throws Exception {
        // The failure the whole guard exists for: a six-mod dev client, or the headless
        // harness, landing on <gamedir>/mc-recipe-dump in a pack directory. The artifact it
        // would replace costs a launch of the full 367-jar pack to make again.
        List<String> pack = mods("a", "b", "c", "d", "e", "f", "g");
        File dir = dumpDir("realdump", pack);

        String refusal = DumpCommand.refuseToClobber(dir, mods("jei", "mcrecipedump"), false);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("REFUSING"));
        assertTrue(refusal, refusal.contains("7 mods there"));
        assertTrue(refusal, refusal.contains("2 loaded here"));
        assertTrue(refusal, refusal.contains(DumpCommand.FORCE_ARG));
    }

    @Test
    public void theSameJarSetDumpsStraightOverItself() throws Exception {
        // The normal path, and it must stay silent: re-dumping a pack you did not change is
        // what everyone does, and a guard that fires there gets forced past out of habit.
        List<String> pack = mods("a", "b", "c");
        assertNull(DumpCommand.refuseToClobber(dumpDir("same", pack), pack, false));
    }

    @Test
    public void forceIsTheWayPast() throws Exception {
        File dir = dumpDir("forced", mods("a", "b", "c"));
        assertNull(DumpCommand.refuseToClobber(dir, mods("jei"), true));
    }

    @Test
    public void anEmptyDirectoryIsNotSomethingToProtect() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "fresh-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        dir.deleteOnExit();
        assertNull(DumpCommand.refuseToClobber(dir, mods("jei"), false));
    }

    @Test
    public void aDirectoryThatIsNotThereYetIsNotSomethingToProtect() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "absent-"
                + System.nanoTime());
        assertNull(DumpCommand.refuseToClobber(dir, mods("jei"), false));
    }

    @Test
    public void aPreSchemaSixDumpIsOverwrittenWithoutAFight() throws Exception {
        // Every dump on disk today. Refusing here would refuse the FIRST dump anyone takes
        // after installing this build, which is the one that records the digest making
        // every later dump checkable.
        File dir = new File(System.getProperty("java.io.tmpdir"), "old-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        dir.deleteOnExit();
        File summary = new File(dir, DumpCommand.SUMMARY_FILE);
        summary.deleteOnExit();
        Files.write(summary.toPath(),
                    "{\"mod_version\":\"0.9.11\",\"schema\":5}"
                            .getBytes(StandardCharsets.UTF_8));

        assertNull(DumpCommand.refuseToClobber(dir, mods("jei"), false));
    }

    @Test
    public void aLoaderThatWillNotAnswerDoesNotBlockADump() throws Exception {
        // `activeModIds` returns null outside a running Forge, which is exactly this test
        // JVM -- so the null below is the real thing rather than a stand-in, and it is the
        // input `refuseToClobber` must read as "cannot compare" rather than as "no mods".
        assertNull(DumpCommand.activeModIds());
        File dir = dumpDir("noloader", mods("a", "b", "c"));
        assertNull(DumpCommand.refuseToClobber(dir, DumpCommand.activeModIds(), false));
    }

    @Test
    public void aSummaryWithANullJarSetIsUnrecordedRatherThanUnreadable() throws Exception {
        // `readModSet` steps over a JSON null before it looks at the field name, and if it
        // did not, gson would throw inside the try and the whole summary would read as
        // absent -- turning one malformed field into "this dump declares nothing".
        File file = File.createTempFile("summary-null", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(),
                    "{\"schema\":6,\"mod_count\":null,\"mod_digest\":null,\"recipes\":7}"
                            .getBytes(StandardCharsets.UTF_8));

        DumpCommand.ModSet set = DumpCommand.readModSet(file);

        assertEquals(-1, set.count);
        assertNull(set.digest);
    }

    @Test
    public void aDigestWithNoCountBesideItDoesNotPrintMinusOneMods() throws Exception {
        // `writeSummary` emits the pair or neither, so this shape cannot come from our own
        // writer. It can come from a hand edit, and hand-edited artifacts are the entire
        // subject of #194 -- the refusal must stay readable on one.
        File dir = new File(System.getProperty("java.io.tmpdir"),
                            "halfrecorded-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        dir.deleteOnExit();
        File summary = new File(dir, DumpCommand.SUMMARY_FILE);
        summary.deleteOnExit();
        Files.write(summary.toPath(),
                    ("{\"schema\":6,\"mod_digest\":\""
                            + DumpCommand.modDigest(mods("a", "b", "c")) + "\"}")
                            .getBytes(StandardCharsets.UTF_8));

        String refusal = DumpCommand.refuseToClobber(dir, mods("jei"), false);

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("an unrecorded number of mods there"));
        assertFalse(refusal, refusal.contains("-1"));
    }

    @Test
    public void theWriterAndTheReaderNameTheSameFile() {
        // Not a tautology: `writeSummary` and `readModSet` are called with the filename by
        // their callers, and before #194 gave the file a second speller there was only one.
        // A drift here does not throw -- `readModSet` finds nothing, reports "cannot say",
        // and the clobber guard waves every dump through while looking entirely green.
        assertEquals("summary.json", DumpCommand.SUMMARY_FILE);
    }

    @Test
    public void forceIsOptInAndAMistypedOneStillRefuses() {
        assertTrue(DumpCommand.forced(new String[] {"force"}));
        assertTrue(DumpCommand.forced(new String[] {" FORCE "}));
        assertFalse(DumpCommand.forced(new String[] {"forse"}));
        assertFalse(DumpCommand.forced(new String[] {}));
        // And it does not disturb the two flags that were already there.
        assertTrue(DumpCommand.wantsTrace(new String[] {"force"}));
        assertTrue(DumpCommand.wantsIcons(new String[] {"force"}));
    }
}
