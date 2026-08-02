package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.Csr;
import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which machines you actually have, and what that means for recipe choice. Ports
 * `recipegraph/machines.py`.
 *
 * THE PROBLEM. On a 124k-recipe graph almost every item has dozens of routes, most through
 * machines the player has never built. Without this the solver plans a chain through a
 * Recursive Processor you do not own, which is how "64 Borax" ended up routed via Chaos
 * Fragments. Every recipe carries its JEI category and a category IS a machine, so machine
 * availability is a constraint on categories -- which turns recipe choice from a guess into
 * a filter.
 *
 * THIS RUNS ONCE, over ~500 categories, and is not on any hot path. That is why it is
 * allowed to work in Strings: identification is inherently textual -- camelCase boundaries,
 * tile-entity aliasing, running-state suffixes -- and forcing it through int ids would make
 * it slower to read for no measurable gain. Candidates are converted to key ids on the way
 * out, which is the boundary everything downstream lives on.
 */
public final class Machines {

    /**
     * Items JEI lists as a catalyst that are NOT the machine.
     *
     * Modular Machinery's blueprint is the whole list: one cheap item that catalyses 226
     * unrelated categories. You can craft a blueprint; that does not give you the machine.
     * Excluding it in {@link MachineStates#buildTargets} is load-bearing -- see there.
     */
    static final Set<String> NOT_A_MACHINE = new HashSet<String>(
            Arrays.asList("modularmachinery:itemblueprint"));

    /** Categories that need no machine at all. Never gate these; the player always has hands. */
    private static final Set<String> ALWAYS_AVAILABLE = new HashSet<String>(Arrays.asList(
            "minecraft.crafting", "minecraft.crafting.shaped", "minecraft.crafting.shapeless",
            "minecraft.anvil", "minecraft.brewing", "minecraft.fuel", "minecraft.smelting",
            "jei.information", "jei.description"));

    /**
     * Categories with no machine BY NATURE, which is a different answer from "we could not
     * work out which block this is".
     *
     * Bee and tree breeding happen in an Apiary JEI registers no catalyst for; a chicken lays
     * where it stands, so the chicken IS the machine. Reporting those as `unknown` reads as a
     * tool failure and prices real, always-available production at 120x hand-crafting.
     *
     * This IS a list and it has to be -- nothing in a recipe distinguishes "needs no machine"
     * from "machine not identified" -- but it is a list of STRUCTURAL SITUATIONS rather than
     * a per-pack lookup. A pack needing another entry adds it through
     * {@link Evidence#noMachine} instead of editing this.
     *
     * DO NOT collapse the three `chickens.` entries into a bare `chickens.` prefix. That mod
     * also registers `chickens.drops` and `chickens.throws`, which are display categories
     * dropped elsewhere today -- so a prefix would look harmless while silently granting "no
     * machine needed" to whatever the mod registers next. Each entry is a claim about one
     * category. Substrings, matched case-insensitively.
     */
    private static final String[] NO_MACHINE_PATTERNS = {
        "jeibees.mutation", "jeibees.produce",
        "beetree",
        "chickens.laying", "chickens.henhousing", "chickens.breeding",
    };

    /**
     * Below this dump schema the built-in no-machine patterns do not fire.
     *
     * Every pattern above describes production by a CREATURE, and a creature's identity lives
     * in NBT the dump only began emitting at schema 3. Below that, all 437 bee mutations are
     * the same four keys and `produce.rootBees` is one input claiming to make 323 unrelated
     * items -- pricing that as free lets anything reach anything through a generic drone.
     * Measured: Americium-242 rerouted onto bee larvae, diamond and glass panes.
     *
     * So the verdict is gated on the data being able to support it, and it self-heals on the
     * next dump rather than needing a flag anyone has to remember. See #20.
     */
    static final int SPECIES_SCHEMA = 3;

