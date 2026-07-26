package io.github.jacoblasky.recipedump;

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
@Mod(modid = RecipeDumpMod.MODID, useMetadata = true,
     clientSideOnly = true, dependencies = "required-after:jei")
public class RecipeDumpMod {

    public static final String MODID = "mcrecipedump";

    /** Set by DumpPlugin once JEI finishes loading; null before then. */
    public static IJeiRuntime runtime;

    /**
     * The running mod's version, read from the metadata Forge already parsed.
     *
     * `useMetadata = true` above is what makes gradle.properties the ONLY place a version
     * lives: it flows to mcmod.info through processResources, Forge reads it from there, and
     * nothing in Java restates it. A `VERSION` constant here previously said 0.1.0 against a
     * 0.4.1 build, so Forge, the mod list and every crash report named a release that never
     * existed. DO NOT reintroduce one -- an annotation needs a compile-time constant, which
     * is exactly why the duplication could not be kept honest.
     */
    public static String version() {
        try {
            net.minecraftforge.fml.common.ModContainer mod =
                    net.minecraftforge.fml.common.Loader.instance()
                            .getIndexedModList().get(MODID);
            if (mod != null && mod.getVersion() != null) {
                return mod.getVersion();
            }
        } catch (Throwable ignored) {
            // Metadata is best-effort; a dump is still valid without a version stamp.
        }
        return "unknown";
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new DumpCommand());
    }
}
