package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
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
    public void aTiedRunKeepsInsertionOrderWhenItDisagreesWithTheKeyIds() {
        // THE ONE ORDERING CASE IN THIS FILE THAT CAN FAIL, and every other one was written
        // before anybody noticed that. This counter is keyed by `int`, so for small
        // non-negative Integer keys `HashMap`'s spread function is the identity and a table
        // that stays at 16 buckets iterates them ASCENDING -- which is exactly the order the
        // cases above insert them in (0..5, 10..12, 1..2, 4..6). Swapping the LinkedHashMap
        // for a HashMap, or for a TreeMap<Integer>, changes not one of their expected values.
        //
        // MEASURED BY DOING IT, not reasoned from java.util: with the field swapped to
        // `HashMap` this case fails (it returns [3=1, 7=1, 9=1]) and all five of the others
        // still pass; with `TreeMap<Integer>` the same. See #192 and the PR that closed it.
        KeyCounter counter = new KeyCounter();
        counter.add(9, 1);
        counter.add(3, 1);
        counter.add(7, 1);
        assertEquals("[9=1, 3=1, 7=1]", render(counter.mostCommon()));
    }

    @Test
    public void theTiedFixtureIsOrderedInNeitherDirection() {
        // The guard on the guard, in the idiom of
        // `RecipeGraphOrderTest.theFixtureItselfIsNotAccidentallySorted`. 9, 3, 7 is neither
        // ascending nor descending, and both halves matter: ascending is what a HashMap and a
        // TreeMap<Integer> reproduce, descending is what "sort ascending then reverse the
        // list" reproduces. If a later edit tidies those ids into either order, the case above
        // silently stops discriminating between a correct implementation and both wrong ones,
        // and would keep passing forever. The literals are restated rather than shared so this
        // fails on the tidy-up rather than following it.
        List<Integer> expected = Arrays.asList(9, 3, 7);
        assertFalse("the tied ids must not be ascending", isOrdered(expected, 1));
        assertFalse("the tied ids must not be descending", isOrdered(expected, -1));
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

    /** True when `ids` runs monotonically in `direction` (+1 ascending, -1 descending). */
    private static boolean isOrdered(List<Integer> ids, int direction) {
        for (int i = 1; i < ids.size(); i++) {
            if (direction * ids.get(i - 1).compareTo(ids.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }

    private static String render(List<KeyCounter.Entry> entries) {
        List<String> parts = new ArrayList<String>();
        for (KeyCounter.Entry entry : entries) {
            parts.add(entry.keyId + "=" + entry.count);
        }
        return parts.toString();
    }
}
