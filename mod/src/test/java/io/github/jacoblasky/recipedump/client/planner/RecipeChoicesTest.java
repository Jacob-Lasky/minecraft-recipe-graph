package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Pins;
import org.junit.Test;

/**
 * The picker's candidate lookup, against a real {@link RecipeGraph}.
 *
 * A REAL GRAPH RATHER THAN A STUB, built by `GraphBuilder` the way the plan tests do. The
 * whole job of this class is to ask the graph a question and match the answer against what
 * the solver wrote onto a node; a stubbed graph would let it agree with itself about both.
 */
public class RecipeChoicesTest {

    private static RecipeGraph threeWaysToMakeAPlate() {
        return ChoiceGraphs.threeWaysToMakeAPlate();
    }

    private static PlanNode node(String key, String rid) {
        JsonObject json = new JsonObject();
        json.addProperty("key", key);
        json.addProperty("label", "Plate");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.CRAFT);
        if (rid != null) {
            json.addProperty("recipe", rid);
        }
        return PlanJson.readNode(json);
    }

    @Test
    public void everyRecipeThatMakesTheKeyIsOffered() {
        RecipeChoices choices = RecipeChoices.forNode(
                threeWaysToMakeAPlate(), node("mod:plate", "hei:minecraft.crafting:1"), null);
        assertEquals(3, choices.total());
        assertEquals(3, choices.shown().size());
        assertEquals(0, choices.more());
    }

    @Test
    public void theRecipeThePlanTookIsMarkedAndComesFirst() {
        // Matched on the RID, which is exactly what `Solver` writes onto the node
        // (`node.recipe = store.rid(recipeId)`). Matching on the label or the category would
        // be a guess that happens to work on a fixture with distinct categories.
        RecipeChoices choices = RecipeChoices.forNode(
                threeWaysToMakeAPlate(), node("mod:plate", "hei:thermal.press:3"), null);
        List<RecipeChoice> shown = choices.shown();
        assertEquals("hei:thermal.press:3", shown.get(0).rid());
        assertTrue(shown.get(0).inUse());
        for (int i = 1; i < shown.size(); i++) {
            assertFalse(shown.get(i).rid() + " should not read as in use", shown.get(i).inUse());
        }
    }

    @Test
    public void exactlyOneChoiceIsEverMarkedInUse() {
        RecipeChoices choices = RecipeChoices.forNode(
                threeWaysToMakeAPlate(), node("mod:plate", "hei:techreborn.rolling:2"), null);
        int inUse = 0;
        for (RecipeChoice choice : choices.shown()) {
            if (choice.inUse()) {
                inUse++;
            }
        }
        assertEquals(1, inUse);
    }

    @Test
    public void aNodeWhoseRecipeIsNotInThisGraphMarksNothingRatherThanGuessing() {
        // Reachable for real: a fixture can be rendered while a different graph is loaded,
        // and a redump can move a rid. Marking the first row would be a confident lie about
        // which recipe the plan took.
        RecipeChoices choices = RecipeChoices.forNode(
                threeWaysToMakeAPlate(), node("mod:plate", "hei:from-another-graph:9"), null);
        assertEquals(3, choices.shown().size());
        for (RecipeChoice choice : choices.shown()) {
            assertFalse(choice.inUse());
        }
    }

    @Test
    public void aPinnedRecipeIsMarkedAndOutranksTheUnpinnedOnes() {
        RecipeGraph graph = threeWaysToMakeAPlate();
        // The pin names the rolling mill; the plan took hand crafting.
        int rolling = -1;
        io.github.jacoblasky.recipedump.graph.IntArray producers =
                new io.github.jacoblasky.recipedump.graph.IntArray();
        int count = graph.producers(graph.keyId("mod:plate"), producers);
        for (int i = 0; i < count; i++) {
            if ("hei:techreborn.rolling:2".equals(graph.recipes().rid(producers.get(i)))) {
                rolling = producers.get(i);
            }
        }
        assertTrue("the fixture must contain the rolling recipe", rolling >= 0);

        Map<String, Pins.Pin> pins = new HashMap<String, Pins.Pin>();
        pins.put("mod:plate", Pins.make(graph, rolling));

        RecipeChoices choices = RecipeChoices.forNode(
                graph, node("mod:plate", "hei:minecraft.crafting:1"), pins);
        List<RecipeChoice> shown = choices.shown();
        assertTrue("in use comes first", shown.get(0).inUse());
        assertTrue("then the pinned one", shown.get(1).pinned());
        assertEquals("hei:techreborn.rolling:2", shown.get(1).rid());
    }

    @Test
    public void aKeyTheGraphDoesNotHaveOffersNothingRatherThanThrowing() {
        // A picker that threw here would take out the resize pass, which swallows it.
        RecipeChoices choices = RecipeChoices.forNode(
                threeWaysToMakeAPlate(), node("mod:not-in-this-graph", null), null);
        assertTrue(choices.isEmpty());
        assertEquals(0, choices.total());
        assertTrue(RecipeChoices.forNode(null, node("mod:plate", null), null).isEmpty());
    }

    @Test
    public void everyEmptyAnswerSaysWhichOfTheThreeReasonsItIs() {
        // "No graph yet", "not in this graph" and "nothing makes it" are three different
        // situations and only the last is a fact about the item. An empty picker that says
        // nothing turns all three into "this feature does not work".
        assertEquals("no recipe graph is loaded yet",
                     RecipeChoices.forNode(null, node("mod:plate", null), null).why());
        assertEquals("mod:not-in-this-graph is not in the loaded graph",
                     RecipeChoices.forNode(threeWaysToMakeAPlate(),
                                           node("mod:not-in-this-graph", null), null).why());
        assertEquals("nothing in the pack makes this",
                     RecipeChoices.forNode(threeWaysToMakeAPlate(),
                                           node("mod:ingot", null), null).why());
        assertEquals("a populated answer has nothing to explain", "",
                     RecipeChoices.forNode(threeWaysToMakeAPlate(),
                                           node("mod:plate", null), null).why());
        assertEquals("reason not given", RecipeChoices.none("").why());
    }

    @Test
    public void everyChoiceCarriesThePinAClickWouldStore() {
        // The row holds the FINISHED pin rather than a recipe id to look up later: a graph
        // reload between opening the picker and clicking renumbers every recipe, and an id
        // resolved at click time would pin whatever now sits at that index.
        RecipeGraph graph = threeWaysToMakeAPlate();
        for (RecipeChoice choice : RecipeChoices.forNode(graph, node("mod:plate", null), null)
                                                .shown()) {
            assertEquals(Pins.fingerprint(graph, choice.recipeId()), choice.pin().fingerprint);
            assertEquals(choice.label(), choice.pin().label);
            assertEquals(choice.category(), choice.pin().category);
        }
    }

    /**
     * A container transfer is NOT offered, because the solver would never take it.
     *
     * `RecipeGraph.producers` returns both for a fluid; `realProducers` drops the transfer,
     * and that narrow set is what `Solver.pickRecipe` ranks over, what `Pins.resolve` matches
     * a fingerprint against, and what `node.alternatives` counts. A picker built on the wide
     * one offers a row that pins to nothing: the click succeeds, the file is written, and the
     * next plan reports the pin DEAD -- about a recipe the picker had just shown as a choice.
     */
    @Test
    public void aContainerTransferIsNotOfferedAsAWayToMakeAFluid() {
        RecipeGraph graph = ChoiceGraphs.fluidWithATransfer("fluid:boron");
        RecipeChoices choices = RecipeChoices.forNode(graph, node("fluid:boron", null), null);

        assertEquals("the transfer must not be a candidate", 1, choices.total());
        assertEquals("hei:real:1", choices.shown().get(0).rid());

        // And the count the picker reports agrees with the count the menu entry shows, which
        // is the same narrow set on the node.
        io.github.jacoblasky.recipedump.graph.IntArray wide =
                new io.github.jacoblasky.recipedump.graph.IntArray();
        assertEquals("the fixture must actually contain the wider case", 2,
                     graph.producers(graph.keyId("fluid:boron"), wide));
    }

    @Test
    public void aLeafWithNoProducerOffersNothing() {
        RecipeChoices choices = RecipeChoices.forNode(
                threeWaysToMakeAPlate(), node("mod:ingot", null), null);
        assertTrue("nothing makes the ingot in this graph", choices.isEmpty());
    }

    @Test
    public void aKeyWithMoreCandidatesThanTheCapSaysHowManyItLeftOut() {
        // The fixtures reach 172 alternatives on one node. A capped list that does not say so
        // is the same failure as a truncated plan that does not say so.
        int many = RecipeChoices.MAX_SHOWN + 7;
        RecipeChoices choices = RecipeChoices.forNode(
                ChoiceGraphs.makingKey("mod:plate", many), node("mod:plate", null), null);
        assertEquals(many, choices.total());
        assertEquals(RecipeChoices.MAX_SHOWN, choices.shown().size());
        assertEquals(7, choices.more());
    }

    @Test
    public void everyChoiceCarriesTheLabelAPinWouldStore() {
        // `Pins.label` and not a label of this class's own, so a picker row and a dead pin's
        // note describe the same recipe with the same words.
        RecipeGraph graph = threeWaysToMakeAPlate();
        for (RecipeChoice choice : RecipeChoices.forNode(graph, node("mod:plate", null), null)
                                                .shown()) {
            assertEquals(Pins.label(graph, choice.recipeId()), choice.label());
            assertFalse(choice.category().isEmpty());
        }
    }
}
