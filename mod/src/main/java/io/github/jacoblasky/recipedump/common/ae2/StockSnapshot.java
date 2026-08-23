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
 * A snapshot carries what the network HOLDS and what is CONNECTED to it, and the two share a
 * single {@link Reason} because they are the same walk of the same grid behind the same gate:
 * no terminal, no range, no power or no permission refuses both at once. Splitting the
 * availability would be inventing a state the read cannot produce -- there is no way to
 * succeed at the items and fail at the machines -- and it would let a caller price a plan
 * against machines from a read whose stock half had refused.
 *
 * The placed half is DELIBERATELY NARROWER THAN THE OFFLINE TOOL'S. `recipegraph have` scans
 * region files and sees every tile entity in the save; this sees only what is on the grid the
 * player's wireless terminal is linked to. That is the MVP scope decided in #320 -- a machine
 * counts as owned when it is connected to the ME system -- and {@link ScenarioSource#PLACED}
 * is where it is written down for the player.
 */
public final class StockSnapshot {

    /** Why a network could not be read. Every one is a different thing to go and do. */
    public enum Reason {
        /** Read succeeded. The only value for which counts are meaningful. */
        OK("read"),
        NO_AE2("Applied Energistics is not installed"),
        NO_TERMINAL("no wireless terminal in your inventory"),
        NOT_LINKED("your wireless terminal is not linked to a network"),
        NETWORK_GONE("the linked network no longer exists"),
        OUT_OF_RANGE("no wireless access point in range"),
        NO_POWER("your wireless terminal is out of power"),
        NO_PERMISSION("you do not have extract access to that network"),
        NO_STORAGE("that network has no storage attached");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /** A sentence for the player, with no punctuation or prefix of its own. */
        public String message() {
            return message;
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
     * (The placed map is NODE-keyed, so cables and drives appear under their own item keys too:
     * an available snapshot with an EMPTY placed map is essentially unreachable off a real grid
     * walk, and only catalyst keys are ever read back by `Machines.resolve`. The empty case
     * exists for deserialization of a pre-placed payload and for tests.)
     *
     * BOTH HALVES ARE REQUIRED AND THERE IS NO ONE-ARGUMENT FORM, deliberately. A caller that
     * could name only the items would be handing on a snapshot whose placed half silently reads
     * as "you own no machines" -- the same confidently-wrong claim this class exists to prevent,
     * one field over. Every caller has both, because both come out of the one grid walk.
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
     * Every node on the grid, keyed as item keys, to how many were seen -- cables and drives
     * included, not only machines in the catalyst sense; `Machines.resolve` only ever looks up
     * catalyst keys, so the extra rows are inert there.
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
     * `placed` from a region-file walk of every tile entity in the save; this writes it from
     * the grid the player is linked to. `ScenarioInputs.resolve` cannot tell them apart and
     * must not need to -- it reads a block id to a count either way -- so the difference is
     * declared to the PLAYER on {@link ScenarioSource#PLACED} rather than encoded here.
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
