package io.github.jacoblasky.recipedump.graph;

import java.util.Arrays;

/**
 * A growable {@code long} list, the 64-bit sibling of {@link IntArray}.
 *
 * ONE CALLER TODAY, AND IT NEEDS THE FULL WIDTH: ProjectE EMC values reach
 * 422,212,465,065,984 on the reference pack, four orders of magnitude past what an
 * {@code int} holds. Narrowing that column would wrap the most expensive item in the pack to
 * something small and positive, which prices it as nearly free -- a wrong answer that looks
 * like a bargain rather than like an overflow. DO NOT "unify" this with {@link IntArray} by
 * narrowing the values.
 */
public final class LongArray {

    private long[] data;
    private int size;

    public LongArray() {
        this(16);
    }

    public LongArray(int capacity) {
        this.data = new long[Math.max(1, capacity)];
    }

    public void add(long value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 1);
        }
        data[size++] = value;
    }

    public long get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException(index + " >= " + size);
        }
        return data[index];
    }

    public int size() {
        return size;
    }

    public long[] trimmed() {
        return size == data.length ? data : Arrays.copyOf(data, size);
    }
}
