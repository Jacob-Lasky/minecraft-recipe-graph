package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The words in one tree row, with no widget anywhere near them.
 *
 * A PORT OF `render._node_html`'s text, for the same reason {@link NodeStatus} ports the
 * palette: while both surfaces exist, a plan must read the same in the browser and in game.
 * The order of the meta parts is the browser's order, because a reader moving between the
 * two should find the same fact in the same place.
 *
 * Separated from the widget so it can be asserted as strings. A layout test can tell you a
 * row is 12 pixels tall; only this can tell you it says "any of 14" rather than "14 recipes",
 * and that distinction is the whole of #118's lesson about the two different counts.
 */
public final class NodeRowText {

    /** The separator the web UI uses between meta parts, in the one place it is spelled. */
    static final String SEPARATOR = " · ";

    /**
     * Minecraft's default font advance, in pixels, for ordinary ASCII: a 5px glyph plus 1px.
     *
     * USED TO BUDGET CHARACTERS, because the real width cannot be asked for. `FontRenderer`
     * needs a GL context, so a headless build -- which is where every layout assertion in
     * this project runs -- cannot measure a string at all. Six is the advance for the great
     * majority of the characters a key or a label contains; the narrow ones (i, l, .) are
     * 2 to 4, so the budget UNDERFILLS a little and never overflows, which is the direction
     * to be wrong in.
     */
    static final int CHAR_WIDTH = 6;

    /** What a truncated string ends with. Three dots rather than an ellipsis glyph, which
     *  Minecraft's default font does not carry. */
    static final String ELLIPSIS = "...";

    private NodeRowText() {
    }

    /**
     * Shorten `text` to fit `widthPx`, ending in {@link #ELLIPSIS} when it had to cut.
     *
     * TRUNCATION AND NOT WRAPPING, and the screenshot is what settled it. A `TextWidget`
     * given more text than its box wraps onto a second line and DRAWS OVER the row beneath --
     * it does not clip, and the layout pass reports nothing, because as far as the sizer is
     * concerned every row is still 11 pixels tall and correctly stacked. The first real
     * render of a 49-node plan was six rows of overlapping text on top of each other.
     */
    public static String fit(String text, int widthPx) {
        if (text == null) {
            return "";
        }
        int budget = widthPx / CHAR_WIDTH;
        if (budget <= 0) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        if (budget <= ELLIPSIS.length()) {
            return text.substring(0, budget);
        }
        return text.substring(0, budget - ELLIPSIS.length()) + ELLIPSIS;
    }

