package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanEntry;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.PlanResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;

import org.junit.Test;

/**
 * `per_run` and `yield_chance` survive the emitter and the reader, in both directions.
 *
 * THE ROUND TRIP IS THE SEAM AND THAT IS WHY THIS TEST GOES THROUGH BOTH HALVES.
 * `PlannerEntry.planFor` is `PlanJson.readResult(planner.resultJson())`, and its javadoc
 * forbids replacing that with a field-by-field adapter, so the in-game path really is
 * `plan.PlanJson.toJson` followed by `client.planner.PlanJson.readResult`. Before #252 BOTH
 * halves truncated, the writer through `.longValue()` and the reader through `getAsLong()`, so
 * a test exercising one half alone would have passed against the other half's bug.
 *
 * THE PRE-#252 BEHAVIOUR WAS MEASURED RATHER THAN ASSUMED. A node carrying
 * `"per_run": 0.001` read back as `0` with NO exception thrown, which is why this is a
 * silent-wrong-number defect rather than a loud one, and why the assertions below check a
 * VALUE rather than merely that nothing was raised.
 */
public class PerRunRoundTripTest {

    private static PlanResult resultWith(Double perRun, Double yieldChance) {
        PlanResult r = new PlanResult();
        r.target = "minecraft:iron_ingot";
        r.targetName = "Iron Ingot";
        r.qty = 1000L;
        r.shoppingList = new ArrayList<PlanEntry>();
        r.usedFromStock = new ArrayList<PlanEntry>();
        r.fromSources = new ArrayList<PlanEntry>();
        r.tokensNeeded = new ArrayList<PlanEntry>();
        r.fromEmc = new ArrayList<PlanEntry>();
        r.machinesToBuild = new ArrayList<PlanResult.MachineToBuild>();
        r.nodes = 1;
        r.tree = new PlanNode.Builder()
                .key("minecraft:iron_ingot")
                .label("Iron Ingot")
                .need(Long.valueOf(1000L))
                .status("craft")
                .recipe("r1")
                .runs(Long.valueOf(1000L))
                .perRun(perRun)
                .yieldChance(yieldChance)
                .build();
        return r;
    }

    @Test
    public void aFractionalPerRunSurvivesTheWriterAndTheReader() {
        String json = io.github.jacoblasky.recipedump.plan.PlanJson
                .toJson(resultWith(Double.valueOf(0.001), Double.valueOf(0.001)));
        // THE WIRE IS CHECKED BEFORE THE READER, so a writer that truncated to `0` fails here
        // rather than being masked by a reader that would have truncated the same way.
        assertTrue("writer dropped the fraction: " + json, json.contains("\"per_run\": 0.001"));
        assertTrue("writer dropped yield_chance: " + json,
                   json.contains("\"yield_chance\": 0.001"));

        PlanView plan = PlanJson.readResult(json);
        assertEquals(0.001, plan.tree().perRun(), 1e-9);
        assertEquals(0.001, plan.tree().yieldChance(), 1e-9);
    }

    @Test
    public void anIntegralPerRunStaysAnIntegerOnTheWire() {
        // WHY THE EXACT BYTES MATTER: `tests/fixtures/plan/*.json` carry 1,406 `per_run`
        // occurrences and every one is an integer. Writing `4.0` would rewrite all of them, so
        // real movement in a later diff would be buried in the churn. This assertion is what
        // keeps #252's emitter change at zero fixture cost.
        String json = io.github.jacoblasky.recipedump.plan.PlanJson
                .toJson(resultWith(Double.valueOf(4.0), null));
        assertTrue("integral per_run should write as `4`, got: " + json,
                   json.contains("\"per_run\": 4"));
        assertFalse("integral per_run must not write as a double: " + json,
                    json.contains("\"per_run\": 4.0"));
        // Omitted rather than `"yield_chance":null`, per this emitter's stated omission rule.
        assertFalse("absent yield_chance must be omitted: " + json,
                    json.contains("yield_chance"));

        assertEquals(4.0, PlanJson.readResult(json).tree().perRun(), 1e-9);
    }

    @Test
    public void everyCommittedFixtureStillReadsItsPerRunUnchanged() {
        // THE POPULATION GUARD. The two tests above are hand-built nodes; this one holds the
        // change against every real plan.
        //
        // "ALL OF THEM ARE INTEGRAL" WAS TRUE AND WAS AN ARTIFACT (#280). It held on every
        // oracle this repository ever had, and only because all of them predated chance
        // outputs: a node yielded 20% of the time has a per-run yield of 0.2, and until
        // `graph-s8b` no graph carried the `q` that produces one. So the old assertion could
        // not have failed, which is not the same as it being right -- the same shape as a
        // fixture that cannot exercise its own case.
        //
        // THE REAL RULE IS SHARPER AND IS NOW TESTABLE: a per_run is integral UNLESS the node
        // is chance-yielded, and then it is exactly the fraction the chance implies. Measured
        // across the committed set: 8 fractional values, 8 of them carrying `yield_chance`,
        // and ZERO fractional without one. That correspondence is the assertion; a fractional
        // per_run on a node with no chance really would be the reader misfiring.
        int seen = 0;
        int chanced = 0;
        for (String name : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(name).flatten()) {
                if (node.runs() > 0L && node.perRun() > 0.0) {
                    if (node.yieldChance() < 1.0) {
                        chanced++;
                    } else {
                        assertEquals(name + " read a whole per_run as a fraction",
                                     node.perRun(), Math.rint(node.perRun()), 1e-9);
                    }
                    seen++;
                }
            }
        }
        // A loop over an empty population passes vacuously, which is the failure this repo
        // keeps finding; assert the denominator.
        assertTrue("no per_run values found; the fixtures or the reader moved", seen > 0);
        // AND ASSERT THE EXEMPTION IS REACHED, or the branch above is a way of not testing.
        // Zero here means the fixtures went back to an oracle with no chance outputs and the
        // rule has quietly stopped being exercised -- which is exactly how the old version
        // passed for as long as it did.
        assertTrue("no chance-yielded nodes found, so the fractional case is untested and"
                   + " this guard has gone back to asserting the artifact", chanced > 0);
    }
}
