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
    private static final String TAG_KEY = "k";
    private static final String TAG_COUNT = "n";

    private final Reason reason;
    private final Map<String, Long> counts;

    private StockSnapshot(Reason reason, Map<String, Long> counts) {
        this.reason = reason;
        this.counts = counts;
    }

    public static StockSnapshot unavailable(Reason reason) {
        if (reason == Reason.OK) {
            throw new IllegalArgumentException("OK is not a refusal");
        }
        return new StockSnapshot(reason, Collections.<String, Long>emptyMap());
    }

    /** An available snapshot. May be empty -- a new network really does hold nothing. */
    public static StockSnapshot of(Map<String, Long> counts) {
        return new StockSnapshot(Reason.OK,
                Collections.unmodifiableMap(new LinkedHashMap<String, Long>(counts)));
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
        return out;
    }

    /**
     * The inverse. An unrecognised reason reads as {@link Reason#NETWORK_GONE}.
     *
     * NOT as OK, which is the direction that matters: a payload this build cannot understand
     * must never be treated as a successful read of an empty network, because that is the
     * confidently-wrong-plan failure arriving by packet instead of by bug.
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
        return of(counts);
    }
}
