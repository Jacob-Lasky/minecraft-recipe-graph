package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The streaming reader, against a miniature `graph.json`.
 *
 * The document below is written the way `Graph.save` writes one: SORTED KEYS. That ordering
 * is not cosmetic here -- it puts `ore_guessed` before `ore_members` and `names` before
 * `recipes`, and both orderings have already broken a loader that assumed otherwise. Keep
 * the fields sorted when adding to this fixture, because a reader that only works on the
 * order a hand-written test happens to use is a reader that fails on the real file.
 */
public class GraphJsonReaderTest {

    /**
     * Package-visible because `NaiveGraphAgreementTest` reads the SAME bytes into the other
     * model. Two fixtures would let the two readers drift while both stayed green, which is
     * exactly the drift that test exists to catch.
     */
    static final String DOCUMENT = "{"
            + "\"catalysts\":{\"te.pulverizer\":[\"thermalexpansion:machine:1\"]},"
            + "\"category_mods\":{\"te.pulverizer\":\"Thermal Expansion\"},"
            + "\"dimension_ores\":{\"contenttweaker:sednanite_ore\":[147,\"Sedna\"]},"
            + "\"dump_schema\":4,"
            + "\"dump_version\":\"0.8.0\","
            + "\"future_field_nobody_here_knows\":{\"whatever\":[1,2,3]},"
            + "\"instance_dir\":\"/coding/pack\","
            + "\"multiblocks\":{\"catalyzer\":{\"blind\":2,"
            + "\"controller\":\"modularmachinery:catalyzer_controller\","
            + "\"name\":\"Entropic Catalyzer\","
            + "\"parts\":[[40,[\"abyssalcraft:darkstone_brick\"]],"
            + "[3,[\"minecraft:stone\",\"minecraft:cobblestone\"]]],"
            + "\"slots\":92}},"
            + "\"names\":{\"minecraft:iron_ingot\":\"Iron Ingot\","
            + "\"modularmachinery:catalyzer_controller\":\"tile.null.name\"},"
            + "\"ore_guessed\":[\"ingotIron\"],"
            + "\"ore_members\":{\"ingotIron\":[\"minecraft:iron_ingot\"]},"
            + "\"recipes\":[{"
            + "\"cat\":\"crafting_shaped\","
            + "\"id\":\"pack.jar!assets/x/recipes/block.json\","
            + "\"in\":[{\"alt\":[\"ore:ingotIron\",\"ore:ingotIron\"],\"qty\":9}],"
            + "\"machine\":\"Crafting (shaped)\","
            + "\"out\":[{\"key\":\"minecraft:iron_block\",\"qty\":1}],"
            + "\"src\":\"jar_json\"},{"
            + "\"cat\":\"forestry.squeezer\","
            + "\"id\":\"squeeze\","
            + "\"in\":[{\"alt\":[\"forestry:can:1\"],\"qty\":1},"
            + "{\"alt\":[\"fluid:water\"],\"qty\":1000,\"role\":\"fluid\"}],"
            + "\"out\":[{\"key\":\"forestry:ingot_tin\",\"qty\":1}],"
            + "\"src\":\"hei_dump\",\"var\":1,\"xf\":1}]"
            + "}";

    private static RecipeGraph graph;

    @BeforeClass
    public static void read() throws IOException {
        byte[] bytes = DOCUMENT.getBytes("UTF-8");
        graph = GraphJsonReader.read(new ByteArrayInputStream(bytes), bytes.length);
    }

    @Test
    public void everySectionOfTheDocumentReachesTheGraph() {
        assertEquals(2, graph.recipes().count());
        assertEquals(4, graph.dumpSchema());
        assertEquals("0.8.0", graph.dumpVersion());
        assertEquals("/coding/pack", graph.instanceDir());
        assertEquals(1, graph.oreGroupCount());
        assertEquals(1, graph.multiblocks().count());
        assertEquals(1, graph.dimensionOreCount());
    }

    @Test
    public void anUnknownTopLevelSectionIsSkippedRatherThanRefused() {
        // The dump format is free to grow -- nothing here is owed backwards compatibility --
        // and a reader that refuses a graph carrying a field it does not use is a
        // self-inflicted outage.
        assertTrue(graph.keyId("whatever") < 0);
        assertEquals(2, graph.recipes().count());
    }

