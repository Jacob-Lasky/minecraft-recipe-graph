package io.github.jacoblasky.recipedump.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Outputs a run does NOT always yield, the Java half of #223.
 *
 * The dump's output-stack schema had no field for a yield chance, so a recipe producing its
 * product 10% of the time was recorded as producing it every run and the solver divided the
 * work by a number ten times too high. Every plan through one understated the runs needed and
 * therefore every input those runs consume.
 *
 * THE MIRROR OF #175 AND NOT A COPY OF IT. An input chance scales a cost the recipe CONSUMES;
 * an output chance scales the YIELD, which is a DIVISOR. So the arithmetic moves in two places
 * -- `Cost.relax` divides the ingredient term by the expected yield and `Solver.build` divides
 * the remaining demand by it -- and moving one alone would reintroduce #29's ranker-versus-
 * solver divergence rather than curing anything.
 *
 * THE PYTHON SIDE IS THE SPECIFICATION AND THIS MUST AGREE WITH IT, because the golden plan
 * fixtures compare the two field for field. `tests/test_schema_eight.py` is the same set of
 * assertions against `recipegraph`; if one of these changes, that one changes with it.
 *
 * AND THE GOLDEN GATE CANNOT STAND IN FOR THIS FILE, which is the reason it exists at all. The
 * fixtures' oracle is a SCHEMA-5 graph: it carries no `q` anywhere, so every chance in it is
 * 1.0 and a port that dropped this feature entirely would pass the gate on all 21 fixtures.
 * The same masking hid a float-narrowed consume chance for the whole of #175's life.
 */
public class ChanceOutputTest {

    /**
     * One recipe: `qty` of the widget per run, yielded `chance` of the time, for one material.
     *
     * The machine is left unnamed and the category is `minecraft.crafting`, so machine gating
     * contributes a fixed `base` to every price here and the only thing moving between cases
     * is the divisor.
     */
    private static RecipeGraph chanceGraph(int qty, double chance) {
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:widget"), "Widget");
        b.name(b.key("mod:material"), "Material");
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:material"));
        b.endSlot();
        b.output(b.key("mod:widget"), qty, chance);
        b.endRecipe("r:widget", "minecraft.crafting", null, "hei_dump", false, false);
        return b.build();
    }

    // -- the store column ------------------------------------------------------------------

    @Test
    public void theDefaultIsCertaintySoAnOldGraphIsUnchanged() {
        // The property that let the reader land ahead of the mod change: an absent `q` is 1.0,
        // so every graph any existing dump can produce plans exactly as it did. Getting this
        // backwards would make every output of every old graph unobtainable at once.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:out"), 1);
        b.endRecipe("r", "minecraft.crafting", null, "jar_json", false, false);
        RecipeGraph g = b.build();

        assertEquals(1.0, g.recipes().outputChanceAt(0), 0.0);
        assertEquals(1.0, g.recipes().expectedYield(0, g.keyId("mod:out")), 0.0);
    }

    @Test
    public void theChanceIsHELDASADOUBLEANDNotNarrowed() {
        // 0.001 IS THE VALUE THAT PROVES IT, and it is a value the pack really declares: the
        // observed range of `addItemOutput` chances runs 0.99 down to 0.001. Through a float
        // it comes back as 0.0010000000474974513, which is a different double from the one
        // python parsed out of the same `graph.json` -- and `PlanFixtureTest` compares the two
        // implementations' numbers with a ZERO delta. See `RecipeStore.outputChance`.
        RecipeGraph g = chanceGraph(1, 0.001);
        assertEquals("a float round trip would land 4.7e-11 away from this",
                0.001, g.recipes().outputChanceAt(0), 0.0);
    }

