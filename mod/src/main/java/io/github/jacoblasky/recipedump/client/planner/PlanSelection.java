package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * Which node the player last clicked, for the tree and the diagram to agree about.
 *
 * A HOLDER, matching {@link NodeActionsHolder}, and for the same reason: the thing that
 * selects is a click deep inside a widget and the things that want to know are a renderer in
 * another package (`client.flow`) and the node menu that {@link PlannerActions#openNodeMenu}
 * opens. Threading it through every widget factory would put a parameter nobody reads on
 * every signature here and in `client.flow`.
 *
 * CLIENT-SIDE AND SINGLE-THREADED. Every caller is the client thread -- a click handler or a
 * draw -- so this needs no synchronisation and would be misleading with it: a volatile field
 * would imply a cross-thread contract that does not exist and that nothing enforces.
 *
 * <h2>Two identities, and using one for both is the bug</h2>
 *
 * A selection is a KEY for drawing and a NODE for acting, and they are not interchangeable.
 *
 * The same key appears once per parent that needs it -- iron ingot under the hopper and again
 * under the chest -- and `need` DIFFERS per occurrence: 4 here, 18 there. Highlighting wants
 * the key, so every occurrence of the selected item lights up and a reader can see where else
 * it is used; that is the whole value of highlighting on a diagram. But `openNodeMenu`,
 * `addToTodo` and the recipe picker all read `node.need()`, so they want the node the player
 * ACTUALLY CLICKED.
 *
 * Collapsing the two picks a wrong quantity silently. "Add to TODO" from the diagram would add
 * 4 when the player meant 18, and 4 is a plausible number that nobody checks -- there is no
 * error, no mismatch, and no way to tell from the result that the wrong occurrence answered.
 * So {@link #isSelected} takes a key and {@link #selectedNode} hands back the node.
 */
public final class PlanSelection {

    /**
     * The key of the selected node, or "" for nothing selected.
     *
     * HELD SEPARATELY rather than read off {@link #selected}, so {@link #isSelected} is a
     * string compare against a field and not a null check plus a dereference on a path that
     * runs once per drawn node per frame.
     */
    private static String key = "";

    private static PlanNode selected;

    private PlanSelection() {
    }

    /** Select `node`, or clear the selection when it is null. */
    public static void select(PlanNode node) {
        selected = node;
        key = node == null || node.key() == null ? "" : node.key();
    }

    public static void clear() {
        select(null);
    }

    /**
     * Whether `candidate` is the selected ITEM. For drawing.
     *
     * TRUE FOR EVERY OCCURRENCE of the key, deliberately -- see the class note. A diagram
     * highlighting only the box that was clicked answers a question nobody asked; the useful
     * one is "where else does this go".
     *
     * `equals` AND NOT `==`, and the tempting optimisation is unsafe here. `PlanNode` keys come
     * out of gson in `PlanJson.readNode` and are not `String.intern()`'d -- and
     * `PlannerShotTest.everyKeyInTheTreeIsInternedAndNotJustTheRoot` does NOT mean they are:
     * "interned" there is into `RecipeGraph`'s key-id table, `graph.keyId(key) >= 0`. Reference
     * equality would therefore never match, and the symptom is highlighting that simply does
     * not happen, with no error to grep for. s1harness caught that reading.
     *
     * CONFIRMED BY MUTATION rather than left as an argument: swapping this to `==` reddens
     * `anEqualKeyFromSomewhereElseStillCountsAsSelected` AND
     * `everyOccurrenceOfTheItemHighlights`. The second one failing is the interesting half --
     * it means gson hands back a DIFFERENT String instance for each occurrence of the same key
     * within one parse, so `==` would fail even between two nodes of the same tree.
     *
     * The cost of `equals` here is settled rather than assumed: the flow canvas draws at most
     * 23 of 4,000 nodes in a frame, measured on the timing run, and the bound is geometric --
     * a 372px viewport over a 26px row pitch cannot hold more than about forty boxes whatever
     * the plan size. Forty string compares a frame is not worth an unsafe shortcut.
     */
    public static boolean isSelected(String candidate) {
        return !key.isEmpty() && key.equals(candidate);
    }

    /**
     * The node the player actually clicked, or null. For ACTING.
     *
     * Never re-derive this from {@link #selectedKey} by walking the tree for a match: the walk
     * finds the first occurrence, which is the wrong one whenever the player clicked any other,
     * and the difference is a quantity rather than a crash.
     */
    public static PlanNode selectedNode() {
        return selected;
    }

    /** The selected item's key, or "". For a caller that wants to say what is selected. */
    public static String selectedKey() {
        return key;
    }
}
