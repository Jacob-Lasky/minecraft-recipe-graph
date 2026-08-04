package io.github.jacoblasky.recipedump.graph;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Loads `graph.json` into a {@link RecipeGraph} without ever holding the document.
 *
 * STREAMING IS NOT AN OPTIMISATION HERE, IT IS THE ONLY WAY IN. `graph.json` is 110 MB, and
 * a DOM parse of it -- `new JsonParser().parse(reader)` -- materialises a `JsonObject` per
 * recipe, a `JsonArray` per ingredient list and a `JsonPrimitive` wrapping a `String` per
 * key occurrence, all alive at once BEFORE anything is copied into the compact model. That
 * peak is several times the size of the finished graph, and it is a peak a Minecraft client
 * does not have spare. DO NOT swap this for a DOM parse or a reflective binding; the
 * measured retained size of the model says nothing about whether the load survives.
 *
 * The reader is deliberately tolerant of unknown fields and missing sections, because
 * nothing here is owed backwards compatibility (single user, single install, nothing
 * published) and the dump format is expected to change shape outright. What it is NOT
 * tolerant of is a field whose TYPE has changed, which would be a silently wrong graph.
 */
public final class GraphJsonReader {

    /**
     * Sizing hints derived from the file size.
     *
     * Measured on the reference pack: 110 MB of JSON produces ~300,000 keys and ~11 MB of
     * key bytes, so one key per ~370 bytes of file. A hint being wrong costs a rehash, not
     * a failure, so these are deliberately rough -- their job is to keep a cold load off the
     * rehash path, not to be right.
     */
    private static final long BYTES_PER_KEY = 370L;
    private static final long BYTES_PER_RECIPE = 950L;

    private GraphJsonReader() {
    }

