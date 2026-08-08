package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.jacoblasky.recipedump.graph.GraphJsonReader;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A graph that CARRIES `declared_provenance`, planned in Java and compared with Python. #262.
 *
 * WHY THIS EXISTS BESIDE {@link PlanFixtureTest} RATHER THAN INSIDE IT. That gate is the
 * correctness proof of the whole port and it cannot say a word about #171: `declared_provenance`
 * is written by `index.build` and is absent from every graph.json on disk INCLUDING the oracle,
 * so both languages compute the same 285-key set and the gate compares like with like. It is
 * green, and it is green for a reason that expires the next time anybody redumps -- at which
 * point the divergence arrives as an opaque fixture diff on a branch that has nothing to do with
 * this one. The only way to test the port before that day is to SYNTHESISE a graph carrying the
 * field, which is `tools/make-provenance-fixture.py` and `tests/fixtures/provenance.json`.
 *
 * AND IT NEEDS NO ORACLE, which is the second thing it buys. The golden gate `Assume`-skips
 * itself into invisibility without `$RECIPEGRAPH_ORACLE` -- CI has no 121 MB graph and never
 * will -- so without this, nothing about this feature would be checked on any machine that has
 * not built one. This runs everywhere, on 10 recipes.
 *
 * FIVE CLAIMS, AND THE PARTITION IS THE ONE WORTH READING TWICE. The two provenance sets are the
 * two halves of ONE five-clause predicate, split on the declaration, so a key that fell out of
 * BOTH would be priced by neither rule and nothing would say so. The fixture therefore carries
 * the sets computed with the declarations and WITHOUT them, and
 * {@link #removingEveryDeclarationMovesEveryKeyToTheOtherSetAndLosesNone} asserts the two arms
 * partition exactly -- which is what makes the `live_keys` clause observable, since the key no
 * recipe touches must be missing from all four sets rather than from three.
 *
 * NUMBERS ARE PARSED, NEVER DIFFED AS TEXT, for {@link JsonCompare}'s reason: Python `repr` and
 * `Double.toString` produce different strings for a third of the doubles in the golden set and
 * different VALUES for none of them.
 */
public class ProvenanceFixtureTest {

    private static JsonObject fixture;
    private static RecipeGraph graph;
    private static RecipeGraph undeclared;

    @BeforeClass
    public static void read() throws IOException {
        File file = fixtureFile();
        assertNotNull("tests/fixtures/provenance.json is missing; regenerate it with "
                + "python3 tools/make-provenance-fixture.py", file);
        fixture = new JsonParser()
                .parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        graph = load(fixture.getAsJsonObject("graph"));

        // THE SAME DOCUMENT WITH THE ONE SECTION REMOVED, and not a second hand-written graph.
        // The partition claim is about ONE pack read two ways; two documents would let the arms
        // differ in something else and still agree, which is the shape of a control that has
        // stopped controlling.
        JsonObject stripped = new JsonParser().parse(fixture.getAsJsonObject("graph").toString())
                .getAsJsonObject();
        stripped.remove("declared_provenance");
        undeclared = load(stripped);
    }

    private static RecipeGraph load(JsonObject doc) throws IOException {
        byte[] bytes = doc.toString().getBytes(StandardCharsets.UTF_8);
        return GraphJsonReader.read(new ByteArrayInputStream(bytes), bytes.length);
    }

    @Test
    public void theReaderCarriesEveryDeclarationOffTheDocument() {
        // THE READER FIRST, because everything below is vacuously true against a graph that
        // read none of them: `GraphJsonReader` skips an unknown top-level section BY DESIGN, so
        // a port that never learned the field would load this fixture, ignore it and compute
        // the pre-#171 answer -- which is exactly the silent divergence #262 is about. A count
        // of zero here is the state this whole file exists to catch.
        JsonObject declared = fixture.getAsJsonObject("graph")
                .getAsJsonObject("declared_provenance");
        assertEquals("the reader dropped declarations off the document",
                declared.entrySet().size(), graph.declaredProvenanceCount());
        for (Map.Entry<String, JsonElement> e : declared.entrySet()) {
            int keyId = graph.keyId(e.getKey());
            assertTrue("the reader did not intern " + e.getKey() + ", so its liveness cannot "
                    + "even be asked", keyId >= 0);
            assertEquals(e.getKey(), e.getValue().getAsString(),
                    graph.declaredProvenance(keyId));
        }
        assertEquals("a graph without the section must read as declaring nothing, which is "
                + "the pre-#171 behaviour", 0, undeclared.declaredProvenanceCount());
    }

    @Test
    public void bothProvenanceSetsAreTheOnesPythonComputes() {
        JsonObject sets = fixture.getAsJsonObject("sets");
        assertEquals("pack_authored_unsourced",
                strings(sets.getAsJsonArray("pack_authored_unsourced")),
                keysIn(graph, Unsourced.packAuthored(graph)));
        assertEquals("pack_authored_declared",
                map(sets.getAsJsonObject("pack_authored_declared")),
                declaredKinds(graph, Unsourced.packAuthoredDeclared(graph)));
    }

    @Test
    public void removingEveryDeclarationMovesEveryKeyToTheOtherSetAndLosesNone() {
        // THE PARTITION, WHICH IS THE ASSERTION THIS FILE IS FOR. The two sets run one
        // five-clause predicate and split on the declaration, so the union with the pack read
        // must be exactly the unsourced set with the pack unread. A key that fell out of both
        // is charged by neither `Cost.seed` rule and stays at whatever the relaxation left it,
        // silently -- and `live_keys` is the clause that makes that possible, because the
        // declared half iterates the PACK'S map rather than the live set. Python shipped
        // without it for one measurement and 54 dead keys went from infinity to a gate price.
        TreeSet<String> union = new TreeSet<String>(keysIn(graph, Unsourced.packAuthored(graph)));
        union.addAll(declaredKinds(graph, Unsourced.packAuthoredDeclared(graph)).keySet());
        assertEquals("the two arms must partition one predicate exactly",
                keysIn(undeclared, Unsourced.packAuthored(undeclared)), union);

        // AND NEITHER SET MAY HOLD THE SAME KEY, which the union above cannot show on its own:
        // a key in both would collapse into one entry and the sizes would still agree.
        for (String key : declaredKinds(graph, Unsourced.packAuthoredDeclared(graph)).keySet()) {
            assertTrue(key + " is in BOTH provenance sets, so its price and its badge disagree",
                    !keysIn(graph, Unsourced.packAuthored(graph)).contains(key));
        }
    }

    @Test
    public void aDeclaredKeyNoRecipeTouchesIsInNeitherSetEitherWay() {
        // THE `live_keys` CLAUSE, NAMED. The partition above would still hold if this key were
        // wrongly admitted to both arms, so it is asserted directly: the pack declares it, and
        // it is in no set with the pack read, no set with the pack unread, and carries no price
        // -- because inventing a number for an item that cannot appear in any plan is what the
        // clause exists to stop.
        String dead = null;
        for (Map.Entry<String, JsonElement> e : fixture.getAsJsonObject("graph")
                .getAsJsonObject("declared_provenance").entrySet()) {
            int keyId = graph.keyId(e.getKey());
            if (keyId >= 0 && !graph.isLive(keyId)) {
                dead = e.getKey();
            }
        }
        assertNotNull("the fixture no longer carries a declared key no recipe touches, so this "
                + "clause is no longer exercised; put one back in "
                + "tools/make-provenance-fixture.py", dead);
        assertTrue(dead + " is live in this graph after all", !graph.isLive(graph.keyId(dead)));
        IntArray scratch = new IntArray();
        assertTrue(dead, !Unsourced.isPackAuthoredDeclared(graph, graph.keyId(dead), scratch));
        assertTrue(dead, !Unsourced.isPackAuthoredUnsourced(graph, graph.keyId(dead), scratch));
        assertTrue(dead + " must not be priced by the provenance band",
                !fixture.getAsJsonObject("costs").has(dead));
    }

    @Test
    public void everyPriceIsTheOnePythonComputes() {
        // LITERALS ON BOTH SIDES, WHICH IS FREE HERE AND IS THE POINT. The fixture holds 1000.0,
        // 200.0 and 2000.0 as numbers, so collapsing `GATE_COST`, `LOOT_COST` and
        // `UNSOURCED_COST` to one value fails this -- where an assertion written as
        // `assertEquals(GATE_COST, ...)` has the same symbol on both sides and stays green over
        // a model that no longer discriminates at all. `tests/test_provenance.py` records
        // measuring exactly that.
        CostTable costs = ScenarioInputs.price(graph, resolved());
        List<String> wrong = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> e : fixture.getAsJsonObject("costs").entrySet()) {
            int keyId = graph.keyId(e.getKey());
            if (keyId < 0) {
                wrong.add(e.getKey() + ": the Java graph has no such key");
                continue;
            }
            double want = e.getValue().getAsDouble();
            double got = costs.cost(keyId);
            if (want != got) {
                wrong.add(e.getKey() + ": python " + want + ", java " + got);
            }
        }
        assertTrue(wrong.size() + " prices disagree with python:\n  " + join(wrong),
                wrong.isEmpty());
    }

    @Test
    public void thePlanIsByteForByteThePythonOne() {
        // THE WHOLE FEATURE END TO END: the reader, both sets, the band, the badge, the note and
        // the shopping row, compared as one document rather than as six assertions that could
        // each be right while the plan a player sees is wrong. This is what
        // `everyFixturePlansExactlyAsThePythonOracleDoes` will assert on the day the oracle
        // carries the field, run against a graph small enough to have today.
        JsonObject request = fixture.getAsJsonObject("request");
        ScenarioInputs.Resolved inputs = resolved();
        CostTable costs = ScenarioInputs.price(graph, inputs);
        int target = graph.keyId(request.get("item").getAsString());
        assertTrue("the fixture graph has no " + request.get("item").getAsString(), target >= 0);
        PlanResult plan = ScenarioInputs.solverFor(graph, inputs, costs,
                Solver.DEFAULT_MAX_NODES).solve(target, request.get("qty").getAsLong());

        String why = JsonCompare.describe(fixture.get("result"),
                new JsonParser().parse(PlanJson.toJson(plan)));
        assertNull(why, why);
    }

    @Test
    public void theDeclaredLeafIsNotAlsoBadgedUnsourced() {
        // THE INVARIANT THAT KEEPS THE PRICE AND THE BADGE HONEST, said out loud rather than
        // left to the document comparison. `Cost.seed` charges these `provenanceCost` and NOT
        // `UNSOURCED_COST`, so a node carrying both marks would tell a reader the tool cannot
        // explain an item it just explained -- and the two are mutually exclusive by
        // construction, so seeing both is a bug rather than a case to render.
        int seen = 0;
        for (JsonElement node : nodes(fixture.getAsJsonObject("result").getAsJsonObject("tree"))) {
            JsonObject o = node.getAsJsonObject();
            if (!o.has("provenance")) {
                continue;
            }
            seen++;
            assertTrue(o.get("key").getAsString() + " carries both marks",
                    !o.has("unsourced"));
            assertTrue(o.get("key").getAsString() + " says nothing about where it comes from",
                    o.has("note"));
        }
        assertEquals("the fixture must exercise all three kinds and the unknown-kind fallback",
                4, seen);
    }

    // -- helpers ---------------------------------------------------------------------------

    /**
     * The fixture's own `scenario` block, resolved through the class the golden gate uses.
     *
     * NOT `new JsonObject()`, WHICH IS THE OBVIOUS SPELLING AND IS NOT THE SAME INPUT. Python's
     * generator runs `machines.describe`, `generators.resolve` and `tokens.resolve` over a BARE
     * scenario rather than passing the `Solver`'s keyword defaults, so a Java arm that resolved
     * an empty document would be comparing two solvers configured differently -- and calling
     * the difference a port bug. Echoing the block into the fixture and resolving it here is
     * the arrangement `PlanFixtureTest` already uses, for the same reason.
     */
    private static ScenarioInputs.Resolved resolved() {
        return ScenarioInputs.resolve(graph, fixture.getAsJsonObject("scenario"));
    }

    private static List<JsonElement> nodes(JsonObject root) {
        List<JsonElement> out = new ArrayList<JsonElement>();
        out.add(root);
        if (root.has("children")) {
            for (JsonElement child : root.getAsJsonArray("children")) {
                out.addAll(nodes(child.getAsJsonObject()));
            }
        }
        return out;
    }

    /**
     * A bitset rendered back to key STRINGS, and it takes its own graph for a reason.
     *
     * KEY IDS ARE NOT COMPARABLE ACROSS TWO GRAPHS. The two arms are read from documents that
     * differ by one section, and `declared_provenance` interns keys as it is parsed, so id 41
     * is a different item in each. Rendering both sides to strings is what makes the partition
     * comparison mean anything -- comparing the raw bitsets would compare two permutations.
     */
    private static TreeSet<String> keysIn(RecipeGraph g, long[] bits) {
        TreeSet<String> out = new TreeSet<String>();
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            if (io.github.jacoblasky.recipedump.graph.Bits.get(bits, keyId)) {
                out.add(g.key(keyId));
            }
        }
        return out;
    }

    private static TreeMap<String, String> declaredKinds(RecipeGraph g, long[] bits) {
        TreeMap<String, String> out = new TreeMap<String, String>();
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            if (io.github.jacoblasky.recipedump.graph.Bits.get(bits, keyId)) {
                out.put(g.key(keyId), g.declaredProvenance(keyId));
            }
        }
        return out;
    }

    private static TreeSet<String> strings(com.google.gson.JsonArray array) {
        TreeSet<String> out = new TreeSet<String>();
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static TreeMap<String, String> map(JsonObject object) {
        TreeMap<String, String> out = new TreeMap<String, String>();
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            out.put(e.getKey(), e.getValue().getAsString());
        }
        return out;
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append("\n  ");
        }
        return out.toString();
    }

    /**
     * The fixture, found the way `DigestFixtureTest` finds its own: `../` then `./`, because
     * gradle runs with `mod/` as the working directory and a plain `tests/` path resolves
     * against whichever of the two the runner happened to pick.
     */
    private static File fixtureFile() {
        for (String prefix : new String[] {"../", "./"}) {
            File candidate = new File(prefix + "tests/fixtures/provenance.json");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        fail("could not find tests/fixtures/provenance.json from "
                + new File(".").getAbsolutePath());
        return null;
    }
}
