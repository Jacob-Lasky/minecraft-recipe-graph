package io.github.jacoblasky.recipedump.graph;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The BEFORE in the before/after: `model.py` transliterated into Java, object for object.
 *
 * WHY THIS EXISTS AND WHY IT IS NOT DEAD CODE. #126 asks whether the graph can live in a
 * Minecraft client's heap, and the compact model in the main source set is an answer to a
 * question -- "what does the obvious implementation cost?" -- that nobody had measured. An
 * argument that flat primitive arrays were NECESSARY, rather than merely tidy, has to be able
 * to name the number they replaced. This class is that number.
 *
 * It is a faithful transliteration, deliberately: a {@code Recipe} object per recipe, an
 * {@code Ingredient} per input slot, a {@code String} per key OCCURRENCE (which is what a
 * streaming parser hands you if you do not intern), and {@code HashMap} indexes over all of
 * it. The `--interned` variant changes exactly one thing -- key strings are canonicalised
 * through a map -- so the ladder isolates how much of the cost is duplicate strings and how
 * much is the object graph itself.
 *
 * DO NOT "improve" this class. Making it cheaper makes the comparison dishonest. It lives in
 * the TEST source set precisely so nothing can start depending on it.
 */
final class NaiveGraph {

    static final class Ingredient {
        String[] alternatives;
        int qty;
        String role;
    }

    static final class Recipe {
        String rid;
        String source;
        String category;
        String machine;
        String[] outputKeys;
        int[] outputQty;
        Ingredient[] inputs;
        boolean transfer;
        boolean variant;
    }

    static final class Multiblock {
        String registryName;
        String displayName;
        String controller;
        int slots;
        int blind;
        List<int[]> partCounts = new ArrayList<int[]>();
        List<String[]> partKeys = new ArrayList<String[]>();
    }

    private final List<Recipe> recipes = new ArrayList<Recipe>();
    private final Map<String, String> names = new HashMap<String, String>();
    private final Map<String, List<String>> oreMembers = new HashMap<String, List<String>>();
    private final Map<String, List<String>> oreIndex = new HashMap<String, List<String>>();
    private final Map<String, List<String>> catalysts = new HashMap<String, List<String>>();
    private final Map<String, String> categoryMods = new HashMap<String, String>();
    private final Map<String, String[]> dimensionOres = new HashMap<String, String[]>();
    private final List<Multiblock> multiblocks = new ArrayList<Multiblock>();
    private final Map<String, List<Recipe>> byOutput = new HashMap<String, List<Recipe>>();
    private final Map<String, List<Recipe>> byInput = new HashMap<String, List<Recipe>>();
    private final Set<String> worldOres = new HashSet<String>();

    /**
     * Canonicalises key strings when the caller asked for it.
     *
     * NOT {@code String.intern()}. That puts every key in the JVM's own string table, which
     * on Java 8 is a fixed-size hash table sized for a few tens of thousands of entries and
     * would turn 300,000 interned keys into a pathological bucket chain -- a measurement of
     * the wrong thing. A plain HashMap gives the same deduplication with predictable cost,
     * and it is retained alongside the graph because a real implementation that interned at
     * load would have to keep it to intern anything later.
     */
    private final Map<String, String> canonical;

    private NaiveGraph(boolean intern) {
        this.canonical = intern ? new HashMap<String, String>() : null;
    }

    private String key(String value) {
        if (canonical == null) {
            return value;
        }
        String existing = canonical.get(value);
        if (existing != null) {
            return existing;
        }
        canonical.put(value, value);
        return value;
    }

    int recipeCount() {
        return recipes.size();
    }

    // -- read side, used only by `NaiveGraphAgreementTest` -----------------------------------
    //
    // The before/after in the PR is only worth anything if both readers put the same data in
    // their respective shapes. These accessors are what lets a test say so; without them the
    // naive model could quietly stop parsing a section and the comparison would flatter the
    // compact one.

    Set<String> outputKeys() {
        return byOutput.keySet();
    }

