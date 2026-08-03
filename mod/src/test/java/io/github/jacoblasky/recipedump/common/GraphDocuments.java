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

    private GraphDocuments() {
    }
}
