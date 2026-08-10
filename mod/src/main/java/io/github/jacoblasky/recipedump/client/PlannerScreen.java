package io.github.jacoblasky.recipedump.client;

import java.util.function.Function;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;

import io.github.jacoblasky.recipedump.DumpCommand;
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
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.GraphFacts;

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

    /**
     * The graph counter's weight in {@link #stamp}, and the headroom the load term lives under.
     *
     * IT IS NOT AN ARBITRARY PRIME ANY MORE. It was one -- three counters that only go up can
     * be weighted anything and summed -- but #271 added a term that falls back to zero, and the
     * gap between {@link #LOAD_STEPS} and this is exactly what keeps the sum going up across
     * that fall. See {@link #stamp}.
     */
    static final long GRAPH_WEIGHT = 1021L;

    /** The plan counter's weight in {@link #stamp}. */
    static final long PLAN_WEIGHT = 31L;

    /**
     * How finely the graph read is reported to the window: twentieths, so 5% steps.
     *
     * THE CEILING ON THE EXTRA REBUILDS ONE LOAD CAN CAUSE, which is why the number is here
     * rather than inline. See {@link #loadStep}, and see {@code PlannerWindow} for the
     * prohibition this number is the answer to.
     */
    static final long LOAD_STEPS = 20L;

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
     * What the window has drawn: the graph's generation, the plan's generation and the book's
     * revision together.
     *
     * ALL THREE, because the panel is a function of all three and they change independently.
     * The plan arrives from a worker thread; the book arrives from the server after "Add to
     * TODO" or "Favourite" in the node menu, and it is what the footer's "N on TODO" counts.
     * Watching only the plan left those two menu entries looking broken: the packet went, the
     * server answered, and nothing on screen moved.
     *
     * THE GRAPH TERM IS #201, and it is the term whose ABSENCE was invisible. The first two
     * describe a plan, and there is no plan at all during the 5.47 s graph read -- so a window
     * opened in that gap watched two counters that were both frozen for a reason, and sat on
     * "reading graph.json" until the player closed it. Neither counter was wrong; between them
     * they simply said nothing about the thing the window was waiting for.
     *
     * IT IS NOT THE WHOLE FIX ON ITS OWN. Adding it makes the window redraw when the graph
     * lands, and what it redraws as is "nothing planned yet", because nothing started a plan
     * in the meantime. {@link PlannerEntry#resumeWhenTheGraphLands} is the other half and
     * {@code PlannerWindow.onUpdate} runs it FIRST, so the rebuild this counter triggers draws
     * the plan rather than the absence of one.
     *
     * AND A FOURTH TERM THAT IS NOT A COUNTER (#271): twentieths of the graph read, folded in
     * only while a read is running. The three counters above move on state TRANSITIONS, and
     * LOADING is one state -- so between entering it and leaving it every one of them is
     * constant, and the panel built at 0% was the panel still on screen at 99%.
     * `GraphService.progress()` reported real bytes the whole time and nothing redrew to show
     * them. See {@link #loadStep} for why twentieths and not per-tick.
     *
     * WEIGHTED AND SUMMED, which is safe because THE SUM ONLY EVER GOES UP -- each counter
     * documents that on itself, and `GraphService.reset` bumps forward rather than back to
     * zero for exactly this reason. Any increase in any term moves the sum, and nothing needs
     * to recover which one moved. DO NOT add a term that can decrease.
     *
     * THE LOAD TERM DOES FALL BACK TO ZERO, AND {@link #GRAPH_WEIGHT} IS WHAT MAKES THAT SAFE.
     * It is the one term that is not monotone: it climbs to at most {@link #LOAD_STEPS} during
     * a read and is zero again the moment the read ends. But a read can only end by bumping the
     * graph counter -- READY, FAILED and `reset` all do, and MISSING never enters LOADING at
     * all -- so the fall is always paid for by a jump of {@link #GRAPH_WEIGHT}. The sum
     * therefore still only goes up, and it does so BECAUSE `LOAD_STEPS < GRAPH_WEIGHT`, which
     * is an arithmetic fact `PlannerScreenTest.theLoadTermCanNeverOutweighTheGraphCounter`
     * pins. DO NOT raise `LOAD_STEPS` towards `GRAPH_WEIGHT` without re-reading that test: the
     * failure it prevents is silent, and it is a window sitting on a stale panel for ever.
     *
     * PACKAGE-VISIBLE RATHER THAN PRIVATE, so `PlannerScreenTest` can read it. It was private
     * when #201 was filed, and that is a large part of why the issue's evidence had to be read
     * off the code instead of executed: the window needs ModularUI and a live client, so this
     * counter is the only piece of the recovery that a headless test can reach at all.
     */
    static long stamp(PlanBook book) {
        GraphService graphs = GraphService.get();
        // A SEQLOCK, AND NOT TWO PLAIN READS. `generation` and `progress` are separate
        // volatile fields, so a load finishing between them hands back a pair that never
        // existed -- the OLD generation with the NEW `progress() == -1`, which is numerically
        // BELOW a stamp this window has already drawn. That is the one thing the note above
        // forbids, and it would cost a tick of staleness at the exact moment the graph lands,
        // which is the moment #201 is about. Re-reading the counter and discarding the
        // progress when it moved pairs the two or pairs neither.
        long graphGeneration = graphs.generation();
        float progress = graphs.progress();
        if (graphs.generation() != graphGeneration) {
            graphGeneration = graphs.generation();
            progress = -1.0f;
        }
        return stamp(graphGeneration, PlannerService.get().generation(), book.revision(),
                     progress);
    }

    /**
     * The counter as a function of what it is made of, so the arithmetic can be swept.
     *
     * A SECOND OVERLOAD RATHER THAN A TEST THAT DRIVES A REAL LOAD, for the reason
     * {@link #clearSelectionIfPlanChanged} is a method: the policy is what is worth pinning,
     * and the live route can only be sampled by racing a 5.47 s daemon thread from a test.
     * `PlannerScreenTest` does both -- it sweeps this, and it samples the real service once to
     * prove {@link #stamp(PlanBook)} actually reads `progress()` rather than computing a number
     * that would be right if anyone passed it in.
     *
     * @param loadProgress {@code GraphService.progress()}: 0.0 to 1.0 through the file, or
     *                     negative when no read is running.
     */
    static long stamp(long graphGeneration, long planGeneration, long bookRevision,
                      float loadProgress) {
        return graphGeneration * GRAPH_WEIGHT + planGeneration * PLAN_WEIGHT + bookRevision
                + loadStep(loadProgress);
    }

    /**
     * Which twentieth of the read we are in, or 0 when nothing is being read.
     *
     * TWENTIETHS, WHICH IS THE WHOLE ANSWER TO THE PROHIBITION ON {@code PlannerWindow}. That
     * note forbids redrawing on anything cheaper than a generation bump, because a rebuild
     * throws away scroll position -- and it is right to. Twenty is a NUMBER rather than an
     * assurance: it caps the extra rebuilds a whole graph read can cause at twenty, and at
     * zero once the read is over, because `GraphService.progress()` returns -1 outside LOADING
     * and this returns 0 for that. DO NOT swap this for the raw float, for a per-tick value or
     * for a byte count; those are the shapes the prohibition names, and each of them redraws
     * on a tick.
     *
     * FLOORED, so the value is stable across the many ticks inside one twentieth. Clamped on
     * its own argument as well as by `progress()`, so a caller sweeping this cannot walk it
     * past {@link #LOAD_STEPS} and quietly break the inequality {@link #stamp} depends on.
     */
    static long loadStep(float loadProgress) {
        if (loadProgress < 0.0f) {
            return 0L;
        }
        return (long) (Math.min(1.0f, loadProgress) * LOAD_STEPS);
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
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book, schemaCheck(), actions);
        actions.attachTo(panel);
        return avoidedByJei(panel);
    }

    /**
     * How the loaded graph's dump format compares with this build's, or null when no graph is
     * loaded. The measuring half of #285; `GraphFacts.checkSchema` is the deciding half.
     *
     * THE JAR'S NUMBER IS READ HERE AND NOT IN `plan/`, exactly as `BrowseScreen` reads the
     * running mod list for the pack check: `DumpCommand` imports Minecraft and JEI, so `plan/`
     * may not name it and `tools/ci-java.sh` could not compile it. `DumpCommand.SCHEMA` is a
     * compile-time constant, so this reference costs no class load on top of that.
     *
     * TWO INTS AND NOT A `GraphFacts`. Building one walks all 124,467 recipes, and this runs
     * every time the planner window rebuilds itself -- which is once per plan, per book change,
     * and per graph generation. The Graph tab pays that walk because it prints the totals; this
     * needs one field.
     *
     * NULL WHERE THERE IS NO GRAPH, AND IT IS NOT A SILENCE THAT HIDES ANYTHING. `openPlanner`
     * draws a not-yet panel for every graph state that is not READY, so a tree on screen means a
     * graph was loaded; the reachable null is `openPlan`, the screenshot harness drawing a
     * stored fixture that never came from one. See `PlannerWidgets.staleGraphWarning`.
     *
     * PUBLIC ONLY SO `shot.PlannerShot` CAN ASK THE SAME QUESTION. It builds one panel by hand
     * rather than through {@link #openPlan}, and its own note says that panel is "the same panel
     * `openPlan` would have built" -- a claim that stops being true the moment the two disagree
     * about what to pass here. DO NOT let a caller supply its own answer instead.
     */
    public static GraphFacts.SchemaCheck schemaCheck() {
        RecipeGraph graph = GraphService.get().graph();
        return graph == null ? null
                : GraphFacts.checkSchema(graph.dumpSchema(), DumpCommand.SCHEMA);
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
     * A COMBINED COUNTER, not just the plan's -- see {@link #stamp}. Weighting and summing is
     * enough here because all three terms only ever go UP: any change to any of them moves the
     * sum, and nothing needs to recover which one moved.
     *
     * AND THE GRAPH IS ONE OF THE THREE (#201). Without it a window opened during the 5.47 s
     * graph read watched only counters that describe a PLAN -- and no plan can exist before
     * the graph lands, so it watched two numbers that were frozen by design and never
     * rebuilt. Each term is named for what it is FOR rather than merely listed, because the
     * missing one survived #191 wiring three seams in this very file: a list of counters reads
     * as complete, and a list of the QUESTIONS the window is waiting on does not.
     *
     * A COUNTER POLLED FROM `onUpdate` RATHER THAN A CALLBACK. `onUpdate` is ModularUI's
     * per-client-tick hook, so this runs on the thread that is allowed to touch a GUI; a
     * callback fired from the solver thread would rebuild widgets off the client thread,
     * which in 1.12.2 surfaces as a ConcurrentModificationException from inside a GUI and
     * gets blamed on the GUI. The cost is one volatile read per tick.
     *
     * REOPENING THROWS AWAY SCROLL POSITION AND ANY OPEN SUB-PANEL, and that is acceptable
     * ONLY where there is no scroll position to lose. DO NOT extend this to redraw on a tick
     * counter, on a clock, on `progress()` as a raw float, or on a byte count; a window that
     * reset the player's scroll on a tick would be worse than one that never refreshed. That
     * prohibition is unchanged and it is the reason the paragraph below has to argue rather
     * than assert.
     *
     * THE ONE TERM THAT IS NOT A GENERATION BUMP IS THE GRAPH READ (#271), AND IT IS ADMITTED
     * ON TWO MEASURED FACTS RATHER THAN ON PLAUSIBILITY:
     *
     *   1. IT IS BOUNDED, at {@link #LOAD_STEPS} extra rebuilds for a whole 5.47 s read and at
     *      zero afterwards -- `GraphService.progress()` returns -1 outside LOADING and
     *      {@link #loadStep} returns 0 for that. A READY graph costs exactly what it cost
     *      before. That is the prohibition's cost argument answered with a number.
     *   2. THERE IS NOTHING TO LOSE WHILE IT FIRES. `PlannerEntry.stateFor` returns a non-null
     *      `PlannerState` for EVERY graph state that is not READY, so while the read is running
     *      `builderFor` can only build `PlannerWidgets.statePanel` -- an eyebrow and one line of
     *      text, no `ListWidget`, no scroll, no sub-panel to close.
     *      `PlannerScreenTest.theLoadingPanelHasNoScrollPositionToThrowAway` pins both halves,
     *      because "the panel is only two lines" is a claim about OTHER code and would rot
     *      silently the day a progress bar or a cancel button is added to it.
     *
     * DO NOT let the load term survive a change that puts a scrollable widget on the loading
     * panel. The two facts are what buy the exception, and the second one stops holding first.
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
            // FIRST, BEFORE THE STAMP IS READ (#201). A window opened during the graph read is
            // holding the target it was opened for; this is the tick that notices the graph has
            // landed and asks the question. Reading the stamp first would rebuild on the graph
            // counter alone and draw "nothing planned yet" for a frame before the plan started.
            //
            // ON THIS THREAD AND NOT FROM `GraphService.onLoad`, which is the hook that looks
            // right and is not: it runs on the loader thread and before READY is published. See
            // `PlannerEntry.resumeWhenTheGraphLands` and the counter note above.
            PlannerEntry.resumeWhenTheGraphLands();
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
