package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.SecondaryPanel;

import io.github.jacoblasky.recipedump.client.PlannerStock;
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
    private IPanelHandler caveats;
    private IPanelHandler rowMenu;

    /** The node the next sub-panel describes. Client thread only, like every field here. */
    private PlanNode current;

    /**
     * The shopping row the next row menu describes. Client thread only, like every field here.
     *
     * A SECOND FIELD RATHER THAN A WIDER TYPE ON `current`, because the two menus are about
     * different things and a shared field would let a row menu open on a node -- which is the
     * per-occurrence quantity #251 exists to keep out of this surface. Two fields make that
     * unrepresentable; one field plus a comment would make it merely unlikely.
     */
    private PlanView.EntryRow currentRow;

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
        // SELECTED FIRST, so opening a menu and highlighting the item are one click rather
        // than two. A diagram that stayed unhighlighted while its menu was open would read as
        // a diagram that had not registered the click.
        //
        // THE READER IS `PlannerWidgets.ClickableGroup.draw`, on both surfaces, and it did not
        // exist until #213 -- for six merged PRs this line wrote a selection nothing drew, and
        // the comment above it named `client.flow` as the reader it did not have. The write was
        // always correct; the missing half was the read.
        selectNode(node);
        current = node;
        open(menu());
    }

    @Override
    public void selectNode(PlanNode node) {
        PlanSelection.select(node);
    }

    @Override
    public void openRecipePicker(PlanNode node) {
        current = node;
        open(picker());
    }

    @Override
    public void openCaveats() {
        open(caveats());
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
        //
        // THROUGH `PlannerStock` FOR THE SAME REASON OPENING THE PLANNER DOES. A player who
        // has had the window open for a while and then pins a recipe is asking a fresh
        // question, and answering it against a stock read from several minutes ago would
        // price a route against items they have since spent. It costs nothing when the held
        // read is still fresh, which is the usual case for a click made while looking at the
        // plan the read produced.
        PlannerStock.planWhenRead(new Runnable() {
            @Override
            public void run() {
                PlannerService.get().replan();
            }
        });
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
    @Override
    public void openRowMenu(PlanView.EntryRow row) {
        // NO `selectNode` HERE, and its absence is the point. `openNodeMenu` selects first so
        // the diagram lights up the occurrence that was clicked; a shopping row IS every
        // occurrence at once, so there is no one node to select and picking any of them would
        // be #251's rejected option 1 arriving by way of a highlight.
        currentRow = row;
        open(rowMenu());
    }

    @Override
    public void addRowToTodo(PlanView.EntryRow row) {
        // `row.need()` AND NOT ANY NODE'S. This is the aggregate the whole issue is about: the
        // total the plan still wants of this item, across every parent that wants it, which is
        // the number a player gathering things acts on.
        PlanBookNetwork.CHANNEL.sendToServer(
                new PlanBookEditMessage(PlanBookEdit.SET_TODO, row.key(), row.need()));
        closeMenu();
    }

    @Override
    public void favouriteRow(PlanView.EntryRow row) {
        // ADD rather than toggle, and quantity 0, for the reasons `toggleFavourite` gives: this
        // panel is built from a PLAN and has no copy of the book to consult, and a favourite
        // carries no quantity.
        PlanBookNetwork.CHANNEL.sendToServer(
                new PlanBookEditMessage(PlanBookEdit.ADD_FAVOURITE, row.key(), 0L));
        closeMenu();
    }

    /**
     * The row menu's panel handler, built at open time like the other three.
     *
     * ITS OWN HANDLER AND NOT `menu()`, because `IPanelHandler.simple` caches the panel it
     * built and `open` deletes that cache before each open. Sharing one handler between the
     * node menu and the row menu would work by accident and break the moment either stopped
     * being deleted first.
     */
    private IPanelHandler rowMenu() {
        if (rowMenu == null && parent != null) {
            rowMenu = IPanelHandler.simple(parent, new SecondaryPanel.IPanelBuilder() {
                @Override
                public ModularPanel build(ModularPanel opener,
                                          net.minecraft.entity.player.EntityPlayer player) {
                    return PlannerWidgets.rowMenu(currentRow, LivePlannerActions.this);
                }
            }, true);
        }
        return rowMenu;
    }

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

    /**
     * The caveats panel, built at open time like the other two.
     *
     * AND FOR A SHARPER REASON THAN THEY HAVE. `ScenarioSource.status` is documented as being
     * read per plan and not cached, because a grid can go out of range between two plans -- so
     * a panel built once at attach time would keep showing the refusal that applied when the
     * window opened. `open` deletes the cached panel before every open, which is what makes
     * this re-ask.
     */
    private IPanelHandler caveats() {
        if (caveats == null && parent != null) {
            caveats = IPanelHandler.simple(parent, new SecondaryPanel.IPanelBuilder() {
                @Override
                public ModularPanel build(ModularPanel opener,
                                          net.minecraft.entity.player.EntityPlayer player) {
                    return PlannerWidgets.caveatsPanel();
                }
            }, true);
        }
        return caveats;
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
