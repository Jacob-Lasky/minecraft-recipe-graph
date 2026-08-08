package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Dump schema 7: an item input may say how much of itself a run actually spends. Issue #175.
 *
 * THE READER HALF LANDED LONG BEFORE ANY DUMP COULD WRITE ONE. `Ingredient.consume_chance`,
 * `hei_dump._consume_chance`, `Cost` and `Solver` all handle `p` already, and
 * `tests/test_consumption.py` covers the arithmetic thoroughly in both languages. What is new
 * here is that the EMITTER exists, so this file is about the number and the wiring.
 *
 * WHY A JAVA FILE WHEN THE PYTHON SIDE ALREADY PINS ALL OF THIS. `tests/test_schema_seven.py`
 * asserts against DumpCommand.java by READING ITS SOURCE, which catches a renamed field and
 * cannot catch a compile-time mistake -- a chance passed to the wrong parameter, or a
 * signature that stopped taking one at all, both leave the source text it greps for intact.
 * This half compiles.
 */
public class SchemaSevenTest {

    // THE SCHEMA NUMBER IS PINNED IN `SchemaEightTest`, NOT HERE. It is one constant with one
    // current value, so it belongs to the newest schema's test; asserting 7 here after #223
    // moved it to 8 would be a test that fails on the correct code. What this file still owns
    // is everything schema 7 ADDED, which schema 8 does not change. #223.

    @Test
    public void aBridgeThatCannotResolveItsModSaysSoRatherThanThrowing() {
        // No Tinkers on the test classpath, which is the case every non-Tinkers pack is in.
        // The bridge must be inert rather than fatal: a hard reference here would stop
        // DumpCommand class-loading at all, which is the trap ProjectEBridge documents.
        assertFalse(TinkersCastingBridge.available());
        assertTrue(TinkersCastingBridge.absence(),
                   TinkersCastingBridge.absence().length() > 0);
    }

    @Test
    public void anAbsentBridgeReturnsNoChanceRatherThanADefaultOne() {
        // NULL AND 1.0 ARE DIFFERENT ANSWERS HERE and conflating them would be the quiet
        // failure. Null means "this source has nothing to say", which lets the next source
        // be asked; 1.0 would be a positive claim that the slot IS spent, made by a bridge
        // that could not even find its mod.
        assertNull(TinkersCastingBridge.itemInputChance(null));
    }

    @Test
    public void theModularMachineryChanceReaderIsAlsoInertWithoutItsMod() {
        assertNull(ModularMachineryBridge.itemInputChances(null, java.util.Collections
                .<java.util.List<Object>>emptyList()));
    }

    @Test
    public void anEmptySlotListIsNotAskedAboutAtAll() {
        // A recipe with no item inputs -- a smeltery melt, an energy-only machine step --
        // must not reach either bridge. Cheap, and it is most of the pack.
        assertNull(ModularMachineryBridge.itemInputChances(new Object(), java.util.Collections
                .<java.util.List<Object>>emptyList()));
    }

    @Test
    public void theSummaryDeclaresTheCatalystCountEvenWhenItIsZero() throws Exception {
        // ZERO IS A MEASUREMENT AND ABSENT IS NOT. A bridge that quietly stops resolving --
        // a Tinkers update renaming its recipe class, a pack dropping Modular Machinery --
        // emits no `p` at all, which is byte-for-byte the dump a pack with no catalysts
        // emits. The only symptom would be that plans get slowly more expensive. So the
        // field is written unconditionally, exactly as `names_failed` is.
        java.io.File file = java.io.File.createTempFile("summary", ".json");
        file.deleteOnExit();
        DumpCommand.writeSummary(file, new java.util.LinkedHashMap<String, int[]>(),
                                 new java.util.LinkedHashMap<String, String>(),
                                 0, 0, 0, 0, 0, 0, 0, java.util.Arrays.asList("jei"));
        String json = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                 java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"catalyst_slots\": 0"));
    }

    @Test
    public void aDumpThatFoundCatalystsSaysHowMany() throws Exception {
        java.io.File file = java.io.File.createTempFile("summary", ".json");
        file.deleteOnExit();
        DumpCommand.writeSummary(file, new java.util.LinkedHashMap<String, int[]>(),
                                 new java.util.LinkedHashMap<String, String>(),
                                 0, 0, 0, 0, 0, 14354, 0, java.util.Arrays.asList("jei"));
        String json = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                 java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json, json.contains("\"catalyst_slots\": 14354"));
    }
}
