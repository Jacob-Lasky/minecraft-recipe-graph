package io.github.jacoblasky.recipedump.plan;

/**
 * What counts as a WHOLE quantity, in the one place both surfaces ask.
 *
 * ONE SPELLING, AND THE DRIFT IT PREVENTS IS NOT HYPOTHETICAL (#252). Two callers decide
 * something from this predicate and they must agree:
 *
 *   - `plan.PlanJson.writePerRun` uses it as ONE OF TWO CLAUSES when picking the JSON token
 *     `4` or `4.0`, which the plan fixtures freeze byte for byte.
 *   - `client.planner.NodeRowText.amount` uses it ALONE when picking the drawn string, `4` or
 *     `0.004`.
 *
 * THE EMITTER DOES NOT DECIDE ON THIS PREDICATE ALONE, AND THE JAVADOC SAID IT DID UNTIL #223
 * CORRECTED IT. The wire rule is `isWhole(perRun) && node.yieldChance == null`, because
 * `Recipe.expected_yield` returns an int only when every contributing slot is CERTAIN and
 * refuses `float(total).is_integer()` in as many words: two slots of 4 at a chance of 0.5 sum
 * to exactly 4.0, and that is still an expectation that must not masquerade as a guaranteed
 * count. On that recipe python writes `4.0` and this predicate alone would write `4`. Here
 * `isWhole` is the CAST GUARD rather than the rule: absence of a chance is not proof of
 * wholeness on hand-written JSON, and a `per_run` of 0.5 with no `yield_chance` would cast to
 * a long and write `0`, which is data loss rather than a display defect.
 *
 * THE RENDER SIDE DELIBERATELY DOES NOT TAKE THE NARROW CLAUSE, and that asymmetry is the
 * point rather than an oversight. An expected four and a certain four DRAW THE SAME, because
 * certainty reaches the reader through the `yields ...% of the time` phrase beside the number
 * rather than through the number's own formatting. Adding `yieldChance == null` there would
 * print `4.0` in a row that already says how often the recipe works, which is noise. Two
 * callers, one predicate, two rules built on it -- which is exactly what this javadoc is for
 * and exactly what goes stale silently.
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
