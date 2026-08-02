package io.github.jacoblasky.recipedump.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * A fixture's `scenario` block turned into the inputs a {@link Solver} takes.
 *
 * WHY THIS IS IN THE TEST TREE. A scenario is a document describing a world -- placed tile
 * entities, which dimensions have been visited, what the player has learned. Production never
 * sees one: the mod reads the live world, the live ProjectE registry and the live AE2 grid.
 * Only the fixtures speak this language, so only the fixtures carry the code that reads it.
 *
 * FOUR RESOLVERS ARE PORTED HERE AND NOWHERE ELSE, and that is a deliberate, narrow bet.
 * `machines.resolve` and `cost.estimate` are production concerns and are graphmodel's, in
 * `Machines` and `Cost`. These four are not:
 *
 *   `generators.resolve`   curated block -&gt; output map, matched against placed tile entities
 *   `tokens.resolve`       the curated placeholder map
 *   `dimensions.gates_for` the join of the graph's static half and the save's visited half
 *   `projecte.available`   learned AND carrying a positive EMC value
 *
 * Each is a handful of lines over a small curated table, and the tables are copied from the
 * Python originals rather than re-derived. IF ANY OF THEM GROWS A SECOND CALLER IN THE MOD,
 * MOVE IT TO `main` RATHER THAN COPYING IT -- a second home for `DEFAULT_TOKENS` is a second
 * thing to keep in step with `tokens.py`, and the symptom of drift is a plan that quietly
 * stops badging a quest gate.
 */
final class ScenarioInputs {

    /**
     * `tokens.DEFAULT_TOKENS`, copied verbatim. A pack placeholder standing in for an
     * instruction rather than an item: loot, a progression gate, a class of materials, or a
     * mechanic. FOUR KINDS because one bucket would lie -- folding a gate in with loot tells
     * a player to go and hunt for "Chapter 1".
     */
    private static final String[][] DEFAULT_TOKENS = {
        {"contenttweaker:boss_drop", "loot"},
        {"contenttweaker:dungeon_drop", "loot"},
        {"contenttweaker:hunter_mobs", "loot"},
        {"contenttweaker:trader_drop", "loot"},
        {"contenttweaker:battle_tower", "loot"},
        {"contenttweaker:mineralis_ritual", "loot"},
        {"contenttweaker:good_woot_drops", "loot"},
        {"contenttweaker:orbital_laser_drops", "loot"},
        {"contenttweaker:fisher_drop", "loot"},
        {"contenttweaker:rare_loot_table", "loot"},
        {"contenttweaker:foraging_loot_table", "loot"},
        {"contenttweaker:found_in_overworld", "loot"},
        {"contenttweaker:use_this_summon_item", "loot"},
        {"contenttweaker:recycler_drop", "loot"},
        {"contenttweaker:chapter_1", "gate"},
        {"contenttweaker:space_station", "gate"},
        {"contenttweaker:hunter_level_20", "gate"},
        {"contenttweaker:hunter_level_1", "gate"},
        {"contenttweaker:chapter_2", "gate"},
        {"contenttweaker:gamestage_recipe", "gate"},
        {"contenttweaker:hunter_level_30", "gate"},
        {"contenttweaker:chapter_4", "gate"},
        {"contenttweaker:chapter_8", "gate"},
        {"contenttweaker:chapter_6", "gate"},
        {"contenttweaker:chapter_5", "gate"},
        {"contenttweaker:good_sword_materials", "hint"},
        {"contenttweaker:good_shuriken_materials", "hint"},
        {"contenttweaker:good_tool_materials", "hint"},
        {"contenttweaker:coolant_great", "hint"},
        {"contenttweaker:multiblock_preview", "method"},
        {"contenttweaker:dream_infusion_crafting", "method"},
        {"contenttweaker:passive_crafting_subnets", "method"},
        {"contenttweaker:runic_altar_automation", "method"},
        {"contenttweaker:thaumatorium_automation", "method"},
        {"contenttweaker:infusion_pseudo_automation", "method"},
        {"contenttweaker:passive_packagedauto", "method"},
        {"contenttweaker:right_click_with_lots_of_infusionstones", "method"},
    };

    /** `generators.DEFAULT_GENERATORS`: an input-free block and what it emits. */
    private static final String[][] DEFAULT_GENERATORS = {
        {"nuclearcraft:water_source", "fluid:water"},
        {"nuclearcraft:water_source_dense", "fluid:water"},
        {"nuclearcraft:water_source_compact", "fluid:water"},
        {"nuclearcraft:cobblestone_generator", "minecraft:cobblestone"},
        {"nuclearcraft:cobblestone_generator_dense", "minecraft:cobblestone"},
        {"nuclearcraft:cobblestone_generator_compact", "minecraft:cobblestone"},
        {"thermalexpansion:device", "fluid:water"},
        {"industrialforegoing:water_condensator", "fluid:water"},
    };

