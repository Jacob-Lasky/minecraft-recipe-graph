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
     * `key: value, value` list and a dash after that reads as another list item. It is also
     * four characters shorter, which the three-line cap does not need today and will if a
     * sixth input is declared.
     */
    static final String POINTER = "(click for what each costs you)";

    /** The heading of the detail panel. */
    static final String TITLE = "What this plan could not see";

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

    /** True when there is anything to disclose, so the caveat line is worth a click. */
    public static boolean any() {
        return !ScenarioSource.missingNotes().isEmpty();
    }

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
        if (notes.isEmpty()) {
            // NOT AN EMPTY PANEL. Every input live is a real and reachable state -- it is what
            // `ScenarioSourceTest` asserts with every reader installed -- and a blank window
            // reads as a rendering fault rather than as good news.
            out.add("every input was read; this plan assumes nothing");
            return out;
        }
        for (int i = 0; i < notes.size(); i++) {
            String numbered = (i + 1) + ". " + notes.get(i);
            // WRAPPED WITH NO LINE LIMIT, hence `Integer.MAX_VALUE` rather than a number: the
            // limit argument is what `fit` would cut on, and there is nothing here worth
            // cutting. The longest note is 135 characters and wraps to three lines.
            out.addAll(NodeRowText.wrap(numbered, widthPx, Integer.MAX_VALUE));
        }
        return out;
    }
}
