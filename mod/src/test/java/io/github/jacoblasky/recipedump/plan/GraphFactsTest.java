package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import java.util.List;

import org.junit.Test;

/**
 * The graph's own identity and size, as the screen that answers "am I reading the right graph"
 * needs them.
 *
 * WHY THE BY-SOURCE TALLY IS THE PART WORTH TESTING. The four totals are pass-throughs to
 * `RecipeGraph` accessors that their own tests already cover; the tally is the one thing this
 * class COMPUTES, it is the one a reader uses to judge whether a graph is trustworthy --
 * `hei_dump` is the running game's answer and `jar_json` is a reader that cannot see
 * CraftTweaker deletions (#227) -- and it is the one that can silently disagree with the recipe
 * total if a source id ever fails to resolve.
 */
public class GraphFactsTest {

    private static void recipe(GraphBuilder b, String rid, String category, String source) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key("mod:out_" + rid), 1);
        b.endRecipe(rid, category, "Machine", source, false, false);
    }

    @Test
    public void everyRecipeIsCountedUnderExactlyOneSource() {
        // THE SUM IS THE ASSERTION. A source id that failed to resolve, or a recipe skipped by
        // the walk, shows up here as per-source figures that do not add to the recipe total --
        // which on the screen is a table that quietly accounts for less than it claims.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "hei_dump");
        recipe(b, "c", "cat.one", "jar_json");
        recipe(b, "d", "cat.two", "hei_dump");
        recipe(b, "e", "cat.two", "hei_dump");
        RecipeGraph graph = b.build();

        GraphFacts facts = GraphFacts.of(graph);
        assertEquals(4, facts.recipes());
        int summed = 0;
        for (GraphFacts.Source source : facts.sources()) {
            summed += source.recipes();
        }
        assertEquals("the per-source figures must account for every recipe",
                     facts.recipes(), summed);
    }

    @Test
    public void sourcesAreOrderedBiggestFirst() {
        // Insertion order is the recipe walk's, which is stable and meaningless to a reader.
        // The interesting fact is which source supplied most of the pack.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "jar_json");
        recipe(b, "b", "cat.one", "hei_dump");
        recipe(b, "c", "cat.one", "hei_dump");
        RecipeGraph graph = b.build();

        List<GraphFacts.Source> sources = GraphFacts.of(graph).sources();
        assertEquals(2, sources.size());
        assertEquals("hei_dump", sources.get(0).name());
        assertEquals(2, sources.get(0).recipes());
        assertEquals("jar_json", sources.get(1).name());
    }

    @Test
    public void aTieBetweenSourcesIsBrokenByNameSoTheOrderIsTotal() {
        // Without the second term the order is whatever the walk produced, and a screen whose
        // rows swap between two runs over one graph is a screen nobody can diff.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "zzz");
        recipe(b, "b", "cat.one", "aaa");
        RecipeGraph graph = b.build();

        List<GraphFacts.Source> sources = GraphFacts.of(graph).sources();
        assertEquals("aaa", sources.get(0).name());
        assertEquals("zzz", sources.get(1).name());
    }

    @Test
    public void theTotalsComeStraightOffTheGraph() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "hei_dump");
        recipe(b, "b", "cat.two", "hei_dump");
        RecipeGraph graph = b.build();

        GraphFacts facts = GraphFacts.of(graph);
        assertEquals(graph.recipes().count(), facts.recipes());
        assertEquals(graph.keyCount(), facts.keys());
        assertEquals(graph.namedKeyCount(), facts.namedKeys());
        assertEquals(graph.categoryCount(), facts.categories());
        assertEquals(graph.oreGroupCount(), facts.oreGroups());
    }

    @Test
    public void anAbsentIdentityIsAnEmptyStringRatherThanNull() {
        // The widget draws these directly and a null reaching `IKey.str` throws inside a panel
        // build, which `WidgetTree.resizeInternal` swallows -- leaving the whole screen at 0x0
        // with no message. A graph built without an instance path is the normal case for a
        // pre-schema-5 dump, so this is a real state and not a hypothetical.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "hei_dump");
        GraphFacts facts = GraphFacts.of(b.build());
        assertEquals("", facts.instanceDir());
        assertEquals("", facts.dumpVersion());
    }

    @Test
    public void anEmptyGraphReportsZeroesRatherThanThrowing() {
        // The screen is reachable the moment a graph loads, and a graph with no recipes is what
        // a truncated or wrong-format file produces. Reporting zeroes is what lets the reader
        // SEE that, which is the entire purpose of the screen.
        GraphFacts facts = GraphFacts.of(new GraphBuilder().build());
        assertEquals(0, facts.recipes());
        assertTrue(facts.sources().isEmpty());
    }
}
