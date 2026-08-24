package io.github.jacoblasky.recipedump.client.planner;

import java.util.ArrayList;
import java.util.List;

import io.github.jacoblasky.recipedump.client.PlannerStock;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;

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
 * sentence said the same thing every time, and after it each refusal named a different thing to do
 * while `summary` still answered `planned without: have` for all of them.
 *
 * THAT SECOND HALF IS FIXED AND #321 IS WHERE IT WAS ARGUED. {@link #summaryLine} now LEADS with
 * the refusal reason, so the short actionable sentence is on the line nobody has to click for,
 * and the detail panel keeps the long consequence notes. The pair of examples above is the
 * before-and-after of exactly that change; do not read the first line as current behaviour.
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
            "\"placed\" means machines on the ME network your wireless terminal is linked to, plus"
            + " any machine standing in a chunk the server has loaded; one in an unloaded chunk"
            + " still reads as buildable";

    /**
     * The consequence of a refused stock read, for the top of the panel. #331
     *
     * <h2>THE REFUSAL WAS THE QUIETEST THING ON A PANEL THAT WAS SHOUTING THE WRONG ANSWER</h2>
     *
     * #321 got the refusal's own words onto {@link #summaryLine}, which is real progress and was
     * not enough: that line renders at the FOOTER, under the tree, in one wrapped block a reader
     * arrives at last. Meanwhile every row of the tree above it says `needed`, in red, at full
     * height. Measured on 0.11.0: Jake read a plan solved against a refused read and reported "the
     * ingredients still aren't being detected correctly" -- the mod knew exactly why it had read
     * nothing, said so, and was outvoted by its own tree.
     *
     * SO THIS IS A SECOND LINE AND NOT A LOUDER FIRST ONE. The two say different things on
     * purpose: this one names the CONSEQUENCE for the rows underneath it, and {@link #summaryLine}
     * keeps the CAUSE, the field list and the way in. Printing the cause twice on one 220-pixel
     * panel would spend a line of tree to say what is already three lines below.
     *
     * <h2>FIXED TEXT, WHICH IS THE ONLY FORM THAT CANNOT BE CUT</h2>
     *
     * 59 characters against the tree panel's 64-character line, so it fits with slack and fits
     * FOREVER -- there is no runtime part to grow. That is deliberate rather than lucky: the
     * composed alternative, `reason + " -- " + consequence`, runs to 106 characters at
     * `NOT_LINKED` and would wrap to two lines on a panel that pays for them out of the tree, or
     * be truncated, which is the failure {@link #POINTER} exists to name. The reason is already
     * carried, in full, one line-block below. `PlannerLayoutTest` pins the no-cut claim.
     *
     * <h2>SILENT FOR `NO_AE2`, AND THAT IS THE ONE EXCEPTION</h2>
     *
     * `PlannerStock.refusedReason` is where that is argued: a pack with no Applied Energistics has
     * no network to fail to read, and a red line on every plan for the rest of the game is noise
     * that gets tuned out -- taking the line that matters with it. Every other refusal is a state
     * the player is IN and can leave.
     */
    static final String STOCK_REFUSED =
            "your ME network was not read, so everything reads as needed";

    private PlanCaveats() {
    }

    /**
     * {@link #STOCK_REFUSED} when the stock read refused, and "" otherwise.
     *
     * A METHOD RATHER THAN THE CONSTANT AT THE CALL SITE, so the `NO_AE2` exception is decided
     * once. Two panels draw this now -- the tree panel and the diagram's chrome band -- and a
     * second copy of the condition is how `present.py`'s four private status dictionaries
     * happened. Empty string rather than null, matching {@link #summaryLine}, so both callers
     * reserve their height the same way.
     */
    public static String stockRefusalLine() {
        StockSnapshot.Reason refused = PlannerStock.refusedReason();
        if (refused == null || refused == StockSnapshot.Reason.NO_AE2) {
            return "";
        }
        return STOCK_REFUSED;
    }

    /**
     * What the summary line puts between the refusal reason and the field list.
     *
     * A DASH RATHER THAN A FULL STOP, because the two halves are one thought: the reason is WHY
     * the fields after it are missing. A full stop would read as two unrelated announcements, and
     * the whole defect #321 §1 describes is a player unable to connect "planned without: have" to
     * anything they could do.
     */
    private static final String REASON_JOIN = " -- ";

    /**
     * The always-visible line: WHY, which inputs are missing, and that there is more to read.
     *
     * Empty when every input is live, which is what {@link ScenarioSource#summary} already
     * answers and what the panel treats as "reserve no caveat lines".
     *
     * <h2>THE REFUSAL REASON LEADS, AND THAT IS #321 §1</h2>
     *
     * Before this, the always-visible line was `planned without: have, placed, ...` and the reason
     * -- the only sentence naming something the player can DO -- was on the detail panel behind a
     * click they had no reason to make. `ScenarioSource.Status.unavailable`'s own javadoc cites
     * exactly that difference as why it takes a string rather than a boolean, and the string was
     * the half that never arrived. So the short form now leads the line:
     *
     * <pre>
     *   before: planned without: have, placed, craftables, ... (click for what each costs you)
     *   after:  no wireless terminal in your inventory -- planned without: have, placed, ... (...)
     * </pre>
     *
     * FIRST RATHER THAN APPENDED, because a wrapped line is read from the top and the first words
     * are the ones that get read. The cost is real and is the right trade: a refused read now
     * spends a third caveat line, and `PlannerWidgets` takes that out of the tree's height. A line
     * of tree is cheaper than a player who cannot tell a refused read from an empty network.
     *
     * <h2>IT MUST FIT, AND `PlanCaveatsTest` IS WHAT SAYS SO</h2>
     *
     * `PlannerWidgets` wraps this to `MAX_CAVEAT_LINES` and TRUNCATES past it, and {@link #POINTER}
     * is the half that must never be the casualty. NO CHARACTER COUNT PREDICTS WHETHER THIS FITS:
     * the line is four parts, the field list among them changes at runtime, and `NodeRowText.wrap`
     * breaks on spaces, so three 64-column lines carry well under 192 characters -- the rejected
     * `NO_REPLY` composed to 176 and still lost the pointer. So
     * `everyRefusalReasonFitsTheCaveatCapWithThePointerIntact` composes and wraps this line for
     * EVERY reason and fails if the pointer is gone. DO NOT lengthen a reason message without
     * running it; it has already caught one that fitted the arithmetic and not the line.
     */
    public static String summaryLine() {
        String summary = ScenarioSource.summary();
        if (summary.isEmpty()) {
            return "";
        }
        String reason = PlannerStock.refusalSentence();
        String head = reason == null ? "" : reason + REASON_JOIN;
        return head + summary + " " + POINTER;
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
            out.addAll(strandedLines(widthPx));
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
        out.addAll(strandedLines(widthPx));
        return out;
    }

    /**
     * What the graph could not understand about the stock it WAS given. #322
     *
     * <h2>A SUCCESSFUL READ CAN STILL HAVE LOST SOMETHING, which is the gap this fills</h2>
     *
     * Every other line on this panel is about an input that was not read. This one is about an
     * input that WAS: `ScenarioInputs.resolve` drops any `have` key the graph has never heard of,
     * and until #322 it did so with no count, no log and no caveat, while `have` went on reporting
     * live. So the plan claimed it had consulted your stock while discarding part of it -- and
     * after a redump or a graph swap the in-game planner is precisely where a player notices
     * "it told me to make something I have", with nothing to tell that apart from #321's refusals.
     *
     * <h2>ONLY WHEN IT CAN MATTER, which is the offline reader's rule and not a new one</h2>
     *
     * Silent when nothing was stranded, and silent when no plan has been resolved at all -- the
     * two are different states and `PlannerService.strandedStock` returns null for the second on
     * purpose. `gaps.stock_coverage` (#92) is the precedent: it says its piece only when the dump
     * schema and the digest format could actually disagree.
     *
     * <h2>THE VOLUME IS IN THE SENTENCE AND IT IS THE POINT</h2>
     *
     * Key count alone is the misleading number -- closed #80 measured 11,353 digest keys churning
     * across redumps while nearly all the ITEMS sat in plain keys that cannot miss. So the line
     * carries both, and a player reading "3 stock entries (12 items)" can dismiss it while one
     * reading "3 stock entries (40,000 items)" knows the plan is wrong.
     *
     * UNNUMBERED, LIKE {@link #PLACED_SCOPE} AND FOR ITS REASON: a player counts the numbered
     * entries against `planned without: a, b, c`, and this is not a missing input.
     */
    static List<String> strandedLines(int widthPx) {
        ScenarioInputs.Stranded stranded = PlannerService.get().strandedStock();
        if (stranded == null || !stranded.any()) {
            return new ArrayList<String>();
        }
        boolean one = stranded.keys() == 1;
        String entries = one ? "1 stock entry" : stranded.keys() + " stock entries";
        // GROUPED, THROUGH THE FORMATTER THE REST OF THE PLANNER USES. A stranded volume is the
        // number that decides whether this line matters, and `40002 items` is a number a reader has
        // to count digits on -- which is `NodeRowText.grouped`'s own argument about 934,400 mB. Not
        // `NumberFormat.getInstance()`, for that method's stated reason: it would render `40.002`
        // on a German client beside a plan that renders `40,002`.
        String items = stranded.volume() == 1L
                ? "1 item" : NodeRowText.grouped(stranded.volume()) + " items";
        return new ArrayList<String>(NodeRowText.wrapRow(
                entries + " (" + items + ") did not match this graph, so " + (one ? "it was" : "they"
                + " were") + " not counted as owned -- is the graph older than your dump?", widthPx));
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
