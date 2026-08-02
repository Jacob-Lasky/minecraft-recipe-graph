package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

/**
 * The golden plan fixtures, and what this port can be held to by them TODAY.
 *
 * THE END-TO-END GATE IS NOT RUNNING YET, AND SAYING SO IS THE POINT. Each
 * `tests/fixtures/plan/plan-*.json` carries a `scenario` block of PLACED TILE ENTITIES,
 * visited dimensions and machine overrides -- not of solver inputs. Turning that into the
 * `freeSources`, `machineStates` and `CostTable` a {@link Solver} takes is
 * `generators.resolve`, `machines.resolve`, `tokens.resolve` and `cost.estimate`, all of
 * which are the other half of this port and none of which exist in Java yet. Wiring the
 * comparison against placeholder inputs would produce a green suite that proves nothing,
 * which is worse than an honest gap.
 *
 * What IS assertable now, and is: that the wire format this port emits uses exactly the field
 * names the oracle wrote. That catches a snake_case slip, a dropped optional key and a
 * misspelled status against real oracle output rather than against a hand-typed guess, and it
 * is the failure mode most likely to survive every unit test in {@link SolverTest}.
 */
public class PlanFixtureTest {

    /**
     * Fields {@link PlanJson} can emit, discovered by serialising a maximal result rather
     * than listed here.
     *
     * DERIVED AND NOT TYPED OUT, because a hand-maintained list is a second place for the
     * field names to live and would drift the moment someone adds a key to `PlanJson` and not
     * to this file -- at which point the test would report a fixture problem and mean a test
     * problem.
     */
    private static Set<String> emittableNames() {
        PlanResult result = new PlanResult();
        result.target = "mod:thing";
        result.targetName = "Thing";
        result.pinsOverruled.put("mod:thing", "overruled");
        result.qty = 1;
        result.tree = maximalNode();
        result.tree.children = Collections.singletonList(maximalNode());
        result.shoppingList = Collections.singletonList(maximalEntry());
        result.usedFromStock = Collections.singletonList(maximalEntry());
        result.fromSources = Collections.singletonList(maximalEntry());
        result.tokensNeeded = Collections.singletonList(maximalEntry());
        result.fromEmc = Collections.singletonList(maximalEntry());
        result.machinesToBuild = Collections.singletonList(
                new PlanResult.MachineToBuild("cat", "Machine", "buildable", "why"));
        Set<String> names = new LinkedHashSet<String>();
        collectNames(new JsonParser().parse(PlanJson.toJson(result)), names);
        return names;
    }

    private static PlanNode maximalNode() {
        PlanNode node = new PlanNode();
        node.key = "mod:thing";
        node.name = "Thing";
        node.kind = "item";
        node.label = "Thing";
        node.need = 1;
        node.status = PlanStatus.CRAFT;
        node.fromStock = 1L;
        node.note = "note";
        node.recipe = "r:1";
        node.category = "crafting_shaped";
        node.runs = 1L;
        node.perRun = 1L;
        node.alternatives = 1;
        node.pinned = Boolean.TRUE;
        node.machine = "Machine";
        node.machineState = "buildable";
        node.machineWhy = "why";
        node.resolvedTo = "mod:other";
        node.altCount = 2;
        node.dimension = "The End";
        node.tokenKind = Tokens.kindName(Tokens.GATE);
        return node;
    }

    private static PlanEntry maximalEntry() {
        PlanEntry entry = new PlanEntry("mod:thing", "Thing", "item", "Thing", 1);
        entry.why = "why";
        entry.tokenKind = Tokens.kindName(Tokens.GATE);
        entry.emc = 2048L;
        return entry;
    }

    @Test
    public void everyFieldTheOracleWritesIsOneThisPortCanEmit() throws IOException {
        List<File> fixtures = planFixtures();
        Assume.assumeFalse("no tests/fixtures/plan/plan-*.json yet; this gate is waiting on "
                + "the fixture set", fixtures.isEmpty());

        Set<String> emittable = emittableNames();
        Set<String> unknown = new TreeSet<String>();
        for (File fixture : fixtures) {
            JsonObject doc = read(fixture);
            assertTrue(fixture + " has no `result` block", doc.has("result"));
            Set<String> names = new LinkedHashSet<String>();
            collectNames(doc.get("result"), names);
            for (String name : names) {
                if (!emittable.contains(name)) {
                    unknown.add(name + "  (" + fixture.getName() + ")");
                }
            }
        }
        assertTrue("the oracle writes fields this port cannot emit: " + unknown,
                unknown.isEmpty());
    }

    @Test
    public void everyStatusTheOracleWritesIsOneThisPortDeclares() throws IOException {
        List<File> fixtures = planFixtures();
        Assume.assumeFalse("no tests/fixtures/plan/plan-*.json yet", fixtures.isEmpty());

        // `PlanStatus` holds strings and not an enum precisely so the constant and the wire
        // value cannot drift -- but that only helps if a MISSING or MISSPELLED constant is
        // caught, and no unit test in this package would notice `"oredict"` becoming
        // `"ore_dict"`. The oracle's own output is the authority.
        Set<String> declared = new LinkedHashSet<String>(Arrays.asList(
                PlanStatus.HAVE, PlanStatus.PARTIAL, PlanStatus.CRAFT, PlanStatus.RAW,
                PlanStatus.SOURCE, PlanStatus.CYCLE, PlanStatus.DEPTH, PlanStatus.TOKEN,
                PlanStatus.EMC, PlanStatus.OREDICT));
        Set<String> unknown = new TreeSet<String>();
        for (File fixture : fixtures) {
            collectStatuses(read(fixture).get("result"), declared, unknown,
                    fixture.getName());
        }
        assertTrue("the oracle writes statuses PlanStatus does not declare: " + unknown,
                unknown.isEmpty());
    }

