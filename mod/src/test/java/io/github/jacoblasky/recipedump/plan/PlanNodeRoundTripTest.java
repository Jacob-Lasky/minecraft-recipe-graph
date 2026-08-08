package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

/**
 * A fixture, read into {@link PlanNode} and written back out, must come out identical.
 *
 * THIS TEST COULD NOT EXIST A DAY AGO, and building it is most of the reason the two
 * `PlanNode` classes were merged into one. The reader lived in `client.planner` over its own
 * node type and the writer lived here over another, so there was no path from a fixture to
 * JSON and no way to ask whether the pair agreed. Each side was self-consistent, which is the
 * failure mode `present.py`'s own docstring describes one level down.
 *
 * WHAT IT ACTUALLY CATCHES is absence. Python OMITS an optional key rather than writing
 * `false` or `0`, and the golden fixtures freeze exactly which keys each node carries. The
 * reader used to default every absent number to zero and every absent flag to false, which
 * was harmless while it fed a renderer -- a row draws the same either way -- and stops being
 * harmless the moment the same object can be written back out. A single `0` substituted for a
 * missing `runs` puts `"runs": 0` on every leaf in the pack and disagrees with all 19 plan
 * fixtures at once.
 *
 * Compared through {@link JsonCompare} rather than as text, for the reason that class exists:
 * 1,966 of 6,012 doubles in the fixture set format differently between the two languages and
 * none of them differ in value.
 */
public class PlanNodeRoundTripTest {

    @Test
    public void everyFixtureTreeSurvivesAReadAndAWriteUnchanged() throws IOException {
        List<File> fixtures = planFixtures();
        Assume.assumeFalse("no tests/fixtures/plan/plan-*.json", fixtures.isEmpty());

        List<String> failures = new ArrayList<String>();
        for (File fixture : fixtures) {
            JsonObject doc = read(fixture);
            JsonObject wanted = doc.getAsJsonObject("result").getAsJsonObject("tree");

            PlanNode node = io.github.jacoblasky.recipedump.client.planner.PlanJson
                    .readNode(wanted);
            String why = JsonCompare.describe(wanted, treeOf(node));
            if (why != null) {
                failures.add(fixture.getName() + ": " + why);
            }
        }
        assertTrue(failures.size() + " of " + fixtures.size()
                + " fixture trees changed on a round trip:\n  " + join(failures),
                failures.isEmpty());
    }

    @Test
    public void aNodeCARRYINGEveryOptionalFieldAlsoRoundTrips() {
        // Every node carries `name`, because that is the one field whose reader keeps a
        // fallback -- see `client.planner.PlanJson.readNode`. It is present on all 571
        // fixture nodes and written unconditionally, so a node without one is not a shape the
        // wire format produces and is not what this test is for.
        //
        // THE FIXTURES ONLY COVER WHAT THEY HAPPEN TO CONTAIN. The field names live in two
        // files -- the writer here and the reader in `client.planner` -- and a rename in one
        // is caught by the round trip only for fields some fixture exercises. This sets EVERY
        // optional field, so a name that no plan happens to use is still pinned.
        //
        // "EVERY" IS LOAD-BEARING AND THIS SET WAS INCOMPLETE UNTIL #223. `not_consumed` and
        // `interchangeable` were both missing, and `not_consumed` was not read back at all --
        // a field the writer had emitted since #175, dropped in a round trip, with no fixture
        // carrying one to notice. Adding a field to `PlanNode` means adding it HERE.
        String json = "{\"key\":\"mod:thing\",\"name\":\"Thing\",\"kind\":\"item\","
                + "\"label\":\"Thing\",\"need\":7,\"status\":\"partial\","
                + "\"from_stock\":3,\"note\":\"a note\",\"recipe\":\"r:1\","
                + "\"category\":\"crafting_shaped\",\"runs\":2,\"per_run\":0.5,"
                + "\"yield_chance\":0.125,"
                + "\"alternatives\":5,\"interchangeable\":4,\"pinned\":true,"
                + "\"machine\":\"Smeltery\","
                + "\"machine_state\":\"buildable\",\"machine_why\":\"craftable: x\","
                + "\"resolved_to\":\"mod:other\",\"alt_count\":3,"
                + "\"not_consumed\":true,"
                + "\"dimension\":\"The End\",\"token_kind\":\"gate\","
                + "\"unsourced\":true,"
                + "\"children\":[{\"key\":\"mod:child\",\"label\":\"Child\","
                + "\"name\":\"Child\",\"kind\":\"item\",\"need\":1,"
                + "\"status\":\"raw\"}]}";
        com.google.gson.JsonElement wanted = new JsonParser().parse(json);
        PlanNode node = io.github.jacoblasky.recipedump.client.planner.PlanJson
                .readNode(wanted.getAsJsonObject());
        String why = JsonCompare.describe(wanted, treeOf(node));
        assertNull(why, why);
    }

