package io.github.jacoblasky.recipedump.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a fluid is actually CALLED, recovered from the containers it is bottled in.
 *
 * WHY THIS EXISTS. The dump records a fluid ingredient as its registry name and nothing else
 * -- `{"f":"nethengeic_fluid","a":1000}` -- and not one of the pack's 261,095 display names
 * is a `fluid:` key. Prettifying the registry name is fine right up until the pack renames a
 * fluid in lang, and MeatballCraft reuses another mod's fluid and renames it constantly: the
 * registry name is then the PRE-RENAME identity, a different substance as far as anyone
 * reading the screen is concerned. Measured on the reference graph, 789 of 1,198 fluid
 * labels were wrong that way. `fluid:nethengeic_fluid` is "Strong Mythic Essence".
 *
 * The renamed ones are the damaging class because the real name is UNREACHABLE BY SEARCH.
 * Reported as "strong mythic essence doesn't actually exist as a fluid, I just see the cell
 * or bucket versions". See #103.
 *
 * The names were on disk the whole time, one indirection away: a filled container is an
 * ITEM, items.csv names it, and Forestry names a can of anything "&lt;the fluid&gt; Can". So
 * a recipe pairing one container with one fluid states the fluid's real name.
 */
public final class FluidNames {

    /** A graph with no fluid evidence at all -- and the re-entrancy seed. See `fluidNames()`. */
    public static final FluidNames EMPTY = new FluidNames(new int[0], new int[0],
            StringTable.builder(0, 0, true, false).build());

    /**
     * Base key -&gt; the word its label ends in. A CURATED LIST, NOT A SUFFIX GUESS.
     *
     * The guess is actively wrong here and it was measured. Accepting any
     * "&lt;something&gt; Cell" admits `plustic:battery_cell`, whose "Manyullyn Battery Cell"
     * outvoted the truth 5 to 2 and renamed `fluid:manyullyn` to "Manyullyn Battery"; the
     * same pass renamed `fluid:stone` to "Seared Stone" and `fluid:copper` to "Copper
     * Battery". Those items are named AFTER a fluid without BEING a container for it, and
     * their labels are perfectly well formed, so no amount of vote-counting separates them.
     *
     * Only bases that actually vote on the reference pack are listed. An id nothing uses is
     * how a curated list starts drifting from the pack it describes.
     */
    private static final String[][] CONTAINERS = {
        {"forestry:can:1", "Can"},              // 1,200 votes -- near-total coverage alone
        {"forestry:refractory:1", "Capsule"},   // 1,200
        {"forestry:capsule:1", "Capsule"},      // 725
        {"techreborn:dynamiccell", "Cell"},     // 29
        {"forge:bucketfilled", "Bucket"},       // 20
        {"openblocks:tank", "Tank"},            // 8
    };

    /** Fluid key ids, ascending, so {@link #nameOf} can binary-search. */
    private final int[] fluidKeyId;
    private final int[] nameStringId;
    private final StringTable names;

    private FluidNames(int[] fluidKeyId, int[] nameStringId, StringTable names) {
        this.fluidKeyId = fluidKeyId;
        this.nameStringId = nameStringId;
        this.names = names;
    }

    public int size() {
        return fluidKeyId.length;
    }

    /** The derived name for a fluid key, or null when no container ever paired with it. */
    public String nameOf(int keyId) {
        int at = Arrays.binarySearch(fluidKeyId, keyId);
        return at < 0 ? null : names.get(nameStringId[at]);
    }

    public long retainedBytes() {
        return Sizes.object(3 * Sizes.REFERENCE) + Sizes.bytes(fluidKeyId)
                + Sizes.bytes(nameStringId) + names.retainedBytes();
    }

    /**
     * `{fluid: display name}` for every fluid the graph's recipes bottle.
     *
     * Fluids with no pairing are simply ABSENT rather than guessed at, so the caller keeps
     * its own fallback. There are none on the reference pack -- 1,198 of 1,198 are named,
     * none of them on a single vote -- but a smaller pack has every right to hold a fluid no
     * container ever touches.
     */
    public static FluidNames derive(RecipeGraph graph) {
        Map<Integer, Map<String, int[]>> votes = tally(graph);
        if (votes.isEmpty()) {
            return EMPTY;
        }
        int[] fluids = new int[votes.size()];
        int at = 0;
        for (Integer fluid : votes.keySet()) {
            fluids[at++] = fluid;
        }
        Arrays.sort(fluids);
        StringTable.Builder names = StringTable.builder(fluids.length, fluids.length * 24,
                true, false);
        int[] nameIds = new int[fluids.length];
        for (int i = 0; i < fluids.length; i++) {
            nameIds[i] = names.add(decide(votes.get(fluids[i])));
        }
        return new FluidNames(fluids, nameIds, names.build());
    }