    @Test
    public void theFixturesStillCarryTheBlocksTheGateWillRead() throws IOException {
        List<File> fixtures = planFixtures();
        Assume.assumeFalse("no tests/fixtures/plan/plan-*.json yet", fixtures.isEmpty());
        for (File fixture : fixtures) {
            JsonObject doc = read(fixture);
            for (String block : new String[] {"request", "scenario", "result", "graph"}) {
                assertTrue(fixture.getName() + " lost its `" + block + "` block",
                        doc.has(block));
            }
            JsonObject request = doc.getAsJsonObject("request");
            assertTrue(fixture.getName() + " request has no item", request.has("item"));
            assertTrue(fixture.getName() + " request has no qty", request.has("qty"));
        }
    }

    // -- the comparison itself, proven before anything depends on it ----------------------

    @Test
    public void numbersCompareNumericallyAndNotAsText() {
        // MEASURED ON THE ORACLE: 1,966 of 6,012 doubles in the fixture set format
        // differently between Python's repr() and Java's Double.toString(), and ZERO differ
        // in value. A textual comparison of these files fails a third of the time on a
        // correct port, which is the fastest way to make someone "fix" working code.
        assertTrue(JsonCompare.equal(parse("{\"a\": 1.0}"), parse("{\"a\": 1}")));
        assertTrue(JsonCompare.equal(parse("{\"a\": 0.1}"), parse("{\"a\": 1.0e-1}")));
        assertTrue(JsonCompare.equal(parse("{\"a\": 1.0E22}"), parse("{\"a\": 1e22}")));
        assertFalse(JsonCompare.equal(parse("{\"a\": 1.0}"), parse("{\"a\": 1.0000001}")));
    }

    @Test
    public void anAbsentFieldIsNotTheSameAsANullOne() {
        // Python omits a key rather than writing null, and the fixtures freeze which keys are
        // present. A comparison that treated the two alike would pass a port that emitted
        // `"from_stock": null` on every leaf.
        assertFalse(JsonCompare.equal(parse("{\"a\": 1}"), parse("{\"a\": 1, \"b\": null}")));
        assertFalse(JsonCompare.equal(parse("{\"a\": 1, \"b\": null}"), parse("{\"a\": 1}")));
    }

    @Test
    public void arrayOrderIsPartOfTheComparison() {
        // Five of the result's lists are `Counter.most_common()` output and that order IS the
        // contract. A set-like comparison here would pass a HashMap-backed port.
        assertFalse(JsonCompare.equal(parse("[1, 2]"), parse("[2, 1]")));
        assertTrue(JsonCompare.equal(parse("[1, 2]"), parse("[1, 2]")));
    }

    @Test
    public void objectKeyOrderIsNotPartOfTheComparison() {
        // JSON objects are unordered by the spec and gson's writer emits in insertion order,
        // which has nothing to do with Python's dict order. Only the LISTS carry meaning.
        assertTrue(JsonCompare.equal(parse("{\"a\": 1, \"b\": 2}"),
                parse("{\"b\": 2, \"a\": 1}")));
    }

    @Test
    public void aMismatchNamesThePathAndNotJustTheFact() {
        // A 12,000-line fixture and a bare "not equal" is an afternoon. The path is what
        // turns it into a minute.
        String why = JsonCompare.describe(
                parse("{\"tree\": {\"children\": [{\"need\": 4}]}}"),
                parse("{\"tree\": {\"children\": [{\"need\": 5}]}}"));
        assertTrue(why, why.contains("tree.children[0].need"));
        assertTrue(why, why.contains("4"));
        assertTrue(why, why.contains("5"));
    }

    // -- helpers ---------------------------------------------------------------------------

    private static JsonElement parse(String json) {
        return new JsonParser().parse(json);
    }

    private static void collectStatuses(JsonElement element, Set<String> declared,
                                        Set<String> unknown, String fixture) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if ("status".equals(entry.getKey()) && value.isJsonPrimitive()
                        && !declared.contains(value.getAsString())) {
                    unknown.add(value.getAsString() + "  (" + fixture + ")");
                }
                collectStatuses(value, declared, unknown, fixture);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectStatuses(child, declared, unknown, fixture);
            }
        }
    }

    private static void collectNames(JsonElement element, Set<String> into) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                into.add(entry.getKey());
                collectNames(entry.getValue(), into);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectNames(child, into);
            }
        }
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

    /**
     * The `plan-*.json` fixtures, or empty when they are not in the tree.
     *
     * `cost.json` and `machines.json` sit in the same directory and are the OTHER half's
     * acceptance criteria, so they are excluded by the `plan-` prefix rather than by trying
     * to parse them as plans.
     *
     * Gradle runs tests from `mod/`, a direct run happens from the repository root; try both,
     * as `DigestFixtureTest` does.
     */
    private static List<File> planFixtures() {
        for (String candidate : new String[] {"../tests/fixtures/plan", "tests/fixtures/plan"}) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                File[] found = dir.listFiles();
                List<File> plans = new ArrayList<File>();
                if (found != null) {
                    for (File file : found) {
                        if (file.getName().startsWith("plan-")
                                && file.getName().endsWith(".json")) {
                            plans.add(file);
                        }
                    }
                }
                Collections.sort(plans);
                return plans;
            }
        }
        return Collections.emptyList();
    }

}
