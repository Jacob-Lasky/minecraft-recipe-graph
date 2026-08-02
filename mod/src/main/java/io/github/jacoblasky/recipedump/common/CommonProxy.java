package io.github.jacoblasky.recipedump.common;

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
    }

    public void init(FMLInitializationEvent event) {
    }
}
