package io.github.jacoblasky.recipedump.common.net;

import io.github.jacoblasky.recipedump.common.ae2.Ae2Stock;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: "read my ME network".
 *
 * EMPTY PAYLOAD, AND IT MUST STAY EMPTY. The server already knows who is asking -- the
 * message context carries the player -- and everything about WHICH network is derived from
 * that player's own inventory and position on the server. A client that named a network, a
 * position or a terminal slot would be a client asserting facts the server must check anyway,
 * which is the shape every "trusted client" exploit takes. There is nothing to put in here.
 */
public class StockRequestMessage implements IMessage {

    /** Required by the network layer, which instantiates by reflection before decoding. */
    public StockRequestMessage() {
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // Nothing. See the class note: the server derives everything from the sender.
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    public static class Handler implements IMessageHandler<StockRequestMessage, IMessage> {

        /**
         * ON THE SERVER THREAD, not the netty IO thread this arrives on.
         *
         * Reading a grid walks live AE2 state that the server tick is mutating; doing it from
         * the IO thread is the 1.12.2 race that surfaces as a
         * ConcurrentModificationException blamed on whichever mod's storage bus happened to
         * be mid-iteration. `PlanBookEditMessage` schedules for the same reason.
         *
         * Returning null and sending the reply ourselves rather than returning an IMessage:
         * the return value would be dispatched immediately, on this thread, before the
         * scheduled read has run.
         */
        @Override
        public IMessage onMessage(final StockRequestMessage message, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServer().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    StockSnapshot snapshot = Ae2Stock.read(player);
                    PlanBookNetwork.CHANNEL.sendTo(new StockReplyMessage(snapshot), player);
                }
            });
            return null;
        }
    }
}
