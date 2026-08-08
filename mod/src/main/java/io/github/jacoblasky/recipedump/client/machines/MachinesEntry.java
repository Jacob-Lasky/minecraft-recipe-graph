package io.github.jacoblasky.recipedump.client.machines;

import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.MachinesService;

/**
 * Which window the machines screen should open, from what the services can answer with.
 *
 * SEPARATE FROM THE SCREEN SO IT CAN BE TESTED, exactly as `PlannerEntry` is: the proxy method
 * takes an `EntityPlayer`, so nothing about the decision below could be exercised without a
 * running client. This is the decision on its own -- service states in, one of four windows
 * out, no Minecraft type in the signature -- and `MachinesEntryTest` walks every branch in
 * milliseconds.
 *
 * THE STATES ARE NOT INTERCHANGEABLE, which is `PlannerState`'s own argument and it applies
 * here with one addition. "still reading the graph", "no graph.json, looked in ..." and
 * "reading machines..." are three different sentences, and the third is new to this screen:
 * the graph can be fully READY while the machine verdicts are still being resolved, because
 * resolving them is a second pass this service starts on first open. A screen that showed
 * "nothing yet" during that pass would look broken for the two seconds it is most likely to
 * be looked at.
 */
public final class MachinesEntry {

    private MachinesEntry() {
    }

    /**
     * The not-yet state to draw, or null when {@link MachinesService#table} is usable.
     *
     * THE GRAPH PROBLEM OUTRANKS THE MACHINES ONE, per `PlannerEntry.stateFor`: a player whose
     * `graph.json` is missing has one thing to fix, and reporting "reading machines failed"
     * underneath would send them to the item instead of to the file.
     */
    public static PlannerState stateFor(GraphService graphs, MachinesService machines) {
        switch (graphs.state()) {
            case MISSING:
            case FAILED:
                return PlannerState.failed(graphs.describe());
            case IDLE:
            case LOADING:
                return PlannerState.loading(graphs.describe());
            default:
                break;
        }
        switch (machines.state()) {
            case BUILDING:
                return PlannerState.loading(machines.describe());
            case FAILED:
                return PlannerState.failed(machines.describe());
            case DONE:
                // A DONE SERVICE HOLDING NO TABLE IS A FAILURE, NOT AN EMPTY SCREEN. The same
                // guard `PlannerEntry` puts on a null `resultJson`: the two fields are written
                // before the state, so this cannot happen -- and a screen that rendered an
                // empty table if it ever did would report "0 categories" about a pack with 503.
                return machines.table() == null
                        ? PlannerState.failed(machines.describe()) : null;
            default:
                // IDLE means `ensure` has not run yet, which the screen does on open. Saying
                // "reading machines..." is true a moment early rather than false.
                return PlannerState.loading("reading machines...");
        }
    }
}
