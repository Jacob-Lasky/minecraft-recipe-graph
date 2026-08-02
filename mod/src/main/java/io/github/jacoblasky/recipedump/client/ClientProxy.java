package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.common.CommonProxy;
import io.github.jacoblasky.recipedump.shot.ShotHarness;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/**
 * Everything that only exists on a client: the dump command and the screenshot harness.
 *
 * This class, not `RecipeDumpMod`, is where `net.minecraft.client` and JEI are allowed. It is
 * reached only through `@SidedProxy`, which means FML never loads it on a dedicated server --
 * that is the whole mechanism, and it is why moving these two calls out of the mod class was
 * the point of the split rather than a tidy-up.
 *
 * THE DUMP IS STILL CLIENT-ONLY AND ALWAYS WILL BE. JEI's recipe registry is built from
 * client-side category registration, so there is nothing on a server to dump. Recipe contents
 * are identical on both sides, so a client dump remains valid for a server world.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientCommandHandler.instance.registerCommand(new DumpCommand());
        // Does nothing at all unless `-Dmcrecipedump.shot` was passed, which only the
        // headless screenshot harness does. See ShotHarness and harness/README.md (#124).
        ShotHarness.arm();
    }
}
