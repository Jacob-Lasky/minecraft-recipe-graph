package io.github.jacoblasky.recipedump.client;

import java.util.function.Function;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.client.planner.LivePlannerActions;
import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerAreaSource;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;

/**
 * Opens the planner's windows. Everything they contain is built by {@link PlannerWidgets},
 * which is where the design lives and where the tests point.
 *
 * TWO ENTRY POINTS BECAUSE THERE ARE TWO CALLERS, and the split is honest rather than
 * scaffolding:
 *
 *   - {@link #openPlanner} is what the calculator item does. It picks between the tree and
 *     the four not-yet states each time it draws, and it REDRAWS ITSELF when the answer
 *     underneath it changes -- see {@code PlannerWindow}.
 *   - {@link #openPlan} takes a plan already in hand and draws it once. It is what the
 *     screenshot harness drives against the frozen fixtures, where there is no service to
 *     watch and nothing that could change. The argument type does not differ between the
 *     two, because `tests/fixtures/plan/*.json` IS the solver's output shape.
 *
 * DO NOT MAKE THIS A `CustomModularScreen` SUBCLASS. That constructor hands `this::buildUI`
 * to `ModularScreen`, which calls it during `super(...)`, before any subclass field is
 * assigned -- so a screen holding its data in a field reads null while building its own
 * panel. Measured in #140, where the only symptom the client gave was
 * `opening 'planner' threw NullPointerException`, with the stack swallowed by `ShotScreens`.
 * A captured local cannot go wrong that way, and {@code PlannerWindow} keeps to it: its
 * fields are read from `onUpdate` and never from the panel builder, which is handed a
 * captured constructor argument.
 */
public final class PlannerScreen {

    private PlannerScreen() {
    }

    /**
     * The planner, showing whatever the services can answer with right now.
     *
     * @param book the player's plan book, for the "still needed" column.
     */
    public static void openPlanner(PlanBook book) {
        // THE COUNTERS ARE READ BEFORE THE PANEL IS BUILT, not after. Read afterwards, a plan
        // landing between the two would be drawn AND recorded as already drawn, and the
        // window would sit on it -- a missed update, which is the failure that matters. Read
        // first, the same race costs one redundant rebuild of an identical panel.
        ClientGUI.open(new PlannerWindow(book, stamp(book), plans()));
    }

    /**
     * What the window has drawn: the plan's generation and the book's revision together.
     *
     * BOTH, because the panel is a function of both and they change independently. The plan
     * arrives from a worker thread; the book arrives from the server after "Add to TODO" or
     * "Favourite" in the node menu, and it is what the footer's "N on TODO" counts. Watching
     * only the plan left those two menu entries looking broken: the packet went, the server
     * answered, and nothing on screen moved.
     */
    private static long stamp(PlanBook book) {
        return PlannerService.get().generation() * 31L + book.revision();
    }

    /**
     * The plan generation on its own, WITHOUT the book folded in. See {@link #stamp} for the
     * combined counter and why the window watches both.
     *
     * SEPARATE BECAUSE ONE CONSUMER NEEDS TO TELL THE TWO APART. The selection is cleared when
     * the PLAN changes and must survive a book change: "Favourite" and "Add to TODO" both bump
     * the revision, and dropping the highlight because the player starred the node they were
     * looking at would make the star look like it had cancelled the click.
     */
    private static long plans() {
        return PlannerService.get().generation();
    }

