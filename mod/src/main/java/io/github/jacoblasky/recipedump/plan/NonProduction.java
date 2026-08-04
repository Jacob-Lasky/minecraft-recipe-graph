package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.Csr;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.graph.RecipeStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which recipes are JEI DOCUMENTATION rather than routes. Ports `recipegraph/notproduction.py`.
 *
 * Two reported symptoms, one shape of lie. Planning a vanilla Chest went
 * {@code Chest -> Chest Cart -> Scrap Box -> Matter Reprocessor} and asked for four machines
 * and hundreds of bee princesses, when the answer is eight planks: `TechReborn.Scrapbox` is a
 * random loot table, published by JEI as one entry per possible outcome. Planning a Mithrillium
 * Ingot asked for an Item Router, a Distributor Module and a Bee Sample, none of which are used
 * to make Mithrillium: the pack writes a JEI card explaining how to AUTOMATE something as
 * {@code recipes.addShapeless(<output>, [<marker>, <rig parts...>])}, and seven of them share
 * one byte-identical input list across seven different outputs. See #211 and #169.
 *
 * A BITSET OVER RECIPE IDS, COMPUTED PER SOLVE, NOT A FLAG ON THE GRAPH, and that is
 * {@link Unsourced}'s decision made again for the same reason one class over: {@code
 * GraphService} hands one graph to concurrent off-thread solves, so a per-recipe field written
 * during a solve would be a data race. Python marks the recipe objects and memoises on the
 * graph because it has one graph per process.
 *
 * IT IS ALSO NOT IN THE GRAPH FILE, which is python's decision rather than a porting shortcut.
 * The rule reads the resolved token map, which is per-world user config, so a value baked at
 * build time would keep demoting a marker's recipes after the user disabled that marker, would
 * be a schema bump on 124,467 records, and would do nothing at all until the pack was
 * re-dumped. Read `notproduction.py`'s header before persisting it.
 */
final class NonProduction {

    /**
     * The ground a recipe was demoted on. Kept as separate bits, not one flag, because the two
     * are counted and reported separately: a ground whose count drifts on the next redump is a
     * ground whose evidence has stopped matching the pack.
     */
    static final int LOOT_TABLE = 0;
    static final int ANNOTATION = 1;
    static final int GROUNDS = 2;

