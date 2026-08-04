package io.github.jacoblasky.recipedump.client.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.Pins;

/**
 * Every recipe that makes a node's key, for the picker to offer.
 *
 * WHY THIS IS NOT ON `PlanNode`. The plan shape carries `alternatives` as a COUNT, not a list,
 * and that is the right call -- inlining every candidate would put thousands of recipes into a
 * tree that already reaches 634 nodes. So the count comes with the plan and the list is looked
 * up on demand, once, when someone opens the picker.
 *
 * The lookup needs the graph, which is why it lives here rather than in `PlannerWidgets`: the
 * widgets stay a pure function of data and every layout assertion in this package keeps
 * running with no graph, no client and no window.
 */
public final class RecipeChoices {

    /**
     * How many candidates the picker will show.
     *
     * `plan-in-stock`'s root has 5, but the fixtures reach 172 on one node and the pack has
     * keys with more. A picker listing 172 rows is a wall, and every row past the first
     * screenful costs layout on a click. The cap is reported rather than silent -- see
     * {@link #more} -- because a truncated list of choices that does not say so is the same
     * failure as a truncated plan that does not say so.
     */
    public static final int MAX_SHOWN = 24;

    private final List<RecipeChoice> shown;
    private final int total;
    private final String why;

    private RecipeChoices(List<RecipeChoice> shown, int total, String why) {
        this.shown = Collections.unmodifiableList(shown);
        this.total = total;
        this.why = why;
    }

    /**
     * Nothing to offer, AND WHY NOT.
     *
     * "No graph is loaded yet", "this key is not in the graph" and "nothing in the pack makes
     * this" are three different situations, and only the third is a fact about the item. The
     * first two are faults the player can act on -- a missing `graph.json`, or a plan drawn
     * from a fixture against a different pack -- and an empty picker that says nothing turns
     * all three into "this feature does not work". The picker prints this verbatim.
     *
     * @param why a sentence for a player, never empty.
     */
    public static RecipeChoices none(String why) {
        return new RecipeChoices(new ArrayList<RecipeChoice>(), 0,
                                 why == null || why.isEmpty() ? "reason not given" : why);
    }

    /**
     * The recipes that make `node`'s key, the one in use first.
     *
     * IN-USE FIRST, then pinned, then graph order. The recipe the plan took is the one the
     * reader is comparing everything against, so it belongs at the top; graph order for the
     * rest because it is stable across runs and any cost-based order here would be a second
     * opinion about ranking that the solver has already formed.
     *
     * @param pins the player's pins by key, or null. Only used to mark rows.
     */
    public static RecipeChoices forNode(RecipeGraph graph, PlanNode node,
                                        Map<String, Pins.Pin> pins) {
        if (graph == null) {
            return none("no recipe graph is loaded yet");
        }
        if (node == null) {
            return none("no item selected");
        }
        int keyId = graph.keyId(node.key());
        if (keyId < 0) {
            // A key the graph does not have. Reachable for real: a plan can be rendered from
            // a fixture while a different graph is loaded, and after a redump a key can move.
            return none(node.key() + " is not in the loaded graph");
        }
        // `realProducers` AND NOT `producers`, WHICH IS A WIDER SET AND THE WRONG ONE.
        // For a FLUID key the two differ: `producers` includes container transfers -- a
        // bucket emptied to "make" the fluid -- and `realProducers` drops them. Everything
        // downstream of a click here uses the narrow set: `Solver.rank` ranks over
        // `realProducers`, `Pins.resolve` matches a fingerprint against `realProducers`, and
        // `node.alternatives` is `realProducerCount`. Offering the wide set would put rows in
        // the picker that the solver can never take, and clicking one would write a pin that
        // resolves DEAD -- "nothing here makes this way any more", about a recipe the picker
        // had just listed. It would also make the picker's total disagree with the count on
        // the menu entry that opened it.
        IntArray producers = new IntArray();
        int count = graph.realProducers(keyId, producers);
        if (count <= 0) {
            return none("nothing in the pack makes this");
        }

        Pins.Pin pin = pins == null ? null : pins.get(node.key());
        List<RecipeChoice> inUse = new ArrayList<RecipeChoice>();
        List<RecipeChoice> pinned = new ArrayList<RecipeChoice>();
        List<RecipeChoice> rest = new ArrayList<RecipeChoice>();

        for (int i = 0; i < count; i++) {
            int recipeId = producers.get(i);
            String rid = graph.recipes().rid(recipeId);
            // MATCHED ON THE RID, which is exactly what `Solver` wrote onto the node
            // (`node.recipe = store.rid(recipeId)`), so this is a real comparison rather than
            // a guess from the label or the category.
            boolean isInUse = node.recipe() != null && node.recipe().equals(rid);
            // MADE FOR EVERY CANDIDATE, not only for the pinned one, because the row has to
            // carry the pin it would write -- see `RecipeChoice`. It is also the same call
            // that answers the `isPinned` question, so the comparison below and the value a
            // click stores cannot disagree about which recipe this row is.
            //
            // BEFORE THE CAP, so it runs once per PRODUCER and not once per shown row: the
            // ordering below needs `isPinned` for all of them. The bound is the widest key
            // in the pack -- 307 producers on `minecraft:paper` -- of one blake2b over a
            // recipe's slots each, once, when the picker opens. Deferring the fingerprint to
            // the shown rows would save most of that and reintroduce the reload window
            // `RecipeChoice` documents, which is the wrong trade for a click.
            Pins.Pin made = Pins.make(graph, recipeId);
            boolean isPinned = pin != null && pin.fingerprint.equals(made.fingerprint);
            RecipeChoice choice = new RecipeChoice(recipeId, rid, made, isInUse, isPinned);
            if (isInUse) {
                inUse.add(choice);
            } else if (isPinned) {
                pinned.add(choice);
            } else {
                rest.add(choice);
            }
        }

        List<RecipeChoice> ordered = new ArrayList<RecipeChoice>(count);
        ordered.addAll(inUse);
        ordered.addAll(pinned);
        ordered.addAll(rest);
        int total = ordered.size();
        if (total > MAX_SHOWN) {
            ordered = new ArrayList<RecipeChoice>(ordered.subList(0, MAX_SHOWN));
        }
        return new RecipeChoices(ordered, total, "");
    }

    /** The rows to draw, capped at {@link #MAX_SHOWN}. */
    public List<RecipeChoice> shown() {
        return shown;
    }

    /** How many recipes make this key in total, before the cap. */
    public int total() {
        return total;
    }

    /** How many were left out, or 0. Said out loud by the picker rather than dropped. */
    public int more() {
        return Math.max(0, total - shown.size());
    }

    public boolean isEmpty() {
        return shown.isEmpty();
    }

    /** Why there is nothing to show, or "" when there is. See {@link #none}. */
    public String why() {
        return why;
    }
}
