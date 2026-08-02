package io.github.jacoblasky.recipedump.graph;

/**
 * A bitset over key ids, as a bare {@code long[]}.
 *
 * WHY NOT {@code java.util.BitSet} or {@code HashSet<String>}. The sets this graph needs --
 * world ores, live keys, reshaped-only keys, keys whose label is an unlocalized lang key --
 * are all "which of the ~300,000 interned keys are in this set", which is 37 KB as bits and
 * roughly 20 MB as a {@code HashSet<String>}. {@code BitSet} would do, but it is an object
 * per set with its own growth policy, and these are fixed-width the moment the key table is
 * frozen; a bare array makes that explicit and lets {@link Sizes} account for it exactly.
 */
public final class Bits {

    private Bits() {
    }

    public static long[] ofSize(int count) {
        return new long[(count + 63) >>> 6];
    }

    public static void set(long[] bits, int index) {
        bits[index >>> 6] |= 1L << (index & 63);
    }

    public static boolean get(long[] bits, int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public static int cardinality(long[] bits) {
        int total = 0;
        for (long word : bits) {
            total += Long.bitCount(word);
        }
        return total;
    }
}
