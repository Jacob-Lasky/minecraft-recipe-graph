package io.github.jacoblasky.recipedump.plan;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import io.github.jacoblasky.recipedump.graph.IntArray;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.graph.RecipeStore;

/**
 * A human's recipe choice, and how it survives the next dump. Ported from
 * `recipegraph/pins.py`; the reasoning below is that file's and is carried across verbatim
 * where it still applies.
 *
 * WHY THIS EXISTS. Jake: *"i can't reroute a tree's path even though it certainly exists
 * within the graph. but I want to be able to redirect and pick the path and SAVE that path
 * so that it doesn't get overwritten (i'm fine with suggestions)."* Suggestions are welcome,
 * silent overwrites are not: the ranking keeps proposing, and a pin outranks the proposal
 * until it is withdrawn. See #30.
 *
 * THE HARD PART IS THE IDENTITY, NOT THE UI. A recipe id is `hei:&lt;category&gt;:&lt;line number&gt;`
 * or `&lt;jar&gt;!assets/.../file.json`. Neither survives what Jake does often: a redump renumbers
 * every hei line, and a mod update renames the jar. A pin stored by id would silently stop
 * applying, which is the exact failure mode the feature exists to prevent, so a pin is
 * stored by a FINGERPRINT of what the recipe IS.
 *
 * The fingerprint deliberately changes when the pack changes the recipe, because that is
 * when you want the pin to lapse and say so rather than to keep pointing at something that
 * now makes a different thing. And it deliberately does NOT cover the machine name (a
 * localised display string) or the source (which extractor found it), because neither
 * changes what the recipe does.
 *
 * Three outcomes, and the middle one is why the category is stored alongside:
 *
 *   `exact`     the fingerprinted recipe is still here. Use it.
 *   `category`  it is gone, but the pack still makes this item that way. "Make iron by
 *               smelting" is usually what a pin MEANT, so fall back to the category and
 *               say so, rather than silently reverting to the ranking.
 *   `dead`      nothing here makes it that way any more. Report it; change nothing.
 *
 * A fingerprint is not unique and is not meant to be: 437 of the pack's bee mutations are
 * byte-identical recipes, and pinning one of them means any of them will do.
 */
public final class Pins {

    /**
     * Field, slot and alternative separators. Control characters because an item key is
     * `mod:name:meta#digest`, `ore:name` or `fluid:name` and a category uid is dotted: none
     * of them can contain these, so no value can imitate the structure around it.
     *
     * MUST MATCH `pins._FIELD` / `_SLOT` / `_ALT`. `PinsTest` reads that source and asserts
     * it, because a pin file is written by whichever side is running and read by both.
     */
    static final String FIELD = "\u001F";
    static final String SLOT = "\u001E";
    static final String ALT = "\u001D";

    /** Hex digits in a fingerprint. Must match `pins.FINGERPRINT_DIGITS`. */
    public static final int FINGERPRINT_DIGITS = 12;

    public static final String EXACT = "exact";
    public static final String CATEGORY = "category";
    public static final String DEAD = "dead";

    /**
     * The field names of the stored form, spelled ONCE.
     *
     * They were spelled four times: here, in {@link #save}, in `PinStore.document` and again
     * in `ScenarioInputs.resolvePins` -- four hand-written copies of a four-word schema, on
     * the path between a click in the recipe picker and the recipe a plan takes. The failure
     * mode of a mismatch is not an exception: it is a pin that quietly does not apply, which
     * is the one bug the whole pin feature exists to prevent. `PinsTest` asserts these
     * against `recipegraph/pins.py`, which writes the same file.
     */
    static final String PINS_FIELD = "pins";
    static final String FINGERPRINT_FIELD = "fingerprint";
    static final String CATEGORY_FIELD = "category";
    static final String LABEL_FIELD = "label";

    private Pins() {
    }

    /** The stored form of one pin: what recipe, in what category, and what it looked like. */
    public static final class Pin {
        public final String fingerprint;
        public final String category;
        public final String label;

        public Pin(String fingerprint, String category, String label) {
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.category = category == null ? "" : category;
            this.label = label == null ? "" : label;
        }
    }

    /** What became of one pin: {@link #EXACT}, {@link #CATEGORY} or {@link #DEAD}, plus why. */
    public static final class Note {
        public final String state;
        public final String note;

        Note(String state, String note) {
            this.state = state;
            this.note = note;
        }
    }

