package io.github.jacoblasky.recipedump.graph;

import java.io.File;
import java.io.IOException;

/**
 * Measures what a real graph costs in a real JVM: retained heap and wall-clock load time.
 *
 * THIS IS THE DELIVERABLE OF #126, and the whole point is that the number is MEASURED rather
 * than estimated, so the methodology has to be legible enough to argue with.
 *
 * <h2>How the number is taken</h2>
 *
 * <ol>
 * <li>The JVM is quiesced -- repeated {@code System.gc()} until {@code totalMemory() -
 *     freeMemory()} stops moving -- and that figure is the BASELINE.</li>
 * <li>The graph is loaded. Every parser buffer is local to the load call and unreachable the
 *     moment it returns, so nothing about the parse is still held.</li>
 * <li>The JVM is quiesced again, exactly as before. The difference is the RETAINED size.</li>
 * <li>The graph's own {@link GraphSizes} accounting -- computed from actual array lengths --
 *     is printed beside it. The two numbers are independent, and the gap between them is
 *     printed rather than explained away: a large gap means the accounting has missed a
 *     field, and neither number should then be trusted.</li>
 * </ol>
 *
 * <h2>Three things that make a heap measurement lie, and what is done about each</h2>
 *
 * <ul>
 * <li>MEASURING TOO SOON. A parse that allocated a large buffer inflates the reading if the
 *     buffer is still reachable, or if the collector has not yet got to it. Hence quiescence
 *     rather than one {@code System.gc()}, and hence the parser living entirely inside
 *     {@link GraphJsonReader}.</li>
 * <li>A NON-COMPACTING COLLECTOR. A concurrent collector's "used" figure includes garbage it
 *     has not reached and floating regions it has not evacuated. RUN THIS WITH
 *     {@code -XX:+UseSerialGC}: its {@code System.gc()} is a full compacting collection, so
 *     "used" afterwards is the live set and nothing else. The harness prints the collector in
 *     use so a reading taken without it is identifiable after the fact.</li>
 * <li>A HEAP SO LARGE THE COLLECTOR NEVER HAS TO TRY. Reported alongside is the smallest
 *     {@code -Xmx} the load survives, which is a falsifiable statement about the gate in a
 *     way a "used" figure is not -- it covers the PARSE PEAK too, which retained size says
 *     nothing about.</li>
 * </ul>
 *
 * Run it under Java 8, not a modern JDK. Minecraft 1.12.2 runs on 8, and 8 has no compact
 * strings: a measurement taken on 17 or 25 would under-report every string in the graph by
 * roughly half and the gate would be assessed against a JVM nobody is running.
 */
public final class HeapGateHarness {

