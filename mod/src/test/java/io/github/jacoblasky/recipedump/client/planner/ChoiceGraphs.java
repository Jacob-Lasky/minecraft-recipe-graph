package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Small real graphs for the two tests that need candidates to pick between.
 *
 * A REAL `RecipeGraph` AND NOT A STUB, for `RecipeChoicesTest`'s reason: the whole job of
 * `RecipeChoices` is to ask the graph a question and match the answer against what the solver
 * wrote onto a node, so a stub would let it agree with itself about both halves. Shared
 * rather than written twice because the layout tests need the same shape and a second copy is
 * a second thing to keep in step with `GraphBuilder`.
 */
final class ChoiceGraphs {

    private ChoiceGraphs() {
    }

    /** One recipe making `output` from `input`, so `producers()` has something to return. */
    static void recipe(GraphBuilder b, String rid, String category, String input,
                       String output) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key(input));
        b.endSlot();
        b.output(b.key(output), 1);
        b.endRecipe(rid, category, category, "test", false, false);
    }

    /** `ways` distinct recipes, each making `key` from `mod:ingot`. */
    static RecipeGraph makingKey(String key, int ways) {
        GraphBuilder b = new GraphBuilder();
        for (int i = 0; i < ways; i++) {
            recipe(b, "hei:cat" + i + ":" + i, "cat" + i, "mod:ingot", key);
        }
        return b.build();
    }

    /**
     * A fluid made by one real recipe and one CONTAINER TRANSFER, which the solver refuses.
     *
     * `RecipeGraph.producers` returns both for a fluid key and `realProducers` drops the
     * transfer; everything a picker click leads to -- the ranking, `Pins.resolve`, and the
     * `alternatives` count on the node -- uses the narrow set.
     */
    static RecipeGraph fluidWithATransfer(String fluid) {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:ingot"));
        b.endSlot();
        b.output(b.key(fluid), 1000);
        b.endRecipe("hei:real:1", "nuclearcraft_melter", "Melter", "test", false, false);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:bucket_of_it"));
        b.endSlot();
        b.output(b.key(fluid), 1000);
        b.endRecipe("hei:transfer:2", "container", "Bucket", "test", true, false);
        return b.build();
    }

    /** Three named ways to make `mod:plate`, in the categories a real pack would use. */
    static RecipeGraph threeWaysToMakeAPlate() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "hei:minecraft.crafting:1", "minecraft.crafting", "mod:ingot", "mod:plate");
        recipe(b, "hei:techreborn.rolling:2", "techreborn.rolling", "mod:ingot", "mod:plate");
        recipe(b, "hei:thermal.press:3", "thermal.press", "mod:ingot", "mod:plate");
        return b.build();
    }
}
