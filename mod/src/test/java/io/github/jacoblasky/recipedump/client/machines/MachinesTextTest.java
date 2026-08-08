package io.github.jacoblasky.recipedump.client.machines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.MachineTable;

import org.junit.Test;

/**
 * Every string and every derived column width the machines screen computes.
 *
 * WHY THESE NEED THEIR OWN TEST WHEN `MachinesLayoutTest` ALREADY LAYS THE PANEL OUT. That one
 * asserts boxes; it never reads a single character. So a chip that stopped marking itself as
 * selected, a footer that said "503 of 503 shown", or a manual verdict that lost its `(manual)`
 * mark would all lay out perfectly and pass every geometry assertion in the package. The
 * screenshot would catch it -- in about two minutes, once, for one filter state, and only if
 * the reviewer looked closely at the right row.
 *
 * THE SELECTED-CHIP MARK IS THE ONE THAT MOST NEEDED PINNING. It is a bracket AND a colour
 * rather than a colour alone, which is a deliberate accessibility decision argued at length in
 * {@link MachinesWidgets#chips} -- and a decision recorded only in a comment is a decision the
 * next person deletes, because nothing fails when they do.
 */
public class MachinesTextTest {

    private static MachineTable table() {
        return MachineTables.wide();
    }

    private static MachineTable.Row anyRow(MachineTable table, boolean manual) {
        for (MachineTable.Row row : table.allRows()) {
            if (row.manual() == manual) {
                return row;
            }
        }
        throw new AssertionError("the fixture has no manual=" + manual + " row");
    }

    // -- the state chips -----------------------------------------------------------------------

    @Test
    public void aSelectedChipIsMarkedWithBracketsAndNotOnlyWithColour() {
        // COLOUR ALONE IS NOT A SIGNAL HERE, and this is the assertion that says so. The chips
        // already carry the four state colours, so a lit `no route` chip and an unlit one would
        // differ only in how red they are -- unreadable in a greyscale screenshot, unreadable
        // with a red-green deficiency, and ambiguous even with perfect colour vision.
        String off = MachinesWidgets.chipText(MachineInfo.UNAVAILABLE, 3, false);
        String on = MachinesWidgets.chipText(MachineInfo.UNAVAILABLE, 3, true);
        assertNotEquals("a selected chip must not read identically to an unselected one",
                        off, on);
        assertFalse(off, off.startsWith("["));
        assertTrue(on, on.startsWith("[") && on.endsWith("]"));
        assertTrue(on, on.contains(MachineLabels.label(MachineInfo.UNAVAILABLE)));
    }

    @Test
    public void aChipCarriesItsNarrowedCountGrouped() {
        assertTrue(MachinesWidgets.chipText(MachineInfo.HAVE, 14410, false).contains("14,410"));
    }

    @Test
    public void aChipWithNothingBehindItIsMutedEvenWhenSelected() {
        // THE ZERO WINS OVER THE SELECTION, and the ordering is the point: a lit chip matching
        // nothing is exactly what `MachineTable.reconcile` is about to clear, so drawing it in
        // its full state colour would advertise a selection one frame from disappearing.
        assertEquals(NodeStatus.INK_MUTED,
                     MachinesWidgets.chipColour(MachineInfo.HAVE, 0, true));
        assertEquals(NodeStatus.INK_MUTED,
                     MachinesWidgets.chipColour(MachineInfo.HAVE, 0, false));
    }

