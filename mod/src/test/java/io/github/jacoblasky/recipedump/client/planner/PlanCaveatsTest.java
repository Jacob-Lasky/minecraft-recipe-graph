package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.github.jacoblasky.recipedump.client.LiveStock;
import io.github.jacoblasky.recipedump.client.PlannerStock;
import io.github.jacoblasky.recipedump.common.GraphDocuments;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.github.jacoblasky.recipedump.plan.Solver;

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

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String savedGraphProperty;

    @Before
    @After
    public void isolate() {
        // ALL FOUR ARE STATIC. A leaked reader answers for the next test's `have`, a leaked
        // snapshot decides whether the summary line carries a refusal (#321), and a leaked plan
        // decides whether the panel reports stranded stock (#322) -- so a case that forgot any of
        // them would set the next one's answer. `@Before` as well as `@After` because a case in
        // ANOTHER class can leave the same state behind.
        ScenarioSource.resetReaders();
        LiveStock.forget();
        PlannerService.get().reset();
        GraphService.get().reset();
    }

    @Before
    public void rememberGraphProperty() {
        savedGraphProperty = System.getProperty(GraphSource.PROPERTY);
    }

    @After
    public void restoreGraphProperty() {
        if (savedGraphProperty == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, savedGraphProperty);
        }
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
        // A `lines.size() <= MAX_CAVEAT_LINES` assertion used to sit here and was DELETED as
        // tautological: `wrap` loops on `lines.size() < maxLines`, so it asserted the wrapper's own
        // loop condition and would have held with the caveat cut to a single ellipsis. The
        // round-trip below is what actually says nothing was lost.
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
        // MATCHED ON THE CONSTANT'S OWN OPENING WORDS rather than on a remembered phrase. This
        // used to search for the literal "means machines connected", and #320 phase 2 reworded the
        // sentence -- at which point the loop below stopped examining any line at all and the
        // assertion inside it became unreachable while the test went on passing. A slice of the
        // constant cannot go stale that way.
        String opening = PlanCaveats.PLACED_SCOPE.substring(0, 24);
        boolean sawTheScopeSentence = false;
        for (String line : lines) {
            if (line.contains(opening)) {
                sawTheScopeSentence = true;
                assertFalse("the scope sentence opens with a note number: " + line,
                            line.matches("^\\d+\\. .*"));
            }
        }
        assertTrue("the scope sentence was not on the panel at all, so the numbering assertion"
                   + " above examined nothing", sawTheScopeSentence);
    }

    /**
     * The scope sentence covers BOTH sources of `placed`, which is #320 phase 2.
     *
     * WITNESS. On master this sentence said only "machines connected to the ME network your
     * wireless terminal is linked to; one sitting off the network still reads as buildable" -- and
     * after phase 2 that is false in the direction that matters: a machine sitting off the network
     * DOES count now if its chunk is loaded, and what does not count is a machine in an unloaded
     * chunk. A player who read the old sentence and built a machine off-grid would conclude the
     * planner could not see it, which is the reverse of the truth.
     */
    @Test
    public void thePlacedScopeNamesTheLoadedChunkScanAndNotOnlyTheGrid() {
        assertTrue("the sentence must still name the ME network half: "
                   + PlanCaveats.PLACED_SCOPE,
                   PlanCaveats.PLACED_SCOPE.contains("ME network"));
        assertTrue("the sentence never mentions the loaded-chunk half, so a player cannot know"
                   + " an off-grid machine now counts: " + PlanCaveats.PLACED_SCOPE,
                   PlanCaveats.PLACED_SCOPE.contains("loaded"));
        assertTrue("and it must say what is still NOT counted, which is now the unloaded chunk"
                   + " rather than the off-grid machine: " + PlanCaveats.PLACED_SCOPE,
                   PlanCaveats.PLACED_SCOPE.contains("unloaded"));
        assertFalse("the old sentence's claim that an off-grid machine reads buildable is now"
                    + " wrong: " + PlanCaveats.PLACED_SCOPE,
                    PlanCaveats.PLACED_SCOPE.contains("off the network"));
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
     * every time; now each refusal names a different thing to go and do -- your network is not
     * loaded, no terminal in your inventory, the terminal is out of power, nobody has asked yet --
     * while `summary()` still answers `planned without: have` for all of them. If the panel showed
     * the declared constant instead of the reader's answer, this whole feature would be decorative.
     */
    @Test
    public void aRuntimeRefusalsOwnWordsAreWhatTheDetailPanelShows() {
        // THE REAL WORDING OF A REAL REFUSAL, taken from the enum rather than spelt here, so it
        // cannot come to quote a message the mod no longer sends -- which is what happened to this
        // literal twice over: it read "no wireless access point in range" through #331's rewording
        // and then through #331 RETIRING that reason altogether. Which reason it is does not matter
        // to this test; that it differs from `HAVE`'s declared constant does, and the assertion
        // below checks that rather than assuming it. `NO_POWER` because it is a refusal a player
        // can actually be handed.
        final String refusal = StockSnapshot.Reason.NO_POWER.message();
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

    // -- the refusal reason on the line nobody has to click for (#321 §1) -----------------------

    /**
     * WITNESS. The always-visible line carries the refusal's own sentence.
     *
     * ON MASTER THIS FAILS, and it is the whole of #321 §1: `summaryLine` was
     * `ScenarioSource.summary() + " " + POINTER`, so a player standing at a wired ME Terminal with
     * no wireless terminal on them read `planned without: have, placed, ...` and had no way to
     * learn that the answer was "pick up your wireless terminal". The reason string was computed,
     * stored and rendered -- on a panel behind a click they had no reason to make.
     *
     * GOES THROUGH `LiveStock` RATHER THAN A STUBBED READER, because the claim under test is that
     * the sentence travels from the held snapshot to the line. A test that installed a
     * `ScenarioSource.HAVE` reader returning the string would assert that a string I supplied comes
     * back, which is what #190's own header calls the shape of a test that passes for as long as
     * the defect exists.
     */
    @Test
    public void theSummaryLineLeadsWithTheRefusalsOwnReason() {
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL));
        PlannerStock.install();
        String line = PlanCaveats.summaryLine();
        String reason = StockSnapshot.Reason.NO_TERMINAL.message();
        assertTrue("the refusal reason is still only reachable by clicking: " + line,
                   line.contains(reason));
        assertTrue("the reason has to be the first thing read, not an afterthought: " + line,
                   line.startsWith(reason));
        assertTrue("the field list must survive beside it: " + line,
                   line.contains(ScenarioSource.HAVE.field()));
        assertTrue("and so must the way in: " + line, line.contains(PlanCaveats.POINTER));
    }

    /**
     * A SUCCESSFUL read adds no reason, because there is none to give.
     *
     * THE CONTROL. Without it, a `summaryLine` that appended some fixed sentence unconditionally
     * would pass the witness above, and the line would grow a permanent extra clause that says
     * nothing -- which is the "tidy sentence that looks like ordinary UI and nobody reports"
     * failure `ScenarioSource.HAVE`'s own javadoc names.
     */
    @Test
    public void aSuccessfulReadAddsNoReasonToTheLine() {
        LiveStock.accept(StockSnapshot.of(
                java.util.Collections.singletonMap("minecraft:stone", Long.valueOf(1L)),
                io.github.jacoblasky.recipedump.common.ae2.Snapshots.NO_MACHINES));
        PlannerStock.install();
        String line = PlanCaveats.summaryLine();
        assertTrue("some inputs are still unread, so there is a line to examine: " + line,
                   line.contains(PlanCaveats.POINTER));
        assertTrue("a successful read must not prepend anything: " + line,
                   line.startsWith(ScenarioSource.summary()));
    }

    /**
     * GUARD. Every refusal reason fits the plan panel's three-line cap with the pointer intact.
     *
     * THE CAP TRUNCATES, and `POINTER`'s own javadoc says it is the half that must never be the
     * casualty -- "drop a field name from the list first, since the fields are recoverable from the
     * panel and the way in is not". Leading the line with a reason spends 25 to 49 characters of a
     * budget that had roughly one line of headroom, so "it fits" is a measurement rather than an
     * argument, and it has to hold for the LONGEST reason rather than for the one I happened to
     * try. This is the test to run before lengthening any `Reason.message()`, and #331 ran it:
     * `NETWORK_GONE` went from 35 characters to 47 on the strength of this passing afterwards. The
     * figure said "34-46" until then, which was wrong at both ends -- `Reason`'s own header records
     * the same correction and is the authority on the range.
     *
     * IT STILL SWEEPS `OUT_OF_RANGE`, WHICH NOTHING MINTS. That value is retired (#331 deleted the
     * range gate it reported, having measured that the gate never passed on this pack) but stays
     * decodable off the wire, so if a mismatched build ever sends it the sentence has to fit rather
     * than shove the pointer off the line. Sweeping `values()` rather than a hand-kept list is what
     * makes that free.
     */
    @Test
    public void everyRefusalReasonFitsTheCaveatCapWithThePointerIntact() {
        int checked = 0;
        for (StockSnapshot.Reason reason : StockSnapshot.Reason.values()) {
            if (reason == StockSnapshot.Reason.OK) {
                continue;
            }
            ScenarioSource.resetReaders();
            LiveStock.forget();
            LiveStock.accept(StockSnapshot.unavailable(reason));
            PlannerStock.install();
            String line = PlanCaveats.summaryLine();
            List<String> lines = NodeRowText.wrap(line, PlannerWidgets.CONTENT_WIDTH,
                                                  PlannerWidgets.MAX_CAVEAT_LINES);
            // THERE IS DELIBERATELY NO `lines.size() <= MAX_CAVEAT_LINES` ASSERTION. `wrap` loops
            // on `lines.size() < maxLines`, so that can never be false -- it is an assertion about
            // the wrapper's for-loop dressed up as one about the caveat, and it would pass with the
            // line truncated to nothing. The pointer and ellipsis checks below are the real test.
            assertTrue(reason + " pushed the way-in pointer off the line: " + lines,
                       rejoin(lines).contains(PlanCaveats.POINTER));
            assertTrue(reason + " lost its own reason to the wrap: " + lines,
                       rejoin(lines).contains(reason.message()));
            for (String wrapped : lines) {
                assertFalse(reason + " produced a cut line: " + wrapped,
                            wrapped.endsWith(NodeRowText.ELLIPSIS));
            }
            checked++;
        }
        assertTrue("no reason was checked, so this asserted nothing", checked > 0);
    }

    // THE OUT-OF-RANGE WITNESS THAT STOOD HERE IS GONE, AND ITS SUBJECT WITH IT. #331
    //
    // It asserted that the refusal names the dimension rather than a distance, which was the right
    // fix for the wrong defect: a region scan of every dimension in the reference save found not one
    // vanilla `IWirelessAccessPoint` anywhere, and the pack's `ae2wut` terminal opens through the
    // wrapped mods' own GUI factories, so `Ae2StockReader`'s range gate had never passed for anyone
    // -- including inside the base. Better wording on a refusal that should never have fired is a
    // tidier bug. The gate is deleted and the reason is retired.
    //
    // WHAT REPLACED IT AND WHERE, because a deleted test that is not replaced is a deleted
    // assertion: `Ae2StockTest.noRangeGateSurvivesToRefuseALinkedLoadedGridWith`
    // `NoAccessPoint` scans the compiled reader's constant pool for `IWirelessAccessPoint` and for
    // `OUT_OF_RANGE` and finds neither,
    // with a control proving the scan can see the gates that remain. That is the regression test for
    // the reported world -- a linked, loaded, powered, permitted grid with no access point anywhere
    // reads OK -- expressed at the only level a JUnit run can reach it, because an `EntityPlayer`
    // cannot be constructed here and a faked grid would assert that a mock matches my code. It
    // lives in that class rather than this one because it is a fact about the READER, not about the
    // words on the caveat line.

    /**
     * WITNESS. The gone-network refusal leads with the cause a player can act on. #331
     *
     * ON MASTER THE MESSAGE IS "the linked network no longer exists", which is true of exactly one
     * of the two states that reach this reason and is the rarer one. The grid is found from the
     * security station the encryption key names, so a base whose chunks are not in memory is
     * indistinguishable from one that was dismantled -- and on a server with nobody at the base,
     * unloaded is ordinary. One of those a player can fix in a minute; the other tells him his ME
     * system has been deleted.
     *
     * BOTH HALVES ARE NAMED, because the read genuinely cannot tell them apart. Claiming only the
     * alarming one is the defect `NO_REPLY`'s hedge exists to avoid, one reason over.
     */
    @Test
    public void theGoneNetworkRefusalLeadsWithNotLoadedRatherThanDeleted() {
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NETWORK_GONE));
        PlannerStock.install();
        String line = PlanCaveats.summaryLine();
        String message = StockSnapshot.Reason.NETWORK_GONE.message();
        assertTrue("the summary line must carry the reason: " + line, line.contains(message));
        assertTrue("`not loaded` has to be the first cause read, because it is the one a player"
                   + " can act on: " + message,
                   message.indexOf("not loaded") >= 0
                           && message.indexOf("not loaded") < message.indexOf("no longer"));
        assertTrue("both halves stay, because the read cannot tell them apart: " + message,
                   message.contains("no longer exists"));
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertTrue("and the detail panel must point at the loaded-chunk cause: " + rejoined,
                   rejoined.contains("unloaded"));
    }

    /**
     * WITNESS. A refused read gets a line of its own that names the CONSEQUENCE. #331
     *
     * ON MASTER THERE IS NO SUCH LINE, and #321's refusal-led {@link PlanCaveats#summaryLine} is
     * why that was not obvious: the words existed, at the footer, under a tree of rows saying
     * `needed` in red. `PlannerLayoutTest.aRefusedStockReadIsSaidAboveTheTreeAndNotOnlyInTheFooter`
     * is the half that asserts WHERE it lands; this is the half that asserts the two lines say
     * different things, because two copies of the cause would be a line of tree spent on nothing.
     */
    @Test
    public void theRefusalLineNamesTheConsequenceAndNotTheCauseAgain() {
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL));
        PlannerStock.install();
        String prominent = PlanCaveats.stockRefusalLine();
        assertFalse("a refused read owes the player a line of its own", prominent.isEmpty());
        assertFalse("it must not repeat the cause the footer already carries in full: "
                    + prominent,
                    prominent.contains(StockSnapshot.Reason.NO_TERMINAL.message()));
        assertTrue("and it must say what the rows below it are wrong about: " + prominent,
                   prominent.contains("needed"));
        assertTrue("the footer keeps the cause, so between them nothing is lost: "
                   + PlanCaveats.summaryLine(),
                   PlanCaveats.summaryLine()
                           .startsWith(StockSnapshot.Reason.NO_TERMINAL.message()));
    }

    // -- stock the graph could not understand (#322) --------------------------------------------

    /**
     * WITNESS. A stock key the graph never heard of is admitted to, with its volume.
     *
     * ON MASTER NOTHING SAYS THIS AT ALL. `ScenarioInputs.resolve` drops any `have` key with no
     * key id -- no count, no log, no caveat -- while `have` goes on reporting live, so the plan
     * claims it consulted your stock while discarding part of it. After a redump the in-game
     * planner is exactly where a player notices "it told me to make something I have", and nothing
     * distinguished that from #321's refusals.
     *
     * THE VOLUME IS ASSERTED SEPARATELY FROM THE COUNT, because key count alone is the misleading
     * number: closed #80 measured 11,353 digest keys churning across redumps while almost all the
     * ITEMS live in plain keys that cannot miss. Three keys carrying 40,000 ingots and three
     * carrying twelve are the same count and opposite situations.
     */
    @Test
    public void strandedStockIsAdmittedToWithBothItsNumbers() throws Exception {
        planWithStock("mod:ingot", 5L, "mod:unknown_a", 40_000L, "mod:unknown_b", 2L);
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertTrue("nothing on the panel admits the graph dropped part of the stock: " + rejoined,
                   rejoined.contains("did not match this graph"));
        assertTrue("the KEY count is missing: " + rejoined,
                   rejoined.contains("2 stock entries"));
        assertTrue("the VOLUME is missing, which is the number that decides whether it matters: "
                   + rejoined, rejoined.contains("40,002 items"));
        // GROUPED, and asserted as such: an ungrouped `40002` is a number the reader has to count
        // digits on, which is `NodeRowText.grouped`'s own argument. Asserting the grouped form is
        // also what stops someone "simplifying" this back to string concatenation.
        assertFalse("the volume must go through the grouping formatter: " + rejoined,
                    rejoined.contains("40002"));
    }

    /**
     * And silence when every key landed, because good news nobody needs told is noise.
     *
     * THE CONTROL, and the offline reader's rule rather than a new one: `gaps.stock_coverage` (#92)
     * speaks only when the schemas could actually disagree.
     */
    @Test
    public void nothingIsSaidWhenEveryStockKeyLanded() throws Exception {
        planWithStock("mod:ingot", 5L);
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertFalse("a plan that stranded nothing still complained: " + rejoined,
                    rejoined.contains("did not match this graph"));
    }

    /**
     * And silence before any plan has been resolved, which is a DIFFERENT state.
     *
     * `PlannerService.strandedStock()` answers null until a resolve has happened, and null must not
     * be read as "nothing was stranded": one has nothing to say and the other is a claim. A caveat
     * panel opened before the first plan that announced clean stock coverage would be reporting on
     * a read that never took place.
     */
    @Test
    public void nothingIsSaidBeforeAnyPlanHasBeenResolved() {
        PlannerService.get().reset();
        assertTrue("reset must clear it, or this test cannot distinguish the two states",
                   PlannerService.get().strandedStock() == null);
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertFalse(rejoined, rejoined.contains("did not match this graph"));
    }

    /** One stranded key reads as "1 stock entry", because "1 stock entries" reads as a bug. */
    @Test
    public void theSentenceAgreesWithItselfAboutNumber() throws Exception {
        planWithStock("mod:unknown_a", 1L);
        String rejoined = rejoin(PlanCaveats.detailLines(PlannerWidgets.PANEL_WIDTH));
        assertTrue(rejoined, rejoined.contains("1 stock entry (1 item) did not match"));
        // AND THE PRONOUN AGREES. "1 stock entry ... so they were not counted" is the shape review
        // caught: the noun is singular and the verb was not, which reads as a string built by
        // concatenation rather than written for a player.
        assertTrue("singular entry needs a singular verb: " + rejoined,
                   rejoined.contains("so it was not counted as owned"));
        assertFalse(rejoined, rejoined.contains("they were"));
    }

    /**
     * Run a REAL plan whose `have` holds the given key/count pairs, and settle it.
     *
     * <h2>THE PRODUCTION PATH END TO END, AND THAT IS THE POINT OF THE SETUP COST</h2>
     *
     * The thread under test is long -- a stock reader answers `ScenarioSource.HAVE`,
     * `PlannerService.liveScenario` builds the document, `ScenarioInputs.resolve` strands what the
     * graph does not know, `PlannerService` keeps the pair, `PlanCaveats` reads it -- and every
     * shorter version of this test would stub out the half that has historically been broken. #191
     * and #320 were both "the value arrives, is stored, and nothing reads it"; a test that handed
     * `PlanCaveats` a number directly would pass with that seam severed.
     *
     * The graph is `GraphDocuments.TINY`, which knows `mod:plate` and `mod:ingot` and nothing else,
     * so any other key in `have` is genuinely stranded rather than declared to be.
     */
    private void planWithStock(Object... pairs) throws Exception {
        GraphDocuments.loadTinyGraphFrom(folder.getRoot());
        final JsonObject have = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            have.addProperty((String) pairs[i], (Long) pairs[i + 1]);
        }
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available(have);
            }
        });
        assertTrue("the plan was refused before it started",
                   PlannerService.get().plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES));
        long deadline = System.currentTimeMillis() + 60_000L;
        while (PlannerService.get().state() == PlannerService.State.PLANNING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the planner never finished: "
                                         + PlannerService.get().describe());
            }
            Thread.sleep(5L);
        }
        assertEquals("the plan did not succeed, so the caveat under test has no scenario behind"
                     + " it: " + PlannerService.get().describe(),
                     PlannerService.State.DONE, PlannerService.get().state());
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
