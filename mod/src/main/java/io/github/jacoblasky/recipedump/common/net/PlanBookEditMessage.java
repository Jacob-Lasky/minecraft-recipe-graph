package io.github.jacoblasky.recipedump.common.net;

import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlanBookCapability;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: one edit to the sender's own plan book.
 *
 * THE SERVER IS THE ONLY WRITER. A client edits by asking, and gets the result back as a
 * {@link PlanBookSyncMessage}; it never mutates its own copy and hopes. That costs a round
 * trip on a keypress, which nobody will notice, and buys the thing that matters: the book
 * that is actually saved is the one the server holds, so a rejected edit -- a blank key, a
 * book at its cap -- cannot leave the two sides disagreeing about what is starred.
 */
public class PlanBookEditMessage implements IMessage {

    private PlanBookEdit edit;
    private String key;
    private long quantity;

    /** Required by the network layer, which instantiates by reflection before decoding. */
    public PlanBookEditMessage() {
    }

    public PlanBookEditMessage(PlanBookEdit edit, String key, long quantity) {
        this.edit = edit;
        this.key = key;
        this.quantity = quantity;
    }

    public PlanBookEdit edit() {
        return edit;
    }

    public String key() {
        return key;
    }

    public long quantity() {
        return quantity;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(edit.id());
        ByteBufUtils.writeUTF8String(buf, key == null ? "" : key);
        buf.writeLong(quantity);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // Null for an id this build does not know, rather than an exception on the netty
        // thread. `Handler` drops the message; see `PlanBookEdit.byId`.
        edit = PlanBookEdit.byId(buf.readByte());
        key = ByteBufUtils.readUTF8String(buf);
        quantity = buf.readLong();
    }

    public static class Handler implements IMessageHandler<PlanBookEditMessage, IMessage> {

        /**
         * @return null always. The reply is a full {@link PlanBookSyncMessage} sent from the
         *     server thread once the edit has actually been applied, not a return value from
         *     here -- this method runs on the netty thread, where the book does not yet know
         *     it has changed.
         */
        @Override
        public IMessage onMessage(final PlanBookEditMessage message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            if (message.edit == null) {
                return null;
            }
            // ON THE SERVER THREAD, NOT THIS ONE. `onMessage` runs on a netty IO thread, and
            // touching an entity's capabilities from there is the classic 1.12.2 concurrent
            // modification that shows up as a rare, unreproducible crash in someone else's
            // code. `addScheduledTask` hands it to the tick loop.
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    PlanBook book = PlanBookCapability.of(player);
                    if (book == null) {
                        return;
                    }
                    // Synced unconditionally, not only when the book changed. A rejected edit
                    // is exactly when the client's optimistic view is most likely to be wrong,
                    // so that is the case that most needs correcting.
                    message.edit.applyTo(book, message.key, message.quantity);
                    PlanBookNetwork.syncTo(player);
                }
            });
            return null;
        }
    }
}
