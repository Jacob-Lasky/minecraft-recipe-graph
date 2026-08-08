package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.common.CommonProxy;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.PlanTargetKeybind;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
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
        // Registered here rather than in a static initialiser: `registerKeyBinding` writes
        // into the game settings, and doing that whenever the class happens to load is how a
        // binding ends up registered twice. Safe without JEI -- the key resolves nothing and
        // says nothing, which is what an unbound feature should do.
        PlanTargetKeybind.register();
        // AND THE LISTENER THAT GIVES THE KEY SOMETHING TO DO. Registering the binding without
        // this is what the mod shipped for the whole of #19 phase 4: the key resolved a JEI
        // target and dropped it, because `PlannerHooks`' default does exactly that and does it
        // silently (#191). The two lines belong together -- a binding with no listener is a
        // control that answers nothing, and neither half fails on its own.
        PlanTarget.install();
        // ANSWER FOR `ScenarioSource.HAVE` FROM HERE ON, so a plan is priced against what the
        // player owns instead of against the assumption that they own nothing. Installed at
        // init rather than when the planner opens: the source is asked while a plan is being
        // built, and a reader installed by the window would leave the FIRST plan -- the one
        // started as the window opens -- reading as unwired.
        PlannerStock.install();
        // BUILD JEI'S STACK INDEX WHEN THE GRAPH LANDS, on the loader thread, so the first
        // context menu does not pay for a walk of ~35,000 item stacks. `indexFor` is
        // null-safe and does nothing useful without JEI, so this is unconditional; the
        // listener runs before `GraphService` publishes READY, which is what keeps it off
        // the frame that first reads the graph. See graphmodel's note on `JeiBridge.indexOf`,
        // which rebuilds whenever the graph's IDENTITY changes -- `GraphService.graph()` is a
        // plain field read and returns the same object until a reload, which is the property
        // that makes that comparison cheap rather than a per-frame rebuild.
        GraphService.get().onLoad(new GraphService.Listener() {
            @Override
            public void graphLoaded(RecipeGraph graph) {
                JeiBridge.indexFor(graph);
            }
        });
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
     * Take the stock snapshot the server read off this player's ME network.
     *
     * ON THE CLIENT THREAD, for the reason {@link #applyPlanBookSync} records: `onMessage` runs
     * on a netty IO thread, and this hands the snapshot to `PlannerStock`, which may start a
     * plan and will certainly be read while a panel is drawing.
     *
     * WITHOUT THIS OVERRIDE THE REPLY LANDS IN `CommonProxy`'S EMPTY BODY, which is where it
     * landed from the moment the packet was written until #191 -- the server read the grid,
     * serialised it, sent it, and the client threw it away with no error anywhere.
     */
    @Override
    public void applyStockSnapshot(final NBTTagCompound payload) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                PlannerStock.accept(payload);
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
            PlannerEntry.open(book);
        } catch (Throwable missing) {
            tell(player, "the planner needs ModularUI 3.1.5, which is not installed");
        }
    }

    /**
     * Open the machines table.
     *
     * GUARDED THE SAME WAY {@link #openPlanner} IS, AND FOR THE SAME REASON RATHER THAN BY
     * SYMMETRY. `MachinesScreen` names ModularUI directly, so it is loaded HERE, on the first
     * shift-right-click, rather than when this proxy is constructed. The shipped jar
     * deliberately declares no ModularUI dependency -- the screenshot harness relies on that
     * too -- so a pack without it must still load the mod and still dump.
     *
     * DO NOT "simplify" this by importing `MachinesScreen` at the top and dropping the catch.
     * That is the eager-resolution trap the two reflective bridges document at length: the
     * class reference would move into this proxy's constant pool and every client without
     * ModularUI would fail to load the proxy, taking the dump command with it.
     *
     * IT NEEDS NO PLAN BOOK, unlike the planner. The machines table is a view of the pack, not
     * of the player, which is what lets this open when the planner would only show a not-yet
     * panel.
     */
    @Override
    public void openMachines(EntityPlayer player) {
        try {
            MachinesScreen.open();
        } catch (Throwable missing) {
            tell(player, "the machines table needs ModularUI 3.1.5, which is not installed");
        }
    }

    private static void tell(EntityPlayer player, String message) {
        player.sendMessage(new TextComponentString("[mc-recipe-dump] " + message));
    }
}
