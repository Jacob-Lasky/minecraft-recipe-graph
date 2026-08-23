package io.github.jacoblasky.recipedump.client.browse;

import io.github.jacoblasky.recipedump.common.ScenarioSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of a screen's own scenario inputs went unread, in the reader's terms.
 *
 * EXTRACTED FROM `MachineCaveats` WHEN THE SECOND SCREEN NEEDED IT (#255), and the thing worth
 * keeping is the argument, not the loop. Both directions of getting this wrong are real:
 *
 *   - NAMING TOO MANY is not the safe side it looks like. `PlanCaveats` names every input a
 *     PLAN consumes, which is seven; a machine verdict uses four and the free-source list uses
 *     two. A reader who checks a named input, finds it irrelevant to the screen they are on,
 *     and concludes the warning is boilerplate will skip the entries that are real.
 *   - NAMING TOO FEW hides the tool's blind spot on the one screen it is about.
 *     `ScenarioSource.PLACED`'s own note is already a sentence about both callers: with no
 *     reader installed, "no machine counts as owned and every one reads as buildable".
 *
 * WHAT THIS DELIBERATELY DOES NOT SAY, since #320 made `placed` live. A source that WAS read
 * contributes nothing here, and `placed` is now read from the player's linked ME network -- a
 * narrower thing than the field name suggests. The SCOPE of a successful read is not a caveat
 * about an unread input and does not belong in this count: a line that were never empty would
 * steal a row from every screen forever, which is the opposite of the reserve-no-line contract
 * every caller depends on. `client.planner.PlanCaveats.PLACED_SCOPE` states it on the one panel
 * that sizes to its content.
 *
 * SO EACH SCREEN DECLARES ITS OWN INPUTS AND THIS COUNTS THEM. The declaration is the part that
 * has to be right and the part a test can pin; the counting is four lines that were about to
 * exist twice.
 */
public final class InputCaveats {

    private InputCaveats() {
    }

    /**
     * The unread inputs among `feeds`, by field name, in declaration order.
     *
     * EMPTY WHEN EVERY ONE IS LIVE, which every caller treats as "reserve no caveat line". A
     * source that is live because there is genuinely nothing to read -- a setting with no UI to
     * change it -- reports live and is correctly absent here, so the line is about gaps rather
     * than about emptiness.
     */
    public static List<String> missing(ScenarioSource[] feeds) {
        List<String> out = new ArrayList<String>();
        for (ScenarioSource source : feeds) {
            if (!source.live()) {
                out.add(source.field());
            }
        }
        return out;
    }

    /**
     * `<verb> without: have, placed`, or "" when every input was read.
     *
     * THE VERB IS THE CALLER'S because the sentence has to be about what the screen CLAIMS.
     * "verdicts computed without" and "list built without" are different admissions, and a
     * shared wording would make one of the two screens say something slightly false about its
     * own contents.
     *
     * IT NAMES THE FIELDS RATHER THAN COUNTING THEM. A count says how much is missing; a player
     * can only act on which, and `have` and `placed` are two different things to go and wire up.
     */
    public static String summaryLine(String verb, ScenarioSource[] feeds) {
        List<String> missing = missing(feeds);
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(verb).append(" without: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(missing.get(i));
        }
        return out.toString();
    }
}
