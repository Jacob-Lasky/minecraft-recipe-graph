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
        // change against every real plan. All 1,406 committed values are integral, so a
        // non-integral reading here is this change misfiring rather than a fixture being odd.
        int seen = 0;
        for (String name : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(name).flatten()) {
                if (node.runs() > 0L && node.perRun() > 0.0) {
                    assertEquals(name + " read a whole per_run as a fraction",
                                 node.perRun(), Math.rint(node.perRun()), 1e-9);
                    seen++;
                }
            }
        }
        // A loop over an empty population passes vacuously, which is the failure this repo
        // keeps finding; assert the denominator.
        assertTrue("no per_run values found; the fixtures or the reader moved", seen > 0);
    }
}
