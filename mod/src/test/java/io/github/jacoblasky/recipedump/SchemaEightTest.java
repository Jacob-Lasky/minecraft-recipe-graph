package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Dump schema 8: an item output may say how often a run actually yields it. Issue #223.
 *
 * THE MIRROR OF `SchemaSevenTest` AND NOT ITS COPY. Schema 7's `p` says a run may not SPEND an
 * input, which makes a recipe cheaper by a bounded amount. Schema 8's `q` says a run may not
 * PRODUCE an output, which lands in a DIVISOR: the reference pack attaches chances down to
 * 0.001 to 834 of its 835 `addItemOutput` calls, so an unread `q` understates the runs a plan
 * needs by up to 1000x, and understates every input those runs consume with it.
 *
 * WHY A JAVA FILE WHEN THE PYTHON SIDE READS THIS SOURCE TOO. `tests/test_schema_seven.py`
 * greps DumpCommand.java for the field spelling, which catches a rename and cannot catch a
 * compile-time mistake: a yield handed to the `p` parameter, or a chance array read against the
 * wrong list, both leave the source text a grep looks for perfectly intact. This half runs the
 * emitter over real ItemStacks and reads the bytes back.
 */
public class SchemaEightTest {

    @BeforeClass
    public static void bootstrap() {
        // Item registry, needed before any ItemStack can exist. Headless-safe.
        Bootstrap.register();
    }

    /** One output slot holding one stack, which is the shape `flatStacks` collapses to. */
    private static List<Object> slot(ItemStack... stacks) {
        return new ArrayList<Object>(Arrays.asList((Object[]) stacks));
    }

    @SafeVarargs
    private static List<List<Object>> slots(List<Object>... each) {
        return new ArrayList<List<Object>>(Arrays.asList(each));
    }

    private static ItemStack stick() {
        return new ItemStack(Items.STICK);
    }

    private static ItemStack apple() {
        return new ItemStack(Items.APPLE);
    }

    // ------------------------------------------------------------------ the schema number

    @Test
    public void theSchemaIsEight() {
        // Pinned here as well as read out of the jar's constant pool by
        // tests/test_dist_jar.py, because the python side's dump_meta.SCHEMA has to move with
        // it and `tests/test_catalysts.py` compares the two by reading this source. It lives
        // in the NEWEST schema's test file, which is why `SchemaSevenTest` no longer has it.
        assertEquals(8, DumpCommand.SCHEMA);
    }

    // ------------------------------------------------------- the field, and where it goes

    @Test
    public void aChanceOutputSaysQAndTheWholeStackReadsAsTheReaderExpects() {
        // The exact bytes, not a `contains`. The reader half of #223 is being written against
        // this shape, and a stack that gained a field, lost `c`, or spelled the id differently
        // would still satisfy every substring check in this file.
        String json = DumpCommand.flatStacks(slots(slot(stick())), null, new float[] {0.1f});
        assertEquals("[{\"i\":\"minecraft:stick\",\"m\":0,\"c\":1,\"q\":0.1}]", json);
    }

    @Test
    public void aCertainOutputCarriesNoQAtAll() {
        // OMITTED AT 1.0, NOT WRITTEN AS 1.0, which is what makes a schema-8 dump
        // byte-identical to a schema-7 one wherever nothing is chance-yielded. The diff
        // between two dumps is then exactly the outputs this issue changed.
        String certain = DumpCommand.flatStacks(slots(slot(stick())), null, new float[] {1.0f});
        String unread = DumpCommand.flatStacks(slots(slot(stick())), null, null);
        assertFalse(certain, certain.contains("q"));
        assertEquals("a chance of exactly 1.0 must emit the pre-#223 bytes", unread, certain);
    }

    @Test
    public void anEmptyOutputSlotDoesNotShiftTheChanceOntoTheNextProduct() {
        // THE OFF-BY-ONE THIS ISSUE CANNOT AFFORD. `flatStacks` drops a slot that yields no
        // stack, so its emitted list is shorter than the slot list it walked, while the chance
        // array is aligned to the SLOT list because that is what Modular Machinery's
        // requirements correspond to. Reading the chance off the emitted position instead
        // would hang slot 1's 0.25 on the only product there is, inventing a recipe that
        // needs four times the runs it needs.
        String json = DumpCommand.flatStacks(slots(slot(), slot(stick())), null,
                                             new float[] {1.0f, 0.25f});
        assertEquals("[{\"i\":\"minecraft:stick\",\"m\":0,\"c\":1,\"q\":0.25}]", json);
    }

