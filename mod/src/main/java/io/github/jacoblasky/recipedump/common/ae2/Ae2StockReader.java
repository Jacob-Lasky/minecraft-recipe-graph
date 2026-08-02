package io.github.jacoblasky.recipedump.common.ae2;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.implementations.tiles.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import io.github.jacoblasky.recipedump.DumpCommand;
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
        return StockSnapshot.of(countsOf(items));
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
}