    /** `generators.VANILLA_FREE`, on by default. */
    private static final String VANILLA_WATER_KEY = "fluid:water";
    private static final String VANILLA_WATER_WHY =
            "vanilla infinite water (two source blocks and a bucket)";

    /** `dimensions.HOME_DIMENSION`: the overworld is always visited. */
    private static final int HOME_DIMENSION = 0;

    private ScenarioInputs() {
    }

    /** Everything a scenario resolves to, so a cost table can be shared between fixtures. */
    static final class Resolved {
        final Map<Integer, Long> have = new LinkedHashMap<Integer, Long>();
        final Set<Integer> craftables = new LinkedHashSet<Integer>();
        final Map<Integer, String> freeSources = new LinkedHashMap<Integer, String>();
        final Map<Integer, Integer> tokenKinds = new LinkedHashMap<Integer, Integer>();
        final Map<Integer, String> dimensionGates = new LinkedHashMap<Integer, String>();
        final Set<Integer> emcAvailable = new LinkedHashSet<Integer>();
        final Map<Integer, Set<String>> pinned = new LinkedHashMap<Integer, Set<String>>();
        MachineStates machineStates;

        /**
         * What two scenarios must agree on to share one cost table.
         *
         * KEYED ON WHAT `Cost.estimate` IS ACTUALLY HANDED, not on a hand-maintained list of
         * scenario fields -- the Python generator makes the same choice for the same reason.
         * A field list is wrong in both directions: a new pricing input forgotten there makes
         * two scenarios share a table computed for one of them, and a non-pricing field left
         * in it prices the same table twice.
         *
         * `craftables` and `pinned` are absent because neither reaches the cost model.
         */
        String costSignature() {
            return have + "|" + freeSources.keySet() + "|" + tokenKinds + "|"
                    + dimensionGates.keySet() + "|" + emcAvailable + "|"
                    + Arrays.toString(machineStates.summarise()) + "|"
                    + Arrays.toString(machineStates.describedCategories());
        }
    }

    static Resolved resolve(RecipeGraph g, JsonObject scenario) {
        Resolved out = new Resolved();

        Map<String, Long> haveByKey = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, JsonElement> e : object(scenario, "have").entrySet()) {
            haveByKey.put(e.getKey(), e.getValue().getAsLong());
            int keyId = g.keyId(e.getKey());
            if (keyId >= 0) {
                out.have.put(keyId, e.getValue().getAsLong());
            }
        }
        for (JsonElement e : scenario.getAsJsonArray("craftables")) {
            int keyId = g.keyId(e.getAsString());
            if (keyId >= 0) {
                out.craftables.add(keyId);
            }
        }

