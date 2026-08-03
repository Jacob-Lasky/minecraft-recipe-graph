package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.Pins;

/**
 * One recipe that makes a node's key, as the picker shows it.
 *
 * A VALUE, computed once when the picker opens. The widgets never touch the graph -- that is
 * what keeps every layout assertion in this package runnable with no graph, no client and no
 * window, and it is why `RecipeChoices` is a separate class from the panel that draws them.
 *
 * IT CARRIES THE FINISHED PIN, not the ingredients for making one later. The alternative --
 * holding the recipe id and calling `Pins.make(graph, id)` when the row is clicked -- looks
 * tidier and has a window in it: a graph reload between opening the picker and clicking a row
 * renumbers every recipe, so the click would pin whatever now sits at that index. That is a
 * wrong recipe pinned under the right name, and nothing downstream can detect it -- a
 * fingerprint that matches something is indistinguishable from one that matches the right
 * thing. Fingerprinting at build time closes the window and costs one blake2b per candidate.
 */
public final class RecipeChoice {

    private final int recipeId;
    private final String rid;
    private final Pins.Pin pin;
    private final boolean inUse;
    private final boolean pinned;

    RecipeChoice(int recipeId, String rid, Pins.Pin pin, boolean inUse, boolean pinned) {
        this.recipeId = recipeId;
        this.rid = rid;
        this.pin = pin;
        this.inUse = inUse;
        this.pinned = pinned;
    }

    /** The graph's own id. Meaningless across a rebuild; do not persist it, use {@link #pin}. */
    public int recipeId() {
        return recipeId;
    }

    /** The dump's recipe id, `hei:minecraft.crafting:25760` or a jar path. Stable. */
    public String rid() {
        return rid;
    }

    /** Exactly what {@link io.github.jacoblasky.recipedump.common.PinStore} will store. */
    public Pins.Pin pin() {
        return pin;
    }

    /** `Pins.label`, so a picker row and a dead pin's note describe the recipe alike. */
    public String label() {
        return pin.label;
    }

    public String category() {
        return pin.category;
    }

    /** True for the recipe the plan actually took. */
    public boolean inUse() {
        return inUse;
    }

    /** True when a pin already names this recipe. Clicking such a row REMOVES the pin. */
    public boolean pinned() {
        return pinned;
    }

    @Override
    public String toString() {
        return rid + (inUse ? " (in use)" : "") + (pinned ? " (pinned)" : "");
    }
}
