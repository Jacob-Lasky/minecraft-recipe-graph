package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The wholeness predicate the emitter and the row renderer BOTH ask.
 *
 * Tested on its own rather than only through its two callers, because the value of this class
 * is that there is exactly one answer. A bug here is a disagreement between the wire and the
 * panel, and that shows up as a formatting oddity nobody attributes to a predicate.
 */
public class QuantitiesTest {

    @Test
    public void wholeNumbersAreWhole() {
        assertTrue(Quantities.isWhole(0.0));
        assertTrue(Quantities.isWhole(1.0));
        assertTrue(Quantities.isWhole(4.0));
        assertTrue(Quantities.isWhole(-3.0));
        // The pack's largest real yield, which is the reason `runs` is a long.
        assertTrue(Quantities.isWhole(60_466_176.0));
    }

    @Test
    public void theChancesThisPackActuallyCarriesAreNotWhole() {
        // #223's measured range: 834 of 835 `addItemOutput` chances are fractional, spanning
        // 0.99 down to 0.001. Every one of these must take the fractional branch or the
        // emitter writes it as an integer and the yield vanishes.
        assertFalse(Quantities.isWhole(0.99));
        assertFalse(Quantities.isWhole(0.5));
        assertFalse(Quantities.isWhole(0.004));
        assertFalse(Quantities.isWhole(0.001));
    }

    @Test
    public void nonFiniteIsNotWholeSoItFailsLoudlyRatherThanRounding() {
        // These take the fractional branch, where gson refuses to write the token at all. A
        // plan carrying NaN is a defect upstream and this is not the layer to launder it into
        // a plausible integer.
        assertFalse(Quantities.isWhole(Double.NaN));
        assertFalse(Quantities.isWhole(Double.POSITIVE_INFINITY));
        assertFalse(Quantities.isWhole(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void anIntegralValuePastLongRangeIsNotWhole() {
        // FOUND BY WRITING THIS TEST. 1e19 is integral as a double, so a predicate that asked
        // only about integrality answered true, and both callers cast to long straight
        // afterwards: the emitter would have written 9,223,372,036,854,775,807 for a plan that
        // said 10,000,000,000,000,000,000. The question is "can this be written as a long".
        double past = 1.0e19;
        assertFalse("past long range must not be called whole", Quantities.isWhole(past));
        // The saturating cast, demonstrated rather than asserted from memory.
        assertFalse("(long) 1e19 does not round-trip", (long) past == past);
    }

    @Test
    public void theEdgeOfLongRangeIsNotAdmittedByARoundedBound() {
        // `Long.MAX_VALUE` is not representable as a double and converts UP to 2^63, so a `<=`
        // bound would admit 2^63 itself, which is one past what a long holds. 2^63 as a double
        // is exactly that rounded value, so this is the case a loose bound gets wrong.
        assertFalse("2^63 is one past long range", Quantities.isWhole(9.223372036854775807e18));
        // Just inside, and genuinely representable.
        assertTrue(Quantities.isWhole(9.0e18));
    }
}
