package io.github.jacoblasky.recipedump.client;

import java.util.function.Function;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.client.browse.BrowseTabs;
import io.github.jacoblasky.recipedump.client.machines.LiveMachinesActions;
import io.github.jacoblasky.recipedump.client.machines.MachinesEntry;
import io.github.jacoblasky.recipedump.client.machines.MachinesWidgets;
import io.github.jacoblasky.recipedump.client.planner.PlannerAreaSource;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.ScenarioService;
import io.github.jacoblasky.recipedump.plan.MachineTable;

/**
 * Opens the machines window. Everything it contains is built by {@link MachinesWidgets}.
 *
 * THE SAME SHAPE AS `PlannerScreen` AND THE SAME THREE RULES, which are inherited rather than
 * rediscovered:
 *
 *   - NOT a `CustomModularScreen` subclass. That constructor hands `this::buildUI` to
 *     `ModularScreen`, which calls it during `super(...)` before any subclass field is
 *     assigned, so a screen holding its data in a field reads null while building its own
 *     panel. Measured in #140, where the only symptom was
 *     `opening 'planner' threw NullPointerException` with the stack swallowed.
 *   - The window WATCHES a counter from `onUpdate` rather than taking a callback, because the
 *     publisher is a worker thread and rebuilding widgets off the client thread surfaces in
 *     1.12.2 as a ConcurrentModificationException from inside a GUI.
 *   - The counter is read BEFORE the panel is built. Read afterwards, a table landing between
 *     the two would be drawn AND recorded as drawn, and the window would sit on it.
 *
 * ONE DIFFERENCE FROM THE PLANNER, AND IT IS THE INTERESTING ONE. That window rebuilds only on
 * a generation bump, and its own note says not to extend it to anything cheaper because
 * reopening throws away scroll position. This window rebuilds on a generation bump OR on a
 * filter click, and the second is not cheaper -- it is the reader deliberately changing what
 * the list contains, at which point a scroll offset into the previous list is not worth
 * keeping either. Both cases replace the rows; neither is a per-tick redraw.
 */
public final class MachinesScreen {

    private MachinesScreen() {
    }

    /**
     * The machines table, showing whatever the services can answer with right now.
     *
     * `ensure()` FIRST, BECAUSE OPENING IS THE TRIGGER. The service is not started at load --
     * see its class note -- so the first open is what sets the resolve going, and the window
     * then shows "reading machines..." until the counter moves. When there is no graph yet it
     * answers false and changes nothing, and {@code MachinesWindow.onUpdate} asks again.
     *
     * THIS IS ALSO THE ONLY PLACE A FAILED RESOLVE IS RETRIED, and it has to be the only one.
     * Reopening the screen is the player saying "try again"; a tick hook doing the same would
     * relaunch a worker thread every time one failed, forever.
     *
     * THE STAMP IS READ BEFORE THE PANEL IS BUILT, not after. Read afterwards, a table landing
     * between the two would be drawn AND recorded as already drawn, and the window would sit on
     * it -- a missed update, which is the failure that matters.
     */
    public static void open() {
        ScenarioService.get().ensure();
        ClientGUI.open(new MachinesWindow(new LiveMachinesActions(), stamp()));
    }

    /**
     * What the window has drawn: the table's generation and the graph's state together.
     *
     * BOTH, AND LEAVING THE GRAPH OUT IS A BUG I SHIPPED AND CAUGHT IN REVIEW. The window is
     * routinely opened during the 5.47 s graph read -- that is most of the time a player is
     * likely to press the key impatiently -- and while the graph is loading `ScenarioService`
     * has nothing to say, so its generation does not move. Watching it alone left the window
     * latched on "loading graph" with no path out, which is #201's exact shape one screen over.
     *
     * THE STATE ORDINAL AND NOT `progress()`. Progress changes every tick and rebuilding a
     * panel per tick is what `PlannerScreen`'s note forbids -- it would reset the reader's
     * scroll position continuously. The cost is that the percentage in "loading graph, 40%" is
     * frozen at whatever it read when the panel was built; the transitions that change what the
     * screen SAYS all move the ordinal, which is the property that matters.
     *
     * Multiplying by a prime rather than concatenating is enough because the generation only
     * ever goes up and the ordinal is small: any change to either moves the sum, and nothing
     * needs to recover which one moved.
     */
    private static long stamp() {
        return ScenarioService.get().generation() * 31L
                + GraphService.get().state().ordinal();
    }

