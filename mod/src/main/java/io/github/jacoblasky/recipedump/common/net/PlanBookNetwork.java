package io.github.jacoblasky.recipedump.common.net;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlanBookCapability;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The plan book's channel: one server-to-client sync and one client-to-server edit.
 *
 * The channel name is the modid, which is the convention and is also the length limit worth
 * respecting -- 1.12.2 truncates a channel name over 20 characters, and two mods whose names
 * collide after truncation share a channel and corrupt each other's packets.
 */
@Mod.EventBusSubscriber(modid = RecipeDumpMod.MODID)
public final class PlanBookNetwork {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(RecipeDumpMod.MODID);

    /**
     * Discriminators. STABLE NUMBERS, like {@link PlanBookEdit}'s ids: both sides decode by
     * this number, so renumbering makes one message arrive as another.
     */
    private static final int ID_SYNC = 0;
    private static final int ID_EDIT = 1;
    private static final int ID_STOCK_REQUEST = 2;
    private static final int ID_STOCK_REPLY = 3;

    private static boolean registered;

    private PlanBookNetwork() {
    }

    /**
     * Called from the proxy in preInit, on both sides.
     *
     * BOTH DIRECTIONS ARE REGISTERED ON BOTH SIDES, which looks redundant and is not: a
     * message can only be SENT if it is registered on the sending side, and the server is what
     * sends the client-bound sync. Registering only the receiving half on each side is the
     * mistake that produces "Received invalid discriminator" on the first login.
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.registerMessage(PlanBookSyncMessage.Handler.class, PlanBookSyncMessage.class,
                                ID_SYNC, Side.CLIENT);
        CHANNEL.registerMessage(PlanBookEditMessage.Handler.class, PlanBookEditMessage.class,
                                ID_EDIT, Side.SERVER);
        // The live AE2 stock pair (#19 Phase 5). Same channel rather than a second one: a
        // channel costs a registration on both sides and these are the same conversation
        // between the same two parties.
        CHANNEL.registerMessage(StockRequestMessage.Handler.class, StockRequestMessage.class,
                                ID_STOCK_REQUEST, Side.SERVER);
        CHANNEL.registerMessage(StockReplyMessage.Handler.class, StockReplyMessage.class,
                                ID_STOCK_REPLY, Side.CLIENT);
    }

    /** Send a player their own book. Silently does nothing for a client-side player. */
    public static void syncTo(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        PlanBook book = PlanBookCapability.of(player);
        if (book != null) {
            CHANNEL.sendTo(new PlanBookSyncMessage(book), (EntityPlayerMP) player);
        }
    }

    /**
     * Push the book on login.
     *
     * `PlayerLoggedInEvent` is the first point at which the player's capabilities have been
     * read from disk AND the connection can accept a payload. Sending from
     * `AttachCapabilitiesEvent` instead would send an empty book every time, and the symptom
     * is favourites that only appear after the session's first edit.
     */
    @SubscribeEvent
    public static void onLogin(net.minecraftforge.fml.common.gameevent.PlayerEvent
                                       .PlayerLoggedInEvent event) {
        syncTo(event.player);
    }

    /**
     * Re-send after a respawn or a dimension change.
     *
     * The player entity is REPLACED by both, `PlanBookCapability.copyOnClone` carries the book
     * onto the new one, and the client's copy is attached to a new entity too -- so without
     * this the client shows an empty book until its next edit. Two events rather than one
     * because Forge does not merge them.
     */
    @SubscribeEvent
    public static void onRespawn(net.minecraftforge.fml.common.gameevent.PlayerEvent
                                         .PlayerRespawnEvent event) {
        syncTo(event.player);
    }

    @SubscribeEvent
    public static void onChangedDimension(net.minecraftforge.fml.common.gameevent.PlayerEvent
                                                  .PlayerChangedDimensionEvent event) {
        syncTo(event.player);
    }
}