    /**
     * JEI category uids that publish a random loot table. Mirrors
     * {@code tokens.LOOT_TABLE_CATEGORIES}, and the two must not drift: python's copy is
     * hashed into the cost cache fingerprint, so a name added there and not here makes the
     * in-game planner price a route the served plan refuses.
     *
     * Only the first is present in a built graph, with 343 entries measured. The other two are
     * removed earlier by the substring patterns in `index.NON_RECIPE_CATEGORY_PATTERNS`, so no
     * graph on disk carries either name; they are declared because they are the same claim about
     * the same pack. See the python list, which carries the provenance of every figure.
     */
    private static final Set<String> LOOT_TABLE_CATEGORIES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "TechReborn.Scrapbox", "intestines_loot_table", "aoa_extraction_loot")));

    private NonProduction() {
    }

    /** The counts {@link #recipes} withheld and demoted, for reporting. Never null. */
    static final class Counts {
        final int[] demoted = new int[GROUNDS];
        final int[] withheld = new int[GROUNDS];
    }

    /**
     * The recipes no plan may route through, as a bitset over recipe ids.
     *
     * `counts` may be null when the caller does not report. See {@link #markers} for the
     * annotation rule and the comment on the guard below for the safety property.
     */
    static long[] recipes(RecipeGraph g, Map<Integer, Integer> tokenKinds, Counts counts) {
        RecipeStore store = g.recipes();
        int[] ground = new int[store.count()];
        Arrays.fill(ground, -1);
        Set<Integer> markerKeys = markers(g, tokenKinds);
        boolean[] lootCategory = new boolean[g.categoryCount()];
        for (int category = 0; category < lootCategory.length; category++) {
            lootCategory[category] = LOOT_TABLE_CATEGORIES.contains(g.categoryName(category));
        }
        for (int recipe = 0; recipe < store.count(); recipe++) {
            if (lootCategory[store.categoryId(recipe)]) {
                ground[recipe] = LOOT_TABLE;
            } else if (!markerKeys.isEmpty() && carriesMarker(store, recipe, markerKeys)) {
                ground[recipe] = ANNOTATION;
            }
        }
        // THE ALTERNATIVE-PRODUCER GUARD, which is the safety property of the whole class.
        // Demote a recipe only when EVERY output of it has some OTHER producer that is not
        // itself a candidate, so this can never remove the last route to a key. Without it,
        // withholding a sole producer sends the output back to `Cost` seeding it as a raw leaf
        // -- as reported, `contenttweaker:imp_skin` collapses 248.35 to 1.0, level with dirt,
        // and the plan stops lying about HOW and starts lying about HOW MUCH.
        //
        // "NOT ITSELF A CANDIDATE" rather than "not itself demoted", so the answer cannot
        // depend on the order recipes are visited: two cards covering each other's outputs
        // would each see the other as a real alternative and both would demote.
        long[] out = Bits.ofSize(store.count());
        Csr byOutput = g.byOutput();
        for (int recipe = 0; recipe < store.count(); recipe++) {
            if (ground[recipe] < 0) {
                continue;
            }
            boolean covered = true;
            for (int p = store.outputStart(recipe); p < store.outputEnd(recipe) && covered;
                    p++) {
                covered = hasUndemotedProducer(g, byOutput, store.outputKeyAt(p), recipe,
                        ground);
            }
            if (covered) {
                Bits.set(out, recipe);
            }
            if (counts != null) {
                int[] tally = covered ? counts.demoted : counts.withheld;
                tally[ground[recipe]]++;
            }
        }
        return out;
    }

    /**
     * Whether some producer of `keyId` other than `self` is not a candidate for demotion.
     *
     * WIDENED THE SAME WAY {@code RecipeGraph.producers} WIDENS, wildcard sibling included.
     * A wildcard-meta producer is a real alternative route, and python asks
     * `graph.producers(key)`, which widens. Reading only the exact row would find no
     * alternative for a concrete meta and withhold a demotion python performs.
     */
    private static boolean hasUndemotedProducer(RecipeGraph g, Csr byOutput, int keyId,
                                                int self, int[] ground) {
        if (rowHasUndemoted(byOutput, keyId, self, ground)) {
            return true;
        }
        int wild = g.wildcardSibling(keyId);
        return wild >= 0 && rowHasUndemoted(byOutput, wild, self, ground);
    }

    private static boolean rowHasUndemoted(Csr byOutput, int keyId, int self, int[] ground) {
        for (int p = byOutput.start(keyId); p < byOutput.end(keyId); p++) {
            int other = byOutput.at(p);
            if (other != self && ground[other] < 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean carriesMarker(RecipeStore store, int recipe, Set<Integer> markers) {
        for (int slot = store.slotStart(recipe); slot < store.slotEnd(recipe); slot++) {
            for (int a = store.altStart(slot); a < store.altEnd(slot); a++) {
                if (markers.contains(Integer.valueOf(store.altKeyAt(a)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The curated markers whose recipes are documentation cards. THREE CONDITIONS.
     *
     * Each has a counterexample measured on the reference graph that the other two do not
     * catch. Add a fourth only with the same evidence, and read `notproduction.py`'s version
     * of this comment, which carries the counts.
     *
     * <ol>
     * <li>THE MARKER'S KIND IS {@code METHOD}. A METHOD marker says "the work happens in a
     * mechanic"; a LOOT one says "go and find it", which is a real way to get a thing and is
     * priced for it at {@code Cost.LOOT_COST}. Without this, six LOOT markers fire, including
     * `good_woot_drops` -- the `imp_skin` regression above -- plus four GATE and three HINT.
     * <li>EVERY FAMILY THE MARKER APPEARS IN YIELDS MORE THAN ONE DISTINCT OUTPUT, where a
     * FAMILY is the marker paired with one exact other-input set. An annotation card shares
     * one input list across N outputs and one recipe cannot produce N items; a genuine marker
     * varies its other inputs per output, as `dungeon_drop` plus a callstone does, one to one.
     * PER FAMILY rather than per marker, because `passive_crafting_subnets` is three cards
     * sharing a marker and a per-marker test misses all 25 of its recipes. Requiring EVERY
     * family to have the shape is what keeps `multiblock_preview` and
     * `dream_infusion_crafting`, both METHOD and both genuine, whose 42 multi-output recipes a
     * per-recipe test would demote.
     * <li>the guard in {@link #recipes}.
     * </ol>
     */
    static Set<Integer> markers(RecipeGraph g, Map<Integer, Integer> tokenKinds) {
        Set<Integer> out = new HashSet<Integer>();
        if (tokenKinds == null || tokenKinds.isEmpty()) {
            return out;
        }
        RecipeStore store = g.recipes();
        Csr byInput = g.byInput();
        for (Map.Entry<Integer, Integer> entry : tokenKinds.entrySet()) {
            if (entry.getValue().intValue() != Tokens.METHOD) {
                continue;
            }
            int marker = entry.getKey().intValue();
            // THE EXACT ROW ONLY, matching python's `graph.by_input.get(marker)`. A marker is
            // a producerless script item with no wildcard meta and no oredict group, so
            // `RecipeGraph.consumers`' widenings would add nothing here and would silently
            // pull in unrelated recipes if that ever stopped being true.
            if (marker < 0 || byInput.count(marker) <= 0) {
                continue;
            }
            Map<String, Set<String>> families = new HashMap<String, Set<String>>();
            for (int p = byInput.start(marker); p < byInput.end(marker); p++) {
                int recipe = byInput.at(p);
                String family = otherInputs(store, recipe, marker);
                Set<String> outputs = families.get(family);
                if (outputs == null) {
                    outputs = new HashSet<String>();
                    families.put(family, outputs);
                }
                outputs.add(outputKey(store, recipe));
            }
            boolean everyFamilyMultiOutput = true;
            for (Set<String> outputs : families.values()) {
                if (outputs.size() <= 1) {
                    everyFamilyMultiOutput = false;
                    break;
                }
            }
            if (everyFamilyMultiOutput) {
                out.add(Integer.valueOf(marker));
            }
        }
        return out;
    }

    /**
     * The recipe's slots with `marker`'s removed, as a canonical string.
     *
     * BY MEMBERSHIP IN THE SLOT'S ALTERNATIVES, NOT BY POSITION: `battle_tower`'s recipes
     * carry two markers, so "drop the first slot" removes the wrong one on half of them.
     *
     * SORTED ALTERNATIVES AND SORTED SLOTS, because the question is whether two recipes ask
     * for THE SAME THINGS and neither slot order nor alternative order is part of that.
     * A STRING KEY rather than a value class, for the reason {@code Solver.offerShape} gives:
     * it only ever feeds a HashMap, and a record type would need equals/hashCode kept in step
     * with python's tuple.
     */
    private static String otherInputs(RecipeStore store, int recipe, int marker) {
        List<String> slots = new ArrayList<String>();
        for (int slot = store.slotStart(recipe); slot < store.slotEnd(recipe); slot++) {
            List<Integer> alts = new ArrayList<Integer>();
            boolean isMarker = false;
            for (int a = store.altStart(slot); a < store.altEnd(slot); a++) {
                int key = store.altKeyAt(a);
                isMarker |= key == marker;
                alts.add(Integer.valueOf(key));
            }
            if (isMarker) {
                continue;
            }
            Collections.sort(alts);
            slots.add(alts + "x" + store.slotQty(slot));
        }
        Collections.sort(slots);
        return slots.toString();
    }

    private static String outputKey(RecipeStore store, int recipe) {
        List<Integer> keys = new ArrayList<Integer>();
        for (int p = store.outputStart(recipe); p < store.outputEnd(recipe); p++) {
            keys.add(Integer.valueOf(store.outputKeyAt(p)));
        }
        Collections.sort(keys);
        return keys.toString();
    }
}
