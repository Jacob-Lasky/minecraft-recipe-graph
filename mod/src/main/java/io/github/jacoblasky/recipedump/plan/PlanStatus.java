package io.github.jacoblasky.recipedump.plan;

/**
 * The `status` a plan node can carry, matching the `STATUS_*` constants in `solve.py`.
 *
 * STRINGS AND NOT AN ENUM, and that is a deliberate cost. These values go straight into the
 * wire format that `tests/fixtures/plan/*.json` freeze, `recipegraph/present.py` renders them
 * on the Python side, and the in-game GUI will colour node rows by them (#19). An enum would
 * be nicer Java and would put a `name()` call, or worse a `toString()`, between the constant
 * and the JSON -- which is one rename away from writing `CRAFT` where the fixture says
 * `craft`, with nothing but a fixture diff to say why.
 */
public final class PlanStatus {

    /** Fully covered by inventory. */
    public static final String HAVE = "have";
    /** Some from inventory, remainder crafted. */
    public static final String PARTIAL = "partial";
    /** Crafted from sub-ingredients. */
    public static final String CRAFT = "craft";
    /** No recipe known and not in inventory, so it goes on the shopping list. */
    public static final String RAW = "raw";
    /** An infinite generator you own makes this; nothing to plan. */
    public static final String SOURCE = "source";
    /** The recipe loops back on an ancestor. */
    public static final String CYCLE = "cycle";
    /** Hit the depth, node or work cap. */
    public static final String DEPTH = "depth";
    /**
     * A pack placeholder standing in for an instruction: loot, a quest gate, a class of
     * materials, or a mechanic. Not craftable and not shoppable, so it leaves the shopping
     * list and is reported on its own. See `tokens.py`.
     */
    public static final String TOKEN = "token";
    /**
     * The ProjectE transmutation network can make this: the item has an EMC value AND
     * somebody has learned it. A TERMINATOR IN THE `source` FAMILY RATHER THAN A RECIPE,
     * because it is not a crafting step -- it is a thing you already effectively have, and
     * the plan should say so and stop. See #50.
     */
    public static final String EMC = "emc";

    /**
     * An `ore:` node that resolved to a concrete member. Not one of `solve.py`'s named
     * STATUS_ constants -- it is a bare string literal there, in `resolve_ore` -- but it
     * reaches the wire format exactly like the rest, so it belongs beside them rather than
     * hidden inside the one method that writes it.
     */
    public static final String OREDICT = "oredict";

    private PlanStatus() {
    }
}