    /**
     * The result of {@link #resolve}: what the solver may use, and what to tell the player.
     *
     * `accepted` is a SET of recipe ids per item rather than one id, so the category fallback
     * and an exact hit are the same thing to the solver: it keeps its own ranking among
     * whatever is acceptable instead of being handed a recipe picked here by dump order. That
     * also means a fingerprint matching several identical recipes needs no special case.
     *
     * An item with a DEAD pin appears in `notes` and NOT in `accepted`, which is what makes
     * the solver fall back to the ranking without anything being hidden.
     */
    public static final class Resolution {
        public final Map<String, Set<String>> accepted;
        public final Map<String, Note> notes;

        Resolution(Map<String, Set<String>> accepted, Map<String, Note> notes) {
            this.accepted = accepted;
            this.notes = notes;
        }
    }

    /**
     * A short id for WHAT a recipe is, stable across dumps.
     *
     * blake2b rather than the mod's own `DumpCommand.fnv`, which is a cross-language contract
     * about NBT identity: tying pin identity to it would mean a change to the dump's NBT
     * format silently lapsed every pin. These two hashes have nothing to do with each other
     * and must not share an implementation. See {@link Blake2b} for why it is written out
     * here rather than taken from the JDK.
     */
    public static String fingerprint(RecipeGraph g, int recipeId) {
        RecipeStore store = g.recipes();
        List<String> parts = new ArrayList<String>();
        parts.add(categoryOf(g, recipeId));

        // SORTED AS PYTHON SORTS THE TUPLES, not as strings of the rendered form. Python
        // compares `(key, qty)` and `(alternatives, qty, role)` element by element, so the
        // quantity is compared AS A NUMBER; rendering first and sorting the text would order
        // 10 before 2 and silently produce a different fingerprint for any recipe with two
        // outputs of one key or two slots of one ingredient at different counts. Cheap to
        // get right, and impossible to notice when wrong: the pin simply stops matching and
        // reads as the pack having changed the recipe.
        //
        // String ordering itself is safe to leave to `compareTo`: it is by UTF-16 code unit
        // against Python's code point, and the two agree for everything below U+10000. An
        // item key is ASCII.
        List<Sortable> outputs = new ArrayList<Sortable>();
        for (int p = store.outputStart(recipeId); p < store.outputEnd(recipeId); p++) {
            String key = g.key(store.outputKeyAt(p));
            int qty = store.outputQtyAt(p);
            outputs.add(new Sortable(key, qty, "", key + SLOT + qty));
        }
        Collections.sort(outputs);
        for (Sortable output : outputs) {
            parts.add("o" + output.rendered);
        }

        // Sorted, because slot order is an artefact of how the extractor walked the recipe
        // and two dumps of one recipe must not fingerprint differently over it.
        // Alternatives sorted too. Their order carries a meaning elsewhere (the first is the
        // canonical one) but not here: a slot that accepts any of three ores is the same slot
        // whichever one an extractor happened to list first, and letting that reorder lapse a
        // pin would make pins feel arbitrary.
        List<Sortable> slots = new ArrayList<Sortable>();
        for (int slot = store.slotStart(recipeId); slot < store.slotEnd(recipeId); slot++) {
            List<String> alternatives = new ArrayList<String>();
            for (int p = store.altStart(slot); p < store.altEnd(slot); p++) {
                alternatives.add(g.key(store.altKeyAt(p)));
            }
            Collections.sort(alternatives);
            String joined = join(alternatives, ALT);
            int qty = store.slotQty(slot);
            String role = roleOf(g, store.slotRoleId(slot));
            slots.add(new Sortable(joined, qty, role,
                    joined + SLOT + qty + SLOT + role));
        }
        Collections.sort(slots);
        for (Sortable slot : slots) {
            parts.add("i" + slot.rendered);
        }

        return Blake2b.hex(join(parts, FIELD), FINGERPRINT_DIGITS / 2);
    }

