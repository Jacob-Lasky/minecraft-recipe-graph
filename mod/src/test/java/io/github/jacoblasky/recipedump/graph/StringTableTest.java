package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Interning round-trips.
 *
 * The whole model addresses strings by int, so a table that hands back the wrong string for
 * an id is not a display bug: it is a plan for the wrong item, computed correctly. These
 * cases push the paths where that could happen -- collisions, rehash growth, multi-byte
 * characters, and strings that are prefixes of one another.
 */
public class StringTableTest {

    @Test
    public void everyInternedStringComesBackByItsOwnId() {
        StringTable.Builder builder = StringTable.builder(4, 16, true, true);
        String[] values = {"minecraft:stone", "fluid:water", "ore:ingotIron", ""};
        int[] ids = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            ids[i] = builder.add(values[i]);
        }
        StringTable table = builder.build();
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], table.get(ids[i]));
            assertEquals(ids[i], table.idOf(values[i]));
        }
        assertEquals(values.length, table.size());
    }

    @Test
    public void addingTheSameStringTwiceReturnsTheSameId() {
        StringTable.Builder builder = StringTable.builder(4, 16, true, true);
        int first = builder.add("modularmachinery:mythic_processor_melter_controller");
        int second = builder.add("modularmachinery:mythic_processor_melter_controller");
        assertEquals(first, second);
        assertEquals(1, builder.build().size());
    }

    @Test
    public void anAppendOnlyTableKeepsDuplicatesApart() {
        // Recipe ids go in an append-only table, so two JEI categories handing back the same
        // wrapper id must still occupy their own rows -- the id is addressed by recipe, not
        // looked up by value.
        StringTable.Builder builder = StringTable.builder(4, 16, false, false);
        assertEquals(0, builder.add("same"));
        assertEquals(1, builder.add("same"));
        StringTable table = builder.build();
        assertEquals("same", table.get(0));
        assertEquals("same", table.get(1));
    }

    @Test
    public void aStringThatIsAPrefixOfAnotherIsNotConfusedWithIt() {
        // The blob has no separators, so a length check is the only thing keeping
        // `minecraft:stone` out of `minecraft:stonebrick`'s row.
        StringTable.Builder builder = StringTable.builder(4, 8, true, true);
        int shortId = builder.add("minecraft:stone");
        int longId = builder.add("minecraft:stonebrick");
        StringTable table = builder.build();
        assertFalse(shortId == longId);
        assertEquals("minecraft:stone", table.get(shortId));
        assertEquals("minecraft:stonebrick", table.get(longId));
        assertEquals(shortId, table.idOf("minecraft:stone"));
    }

    @Test
    public void aTableGrowsPastItsHintWithoutLosingAnything() {
        // Deliberately hinted at four entries and eight bytes, so both the blob and the hash
        // index have to grow several times. A rehash that dropped or duplicated a row would
        // show up here and nowhere else.
        StringTable.Builder builder = StringTable.builder(4, 8, true, true);
        int count = 5000;
        for (int i = 0; i < count; i++) {
            assertEquals(i, builder.add("mod:item" + i));
        }
        StringTable table = builder.build();
        assertEquals(count, table.size());
        for (int i = 0; i < count; i++) {
            assertEquals("mod:item" + i, table.get(i));
            assertEquals(i, table.idOf("mod:item" + i));
        }
        assertEquals(-1, table.idOf("mod:item" + count));
    }

    @Test
    public void multiByteCharactersSurviveTheUtf8RoundTrip() {
        // Display names carry section signs and the occasional non-ASCII character, and the
        // blob is indexed in BYTES while a String is indexed in chars.
        StringTable.Builder builder = StringTable.builder(4, 8, true, true);
        String[] values = {"§3Abyssalnite Axe", "Niðhogg", "ツ"};
        for (String value : values) {
            builder.add(value);
        }
        StringTable table = builder.build();
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], table.get(i));
            assertEquals(i, table.idOf(values[i]));
        }
        assertTrue(table.byteLength(2) > 1);
    }

    @Test
    public void aTableBuiltWithoutAnIndexRefusesLookupRatherThanAnsweringSlowly() {
        StringTable table = StringTable.builder(4, 16, true, false).build();
        try {
            table.idOf("anything");
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("lookup index"));
        }
    }
}