    public static RecipeGraph read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return read(in, file.length());
        } finally {
            in.close();
        }
    }

    public static RecipeGraph read(InputStream in, long expectedBytes) throws IOException {
        int keyHint = (int) Math.max(1024, Math.min(1 << 22, expectedBytes / BYTES_PER_KEY));
        int recipeHint =
                (int) Math.max(256, Math.min(1 << 22, expectedBytes / BYTES_PER_RECIPE));
        GraphBuilder builder = new GraphBuilder(keyHint, keyHint * 36, recipeHint,
                recipeHint * 80, keyHint, keyHint * 8);
        // 1 MB of buffer, because the default 8 KB turns a 110 MB read into 14,000 syscalls
        // against a FUSE mount, where each one is far dearer than on a local disk.
        JsonReader reader = new JsonReader(new InputStreamReader(
                new BufferedInputStream(in, 1 << 20), "UTF-8"));
        try {
            readGraph(reader, builder);
        } finally {
            reader.close();
        }
        return builder.build();
    }

    private static void readGraph(JsonReader reader, GraphBuilder builder) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (field.equals("recipes")) {
                readRecipes(reader, builder);
            } else if (field.equals("names")) {
                readNames(reader, builder);
            } else if (field.equals("ore_members")) {
                readOreMembers(reader, builder);
            } else if (field.equals("ore_guessed")) {
                readOreGuessed(reader, builder);
            } else if (field.equals("catalysts")) {
                readCatalysts(reader, builder);
            } else if (field.equals("category_mods")) {
                readCategoryMods(reader, builder);
            } else if (field.equals("dimension_ores")) {
                readDimensionOres(reader, builder);
            } else if (field.equals("multiblocks")) {
                readMultiblocks(reader, builder);
            } else if (field.equals("max_damage")) {
                readMaxDamage(reader, builder);
            } else if (field.equals("emc")) {
                readEmc(reader, builder);
            } else if (field.equals("blueprint_machines")) {
                readBlueprintMachines(reader, builder);
            } else if (field.equals("machine_names")) {
                readMachineNames(reader, builder);
            } else if (field.equals("icons")) {
                readIcons(reader, builder);
            } else if (field.equals("dump_schema")) {
                builder.dumpSchema(reader.peek() == JsonToken.NULL ? zeroAfterNull(reader)
                        : reader.nextInt());
            } else if (field.equals("dump_version")) {
                builder.dumpVersion(nextStringOrNull(reader));
            } else if (field.equals("instance_dir")) {
                builder.instanceDir(nextStringOrNull(reader));
            } else {
                // Unknown top-level section. Skipped rather than rejected: the dump format is
                // free to grow, and a reader that refuses to load a graph carrying a field it
                // does not use is a self-inflicted outage.
                reader.skipValue();
            }
        }
        reader.endObject();
    }

    private static void readRecipes(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            readRecipe(reader, builder);
        }
        reader.endArray();
    }

    private static void readRecipe(JsonReader reader, GraphBuilder builder)
            throws IOException {
        builder.beginRecipe();
        String rid = null;
        String category = null;
        String machine = null;
        String source = null;
        boolean transfer = false;
        boolean variant = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (field.equals("out")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    String key = null;
                    int qty = 1;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String outField = reader.nextName();
                        if (outField.equals("key")) {
                            key = reader.nextString();
                        } else if (outField.equals("qty")) {
                            qty = reader.nextInt();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (key != null) {
                        builder.output(builder.key(key), qty);
                    }
                }
                reader.endArray();
            } else if (field.equals("in")) {
                readSlots(reader, builder);
            } else if (field.equals("id")) {
                rid = reader.nextString();
            } else if (field.equals("cat")) {
                category = reader.nextString();
            } else if (field.equals("src")) {
                source = reader.nextString();
            } else if (field.equals("machine")) {
                machine = nextStringOrNull(reader);
            } else if (field.equals("xf")) {
                transfer = reader.nextInt() != 0;
            } else if (field.equals("var")) {
                variant = reader.nextInt() != 0;
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        builder.endRecipe(rid, category, machine, source, transfer, variant);
    }

    /**
     * One input slot per element, each with its alternatives.
     *
     * The alternatives are deduplicated HERE, keeping first-seen order, because
     * `model.Ingredient.__init__` does the same and the first alternative is the canonical
     * one. Doing it at read time rather than in the store keeps the store a dumb container.
     */
    private static void readSlots(JsonReader reader, GraphBuilder builder) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            int qty = 1;
            String role = null;
            // 1.0f, NOT 0.0f: absent `p` means fully consumed, and this default is the only
            // reason a graph written before #175 reads identically. Getting it backwards would
            // make every slot in every old graph a permanent requirement.
            float consumeChance = 1.0f;
            IntArray alternatives = new IntArray(4);
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (field.equals("alt")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull();
                            continue;
                        }
                        int key = builder.key(reader.nextString());
                        boolean seen = false;
                        for (int i = 0; i < alternatives.size() && !seen; i++) {
                            seen = alternatives.get(i) == key;
                        }
                        if (!seen) {
                            alternatives.add(key);
                        }
                    }
                    reader.endArray();
                } else if (field.equals("qty")) {
                    qty = reader.nextInt();
                } else if (field.equals("role")) {
                    role = nextStringOrNull(reader);
                } else if (field.equals("p")) {
                    consumeChance = (float) reader.nextDouble();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            builder.beginSlot(qty, role, consumeChance);
            for (int i = 0; i < alternatives.size(); i++) {
                builder.alternative(alternatives.get(i));
            }
            builder.endSlot();
        }
        reader.endArray();
    }

    private static void readNames(JsonReader reader, GraphBuilder builder) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            String label = nextStringOrNull(reader);
            if (label != null) {
                builder.name(builder.key(key), label);
            }
        }
        reader.endObject();
    }

    private static void readOreMembers(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            builder.beginOreGroup(reader.nextName());
            reader.beginArray();
            while (reader.hasNext()) {
                builder.oreMember(builder.key(reader.nextString()));
            }
            reader.endArray();
            builder.endOreGroup();
        }
        reader.endObject();
    }

    private static void readOreGuessed(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            builder.markOreGuessed(reader.nextString());
        }
        reader.endArray();
    }

    private static void readCatalysts(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            builder.beginCatalyst(reader.nextName());
            reader.beginArray();
            while (reader.hasNext()) {
                builder.catalystKey(builder.key(reader.nextString()));
            }
            reader.endArray();
            builder.endCatalyst();
        }
        reader.endObject();
    }

    private static void readCategoryMods(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            String category = reader.nextName();
            String mod = nextStringOrNull(reader);
            if (mod != null) {
                builder.categoryMod(category, mod);
            }
        }
        reader.endObject();
    }

    private static void readDimensionOres(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            int key = builder.key(reader.nextName());
            reader.beginArray();
            int dimensionId = reader.nextInt();
            String name = reader.nextString();
            while (reader.hasNext()) {
                reader.skipValue();
            }
            reader.endArray();
            builder.dimensionOre(key, dimensionId, name);
        }
        reader.endObject();
    }

    private static void readMultiblocks(JsonReader reader, GraphBuilder builder)
            throws IOException {
        Multiblocks.Builder machines = builder.multiblocks();
        reader.beginObject();
        while (reader.hasNext()) {
            String registryName = reader.nextName();
            machines.beginMachine();
            String displayName = null;
            int controller = -1;
            int slots = 0;
            int blind = 0;
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (field.equals("parts")) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        // Each part is the two-element pair [count, [keys...]].
                        reader.beginArray();
                        machines.beginPart(reader.nextInt());
                        reader.beginArray();
                        while (reader.hasNext()) {
                            machines.addPartAlternative(builder.key(reader.nextString()));
                        }
                        reader.endArray();
                        machines.endPart();
                        while (reader.hasNext()) {
                            reader.skipValue();
                        }
                        reader.endArray();
                    }
                    reader.endArray();
                } else if (field.equals("controller")) {
                    String key = nextStringOrNull(reader);
                    controller = key == null ? -1 : builder.key(key);
                } else if (field.equals("name")) {
                    displayName = nextStringOrNull(reader);
                } else if (field.equals("slots")) {
                    slots = reader.nextInt();
                } else if (field.equals("blind")) {
                    blind = reader.nextInt();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            machines.endMachine(registryName, displayName, controller, slots, blind);
        }
        reader.endObject();
    }

    private static void readMaxDamage(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            // Keyed by the UNDAMAGED item, which is the key the registry reports against and
            // the key `damageBase` collapses a worn one onto.
            int stem = builder.key(reader.nextName());
            builder.damageable(stem, reader.nextInt());
        }
        reader.endObject();
    }

    private static void readEmc(JsonReader reader, GraphBuilder builder) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            int key = builder.key(reader.nextName());
            // `nextLong`, not `nextInt`: the largest EMC value on the reference pack is
            // 422,212,465,065,984. It is also not `nextDouble` -- ProjectE's EMC is integral,
            // and reading a fractional value would be a format change worth failing on rather
            // than silently rounding.
            builder.emc(key, reader.nextLong());
        }
        reader.endObject();
    }

    private static void readBlueprintMachines(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            int blueprint = builder.key(reader.nextName());
            String machine = nextStringOrNull(reader);
            if (machine != null) {
                builder.blueprints().blueprint(blueprint, machine);
            }
        }
        reader.endObject();
    }

    private static void readMachineNames(JsonReader reader, GraphBuilder builder)
            throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            // NOT interned as a graph key: an MM registry name is not an item id, and putting
            // one in the key table would make it findable as an item that does not exist.
            String machine = reader.nextName();
            String localized = nextStringOrNull(reader);
            if (localized != null) {
                builder.blueprints().machineName(machine, localized);
            }
        }
        reader.endObject();
    }

    /**
     * The icon atlas INDEX. The PNG pages live beside `graph.json`, never inside it.
     *
     * Empty (`{}`) on every graph built so far, because the atlas needs a client launch that
     * has not happened. Read anyway rather than skipped, so the day one appears it costs no
     * code change -- and so the heap report prices it the first time it is real.
     */
    private static void readIcons(JsonReader reader, GraphBuilder builder) throws IOException {
        int iconSize = 0;
        int columns = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (field.equals("icon")) {
                // The sprite EDGE LENGTH in pixels, not a filename. `keys` carries column and
                // row rather than pixel offsets precisely so the reader multiplies by this
                // and cannot disagree with the writer about the sprite size.
                iconSize = reader.nextInt();
            } else if (field.equals("cols")) {
                columns = reader.nextInt();
            } else if (field.equals("pages")) {
                reader.beginArray();
                while (reader.hasNext()) {
                    builder.icons().page(reader.nextString());
                }
                reader.endArray();
            } else if (field.equals("keys")) {
                reader.beginObject();
                while (reader.hasNext()) {
                    int key = builder.key(reader.nextName());
                    reader.beginArray();
                    int page = reader.nextInt();
                    int column = reader.nextInt();
                    int row = reader.nextInt();
                    while (reader.hasNext()) {
                        reader.skipValue();
                    }
                    reader.endArray();
                    builder.icons().at(key, page, column, row);
                }
                reader.endObject();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        builder.icons().sheet(iconSize, columns);
    }

    private static String nextStringOrNull(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextString();
    }

    /** Consumes an explicit `null` where an int was expected and reads it as 0. */
    private static int zeroAfterNull(JsonReader reader) throws IOException {
        reader.nextNull();
        return 0;
    }
}
