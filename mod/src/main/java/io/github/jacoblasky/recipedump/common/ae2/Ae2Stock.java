package io.github.jacoblasky.recipedump.common.ae2;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Reads the player's ME network, or says why it could not. Issue #19 Phase 5.
 *
 * <h2>WHICH NETWORK, which is the real design question here</h2>
 *
 * AE2 has no notion of "the player's network". A player can have several, share one, or be
 * standing next to somebody else's. Three candidates were considered and only one survives:
 *
 * <ol>
 * <li><b>An open ME terminal.</b> Unambiguous -- the player has literally chosen that network
 *     and AE2 has already resolved its security. It is nevertheless IMPOSSIBLE here: 1.12.2
 *     shows one screen at a time, so opening the planner CLOSES the terminal, and there is no
 *     moment at which both are open. Capturing the last terminal the player used would work
 *     and is exactly the stale-data answer this phase exists to remove.</li>
 * <li><b>Any grid within some radius of the player.</b> Needs no item, and is wrong twice: a
 *     radius is a number this mod would be inventing, and on a server it reads a network the
 *     player may have no access to. AE2 ships a security system precisely so that "can this
 *     player see this network" is not a question anyone else answers.</li>
 * <li><b>The network a wireless terminal in the inventory is linked to.</b> This one.</li>
 * </ol>
 *
 * <h2>Why the wireless terminal, and why nothing here is invented</h2>
 *
 * Every step of the chain is AE2's own notion, taken from AE2's own published API, so this
 * mod decides nothing about access that AE2 has not already decided:
 *
 * <ul>
 * <li>which network -- the terminal's encryption key, through AE2's locatable registry;</li>
 * <li>whether you are close enough -- an {@code IWirelessAccessPoint} on that grid whose own
 *     {@code getRange()} covers you, which is the same test AE2's wireless terminal makes;</li>
 * <li>whether the terminal can run -- the handler's own {@code hasPower};</li>
 * <li>whether you may look -- {@code ISecurityGrid.hasPermission(player, EXTRACT)}.</li>
 * </ul>
 *
 * IT IS ALSO WHAT THE CALCULATOR IS MADE OF. The recipe puts a Wireless Receiver in the item
 * for the stated reason that it reads your network at range; if the planner instead sniffed
 * any nearby grid, that component would be decoration. The lore and the capability agree
 * because they are the same mechanism.
 *
 * <h2>Loading without AE2</h2>
 *
 * AE2 is a {@code compileOnly} dependency, so a direct reference from a class the mod always
 * loads is a resolution failure on a pack without it. {@link Ae2StockReader} holds every AE2
 * type and is touched ONLY after the presence check below -- the same shape
 * `ClientProxy.openPlanner` uses for ModularUI, and the reason the two reflective bridges
 * exist for ProjectE and Modular Machinery. DO NOT move an AE2 import into this class.
 */
public final class Ae2Stock {

    private static final String AE_API = "appeng.api.AEApi";

    private static Boolean present;

    private Ae2Stock() {
    }

    /** True when AE2 is installed. Cached: the answer cannot change within a run. */
    public static synchronized boolean isAvailable() {
        if (present == null) {
            try {
                Class.forName(AE_API);
                present = Boolean.TRUE;
            } catch (Throwable absent) {
                present = Boolean.FALSE;
            }
        }
        return present.booleanValue();
    }

    /**
     * SERVER SIDE ONLY. The grid lives on the server and a client has no view of it.
     *
     * Never throws and never returns null: every failure is a named {@link
     * StockSnapshot.Reason} the player can act on. A `Throwable` catch rather than a narrow
     * one because this walks six AE2 interfaces across an arbitrary pack's grid, and a
     * planner that dies on somebody's exotic storage bus is worse than one that says it could
     * not read.
     */
    public static StockSnapshot read(EntityPlayer player) {
        if (player == null || player.world == null || player.world.isRemote) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NETWORK_GONE);
        }
        if (!isAvailable()) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NO_AE2);
        }
        try {
            return Ae2StockReader.read(player);
        } catch (Throwable failed) {
            return StockSnapshot.unavailable(StockSnapshot.Reason.NETWORK_GONE);
        }
    }
}
