package io.github.jacoblasky.recipedump.shot;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.util.AEPartLocation;
import io.github.jacoblasky.recipedump.client.LiveStock;
import io.github.jacoblasky.recipedump.client.PlannerStock;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.Ae2Stock;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * `ae2-placed`: does a machine on the ME network really read as OWNED, through the production
 * read, against a real AE2 grid? #320
 *
 * <h2>WHY THIS EXISTS WHEN `ae2-probe` ALREADY WALKS A GRID</h2>
 *
 * `Ae2ProbeShot` is explicit that it proves the ENVIRONMENT and not the production path -- the
 * two share only their last two calls, and its own header lists the four steps it never
 * exercises: finding a wireless terminal in the player's inventory, reading its encryption key,
 * resolving that key through AE2's locatable registry, and range-checking the player against an
 * `IWirelessAccessPoint`. `harness/README.md` says outright that "the probe that drives the real
 * path" is owed. This is that probe, for the machine half.
 *
 * IT CALLS `Ae2Stock.read(player)` AND NOTHING BELOW IT. That is the entry point
 * `StockRequestMessage.Handler` calls in game, so every gate is the real gate: no terminal, no
 * link, no access point in range, no power, no permission all refuse here exactly as they refuse
 * a player. A probe that reached into `Ae2StockReader.placedOf` would be testing my own
 * enumeration against my own expectation of it -- mocked tests confirm parser shape, not wire
 * shape, and the shape in question is AE2's.
 *
 * <h2>Five criteria, fixed in advance, and TWO OF THEM ARE NEGATIVE</h2>
 *
 * `Ae2ProbeShot`'s header makes the case: a grid that never forms and a grid that forms empty
 * are indistinguishable from a working read of an empty network, so "no error" is not evidence.
 * The same trap is sharper here, because the thing under test is a MAP THAT CAN LEGITIMATELY BE
 * EMPTY. So this probe cannot pass by finding nothing, and it cannot pass by finding everything:
 *
 * <ol>
 * <li>The read SUCCEEDS -- `snapshot.isAvailable()`. Every refusal names itself in the log, so a
 *     failure here says which gate stopped rather than "AE2 broke".</li>
 * <li>The machine ON the grid is in `placed()`, under the item key the graph would name it by.
 *     Nothing short of a walked grid produces that key.</li>
 * <li>The machine OFF the grid is NOT in `placed()`. This is the criterion that makes the probe
 *     able to fail in the other direction: an enumeration that returned every tile entity in the
 *     chunk, or a `placed` map somebody filled from the world, would satisfy criterion 2 and
 *     break here. A vanilla furnace is placed three blocks away and is not an `IGridHost`.</li>
 * <li>The verdicts follow, through `ScenarioInputs.resolve` -- the ONE resolver the golden
 *     fixtures use. The on-grid category reads `have` with evidence starting `placed:`, and the
 *     off-grid category reads `buildable`. Asserting the state alone would not do: `have` is
 *     also what the machine ITEM sitting in stock produces, which is a route that already
 *     worked, so the evidence string is what proves the verdict came from the grid walk.</li>
 * <li>The CAVEAT IS GONE: `ScenarioSource.PLACED` reports live and `summary()` no longer names
 *     `placed`. Only a real read can put the source in that state, so no unit test can assert
 *     it -- and it is the half a player actually sees.</li>
 * </ol>
 *
 * CRITERION 4 GOES THROUGH THE PRODUCTION INSTALLER, which an earlier cut of this did not. It
 * called `ScenarioSource.PLACED.readBy(...)` with a reader built here, and that proves a document
 * I assembled resolves the way I expected rather than that the shipped wiring carries the
 * snapshot. It now runs `LiveStock.accept` then `PlannerStock.install` -- the pair `ClientProxy`
 * runs -- so what is under test is the JOIN, which is the thing #191 and #320 both broke.
 *
 * <h2>The synthetic graph is the point of control, not a shortcut</h2>
 *
 * Criterion 4 needs a graph in which the two machines are catalysts of two categories, and the
 * pack graph loaded here is whatever the dev mod set produced. So the graph is BUILT HERE, with
 * both machines craftable -- which is what makes the off-grid one read `buildable` rather than
 * `unavailable`. An unbuildable machine would go from `unavailable` to `have` and the probe
 * would be passing on a weaker distinction than the one #320 is about, which is precisely
 * `buildable` versus `have`: a machine you are standing next to priced as one you must build.
 *
 * <h2>`holdCapture` AND NOT `requestSettleFrames`. #319</h2>
 *
 * `Ae2ProbeShot` declares a settle window of 150 frames and hopes its twenty server ticks fit
 * inside it, which is a stopping rule that is sound only by arithmetic nobody re-checks -- #319.
 * Every other shot that waits for something holds until the thing it is waiting for is done.
 * This one holds until the ticker has run to a verdict, so the capture cannot outrun the probe
 * and a slower host cannot turn a pass into "the screen never reported".
 *
 * A HOLD IS STILL NOT A VERDICT, which `ShotScreens.holdCapture` says in as many words: `busy()`
 * going false means the probe stopped, not that it succeeded. So this also
 * {@link ShotScreens#expectReport}s and answers exactly once.
 *
 * <h2>Loading</h2>
 *
 * ONLY REACHED THROUGH THE SCREEN REGISTRY, like `Ae2ProbeShot`, so a client without AE2 never
 * resolves this class. It names AE2 types directly, which `Ae2StockReader`'s header forbids for
 * anything that loads unconditionally.
 */
final class Ae2PlacedProbeShot {

    /** Power for the grid. Creative, so no generation has to be arranged. */
    private static final String ENERGY = "appliedenergistics2:creative_energy_cell";
    /**
     * The security station, and it is NOT optional decoration.
     *
     * A wireless terminal's encryption key is the SERIAL OF A SECURITY STATION, resolved through
     * AE2's locatable registry -- so without one there is no key to write on the terminal and
     * `Ae2StockReader` refuses with `NOT_LINKED` before it looks at anything else. This is one of
     * the four steps `ae2-probe` never reaches.
     */
    private static final String SECURITY = "appliedenergistics2:security_station";
    /** What makes the player "in range". Its own `getRange()` is the test the reader makes. */
    private static final String ACCESS = "appliedenergistics2:wireless_access_point";
    /**
     * The machine that should read as owned: a real AE2 machine with a real item form.
     *
     * A MOLECULAR ASSEMBLER RATHER THAN THE ME CHEST `ae2-probe` USES, because "is this a
     * machine" is the question and a chest is storage. It is also a machine the pack's graph
     * really does carry as a crafting catalyst, so the key this produces is the shape a verdict
     * is looked up by rather than a curiosity.
     */
    private static final String ON_GRID = "appliedenergistics2:molecular_assembler";
    /**
     * The machine that must NOT read as owned, and criterion 3 is the whole reason it is here.
     *
     * A VANILLA FURNACE: placed, in the world, in a loaded chunk, three blocks from the grid, and
     * not an `IGridHost`. Under #320's scope it is exactly the case that still reads `buildable`
     * -- and it is the control that stops this probe passing on an enumeration that simply
     * returned everything nearby.
     */
    private static final String OFF_GRID = "minecraft:furnace";
    /** The terminal itself. An item, not a block. */
    private static final String TERMINAL = "appliedenergistics2:wireless_terminal";

    /** Category uids for the synthetic graph. Only this class ever reads them. */
    private static final String ON_GRID_CATEGORY = "probe.on_grid";
    private static final String OFF_GRID_CATEGORY = "probe.off_grid";

    private Ae2PlacedProbeShot() {
    }

    /** Where things went. Written by {@link #place}, read by {@link #probe}. */
    private static BlockPos securityAt;
    private static BlockPos assemblerAt;
    private static BlockPos furnaceAt;

    /** False once the probe has reached a verdict. Read by the harness through `Hold`. */
    private static volatile boolean running;

    /**
     * Server ticks observed since the probe armed.
     *
     * THE EVIDENCE THAT THE WORLD IS RUNNING AT ALL, and the field that turns a hang into a
     * finding. A paused `IntegratedServer` never calls `super.tick()`, so this stays at 0 and
     * the driver can say so instead of waiting for a grid that cannot form.
     */
    private static volatile int serverTicks;

    static void open(String arg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            throw new IllegalStateException("ae2-placed needs a world: run it with "
                    + "-D" + ShotHarness.PROP_WORLD + "=<name>");
        }
        final MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            throw new IllegalStateException("ae2-placed needs the integrated server");
        }
        running = true;
        serverTicks = 0;
        securityAt = null;
        assemblerAt = null;
        furnaceAt = null;

        // NO GUI SCREEN, AND KEEP IT THAT WAY. MEASURED 2026-08-22, twice.
        //
        // A single-player client with a screen open sets `Minecraft.isGamePaused`, and a paused
        // `IntegratedServer.tick()` skips `super.tick()` entirely -- it drains only its
        // `futureTaskQueue`. So `TickEvent.ServerTickEvent` stops firing AND, far worse, no tile
        // entity ticks: AE2 can never create its grid nodes, which is the whole subject here.
        // Run 1 opened `HarnessFixtureScreen`, logged "Saving and pausing game..." in the same
        // second, and spun 676 s to `exit 1` with its ticker frozen at tick 0.
        //
        // `expectNoScreen` alone was NOT enough -- run 2 logged "opened no screen; capturing the
        // HUD" and STILL paused, so something else had a screen up as the world finished loading.
        // Hence the client-tick driver below, which closes whatever it finds and says what it
        // closed rather than assuming.
        //
        // DO NOT "fix" a future hang here by raising the timeout. The pause is the cause; a
        // longer wait only postpones the same exit.
        ShotScreens.expectNoScreen();
        // AND TURN OFF THE AUTO-PAUSE, WHICH IS THE ACTUAL CAUSE. MEASURED, attempt 3: the screen
        // this probe kept finding open was `net.minecraft.client.gui.GuiIngameMenu`, and closing
        // it did not help -- the server still ticked once in 200 client ticks.
        //
        // Minecraft opens that menu ITSELF, every tick, whenever `pauseOnLostFocus` is set and
        // `Display.isActive()` is false. Under Xvfb with no window manager the display is never
        // "active", so the client pauses on the frame the world finishes loading and re-pauses on
        // every frame after -- a fight a probe closing screens cannot win, because it is closing
        // the symptom. One line settles it at the source.
        //
        // THIS IS A CLIENT SETTING AND NOT A WORLD CHANGE, so it alters nothing about what the
        // probe measures: the grid, the read and the verdicts are all server-side. It is also why
        // `ae2-probe`'s twenty-server-tick wait is unsound in a way #319 does not yet mention --
        // its ticks cannot arrive either.
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = false;
        // BEFORE THE `register`, for `Ae2ProbeShot`'s stated reason: the driver can fail on its
        // very first tick, so registering first leaves a window in which the probe clears a debt
        // that has not been declared yet.
        ShotScreens.expectReport("ae2-placed verdict");
        // #319: HOLD UNTIL THE PROBE IS DONE, rather than declaring a frame budget and trusting
        // it to be longer than the work.
        ShotScreens.holdCapture(new ShotScreens.Hold() {
            @Override
            public boolean busy() {
                return running;
            }
        });
        MinecraftForge.EVENT_BUS.register(new Driver(server));
    }

    /**
     * Drives the probe from the CLIENT tick, and counts SERVER ticks as evidence.
     *
     * <h2>WHY THE CLIENT TICK IS THE CLOCK</h2>
     *
     * The client tick always runs -- the harness's own "holding the capture" line proves the game
     * loop is alive -- while the server tick stops dead whenever the client is paused. A probe
     * clocked off the server tick therefore cannot distinguish "AE2 is still settling" from "the
     * server has not ticked at all", and it hangs instead of saying so. Both of this probe's
     * earlier runs hung exactly that way.
     *
     * So: the client tick counts down, {@link #serverTicks} counts what the server actually did,
     * and if the server never ticked the probe REPORTS THAT rather than waiting for a grid that
     * cannot form. That is the difference between a probe and a hang.
     *
     * <h2>The work still happens on the server thread</h2>
     *
     * `addScheduledTask` for both the place and the read, which is what
     * `StockRequestMessage.Handler` does and for the reason it gives: walking a grid off the
     * server tick is the 1.12.2 race that surfaces as somebody else's ConcurrentModification.
     * Scheduled tasks are drained even while paused, so the place lands either way -- what a
     * pause costs is the tile-entity ticking in between, which is why it has to be detected.
     */
    private static final class Driver {

        /**
         * Client ticks to allow before reading. Twenty per second, so 200 is ten seconds.
         *
         * WALL-CLOCK GENEROUS, because what it is really waiting for is AE2 connecting nodes and
         * an access point coming up ACTIVE, and those are server ticks whose rate this cannot
         * control. Ten seconds is far more than the ~2 s of server ticks needed, and the capture
         * waits on `busy()` regardless, so overshooting costs a few seconds once per run.
         */
        private static final int CLIENT_TICKS = 200;

        /** Server ticks the probe must have seen for a grid to be possible at all. */
        private static final int MIN_SERVER_TICKS = 20;

        private final MinecraftServer server;
        private int clientTicks;
        private boolean placed;
        /**
         * How many times a screen had to be closed.
         *
         * COUNTED RATHER THAN FLAGGED, because the two failures look identical in a log that
         * only says "closed one". Attempt 3 closed `GuiIngameMenu` once and still saw the server
         * stop, and a flag could not distinguish "it opened once" from "it reopens every tick" --
         * which is what `pauseOnLostFocus` does under Xvfb, and which is the actual cause.
         */
        private int screensClosed;

        Driver(MinecraftServer server) {
            this.server = server;
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                serverTicks++;
            }
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            try {
                // CLOSE WHATEVER IS PAUSING US, AND SAY WHAT IT WAS. Reported once: a screen
                // reappearing every tick is a different problem from one that opened once, and
                // a log line per tick would bury the verdict.
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.currentScreen != null) {
                    screensClosed++;
                    if (screensClosed == 1) {
                        ShotHarness.log("ae2-placed: closing " + mc.currentScreen.getClass()
                                .getName() + ", which pauses the integrated server");
                    }
                    mc.displayGuiScreen(null);
                }
                if (!placed) {
                    placed = true;
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            if (!place(server)) {
                                running = false;
                            }
                        }
                    });
                    return;
                }
                if (!running) {
                    MinecraftForge.EVENT_BUS.unregister(this);
                    return;
                }
                if (++clientTicks < CLIENT_TICKS) {
                    if (clientTicks % 40 == 0) {
                        ShotHarness.log("ae2-placed: waiting, client tick " + clientTicks + "/"
                                + CLIENT_TICKS + ", server ticks seen " + serverTicks);
                    }
                    return;
                }
                MinecraftForge.EVENT_BUS.unregister(this);
                if (serverTicks < MIN_SERVER_TICKS) {
                    // THE HONEST ANSWER WHEN THE WORLD NEVER RAN. Without this the probe waits
                    // for a grid that cannot exist and the run dies on the harness timeout,
                    // which reads as "the probe hung" rather than "the server was paused".
                    stopped("the integrated server ticked only " + serverTicks + " times in "
                            + CLIENT_TICKS + " client ticks, so no tile entity ever ran and AE2"
                            + " could not build a grid -- the client is paused; screens closed: "
                            + screensClosed);
                    running = false;
                    return;
                }
                server.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            probe(server);
                        } catch (Throwable t) {
                            stopped("the probe threw " + t);
                            t.printStackTrace();
                        } finally {
                            running = false;
                        }
                    }
                });
            } catch (Throwable t) {
                MinecraftForge.EVENT_BUS.unregister(this);
                stopped("the driver threw " + t);
                t.printStackTrace();
                running = false;
            }
        }
    }

    /** Say why the probe stopped, once, to the log and to the exit code. */
    private static void stopped(String reason) {
        ShotHarness.log("ae2-placed: STOPPED, " + reason);
        ShotScreens.reportFail(reason);
    }

    private static Block block(String id) {
        return Block.REGISTRY.getObject(new ResourceLocation(id));
    }

    /**
     * Four blocks: three AE2 in one line so they share a grid, and a furnace that does not.
     *
     * THE FURNACE IS DELIBERATELY THREE BLOCKS CLEAR of the AE2 line. Adjacency is what joins AE2
     * blocks into a grid, and a furnace cannot join one at any distance -- but a reader coming to
     * this later should not have to know that to see that the furnace is meant to be off-grid.
     */
    private static boolean place(MinecraftServer server) {
        World world = server.getWorld(0);
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            stopped("the server has no player to place the blocks beside");
            return false;
        }
        EntityPlayerMP player = players.get(0);

        String[] ids = {ENERGY, SECURITY, ACCESS, ON_GRID, OFF_GRID};
        for (String id : ids) {
            if (block(id) == Blocks.AIR) {
                // Ids verified against the jar's en_us.lang rather than guessed, so this firing
                // means the fork renamed something -- worth saying plainly.
                stopped("no block registered as " + id);
                return false;
            }
        }

        BlockPos energyAt = new BlockPos(player.posX + 1, player.posY, player.posZ);
        securityAt = energyAt.east();
        assemblerAt = securityAt.east();
        BlockPos accessAt = energyAt.north();
        furnaceAt = assemblerAt.east().east().east();

        world.setBlockState(energyAt, block(ENERGY).getDefaultState());
        world.setBlockState(securityAt, block(SECURITY).getDefaultState());
        world.setBlockState(assemblerAt, block(ON_GRID).getDefaultState());
        world.setBlockState(accessAt, block(ACCESS).getDefaultState());
        world.setBlockState(furnaceAt, block(OFF_GRID).getDefaultState());

        ShotHarness.log("ae2-placed: placed energy=" + energyAt + " security=" + securityAt
                + " assembler=" + assemblerAt + " access=" + accessAt
                + " (off-grid furnace=" + furnaceAt + ")");
        return true;
    }

    /**
     * Make the probe player the network's owner.
     *
     * WITHOUT THIS THE READ REFUSES WITH `NO_PERMISSION` AND THE REFUSAL IS CORRECT. A security
     * station placed by `setBlockState` was placed by nobody, so the grid has an owner the player
     * is not, and `ISecurityGrid.hasPermission(player, EXTRACT)` says no -- which is AE2 doing
     * its job. A player who walked up and placed the station would BE the owner, so setting the
     * node's player id is how a hand on a keyboard is reproduced rather than how a gate is
     * dodged. `IGridNode.setPlayerID` and `IPlayerRegistry.getID` are both published API.
     *
     * DO NOT "simplify" this by removing the security station instead. The station is what
     * carries the serial the terminal is linked to; with no station there is no link, and the
     * read refuses one gate earlier for a different reason.
     */
    private static void takeOwnership(IGridNode node, EntityPlayerMP player) {
        int id = AEApi.instance().registries().players().getID(player);
        node.setPlayerID(id);
        ShotHarness.log("ae2-placed: security station owner set to AE player id " + id);
    }

    /**
     * A wireless terminal in the player's inventory, linked to this network and charged.
     *
     * THREE THINGS, AND EVERY ONE OF THEM IS A GATE `Ae2StockReader` CHECKS. The item has to be
     * recognised by AE2's wireless registry, its encryption key has to name a security station
     * the locatable registry can resolve, and it has to hold at least `POWER_PER_READ`. Getting
     * any of them wrong produces a refusal rather than an error, which is why each is logged.
     *
     * CHARGED TO FULL rather than to the one AE the reader wants: `POWER_PER_READ` is this mod's
     * own judgement and is documented as unmeasured, so a probe pinned to it would fail the day
     * somebody raises it -- and that day the probe should keep passing, because a player's
     * terminal is not charged to the threshold either.
     */
    private static String giveLinkedTerminal(EntityPlayerMP player, long serial) {
        Item item = Item.REGISTRY.getObject(new ResourceLocation(TERMINAL));
        if (item == null) {
            return "no item registered as " + TERMINAL;
        }
        ItemStack terminal = new ItemStack(item);
        IWirelessTermHandler handler =
                AEApi.instance().registries().wireless().getWirelessTerminalHandler(terminal);
        if (handler == null) {
            return TERMINAL + " is not registered as a wireless terminal";
        }
        handler.setEncryptionKey(terminal, String.valueOf(serial), "");
        if (item instanceof IAEItemPowerStorage) {
            IAEItemPowerStorage battery = (IAEItemPowerStorage) item;
            battery.injectAEPower(terminal, battery.getAEMaxPower(terminal),
                                 Actionable.MODULATE);
            ShotHarness.log("ae2-placed: terminal charged to "
                    + battery.getAECurrentPower(terminal) + " AE");
        } else {
            // Said rather than assumed: a terminal that stores no power would refuse with
            // NO_POWER and the reason would look like a flat battery instead of a wrong type.
            ShotHarness.log("ae2-placed: WARNING " + TERMINAL
                    + " is not an IAEItemPowerStorage, so it cannot be charged");
        }
        if (!player.inventory.addItemStackToInventory(terminal)) {
            return "the player's inventory would not take the terminal";
        }
        ShotHarness.log("ae2-placed: linked terminal to serial " + serial
                + " and put it in the player's inventory");
        return null;
    }

    /**
     * Two categories, each catalysed by one of the two machines, both machines craftable.
     *
     * BOTH CRAFTABLE IS THE LOAD-BEARING PART. `Machines.candidateVerdict` answers `buildable`
     * for a candidate with producers and `unavailable` for one without, so without the crafting
     * recipes the off-grid category would read `unavailable` and criterion 4 would be proving
     * that an unowned machine is unowned. #320 is about `buildable` versus `have`.
     */
    private static RecipeGraph probeGraph() {
        GraphBuilder b = new GraphBuilder();
        machineCategory(b, ON_GRID_CATEGORY, "On Grid", ON_GRID);
        machineCategory(b, OFF_GRID_CATEGORY, "Off Grid", OFF_GRID);
        return b.build();
    }

    private static void machineCategory(GraphBuilder b, String uid, String title, String key) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("probe:leaf"));
        b.endSlot();
        b.output(b.key("probe:out_" + uid), 1);
        b.endRecipe(uid + ".r", uid, title, "probe", false, false);
        // The machine itself, craftable by hand, so its unowned verdict is `buildable`.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("probe:leaf"));
        b.endSlot();
        b.output(b.key(key), 1);
        b.endRecipe(uid + ".machine", "minecraft.crafting", "Crafting Table", "probe",
                    false, false);
        b.beginCatalyst(uid);
        b.catalystKey(b.key(key));
        b.endCatalyst();
    }

    private static void probe(MinecraftServer server) {
        World world = server.getWorld(0);
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
        if (players.isEmpty() || securityAt == null) {
            stopped("nothing was placed");
            return;
        }
        EntityPlayerMP player = players.get(0);

        TileEntity station = world.getTileEntity(securityAt);
        if (!(station instanceof IGridHost) || !(station instanceof ILocatable)) {
            String what = station == null ? "null" : station.getClass().getName();
            stopped("step 1: the security station's tile is " + what
                    + " and not both an IGridHost and an ILocatable");
            return;
        }
        IGridNode node = ((IGridHost) station).getGridNode(AEPartLocation.INTERNAL);
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) {
            stopped("step 1: the station's tile exists, node=" + (node != null)
                    + ", and no grid formed");
            return;
        }
        takeOwnership(node, player);
        IEnergyGrid power = grid.getCache(IEnergyGrid.class);
        ShotHarness.log("ae2-placed: grid formed, nodes=" + countNodes(grid) + ", powered="
                + (power != null && power.isNetworkPowered()));

        long serial = ((ILocatable) station).getLocatableSerial();
        String refused = giveLinkedTerminal(player, serial);
        if (refused != null) {
            stopped("step 2: " + refused);
            return;
        }

        // THE PRODUCTION ENTRY POINT, and the only call in this class that is not setup.
        // `StockRequestMessage.Handler` calls exactly this, on exactly this thread.
        StockSnapshot snapshot = Ae2Stock.read(player);

        // CRITERION 1.
        ShotHarness.log("ae2-placed: Ae2Stock.read -> available=" + snapshot.isAvailable()
                + ", reason=" + snapshot.reason() + " (\"" + snapshot.reason().message() + "\")"
                + ", items=" + snapshot.distinctKeys()
                + ", placed=" + snapshot.distinctPlacedKeys());
        if (!snapshot.isAvailable()) {
            stopped("step 3: the production read refused with " + snapshot.reason()
                    + " -- \"" + snapshot.reason().message() + "\"");
            return;
        }
        ShotHarness.log("ae2-placed: placed keys " + new ArrayList<String>(
                snapshot.placed().keySet()));

        // CRITERIA 2 AND 3.
        boolean sawOnGrid = snapshot.placedCount(ON_GRID) > 0;
        boolean sawOffGrid = snapshot.placedCount(OFF_GRID) > 0;
        ShotHarness.log("ae2-placed: " + ON_GRID + " on the grid -> " + sawOnGrid
                + " (criterion: true), " + OFF_GRID + " off the grid -> " + sawOffGrid
                + " (criterion: false)");

        // CRITERION 4, and IT GOES THROUGH THE PRODUCTION INSTALLER RATHER THAN AN AD-HOC
        // READER. An earlier cut of this called `ScenarioSource.PLACED.readBy(...)` here with a
        // reader built on the spot, which proves that a document I assembled resolves the way I
        // expected -- not that the shipped wiring carries the snapshot. `LiveStock.accept` then
        // `PlannerStock.install` is the exact pair `ClientProxy` runs, so what is under test is
        // the join, which is the thing #191 and #320 both broke.
        //
        // SAFE ON THIS THREAD: neither call touches `Minecraft`. `PlannerStock.canAsk` is the
        // only method in that class that does, and nothing here reaches it.
        LiveStock.accept(snapshot);
        PlannerStock.install();
        RecipeGraph graph = probeGraph();
        ScenarioInputs.Resolved resolved = ScenarioInputs.resolve(graph, PlannerService
                .liveScenario());
        MachineInfo owned = resolved.machineStates().info(graph.categoryId(ON_GRID_CATEGORY));
        MachineInfo unowned = resolved.machineStates().info(graph.categoryId(OFF_GRID_CATEGORY));
        // CRITERION 4b: the caveat must be GONE, because a live `placed` is what makes the
        // planner stop saying "planned without: placed". Asserted here rather than in a unit
        // test because only a real read can put the source in this state for real.
        boolean caveatGone = ScenarioSource.PLACED.live()
                && !ScenarioSource.summary().contains(ScenarioSource.PLACED.field());
        ShotHarness.log("ae2-placed: PLACED.live()=" + ScenarioSource.PLACED.live()
                + ", summary=\"" + ScenarioSource.summary() + "\""
                + " (criterion: live and the summary does not name `placed`)");

        boolean ownedOk = owned.state() == MachineInfo.HAVE
                && owned.why() != null && owned.why().startsWith("placed:");
        boolean unownedOk = unowned.state() == MachineInfo.BUILDABLE;
        ShotHarness.log("ae2-placed: on-grid verdict state=" + owned.state() + " why=\""
                + owned.why() + "\" (criterion: HAVE=" + MachineInfo.HAVE + " and why starts"
                + " \"placed:\"), off-grid verdict state=" + unowned.state() + " why=\""
                + unowned.why() + "\" (criterion: BUILDABLE=" + MachineInfo.BUILDABLE + ")");

        String verdict = "read available true, on-grid placed " + sawOnGrid
                + ", off-grid placed " + sawOffGrid + " (want false), on-grid HAVE+placed "
                + ownedOk + ", off-grid BUILDABLE " + unownedOk + ", caveat gone " + caveatGone;
        ShotHarness.log("ae2-placed: VERDICT " + verdict);

        // THE ONLY PASS IN THIS CLASS, and it needs every criterion. Every other exit calls
        // `reportFail`, so a run whose criteria did not hold cannot come back green.
        // DO NOT report a pass outside this branch.
        if (sawOnGrid && !sawOffGrid && ownedOk && unownedOk && caveatGone) {
            ShotScreens.reportPass();
        } else {
            ShotScreens.reportFail(verdict);
        }
        // NO SCREEN IS OPENED ANYWHERE IN THIS CLASS, here least of all: this runs on the SERVER
        // thread, and opening a client screen from a server tick is wrong twice over. The reason
        // there is none at all is in `open` -- a paused single-player client stops ticking its
        // integrated server, which freezes this very ticker.
    }

    /** Nodes in the grid. `getNodes()` returns AE2's `IReadOnlyCollection`, which has size(). */
    private static int countNodes(IGrid grid) {
        return grid.getNodes().size();
    }
}
