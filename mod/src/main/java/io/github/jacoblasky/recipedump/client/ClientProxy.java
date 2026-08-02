package io.github.jacoblasky.recipedump.client;

import java.util.List;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.client.planner.PlanJson;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.plan.Solver;
import io.github.jacoblasky.recipedump.common.CommonProxy;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.client.jei.PlanTargetKeybind;
import io.github.jacoblasky.recipedump.common.PlanBookCapability;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
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
     * Hold the latest snapshot the server sent, for whatever asked.
     *
     * ON THE CLIENT THREAD, for the reason `applyPlanBookSync` records directly above: this
     * arrives on a netty IO thread and the planner reads it while rendering.
     *
     * REPLACED WHOLE, never merged into what was there. A snapshot is what the network held
     * at one instant; merging a new read into an old one would invent a state the network was
     * never in, and the item that quietly persisted is the one the player already used.
     */
    @Override
    public void applyStockSnapshot(final NBTTagCompound payload) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                LiveStock.accept(StockSnapshot.deserializeNBT(payload));
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
        // Ask the server for the network the moment the window opens, so the reply is in
        // flight while the player is still reading the first screen. NOT on a timer and not
        // per frame -- see LiveStock for why a planner is one question rather than a window.
        LiveStock.request();
        try {
            openWithPlan(book);
        } catch (Throwable missing) {
            tell(player, "the planner needs ModularUI 3.1.5, which is not installed");
        }
    }

    /**
     * Open the tree if a plan is ready, the plan book if not, and start a plan either way.
     *
     * THE JSON ROUND TRIP IS THE SEAM, NOT A SHORTCUT. `client.planner.PlanJson.readResult`
     * was written against `tests/fixtures/plan/*.json`, and `PlanJson.toJson` is the exact
     * text `PlanFixtureTest` compares against those fixtures -- so rendering through it means
     * the in-game panel draws from bytes provably identical to the ones the golden gate
     * proves match Python. An adapter mapping `PlanResult` to `PlanView` field by field would
     * be a fourth place the plan shape lives, and the way it fails is a dropped field
     * rendering as a blank row rather than as an error. Agreed with the panel's author before
     * either side was written.
     *
     * The plan is started AFTER the window opens rather than before, because opening is
     * instant and planning is not: the first frame shows the book, and the next use of the
     * item shows the tree. A player who right-clicks and waits several seconds for a window
     * has been given a slow tool, which is the same argument that put the graph load on its
     * own thread.
     */
    private static void openWithPlan(PlanBook book) {
        PlannerService planner = PlannerService.get();
        String json = planner.state() == PlannerService.State.DONE ? planner.resultJson() : null;
        if (json != null) {
            PlannerScreen.openPlan(PlanJson.readResult(json), book);
        } else {
            PlannerScreen.open(book);
        }
        planFirstTodo(book);
    }

    /**
     * Start planning the book's first entry, if there is one and nothing is already running.
     *
     * Deliberately silent about every reason it might not run. An empty book, a graph still
     * loading and a plan already in flight are all ordinary, and none of them is worth a line
     * of chat every time the item is used. A MISSING or FAILED graph is the exception,
     * because a player who has not installed `graph.json` has no other way to find out -- and
     * `GraphService.describe` names the paths it tried, which is the difference between
     * fixing it and filing a bug.
     *
     * THE FIRST TODO IS THE TARGET, which is a placeholder rule rather than a design. #19
     * Phase 3 gives the panel a target picker and #145 already sets one from JEI; what this
     * buys today is that the whole path -- graph, scenario, cost table, solver, JSON, panel --
     * runs against real pack data on a real client rather than only in a JUnit gate.
     */
    private static void planFirstTodo(PlanBook book) {
        GraphService graphs = GraphService.get();
        if (graphs.state() == GraphService.State.MISSING
                || graphs.state() == GraphService.State.FAILED) {
            return;
        }
        List<String> todo = book.todoKeys();
        String target = !todo.isEmpty() ? todo.get(0)
                : (book.favourites().isEmpty() ? null : book.favourites().get(0));
        if (target == null) {
            return;
        }
        long qty = !todo.isEmpty() ? book.todoQuantity(target) : 1L;
        PlannerService.get().plan(target, Math.max(1L, qty), Solver.DEFAULT_MAX_NODES);
    }

    private static void tell(EntityPlayer player, String message) {
        player.sendMessage(new TextComponentString("[mc-recipe-dump] " + message));
    }
}