    Set<String> inputKeys() {
        return byInput.keySet();
    }

    int producerCount(String key) {
        List<Recipe> made = byOutput.get(key);
        return made == null ? 0 : made.size();
    }

    int consumerCount(String key) {
        List<Recipe> used = byInput.get(key);
        return used == null ? 0 : used.size();
    }

    Map<String, String> names() {
        return names;
    }

    Set<String> worldOreKeys() {
        return worldOres;
    }

    Map<String, List<String>> oreGroups() {
        return oreMembers;
    }

    String census() {
        return "recipes                " + recipes.size() + "\n"
                + "names                  " + names.size() + "\n"
                + "oredict groups         " + oreMembers.size() + "\n"
                + "world ores             " + worldOres.size() + "\n"
                + "by-output keys         " + byOutput.size() + "\n"
                + "by-input keys          " + byInput.size() + "\n"
                + "catalyst categories    " + catalysts.size() + "\n"
                + "multiblocks            " + multiblocks.size() + "\n"
                + "canonicalised keys     " + (canonical == null ? "n/a" : canonical.size());
    }

    static NaiveGraph read(File file, boolean intern) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return read(in, intern);
        } finally {
            in.close();
        }
    }

    static NaiveGraph read(InputStream in, boolean intern) throws IOException {
        NaiveGraph graph = new NaiveGraph(intern);
        JsonReader reader = new JsonReader(new InputStreamReader(
                new BufferedInputStream(in, 1 << 20), "UTF-8"));
        try {
            graph.readGraph(reader);
        } finally {
            reader.close();
        }
        graph.index();
        return graph;
    }

    private void readGraph(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (field.equals("recipes")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    recipes.add(readRecipe(reader));
                }
                reader.endArray();
            } else if (field.equals("names")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    names.put(key(name), reader.nextString());
                }
                reader.endObject();
            } else if (field.equals("ore_members")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String group = reader.nextName();
                    List<String> members = new ArrayList<String>();
                    reader.beginArray();
                    while (reader.hasNext()) {
                        members.add(key(reader.nextString()));
                    }
                    reader.endArray();
                    oreMembers.put(group, members);
                }
                reader.endObject();
            } else if (field.equals("catalysts")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String category = reader.nextName();
                    List<String> keys = new ArrayList<String>();
                    reader.beginArray();
                    while (reader.hasNext()) {
                        keys.add(key(reader.nextString()));
                    }
                    reader.endArray();
                    catalysts.put(category, keys);
                }
                reader.endObject();
            } else if (field.equals("category_mods")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    categoryMods.put(reader.nextName(), reader.nextString());
                }
                reader.endObject();
            } else if (field.equals("dimension_ores")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String ore = key(reader.nextName());
                    reader.beginArray();
                    String dimension = String.valueOf(reader.nextInt());
                    String name = reader.nextString();
                    while (reader.hasNext()) {
                        reader.skipValue();
                    }
                    reader.endArray();
                    dimensionOres.put(ore, new String[] {dimension, name});
                }
                reader.endObject();
            } else if (field.equals("multiblocks")) {
                readMultiblocks(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private Recipe readRecipe(JsonReader reader) throws IOException {
        Recipe recipe = new Recipe();
        List<String> outputKeys = new ArrayList<String>();
        List<Integer> outputQty = new ArrayList<Integer>();
        List<Ingredient> inputs = new ArrayList<Ingredient>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (field.equals("out")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String outKey = null;
                    int qty = 1;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String outField = reader.nextName();
                        if (outField.equals("key")) {
                            outKey = key(reader.nextString());
                        } else if (outField.equals("qty")) {
                            qty = reader.nextInt();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (outKey != null) {
                        outputKeys.add(outKey);
                        outputQty.add(Integer.valueOf(qty));
                    }
                }
                reader.endArray();
            } else if (field.equals("in")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    inputs.add(readIngredient(reader));
                }
                reader.endArray();
            } else if (field.equals("id")) {
                recipe.rid = reader.nextString();
            } else if (field.equals("cat")) {
                recipe.category = reader.nextString();
            } else if (field.equals("src")) {
                recipe.source = reader.nextString();
            } else if (field.equals("machine")) {
                recipe.machine = reader.peek() == JsonToken.NULL ? nullAfter(reader)
                        : reader.nextString();
            } else if (field.equals("xf")) {
                recipe.transfer = reader.nextInt() != 0;
            } else if (field.equals("var")) {
                recipe.variant = reader.nextInt() != 0;
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        recipe.outputKeys = outputKeys.toArray(new String[outputKeys.size()]);
        recipe.outputQty = new int[outputQty.size()];
        for (int i = 0; i < recipe.outputQty.length; i++) {
            recipe.outputQty[i] = outputQty.get(i).intValue();
        }
        recipe.inputs = inputs.toArray(new Ingredient[inputs.size()]);
        return recipe;
    }

    private Ingredient readIngredient(JsonReader reader) throws IOException {
        Ingredient ingredient = new Ingredient();
        ingredient.qty = 1;
        ingredient.role = "item";
        List<String> alternatives = new ArrayList<String>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (field.equals("alt")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String alt = key(reader.nextString());
                    if (!alternatives.contains(alt)) {
                        alternatives.add(alt);
                    }
                }
                reader.endArray();
            } else if (field.equals("qty")) {
                ingredient.qty = reader.nextInt();
            } else if (field.equals("role")) {
                ingredient.role = reader.nextString();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        ingredient.alternatives = alternatives.toArray(new String[alternatives.size()]);
        return ingredient;
    }

    private void readMultiblocks(JsonReader reader) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            Multiblock machine = new Multiblock();
            machine.registryName = reader.nextName();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (field.equals("parts")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        reader.beginArray();
                        machine.partCounts.add(new int[] {reader.nextInt()});
                        List<String> keys = new ArrayList<String>();
                        reader.beginArray();
                        while (reader.hasNext()) {
                            keys.add(key(reader.nextString()));
                        }
                        reader.endArray();
                        machine.partKeys.add(keys.toArray(new String[keys.size()]));
                        while (reader.hasNext()) {
                            reader.skipValue();
                        }
                        reader.endArray();
                    }
                    reader.endArray();
                } else if (field.equals("controller")) {
                    machine.controller = reader.peek() == JsonToken.NULL ? nullAfter(reader)
                            : key(reader.nextString());
                } else if (field.equals("name")) {
                    machine.displayName = reader.nextString();
                } else if (field.equals("slots")) {
                    machine.slots = reader.nextInt();
                } else if (field.equals("blind")) {
                    machine.blind = reader.nextInt();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            multiblocks.add(machine);
        }
        reader.endObject();
    }

    /** The derived indexes `model.Graph` builds lazily, built here so the totals compare. */
    private void index() {
        for (Recipe recipe : recipes) {
            for (String outKey : recipe.outputKeys) {
                List<Recipe> made = byOutput.get(outKey);
                if (made == null) {
                    made = new ArrayList<Recipe>();
                    byOutput.put(outKey, made);
                }
                made.add(recipe);
            }
            Set<String> seen = new HashSet<String>();
            for (Ingredient ingredient : recipe.inputs) {
                for (String alt : ingredient.alternatives) {
                    if (seen.add(alt)) {
                        List<Recipe> used = byInput.get(alt);
                        if (used == null) {
                            used = new ArrayList<Recipe>();
                            byInput.put(alt, used);
                        }
                        used.add(recipe);
                    }
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : oreMembers.entrySet()) {
            boolean world = Keys.isWorldOreGroup(entry.getKey());
            for (String member : entry.getValue()) {
                List<String> groups = oreIndex.get(member);
                if (groups == null) {
                    groups = new ArrayList<String>();
                    oreIndex.put(member, groups);
                }
                groups.add(entry.getKey());
                if (world) {
                    worldOres.add(member);
                }
            }
        }
    }

    private static String nullAfter(JsonReader reader) throws IOException {
        reader.nextNull();
        return null;
    }
}
