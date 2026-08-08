package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Provenance;

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
     *
     * PUBLIC, because a label budget is only meaningful in CHARACTERS and the classes that have
     * to state one are not all in this package. `FlowLayout`'s node width is chosen for the name
     * it has to hold and `FlowCanvasTest` asserts that in characters; doing the division against
     * a hardcoded 6 there would be a second copy of this number, in a file whose whole job is
     * catching the first copy drifting.
     */
    public static final int CHAR_WIDTH = 6;

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
     * ON WORD BOUNDARIES WHERE IT CAN. The first caller was `ScenarioSource.summary` -- a
     * comma-separated list of scenario field names -- where splitting `visited_dimensions`
     * across two lines makes it read as two inputs rather than one. `PlanCaveats` is the
     * second and wants it more, because what it wraps is prose.
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
        return grouped(need) + "x";
    }

    /**
     * The same grouping without the trailing `x`: `934,400`.
     *
     * SHARED WITH `client.machines` RATHER THAN COPIED THERE (#254), and the reason is the
     * paragraph above this pair rather than brevity. A second comma-inserter next door is a
     * second thing that can be "simplified" into `NumberFormat.getInstance()`, which renders
     * `934.400` on a German client -- and a recipe count that reads as a different number
     * depending on the player's locale is the exact failure {@link #quantity} was written to
     * avoid. One implementation cannot drift from itself.
     *
     * A RECIPE COUNT IS NOT A QUANTITY, which is why the machines table cannot just call
     * {@link #quantity}: "1,441x" beside a category name claims the reader needs 1,441 of
     * something.
     */
    public static String grouped(long value) {
        StringBuilder digits = new StringBuilder(Long.toString(Math.abs(value)));
        for (int at = digits.length() - 3; at > 0; at -= 3) {
            digits.insert(at, ',');
        }
        return (value < 0 ? "-" : "") + digits;
    }

    /**
     * One line per row of a summary list, with the parts that tell the rows apart.
     *
     * THREE THINGS WERE MISSING FROM THIS LIST AND EACH ONE ALONE MADE IT MISLEADING (#190):
     *
     *   1. THE UNSOURCED MARK. `render._rows` draws it and the in-game list did not, so after
     *      #176 a row nothing in the graph makes read as an ordinary thing to go and fetch.
     *      `plan-fluid-chain` keeps two such rows.
     *   2. THE KEY, ON THE ROWS THAT COLLIDE. `plan-fluid-chain` holds two distinct keys both
     *      labelled "Soul Vial" and this list drew them as two identical rows, which is not a
     *      list a player can gather from.
     *   3. THE PER-LIST DECORATION -- which generator a free row came from, what kind of
     *      instruction a token is, what a transmutation costs. Without it the four lists other
     *      than the shopping list are four copies of "some quantity of something".
     *
     * ONLY THE COLLIDING ROWS PAY FOR THE KEY, and that is the whole reason this takes the
     * LIST rather than a row. A registry key on every row would push the label out of the
     * panel to serve the rows that need it, and `enderio:item_soul_vial:1#40f3a0f3892d` is not
     * what a player is looking for when the name is already unambiguous. Whether a label is
     * ambiguous is a property of the list, so only the list can decide it. Measured across
     * every fixture: ONE label collides on a shopping list, `Soul Vial` twice in
     * `plan-fluid-chain`, out of 266 summary rows. `plan-same-name`'s six items called "Iron
     * Plate" are on the TREE, not on its shopping list, which is a distinction worth keeping
     * straight because it decides which surface a fix belongs on.
     *
     * WRAPPED AND NOT CUT, WHICH IS THE OPPOSITE OF {@link #fit} AND MATCHES `wrap`'s CONTRACT.
     * Measured: the longest line this produces is 52 characters for a key-disambiguated row and
     * 73 for a free row naming its generator, against 64 characters at the panel's width. So
     * `fit` would drop the end of the very part these rows grew in order to carry -- the key
     * that tells two rows apart, or the sentence saying which generator supplies one. A caller
     * that owns a LIST of rows can spend a second row, which is exactly the case `wrap` says it
     * is for; continuation lines are indented so a two-row entry does not read as two entries.
     *
     * @param widthPx how wide the row is. The rows come back fitting it, so the caller's own
     *                `fit` has nothing left to cut.
     */
    public static List<String> entryLines(List<PlanView.EntryRow> rows, int widthPx) {
        List<String> out = new ArrayList<String>();
        if (rows == null) {
            return out;
        }
        java.util.Set<String> ambiguous = ambiguousLabels(rows);
        for (PlanView.EntryRow row : rows) {
            out.addAll(entryLine(row, ambiguous.contains(row.label()), widthPx));
        }
        return out;
    }

    /**
     * The labels that appear on more than one row, so only those rows pay for their key.
     *
     * SPLIT OUT OF {@link #entryLines} SO A CALLER CAN KEEP THE ROW BESIDE ITS TEXT. A flat
     * `List<String>` throws away which `EntryRow` produced which line, and a row can wrap over
     * two of them, so a caller that needs the row's KEY -- to draw an item icon in the column
     * beside it, which `wt/ui-item-icons` is adding -- cannot recover it by index. With this and
     * {@link #entryLine} it can loop the rows itself and never has to re-derive the ambiguity,
     * which is the one part that genuinely needs the whole list.
     */
    public static java.util.Set<String> ambiguousLabels(List<PlanView.EntryRow> rows) {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        java.util.Set<String> ambiguous = new java.util.HashSet<String>();
        if (rows == null) {
            return ambiguous;
        }
        for (PlanView.EntryRow row : rows) {
            if (!seen.add(row.label())) {
                ambiguous.add(row.label());
            }
        }
        return ambiguous;
    }

    /**
     * One row's line or lines. See {@link #entryLines} for every rule this applies.
     *
     * @param disambiguate whether this row's label collides with another's, from
     *                     {@link #ambiguousLabels}. A caller passing `false` for a colliding row
     *                     draws two rows a player cannot tell apart, which is the defect
     *                     `entryLines` exists to prevent; prefer that wrapper unless you need
     *                     the row alongside its text.
     */
    public static List<String> entryLine(PlanView.EntryRow row, boolean disambiguate,
                                         int widthPx) {
        List<String> parts = new ArrayList<String>();
        if (disambiguate) {
            parts.add(row.key());
        }
        if (row.why() != null && !row.why().isEmpty()) {
            parts.add(row.why());
        } else if (row.unsourced()) {
            // NEVER BOTH, which `render._rows` states as a rule rather than a coincidence:
            // `why` is set only on the infinite-sources list, whose rows are by definition
            // sourced. The `else` keeps that true here rather than restating it.
            parts.add(NodeStatus.UNSOURCED_BADGE);
        } else if (row.provenance() != null && !row.provenance().isEmpty()) {
            // THE COMPLEMENT OF THE BADGE ABOVE, NOT A SECOND ONE. #171/#262: the pack says
            // how you get this, so "no known source" would be a lie and silence would be a
            // regression -- `render._rows` puts the same word on the same row in the browser.
            parts.add(Provenance.badgeFor(row.provenance()));
        }
        if (row.tokenKind() != null && !row.tokenKind().isEmpty()) {
            parts.add(NodeStatus.tokenBadge(row.tokenKind()));
        }
        if (row.emc() != null) {
            // THE NUMBER AND NOT "from EMC", per `PlanEntry.emc`: a cost a player can look
            // up is a claim they can check, and a bare "from EMC" is one they must trust.
            parts.add("EMC " + quantityPlain(row.emc().longValue()));
        }
        String head = amount(row.need(), row.kind()) + " " + row.label();
        return wrapRow(parts.isEmpty() ? head : head + SEPARATOR + join(parts), widthPx);
    }

    /** What a wrapped row's continuation lines are prefixed with, so one entry reads as one. */
    static final String CONTINUATION = "  ";

    /**
     * `text` over as many rows of `widthPx` as it needs, continuations indented.
     *
     * THE BUDGET IS THE NARROWER LINE FOR ALL OF THEM, deliberately, rather than giving the
     * first row the full width and the rest less. Wrapping first and indenting afterwards
     * would push every continuation two characters past the box, and the caller's `fit` would
     * then cut them -- reintroducing the truncation this exists to avoid, in the one place
     * nobody would look for it. The first row loses two characters and nothing overflows.
     */
    static List<String> wrapRow(String text, int widthPx) {
        List<String> wrapped = wrap(text, widthPx - CONTINUATION.length() * CHAR_WIDTH,
                                    Integer.MAX_VALUE);
        List<String> out = new ArrayList<String>(wrapped.size());
        for (int i = 0; i < wrapped.size(); i++) {
            out.add(i == 0 ? wrapped.get(i) : CONTINUATION + wrapped.get(i));
        }
        return out;
    }

    /**
     * The quantity with its unit: `64x` for an item, `934,400 mB` for a fluid.
     *
     * mB ON THE NUMBER RATHER THAN IN THE NAME, matching `render._rows`, because the unit
     * belongs to the quantity. NEVER converted to buckets: recipes are authored in mB and
     * rounding a partial-bucket step would misreport it.
     */
    static String amount(long need, String kind) {
        // `Keys` AND NOT THE LITERAL: see `Keys.NON_ITEM_KINDS`, which exists so a fourth
        // kind is one line there. The wire `kind` field carries exactly that vocabulary.
        if (Keys.kindName(Keys.KIND_FLUID).equals(kind)) {
            return quantityPlain(need) + " mB";
        }
        return quantity(need);
    }

    /**
     * One line per machine the plan needs and the player does not have, naming the roadblock.
     *
     * THE FOOTER COUNTS THESE AND THIS IS WHAT THEY ARE. "3 machine(s) to build" was the whole
     * of what the panel said until #190, and a count a player cannot expand is a count they
     * cannot act on: the three machines may be one blueprint away or three tech tiers away and
     * the number reads the same either way. All four `MachineRow` accessors existed and only
     * `machinesToBuild().size()` was called.
     *
     * THE MACHINE NAME WINS OVER THE CATEGORY where they differ, which is `NodeRowText.meta`'s
     * rule and the browser's: a category like `modularmachinery.recipes.ender_stone` is the
     * internal name for a machine the player knows by a different one.
     *
     * WRAPPED, BECAUSE THESE ARE THE LONGEST LINES THE PANEL HOLDS AND BY A LONG WAY. Measured
     * across the fixtures, the worst is 124 characters -- `Recursive Processor: Chemical
     * Reactor` plus `craftable: modularmachinery:mythic_processor_chemical_reactor_controller`
     * -- which is 744 pixels and cannot be made to fit any screen Minecraft runs on, let alone
     * the 427-pixel minimum this package is sized against. Cutting it would remove the registry
     * name of the thing the player has to go and craft, which is the entire content of the row.
     *
     * @param widthPx how wide the row is. See {@link #entryLines}.
     */
    public static List<String> machineLines(List<PlanView.MachineRow> machines, int widthPx) {
        List<String> out = new ArrayList<String>();
        if (machines == null) {
            return out;
        }
        for (PlanView.MachineRow machine : machines) {
            String name = machine.machine() == null || machine.machine().isEmpty()
                    ? machine.category() : machine.machine();
            List<String> parts = new ArrayList<String>();
            parts.add(machine.stateLabel());
            if (machine.why() != null && !machine.why().isEmpty()) {
                parts.add(machine.why());
            }
            out.addAll(wrapRow(name + SEPARATOR + join(parts), widthPx));
        }
        return out;
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
        // LAST, DELIBERATELY. See `machineWhyBit`: it is the longest part and the one whose
        // full text lives elsewhere, so it is the one to lose when the row is cut.
        String machineWhy = machineWhyBit(node);
        if (machineWhy != null) {
            parts.add(machineWhy);
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
     *
     * THE REASON IS A SEPARATE PART AND NOT PACKED INTO THESE PARENTHESES; see
     * {@link #machineWhyBit}.
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

    /**
     * What stands between the player and this node's machine, or null.
     *
     * `machine_why` WAS PARSED INTO `PlanNode` AND READ BY NOTHING, main or test, until #190.
     * `render.py` puts it in the badge tooltip; the dimmed meta run is this panel's nearest
     * equivalent to a tooltip. Guarded by {@link NodeStatus#isRoadblock} because that is the
     * exact condition `solve.py` writes the field under: a machine the player HAS gets no
     * `why`, so an unguarded read would return null on most rows anyway.
     *
     * ITS OWN PART RATHER THAN INSIDE {@link #machineBit}'S PARENTHESES, and the reason is that
     * the two say overlapping things. The state is a fixed word from `NodeStatus`'s vocabulary
     * and the `why` is prose that frequently restates it -- `buildable` beside
     * `craftable: nuclearcraft:centrifuge_idle` -- so packing them together produced
     * `Centrifuge (buildable: craftable: nuclearcraft:centrifuge_idle)`, which reads as a
     * formatting fault. Kept apart, the state stays the scannable word it is meant to be.
     *
     * AND IT GOES LAST, SO {@link #fit} CUTS IT FIRST. That is the right end to lose: the
     * machine's name and its state are what a reader scans for, and the full sentence is on the
     * TODO panel's machine section via {@link #machineLines}, wrapped rather than cut. Keeping
     * it last is also what leaves the browser's part order undisturbed.
     */
    private static String machineWhyBit(PlanNode node) {
        if (!NodeStatus.isRoadblock(node.machineState())) {
            return null;
        }
        String why = node.machineWhy();
        return why == null || why.isEmpty() ? null : why;
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
        //
        // IT QUOTES THE WORK BUDGET AND NOT THE NODE CAP, which is `PlanResult.exhausted`'s own
        // instruction: the node count is far below its cap in this case, so quoting the cap at
        // this reader is simply wrong. And it says raising the NODE budget is the lever, because
        // that is the only control there is and the work budget derives from it -- a sentence
        // that named a budget the player cannot set would be worse than no sentence.
        //
        // THE NUMBERS ARE WHY `work` AND `work_budget` ARE READ AT ALL (#190). `cli.cmd_plan`
        // prints both and this said "search gave up early" with none, which asks the player to
        // trust the rule instead of checking it. Both fields were parsed and had no reader for
        // the length of this review; do not let that happen again by dropping them from here.
        //
        // THE NUMBERS ARE OPTIONAL AND THE SENTENCE IS NOT. `work_budget` absent reads as 0
        // through this reader, and a first draft of this quoted it unconditionally -- so a
        // result carrying `exhausted` without the pair rendered "search gave up after 0 of 0
        // steps", which is arithmetic nonsense and strictly worse than saying nothing precise.
        // Caught by `aPlanThatGaveUpEarlySaysSoEvenWhenItWasNotTruncated`, which builds exactly
        // that result. The solver always writes both; hand-written JSON and older fixtures need
        // not, and this class reads every field defensively for that reason.
        if (plan.exhausted()) {
            if (plan.workBudget() > 0) {
                return "search gave up after " + quantityPlain(plan.work()) + " of "
                       + quantityPlain(plan.workBudget())
                       + " steps -- raise the node budget to raise this";
            }
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
