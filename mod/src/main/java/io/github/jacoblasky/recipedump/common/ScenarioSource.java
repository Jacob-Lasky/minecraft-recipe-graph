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
     * AE2 network contents. THE READ EXISTS AND THE JOIN DOES NOT (#191).
     * `Ae2StockReader` walks the live grid through `IStorageGrid` server-side and the
     * snapshot reaches the client in `LiveStock` (#150). What is missing is two wires:
     * nothing calls {@link #readBy} for this source, and `PlannerService.liveScenario()`
     * never feeds `LiveStock.latest()` into the `have` field. Until both land the planner
     * still plans against an empty pool, so this stays not-live and says so.
     */
    HAVE("have", false, "AE2 stock is not used by this plan (#191), so it assumes you "
            + "own nothing"),

    /**
     * AE2 autocraftable patterns. Needs the same join as {@link #HAVE} AND a read of its own:
     * `Ae2StockReader.countsOf` deliberately SKIPS craftable-only entries, because counting a
     * pattern as stock would tell a player they already own something they would have to
     * make. So a craftables list is a separate pass, then the same `readBy` plus
     * `liveScenario()` wiring.
     */
    CRAFTABLES("craftables", false, "AE2 autocrafting patterns are not read (#191)"),

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

    /**
     * What a source reports right now: whether it is live, and what to say when it is not.
     *
     * A PAIR RATHER THAN TWO LOOKUPS, so a reader cannot get "live" from one call and a
     * stale note from another. `StockSnapshot` decides both at once -- a refusal IS the
     * reason -- and splitting them would let the caveat name a problem the flag says does
     * not exist.
     */
    public static final class Status {

        private final boolean live;
        private final String note;

        private Status(boolean live, String note) {
            this.live = live;
            this.note = note;
        }

        /** Reading succeeded; the planner is using real data and has nothing to warn about. */
        public static Status available() {
            return new Status(true, "");
        }

        /**
         * Reading did not happen or refused. `why` is shown to the player verbatim.
         *
         * MAKE IT SAY WHAT TO DO. "AE2 stock is not read yet" tells a player nothing they can
         * act on; "no wireless access point in range" tells them to walk toward their base.
         * That difference is the whole reason this takes a string rather than a boolean.
         *
         * AN EMPTY REASON IS REPLACED WITH SOMETHING THAT READS AS WRONG, deliberately, and
         * graphmodel asked for it that way. A reader returning no reason is a bug in the
         * reader; a tidy filler like "not available" hides it behind ordinary-looking UI and
         * nobody reports it, where "reason not given" reads as a fault and gets raised. The
         * alternative -- rendering the empty string -- draws a blank line under the caveat,
         * which looks like a rendering fault rather than a missing input.
         */
        public static Status unavailable(String why) {
            return new Status(false, why == null || why.isEmpty() ? NO_REASON_GIVEN : why);
        }

        public boolean live() {
            return live;
        }

        public String note() {
            return note;
        }
    }

    /**
     * Shown when a reader refuses without saying why. Phrased to read as a FAULT rather than
     * as ordinary UI -- see {@link Status#unavailable}.
     */
    static final String NO_REASON_GIVEN = "reason not given";

    /** Answers for a source that can only know at runtime whether it read anything. */
    public interface Reader {
        /** Called per plan, not cached: a grid can go out of range between two plans. */
        Status status();
    }

    private final String field;
    private final Status declared;

    /**
     * The runtime reader, when something has installed one.
     *
     * VOLATILE, AND DO NOT DROP IT TO A PLAIN FIELD. `PinStore.load` installs one from
     * `CommonProxy.preInit`, which is the mod-loading thread, and {@link #status} is read
     * while a panel is drawing on the client thread; the two share no lock. #191's live-stock
     * reader adds a third writer off a world event. The callers only LOOK single-threaded.
     */
    private volatile Reader reader;

    ScenarioSource(String field, boolean live, String note) {
        this.field = field;
        this.declared = live ? Status.available() : Status.unavailable(note);
    }

    /**
     * Let something answer for this source at runtime instead of the declared constant.
     *
     * WHY A READER RATHER THAN A FLAG THE CALLER FLIPS. Only the thing doing the reading
     * knows whether it read anything: `StockSnapshot` distinguishes "the network is empty"
     * from "there is no network in range" from "you have no wireless terminal", and a caller
     * setting `live = true` after calling it would be re-deriving that from the outside and
     * getting it wrong the first time a read failed. It also means the caveat can name WHICH
     * refusal happened rather than restating a compile-time sentence.
     *
     * `null` restores the declared constant, which is what a world unload should do -- a
     * reader that outlives its world would keep answering for a grid nobody is near.
     */
    public void readBy(Reader newReader) {
        this.reader = newReader;
    }

    /** What this source reports now: the reader's answer, or the declared constant. */
    public Status status() {
        Reader current = reader;
        if (current == null) {
            return declared;
        }
        Status answer = current.status();
        // A reader that returns null is a bug in the reader, and defaulting to "live" would
        // silently claim the input was read. The declared constant is the safe answer: it
        // says not-live, which is the truth about a reader that just failed to answer.
        return answer == null ? declared : answer;
    }

    /** Drop every installed reader. For a world unload, and for test isolation. */
    public static void resetReaders() {
        for (ScenarioSource source : values()) {
            source.reader = null;
        }
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
        return status().live();
    }

    /** What to tell the player, for a source that is not live. Empty when it is. */
    public String note() {
        return status().note();
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
            Status now = source.status();
            if (!now.live() && !now.note().isEmpty()) {
                out.add(now.note());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** One line summarising what the plan could not see, or "" when it saw everything. */
    public static String summary() {
        List<String> missing = new ArrayList<String>();
        for (ScenarioSource source : values()) {
            if (!source.live()) {
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
