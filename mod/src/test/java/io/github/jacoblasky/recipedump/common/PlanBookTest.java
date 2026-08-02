package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

/**
 * The per-player plan book: what it stores, and that a round trip through NBT is lossless.
 *
 * Worth a real test rather than an eyeball because this is the one piece of #19 that is
 * WRITTEN TO A SAVE. A serialisation bug here is not a wrong pixel, it is a player's
 * favourites gone after a relog, discovered later and unrecoverable.
 */
public class PlanBookTest {

    private static PlanBook roundTrip(PlanBook book) {
        NBTTagCompound nbt = book.serializeNBT();
        PlanBook read = new PlanBook();
        read.deserializeNBT(nbt);
        return read;
    }

    @Test
    public void aFreshBookIsEmpty() {
        PlanBook book = new PlanBook();
        assertTrue(book.isEmpty());
        assertTrue(book.favourites().isEmpty());
        assertTrue(book.todoKeys().isEmpty());
        assertEquals(0L, book.todoQuantity("minecraft:iron_ingot"));
    }

    @Test
    public void favouritesComeBackInTheOrderTheyWereStarred() {
        PlanBook book = new PlanBook();
        book.addFavourite("c");
        book.addFavourite("a");
        book.addFavourite("b");
        assertEquals(Arrays.asList("c", "a", "b"), book.favourites());
        assertEquals(Arrays.asList("c", "a", "b"), roundTrip(book).favourites());
    }

    @Test
    public void starringSomethingTwiceDoesNotDuplicateItOrReorderIt() {
        PlanBook book = new PlanBook();
        book.addFavourite("a");
        book.addFavourite("b");
        assertFalse("the second star changed nothing, so nothing needs syncing",
                    book.addFavourite("a"));
        assertEquals(Arrays.asList("a", "b"), book.favourites());
    }

    @Test
    public void aFluidKeyIsJustAnotherKey() {
        // The planner speaks graph keys, and 1,198 of this pack's fluids have no item at all.
        // If this ever stops working the book has quietly become item-only.
        PlanBook book = new PlanBook();
        book.addFavourite("fluid:nethengeic_fluid");
        book.setTodo("essentia:perditio", 12L);
        PlanBook read = roundTrip(book);
        assertTrue(read.isFavourite("fluid:nethengeic_fluid"));
        assertEquals(12L, read.todoQuantity("essentia:perditio"));
    }

    @Test
    public void anNbtDiscriminatedKeySurvivesTheRoundTripIntact() {
        // 298,765 of 340,324 named keys carry a `#digest`. A book that mangled one would plan
        // the base item instead, which is a different ingredient.
        String key = "thaumadditions:vis_pod#0116bb2287a7";
        PlanBook book = new PlanBook();
        book.addFavourite(key);
        assertTrue(roundTrip(book).isFavourite(key));
    }

    @Test
    public void aTodoQuantityBiggerThanAnIntSurvives() {
        // Fluids are counted in mB and one Borax plan draws 934,400 of them; an int field
        // would have overflowed silently and negatively somewhere above two billion.
        long huge = 60_466_176_000L;
        PlanBook book = new PlanBook();
        book.setTodo("fluid:water", huge);
        assertEquals(huge, roundTrip(book).todoQuantity("fluid:water"));
    }

    @Test
    public void settingATodoQuantityToZeroRemovesTheRow() {
        // This is what lets the edit packet carry one verb instead of a separate delete.
        PlanBook book = new PlanBook();
        book.setTodo("minecraft:iron_ingot", 5L);
        assertTrue(book.setTodo("minecraft:iron_ingot", 0L));
        assertTrue(book.todoKeys().isEmpty());
        assertEquals(0L, book.todoQuantity("minecraft:iron_ingot"));
    }

    @Test
    public void aNegativeQuantityRemovesRatherThanStoringNonsense() {
        PlanBook book = new PlanBook();
        book.setTodo("minecraft:iron_ingot", 5L);
        book.setTodo("minecraft:iron_ingot", -1L);
        assertTrue(book.todoKeys().isEmpty());
    }

    @Test
    public void updatingATodoQuantityKeepsItsPlaceInTheOrder() {
        PlanBook book = new PlanBook();
        book.setTodo("a", 1L);
        book.setTodo("b", 1L);
        book.setTodo("a", 99L);
        assertEquals(Arrays.asList("a", "b"), book.todoKeys());
        assertEquals(99L, book.todoQuantity("a"));
    }

    @Test
    public void aBlankKeyIsRejectedRatherThanStored() {
        // A blank row is invisible in the UI and impossible to delete from it.
        PlanBook book = new PlanBook();
        assertFalse(book.addFavourite(null));
        assertFalse(book.addFavourite(""));
        assertFalse(book.addFavourite("   "));
        assertFalse(book.setTodo("", 5L));
        assertTrue(book.isEmpty());
    }

    @Test
    public void theCapsHoldAndAreNotOffByOne() {
        // The caps exist so the sync packet cannot grow past what 1.12.2 will carry, so the
        // boundary is the thing worth pinning rather than the number.
        PlanBook book = new PlanBook();
        for (int i = 0; i < PlanBook.MAX_FAVOURITES; i++) {
            assertTrue("favourite " + i + " should fit", book.addFavourite("key" + i));
        }
        assertFalse("one past the cap must be refused",
                    book.addFavourite("one-too-many"));
        assertEquals(PlanBook.MAX_FAVOURITES, book.favourites().size());

        for (int i = 0; i < PlanBook.MAX_TODO; i++) {
            assertTrue("todo " + i + " should fit", book.setTodo("todo" + i, 1L));
        }
        assertFalse(book.setTodo("one-too-many", 1L));
        assertEquals(PlanBook.MAX_TODO, book.todoKeys().size());
        // A book at its cap must still be EDITABLE, or a full list is a stuck list.
        assertTrue("updating an existing row is not a new row", book.setTodo("todo0", 7L));
        assertTrue(book.setTodo("todo0", 0L));
    }

    @Test
    public void readingBackARoundTripDoesNotAccumulateOntoWhatWasThere() {
        // `deserializeNBT` runs on an instance that already exists -- the capability's -- so a
        // read that appended instead of replacing would double a player's list every login.
        PlanBook book = new PlanBook();
        book.addFavourite("a");
        book.setTodo("x", 1L);
        book.deserializeNBT(book.serializeNBT());
        assertEquals(Arrays.asList("a"), book.favourites());
        assertEquals(Arrays.asList("x"), book.todoKeys());
    }

    @Test
    public void readingNonsenseNbtLeavesAnEmptyBookRatherThanThrowing() {
        // This runs while the player entity is being read. A throw here is a player who
        // cannot log in, which is far worse than a book that has to be re-starred.
        PlanBook book = new PlanBook();
        book.addFavourite("a");
        book.deserializeNBT(new NBTTagCompound());
        assertTrue(book.isEmpty());
        book.deserializeNBT(null);
        assertTrue(book.isEmpty());
    }

    @Test
    public void copyFromReplacesEverythingRatherThanMerging() {
        // How a sync packet lands: the server's book is the truth, not an addition to it.
        PlanBook client = new PlanBook();
        client.addFavourite("stale");
        client.setTodo("stale", 3L);
        PlanBook server = new PlanBook();
        server.addFavourite("fresh");
        client.copyFrom(server);
        assertEquals(Arrays.asList("fresh"), client.favourites());
        assertTrue(client.todoKeys().isEmpty());
    }
}