    /**
     * A panel handed over directly, drawn once. For the screenshot harness.
     *
     * The panel and its actions are built TOGETHER and in that order, because opening a
     * sub-panel needs the panel it hangs off -- which does not exist until the main one has
     * been built. That circularity is why `MachinesActions` is an interface handed in rather
     * than something the widgets reach for.
     */
    public static void openTable(MachineTable table, LiveMachinesActions actions) {
        openPanel(panelFor(table, actions));
    }

    /** One of the machines screen's windows, for the screenshot harness to photograph. */
    public static void openPanel(final ModularPanel panel) {
        ClientGUI.open(new ModularScreen(RecipeDumpMod.MODID,
                                         new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                return panel;
            }
        }));
    }

    private static ModularPanel panelFor(MachineTable table, LiveMachinesActions actions) {
        MachineTable.Filter filter = actions.filterFor(table);
        ModularPanel panel = MachinesWidgets.machinesPanel(
                table, filter, actions, BrowseScreen.navFor(BrowseTabs.Tab.MACHINES));
        actions.attachTo(panel);
        return avoidedByJei(panel);
    }

    /**
     * Tell JEI to lay its item list out AROUND this panel rather than over it.
     *
     * EVERY PANEL THIS OPENS, not only the table. `PlannerScreen` records what the narrower
     * version cost: the five-second window a player actually stares at was the one JEI was
     * allowed to draw over, so the message they were waiting to read was the one that got
     * covered. The not-yet panels are windows like any other.
     */
    private static ModularPanel avoidedByJei(ModularPanel panel) {
        PlannerAreaSource.install(panel);
        return panel;
    }

    /**
     * The machines window, which rebuilds itself when the service has a new answer or when the
     * reader changes a filter.
     *
     * THE ACTIONS OBJECT SURVIVES THE REBUILD and the window does not. That is what carries
     * the filter across: a new window with a new `LiveMachinesActions` would reset the chips
     * on every service bump, so a table that rebuilt while the player was reading `no route`
     * would silently drop them back to every state.
     */
    private static final class MachinesWindow extends ModularScreen {

        private final LiveMachinesActions actions;
        private final long drawn;
        /** Set by a filter click; checked and cleared from {@link #onUpdate}. */
        private boolean stale;

        MachinesWindow(LiveMachinesActions actions, long stamp) {
            super(RecipeDumpMod.MODID, builderFor(actions));
            this.actions = actions;
            this.drawn = stamp;
            actions.redrawWith(new LiveMachinesActions.Redraw() {
                @Override
                public void redraw() {
                    stale = true;
                }
            });
        }

        /**
         * A FLAG SET BY THE CLICK AND ACTED ON NEXT TICK, rather than reopening inside the
         * click handler. `ClientGUI.open` swaps the screen out from under the widget whose
         * `onMousePressed` is still on the stack, and ModularUI keeps walking that widget tree
         * afterwards. Deferring by one tick costs nothing a reader can perceive and keeps the
         * rebuild on the same path the service bump already uses.
         */
        @Override
        public void onUpdate() {
            super.onUpdate();
            // START THE RESOLVE THE MOMENT THE GRAPH BECOMES READABLE. `open()` calls `ensure`
            // once, and when the window is opened during the graph read that call has nothing
            // to work with. Without this the graph would land, the window would redraw off the
            // state ordinal, and it would draw "nothing yet" against a service nobody had ever
            // asked to start.
            //
            // GUARDED ON IDLE, WHICH IS WHAT KEEPS IT OFF A RETRY LOOP. `ensure` restarts a
            // FAILED resolve, and a tick hook calling it unguarded would relaunch a worker
            // thread every time one failed, forever. IDLE is only ever true before the first
            // start, so this fires at most once per window.
            if (ScenarioService.get().state() == ScenarioService.State.IDLE) {
                ScenarioService.get().ensure();
            }
            long now = stamp();
            if (stale || now != drawn) {
                stale = false;
                ClientGUI.open(new MachinesWindow(actions, now));
            }
        }
    }

    /**
     * What the machines screen should draw at the moment it is built.
     *
     * A FUNCTION AND NOT A PANEL, because a rebuilt window has to ask the question again
     * rather than redraw the answer its predecessor was given -- which is the whole point of
     * rebuilding. It takes `actions` as a captured argument rather than reading a field, for
     * the `CustomModularScreen` reason on the class: it is invoked with the screen only partly
     * constructed.
     */
    private static Function<ModularGuiContext, ModularPanel> builderFor(
            final LiveMachinesActions actions) {
        return new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                PlannerState state = MachinesEntry.stateFor(GraphService.get(),
                                                            ScenarioService.get());
                if (state != null) {
                    return avoidedByJei(MachinesWidgets.statePanel(state));
                }
                return panelFor(ScenarioService.get().table(), actions);
            }
        };
    }
}
