package io.github.jacoblasky.recipedump.shot;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEPartLocation;
import appeng.api.AEApi;
import appeng.api.config.Actionable;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;

/**
 * `ae2-probe`: is Phase 5's live AE2 read testable under the headless harness?
 *
 * THE QUESTION, AND WHY IT IS NOT "DOES AE2 LOAD". AE2 has been in the dev mod set since
 * 150c56b (#128) -- `packJars` resolves `ae2-uel-*.jar` and `stageDevMods` stages it, so every
 * screenshot this project has taken was rendered by a client with `appliedenergistics2`
 * loaded. What was never established is whether a GRID can exist here, which is what
 * `Ae2StockReader` actually walks: an `IGridHost` on a tile entity, `getGridNode`, `getGrid`,
 * then the grid's caches.
 *
 * THE FAILURE MODE THIS IS BUILT AROUND. A grid that does not form hands back null, and a
 * grid that forms but is empty hands back an empty list. **Those are indistinguishable from
 * a working read of an empty network**, and reporting either as "AE2 works" is the same
 * mistake as the cursor probe's first version, which logged six lines of AGREE while
 * comparing nothing to nothing. So the criterion is fixed here, in advance, and it is not
 * the absence of an error:
 *
 *   1. TWO nodes in one grid. A single AE2 block always has a node; two adjacent blocks
 *      sharing a grid requires the connection to have actually formed.
 *   2. The grid reports itself POWERED, which requires the creative cell to be recognised
 *      as an energy source rather than merely present.
 *   3. An item INJECTED through `IMEMonitor` comes back out of `getStorageList`, in the
 *      quantity that went in ({@link #INJECTED}). Cobblestone cannot appear in a storage list
 *      that is not backed by a working grid, and no amount of null-handling can fake it.
 *
 * Anything short of all three is reported as a partial result naming the step that stopped,
 * not as a failure of the harness and not as a success.
 *
 * AND THE VERDICT DECIDES THE EXIT CODE, in both directions. All three criteria holding is the
 * only path that calls `ShotScreens.reportPass`; every other way out of this class calls
 * `reportFail` with the step that stopped, so a run whose criteria did not hold cannot come
 * back green. That is not how this was first written -- see `ShotScreens.expectReport` -- and
 * it is the difference between a probe and a picture of one.
 *
 * ON THE SERVER THREAD, AND THE FIRST VERSION WAS NOT. It placed its blocks in `mc.world`
 * and asked the resulting tile entity for a grid node, which came back null -- and the
 * obvious reading of that is "AE2 grids do not work headlessly". The real reason is that an
 * AE2 grid only exists server side, so a client-side tile has no node to give. The vanilla
 * chest in `world-probe` worked client side and made the approach look sound.
 *
 * `StockRequestMessage.Handler` is the shape to copy, and it is explicit about why: the read
 * runs on `EntityPlayerMP` through `player.getServer().addScheduledTask`, because walking a
 * grid off the server tick is the 1.12.2 race that surfaces as a
 * ConcurrentModificationException blamed on somebody else's storage bus. A probe that ran on
 * the client thread would not be testing the path Phase 5 uses even if it happened to answer.
 *
 * IT PROVES THE ENVIRONMENT AND NOT THE PRODUCTION PATH, and the two share only their last two
 * calls: `getGridNode(AEPartLocation.INTERNAL)` and `getGrid()`. This class reaches those from
 * a tile entity it placed itself. `Ae2StockReader` reaches them by finding a wireless terminal
 * in the player's inventory, reading its encryption key, resolving that key through AE2's
 * locatable registry, and range-checking the player against an `IWirelessAccessPoint` -- NONE
 * of which happens here. So a green run here says the harness can host such a test; it says
 * nothing whatever about whether the real read works. See #191.
 *
 * ONLY REACHED THROUGH THE SCREEN REGISTRY, like `JeiRecipeShot`, so a client without AE2
 * never resolves this class. It names AE2 types directly, which is exactly what
 * `Ae2StockReader`'s header forbids for anything that loads unconditionally.
 */
final class Ae2ProbeShot {

    /** The two blocks. A creative cell for power, an ME chest for storage. */
    private static final String ENERGY = "appliedenergistics2:creative_energy_cell";
    private static final String CHEST = "appliedenergistics2:chest";
    private static final String CELL = "appliedenergistics2:storage_cell_1k";

