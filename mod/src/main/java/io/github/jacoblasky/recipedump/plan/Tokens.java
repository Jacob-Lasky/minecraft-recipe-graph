package io.github.jacoblasky.recipedump.plan;

/**
 * The kinds of PLACEHOLDER a pack uses to say "this comes from somewhere the graph cannot
 * see". Ports the kind vocabulary of `recipegraph/tokens.py`.
 *
 * A token is a ContentTweaker item standing in for something that is not a recipe: a boss
 * drop, a locked quest chapter, a hint that any member of a class will do, or a note that
 * the work happens in a machine. The graph has no edge for any of those, so without a
 * placeholder the chain simply dead-ends.
 *
 * WHAT EACH KIND COSTS lives in {@code Cost.TOKEN_COST}, keyed by these constants. Keyed by
 * KIND rather than by token, which is the honest granularity available: a per-token number
 * would be more truthful -- Chapter 1 and a Sedna trip are not the same afternoon -- and it
 * needs a curated figure per id that nobody has measured.
 */
public final class Tokens {

    /** Something you get by killing a thing: a boss drop, a rare mob drop. */
    public static final int LOOT = 0;
    /** Something behind progression: a locked quest chapter, an unopened stage. */
    public static final int GATE = 1;
    /** "Any member of this class will do" -- stands in for one ordinary material. */
    public static final int HINT = 2;
    /** "The work happens in a machine" -- the machine is already charged for elsewhere. */
    public static final int METHOD = 3;

    public static final int KIND_COUNT = 4;

    private static final String[] KIND_NAMES = {"loot", "gate", "hint", "method"};

    private Tokens() {
    }

    public static String kindName(int kind) {
        return KIND_NAMES[kind];
    }

    /** The constant for a kind name, or -1. Used when reading the user's `tokens.json`. */
    public static int kindOf(String name) {
        for (int kind = 0; kind < KIND_NAMES.length; kind++) {
            if (KIND_NAMES[kind].equals(name)) {
                return kind;
            }
        }
        return -1;
    }
}