    @Test
    public void aChanceOnADroppedSlotDoesNotLeakOntoTheSurvivingOne() {
        // The same misalignment in the other direction, which is the one that invents a FREE
        // recipe rather than an expensive one: slot 0 is chanced and yields nothing, slot 1 is
        // certain. An emitted-list index would read 0.25 for the stick and understate nothing;
        // it would UNDERSTATE the runs. Neither direction may happen.
        String json = DumpCommand.flatStacks(slots(slot(), slot(stick())), null,
                                             new float[] {0.25f, 1.0f});
        assertEquals("[{\"i\":\"minecraft:stick\",\"m\":0,\"c\":1}]", json);
    }

    @Test
    public void eachOutputSlotGetsItsOwnChance() {
        String json = DumpCommand.flatStacks(slots(slot(stick()), slot(apple())), null,
                                             new float[] {0.5f, 0.125f});
        assertEquals("[{\"i\":\"minecraft:stick\",\"m\":0,\"c\":1,\"q\":0.5},"
                     + "{\"i\":\"minecraft:apple\",\"m\":0,\"c\":1,\"q\":0.125}]", json);
    }

    @Test
    public void aShortChanceArrayLeavesTheRestCertainRatherThanThrowing() {
        // A bridge that answered about fewer slots than the wrapper reported must cost the
        // slots it did not describe, not the dump. Losing 335,000 recipes to an
        // ArrayIndexOutOfBounds is a far worse failure than one unmarked yield.
        String json = DumpCommand.flatStacks(slots(slot(stick()), slot(apple())), null,
                                             new float[] {0.5f});
        assertEquals("[{\"i\":\"minecraft:stick\",\"m\":0,\"c\":1,\"q\":0.5},"
                     + "{\"i\":\"minecraft:apple\",\"m\":0,\"c\":1}]", json);
    }

    // --------------------------------------------------- the two chances stay on their side

    @Test
    public void anInputStackCarriesPAndNeverQ() {
        // `p` and `q` ARE OPPOSITE FACTS AND THE EMITTER MUST NOT CONFUSE THEM. `p` says a run
        // may not spend this input, which makes the recipe cheaper; `q` says a run may not
        // yield this output, which makes it dearer. An input that emitted `q` would tell the
        // solver a recipe sometimes fails to consume its own ingredient, which is not a
        // sentence the model has.
        String json = DumpCommand.stackSlots(slots(slot(stick())), null, new float[] {0.0f});
        assertEquals("[[{\"i\":\"minecraft:stick\",\"m\":0,\"c\":1,\"p\":0.0}]]", json);
        assertFalse(json, json.contains("q"));
    }

    @Test
    public void anOutputStackCarriesQAndNeverP() {
        // The mirror, and the one that matters more: `p` on an output reads as "this output is
        // a catalyst" to every consumer of `p`, which is a confident wrong answer rather than
        // a parse error. `DumpCommand.inputStack` and `outputStack` exist to make it
        // unreachable from a call site.
        String json = DumpCommand.flatStacks(slots(slot(stick())), null, new float[] {0.001f});
        assertFalse(json, json.contains("\"p\""));
        assertTrue(json, json.contains("\"q\":0.001"));
    }

    // ------------------------------------------------------------------------ the counter

    @Test
    public void theCountMatchesTheQFieldsTheDumpActuallyWrote() {
        // A COUNT THAT CANNOT BE CHECKED IS NOT A MEASUREMENT. `chance_outputs` exists so a
        // reader can tell "this pack has no chance outputs" from "the bridge stopped
        // resolving", and it can only do that if the number is exactly how many `q` fields
        // recipes.ndjson holds. So it is counted on the EMISSION: the empty slot below is
        // chanced and writes nothing, and must not be counted.
        DumpCommand.KeySink sink = new DumpCommand.KeySink(false);
        String json = DumpCommand.flatStacks(
                slots(slot(stick()), slot(), slot(apple()), slot(stick())), sink,
                new float[] {0.5f, 0.5f, 1.0f, 0.25f});
        int written = json.split("\"q\":", -1).length - 1;
        assertEquals(2, written);
        assertEquals(written, sink.chanceOutputs());
    }

    @Test
    public void aDumpWithNoChanceOutputsCountsZeroRatherThanNothing() {
        DumpCommand.KeySink sink = new DumpCommand.KeySink(false);
        DumpCommand.flatStacks(slots(slot(stick())), sink, null);
        assertEquals(0, sink.chanceOutputs());
    }

