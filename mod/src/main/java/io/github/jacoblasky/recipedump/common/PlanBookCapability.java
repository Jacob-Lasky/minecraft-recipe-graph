package io.github.jacoblasky.recipedump.common;

import javax.annotation.Nullable;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Attaches a {@link PlanBook} to every player and keeps it across the ways a player entity
 * gets replaced.
 *
 * Capability rather than world-saved data, which is Jake's call and the right one: the book
 * is per-player state, so attaching it to the player means vanilla's own entity NBT persists
 * it, it comes back on relog for free, and it follows the character rather than the save.
 */
@Mod.EventBusSubscriber(modid = RecipeDumpMod.MODID)
public final class PlanBookCapability {

    public static final ResourceLocation KEY =
            new ResourceLocation(RecipeDumpMod.MODID, "plan_book");

    /**
     * Injected by Forge once {@link #register} has run. Null in a plain JUnit JVM, which has
     * no FML to do the injecting -- which is why {@link Storage} takes the capability as a
     * parameter it ignores, so the serialisation path is testable without one.
     */
    @CapabilityInject(PlanBook.class)
    public static Capability<PlanBook> PLAN_BOOK = null;

    private PlanBookCapability() {
    }

    /** Called from the proxy in preInit; must run before any player is attached to. */
    public static void register() {
        CapabilityManager.INSTANCE.register(PlanBook.class, new Storage(), PlanBook.class);
    }

    /** The player's book, or null before the capability is registered. */
    @Nullable
    public static PlanBook of(EntityPlayer player) {
        if (PLAN_BOOK == null || player == null) {
            return null;
        }
        return player.getCapability(PLAN_BOOK, null);
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof EntityPlayer) {
            event.addCapability(KEY, new Provider());
        }
    }

    /**
     * Carry the book across death and the End portal.
     *
     * WITHOUT THIS THE BOOK IS LOST ON DEATH, and only on death, which is the worst shape of
     * bug to find: it survives relog, world reload and dimension travel, because those reuse
     * the player's serialised NBT, and then a creeper deletes it. `PlayerEvent.Clone` is the
     * only hook where the old entity is still reachable.
     *
     * Copied on `wasDeath` as well as on the return from the End, because the alternative --
     * copying only when `wasDeath` -- loses it on the other one.
     */
    @SubscribeEvent
    public static void copyOnClone(PlayerEvent.Clone event) {
        PlanBook old = of(event.getOriginal());
        PlanBook fresh = of(event.getEntityPlayer());
        if (old != null && fresh != null) {
            fresh.copyFrom(old);
        }
    }

    /**
     * The attached instance plus its NBT, in one object.
     *
     * `ICapabilitySerializable` rather than a bare provider: that is the interface Forge looks
     * for when it writes the entity, and a provider that only implements `ICapabilityProvider`
     * attaches fine, works all session, and silently saves nothing.
     */
    public static final class Provider implements ICapabilitySerializable<NBTTagCompound> {

        private final PlanBook book = new PlanBook();

        public PlanBook book() {
            return book;
        }

        /**
         * `PLAN_BOOK != null` FIRST, and it is not defensive noise.
         *
         * Every provider on the entity is asked about every capability, and a caller passing
         * null is common enough that Forge's own code guards for it. Without the null check
         * the comparison is `null == null` whenever the injection has not happened, so this
         * provider answers yes to ANY query -- and `getCapability` then hands a `PlanBook`
         * back to a mod that asked for an item handler. Caught by
         * `withNoCapabilityInjectedTheProviderOffersNothingRatherThanThrowing`.
         */
        @Override
        public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
            return PLAN_BOOK != null && capability == PLAN_BOOK;
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked")
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
            return hasCapability(capability, facing) ? (T) book : null;
        }

        @Override
        public NBTTagCompound serializeNBT() {
            return book.serializeNBT();
        }

        @Override
        public void deserializeNBT(NBTTagCompound nbt) {
            book.deserializeNBT(nbt);
        }
    }

    /**
     * Forge's storage hook. It exists because the capability API demands one, and it delegates
     * straight to the book, which is where the format actually lives.
     *
     * IT IGNORES THE `Capability` ARGUMENT ON PURPOSE, and that is what makes the format
     * testable: a JUnit JVM has no FML to inject `PLAN_BOOK`, so a storage that dereferenced
     * it could only be exercised in a running game. Passing null here is legal and covered.
     */
    public static final class Storage implements Capability.IStorage<PlanBook> {

        @Override
        public NBTBase writeNBT(Capability<PlanBook> capability, PlanBook instance,
                                EnumFacing side) {
            return instance.serializeNBT();
        }

        @Override
        public void readNBT(Capability<PlanBook> capability, PlanBook instance, EnumFacing side,
                            NBTBase nbt) {
            instance.deserializeNBT((NBTTagCompound) nbt);
        }
    }
}
