package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The parts of schema 5 that can be answered without a running game.
 *
 * The five changes in this schema mostly need the game -- ProjectE's EMC table, Modular
 * Machinery's registry and a GL context are none of them things a headless test can
 * conjure. What IS testable here is the part that decides what the others are ASKED
 * about, and it is the part with a measured wrong answer waiting for it: whether an
 * item's metadata is its durability. Getting that backwards merges nine genuinely
 * different chisel blocks or leaves 46 rows of one axe on the screen, and both failures
 * are invisible until a human looks at a search result.
 */
public class SchemaFiveTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    public void aToolIsDamageableSoItsMetaIsDurability() {
        // The reported case: 46 keys called Iron Axe, one of them holding all the stock.
        assertTrue(DumpCommand.damageable(Items.IRON_AXE));
        assertTrue(DumpCommand.damageable(Items.IRON_SWORD));
        assertTrue(DumpCommand.damageable(Items.IRON_CHESTPLATE));
    }

    @Test
    public void aSubtypeBlockIsNotDamageableHoweverManyMetasItHas() {
        // #110's lesson in vanilla form. Wool has 16 metas and they are 16 colours, not
        // 16 states of wear; the pack's equivalent is chisel:lapis:0 through :8. A rule
        // that collapsed these would undo the distinction #110 established.
        Item wool = Item.getItemFromBlock(Blocks.WOOL);
        assertFalse(DumpCommand.damageable(wool));
        assertFalse(DumpCommand.damageable(Items.DYE));
        assertFalse(DumpCommand.damageable(Items.STICK));
    }

    @Test
    public void aNullItemIsNotDamageable() {
        // writeDamageable walks a registry, and addIconTarget takes whatever a JEI
        // ingredient hands over; neither is entitled to assume non-null.
        assertFalse(DumpCommand.damageable(null));
    }

    @Test
    public void theTwoSuppressFlagsAreIndependent() {
        String[] none = {};
        assertTrue(DumpCommand.wantsTrace(none));
        assertTrue(DumpCommand.wantsIcons(none));

        assertFalse(DumpCommand.wantsTrace(new String[] {"notrace"}));
        assertTrue("notrace must not silently take the icons with it",
                   DumpCommand.wantsIcons(new String[] {"notrace"}));

        assertFalse(DumpCommand.wantsIcons(new String[] {"noicons"}));
        assertTrue("noicons must not silently take the trace with it",
                   DumpCommand.wantsTrace(new String[] {"noicons"}));

        String[] both = {"noicons", "notrace"};
        assertFalse(DumpCommand.wantsTrace(both));
        assertFalse(DumpCommand.wantsIcons(both));
    }

    @Test
    public void anUnknownArgumentStillGetsBothFiles() {
        // The fail-safe direction, and the reason `nbttrace` from the older docs survived
        // in handoffs for months: a mistyped suppress flag writes the file anyway.
        assertTrue(DumpCommand.wantsTrace(new String[] {"nbttrace"}));
        assertTrue(DumpCommand.wantsIcons(new String[] {"icons"}));
        assertTrue(DumpCommand.wantsTrace(null));
        assertTrue(DumpCommand.wantsIcons(null));
    }

    @Test
    public void theFlagsAreCaseAndWhitespaceInsensitive() {
        assertFalse(DumpCommand.wantsIcons(new String[] {" NoIcons "}));
        assertFalse(DumpCommand.wantsTrace(new String[] {" NoTrace "}));
    }

    @Test
    public void theAtlasGeometryDividesEvenly() {
        // icons.json publishes `icon`, `page` and `cols` and the reader multiplies column
        // by icon to find a sprite. A page edge that is not a whole number of sprites puts
        // the last column partly off the page, which reads as a corrupt atlas rather than
        // as arithmetic.
        assertEquals(0, IconAtlas.PAGE_PX % IconAtlas.ICON_PX);
        assertEquals(IconAtlas.PAGE_PX / IconAtlas.ICON_PX, IconAtlas.COLS);
        assertEquals(IconAtlas.COLS * IconAtlas.COLS, IconAtlas.PER_PAGE);
    }

    @Test
    public void theSoftDependenciesReportTheirAbsenceRatherThanThrowing() {
        // Neither ProjectE nor Modular Machinery is on the test classpath, which is the
        // same position as a pack that does not run them. The dump must survive that and
        // must be able to SAY why the file is missing -- an empty reason string would put
        // "no emc.json: " in the player's chat.
        assertFalse(ProjectEBridge.available());
        assertFalse(ProjectEBridge.absence().isEmpty());
        assertEquals(0L, ProjectEBridge.emc(new ItemStack(Items.STICK)));

        assertFalse(ModularMachineryBridge.available());
        assertFalse(ModularMachineryBridge.absence().isEmpty());
        assertTrue(ModularMachineryBridge.machines().isEmpty());
        assertEquals(null,
                ModularMachineryBridge.machineOf(new ItemStack(Items.STICK)));
    }

    /**
     * WRITTEN BY THE REAL WRITER, and this is #123's follow-up in one assertion.
     *
     * On the first real run the icon phase announced 35,675 sprites, filled and wrote page
     * 0 -- 16,361 of 16,384, verified good -- and the game was closed seven seconds later.
     * `icons.json` was only written in the terminal branch, so a complete 3.6 MB atlas page
     * survived with nothing to say which item was where, which makes it worth exactly as
     * much as no page at all. The index is now rewritten after every flush.
     *
     * What has to hold for that to be worth anything is that a PARTIAL index is VALID: it
     * names only the pages on disk, and carries only entries pointing into them.
     *
     */
    @Test
    public void aPartialIndexNamesOnlyThePagesThatWereFlushed() throws Exception {
        IconAtlas atlas = new IconAtlas(null, new File("."),
                new java.util.LinkedHashMap<String, ItemStack>(), new Runnable() {
                    @Override
                    public void run() {
                    }
                });
        atlas.pages.add("icons-0.png");
        atlas.placed.put("minecraft:stone", new int[] {0, 3, 7});
        atlas.placed.put("minecraft:dirt", new int[] {0, 4, 7});

        File out = File.createTempFile("icons", ".json");
        out.deleteOnExit();
        atlas.writeIndex(out);
        String json = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        assertTrue(json, json.contains("\"icon\": " + IconAtlas.ICON_PX));
        assertTrue(json, json.contains("\"cols\": " + IconAtlas.COLS));
        assertTrue(json, json.contains("[\"icons-0.png\"]"));
        assertTrue(json, json.contains("\"minecraft:stone\": [0,3,7]"));
        // The page still being rendered contributes nothing, because nothing was ever put
        // in `placed` for it. A reader that met an entry naming a page that is not in
        // `pages` would have to drop it; this is why it never has to.
        assertFalse(json, json.contains("[1,"));
    }

    // THE SCHEMA NUMBER IS PINNED IN `SchemaSixTest`, NOT HERE. It used to be, spelled
    // `theSchemaIsFive`, and that is a literal that goes stale in a file named for the
    // number it holds: a bump has to edit an assertion whose own name then lies about what
    // it checks. It lives with the current schema's tests and moves with them.
}
