package io.github.jacoblasky.recipedump.common;

/**
 * A graph document small enough to read at a glance, for the tests that need a real load.
 *
 * Deliberately NOT a copy of `graph.SchemaFiveFactsTest.DOCUMENT`: that one exists to carry
 * every schema-5 field so the reader can be held to all of them, and a service test that
 * depended on its contents would fail whenever a field was added to it for reasons having
 * nothing to do with loading. This one holds the least that produces a usable graph -- one
 * craftable item with one raw input -- so a change to it can only ever be about these tests.
 *
 * PUBLIC, AND THE ONE PLACE THIS DOCUMENT IS SPELLED. It was package-private, so
 * `client.PlannerEntryTest` carried a hand-copy under a comment reading "duplicated only
 * because packages differ" -- which names the cause and accepts the drift. Three spellings of
 * one schema is three chances to update two of them, and the schema is the contract
 * `GraphJsonReader` is held to. DO NOT paste this string into a test package again; if the
 * document needs to differ, add a factory here.
 */
public final class GraphDocuments {

    /**
     * One recipe: a plate crafted from an ingot. Enough for a graph to be READY and for a
     * solver to return a two-node tree, which is all the common side needs to prove.
     */
    public static final String TINY = craftedFrom("mod:plate", "Plate");

    /**
     * The same document with a caller-chosen output key.
     *
     * A FACTORY RATHER THAN A SECOND CONSTANT. `client.jei.JeiNodeActionsTest` needs this
     * document keyed to an item a real `ItemStack` can be built for, because it indexes one
     * against the loaded graph; `mod:plate` is not producible by any `Item` and a second
     * hand-written copy is how the first one happened.
     *
     * @param outKey the crafted item's discriminated key, as `DumpCommand.stackKey` spells it
     * @param outName its display name
     */
    public static String craftedFrom(String outKey, String outName) {
        return "{"
                + "\"dump_schema\":5,"
                + "\"names\":{\"" + outKey + "\":\"" + outName + "\",\"mod:ingot\":\"Ingot\"},"
                + "\"recipes\":[{"
                + "\"cat\":\"minecraft.crafting\","
                + "\"id\":\"" + outName.toLowerCase() + "-from-ingot\","
                + "\"in\":[{\"alt\":[\"mod:ingot\"],\"qty\":1}],"
                + "\"out\":[{\"key\":\"" + outKey + "\",\"qty\":1}],"
                + "\"src\":\"hei_dump\"}]"
                + "}";
    }

    /**
     * Write {@link #TINY} into `dir`, point `GraphService` at it, and wait until it is READY.
     *
     * THE PROCEDURE MOVED HERE FOR THE REASON THE DOCUMENT DID. This class already forbids
     * pasting the JSON into another test package, and #254 showed the rule was drawn one step
     * too small: `client.PlannerEntryTest` and `client.machines.MachinesEntryTest` both need a
     * loaded graph, and the second arrived as a verbatim copy of the first's `loadGraph` --
     * same temp file, same system property, same poll, same 30-second deadline. That is four
     * things to keep in step, and the deadline is the one that rots silently: a copy left at
     * five seconds passes on an idle box and fails under the twelve-agent load this host
     * actually runs.
     *
     * IT THROWS RATHER THAN RETURNING A FLAG. Every caller needs a loaded graph to mean
     * anything at all, so a caller that ignored a false would go on to assert about a service
     * in LOADING and report a confusing failure two frames later instead of this one.
     *
     * THE CALLER STILL OWNS `GraphService.reset()` AND THE PROPERTY, in its own `@Before` and
     * `@After`. This deliberately does not clean up: `GraphSource.PROPERTY` is process-wide,
     * so a helper that restored it would fight the fixture that saved it, and the two tests
     * already have the save/restore pair the JUnit lifecycle wants.
     *
     * @param dir a per-test temporary directory, usually `TemporaryFolder.getRoot()`
     * @return the graph file that was written
     */
    public static java.io.File loadTinyGraphFrom(java.io.File dir) throws Exception {
        java.io.File file = new java.io.File(dir, "graph.json");
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(TINY.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        System.setProperty(GraphSource.PROPERTY, file.getPath());
        GraphService.get().startLoad(null);
        // THIRTY SECONDS FOR A DOCUMENT THIS SIZE IS NOT A GUESS ABOUT THE READ, it is headroom
        // for a shared host: the read is milliseconds and the wait exists so a box running a
        // dozen containers does not turn a scheduling delay into a red test.
        long deadline = System.currentTimeMillis() + 30_000L;
        while (GraphService.get().state() != GraphService.State.READY) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("graph never loaded: " + GraphService.get().describe());
            }
            Thread.sleep(5L);
        }
        return file;
    }

    private GraphDocuments() {
    }
}
