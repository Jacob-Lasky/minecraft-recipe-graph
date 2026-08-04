package io.github.jacoblasky.recipedump.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
     * AE2 network contents, read live off the player's ME network by `Ae2Stock` and installed
     * from the client by `PlannerStock` (#191).
     *
     * THE DECLARED CONSTANT IS NOW A FAULT REPORT, not a roadmap entry. It is reached only
     * when NOTHING has installed a reader, which since #191 means the client wiring did not
     * run -- every ordinary not-read-yet case is a reader ANSWERING, and it names which one:
     * nobody has asked, or no terminal, or out of range. Phrased to read as wrong for
     * {@link Status#unavailable}'s reason: a tidy sentence here would look like ordinary UI
     * and nobody would report it.
     *
     * The string this used to carry -- "AE2 stock is not read yet (#19 phase 5)" -- is the one
     * {@link Status#unavailable} quotes as its own example of a message a player cannot act
     * on. DO NOT put a sentence back here that only names a phase.
     */
    HAVE("have", false, "no ME network reader is installed, so this plan assumes you own "
            + "nothing -- the planner should have installed one when the client started"),

    /**
     * AE2 autocraftable patterns. THE JOIN {@link #HAVE} NEEDED NOW EXISTS AND THIS STILL
     * CANNOT USE IT, which is the whole of what is left.
     *
     * `readBy` and `liveDocument` will carry a craftables list the moment there is one to
     * carry; what is missing is the READ. `Ae2StockReader.countsOf` deliberately skips
     * craftable-only entries -- AE2's list carries items the network can autocraft but does
     * not hold, at size zero, and counting those as stock would tell a player they own
     * something they must make -- and `StockSnapshot` has no field to put them in. So this
     * wants a second pass over the grid and a second field on the wire, not a wire.
     */
    CRAFTABLES("craftables", false, "AE2 autocrafting patterns are not read, so this plan "
            + "costs everything as if you had to craft it from raw materials yourself"),

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
     * What a source reports right now: whether it is live, what to say when it is not, and the
     * value it read.
     *
     * ONE ANSWER RATHER THAN SEVERAL LOOKUPS, so a reader cannot get "live" from one call and
     * a stale note or a stale value from another. `StockSnapshot` decides all three at once --
     * a refusal IS the reason and a refusal holds no counts -- and splitting them would let
     * the caveat name a problem the flag says does not exist, or let a plan be priced against
     * numbers from a read that failed. The counts joined the pair in #191, where the planner
     * fetched the value from one place and the flag from another and the two disagreed in the
     * dangerous direction: the client held a snapshot of the network and the `have` field it
     * built beside it was still `{}`.
     */
    public static final class Status {

        private final boolean live;
        private final String note;
        private final JsonElement contents;

        private Status(boolean live, String note, JsonElement contents) {
            this.live = live;
            this.note = note;
            this.contents = contents;
        }

        /**
         * Reading succeeded and there is nothing to contribute -- the field keeps its empty
         * form. For a source that is live because there is genuinely nothing to read, like a
         * setting with no UI to change it.
         */
        public static Status available() {
            return new Status(true, "", null);
        }

        /**
         * Reading succeeded and produced this, which becomes the source's field in the
         * scenario document {@link ScenarioSource#liveDocument} builds.
         *
         * Null collapses to the empty form rather than being written as a JSON null, which
         * `ScenarioInputs` would not read and a fixture never contains.
         */
        public static Status available(JsonElement contents) {
            return new Status(true, "", contents);
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
            // NO CONTENTS, AND THERE IS NO OVERLOAD THAT TAKES ANY. A refusal that carried a
            // value would be the planner pricing a route against numbers from a read that
            // failed, with a caveat above it saying the read did not happen -- which is worse
            // than either half alone, because the caveat makes the wrong number look checked.
            return new Status(false, why == null || why.isEmpty() ? NO_REASON_GIVEN : why, null);
        }

        public boolean live() {
            return live;
        }

        public String note() {
            return note;
        }

        /** What this source read, or null for the field's empty form. */
        public JsonElement contents() {
            return contents;
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
     * `liveDocument` now derives the document from these entries, so a new input is one line
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
     * The scenario document: every field present, and every field an installed reader read
     * filled in.
     *
     * EVERY FIELD PRESENT EVEN WHEN EMPTY, because the shape is the contract. `ScenarioInputs`
     * treats an absent field and an empty one the same, but a fixture states all ten and a
     * document that omitted some would be a different document to read even where it resolves
     * identically. It is also what makes the in-game inputs comparable with a fixture by eye.
     * A source with no reader, or one that refused, contributes its empty form and a caveat,
     * so "empty" and "not read" are told apart by {@link #missingNotes} rather than by a
     * missing key.
     *
     * DERIVED FROM THE READERS RATHER THAN ASSEMBLED BY THE PLANNER. The planner used to add
     * the one field it could fill by name, which meant the second field to go live was a line
     * somebody had to remember to write -- and the failure when they did not was a plan
     * silently costed as though the player owned nothing, which is the exact bug #191 found on
     * `have`. Installing a reader is now the whole of wiring an input up.
     *
     * ONE FIELD IS STILL FILLED BY HAND AND IT IS `pins`. A reader's contents are dropped when
     * its status is unavailable, deliberately, and pins made this session must survive a pin
     * FILE that could not be written -- so {@link PlannerService#liveScenario} adds them after
     * this and says why. DO NOT assume every value here came from a reader.
     */
    public static JsonObject liveDocument() {
        JsonObject out = new JsonObject();
        for (ScenarioSource source : values()) {
            JsonElement read = source.status().contents();
            out.add(source.field(), read == null ? source.emptyForm() : read);
        }
        return out;
    }

    /** An empty `{}` or `[]`, whichever this field is. */
    private JsonElement emptyForm() {
        if (isArray()) {
            return new JsonArray();
        }
        return new JsonObject();
    }

    /**
     * The notes for every source that is not live, in declaration order.
     *
     * WHAT A MISSING INPUT COSTS, WHERE {@link #summary} SAYS ONLY WHICH ONE IS MISSING. The
     * two are halves of one disclosure and only this half is worth acting on: `summary` is
     * built from `source.field`, so a player reads `planned without: have` whichever refusal
     * happened, while this carries the sentence that names the refusal and the thing to go and
     * do about it. Since #191 there are three different answers behind that one field name.
     *
     * DRAWN BY `client.planner.PlanCaveats`, AND IT WAS DRAWN BY NOTHING UNTIL #190. Its only
     * callers were `ScenarioSourceTest` and `PinStoreTest` for long enough that the panel
     * shipped naming five missing inputs with no explanation of any of them, which is a
     * stricter version of the write-only pattern than the one that found it: not a missing
     * feature, a missing ADMISSION of five missing features. If this comes to have no caller
     * outside tests again, that is the defect returning and not a tidy-up opportunity.
     */
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
