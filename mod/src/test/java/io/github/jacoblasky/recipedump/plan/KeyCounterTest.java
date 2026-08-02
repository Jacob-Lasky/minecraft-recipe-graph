package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * `Counter.most_common()`, which is the contract five of the plan's lists are built on.
 *
 * Worth its own test file because every wrong answer here is a passing build. The multiset is
 * right, the counts are right, and the ORDER is different -- so nothing fails until a golden
 * fixture does, at which point the diff points at a list of items and not at the map type
 * that produced them.
 */
public class KeyCounterTest {

    @Test
    public void countsDescendWithTiesInInsertionOrder() {
        // The exact case measured against CPython:
        //   >>> c = Counter(); for k, n in [...]: c[k] += n
        //   >>> c.most_common()
        //   [('first_9', 9), ('second_9', 9), ('first_5', 5), ('second_5', 5),
        //    ('third_5', 5), ('only_1', 1)]
        // Key ids stand in for the names: 0=first_5, 1=first_9, 2=second_5, 3=only_1,
        // 4=third_5, 5=second_9.
        KeyCounter counter = new KeyCounter();
        counter.add(0, 5);
        counter.add(1, 9);
        counter.add(2, 5);
        counter.add(3, 1);
        counter.add(4, 5);
        counter.add(5, 9);
        assertEquals("[1=9, 5=9, 0=5, 2=5, 4=5, 3=1]", render(counter.mostCommon()));
    }

    @Test
    public void aTiedRunIsNotReversed() {
        // The specific wrong answer this class exists to prevent. "Sort ascending, then
        // reverse the list" produces the right multiset and turns each tied run BACKWARDS;
        // Python's `reverse=True` reverses the COMPARISON and keeps the run in insertion
        // order. Measured: `reversed(sorted(...))` on the fixture above gives
        // second_9 before first_9 and third_5 before first_5.
        KeyCounter counter = new KeyCounter();
        counter.add(10, 7);
        counter.add(11, 7);
        counter.add(12, 7);
        assertEquals("[10=7, 11=7, 12=7]", render(counter.mostCommon()));
    }

    @Test
    public void insertionOrderIsFirstTouchAndNotLastTouch() {
        // `counter[k] += n` on an existing key must not move it to the back. It does not in
        // Python, because a dict keeps a key's original position on reassignment, and it does
        // not here for the same reason -- but only as long as this stays a LinkedHashMap
        // without access ordering.
        KeyCounter counter = new KeyCounter();
        counter.add(1, 3);
        counter.add(2, 3);
        counter.add(1, 0);      // touched again, same count
        assertEquals("[1=3, 2=3]", render(counter.mostCommon()));
    }

    @Test
    public void aCopyPreservesInsertionOrderAndDoesNotAlias() {
        // Backtracking restores a snapshot, so a copy that lost the order would reorder the
        // shopping list of any plan that backtracked -- and it would do it only sometimes.
        KeyCounter counter = new KeyCounter();
        counter.add(4, 1);
        counter.add(5, 1);
        counter.add(6, 1);
        KeyCounter copy = counter.copy();
        counter.add(4, 100);
        assertEquals("the copy must not see a later write", "[4=1, 5=1, 6=1]",
                render(copy.mostCommon()));
        assertEquals("[4=101, 5=1, 6=1]", render(counter.mostCommon()));
    }

    @Test
    public void countsAreLongBecauseThisPackOverflowsAnInt() {
        // The pack has an Enchanted Greenhouse recipe yielding 60,466,176 fruit. Two of those
        // in a shopping list overflow a 32-bit total into a negative quantity.
        KeyCounter counter = new KeyCounter();
        counter.add(1, 60466176L);
        counter.add(1, 60466176L * 100);
        assertEquals(60466176L * 101, counter.get(1));
    }

    @Test
    public void nothingMayInsertIntoTheInventoryPool() {
        // The pool's base-key index is built once and never invalidated, which is only safe
        // while the key set is fixed. Python pins the same invariant in
        // `TestPool.test_pool_key_set_is_fixed_for_a_solvers_lifetime`.
        KeyCounter pool = new KeyCounter();
        pool.add(1, 10);
        pool.subtractExisting(1, 4);
        assertEquals(6, pool.get(1));
        try {
            pool.subtractExisting(2, 1);
            fail("subtracting an absent key must throw rather than insert it");
        } catch (IllegalStateException expected) {
            // The message has to name the index, because the symptom of a silent insert is a
            // wildcard lookup quietly missing stock somewhere else entirely.
            org.junit.Assert.assertTrue(expected.getMessage(),
                    expected.getMessage().contains("base-key index"));
        }
    }

    private static String render(List<KeyCounter.Entry> entries) {
        List<String> parts = new ArrayList<String>();
        for (KeyCounter.Entry entry : entries) {
            parts.add(entry.keyId + "=" + entry.count);
        }
        return parts.toString();
    }
}
