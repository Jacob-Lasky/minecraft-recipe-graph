package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.SecondaryPanel;

import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PinStore;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.common.net.PlanBookEdit;
import io.github.jacoblasky.recipedump.common.net.PlanBookEditMessage;
import io.github.jacoblasky.recipedump.common.net.PlanBookNetwork;

/**
 * The real actions, against a live screen and a live server connection.
 *
 * THE EDITS GO TO THE SERVER RATHER THAN TO THE LOCAL BOOK, which is #140's rule and not a
 * detour: the server is the only writer, and it answers with a whole-book sync. A local edit
 * would show a star the server may have rejected -- the book is capped -- and the two sides
 * would then disagree about what is favourited with nothing to reconcile them.
 *
 * The sub-panels are built LAZILY, one per open, because the node they describe changes with
 * every click. `IPanelHandler.simple`'s builder is invoked at open time, so the field holding
 * the node only has to be right at that moment.
 */
public final class LivePlannerActions implements PlannerActions {

    private ModularPanel parent;
    private IPanelHandler menu;
    private IPanelHandler picker;

    /** The node the next sub-panel describes. Client thread only, like every field here. */
    private PlanNode current;

    /**
     * Attach to the panel the sub-panels will hang off.
     *
     * SEPARATE FROM THE CONSTRUCTOR because the dependency is circular: the widgets need the
     * actions in order to be built, and `IPanelHandler.simple` needs the finished panel those
     * widgets are in. Building the panel twice -- once to get a parent, once for real -- was
     * the first attempt, and it left every sub-panel hanging off a panel nobody ever saw.
     */
    public void attachTo(ModularPanel panel) {
        this.parent = panel;
    }

    @Override
    public NodeActions nodeActions() {
        return NodeActionsHolder.actions();
    }

    @Override
    public void openNodeMenu(PlanNode node) {
        current = node;
        open(menu());
    }

    @Override
    public void openRecipePicker(PlanNode node) {
        current = node;
        open(picker());
    }

    /**
     * Write the pin, then ask for the plan again.
     *
     * NOT SENT TO THE SERVER, unlike the plan-book edits above, and the difference is what
     * each thing IS. A favourite is per-player state the server owns and syncs; a pin is a
     * statement about the pack, held in a file beside `graph.json`, and the planner that
     * reads it runs on this client. #140's one-writer rule is about the book, not about
     * everything a click can touch.
     *
     * THE SAVE IS NOT CHECKED HERE and that is deliberate. `PinStore.pin` returns false when
     * the file could not be written and records why, and the reader it installed on
     * `ScenarioSource.PINS` puts that sentence on the planner's caveat line -- which the
     * re-solve below is about to redraw. Handling it here as well would be a second place
     * that decides how a failed pin is phrased.
     */
    @Override
    public void pinRecipe(PlanNode node, RecipeChoice choice) {
        Interactable.playButtonClickSound();
        if (choice.pinned()) {
            PinStore.get().unpin(node.key());
        } else {
            PinStore.get().pin(node.key(), choice.pin());
        }
        closePicker();
        closeMenu();
        // A pin changes an INPUT, so the answer has to be computed again -- there is no way
        // to patch one subtree, because another recipe takes other ingredients and the cost
        // of the whole branch moves. Cheap in the usual case: `costSignature` does not
        // mention pins, so the cached cost table survives and only the solve is repeated.
        PlannerService.get().replan();
    }

    @Override
    public void addToTodo(PlanNode node) {
        PlanBookNetwork.CHANNEL.sendToServer(
                new PlanBookEditMessage(PlanBookEdit.SET_TODO, node.key(), node.need()));
        closeMenu();
    }

    @Override
    public void toggleFavourite(PlanNode node) {
        // ADD, not toggle, and the asymmetry is deliberate. #140 syncs the book to the client,
        // but this panel is built from a PLAN rather than from the book, so it has no copy to
        // consult for the current state. Adding twice is a no-op on the server --
        // `addFavourite` returns false and the sync comes back unchanged -- whereas guessing
        // "remove" wrong deletes something the player wanted.
        PlanBookNetwork.CHANNEL.sendToServer(
                new PlanBookEditMessage(PlanBookEdit.ADD_FAVOURITE, node.key(), 0L));
        closeMenu();
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
        // the PREVIOUS node's menu on the second click.
        handler.deleteCachedPanel();
        handler.openPanel();
    }

    private void closeMenu() {
        if (menu != null) {
            menu.closePanel();
        }
    }

    private void closePicker() {
        if (picker != null) {
            picker.closePanel();
        }
    }

    /**
     * Lazily, because {@link #attachTo} runs after construction and a handler built before it
     * would have a null parent.
     *
     * Null when nothing has attached -- which is the screenshot harness and the layout tests.
     * They build the widgets and never click, so a missing handler is correct rather than an
     * error, and {@link #open} treats it as a no-op.
     */
    private IPanelHandler menu() {
        if (menu == null && parent != null) {
            menu = IPanelHandler.simple(parent, new SecondaryPanel.IPanelBuilder() {
                @Override
                public ModularPanel build(ModularPanel opener,
                                          net.minecraft.entity.player.EntityPlayer player) {
                    return PlannerWidgets.nodeMenu(current, LivePlannerActions.this);
                }
            }, true);
        }
        return menu;
    }

    private IPanelHandler picker() {
        if (picker == null && parent != null) {
            picker = IPanelHandler.simple(parent, new SecondaryPanel.IPanelBuilder() {
                @Override
                public ModularPanel build(ModularPanel opener,
                                          net.minecraft.entity.player.EntityPlayer player) {
                    // THE CANDIDATES ARE LOOKED UP HERE, at open time, and not inside the
                    // widget. `PlannerWidgets` takes data and nothing else, which is what
                    // lets every layout assertion in this package run with no graph and no
                    // window; a widget that reached for `GraphService` would end that.
                    return PlannerWidgets.recipePicker(
                            current,
                            RecipeChoices.forNode(GraphService.get().graph(), current,
                                                  PinStore.get().pins()),
                            LivePlannerActions.this);
                }
            }, true);
        }
        return picker;
    }
}
