package com.meatballcraft.recipedump;

import mezz.jei.api.IJeiRuntime;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Dumps every recipe HEI/JEI knows about to NDJSON, for offline crafting-tree tools.
 *
 * CLIENT SIDE ONLY, by necessity: JEI's recipe registry only exists on the client,
 * because that is where recipe categories are registered. Recipe *contents* are
 * identical client- and server-side, so a client dump is valid for a server world.
 */
@Mod(modid = MbcRecipeDump.MODID, name = MbcRecipeDump.NAME, version = MbcRecipeDump.VERSION,
     clientSideOnly = true, dependencies = "required-after:jei")
public class MbcRecipeDump {

    public static final String MODID = "mbcrecipedump";
    public static final String NAME = "MBC Recipe Dump";
    public static final String VERSION = "0.1.0";

    /** Set by DumpPlugin once JEI finishes loading; null before then. */
    public static IJeiRuntime runtime;

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new DumpCommand());
    }
}