    @Test
    public void anAbsentFlagStaysAbsentRatherThanBecomingFalse() {
        // The specific substitution the reader used to make, stated as its own case so a
        // regression names the cause rather than pointing at 19 fixtures.
        JsonObject json = new JsonParser()
                .parse("{\"key\":\"a:b\",\"label\":\"B\",\"kind\":\"item\",\"need\":1,"
                        + "\"status\":\"raw\"}").getAsJsonObject();
        PlanNode node = io.github.jacoblasky.recipedump.client.planner.PlanJson.readNode(json);

        assertNull("pinned was absent and must stay absent", node.pinned);
        assertNull("unsourced was absent and must stay absent", node.unsourced);
        assertNull("from_stock was absent and must stay absent", node.fromStock);
        assertNull("runs was absent and must stay absent", node.runs);
        assertNull("per_run was absent and must stay absent", node.perRun);
        // #223. Absent means CERTAIN, and the reader must not manufacture a 0.0 that
        // `PlanNode.yieldChance()` would then have to distinguish from a real one.
        assertNull("yield_chance was absent and must stay absent", node.yieldChance);
        // AND THE ACCESSOR READS ABSENT AS CERTAIN, not as zero. Every other optional number
        // on this class defaults to 0, and a renderer that assumed the same here would draw
        // every ordinary craft node as a recipe that never works. #223.
        assertEquals(1.0, node.yieldChance(), 0.0);
        assertNull("children was absent, so the field is null", node.children);

        // ...and the accessors still answer without a null check, which is the other half of
        // the contract and the reason the widgets can drop theirs.
        org.junit.Assert.assertFalse(node.pinned());
        org.junit.Assert.assertFalse(node.unsourced());
        org.junit.Assert.assertEquals(0L, node.fromStock());
        org.junit.Assert.assertTrue(node.children().isEmpty());
        org.junit.Assert.assertFalse(node.hasChildren());
    }

    @Test
    public void aPresentFalseIsNotSomethingTheWireFormatHas() {
        // Python writes these flags only when true. A reader that turned an explicit `false`
        // into `Boolean.FALSE` would round-trip it back out and add a key no fixture carries.
        JsonObject json = new JsonParser()
                .parse("{\"key\":\"a:b\",\"label\":\"B\",\"need\":1,\"pinned\":false}")
                .getAsJsonObject();
        PlanNode node = io.github.jacoblasky.recipedump.client.planner.PlanJson.readNode(json);
        assertNull("an explicit false must not become a written key", node.pinned);
    }

    /** The node's own JSON, extracted from a one-node result. */
    private static com.google.gson.JsonElement treeOf(PlanNode node) {
        PlanResult result = new PlanResult();
        result.target = node.key();
        result.targetName = node.name();
        result.qty = node.need();
        result.tree = node;
        result.shoppingList = Collections.<PlanEntry>emptyList();
        result.usedFromStock = Collections.<PlanEntry>emptyList();
        result.fromSources = Collections.<PlanEntry>emptyList();
        result.tokensNeeded = Collections.<PlanEntry>emptyList();
        result.fromEmc = Collections.<PlanEntry>emptyList();
        result.machinesToBuild = Collections.<PlanResult.MachineToBuild>emptyList();
        return new JsonParser().parse(PlanJson.toJson(result)).getAsJsonObject().get("tree");
    }

    private static JsonObject read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return new JsonParser()
                    .parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } finally {
            in.close();
        }
    }

    private static List<File> planFixtures() {
        for (String candidate : new String[] {"../tests/fixtures/plan", "tests/fixtures/plan"}) {
            File dir = new File(candidate);
            if (!dir.isDirectory()) {
                continue;
            }
            File[] found = dir.listFiles();
            List<File> plans = new ArrayList<File>();
            if (found != null) {
                for (File file : found) {
                    if (file.getName().startsWith("plan-") && file.getName().endsWith(".json")) {
                        plans.add(file);
                    }
                }
            }
            Collections.sort(plans);
            return plans;
        }
        return Collections.emptyList();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append("\n  ");
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
