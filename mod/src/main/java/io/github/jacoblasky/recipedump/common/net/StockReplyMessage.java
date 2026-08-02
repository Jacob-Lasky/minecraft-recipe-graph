package io.github.jacoblasky.recipedump.common.net;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server to client: what the network holds, or the named reason it could not be read.
 *
 * A REFUSAL IS A REPLY, not a dropped packet. Every failure path on the server sends one of
 * these carrying a `Reason`, because a client that asked and heard nothing cannot tell "no
 * network in range" from "the packet was lost" -- and the sensible thing to do about those
 * two is different. `StockSnapshot` refuses to carry an empty map and an OK at the same time,
 * so the distinction survives the wire by construction.
 *
 * SIZE. One entry is a key and a long, and the reference network holds around 20,000 distinct
 * keys -- roughly a megabyte, sent once when a plan is requested rather than per tick. That is
 * why this is a reply to an explicit ask and not a subscription: AE2's own terminal streams
 * deltas because it is open continuously, and a planner is not.
 */
public class StockReplyMessage implements IMessage {

    private NBTTagCompound payload;

    /** Required by the network layer, which instantiates by reflection before decoding. */
    public StockReplyMessage() {
    }

    public StockReplyMessage(StockSnapshot snapshot) {
        this.payload = snapshot.serializeNBT();
    }

    /** The serialised snapshot. Exposed for the round-trip test and for the handler. */
    public NBTTagCompound payload() {
        return payload;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeTag(buf, payload);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        payload = ByteBufUtils.readTag(buf);
    }

    /**
     * Through the proxy, for the reason `PlanBookSyncMessage.Handler` records: registering a
     * client-bound handler still loads its class on a dedicated server, so naming `Minecraft`
     * here would make that load a coin flip.
     */
    public static class Handler implements IMessageHandler<StockReplyMessage, IMessage> {

        @Override
        public IMessage onMessage(StockReplyMessage message, MessageContext ctx) {
            RecipeDumpMod.proxy.applyStockSnapshot(message.payload);
            return null;
        }
    }
}
