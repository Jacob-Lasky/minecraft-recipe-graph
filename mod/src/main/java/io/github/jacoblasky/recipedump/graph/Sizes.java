package io.github.jacoblasky.recipedump.graph;

/**
 * Analytical retained-size accounting for the graph's own arrays.
 *
 * WHY AN ANALYTICAL NUMBER AT ALL, when the JVM can be asked. Because
 * {@code totalMemory() - freeMemory()} answers for the WHOLE heap and cannot say which
 * component spent it, and "which one dominates" is the actionable half of the heap gate.
 * These numbers are exact for the structures this package builds -- every one of them is a
 * primitive array whose length is known -- and the harness cross-checks their sum against a
 * measured post-GC heap delta. Agreement is what makes either number trustworthy; a gap
 * means this accounting has missed a field, and the harness reports the gap rather than
 * hiding it.
 *
 * The layout constants below assume a 64-bit HotSpot with COMPRESSED OOPS, which is what a
 * Minecraft client running under a heap below 32 GB gets. DO NOT quote these figures for a
 * measurement taken with {@code -XX:-UseCompressedOops} or above the 32 GB threshold: object
 * headers grow to 16 and references to 8, and the accounting would silently under-report.
 */
public final class Sizes {

    /** Mark word plus a compressed class pointer, padded to the 8-byte object alignment. */
    public static final int OBJECT_HEADER = 16;

    /** Object header plus the 4-byte array length field, already 8-byte aligned. */
    public static final int ARRAY_HEADER = 16;

    /** A compressed reference. */
    public static final int REFERENCE = 4;

    private Sizes() {
    }

    public static long align(long bytes) {
        return (bytes + 7L) & ~7L;
    }

    public static long array(int length, int elementBytes) {
        return align((long) ARRAY_HEADER + (long) length * elementBytes);
    }

    public static long bytes(byte[] array) {
        return array == null ? 0L : array(array.length, 1);
    }

    public static long bytes(int[] array) {
        return array == null ? 0L : array(array.length, 4);
    }

    public static long bytes(long[] array) {
        return array == null ? 0L : array(array.length, 8);
    }

    public static long bytes(double[] array) {
        return array == null ? 0L : array(array.length, 8);
    }

    /** An object with `fieldBytes` of declared fields, header included. */
    public static long object(long fieldBytes) {
        return align(OBJECT_HEADER + fieldBytes);
    }

    /** Rendered for a report: `12.3 MB`. */
    public static String human(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * One line of a size report, newline included.
     *
     * ONE FORMAT, because the accounted breakdown and the measured total are meant to be
     * read as one table and compared column to column. Two printf strings drift, and the
     * moment the columns stop lining up the comparison that the whole measurement rests on
     * becomes something the reader has to do by eye.
     */
    public static String row(String label, long bytes) {
        return String.format("%-40s %12s  %10d%n", label, human(bytes), bytes);
    }
}
