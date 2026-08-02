package io.github.jacoblasky.recipedump.plan;

/**
 * What is known about one JEI category's machine: the verdict, and the evidence for it.
 *
 * FOUR STATES, NOT TWO, and collapsing any pair of them produces wrong plans. "Don't own
 * it", "can't use it" and "couldn't tell" are three different answers:
 *
 * <ul>
 * <li>{@link #HAVE} -- placed in the world, or its item is in stock</li>
 * <li>{@link #BUILDABLE} -- not present, but the machine item itself is craftable</li>
 * <li>{@link #UNKNOWN} -- the category's machine could not be identified at all</li>
 * <li>{@link #UNAVAILABLE} -- identified, and there is no route to it, or disabled by hand</li>
 * </ul>
 *
 * A plan may legitimately route through a `buildable` machine; it just has to TELL you to
 * build it first. That is the "I only have a crafting table, so I will make a furnace" case.
 *
 * WHY `unknown` EXISTS AS ITS OWN STATE. Folding it into `unavailable` was catastrophic: on
 * the reference pack 360 of 521 categories -- 40% of the graph -- could not be name-matched
 * to a machine item, and every one of them was priced as unusable. A JEI title is often the
 * recipe TYPE rather than the machine ("Casting" is done in a Casting Table), so title
 * matching will always miss some. An unidentified machine must cost more than one the player
 * can demonstrably build and far less than one proven out of reach.
 *
 * THE CONSTANT ORDER IS LOAD-BEARING TWICE. It indexes {@code Cost.MACHINE_COST}, and it is
 * the ranking of how DIRECT the evidence is. Renumbering these silently reprices the pack.
 */
public final class MachineInfo {

    public static final int HAVE = 0;
    public static final int BUILDABLE = 1;
    public static final int UNKNOWN = 2;
    public static final int UNAVAILABLE = 3;

    /** Indexed by the state constants, so a state always has exactly one spelling. */
    private static final String[] STATE_NAMES = {"have", "buildable", "unknown", "unavailable"};

    public static final int STATE_COUNT = 4;

    public static String stateName(int state) {
        return STATE_NAMES[state];
    }

    /** The constant for a state name, or -1. Used when reading a hand-written override. */
    public static int stateOf(String name) {
        for (int state = 0; state < STATE_NAMES.length; state++) {
            if (STATE_NAMES[state].equals(name)) {
                return state;
            }
        }
        return -1;
    }

    private static final int[] NO_CANDIDATES = new int[0];
    private static final String[] NO_EVIDENCE = new String[0];

    private final int categoryId;
    private final int state;
    private final String why;
    private final String title;
    private final String mod;
    private final int recipeCount;
    private final int[] candidates;
    private final int[] candidateStates;
    private final String[] candidateWhy;
    private final boolean manual;
    private final boolean fromCatalyst;

    MachineInfo(int categoryId, int state, String why, String title, String mod,
                int recipeCount, int[] candidates, int[] candidateStates,
                String[] candidateWhy, boolean manual, boolean fromCatalyst) {
        this.categoryId = categoryId;
        this.state = state;
        this.why = why;
        this.title = title;
        this.mod = mod;
        this.recipeCount = recipeCount;
        this.candidates = candidates == null ? NO_CANDIDATES : candidates;
        this.candidateStates = candidateStates == null ? NO_CANDIDATES : candidateStates;
        this.candidateWhy = candidateWhy == null ? NO_EVIDENCE : candidateWhy;
        this.manual = manual;
        this.fromCatalyst = fromCatalyst;
    }

    public int categoryId() {
        return categoryId;
    }

    public int state() {
        return state;
    }

    /** The evidence sentence, e.g. `placed: minecraft:furnace`. Never null. */
    public String why() {
        return why;
    }

    /** The cleaned JEI category title, or null when the dump carried none. */
    public String title() {
        return title;
    }

    /**
     * The mod's DISPLAY name, for grouping.
     *
     * DO NOT feed this to a registry-modid comparison. "Industrial Foregoing" cannot match
     * `industrialforegoing:plant_gatherer`, and swapping the two would break machine
     * identification, which is currently correct.
     */
    public String mod() {
        return mod;
    }

    public int recipeCount() {
        return recipeCount;
    }

    /**
     * Machine item key ids that could BE this category's machine, most specific first.
     *
     * EVERY CANDIDATE IS JUDGED, not just the winner. "Smelting is done in more than just the
     * controller" is true of a lot of categories, and reporting one verdict hides the three
     * other blocks that would also do (#27).
     */
    public int[] candidates() {
        return candidates;
    }

    /** Parallel to {@link #candidates}. */
    public int[] candidateStates() {
        return candidateStates;
    }

    /** Parallel to {@link #candidates}. */
    public String[] candidateWhy() {
        return candidateWhy;
    }

    /** True when a human set this state by hand, which outranks every automatic verdict. */
    public boolean manual() {
        return manual;
    }

    /** True when the candidates came from JEI's own "made in" list rather than name matching. */
    public boolean fromCatalyst() {
        return fromCatalyst;
    }
}
