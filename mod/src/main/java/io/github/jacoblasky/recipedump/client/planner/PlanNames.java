package io.github.jacoblasky.recipedump.client.planner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * Graph key to the name a player would recognise, read off a solved plan.
 *
 * THE PANEL THIS EXISTS FOR PRINTED KEYS AT PLAYERS. The TODO list showed
 * `thaumadditions:vis_pod#0116bb2287a7` while the shopping list two rows underneath it, in the
 * same window, showed "Life Essence" -- because {@link io.github.jacoblasky.recipedump.common.PlanBook}
 * stores keys and {@link PlanView.EntryRow} carries a label. The names were already on
 * screen; nothing joined them to the rows that needed them.
 *
 * <h2>The plan is the source, and that is an argument rather than a convenience</h2>
 *
 * The obvious alternative is {@link io.github.jacoblasky.recipedump.graph.RecipeGraph#display},
 * and it would answer for every key in the pack rather than for the ones in this plan. It is
 * not used here for two reasons, in this order:
 *
 * <ol>
 * <li>EVERY KEY ON THE TODO LIST GOT THERE FROM A PLAN. "Add to TODO" in the node menu sends
 *     `node.key()` and nothing else writes the list, so the plan the player was looking at
 *     when they added a row is the plan that named it. Reaching for the graph to re-derive a
 *     name the plan is already carrying is a second source for one fact.</li>
 * <li>{@link PlannerWidgets} TAKES DATA AND NOTHING ELSE. Its header is explicit that no
 *     screen, context or client state reaches it, and that is what lets every layout
 *     assertion in this package run with no graph and no window. A widget that called
 *     `GraphService.get()` would end that; a widget handed a `PlanNames` built from its own
 *     `PlanView` argument does not.</li>
 * </ol>
 *
 * A key the plan does not mention -- a row added against a plan for a different target, then
 * kept while the player planned something else -- FALLS BACK TO THE KEY, which is
 * {@link NodeRowText#label}'s rule and for its reason: a key at least says which item, and a
 * blank row hides the fact that something is on the list at all.
 *
 * SO A TODO ROW'S APPEARANCE DEPENDS ON WHICH PLAN IS OPEN, and that is worth stating rather than
 * leaving to be discovered. `thaumadditions:vis_pod#0116bb2287a7` reads as its name while a plan
 * mentioning it is open and as the raw key while a different plan is. That is the right trade --
 * strictly better in the resolving case, identical in the other, and the same graceful degradation
 * {@link NodeRowText#label} already establishes for a node with no label -- but it is a row that
 * visibly changes for a reason a player cannot see, so a reader who meets it without warning will
 * file it as a bug. Raised by #190's author, who owns the rule this satisfies.
 *
 * IMMUTABLE AND BUILT PER PANEL. A holder cached across plans would be the third piece of
 * global mutable client state in this package, and unlike {@link NodeActionsHolder} and
 * {@link PlanSelection} it would have nothing to gain from it: the map is a few dozen entries
 * built by one walk of a tree that has already been walked to draw it.
 */
public final class PlanNames {

    private static final PlanNames NONE = new PlanNames(new HashMap<String, String>());

    private final Map<String, String> byKey;

    private PlanNames(Map<String, String> byKey) {
        this.byKey = byKey;
    }

    /**
     * Every name `plan` knows, from its tree and its shopping list.
     *
     * ALL OF THEM, because they do not carry the same keys. The summary lists hold what is still
     * outstanding, already owned, drawn from a source, transmuted or gone to fetch, so a key that
     * is resolved rather than expanded is in a list and not in the tree; and a truncated plan's
     * lists can name a key whose subtree was cut. FIRST WRITER WINS, matching
     * {@link io.github.jacoblasky.recipedump.client.StackIndex}: they agree wherever they overlap,
     * since every label came out of the same solve.
     */
    public static PlanNames of(PlanView plan) {
        if (plan == null) {
            return NONE;
        }
        Map<String, String> byKey = new HashMap<String, String>();
        collect(plan.tree(), byKey);
        // ALL FIVE SUMMARY LISTS AND NOT JUST THE SHOPPING ONE. #190 turned `shopping_list` into
        // one of five `EntryRow` lists the plan carries, and the other four name keys the tree may
        // not: `used_from_stock` is by definition what the player already has, so those keys are
        // resolved rather than expanded, and `from_emc` and `tokens_needed` are terminal.
        record(byKey, plan.shoppingList());
        record(byKey, plan.usedFromStock());
        record(byKey, plan.fromSources());
        record(byKey, plan.tokensNeeded());
        record(byKey, plan.fromEmc());
        return new PlanNames(byKey);
    }

    private static void record(Map<String, String> byKey, List<PlanView.EntryRow> rows) {
        if (rows == null) {
            return;
        }
        for (PlanView.EntryRow row : rows) {
            record(byKey, row.key(), row.label());
        }
    }

    /** Nothing named. For a caller with no plan in hand; every key answers as itself. */
    public static PlanNames none() {
        return NONE;
    }

    /**
     * Recursive for the reason `PlanJson.readNode` is: the deepest fixture is 634 nodes and
     * `solve.py` caps depth at 24, so the stack this uses is bounded by the format.
     */
    private static void collect(PlanNode node, Map<String, String> byKey) {
        if (node == null) {
            return;
        }
        record(byKey, node.key(), node.label());
        for (PlanNode child : node.children()) {
            collect(child, byKey);
        }
    }

    /**
     * NAMES ONLY, NOT KEYS DRESSED AS NAMES. A node whose label is empty is a planner bug and
     * recording its key here would make {@link #knows} answer true for it -- which would then
     * report the panel as having named something it had not, and the fallback in
     * {@link #labelFor} would never be exercised by a test that asked.
     */
    private static void record(Map<String, String> byKey, String key, String label) {
        if (key == null || key.isEmpty() || label == null || label.isEmpty()) {
            return;
        }
        if (!byKey.containsKey(key)) {
            byKey.put(key, label);
        }
    }

    /** The display name for `key`, or `key` itself when this plan never mentioned it. */
    public String labelFor(String key) {
        if (key == null) {
            return "";
        }
        String label = byKey.get(key);
        return label == null ? key : label;
    }

    /**
     * Whether {@link #labelFor} would answer with a name rather than with the key back.
     *
     * A TEST SEAM AND NOT THE DEAD-ACCESSOR SHAPE #190 IS SWEEPING, and the distinction is worth
     * writing down because the next sweep will find it. Those were PRODUCTION accessors nothing
     * drew; this is an assertion about a map that IS fully drawn, through {@link #labelFor}. It is
     * also the only way to assert that the fallback fires: `labelFor(k).equals(k)` passes trivially
     * for a key that happens to be its own label, so it cannot tell a miss from a hit.
     */
    public boolean knows(String key) {
        return key != null && byKey.containsKey(key);
    }

    /**
     * How many keys this plan named. For the guard that the subject matter is not empty.
     *
     * THE SAME TEST SEAM, for the same reason: `everyKeyInTheTreeIsNamed` iterates the plan and
     * would pass vacuously against a fixture that named nothing, so it asserts this first. `size`
     * is the anti-vacuity check, not an unread accessor. DO NOT DELETE EITHER IN A DEAD-SYMBOL
     * SWEEP without reading the assertions they hold up.
     */
    public int size() {
        return byKey.size();
    }
}