    private HeapGateHarness() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: HeapGateHarness <graph.json> [--naive] [--interned]"
                    + " [--iterations N]");
            System.exit(2);
        }
        File file = new File(args[0]);
        boolean naive = false;
        boolean interned = false;
        int iterations = 1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--naive")) {
                naive = true;
            } else if (args[i].equals("--interned")) {
                naive = true;
                interned = true;
            } else if (args[i].equals("--iterations")) {
                if (i + 1 >= args.length) {
                    System.err.println("--iterations needs a count");
                    System.exit(2);
                }
                iterations = Integer.parseInt(args[++i]);
            } else {
                System.err.println("unknown argument: " + args[i]);
                System.exit(2);
            }
        }

        System.out.println("graph        " + file.getAbsolutePath());
        System.out.println("file size    " + Sizes.human(file.length()));
        System.out.println("model        "
                + (naive ? (interned ? "naive, keys interned" : "naive") : "compact"));
        System.out.println("java         " + System.getProperty("java.version")
                + "  " + System.getProperty("java.vm.name"));
        System.out.println("max heap     " + Sizes.human(Runtime.getRuntime().maxMemory()));
        System.out.println("collector    " + collectors());
        System.out.println();

        for (int iteration = 1; iteration <= iterations; iteration++) {
            if (naive) {
                measureNaive(file, interned, iteration);
            } else {
                measureCompact(file, iteration);
            }
        }
    }

    private static void measureCompact(File file, int iteration) throws IOException {
        long baseline = quiesce();
        long started = System.nanoTime();
        RecipeGraph graph = GraphJsonReader.read(file);
        long loadNanos = System.nanoTime() - started;
        // Derived HERE rather than left to the first fluid the GUI renders, so the figure
        // below is the steady state a running planner sits at and not a snapshot taken
        // before a table it will certainly build.
        long derivedAt = System.nanoTime();
        graph.fluidNames();
        long fluidNanos = System.nanoTime() - derivedAt;
        long retained = quiesce() - baseline;

        GraphSizes sizes = graph.sizes();
        System.out.println("=== iteration " + iteration + " ===");
        System.out.printf("load                                     %.2f s%n",
                loadNanos / 1e9);
        System.out.printf("derive fluid names                       %.2f s%n",
                fluidNanos / 1e9);
        System.out.println();
        System.out.print(sizes);
        System.out.println();
        report("measured retained (post-GC heap delta)", retained);
        report("accounted for by GraphSizes", sizes.total());
        report("unaccounted", retained - sizes.total());
        System.out.println();
        System.out.println(census(graph));
        // The graph must still be reachable at the moment `quiesce` ran, and a JIT that can
        // prove otherwise is entitled to collect it early. Touching it here is what makes
        // the reachability an observable fact rather than an assumption.
        System.out.println("liveness check: key 0 = " + graph.key(0));
        System.out.println();
    }

    private static void measureNaive(File file, boolean interned, int iteration)
            throws IOException {
        long baseline = quiesce();
        long started = System.nanoTime();
        NaiveGraph graph = NaiveGraph.read(file, interned);
        long loadNanos = System.nanoTime() - started;
        long retained = quiesce() - baseline;

        System.out.println("=== iteration " + iteration + " (naive model) ===");
        System.out.printf("load                                     %.2f s%n",
                loadNanos / 1e9);
        report("measured retained (post-GC heap delta)", retained);
        System.out.println();
        System.out.println(graph.census());
        System.out.println("liveness check: recipes = " + graph.recipeCount());
        System.out.println();
    }

    private static String census(RecipeGraph graph) {
        StringBuilder out = new StringBuilder();
        out.append("recipes                ").append(graph.recipes().count()).append('\n');
        out.append("input slots            ").append(graph.recipes().slotCount()).append('\n');
        out.append("slot alternatives      ").append(graph.recipes().alternativeCount())
                .append('\n');
        out.append("interned keys          ").append(graph.keyCount()).append('\n');
        out.append("keys with a name       ").append(graph.namedKeyCount()).append('\n');
        out.append("distinct name strings  ").append(graph.distinctNameCount()).append('\n');
        out.append("unlocalized labels     ").append(graph.unlocalizedNameCount()).append('\n');
        out.append("oredict groups         ").append(graph.oreGroupCount()).append('\n');
        out.append("world ores             ").append(graph.worldOreCount()).append('\n');
        out.append("live keys              ").append(graph.liveKeyCount()).append('\n');
        out.append("reshaped-only keys     ").append(graph.reshapedOnlyCount()).append('\n');
        out.append("by-output edges        ").append(graph.byOutput().edges()).append('\n');
        out.append("by-input edges         ").append(graph.byInput().edges()).append('\n');
        out.append("catalyst categories    ").append(graph.categoryCount()).append('\n');
        out.append("multiblocks            ").append(graph.multiblocks().count())
                .append(" (").append(graph.multiblocks().positions()).append(" positions)")
                .append('\n');
        out.append("dimension-gated ores   ").append(graph.dimensionOreCount()).append('\n');
        out.append("fluids named by a can  ").append(graph.fluidNames().size()).append('\n');
        out.append("dump schema / version  ").append(graph.dumpSchema()).append(" / ")
                .append(graph.dumpVersion()).append('\n');
        return out.toString();
    }

    private static void report(String label, long bytes) {
        System.out.print(Sizes.row(label, bytes));
    }

    /**
     * Collects until the used-heap figure stops moving, and returns it.
     *
     * ONE {@code System.gc()} IS NOT ENOUGH and the difference is not small. Finalisable and
     * weakly-reachable objects need a second cycle to actually go, and a collector that has
     * just promoted a large object graph frequently reports a figure that falls again on the
     * next pass. The loop runs until two consecutive readings agree within a kilobyte, or
     * until it has tried ten times -- and if it never settles it says so, because a figure
     * that would not converge is not a measurement.
     */
    private static long quiesce() {
        long previous = Long.MIN_VALUE;
        for (int attempt = 0; attempt < 10; attempt++) {
            System.gc();
            System.runFinalization();
            System.gc();
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            if (Math.abs(used - previous) < 1024L) {
                return used;
            }
            previous = used;
        }
        System.out.println("WARNING: heap never quiesced; the figure below is the last "
                + "reading and should not be quoted as a measurement");
        return previous;
    }

    private static String collectors() {
        StringBuilder out = new StringBuilder();
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(bean.getName());
        }
        return out.toString();
    }
}
