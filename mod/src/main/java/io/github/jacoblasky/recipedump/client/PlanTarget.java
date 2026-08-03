package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.client.jei.PlannerHooks;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlanBookCapability;
import io.github.jacoblasky.recipedump.common.PlannerService;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * What the `=` keybind does with the item it resolved: the planner's half of
 * {@link PlannerHooks.TargetListener}.
 *
 * THE SIBLING OF {@code PlannerAreaSource}, and it was missing for the whole of #19 phase 4.
 * The two seams sit side by side on `PlannerHooks`; the area source was installed from
 * `PlannerScreen` and this one was installed by nothing, so pressing the key over an item in
 * JEI resolved a target and dropped it -- exactly as `PlannerHooks`' default documents, in the
 * game, for the one interaction the whole of #19 exists for (#191).
 *
 * <h2>The key goes THROUGH the graph, not around it</h2>
 *
 * {@link DumpCommand#stackKey} is the one place the key format exists, and it is the same
 * function the dump used to write `graph.json` and the same one `Ae2StockReader` uses on a live
 * stack. That is what makes what the player points at land on the key the graph holds. DO NOT
 * key a stack any other way here; a second spelling resolves to nothing and reads as "that item
 * is not in the pack".
 *
 * <h2>Not being in the graph is an ANSWER, not a reason to do nothing</h2>
 *
 * `PlanTargetKeybind` has four ways to do nothing and keeps them indistinguishable, because
 * every one of them is an idle keypress. This is not one of those: the player pointed at a real
 * item and asked for it. So the window opens either way and {@code PlannerEntry} says which of
 * "still loading", "no graph.json" or "no such item in the graph" happened -- three different
 * things to go and do, and silence is none of them.
 *
 * The one case that DOES decline is a solve already running, which is the example
 * `PlannerHooks.TargetListener` gives for returning false: `PlannerService` refuses to queue,
 * so accepting would move the player to a window that is going to ignore them.
 */
public final class PlanTarget implements PlannerHooks.TargetListener {

    private PlanTarget() {
    }

    /**
     * Install this as the keybind's listener. Called once, from {@code ClientProxy.init}.
     *
     * BESIDE `PlanTargetKeybind.register()` AND NOT FROM THE PLANNER SCREEN, which is the
     * difference from the area source. JEI asks where the planner is while it is open, so that
     * seam is per-panel; the keybind fires over JEI's item list with no planner open at all, so
     * a listener installed when a window opens would be installed only after the feature had
     * already been used once and done nothing.
     */
    public static void install() {
        PlannerHooks.setTargetListener(new PlanTarget());
    }

    @Override
    public boolean onPlanTarget(ItemStack stack) {
        return accept(stack, bookOfLocalPlayer());
    }

    /**
     * The decision, with the book handed in rather than fetched.
     *
     * SEPARATE BECAUSE {@link #bookOfLocalPlayer} CANNOT BE CALLED FROM A TEST AT ALL. Merely
     * loading `Minecraft` needs LWJGL, which is not on a JUnit classpath, so any method that
     * names it throws `NoClassDefFoundError` before reaching its first line -- measured, not
     * assumed. Everything worth getting wrong is on this side of that wall: which refusals are
     * refusals, and in which order.
     */
    static boolean accept(ItemStack stack, PlanBook book) {
        if (PlannerService.get().state() == PlannerService.State.PLANNING) {
            return false;
        }
        String key = DumpCommand.stackKey(stack);
        if (key == null || key.isEmpty()) {
            // An item the dump could not key is one the graph cannot contain, so there is
            // nothing to open a window about.
            return false;
        }
        if (book == null) {
            return false;
        }
        PlannerEntry.openOn(book, key);
        return true;
    }

    /**
     * The local player's plan book, or null when there is no player or no capability.
     *
     * NULL RATHER THAN A MESSAGE, unlike `ClientProxy.openPlanner`, which tells the player the
     * capability is missing. That call is a right-click on an item the player crafted and is
     * holding, so silence would be a broken item; this one is a keypress, and a chat line on a
     * key that may have been pressed by accident is noise. The right-click path still reports
     * it, so the same fault is still visible.
     */
    private static PlanBook bookOfLocalPlayer() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        EntityPlayer player = mc.player;
        return player == null ? null : PlanBookCapability.of(player);
    }
}
