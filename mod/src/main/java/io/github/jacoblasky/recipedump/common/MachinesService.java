package io.github.jacoblasky.recipedump.common;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.MachineTable;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;

/**
 * The machines table, resolved off the client thread and held until something changes.
 *
 * WHY A SERVICE AND NOT A CALL FROM THE SCREEN. `Machines.resolve` walks every candidate of
 * every category against a 45 MB graph -- 503 categories on the reference pack -- and building
 * it inside `onItemRightClick` would pay that on the frame the window opens. `GraphService`
 * already argues this shape for the graph read itself: a player who right-clicks and waits has
 * been given a slow tool.
 *
 * WHY IT IS NOT STARTED AT preInit LIKE THE GRAPH. The graph is read at load because every
 * feature needs it; this needs the graph to exist first, and it is only ever wanted by one
 * screen that may never be opened. Resolving it eagerly would spend a worker thread on every
 * world join for a window most sessions never see. It is built on first open and then kept.
 *
 * IN `common/` AND NAMING NO CLIENT TYPE, which is the convention `GraphSource` records: "the
 * graph is pack data rather than a client resource... Phase 5's live AE2 read landed
 * server-side and turned out not to need the graph, so every graph consumer today is
 * client-side -- that is NOT a reason to move this file under `client.`". Same argument here,
 * and `CommonSideSafetyTest` is what holds it.
 *
 * THE COUNTERPART TO `PlannerService`, DELIBERATELY SEPARATE. That service is about one target
 * and is refused while a solve is running; this is about the pack and has no target. Folding
 * them together would make opening the machines table refuse while a plan is being solved,
 * which is exactly when a player wants to know which machine they are missing.
 */
public final class MachinesService {

    private static final MachinesService INSTANCE = new MachinesService();

    /** What a build can be doing. */
    public enum State {
        IDLE,
        /** Resolving on the worker thread. */
        BUILDING,
        /** {@link #table} is usable. */
        DONE,
        /** {@link #detail} says why there is no table. */
        FAILED
    }

    private volatile State state = State.IDLE;
    private volatile MachineTable table;
    private volatile String detail = "";
    private volatile long generation;

    /**
     * The graph the held table was built from, compared by IDENTITY.
     *
     * A REFERENCE COMPARISON AND NOT A VERSION FIELD, which is `JeiBridge.indexOf`'s rule:
     * `GraphService.graph()` is a plain field read that returns the same object until a
     * reload, so `!=` is exact and costs nothing. A table built from a graph that has since
     * been replaced would describe machines from the previous pack.
     */
    private volatile RecipeGraph builtFrom;

    private MachinesService() {
    }

    public static MachinesService get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    /** Null until {@link State#DONE}. */
    public MachineTable table() {
        return table;
    }

    public String detail() {
        return detail;
    }

    /**
     * How many times this service has had something new to say. Strictly increasing.
     *
     * A COUNTER RATHER THAN A LISTENER, for the reason `PlannerService.generation` gives: the
     * one subscriber is an open GUI and the publisher is a worker thread, so a callback would
     * rebuild widgets off the client thread. Bumped LAST in each transition, after the state
     * it describes, so a reader that sees a new number sees everything behind it.
     */
    public long generation() {
        return generation;
    }

    /**
     * Make sure a table is being built or is already built. Returns false when it cannot be.
     *
     * IDEMPOTENT AND CHEAP TO CALL FROM A DRAW, because the screen calls it every time it
     * rebuilds. A held table for the current graph is a volatile read and two comparisons.
     */
    public synchronized boolean ensure() {
        RecipeGraph graph = GraphService.get().graph();
        if (graph == null) {
            // NO GRAPH IS NOT A MACHINES FAILURE, AND SAYING IT WAS BROKE THE SCREEN. This
            // used to set FAILED with the graph's own message, which was redundant --
            // `MachinesEntry.stateFor` already reports the graph problem and ranks it above
            // this service's -- and it was worse than redundant: it moved the service out of
            // IDLE, which is the state the window watches to know the resolve has not been
            // started yet. A window opened during the 5.47 s graph load therefore latched on
            // FAILED, nothing bumped the counter when the graph arrived, and the screen sat on
            // "loading" forever. That is #201's exact shape, reproduced in a second screen.
            //
            // SO THIS LEAVES THE STATE ALONE and answers false. The caller is a tick hook that
            // will ask again.
            return false;
        }
        if (state == State.BUILDING) {
            return true;
        }
        if (state == State.DONE && builtFrom == graph) {
            return true;
        }
        return start(graph);
    }

    /**
     * Throw the held table away and build it again.
     *
     * FOR THE INPUTS RATHER THAN THE GRAPH. A verdict is decided by placed blocks and stock,
     * and both move while the player plays -- so a table held from before they built the
     * machine they were looking up is a table that still says they cannot use it. The screen
     * offers this rather than polling, because re-resolving on a timer would spend a worker
     * thread on a window nobody is looking at.
     */
    public synchronized boolean rebuild() {
        RecipeGraph graph = GraphService.get().graph();
        if (graph == null) {
            return ensure();
        }
        if (state == State.BUILDING) {
            return true;
        }
        return start(graph);
    }

    private boolean start(final RecipeGraph graph) {
        state = State.BUILDING;
        detail = "";
        generation++;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                build(graph);
            }
        }, "mcrecipedump-machines");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return true;
    }

    private void build(RecipeGraph graph) {
        try {
            // THROUGH `ScenarioInputs.resolve` AND NOT `Machines.resolve` DIRECTLY. That class's
            // header is explicit that a second resolver on the production path is code the
            // golden gate never touches, and a machines screen that described the pack
            // differently from the way the solver planned it is precisely the divergence the
            // warning is about. The cost table is NOT built here -- `price` is the expensive
            // step and this screen shows no prices.
            ScenarioInputs.Resolved resolved =
                    ScenarioInputs.resolve(graph, PlannerService.liveScenario());
            MachineTable built = MachineTable.of(graph, resolved.machineStates());
            // `table` and `builtFrom` BEFORE `state`, so a reader that sees DONE sees both.
            // Same discipline as `PlannerService.runPlan` and `GraphService`, and the same
            // absence of a lock.
            table = built;
            builtFrom = graph;
            state = State.DONE;
            generation++;
        } catch (RuntimeException e) {
            fail(e);
        } catch (OutOfMemoryError e) {
            fail(e);
        }
    }

    private void fail(Throwable e) {
        table = null;
        builtFrom = null;
        detail = "reading machines failed: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        state = State.FAILED;
        generation++;
    }

    /** One line for a player: what is happening, or what went wrong. */
    public String describe() {
        switch (state) {
            case BUILDING:
                return "reading machines...";
            case DONE:
                MachineTable held = table;
                return held == null ? "ready" : held.allRows().size() + " categories";
            case FAILED:
                return detail;
            default:
                return "";
        }
    }

    /** For tests, which must not inherit another test's held table. */
    public synchronized void reset() {
        state = State.IDLE;
        table = null;
        builtFrom = null;
        detail = "";
        generation++;
    }
}
