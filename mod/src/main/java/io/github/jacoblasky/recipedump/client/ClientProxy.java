package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.common.CommonProxy;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlanBookCapability;
import io.github.jacoblasky.recipedump.shot.ShotHarness;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/**
 * Everything that only exists on a client: the dump command, the screenshot harness, and the
 * planner window.
 *
 * This class, not `RecipeDumpMod`, is where `net.minecraft.client` and JEI are allowed. It is
 * reached only through `@SidedProxy`, which means FML never loads it on a dedicated server --
 * that is the whole mechanism, and it is why moving these calls out of the mod class was the
 * point of the split rather than a tidy-up.
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

    /**
     * Copy the server's book onto the local player's.
     *
     * ON THE CLIENT THREAD. `onMessage` runs on a netty IO thread, and writing a capability
     * that the render thread is reading is the classic 1.12.2 race: it shows up as a
     * ConcurrentModificationException from inside a GUI, blamed on the GUI.
     */
    @Override
    public void applyPlanBookSync(final NBTTagCompound payload) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                PlanBook book = PlanBookCapability.of(mc.player);
                if (book != null) {
                    book.deserializeNBT(payload);
                }
            }
        });
    }

    /**
     * Open the planner window.
     *
     * GUARDED, because the shipped jar deliberately declares no ModularUI dependency -- the
     * screenshot harness relies on that too, and `ShotHarness` reaches ModularUI reflectively
     * for the same reason. `PlannerScreen` names ModularUI directly, so it is loaded HERE, on
     * the first right-click, rather than when this proxy is constructed. A pack without
     * ModularUI therefore still loads the mod, still dumps, and says so once if the item is
     * used.
     *
     * DO NOT "simplify" this by importing `PlannerScreen` at the top and dropping the catch.
     * That is the same eager-resolution trap the two reflective bridges document: the class
     * reference would move into this proxy's constant pool and every client without ModularUI
     * would fail to load the proxy, taking the dump command with it.
     */
    @Override
    public void openPlanner(EntityPlayer player) {
        PlanBook book = PlanBookCapability.of(player);
        if (book == null) {
            // The capability is attached to every player, so this means preInit never ran --
            // worth saying rather than opening an empty window that looks like data loss.
            tell(player, "plan book unavailable: the capability is not registered");
            return;
        }
        try {
            PlannerScreen.open(book);
        } catch (Throwable missing) {
            tell(player, "the planner needs ModularUI 3.1.5, which is not installed");
        }
    }

    private static void tell(EntityPlayer player, String message) {
        player.sendMessage(new TextComponentString("[mc-recipe-dump] " + message));
    }
}
