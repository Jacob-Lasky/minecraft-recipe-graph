package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Proof that the fixture comparator does the one thing it exists to do.
 *
 * A helper whose whole purpose is to tolerate a formatting difference and catch a value
 * difference is worthless if it does either the wrong way round, and both mistakes are
 * invisible until a real fixture run -- one as a wall of false failures, the other as a green
 * suite hiding a broken port. The first two cases below are the pair that matters.
 */
public class JsonFixturesTest {

    @Test
    public void pythonAndJavaSpellingsOfTheSameDoubleCompareEqual() {
        // Real pairs from the 6,012-value measurement in JsonFixtures' javadoc: same 64-bit
        // pattern, different notation, 33% of the sample. A text comparison fails all of
        // these and reads as a numerical bug in the port.
        assertEquivalent("{\"c\":1e-09}", "{\"c\":1.0E-9}");
        assertEquivalent("{\"c\":5.2985852125019304e+159}",
                "{\"c\":5.2985852125019304E159}");
        assertEquivalent("{\"c\":-1.0781342458047461e-122}",
                "{\"c\":-1.0781342458047461E-122}");
        assertEquivalent("{\"c\":1}", "{\"c\":1.0}");
    }

    @Test
    public void aValueThatActuallyDiffersFailsAndSaysByHowMuch() {
        // The other half. An epsilon would hide this, which is why there is no tolerance
        // parameter: the point of a golden fixture is that the arithmetic reproduces exactly.
        String message = failureOf("{\"cost\":119.0}", "{\"cost\":119.00000000000001}");
        assertTrue(message, message.startsWith("$.cost: expected 119.0"));
        // The bit patterns are reported, because two doubles that print the same and differ
        // is exactly the case a reader cannot resolve from the decimal text.
        assertTrue(message, message.contains("bits "));
    }

    @Test
    public void arrayOrderIsComparedByPositionBecauseTheFixtureFreezesIt() {
        // A list with the right members in the wrong order is a real failure, not a
        // formatting one. See RecipeGraph's iteration-order section and #129.
        assertEquivalent("{\"need\":[\"a\",\"b\"]}", "{\"need\":[\"a\",\"b\"]}");
        String message = failureOf("{\"need\":[\"a\",\"b\"]}", "{\"need\":[\"b\",\"a\"]}");
        assertTrue(message, message.startsWith("$.need[0]:"));
    }

    @Test
    public void objectKeyOrderIsDeliberatelyNotCompared() {
        // Neither language promises it through a parse and no consumer reads a plan by field
        // position, so requiring it would fail on a difference that means nothing.
        assertEquivalent("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}");
    }

    @Test
    public void aMissingOrExtraFieldIsNamedRatherThanIgnored() {
        assertEquals("$: missing key qty", failureOf("{\"qty\":1}", "{}"));
        assertEquals("$: unexpected key extra", failureOf("{}", "{\"extra\":1}"));
    }

    @Test
    public void aLengthMismatchReportsBothLengthsBeforeAnyElement() {
        assertEquals("$.need: expected 2 elements, got 1",
                failureOf("{\"need\":[1,2]}", "{\"need\":[1]}"));
    }

    @Test
    public void nullAndATypeChangeAreBothRealDifferences() {
        assertTrue(failureOf("{\"m\":null}", "{\"m\":\"Squeezer\"}").startsWith("$.m:"));
        assertTrue(failureOf("{\"m\":\"1\"}", "{\"m\":1}").startsWith("$.m:"));
        assertEquivalent("{\"m\":null}", "{\"m\":null}");
    }

    @Test
    public void thePathNamesWhereInsideANestedPlanTheDifferenceIs() {
        // A fixture is thousands of lines and "they differ" is not a finding.
        String message = failureOf(
                "{\"tree\":{\"children\":[{\"qty\":1},{\"qty\":2}]}}",
                "{\"tree\":{\"children\":[{\"qty\":1},{\"qty\":3}]}}");
        assertTrue(message, message.startsWith("$.tree.children[1].qty:"));
    }

    private static void assertEquivalent(String expected, String actual) {
        JsonFixtures.assertEquivalent(expected, actual);
    }

    private static String failureOf(String expected, String actual) {
        try {
            JsonFixtures.assertEquivalent(expected, actual);
        } catch (AssertionError caught) {
            return caught.getMessage();
        }
        throw new AssertionError("expected a difference between " + expected + " and " + actual);
    }
}
