package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The machines table's orderings and its two co-dependent filters.
 *
 * WHY THESE ASSERTIONS AND NOT A SCREENSHOT. Everything here is a rule that was reported by a
 * human looking at the live web page rather than caught by a test -- #16 (the filters do not
 * narrow each other), #32 (the mods that DO match are lost among the 71 that do not) and the
 * collation tie that made Python and JavaScript disagree about 74 of 77 names. `machines.py`'s
 * own docstrings say so: "#16 and #32 were both reported by a human rather than caught by a
 * test". The port is the moment to fix that, so every one of those behaviours is pinned here.
 *
 * IN `plan/`, WHICH IS WHERE `tools/ci-java.sh` ACTUALLY LOOKS. That job runs `graph/` and
 * `plan/` and nothing else -- 324 assertions of 826 -- so a filter rule asserted beside the
 * widgets would be a rule no pull request runs (#244). See {@link MachineTable}'s class note.
 */
public class MachineTableTest {

    /**
     * `count` recipes in `category`, grouped under the mod DISPLAY name `mod`.
     *
     * THE DISPLAY NAME IS SET EXPLICITLY AND NOT LEFT TO THE UID FALLBACK. `Machines.modName`
     * does fall back to the first token of the uid, but `tokens` lowercases -- so a test that
     * relied on the fallback could only ever produce lowercase mod names, and the collation
     * assertions below, which are entirely about case, would pass against any comparator at
     * all. `categoryMod` is also what the real graph carries: "Industrial Foregoing", not
     * `industrialforegoing`.
     */
    private static void recipes(GraphBuilder b, String category, String mod, int count) {
        b.categoryMod(category, mod);
        for (int i = 0; i < count; i++) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:leaf"));
            b.endSlot();
            b.output(b.key("mod:out_" + category.replace('.', '_') + "_" + i), 1);
            b.endRecipe(category + ".r" + i, category, "Machine " + category, "test",
                        false, false);
        }
    }

    /**
     * Four categories over two mods, with every state forced by hand.
     *
     * FORCED THROUGH `Evidence.override` RATHER THAN BY CONSTRUCTING `MachineInfo` DIRECTLY.
     * The constructors are reachable from this package and using them would be quicker; it
     * would also mean these assertions never touch `Machines.resolve`, so a resolve that
     * stopped producing the states this table groups on would leave every test here green.
     * An override is a real input the production path reads.
     *
     * <pre>
     *   alpha.one   5 recipes   HAVE
     *   alpha.two   1 recipe    UNAVAILABLE
     *   beta.one    3 recipes   BUILDABLE
     *   beta.two    2 recipes   BUILDABLE
     * </pre>
     */
    private static MachineTable table() {
        GraphBuilder b = new GraphBuilder();
        recipes(b, "alpha.one", "Alpha", 5);
        recipes(b, "alpha.two", "Alpha", 1);
        recipes(b, "beta.one", "Beta", 3);
        recipes(b, "beta.two", "Beta", 2);
        RecipeGraph graph = b.build();
        MachineStates states = Machines.resolve(graph, new Evidence()
                .override("alpha.one", MachineInfo.HAVE)
                .override("alpha.two", MachineInfo.UNAVAILABLE)
                .override("beta.one", MachineInfo.BUILDABLE)
                .override("beta.two", MachineInfo.BUILDABLE));
        return MachineTable.of(graph, states);
    }

    private static List<String> uids(List<MachineTable.Row> rows) {
        List<String> out = new ArrayList<String>();
        for (MachineTable.Row row : rows) {
            out.add(row.uid());
        }
        return out;
    }

    private static MachineTable.Row row(MachineTable table, String uid) {
        for (MachineTable.Row row : table.allRows()) {
            if (row.uid().equals(uid)) {
                return row;
            }
        }
        throw new AssertionError("no such row: " + uid);
    }

    // -- orderings -----------------------------------------------------------------------------

    @Test
    public void rowsSortByStateThenByHowMuchOfThePackTheCategoryCarries() {
        // The state constant IS the rank, which is the same number Python's `STATE_RANK`
        // enumerates off the same tuple that orders the cost bands. Recipes descending inside
        // a state, because "which machine matters" is a question about how much it opens.
        assertEquals("[alpha.one, beta.one, beta.two, alpha.two]",
                     uids(table().allRows()).toString());
    }

    @Test
    public void aTieOnStateAndRecipesFallsBackToTheUidSoTheOrderIsTotal() {
        GraphBuilder b = new GraphBuilder();
        recipes(b, "zeta.same", "Zeta", 2);
        recipes(b, "alpha.same", "Alpha", 2);
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()
                .override("zeta.same", MachineInfo.HAVE)
                .override("alpha.same", MachineInfo.HAVE)));
        // WITHOUT THE THIRD TERM THIS ORDER IS WHATEVER `describedCategories` HAPPENED TO
        // RETURN, which is first-appearance-in-recipes and therefore depends on the dump. A
        // table whose rows move between two runs over the same pack is a table nobody can
        // diff, and it would present as a flaky screenshot rather than as a sort bug.
        assertEquals("[alpha.same, zeta.same]", uids(table.allRows()).toString());
    }

    @Test
    public void modsAreOrderedByHowManyCategoriesTheyHaveAndThenByName() {
        assertEquals("[Alpha, Beta]", table().mods().toString());
    }

    @Test
    public void theNameTieIsBrokenCaseInsensitivelySoALowercaseModidSortsInline() {
        // `machines.mod_order` uses `casefold` for exactly this: `aether_legacy` belongs next
        // to `Advent of Ascension`, not after `Woot`. Codepoint order alone puts every
        // lowercase modid below every capitalised one.
        GraphBuilder b = new GraphBuilder();
        recipes(b, "woot.a", "Woot", 1);
        recipes(b, "aether.a", "aether_legacy", 1);
        recipes(b, "advent.a", "Advent of Ascension", 1);
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()));
        assertEquals("[Advent of Ascension, aether_legacy, Woot]", table.mods().toString());
    }

    @Test
    public void theCountTermBeatsTheNameTerm() {
        GraphBuilder b = new GraphBuilder();
        recipes(b, "zzz.a", "Zzz", 1);
        recipes(b, "zzz.b", "Zzz", 1);
        recipes(b, "aaa.a", "Aaa", 1);
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()));
        assertEquals("[Zzz, Aaa]", table.mods().toString());
    }

    // -- totals --------------------------------------------------------------------------------

    @Test
    public void categoryAndRecipeTotalsAreCountedSeparatelyBecauseTheyAnswerDifferentThings() {
        MachineTable table = table();
        int[] categories = table.stateTotals();
        assertEquals(1, categories[MachineInfo.HAVE]);
        assertEquals(2, categories[MachineInfo.BUILDABLE]);
        assertEquals(0, categories[MachineInfo.UNKNOWN]);
        assertEquals(1, categories[MachineInfo.UNAVAILABLE]);

        // ONE CATEGORY ON HAND CARRIES MORE RECIPES THAN THE TWO BUILDABLE ONES, which is the
        // whole reason both figures are shown: the category count says "1 of 4" and the recipe
        // count says that one covers nearly half the pack.
        int[] recipes = table.recipeTotals();
        assertEquals(5, recipes[MachineInfo.HAVE]);
        assertEquals(5, recipes[MachineInfo.BUILDABLE]);
        assertEquals(1, recipes[MachineInfo.UNAVAILABLE]);
    }

    // -- the filters narrow each other (#16) ---------------------------------------------------

    @Test
    public void withNothingSelectedEveryRowIsShown() {
        assertEquals(4, table().rows(MachineTable.Filter.NONE).size());
    }

    @Test
    public void noChipOnMeansEveryStateRatherThanNone() {
        // A table that went blank when the last chip was switched off would be a table you
        // could break by clicking twice. `MACHINES_JS` reads `!states.length||active[st]`.
        MachineTable table = table();
        MachineTable.Filter both = MachineTable.Filter.NONE
                .toggleState(MachineInfo.HAVE)
                .toggleState(MachineInfo.HAVE);
        assertTrue(both.everyState());
        assertEquals(4, table.rows(both).size());
    }

    @Test
    public void selectingAStateNarrowsTheModCountsToThatState() {
        MachineTable table = table();
        MachineTable.Narrowed narrowed =
                table.narrowed(MachineTable.Filter.NONE.toggleState(MachineInfo.BUILDABLE));
        // Both of beta's categories are buildable; neither of alpha's is. Before #16 the mod
        // list kept its unconditional totals here and picking `alpha` produced an empty table
        // with no hint why.
        assertEquals(2, narrowed.mod("Beta"));
        assertEquals(0, narrowed.mod("Alpha"));
    }

    @Test
    public void selectingAModNarrowsTheStateCounts() {
        MachineTable.Narrowed narrowed = table().narrowed(
                MachineTable.Filter.NONE.withMod("Alpha"));
        assertEquals(1, narrowed.state(MachineInfo.HAVE));
        assertEquals(0, narrowed.state(MachineInfo.BUILDABLE));
        assertEquals(1, narrowed.state(MachineInfo.UNAVAILABLE));
    }

    @Test
    public void aChipsOwnSelectionDoesNotNarrowItsOwnCount() {
        // COUNTS COME FROM THE OTHER AXIS ONLY. Counting a chip against itself makes every
        // number move as you click, and `MACHINES_JS` chose a slightly generous figure over a
        // moving one deliberately. With no mod chosen, switching a state on must not change
        // any state count.
        MachineTable table = table();
        MachineTable.Narrowed before = table.narrowed(MachineTable.Filter.NONE);
        MachineTable.Narrowed after =
                table.narrowed(MachineTable.Filter.NONE.toggleState(MachineInfo.HAVE));
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            assertEquals("state " + state, before.state(state), after.state(state));
        }
    }

    @Test
    public void aModThatCanNoLongerMatchIsCleared() {
        // The reported bug in #16 was "picking one then yields an empty table". An empty table
        // with both filters still lit gives the reader nothing to undo.
        MachineTable table = table();
        MachineTable.Filter impossible = MachineTable.Filter.NONE
                .withMod("Alpha")
                .toggleState(MachineInfo.BUILDABLE);
        MachineTable.Filter fixed = table.reconcile(impossible);
        assertEquals(null, fixed.mod());
        assertTrue("the state the reader just chose survives",
                   fixed.state(MachineInfo.BUILDABLE));
        assertEquals(2, table.rows(fixed).size());
    }

    @Test
    public void aStateThatCanNoLongerMatchIsCleared() {
        MachineTable table = table();
        MachineTable.Filter impossible = MachineTable.Filter.NONE
                .toggleState(MachineInfo.UNKNOWN);
        MachineTable.Filter fixed = table.reconcile(impossible);
        assertFalse(fixed.state(MachineInfo.UNKNOWN));
        assertTrue(fixed.everyState());
    }

    @Test
    public void reconcilingIsIdempotentBecauseClearingOnlyEverWidens() {
        // ONE PASS CONVERGES, and this is the assertion behind that claim. Dropping a
        // selection can only add rows, so nothing cleared can make something else newly
        // impossible -- which is why `reconcile` is not a loop.
        MachineTable table = table();
        MachineTable.Filter once = table.reconcile(MachineTable.Filter.NONE
                .withMod("Alpha")
                .toggleState(MachineInfo.BUILDABLE)
                .toggleState(MachineInfo.UNKNOWN));
        MachineTable.Filter twice = table.reconcile(once);
        assertEquals(once.mod(), twice.mod());
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            assertEquals("state " + state, once.state(state), twice.state(state));
        }
        assertEquals(table.rows(once).size(), table.rows(twice).size());
    }

    @Test
    public void aLiveSelectionIsLeftAlone() {
        // The half that would pass by clearing everything. A `reconcile` that dropped a
        // working filter would look identical in the two tests above and would make the
        // screen impossible to use.
        MachineTable table = table();
        MachineTable.Filter good = MachineTable.Filter.NONE
                .withMod("Beta")
                .toggleState(MachineInfo.BUILDABLE);
        MachineTable.Filter after = table.reconcile(good);
        assertEquals("Beta", after.mod());
        assertTrue(after.state(MachineInfo.BUILDABLE));
        assertEquals(2, table.rows(after).size());
    }

    // -- the mod list's own order (#32) --------------------------------------------------------

    @Test
    public void modsWithNothingInTheCurrentFilterSinkBelowTheOnesThatHaveSomething() {
        // #16 disabled them in place; #32 found that left the mods that DO match scattered
        // among the ones that do not, so the answer was unreachable. They keep their place in
        // the list and stop being in the way.
        MachineTable table = table();
        MachineTable.Filter buildable =
                MachineTable.Filter.NONE.toggleState(MachineInfo.BUILDABLE);
        assertEquals("[Beta, Alpha]",
                     table.modsInOfferOrder(table.narrowed(buildable)).toString());
    }

    @Test
    public void theBaseOrderIsKeptInsideEachGroupRatherThanRecomputed() {
        // The empty group is one enormous tie -- filtering to `no route` leaves 74 of 77 mods
        // at zero -- and re-sorting a tie by anything is how the browser and Python came to
        // disagree about all 74. Both groups here keep `mods()` order.
        GraphBuilder b = new GraphBuilder();
        recipes(b, "aaa.x", "Aaa", 1);
        recipes(b, "bbb.x", "Bbb", 1);
        recipes(b, "ccc.x", "Ccc", 1);
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()
                .override("aaa.x", MachineInfo.HAVE)
                .override("bbb.x", MachineInfo.UNAVAILABLE)
                .override("ccc.x", MachineInfo.UNAVAILABLE)));
        assertEquals("[Aaa, Bbb, Ccc]", table.mods().toString());
        MachineTable.Filter unavailable =
                MachineTable.Filter.NONE.toggleState(MachineInfo.UNAVAILABLE);
        assertEquals("[Bbb, Ccc, Aaa]",
                     table.modsInOfferOrder(table.narrowed(unavailable)).toString());
    }

    @Test
    public void everyModIsStillOfferedWhateverTheFilter() {
        // Removed options make the list jump under the cursor and a visible zero is an answer,
        // so the length never changes.
        MachineTable table = table();
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            MachineTable.Filter one = MachineTable.Filter.NONE.toggleState(state);
            assertEquals("state " + state, table.mods().size(),
                         table.modsInOfferOrder(table.narrowed(one)).size());
        }
    }

    // -- what a row carries --------------------------------------------------------------------

    @Test
    public void aRowWithNoTitleFallsBackToItsUidSoTheNameColumnIsNeverBlank() {
        // 40% of the reference pack could not be name-matched, and a blank name column would
        // make exactly the rows a reader came for unreadable.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "mystery.category", null, "test", false, false);
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()));
        assertEquals("mystery.category", row(table, "mystery.category").name());
    }

    @Test
    public void aHandSetVerdictIsMarkedManualBecauseItsEvidenceDoesNotExplainItsColour() {
        assertTrue(row(table(), "alpha.one").manual());
    }

    @Test
    public void everyCandidateIsCarriedAcrossWithItsOwnVerdictRatherThanJustTheWinner() {
        // `MachineInfo.candidates` -- "Smelting is done in more than just the controller" is
        // true of a lot of categories, and reporting one verdict hides the other blocks that
        // would also do (#27). The detail panel is the only surface that can show it, so the
        // row has to carry them.
        GraphBuilder b = new GraphBuilder();
        recipes(b, "mod.press", "Mod", 1);
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press_a"));
        b.catalystKey(b.key("mod:press_b"));
        b.endCatalyst();
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()));
        MachineTable.Row row = row(table, "mod.press");
        assertEquals(2, row.candidates().size());
        for (MachineTable.Candidate candidate : row.candidates()) {
            assertTrue(candidate.key(), candidate.key().startsWith("mod:press_"));
        }
    }

    @Test
    public void aCatalystDerivedVerdictSaysSoInItsEvidenceBecauseItIsJeisMappingNotOurGuess() {
        GraphBuilder b = new GraphBuilder();
        recipes(b, "mod.press", "Mod", 1);
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press_a"));
        b.endCatalyst();
        RecipeGraph graph = b.build();
        MachineTable table = MachineTable.of(graph, Machines.resolve(graph, new Evidence()));
        assertTrue(row(table, "mod.press").why(),
                   row(table, "mod.press").why().endsWith(" (from JEI)"));
    }
}