    @Test
    public void twoSlotsOfOneKeyKeepTwoChances() {
        // The reason the chance is a per-output COLUMN rather than a per-recipe number, and
        // the reason `model.Recipe.yield_chance` is a list rather than a dict keyed by output
        // key. 618 recipes in the reference graph name one output key more than once --
        // TechReborn's Industrial Grinder is the population, four secondary slots of the same
        // chunk -- and one chance per key would silently keep one of them.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:b"), 10, 0.5);
        b.output(b.key("mod:b"), 4, 0.25);
        b.endRecipe("r", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();

        assertEquals(0.5, g.recipes().outputChanceAt(0), 0.0);
        assertEquals(0.25, g.recipes().outputChanceAt(1), 0.0);
        assertEquals(10 * 0.5 + 4 * 0.25,
                g.recipes().expectedYield(0, g.keyId("mod:b")), 0.0);
    }

    @Test
    public void theNominalYieldIgnoresEveryChanceAndTheExpectedOneDoesNot() {
        // The pair `Solver.build` reports as a ratio. Reading either where the other is meant
        // is #223 undone: the nominal one IS the pre-#223 yield.
        RecipeGraph g = chanceGraph(4, 0.25);
        int widget = g.keyId("mod:widget");
        assertEquals(4L, g.recipes().nominalYield(0, widget));
        assertEquals(1.0, g.recipes().expectedYield(0, widget), 0.0);
    }

    @Test
    public void expectedYieldIsZeroForAKeyTheRecipeDoesNotMake() {
        // Callers must handle this rather than dividing by it. `Solver.build` does, and says
        // so; this pins the contract that makes its guard meaningful.
        RecipeGraph g = chanceGraph(1, 1.0);
        assertEquals(0.0, g.recipes().expectedYield(0, g.keyId("mod:material")), 0.0);
    }

    // -- the cost side ---------------------------------------------------------------------

    @Test
    public void aHalfChanceOutputCostsTheSameAsHalfTheYield() {
        // `scaledQty` is where the chance lands, so this is the whole cost-side claim in one
        // assertion: half the yield, twice the divisor. Mirrors
        // `test_schema_eight.TheYieldIsTheDivisorTest`.
        RecipeGraph g = chanceGraph(1, 1.0);
        int iron = g.keyId("mod:widget");
        assertEquals(Cost.scaledQty(g, iron, 2),
                Cost.scaledQty(g, iron, 4, 0.5), 0.0);
    }

    @Test
    public void aZeroChanceOutputDoesNotDivideByZero() {
        // A recipe that never yields its output would otherwise manufacture an infinitely
        // cheap resource. The floor that already existed for sub-millibucket fluids catches
        // this by construction, which is why the chance belongs INSIDE `scaledQty` rather
        // than at the call site.
        RecipeGraph g = chanceGraph(1, 1.0);
        assertTrue(Cost.scaledQty(g, g.keyId("mod:widget"), 64, 0.0) > 0.0);
    }

    @Test
    public void theDefaultChanceLeavesEveryExistingPriceWhereItWas() {
        RecipeGraph g = chanceGraph(1, 1.0);
        int item = g.keyId("mod:widget");
        for (long qty : new long[] {1L, 64L, 1000L}) {
            assertEquals("x" + qty + " must be untouched when nothing is uncertain",
                    Cost.scaledQty(g, item, qty), Cost.scaledQty(g, item, qty, 1.0), 0.0);
        }
    }

    @Test
    public void aChanceRecipePricesItsOutputHigherThanACertainOne() {
        // END TO END THROUGH THE RELAXATION, not just through `scaledQty`, because the whole
        // defect was that the chance never reached the divisor. #223 only ever moves prices
        // UP, so this asserts the direction as well as the inequality.
        double certain = priceOfWidget(chanceGraph(1, 1.0));
        double tenth = priceOfWidget(chanceGraph(1, 0.1));
        assertTrue("a 10% yield must not be as cheap as a certain one: " + tenth
                + " against " + certain, tenth > certain);
    }

    private static double priceOfWidget(RecipeGraph g) {
        return Cost.estimate(g, new CostInputs()).cost(g.keyId("mod:widget"));
    }

    // -- the solver side -------------------------------------------------------------------

    @Test
    public void aTenthChanceRecipeNeedsTenTimesTheRuns() {
        // The headline number of the issue. One widget from a recipe that delivers one widget
        // a tenth of the time is ten runs, and it was one.
        PlanNode tree = planFor(chanceGraph(1, 0.1), 1);
        assertEquals(Long.valueOf(10), tree.runs);
        assertEquals(0.1, tree.perRun.doubleValue(), 0.0);
    }

    @Test
    public void theRunsFOLLOWTheInputsSoTheWholeSubtreeScales() {
        // The reason the issue is a P1 rather than a display bug: the run count multiplies
        // every input those runs consume, so a plan through a chance recipe understated its
        // whole shopping list and not just one number.
        PlanNode tree = planFor(chanceGraph(1, 0.1), 1);
        assertEquals("ten runs need ten materials", 10L,
                tree.children().get(0).need());
    }

    @Test
    public void theYieldChanceIsReportedAsTheRatioOfExpectedToNominal() {
        // A plan that quietly presents an expectation as a certainty is the honest-number
        // problem #181 opened; this is the field a renderer keys on to say "one run in ten".
        PlanNode tree = planFor(chanceGraph(4, 0.25), 1);
        assertEquals(1.0, tree.perRun.doubleValue(), 0.0);   // 4 x 0.25
        assertEquals(0.25, tree.yieldChance.doubleValue(), 0.0);
    }

    @Test
    public void aCertainRecipeCarriesNoYieldChanceAtAll() {
        // OMITTED AT CERTAINTY, so every plan of a pre-#223 graph is unchanged and the field's
        // PRESENCE is the signal. Written as its own case because `PlanJson` would happily
        // emit `"yield_chance": 1.0` on all 571 fixture nodes and every fixture would move.
        assertNull(planFor(chanceGraph(4, 1.0), 1).yieldChance);
    }

    @Test
    public void aRecipeThatNeverYieldsTheKeyFallsBackToTheNominalYield() {
        // No such recipe exists in the reference pack -- all 835 `addItemOutput` chances are
        // above zero -- so this is a guard against a future dump. Dividing by zero here would
        // report ONE run for any demand, which is a plan that is confidently wrong and looks
        // ordinary; falling back to the nominal yield keeps it finite and visibly wrong.
        PlanNode tree = planFor(chanceGraph(4, 0.0), 8);
        assertEquals(Long.valueOf(2), tree.runs);
        assertEquals(4.0, tree.perRun.doubleValue(), 0.0);
    }

    @Test
    public void theExpanderSUMSEverySlotThatMakesTheKey() {
        // WHAT THIS USED TO GET WRONG, and it is a pre-existing divergence rather than
        // anything #223 introduced: `build` read the FIRST matching output slot and stopped,
        // while `offerShape` already summed them. The two therefore disagreed about what a run
        // produces on the 618 reference-graph recipes naming one output key twice. Both call
        // `RecipeStore.expectedYield` now.
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:dust"), "Dust");
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:ore"));
        b.endSlot();
        // The Industrial Grinder shape: several slots of one key, the first the largest, so a
        // first-match read is INDISTINGUISHABLE from a correct one on the run count unless the
        // later slots are counted.
        b.output(b.key("mod:dust"), 45);
        b.output(b.key("mod:dust"), 23);
        b.endRecipe("r:grind", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();

        PlanNode tree = planFor(g, "mod:dust", 68);
        assertEquals("68 of a 68-per-run recipe is one run, not two", Long.valueOf(1),
                tree.runs);
        assertEquals(68.0, tree.perRun.doubleValue(), 0.0);
    }

    // -- the ceiling, and the routing between the two of them ---------------------------------

    /**
     * The exact integer ceiling this method used before #223, and still uses when certain.
     * Spelled here rather than reached through `Solver` because the sweep below is about the
     * ARITHMETIC, exactly as `test_schema_eight` sweeps python's two spellings against each
     * other. Kept identical to the expression in {@link Solver}; if one moves, both move.
     */
    private static long integerCeiling(long remainder, long nominal) {
        return -Math.floorDiv(-remainder, nominal);
    }

    /** What the naive float port computes. */
    private static long floatCeiling(long remainder, double perRun) {
        return (long) Math.ceil(remainder / perRun);
    }

    @Test
    public void theUNROUTEDFloatCeilingWOULDPlanOneRunTooMany() {
        // THE WITNESS, AND THE ONLY ONE IN THIS FILE. Revert `Solver.build` to the
        // unconditional `(long) Math.ceil(remainder / perRun)` and every other ceiling test
        // here stays GREEN -- they are guards, because the two ceilings agree everywhere in
        // the ranges those tests cover. This one goes RED. Checked by computing both
        // ceilings over the reverted path rather than by assuming.
        //
        // DRIVEN THROUGH THE SOLVER, not through the two helpers below, so what is under test
        // is the ROUTING and not a pair of expressions this file wrote for itself. Both
        // magnitudes are reachable: 631,685,691 fits the `int` an output quantity is stored
        // in, and the demand is a `long` because `need` multiplies down a chain.
        //
        // ASSERTED AGAINST A LITERAL, deliberately. Writing the expected value as
        // `integerCeiling(...)` would make the test read its answer out of the same
        // expression it is testing, and it would stay green with the routing removed.
        PlanNode tree = planFor(chanceGraph(631685691, 1.0), 1355919244519453614L);
        assertEquals("the integer ceiling is 2146509354; the double divide lands one bit"
                        + " above an exact integer and Math.ceil takes it to 2146509355",
                Long.valueOf(2146509354L), tree.runs);
    }

    @Test
    public void theTwoCeilingsAgreeOnEveryCertainCaseInAWideSweep() {
        // A GUARD, NOT A WITNESS: this passes with the routing reverted, because the two
        // ceilings agree at every point in this grid. It is worth keeping anyway -- it is the
        // mirror of `ACertainRecipePlansExactlyAsItAlwaysDidTest` in python and it pins the
        // property the routing rests on -- but the test that would actually catch the defect
        // is `theUNROUTEDFloatCeilingWOULDPlanOneRunTooMany` above.
        //
        // #223 must be a no-op on every graph built before schema 8, and those carry a chance
        // of 1.0 everywhere, so a run count that moved here would be churn on recipes this
        // issue does not touch -- indistinguishable, in a fixture diff, from the real change.
        for (long nominal = 1; nominal <= 64; nominal++) {
            for (long remainder = 1; remainder < 400; remainder++) {
                assertEquals("runs must not move for a certain recipe: " + remainder + " / "
                                + nominal,
                        integerCeiling(remainder, nominal),
                        floatCeiling(remainder, (double) nominal));
            }
        }
    }

    @Test
    public void theTwoCeilingsReallyDoDivergeAtMagnitude() {
        // ALSO A GUARD. It pins the measured arithmetic the witness above rests on, in BOTH
        // directions, so that a reader can see the property is real without running the
        // solver -- but it exercises the two helpers below rather than `Solver`, so it does
        // not go red when the routing is removed. The first pair is the same case the witness
        // drives end to end.
        //
        // `remainder` really can get here. It is a demand multiplied down a chain, and this
        // pack has a recipe yielding 60,466,176 at once, which is why the parameter is a
        // `long` in the first place.
        assertEquals("the float ceiling is one run TOO MANY here, which is the direction"
                        + " `3a21fd5` was written about",
                2146509355L, floatCeiling(1355919244519453614L, 631685691.0));
        assertEquals(2146509354L, integerCeiling(1355919244519453614L, 631685691L));

        assertEquals("and one run TOO FEW here, so a tolerance would not have fixed it",
                7650273286L, floatCeiling(7432069796801169483L, 971477687.0));
        assertEquals(7650273287L, integerCeiling(7432069796801169483L, 971477687L));
    }

    @Test
    public void theFloatPathIsEnteredONLYWhenSomethingIsGenuinelyUncertain() {
        // The condition `Solver.build` routes on, asserted directly: expected equals nominal
        // EXACTLY when nothing is uncertain, because the multiply is by exactly 1.0 and 1.0
        // is exact in IEEE754. Mirrors python's
        // `test_and_the_float_path_is_only_taken_when_something_is_uncertain`.
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:b"), 7);
        b.output(b.key("mod:b"), 5);
        b.endRecipe("certain", "minecraft.crafting", null, "hei_dump", false, false);
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:a"));
        b.endSlot();
        b.output(b.key("mod:c"), 7);
        b.output(b.key("mod:c"), 5, 0.5);
        b.endRecipe("uncertain", "minecraft.crafting", null, "hei_dump", false, false);
        RecipeGraph g = b.build();

        int certain = g.keyId("mod:b");
        assertEquals(12.0, g.recipes().expectedYield(0, certain), 0.0);
        assertEquals(12L, g.recipes().nominalYield(0, certain));
        assertTrue("a certain recipe must take the integer path",
                g.recipes().expectedYield(0, certain) == g.recipes().nominalYield(0, certain));

        int uncertain = g.keyId("mod:c");
        assertEquals(9.5, g.recipes().expectedYield(1, uncertain), 0.0);
        assertEquals(12L, g.recipes().nominalYield(1, uncertain));
        assertTrue("an uncertain recipe must take the float path",
                g.recipes().expectedYield(1, uncertain) != g.recipes().nominalYield(1, uncertain));
    }

    @Test
    public void aCertainRecipePlansExactlyTheRunsItAlwaysDid() {
        // A GUARD, and the ordinary-scale control for the witness: 5 of a 4-per-run recipe
        // was two runs before #223 and must still be two. Both ceilings give 2 here, so this
        // stays green with the routing reverted; what it protects is the everyday case
        // continuing to work while the witness protects the edge.
        PlanNode tree = planFor(chanceGraph(4, 1.0), 5);
        assertEquals(Long.valueOf(2), tree.runs);
        assertNull("and it must carry no uncertainty mark at all", tree.yieldChance);
    }

    // -- the offer shape ---------------------------------------------------------------------

    @Test
    public void twoOffersThatDifferONLYINYIELDChanceAreNotTheSameOffer() {
        // #181's tie report rests on `offerShape`, and #223 put the EXPECTED yield in it.
        // Two recipes that both say "4 widgets" are not the same offer when one of them
        // delivers a quarter of the time, and calling that pair an arbitrary tie would tell a
        // player a fourfold difference is a coin toss.
        //
        // ASSERTED ON THE SHAPE DIRECTLY BECAUSE A PLAN CANNOT SHOW IT. The chance also moves
        // the PRICE, so the two never score equally and `interchangeable` is never written for
        // either -- which means a shape that had stopped discriminating would look exactly
        // like this one from outside.
        RecipeGraph g = twoOfferGraph(4, 1.0, 4, 0.25);
        Solver solver = new Solver.Builder(g).build();
        int widget = g.keyId("mod:widget");
        assertTrue("a certain offer and a quarter-chance one must not share a shape",
                !solver.offerShape(0, widget).equals(solver.offerShape(1, widget)));
    }

    @Test
    public void twoIdenticalOffersSTILLShareAShape() {
        // The control. Without it the assertion above would pass on a shape that had become
        // unique per recipe, which is the failure that makes #181's whole measurement vacuous.
        RecipeGraph g = twoOfferGraph(4, 0.25, 4, 0.25);
        Solver solver = new Solver.Builder(g).build();
        int widget = g.keyId("mod:widget");
        assertEquals(solver.offerShape(0, widget), solver.offerShape(1, widget));
    }

    /** Two recipes for the widget from one material, differing only in quantity and chance. */
    private static RecipeGraph twoOfferGraph(int qtyA, double chanceA,
                                             int qtyB, double chanceB) {
        GraphBuilder b = new GraphBuilder();
        b.name(b.key("mod:widget"), "Widget");
        b.name(b.key("mod:material"), "Material");
        for (int i = 0; i < 2; i++) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:material"));
            b.endSlot();
            b.output(b.key("mod:widget"), i == 0 ? qtyA : qtyB, i == 0 ? chanceA : chanceB);
            b.endRecipe("r:" + i, "minecraft.crafting", null, "hei_dump", false, false);
        }
        return b.build();
    }

    private static PlanNode planFor(RecipeGraph g, long qty) {
        return planFor(g, "mod:widget", qty);
    }

    private static PlanNode planFor(RecipeGraph g, String key, long qty) {
        return new Solver.Builder(g).build().solve(g.keyId(key), qty).tree;
    }
}
