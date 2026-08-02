package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The two models must read the same file into the same graph.
 *
 * WHY THIS TEST EXISTS. {@code NaiveGraph} is the BEFORE in the heap comparison reported on
 * #126: it is the number that says the flat-array design was necessary rather than merely
 * tidy. That comparison is only honest if both readers ingest the same data -- a naive model
 * that quietly stopped parsing a section would look cheaper and flatter the compact one by
 * exactly the amount it dropped.
 *
 * Nothing in production depends on {@code NaiveGraph}, so without this it would rot in
 * silence and the next person to re-run the measurement would get a number they could not
 * defend. It reads the SAME bytes as {@code GraphJsonReaderTest}, deliberately.
 */
public class NaiveGraphAgreementTest {

    private static RecipeGraph compact;
    private static NaiveGraph naive;

    @BeforeClass
    public static void readBoth() throws IOException {
        byte[] bytes = GraphJsonReaderTest.DOCUMENT.getBytes("UTF-8");
        compact = GraphJsonReader.read(new ByteArrayInputStream(bytes), bytes.length);
        naive = NaiveGraph.read(new ByteArrayInputStream(bytes), false);
    }

    @Test
    public void bothModelsHoldTheSameNumberOfRecipesAndNames() {
        assertEquals(naive.recipeCount(), compact.recipes().count());
        assertEquals(naive.names().size(), compact.namedKeyCount());
        assertEquals(naive.oreGroups().size(), compact.oreGroupCount());
        assertEquals(naive.worldOreKeys().size(), compact.worldOreCount());
    }

    @Test
    public void bothModelsReadEverySchemaFiveSection() throws IOException {
        // Skipping a section makes the naive model look cheaper than it is by exactly what
        // that section weighs, which flatters the compact one in the comparison this class
        // exists to keep honest. All five were in fact being skipped once already.
        byte[] bytes = SchemaFiveFactsTest.DOCUMENT.getBytes("UTF-8");
        RecipeGraph small = GraphJsonReader.read(new ByteArrayInputStream(bytes), bytes.length);
        NaiveGraph plain = NaiveGraph.read(new ByteArrayInputStream(bytes), false);
        assertEquals(plain.maxDamageCount(), small.damageableCount());
        assertEquals(plain.emcCount(), small.emcCount());
        assertEquals(plain.blueprintCount(), small.blueprints().blueprintCount());
        assertEquals(plain.machineNameCount(), small.blueprints().namedMachineCount());
        assertEquals(plain.iconCount(), small.icons().size());
    }

    @Test
    public void bothModelsAgreeOnWhichKeysAreProducedAndConsumed() {
        assertEquals(naive.outputKeys(), producedKeys());
        assertEquals(naive.inputKeys(), consumedKeys());
        assertTrue("fixture produces nothing; the comparison would be vacuous",
                naive.outputKeys().size() > 0);
    }

    @Test
    public void bothModelsAgreeOnHowManyRecipesTouchEachKey() {
        // Edge COUNTS, not just key sets, because the two indexes deduplicate differently by
        // construction: by-input collapses a recipe's repeated slots while by-output keeps a
        // repeated output stack. A model that got either rule wrong agrees on the key set
        // and disagrees here.
        for (String key : naive.outputKeys()) {
            int id = compact.keyId(key);
            assertEquals(key, naive.producerCount(key), compact.byOutput().count(id));
        }
        for (String key : naive.inputKeys()) {
            int id = compact.keyId(key);
            assertEquals(key, naive.consumerCount(key), compact.byInput().count(id));
        }
    }

    @Test
    public void bothModelsAgreeOnEveryRecordedLabel() {
        for (java.util.Map.Entry<String, String> entry : naive.names().entrySet()) {
            int id = compact.keyId(entry.getKey());
            assertTrue(entry.getKey(), id >= 0);
            assertTrue(entry.getKey(), compact.hasName(id));
        }
    }

    @Test
    public void interningKeysChangesTheRepresentationAndNothingElse() throws IOException {
        // The middle rung of the heap ladder. If interning changed what was read, the
        // 364 MB -> 173 MB step would be measuring two different graphs.
        byte[] bytes = GraphJsonReaderTest.DOCUMENT.getBytes("UTF-8");
        NaiveGraph interned = NaiveGraph.read(new ByteArrayInputStream(bytes), true);
        assertEquals(naive.recipeCount(), interned.recipeCount());
        assertEquals(naive.outputKeys(), interned.outputKeys());
        assertEquals(naive.inputKeys(), interned.inputKeys());
        assertEquals(naive.names(), interned.names());
    }

    private static Set<String> producedKeys() {
        Set<String> out = new HashSet<String>();
        for (int key = 0; key < compact.keyCount(); key++) {
            if (compact.byOutput().count(key) > 0) {
                out.add(compact.key(key));
            }
        }
        return out;
    }

    private static Set<String> consumedKeys() {
        Set<String> out = new HashSet<String>();
        for (int key = 0; key < compact.keyCount(); key++) {
            if (compact.byInput().count(key) > 0) {
                out.add(compact.key(key));
            }
        }
        return out;
    }
}
