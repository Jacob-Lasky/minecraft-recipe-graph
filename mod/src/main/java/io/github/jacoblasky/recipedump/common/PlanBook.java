package io.github.jacoblasky.recipedump.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * One player's favourites and TODO list, in graph keys.
 *
 * A KEY, NOT AN ItemStack. The whole planner speaks the graph's key strings -- `iron_ingot`
 * discriminated as `minecraft:iron_ingot`, a fluid as `fluid:water`, an NBT variant as
 * `<id>#<digest>` -- and 1,198 of this pack's fluids have no item at all. Storing stacks
 * would make half the plannable things unstorable and would re-key the book every time the
 * digest format moved.
 *
 * Attached to the player rather than kept in world data, which is Jake's call: the book
 * travels with the character, so it survives a world swap and follows him to a different
 * server.
 */
public final class PlanBook implements INBTSerializable<NBTTagCompound> {

    /**
     * Caps, and they are about the PACKET rather than about memory.
     *
     * The whole book is synced as one custom payload, and 1.12.2 drops a payload over
     * 1,048,576 bytes by disconnecting the player. A key runs to about 64 characters, so 256
     * favourites plus 256 TODO rows is on the order of 40 kB and cannot get near that however
     * enthusiastic the player is. Without a cap the client can grow a book until the packet
     * that carries it kicks him, and the symptom is a disconnect with no obvious cause.
     */
    public static final int MAX_FAVOURITES = 256;
    public static final int MAX_TODO = 256;

    private static final String TAG_FAVOURITES = "favourites";
    private static final String TAG_TODO = "todo";
    private static final String TAG_KEY = "key";
    private static final String TAG_QTY = "qty";

    /** Insertion-ordered: favourites read back in the order they were starred. */
    private final Set<String> favourites = new LinkedHashSet<String>();

    /**
     * Insertion-ordered, key to quantity.
     *
     * QUANTITY IS A LONG. Fluids are counted in mB and the reference pack routinely plans six
     * and seven figures of them -- one Borax plan draws 934,400 mB of water -- so an int is
     * about four Enchanted Greenhouse runs away from overflowing, silently and negatively.
     */
    private final Map<String, Long> todo = new LinkedHashMap<String, Long>();

    /**
     * How many times this book has changed. Strictly increasing, never reset.
     *
     * FOR AN OPEN WINDOW TO NOTICE, and it is the same counter `PlannerService.generation`
     * is, for the same reason: the book is written by a sync packet arriving on a netty IO
     * thread and read by a GUI on the client thread, so the GUI polls rather than being
     * called back. Without it, "Add to TODO" in the node menu sends its packet, the server
     * answers with a whole-book sync, and the footer under the tree keeps showing the old
     * count -- a control that did work and looks like it did not.
     *
     * BUMPED ONLY WHEN SOMETHING REALLY CHANGED. Every mutator here already returns whether
     * it changed the book, because the server uses that to decide whether to sync; the
     * counter follows the same answer, so a rejected edit (the book is capped) does not
     * redraw a window to show nothing new.
     *
     * NOT volatile, and deliberately: the writer is `ClientProxy.applyPlanBookSync`, which
     * hands the deserialise to `Minecraft.addScheduledTask` precisely so it runs on the
     * client thread. One thread writes and the same thread reads. A `volatile` here would
     * suggest otherwise and invite somebody to write from the IO thread after all.
     */
    private int revision;

    /** See {@link #revision}. */
    public int revision() {
        return revision;
    }

    /** Starred keys, oldest first. Unmodifiable; edit through this class. */
    public List<String> favourites() {
        return Collections.unmodifiableList(new ArrayList<String>(favourites));
    }

    public boolean isFavourite(String key) {
        return favourites.contains(key);
    }

    /** @return true when the book changed, so a caller knows whether to sync. */
    public boolean addFavourite(String key) {
        if (isBlank(key) || favourites.size() >= MAX_FAVOURITES) {
            return false;
        }
        return changed(favourites.add(key));
    }

