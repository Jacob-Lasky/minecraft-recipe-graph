package io.github.jacoblasky.recipedump.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Every input a plan takes besides the graph, and whether the game can supply it yet.
 *
 * WHY AN EXPLICIT LIST RATHER THAN AN EMPTY SCENARIO. A scenario field the mod cannot read is
 * not neutral: an empty `have` is the claim "you own nothing", and a plan built on it tells a
 * player to go and get 377 iron ingots that are sitting in their ME system. That is not a
 * missing feature, it is a WRONG ANSWER delivered with the same confidence as a right one,
 * and it is the exact shape of the stranded-stock bug the Python side spent #64 on -- where
 * the tool "plans as though you own none of it" and the symptom is being told to make
 * something you already have.
 *
 * So each input says whether it is live, and the planner shows the ones that are not. The
 * player then knows the plan is a lower bound on what they must gather rather than an answer.
 *
 * THIS IS A CONTRACT WITH THE FIXTURES, NOT A TODO LIST. The names are the `scenario` keys in
 * `tests/fixtures/plan/*.json`, so "which inputs is the game supplying" and "which inputs
 * does a fixture set" are the same question with the same vocabulary, and a fixture can be
 * reproduced in game by reading this list. `ScenarioSourceTest` asserts the names match a
 * committed fixture rather than trusting them to be copied correctly.
 */
public enum ScenarioSource {

    /**
     * AE2 network contents. Phase 5 reads the live grid through `IStorageGrid`; until then
     * the planner assumes an empty pool and says so.
     */
    HAVE("have", false, "AE2 stock is not read yet (#19 phase 5), so this plan assumes you "
            + "own nothing"),

    /** AE2 autocraftable patterns. Same grid read, same phase. */
    CRAFTABLES("craftables", false, "AE2 autocrafting patterns are not read yet (#19 phase 5)"),

    /**
     * Placed tile entities, which decide machine availability and infinite sources. A world
     * scan, and the offline tool does it by reading region files -- neither is a thing to do
     * on a render thread, so it needs its own design rather than a quick call.
     */
    PLACED("placed", false, "placed machines and generators are not scanned yet, so every "
            + "machine reads as buildable rather than owned"),

    /**
     * Which dimensions have terrain. Read from the save's region directories, so the server
     * knows and a connected client does not; needs a packet or a server-side plan.
     */
    VISITED_DIMENSIONS("visited_dimensions", false,
            "visited dimensions are not known client-side, so no trip is priced (#112)"),

    /**
     * ProjectE knowledge. It IS a capability on the player and therefore reachable, but
     * reading it means a soft dependency on ProjectE's API and a fallback for packs without
     * it. Honest to declare rather than half-read: an empty knowledge set silently disables
     * #50's terminator, which is the difference between "you can transmute this" and a
     * subtree telling you to go and farm a dungeon.
     */
    EMC_KNOWLEDGE("emc_knowledge", false,
            "ProjectE knowledge is not read yet, so nothing terminates on EMC (#50)"),

    /**
     * Hand-set machine states. Genuinely empty rather than unavailable -- there is no UI to
     * set one, so "the player has set none" is the truth and not a gap.
     */
    MACHINE_OVERRIDES("machine_overrides", true, ""),

    /** Same: no UI, so an empty list is the honest answer rather than a missing read. */
    NO_MACHINE("no_machine", true, ""),

    SOURCE_OVERRIDES("source_overrides", true, ""),

    TOKEN_OVERRIDES("token_overrides", true, ""),

    /**
     * Pinned recipe choices. Empty today and NOT a gap in the same sense: the plan book
     * capability already syncs to the client, so this is the first of the unavailable inputs
     * that becomes available without new world reading.
     */
    PINS("pins", true, "");

    private final String field;
    private final boolean live;
    private final String note;

    ScenarioSource(String field, boolean live, String note) {
        this.field = field;
        this.live = live;
        this.note = note;
    }

    /**
     * True when this field is a JSON array rather than an object.
     *
     * HERE RATHER THAN IN THE BUILDER, so there is ONE list of the scenario fields. The
     * planner used to restate all ten when building its document, with a test asserting the
     * two lists matched -- which catches the drift but only after someone has written it, and
     * says nothing about the field that was added to both and typed wrongly in one.
     * `emptyDocument` now derives the document from these entries, so a new input is one line
     * here and correct everywhere.
     */
    public boolean isArray() {
        return this == CRAFTABLES || this == NO_MACHINE;
    }

    /** The `scenario` key in a plan fixture. */
    public String field() {
        return field;
    }

    /** True when the game supplies this for real, false when the planner is guessing. */
    public boolean live() {
        return live;
    }

    /** What to tell the player, for a source that is not live. Empty when it is. */
    public String note() {
        return note;
    }

    /**
     * A scenario document with every field present and empty.
     *
     * EVERY FIELD PRESENT, because the shape is the contract: `ScenarioInputs` treats an
     * absent field and an empty one the same, but a fixture states all ten and a document
     * that omitted some would be a different document to read even where it resolves the
     * same. It is also what makes the in-game inputs comparable with a fixture by eye.
     */
    public static JsonObject emptyDocument() {
        JsonObject out = new JsonObject();
        for (ScenarioSource source : values()) {
            if (source.isArray()) {
                out.add(source.field(), new JsonArray());
            } else {
                out.add(source.field(), new JsonObject());
            }
        }
        return out;
    }

    /** The notes for every source that is not live, in declaration order. */
    public static List<String> missingNotes() {
        List<String> out = new ArrayList<String>();
        for (ScenarioSource source : values()) {
            if (!source.live && !source.note.isEmpty()) {
                out.add(source.note);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** One line summarising what the plan could not see, or "" when it saw everything. */
    public static String summary() {
        List<String> missing = new ArrayList<String>();
        for (ScenarioSource source : values()) {
            if (!source.live) {
                missing.add(source.field);
            }
        }
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("planned without: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(missing.get(i));
        }
        return sb.toString();
    }
}