    /**
     * One line naming a recipe, for a pin file a human has to be able to read.
     *
     * Stored with the pin rather than looked up when it is shown, so a pin whose recipe has
     * vanished can still say what it used to point at. A dead pin that can only report a hex
     * string is a pin nobody can decide what to do about.
     */
    public static String label(RecipeGraph g, int recipeId) {
        RecipeStore store = g.recipes();
        // The FIRST alternative of each of the first four slots, in authored order, which is
        // why this does not reuse the sorted rendering above: a label is for reading and the
        // canonical alternative is the one worth showing.
        // THE FIRST FOUR SLOTS, then those with alternatives -- not the first four slots
        // that HAVE alternatives. Python is `for ing in recipe.inputs[:4] if
        // ing.alternatives`, which slices before it filters, so a recipe whose first slot is
        // empty shows three ingredients and not four. Reading it the other way round changes
        // the stored label of every such pin, and the label is the only thing a player has
        // to recognise a dead pin by.
        List<String> ins = new ArrayList<String>();
        int slotStart = store.slotStart(recipeId);
        int slotEnd = store.slotEnd(recipeId);
        int window = Math.min(slotEnd, slotStart + 4);
        for (int slot = slotStart; slot < window; slot++) {
            if (store.altStart(slot) < store.altEnd(slot)) {
                ins.add(g.bareName(store.altKeyAt(store.altStart(slot))));
            }
        }
        String joined = join(ins, ", ");
        if (slotEnd - slotStart > 4) {
            joined += ", ...";
        }
        String out = store.outputStart(recipeId) < store.outputEnd(recipeId)
                ? g.bareName(store.outputKeyAt(store.outputStart(recipeId)))
                : "?";
        return out + " from " + (joined.isEmpty() ? "nothing" : joined);
    }

    /** The stored form of a pin on `recipeId`. */
    public static Pin make(RecipeGraph g, int recipeId) {
        return new Pin(fingerprint(g, recipeId), categoryOf(g, recipeId), label(g, recipeId));
    }

    /**
     * Match each stored pin against what the graph holds now.
     *
     * Keyed by item KEY rather than by key id, matching `pins.resolve`, because a pin file
     * outlives the graph that produced it: an item the pack has removed has no id at all, and
     * it still has to come back as a DEAD note naming what the player pinned. Resolving to
     * ids here would drop it silently, which is the one thing this whole file exists to
     * prevent.
     */
    public static Resolution resolve(RecipeGraph g, Map<String, Pin> pins) {
        Map<String, Set<String>> accepted = new LinkedHashMap<String, Set<String>>();
        Map<String, Note> notes = new LinkedHashMap<String, Note>();
        IntArray candidates = new IntArray();
        for (Map.Entry<String, Pin> entry : pins.entrySet()) {
            String key = entry.getKey();
            Pin pin = entry.getValue();
            candidates.clear();
            int keyId = g.keyId(key);
            if (keyId >= 0) {
                g.realProducers(keyId, candidates);
            }

            Set<String> exact = new LinkedHashSet<String>();
            Set<String> sameCategory = new LinkedHashSet<String>();
            for (int i = 0; i < candidates.size(); i++) {
                int recipeId = candidates.get(i);
                if (fingerprint(g, recipeId).equals(pin.fingerprint)) {
                    exact.add(g.recipes().rid(recipeId));
                }
                if (categoryOf(g, recipeId).equals(pin.category)) {
                    sameCategory.add(g.recipes().rid(recipeId));
                }
            }

            if (!exact.isEmpty()) {
                accepted.put(key, exact);
                notes.put(key, new Note(EXACT, ""));
            } else if (!sameCategory.isEmpty()) {
                accepted.put(key, sameCategory);
                notes.put(key, new Note(CATEGORY,
                        "the pinned recipe is gone; using another " + pin.category
                                + " recipe"));
            } else {
                notes.put(key, new Note(DEAD, "nothing here makes this "
                        + (pin.category.isEmpty() ? "way" : pin.category) + " any more"));
            }
        }
        return new Resolution(accepted, notes);
    }

    /**
     * `{item key: pin}` from disk. NEVER RAISES: a broken file must not break planning.
     *
     * Reads the file `recipegraph/pins.py` writes, deliberately, because both sides are live
     * until #19 phase 6 retires the Python UI -- a player who pins from the web page and then
     * opens the in-game planner has to see the same choice. Anything malformed is skipped
     * rather than rejected, entry by entry: one bad pin costs that pin and not the file.
     *
     * PREFER {@link #read} WHEN SOMEBODY IS WATCHING. This form cannot distinguish "no pins"
     * from "your pin file is corrupt and every choice you made is being ignored", and the
     * two look identical to a player: the plan simply takes a route they thought they had
     * ruled out, with nothing on screen to explain it. Keep this overload for the offline
     * paths, where returning `{}` really is the whole answer.
     */
    public static Map<String, Pin> load(File path) {
        return read(path).pins;
    }

    /**
     * What {@link #read} found, and what it had to throw away to find it.
     *
     * `problem` is empty when the file was read cleanly OR was simply absent -- no pin file
     * is the normal state and not a fault. It is non-empty only when something WAS there and
     * did not survive, which is the case the silent load cannot express.
     */
    public static final class Loaded {

