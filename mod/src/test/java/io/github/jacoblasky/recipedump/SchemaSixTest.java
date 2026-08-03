package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
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
    public void theSchemaIsSix() {
        // Pinned here as well as read out of the jar's constant pool by
        // tests/test_dist_jar.py, because the python side's dump_meta.SCHEMA has to move
        // with it and `tests/test_catalysts.py` compares the two by reading this source.
        assertEquals(6, DumpCommand.SCHEMA);
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

        DumpCommand.writeSummary(file, perCategory, categoryMod, 7, 0, 2, 41, 3);
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"names\": 41"));
        assertTrue(json, json.contains("\"names_failed\": 3"));
        assertTrue(json, json.contains("\"schema\": 6"));
    }

    @Test
    public void aCleanDumpStillDeclaresTheCountsAsZero() throws Exception {
        // The whole point of #194: silence and success must not look the same. A summary
        // that omitted the fields when nothing failed would leave a schema-6 dump that lost
        // names indistinguishable from a schema-5 dump that could not say.
        File file = File.createTempFile("summary-clean", ".json");
        file.deleteOnExit();
        DumpCommand.writeSummary(file, new LinkedHashMap<String, int[]>(),
                                 new LinkedHashMap<String, String>(), 0, 0, 0, 0, 0);
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"names\": 0"));
        assertTrue(json, json.contains("\"names_failed\": 0"));
    }
}