    /**
     * The two sources name hand-crafting differently.
     *
     * The JEI dump says `minecraft.crafting`, the offline jar reader says `crafting_shaped` /
     * `crafting_shapeless`. Both are a crafting table, and matching only one left 10,301
     * offline recipes gated behind a machine the player was told they did not have. DO NOT
     * test either prefix directly; go through {@link #isHandCrafting} so the two conventions
     * cannot drift apart again.
     */
    private static final String[] HAND_CRAFTING_PREFIXES =
            {"minecraft.crafting", "crafting_shaped", "crafting_shapeless"};

    private static final Pattern SPLIT = Pattern.compile("[^a-z0-9]+");
    /**
     * Category uids are frequently camelCase (`TechReborn.WireMill`) while registry names are
     * snake_case. Lowercasing alone gives `wiremill`, which matches nothing; the boundary has
     * to be recovered before the case is thrown away.
     */
    private static final Pattern CAMEL =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+");
    /**
     * Machine blocks are commonly registered once per running state. NuclearCraft ships
     * `alloy_furnace_idle` / `_active` as separate items while the placed tile entity is the
     * bare `alloy_furnace`, so a literal comparison reports a machine you are standing next
     * to as merely "buildable".
     */
    private static final Pattern STATE_SUFFIX = Pattern.compile(
            "_(idle|active|on|off|lit|unlit|powered|unpowered|running|working)$");
    /** Minecraft colour/format codes: the section sign plus one character. */
    private static final Pattern FORMAT_CODE = Pattern.compile("§.");

    private Machines() {
    }

    // -- text helpers, each one a rule someone paid for -------------------------------------

    /** Strip format codes. Null-safe, because JEI category titles are often absent. */
    public static String cleanLabel(String label) {
        if (label == null) {
            return null;
        }
        String out = FORMAT_CODE.matcher(label).replaceAll("").trim();
        return out.isEmpty() ? null : out;
    }

