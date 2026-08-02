package io.github.jacoblasky.recipedump;

import io.github.jacoblasky.recipedump.common.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Dumps every recipe HEI/JEI knows about to NDJSON, for offline crafting-tree tools, and from
 * #19 hosts the in-game planner.
 *
 * COMMON-SIDE SINCE #19 PHASE 2, and the distinction that makes that safe is between the MOD
 * and the DUMP. The dump is client-only by necessity -- JEI's recipe registry only exists
 * where categories are registered, and recipe contents are identical on both sides, so a
 * client dump is valid for a server world. The planner is not: a craftable item, a per-player
 * capability and packets have to exist on the server Jake actually plays on. So the client
 * half moved behind `ClientProxy` and the mod itself now loads on both sides.
 *
 * NOTHING IN THIS CLASS MAY REFERENCE `net.minecraft.client` OR `mezz.jei`. It is the one
 * class a dedicated server is guaranteed to load, so a stray import here is a server that
 * will not start -- and nobody working on this repository can launch one to find out.
 * `CommonSideSafetyTest` reads the compiled bytes and asserts it.
 */
@Mod(modid = RecipeDumpMod.MODID, useMetadata = true, dependencies = RecipeDumpMod.DEPENDENCIES)
public class RecipeDumpMod {

    public static final String MODID = "mcrecipedump";

    /**
     * `after`, NOT `required-after:jei`, and dropping the "required" was forced rather than
     * chosen.
     *
     * 1.12.2's `@Mod` dependency string has no way to say "required on the client only", and
     * JEI does not exist on a dedicated server. Left as `required-after` the mod would refuse
     * to load on Jake's server, which is the environment Phase 2 exists to reach. `after` still
     * buys the thing that actually matters -- JEI loads first, so `DumpPlugin` is registered
     * before anything asks for a runtime.
     *
     * What it costs is that a CLIENT without JEI now loads this mod and has a `/recipedump`
     * that cannot work. `DumpCommand` already handles that: it checks `DumpPlugin.runtime` for
     * null and says "JEI runtime not available yet" rather than throwing.
     *
     * THIS STRING IS THE ONLY PLACE DEPENDENCIES ARE DECLARED, and mcmod.info deliberately no
     * longer carries a `dependencies` array. It used to, and it did nothing: FML reads
     * dependencies from the metadata only when mcmod.info sets `useDependencyInformation`,
     * which this one never has, so `FMLModContainer.bindMetadata` parsed this annotation and
     * ignored the JSON. Two declarations, one inert, is how they drift -- verified by
     * disassembling `bindMetadata` rather than by reading a wiki.
     */
    static final String DEPENDENCIES = "after:jei";

    /**
     * The client half. FML never loads `ClientProxy` on a dedicated server, which is what
     * keeps `net.minecraft.client` and JEI out of a server's class loading entirely.
     */
    @SidedProxy(modId = MODID,
                clientSide = "io.github.jacoblasky.recipedump.client.ClientProxy",
                serverSide = "io.github.jacoblasky.recipedump.common.CommonProxy")
    public static CommonProxy proxy;

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
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
