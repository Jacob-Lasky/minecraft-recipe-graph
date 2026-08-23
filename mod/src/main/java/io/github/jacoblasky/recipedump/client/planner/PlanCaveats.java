package io.github.jacoblasky.recipedump.client.planner;

import java.util.ArrayList;
import java.util.List;

import io.github.jacoblasky.recipedump.common.ScenarioSource;

/**
 * The words the planner uses to admit what it could not see.
 *
 * THE ADMISSION WAS WRITTEN AND NEVER REACHED A SCREEN, which is what #190 is about and is a
 * stricter version of the write-only pattern than the one that started the sweep: there a
 * feature was missing, here the admission of five missing features was missing.
 * `ScenarioSource.summary` renders and names the FIELDS that are not live -- `have`,
 * `craftables`, `placed` -- and `ScenarioSource.missingNotes` collects the hand-written
 * sentence explaining what each one COSTS the player, whose only callers were two tests.
 *
 * Compare what a player got with what had been written for them:
 *
 *   rendered:     `planned without: have`
 *   not rendered: `no wireless terminal in your inventory`
 *
 * The first is a symptom they cannot act on. The second tells them to go and find their
 * terminal. `ScenarioSource.Status.unavailable`'s own javadoc cites that difference as the
 * whole reason it takes a string rather than a boolean, and the string was the half that never
 * arrived. #191 made this strictly worse rather than better: before it, the unrendered
 * sentence said the same thing every time, and now each refusal names a different thing to do
 * while `summary` still answers `planned without: have` for all of them.
 *
 * TWO LEVELS, AND NEITHER LEVEL ALONE WORKS. The notes are around 470 characters across five
 * sources -- roughly eight lines at `PlannerWidgets.CONTENT_WIDTH` -- and the planner body is
 * 208 pixels at ten pixels a line, so printing them above the tree would spend half the window
 * on caveats and leave the tree a strip. Printing none of them is the bug. So {@link
 * #summaryLine} stays one wrapped line that SAYS THERE IS MORE AND HOW TO GET IT, and {@link
 * #detailLines} is the full text on its own panel.
 *
 * THE POINTER IS WHAT MAKES THIS HONEST RATHER THAN MERELY TIDY. A detail panel a player has
 * to guess exists is the same defect one level down, so the summary line names the gesture. DO
 * NOT drop the pointer to save characters; drop a field name from the list first, since the
 * fields are recoverable from the panel and the way in is not.
 */
public final class PlanCaveats {

    /**
     * What the summary line adds to say the explanation exists.
     *
     * PARENTHESISED RATHER THAN SET OFF WITH A DASH, because the line it joins is already a
     * `key: value, value` list and a dash after that reads as another item in the list. The two
     * forms are the same length to within a character, so this is a reading argument and not a
     * budget one.
     */
    static final String POINTER = "(click for what each costs you)";

    /** The heading of the detail panel. */
    static final String TITLE = "What this plan could not see";

    /**
     * What `placed` means in game, said in full, because it is narrower than the field name.
     *
     * A SOURCE THAT READ SOMETHING NARROW STILL OWES THE PLAYER THE SHAPE OF IT. #320 made
     * {@link ScenarioSource#PLACED} live by enumerating the machines on the ME network the
     * player's wireless terminal is linked to -- not by scanning the world, which is what the
     * offline tool does and what the field name suggests. So a machine standing off the network
     * reads `buildable` while the plan carries no caveat at all, and a player who read
     * `planned without: placed` yesterday and reads nothing today would reasonably conclude
     * every machine they own is now accounted for.
     *
     * ON THE DETAIL PANEL AND NOT THE SUMMARY LINE, which is a constraint rather than a
     * preference: {@link #summaryLine} is empty when nothing is missing and the planner reserves
     * its row from exactly that emptiness, so a sentence there would steal a line of the tree on
     * every plan forever. The panel sizes to its content and can afford one more line.
     */
    static final String PLACED_SCOPE =
            "\"placed\" means machines connected to the ME network your wireless terminal is"
            + " linked to; one sitting off the network still reads as buildable";

    private PlanCaveats() {
    }

