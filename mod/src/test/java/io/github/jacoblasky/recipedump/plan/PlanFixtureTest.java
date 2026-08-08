package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.GraphJsonReader;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import org.junit.Assume;
import org.junit.Test;

/**
 * The golden plan fixtures, and everything this port is held to by them.
 *
 * THE END-TO-END GATE RUNS. An earlier version of this comment said it did not, and that each
 * fixture's `scenario` block of placed tile entities, visited dimensions and machine overrides
 * could not be turned into the `freeSources`, `machineStates` and `CostTable` a {@link Solver}
 * takes because `generators.resolve`, `machines.resolve`, `tokens.resolve` and `cost.estimate`
 * had no Java side. {@link ScenarioInputs} is that Java side, and the gate below calls its
 * `resolve`, `price` and `solverFor` on every fixture. Corrected under #192, which found the
 * sentence still here after the work landed.
 *
 * THE GATE IS ORACLE-GATED, THOUGH, so the cheaper assertions around it carry a run that has
 * no oracle -- CI has no 121 MB graph and never will. They check that the wire format this port
 * emits uses exactly the field names and statuses the oracle wrote, that every fixture still
 * names the same oracle, and that the gate's refusal of a graph the fixtures do NOT name works
 * in both directions. That catches a snake_case slip, a dropped optional key and a misspelled
 * status against real oracle output rather than against a hand-typed guess, and it is the
 * failure mode most likely to survive every unit test in {@link SolverTest}.
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
        node.perRun = Double.valueOf(1.0);
        node.yieldChance = Double.valueOf(0.5);
        node.alternatives = 1;
        // MAXIMAL MEANS MAXIMAL, and `pinned` below suppresses this one on a REAL node --
        // #181 writes `interchangeable` only in the `else` of the pin branch. Both are set
        // here anyway, because this node is not a plan, it is the enumeration of every field
        // the writer can emit. Leaving it out is how a new wire field reaches the fixtures
        // with nothing on the port able to produce it, which is what this test caught.
        node.interchangeable = 62;
        node.pinned = Boolean.TRUE;
        node.machine = "Machine";
        node.machineState = "buildable";
        node.machineWhy = "why";
        node.resolvedTo = "mod:other";
        node.altCount = 2;
        node.dimension = "The End";
        node.tokenKind = Tokens.kindName(Tokens.GATE);
        node.unsourced = Boolean.TRUE;
        // #171/#262. Set beside `unsourced` even though `Solver.expand` writes exactly one of
        // them on a real node, for the reason `interchangeable` is set beside `pinned` above:
        // this node is not a plan, it is the enumeration of every field the writer can emit.
        node.provenance = Provenance.PUZZLE;
        return node;
    }

    private static PlanEntry maximalEntry() {
        PlanEntry entry = new PlanEntry("mod:thing", "Thing", "item", "Thing", 1);
        entry.why = "why";
        entry.tokenKind = Tokens.kindName(Tokens.GATE);
        entry.emc = 2048L;
        entry.unsourced = Boolean.TRUE;
        entry.provenance = Provenance.PUZZLE;
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
    public void theTieThresholdIsTheSAMENUMBERONBOTHSIDES() throws IOException {
        // #181. `Solver.TIE_MIN` and `solve.TIE_MIN` decide whether a node carries the
        // "interchangeable" mark, so a divergence renders a DIFFERENT PLAN on each side.
        //
        // WHY THIS EXISTS WHEN THE GOLDEN GATE WOULD PROBABLY CATCH IT. Probably is the
        // problem. The gate would only notice if some fixture happens to hold a tie whose
        // size falls between the two values -- true today, because 29 marks sit at 3 and 4,
        // and an accident of which targets are in the set rather than a guarantee. Raise
        // python to 5 and java to 6 and the gate goes green while the two disagree.
        //
        // The java comment beside the constant says it "MUST stay equal", which is exactly
        // the shape of a rule with nothing enforcing it -- the same defect as a cache
        // fingerprint that omits a constant, or a pinned-constant list that omits a name.
        // Both of those shipped in this repository before somebody wrote the assertion.
        Matcher matcher = Pattern.compile("(?m)^TIE_MIN\\s*=\\s*(\\d+)")
                                 .matcher(readSolvePy());
        assertTrue("recipegraph/solve.py should define TIE_MIN at module level",
                   matcher.find());
        assertEquals("solve.TIE_MIN and Solver.TIE_MIN must be the same number",
                     Integer.parseInt(matcher.group(1)), Solver.TIE_MIN);
    }

    private static String readSolvePy() throws IOException {
        for (String prefix : Arrays.asList("../", "./")) {
            File candidate = new File(prefix + "recipegraph/solve.py");
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()),
                                  StandardCharsets.UTF_8);
            }
        }
        fail("could not find recipegraph/solve.py from " + new File(".").getAbsolutePath());
        return null;
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

    @Test
    public void everyFixtureNamesTheSameOracle() throws IOException {
        // Mirrors `tests/test_plan_fixtures.py:test_every_fixture_names_the_same_oracle`, and
        // deliberately needs NO oracle, so it runs in CI. Two fixtures generated against
        // different graphs would be internally consistent and jointly meaningless, and a
        // half-finished regeneration is exactly how that happens. The gate below then rests on
        // this: it loads ONE graph and refuses it against ONE recorded identity.
        List<File> fixtures = planFixtures();
        Assume.assumeFalse("no tests/fixtures/plan/plan-*.json yet", fixtures.isEmpty());
        JsonObject identity = theOneOracleTheFixturesName(fixtures);
        assertTrue("the oracle had no dimension_ores, so it predates #112/#117",
                identity.get("dimension_ores").getAsLong() > 0);
    }

    // -- the refusal, proven WITHOUT the 121 MB oracle -------------------------------------

    /**
     * The refusal itself, exercised on a graph small enough to build here.
     *
     * WHY THIS EXISTS RATHER THAN "I RAN IT AGAINST THE WRONG ORACLE ONCE". The guard below
     * only executes when someone sets `$RECIPEGRAPH_ORACLE`, which CI never does, so without
     * these three it would be a guard whose failing path had been observed exactly once, by
     * hand, by the person who wrote it -- the shape this repository keeps getting caught by.
     * A `GraphBuilder` graph and a fabricated identity block reach every branch in
     * milliseconds and run on every pull request.
     */
    @Test
    public void aGraphWhoseCountsDisagreeWithTheFixturesIsRefused() throws IOException {
        RecipeGraph graph = tinyGraph();
        File file = tinyFile();
        JsonObject identity = identityOf(graph, file);
        identity.addProperty("recipes", identity.get("recipes").getAsLong() + 1);
        try {
            refuseAnOracleTheFixturesDoNotName(identity, graph, file);
            fail("a graph with a different recipe count must be refused");
        } catch (AssertionError expected) {
            // The message has to name the FIELD and both numbers, because "wrong oracle" with
            // no detail sends the reader to regenerate fixtures they may not need to.
            assertTrue(expected.getMessage(), expected.getMessage().contains("recipes: "));
            assertTrue(expected.getMessage(), expected.getMessage().contains("this graph has"));
        }
    }

    @Test
    public void aRebuiltOracleIsNotRefusedAndSaysSoInTheFailureMessage() throws IOException {
        // Same graph, a sha and byte count from somewhere else: the path-dependent-rebuild
        // case, which MUST still run the comparison. Refusing it would strand the gate on the
        // other machine, and the caveat is what stops a red diff being read as a port bug.
        RecipeGraph graph = tinyGraph();
        File file = tinyFile();
        JsonObject identity = identityOf(graph, file);
        identity.addProperty("sha256", "00000000000000000000000000000000"
                + "00000000000000000000000000000000");
        identity.addProperty("bytes", file.length() + 10);

        String caveat = refuseAnOracleTheFixturesDoNotName(identity, graph, file);
        assertFalse("a rebuild must be reported, not swallowed", caveat.isEmpty());
        assertTrue(caveat, caveat.contains("REBUILD"));
        assertTrue("the caveat must carry both digests, or it cannot be checked",
                caveat.contains("0000000000") && caveat.contains(sha256(file)));
    }

    @Test
    public void anIdentityFieldThisRefusalDoesNotCheckIsItselfARefusal() throws IOException {
        // `graph_identity` growing a field that nothing here compares is the `cost.fingerprint`
        // defect exactly: a guard that enumerates, and is outgrown, and keeps passing.
        RecipeGraph graph = tinyGraph();
        File file = tinyFile();
        JsonObject identity = identityOf(graph, file);
        identity.addProperty("emc_values", 4321);
        try {
            refuseAnOracleTheFixturesDoNotName(identity, graph, file);
            fail("a field the refusal does not check must fail rather than go unchecked");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("emc_values"));
        }
    }

    @Test
    public void theMatchingCaseIsSilent() throws IOException {
        // The control: without it the three above would all pass on a refusal that refused
        // EVERYTHING, which is a guard that has stopped discriminating rather than one that
        // works.
        RecipeGraph graph = tinyGraph();
        File file = tinyFile();
        assertEquals("", refuseAnOracleTheFixturesDoNotName(identityOf(graph, file), graph,
                file));
    }

    /** A graph with one of everything the identity block counts. */
    private static RecipeGraph tinyGraph() {
        GraphBuilder b = new GraphBuilder();
        b.dumpSchema(5);
        b.name("mod:out", "Out");
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:in"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r:1", "crafting_shaped", null, "jar_json", false, false);
        b.beginOreGroup("ingotThing");
        b.oreMember(b.key("mod:in"));
        b.endOreGroup();
        b.dimensionOre(b.key("mod:in"), 1, "The Nether");
        return b.build();
    }

    /**
     * A file for the digest to run over. CONTENT IRRELEVANT, LENGTH AND HASH NOT: the identity
     * block records both, and {@link #identityOf} reads them off this rather than off a
     * constant, so the matching case cannot pass by accident.
     */
    private static File tinyFile() throws IOException {
        File file = File.createTempFile("plan-fixture-oracle", ".json");
        file.deleteOnExit();
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write("{\"not\": \"a real graph\"}".getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return file;
    }

    /** What `tools/make-java-fixtures.py:graph_identity` would write for this pair. */
    private static JsonObject identityOf(RecipeGraph graph, File file) throws IOException {
        JsonObject identity = new JsonObject();
        identity.addProperty("sha256", sha256(file));
        identity.addProperty("bytes", file.length());
        identity.addProperty("dump_schema", graph.dumpSchema());
        identity.addProperty("recipes", graph.recipes().count());
        identity.addProperty("names", graph.namedKeyCount());
        identity.addProperty("ore_members", graph.oreGroupCount());
        identity.addProperty("multiblocks", graph.multiblocks().count());
        identity.addProperty("dimension_ores", graph.dimensionOreCount());
        return identity;
    }

    // -- the golden gate ------------------------------------------------------------------

    /**
     * Every fixture, solved in Java and compared field for field with the Python oracle.
     *
     * ORACLE-GATED, exactly as `tests/test_plan_fixtures.py` is and for the same reasons: the
     * graph is 121 MB, is not in git, and CI will never have it. Point
     * `$RECIPEGRAPH_ORACLE` at it to run this.
     *
     * AND IT REFUSES A GRAPH THE FIXTURES DO NOT NAME, which until #192 this javadoc claimed
     * and the code did not do -- `sha256` was read into a local and used only inside a failure
     * string, and nothing in `mod/src/main/java` computed a digest at all. See
     * {@link #refuseAnOracleTheFixturesDoNotName} for what "match" means and why the sha alone
     * is not it. A plan compared against a different graph fails for reasons that have nothing
     * to do with the port, and the natural response to that is to weaken the comparison.
     *
     * Reports EVERY disagreeing fixture with its path, not just the first. Sixteen plans and
     * a single "expected ... but was ..." would mean sixteen runs to learn the shape of the
     * problem.
     */
    @Test
    public void everyFixturePlansExactlyAsThePythonOracleDoes() throws IOException {
        String oracle = System.getenv("RECIPEGRAPH_ORACLE");
        Assume.assumeTrue("set RECIPEGRAPH_ORACLE to the oracle graph to run the golden gate",
                oracle != null && new File(oracle).isFile());
        List<File> fixtures = planFixtures();
        Assume.assumeFalse("no plan fixtures", fixtures.isEmpty());

        File oracleFile = new File(oracle);
        JsonObject identity = theOneOracleTheFixturesName(fixtures);
        RecipeGraph graph = GraphJsonReader.read(oracleFile);
        // BEFORE A SINGLE PLAN IS SOLVED, so a wrong oracle says "wrong oracle" rather than
        // producing twenty structural diffs that read as a broken port.
        String rebuilt = refuseAnOracleTheFixturesDoNotName(identity, graph, oracleFile);
        Map<String, CostTable> priced = new LinkedHashMap<String, CostTable>();
        List<String> failures = new ArrayList<String>();

        for (File fixture : fixtures) {
            JsonObject doc = read(fixture);
            JsonObject request = doc.getAsJsonObject("request");

            ScenarioInputs.Resolved resolved =
                    ScenarioInputs.resolve(graph, doc.getAsJsonObject("scenario"));
            String signature = resolved.costSignature();
            CostTable costs = priced.get(signature);
            if (costs == null) {
                costs = ScenarioInputs.price(graph, resolved);
                priced.put(signature, costs);
            }
            int maxNodes = request.has("max_nodes")
                    ? request.get("max_nodes").getAsInt() : Solver.DEFAULT_MAX_NODES;
            int target = graph.keyId(request.get("item").getAsString());
            if (target < 0) {
                failures.add(fixture.getName() + ": the oracle graph has no key "
                        + request.get("item").getAsString());
                continue;
            }
            PlanResult plan = ScenarioInputs.solverFor(graph, resolved, costs, maxNodes)
                    .solve(target, request.get("qty").getAsLong());

            String why = JsonCompare.describe(doc.get("result"),
                    new JsonParser().parse(PlanJson.toJson(plan)));
            if (why != null) {
                failures.add(fixture.getName() + ": " + why);
            }
        }
        assertTrue(failures.size() + " of " + fixtures.size()
                + " fixtures disagree with the oracle:" + rebuilt + "\n  "
                + join(failures, "\n  "), failures.isEmpty());
    }

    /**
     * The one `graph` identity block every fixture records, which they must agree on.
     *
     * Compared as whole JSON objects rather than field by field, so a field ADDED to
     * `graph_identity` is covered without anyone remembering to extend this.
     */
    private static JsonObject theOneOracleTheFixturesName(List<File> fixtures)
            throws IOException {
        JsonObject first = read(fixtures.get(0)).getAsJsonObject("graph");
        for (File fixture : fixtures) {
            assertEquals(fixture.getName() + " was generated against another graph than "
                            + fixtures.get(0).getName(),
                    first, read(fixture).getAsJsonObject("graph"));
        }
        return first;
    }

    /**
     * Refuse an oracle the fixtures were not generated against, and return the caveat, if any,
     * that a later failure has to be read with.
     *
     * WHAT "MATCH" MEANS, BECAUSE THE SHA ALONE IS THE WRONG TEST. `graph.instance_dir` is an
     * absolute path recorded INSIDE the graph, so two builds from the identical preserved dump
     * on machines whose instance lives at different paths differ by exactly the length of that
     * string and hash differently -- measured, two oracles from `mc-recipe-dump.s5-run1` came
     * out 121,448,519 and 121,448,529 bytes with byte-identical recipe counts. Refusing on the
     * sha would therefore refuse a PERFECTLY GOOD oracle on the other machine and leave
     * regenerating all 20 fixtures as the only way to run the gate at all, which is how a gate
     * gets switched off.
     *
     * So the SEMANTIC identity is the refusal: `dump_schema` and the five counts, which is the
     * tuple `graph_identity` records for exactly this reason and the one the skill says to
     * compare when a hash moves. Any of those differing is a different graph and throws here.
     * The sha and byte count are compared too and are a CAVEAT rather than a refusal: they are
     * appended to the failure message of the comparison below, so the reader of a red gate is
     * told the other possible cause at the moment it matters and is not asked to notice a
     * warning that scrolled past twenty minutes earlier.
     *
     * The counts are checked on EVERY run, including the sha-matches fast path, on purpose. A
     * fallback that only runs when something has already gone wrong is a fallback nobody has
     * ever executed; this way a wrong accessor on the Java side is a red gate on the first run
     * rather than a surprise years later. It costs one comparison against a loaded graph.
     */
    private static String refuseAnOracleTheFixturesDoNotName(JsonObject identity,
                                                             RecipeGraph graph, File oracle)
            throws IOException {
        Map<String, Long> got = new LinkedHashMap<String, Long>();
        got.put("dump_schema", (long) graph.dumpSchema());
        got.put("recipes", (long) graph.recipes().count());
        got.put("names", (long) graph.namedKeyCount());
        got.put("ore_members", (long) graph.oreGroupCount());
        got.put("multiblocks", (long) graph.multiblocks().count());
        got.put("dimension_ores", (long) graph.dimensionOreCount());
        // AND EVERY FIELD `graph_identity` WRITES IS CHECKED BY SOMETHING HERE. A guard that
        // enumerates is a guard that can be outgrown: `cost.fingerprint` hashed every constant
        // `_relax` used and none `_seed` used, and served a stale table as current for it. So a
        // field added to `graph_identity` fails HERE, naming itself, rather than being silently
        // unchecked -- which is the same class of defect as the missing comparison this method
        // was written to fix.
        Set<String> checked = new LinkedHashSet<String>(got.keySet());
        checked.addAll(Arrays.asList("sha256", "bytes"));
        // `entrySet()` and not `keySet()`: gson 2.8.0 is the version Minecraft 1.12.2 ships
        // and its JsonObject has no keySet().
        Set<String> unchecked = new TreeSet<String>();
        for (Map.Entry<String, JsonElement> field : identity.entrySet()) {
            unchecked.add(field.getKey());
        }
        unchecked.removeAll(checked);
        assertTrue("`graph_identity` in tools/make-java-fixtures.py has grown " + unchecked
                + ", which this refusal does not compare against the loaded graph. Add it to"
                + " `got` above, or say here why it cannot be read off a RecipeGraph.",
                unchecked.isEmpty());

        List<String> wrong = new ArrayList<String>();
        for (Map.Entry<String, Long> entry : got.entrySet()) {
            long want = identity.get(entry.getKey()).getAsLong();
            if (want != entry.getValue().longValue()) {
                wrong.add(entry.getKey() + ": the fixtures were generated against " + want
                        + ", this graph has " + entry.getValue());
            }
        }
        assertTrue("$RECIPEGRAPH_ORACLE=" + oracle + " IS NOT THE GRAPH THE FIXTURES WERE"
                + " GENERATED AGAINST, so comparing plans to them would fail for reasons that"
                + " have nothing to do with the port:\n  " + join(wrong, "\n  ")
                + "\n  Regenerate with tools/make-java-fixtures.py, or point"
                + " $RECIPEGRAPH_ORACLE at the right graph.", wrong.isEmpty());

        String wantSha = identity.get("sha256").getAsString();
        String gotSha = sha256(oracle);
        long wantBytes = identity.get("bytes").getAsLong();
        if (wantSha.equals(gotSha) && wantBytes == oracle.length()) {
            return "";
        }
        return "\n  NOTE: every count above matches, so this IS the same dump, but this"
                + " oracle is a REBUILD of it: " + oracle.length() + " bytes / " + gotSha
                + " against the fixtures' " + wantBytes + " / " + wantSha + ". That is"
                + " expected when the graph was built on another machine, because"
                + " `graph.instance_dir` is an absolute path inside the file. Rule out a"
                + " genuine behaviour change before reading the diffs below as a port bug.";
    }

    /**
     * The sha256 of a file, over the SAME BYTES `graph_identity` digests.
     *
     * `hashlib.sha256()` over the whole file in 1 MB blocks in
     * `tools/make-java-fixtures.py:graph_identity`, so this is the plain file digest and NOT a
     * digest over parsed content -- a guard computed over anything else would be a guard that
     * fails for the wrong reason, which is worse than the missing one it replaced.
     */
    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM ships SHA-256", impossible);
        }
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] block = new byte[1 << 20];
            int read;
            while ((read = in.read(block)) > 0) {
                digest.update(block, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
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
