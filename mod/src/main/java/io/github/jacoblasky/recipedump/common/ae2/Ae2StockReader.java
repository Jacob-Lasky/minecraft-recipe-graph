package io.github.jacoblasky.recipedump.common.ae2;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.common.PlacedTileEntities;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * THE ONLY CLASS IN THIS MOD THAT NAMES AN AE2 TYPE.
 *
 * Loaded exclusively through {@link Ae2Stock}, after its presence check. A reference to this
 * class from anywhere that loads unconditionally would make the mod fail to load on a pack
 * without AE2 -- the same eager-resolution trap the ProjectE and Modular Machinery bridges
 * document at length. DO NOT import it anywhere else.
 *
 * {@link Ae2Stock} carries the argument for WHY it is the wireless terminal's network and not
 * some other; this class is only the walk.
 */
final class Ae2StockReader {

    /**
     * What one read costs the terminal, in AE energy.
     *
     * AE2's own wireless terminal charges per tick that its GUI is open. A plan is a single
     * question rather than an open window, so it is charged once -- and it IS charged,
     * because a free read would make the planner a strictly better wireless terminal than the
     * wireless terminal.
     *
     * THE MAGNITUDE IS A JUDGEMENT AND NOBODY HAS MEASURED IT, the same disclaimer the cost
     * model's constants carry. What is defensible is that it is non-zero and that it also
     * GATES the read, so a terminal too flat to open is too flat to plan with. Whether one
     * plan should cost the same as one tick of an open terminal is not something the graph or
     * the API can answer.
     */
    private static final double POWER_PER_READ = 1.0;

    private Ae2StockReader() {
    }