    @Test
    public void aSelectedChipWithRowsBehindItTakesItsStateColour() {
        // The half that would pass by muting everything.
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            assertEquals("state " + state, MachineLabels.colour(state),
                         MachinesWidgets.chipColour(state, 1, true));
            assertEquals("an unselected chip is muted whatever its state", NodeStatus.INK_MUTED,
                         MachinesWidgets.chipColour(state, 1, false));
        }
    }

    // -- the mod filter ------------------------------------------------------------------------

    @Test
    public void theModButtonSaysEveryWhenNothingIsChosen() {
        MachineTable table = table();
        assertEquals("mod: every",
                     MachinesWidgets.modButtonText(MachineTable.Filter.NONE,
                                                   table.narrowed(MachineTable.Filter.NONE)));
    }

    @Test
    public void theModButtonNamesTheChosenModAndItsNarrowedCount() {
        MachineTable table = table();
        String mod = table.mods().get(0);
        MachineTable.Filter filter = MachineTable.Filter.NONE.withMod(mod);
        String text = MachinesWidgets.modButtonText(filter, table.narrowed(filter));
        assertTrue(text, text.startsWith("mod: " + mod + " ("));
        assertTrue(text, text.endsWith(")"));
    }

    @Test
    public void theWayOutOfTheFilterIsSpeltDifferentlyFromAModNamed() {
        // The "every mod" row is the way OUT of a filter and must not read like a mod called
        // "every mod". `modRow` passes null for it, and that null is the whole distinction.
        assertEquals("every mod (503)", MachinesWidgets.modRowText(null, 503));
        assertEquals("NuclearCraft (28)", MachinesWidgets.modRowText("NuclearCraft", 28));
    }

    // -- the table's own text ------------------------------------------------------------------

    @Test
    public void theHeadingCarriesBothTotalsBecauseEitherAloneFlatters() {
        MachineTable table = table();
        String heading = MachinesWidgets.heading(table);
        assertTrue(heading, heading.contains("categories"));
        assertTrue(heading, heading.contains("recipes"));
        assertTrue(heading, heading.contains(NodeRowText.grouped(table.allRows().size())));
        long recipes = 0;
        for (int count : table.recipeTotals()) {
            recipes += count;
        }
        assertTrue(heading, heading.contains(NodeRowText.grouped(recipes)));
    }

    @Test
    public void theFooterOnlySaysNOfMWhenSomethingIsActuallyHidden() {
        // "503 of 503 shown" is noise that reads as a filter being active. The unfiltered case
        // is the common one and it should say what the table holds, not do arithmetic.
        assertEquals("503 categories", MachinesWidgets.footer(503, 503));
        assertEquals("12 of 503 shown", MachinesWidgets.footer(12, 503));
        assertEquals("0 of 503 shown", MachinesWidgets.footer(0, 503));
    }

    @Test
    public void aHandSetVerdictIsMarkedOnTheRowAndInTheDetail() {
        // `(manual)` IS NOT DECORATION: a hand-set state outranks every automatic verdict, so
        // it is the one row whose evidence sentence does not explain the colour beside it.
        MachineTable table = table();
        MachineTable.Row manual = anyRow(table, true);
        assertTrue(MachinesWidgets.rowName(manual),
                   MachinesWidgets.rowName(manual).endsWith(" (manual)"));
        assertTrue(MachinesWidgets.detailVerdict(manual),
                   MachinesWidgets.detailVerdict(manual).endsWith(" (manual)"));
        assertTrue(MachinesWidgets.rowName(manual).startsWith(manual.name()));
    }

    @Test
    public void anAutomaticVerdictCarriesNoManualMark() {
        MachineTable.Row automatic = anyRow(MachineTables.withCandidates(), false);
        assertEquals(automatic.name(), MachinesWidgets.rowName(automatic));
        assertFalse(MachinesWidgets.detailVerdict(automatic),
                    MachinesWidgets.detailVerdict(automatic).contains("(manual)"));
    }

    @Test
    public void theDetailVerdictLeadsWithTheLabelAndSaysHowMuchTheCategoryCarries() {
        MachineTable.Row row = MachineTables.pressRow();
        String verdict = MachinesWidgets.detailVerdict(row);
        assertTrue(verdict, verdict.startsWith(MachineLabels.label(row.state())));
        assertTrue(verdict, verdict.contains(NodeRowText.grouped(row.recipes())));
        assertTrue(verdict, verdict.contains("recipes"));
    }

    @Test
    public void theCandidatesHeadingAgreesWithItsOwnNumber() {
        // "1 candidate blocks" is the kind of thing nobody notices in a screenshot and
        // everybody notices in a bug report.
        assertEquals("1 candidate block", MachinesWidgets.candidatesHeading(1));
        assertEquals("0 candidate blocks", MachinesWidgets.candidatesHeading(0));
        assertEquals("3 candidate blocks", MachinesWidgets.candidatesHeading(3));
    }

    // -- the derived column width --------------------------------------------------------------

    private static List<MachineTable.Row> rowsWithRecipeCounts(int... counts) {
        MachineTable table = MachineTables.wide();
        List<MachineTable.Row> picked = new ArrayList<MachineTable.Row>();
        for (int wanted : counts) {
            for (MachineTable.Row row : table.allRows()) {
                if (row.recipes() == wanted) {
                    picked.add(row);
                    break;
                }
            }
        }
        assertEquals("the fixture must hold a row for every requested count",
                     counts.length, picked.size());
        return picked;
    }

    @Test
    public void theRecipeColumnIsSizedFromTheRowsOnScreenRatherThanTheWholeTable() {
        // Filtering to `no route` can leave three categories carrying two recipes each, and a
        // column sized for the pack's five-figure maximum would spend 30 pixels of a 388-pixel
        // panel drawing space beside `2`.
        int narrow = MachinesWidgets.recipeColumnWidth(rowsWithRecipeCounts(1, 2),
                                                       MachinesWidgets.CONTENT_WIDTH);
        int wide = MachinesWidgets.recipeColumnWidth(rowsWithRecipeCounts(1, 2, 14410),
                                                     MachinesWidgets.CONTENT_WIDTH);
        assertTrue(narrow + " should be narrower than " + wide, narrow < wide);
        assertEquals("one character per digit", NodeRowText.CHAR_WIDTH, narrow);
        assertEquals("six characters for `14,410`", 6 * NodeRowText.CHAR_WIDTH, wide);
    }

    @Test
    public void theRecipeColumnIsCappedByWhatIsLeftAfterTheNameColumnsFloor() {
        // The cap that stops a runaway count from squeezing the name out. Per #125 nothing
        // would report the overflow if it did. Sized so the room left is POSITIVE but smaller
        // than the six characters `14,410` wants -- the interesting case, and the one a
        // `Math.min` in the wrong order gets wrong.
        int room = MachinesWidgets.STATE_COLUMN + MachinesWidgets.GAP * 2
                + MachinesWidgets.MIN_NAME + 12;
        int width = MachinesWidgets.recipeColumnWidth(rowsWithRecipeCounts(14410), room);
        assertEquals("the column takes what is left and no more", 12, width);
        assertTrue("and that is less than it asked for",
                   width < 6 * NodeRowText.CHAR_WIDTH);
    }

    @Test
    public void aPanelTooNarrowForBothColumnsDropsTheCountRatherThanGoingNegative() {
        // A negative width is not a smaller column, it is a box ModularUI will lay out at zero
        // and a `pos(width - recipeWidth)` that lands to the RIGHT of the panel edge. The floor
        // is what makes `tableRow` able to ask "is there a recipe column at all".
        int tooNarrow = MachinesWidgets.STATE_COLUMN + MachinesWidgets.MIN_NAME;
        assertEquals(0, MachinesWidgets.recipeColumnWidth(rowsWithRecipeCounts(14410),
                                                          tooNarrow));
    }

    @Test
    public void anEmptyTableAsksForNoRecipeColumnAtAll() {
        assertEquals(0, MachinesWidgets.recipeColumnWidth(
                new ArrayList<MachineTable.Row>(), MachinesWidgets.CONTENT_WIDTH));
    }
}
