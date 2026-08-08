package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
        // CATEGORIES THAT APPEAR IN RECIPES, not every interned one. On the real oracle those
        // differ by 172 -- 676 interned against 504 in recipes -- and the machines tab shows
        // the second. Two tabs disagreeing about the pack's size is how a reader stops
        // believing either number.
        assertEquals(1, facts.categories());
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

    // -- is this graph for this pack? ----------------------------------------------------------

    private static RecipeGraph stamped(String digest, int count) {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "hei_dump");
        b.dumpModDigest(digest);
        b.dumpModCount(count);
        return b.build();
    }

    @Test
    public void aGraphBuiltFromTheRunningJarSetMatches() {
        GraphFacts.PackCheck check = GraphFacts.of(stamped("abc123", 410))
                .checkAgainst("abc123", 410);
        assertEquals(GraphFacts.Verdict.MATCHES, check.verdict());
        assertTrue(check.detail(), check.detail().contains("410"));
    }

    @Test
    public void aDifferentJarSetIsAMismatchThatNamesBothCounts() {
        // `_refuse_the_wrong_pack` warns that a dump from a smaller jar set "produces a graph
        // that looks entirely normal and is missing whole mods". The counts are what let a
        // player see WHICH DIRECTION they are wrong in, which is the actionable half.
        GraphFacts.PackCheck check = GraphFacts.of(stamped("abc123", 367))
                .checkAgainst("def456", 410);
        assertEquals(GraphFacts.Verdict.DIFFERS, check.verdict());
        assertTrue(check.detail(), check.detail().contains("367"));
        assertTrue(check.detail(), check.detail().contains("410"));
    }

    @Test
    public void aDumpWithNoRecordedDigestCannotTellAndSaysWhy() {
        // THE STATE THAT MUST NEVER RENDER AS AGREEMENT. `dump_meta` says the CLI check is
        // "silent whenever it cannot compare", which is a safe default for a command and not
        // for a screen: a screen has no equivalent of silence, so whatever is drawn here gets
        // read as a pass. A pre-schema-6 dump records no digest, and the reader has to be told
        // that the check did not happen AND what to do about it.
        GraphFacts.PackCheck check = GraphFacts.of(stamped(null, 0)).checkAgainst("abc123", 410);
        assertEquals(GraphFacts.Verdict.CANNOT_TELL, check.verdict());
        assertNotEquals("it must not read as a match", GraphFacts.Verdict.MATCHES,
                        check.verdict());
        assertTrue(check.detail(), check.detail().contains("redump"));
    }

    @Test
    public void anUnreadableLiveModListCannotTellAndSaysSoDifferently() {
        // `DumpCommand.activeModIds` answers null outside a running Forge -- the unit tests and
        // any tooling. That is a different gap from an old dump and the reader is told which:
        // one is fixed by redumping, the other is not fixed by the player at all.
        GraphFacts.PackCheck check = GraphFacts.of(stamped("abc123", 410)).checkAgainst(null, 0);
        assertEquals(GraphFacts.Verdict.CANNOT_TELL, check.verdict());
        assertTrue(check.detail(), check.detail().contains("mod list"));
        assertNotEquals("the two cannot-tell reasons must be distinguishable",
                        GraphFacts.of(stamped(null, 0)).checkAgainst("abc", 1).detail(),
                        check.detail());
    }

    @Test
    public void anEmptyDigestIsTreatedAsAbsentRatherThanAsAValueToCompare() {
        // An empty string would compare unequal to a real digest and report MISMATCH, which is
        // a confident wrong answer where the truth is "nothing recorded".
        assertEquals(GraphFacts.Verdict.CANNOT_TELL,
                     GraphFacts.of(stamped("", 0)).checkAgainst("abc123", 410).verdict());
        assertEquals(GraphFacts.Verdict.CANNOT_TELL,
                     GraphFacts.of(stamped("abc123", 410)).checkAgainst("", 410).verdict());
    }

    @Test
    public void everyVerdictCarriesANonEmptyReason() {
        // The panel draws `detail()` unconditionally under the verdict word. A blank line there
        // would be the screen going quiet at the moment it has something to say.
        GraphFacts.PackCheck[] all = {
            GraphFacts.of(stamped("a", 1)).checkAgainst("a", 1),
            GraphFacts.of(stamped("a", 1)).checkAgainst("b", 2),
            GraphFacts.of(stamped(null, 0)).checkAgainst("b", 2),
            GraphFacts.of(stamped("a", 1)).checkAgainst(null, 0),
        };
        for (GraphFacts.PackCheck check : all) {
            assertFalse(check.verdict().toString(), check.detail().isEmpty());
        }
    }

    @Test
    public void theRecordedStampSurvivesTheReadRatherThanBeingDropped() {
        // The reader half: `dump_mod_digest` and `dump_mod_count` are written by
        // `model.Graph.save` and were not read by the Java reader at all until #255. A field
        // that is never read renders as CANNOT_TELL, which is read as "nothing is wrong".
        GraphFacts facts = GraphFacts.of(stamped("deadbeef", 410));
        assertEquals("deadbeef", facts.modDigest());
        assertEquals(410, facts.modCount());
    }

    @Test
    public void categoriesCountsOnlyTheOnesRecipesActuallyUse() {
        // THE SCREENSHOT CAUGHT THIS. `graph.categoryCount()` is every interned category and
        // included 172 catalyst-only ones the machines tab never lists, so the Graph tab said
        // 676 where the Machines tab said 504.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "a", "cat.one", "hei_dump");
        recipe(b, "b", "cat.one", "hei_dump");
        recipe(b, "c", "cat.two", "hei_dump");
        // A category interned by a catalyst and used by no recipe: counted by
        // `graph.categoryCount()` and correctly absent here.
        b.beginCatalyst("cat.unused");
        b.catalystKey(b.key("mod:block"));
        b.endCatalyst();
        RecipeGraph graph = b.build();

        assertEquals("the fixture must have an unused category, or this proves nothing",
                     3, graph.categoryCount());
        assertEquals(2, GraphFacts.of(graph).categories());
    }

    @Test
    public void theCannotTellReasonFitsThePanelWidth() {
        // 64 characters is 388px at 6px a character. The first version ran to 66 and the
        // screenshot showed "redump to enable the ..." with the actionable word cut off --
        // which is the one word in the sentence a reader needs.
        for (GraphFacts.PackCheck check : new GraphFacts.PackCheck[] {
                GraphFacts.of(stamped(null, 0)).checkAgainst("abc", 1),
                GraphFacts.of(stamped("abc", 1)).checkAgainst(null, 0)}) {
            assertTrue(check.detail() + " is " + check.detail().length() + " chars",
                       check.detail().length() <= 64);
        }
    }
}