    /**
     * How much cobblestone goes in, and therefore how much has to come back out.
     *
     * ONE CONSTANT BECAUSE CRITERION 3 IS AN EQUALITY AND BOTH SIDES OF IT ARE THIS NUMBER.
     * Written out three times -- the stack, the log line and the comparison -- it can be
     * changed in two of them, and the failure that produces is a probe comparing 64 against 32
     * and reporting a broken grid. A criterion that can disagree with itself is worse than no
     * criterion, because it fails in the direction that looks like a real finding.
     */
    private static final int INJECTED = 64;

    private Ae2ProbeShot() {
    }

    static void open(String arg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            throw new IllegalStateException("ae2-probe needs a world: run it with "
                    + "-D" + ShotHarness.PROP_WORLD + "=<name>");
        }
        final net.minecraft.server.MinecraftServer server = mc.getIntegratedServer();
        if (server == null) {
            throw new IllegalStateException("ae2-probe needs the integrated server");
        }
        // DECLARED, not left to the command line. Twenty server ticks is about a second and
        // the default settle is under one on this rasteriser, so without this the run
        // captures and exits before the verdict is logged -- and a log with no verdict line
        // reads as "did not run", which is indistinguishable from "found nothing".
        ShotScreens.requestSettleFrames(150);
        // The debt this screen owes. See `ShotScreens.expectReport` for the run that made it
        // necessary: a probe that logs nothing is indistinguishable from one never run.
        //
        // BOTH OF THESE MUST PRECEDE THE `register` BELOW, AND THAT ORDER IS LOAD-BEARING. The
        // Ticker runs on the SERVER thread and can fail on its very first tick -- `place` does
        // exactly that when a block is missing. Registering first leaves a window in which the
        // probe calls `reportFail`, clearing a debt that has not been declared yet, and then
        // this line re-arms it: the run would then report "the screen never reported" and throw
        // away the real reason, which is the failure mode this whole guard exists to prevent.
        ShotScreens.expectReport("ae2-probe verdict");

