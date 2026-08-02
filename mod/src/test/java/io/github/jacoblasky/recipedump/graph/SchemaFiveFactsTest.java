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
 * The five per-item fact tables dump schema 5 added, and the display rules they drive.
 *
 * Read from JSON rather than hand-built, because the reader is half of what is being asserted
 * -- these arrived after the first version of this package and were silently SKIPPED as
 * unknown sections, which under-reported the graph's heap by everything they weigh. A test
 * that only exercised the builder would still pass with the reader ignoring them.
 *
 * Sections appear here in the SORTED order `Graph.save` writes them, which puts
 * `blueprint_machines` a long way before the `machine_names` it joins to and `emc` before the
 * keys any recipe mentions. Both orderings are load-bearing and neither is obvious.
 */
public class SchemaFiveFactsTest {

    /**
     * Package-visible because `NaiveGraphAgreementTest` reads the SAME bytes into the other
     * model, to prove neither reader is quietly skipping a section.
     */
    static final String DOCUMENT = "{"
            + "\"blueprint_machines\":{"
            + "\"modularmachinery:itemblueprint#010c58f252c0\":"
            + "\"modularmachinery:dragonfire_crucible\","
            + "\"modularmachinery:itemblueprint#02570568db68\":\"modularmachinery:orphaned\"},"
            + "\"dump_schema\":5,"
            + "\"emc\":{\"minecraft:stone\":1,\"minecraft:diamond\":8192,"
            + "\"avaritia:resource:5\":422212465065984,\"mod:worthless\":0},"
            + "\"icons\":{\"icon\":16,\"cols\":128,"
            + "\"pages\":[\"icons-0.png\",\"icons-1.png\"],"
            + "\"keys\":{\"minecraft:diamond\":[1,3,4],\"minecraft:stone\":[0,0,0]}},"
            + "\"machine_names\":{"
            + "\"modularmachinery:dragonfire_crucible\":\"Dragonfire Crucible\"},"
            + "\"max_damage\":{\"minecraft:iron_axe\":250},"
            + "\"names\":{\"minecraft:iron_axe\":\"Iron Axe\","
            + "\"chisel:lapis\":\"Lapis Block\","
            + "\"modularmachinery:itemblueprint#010c58f252c0\":\"Machine Blueprint\","
            + "\"modularmachinery:itemblueprint#02570568db68\":\"Machine Blueprint\"},"
            + "\"recipes\":[]"
            + "}";

    private static RecipeGraph graph;

    @BeforeClass
    public static void read() throws IOException {
        byte[] bytes = DOCUMENT.getBytes("UTF-8");
        graph = GraphJsonReader.read(new ByteArrayInputStream(bytes), bytes.length);
    }

    private static int id(String key) {
        int keyId = graph.keyId(key);
        assertTrue("fixture does not know " + key, keyId >= 0);
        return keyId;
    }

    @Test
    public void aDamageableItemsMetaReadsAsDurabilityAndSaysSo() {
        // A bare "(187)" beside an item name reads as a variant number and is in fact a
        // durability reading, which is how 46 rows of one Iron Axe looked like 46 items. #118
        int worn = graph.keyId("minecraft:iron_axe:187");
        assertTrue(worn < 0);
        GraphBuilder b = new GraphBuilder();
        b.name("minecraft:iron_axe", "Iron Axe");
        int stem = b.key("minecraft:iron_axe");
        b.damageable(stem, 250);
        int wornKey = b.key("minecraft:iron_axe:187");
        RecipeGraph withWear = b.build();
        assertEquals("Iron Axe (187/250 damage)", withWear.bareName(wornKey));
        assertEquals(187, withWear.damage(wornKey));
        assertEquals(250, withWear.maxDamage(wornKey));
    }

    @Test
    public void aMetaThatIsARealSubtypeIsLeftAloneBecauseTheGateIsPackData() {
        // Every structural rule anyone proposed for "is this meta a durability value" also
        // matched the nine genuinely distinct `chisel:lapis` blocks. The gate is the registry
        // table, not the shape of the key.
        GraphBuilder b = new GraphBuilder();
        b.name("chisel:lapis", "Lapis Block");
        int subtype = b.key("chisel:lapis:3");
        RecipeGraph plain = b.build();
        assertEquals("Lapis Block (3)", plain.bareName(subtype));
        assertEquals(-1, plain.damage(subtype));
        assertEquals(-1, plain.maxDamage(subtype));
        assertEquals("chisel:lapis:3", plain.damageBase("chisel:lapis:3"));
    }

    @Test
    public void collapsingAWornKeyKeepsItsNbtDigest() {
        // A named or enchanted tool is still its own item; only the durability tick
        // collapses. The digest is split off and re-attached rather than being allowed to
        // reach `split_key`, which does not know about it.
        GraphBuilder b = new GraphBuilder();
        b.damageable(b.key("minecraft:iron_axe"), 250);
        RecipeGraph tools = b.build();
        assertEquals("minecraft:iron_axe", tools.damageBase("minecraft:iron_axe:187"));
        assertEquals("minecraft:iron_axe#48a337d94489",
                tools.damageBase("minecraft:iron_axe:187#48a337d94489"));
        // Undamaged and non-damageable keys pass through untouched.
        assertEquals("minecraft:iron_axe", tools.damageBase("minecraft:iron_axe"));
        assertEquals("fluid:water", tools.damageBase("fluid:water"));
    }

