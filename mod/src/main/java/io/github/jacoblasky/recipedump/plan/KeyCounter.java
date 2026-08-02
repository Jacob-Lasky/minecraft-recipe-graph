package io.github.jacoblasky.recipedump.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `collections.Counter` over key ids, with the one behaviour of it the wire format depends
 * on: {@link #mostCommon}.
 *
 * WHY THIS IS ITS OWN CLASS RATHER THAN A `HashMap` AND A `sort` AT EACH CALL SITE. Five of
 * the lists `Solver.solve` returns are `Counter.most_common()` output, and that order is part
 * of the contract the golden fixtures freeze. `most_common` is count DESCENDING with ties
 * broken by INSERTION order, and there are three ways to get that wrong in Java, none of
 * which fails loudly:
 *
 *   LinkedHashMap + a stable sort by count descending   correct
 *   HashMap + sort                                      right multiset, wrong order
 *   TreeMap                                             alphabetical, wrong differently
 *
 * Having exactly one implementation is what stops the fourth call site picking the wrong one.
 *
 * Counts are `long`. This pack has a recipe yielding 60,466,176 fruit, so a shopping-list
 * total is a multiplication away from wrapping an `int` into a negative quantity.
 */
final class KeyCounter {

    /** Insertion-ordered, because that IS the tiebreak. Never swap this for a HashMap. */
    private final LinkedHashMap<Integer, long[]> counts;

    KeyCounter() {
        this.counts = new LinkedHashMap<Integer, long[]>();
    }

    private KeyCounter(LinkedHashMap<Integer, long[]> counts) {
        this.counts = counts;
    }

    /** `counter[keyId] += n`, inserting on first touch exactly as Python's Counter does. */
    void add(int keyId, long n) {
        long[] slot = counts.get(keyId);
        if (slot == null) {
            counts.put(keyId, new long[] {n});
        } else {
            slot[0] += n;
        }
    }

    /**
     * `counter[keyId] -= n` for a key that MUST already be present.
     *
     * The inventory pool's key set is fixed for a solver's lifetime -- `Solver` indexes it
     * once by base key and never invalidates that index -- so an insert here would leave the
     * index describing a pool that no longer exists, and the symptom would be a wildcard
     * lookup quietly missing stock. Python pins the same invariant in
     * `TestPool.test_pool_key_set_is_fixed_for_a_solvers_lifetime`; this is the runtime half.
     */
    void subtractExisting(int keyId, long n) {
        long[] slot = counts.get(keyId);
        if (slot == null) {
            throw new IllegalStateException(
                    "the inventory pool's key set is fixed; nothing may insert key id "
                            + keyId + ". See Solver's base-key index.");
        }
        slot[0] -= n;
    }

    /** `counter.get(keyId, 0)`. */
    long get(int keyId) {
        long[] slot = counts.get(keyId);
        return slot == null ? 0L : slot[0];
    }

    boolean isEmpty() {
        return counts.isEmpty();
    }

    /** The key ids, in insertion order. Used to build the base-key index once. */
    Iterable<Integer> keys() {
        return counts.keySet();
    }

    /** `counter.copy()`: a snapshot that preserves insertion order, as Python's does. */
    KeyCounter copy() {
        LinkedHashMap<Integer, long[]> clone =
                new LinkedHashMap<Integer, long[]>(counts.size() * 2);
        for (Map.Entry<Integer, long[]> entry : counts.entrySet()) {
            clone.put(entry.getKey(), new long[] {entry.getValue()[0]});
        }
        return new KeyCounter(clone);
    }

    /**
     * `Counter.most_common()`: count descending, ties in insertion order.
     *
     * `Collections.sort` IS the right tool because it is guaranteed stable. DO NOT reach for
     * "sort ascending then reverse the list" -- it looks equivalent and is not. Measured
     * against CPython on a counter holding two 9s and three 5s inserted in a known order,
     * `sorted(..., reverse=True)` returns them in insertion order within each count while
     * `reversed(sorted(...))` returns each tied run BACKWARDS. Python's `reverse=True`
     * reverses the comparison, not the output, and so does the comparator below.
     */
    List<Entry> mostCommon() {
        List<Entry> entries = new ArrayList<Entry>(counts.size());
        for (Map.Entry<Integer, long[]> entry : counts.entrySet()) {
            entries.add(new Entry(entry.getKey(), entry.getValue()[0]));
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.count, a.count);
            }
        });
        return entries;
    }

    /** One `(key id, count)` pair out of {@link #mostCommon}. */
    static final class Entry {
        final int keyId;
        final long count;

        Entry(int keyId, long count) {
            this.keyId = keyId;
            this.count = count;
        }
    }
}
