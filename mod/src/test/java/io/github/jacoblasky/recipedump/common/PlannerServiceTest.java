package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.PlanResult;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * A plan run end to end on the common side: scenario, cost table, solver, JSON.
 *
 * WHAT THIS COVERS THAT THE GOLDEN GATE DOES NOT. `PlanFixtureTest` proves the solver agrees
 * with Python when handed a scenario read from a fixture. It says nothing about the path the
 * GAME takes to get there -- building the document, finding a graph, pricing once and reusing
 * it, and surviving a target nobody has. Those are this class's, and every one of them is a
 * way to produce no plan or a wrong one without the solver being at fault.
 *
 * The two share `ScenarioInputs` deliberately, so what is left to test here is the plumbing
 * rather than the planning.
 */
public class PlannerServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String saved;

    @Before
    public void isolate() throws IOException {
        saved = System.getProperty(GraphSource.PROPERTY);
        GraphService.get().reset();
        PlannerService.get().reset();
        File file = new File(folder.getRoot(), "graph.json");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(GraphDocuments.TINY.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        System.setProperty(GraphSource.PROPERTY, file.getPath());
    }

    @After
    public void restore() {
        if (saved == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, saved);
        }
        GraphService.get().reset();
        PlannerService.get().reset();
    }

    private void loadGraph() throws InterruptedException {
        GraphService.get().startLoad(null);
        long deadline = System.currentTimeMillis() + 30_000L;
        while (GraphService.get().state() != GraphService.State.READY) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("graph never loaded: "
                        + GraphService.get().describe());
            }
            Thread.sleep(5L);
        }
    }

    private PlannerService.State settle() throws InterruptedException {
        PlannerService planner = PlannerService.get();
        long deadline = System.currentTimeMillis() + 60_000L;
        while (planner.state() == PlannerService.State.PLANNING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the planner never finished; an unhandled throw on "
                        + "the worker thread looks exactly like this");
            }
            Thread.sleep(5L);
        }
        return planner.state();
    }

    @Test
    public void itPlansARealTargetAgainstALoadedGraph() throws Exception {
        loadGraph();
        PlannerService planner = PlannerService.get();
        assertTrue(planner.plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES));
        assertEquals(PlannerService.State.DONE, settle());

        PlanResult result = planner.result();
        assertNotNull(result);
        assertEquals("mod:plate", result.target);
        assertEquals(1L, result.qty);
        // Plate is crafted, the ingot below it is not, so a two-node tree is the whole plan
        // and the ingot is the one thing to go and get.
        assertEquals(2, result.nodes);
        assertEquals(1, result.shoppingList.size());
        assertEquals("mod:ingot", result.shoppingList.get(0).key);
    }

    @Test
    public void theJsonHandoffIsWhatTheGoldenGateCompares() throws Exception {
        // The panel parses this with `client.planner.PlanJson`, which was written against the
        // fixtures. Proving the in-game bytes are the same SHAPE is what makes "the port
        // agrees offline" and "the mod agrees in game" one claim instead of two.
        loadGraph();
        PlannerService planner = PlannerService.get();
        planner.plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES);
        assertEquals(PlannerService.State.DONE, settle());

        JsonObject doc = new JsonParser().parse(planner.resultJson()).getAsJsonObject();
        for (String block : new String[] {"target", "target_name", "qty", "tree",
                                          "shopping_list", "nodes", "truncated", "max_nodes"}) {
            assertTrue("the handoff lost " + block, doc.has(block));
        }
        assertEquals("mod:plate", doc.get("target").getAsString());
    }

    @Test
    public void theSecondPlanReusesTheFirstCostTable() throws Exception {
        // Pricing is two relaxations over every recipe in the pack and is the dearest part of
        // a first plan. Re-pricing per target would make every plan in a session pay it.
        // COUNTED, NOT TIMED: a timing assertion on a shared machine is flaky, and one that
        // only checks both runs finished asserts nothing at all.
        loadGraph();
        PlannerService planner = PlannerService.get();
        assertEquals(0, planner.pricedScenarios());
        planner.plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES);
        assertEquals(PlannerService.State.DONE, settle());
        assertEquals(1, planner.pricedScenarios());
        planner.plan("mod:plate", 7L, Solver.DEFAULT_MAX_NODES);
        assertEquals(PlannerService.State.DONE, settle());
        assertEquals("the second plan priced the pack again", 1, planner.pricedScenarios());
    }

    @Test
    public void resetDropsTheCostTablesSoAReloadedGraphIsNotPricedByAnOldOne() throws Exception {
        // A cost table is keyed on the scenario, not on the graph. Keeping one across a
        // reload would price a new graph with an old table and produce a plan nothing
        // explains.
        loadGraph();
        PlannerService planner = PlannerService.get();
        planner.plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES);
        assertEquals(PlannerService.State.DONE, settle());
        assertEquals(1, planner.pricedScenarios());
        planner.reset();
        assertEquals(0, planner.pricedScenarios());
        assertEquals(PlannerService.State.IDLE, planner.state());
    }

    @Test
    public void oneScenarioHasOneCostSignature() throws Exception {
        // What the cache keys on. Two resolutions of the same document must agree, or every
        // plan prices from scratch and the cache is decoration.
        loadGraph();
        RecipeGraph graph = GraphService.get().graph();
        String a = ScenarioInputs.resolve(graph, PlannerService.liveScenario()).costSignature();
        String b = ScenarioInputs.resolve(graph, PlannerService.liveScenario()).costSignature();
        assertEquals(a, b);
    }

    @Test
    public void anUnknownTargetIsRefusedAndSaysSo() throws Exception {
        loadGraph();
        PlannerService planner = PlannerService.get();
        assertFalse(planner.plan("mod:nope", 1L, Solver.DEFAULT_MAX_NODES));
        assertEquals(PlannerService.State.FAILED, planner.state());
        assertTrue(planner.detail(), planner.detail().contains("mod:nope"));
        assertNull(planner.result());
    }

    @Test
    public void planningWithNoGraphReportsTheGraphProblemAndNotAPlanningOne() throws Exception {
        // The player's actual problem is that graph.json is missing. Saying "planning failed"
        // would send them to the item and away from the file.
        System.clearProperty(GraphSource.PROPERTY);
        GraphService.get().startLoad(folder.newFolder("nowhere"));
        PlannerService planner = PlannerService.get();
        assertFalse(planner.plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES));
        assertEquals(PlannerService.State.FAILED, planner.state());
        assertTrue(planner.detail(), planner.detail().contains("no graph.json"));
    }

    @Test
    public void theLiveScenarioResolvesWithoutTheDefaultsGoingMissing() throws Exception {
        // An all-empty document must still pick up what `ScenarioInputs` supplies by default
        // -- vanilla water free and the curated token map -- because those are pack facts
        // rather than world state. Losing them would silently change every in-game plan
        // relative to the identical fixture scenario.
        loadGraph();
        RecipeGraph graph = GraphService.get().graph();
        String signature =
                ScenarioInputs.resolve(graph, PlannerService.liveScenario()).costSignature();
        String bare = ScenarioInputs
                .resolve(graph, new JsonParser().parse("{}").getAsJsonObject())
                .costSignature();
        assertEquals("an all-empty document must resolve exactly as an absent one",
                     bare, signature);
    }
}
