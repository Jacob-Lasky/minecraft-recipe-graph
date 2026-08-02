package io.github.jacoblasky.recipedump.common.net;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server to client: the player's whole plan book.
 *
 * WHOLE BOOK, NOT A DELTA. The book is capped at 256 favourites and 256 TODO rows precisely
 * so that it fits comfortably in one payload, and a full replace cannot drift out of step
 * with the server the way a stream of deltas can when one is dropped or arrives late. It is
 * sent on login and after every accepted edit, which is a handful of packets per session.
 */
public class PlanBookSyncMessage implements IMessage {

    private NBTTagCompound payload;

    /** Required by the network layer, which instantiates by reflection before decoding. */
    public PlanBookSyncMessage() {
    }

    public PlanBookSyncMessage(PlanBook book) {
        this.payload = book.serializeNBT();
    }

    /** The serialised book. Exposed for the round-trip test and for the handler. */
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
     * REGISTERED ON BOTH SIDES, AND THAT IS WHY IT GOES THROUGH THE PROXY.
     *
     * `SimpleNetworkWrapper.registerMessage` instantiates the handler class as it registers,
     * on whichever side is registering, so a client-bound handler class is still loaded on a
     * dedicated server. Naming `Minecraft` here would put it in this class's constant pool and
     * make that load a coin flip. `RecipeDumpMod.proxy.applyPlanBookSync` is a no-op on the
     * server and does the real work in `ClientProxy`.
     */
    public static class Handler implements IMessageHandler<PlanBookSyncMessage, IMessage> {

        @Override
        public IMessage onMessage(PlanBookSyncMessage message, MessageContext ctx) {
            RecipeDumpMod.proxy.applyPlanBookSync(message.payload);
            return null;
        }
    }
}