    static StockSnapshot read(EntityPlayer player) {
        ItemStack terminal = findWirelessTerminal(player);
        if (terminal == null) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL);
        }
        IWirelessTermHandler handler =
                AEApi.instance().registries().wireless().getWirelessTerminalHandler(terminal);
        if (handler == null) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL);
        }

        String encoded = handler.getEncryptionKey(terminal);
        if (encoded == null || encoded.isEmpty()) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NOT_LINKED);
        }
        IGrid grid = gridFor(encoded);
        if (grid == null) {
            // The network the terminal names is gone -- the security terminal was broken, or
            // the grid was dismantled. Distinct from "not linked", because the fix is
            // different: one is re-link, the other is go and look at your base.
            return StockSnapshot.unavailable(StockSnapshot.Reason.NETWORK_GONE);
        }

        if (!inRangeOfAccessPoint(grid, player)) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE);
        }
        // Power AFTER range, matching the order AE2's own terminal reports them in: being out
        // of range is the more common and more actionable answer, and charging for a read
        // that was going to fail anyway would be a quiet theft.
        if (!handler.hasPower(player, POWER_PER_READ, terminal)) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_POWER);
        }

        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (security != null && !security.hasPermission(player, SecurityPermissions.EXTRACT)) {
            // EXTRACT rather than BUILD or CRAFT: the planner's question is "what could I take
            // out of here", and a player who may not take from a network has no business
            // being told a plan priced against its contents.
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_PERMISSION);
        }

        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        if (storage == null) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_STORAGE);
        }
        IMEMonitor<IAEItemStack> items =
                storage.getInventory(AEApi.instance().storage().getStorageChannel(
                        appeng.api.storage.channels.IItemStorageChannel.class));
        if (items == null) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_STORAGE);
        }
        handler.usePower(player, POWER_PER_READ, terminal);
        // ONE CHARGE FOR ALL THREE HALVES, and the arithmetic joke is deliberate: items, grid
        // machines and loaded tile entities are one question asked once -- see POWER_PER_READ.
        // Charging per source would make a plan cost more the more of the world it looked at,
        // which is a tax on honesty.
        Map<String, Integer> placed = placedOf(grid);
        // #320 PHASE 2, AND IT MUST STAY BELOW EVERY GATE ABOVE. The loaded-tile-entity scan needs
        // no AE2 at all -- that is why it lives in `common` and names no AE2 type -- so the ONLY
        // thing stopping it granting machines to a player with no ME network is its position in
        // this method, after the terminal, range, power and permission checks have all passed.
        // #320's balance decision is that nothing is owned without an ME connection. DO NOT hoist
        // this call above the gates or move it to a handler that does not pass them.
        PlacedTileEntities.scan(player.getServer(), placed);
        return StockSnapshot.of(countsOf(items), placed);
    }

    /**
     * The first wireless terminal in the player's inventory, or null.
     *
     * FIRST rather than best, and the main hand is not preferred. A player carrying two
     * terminals linked to two networks is choosing between them by inventory order, which is
     * arbitrary -- but every alternative is worse: preferring the held one makes the answer
     * change when you switch hotbar slots mid-plan, and asking the player to choose is a
     * dialog for a case that does not happen.
     */
    private static ItemStack findWirelessTerminal(EntityPlayer player) {
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack != null && !stack.isEmpty()
                    && AEApi.instance().registries().wireless().isWirelessTerminal(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** The grid a terminal's encryption key names, or null. */
    private static IGrid gridFor(String encoded) {
        long serial;
        try {
            serial = Long.parseLong(encoded);
        } catch (NumberFormatException notASerial) {
            return null;
        }
        ILocatable found = AEApi.instance().registries().locatable().getLocatableBy(serial);
        if (!(found instanceof IGridHost)) {
            return null;
        }
        IGridNode node = ((IGridHost) found).getGridNode(AEPartLocation.INTERNAL);
        return node == null ? null : node.getGrid();
    }

    /**
     * Whether any ACTIVE access point on this grid has the player inside its own range.
     *
     * THE RANGE IS AE2'S, NOT THIS MOD'S. `IWirelessAccessPoint.getRange()` is what AE2's own
     * wireless terminal tests against, so a player who can open their terminal here can read
     * their network here and the two can never disagree about where the boundary is.
     *
     * Squared distance, so no square root and no risk of a rounding difference at the exact
     * boundary. Dimension is checked first: a range is a number of blocks and blocks in
     * another dimension are not near anything.
     *
     * WALKS THE NODES AND TESTS `instanceof` RATHER THAN CALLING `getMachines`. Two reasons,
     * and the first is that it does not compile otherwise: `getMachines` is typed
     * `Class&lt;? extends IGridHost&gt;` and `IWirelessAccessPoint` extends `IActionHost`,
     * which is a different interface. The second is the better one -- `getMachines` matches a
     * registered concrete class, so it would find AE2's own access point and miss any other
     * mod's implementation of the same interface, while this finds both.
     */
    private static boolean inRangeOfAccessPoint(IGrid grid, EntityPlayer player) {
        int dimension = player.world.provider.getDimension();
        for (IGridNode node : grid.getNodes()) {
            Object machine = node.getMachine();
            if (!(machine instanceof IWirelessAccessPoint)) {
                continue;
            }
            IWirelessAccessPoint point = (IWirelessAccessPoint) machine;
            if (!point.isActive()) {
                continue;
            }
            DimensionalCoord at = point.getLocation();
            if (at == null || at.getWorld() == null
                    || at.getWorld().provider.getDimension() != dimension) {
                continue;
            }
            double range = point.getRange();
            double dx = at.x - player.posX;
            double dy = at.y - player.posY;
            double dz = at.z - player.posZ;
            if (dx * dx + dy * dy + dz * dz <= range * range) {
                return true;
            }
        }
        return false;
    }

    /**
     * The network's contents, keyed exactly as the dump keys them.
     *
     * {@link DumpCommand#stackKey} on the live stack, which is the whole reason live reading
     * beats the world-save reader: no digest has to be RECONSTRUCTED from region bytes and
     * no schema change can strand a key, because this is the same function that wrote the
     * graph looking at the same object.
     *
     * `getStackSize` rather than the ItemStack's count -- an `IAEItemStack` carries a long
     * and the `ItemStack` it makes is capped at 64, so counting the stacks would report a
     * network holding two million cobblestone as holding 64.
     *
     * CRAFTABLE-ONLY ENTRIES ARE SKIPPED. AE2's list includes items the network can autocraft
     * but does not hold, at size zero; counting those as stock would tell the planner you
     * already own something you would have to make.
     */
    private static Map<String, Long> countsOf(IMEMonitor<IAEItemStack> items) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (IAEItemStack entry : items.getStorageList()) {
            if (entry == null || entry.getStackSize() <= 0L) {
                continue;
            }
            ItemStack stack = entry.createItemStack();
            String key = DumpCommand.stackKey(stack);
            if (key == null) {
                continue;
            }
            Long already = counts.get(key);
            // Summed rather than put: two AE entries can key alike once cosmetic NBT is
            // stripped by the discriminator, and the second must not replace the first.
            counts.put(key, Long.valueOf(
                    (already == null ? 0L : already.longValue()) + entry.getStackSize()));
        }
        return counts;
    }

    /**
     * The machines connected to this grid, keyed as item keys, to how many were seen. #320
     *
     * <h2>THE GRID IS ONE OF TWO SOURCES SINCE PHASE 2. This is the grid half.</h2>
     *
     * A machine counts as owned when it is CONNECTED TO THE ME SYSTEM (phase 1, Jake's call on
     * #320) or when it is STANDING IN A LOADED CHUNK ({@link PlacedTileEntities}, phase 2). Both
     * merge into the one `placed` map this returns into, both ride the same gates, and neither
     * outranks the other -- `Machines.candidateVerdict` tests membership and quotes the key, so
     * there is nothing a per-source tag could change.
     *
     * WHAT THE GRID HALF STILL EARNS ITS KEEP FOR, now that the world is scanned too: a grid node
     * is not always a tile entity in a loaded chunk. An AE2 PART shares its host tile with the
     * cable, so a storage bus or an ME interface has a node and no tile of its own, and AE2's
     * `getMachineRepresentation()` is the only thing that names the part's item. DO NOT delete this
     * method as redundant with the tile-entity scan; they see overlapping, not identical, sets.
     *
     * <h2>WHAT AE2'S ENUMERATION ACTUALLY RETURNS, measured 2026-08-22 against the pack</h2>
     *
     * A node exists only for something that IMPLEMENTS `IGridHost` and registers one. Measured
     * over MeatballCraft's 366 jars: 21 reference `IGridHost` in a constant pool and 11 declare
     * it as a direct superinterface -- AE2 itself, ExtraCells, ae2stuff, ThaumicEnergistics,
     * OpenComputers, EnderIO's ME conduit, capabilityadapter, and the PackagedAuto family
     * (PackagedAuto, PackagedExCrafting, PackagedAvaritia, PackagingProvider), whose crafters
     * are real machines. The other 345 never touch it.
     *
     * SO A MACHINE ATTACHED THROUGH A STORAGE BUS IS INVISIBLE HERE, and that is not a bug to
     * fix: the bus is the node, and the chest or furnace behind it is not on the grid at all.
     * Modular Machinery CE only REFERENCES `IGridHost` and declares none, so a multiblock
     * controller is not a grid node either.
     *
     * NEITHER OF THOSE IS INVISIBLE TO THE PLAYER ANY MORE, and that is what phase 2 changed: both
     * are tile entities, so {@link PlacedTileEntities} sees them if their chunk is loaded, and
     * {@link io.github.jacoblasky.recipedump.common.ModularMachineryControllers} reads which machine
     * a formed controller has become. The sentence above remains true about THIS method and is why
     * the second source exists.
     *
     * <h2>Why the machine representation and not the block at the node's position</h2>
     *
     * `IGridBlock.getMachineRepresentation()` is AE2'S OWN answer to "what item is this
     * machine", so nothing here invents a block-to-item mapping. `Machines.candidateItems`
     * already records why that lands in the right namespace -- "a machine block's item and
     * block registry names are the same in 1.12.2, so a candidate can be checked against both
     * placed tile entities and inventory with no extra mapping" -- and AE2 populates it from
     * the tile's own item, as does every third-party implementation measured above
     * (PackagedAuto builds `new ItemStack(block, 1, meta)`).
     *
     * `baseStackKey` RATHER THAN `stackKey`, for the catalyst walk's reason: a machine verdict
     * is a claim about an ITEM and not about one NBT state of it, so a digest here would strand
     * the match against the graph's bare machine key.
     *
     * <h2>Two filters that look right and are NOT applied</h2>
     *
     * DO NOT FILTER ON `isWorldAccessible()`. It reads like "is this a real placed block" and
     * it is not: measured on this jar, `AEBasePart`'s CONSTRUCTOR passes
     * `this instanceof PartCable` into `AENetworkProxy` (whose `isWorldAccessible()` returns
     * that flag), while `AENetworkTile.createProxy` passes true for every tile -- so every AE2
     * part that is not a cable -- an ME Interface, a
     * storage bus, a pattern provider -- reports false while being a block the player placed.
     * The flag distinguishes the node that OWNS the world tile from the parts sharing it. Using
     * it would drop real machines and keep cables, which is backwards.
     *
     * DO NOT FILTER ON `isActive()`. A machine with no channel or no power is still one the
     * player BUILT, which is what `Evidence.placed` means -- "you built it, it is there" -- and
     * the offline region reader makes no power check either. Availability of a machine is not
     * the same question as whether it is running right now.
     */
    private static Map<String, Integer> placedOf(IGrid grid) {
        Map<String, Integer> placed = new LinkedHashMap<String, Integer>();
        for (IGridNode node : grid.getNodes()) {
            IGridBlock block = node.getGridBlock();
            if (block == null) {
                continue;
            }
            // Null-safe on the ItemStack too: the API says non-null and a third-party
            // implementation is not the API. `baseStackKey` answers null for an empty stack,
            // for an item with no registry name, and now for a null one.
            String key = DumpCommand.baseStackKey(block.getMachineRepresentation());
            if (key == null) {
                continue;
            }
            Integer already = placed.get(key);
            // Summed rather than put, for `countsOf`'s reason one field over: eight drives on
            // one grid are eight sightings of the same key, and the eighth must not replace
            // the first.
            placed.put(key, Integer.valueOf((already == null ? 0 : already.intValue()) + 1));
        }
        return placed;
    }
}
