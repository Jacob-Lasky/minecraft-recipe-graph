package io.github.jacoblasky.recipedump.common.ae2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.common.ScenarioSource;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * What the player's ME network holds right now, or a named reason it could not be read.
 *
 * <h2>A REFUSAL IS NOT AN EMPTY INVENTORY, and conflating them is the whole point of this
 * type</h2>
 *
 * The planner prices every route against what you own. Hand it an empty map when the network
 * simply could not be reached and it does not fail -- it produces a confident, complete,
 * entirely wrong plan telling you to craft 4,000 things you already have on a shelf. That is
 * strictly worse than refusing, because a refusal is visible and a wrong plan is not.
 *
 * So there is no "empty" state that means both. A snapshot either {@link #isAvailable} and
 * carries counts, or it carries a {@link Reason} and the caller must not plan against it.
 * An available snapshot MAY legitimately be empty -- a freshly built network really does hold
 * nothing -- and that is a different statement from any of the refusals.
 *
 * <h2>Keys are the dump's keys, digest and all</h2>
 *
 * Counts are keyed exactly as `graph.json` keys them, because they are built by handing the
 * live `ItemStack` to {@link io.github.jacoblasky.recipedump.DumpCommand#stackKey} -- the one
 * place that format exists. That is the single biggest advantage of reading the network live
 * over reading the world save: the offline reader has to RECONSTRUCT the NBT digest from
 * region-file bytes and re-derive what the dump would have written, and every schema change
 * has silently stranded stock that way. Here the stack is the stack.
 *
 * <h2>TWO MAPS, ONE READ, ONE REASON. #320</h2>
 *
 * A snapshot carries what the network HOLDS and what the read SAW PLACED, and the two share a
 * single {@link Reason} because they come out of one call behind one gate: no terminal, no range,
 * no power or no permission refuses both at once. Splitting the availability would be inventing a
 * state the read cannot produce -- there is no way to succeed at the items and fail at the
 * machines -- and it would let a caller price a plan against machines from a read whose stock half
 * had refused.
 *
 * THE PLACED HALF NOW HAS TWO SOURCES AND STILL ONE REASON, which is why nothing above changes.
 * `Ae2StockReader.placedOf` walks the grid's nodes (#320 phase 1) and
 * {@link io.github.jacoblasky.recipedump.common.PlacedTileEntities} scans each loaded world's tile
 * entities (#320 phase 2) -- including formed Modular Machinery controllers -- and both merge into
 * the one map. A count is a count: `Machines.candidateVerdict` tests membership and quotes the key,
 * never the source, so there is nothing a per-source field on the wire could tell a reader.
 *
 * STILL NARROWER THAN THE OFFLINE TOOL'S, ON ONE AXIS. `recipegraph have` scans region files and
 * sees every tile entity in the save, LOADED OR NOT; this sees the grid plus whatever chunks the
 * server currently has in memory. {@link ScenarioSource#PLACED} is where that scope is argued and
 * `client.planner.PlanCaveats.PLACED_SCOPE` is where it is written down for the player.
 */
public final class StockSnapshot {

    /**
     * Why a network could not be read. Every one is a different thing to go and do.
     *
     * <h2>TWO STRINGS PER REASON, AND THE SPLIT IS A PIXEL BUDGET RATHER THAN A STYLE</h2>
     *
     * {@link #message} is the SHORT cause and it goes on the always-visible caveat line (#321 §1),
     * which `PlannerWidgets` wraps to three lines and TRUNCATES past -- and the pointer at the
     * detail panel is the half that must never be the casualty.
     *
     * <h3>NO CHARACTER COUNT IS THE BUDGET, AND ARITHMETIC ON ONE WILL LIE TO YOU</h3>
     *
     * The wrap is `NodeRowText.wrap`, which takes `CONTENT_WIDTH / CHAR_WIDTH` = 64 characters a
     * line and breaks on SPACES. So three lines are not 192 usable characters: every line throws
     * away whatever sits between its last space and the 64th column, and how much that is depends
     * on where the spaces fall in the whole composed line. The line is not just this string either
     * -- it is `message` + " -- " + the field list + " " + the pointer, and THE FIELD LIST CHANGES
     * AT RUNTIME as sources go live.
     *
     * The measured proof that arithmetic lies here: the rejected `NO_REPLY` composed to 176
     * characters against a nominal 3x64 = 192 and STILL did not fit, because its middle line broke
     * after "placed," at column 45 and threw away 19. A per-message count cannot see that.
     *
     * SO THE ONLY AUTHORITY IS
     * `PlanCaveatsTest.everyRefusalReasonFitsTheCaveatCapWithThePointerIntact`, which composes and
     * wraps the REAL line for every value below. RUN IT before lengthening a message. For scale
     * only, the EIGHT REFUSAL messages span 25 characters (`NO_REPLY`) to 49 (`NOT_LINKED`) --
     * measured 2026-08-23 and re-counted after #331 lengthened `NETWORK_GONE` to 47, which moved
     * the middle of the range and neither end. `OK`'s "read" is not counted: it is never composed
     * into a caveat line, so including it would make the floor 4 and the range meaningless. So the
     * count is `values().length` MINUS ONE -- nine values, eight refusals.
     *
     * THIS PARAGRAPH HAS NOW BEEN WRONG ABOUT THE COUNT THREE TIMES: "ten messages", then "34-46",
     * then "nine refusals" written by the #331 re-count that fixed everything except the number.
     * That is the standing argument for running the test instead of reading this, and for counting
     * `Reason.values()` rather than the entries in this list.
     *
     * SEVEN OF THE EIGHT CAN ACTUALLY APPEAR. {@link Reason#OUT_OF_RANGE} is retired and nothing
     * mints it, but the cap test still composes and wraps its line, and that is deliberate rather
     * than an oversight: the value is still decodable off the wire, so if a mismatched build ever
     * does send it the sentence has to fit rather than shove the way-in pointer off the line.
     *
     * THE TEST HAS ALREADY CAUGHT ONE: `NO_REPLY` was first written as "the server did not answer
     * -- this mod may not be installed on it", 64 characters, and it pushed the way-in pointer off
     * the line. That is what {@link #advice} exists for.
     *
     * {@link #advice} is the extra sentence for the DETAIL PANEL, where there is room, and it is
     * empty for every reason whose cause is already the whole story. "No wireless terminal in your
     * inventory" needs no elaboration; "the server did not answer" does, because the thing to go and
     * check is not in the sentence.
     *
     * DO NOT lengthen a `message` to say what an `advice` is for. The cap test will fail, and the
     * fix it is pointing at is moving the words rather than raising `MAX_CAVEAT_LINES` -- see that
     * constant's own javadoc, which forbids raising it for anything but a longer FIELD LIST.
     */
    public enum Reason {
        /** Read succeeded. The only value for which counts are meaningful. */
        OK("read"),
        NO_AE2("Applied Energistics is not installed"),
        NO_TERMINAL("no wireless terminal in your inventory"),
        NOT_LINKED("your wireless terminal is not linked to a network"),
        /**
         * The encryption key names a network this read cannot find. #331
         *
         * <h2>NOT LOADED LEADS, BECAUSE ON A SERVER IT IS THE LIKELIER HALF</h2>
         *
         * The message used to be "the linked network no longer exists", which is true of exactly
         * one of the two states that reach here and is the rarer one. `Ae2StockReader` locates the
         * grid from the security station the terminal's encryption key names, so a network whose
         * base chunks are simply not in memory is indistinguishable from one that was dismantled
         * -- and on a multiplayer server, with nobody standing at the base, unloaded is the
         * ordinary case. Measured on #331: Jake logged out in dimension 20 tens of thousands of
         * blocks from his base, and "no longer exists" told him his ME system had been deleted.
         *
         * A PLAYER CAN ACT ON "not loaded" AND CANNOT ACT ON "gone", which is what settles the
         * order rather than which is more often true. Both are named because the read genuinely
         * cannot tell them apart, and claiming only the alarming one is the same defect
         * {@link #NO_REPLY}'s hedge exists to avoid one reason over.
         */
        NETWORK_GONE("your network is not loaded, or no longer exists",
                     "on a server the chunks holding its security station may simply be"
                     + " unloaded -- is anyone standing near your base?"),
        /**
         * NOTHING MINTS THIS ANY MORE. #331
         *
         * <h2>THE GATE IT REPORTED IS GONE, BECAUSE IT NEVER PASSED</h2>
         *
         * `Ae2StockReader` used to refuse a read when no ACTIVE `IWirelessAccessPoint` on the grid
         * had the player inside its own range, on the argument that this is the test AE2's own
         * wireless terminal makes. It is -- and this pack's terminal does not make it. Measured
         * 2026-08-23 by a full region scan of every dimension in the reference save: not ONE vanilla
         * access point exists in the world, the base networks through `ae2stuff:wireless` connectors,
         * and `ae2wut`'s universal terminal opens the wrapped terminals through the underlying mods'
         * own GUI factories. So the gate refused EVERY live read from 0.11.0 onwards, including
         * standing inside the base, and it reached the player as "the ingredients still aren't being
         * detected correctly". The gate is deleted; see {@link
         * io.github.jacoblasky.recipedump.common.ae2.Ae2StockReader#read} for the measurement and for
         * why an access-point check must not be restored on the strength of AE2's API alone.
         *
         * <h2>THE VALUE STAYS, AND THAT IS NOT COMPATIBILITY WORK</h2>
         *
         * Backwards compatibility is explicitly not a constraint on this project before v1.x.x, so
         * this is NOT kept to decode an old payload. It is kept because {@link
         * StockSnapshot#deserializeNBT} maps an unrecognised reason to {@link #NETWORK_GONE}, and a
         * client and server one jar apart during a rolling reinstall would then turn a refusal this
         * build cannot name into a claim about the player's base. Deleting the constant buys nothing
         * and costs one wrong sentence in a window nobody chose to be in.
         *
         * DEPRECATED SO A RE-MINT IS A COMPILER WARNING, and
         * `Ae2StockTest.nothingInProductionMintsTheRetiredRangeRefusal` is the part that actually
         * fails: an annotation is advice and that test is the gate. Every test that wanted merely
         * "some refusal" was moved off this value when it was deprecated, so a warning here means a
         * caller genuinely reached for the retired one rather than that the suite is noisy.
         *
         * THE MESSAGE SAYS IT IS RETIRED RATHER THAN DESCRIBING A BOUNDARY. An earlier pass on #331
         * reworded this to "no access point covers you in this dimension" with advice explaining
         * where the range came from, which was a better sentence about a gate that had already been
         * measured never to fire. A plausible player-facing sentence on an unreachable value is how
         * a reader concludes the range rule is still current; if this string ever renders, the bug is
         * that it was minted.
         */
        @Deprecated
        OUT_OF_RANGE("this refusal is retired and should not appear",
                     "the range gate it reported was deleted in #331 after it was measured never"
                     + " to pass on this pack -- seeing this means a build minted it by mistake"),
        NO_POWER("your wireless terminal is out of power"),
        NO_PERMISSION("you do not have extract access to that network"),
        NO_STORAGE("that network has no storage attached"),
        /**
         * The server never answered. #321
         *
         * <h2>NOT SENT BY THE SERVER, and it is the only reason that is not</h2>
         *
         * Every other value here is a verdict `Ae2StockReader` reached and put on the wire. This
         * one is minted BY THE CLIENT, by `PlannerStock.releaseIfUnanswered`, when a plan has
         * waited past its deadline for a reply that is not coming. It is a reason rather than a
         * new kind of state for exactly one purpose: everything downstream of a refusal already
         * works. The status readers name it, the caveat line quotes it, `LiveStock.isFresh`
         * declines to treat it as fresh so the next plan re-asks, and no branch anywhere needs to
         * learn about a fourth possibility.
         *
         * THE LIKELIEST CAUSE IS A MISSING SERVER-SIDE INSTALL, measured in the decompiled 1.12.2
         * source on #321: a client mod missing from a dedicated server's `mods/` is NOT refused
         * at handshake -- `Loader.checkRemoteModList` returns true and
         * `NetworkModHolder.DefaultNetworkChecker.checkCompatible` explicitly accepts a
         * client-side-extra mod -- and `NetworkDispatcher` then routes the unknown channel's
         * payload to vanilla handling, which ignores it. No kick, no log, no error.
         *
         * SO THE ADVICE HEDGES, AND THE HEDGE IS NOT TIMIDITY. The give-up fires on a five-second
         * deadline, which a merely BUSY server can miss -- and on an integrated server the mod is
         * installed by definition, so "this mod is not installed" would be flatly false to the
         * single-player who is by far the likeliest reader. Naming both possibilities keeps the
         * sentence true in every case it can appear in, and the recovery is the same either way:
         * the refusal is not fresh, so the next plan asks again. Review caught the unhedged version.
         */
        NO_REPLY("the server did not answer",
                 "it may be busy, or this mod may not be installed on it");

        private final String message;
        private final String advice;

        Reason(String message) {
            this(message, "");
        }

        Reason(String message, String advice) {
            this.message = message;
            this.advice = advice;
        }

        /**
         * The short cause, for the always-visible caveat line. No punctuation or prefix of its own.
         *
         * KEEP IT SHORT, AND LET THE CAP TEST DECIDE WHAT SHORT IS. See the enum's own header: this
         * string is only one of four parts of a word-wrapped, truncated line, so no character count
         * predicts whether it fits and none is written here to be trusted.
         */
        public String message() {
            return message;
        }

        /** The extra sentence for the detail panel, or "" when the cause says it all. */
        public String advice() {
            return advice;
        }

        /**
         * Both, for a surface with room. `""` advice yields the message unchanged.
         *
         * COMPOSED HERE RATHER THAN AT THE CALL SITE, so there is one spelling of the join and no
         * caller has to remember that `advice` might be empty. `PlannerStock.statusOf` is the
         * caller; `PlannerStock.refusalSentence` deliberately uses {@link #message} alone.
         */
        public String explanation() {
            return advice.isEmpty() ? message : message + " -- " + advice;
        }
    }

    private static final String TAG_REASON = "reason";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_PLACED = "placed";
    private static final String TAG_KEY = "k";
    private static final String TAG_COUNT = "n";

    private final Reason reason;
    private final Map<String, Long> counts;
    private final Map<String, Integer> placed;

    private StockSnapshot(Reason reason, Map<String, Long> counts,
                          Map<String, Integer> placed) {
        this.reason = reason;
        this.counts = counts;
        this.placed = placed;
    }

    public static StockSnapshot unavailable(Reason reason) {
        if (reason == Reason.OK) {
            throw new IllegalArgumentException("OK is not a refusal");
        }
        return new StockSnapshot(reason, Collections.<String, Long>emptyMap(),
                Collections.<String, Integer>emptyMap());
    }

    /**
     * An available snapshot. Either map may be empty -- a new network really does hold nothing.
     * (The placed map keys every grid NODE and every loaded TILE ENTITY, so cables, drives and
     * chests appear under their own item keys too: an available snapshot with an EMPTY placed map
     * is essentially unreachable off a real read, since the security station the terminal is linked
     * to is itself a node. The empty case exists for deserialization of a pre-placed payload and
     * for tests.)
     *
     * BOTH HALVES ARE REQUIRED AND THERE IS NO ONE-ARGUMENT FORM, deliberately. A caller that
     * could name only the items would be handing on a snapshot whose placed half silently reads
     * as "you own no machines" -- the same confidently-wrong claim this class exists to prevent,
     * one field over. Every caller has both, because both come out of the one read.
     */
    public static StockSnapshot of(Map<String, Long> counts, Map<String, Integer> placed) {
        return new StockSnapshot(Reason.OK,
                Collections.unmodifiableMap(new LinkedHashMap<String, Long>(counts)),
                Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(placed)));
    }

    public boolean isAvailable() {
        return reason == Reason.OK;
    }

    public Reason reason() {
        return reason;
    }

    /** Empty for a refusal. Check {@link #isAvailable} before planning against it. */
    public Map<String, Long> counts() {
        return counts;
    }

    /** How much of one key the network holds, or 0. */
    public long count(String key) {
        Long held = counts.get(key);
        return held == null ? 0L : held.longValue();
    }

    public int distinctKeys() {
        return counts.size();
    }

    /**
     * Everything the read saw placed, to how many were seen: every node on the grid PLUS every
     * tile entity in a loaded chunk, keyed as item keys (and, where a tile entity's registry id
     * differs from its block's item id, under that too -- see `PlacedTileEntities`).
     *
     * FULL OF THINGS THAT ARE NOT MACHINES, and that is not waste. Cables, drives, chests and
     * signs all key here; `Machines.resolve` only ever looks up CATALYST keys and
     * `resolveFreeSources` only generator keys, so the extra rows are inert at both readers.
     * Filtering to "machines" would mean inventing a definition of machine that the graph already
     * owns, in the one place that cannot see the graph.
     *
     * Empty for a refusal. {@link #isAvailable} is what tells a refusal from a real read -- the
     * same rule {@link #counts} obeys and for the same reason.
     */
    public Map<String, Integer> placed() {
        return placed;
    }

    /** How many of one machine key the grid carries, or 0. */
    public int placedCount(String key) {
        Integer seen = placed.get(key);
        return seen == null ? 0 : seen.intValue();
    }

    public int distinctPlacedKeys() {
        return placed.size();
    }

    /**
     * The `have` field of a scenario document, in the shape `ScenarioInputs.resolve` reads and
     * a plan fixture writes.
     *
     * ON THE SNAPSHOT RATHER THAN AT THE PLANNER, for `PinStore.document`'s reason: the keys
     * are already the dump's keys and the counts are already longs, so the one place that
     * knows the format is the one place that holds it. A converter at the call site would be a
     * second spelling of the field, free to narrow a count or re-derive a key.
     *
     * A REFUSAL PRODUCES AN EMPTY OBJECT because it holds no counts, and that is safe ONLY
     * because {@link ScenarioSource} carries the refusal separately: the empty object goes
     * into the document beside a caveat naming the reason, rather than as the silent claim
     * "you own nothing" this class exists to prevent. DO NOT read this without checking
     * {@link #isAvailable}.
     */
    public JsonObject document() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            out.addProperty(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /**
     * The `placed` field of a scenario document, in the shape `ScenarioInputs.resolve` reads.
     *
     * SAME SHAPE AS THE OFFLINE TOOL'S, WITH A NARROWER MEANING. `recipegraph have` writes
     * `placed` from a region-file walk of every tile entity in the save, loaded or not; this writes
     * it from the player's grid plus the chunks the server currently holds. `ScenarioInputs.resolve`
     * cannot tell them apart and must not need to -- it reads a block id to a count either way --
     * so the difference is declared to the PLAYER on {@link ScenarioSource#PLACED} rather than
     * encoded here.
     *
     * A REFUSAL PRODUCES AN EMPTY OBJECT, safe for exactly the reason {@link #document} is:
     * the refusal travels beside it on {@link ScenarioSource}, so the empty object arrives with
     * a caveat naming the reason rather than as the claim "you own no machines".
     */
    public JsonObject placedDocument() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Integer> entry : placed.entrySet()) {
            out.addProperty(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /**
     * For the packet.
     *
     * A LIST OF PAIRS RATHER THAN A COMPOUND KEYED BY ITEM KEY, and that is not stylistic. An
     * NBT compound's keys are its tag names, and a graph key contains `:` and `#` -- legal in
     * a tag name but a standing invitation for something downstream to try to parse one.
     * Pairs keep the key a value.
     *
     * Counts are LONGS. An ME network holding more than 2^31 of a bulk item is ordinary in
     * this pack -- the reference save holds 71.8 million items -- and a narrowed count would
     * wrap negative and read as owning none.
     *
     * THE PLACED LIST IS INTS AND THAT IS NOT AN INCONSISTENCY. It counts grid NODES, and a
     * grid with two billion nodes does not exist; `Evidence.placed` and the offline tool's
     * `placed` map are both int-valued for the same reason, so widening here would be a
     * different type from the one the field is read into at both ends.
     */
    public NBTTagCompound serializeNBT() {
        NBTTagCompound out = new NBTTagCompound();
        out.setString(TAG_REASON, reason.name());
        NBTTagList items = new NBTTagList();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString(TAG_KEY, entry.getKey());
            row.setLong(TAG_COUNT, entry.getValue().longValue());
            items.appendTag(row);
        }
        out.setTag(TAG_ITEMS, items);
        NBTTagList machines = new NBTTagList();
        for (Map.Entry<String, Integer> entry : placed.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString(TAG_KEY, entry.getKey());
            row.setInteger(TAG_COUNT, entry.getValue().intValue());
            machines.appendTag(row);
        }
        out.setTag(TAG_PLACED, machines);
        return out;
    }

    /**
     * The inverse. An unrecognised reason reads as {@link Reason#NETWORK_GONE}.
     *
     * NOT as OK, which is the direction that matters: a payload this build cannot understand
     * must never be treated as a successful read of an empty network, because that is the
     * confidently-wrong-plan failure arriving by packet instead of by bug.
     *
     * A MISSING `placed` LIST DECODES AS NO MACHINES AND NOT AS A REFUSAL, which is the one
     * asymmetry here worth stating. It cannot arise between two halves of this build -- one jar
     * serves both sides -- and the alternative is worse: refusing the whole snapshot over an
     * absent list would throw away a stock read that is present and correct, which is a
     * regression to "you own nothing" triggered by the newer field. A row with a
     * NON-POSITIVE count is dropped instead, because zero sightings of a machine is not a
     * sighting and `ScenarioInputs` would index the key regardless of its count.
     */
    public static StockSnapshot deserializeNBT(NBTTagCompound payload) {
        Reason reason;
        try {
            reason = Reason.valueOf(payload.getString(TAG_REASON));
        } catch (IllegalArgumentException unknown) {
            return unavailable(Reason.NETWORK_GONE);
        }
        if (reason != Reason.OK) {
            return unavailable(reason);
        }
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        NBTTagList items = payload.getTagList(TAG_ITEMS, 10);
        for (int i = 0; i < items.tagCount(); i++) {
            NBTTagCompound row = items.getCompoundTagAt(i);
            String key = row.getString(TAG_KEY);
            if (!key.isEmpty()) {
                counts.put(key, Long.valueOf(row.getLong(TAG_COUNT)));
            }
        }
        Map<String, Integer> machines = new LinkedHashMap<String, Integer>();
        NBTTagList placedRows = payload.getTagList(TAG_PLACED, 10);
        for (int i = 0; i < placedRows.tagCount(); i++) {
            NBTTagCompound row = placedRows.getCompoundTagAt(i);
            String key = row.getString(TAG_KEY);
            int seen = row.getInteger(TAG_COUNT);
            if (!key.isEmpty() && seen > 0) {
                machines.put(key, Integer.valueOf(seen));
            }
        }
        return of(counts, machines);
    }
}
