package io.github.jacoblasky.recipedump.plan;

/**
 * What counts as a WHOLE quantity, in the one place both surfaces ask.
 *
 * ONE SPELLING, AND THE DRIFT IT PREVENTS IS NOT HYPOTHETICAL (#252). Two callers decide
 * something from this predicate and they must agree:
 *
 *   - `plan.PlanJson.writeQuantity` picks the JSON token, `4` or `4.0`, which the 21 plan
 *     fixtures freeze byte for byte across 1,406 `per_run` occurrences.
 *   - `client.planner.NodeRowText.amount` picks the drawn string, `4` or `0.004`.
 *
 * If those two ever disagree about which values are whole, the wire says one thing and the row
 * beside it says another, on the same number, with both halves individually passing their own
 * tests. That is the adjacency defect a #190 screenshot caught on fluids: `934,400x` above
 * `934400 mB`, invisible in a diff because neither half was wrong alone.
 *
 * NON-FINITE IS NOT WHOLE, deliberately, and the consequence is a LOUD failure rather than a
 * quiet one. `NaN` and the infinities fall to the fractional branch, where gson's
 * `JsonWriter.value(double)` throws rather than writing a token no JSON parser accepts. A plan
 * carrying `NaN` is a defect upstream, and this is not the layer that should paper over it by
 * rounding to a plausible integer.
 */
public final class Quantities {

    private Quantities() {
    }

    /**
     * Whether `value` is an exact integer that a `(long)` cast carries without loss.
     *
     * THE RANGE CHECK IS NOT PEDANTRY, IT IS WHAT THE CALLERS ACTUALLY NEED. Both of them cast
     * to `long` immediately after asking, so a predicate answering only "is it integral" would
     * hand them a number the cast then silently changes: `(long) 1e19` saturates to
     * `Long.MAX_VALUE`, so the emitter would write 9,223,372,036,854,775,807 for a plan that
     * said 10,000,000,000,000,000,000 and no test on integrality would notice. The question
     * worth asking is "can this be written as a long", and that is what this answers.
     *
     * `Math.rint` RATHER THAN A CAST-AND-COMPARE for the integral half, since `(long) value ==
     * value` is exactly the saturating comparison described above and would answer true for
     * the case it cannot represent.
     *
     * The bound is written as a double comparison deliberately: `Long.MAX_VALUE` is not
     * representable as a double and rounds UP to 2^63 when converted, so `<=` against it would
     * admit 2^63 itself, which is one past the range. Strict `<` against the rounded bound is
     * the correct edge.
     */
    public static boolean isWhole(double value) {
        return !Double.isNaN(value)
               && !Double.isInfinite(value)
               && value == Math.rint(value)
               && value >= (double) Long.MIN_VALUE
               && value < (double) Long.MAX_VALUE;
    }
}