        Map<String, Integer> placed = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, JsonElement> e : object(scenario, "placed").entrySet()) {
            placed.put(e.getKey(), e.getValue().getAsInt());
        }

        // machines.describe -> Machines.resolve. Placed tile entities and stock are the
        // evidence; the overrides are a human decision and outrank both.
        Evidence evidence = new Evidence();
        for (Map.Entry<String, Integer> e : placed.entrySet()) {
            evidence.placed(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Long> e : haveByKey.entrySet()) {
            evidence.stock(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, JsonElement> e
                : object(scenario, "machine_overrides").entrySet()) {
            int state = MachineInfo.stateOf(e.getValue().getAsString());
            if (state >= 0) {
                evidence.override(e.getKey(), state);
            }
        }
        for (JsonElement e : scenario.getAsJsonArray("no_machine")) {
            evidence.noMachine(e.getAsString());
        }
        out.machineStates = Machines.resolve(g, evidence);

        resolveFreeSources(g, placed, haveByKey, object(scenario, "source_overrides"), out);
        resolveTokens(g, object(scenario, "token_overrides"), out);
        resolveGates(g, object(scenario, "visited_dimensions"), out);
        resolveEmc(g, object(scenario, "emc_knowledge"), out);
        resolvePins(g, object(scenario, "pins"), out);
        return out;
    }

    /**
     * `generators.resolve`: what an owned generator makes free.
     *
     * Evidence is a placed tile entity first, then the generator item sitting in stock -- the
     * same order of directness machine availability uses. A generator in a drive is not
     * producing anything yet, but it is one place-block away, so it counts.
     *
     * FREE IS NOT ZERO, and that is decided in the solver rather than here: a free source
     * terminates a branch and is tallied in `from_sources` with its quantity, never folded
     * into stock. Seeding it at cost 0 would blind the ranker to quantity and a plan would
     * ask for a swimming pool.
     */
    private static void resolveFreeSources(RecipeGraph g, Map<String, Integer> placed,
                                           Map<String, Long> stock, JsonObject overrides,
                                           Resolved out) {
        Set<String> disabled = strings(overrides, "disabled");
        Map<String, String> normalisedPlaced = index(placed.keySet());
        Map<String, String> normalisedStock = index(stock.keySet());

        Map<String, List<String>> table = new LinkedHashMap<String, List<String>>();
        for (String[] row : DEFAULT_GENERATORS) {
            List<String> outputs = table.get(row[0]);
            if (outputs == null) {
                outputs = new ArrayList<String>(1);
                table.put(row[0], outputs);
            }
            outputs.add(row[1]);
        }
        if (overrides.has("generators") && overrides.get("generators").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e
                    : overrides.getAsJsonObject("generators").entrySet()) {
                List<String> outputs = new ArrayList<String>();
                for (JsonElement key : e.getValue().getAsJsonArray()) {
                    outputs.add(key.getAsString());
                }
                table.put(e.getKey(), outputs);
            }
        }

        for (Map.Entry<String, List<String>> entry : table.entrySet()) {
            String normalised = Machines.normaliseBlock(entry.getKey());
            String why;
            if (normalisedPlaced.containsKey(normalised)) {
                why = "placed: " + normalisedPlaced.get(normalised);
            } else if (normalisedStock.containsKey(normalised)) {
                why = "in stock: " + normalisedStock.get(normalised);
            } else {
                continue;
            }
            for (String key : entry.getValue()) {
                addFree(g, out, key, why, disabled);
            }
        }

        // `vanilla_water` defaults to TRUE. A scenario spelling the defaults out by hand
        // would stop tracking a change to them, so absence means the documented default.
        boolean vanillaWater = !overrides.has("vanilla_water")
                || overrides.get("vanilla_water").getAsBoolean();
        if (vanillaWater) {
            addFree(g, out, VANILLA_WATER_KEY, VANILLA_WATER_WHY, disabled);
        }
    }

    /** `setdefault`: the FIRST reason wins, so a placed block outranks one in a drive. */
    private static void addFree(RecipeGraph g, Resolved out, String key, String why,
                                Set<String> disabled) {
        if (disabled.contains(key)) {
            return;
        }
        int keyId = g.keyId(key);
        if (keyId >= 0 && !out.freeSources.containsKey(keyId)) {
            out.freeSources.put(keyId, why);
        }
    }

    /** `tokens.resolve`: the curated map plus the user's, minus their removals. */
    private static void resolveTokens(RecipeGraph g, JsonObject overrides, Resolved out) {
        Map<String, String> table = new LinkedHashMap<String, String>();
        for (String[] row : DEFAULT_TOKENS) {
            table.put(row[0], row[1]);
        }
        if (overrides.has("tokens") && overrides.get("tokens").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e
                    : overrides.getAsJsonObject("tokens").entrySet()) {
                if (Tokens.kindOf(e.getValue().getAsString()) >= 0) {
                    table.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        for (String key : strings(overrides, "disabled")) {
            table.remove(key);
        }
        for (Map.Entry<String, String> e : table.entrySet()) {
            int keyId = g.keyId(e.getKey());
            if (keyId >= 0) {
                out.tokenKinds.put(keyId, Tokens.kindOf(e.getValue()));
            }
        }
    }

    /**
     * `dimensions.gates_for`: the ores a plan would need a trip to reach.
     *
     * A STOCK FILE WITH NO `dimensions` RECORD GATES NOTHING. Reading "no record" as "never
     * been anywhere" would surcharge eight ores the moment an old stock file was loaded -- a
     * silent repricing of the pack triggered by a missing field.
     */
    private static void resolveGates(RecipeGraph g, JsonObject visited, Resolved out) {
        if (g.dimensionOreCount() == 0 || visited.entrySet().isEmpty()) {
            return;
        }
        Set<String> folders = new LinkedHashSet<String>();
        for (Map.Entry<String, JsonElement> e : visited.entrySet()) {
            folders.add(e.getKey());
        }
        // Scanned rather than iterated, because the graph exposes the dimension of a key and
        // not the set of keys that have one. One pass over the key table at fixture time is
        // nothing beside loading the graph that holds it.
        for (int keyId = 0; keyId < g.keyCount(); keyId++) {
            String name = g.dimensionName(keyId);
            if (name == null) {
                continue;
            }
            int dim = g.dimensionOf(keyId);
            if (dim != HOME_DIMENSION && !folders.contains("DIM" + dim)) {
                out.dimensionGates.put(keyId, name);
            }
        }
    }

    /**
     * `projecte.available`: learned AND carrying a positive EMC value.
     *
     * BOTH HALVES ARE REQUIRED AND THE VALUE HALF IS THE SAFETY ONE. ProjectE's own value of
     * 0 means "cannot be transmuted", and a pack this heavily scripted disables plenty of
     * items by setting exactly that -- so learning alone is not evidence of a route. #50's
     * stated worst case is asserting a route the pack has actually disabled.
     */
    private static void resolveEmc(RecipeGraph g, JsonObject knowledge, Resolved out) {
        if (g.emcCount() == 0) {
            return;
        }
        boolean full = knowledge.has("full") && knowledge.get("full").getAsBoolean();
        if (full) {
            for (int keyId = 0; keyId < g.keyCount(); keyId++) {
                if (g.emc(keyId) > 0) {
                    out.emcAvailable.add(keyId);
                }
            }
            return;
        }
        for (String key : strings(knowledge, "learned")) {
            int keyId = g.keyId(key);
            if (keyId >= 0 && g.emc(keyId) > 0) {
                out.emcAvailable.add(keyId);
            }
        }
    }

    private static void resolvePins(RecipeGraph g, JsonObject pins, Resolved out) {
        Map<String, Pins.Pin> stored = new LinkedHashMap<String, Pins.Pin>();
        for (Map.Entry<String, JsonElement> e : pins.entrySet()) {
            JsonObject pin = e.getValue().getAsJsonObject();
            stored.put(e.getKey(), new Pins.Pin(
                    pin.has("fingerprint") ? pin.get("fingerprint").getAsString() : "",
                    pin.has("category") ? pin.get("category").getAsString() : "",
                    pin.has("label") ? pin.get("label").getAsString() : ""));
        }
        Pins.Resolution resolution = Pins.resolve(g, stored);
        for (Map.Entry<String, Set<String>> e : resolution.accepted.entrySet()) {
            int keyId = g.keyId(e.getKey());
            if (keyId >= 0) {
                out.pinned.put(keyId, e.getValue());
            }
        }
    }

    /** `Cost.estimate` for a resolved scenario. The expensive step; memoise on the caller. */
    static CostTable price(RecipeGraph g, Resolved resolved) {
        CostInputs inputs = new CostInputs();
        for (int keyId : resolved.have.keySet()) {
            inputs.have(keyId);
        }
        for (int keyId : resolved.freeSources.keySet()) {
            inputs.freeSource(keyId);
        }
        for (int keyId : resolved.emcAvailable) {
            inputs.emcAvailable(keyId);
        }
        for (int keyId : resolved.dimensionGates.keySet()) {
            inputs.dimensionGated(keyId);
        }
        for (Map.Entry<Integer, Integer> e : resolved.tokenKinds.entrySet()) {
            inputs.token(e.getKey(), e.getValue());
        }
        inputs.machineStates(resolved.machineStates);
        inputs.machineItemsFrom(resolved.machineStates);
        return Cost.estimate(g, inputs);
    }

    /** The Solver the Python generator's `solver_for` builds. Keep the argument list in step. */
    static Solver solverFor(RecipeGraph g, Resolved r, CostTable costs, int maxNodes) {
        return new Solver.Builder(g)
                .have(r.have)
                .craftables(r.craftables)
                .machineStates(r.machineStates)
                .costs(costs)
                .freeSources(r.freeSources)
                .tokenKinds(r.tokenKinds)
                .pinned(r.pinned)
                .maxNodes(maxNodes)
                .dimensionGates(r.dimensionGates)
                .emcAvailable(r.emcAvailable)
                .build();
    }

    // -- small helpers ---------------------------------------------------------------------

    private static JsonObject object(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : new JsonObject();
    }

    private static Set<String> strings(JsonObject parent, String name) {
        Set<String> out = new LinkedHashSet<String>();
        if (parent.has(name) && parent.get(name).isJsonArray()) {
            for (JsonElement e : parent.getAsJsonArray(name)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    /**
     * `machines.index_ids`: every spelling of an id mapped back to the id itself.
     *
     * A legacy dotted tile-entity id arrives from the save as `minecraft:mod.thing` and would
     * never literally equal the `mod:thing` a curated table is written in.
     */
    private static Map<String, String> index(Iterable<String> ids) {
        Map<String, String> out = new HashMap<String, String>();
        for (String id : ids) {
            for (String form : Machines.matchForms(id)) {
                out.put(form, id);
            }
            out.put(Machines.normaliseBlock(id), id);
        }
        return out;
    }
}
