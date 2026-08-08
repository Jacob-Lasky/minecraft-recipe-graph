package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The free-sources list, as the screen that explains why something costs nothing needs it.
 *
 * BUILT FROM `ScenarioInputs.Resolved.freeSources`, which is the same map `solverFor` prices
 * against -- so what this lists and what the plan charged for cannot come apart. That is the
 * whole reason the screen reads a resolved scenario rather than calling `generators.resolve`
 * itself, and `ScenarioInputs`' own header is explicit about the alternative being code the
 * golden gate never touches.
 */
public class SourceTableTest {

    private static RecipeGraph graphWith(String... keys) {
        GraphBuilder b = new GraphBuilder();
        for (String key : keys) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:leaf"));
            b.endSlot();
            b.output(b.key(key), 1);
            b.endRecipe("r_" + key, "cat.one", "Machine", "test", false, false);
        }
        return b.build();
    }

    private static Map<Integer, String> free(RecipeGraph graph, String... keyAndWhy) {
        Map<Integer, String> out = new LinkedHashMap<Integer, String>();
        for (int i = 0; i < keyAndWhy.length; i += 2) {
            out.put(Integer.valueOf(graph.keyId(keyAndWhy[i])), keyAndWhy[i + 1]);
        }
        return out;
    }

    @Test
    public void rowsAreSortedByKeySoTheListCanBeScannedForOne() {
        // `sources_page` sorts the same way and it is right for a list a reader SCANS rather
        // than ranks. The resolution order is generator-declaration order: stable, and
        // arbitrary to anyone reading it.
        RecipeGraph graph = graphWith("mod:zinc", "mod:apple", "mod:mud");
        SourceTable table = SourceTable.of(graph, free(graph,
                "mod:zinc", "placed: mod:zinc_press",
                "mod:apple", "placed: mod:orchard",
                "mod:mud", "curated default"));
        assertEquals(3, table.size());
        assertEquals("mod:apple", table.rows().get(0).key());
        assertEquals("mod:mud", table.rows().get(1).key());
        assertEquals("mod:zinc", table.rows().get(2).key());
    }

    @Test
    public void theEvidenceIsCarriedThroughVerbatim() {
        // The sentence IS the answer to "why is this free". Rewording it here would be a second
        // vocabulary for something `generators.resolve` already spells.
        RecipeGraph graph = graphWith("mod:water");
        SourceTable table = SourceTable.of(graph, free(graph,
                "mod:water", "placed: nuclearcraft:water_source"));
        assertEquals("placed: nuclearcraft:water_source", table.rows().get(0).why());
    }

    @Test
    public void anUnnamedKeyGetsItsPrettifiedRegistryPathAndNotTheRawKey() {
        // THIS TEST FAILED FIRST AND THE MODEL WAS WRONG, not the test. `SourceTable` carried a
        // fall back to the raw key for a name that "might" be blank; `RecipeGraph.recordedName`
        // is total, so that branch was unreachable AND it was the wrong answer. `bareName`'s own
        // note says why: "a raw key sitting next to properly-cased names reads as a variable
        // rather than an item" -- and fluids and oredict entries, which routinely have no
        // recorded name, are exactly the rows this screen exists to explain.
        RecipeGraph graph = graphWith("mod:unnamed");
        SourceTable table = SourceTable.of(graph, free(graph, "mod:unnamed", "curated"));
        assertEquals("Unnamed", table.rows().get(0).name());
        assertEquals("mod:unnamed", table.rows().get(0).key());
    }

    @Test
    public void anUnderscoredPathBecomesSeparateCapitalisedWords() {
        // The half that would pass on a prettifier that only capitalised the first letter, and
        // the shape most modded registry names actually take.
        RecipeGraph graph = graphWith("mod:water_source_block");
        SourceTable table = SourceTable.of(graph, free(graph, "mod:water_source_block", "x"));
        assertEquals("Water Source Block", table.rows().get(0).name());
    }

    @Test
    public void aRecordedNameBeatsThePrettifiedPath() {
        // The other half: with a real name recorded, that is what shows -- so the two tests
        // together pin both branches of `recordedName` rather than only the unnamed one.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:water"), "Water");
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key("mod:water"), 1);
        b.endRecipe("r", "cat.one", "Machine", "test", false, false);
        RecipeGraph graph = b.build();

        SourceTable table = SourceTable.of(graph, free(graph, "mod:water", "curated"));
        assertEquals("Water", table.rows().get(0).name());
        assertEquals("mod:water", table.rows().get(0).key());
    }

    @Test
    public void anEmptyResolutionIsAnEmptyTableRatherThanAThrow() {
        // With `placed` unread and no curated default matching the pack, nothing is free. That
        // is a real state the screen has to draw, and it says so rather than showing a blank.
        RecipeGraph graph = graphWith("mod:thing");
        SourceTable table = SourceTable.of(graph, new LinkedHashMap<Integer, String>());
        assertEquals(0, table.size());
        assertTrue(table.rows().isEmpty());
    }

    @Test
    public void aNullEvidenceSentenceBecomesEmptyRatherThanReachingTheWidget() {
        // A null handed to `IKey.str` throws inside a panel build, and
        // `WidgetTree.resizeInternal` swallows it -- so the symptom is the whole screen at 0x0
        // rather than a stack trace naming this map.
        RecipeGraph graph = graphWith("mod:thing");
        Map<Integer, String> free = new LinkedHashMap<Integer, String>();
        free.put(Integer.valueOf(graph.keyId("mod:thing")), null);
        assertEquals("", SourceTable.of(graph, free).rows().get(0).why());
    }
}
