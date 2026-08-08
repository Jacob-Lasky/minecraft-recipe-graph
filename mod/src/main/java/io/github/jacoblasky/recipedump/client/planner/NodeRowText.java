package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Provenance;
import io.github.jacoblasky.recipedump.plan.Quantities;

import java.math.BigDecimal;
import java.math.MathContext;
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

    /**
     * The separator the web UI uses between meta parts, in the one place it is spelled.
     *
     * PUBLIC FOR `client.browse` (#255), because the graph screen joins its fingerprint figures
     * the same way the planner joins its footer, and a second separator spelled next door would
     * be two conventions on one 400px panel a player sees both halves of. The reason it is one
     * place is unchanged: this glyph is the browser's, and the two front ends look wrong beside
     * each other the moment one of them picks a dash.
     */
    public static final String SEPARATOR = " · ";

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
            // NEVER BOTH, which `render._rows` states as a rule rather than a coincidence.
            //
            // THE REASON IS LIST DISJOINTNESS, NOT WHERE `why` COMES FROM, and the previous
            // wording here said `why` is set only on the infinite-sources list. That is not
            // true and it matters, because it is the sentence the next person reasons from.
            // MEASURED over every plan fixture, not reasoned:
            //
            //     rows carrying `why`         188   (9 from_sources, 179 machines_to_build)
            //     rows carrying `unsourced`     7   (all of them shopping rows)
            //     rows carrying BOTH            0
            //
            // AND THE ZERO IS EARNED, because the same walk returned 188 and 7 for the two
            // populations separately -- a search that had stopped matching would have reported
            // the same 0 while seeing nothing at all. The `else` is safe because those three
            // lists are DISJOINT, not because of what sets `why`.
            //
            // SO THE THING THAT WOULD BREAK IT IS A `why` ON A SHOPPING ROW, which is the one
            // list that can be unsourced -- and this `else` would then silently drop the
            // UNSOURCED badge, the marker saying nothing in the pack can make the item. #270
            // is about exactly that badge on a dimension-gated ore. If you add a `why` to
            // that list, split this into two `if`s and check what a row carrying both should
            // read as; do not assume the old sentence was the reason.
            //
            // THE CHAIN IS THREE WIDE NOW AND THE THIRD ARM IS UNMEASURED AGAINST THIS ONE.
            // #262 added the `provenance` branch below, and its argument is sound on today's
            // data: the pack saying how you get something makes "no known source" a lie. But
            // the numbers above are `why` against `unsourced`; NOBODY HAS COUNTED A ROW
            // CARRYING BOTH `provenance` AND `unsourced`, and if one exists it takes this arm
            // and the provenance word is dropped in silence. #270 carries that, together with
            // `NodeStatus.badge` testing `unsourced()` ahead of the status entry, which is the
            // same precedence question one layer up.
            //
            // AND NOTE HOW THAT BRANCH ARRIVED: the paragraph above says to check the
            // both-carrying case before adding an arm, it was in the right place, and it still
            // did not fire -- because the next author was working in another file and had no
            // reason to read this one. A warning only reaches someone already looking at it.
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
     * KEY -> the shortest fragment that tells it apart from the others sharing its label. #232.
     *
     * ABSENT MEANS NOTHING COLLIDES WITH THIS ROW, so {@link java.util.Map#get} returning null
     * is the whole question a caller has to ask, and there is no second lookup to forget.
     *
     * A FRAGMENT, NOT THE KEY, AND THE REASON IS MEASURED. The first version of this put the
     * whole key at the front of the meta run, and on every one of the 11 rows that already
     * carry a machine name that line ALREADY overflows: `Ender Pearl · Crafting · 5...` at 29
     * columns. `fit` keeps the head, so the key survived and the machine name -- which renders
     * on master today -- was what got dropped. Fixing a collision by deleting information the
     * row already showed is a worse row, not a better one.
     *
     * TOKENS, so the fragment MEANS something. Split on ':' and '#', elide the run the
     * colliding keys share, and take the shortest remaining run that is unique among them:
     *
     *     chisel:concrete_brown:1  against  minecraft:concrete:12     ->  chisel / minecraft
     *     enderio:item_soul_vial:1#32d8050d982c  against  ...#40f3..  ->  32d8050d982c
     *
     * The shortest LOCALLY unique suffix would be "1" against "2", which is unique and tells a
     * reader nothing. A disambiguator a player cannot interpret has not disambiguated the rows,
     * it has only made them differ. The mod that owns the item is the question a player
     * actually has when two things share a name.
     */
    public static java.util.Map<String, String> disambiguators(PlanNode root) {
        java.util.Map<String, java.util.Set<String>> keysByLabel =
                new java.util.LinkedHashMap<String, java.util.Set<String>>();
        collectKeys(root, keysByLabel);
        java.util.Map<String, String> fragments = new java.util.HashMap<String, String>();
        for (java.util.Map.Entry<String, java.util.Set<String>> entry : keysByLabel.entrySet()) {
            java.util.Set<String> keys = entry.getValue();
            if (keys.size() < 2) {
                continue;
            }
            for (String key : keys) {
                fragments.put(key, fragment(key, keys));
            }
        }
        return fragments;
    }

    private static void collectKeys(PlanNode node,
                                    java.util.Map<String, java.util.Set<String>> into) {
        if (node == null) {
            return;
        }
        String text = label(node);
        java.util.Set<String> keys = into.get(text);
        if (keys == null) {
            keys = new java.util.HashSet<String>();
            into.put(text, keys);
        }
        keys.add(node.key());
        for (PlanNode child : node.children()) {
            collectKeys(child, into);
        }
    }

    /** `key` split on the two characters a mod id uses to qualify itself. */
    private static List<String> tokens(String key) {
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ':' || c == '#') {
                out.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    /**
     * The shortest run of whole tokens that separates `key` from the rest of `siblings`.
     *
     * Leading tokens every sibling shares are dropped first: they are identical by definition,
     * so they spend the row's width saying nothing. What is left is the first place the keys
     * actually diverge, which is the only part a reader needs.
     */
    static String fragment(String key, java.util.Set<String> siblings) {
        List<String> mine = tokens(key);
        List<List<String>> others = new ArrayList<List<String>>();
        for (String sibling : siblings) {
            if (!sibling.equals(key)) {
                others.add(tokens(sibling));
            }
        }
        int shared = 0;
        while (shared < mine.size()) {
            boolean allMatch = true;
            for (List<String> other : others) {
                if (shared >= other.size() || !other.get(shared).equals(mine.get(shared))) {
                    allMatch = false;
                    break;
                }
            }
            if (!allMatch) {
                break;
            }
            shared++;
        }
        for (int end = shared + 1; end <= mine.size(); end++) {
            boolean unique = true;
            for (List<String> other : others) {
                if (other.size() >= end && other.subList(shared, end)
                        .equals(mine.subList(shared, end))) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                return join(mine.subList(shared, end), ":");
            }
        }
        return key;
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * The label, with its disambiguating fragment in brackets when it has one.
     *
     * BRACKETS BESIDE THE LABEL RATHER THAN A PART OF THE META RUN, which is what makes the
     * no-eviction rule satisfiable at all. `fit` cuts the tail, so anything placed in the meta
     * run competes with the machine name behind it; anything placed here sits AHEAD of the cut
     * and the rest of the line is unchanged. `RecipeGraph.bareName` already writes `Wool (3)`
     * and `<base> (<variant>)`, so this is the house spelling rather than a new one.
     */
    public static String labelWith(PlanNode node, String fragment) {
        return fragment == null ? label(node) : label(node) + " (" + fragment + ")";
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
        // EARLY IN THE RUN, WHICH IS THE OPPOSITE END FROM `machineWhyBit` AND FOR THE SAME
        // REASON (#252). That one goes last so `fit` cuts it first, because the machine
        // and its state are what a reader scans for and the full sentence lives on the TODO
        // panel. This one has no second home and it is a warning: a fractional yield means the
        // machine will run and produce nothing, which changes whether the plan is worth
        // starting. Cutting it first would drop the only thing on the row that says so.
        //
        // NO BROWSER ORDER TO MIRROR HERE, unlike everything else in this run. `render.py`
        // draws neither `runs` nor `per_run` on a row, which is what #190 found and #252 is
        // fixing, so the placement is argued from this panel rather than copied.
        String yield = yieldBit(node);
        if (yield != null) {
            parts.add(yield);
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

    /**
     * How much work this node is, and how much of it is wasted, or null on a leaf.
     *
     * ONE PART RATHER THAN THREE, so {@link #fit} keeps or drops the whole statement. Split
     * across three parts, a cut lands mid-sentence and leaves `1,000 runs` standing alone,
     * which is the number that looks fine and is the reason the other two exist.
     *
     * GUARDED ON `runs`, NOT ON `perRun`. A leaf has neither, and `perRun` is the one that can
     * legitimately be absent on a node that has runs: `solve.py` writes `or 1` rather than
     * emit a zero yield, so a missing `per_run` means "not recorded" and not "yields nothing".
     *
     * THE CHANCE IS ONLY SHOWN BELOW 1. `yield_chance` is written by `solve.Solver._build` as
     * `per_run / nominal` and only when the expectation falls short, so a present value at 1
     * would be a plan saying "this yields all of it", which is what every row without the mark
     * already says. See `PlanNode.yieldChance` for why it is a separate field from `perRun`.
     */
    private static String yieldBit(PlanNode node) {
        if (node.runs() <= 0) {
            return null;
        }
        // NOTHING TO SAY ON A ONE-FOR-ONE CRAFT, AND THE SCREENSHOT IS WHAT SETTLED IT (#252).
        // "1 run, 1 per run" is the default every reader already assumes, and it is not rare:
        // measured across the committed fixtures, 400 of 1,406 craft nodes are exactly this
        // case, 28.4%, and 18 of the 22 in `plan-cycle`. The first shot of this render was a
        // panel of rows all saying it, with the machine name cut off the end to make room.
        //
        // WIDTH IS THE COST, NOT TIDINESS. `fit` cuts the meta run from the right, so a phrase
        // carrying no information does not merely fail to help, it evicts `machineBit`, which
        // is what a reader scans for. No test caught this because every part was individually
        // correct and the defect only exists in aggregate, which is the #190 fluid-adjacency
        // lesson in a new place: some things are only visible in a picture.
        boolean singleRun = node.runs() == 1L;
        boolean unitYield = node.perRun() == 0.0 || node.perRun() == 1.0;
        boolean certain = !(node.yieldChance() > 0.0 && node.yieldChance() < 1.0);
        if (singleRun && unitYield && certain) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(quantityPlain(node.runs())).append(node.runs() == 1L ? " run" : " runs");
        double chance = node.yieldChance();
        boolean chanced = chance > 0.0 && chance < 1.0;
        // `per_run` GIVES UP ITS PLACE TO THE CHANCE, and this is measured rather than a taste
        // call (#252). All three parts plus a realistic label do not fit: at `PANEL_WIDTH` the
        // label column is 53 characters at depth 0 and 45 at the indent cap, `1,000 runs, 0.004
        // per run, 0.1%` is 31, and a 20-character name like `Mildly Recursive Goo` puts the
        // row at 54. The percentage is then cut at EVERY depth, which is how the first two
        // planner shots came back byte-identical: the cut landed before the part that differed.
        //
        // SO THE CHOICE IS WHICH TWO NUMBERS SURVIVE, NOT HOW TO PHRASE THREE. Dropping the
        // per-run quantity takes the row to 16 characters of meta and the percentage then
        // survives at every depth for every label in the reference plans. It is the right one
        // to drop: on a chanced recipe `per_run` is already an EXPECTATION rather than a count
        // -- `Solver` writes the expected yield there -- so it is the number least likely to be
        // read literally, and it is recoverable as `need / runs`. How often the machine pays
        // out is not recoverable from anything else on the row.
        //
        // A CERTAIN RECIPE KEEPS IT, because there is no chance competing for the width and
        // `4 per run` is then a plain fact rather than an expectation.
        if (node.perRun() > 0.0 && !chanced) {
            sb.append(", ").append(amount(node.perRun())).append(" per run");
        }
        if (chanced) {
            // A BARE PERCENTAGE, AND THE FIRST SHOT OF THIS RENDER IS WHY (#252). The phrase
            // was `yields 0.1% of the time`, 23 characters carrying 4 of content, and `fit`
            // cuts the meta run from the right, so on the shot the cut landed inside the run
            // count and the yield was gone. Trading "the number is absent" for "the number is
            // present and unreadable" is not a fix, and the second is worse because it looks
            // like one. The abbreviation alone was NOT enough; see the width note above for
            // the measurement and for why `per_run` gives up its place as well.
            //
            // UNAMBIGUOUS BECAUSE OF WHAT IT SITS NEXT TO: it always follows a run count, and
            // a proportion beside a count of runs has only one reading. DO NOT restore the
            // prose, and DO NOT restore `per_run` beside it, without re-running
            // `theYieldSurvivesTheCutUpToATwentySixCharacterLabelAndNotBeyond`, which measures
            // both against real label lengths rather than a short synthetic one.
            //
            // THIS DOES NOT FIT ON MOST ROWS, AND THE NUMBER IS HERE RATHER THAN FLATTERING.
            // The label column is 37 characters at depth 0 and 29 at the indent cap -- the
            // badge takes 93px of every full-width tree row -- so after a 3-character separator
            // this 16-character meta leaves 18 characters of label at the top and 10 at the
            // bottom. Measured over the committed fixtures, the yield is cut from 574 of 769
            // rows in `plan-fluid-chain` (74.6%) and 35 of 52 in `plan-truncated` (67.3%).
            //
            // IT IS STILL THE BEST FORM AVAILABLE, which is the argument for shipping it. The
            // prose it replaced is 23 characters against this 4, so it was lost on strictly
            // more rows, and before #252 the number was drawn on NONE. Buying the remainder
            // means evicting the badge or the machine, which is the trade #232 is under a hard
            // no-eviction constraint against. Anyone shortening this further should move these
            // percentages and update them here and in the test.
            sb.append(", ").append(percent(chance));
        }
        return sb.toString();
    }

    /**
     * A quantity that is usually whole and is sometimes not.
     *
     * THE WHOLE CASE GOES THROUGH {@link #quantity}'S COMMA GROUPING, so a per-run yield and
     * the `need` beside it on the same row are formatted by one rule. A plan rendering
     * `60,466,176x` above `60466176 per run` is one panel measuring the same thing two ways,
     * which is the defect a #190 screenshot caught on fluids and is invisible in a diff.
     */
    private static String amount(double value) {
        // `Quantities.isWhole` RATHER THAN A LOCAL COMPARISON, so the row and the wire cannot
        // drift about which values are whole.
        //
        // THE EMITTER'S RULE IS STRICTLY NARROWER THAN THIS PREDICATE, and the difference is
        // not a rounding detail. It writes an integer only when the value is whole AND the
        // node carries no `yield_chance`, because a `per_run` that lands on a whole number by
        // arithmetic accident, two slots of four at 0.5, is still an EXPECTATION and must not
        // masquerade as a guaranteed count. `solve.py` refuses `float(total).is_integer()` for
        // exactly that reason. Here it is only ever a display choice, so the narrower clause
        // does not apply: an expected four and a certain four are drawn the same because a
        // reader gets the certainty from the `yields ...% of the time` part beside it.
        if (Quantities.isWhole(value)) {
            return quantityPlain((long) value);
        }
        return significant(value);
    }

    /**
     * A fraction as a percentage, with the precision the fraction actually carries.
     *
     * THREE SIGNIFICANT FIGURES RATHER THAN A FIXED DECIMAL COUNT, because this field spans
     * three orders of magnitude: the pack's output chances run from 0.99 down to 0.001, so
     * `%.1f%%` renders the bottom of that range as `0.1%` and the 0.001 case as `0.0%`, which
     * reads as "never" for a route that does work. `toPlainString` rather than `toString` so a
     * small value cannot come out in scientific notation.
     */
    private static String percent(double fraction) {
        return significant(fraction * 100.0) + "%";
    }

    /**
     * `value` to three significant figures, with no trailing zeros and never in exponent form.
     *
     * ONE COPY, SHARED BY {@link #amount} AND {@link #percent}, which is not tidiness: they
     * format two numbers a reader compares on the same row, `0.004 per run` beside
     * `yields 0.1% of the time`. Two copies of the rounding rule would let one of them drift to
     * a different precision and make the pair look inconsistent rather than merely rounded.
     *
     * `toPlainString` RATHER THAN `toString`, because `BigDecimal.toString` switches to
     * scientific notation for small values and `1E-3` on a crafting row is not a quantity a
     * player can act on.
     */
    private static String significant(double value) {
        return BigDecimal.valueOf(value)
                .round(new MathContext(3))
                .stripTrailingZeros()
                .toPlainString();
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
