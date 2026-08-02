package io.github.jacoblasky.recipedump.common;

/**
 * A graph document small enough to read at a glance, for the common-side service tests.
 *
 * Deliberately NOT a copy of `graph.SchemaFiveFactsTest.DOCUMENT`: that one exists to carry
 * every schema-5 field so the reader can be held to all of them, and a service test that
 * depended on its contents would fail whenever a field was added to it for reasons having
 * nothing to do with loading. This one holds the least that produces a usable graph -- one
 * craftable item with one raw input -- so a change to it can only ever be about these tests.
 */
final class GraphDocuments {

    /**
     * One recipe: a plate crafted from an ingot. Enough for a graph to be READY and for a
     * solver to return a two-node tree, which is all the common side needs to prove.
     */
    static final String TINY = "{"
            + "\"dump_schema\":5,"
            + "\"names\":{\"mod:plate\":\"Plate\",\"mod:ingot\":\"Ingot\"},"
            + "\"recipes\":[{"
            + "\"cat\":\"minecraft.crafting\","
            + "\"id\":\"plate-from-ingot\","
            + "\"in\":[{\"alt\":[\"mod:ingot\"],\"qty\":1}],"
            + "\"out\":[{\"key\":\"mod:plate\",\"qty\":1}],"
            + "\"src\":\"hei_dump\"}]"
            + "}";

    private GraphDocuments() {
    }
}