        public final Map<String, Pin> pins;

        /** Empty when nothing went wrong. Otherwise a sentence to show a player verbatim. */
        public final String problem;

        Loaded(Map<String, Pin> pins, String problem) {
            this.pins = Collections.unmodifiableMap(pins);
            this.problem = problem;
        }
    }

    /**
     * {@link #load}, and a sentence about anything the file lost on the way in.
     *
     * SAME PARSE, NOT A SECOND ONE. The report is a by-product of the read that already
     * happens; a separate "is this file healthy" probe would be a second spelling of the
     * predicate, free to disagree with the one whose answer is actually used.
     *
     * Python has no counterpart and does not need one -- `pins.load` is called by a CLI that
     * can print to a terminal, whereas this one is called by a game with a caveat line and no
     * console anybody reads. The PARSING RULES are the contract and those still match
     * `pins.load` exactly, entry for entry; only the reporting is extra.
     */
    public static Loaded read(File path) {
        Map<String, Pin> out = new LinkedHashMap<String, Pin>();
        if (path == null || !path.isFile()) {
            return new Loaded(out, "");
        }
        JsonObject doc;
        try {
            InputStreamReader reader = new InputStreamReader(
                    Files.newInputStream(path.toPath()), StandardCharsets.UTF_8);
            try {
                JsonElement parsed = new JsonParser().parse(reader);
                doc = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } finally {
                reader.close();
            }
        } catch (RuntimeException | IOException broken) {
            // Matches Python catching (ValueError, OSError): unreadable and unparseable are
            // the same answer here, which is "you have no pins", not a crash on the way to a
            // plan the player asked for. Said out loud, though, because a file that exists
            // and cannot be read is not the same situation as not having one.
            return new Loaded(out, "cannot read " + path.getName() + ": "
                    + broken.getClass().getSimpleName()
                    + (broken.getMessage() == null ? "" : ": " + broken.getMessage()));
        }
        if (doc == null || !doc.has(PINS_FIELD) || !doc.get(PINS_FIELD).isJsonObject()) {
            return new Loaded(out, path.getName() + " has no `" + PINS_FIELD + "` object; "
                    + "every recipe choice in it is being ignored");
        }
        JsonObject stored = doc.getAsJsonObject(PINS_FIELD);
        out.putAll(fromJson(stored));
        int skipped = stored.entrySet().size() - out.size();
        return new Loaded(out, skipped == 0 ? "" : skipped + " recipe choice(s) in "
                + path.getName() + " name no fingerprint and are being ignored");
    }

    /**
     * `{item key: pin}` out of the `pins` object of a pin file or a scenario document.
     *
     * THE ONE READER OF THE STORED SHAPE, and it has to be, because the shape travels: it is
     * written to disk by two languages and passed through a scenario document to the solver.
     * `ScenarioInputs.resolvePins` used to parse it a second time and the copy had drifted --
     * it called `getAsJsonObject()` with no `isJsonObject()` guard, so a hand-edited file with
     * one entry that is a string threw an IllegalStateException from inside a plan, where this
     * one skips it. Neither behaviour is wrong in isolation; having both is.
     *
     * SKIPS RATHER THAN REJECTS, entry by entry, matching `pins.load`: one bad pin costs that
     * pin and not the file. Callers that need to know how many were skipped compare sizes.
     */
    public static Map<String, Pin> fromJson(JsonObject stored) {
        Map<String, Pin> out = new LinkedHashMap<String, Pin>();
        if (stored == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : stored.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject pin = entry.getValue().getAsJsonObject();
            // The fingerprint is the only REQUIRED field, exactly as in Python: a pin without
            // one identifies nothing, while a missing category or label merely reads worse.
            if (!pin.has(FINGERPRINT_FIELD) || !pin.get(FINGERPRINT_FIELD).isJsonPrimitive()) {
                continue;
            }
            out.put(entry.getKey(), new Pin(pin.get(FINGERPRINT_FIELD).getAsString(),
                    optionalString(pin, CATEGORY_FIELD), optionalString(pin, LABEL_FIELD)));
        }
        return out;
    }

    /**
     * The counterpart of {@link #fromJson}: the `pins` object a scenario document carries.
     *
     * NOT USED BY {@link #save}, which writes through a `JsonWriter` to control the key order
     * and the indent -- see that method for why those are a contract rather than a style. The
     * FIELD NAMES are shared, which is the part that can silently break a pin.
     */
    public static JsonObject toJson(Map<String, Pin> pins) {
        JsonObject out = new JsonObject();
        if (pins == null) {
            return out;
        }
        for (Map.Entry<String, Pin> entry : pins.entrySet()) {
            Pin pin = entry.getValue();
            JsonObject one = new JsonObject();
            one.addProperty(FINGERPRINT_FIELD, pin.fingerprint);
            one.addProperty(CATEGORY_FIELD, pin.category);
            one.addProperty(LABEL_FIELD, pin.label);
            out.add(entry.getKey(), one);
        }
        return out;
    }