    /**
     * Break `text` over up to `maxLines` lines of `widthPx`, cutting the last one if it still
     * does not fit.
     *
     * WRAPPING IS A CALLER'S DECISION HERE, WHICH IS THE OPPOSITE OF {@link #fit}, and the
     * two exist together on purpose. A `TextWidget` that wraps ITSELF draws over the row
     * beneath and the sizer reports nothing, which is why `fit` is the default everywhere. A
     * caller that has laid out N stacked boxes and asked for N lines is not that case: it
     * knows how much vertical room it reserved.
     *
     * ON WORD BOUNDARIES WHERE IT CAN, because the one caller is `ScenarioSource.summary` --
     * a comma-separated list of scenario field names -- and splitting `visited_dimensions`
     * across two lines makes it read as two inputs rather than one.
     */
    public static List<String> wrap(String text, int widthPx, int maxLines) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty() || maxLines <= 0) {
            return lines;
        }
        int budget = Math.max(1, widthPx / CHAR_WIDTH);
        String rest = text;
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (rest.length() <= budget) {
                lines.add(rest);
                return lines;
            }
            if (lines.size() == maxLines - 1) {
                // The last line cuts rather than dropping the tail silently, which is `fit`'s
                // whole argument: an ellipsis says there was more, an absence does not.
                lines.add(fit(rest, widthPx));
                return lines;
            }
            int cut = rest.lastIndexOf(' ', budget);
            if (cut <= 0) {
                cut = budget;
            }
            lines.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        return lines;
    }

    /**
     * The quantity, grouped: `934,400x`.
     *
     * GROUPED WITH COMMAS AND NOT ABBREVIATED. `934400x fluid:water` is a real row from a
     * Borax plan, and 934,400 mB read as "934k" would round away the difference between two
     * plans. The browser uses `{:,}` and this matches it, deliberately not `NumberFormat`
     * with a locale -- a plan that renders `934.400` on a German client and `934,400` here
     * is two different numbers to anyone comparing them.
     */
    public static String quantity(long need) {
        StringBuilder digits = new StringBuilder(Long.toString(Math.abs(need)));
        for (int at = digits.length() - 3; at > 0; at -= 3) {
            digits.insert(at, ',');
        }
        return (need < 0 ? "-" : "") + digits + "x";
    }

    /** The item's display name. */
    public static String label(PlanNode node) {
        // A key with no label is a planner bug rather than a rendering one, but a blank row
        // hides it. The key at least says which node.
        return node.label() == null || node.label().isEmpty() ? node.key() : node.label();
    }

    /**
     * The dimmed trailing detail: pinned, stock, machine, alternatives, notes.
     *
     * Empty when there is nothing to say, which is most leaf rows.
     *
     * `pinned` FIRST, WHICH IS THE ONE DEPARTURE FROM `render.py`'S PART ORDER, and the
     * departure is what preserves the property that order was copied for. In the browser
     * `pinned` is not part of the meta run at all -- `render.py` emits it as a `badge` span,
     * set apart from the dotted list beside it -- so a reader's eye finds it whatever else
     * the row says. This port flattened the badge into the meta text and appended it last,
     * where {@link #fit} cuts it: a screenshot of the reference pack showed a pinned iron
     * ingot rendering as `Iron Ingot -- Crafting -- 172 recip...`, with the one word saying
     * the route was the PLAYER'S choice being the first thing dropped. Front of the run is
     * the closest a single truncatable line gets to "set apart", and a fact the reader can
     * see beats one in the same position as the browser's and invisible.
     *
     * Everything after it stays in `render.py`'s order; do not reshuffle the rest.
     */
    public static String meta(PlanNode node) {
        List<String> parts = new ArrayList<String>();
        if (node.pinned()) {
            parts.add("pinned");
        }
        if (node.fromStock() > 0) {
            parts.add(quantityPlain(node.fromStock()) + " from stock");
        }
        String machine = machineBit(node);
        if (machine != null) {
            parts.add(machine);
        }
        // THE INTERCHANGEABLE COUNT REPLACES THE RECIPE COUNT WHERE IT EXISTS, rather than
        // sitting beside it. #181: on `fluid:lifeessence` the row would otherwise read
        // "65 recipes, 62 interchangeable", and 65 is the false-comfort number -- it counts
        // three Blood God Altar routes priced at infinity. Showing both invites the reader to
        // believe there were 65 ways when the model could only distinguish 3.
        //
        // WORDED SO THE READER CAN ACT. "62 interchangeable" says the pick was arbitrary and
        // the picker will show them; "62 alternatives" reads as trivia. The mark is absent on
        // the overwhelming majority of nodes -- 1.3% of multi-producer keys -- which is the
        // whole reason it can be believed when it does appear.
        if (node.interchangeable() > 1) {
            parts.add("any of " + node.interchangeable() + " interchangeable");
        } else if (node.alternatives() > 1) {
            parts.add(node.alternatives() + " recipes");
        }
        // How many things the SLOT would have accepted, as opposed to how many recipes make
        // what is in it. Two different counts; the web UI shows both and so does this,
        // because a node standing in for an oredict slot otherwise looks like the only
        // option it ever had.
        if (node.altCount() > 1) {
            parts.add("any of " + node.altCount());
        }
        if (node.note() != null && !node.note().isEmpty()) {
            parts.add(node.note());
        }
        if (node.resolvedTo() != null && !node.resolvedTo().isEmpty()) {
            parts.add("-> " + node.resolvedTo());
        }
        return join(parts);
    }

    /**
     * The machine, and whether it is in the way.
     *
     * Mirrors the browser's choice of which name to show: the machine when it differs from
     * the category, otherwise the category, and nothing at all for hand crafting -- a
     * `crafting`-prefixed category is a crafting table and saying so on every second row is
     * noise. Returns null rather than "" so the caller cannot accidentally add a blank part.
     */
    private static String machineBit(PlanNode node) {
        String shown = null;
        if (node.machine() != null && !node.machine().equals(node.category())) {
            shown = node.machine();
        } else if (node.category() != null && !node.category().startsWith("crafting")) {
            shown = node.category();
        }
        if (shown == null) {
            return null;
        }
        if (NodeStatus.isRoadblock(node.machineState())) {
            return shown + " (" + NodeStatus.machineStateLabel(node.machineState()) + ")";
        }
        return shown;
    }

    /** The one-line header above the tree: `2x Hopper`, plus the truncation warning. */
    public static String heading(PlanView plan) {
        return quantity(plan.qty()) + " " + plan.targetName();
    }

    /**
     * What the panel says when the tree was cut short, or "" when it was not.
     *
     * SAID, NEVER SILENT. A truncated plan is shaped exactly like a complete one, so a reader
     * who is not told acts on a shopping list that is missing items. The count is included
     * because "truncated" alone invites the reader to guess how much is missing.
     */
    public static String truncationWarning(PlanView plan) {
        if (plan.truncated()) {
            // The budget is what was HIT; the node count is what got emitted before the
            // search stopped, and can exceed it. "cut off at 49 of 40" reads like an
            // arithmetic bug, and did in the first screenshot.
            return "cut off at the " + quantityPlain(plan.maxNodes())
                   + " node budget -- raise it for the rest";
        }
        // A SECOND WAY TO BE INCOMPLETE, and it was silent until the review noticed the
        // accessor had no reader. `exhausted` means the solver ran out of WORK budget rather
        // than out of nodes -- the tree is short for a different reason, and looks just as
        // complete. `Solver` needs a work counter at all because backtracking rewinds `nodes`,
        // so discarded work never counts against the node cap.
        if (plan.exhausted()) {
            return "search gave up early -- the plan below may be missing branches";
        }
        return "";
    }

    private static String quantityPlain(long value) {
        String withSuffix = quantity(value);
        return withSuffix.substring(0, withSuffix.length() - 1);
    }

    private static String join(List<String> parts) {
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            sb.append(SEPARATOR).append(parts.get(i));
        }
        return sb.toString();
    }
}