    public static boolean isHandCrafting(String category) {
        String cat = category == null ? "" : category;
        for (String prefix : HAND_CRAFTING_PREFIXES) {
            if (cat.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #isHandCrafting(String)} by category id, for callers already in int space.
     *
     * Decodes the category name per call rather than caching a bitset, and that is a measured
     * decision rather than laziness: the hot caller scores a few tens of thousands of
     * candidates per plan, and decoding a short category string that many times is
     * milliseconds. A cache here would be a second thing to invalidate for no gain anybody
     * can observe.
     */
    public static boolean isHandCrafting(RecipeGraph graph, int categoryId) {
        return categoryId >= 0 && categoryId < graph.categoryCount()
                && isHandCrafting(graph.categoryName(categoryId));
    }

    /** True when a category has no machine by nature rather than none we could find. */
    public static boolean needsNoMachine(String category, List<String> extra, int schema) {
        String cat = (category == null ? "" : category).toLowerCase();
        if (extra != null) {
            for (String declared : extra) {
                if (String.valueOf(declared).toLowerCase().equals(cat)) {
                    return true;
                }
            }
        }
        if (schema < SPECIES_SCHEMA) {
            return false;
        }
        for (String pattern : NO_MACHINE_PATTERNS) {
            if (cat.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Drop a machine's running-state suffix so variants compare equal. */
    public static String normaliseBlock(String key) {
        String out = String.valueOf(key).toLowerCase();
        String previous = null;
        while (!out.equals(previous)) {
            previous = out;
            out = STATE_SUFFIX.matcher(out).replaceAll("");
        }
        return out;
    }

    /**
     * Every normalised id a placed block should be findable under, verbatim form first.
     *
     * A TILE-ENTITY ID IS NOT AN ITEM ID. Mods that register a colon-less id the old way get
     * it namespaced into `minecraft:` by Forge, so the world save literally records
     *
     * <pre>
     *   minecraft:tconstruct.smeltery_controller     what is placed
     *   tconstruct:smeltery_controller               what JEI calls the machine
     * </pre>
     *
     * and the two can never compare equal: a Smeltery Controller you are standing next to
     * reads as "buildable". So a dotted path in the MINECRAFT namespace is also indexed with
     * its first segment promoted to a namespace. No vanilla registry name contains a dot,
     * which is what makes this unambiguous, and `agricraft:tile.crop` is left alone because
     * an id that already names its mod is not guessing at anything.
     *
     * DO NOT extend this to invent a modid out of the remainder. `tile.woot_anvil` aliases to
     * the useless `tile:woot_anvil` and that is deliberate: nothing in the id says Woot owns
     * it, 9 of the 29 dotted ids in the reference save are that shape, and a guess here would
     * be fabricated evidence rather than a sighting.
     */
    public static String[] matchForms(String key) {
        String norm = normaliseBlock(key);
        int colon = norm.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : norm.substring(0, colon);
        String path = colon < 0 ? norm : norm.substring(colon + 1);
        if (colon >= 0 && path.isEmpty()) {
            // A hand-written override may omit the namespace entirely.
            namespace = "minecraft";
            path = norm;
        }
        if (!namespace.equals("minecraft") || path.indexOf('.') < 0) {
            return new String[] {norm};
        }
        int dot = path.indexOf('.');
        String modid = path.substring(0, dot);
        String rest = path.substring(dot + 1);
        if (modid.isEmpty() || rest.isEmpty()) {
            return new String[] {norm};
        }
        // Normalised AGAIN: the state suffix sits on the END of the legacy path, so
        // `minecraft:mod.machine_idle` has to reach `mod:machine`, not `mod:machine_idle`.
        return new String[] {norm, normaliseBlock(modid + ":" + rest)};
    }

    /**
     * {normalised id: the id as recorded}, for membership tests that ignore state suffixes.
     *
     * The value is always the VERBATIM id, so evidence can quote what the save actually says
     * rather than a form this class invented. First writer wins, matching `setdefault`.
     */
    public static Map<String, String> indexIds(Iterable<String> keys) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String key : keys) {
            for (String form : matchForms(key)) {
                if (!out.containsKey(form)) {
                    out.put(form, key);
                }
            }
        }
        return out;
    }

    /**
     * Does `key` belong to the mod that owns category `uid`?
     *
     * Compares the candidate's modid against the SQUASHED uid rather than against its first
     * token. A modid can itself contain an underscore -- `tinker_io:smart_output` tokenises
     * to `tinker`, so a first-token comparison declares tinker_io's own machine to be from a
     * different mod -- and a uid may separate modid from name with `.`, `_`, `:` or a
     * camelCase boundary, none of which can be told apart from a separator inside the modid.
     * Substring on the squashed form is the only comparison that survives all of those.
     */
    public static boolean sameMod(String uid, String key) {
        String modid = squash(String.valueOf(key).split(":", -1)[0]);
        return !modid.isEmpty() && squash(uid).contains(modid);
    }

    private static String squash(String text) {
        return NON_ALNUM.matcher(String.valueOf(text)).replaceAll("").toLowerCase();
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<String>();
        for (String token : SPLIT.split(String.valueOf(text).toLowerCase(), -1)) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * Registry-name guesses built from a category uid, best first.
     *
     * `TechReborn.WireMill` has to reach `techreborn:wire_mill`, so the camelCase boundary is
     * recovered before lowercasing. The un-split form is tried too, because plenty of mods
     * register `nuclearcraft:cobblestone_generator` style ids from an already-underscored
     * uid, and a few register the squashed form.
     */
    static List<String> idGuesses(String uid) {
        List<String> parts = new ArrayList<String>();
        for (String part : NON_ALNUM.split(String.valueOf(uid), -1)) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        List<String> guesses = new ArrayList<String>();
        if (parts.size() < 2) {
            return guesses;
        }
        String modid = parts.get(0).toLowerCase();
        List<String> words = new ArrayList<String>();
        for (int i = 1; i < parts.size(); i++) {
            for (String word : CAMEL.split(parts.get(i), -1)) {
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        if (words.isEmpty()) {
            return guesses;
        }
        StringBuilder snake = new StringBuilder();
        StringBuilder squashed = new StringBuilder();
        for (String word : words) {
            if (snake.length() > 0) {
                snake.append('_');
            }
            snake.append(word.toLowerCase());
            squashed.append(word.toLowerCase());
        }
        guesses.add(modid + ":" + snake);
        if (!squashed.toString().equals(snake.toString())) {
            guesses.add(modid + ":" + squashed);
        }
        return guesses;
    }

    /**
     * Reorder each category's catalysts so the most specific machine comes first.
     *
     * JEI lists whatever opens a category's recipes, and for Modular Machinery that is the
     * generic Machine Blueprint -- one item catalysing 226 unrelated categories. Taken in
     * JEI's order it becomes the answer to "what machine is this", so a plan read
     * "Mythic Processor: Melter -- craftable: modularmachinery:itemblueprint".
     *
     * Ordering by how many categories an item catalyses, fewest first, fixes it with no
     * threshold and no per-mod list: a purpose-built controller catalyses exactly one
     * category and wins, while a generic block that is genuinely the only candidate (Extra
     * Utilities registers 19 machine types under `extrautils2:machine`) is DEMOTED rather
     * than dropped, so it still answers when nothing better exists.
     *
     * This only fixes WHICH item is named. {@link #NOT_A_MACHINE} and the build-target
     * filter are what stop a blueprint being what a machine is PRICED from.
     */
    static int[] orderBySpecificity(int[] candidates, Map<Integer, Integer> breadth) {
        Integer[] order = new Integer[candidates.length];
        final int[] keys = candidates;
        for (int i = 0; i < candidates.length; i++) {
            order[i] = Integer.valueOf(i);
        }
        final Map<Integer, Integer> width = breadth;
        // Sorted on (breadth, FIRST index of this key), matching python's `ids.index(k)`, and
        // stable so a category listing one key twice keeps the pair adjacent.
        final Map<Integer, Integer> firstIndex = new HashMap<Integer, Integer>();
        for (int i = candidates.length - 1; i >= 0; i--) {
            firstIndex.put(Integer.valueOf(candidates[i]), Integer.valueOf(i));
        }
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                Integer leftKey = Integer.valueOf(keys[left.intValue()]);
                Integer rightKey = Integer.valueOf(keys[right.intValue()]);
                int leftWidth = width.containsKey(leftKey) ? width.get(leftKey).intValue() : 1;
                int rightWidth = width.containsKey(rightKey)
                        ? width.get(rightKey).intValue() : 1;
                if (leftWidth != rightWidth) {
                    return leftWidth < rightWidth ? -1 : 1;
                }
                return firstIndex.get(leftKey).compareTo(firstIndex.get(rightKey));
            }
        });
        int[] out = new int[candidates.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = candidates[order[i].intValue()];
        }
        return out;
    }

    /** `" (name match, other mod)"` when no candidate belongs to the category's own mod. */
    static String crossModNote(String uid, List<String> candidates) {
        if (candidates.isEmpty()) {
            return "";
        }
        for (String candidate : candidates) {
            if (sameMod(uid, candidate)) {
                return "";
            }
        }
        return " (name match, other mod)";
    }

    /**
     * {lowercased display label: keys carrying it}, for name-to-id lookup.
     *
     * BUILT LAZILY AND ONLY WHEN A CATEGORY HAS NO CATALYST, because it is a map over every
     * named key in the graph -- 261,089 of them -- and a graph with catalysts never needs it.
     * Building it eagerly would put tens of megabytes of transient garbage on the resolve
     * path of every pack that does not need name matching at all.
     *
     * KEYS ARE VISITED IN SORTED ORDER, and that is not cosmetic. Python iterates
     * `graph.names`, which is the JSON document order, and `Graph.save` writes with sorted
     * keys -- so the lists come out in sorted-key order there. The candidate list feeds a
     * POSITIONAL tie-break in {@link #resolve}, so visiting in interned-id order instead
     * would silently change which machine gets named for a category whose candidates tie.
     *
     * IT INDEXES THE RECORDED LABEL, NOT THE RENDERED ONE. Python builds this from
     * `graph.names` directly, so an aspect-parameterised entry is keyed under the literal
     * "%s Vis Pod" and a meta sibling under its base's label. Keying it under the rendered
     * name instead -- "Vis Pod", "Wool (3)" -- silently changes which titles match, and a
     * JEI category title is matched against these verbatim.
     */
    private static Map<String, List<String>> buildReverseNames(RecipeGraph graph) {
        List<String> named = new ArrayList<String>();
        for (int key = 0; key < graph.keyCount(); key++) {
            if (graph.hasName(key)) {
                named.add(graph.key(key));
            }
        }
        java.util.Collections.sort(named);
        Map<String, List<String>> reverse = new HashMap<String, List<String>>();
        for (String key : named) {
            String label = graph.recordedName(graph.keyId(key));
            String lower = label.toLowerCase();
            List<String> keys = reverse.get(lower);
            if (keys == null) {
                keys = new ArrayList<String>();
                reverse.put(lower, keys);
            }
            keys.add(key);
        }
        return reverse;
    }

    /**
     * Item keys that could BE the machine for a category.
     *
     * A machine block's item and block registry names are the same in 1.12.2, so a candidate
     * can be checked against both placed tile entities and inventory with no extra mapping.
     * Candidates come from the category's display title -- what JEI labels the machine --
     * then filtered by the category's modid where one is recoverable: without that filter a
     * title like "Furnace" matches a dozen unrelated mods.
     *
     * Title matching is a HEURISTIC and misses roughly 40% of categories, because a JEI title
     * is often the recipe type rather than the machine. Catalysts are the authoritative
     * mapping and win outright when present; this is the fallback for a graph without them.
     */
    static List<String> candidateItems(RecipeGraph graph, String uid, String machineTitle,
                                       Map<String, List<String>> reverseNames) {
        List<String> candidates = new ArrayList<String>();
        String title = cleanLabel(machineTitle);
        if (title != null) {
            List<String> byName = reverseNames.get(title.toLowerCase());
            if (byName != null) {
                candidates.addAll(byName);
            }
        }
        List<String> uidTokens = tokens(uid);
        if (uidTokens.isEmpty()) {
            return candidates;
        }
        List<String> same = new ArrayList<String>();
        for (String candidate : candidates) {
            if (sameMod(uid, candidate)) {
                same.add(candidate);
            }
        }
        if (!same.isEmpty()) {
            return same;
        }
        for (String guess : idGuesses(uid)) {
            int key = graph.keyId(guess);
            if (key >= 0 && graph.hasName(key) && !candidates.contains(guess)) {
                candidates.add(guess);
            }
        }
        return candidates;
    }

    // Evidence ordered by how direct it is; the numbers exist only to rank verdicts.
    private static final int PLACED = 0;
    private static final int IN_STOCK = 1;
    private static final int CRAFTABLE = 2;
    private static final int NO_ROUTE = 3;

    /** One candidate's verdict, kept together so the rank cannot drift from the evidence. */
    private static final class Verdict {
        final int rank;
        final int state;
        final String why;

        Verdict(int rank, int state, String why) {
            this.rank = rank;
            this.state = state;
            this.why = why;
        }
    }

    /**
     * The verdict for ONE candidate machine item.
     *
     * The four checks are in order of how direct the evidence is, and THE ORDER IS TESTED.
     * Reordering exact-then-meta ahead of the NBT-variant check keeps a suite green while
     * making `thermalexpansion.pulverizer` name a Redstone Furnace as its route, which is #28.
     */
    private static Verdict candidateVerdict(RecipeGraph graph, String key,
                                            Map<String, String> placedIndex,
                                            Map<String, String> stockIndex) {
        String norm = normaliseBlock(key);
        if (placedIndex.containsKey(norm)) {
            return new Verdict(PLACED, MachineInfo.HAVE, "placed: " + placedIndex.get(norm));
        }
        if (stockIndex.containsKey(norm)) {
            return new Verdict(IN_STOCK, MachineInfo.HAVE,
                    "in stock: " + stockIndex.get(norm));
        }
        int keyId = graph.keyId(key);
        if (keyId >= 0) {
            IntArray anyVariant = new IntArray(4);
            if (graph.producersAnyVariant(keyId, anyVariant) > 0) {
                // Name the variant that is ACTUALLY craftable when it differs from the
                // catalyst, so the evidence stays checkable rather than asserting a route to
                // a key with no producers of its own.
                String shown = key;
                if (!graph.hasProducers(keyId)) {
                    int[] variants = graph.variantsOf(keyId);
                    shown = key + " (as " + graph.key(variants[0]) + ")";
                }
                return new Verdict(CRAFTABLE, MachineInfo.BUILDABLE, "craftable: " + shown);
            }
            // Last resort: the same registry name at a different metadata value, and ONLY
            // when the pack registered no name for this meta. A tool that selects its mode
            // with damage gets catalogued by JEI under the mode's meta and crafted only at
            // meta 0, and reporting "no route" for a toolbox the player can make is a
            // falsehood, not a caveat.
            int sibling = graph.metaSiblingMade(keyId);
            if (sibling >= 0) {
                return new Verdict(CRAFTABLE, MachineInfo.BUILDABLE,
                        "craftable: " + key + " (as " + graph.key(sibling) + ")");
            }
        }
        return new Verdict(NO_ROUTE, MachineInfo.UNAVAILABLE, "no route to " + key);
    }

    /**
     * Full per-category detail: state, evidence, candidate machine items, recipe count.
     *
     * Categories come back in FIRST-APPEARANCE-IN-RECIPES order, matching python's
     * `describe`, which builds its map by walking the recipe list. That is NOT ascending
     * category id: catalyst categories are interned before any recipe is read.
     */
    public static MachineStates resolve(RecipeGraph graph, Evidence evidence) {
        Evidence facts = evidence == null ? new Evidence() : evidence;
        Map<String, String> placedIndex = indexIds(facts.placedBlocks().keySet());
        Map<String, String> stockIndex = indexIds(facts.stockedItems().keySet());
        Map<String, Integer> overrides = facts.overrideStates();
        List<String> noMachine = facts.noMachineCategories();

        // Categories in first-appearance order, each carrying the FIRST recipe's machine
        // title -- including when that is absent, which is what `setdefault` does.
        IntArray order = new IntArray();
        Map<Integer, Integer> titleOf = new HashMap<Integer, Integer>();
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        for (int recipe = 0; recipe < graph.recipes().count(); recipe++) {
            Integer category = Integer.valueOf(graph.recipes().categoryId(recipe));
            if (!counts.containsKey(category)) {
                order.add(category.intValue());
                titleOf.put(category, Integer.valueOf(graph.recipes().machineId(recipe)));
                counts.put(category, Integer.valueOf(1));
            } else {
                counts.put(category, Integer.valueOf(counts.get(category).intValue() + 1));
            }
        }

        Map<Integer, int[]> catalysts = catalystsByCategory(graph);
        Map<String, List<String>> reverseNames = null;

        MachineInfo[] infos = new MachineInfo[order.size()];
        int[] categories = order.trimmed();
        for (int i = 0; i < categories.length; i++) {
            int category = categories[i];
            String uid = graph.categoryName(category);
            String title = cleanLabel(graph.machineName(titleOf.get(Integer.valueOf(category))
                    .intValue()));
            String mod = modName(graph, category, uid);
            int recipeCount = counts.get(Integer.valueOf(category)).intValue();
            boolean manual = overrides.containsKey(uid);

            if (manual) {
                infos[i] = new MachineInfo(category, overrides.get(uid).intValue(),
                        "manual override", title, mod, recipeCount, null, null, null, true,
                        false);
                continue;
            }
            if (ALWAYS_AVAILABLE.contains(uid) || isHandCrafting(uid)) {
                infos[i] = new MachineInfo(category, MachineInfo.HAVE, "no machine needed",
                        title, mod, recipeCount, null, null, null, false, false);
                continue;
            }
            if (needsNoMachine(uid, noMachine, graph.dumpSchema())) {
                // Distinguished from the line above in the EVIDENCE, not the state: both are
                // ungated, but "no machine needed" on a bee category is a claim about how
                // breeding works and the reader should be able to check it.
                infos[i] = new MachineInfo(category, MachineInfo.HAVE,
                        "no machine needed (bred, grown or laid)", title, mod, recipeCount,
                        null, null, null, false, false);
                continue;
            }

            int[] catalyst = catalysts.get(Integer.valueOf(category));
            boolean fromCatalyst = catalyst != null && catalyst.length > 0;
            List<String> candidates;
            if (fromCatalyst) {
                candidates = new ArrayList<String>(catalyst.length);
                for (int key : catalyst) {
                    candidates.add(graph.key(key));
                }
            } else {
                if (reverseNames == null) {
                    reverseNames = buildReverseNames(graph);
                }
                candidates = candidateItems(graph, uid, title, reverseNames);
            }
            if (candidates.isEmpty()) {
                infos[i] = new MachineInfo(category, MachineInfo.UNKNOWN,
                        "machine item unknown", title, mod, recipeCount, null, null, null,
                        false, false);
                continue;
            }

            // A cross-mod name match is a much weaker claim than a same-mod one: the Extra
            // Utilities furnace category is titled "Furnace" and matches `minecraft:furnace`,
            // so "placed" would otherwise assert you own a machine you never built. Say so in
            // the evidence rather than presenting a guess as a sighting.
            String caveat = fromCatalyst ? "" : crossModNote(uid, candidates);

            int[] candidateKeys = new int[candidates.size()];
            int[] candidateStates = new int[candidates.size()];
            String[] candidateWhy = new String[candidates.size()];
            int bestRank = Integer.MAX_VALUE;
            int bestAt = 0;
            for (int c = 0; c < candidates.size(); c++) {
                String candidate = candidates.get(c);
                Verdict verdict = candidateVerdict(graph, candidate, placedIndex, stockIndex);
                candidateKeys[c] = graph.keyId(candidate);
                candidateStates[c] = verdict.state;
                candidateWhy[c] = verdict.why;
                // Strictly less-than, so the FIRST candidate at the best rank wins. A placed
                // block third in the list beats one merely in stock at the top, because
                // standing next to a machine is stronger evidence than owning its item.
                if (verdict.rank < bestRank) {
                    bestRank = verdict.rank;
                    bestAt = c;
                }
            }
            infos[i] = new MachineInfo(category, candidateStates[bestAt],
                    candidateWhy[bestAt] + caveat, title, mod, recipeCount, candidateKeys,
                    candidateStates, candidateWhy, false, fromCatalyst);
        }
        return new MachineStates(graph, categories, infos);
    }

    /**
     * Which mod owns a category, for grouping and display.
     *
     * JEI's own name when the dump carried one, and only then the uid's first token. The
     * guess is wrong whenever a uid does not begin with its modid, which produced
     * one-category "mods" called `foregoing`, `safe` and `soulbinder` for Industrial
     * Foregoing, Extreme Reactors and enderiomachines.
     */
    private static String modName(RecipeGraph graph, int categoryId, String uid) {
        String known = graph.categoryMod(categoryId);
        if (known != null && !known.isEmpty()) {
            return known;
        }
        List<String> parts = tokens(uid);
        return parts.isEmpty() ? "" : parts.get(0);
    }

    /**
     * The catalyst map, reordered by specificity.
     *
     * MUST RUN OVER THE WHOLE MAPPING: "is this item specific to one machine" is only
     * answerable by looking at every category at once, so the breadth count is taken before
     * any category is ordered.
     */
    private static Map<Integer, int[]> catalystsByCategory(RecipeGraph graph) {
        Map<Integer, int[]> raw = new LinkedHashMap<Integer, int[]>();
        Csr catalysts = graph.catalysts();
        for (int category = 0; category < catalysts.rows(); category++) {
            if (catalysts.count(category) > 0) {
                raw.put(Integer.valueOf(category), catalysts.row(category));
            }
        }
        Map<Integer, Integer> breadth = new HashMap<Integer, Integer>();
        for (int[] keys : raw.values()) {
            for (int key : keys) {
                Integer boxed = Integer.valueOf(key);
                Integer seen = breadth.get(boxed);
                breadth.put(boxed, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
            }
        }
        Map<Integer, int[]> out = new LinkedHashMap<Integer, int[]>();
        for (Map.Entry<Integer, int[]> entry : raw.entrySet()) {
            out.put(entry.getKey(), orderBySpecificity(entry.getValue(), breadth));
        }
        return out;
    }
}
