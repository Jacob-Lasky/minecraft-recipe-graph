package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Test;

import io.github.jacoblasky.recipedump.common.ScenarioSource;

/**
 * The disclosure, which is the whole of #190's first work item.
 *
 * WHAT THESE ASSERT IS THAT A STRING REACHES A READER, not that a method returns it. Every one
 * of the five notes was already correct, already hand-written for a player rather than a
 * developer, and already returned by `ScenarioSource.missingNotes` -- whose only two callers
 * were `ScenarioSourceTest` and `PinStoreTest`. A test that called `missingNotes` and checked
 * its contents would have passed for as long as the defect existed, which is exactly what those
 * two were doing. So these go through {@link PlanCaveats}, the thing the panel calls.
 */
public class PlanCaveatsTest {

    @After
    public void dropReaders() {
        // The readers are static and a leaked one answers for the next test's `have`.
        ScenarioSource.resetReaders();
    }

    /**
     * Every note reaches the panel, in full and in order.
     *
     * THE CENTRAL ASSERTION OF #190 AND THE ONE THAT WOULD HAVE FAILED BEFORE IT. Rejoining the
     * wrapped lines and searching for each note verbatim is what makes this catch a note that
     * is present but CUT, which is the failure a cap would introduce and the one the caveat
     * line on the plan panel already has to live with.
     */
    @Test
    public void everyMissingNoteReachesTheDetailPanelInFull() {
        List<String> notes = ScenarioSource.missingNotes();
        assertFalse("the fixture for this test is the real declared list; it must not be empty",
                    notes.isEmpty());
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH
                                                         - PlannerWidgets.PADDING * 2));
        for (String note : notes) {
            assertTrue("this note never reaches a screen: " + note, rejoined.contains(note));
        }
    }

    /**
     * The notes are numbered, and the count matches the fields the plan panel named.
     *
     * A player who read `planned without: have, craftables, placed` has to be able to tell
     * that this panel accounts for all of them. Five sentences in a column do not say how many
     * sentences there were, so a note silently dropped by a future cap would be invisible.
     */
    @Test
    public void theNotesAreNumberedSoADroppedOneWouldShow() {
        int count = ScenarioSource.missingNotes().size();
        List<String> lines = PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH);
        assertTrue("expected at least one line per note", lines.size() >= count);
        for (int i = 1; i <= count; i++) {
            assertTrue("no line opens \"" + i + ". \"", opensWith(lines, i + ". "));
        }
        assertFalse("a number beyond the note count means a note was counted twice",
                    opensWith(lines, (count + 1) + ". "));
    }

    /**
     * The summary line names the fields AND says the explanation exists.
     *
     * BOTH HALVES, because either alone is the bug. Without the field names this is less than
     * what shipped before #190; without the pointer the detail panel is a window a player has
     * to guess at, which is the same defect one level down.
     */
    @Test
    public void theSummaryLineNamesEveryMissingFieldAndTheWayIn() {
        String line = PlanCaveats.summaryLine();
        for (ScenarioSource source : ScenarioSource.values()) {
            if (!source.live()) {
                assertTrue(source.field() + " is missing from \"" + line + "\"",
                           line.contains(source.field()));
            }
        }
        assertTrue("the player cannot learn the detail panel exists: " + line,
                   line.contains(PlanCaveats.POINTER));
    }

    /**
     * The summary line survives the plan panel's three-line cap without being cut.
     *
     * THE CAP IS THE REASON THIS SPLIT EXISTS, so the half that stays on the plan panel has to
     * fit inside it. `MAX_CAVEAT_LINES` is 3 and was sized against the field list alone; adding
     * the pointer spends characters, and a wrap that cut would drop either the last field name
     * or the way in. Asserting on `PlanCaveats.summaryLine` rather than on
     * `ScenarioSource.summary` is the point of the test: the panel wraps the former, so a test
     * against the latter would pass while the real line truncated.
     */
    @Test
    public void theSummaryLineFitsTheCaveatCapWithoutCutting() {
        String line = PlanCaveats.summaryLine();
        assertFalse("the fixture for this test is the real caveat; it must not be empty",
                    line.isEmpty());
        List<String> lines = NodeRowText.wrap(line, PlannerWidgets.CONTENT_WIDTH,
                                              PlannerWidgets.MAX_CAVEAT_LINES);
        assertTrue("the caveat needs more than " + PlannerWidgets.MAX_CAVEAT_LINES
                   + " lines: " + lines, lines.size() <= PlannerWidgets.MAX_CAVEAT_LINES);
        assertEquals("the wrap lost part of the caveat", line, rejoin(lines).trim());
        for (String wrapped : lines) {
            assertFalse("a wrapped line must not also be cut: " + wrapped,
                        wrapped.endsWith(NodeRowText.ELLIPSIS));
        }
    }

    /**
     * Nothing missing is said as such, rather than as an empty window.
     *
     * A REACHABLE STATE AND NOT A THEORETICAL ONE: `ScenarioSourceTest` reaches it by
     * installing a reader on every source. A blank panel there reads as a rendering fault,
     * which is the argument `Status.unavailable` makes about tidy filler text, run the other
     * way round.
     */
    @Test
    public void everyInputLiveSaysSoInsteadOfDrawingNothing() {
        for (ScenarioSource source : ScenarioSource.values()) {
            source.readBy(new ScenarioSource.Reader() {
                @Override
                public ScenarioSource.Status status() {
                    return ScenarioSource.Status.available();
                }
            });
        }
        assertTrue("summary() should be empty with every input live",
                   ScenarioSource.summary().isEmpty());
        assertTrue("the plan panel must reserve no caveat lines",
                   PlanCaveats.summaryLine().isEmpty());
        List<String> lines = PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH);
        assertFalse("a blank panel reads as a rendering fault", lines.isEmpty());
        assertFalse(lines.get(0).isEmpty());
        assertTrue("the first line still says every input was read: " + lines.get(0),
                   lines.get(0).contains("every input was read"));
        // AND THE SCOPE LINE IS STILL THERE. Every input live is the state in which `placed`
        // being narrower than its name is LEAST obvious, so this is the one case the scope
        // sentence must not be dropped from. See `thePlacedScopeIsStatedOnceTheReadSucceeded`.
        assertTrue("the scope of `placed` vanished in the every-input-live case: " + lines,
                   rejoin(lines).contains(PlanCaveats.PLACED_SCOPE));
    }

    // -- what `placed` means once it is live (#320) ---------------------------------------------

    /**
     * A NARROW READ STILL OWES THE PLAYER ITS SHAPE.
     *
     * #320 made `placed` live by enumerating the machines on the ME network the wireless
     * terminal is linked to -- not by scanning the world, which is what the field name suggests
     * and what the offline tool actually does. So the caveat for `placed` DISAPPEARS while a
     * machine standing off the network still reads as buildable, and a player who read
     * `planned without: placed` yesterday and reads nothing today would reasonably conclude
     * every machine they own is accounted for. This is the sentence that stops that.
     */
    @Test
    public void thePlacedScopeIsStatedOnceTheReadSucceeded() {
        ScenarioSource.PLACED.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available(new com.google.gson.JsonObject());
            }
        });
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertTrue("the scope of what `placed` read never reaches a screen: " + rejoined,
                   rejoined.contains(PlanCaveats.PLACED_SCOPE));
        assertTrue("the sentence has to name the ME network, or it says nothing useful",
                   PlanCaveats.PLACED_SCOPE.contains("ME network"));
        assertTrue("and it has to name what is NOT counted",
                   PlanCaveats.PLACED_SCOPE.contains("buildable"));
    }

    /**
     * And NOT stated when the read refused, because then the refusal already explains it.
     *
     * Two sentences about the same field where one describes a state the player is not in is
     * worse than either alone: it makes the caveat panel look like boilerplate, which is
     * `MachineCaveats`' own argument against over-claiming, one level down.
     */
    @Test
    public void thePlacedScopeIsSilentWhenTheReadDidNotHappen() {
        // No reader installed at all, which is the declared-constant state.
        assertFalse(ScenarioSource.PLACED.live());
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertFalse("the scope of a read that did not happen: " + rejoined,
                    rejoined.contains(PlanCaveats.PLACED_SCOPE));
        // The declared note IS shown, so this is not silence about `placed` -- it is the other
        // sentence about it.
        assertTrue(rejoined, rejoined.contains(ScenarioSource.PLACED.note()));
    }

    @Test
    public void theScopeLineIsNotNumberedAndDoesNotInflateTheNoteCount() {
        // A player counts the numbered entries against `planned without: a, b, c`. An
        // unnumbered sentence after them cannot be mistaken for a further missing input, and
        // a numbered one would make the panel claim one more gap than the plan named.
        ScenarioSource.PLACED.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available(new com.google.gson.JsonObject());
            }
        });
        int count = ScenarioSource.missingNotes().size();
        assertTrue("`placed` going live must leave other sources missing, or this asserts"
                   + " nothing about numbering", count > 0);
        List<String> lines = PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH);
        assertFalse("the scope line was numbered as a missing input",
                    opensWith(lines, (count + 1) + ". "));
        for (String line : lines) {
            if (line.contains("means machines connected")) {
                assertFalse("the scope sentence opens with a note number: " + line,
                            line.matches("^\\d+\\. .*"));
            }
        }
    }

    @Test
    public void theScopeLineIsWrappedRatherThanCut() {
        // This panel caps nothing -- a truncated statement of what `placed` covers is a
        // shorter, WRONG statement, which is the same argument `detailLines` makes about the
        // notes it never caps.
        ScenarioSource.PLACED.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available(new com.google.gson.JsonObject());
            }
        });
        List<String> lines = PlanCaveats.scopeLines(PlannerWidgets.PANEL_WIDTH
                                                    - PlannerWidgets.PADDING * 2);
        assertTrue("the sentence is long enough to need wrapping; one line means the width"
                   + " assumption moved: " + lines, lines.size() > 1);
        assertEquals("the wrap lost part of the sentence",
                     PlanCaveats.PLACED_SCOPE, rejoin(lines).trim());
        for (String line : lines) {
            assertFalse("a line of this panel must never be cut: " + line,
                        line.endsWith(NodeRowText.ELLIPSIS));
        }
    }

    /**
     * A runtime refusal's own words are what the panel shows, not the compile-time constant.
     *
     * THIS IS WHAT #191 MADE URGENT. Before it, the unrendered sentence said the same thing
     * every time; now each refusal names a different thing to go and do -- no access point in
     * range, no terminal in your inventory, nobody has asked yet -- while `summary()` still
     * answers `planned without: have` for all three. If the panel showed the declared constant
     * instead of the reader's answer, this whole feature would be decorative.
     */
    @Test
    public void aRuntimeRefusalsOwnWordsAreWhatTheDetailPanelShows() {
        final String refusal = "no wireless access point in range";
        // READ BEFORE THE READER IS INSTALLED, which is the only moment it is reachable:
        // `note()` answers from the reader afterwards, so capturing it later would compare the
        // refusal with itself and the second assertion below would pass vacuously.
        String declared = ScenarioSource.HAVE.note();
        assertFalse("the declared constant and the refusal must differ for this to test "
                    + "anything", declared.equals(refusal));
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.unavailable(refusal);
            }
        });
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertTrue("the reader's reason never reached the panel: " + rejoined,
                   rejoined.contains(refusal));
        assertFalse("the declared constant was shown instead of the refusal that happened",
                    rejoined.contains(declared));
    }

    private static boolean opensWith(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The panel's lines back into one string, with the wrap undone.
     *
     * THE CONTINUATION INDENT HAS TO COME OFF, and forgetting it is what made this test fail
     * the moment `detailLines` started indenting: a note wrapped over two lines rejoined as
     * `assumes   you own nothing` with three spaces, so `contains(note)` was false about a note
     * that WAS fully on screen. A false negative there is the benign direction, but the same
     * blind spot in the other direction would have passed a note that got cut.
     */
    private static String rejoin(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(line.startsWith(NodeRowText.CONTINUATION)
                              ? line.substring(NodeRowText.CONTINUATION.length()) : line);
        }
        return sb.toString();
    }
}
