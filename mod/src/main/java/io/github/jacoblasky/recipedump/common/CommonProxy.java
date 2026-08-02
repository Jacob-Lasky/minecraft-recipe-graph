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
     * Take a stock snapshot the server read off the player's ME network.
     *
     * A no-op on a server for the same reason as the book sync: the server is where the grid
     * lives and it never receives one of these. On the proxy rather than in the handler
     * because that handler class is loaded on both sides.
     */
    public void applyStockSnapshot(NBTTagCompound payload) {
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
