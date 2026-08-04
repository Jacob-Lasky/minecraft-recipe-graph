package io.github.jacoblasky.recipedump.graph;

import java.util.Arrays;

/**
 * A growable {@code float} list, the sibling of {@link IntArray} and for the same reason.
 *
 * WHY NOT java.util.ArrayList: see {@link IntArray}'s javadoc, which is the argument in full.
 * Boxing a per-slot column of ~335,000 values into {@code Float} objects costs 20 bytes each
 * against 4, and the whole point of this package is that the graph fits in a Minecraft client's
 * spare heap.
 *
 * WHY {@code float} AND NOT A QUANTISED {@code byte}, since one column of ~335k values is the
 * only thing this exists for. The column is {@link RecipeStore}'s consume chance (#175), and a
 * byte scaled by 1/255 cannot distinguish 0.001 from 0.0. That is not a hypothetical precision
 * worry: 0.001 is the bottom rung of the reference pack's only fractional-input ladder, and
 * rounding it to zero would turn an input consumed one run in a thousand into a permanent
 * requirement charged once. See the field's javadoc in {@link RecipeStore}.
 *
 * Used for BUILDING. Finished structures hold a plain {@code float[]} from {@link #trimmed()},
 * so nothing carries spare capacity into the retained graph.
 *
 * NO {@code toBytes()}, DELIBERATELY, unlike {@link IntArray}. That method exists there because
 * narrow columns genuinely fit a byte and a silent truncation would mislabel data; there is no
 * width this column can be narrowed to without losing a value the pack really uses, so offering
 * the conversion would be offering the bug.
 */
public final class FloatArray {

    private float[] data;
    private int size;

    public FloatArray() {
        this(16);
    }

    public FloatArray(int capacity) {
        this.data = new float[Math.max(1, capacity)];
    }

    public void add(float value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 1);
        }
        data[size++] = value;
    }

    public float get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException(index + " >= " + size);
        }
        return data[index];
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
     * Hands back the backing array itself when it happens to be full rather than copying, on
     * the same terms as {@link IntArray#trimmed()}: every caller in this package drops its
     * builder immediately after.
     */
    public float[] trimmed() {
        return size == data.length ? data : Arrays.copyOf(data, size);
    }
}
