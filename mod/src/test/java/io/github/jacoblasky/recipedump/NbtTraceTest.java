package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The issue #80 NBT trace: per-top-level-tag digests, and the sorted-list variant that
 * says whether a tag COULD churn from list order.
 *
 * `DiscriminatorTest` already proves the mechanism this diagnostic hunts for
 * (`listOrderMatters`, `keyInsertionOrderDoesNotChangeTheDigest`); this class does not
 * repeat it. What is new here, and what actually needs guarding, is that the diagnostic
 * cannot reach the digest -- a trace that moved a single discriminated key would cost a
 * whole-pack redump to undo.
 */
public class NbtTraceTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    private static ItemStack withTag(NBTTagCompound tag) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.setTagCompound(tag);
        return stack;
    }

    private static NBTTagList strings(String... values) {
        NBTTagList list = new NBTTagList();
        for (String v : values) {
            list.appendTag(new NBTTagString(v));
        }
        return list;
    }

    private static String canon(NBTTagCompound tag, boolean sortLists) {
        StringBuilder sb = new StringBuilder();
        DumpCommand.canonical(tag, sb, sortLists);
        return sb.toString();
    }

    /**
     * THE test in this file. Everything else here is a diagnostic that can be wrong and
     * be fixed; this one is the guarantee that adding the diagnostic did not move a key.
     */
    @Test
    public void theSortListsOverloadIsByteIdenticalToTheFrozenFormWhenOff() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("two", "one", "three"));
        tag.setString("Material", "cobalt");
        NBTTagCompound nested = new NBTTagCompound();
        nested.setTag("Inner", strings("b", "a"));
        tag.setTag("Modifiers", nested);
        tag.setInteger("Level", 3);

        StringBuilder frozen = new StringBuilder();
        DumpCommand.canonical(tag, frozen);
        assertEquals("the two-argument canonical form is dump schema 4; the sortLists "
                     + "overload must not change it", frozen.toString(), canon(tag, false));
    }

    @Test
    public void aSortedListTagReportsOEqualsUNowThatTheDigestSortsIt() {
        // "o" means "the way the real digest serialises this tag". Once `Special` is in
        // SORTED_LIST_TAGS the digest sorts it, so the trace must stop calling it a suspect
        // -- otherwise the diagnostic goes on reporting churn that can no longer reach a key,
        // and it contradicts the very fix it was built to justify.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Special", strings("two", "one"));
        tag.setTag("Traits", strings("two", "one"));

        String json = DumpCommand.tagDigests(withTag(tag));
        assertEquals("a sorted-list tag can no longer churn from order, so o == u",
                     digest(json, "Special", "o"), digest(json, "Special", "u"));
        assertNotEquals("an ordinary list tag is still order-sensitive and still a suspect",
                        digest(json, "Traits", "o"), digest(json, "Traits", "u"));
    }

    @Test
    public void theDigestItselfIsUnchangedByTheOverloadExisting() {
        // Belt and braces on the above, at the level the key is actually built from.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("two", "one"));
        assertEquals(DumpCommand.discriminator(withTag(tag)),
                     DumpCommand.discriminator(withTag(tag)));
        assertTrue(DumpCommand.discriminator(withTag(tag)).matches("[0-9a-f]{12}"));
    }

    @Test
    public void sortingMakesAPermutedListCompareEqual() {
        // The hypothesis in #80: same trait set, different order, per JVM run.
        NBTTagCompound a = new NBTTagCompound();
        a.setTag("Traits", strings("one", "two", "three"));
        NBTTagCompound b = new NBTTagCompound();
        b.setTag("Traits", strings("three", "one", "two"));

        assertNotEquals("ordered serialisation must still see the permutation",
                        canon(a, false), canon(b, false));
        assertEquals("sorted serialisation is what makes the permutation invisible",
                     canon(a, true), canon(b, true));
    }

    @Test
    public void sortingReachesNestedLists() {
        // A trait list one level down inside a compound is the shape Tinkers actually
        // uses, so a sort that only handled the top level would clear a guilty tag.
        NBTTagCompound a = new NBTTagCompound();
        NBTTagCompound innerA = new NBTTagCompound();
        innerA.setTag("Traits", strings("x", "y"));
        a.setTag("Stats", innerA);
        NBTTagCompound b = new NBTTagCompound();
        NBTTagCompound innerB = new NBTTagCompound();
        innerB.setTag("Traits", strings("y", "x"));
        b.setTag("Stats", innerB);

        assertNotEquals(canon(a, false), canon(b, false));
        assertEquals(canon(a, true), canon(b, true));
    }

    @Test
    public void aTagHoldingAnOrderedListIsFlaggedAndAScalarIsNot() {
        // This is the whole one-dump payoff: o != u marks a suspect, o == u clears it.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("two", "one"));
        tag.setString("Material", "cobalt");

        String json = DumpCommand.tagDigests(withTag(tag));
        assertTrue("Traits should be present: " + json, json.contains("\"Traits\":"));
        assertTrue("Material should be present: " + json, json.contains("\"Material\":"));

        assertNotEquals("a tag with a multi-element list must have o != u",
                        digest(json, "Traits", "o"), digest(json, "Traits", "u"));
        assertEquals("a scalar tag cannot churn from list order, so o == u",
                     digest(json, "Material", "o"), digest(json, "Material", "u"));
    }

    @Test
    public void aSingleElementListCannotChurnSoItIsCleared() {
        // Order is meaningless with one element; flagging it would pad the suspect list
        // with tags that cannot possibly be the cause.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("only"));
        String json = DumpCommand.tagDigests(withTag(tag));
        assertEquals(digest(json, "Traits", "o"), digest(json, "Traits", "u"));
    }

    @Test
    public void theTraceCoversTheSameTagsTheDigestDoes() {
        // If the trace explained a different tag set than the digest was computed over,
        // it would point at the wrong tag convincingly. Cosmetic tags are stripped from
        // both by `identityTag`.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("one"));
        tag.setInteger("RepairCost", 7);
        tag.setInteger("HideFlags", 63);

        String json = DumpCommand.tagDigests(withTag(tag));
        assertTrue(json.contains("\"Traits\":"));
        assertFalse("RepairCost is cosmetic and must not appear: " + json,
                    json.contains("RepairCost"));
        assertFalse("HideFlags is cosmetic and must not appear: " + json,
                    json.contains("HideFlags"));
    }

    @Test
    public void aStackWithNothingIdentifyingHasNoTrace() {
        assertNull(DumpCommand.tagDigests(new ItemStack(Items.STICK)));
        assertNull(DumpCommand.tagDigests(withTag(new NBTTagCompound())));
        NBTTagCompound cosmeticOnly = new NBTTagCompound();
        cosmeticOnly.setInteger("RepairCost", 3);
        assertNull("cosmetic-only NBT is not an identity, so there is nothing to trace",
                   DumpCommand.tagDigests(withTag(cosmeticOnly)));
    }

    @Test
    public void tagsComeOutInASortedStableOrder() {
        // The file is diffed between two dumps; a HashMap order would make every line
        // look changed.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("zeta", "1");
        tag.setString("alpha", "2");
        tag.setString("mid", "3");
        String json = DumpCommand.tagDigests(withTag(tag));
        assertTrue(json, json.indexOf("\"alpha\"") < json.indexOf("\"mid\""));
        assertTrue(json, json.indexOf("\"mid\"") < json.indexOf("\"zeta\""));
    }

    @Test
    public void everyTagDigestIsTwelveHexDigits() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("a", "b"));
        String json = DumpCommand.tagDigests(withTag(tag));
        assertTrue(json, digest(json, "Traits", "o").matches("[0-9a-f]{12}"));
        assertTrue(json, digest(json, "Traits", "u").matches("[0-9a-f]{12}"));
    }

    @Test
    public void aPerTagDigestIsNotTheWholeItemDigest() {
        // Guard against a future reader "fixing" an apparent mismatch: the trace digests
        // one SUBTAG, the key digests the whole compound, so they are not comparable and
        // were never meant to be.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("a"));
        String json = DumpCommand.tagDigests(withTag(tag));
        assertNotEquals(DumpCommand.discriminator(withTag(tag)), digest(json, "Traits", "o"));
    }

    @Test
    public void aPlainDumpWRITESTheTrace() {
        // The default, and the reason it is the default: the file cannot be rebuilt after
        // the fact, and proving churn needs TWO dumps carrying it, so anything less than
        // always-on makes two comparable dumps in a row the unlikely case.
        assertTrue(DumpCommand.wantsTrace(null));
        assertTrue(DumpCommand.wantsTrace(new String[] {}));
    }

    @Test
    public void theSuppressArgIsRecognisedHoweverItIsTyped() {
        assertFalse(DumpCommand.wantsTrace(new String[] {"notrace"}));
        assertFalse(DumpCommand.wantsTrace(new String[] {"NOTRACE"}));
        assertFalse(DumpCommand.wantsTrace(new String[] {"NoTrace"}));
        assertFalse(DumpCommand.wantsTrace(new String[] {" notrace "}));
        assertFalse(DumpCommand.wantsTrace(new String[] {"other", "notrace"}));
    }

    @Test
    public void anythingElseFAILSSAFEAndStillWritesTheTrace() {
        // A typo must not silently produce a dump that cannot answer the question it was
        // run for. That was the failure direction of the opt-in flag this replaced.
        assertTrue(DumpCommand.wantsTrace(new String[] {"nottrace"}));
        assertTrue(DumpCommand.wantsTrace(new String[] {"no-trace"}));
        assertTrue(DumpCommand.wantsTrace(new String[] {null}));
        // A leftover `nbttrace` from the older docs asks for what it already gets.
        assertTrue(DumpCommand.wantsTrace(new String[] {"nbttrace"}));
    }

    @Test
    public void theSinkStillHonoursBeingTurnedOff() {
        assertFalse(new DumpCommand.KeySink(false).tracing());
        assertNull(new DumpCommand.KeySink(false).trace());
        assertTrue(new DumpCommand.KeySink(true).tracing());
    }

    @Test
    public void theSinkRecordsANameAndATraceUnderTheSameKey() {
        // names.json and nbt_trace.json are joined on this key by the python side; they
        // are written from one place so the format cannot drift between them.
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Traits", strings("a", "b"));
        DumpCommand.KeySink sink = new DumpCommand.KeySink(true);
        sink.record("minecraft:stick#abcdef123456", withTag(tag));

        assertTrue(sink.names().containsKey("minecraft:stick#abcdef123456"));
        assertTrue(sink.trace().containsKey("minecraft:stick#abcdef123456"));
    }

    @Test
    public void theTraceOnlyLooksAtKeysCarryingADigest() {
        // Pins the coupling `KeySink.record` relies on to avoid recomputing a digest for
        // every occurrence of every plain stack: `stack` appends `#<digest>` if and only if
        // the stack has identity, so a key with no '#' is skipped WITHOUT calling
        // tagDigests. If the key format ever stops carrying the hash, this fails here
        // rather than silently producing an empty trace file after a launch of the game.
        NBTTagCompound identifying = new NBTTagCompound();
        identifying.setTag("Traits", strings("a", "b"));

        DumpCommand.KeySink sink = new DumpCommand.KeySink(true);
        sink.record("minecraft:stick", withTag(identifying));
        assertTrue("a hash-less key must not be traced even when the stack has NBT",
                   sink.trace().isEmpty());

        sink.record("minecraft:stick#deadbeef0000", withTag(identifying));
        assertEquals(1, sink.trace().size());
    }

    @Test
    public void theSinkSkipsAnUndiscriminatedStackInTheTraceButStillNamesIt() {
        DumpCommand.KeySink sink = new DumpCommand.KeySink(true);
        sink.record("minecraft:stick", new ItemStack(Items.STICK));
        assertTrue("a plain item still needs its display name",
                   sink.names().containsKey("minecraft:stick"));
        assertFalse("but it has no identifying NBT to explain",
                    sink.trace().containsKey("minecraft:stick"));
    }

    /**
     * The cross-language contract, in the same spirit as `tests/fixtures/nbt_digest.json`.
     *
     * This writes a real `nbt_trace.json` with the real writer and compares it against the
     * committed fixture that `tests/test_digest_churn.py` PARSES. Without it, both sides
     * are tested against their own idea of the shape and agree with themselves right up
     * until a live dump -- and a live dump costs a launch of the game, which is the one
     * resource this whole change exists to spend carefully.
     *
     * If this fails after a deliberate format change: regenerate the fixture by running
     * this test with -Dnbttrace.fixture=<path> and commit the result.
     */
    @Test
    public void theWrittenFileMatchesTheFixtureThePythonSideParses() throws Exception {
        Map<String, String> trace = new LinkedHashMap<String, String>();
        NBTTagCompound hatchet = new NBTTagCompound();
        // DELIBERATELY not in sorted order. With ("dense", "sharp") the sort is a no-op, so
        // o == u and the fixture would show only CLEARED tags -- the python side would then
        // be tested against a file that never exercises the flagged path at all.
        hatchet.setTag("Traits", strings("sharp", "dense"));
        hatchet.setString("Material", "cobalt");
        trace.put("tconstruct:hatchet:804#" + DumpCommand.discriminator(withTag(hatchet)),
                  DumpCommand.tagDigests(withTag(hatchet)));

        NBTTagCompound plain = new NBTTagCompound();
        plain.setString("Material", "iron");
        trace.put("tconstruct:pickaxe:1#" + DumpCommand.discriminator(withTag(plain)),
                  DumpCommand.tagDigests(withTag(plain)));

        File out = File.createTempFile("nbt_trace", ".json");
        out.deleteOnExit();
        assertEquals(2, DumpCommand.writeNbtTrace(out, trace));
        String written = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        String override = System.getProperty("nbttrace.fixture");
        if (override != null) {
            Files.write(new File(override).toPath(), written.getBytes(StandardCharsets.UTF_8));
        }

        File fixture = new File("../tests/fixtures/nbt_trace_sample.json");
        if (!fixture.exists()) {
            fixture = new File("tests/fixtures/nbt_trace_sample.json");
        }
        assertTrue("the shared fixture is missing; regenerate with -Dnbttrace.fixture=",
                   fixture.exists());
        String expected = new String(
                Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8);
        assertEquals("nbt_trace.json's shape is a cross-language contract; the python "
                     + "reader is tested against this exact text",
                     expected.replace("\r\n", "\n"), written.replace("\r\n", "\n"));
    }

    private static String digest(String json, String tag, String field) {
        // Deliberately a narrow scrape rather than a JSON parser: the mod has no JSON
        // dependency, and the point is to read what the writer actually emitted.
        String key = "\"" + tag + "\":{";
        int at = json.indexOf(key);
        assertTrue("no such tag in " + json, at >= 0);
        String rest = json.substring(at + key.length());
        String field_key = "\"" + field + "\":\"";
        int f = rest.indexOf(field_key);
        assertTrue("no field " + field + " in " + rest, f >= 0);
        int start = f + field_key.length();
        return rest.substring(start, rest.indexOf('"', start));
    }
}
