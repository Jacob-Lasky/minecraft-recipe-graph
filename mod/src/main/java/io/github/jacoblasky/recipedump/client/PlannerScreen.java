package io.github.jacoblasky.recipedump.client;

import java.util.function.Function;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.client.planner.LivePlannerActions;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerAreaSource;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.common.PlanBook;

/**
 * Opens the planner's windows. Everything they contain is built by {@link PlannerWidgets},
 * which is where the design lives and where the tests point.
 *
 * TWO ENTRY POINTS BECAUSE THERE ARE TWO STATES, and the split is honest rather than
 * scaffolding:
 *
 *   - {@link #open} is what the calculator item does today. There is no Java solver yet
 *     (#141), so what the mod can truthfully show is the plan book it already keeps -- the
 *     TODO list, synced from the server, which is a working feature from #140.
 *   - {@link #openPlan} takes a solved plan and draws the tree. It is what the screenshot
 *     harness drives against the frozen fixtures and what the solver will call with a live
 *     result. The argument type does not change between those two, because
 *     `tests/fixtures/plan/*.json` IS the solver's output shape.
 *
 * DO NOT MAKE THIS A `CustomModularScreen` SUBCLASS. That constructor hands `this::buildUI`
 * to `ModularScreen`, which calls it during `super(...)`, before any subclass field is
 * assigned -- so a screen holding its data in a field reads null while building its own
 * panel. Measured in #140, where the only symptom the client gave was
 * `opening 'planner' threw NullPointerException`, with the stack swallowed by `ShotScreens`.
 * A captured local cannot go wrong that way.
 */
public final class PlannerScreen {

    private PlannerScreen() {
    }

    /** The plan book, with no plan. What the calculator item shows until #141 lands. */
    public static void open(final PlanBook book) {
        openPanel(new Function<ModularGuiContext, ModularPanel>() {
            @Override
            public ModularPanel apply(ModularGuiContext context) {
                ModularPanel panel = PlannerWidgets.todoPanel(PlanView.empty(), book);
                PlannerAreaSource.install(panel);
                return panel;
            }
        });
    }

    /**
     * A solved plan, as a tree.
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
                LivePlannerActions actions = new LivePlannerActions();
                ModularPanel panel = PlannerWidgets.plannerPanel(plan, book, actions);
                actions.attachTo(panel);
                // So JEI lays its item list out AROUND the planner rather than over it
                // (#145's other seam). The source reports nothing once the panel closes, so
                // there is nothing to deregister.
                PlannerAreaSource.install(panel);
                return panel;
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
}
