package io.github.jacoblasky.recipedump.graph;

import java.util.Arrays;

/**
 * A sparse side table: "which of the graph's keys does this fact apply to", as sorted ids.
 *
 * Several of the graph's sections cover a small fraction of the key space -- 15,216 EMC
 * values, 3,381 damageable items, 259 blueprints, 11 dimension-gated ores, against 265,974
 * keys. A dense column would be 4 bytes per key per fact and cost more in padding than the
 * facts themselves; a {@code HashMap<Integer, ...>} would cost a node and a box per entry.
 * Sorted ids plus a parallel value array is 4 bytes per entry that exists and a binary
 * search, which for tables this size is three or four comparisons.
 *
 * VALUES LIVE IN THE CALLER'S OWN ARRAY, indexed by the SLOT this returns, not by key id.
 * That keeps this class free of a value type and lets one index serve several parallel
 * columns -- a dimension-gated ore has both an id and a name, and neither belongs here.
 */
public final class KeyIndex {

    private static final int[] NO_KEYS = new int[0];

    /** Ascending key ids. */
    private final int[] keys;

    private KeyIndex(int[] keys) {
        this.keys = keys;
    }

    public static KeyIndex empty() {
        return new KeyIndex(NO_KEYS);
    }

    public int size() {
        return keys.length;
    }

    public int keyAt(int slot) {
        return keys[slot];
    }

    /** The slot holding `keyId`, or -1. Tolerates the -1 an unknown key lookup returns. */
    public int slotOf(int keyId) {
        if (keyId < 0) {
            return -1;
        }
        int at = Arrays.binarySearch(keys, keyId);
        return at < 0 ? -1 : at;
    }

    public long retainedBytes() {
        return Sizes.object(Sizes.REFERENCE) + Sizes.bytes(keys);
    }

    /**
     * Sorts (key, value) pairs by key while keeping the values alongside.
     *
     * The caller adds in whatever order the JSON arrived and reads back a PERMUTATION that
     * puts the keys in order; every parallel value column has to be permuted the same way,
     * which is what {@link #permutation()} is for. Doing it here rather than in each caller
     * is the difference between one sort and five hand-written ones that must agree.
     */
    public static final class Builder {

        private final IntArray keys = new IntArray();

        public void add(int keyId) {
            keys.add(keyId);
        }

        public int size() {
            return keys.size();
        }

        /**
         * The order the added keys must be read in to come out ascending.
         *
         * `permutation()[slot]` is the index of the entry the caller added that belongs in
         * `slot`. Insertion-sorted rather than sorted with a comparator, because Java 8 has
         * no primitive-key sort that carries a payload and boxing 15,216 pairs to use one
         * would allocate exactly what this package exists to avoid. The sections this serves
         * are small and arrive nearly sorted -- `graph.json` is written with sorted keys and
         * ids are assigned in first-sight order -- so the quadratic worst case is not the
         * case that happens.
         */
        public int[] permutation() {
            int count = keys.size();
            int[] order = new int[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            for (int i = 1; i < count; i++) {
                int slot = order[i];
                int key = keys.get(slot);
                int j = i - 1;
                while (j >= 0 && keys.get(order[j]) > key) {
                    order[j + 1] = order[j];
                    j--;
                }
                order[j + 1] = slot;
            }
            return order;
        }

        public KeyIndex build(int[] permutation) {
            int[] sorted = new int[permutation.length];
            for (int slot = 0; slot < permutation.length; slot++) {
                sorted[slot] = keys.get(permutation[slot]);
            }
            return new KeyIndex(sorted);
        }
    }
}
