package io.github.jacoblasky.recipedump.common;

import io.github.jacoblasky.recipedump.common.net.PlanBookNetwork;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * What the mod does on BOTH sides. The client half is {@code client.ClientProxy}.
 *
 * The mod was `clientSideOnly = true` until #19 Phase 2, and the reason was JEI: its recipe
 * registry only exists on the client, so a dump can only be taken there. That is still true
 * and nothing about the dump moves. What changed is that the planner needs a craftable item,
 * a per-player capability and packets, and those are common-side by nature -- Jake plays on a
 * dedicated server, so a client-only capability would be a capability the server never stores.
 *
 * THE RULE THIS SPLIT EXISTS TO ENFORCE: nothing reachable from this class may touch
 * `net.minecraft.client` or `mezz.jei`, because on a dedicated server those classes are not
 * there to load. `CommonSideSafetyTest` reads the compiled classes and asserts it, because
 * the failure otherwise happens on Jake's server at startup, which is the one place nobody
 * working on this can test.
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        PlanBookCapability.register();
        PlanBookNetwork.register();
        // STARTED HERE, AT preInit, AND NOT WHEN THE PLANNER IS FIRST OPENED. The read is
        // 5.47 s measured, so a player who right-clicks the calculator and waits five seconds
        // for a window has been given a slow tool; started at load, the graph is ready long
        // before anything is crafted. It costs nothing when there is no file -- `startLoad`
        // resolves to MISSING without touching a thread -- so a pack that never supplies one
        // pays only a `File.isFile()`.
        //
        // COMMON, not client. The graph is pack data and Phase 5 wants it server-side to read
        // a real AE2 grid, so a server loading it now is the intended end state rather than
        // waste. `GraphService` names no client class.
        GraphService.get().startLoad(event.getModConfigurationDirectory());
    }

    public void init(FMLInitializationEvent event) {
    }

    /**
     * Take a synced plan book. A no-op on a server, which is the authority and never receives
     * one; {@code ClientProxy} copies it into the local player's book.
     *
     * On the proxy rather than in the packet handler because the handler class is loaded on
     * both sides -- see {@code PlanBookSyncMessage.Handler}.
     */
    public void applyPlanBookSync(NBTTagCompound payload) {
    }

    /**
     * Open the planner UI. A no-op on a server, which has no screens.
     *
     * The calculator item runs on both sides, so without this the item could not open
     * anything without dragging {@code net.minecraft.client} into a common class.
     */
    public void openPlanner(EntityPlayer player) {
    }
}
