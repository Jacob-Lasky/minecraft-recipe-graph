package io.github.jacoblasky.recipedump.graph;

import java.util.Arrays;

/**
 * A growable {@code double} list, the sibling of {@link IntArray} and for the same reason.
 *
 * WHY NOT java.util.ArrayList: see {@link IntArray}'s javadoc, which is the argument in full.
 * Boxing a per-slot column of ~335,000 values into {@code Double} objects costs 24 bytes each
 * against 8, and the whole point of this package is that the graph fits in a Minecraft client's
 * spare heap.
 *
 * WHY {@code double} AND NOT {@code float} OR A QUANTISED {@code byte}. The two columns this
 * exists for are {@link RecipeStore}'s consume chance (#175) and its yield chance (#223), and
 * both arrive as JSON numbers that python parsed into doubles. `PlanFixtureTest` compares this
 * port's plans and costs to python's with a ZERO delta, and a float does not round-trip:
 * `(double) 0.95f` is 0.949999988079071, so a single narrowing puts every derived cost a few
 * ulps away from the oracle and turns the golden gate red on a correct port. The pack's
 * declared chances are 0.99, 0.95, 0.8, 0.5, 0.3, 0.1, 0.05, 0.01, 0.001 and their kin, of
 * which only the halves and quarters survive a float. 4 extra bytes per value is ~2.6 MB
 * across both columns and it is bought deliberately.
 *
 * A byte scaled by 1/255 is worse again, and not hypothetically: it cannot distinguish 0.001
 * from 0.0, and 0.001 is populated on both sides. On the input side it is the bottom rung of
 * the reference pack's only fractional-input ladder, where rounding to zero turns an input
 * consumed one run in a thousand into a permanent requirement charged once; on the output side
 * it is the bottom of the observed range over 834 fractional declarations, where rounding to
 * zero turns a rare product into one the recipe cannot make at all.
 *
 * Used for BUILDING. Finished structures hold a plain {@code double[]} from {@link #trimmed()},
 * so nothing carries spare capacity into the retained graph.
 *
 * NO {@code toBytes()}, DELIBERATELY, unlike {@link IntArray}. That method exists there because
 * narrow columns genuinely fit a byte and a silent truncation would mislabel data; there is no
 * width these columns can be narrowed to without losing a value the pack really uses or the
 * exactness the golden gate rests on, so offering the conversion would be offering the bug.
 */
public final class DoubleArray {

    private double[] data;
    private int size;

    public DoubleArray() {
        this(16);
    }

    public DoubleArray(int capacity) {
        this.data = new double[Math.max(1, capacity)];
    }

    public void add(double value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length + (data.length >> 1) + 1);
        }
        data[size++] = value;
    }

    public double get(int index) {
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
    public double[] trimmed() {
        return size == data.length ? data : Arrays.copyOf(data, size);
    }
}
