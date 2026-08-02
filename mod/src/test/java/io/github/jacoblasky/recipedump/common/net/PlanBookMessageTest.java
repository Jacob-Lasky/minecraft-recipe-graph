package io.github.jacoblasky.recipedump.common.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import io.github.jacoblasky.recipedump.common.PlanBook;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

/**
 * The two packets, encoded and decoded for real against a netty buffer.
 *
 * This needs no game and no network: `toBytes`/`fromBytes` are ordinary methods over a
 * `ByteBuf`, and Forge's `ByteBufUtils` is on the test classpath with the rest of Forge. What
 * it catches is the one bug this shape reliably produces -- a field written in one order and
 * read in another, which does not fail, it just decodes into the wrong variables.
 */
public class PlanBookMessageTest {

    private static <T extends net.minecraftforge.fml.common.network.simpleimpl.IMessage> T
            roundTrip(net.minecraftforge.fml.common.network.simpleimpl.IMessage out, T in) {
        ByteBuf buf = Unpooled.buffer();
        out.toBytes(buf);
        in.fromBytes(buf);
        assertEquals("the decoder must consume exactly what the encoder wrote",
                     0, buf.readableBytes());
        return in;
    }

    /**
     * THE WIRE CONTRACT. These numbers go into a packet and the other side switches on them,
     * so reordering the enum turns "unstar this" into "star this" with nothing to notice. The
     * literals here are the point; do not replace them with `ADD_FAVOURITE.id()`.
     */
    @Test
    public void theEditVerbIdsAreTheNumbersTheWireExpects() {
        assertEquals(0, PlanBookEdit.ADD_FAVOURITE.id());
        assertEquals(1, PlanBookEdit.REMOVE_FAVOURITE.id());
        assertEquals(2, PlanBookEdit.SET_TODO.id());
        assertEquals("a new verb must take the next free id, not an existing one",
                     3, PlanBookEdit.values().length);
    }

    @Test
    public void everyEditVerbFindsItselfByItsOwnId() {
        for (PlanBookEdit edit : PlanBookEdit.values()) {
            assertEquals(edit, PlanBookEdit.byId(edit.id()));
        }
    }

    @Test
    public void anUnknownEditVerbDecodesToNullRatherThanThrowing() {
        // The decoder runs on a netty thread against bytes from a client that may be on a
        // different build, or lying. Neither should be able to kill the handler.
        assertNull(PlanBookEdit.byId(99));
        assertNull(PlanBookEdit.byId(-1));
    }

    @Test
    public void anEditMessageSurvivesTheRoundTripFieldForField() {
        PlanBookEditMessage decoded = roundTrip(
                new PlanBookEditMessage(PlanBookEdit.SET_TODO, "fluid:water", 934_400L),
                new PlanBookEditMessage());
        assertEquals(PlanBookEdit.SET_TODO, decoded.edit());
        assertEquals("fluid:water", decoded.key());
        assertEquals(934_400L, decoded.quantity());
    }

    @Test
    public void anEditMessageCarriesAQuantityBiggerThanAnInt() {
        PlanBookEditMessage decoded = roundTrip(
                new PlanBookEditMessage(PlanBookEdit.SET_TODO, "fluid:water", 60_466_176_000L),
                new PlanBookEditMessage());
        assertEquals(60_466_176_000L, decoded.quantity());
    }

    @Test
    public void anEditMessageWithANullKeyEncodesAsEmptyRatherThanFailing() {
        // `ByteBufUtils.writeUTF8String` throws on null, and a null key is what a UI bug
        // upstream produces. Empty decodes into a key the book rejects, which is the right
        // outcome: nothing happens.
        PlanBookEditMessage decoded = roundTrip(
                new PlanBookEditMessage(PlanBookEdit.ADD_FAVOURITE, null, 0L),
                new PlanBookEditMessage());
        assertEquals("", decoded.key());
        PlanBook book = new PlanBook();
        assertEquals(false, decoded.edit().applyTo(book, decoded.key(), decoded.quantity()));
        assertTrue(book.isEmpty());
    }

    @Test
    public void everyEditVerbDoesToABookWhatItsNameSays() {
        PlanBook book = new PlanBook();
        PlanBookEdit.ADD_FAVOURITE.applyTo(book, "a", 0L);
        assertTrue(book.isFavourite("a"));
        PlanBookEdit.SET_TODO.applyTo(book, "b", 12L);
        assertEquals(12L, book.todoQuantity("b"));
        PlanBookEdit.REMOVE_FAVOURITE.applyTo(book, "a", 0L);
        assertTrue(book.favourites().isEmpty());
        PlanBookEdit.SET_TODO.applyTo(book, "b", 0L);
        assertTrue(book.isEmpty());
    }

    @Test
    public void aSyncMessageCarriesTheWholeBook() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.addFavourite("thaumadditions:vis_pod#0116bb2287a7");
        book.setTodo("fluid:nethengeic_fluid", 1000L);

        PlanBookSyncMessage decoded =
                roundTrip(new PlanBookSyncMessage(book), new PlanBookSyncMessage());
        assertNotNull(decoded.payload());

        PlanBook landed = new PlanBook();
        landed.deserializeNBT(decoded.payload());
        assertEquals(Arrays.asList("minecraft:iron_ingot",
                                   "thaumadditions:vis_pod#0116bb2287a7"),
                     landed.favourites());
        assertEquals(1000L, landed.todoQuantity("fluid:nethengeic_fluid"));
    }

    @Test
    public void anEmptyBookStillProducesAReadableSyncMessage() {
        // Sent on every login, so the empty case is the COMMON one, not an edge.
        PlanBookSyncMessage decoded =
                roundTrip(new PlanBookSyncMessage(new PlanBook()), new PlanBookSyncMessage());
        NBTTagCompound payload = decoded.payload();
        assertNotNull("an empty book must not encode as a null tag", payload);
        PlanBook landed = new PlanBook();
        landed.deserializeNBT(payload);
        assertTrue(landed.isEmpty());
    }

    @Test
    public void aFullBookFitsWellInsideTheCustomPayloadLimit() {
        // The caps on PlanBook exist for this reason and no other, so measure it rather than
        // asserting the caps back at themselves. 1.12.2 disconnects a player over a 1 MiB
        // payload; a worst-case book has to be nowhere near that.
        PlanBook book = new PlanBook();
        String longKey = "somemodwithalongname:some_item_with_a_long_name#0116bb2287a7";
        for (int i = 0; i < PlanBook.MAX_FAVOURITES; i++) {
            book.addFavourite(longKey + i);
        }
        for (int i = 0; i < PlanBook.MAX_TODO; i++) {
            book.setTodo(longKey + "todo" + i, Long.MAX_VALUE);
        }
        ByteBuf buf = Unpooled.buffer();
        new PlanBookSyncMessage(book).toBytes(buf);
        int bytes = buf.readableBytes();
        assertTrue("a full book encoded to " + bytes + " bytes, which is not comfortably "
                   + "inside the 1 MiB custom payload limit", bytes < 200_000);
    }
}
