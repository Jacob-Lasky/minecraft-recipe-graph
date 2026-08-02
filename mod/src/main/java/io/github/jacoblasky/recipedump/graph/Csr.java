package io.github.jacoblasky.recipedump.graph;

/**
 * A one-to-many int index in compressed sparse row form: `offsets` plus flat `data`.
 *
 * WHY NOT {@code Map<String, List<Recipe>>}, which is what `model.py` uses. Every adjacency
 * in this graph is "one key to many recipe ids", and the reference pack has around 1.4
 * million such edges. As a map of lists that is a HashMap node, an ArrayList, its backing
 * {@code Object[]} and a boxed {@code Integer} per edge -- call it 60 bytes an edge, 84 MB,
 * for information that fits in two {@code int[]} at 8 bytes an edge. The heap gate is 400 MB
 * for the WHOLE graph, and there are four of these indexes.
 *
 * Rows are dense: row `i` exists for every `i` in `[0, rows())`, empty ones included, so a
 * key with no producers costs one offset rather than a null check and a map miss.
 *
 * DO NOT hand {@link #row} a hot path. It allocates; the point of this class is that
 * {@link #start}/{@link #end}/{@link #at} let a caller walk the edges with no allocation at
 * all.
 */
public final class Csr {

    private final int[] offsets;
    private final int[] data;

    Csr(int[] offsets, int[] data) {
        this.offsets = offsets;
        this.data = data;
    }

    public int rows() {
        return offsets.length - 1;
    }

    public int edges() {
        return data.length;
    }

    public int start(int row) {
        return offsets[row];
    }

    public int end(int row) {
        return offsets[row + 1];
    }

    public int count(int row) {
        return offsets[row + 1] - offsets[row];
    }

    /** The value at a flat position between {@link #start} and {@link #end}. */
    public int at(int position) {
        return data[position];
    }

    /**
     * A copy of one row.
     *
     * FOR COLD PATHS ONLY -- it allocates. The hot path walks
     * {@link #start}/{@link #end}/{@link #at}, which is the whole reason this class exists.
     */
    public int[] row(int row) {
        int from = offsets[row];
        int length = offsets[row + 1] - from;
        int[] out = new int[length];
        System.arraycopy(data, from, out, 0, length);
        return out;
    }

    /** Appends every value in `row` to `out`. Returns how many were appended. */
    public int appendRow(int row, IntArray out) {
        int from = offsets[row];
        int to = offsets[row + 1];
        for (int i = from; i < to; i++) {
            out.add(data[i]);
        }
        return to - from;
    }

    public long retainedBytes() {
        return Sizes.object(2 * Sizes.REFERENCE) + Sizes.bytes(offsets) + Sizes.bytes(data);
    }

    /**
     * Builds a CSR by counting sort: count per row, prefix-sum, then place.
     *
     * TWO PASSES OVER THE SOURCE RATHER THAN A TEMPORARY LIST PER ROW, because the
     * temporary is the expensive shape -- 300,000 small growable arrays at peak is both the
     * allocation churn and the fragmentation this package is trying to avoid. The caller
     * drives both passes, so it never has to materialise the edges anywhere.
     */
    public static final class Builder {

        private final int[] counts;
        private int[] data;
        private int[] cursor;
        private boolean counting = true;

        public Builder(int rows) {
            this.counts = new int[rows + 1];
        }

        /** Pass one: declare that `row` gains one edge. */
        public void count(int row) {
            counts[row + 1]++;
        }

        /** Ends pass one and allocates. Every {@link #count} must precede this. */
        public void prepare() {
            if (!counting) {
                throw new IllegalStateException("prepare() called twice");
            }
            counting = false;
            for (int i = 1; i < counts.length; i++) {
                counts[i] += counts[i - 1];
            }
            data = new int[counts[counts.length - 1]];
            cursor = new int[counts.length - 1];
            System.arraycopy(counts, 0, cursor, 0, cursor.length);
        }

        /** Pass two: place `value` in `row`. Rows fill in the order they are placed. */
        public void place(int row, int value) {
            if (counting) {
                throw new IllegalStateException("place() before prepare()");
            }
            data[cursor[row]++] = value;
        }

        /**
         * REFUSES A BUILDER THAT COUNTED EDGES AND NEVER PLACED THEM.
         *
         * Silently allocating the data array and handing it back would produce a CSR whose
         * rows are the right LENGTH and entirely zeroes -- so every key would appear to be
         * produced by recipe 0. That is a wrong graph that loads, indexes and plans, which
         * is the worst failure mode available here. An empty builder with nothing counted is
         * fine and stays legal, because a graph with no edges of some kind is ordinary.
         */
        public Csr build() {
            if (counting) {
                prepare();
                if (data.length > 0) {
                    throw new IllegalStateException(
                            data.length + " edges were counted and none were placed");
                }
            }
            return new Csr(counts, data);
        }
    }
}
