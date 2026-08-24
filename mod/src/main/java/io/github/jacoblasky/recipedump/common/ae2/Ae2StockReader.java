package io.github.jacoblasky.recipedump.common.ae2;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.features.IWirelessTermHandler;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEPartLocation;
import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.common.PlacedTileEntities;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/**
 * THE ONLY CLASS OUTSIDE `shot/` THAT NAMES AN AE2 TYPE.
 *
 * Loaded exclusively through {@link Ae2Stock}, after its presence check. A reference to this
 * class from anywhere that loads unconditionally would make the mod fail to load on a pack
 * without AE2 -- the same eager-resolution trap the ProjectE and Modular Machinery bridges
 * document at length. DO NOT import it anywhere else.
 *
 * "OUTSIDE `shot/`" RATHER THAN "IN THIS MOD", WHICH IS WHAT THIS SAID AND WAS NOT TRUE:
 * `shot.Ae2ProbeShot` and `shot.Ae2PlacedProbeShot` both import `appeng` types. They are harness
 * classes, only ever reached by the screenshot harness on a world that has AE2, so they do not
 * put the load-time trap back -- but a header that overstates its own rule is a header the next
 * reader stops believing, and `Ae2StockTest`'s range-gate scan excludes `shot/` for exactly this
 * reason.
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
            // AE2's locatable registry cannot answer for the serial on the terminal. That is
            // ONE VERDICT OVER TWO WORLDS and the reason cannot tell them apart: the security
            // station is not loaded, or it is not there any more. Distinct from "not linked",
            // because the fix differs -- one is re-link, the other is go and look at your base.
            //
            // NOT-LOADED IS THE LIKELIER HALF ON A SERVER and the message leads with it (#331);
            // see `Reason.NETWORK_GONE`. This comment led with "the security terminal was broken,
            // or the grid was dismantled" until then, which is the half a player cannot act on.
            //
            // AND SINCE #331 THIS GATE CARRIES #320's BALANCE RULE ALONE, because the range check
            // above it is gone. A registry hit means a loaded, registered security station, which
            // is what "the player has an ME connection" now rests on.
            return StockSnapshot.unavailable(StockSnapshot.Reason.NETWORK_GONE);
        }

        // THERE IS NO RANGE GATE HERE ANY MORE, AND THE ONE THAT WAS HERE NEVER PASSED. #331
        //
        // `inRangeOfAccessPoint` walked the grid for an ACTIVE `IWirelessAccessPoint` whose own
        // `getRange()` covered the player, in the player's current dimension. Its javadoc claimed
        // an invariant -- "a player who can open their terminal here can read their network here"
        // -- and that invariant was FALSE ON THIS PACK THE DAY IT WAS WRITTEN.
        //
        // MEASURED 2026-08-23, full region scan of every dimension in the reference save: there is
        // not ONE vanilla `IWirelessAccessPoint` in the entire world. The base networks through
        // `ae2stuff:wireless` connectors (103 of them, 28 controllers, one security station,
        // quantum-bridged into DIM43), and the pack's `ae2wut` universal terminal opens the wrapped
        // terminals through the underlying mods' own GUI factories -- verified in the jar: it
        // delegates to ae2fc, mekeng and ae2exttable rather than enforcing AE2's access-point rule.
        // So the terminal opens anywhere and this gate refused everywhere. Every live stock read
        // since 0.11.0 shipped came back OUT_OF_RANGE, including standing inside the base, and it
        // was reported as "the ingredients still aren't being detected correctly".
        //
        // THE GATES ARE NOW EXACTLY WHAT THE PACK'S OWN TERMINAL ENFORCES: a terminal in the
        // inventory, a link, a grid that resolves and is loaded, power, and EXTRACT permission.
        //
        // #320's BALANCE RULE STILL HOLDS, AND `gridFor` IS NOW THE WHOLE OF IT. That rule is that
        // nothing is owned without an ME connection, and the connection is the linked encryption
        // key resolving against a LIVE grid: `gridFor` goes through AE2's locatable registry, which
        // answers only for a security station that is loaded and registered, so a player with no
        // network -- or whose base is unloaded -- still gets `NETWORK_GONE` and owns nothing. That
        // gate was one of five of `Ae2Stock`'s access questions and is now one of four -- that
        // list, not the five-gate chain named above, which counts the terminal too -- carrying an
        // argument the range check used to share. DO NOT weaken it, and DO NOT let `PlacedTileEntities.scan` below rise above it.
        //
        // DO NOT RESTORE A RANGE CHECK because AE2's own terminal makes one. That was the reasoning
        // the first time and the pack does not obey it; a gate that refuses every real read is not
        // fidelity, it is a mod that does not work. If a future pack does enforce access points,
        // measure the save for `IWirelessAccessPoint` before adding it back.
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
        // this method, after the terminal, link, grid, power and permission checks have all passed.
        // (IT SAID "terminal, range, power and permission" UNTIL #331, naming a gate that is gone
        // and omitting the one that now carries the balance rule alone. A list of the gates a call
        // must stay below is worthless if it names gates that do not exist.)
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
