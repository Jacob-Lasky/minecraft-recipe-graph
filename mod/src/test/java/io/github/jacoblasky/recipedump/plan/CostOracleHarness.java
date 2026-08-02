package io.github.jacoblasky.recipedump.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jacoblasky.recipedump.graph.GraphJsonReader;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Dumps every price and every machine verdict, for a bit-for-bit diff against python.
 *
 * WHY THIS EXISTS RATHER THAN A UNIT TEST. The unit tests price graphs small enough to work
 * out by hand, which is what makes them readable -- and it is also their limit. The real
 * graph has 124,467 recipes, 3,116 oredict groups, 259 multiblock structures and a two-pass
 * relaxation over twenty iterations, and the ways two implementations of that can disagree
 * are not ways anyone would think to hand-build: an oredict slot resolving to a different
 * member, a tie broken the other way, a category whose entry cost came from the second
 * candidate instead of the first.
 *
 * So the whole table is dumped and compared against the oracle's. Prices go out as the HEX OF
 * THEIR 64-BIT PATTERN, never as decimal text, for the reason
 * {@link JsonFixtures} records at length: 33% of doubles format differently between the two
 * languages while being bit-identical, so a textual diff would report a third of the pack as
 * broken and be wrong every time.
 *
 * Run it through `mod/tools/cost-oracle.sh`, which drives both sides and diffs them.
 */
public final class CostOracleHarness {

    private CostOracleHarness() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println(
                    "usage: CostOracleHarness <graph.json> <out-prefix> [scenario.json]");
            System.exit(2);
        }
        long started = System.nanoTime();
        RecipeGraph graph = GraphJsonReader.read(new File(args[0]));
        System.out.printf("loaded          %.2f s%n", (System.nanoTime() - started) / 1e9);

        // WITH NO SCENARIO, no world state at all: stock, placed blocks and dimension visits
        // are the half that differs between two machines, and the bare run compares the
        // arithmetic rather than reproducing one save. Every path that matters still runs --
        // identification reads the graph's own catalysts, and both relaxation passes run
        // because build targets exist.
        //
        // WITH ONE, the seeding paths are covered too: `have`, `freeSource`, `token`,
        // `dimensionGated` and `emcAvailable`. Until a scenario could be passed those were
        // unit-tested on each side and never DIFFED against each other, which is a weaker
        // claim than this file's headline and was worth being able to close on demand.
        //
        // The scenario is resolved by `ScenarioInputs`, the same class the golden gate uses,
        // rather than by a second reading of the same JSON. A private derivation here would
        // be a third spelling of "what a scenario means" and the drift would be invisible.
        JsonObject scenario = args.length > 2 ? scenarioFrom(args[2]) : null;
        started = System.nanoTime();
        MachineStates states;
        CostTable table;
        if (scenario == null) {
            states = Machines.resolve(graph, new Evidence());
            System.out.printf("resolve         %.2f s%n",
                    (System.nanoTime() - started) / 1e9);
            started = System.nanoTime();
            table = Cost.estimate(graph, new CostInputs()
                    .machineStates(states)
                    .machineItemsFrom(states));
        } else {
            ScenarioInputs.Resolved resolved = ScenarioInputs.resolve(graph, scenario);
            states = resolved.machineStates;
            System.out.printf("resolve         %.2f s%n",
                    (System.nanoTime() - started) / 1e9);
            started = System.nanoTime();
            table = ScenarioInputs.price(graph, resolved);
        }
        System.out.printf("estimate        %.2f s%n", (System.nanoTime() - started) / 1e9);
        System.out.println("priced keys     " + table.pricedCount());
        int[] summary = states.summarise();
        for (int state = 0; state < summary.length; state++) {
            System.out.println("machines " + MachineInfo.stateName(state) + "   "
                    + summary[state]);
        }

        writeCosts(graph, table, new File(args[1] + ".cost.tsv"));
        writeMachines(graph, states, new File(args[1] + ".machines.tsv"));
        System.out.println("wrote           " + args[1] + ".{cost,machines}.tsv");
    }

    /** The `scenario` block of a plan fixture, or a bare scenario document. */
    private static JsonObject scenarioFrom(String path) throws IOException {
        java.io.Reader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(path), "UTF-8");
        try {
            JsonObject doc = new JsonParser().parse(reader).getAsJsonObject();
            return doc.has("scenario") ? doc.getAsJsonObject("scenario") : doc;
        } finally {
            reader.close();
        }
    }

    /**
     * `key TAB hex-bits`, one per priced key, sorted by key.
     *
     * SORTED, because the two sides walk their tables in different orders by construction --
     * python by dict insertion, this by key id -- and the diff is about contents, not about
     * iteration. Unpriced keys are omitted rather than written as infinity, matching python's
     * dict, where an unreachable key is simply absent.
     */
    private static void writeCosts(RecipeGraph graph, CostTable table, File out)
            throws IOException {
        String[] lines = new String[table.pricedCount()];
        int at = 0;
        for (int key = 0; key < graph.keyCount(); key++) {
            double price = table.cost(key);
            if (!Double.isInfinite(price)) {
                lines[at++] = graph.key(key) + "\t"
                        + Long.toHexString(Double.doubleToLongBits(price));
            }
        }
        java.util.Arrays.sort(lines);
        write(out, lines);
    }

    /** `uid TAB state TAB why TAB entry-bits`, sorted by uid. */
    private static void writeMachines(RecipeGraph graph, MachineStates states, File out)
            throws IOException {
        int[] categories = states.describedCategories();
        String[] lines = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            MachineInfo info = states.info(categories[i]);
            StringBuilder targets = new StringBuilder();
            int[] build = states.buildTargets(categories[i]);
            if (build != null) {
                for (int key : build) {
                    if (targets.length() > 0) {
                        targets.append(',');
                    }
                    targets.append(graph.key(key));
                }
            }
            lines[i] = graph.categoryName(categories[i]) + "\t"
                    + MachineInfo.stateName(info.state()) + "\t" + info.why() + "\t"
                    + targets;
        }
        java.util.Arrays.sort(lines);
        write(out, lines);
    }

    private static void write(File out, String[] lines) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out), "UTF-8"), 1 << 20);
        try {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } finally {
            writer.close();
        }
    }
}