    @Test
    public void aRepeatedAlternativeIsCollapsedKeepingTheFirstAsCanonical() {
        RecipeStore recipes = graph.recipes();
        int slot = recipes.slotStart(0);
        assertEquals(1, recipes.altEnd(slot) - recipes.altStart(slot));
        assertEquals(graph.keyId("ore:ingotIron"), recipes.altKeyAt(recipes.altStart(slot)));
        assertEquals(9, recipes.slotQty(slot));
    }

    @Test
    public void bothRecipeFlagsSurviveTheirCompactJsonSpelling() {
        // `xf` and `var` are written only when true, so a reader that looked for the field
        // rather than for its value would read every recipe as flagged.
        assertFalse(graph.recipes().isTransfer(0));
        assertFalse(graph.recipes().isVariant(0));
        assertTrue(graph.recipes().isTransfer(1));
        assertTrue(graph.recipes().isVariant(1));
    }

    @Test
    public void aMissingMachineFieldReadsAsNoMachineRatherThanAsAnEmptyName() {
        assertEquals("Crafting (shaped)", graph.machineName(graph.recipes().machineId(0)));
        assertEquals(-1, graph.recipes().machineId(1));
        assertNull(graph.machineName(-1));
    }

    @Test
    public void aSlotsRoleIsCarriedThroughAndDefaultsToItem() {
        RecipeStore recipes = graph.recipes();
        assertEquals("item", graph.roleName(recipes.slotRoleId(recipes.slotStart(1))));
        assertEquals("fluid", graph.roleName(recipes.slotRoleId(recipes.slotStart(1) + 1)));
    }

    @Test
    public void aGuessedGroupReadBeforeItsMembershipStillLandsOnOneGroup() {
        // Sorted keys put `ore_guessed` first. Interning the name on arrival would mint an
        // empty group that `ore_members` then collides with, which is exactly the failure
        // this ordering produced against the real 110 MB file.
        assertEquals(1, graph.oreGroupCount());
        assertTrue(graph.isOreGuessed(graph.oreGroupId("ingotIron")));
        Csr members = graph.oreMembers();
        int group = graph.oreGroupId("ingotIron");
        assertEquals(1, members.count(group));
        assertEquals(graph.keyId("minecraft:iron_ingot"), members.at(members.start(group)));
    }

    @Test
    public void aMultiblocksPartsAndControllerAreReadAsKeyIdsNotStrings() {
        Multiblocks machines = graph.multiblocks();
        int machine = machines.idOf("catalyzer");
        assertTrue(machine >= 0);
        // The registry name is the link to both the machine's recipes and its controller, so
        // it has to survive the round trip as well as the display name does.
        assertEquals("catalyzer", machines.registryName(machine));
        assertEquals("Entropic Catalyzer", machines.displayName(machine));
        assertEquals(graph.keyId("modularmachinery:catalyzer_controller"),
                machines.controllerKeyId(machine));
        assertEquals(92, machines.slots(machine));
        assertEquals(2, machines.blind(machine));
        assertEquals(2, machines.partEnd(machine) - machines.partStart(machine));
        assertEquals(43L, machines.positions());
        // A position satisfied by either of two blocks keeps both, because the cost model
        // prices a structure by what can actually fill each position.
        int second = machines.partStart(machine) + 1;
        assertEquals(2, machines.partAltEnd(second) - machines.partAltStart(second));
    }

    @Test
    public void aDimensionGatedOreCarriesBothItsIdAndItsName() {
        int ore = graph.keyId("contenttweaker:sednanite_ore");
        assertEquals(147, graph.dimensionOf(ore));
        assertEquals("Sedna", graph.dimensionName(ore));
        assertEquals(-1, graph.dimensionOf(graph.keyId("minecraft:iron_ingot")));
        assertNull(graph.dimensionName(graph.keyId("minecraft:iron_ingot")));
    }

    @Test
    public void anUnlocalizedNameSurvivesTheReadAndIsReplacedAtDisplayTime() {
        int controller = graph.keyId("modularmachinery:catalyzer_controller");
        assertTrue(graph.hasName(controller));
        assertEquals("Catalyzer Controller", graph.bareName(controller));
    }
}
