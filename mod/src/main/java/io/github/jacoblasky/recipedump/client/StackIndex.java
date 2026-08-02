package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.graph.Bits;
import io.github.jacoblasky.recipedump.graph.Keys;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import net.minecraft.item.ItemStack;

/**
 * The inverse of the dump's key format: graph key id back to an {@link ItemStack}.
 *
 * WHY THIS CANNOT BE COMPUTED. A key is `mod:name[:meta][#digest]` and the digest is a
 * one-way hash of the stack's identity NBT -- that is the whole point of it, and it is what
 * lets one item id mean 46 different bees. So there is no parsing a key back into a stack;
 * the only way is to walk the same item list the dump walked, key each stack the same way,
 * and remember the pairing.
 *
 * KEYED THE SAME WAY BY CONSTRUCTION, not by convention. This calls
 * {@link DumpCommand#stackKey}, which is the one place the format is built, and #19 Phase 4
 * is when that stopped being aspirational -- three sites spelled it inline before, so an
 * inverter matched against any one of them would have worked until it silently did not.
 *
 * <h2>Three resolution steps, each a weaker claim than the last</h2>
 *
 * <ol>
 * <li>The EXACT key. A Forest Drone resolves to that drone.</li>
 * <li>The key with its digest stripped. JEI's item list carries one entry per registered
 *     item, not one per NBT state, so an NBT variant a recipe produces frequently has no
 *     entry of its own -- and showing the player the base item is far better than showing
 *     nothing.</li>
 * <li>The DAMAGE base, for a key whose meta is durability rather than a subtype. JEI lists a
 *     pristine Iron Axe and the graph may hold `minecraft:iron_axe:187`; those are the same
 *     item at different wear, and the graph says so through its damageable table rather than
 *     through a guess at the shape of the key.</li>
 * </ol>
 *
 * Resolved eagerly into one array at build time, so a lookup is a single array read on what
 * will be a GUI hover path. A key that resolves by no step at all stays null, and null is an
 * ordinary answer: a `fluid:`, `ore:` or `essentia:` key names no item at all, and neither
 * does an item this session's JEI never listed.
 *
 * NO JEI IMPORT ANYWHERE IN THIS CLASS, deliberately. It takes the stacks as an argument, so
 * it can be unit-tested against hand-built ones with no runtime -- which is the only way any
 * of Phase 4 gets tested at all.
 */
public final class StackIndex {

    private static final ItemStack[] NONE = new ItemStack[0];

    private final ItemStack[] byKeyId;
    private final int exactCount;
    private final int baseCount;
    private final int damageCount;

    private StackIndex(ItemStack[] byKeyId, int exactCount, int baseCount, int damageCount) {
        this.byKeyId = byKeyId;
        this.exactCount = exactCount;
        this.baseCount = baseCount;
        this.damageCount = damageCount;
    }

    public static StackIndex empty() {
        return new StackIndex(NONE, 0, 0, 0);
    }

    /**
     * Walks `stacks`, keys each one as the dump would, and resolves every graph key it can.
     *
     * FIRST WRITER WINS on a duplicate key, matching how the dump's own per-key sinks
     * record: JEI can list the same logical item twice, and the alternative -- last wins --
     * would make the answer depend on list order for no gain.
     */
    public static StackIndex build(RecipeGraph graph, Iterable<ItemStack> stacks) {
        ItemStack[] byKeyId = new ItemStack[graph.keyCount()];
        int exact = 0;
        for (ItemStack stack : stacks) {
            String key = DumpCommand.stackKey(stack);
            if (key == null) {
                continue;
            }
            int keyId = graph.keyId(key);
            if (keyId >= 0 && byKeyId[keyId] == null) {
                byKeyId[keyId] = stack;
                exact++;
            }
        }
        // Step two and step three, filled here rather than at lookup so the hover path stays
        // one array read.
        //
        // BOTH READ ONLY THE EXACT ENTRIES, which is why the exact set is snapshotted before
        // either runs. Letting the second pass read what the first one filled would allow a
        // key to resolve two weakenings deep -- strip a digest onto a key that had itself
        // collapsed its wear -- and the result would depend on the order the passes ran in.
        // One step from a real listing is a claim that can be explained; two is not.
        long[] exactly = Bits.ofSize(byKeyId.length);
        for (int keyId = 0; keyId < byKeyId.length; keyId++) {
            if (byKeyId[keyId] != null) {
                Bits.set(exactly, keyId);
            }
        }
        int base = fill(graph, byKeyId, exactly, false);
        int damaged = fill(graph, byKeyId, exactly, true);
        return new StackIndex(byKeyId, exact, base, damaged);
    }

    /**
     * Fills unresolved keys from a weaker form of themselves.
     *
     * `byDamage` picks which weakening: the digest stripped, or the durability collapsed.
     * They run as separate passes and in that order, because stripping a digest is a
     * strictly smaller claim than collapsing wear -- a variant of THIS item, against a
     * differently-worn copy of it -- and a key that can be answered the stronger way must
     * never be answered the weaker one.
     */
    private static int fill(RecipeGraph graph, ItemStack[] byKeyId, long[] exactly,
                            boolean byDamage) {
        int filled = 0;
        for (int keyId = 0; keyId < byKeyId.length; keyId++) {
            if (byKeyId[keyId] != null) {
                continue;
            }
            String key = graph.key(keyId);
            String weaker = byDamage ? graph.damageBase(key) : Keys.baseKey(key);
            if (weaker.equals(key)) {
                continue;
            }
            int from = graph.keyId(weaker);
            if (from >= 0 && Bits.get(exactly, from)) {
                byKeyId[keyId] = byKeyId[from];
                filled++;
            }
        }
        return filled;
    }

    /** The stack a graph key names, or null. Null is an ordinary answer -- see the class doc. */
    public ItemStack stackFor(int keyId) {
        return keyId < 0 || keyId >= byKeyId.length ? null : byKeyId[keyId];
    }

    public boolean has(int keyId) {
        return stackFor(keyId) != null;
    }

    /** Keys answered by their own exact stack. */
    public int exactCount() {
        return exactCount;
    }

    /** Keys answered only after their NBT digest was stripped. */
    public int baseCount() {
        return baseCount;
    }

    /** Keys answered only after their durability was collapsed. */
    public int damageCount() {
        return damageCount;
    }

    public int resolvedCount() {
        return exactCount + baseCount + damageCount;
    }
}