    /**
     * The raw evidence, before it is decided: `{fluid: {name: votes}}`.
     *
     * Kept as its own step rather than folded into {@link #derive} so the evidence stays
     * inspectable. A one-vote name and a four-vote unanimous one are very different claims
     * and the verdict alone cannot tell them apart.
     */
    static Map<Integer, Map<String, int[]>> tally(RecipeGraph graph) {
        RecipeStore recipes = graph.recipes();
        int fluidRole = graph.roleId("fluid");
        Map<Integer, Map<String, int[]>> votes = new HashMap<Integer, Map<String, int[]>>();
        List<long[]> pairings = new ArrayList<long[]>();
        for (int r = 0; r < recipes.count(); r++) {
            pairings.clear();
            collectPairings(graph, recipes, fluidRole, r, pairings);
            for (long[] pair : pairings) {
                int fluid = (int) pair[0];
                int item = (int) pair[1];
                String held = containerHolds(graph, item);
                if (held == null) {
                    continue;
                }
                Map<String, int[]> counter = votes.get(fluid);
                if (counter == null) {
                    counter = new HashMap<String, int[]>();
                    votes.put(fluid, counter);
                }
                int[] count = counter.get(held);
                if (count == null) {
                    counter.put(held, new int[] {1});
                } else {
                    count[0]++;
                }
            }
        }
        return votes;
    }

    /**
     * The `(fluid, item)` pairs this recipe states are the same substance.
     *
     * ONE fluid and UNAMBIGUOUS item slots, in both directions, and every clause of that is
     * load-bearing. The pack's generic "Fluid Transposer - Empty" entries list 1,198 filled
     * containers in a SINGLE slot against an output of water; without the single-alternative
     * test every fluid in the game would cast a vote for "Water".
     */
    private static void collectPairings(RecipeGraph graph, RecipeStore recipes, int fluidRole,
                                        int recipe, List<long[]> out) {
        int fluidOutputs = 0;
        int fluidOutputKey = -1;
        for (int p = recipes.outputStart(recipe); p < recipes.outputEnd(recipe); p++) {
            if (graph.isFluid(recipes.outputKeyAt(p))) {
                fluidOutputs++;
                fluidOutputKey = recipes.outputKeyAt(p);
            }
        }
        int fluidSlots = 0;
        int fluidSlot = -1;
        for (int s = recipes.slotStart(recipe); s < recipes.slotEnd(recipe); s++) {
            if (recipes.slotRoleId(s) == fluidRole) {
                fluidSlots++;
                fluidSlot = s;
            }
        }
        if (fluidOutputs == 1 && fluidSlots == 0) {
            // Emptying: one container in, its contents out.
            for (int s = recipes.slotStart(recipe); s < recipes.slotEnd(recipe); s++) {
                if (recipes.altEnd(s) - recipes.altStart(s) == 1) {
                    out.add(new long[] {fluidOutputKey, recipes.altKeyAt(recipes.altStart(s))});
                }
            }
            return;
        }
        if (fluidSlots == 1 && fluidOutputs == 0
                && recipes.altEnd(fluidSlot) - recipes.altStart(fluidSlot) == 1) {
            // Filling: one fluid in, the filled container out.
            int fluid = recipes.altKeyAt(recipes.altStart(fluidSlot));
            for (int p = recipes.outputStart(recipe); p < recipes.outputEnd(recipe); p++) {
                if (!graph.isFluid(recipes.outputKeyAt(p))) {
                    out.add(new long[] {fluid, recipes.outputKeyAt(p)});
                }
            }
        }
    }

    /** The fluid name `item`'s label advertises, or null if it is not a curated container. */
    private static String containerHolds(RecipeGraph graph, int item) {
        String base = Keys.baseKey(graph.key(item));
        String word = null;
        for (String[] entry : CONTAINERS) {
            if (entry[0].equals(base)) {
                word = entry[1];
                break;
            }
        }
        if (word == null) {
            return null;
        }
        return heldBy(graph.bareName(item), word);
    }

    /**
     * `"Molten Sednanite Cell"` with word `"Cell"` -&gt; `"Molten Sednanite"`.
     *
     * Splits at the LAST occurrence of the word, so a fluid whose own name contains a
     * container word ("Cell Culture Cell") still reads. Written out rather than done with a
     * regex because the anchored non-greedy pattern this replaces is subtle enough that a
     * reader has to simulate it, and this runs once per candidate pairing across 117,681
     * recipes.
     */
    static String heldBy(String label, String word) {
        if (label == null || !label.endsWith(word)) {
            return null;
        }
        int end = label.length() - word.length();
        if (end == 0 || !Character.isWhitespace(label.charAt(end - 1))) {
            return null;
        }
        while (end > 0 && Character.isWhitespace(label.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        return label.substring(0, end).trim();
    }

    /**
     * The winning name from one fluid's votes: most votes, then alphabetical.
     *
     * ALPHABETICAL IS A REAL TIE-BREAK, not a formality. Exactly one fluid ties on the
     * reference pack -- `fluid:eternal_dragon_fire`, "Eternal Dragon Fire" and "Niddhog
     * Dragonfire" at four votes each -- and the two names have nothing in common, so the
     * choice has to be deterministic or the label flickers between graph loads.
     */
    static String decide(Map<String, int[]> counter) {
        String best = null;
        int bestVotes = -1;
        for (Map.Entry<String, int[]> entry : counter.entrySet()) {
            int count = entry.getValue()[0];
            if (count > bestVotes
                    || (count == bestVotes && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestVotes = count;
            }
        }
        return best;
    }
}
