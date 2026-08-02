package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

/**
 * The capability wrapper around a {@link PlanBook}: the bit Forge writes into the player.
 *
 * NO FML HERE, WHICH IS WHY THE CAPABILITY ARGUMENT IS IGNORED IN THE PRODUCTION CODE. A
 * plain JUnit JVM never runs `@CapabilityInject`, so `PLAN_BOOK` is null and a `Storage` that
 * dereferenced it could only be exercised inside a running game -- which is exactly where
 * this repository cannot go. Passing null is legal and is what these tests do.
 */
public class PlanBookCapabilityTest {

    private static PlanBook populated() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.setTodo("fluid:water", 934_400L);
        return book;
    }

    @Test
    public void theProviderSerialisesAndReadsBackTheBookItHolds() {
        PlanBookCapability.Provider provider = new PlanBookCapability.Provider();
        provider.book().addFavourite("a");
        provider.book().setTodo("b", 7L);

        NBTTagCompound saved = provider.serializeNBT();

        PlanBookCapability.Provider loaded = new PlanBookCapability.Provider();
        loaded.deserializeNBT(saved);
        assertEquals(Arrays.asList("a"), loaded.book().favourites());
        assertEquals(7L, loaded.book().todoQuantity("b"));
    }

    @Test
    public void theProviderKeepsOneBookRatherThanHandingOutCopies() {
        // The capability contract is that `getCapability` returns the SAME instance every
        // time; a provider that made a copy would accept edits and then save the original.
        PlanBookCapability.Provider provider = new PlanBookCapability.Provider();
        assertSame(provider.book(), provider.book());
    }

    @Test
    public void theStorageHookIsTheSameFormatAsTheBookItself() {
        // Two paths write this data -- Forge's IStorage and the provider's own
        // INBTSerializable -- and if they ever disagreed, which one ran would decide whether
        // a save could be read. They must be byte-identical, not merely both valid.
        PlanBook book = populated();
        PlanBookCapability.Storage storage = new PlanBookCapability.Storage();
        NBTTagCompound viaStorage = (NBTTagCompound) storage.writeNBT(null, book, null);
        assertEquals(book.serializeNBT(), viaStorage);

        PlanBook read = new PlanBook();
        storage.readNBT(null, read, null, viaStorage);
        assertEquals(book.favourites(), read.favourites());
        assertEquals(934_400L, read.todoQuantity("fluid:water"));
    }

    @Test
    public void withNoCapabilityInjectedTheProviderOffersNothingRatherThanThrowing() {
        // `PLAN_BOOK` is null outside a running game. Anything that asks this provider for a
        // capability here is asking for some OTHER mod's, and must get a clean null.
        assertNull("no FML in a unit test, so nothing was injected",
                   PlanBookCapability.PLAN_BOOK);
        PlanBookCapability.Provider provider = new PlanBookCapability.Provider();
        assertFalse(provider.hasCapability(null, null));
        assertNull(provider.getCapability(null, null));
    }

    /**
     * Registration itself, which is reflective and therefore has nothing compile-time to
     * catch it.
     *
     * `CapabilityManager.register` is handed a storage and a default IMPLEMENTATION class,
     * instantiates the latter by reflection and casts it to the former's type parameter. A
     * mismatch -- an interface, a class with no visible no-arg constructor -- is an exception
     * during preInit, on a server, with the mod named but not the reason. Running it here
     * costs nothing and is the whole of what `CommonProxy.preInit` does for the capability.
     */
    @Test
    public void registeringTheCapabilityDoesNotThrow() {
        PlanBookCapability.register();
    }

    @Test
    public void theCapabilityKeyIsNamespacedToThisMod() {
        // Capability keys share one namespace across every mod on the server, so an
        // unprefixed one is a collision waiting for whichever mod also called it "plan_book".
        assertEquals("mcrecipedump", PlanBookCapability.KEY.getNamespace());
        assertEquals("plan_book", PlanBookCapability.KEY.getPath());
    }

    @Test
    public void aBookCopiedOnRespawnCarriesEverythingAndSharesNothing() {
        // What `copyOnClone` does, on the two objects it does it to. Without the copy the
        // book survives relog and dimension travel and is lost only on death, which is the
        // hardest version of this bug to notice.
        PlanBook died = populated();
        PlanBook respawned = new PlanBook();
        respawned.copyFrom(died);
        assertEquals(died.favourites(), respawned.favourites());
        assertEquals(died.todoQuantity("fluid:water"), respawned.todoQuantity("fluid:water"));

        // Separate storage, not a shared reference: the old entity is discarded right after.
        respawned.addFavourite("added-after-respawn");
        assertFalse(died.isFavourite("added-after-respawn"));
        assertTrue(respawned.isFavourite("added-after-respawn"));
    }
}
