package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.BrowseScreen;
import io.github.jacoblasky.recipedump.client.browse.BrowseTabs;
import io.github.jacoblasky.recipedump.client.browse.GraphWidgets;
import io.github.jacoblasky.recipedump.client.browse.SourcesWidgets;
import io.github.jacoblasky.recipedump.client.machines.MachinesWidgets;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.ScenarioService;
import io.github.jacoblasky.recipedump.plan.GraphFacts;

/**
 * The free-sources and graph screens, for `-Dmcrecipedump.shot=sources` and `=graph`.
 *
 * BOTH NEED A REAL GRAPH AND NEITHER FAKES ONE, which is {@link MachinesShot}'s rule and it
 * holds here for a sharper reason on the graph tab: that screen's entire subject is the
 * identity of the graph being read. A fixture would photograph a made-up instance path, which
 * is not a weaker artifact than the real one -- it is a misleading one, because the thing a
 * reviewer checks in the picture is exactly the thing that would be invented.
 *
 * `graph` NEEDS ONLY THE GRAPH; `sources` ALSO NEEDS THE RESOLVE. That asymmetry is the
 * screens' own, not the harness's -- see {@code BrowseScreen.stateFor} -- and it is why the
 * graph shot is the faster of the two to take.
 */
final class BrowseShot {

    private static final long RESOLVE_WAIT_MILLIS = 180_000L;

    private BrowseShot() {
    }

    /** `sources`: what the planner treats as free. */
    static void openSources(String arg) {
        if (!awaitGraph()) {
            return;
        }
        final ScenarioService service = ScenarioService.get();
        service.ensure();
        if (!ShotWaits.until("the resolved scenario", RESOLVE_WAIT_MILLIS,
                new ShotWaits.Busy() {
                    @Override
                    public boolean busy() {
                        return service.state() == ScenarioService.State.BUILDING
                                || service.state() == ScenarioService.State.IDLE;
                    }
                })) {
            explain();
            return;
        }
        if (service.sources() == null) {
            ShotHarness.log("sources: " + service.describe());
            explain();
            return;
        }
        ShotHarness.log("sources: " + service.sources().size() + " free");
        BrowseScreen.openPanel(SourcesWidgets.sourcesPanel(
                service.sources(), BrowseScreen.navFor(BrowseTabs.Tab.SOURCES)));
    }

    /** `graph`: which graph the planner is answering from. */
    static void openGraph(String arg) {
        if (!awaitGraph()) {
            return;
        }
        GraphFacts facts = GraphFacts.of(GraphService.get().graph());
        String path = GraphService.get().source() == null
                ? "" : GraphService.get().source().getPath();
        // THE FACTS GO IN THE LOG AS WELL AS THE PICTURE. A screenshot of this screen is a
        // picture of some numbers, and a reviewer cannot tell a real 124,467 from a fixture's
        // by looking. The log line is what says the run had a graph at all.
        ShotHarness.log("graph: " + facts.recipes() + " recipes from " + path
                + " (dumped from " + facts.instanceDir() + ")");
        BrowseScreen.openPanel(GraphWidgets.graphPanel(
                facts, path, BrowseScreen.navFor(BrowseTabs.Tab.GRAPH)));
    }

    /**
     * True once the graph is READY; otherwise shoots the not-yet panel and answers false.
     *
     * SHOOTS AND SUCCEEDS WITHOUT A GRAPH, per `LivePlanShot`'s rule: that is every CI run and
     * anyone who has not built an oracle, and "no graph.json, looked in ..." is the picture a
     * new player sees.
     */
    private static boolean awaitGraph() {
        if (LivePlanShot.awaitGraph()) {
            return true;
        }
        ShotHarness.log("browse: " + GraphService.get().describe());
        explain();
        return false;
    }

    private static void explain() {
        PlannerState state = PlannerState.failed(GraphService.get().describe());
        BrowseScreen.openPanel(MachinesWidgets.statePanel(state));
    }
}
