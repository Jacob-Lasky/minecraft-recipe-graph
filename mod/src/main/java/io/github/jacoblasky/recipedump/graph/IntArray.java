package io.github.jacoblasky.recipedump.graph;

import java.util.Arrays;

/**
 * A growable {@code int} list, because {@code ArrayList<Integer>} is not affordable here.
 *
 * WHY NOT java.util.ArrayList. The graph holds several million small integers -- key ids,
 * recipe ids, CSR offsets -- and every one of them boxed is a 16-byte {@code Integer} plus
 * a 4-byte reference, so a list of 3 million ids costs 60 MB instead of 12 MB. The whole
 * point of this package is that the graph fits in a Minecraft client's spare heap, and
 * boxing alone would spend the budget. DO NOT replace this with a collection type.
 *
 * Used for BUILDING. Finished structures hold plain {@code int[]}, obtained through
 * {@link #trimmed()}, so nothing carries spare capacity into the retained graph.
 */
public final class IntArray {

    private int[] data;
    private int size;

    public IntArray() {
        this(16);
    }

    public IntArray(int capacity) {
        this.data = new int[Math.max(1, capacity)];
    }

    public void add(int value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 1);
        }
        data[size++] = value;
    }

    public int get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException(index + " >= " + size);
        }
        return data[index];
    }

    public void set(int index, int value) {
        if (index >= size) {
            throw new IndexOutOfBoundsException(index + " >= " + size);
        }
        data[index] = value;
    }

    /** Grows the list to at least `length`, filling any new positions with `fill`. */
    public void ensureSize(int length, int fill) {
        if (length > data.length) {
            data = Arrays.copyOf(data, Math.max(length, data.length + (data.length >> 1) + 1));
        }
        while (size < length) {
            data[size++] = fill;
        }
    }

    public int size() {
        return size;
    }

    public void clear() {
        size = 0;
    }

    /**
     * The backing array at exactly `size` elements.
     *
     * Hands back the backing array itself when it happens to be full rather than copying,
     * so the caller does not pay a second copy of a multi-million entry index at build
     * time. Safe because every caller in this package drops its builder immediately after.
     */
    public int[] trimmed() {
        return size == data.length ? data : Arrays.copyOf(data, size);
    }

    /**
     * The values narrowed to a {@code byte} column, CHECKED rather than truncated.
     *
     * Kinds, ingredient roles, extractor sources and the recipe flag word all have a handful
     * of possible values, so a byte is the right width -- but a silent truncation would turn
     * "source 256" into "source 0" and mislabel every recipe from a newly added extractor,
     * which reads as a data problem rather than as an overflow. Throwing names the column
     * that needs widening instead.
     */
    public byte[] toBytes() {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            int value = data[i];
            if (value < 0 || value > 255) {
                throw new IllegalStateException(
                        "value " + value + " does not fit a byte column; widen it");
            }
            out[i] = (byte) value;
        }
        return out;
    }
}