    /**
     * A plan handed over directly, drawn once. For the screenshot harness.
     *
     * The panel and its actions are built TOGETHER and in that order, because opening a
     * sub-panel needs the panel it hangs off -- which does not exist until the main one has
     * been built. That circularity is why `PlannerActions` is an interface handed in rather
     * than something the widgets reach for.
     */
    public static void openPlan(final PlanView plan, final PlanBook book) {
        openPanel(new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                return planPanel(plan, book);
            }
        });
    }

    /** One of the planner's secondary windows, for the screenshot harness to photograph. */
    public static void openPanel(final ModularPanel panel) {
        openPanel(new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                return panel;
            }
        });
    }

    private static void openPanel(Function<ModularGuiContext, ModularPanel> builder) {
        // Through `ClientGUI` rather than `Minecraft.displayGuiScreen`: a ModularScreen is
        // NOT a GuiScreen, it needs the `GuiScreenWrapper` that ClientGUI builds around it.
        ClientGUI.open(new ModularScreen(RecipeDumpMod.MODID, builder));
    }

    private static ModularPanel planPanel(PlanView plan, PlanBook book) {
        LivePlannerActions actions = new LivePlannerActions();
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book, actions);
        actions.attachTo(panel);
        return avoidedByJei(panel);
    }

    /**
     * Tell JEI to lay its item list out AROUND this panel rather than over it (#145's other
     * seam). The source reports nothing once the panel closes, so nothing to deregister.
     *
     * EVERY PANEL THIS OPENS, not only the one with a tree in it. It used to be the plan panel
     * alone, which meant the five-second window a player actually stares at -- "loading
     * graph.json" -- was the one JEI was allowed to draw over, and the message they are waiting
     * to read is the one that got covered. The four state panels are windows like any other.
     */
    private static ModularPanel avoidedByJei(ModularPanel panel) {
        PlannerAreaSource.install(panel);
        return panel;
    }

    /**
     * The planner window, which rebuilds itself when the services have a new answer.
     *
     * WHY THE WINDOW WATCHES RATHER THAN THE PLANNER PUSHING. A plan is solved on a worker
     * thread -- 0.4 s typically and 26 s at the budget ceiling -- so the window is routinely
     * opened before there is anything to draw, and a pinned recipe re-solves underneath one
     * that is already open. Without this, both cases leave a window showing an answer that is
     * no longer the current one, and the only way to see the new one is to close it and use
     * the item again. A player has no reason to guess that, and a picker whose click produces
     * no visible change reads as a broken button rather than as a slow one.
     *
     * A COMBINED COUNTER, not just the plan's -- see {@link #stamp}. Multiplying by a prime
     * rather than concatenating is enough here because both halves only ever go UP: any
     * change to either moves the sum, and nothing needs to recover which one moved.
     *
     * A COUNTER POLLED FROM `onUpdate` RATHER THAN A CALLBACK. `onUpdate` is ModularUI's
     * per-client-tick hook, so this runs on the thread that is allowed to touch a GUI; a
     * callback fired from the solver thread would rebuild widgets off the client thread,
     * which in 1.12.2 surfaces as a ConcurrentModificationException from inside a GUI and
     * gets blamed on the GUI. The cost is one volatile read per tick.
     *
     * REOPENING THROWS AWAY SCROLL POSITION AND ANY OPEN SUB-PANEL, and that is acceptable
     * ONLY because it happens when the plan itself has changed -- a scroll offset into a tree
     * that no longer exists is not worth keeping. DO NOT extend this to redraw on anything
     * cheaper than a generation bump; a window that reset the player's scroll on a tick would
     * be worse than one that never refreshed.
     */
    private static final class PlannerWindow extends ModularScreen {

        private final PlanBook book;
        private final long drawn;
        /** The plan generation this window's tree came from. See {@link #onUpdate}. */
        private final long plans;

        PlannerWindow(PlanBook book, long stamp, long generation) {
            super(RecipeDumpMod.MODID, builderFor(book));
            this.book = book;
            this.drawn = stamp;
            this.plans = generation;
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            long generation = plans();
            clearSelectionIfPlanChanged(plans, generation);
            long now = stamp(book);
            if (now != drawn) {
                ClientGUI.open(new PlannerWindow(book, now, generation));
            }
        }
    }

    /**
     * A NEW PLAN CLEARS THE SELECTION (#213's second design question).
     *
     * The selected node came out of the tree that is being replaced. Its key may not be in the
     * new plan at all -- pinning a recipe re-plans, and the whole point of the pin is that a
     * different route is taken -- and a highlight nobody can see is worse than none.
     *
     * RE-POINTING AT THE SAME KEY IN THE NEW TREE IS NOT AVAILABLE, and that is the reason this
     * clears rather than filters. {@link PlanSelection#selectedNode} is what the node menu, "Add
     * to TODO" and the picker all read `need()` from, and the occurrence the player clicked has
     * no identity across a re-solve: the same key can appear under six parents at six different
     * quantities, so keeping the key and re-finding a node would hand back a plausible wrong
     * number with nothing to say which occurrence answered. That is exactly the failure
     * `PlanSelection`'s own class note exists to prevent.
     *
     * IDENTITY CANNOT STAND IN FOR THE COUNTER EITHER, which was the tidier-looking first
     * attempt: keep the selection when the selected NODE is still in the plan being drawn.
     * `PlannerEntry.planFor` runs `PlanJson.readResult` on every panel build, so the nodes are
     * new objects every rebuild whether or not the plan changed -- and the check would clear on
     * a book edit as well, which is the case below that must not clear.
     *
     * ON THE PLAN GENERATION AND NOT ON {@link #stamp}. A book edit bumps the combined counter
     * too, and dropping the highlight because the player starred the node they were looking at
     * would read as the star having cancelled the click.
     *
     * A METHOD RATHER THAN TWO LINES INSIDE `onUpdate`, because `onUpdate` needs a constructed
     * `ModularScreen` and a `ClientGUI`, and the policy is what is worth pinning: see
     * `PlannerScreenTest`, which is red against a version comparing `stamp`.
     *
     * @return whether the selection was dropped
     */
    static boolean clearSelectionIfPlanChanged(long drawnGeneration, long currentGeneration) {
        if (drawnGeneration == currentGeneration) {
            return false;
        }
        PlanSelection.clear();
        return true;
    }

    /**
     * What the planner should draw at the moment it is built.
     *
     * A FUNCTION AND NOT A PANEL, because a rebuilt window has to ask the question again
     * rather than redraw the answer its predecessor was given -- which is the whole point of
     * rebuilding. It takes `book` as a captured argument rather than reading a field, for the
     * `CustomModularScreen` reason on the class: it is invoked with the screen only partly
     * constructed.
     */
    private static Function<ModularGuiContext, ModularPanel> builderFor(final PlanBook book) {
        return new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                PlannerState state = PlannerEntry.stateFor(GraphService.get(),
                                                           PlannerService.get());
                if (state != null) {
                    return avoidedByJei(PlannerWidgets.statePanel(state));
                }
                return planPanel(PlannerEntry.planFor(PlannerService.get()), book);
            }
        };
    }
}
