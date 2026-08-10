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
        java.util.List<String> live = io.github.jacoblasky.recipedump.DumpCommand.activeModIds();
        GraphFacts.PackCheck check = facts.checkAgainst(
                io.github.jacoblasky.recipedump.DumpCommand.modDigest(live),
                live == null ? 0 : live.size());
        // THE VERDICT GOES IN THE LOG TOO. The harness runs a FIVE-jar dev set against an
        // oracle built from the full pack, so the honest answer here is MISMATCH -- and a
        // reviewer seeing red in the screenshot needs the log line to tell that apart from a
        // real defect. See harness/README.md.
        ShotHarness.log("graph: pack check " + check.verdict() + " -- " + check.detail());
        GraphFacts.SchemaCheck schema = GraphFacts.checkSchema(
                facts.dumpSchema(), io.github.jacoblasky.recipedump.DumpCommand.SCHEMA);
        // AND THE SCHEMA VERDICT, for the same reason one line up and with a sharper edge: the
        // oracle graphs on this host have run several schemas behind the jar for weeks (#279),
        // so `OLD GRAPH` in the picture is usually the truth about the harness rather than a
        // defect -- and the only way to tell is a log line naming both numbers.
        ShotHarness.log("graph: schema check " + schema.verdict() + " -- " + schema.detail());
        final com.cleanroommc.modularui.screen.ModularPanel panel = GraphWidgets.graphPanel(
                facts, path, check, schema, BrowseScreen.navFor(BrowseTabs.Tab.GRAPH));
        // THE POSITIVE CONTROL FOR #285, ON THE ONE SURFACE THAT CAN ANSWER IT HONESTLY. This
        // opener builds the panel itself, so the check can ask the drawn widget tree for the
        // two strings rather than asking a proxy -- which is #293's rule, and its own note says
        // why a proxy is not good enough: `> 0` shipped and passed a blank picture.
        //
        // BOTH LINES, NOT JUST THE VERDICT WORD. The word and the numbers under it are laid out
        // separately -- `schemaLine` then `detail()` -- so a regression that reserved one line
        // and dropped the other would leave the word on screen and pass a one-line check.
        final String word = GraphWidgets.schemaLine(schema);
        final String reason = schema.detail();
        ShotScreens.expectDrawn(new ShotScreens.Drawn() {
            @Override
            public boolean drewSomething() {
                java.util.List<String> said = textOf(panel);
                return said.contains(word) && said.contains(reason);
            }

            @Override
            public String describe() {
                java.util.List<String> said = textOf(panel);
                return "the graph tab must carry the schema verdict and its reason: "
                        + "verdict line " + (said.contains(word) ? "drawn" : "MISSING")
                        + " (" + word + "), reason line "
                        + (said.contains(reason) ? "drawn" : "MISSING") + " (" + reason + ")";
            }
        });
        BrowseScreen.openPanel(panel);
    }

    /**
     * Every string the panel's text widgets are actually holding, after truncation.
     *
     * AFTER TRUNCATION IS THE POINT, and it is why this compares whole strings rather than
     * searching for a substring. `PlannerWidgets.line` CUTS to the content width, so a sentence
     * two characters too long reaches the screen with its tail replaced by an ellipsis -- and
     * the tail is where the schema numbers are. An equality check fails on that; a `contains`
     * on the first few words would not, which would leave the harness passing a picture whose
     * one job is to be readable.
     */
    private static java.util.List<String> textOf(com.cleanroommc.modularui.api.widget.IWidget root) {
        java.util.List<String> said = new java.util.ArrayList<String>();
        collectText(root, said);
        return said;
    }

    private static void collectText(com.cleanroommc.modularui.api.widget.IWidget widget,
                                    java.util.List<String> into) {
        if (widget instanceof com.cleanroommc.modularui.widgets.TextWidget) {
            into.add(((com.cleanroommc.modularui.widgets.TextWidget<?>) widget)
                             .getKey().getFormatted());
        }
        if (widget instanceof com.cleanroommc.modularui.widget.ParentWidget) {
            for (com.cleanroommc.modularui.api.widget.IWidget child
                    : ((com.cleanroommc.modularui.widget.ParentWidget<?>) widget).getChildren()) {
                collectText(child, into);
            }
        }
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
