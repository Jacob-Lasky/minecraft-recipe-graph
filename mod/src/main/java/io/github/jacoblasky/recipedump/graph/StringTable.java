package io.github.jacoblasky.recipedump.graph;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * Every string this graph knows, stored ONCE as UTF-8 bytes and addressed by int id.
 *
 * WHY NOT {@code String}. Minecraft 1.12.2 runs on Java 8, which has no compact strings: a
 * {@code String} is a 24-byte object wrapping a {@code char[]} that spends TWO bytes per
 * character plus a 16-byte array header. The reference graph holds ~300,000 keys averaging
 * ~30 characters, which is ~30 MB as objects against ~10 MB as UTF-8 -- and the display
 * names are worse, because 261,095 of them collapse to far fewer distinct strings once
 * deduplicated. DO NOT "simplify" this to a {@code HashMap<String, Integer>}: on Java 8 that
 * costs the char arrays, the String objects, the Integer boxes and the map's own nodes, and
 * it is the single change most likely to put this package back over the heap gate.
 *
 * Decoding is LAZY and uncached, by design. A display name is needed only for what is on
 * screen -- a few dozen rows -- while the solver runs entirely on ids, so a cache would
 * retain exactly the strings this class exists to avoid retaining.
 *
 * The lookup index is optional. Tables that are only ever read by id (recipe ids, for one)
 * drop it at build time and save an {@code int[]} the size of the table.
 */
public final class StringTable {

    /** Every string, UTF-8, concatenated with no separators. */
    private final byte[] data;
    /** `count + 1` entries: string `i` is `data[offsets[i] .. offsets[i + 1])`. */
    private final int[] offsets;
    /**
     * Open-addressed id index: slot holds `id + 1`, 0 means empty. Null when the table was
     * built without one, which makes {@link #idOf} unavailable rather than slow.
     */
    private final int[] slots;
    private final int count;

    private StringTable(byte[] data, int[] offsets, int[] slots, int count) {
        this.data = data;
        this.offsets = offsets;
        this.slots = slots;
        this.count = count;
    }

    public int size() {
        return count;
    }