    /**
     * Write `pins` where {@link #load} and `pins.save` will both read them.
     *
     * SORTED KEYS AND ONE-SPACE INDENT, matching Python's `json.dump(..., indent=1,
     * sort_keys=True)`. Not cosmetic: this file is hand-edited and lives beside a user's data,
     * so a side that reformatted it on every write would make every save a whole-file diff and
     * hide the one line that actually changed.
     */
    public static void save(File path, Map<String, Pin> pins) throws IOException {
        File parent = path.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        Writer out = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(path.toPath()), StandardCharsets.UTF_8));
        try {
            JsonWriter w = new JsonWriter(out);
            w.setIndent(" ");
            w.beginObject();
            w.name("_comment").value(SAVE_COMMENT);
            w.name(PINS_FIELD).beginObject();
            for (Map.Entry<String, Pin> entry : new TreeMap<String, Pin>(pins).entrySet()) {
                Pin pin = entry.getValue();
                w.name(entry.getKey()).beginObject();
                // ALPHABETICAL, because Python's `json.dump(..., sort_keys=True)` sorts the
                // inner objects too. Not the declaration order above it.
                w.name(CATEGORY_FIELD).value(pin.category);
                w.name(FINGERPRINT_FIELD).value(pin.fingerprint);
                w.name(LABEL_FIELD).value(pin.label);
                w.endObject();
            }
            w.endObject();
            w.endObject();
            w.flush();
        } finally {
            out.close();
        }
    }

    /** Kept verbatim from `pins.save`, so neither side rewrites the other's header. */
    private static final String SAVE_COMMENT =
            "Recipe choices you made by hand. Keyed by item; `fingerprint` identifies the "
            + "recipe by its category, outputs and inputs, so a pin survives a redump "
            + "renumbering every recipe id. If the pack changes the recipe the fingerprint "
            + "stops matching and the pin falls back to `category`, which the UI reports.";

    private static String optionalString(JsonObject pin, String name) {
        return pin.has(name) && pin.get(name).isJsonPrimitive()
                ? pin.get(name).getAsString() : "";
    }

    /**
     * The category name, never null.
     *
     * `pins.fingerprint` starts from `recipe.category or ""`, so a recipe with no category
     * has to render as the empty string and not as "null" -- the fingerprint is compared
     * across languages and a Java-flavoured null would lapse every pin on such a recipe.
     */
    private static String categoryOf(RecipeGraph g, int recipeId) {
        String category = g.categoryName(g.recipes().categoryId(recipeId));
        return category == null ? "" : category;
    }

    /**
     * The slot's role, which is `"item"` and never empty for a slot that did not name one.
     *
     * Python's `pins.fingerprint` writes `ing.role or ""`, which LOOKS like it can produce
     * an empty string and cannot: `Ingredient.__init__` and `Ingredient.from_json` both
     * default `role` to `"item"`, and `to_json` omits the field only when it already equals
     * `"item"`. `GraphBuilder.beginSlot` normalises null the same way. Verified on both
     * sides, because a Java side that rendered `""` here where Python renders `"item"` would
     * fingerprint every plain item slot differently and lapse every pin in the file.
     */
    private static String roleOf(RecipeGraph g, int roleId) {
        String role = g.roleName(roleId);
        return role == null ? "item" : role;
    }

    /**
     * One element of a Python sort key, compared the way Python compares the tuple.
     *
     * `rendered` is carried alongside rather than recomputed, so the thing that gets hashed
     * is provably the thing that got sorted.
     */
    private static final class Sortable implements Comparable<Sortable> {
        private final String first;
        private final int qty;
        private final String last;
        final String rendered;

        Sortable(String first, int qty, String last, String rendered) {
            this.first = first;
            this.qty = qty;
            this.last = last;
            this.rendered = rendered;
        }

        @Override
        public int compareTo(Sortable other) {
            int cmp = first.compareTo(other.first);
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(qty, other.qty);
            return cmp != 0 ? cmp : last.compareTo(other.last);
        }
    }

    /** `String.join` is Java 8, but this file is read against a Python original; keep it obvious. */
    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
