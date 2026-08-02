package io.github.jacoblasky.recipedump.common;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.CostTable;
import io.github.jacoblasky.recipedump.plan.PlanJson;
import io.github.jacoblasky.recipedump.plan.PlanResult;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * Runs a plan against the loaded graph, off the render thread, and holds the answer.
 *
 * THE SCENARIO IS BUILT AS THE DOCUMENT THE FIXTURES USE, and resolved by
 * {@link ScenarioInputs}, which is the class `PlanFixtureTest` proves agrees with Python.
 * A second production-only resolver would be code the golden gate never touches, so the mod
 * could price a plan differently from the oracle while every fixture stayed green -- and the
 * symptom is a plausible plan taking a different route, which is precisely what #19's fixture
 * strategy exists to make impossible. Building the document also makes the in-game inputs
 * inspectable in the same format a fixture uses, so "plan this target in game and offline and
 * compare" is a thing anyone can do.
 *
 * OFF THE RENDER THREAD, for the same reason the graph load is. A typical plan is around
 * 0.4 s, and `defaults.MAX_NODES_CEILING` exists because the worst case measured 26 s at the
 * default budget and 417 s at eight times it. Even the typical case is eight dropped frames.
 *
 * PRICING IS CACHED ON THE SCENARIO, not on the target. `Cost.estimate` is two relaxations
 * over every recipe in the pack and is by far the dearest part of a first plan; every plan in
 * one session shares one scenario until the player owns something new, so the second plan
 * should be the solve alone. Keyed on {@link ScenarioInputs.Resolved#costSignature}, which is
 * derived from what the cost model is actually handed rather than from a list of field names.
 */
public final class PlannerService {

    private static final PlannerService INSTANCE = new PlannerService();

    /** What a plan run can be doing. */
    public enum State {
        IDLE,
        /** Pricing and solving on the worker thread. */
        PLANNING,
        /** {@link #result} is usable. */
        DONE,
        /** {@link #detail} says why there is no plan. */
        FAILED
    }

    /**
     * One cost table per scenario. A `LinkedHashMap` and not a cache with eviction: there is
     * one scenario in play at a time today, and a map that could evict the only entry would
     * silently reintroduce the cost this exists to avoid.
     */
    private final Map<String, CostTable> priced = new LinkedHashMap<String, CostTable>();

    private volatile State state = State.IDLE;
    private volatile PlanResult result;
    private volatile String resultJson;
    private volatile String detail = "";
    private volatile String targetKey = "";
    private volatile long targetQty;

    private PlannerService() {
    }

    public static PlannerService get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    public PlanResult result() {
        return result;
    }

    /**
     * The finished plan as JSON, or null.
     *
     * THE HANDOFF TO THE PANEL IS JSON ON PURPOSE. `client.planner.PlanJson` already parses
     * exactly this -- it was written against `tests/fixtures/plan/*.json` -- so the panel
     * needs no second reader and the in-game render path runs on bytes provably identical to
     * what the golden gate compares. A field-by-field adapter from `PlanResult` to the view
     * would be a fourth place the plan shape lives, free to drop a field and render a blank
     * row rather than fail. Measured cost of the round trip is in `PlannerServiceTest`.
     */
    public String resultJson() {
        return resultJson;
    }

    public String detail() {
        return detail;
    }

    public String targetKey() {
        return targetKey;
    }

    public long targetQty() {
        return targetQty;
    }

    /** One line for a player: what is happening, or what went wrong. */
    public String describe() {
        switch (state) {
            case PLANNING:
                return "planning " + targetQty + "x " + targetKey;
            case DONE:
                PlanResult r = result;
                return r == null ? "planned" : r.nodes + " nodes"
                        + (r.truncated ? ", truncated at " + r.maxNodes : "");
            case FAILED:
                return detail;
            default:
                return "nothing planned";
        }
    }

    /**
     * The `scenario` document for the live world, in the shape a fixture writes.
     *
     * Every field is present even when empty, because the shape IS the contract: a fixture
     * with a missing key and one with an empty one are different documents, and
     * `ScenarioInputs.resolve` reading a default for an absent field would hide which of the
     * two the game meant. What each field can and cannot be filled from is
     * {@link ScenarioSource}.
     */
    public static JsonObject liveScenario() {
        // DERIVED FROM `ScenarioSource`, not restated. One list of the scenario fields, so a
        // new input cannot be declared in one place and forgotten in the other -- which was
        // the shape of this code until the review, with a test to catch the drift rather than
        // a structure that prevented it.
        return ScenarioSource.emptyDocument();
    }

    /**
     * Start planning `key`. Returns false when there is nothing to plan against.
     *
     * Refuses rather than queues when a run is already going: the only caller is a player
     * pressing a key, and a queue would let an impatient one stack up four solves that each
     * hold the pack's cost table.
     */
    public synchronized boolean plan(final String key, final long qty, final int maxNodes) {
        if (state == State.PLANNING) {
            return false;
        }
        GraphService graphs = GraphService.get();
        final RecipeGraph graph = graphs.graph();
        if (graph == null) {
            state = State.FAILED;
            detail = graphs.describe();
            return false;
        }
        if (graph.keyId(key) < 0) {
            state = State.FAILED;
            detail = "no such item in the graph: " + key;
            return false;
        }
        targetKey = key;
        targetQty = qty;
        result = null;
        resultJson = null;
        detail = "";
        state = State.PLANNING;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runPlan(graph, key, qty, maxNodes);
            }
        }, "mcrecipedump-plan");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    private void runPlan(RecipeGraph graph, String key, long qty, int maxNodes) {
        try {
            ScenarioInputs.Resolved resolved =
                    ScenarioInputs.resolve(graph, liveScenario());
            CostTable costs = costsFor(graph, resolved);
            PlanResult plan = ScenarioInputs.solverFor(graph, resolved, costs, maxNodes)
                    .solve(graph.keyId(key), qty);
            String json = PlanJson.toJson(plan);
            // `result` and `resultJson` BEFORE `state`, so a reader that sees DONE sees both.
            // Same discipline as `GraphService`, and the same absence of a lock.
            result = plan;
            resultJson = json;
            state = State.DONE;
        } catch (RuntimeException e) {
            fail(e);
        } catch (OutOfMemoryError e) {
            fail(e);
        }
    }

    private void fail(Throwable e) {
        result = null;
        resultJson = null;
        detail = "planning failed: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        state = State.FAILED;
    }

    private CostTable costsFor(RecipeGraph graph, ScenarioInputs.Resolved resolved) {
        String signature = resolved.costSignature();
        synchronized (priced) {
            CostTable cached = priced.get(signature);
            if (cached != null) {
                return cached;
            }
        }
        // Priced OUTSIDE the lock. It is seconds of work, and holding a monitor across it
        // would block a second planner thread that is about to want the same table -- which
        // is the common case, so the lock would serialise exactly what it looks like it is
        // protecting. Two threads racing here cost one duplicate table and no wrong answer.
        CostTable table = ScenarioInputs.price(graph, resolved);
        synchronized (priced) {
            CostTable cached = priced.get(signature);
            if (cached != null) {
                return cached;
            }
            priced.put(signature, table);
        }
        return table;
    }

    /**
     * How many distinct scenarios have been priced. A test seam, and a narrow one.
     *
     * Exposed because the alternative was a test that timed two plans and asserted both were
     * non-negative -- which is to say asserted nothing, on a shared machine where a timing
     * comparison would be flaky anyway. Counting the tables proves the cache is USED, which
     * is the actual claim.
     */
    public int pricedScenarios() {
        synchronized (priced) {
            return priced.size();
        }
    }

    /** Drop the plan and the cost tables, so a reloaded graph cannot be priced by an old one. */
    public synchronized void reset() {
        state = State.IDLE;
        result = null;
        resultJson = null;
        detail = "";
        targetKey = "";
        targetQty = 0L;
        synchronized (priced) {
            priced.clear();
        }
    }
}