    /**
     * The always-visible line: which inputs are missing, and that there is more to read.
     *
     * Empty when every input is live, which is what {@link ScenarioSource#summary} already
     * answers and what the panel treats as "reserve no caveat lines".
     */
    public static String summaryLine() {
        String summary = ScenarioSource.summary();
        if (summary.isEmpty()) {
            return "";
        }
        return summary + " " + POINTER;
    }

    // THERE IS DELIBERATELY NO `any()` HERE. One was written and had no caller outside a test,
    // which in this class of all places is the defect rather than a convenience: `plannerPanel`
    // already decides whether to reserve caveat lines from `summaryLine().isEmpty()`, and
    // `detailLines` handles the nothing-missing case itself. A third way to ask the same
    // question is a third thing to keep in step. DO NOT add one back without a caller.

    /**
     * The full disclosure, wrapped to `widthPx`, one numbered entry per missing source.
     *
     * NUMBERED RATHER THAN BULLETED, and the count is the point. A player who has read
     * `planned without: have, craftables, placed` on the plan needs to be able to tell that
     * this panel accounts for all three; five sentences in a column do not say how many
     * sentences there were. It is also what makes a note dropped by a future cap visible.
     *
     * EVERY NOTE IN FULL, NEVER CAPPED. `PlannerWidgets.MAX_CAVEAT_LINES` exists because the
     * TREE pays for the summary line's height, which is the constraint that made the notes
     * unshowable in the first place. This panel has no tree under it and sizes to its content,
     * so the reason for a cap is absent -- and a truncated list of what the planner could not
     * see is a shorter, wrong list, which is `plannerPanel`'s own argument for wrapping the
     * summary rather than cutting it.
     */
    public static List<String> detailLines(int widthPx) {
        List<String> notes = ScenarioSource.missingNotes();
        List<String> out = new ArrayList<String>();
        // THE SCOPE LINE IS APPENDED AT THE END rather than woven into the numbering, and the
        // numbering is why: a player counts the entries against `planned without: a, b, c`, so
        // an unnumbered sentence after them cannot be mistaken for a fourth missing input.
        // It is added AFTER the branch below, so the every-input-was-read case gets it too --
        // that case is where the scope is least obvious and most likely to be over-read.
        if (notes.isEmpty()) {
            // NOT AN EMPTY PANEL. Every input live is a real and reachable state -- it is what
            // `ScenarioSourceTest` asserts with every reader installed -- and a blank window
            // reads as a rendering fault rather than as good news.
            //
            // Wrapped like every other line here even though it fits today, because the
            // asymmetry is the hazard: a line added straight to the list is one `line()` would
            // cut with an ellipsis if anyone lengthened it, in the one panel where nothing may
            // be cut.
            out.addAll(NodeRowText.wrapRow("every input was read; this plan assumes nothing",
                                           widthPx));
            out.addAll(scopeLines(widthPx));
            return out;
        }
        for (int i = 0; i < notes.size(); i++) {
            // THROUGH `wrapRow`, SO THE CONTINUATIONS ARE INDENTED AND THE NUMBERS FORM A
            // COLUMN. Found by looking at the screenshot: with every line flush left, the
            // second line of note 1 sat directly under "1." and the eye had to read the text
            // to find where note 2 began. `wrapRow` also caps nothing, which is what this
            // panel needs -- see the paragraph above. The notes run to 135 characters as
            // declared constants and around 105 once a reader answers, so most take two lines.
            out.addAll(NodeRowText.wrapRow((i + 1) + ". " + notes.get(i), widthPx));
        }
        out.addAll(scopeLines(widthPx));
        return out;
    }

    /**
     * {@link #PLACED_SCOPE}, wrapped, when `placed` was read -- and nothing when it was not.
     *
     * ONLY WHEN THE READ SUCCEEDED, because otherwise the panel says two things about the same
     * field: `PLACED`'s own note already explains that no machine counts as owned, and adding
     * the scope of a read that did not happen beside it describes a state the player is not in.
     *
     * PUBLIC FOR ITS TEST AND FOR NOTHING ELSE would be the wrong reason, so it is package
     * private: {@link #detailLines} is the only caller and the only surface, which is what keeps
     * the sentence from being rendered twice by a second panel that forgets the condition.
     */
    static List<String> scopeLines(int widthPx) {
        if (!ScenarioSource.PLACED.live()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(NodeRowText.wrapRow(PLACED_SCOPE, widthPx));
    }
}