        // A SERVER TICK SUBSCRIBER, NOT ONE SCHEDULED TASK, because the grid does not exist
        // in the tick the blocks are placed. AE2 creates a tile's grid node lazily on its
        // first tick -- `getGridNode` in the placing tick returns null, and the honest-looking
        // reading of that null is "no grid formed headlessly". It is the hover-lag mistake
        // again: reading before the thing under test is ready, and blaming the thing.
        //
        // So: place, wait, then walk. The wait must also be inside the shot's settle window,
        // which is why this screen declares a larger one above -- the harness captures and
        // exits on its own clock, not the probe's.
        MinecraftForge.EVENT_BUS.register(new Ticker(server));
        HarnessFixtureScreen.open();
    }

    /** Places on its first tick, walks the grid {@link #SETTLE_TICKS} later, then unhooks. */
    private static final class Ticker {

        /**
         * Server ticks between placing the blocks and walking the grid.
         *
         * 20, a second, rather than the 1 that would be enough if AE2 only needed its node
         * created. A grid also has to CONNECT its nodes and its energy cache has to run once
         * before `isNetworkPowered` means anything, and those are separate ticks. Generous on
         * purpose: this runs once per probe, not per frame, and an under-wait here produces
         * exactly the false negative the class header is about.
         */
        private static final int SETTLE_TICKS = 20;

        private final net.minecraft.server.MinecraftServer server;
        private int ticks = -1;

        Ticker(net.minecraft.server.MinecraftServer server) {
            this.server = server;
        }

        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void onServerTick(net.minecraftforge.fml.common.gameevent.TickEvent
                                         .ServerTickEvent event) {
            if (event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) {
                return;
            }
            try {
                if (ticks < 0) {
                    if (!place(server)) {
                        // NO PROBE AFTER A FAILED PLACE. `place` has already reported the
                        // specific reason -- which block was missing, or that there was no
                        // player -- and probing anyway would reach the `chestAt == null`
                        // backstop and overwrite that reason with "nothing was placed".
                        MinecraftForge.EVENT_BUS.unregister(this);
                        return;
                    }
                    ticks = 0;
                    return;
                }
                if (++ticks < SETTLE_TICKS) {
                    if (ticks % 5 == 0) {
                        ShotHarness.log("ae2-probe: waiting, server tick " + ticks + "/"
                                + SETTLE_TICKS);
                    }
                    return;
                }
                MinecraftForge.EVENT_BUS.unregister(this);
                probe(server);
            } catch (Throwable t) {
                // Reported rather than swallowed: a probe that dies quietly leaves a log with
                // no verdict line, which reads as "did not run" and not as "failed".
                MinecraftForge.EVENT_BUS.unregister(this);
                stopped("the probe threw " + t);
                t.printStackTrace();
            }
        }
    }

    /**
     * Say why the probe stopped, once, to both places that need to hear it.
     *
     * ONE STRING, LOGGED AND REPORTED. Each stop path used to write its reason twice -- once
     * for the log and once for `reportFail` -- and the two copies had already drifted into
     * different wordings before this was extracted. That drift matters more here than it
     * usually would: the log line and the exit message are the only two things a person reads
     * to find out why a 180-second run failed, and two accounts that disagree send them looking
     * for a third cause that does not exist.
     */
    private static void stopped(String reason) {
        ShotHarness.log("ae2-probe: STOPPED, " + reason);
        ShotScreens.reportFail(reason);
    }

    /** Where the two blocks go. Set by {@link #place}, read by {@link #probe}. */
    private static BlockPos chestAt;

    private static boolean place(net.minecraft.server.MinecraftServer server) {
        net.minecraft.world.World world = server.getWorld(0);
        java.util.List<net.minecraft.entity.player.EntityPlayerMP> players =
                server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            stopped("the server has no player to place the blocks beside");
            return false;
        }
        net.minecraft.entity.player.EntityPlayerMP player = players.get(0);

        Block energy = Block.REGISTRY.getObject(new ResourceLocation(ENERGY));
        Block chest = Block.REGISTRY.getObject(new ResourceLocation(CHEST));
        if (energy == Blocks.AIR || chest == Blocks.AIR) {
            // Names verified against the jar's en_us.lang rather than guessed, so this firing
            // means the fork renamed something -- worth saying plainly instead of nulling out.
            String missing = energy == Blocks.AIR ? ENERGY : CHEST;
            stopped("no block registered as " + missing);
            return false;
        }

        // Adjacent, so AE2 connects them into one grid without a cable.
        BlockPos energyAt = new BlockPos(player.posX + 1, player.posY, player.posZ);
        chestAt = energyAt.east();
        world.setBlockState(energyAt, energy.getDefaultState());
        world.setBlockState(chestAt, chest.getDefaultState());
        ShotHarness.log("ae2-probe: placed " + world.getBlockState(energyAt).getBlock()
                .getRegistryName() + " and " + world.getBlockState(chestAt).getBlock()
                .getRegistryName() + " server-side at " + energyAt + " / " + chestAt);
        return true;
    }

    private static void probe(net.minecraft.server.MinecraftServer server) {
        net.minecraft.world.World world = server.getWorld(0);
        if (chestAt == null) {
            // The backstop. `place` reports its own failures and the ticker does not probe
            // after one, so reaching this means something cleared `chestAt` in between.
            stopped("nothing was placed");
            return;
        }
        TileEntity tile = world.getTileEntity(chestAt);
        if (!(tile instanceof IGridHost)) {
            String what = tile == null ? "null" : tile.getClass().getName();
            stopped("step 1: the ME chest's tile entity is " + what
                    + " and not an IGridHost");
            return;
        }
        IGridNode node = ((IGridHost) tile).getGridNode(AEPartLocation.INTERNAL);
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) {
            stopped("step 1: the tile exists, node=" + (node != null)
                    + ", and no grid formed");
            return;
        }

        // CRITERION 1: two nodes, which needs the two blocks to have actually connected.
        int nodes = countNodes(grid);
        IEnergyGrid power = grid.getCache(IEnergyGrid.class);
        // CRITERION 2: powered, which needs the creative cell recognised as a source.
        boolean powered = power != null && power.isNetworkPowered();
        ShotHarness.log("ae2-probe: grid formed, nodes=" + nodes + ", powered=" + powered
                + " (criterion: nodes >= 2 AND powered)");

        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) {
            stopped("step 3: the grid formed but has no IStorageGrid cache");
            return;
        }

        // A CELL IN THE CHEST, or the network has nowhere to put anything and an empty read
        // proves nothing. Finding the right slot is done by SEARCH rather than by assumption,
        // because the first version assumed slot 0 of the side-less handler and the cell came
        // straight back -- `insertItem` returns the REMAINDER, so "1x storage_cell_1k" in the
        // log was a rejection that reads like a receipt. An ME Chest exposes different
        // inventories per face; which one takes a cell is not something to guess twice.
        Item cell = Item.REGISTRY.getObject(new ResourceLocation(CELL));
        String cellNote = "no " + CELL + " in the registry";
        if (cell != null) {
            cellNote = "no face and slot accepted a storage cell";
            EnumFacing[] faces = {null, EnumFacing.UP, EnumFacing.DOWN, EnumFacing.NORTH};
            outer:
            for (EnumFacing face : faces) {
                net.minecraftforge.items.IItemHandler slots = tile.getCapability(
                        net.minecraftforge.items.CapabilityItemHandler
                                .ITEM_HANDLER_CAPABILITY, face);
                if (slots == null) {
                    ShotHarness.log("ae2-probe:   face " + face + ": no IItemHandler");
                    continue;
                }
                ShotHarness.log("ae2-probe:   face " + face + ": " + slots.getSlots()
                        + " slots");
                for (int slot = 0; slot < slots.getSlots(); slot++) {
                    // SIMULATE FIRST. A real insert into the wrong slot can consume the cell
                    // into a network inventory, and then the next face is tried with nothing
                    // left to insert -- a search that destroys its own subject.
                    ItemStack one = new ItemStack(cell);
                    if (slots.insertItem(slot, one, true).isEmpty()) {
                        ItemStack left = slots.insertItem(slot, new ItemStack(cell), false);
                        cellNote = "cell accepted at face " + face + " slot " + slot
                                + ", leftover " + (left.isEmpty() ? "none" : left.toString());
                        break outer;
                    }
                }
            }
        }
        ShotHarness.log("ae2-probe: " + cellNote);

        IItemStorageChannel channel =
                AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        IMEMonitor<IAEItemStack> monitor = storage.getInventory(channel);
        IAEItemStack cobble =
                channel.createStack(new ItemStack(Blocks.COBBLESTONE, INJECTED));
        IAEItemStack leftover = monitor.injectItems(cobble, Actionable.MODULATE,
                new appeng.me.helpers.BaseActionSource());

        // CRITERION 3: it comes back out. This is the assertion that cannot be faked.
        long stored = 0;
        for (IAEItemStack entry : monitor.getStorageList()) {
            if (entry.getItem() == Item.getItemFromBlock(Blocks.COBBLESTONE)) {
                stored = entry.getStackSize();
            }
        }
        ShotHarness.log("ae2-probe: injected " + INJECTED + " cobblestone, leftover="
                + (leftover == null ? "none" : String.valueOf(leftover.getStackSize()))
                + ", storage list reports " + stored
                + " (criterion: stored == " + INJECTED + ")");
        boolean nodesOk = nodes >= 2;
        boolean storedOk = stored == (long) INJECTED;
        String verdict = "nodes>=2 " + nodesOk + ", powered " + powered
                + ", stored==" + INJECTED + " " + storedOk;
        ShotHarness.log("ae2-probe: VERDICT " + verdict);

        // THE ONLY PASS IN THIS CLASS, and it requires all three criteria. Every other exit
        // from the probe calls `reportFail`, so the exit code carries the verdict rather than
        // merely recording that a verdict was spoken -- and a run whose criteria did not hold
        // cannot come back green. The predecessor of this line called a bare `reported()` on
        // the five failure paths and nothing here, which would have inverted both directions at
        // once; see `ShotScreens.expectReport`. DO NOT report a pass outside this branch.
        if (nodesOk && powered && storedOk) {
            ShotScreens.reportPass();
        } else {
            ShotScreens.reportFail(verdict);
        }
        // NO `HarnessFixtureScreen.open()` HERE. This method runs on the SERVER thread, and
        // that call goes through ModularUI's `ClientGUI`, which touches `Minecraft` -- opening
        // a client screen from the server tick. It is also redundant: `open` already put the
        // fixture screen up on the client thread, which is what the harness captures.
    }

    /** Nodes in the grid. `IGrid.getNodes` is an Iterable, so this counts rather than sizes. */
    private static int countNodes(IGrid grid) {
        int seen = 0;
        for (IGridNode ignored : grid.getNodes()) {
            seen++;
        }
        return seen;
    }
}
