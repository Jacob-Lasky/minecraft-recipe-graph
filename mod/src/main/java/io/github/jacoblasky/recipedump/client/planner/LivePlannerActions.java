package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.SecondaryPanel;

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
        com.cleanroommc.modularui.api.widget.Interactable.playButtonClickSound();
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
                    return PlannerWidgets.recipePicker(current);
                }
            }, true);
        }
        return picker;
    }
}
