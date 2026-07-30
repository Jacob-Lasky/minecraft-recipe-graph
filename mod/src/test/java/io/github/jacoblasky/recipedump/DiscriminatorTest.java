package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The NBT discriminator, which decides whether two stacks are the same ingredient.
 *
 * Worth testing in a real JVM against real NBT rather than only by eye: it defines part
 * of the dump schema, so a digest that is unstable across runs, or that reacts to a tag
 * it claims to ignore, silently splits one item into two graph keys. That failure looks
 * exactly like "the tool says I do not own something I own".
 */
public class DiscriminatorTest {

    @BeforeClass
    public static void bootstrap() {
        // Item registry, needed before any ItemStack can exist. Headless-safe.
        Bootstrap.register();
    }

    private static ItemStack withTag(NBTTagCompound tag) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.setTagCompound(tag);
        return stack;
    }

    private static NBTTagCompound genome(String species) {
        NBTTagCompound chromosome = new NBTTagCompound();
        chromosome.setString("UID0", species);
        chromosome.setString("UID1", species);
        NBTTagList chromosomes = new NBTTagList();
        chromosomes.appendTag(chromosome);
        NBTTagCompound gen = new NBTTagCompound();
        gen.setTag("Chromosomes", chromosomes);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("Genome", gen);
        return tag;
    }

    @Test
    public void aStackWithNoNbtHasNoDiscriminator() {
        assertNull(DumpCommand.discriminator(new ItemStack(Items.STICK)));
        assertNull(DumpCommand.discriminator(withTag(new NBTTagCompound())));
    }

    @Test
    public void twoSpeciesGetTwoDifferentDigests() {
        // The whole point: every bee in the pack was one key because this did not exist.
        String forest = DumpCommand.discriminator(withTag(genome("forestry.speciesForest")));
        String meadows = DumpCommand.discriminator(withTag(genome("forestry.speciesMeadows")));
        assertNotEquals(forest, meadows);
        assertTrue(forest.matches("[0-9a-f]{12}"));
    }

    @Test
    public void theSameSpeciesGetsTheSameDigestEveryTime() {
        assertEquals(DumpCommand.discriminator(withTag(genome("forestry.speciesForest"))),
                     DumpCommand.discriminator(withTag(genome("forestry.speciesForest"))));
    }

    @Test
    public void keyInsertionOrderDoesNotChangeTheDigest() {
        // NBTTagCompound is backed by a HashMap; without sorting, the digest would be an
        // implementation detail and two dumps could disagree.
        NBTTagCompound a = new NBTTagCompound();
        a.setString("alpha", "1");
        a.setInteger("beta", 2);
        a.setString("gamma", "3");
        NBTTagCompound b = new NBTTagCompound();
        b.setString("gamma", "3");
        b.setInteger("beta", 2);
        b.setString("alpha", "1");
        assertEquals(DumpCommand.discriminator(withTag(a)),
                     DumpCommand.discriminator(withTag(b)));
    }

    @Test
    public void cosmeticTagsAreIgnored() {
        // A repaired pickaxe and a fresh one are the same ingredient.
        NBTTagCompound plain = genome("forestry.speciesForest");
        NBTTagCompound decorated = genome("forestry.speciesForest");
        decorated.setInteger("RepairCost", 7);
        decorated.setInteger("HideFlags", 63);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "My Favourite Bee");
        decorated.setTag("display", display);
        assertEquals(DumpCommand.discriminator(withTag(plain)),
                     DumpCommand.discriminator(withTag(decorated)));
    }

    @Test
    public void aStackWhoseOnlyNbtIsCosmeticStaysTheBareKey() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("RepairCost", 3);
        assertNull(DumpCommand.discriminator(withTag(tag)));
    }

    @Test
    public void typesAreDistinguishedRatherThanStringified() {
        // "5" the string and 5 the int are different data and must not collide.
        NBTTagCompound asString = new NBTTagCompound();
        asString.setString("v", "5");
        NBTTagCompound asInt = new NBTTagCompound();
        asInt.setInteger("v", 5);
        NBTTagCompound asByte = new NBTTagCompound();
        asByte.setByte("v", (byte) 5);
        String s = DumpCommand.discriminator(withTag(asString));
        String i = DumpCommand.discriminator(withTag(asInt));
        String b = DumpCommand.discriminator(withTag(asByte));
        assertNotEquals(s, i);
        assertNotEquals(i, b);
    }

    @Test
    public void structureCannotBeForgedOutOfStringContents() {
        // Strings are length-prefixed so their contents can never imitate the separators
        // around them, which is how two different trees would collide.
        NBTTagCompound one = new NBTTagCompound();
        one.setString("a", "x;b=y");
        NBTTagCompound two = new NBTTagCompound();
        two.setString("a", "x");
        two.setString("b", "y");
        assertNotEquals(DumpCommand.discriminator(withTag(one)),
                        DumpCommand.discriminator(withTag(two)));
    }

    private static NBTTagList strings(String... values) {
        NBTTagList list = new NBTTagList();
        for (String v : values) {
            list.appendTag(new NBTTagString(v));
        }
        return list;
    }

    private static NBTTagCompound listUnder(String key, String... values) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(key, strings(values));
        return tag;
    }

    /** One `{id, lvl}` entry, the shape 1.12.2 uses for `ench` and `StoredEnchantments`. */
    private static NBTTagList enchantment(int id, int level) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setShort("id", (short) id);
        entry.setShort("lvl", (short) level);
        NBTTagList list = new NBTTagList();
        list.appendTag(entry);
        return list;
    }

    @Test
    public void listOrderMatters() {
        // Still true for any tag NOT in SORTED_LIST_TAGS, and that is the whole reason the
        // #80 fix is a named list rather than a global sort. If this ever starts passing
        // only because everything is sorted, the digest has begun merging items that differ.
        assertNotEquals(DumpCommand.discriminator(withTag(listUnder("l", "one", "two"))),
                        DumpCommand.discriminator(withTag(listUnder("l", "two", "one"))));
    }

    @Test
    public void theOrderOfASortedListTagDoesNotMatter() {
        // #80: `Special` permutes per JVM run, so two dumps of an unchanged pack gave the
        // same tool two keys. Measured order-only on 9,359 forced pairs.
        for (String tag : DumpCommand.SORTED_LIST_TAGS) {
            assertEquals(tag + " is in SORTED_LIST_TAGS, so its order must not reach the key",
                         DumpCommand.discriminator(withTag(listUnder(tag, "alpha", "beta"))),
                         DumpCommand.discriminator(withTag(listUnder(tag, "beta", "alpha"))));
        }
    }

    @Test
    public void aSortedListTagIsSortedAtAnyDepth() {
        // The sort follows the NAME, not the position: the reason `Special` permutes is the
        // collection its producer iterates, which does not care where the tag ends up.
        NBTTagCompound a = new NBTTagCompound();
        a.setTag("Outer", listUnder("Special", "alpha", "beta"));
        NBTTagCompound b = new NBTTagCompound();
        b.setTag("Outer", listUnder("Special", "beta", "alpha"));
        assertEquals(DumpCommand.discriminator(withTag(a)), DumpCommand.discriminator(withTag(b)));
    }

    @Test
    public void sortingDoesNotLeakFromASortedTagToItsSiblings() {
        // The narrowness IS the fix. `canonical` threads one flag down the tree, so the
        // obvious bug is turning it on for the compound that CONTAINS `Special` rather than
        // for `Special` itself -- which would sort everything and silently merge stacks that
        // differ only in an order-semantic list. That is the global sort the javadoc rejects.
        NBTTagCompound base = listUnder("Special", "alpha", "beta");
        base.setTag("l", strings("one", "two"));
        NBTTagCompound specialMoved = listUnder("Special", "beta", "alpha");
        specialMoved.setTag("l", strings("one", "two"));
        NBTTagCompound siblingMoved = listUnder("Special", "alpha", "beta");
        siblingMoved.setTag("l", strings("two", "one"));

        assertEquals("only the sorted tag moved, so this is one key",
                     DumpCommand.discriminator(withTag(base)),
                     DumpCommand.discriminator(withTag(specialMoved)));
        assertNotEquals("the SIBLING moved, and its order is still information",
                        DumpCommand.discriminator(withTag(base)),
                        DumpCommand.discriminator(withTag(siblingMoved)));
    }

    @Test
    public void aListInsideASortedListTagIsSortedToo() {
        // The trace's "u" field, which is what measured `Special` as order-only, sorts the
        // whole subtree. A fix that sorted only the outermost list would be claiming
        // something the measurement did not license.
        NBTTagCompound a = new NBTTagCompound();
        NBTTagList outerA = new NBTTagList();
        outerA.appendTag(strings("a", "b"));
        outerA.appendTag(strings("c"));
        a.setTag("Special", outerA);
        NBTTagCompound b = new NBTTagCompound();
        NBTTagList outerB = new NBTTagList();
        outerB.appendTag(strings("b", "a"));
        outerB.appendTag(strings("c"));
        b.setTag("Special", outerB);
        assertEquals(DumpCommand.discriminator(withTag(a)), DumpCommand.discriminator(withTag(b)));
    }

    @Test
    public void enchantmentsDoNotChangeWhatAnItemIs() {
        // #63, and #80 measured a second reason: 1.12.2 stores `ench` as {id, lvl} with a
        // registry-allocated id, so the SAME enchantment serialises differently next launch.
        // 1,335 forced pairs churned on it across just five distinct digest transitions.
        NBTTagCompound plain = genome("forestry.speciesForest");
        NBTTagCompound enchanted = genome("forestry.speciesForest");
        enchanted.setTag("ench", enchantment(16, 3));
        assertEquals(DumpCommand.discriminator(withTag(plain)),
                     DumpCommand.discriminator(withTag(enchanted)));

        // And a DIFFERENT enchantment is still the same item, which is the part that makes
        // the per-launch id harmless rather than merely less common.
        NBTTagCompound otherwise = genome("forestry.speciesForest");
        otherwise.setTag("ench", enchantment(99, 1));
        assertEquals(DumpCommand.discriminator(withTag(plain)),
                     DumpCommand.discriminator(withTag(otherwise)));
    }

    @Test
    public void aStackWhoseOnlyNbtIsEnchantmentsStaysTheBareKey() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("ench", enchantment(16, 1));
        assertNull(DumpCommand.discriminator(withTag(tag)));
    }

    @Test
    public void storedEnchantmentsAreDELIBERATELYStillPartOfIdentity() {
        // NOT an oversight, and the reason is recorded beside COSMETIC_TAGS: an enchanted
        // BOOK's identity IS its enchantment, so a Sharpness V book and a Fortune III book
        // are different ingredients. #80 also found no evidence it churns -- 4 observations,
        // all in ambiguous pairings, zero on forced ones. This test exists so that stripping
        // it becomes a deliberate act with a measurement behind it rather than a tidy-up.
        NBTTagCompound sharpness = new NBTTagCompound();
        sharpness.setTag("StoredEnchantments", enchantment(16, 5));
        NBTTagCompound fortune = new NBTTagCompound();
        fortune.setTag("StoredEnchantments", enchantment(35, 3));

        assertNotEquals(DumpCommand.discriminator(withTag(sharpness)),
                        DumpCommand.discriminator(withTag(fortune)));
    }

    @Test
    public void theTwoTagListsDoNotOverlap() {
        // A name on both lists makes the sort dead code on a tag nothing digests, while
        // reading in review as a live claim about that tag's order.
        for (String sorted : DumpCommand.SORTED_LIST_TAGS) {
            for (String cosmetic : DumpCommand.COSMETIC_TAGS) {
                assertNotEquals(sorted, cosmetic);
            }
        }
    }

    @Test
    public void floatsGoInByTheirBitsSoTheyCannotDriftBetweenLanguages() {
        NBTTagCompound a = new NBTTagCompound();
        a.setFloat("v", 0.1f);
        NBTTagCompound b = new NBTTagCompound();
        b.setDouble("v", 0.1d);
        assertNotEquals(DumpCommand.discriminator(withTag(a)),
                        DumpCommand.discriminator(withTag(b)));
    }
}
