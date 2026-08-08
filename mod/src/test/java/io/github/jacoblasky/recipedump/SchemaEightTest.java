package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Dump schema 8: an output stack may say how often a run actually YIELDS it. Issue #223.
 *
 * THE READER HALF LANDED AHEAD OF ANY DUMP THAT CAN WRITE ONE, exactly as #175's did.
 * `Recipe.yield_chance`, `hei_dump._yield_chance`, `GraphJsonReader`, `RecipeStore`, `Cost`
 * and `Solver` all handle `q` already, and `tests/test_schema_eight.py` plus
 * `plan/ChanceOutputTest` cover the arithmetic in both languages. Absent means 1.0, so every
 * dump written before the field existed reads exactly as it did.
 *
 * WHAT THIS FILE OWNS IS THE NUMBER, and the emitter is a separate branch. When the Java side
 * of `q` lands, its assertions belong here beside this one.
 */
public class SchemaEightTest {

    @Test
    public void theSchemaIsEight() {
        // THE TRIPWIRE, INHERITED FROM {@link SchemaSevenTest}, which inherited it from six,
        // which inherited it from five. The newest schema's file owns it; when 9 arrives,
        // move it again.
        //
        // Moving `DumpCommand.SCHEMA` has to be a DECISION. The number tells a reader whether
        // its own recomputation still agrees with the dump, so a bump arriving as a side
        // effect of an unrelated edit is a lie told to every downstream consumer. It is also
        // read out of the jar's constant pool by `tests/test_dist_jar.py` and compared
        // against `dump_meta.SCHEMA` by `tests/test_catalysts.py`, which reads THIS SOURCE --
        // so that comparison catches a rename and cannot catch a compile-time mistake. This
        // half compiles.
        assertEquals(8, DumpCommand.SCHEMA);
    }
}
