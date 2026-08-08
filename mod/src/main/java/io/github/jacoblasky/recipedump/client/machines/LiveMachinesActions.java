package io.github.jacoblasky.recipedump.client.machines;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.SecondaryPanel;

import io.github.jacoblasky.recipedump.client.PlannerStock;
import io.github.jacoblasky.recipedump.common.MachinesService;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.plan.MachineTable;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * The real actions, against a live screen.
 *
 * THE FILTER LIVES HERE AND NOT IN THE WIDGETS, which is what keeps `MachinesWidgets` a pure
 * function of its arguments. A click updates this object and asks the screen to rebuild; the
 * widgets are handed the result and never own it. The alternative -- a widget that mutated a
 * filter -- would make every layout assertion depend on click order.
 *
 * THE SUB-PANELS ARE BUILT LAZILY, one per open, exactly as `LivePlannerActions` builds its
 * three: `IPanelHandler.simple` caches the panel it built, so the mod picker would otherwise
 * show counts narrowed by a filter two clicks old, and the detail panel would show the
 * previous row.
 */
public final class LiveMachinesActions implements MachinesActions {

    /**
     * Rebuilds the window with the current filter. Set by the screen.
     *
     * A CALLBACK RATHER THAN A DIRECT `ClientGUI.open` HERE, because the screen owns how it
     * redraws -- it has a generation counter to carry across and a panel to re-attach -- and
     * two places deciding that is how one of them forgets the counter and the window stops
     * refreshing.
     */
    public interface Redraw {
        void redraw();
    }

    private ModularPanel parent;
    private Redraw redraw;
    private IPanelHandler modPicker;
    private IPanelHandler detail;

    /** The row the next detail panel describes. Client thread only, like every field here. */
    private MachineTable.Row current;

    private MachineTable.Filter filter = MachineTable.Filter.NONE;

    /**
     * Attach to the panel the sub-panels hang off.
     *
     * SEPARATE FROM THE CONSTRUCTOR because the dependency is circular: the widgets need the
     * actions to be built, and `IPanelHandler.simple` needs the finished panel those widgets
     * are in. Same shape as `LivePlannerActions.attachTo`, and the same failure if it is
     * skipped -- every sub-panel hangs off a panel nobody ever sees.
     */
    public void attachTo(ModularPanel panel) {
        this.parent = panel;
    }

    public void redrawWith(Redraw redraw) {
        this.redraw = redraw;
    }

    /**
     * The filter to draw, reconciled against the table it will be drawn over.
     *
     * RECONCILED ON READ RATHER THAN ON WRITE, and the difference matters because the TABLE
     * can change under a filter that never moved: the service rebuilds when the player places
     * a machine, and a mod that had three `no route` categories can have none a minute later.
     * Reconciling only when a chip is clicked would leave that selection lit over an empty
     * table, which is the bug #16 was filed for arriving by a different road.
     */
    public MachineTable.Filter filterFor(MachineTable table) {
        filter = table.reconcile(filter);
        return filter;
    }

    @Override
    public void toggleState(int state) {
        Interactable.playButtonClickSound();
        filter = filter.toggleState(state);
        redraw();
    }

    @Override
    public void openModPicker() {
        open(modPicker());
    }

    @Override
    public void chooseMod(String mod) {
        Interactable.playButtonClickSound();
        filter = filter.withMod(mod);
        closeModPicker();
        redraw();
    }

    @Override
    public void openDetail(MachineTable.Row row) {
        current = row;
        open(detail());
    }

    /**
     * Plan the candidate the reader picked, and leave the machines screen for the planner.
     *
     * ONE OF THE MACHINE ITEM, NOT A COUNT THE SCREEN CHOSE. A machines row is a category and
     * a category is not a quantity of anything, so there is no aggregate to get wrong here --
     * which is why this sidesteps #251 rather than inheriting it. One is also the honest
     * default: a player who needs two will change it in the planner, and a screen that guessed
     * a number would be stating a requirement it has no basis for.
     *
     * THROUGH `PlannerStock` FOR THE REASON `LivePlannerActions.pinRecipe` GIVES: a player who
     * has had this window open for a while is asking a fresh question, and pricing it against
     * a stock read several minutes ago would route around items they have since spent. It
     * costs nothing when the held read is still fresh.
     *
     * THE PLANNER WINDOW IS NOT OPENED HERE. `PlannerService.plan` is asynchronous and this
     * runs on the client thread; opening the planner immediately would show its "nothing
     * planned yet" panel for the length of the solve, which reads as the click having failed.
     * The player opens it with the item, which is the gesture they already know, and by then
     * there is a plan waiting.
     */
    @Override
    public void planMachine(final String key) {
        Interactable.playButtonClickSound();
        closeDetail();
        PlannerStock.planWhenRead(new Runnable() {
            @Override
            public void run() {
                PlannerService.get().plan(key, 1L, Solver.DEFAULT_MAX_NODES);
            }
        });
    }

    private void redraw() {
        if (redraw != null) {
            redraw.redraw();
        }
    }

    private void open(IPanelHandler handler) {
        // The click sound lives here rather than in the widget: it reaches the sound handler
        // and therefore LWJGL, and a widget that made a noise could not be clicked in a
        // headless test.
        Interactable.playButtonClickSound();
        if (handler == null) {
            return;
        }
        // Deleted first, because `simple` caches the panel it built and would otherwise show
        // the PREVIOUS row's detail, or a mod list narrowed by a filter that has since moved.
        handler.deleteCachedPanel();
        handler.openPanel();
    }

    private void closeModPicker() {
        if (modPicker != null) {
            modPicker.closePanel();
        }
    }

    private void closeDetail() {
        if (detail != null) {
            detail.closePanel();
        }
    }

    /**
     * Null when nothing has attached -- which is the screenshot harness and the layout tests.
     * They build the widgets and never click, so a missing handler is correct rather than an
     * error, and {@link #open} treats it as a no-op.
     */
    private IPanelHandler modPicker() {
        if (modPicker == null && parent != null) {
            modPicker = IPanelHandler.simple(parent, new SecondaryPanel.IPanelBuilder() {
                @Override
                public ModularPanel build(ModularPanel opener,
                                          net.minecraft.entity.player.EntityPlayer player) {
                    // THE TABLE IS LOOKED UP HERE, at open time, and not inside the widget.
                    // `MachinesWidgets` takes data and nothing else, which is what lets every
                    // layout assertion in this package run with no graph and no window; a
                    // widget that reached for `MachinesService` would end that.
                    MachineTable table = MachinesService.get().table();
                    return MachinesWidgets.modPicker(table, filter, LiveMachinesActions.this);
                }
            }, true);
        }
        return modPicker;
    }

    private IPanelHandler detail() {
        if (detail == null && parent != null) {
            detail = IPanelHandler.simple(parent, new SecondaryPanel.IPanelBuilder() {
                @Override
                public ModularPanel build(ModularPanel opener,
                                          net.minecraft.entity.player.EntityPlayer player) {
                    return MachinesWidgets.detailPanel(current, LiveMachinesActions.this);
                }
            }, true);
        }
        return detail;
    }
}