    @Test
    public void theMaxDamageTableSurvivesTheJsonRoundTrip() {
        assertEquals(1, graph.damageableCount());
        assertEquals(250, graph.maxDamage(id("minecraft:iron_axe")));
    }

    @Test
    public void emcIsReadAsALongBecauseTheLargestValueOverflowsAnInt() {
        // 422,212,465,065,984 is four orders of magnitude past what an int holds. A narrower
        // column wraps the most expensive item in the pack to something small and positive,
        // which prices it as nearly free -- a wrong answer that looks like a bargain.
        assertEquals(422212465065984L, graph.emc(id("avaritia:resource:5")));
        assertEquals(8192L, graph.emc(id("minecraft:diamond")));
        assertEquals(4, graph.emcCount());
    }

    @Test
    public void anItemWithNoEmcIsDistinguishableFromOneWorthZero() {
        // ProjectE uses 0 for "explicitly worthless", so a caller has to be able to tell that
        // from "not in the table". Collapsing them would make an unpriced item free.
        assertEquals(0L, graph.emc(id("mod:worthless")));
        assertEquals(-1L, graph.emc(id("minecraft:iron_axe")));
    }

    @Test
    public void aBlueprintIsNamedForTheMachineItBuilds() {
        // All 259 blueprints are genuinely named "Machine Blueprint", so a plan for any
        // multiblock reads "1 of 261 possibilities" without this. The PARENTHETICAL form is
        // deliberate: it is findable under both the name the game shows and the name in the
        // player's JEI. #55
        int blueprint = id("modularmachinery:itemblueprint#010c58f252c0");
        assertEquals("Machine Blueprint (Dragonfire Crucible)", graph.bareName(blueprint));
        assertEquals("Machine Blueprint (Dragonfire Crucible)", graph.display(blueprint));
        assertEquals("modularmachinery:dragonfire_crucible",
                graph.blueprints().machineIdOf(blueprint));
    }

    @Test
    public void aBlueprintWhoseMachineIsUnnamedFallsBackRatherThanDecoratingTheAbsence() {
        // No data means the old behaviour, not "Machine Blueprint ()".
        int orphan = id("modularmachinery:itemblueprint#02570568db68");
        assertNull(graph.blueprintName(orphan));
        assertEquals("Machine Blueprint", graph.bareName(orphan));
    }

    @Test
    public void anOrdinaryItemIsNotMistakenForABlueprint() {
        assertNull(graph.blueprintName(id("minecraft:iron_axe")));
        assertEquals("Iron Axe", graph.bareName(id("minecraft:iron_axe")));
        assertEquals(2, graph.blueprints().blueprintCount());
    }

    @Test
    public void theIconAtlasCarriesOnlyTheIndexAndNotThePixels() {
        // The PNG pages travel beside graph.json. Base64 in the document would inflate them
        // by a third and make every graph load pay for pictures it may never draw.
        IconAtlas atlas = graph.icons();
        // `icon` is the sprite EDGE LENGTH in pixels and `pages` is the list of PNG
        // filenames. Both were guessed wrong from a docstring while this section was empty in
        // every graph, which is exactly the shape of mistake a fixture with real values
        // prevents -- these numbers come from the pack's own atlas.
        assertEquals(16, atlas.iconSize());
        assertEquals(128, atlas.columns());
        assertEquals(2, atlas.pageCount());
        assertEquals("icons-1.png", atlas.page(1));
        assertEquals(2, atlas.size());
        int diamond = id("minecraft:diamond");
        assertEquals(1, atlas.pageOf(diamond));
        assertEquals(3, atlas.column(diamond));
        assertEquals(4, atlas.row(diamond));
        assertTrue(atlas.has(id("minecraft:stone")));
        assertFalse(atlas.has(id("minecraft:iron_axe")));
        assertEquals(-1, atlas.pageOf(id("minecraft:iron_axe")));
    }

    @Test
    public void aGraphWithNoneOfTheseSectionsBehavesExactlyAsBefore() {
        // Every one of the five means "the feature is off" when absent, never "something is
        // broken". A pre-schema-5 graph goes on working unchanged.
        RecipeGraph old = new GraphBuilder().build();
        assertEquals(0, old.damageableCount());
        assertEquals(0, old.emcCount());
        assertEquals(0, old.blueprints().blueprintCount());
        assertEquals(0, old.icons().size());
        assertEquals(0, old.icons().iconSize());
        assertEquals(0, old.icons().pageCount());
        assertEquals("mod:thing:3", old.damageBase("mod:thing:3"));
    }

    @Test
    public void theItemFactsBucketIsAccountedSeparatelyFromEverythingElse() {
        // Its own line in the report because these arrived at once with schema 5 and the dump
        // format is still moving: "what did the last bump cost us" has to be answerable from
        // one run rather than by diffing two.
        GraphSizes sizes = graph.sizes();
        assertTrue(sizes.itemFacts > 0);
        assertEquals(sizes.keyTable + sizes.recipes + sizes.names + sizes.adjacency
                + sizes.itemFacts + sizes.other, sizes.total());
    }
}
