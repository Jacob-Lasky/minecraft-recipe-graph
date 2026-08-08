package io.github.jacoblasky.recipedump.client;

import java.util.function.Function;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.client.browse.BrowseTabs;
import io.github.jacoblasky.recipedump.client.browse.GraphWidgets;
import io.github.jacoblasky.recipedump.client.browse.LiveBrowseActions;
import io.github.jacoblasky.recipedump.client.browse.SourcesWidgets;
import io.github.jacoblasky.recipedump.client.planner.PlannerAreaSource;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.ScenarioService;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.GraphFacts;

/**
 * Opens the two smaller browse screens, and routes the tab strip.
 *
 * THE ROUTER LIVES HERE AND NOT IN `client.browse`, deliberately. Machines is one of the three
 * destinations and `MachinesScreen` sits in this package; a widget package that reached back up
 * to a screen class in order to draw a tab would be a cycle, and on a client without ModularUI
 * it would be a cycle that drags the whole UI into a constant pool that must not resolve. The
 * strip asks for a {@link BrowseTabs.Tab}; {@link #openTab} is the only thing that knows what
 * one is.
 *
 * The three `PlannerScreen` rules are inherited whole: not a `CustomModularScreen` subclass
 * (its builder runs during `super(...)`, before any field is assigned -- #140), the window
 * watches a counter from `onUpdate` rather than taking a callback off a worker thread, and the
 * counter is read before the panel is built.
 */
public final class BrowseScreen {

    private BrowseScreen() {
    }

    /**
     * The one door the tab strip goes through.
     *
     * A SWITCH RATHER THAN A MAP OF SUPPLIERS, so adding a fourth screen fails to compile until
     * this is updated. A map with a missing entry is a tab that silently does nothing, which is
     * the failure this whole `openTab` indirection exists to make impossible.
     */
    public static void openTab(BrowseTabs.Tab tab) {
        switch (tab) {
            case MACHINES:
                MachinesScreen.open();
                return;
            case SOURCES:
                openSources();
                return;
            case GRAPH:
                openGraph();
                return;
            default:
                // UNREACHABLE, and it throws rather than returning, because a tab added to the
                // enum and forgotten here would otherwise be a dead control a player clicks
                // twice before deciding the mod is broken.
                throw new IllegalArgumentException("no screen for browse tab " + tab);
        }
    }

    /** What the strip hands to every panel: navigation that refuses to reopen `current`. */
    public static LiveBrowseActions navFor(BrowseTabs.Tab current) {
        return new LiveBrowseActions(current, new LiveBrowseActions.Opener() {
            @Override
            public void open(BrowseTabs.Tab tab) {
                openTab(tab);
            }
        });
    }

    /**
     * The free-sources list.
     *
     * IT RIDES ON `ScenarioService` RATHER THAN RESOLVING ITS OWN SCENARIO, and that is the
     * point rather than a shortcut. Both screens are views of ONE resolved scenario --
     * `ScenarioInputs.resolve` produces the machine verdicts and the free sources in the same
     * pass -- so a second service would resolve the pack twice and, worse, could disagree with
     * the first about what the player owns. `ScenarioInputs`' own header is explicit that a
     * second resolver on the production path is code the golden gate never touches.
     */
    public static void openSources() {
        ScenarioService.get().ensure();
        ClientGUI.open(new BrowseWindow(BrowseTabs.Tab.SOURCES, stamp()));
    }

    /** Which graph the planner is answering from. */
    public static void openGraph() {
        ClientGUI.open(new BrowseWindow(BrowseTabs.Tab.GRAPH, stamp()));
    }

    /** One of the browse windows, handed over built. For the screenshot harness. */
    public static void openPanel(final ModularPanel panel) {
        ClientGUI.open(new ModularScreen(RecipeDumpMod.MODID,
                                         new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                return panel;
            }
        }));
    }

    /**
     * What the window has drawn: the resolve's generation and the graph's state together.
     *
     * BOTH, FOR THE REASON #254 FOUND THE HARD WAY. Watching the service alone leaves a window
     * opened during the 5.47 s graph read latched forever, because that service has nothing to
     * say until a graph exists and so its counter never moves -- #201's shape. The graph screen
     * needs the graph half in particular: it has no service of its own at all.
     */
    private static long stamp() {
        return ScenarioService.get().generation() * 31L
                + GraphService.get().state().ordinal();
    }

    private static ModularPanel avoidedByJei(ModularPanel panel) {
        PlannerAreaSource.install(panel);
        return panel;
    }

    /**
     * Builds whichever browse panel the window is for, or the not-yet panel that explains why
     * it cannot.
     *
     * THE GRAPH TAB NEEDS ONLY A LOADED GRAPH; the sources tab additionally needs the scenario
     * resolved. Asking for the same preconditions on both would make "which graph am I reading"
     * -- the screen whose whole job is to answer a question about a graph that may be wrong --
     * wait on a resolve it does not use.
     */
    static ModularPanel panelFor(BrowseTabs.Tab tab) {
        RecipeGraph graph = GraphService.get().graph();
        // NO NULL-GRAPH BRANCH HERE, and there was one until it was read back: `stateFor`
        // returns a not-yet panel for every graph state that is not READY, and `builderFor`
        // draws that instead of calling this. A second answer for the same case would be dead
        // code whose only effect is to make a future reader think this path is reachable.
        if (tab == BrowseTabs.Tab.GRAPH) {
            String path = GraphService.get().source() == null
                    ? "" : GraphService.get().source().getPath();
            return avoidedByJei(
                    GraphWidgets.graphPanel(GraphFacts.of(graph), path, navFor(tab)));
        }
        return avoidedByJei(SourcesWidgets.sourcesPanel(
                ScenarioService.get().sources(), navFor(tab)));
    }

    /** Rebuilds itself when the services have a new answer. */
    private static final class BrowseWindow extends ModularScreen {

        private final BrowseTabs.Tab tab;
        private final long drawn;

        BrowseWindow(BrowseTabs.Tab tab, long stamp) {
            super(RecipeDumpMod.MODID, builderFor(tab));
            this.tab = tab;
            this.drawn = stamp;
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            // Start the resolve the moment the graph becomes readable, guarded on IDLE so a
            // failed resolve cannot be relaunched every tick. Same rule as `MachinesScreen`.
            if (tab == BrowseTabs.Tab.SOURCES
                    && ScenarioService.get().state() == ScenarioService.State.IDLE) {
                ScenarioService.get().ensure();
            }
            long now = stamp();
            if (now != drawn) {
                ClientGUI.open(new BrowseWindow(tab, now));
            }
        }
    }

    private static Function<ModularGuiContext, ModularPanel> builderFor(
            final BrowseTabs.Tab tab) {
        return new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                PlannerState state = stateFor(tab);
                if (state != null) {
                    return avoidedByJei(io.github.jacoblasky.recipedump.client.machines
                            .MachinesWidgets.statePanel(state));
                }
                return panelFor(tab);
            }
        };
    }

    /**
     * The not-yet state to draw, or null when the tab can be drawn.
     *
     * THE GRAPH TAB IS READY AS SOON AS THE GRAPH IS, which is why this is not simply
     * `MachinesEntry.stateFor`. That one waits for the machine verdicts, and the graph screen
     * does not read them.
     */
    static PlannerState stateFor(BrowseTabs.Tab tab) {
        GraphService graphs = GraphService.get();
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
        if (tab == BrowseTabs.Tab.GRAPH) {
            return null;
        }
        return io.github.jacoblasky.recipedump.client.machines.MachinesEntry
                .stateFor(graphs, ScenarioService.get());
    }
}
