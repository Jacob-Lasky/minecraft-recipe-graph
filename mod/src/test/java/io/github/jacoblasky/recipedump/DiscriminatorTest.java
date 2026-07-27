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

    @Test
    public void listOrderMatters() {
        NBTTagCompound a = new NBTTagCompound();
        NBTTagList first = new NBTTagList();
        first.appendTag(new NBTTagString("one"));
        first.appendTag(new NBTTagString("two"));
        a.setTag("l", first);
        NBTTagCompound b = new NBTTagCompound();
        NBTTagList second = new NBTTagList();
        second.appendTag(new NBTTagString("two"));
        second.appendTag(new NBTTagString("one"));
        b.setTag("l", second);
        assertNotEquals(DumpCommand.discriminator(withTag(a)),
                        DumpCommand.discriminator(withTag(b)));
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