    public boolean removeFavourite(String key) {
        return changed(key != null && favourites.remove(key));
    }

    /** TODO keys, oldest first. */
    public List<String> todoKeys() {
        return Collections.unmodifiableList(new ArrayList<String>(todo.keySet()));
    }

    /** The wanted quantity for a key, or 0 when it is not on the list. */
    public long todoQuantity(String key) {
        Long qty = todo.get(key);
        return qty == null ? 0L : qty.longValue();
    }

    /**
     * Set a wanted quantity, or remove the row when it is zero or less.
     *
     * ZERO REMOVES rather than storing a row that asks for nothing. That is what makes the
     * edit packet one message instead of two: a client that wants a row gone sends a
     * quantity of 0, and nothing has to encode "delete" as a separate verb.
     */
    public boolean setTodo(String key, long quantity) {
        if (isBlank(key)) {
            return false;
        }
        if (quantity <= 0L) {
            return changed(todo.remove(key) != null);
        }
        if (!todo.containsKey(key) && todo.size() >= MAX_TODO) {
            return false;
        }
        Long previous = todo.put(key, Long.valueOf(quantity));
        return changed(previous == null || previous.longValue() != quantity);
    }

    /** Record that the book changed, and pass the answer through. */
    private boolean changed(boolean didChange) {
        if (didChange) {
            revision++;
        }
        return didChange;
    }

    public boolean isEmpty() {
        return favourites.isEmpty() && todo.isEmpty();
    }

    /** Replace everything with another book's contents. How a sync packet lands. */
    public void copyFrom(PlanBook other) {
        favourites.clear();
        favourites.addAll(other.favourites);
        todo.clear();
        todo.putAll(other.todo);
        // UNCONDITIONALLY, unlike the mutators. A wholesale replacement cannot cheaply say
        // whether anything differs, and the cost of being wrong runs one way only: a spurious
        // bump redraws an identical panel, a missing one leaves a window showing a book the
        // player has changed.
        revision++;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList stars = new NBTTagList();
        for (String key : favourites) {
            stars.appendTag(new NBTTagString(key));
        }
        root.setTag(TAG_FAVOURITES, stars);
        NBTTagList rows = new NBTTagList();
        for (Map.Entry<String, Long> entry : todo.entrySet()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString(TAG_KEY, entry.getKey());
            row.setLong(TAG_QTY, entry.getValue().longValue());
            rows.appendTag(row);
        }
        root.setTag(TAG_TODO, rows);
        return root;
    }

    /**
     * Read a book back, tolerating anything.
     *
     * DELIBERATELY LENIENT, because the alternative is worse than data loss: this runs while
     * the player entity is being read, and a throw there is a player who cannot log in. A
     * blank key or a nonsense quantity is dropped and everything else survives. There is no
     * version tag and none is wanted -- the shape may change freely, and a book that reads
     * back empty costs a re-star, not a world.
     */
    @Override
    public void deserializeNBT(NBTTagCompound root) {
        favourites.clear();
        todo.clear();
        // Same reasoning as `copyFrom`: this IS the sync landing, and the clear above has
        // already changed the book whatever the payload turns out to hold.
        revision++;
        if (root == null) {
            return;
        }
        NBTTagList stars = root.getTagList(TAG_FAVOURITES, 8 /* NBT_STRING */);
        for (int i = 0; i < stars.tagCount(); i++) {
            addFavourite(stars.getStringTagAt(i));
        }
        NBTTagList rows = root.getTagList(TAG_TODO, 10 /* NBT_COMPOUND */);
        for (int i = 0; i < rows.tagCount(); i++) {
            NBTTagCompound row = rows.getCompoundTagAt(i);
            setTodo(row.getString(TAG_KEY), row.getLong(TAG_QTY));
        }
    }

    private static boolean isBlank(String key) {
        return key == null || key.trim().isEmpty();
    }
}
