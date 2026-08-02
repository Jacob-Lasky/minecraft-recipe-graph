package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
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
        assertEquals(0L, ProjectEBridge.emc(new net.minecraft.item.ItemStack(Items.STICK)));

        assertFalse(ModularMachineryBridge.available());
        assertFalse(ModularMachineryBridge.absence().isEmpty());
        assertTrue(ModularMachineryBridge.machines().isEmpty());
        assertEquals(null,
                ModularMachineryBridge.machineOf(new net.minecraft.item.ItemStack(Items.STICK)));
    }

    @Test
    public void theSchemaIsFive() {
        // Pinned here as well as read out of the jar's constant pool by
        // tests/test_dist_jar.py, because the python side's dump_meta.SCHEMA has to move
        // with it and `tests/test_catalysts.py` compares the two by reading this source.
        assertEquals(5, DumpCommand.SCHEMA);
    }
}