    public String get(int id) {
        if (id < 0 || id >= count) {
            throw new IndexOutOfBoundsException("string id " + id + " of " + count);
        }
        int start = offsets[id];
        try {
            return new String(data, start, offsets[id + 1] - start, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            // Every JVM is required to support UTF-8. Java 8 has no StandardCharsets
            // overload for this constructor that avoids the checked exception on the
            // String(byte[], int, int, String) form, so it is caught rather than declared.
            throw new IllegalStateException(impossible);
        }
    }

    /** The id of `value`, or -1. Requires a table built with a lookup index. */
    public int idOf(String value) {
        if (slots == null) {
            throw new IllegalStateException("this StringTable was built without a lookup index");
        }
        byte[] probe = utf8(value);
        int mask = slots.length - 1;
        int slot = spread(hash(probe, 0, probe.length)) & mask;
        while (true) {
            int occupant = slots[slot];
            if (occupant == 0) {
                return -1;
            }
            int id = occupant - 1;
            if (matches(id, probe)) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** True when string `id` is byte-identical to `probe`, without decoding it. */
    private boolean matches(int id, byte[] probe) {
        int start = offsets[id];
        if (offsets[id + 1] - start != probe.length) {
            return false;
        }
        for (int i = 0; i < probe.length; i++) {
            if (data[start + i] != probe[i]) {
                return false;
            }
        }
        return true;
    }

    /** The length in UTF-8 BYTES, not characters. For accounting, never for indexing text. */
    public int byteLength(int id) {
        return offsets[id + 1] - offsets[id];
    }

    public long retainedBytes() {
        return Sizes.object(3 * Sizes.REFERENCE + 4)
                + Sizes.bytes(data) + Sizes.bytes(offsets) + Sizes.bytes(slots);
    }

    /** What the lookup index alone costs, so a report can price dropping it. */
    public long indexBytes() {
        return Sizes.bytes(slots);
    }

    static byte[] utf8(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * FNV-1a over the UTF-8 bytes.
     *
     * Chosen because it can be computed over a RANGE of the blob without materialising a
     * String, which is the whole reason interning here is cheaper than a HashMap. Keys in
     * this pack share long prefixes (`modularmachinery:...`), and FNV-1a mixes every byte
     * rather than sampling, so those do not collide en masse.
     */
    private static int hash(byte[] bytes, int start, int length) {
        int h = 0x811c9dc5;
        for (int i = start; i < start + length; i++) {
            h ^= bytes[i] & 0xff;
            h *= 0x01000193;
        }
        return h;
    }

    /** Mixes the high bits down, because the slot index only ever reads the low ones. */
    private static int spread(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    public static Builder builder(int expectedCount, int expectedBytes, boolean deduplicate,
                                  boolean keepIndex) {
        return new Builder(expectedCount, expectedBytes, deduplicate, keepIndex);
    }

    /**
     * Accumulates strings into the blob, optionally collapsing duplicates as it goes.
     *
     * Deduplication happens DURING the build rather than as a pass afterwards, because a
     * pass afterwards would need every string alive at once, which is the peak this class
     * exists to avoid. The name table is where it pays: 261,095 keys carry display names and
     * the distinct strings among them are a fraction of that -- 286 items are called
     * "Spell Book".
     */
    public static final class Builder {

        private final boolean deduplicate;
        private final boolean keepIndex;
        private byte[] data;
        private int used;
        private final IntArray offsets = new IntArray();
        private int[] slots;
        private int count;

        private Builder(int expectedCount, int expectedBytes, boolean deduplicate,
                        boolean keepIndex) {
            this.deduplicate = deduplicate;
            this.keepIndex = keepIndex;
            this.data = new byte[Math.max(64, expectedBytes)];
            this.offsets.add(0);
            // Sized for the expected count at a 0.7 load factor, so a table given an honest
            // hint never rehashes. A rehash re-walks the blob and is the one part of loading
            // that is quadratic-ish if the hint is badly wrong.
            int capacity = 16;
            while (capacity * 7 < Math.max(16, expectedCount) * 10) {
                capacity <<= 1;
            }
            this.slots = new int[capacity];
        }

        /** The id of `value`, adding it if this builder deduplicates and has not seen it. */
        public int add(String value) {
            byte[] probe = utf8(value);
            if (deduplicate) {
                int mask = slots.length - 1;
                int slot = spread(hash(probe, 0, probe.length)) & mask;
                while (true) {
                    int occupant = slots[slot];
                    if (occupant == 0) {
                        break;
                    }
                    if (matchesPending(occupant - 1, probe)) {
                        return occupant - 1;
                    }
                    slot = (slot + 1) & mask;
                }
            }
            int id = append(probe);
            index(id, probe);
            return id;
        }

        private int append(byte[] probe) {
            if (used + probe.length > data.length) {
                int wanted = Math.max(used + probe.length, data.length + (data.length >> 1) + 1);
                data = Arrays.copyOf(data, wanted);
            }
            System.arraycopy(probe, 0, data, used, probe.length);
            used += probe.length;
            offsets.add(used);
            return count++;
        }

        private void index(int id, byte[] probe) {
            if (count * 10 > slots.length * 7) {
                rehash();
            }
            int mask = slots.length - 1;
            int slot = spread(hash(probe, 0, probe.length)) & mask;
            while (slots[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            slots[slot] = id + 1;
        }

        private void rehash() {
            int[] bigger = new int[slots.length << 1];
            int mask = bigger.length - 1;
            for (int id = 0; id < count; id++) {
                int start = offsets.get(id);
                int slot = spread(hash(data, start, offsets.get(id + 1) - start)) & mask;
                while (bigger[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                bigger[slot] = id + 1;
            }
            slots = bigger;
        }

        private boolean matchesPending(int id, byte[] probe) {
            int start = offsets.get(id);
            if (offsets.get(id + 1) - start != probe.length) {
                return false;
            }
            for (int i = 0; i < probe.length; i++) {
                if (data[start + i] != probe[i]) {
                    return false;
                }
            }
            return true;
        }

        public StringTable build() {
            // Trimmed to `used`, not left at capacity: the blob grows by half each time, so
            // an untrimmed table can carry tens of megabytes of slack into the retained
            // graph and every measurement taken off it would be wrong by that much.
            byte[] exact = used == data.length ? data : Arrays.copyOf(data, used);
            return new StringTable(exact, offsets.trimmed(), keepIndex ? slots : null, count);
        }
    }
}