    @Test
    public void theSummaryDeclaresTheChanceOutputCountEvenWhenItIsZero() throws Exception {
        // ZERO IS A MEASUREMENT AND ABSENT IS NOT, exactly as for `catalyst_slots`. A dump
        // whose reader quietly stopped resolving emits no `q` at all, which is byte-for-byte
        // what a pack with no chance outputs emits; the only symptom is that plans understate
        // the runs they need, and nothing about the artifact says so.
        String json = summary(0);
        assertTrue(json, json.contains("\"chance_outputs\": 0"));
    }

    @Test
    public void aDumpThatFoundChanceOutputsSaysHowMany() throws Exception {
        String json = summary(834);
        assertTrue(json, json.contains("\"chance_outputs\": 834"));
        // Beside `catalyst_slots` rather than instead of it: the two sides are counted
        // separately because either reader can break while the other works.
        assertTrue(json, json.contains("\"catalyst_slots\": 0"));
    }

    private static String summary(int chanceOutputs) throws Exception {
        java.io.File file = java.io.File.createTempFile("summary-eight", ".json");
        file.deleteOnExit();
        DumpCommand.writeSummary(file, new java.util.LinkedHashMap<String, int[]>(),
                                 new java.util.LinkedHashMap<String, String>(),
                                 0, 0, 0, 0, 0, 0, chanceOutputs, Arrays.asList("jei"));
        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                          java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------- the bridge

    @Test
    public void theOutputChanceReaderIsInertWithoutModularMachinery() {
        // No Modular Machinery on the test classpath, which is the state every pack without it
        // is in. NULL AND 1.0 ARE DIFFERENT ANSWERS: null means "nothing to say", and every
        // slot then keeps the pre-#223 default; an array of 1.0 would be a positive claim that
        // the recipe always yields, made by a reader that could not find its mod.
        assertNull(ModularMachineryBridge.itemOutputChances(
                null, Collections.<List<Object>>emptyList()));
        assertNull(ModularMachineryBridge.itemOutputChances(
                new Object(), Collections.<List<Object>>emptyList()));
        assertNull(ModularMachineryBridge.itemOutputChances(new Object(),
                slots(slot(stick()))));
    }

    @Test
    public void theChanceReaderSaysWhyItCannotAnswer() {
        // The `/recipedump` line depends on this being a sentence rather than an empty string.
        // Announced separately from the machine-name absence because it resolves different
        // classes: `DynamicRecipeWrapper` and `RequirementItem` rather than `MachineRegistry`
        // and `ItemBlueprint`, so either can fail while the other works.
        assertFalse(ModularMachineryBridge.chancesAvailable());
        assertTrue(ModularMachineryBridge.chanceAbsence(),
                   ModularMachineryBridge.chanceAbsence().length() > 0);
    }

    @Test
    public void theIoTypeKeysSelectDifferentSidesOfTheOrderedMap() {
        // THE ONE PART OF THE READER THAT CAN BE EXERCISED WITHOUT MODULAR MACHINERY, and the
        // part with the most silent failure. `IO_INPUT` and `IO_OUTPUT` are matched against
        // `IOType.toString()` at runtime, so a typo in either, or an `itemOutputChances` that
        // delegated with `IO_INPUT` by copy-paste, produces a dump that is simply missing a
        // side. Every other assertion on this classpath is that the reader returns null,
        // which such a bug satisfies perfectly.
        List<Object> inputReqs = slot();
        List<Object> outputReqs = slot();
        java.util.Map<String, java.util.Map<Class<?>, Object>> byIoType =
                new java.util.LinkedHashMap<String, java.util.Map<Class<?>, Object>>();
        byIoType.put(ModularMachineryBridge.IO_INPUT,
                     Collections.<Class<?>, Object>singletonMap(ItemStack.class, inputReqs));
        byIoType.put(ModularMachineryBridge.IO_OUTPUT,
                     Collections.<Class<?>, Object>singletonMap(ItemStack.class, outputReqs));

        assertSame(inputReqs,
                   ModularMachineryBridge.itemRequirements(byIoType,
                                                           ModularMachineryBridge.IO_INPUT));
        assertSame(outputReqs,
                   ModularMachineryBridge.itemRequirements(byIoType,
                                                           ModularMachineryBridge.IO_OUTPUT));
        // A key the map does not hold answers "nothing to say" rather than falling through to
        // whichever side happens to be first, which is what an `equals` written backwards or a
        // loop missing its `continue` would do.
        assertNull(ModularMachineryBridge.itemRequirements(byIoType, "OUPUT"));
    }

    @Test
    public void aSideWithNoItemRequirementsIsNotTheOtherSide() {
        // An OUTPUT bucket holding only fluids must answer null, not the input list. MM keys
        // the inner map by JEI requirement class, so this is the shape every energy-only or
        // fluid-only side of a recipe has, and it is common.
        java.util.Map<String, java.util.Map<Class<?>, Object>> byIoType =
                new java.util.LinkedHashMap<String, java.util.Map<Class<?>, Object>>();
        byIoType.put(ModularMachineryBridge.IO_INPUT,
                     Collections.<Class<?>, Object>singletonMap(ItemStack.class, slot()));
        byIoType.put(ModularMachineryBridge.IO_OUTPUT,
                     Collections.<Class<?>, Object>singletonMap(String.class, slot()));

        assertNull(ModularMachineryBridge.itemRequirements(byIoType,
                                                           ModularMachineryBridge.IO_OUTPUT));
    }

    // ------------------------------------------------------------------------- the seam

    /**
     * Every method the SHIPPED MOD calls, read out of every production class's constant pool.
     *
     * THE WHOLE MOD RATHER THAN `DumpCommand.class` ALONE, because the walk is spread across
     * nested classes: `flatStacks` lives on DumpCommand and calls `recordChanceOutput`, while
     * the summary is written by `DumpCommand$Runner` and is where `chanceOutputs()` is read.
     * Narrowing to the outer class asserted a fact about javac's nesting rather than about the
     * seam, and it failed on correct code. The screenshot harness is excluded for the reason
     * `SeamInstallationTest` gives: code no player reaches is not production, and a call wired
     * only there would give a working screenshot and a dump with no `q` in it.
     */
    private static java.util.Set<String> productionCalls() throws Exception {
        String harness = ClassFiles.ROOT_PACKAGE + "/shot/";
        java.util.Set<String> refs = new java.util.LinkedHashSet<String>();
        List<java.io.File> classes = ClassFiles.under(ClassFiles.ROOT_PACKAGE);
        assertTrue("no compiled classes found to scan", classes.size() > 0);
        for (java.io.File cls : classes) {
            if (ClassFiles.internalName(cls).startsWith(harness)) {
                continue;
            }
            refs.addAll(ClassFiles.methodReferences(ClassFiles.read(cls)));
        }
        return refs;
    }

    private static void assertCalls(java.util.Set<String> refs, String owner, String method) {
        String prefix = ClassFiles.ROOT_PACKAGE + "/" + owner + "." + method + "(";
        for (String ref : refs) {
            if (ref.startsWith(prefix)) {
                return;
            }
        }
        org.junit.Assert.fail("nothing in the shipped mod calls " + owner + "." + method
                              + "; the seam is installed nowhere");
    }

    @Test
    public void theEmitterActuallyCallsTheOutputChanceReader() throws Exception {
        // A READER NOTHING CALLS IS THE SEAM DEFECT `SeamInstallationTest` EXISTS FOR, and
        // this one is invisible to every other test in this file: they all drive `flatStacks`
        // with a chance array supplied by hand, so deleting the third argument at the one
        // production call site in `encode` leaves them all green and the dump with no `q` in
        // it at all. Read out of the constant pool, so a call javac did not compile cannot
        // pass. #223.
        java.util.Set<String> refs = productionCalls();
        assertCalls(refs, "ModularMachineryBridge", "itemOutputChances");
        assertCalls(refs, "DumpCommand$KeySink", "recordChanceOutput");
        assertCalls(refs, "DumpCommand$KeySink", "chanceOutputs");
    }

    @Test
    public void theDumpAnnouncesTheChanceReaderWhenItCannotResolve() throws Exception {
        // The `/recipedump` line, asserted the only way it can be without a command sender.
        // Its whole job is that a dump with no chances says so at the moment it can still be
        // acted on, and a reply nothing reaches is the same silence it was added to end.
        java.util.Set<String> refs = productionCalls();
        assertCalls(refs, "ModularMachineryBridge", "chancesAvailable");
        assertCalls(refs, "ModularMachineryBridge", "chanceAbsence");
    }

    @Test
    public void bothSidesOfTheChanceReaderShareOneResolution() {
        // One field on one class, read through two named entry points. If these ever disagreed
        // about availability it would mean two resolutions had drifted apart, and the dump
        // would carry `p` without `q` or the reverse, which is worse than carrying neither
        // because the summary would report a half-read pack as a measured one.
        assertNull(ModularMachineryBridge.itemInputChances(new Object(),
                slots(slot(stick()))));
        assertNull(ModularMachineryBridge.itemOutputChances(new Object(),
                slots(slot(stick()))));
    }
}
