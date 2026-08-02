package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Inverting a graph key back to an {@link ItemStack}, which is the half of #19 Phase 4 that
 * can be tested without a JEI runtime.
 *
 * WHY IT IS WORTH TESTING HARD. The digest in a key is a one-way hash, so the inversion is a
 * lookup rather than a computation -- and a lookup built from a key format that has drifted
 * from the writer's fails by returning NOTHING, silently, for exactly the NBT-bearing items
 * the discriminator exists to tell apart. The graph would look fine, the plan would look
 * fine, and clicking a bee would do nothing.
 *
 * The stacks are hand-built rather than taken from JEI, which is the whole reason any of this
 * is reachable in a unit test: {@code StackIndex} takes its stacks as an argument and imports
 * no JEI type.
 */
public class StackIndexTest {

    @BeforeClass
    public static void bootstrap() {
        // Item.REGISTRY has to exist before any ItemStack can name itself. Same reason
        // DigestFixtureTest does it.
        Bootstrap.register();
    }

    private static ItemStack tagged(net.minecraft.item.Item item, String tag, String value) {
        ItemStack stack = new ItemStack(item);
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString(tag, value);
        stack.setTagCompound(nbt);
        return stack;
    }

    private static ItemStack damaged(net.minecraft.item.Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.setItemDamage(damage);
        return stack;
    }

    // -- the key format itself ---------------------------------------------------------------

    @Test
    public void aPlainStackKeysAsItsRegistryNameAndNothingElse() {
        assertEquals("minecraft:stick", DumpCommand.stackKey(new ItemStack(Items.STICK)));
        assertEquals("minecraft:stick", DumpCommand.baseStackKey(new ItemStack(Items.STICK)));
    }

    @Test
    public void aMetaAppearsOnlyWhenItIsNotZero() {
        assertEquals("minecraft:iron_axe:187",
                DumpCommand.stackKey(damaged(Items.IRON_AXE, 187)));
        assertEquals("minecraft:iron_axe", DumpCommand.stackKey(damaged(Items.IRON_AXE, 0)));
    }

    @Test
    public void nbtAppendsADigestAndTheBaseFormDeliberatelyDoesNot() {
        ItemStack bee = tagged(Items.STICK, "Species", "Forest");
        String key = DumpCommand.stackKey(bee);
        assertTrue(key, key.startsWith("minecraft:stick#"));
        // A catalyst is a claim about an ITEM, not one NBT state of it, so the base form is a
        // separate entry point rather than the same one with a flag.
        assertEquals("minecraft:stick", DumpCommand.baseStackKey(bee));
        // Two different species are two different keys -- the whole point of discriminating.
        assertFalse(key.equals(DumpCommand.stackKey(tagged(Items.STICK, "Species", "Meadows"))));
    }

    @Test
    public void anEmptyStackMintsNoKeyAtAll() {
        // A key for nothing would look real everywhere downstream.
        assertNull(DumpCommand.stackKey(ItemStack.EMPTY));
        assertNull(DumpCommand.baseStackKey(ItemStack.EMPTY));
        assertNull(DumpCommand.stackKey(null));
    }

    // -- the inversion -------------------------------------------------------------------------

    @Test
    public void anExactlyListedStackResolvesToItself() {
        ItemStack forest = tagged(Items.STICK, "Species", "Forest");
        ItemStack meadows = tagged(Items.STICK, "Species", "Meadows");
        GraphBuilder b = new GraphBuilder();
        int forestKey = b.key(DumpCommand.stackKey(forest));
        int meadowsKey = b.key(DumpCommand.stackKey(meadows));
        RecipeGraph graph = b.build();

        StackIndex index = StackIndex.build(graph, Arrays.asList(forest, meadows));
        assertSame(forest, index.stackFor(forestKey));
        assertSame(meadows, index.stackFor(meadowsKey));
        assertEquals(2, index.exactCount());
        assertEquals(0, index.baseCount());
    }

    @Test
    public void anNbtVariantJeiNeverListedFallsBackToTheBaseItem() {
        // JEI's item list carries one entry per registered item, not one per NBT state, so a
        // variant a recipe produces frequently has no entry of its own. Showing the base item
        // is far better than showing nothing.
        ItemStack plain = new ItemStack(Items.STICK);
        GraphBuilder b = new GraphBuilder();
        int variant = b.key(DumpCommand.stackKey(tagged(Items.STICK, "Species", "Forest")));
        int base = b.key("minecraft:stick");
        RecipeGraph graph = b.build();

        StackIndex index = StackIndex.build(graph, Arrays.asList(plain));
        assertSame(plain, index.stackFor(base));
        assertSame(plain, index.stackFor(variant));
        assertEquals(1, index.exactCount());
        assertEquals(1, index.baseCount());
    }

    @Test
    public void aWornToolFallsBackToThePristineOneTheItemListActuallyHolds() {
        // JEI lists a pristine Iron Axe; the graph may hold `minecraft:iron_axe:187`. Those
        // are the same item at different wear, and the graph says so through its damageable
        // table rather than through a guess at the shape of the key.
        ItemStack pristine = new ItemStack(Items.IRON_AXE);
        GraphBuilder b = new GraphBuilder();
        int stem = b.key("minecraft:iron_axe");
        b.damageable(stem, Items.IRON_AXE.getMaxDamage());
        int worn = b.key("minecraft:iron_axe:187");
        RecipeGraph graph = b.build();

        StackIndex index = StackIndex.build(graph, Arrays.asList(pristine));
        assertSame(pristine, index.stackFor(worn));
        assertEquals(1, index.damageCount());
    }

    @Test
    public void aMetaThatIsARealSubtypeIsNOTCollapsedOntoItsBase() {
        // The mirror of the case above, and the reason the gate is the pack's damageable
        // table: `chisel:lapis:3` is a genuinely different block from `chisel:lapis`, so
        // resolving it to the base would show the player the wrong item and look right.
        ItemStack plain = new ItemStack(Items.STICK);
        GraphBuilder b = new GraphBuilder();
        b.key("minecraft:stick");
        int subtype = b.key("minecraft:stick:3");
        RecipeGraph graph = b.build();

        StackIndex index = StackIndex.build(graph, Arrays.asList(plain));
        assertNull(index.stackFor(subtype));
        assertEquals(0, index.damageCount());
    }

    @Test
    public void aKeyNamingNoItemAtAllResolvesToNothing() {
        // A fluid, an oredict group or an essentia aspect has no ItemStack behind it, and
        // null is the ordinary answer rather than a failure.
        GraphBuilder b = new GraphBuilder();
        int fluid = b.key("fluid:water");
        int ore = b.key("ore:ingotIron");
        int absent = b.key("somemod:never_listed");
        RecipeGraph graph = b.build();

        StackIndex index = StackIndex.build(graph, Arrays.asList(new ItemStack(Items.STICK)));
        assertNull(index.stackFor(fluid));
        assertNull(index.stackFor(ore));
        assertNull(index.stackFor(absent));
        assertFalse(index.has(fluid));
    }

    @Test
    public void anOutOfRangeOrNegativeKeyIdIsAnOrdinaryMiss() {
        RecipeGraph graph = new GraphBuilder().build();
        StackIndex index = StackIndex.build(graph, new ArrayList<ItemStack>());
        assertNull(index.stackFor(-1));
        assertNull(index.stackFor(9999));
        assertNull(StackIndex.empty().stackFor(0));
        assertEquals(0, index.resolvedCount());
    }

    @Test
    public void theFirstListingOfADuplicateKeyWins() {
        // JEI can list the same logical item twice. Last-wins would make the answer depend on
        // list order for no gain.
        ItemStack first = new ItemStack(Items.STICK);
        ItemStack second = new ItemStack(Items.STICK);
        GraphBuilder b = new GraphBuilder();
        int stick = b.key("minecraft:stick");
        RecipeGraph graph = b.build();

        StackIndex index = StackIndex.build(graph, Arrays.asList(first, second));
        assertSame(first, index.stackFor(stick));
        assertEquals(1, index.exactCount());
    }

    @Test
    public void aWeakenedAnswerNeverChainsOffAnotherWeakenedOne() {
        // Both fill passes read only the EXACT listings, so a key can be at most one step
        // from something JEI really showed. Chaining -- strip a digest onto a key that had
        // itself collapsed its wear -- would make the answer two removes from what was asked
        // AND make it depend on which pass ran first.
        ItemStack pristine = new ItemStack(Items.IRON_AXE);
        GraphBuilder b = new GraphBuilder();
        int stem = b.key("minecraft:iron_axe");
        b.damageable(stem, Items.IRON_AXE.getMaxDamage());
        int worn = b.key("minecraft:iron_axe:187");
        ItemStack wornNamed = damaged(Items.IRON_AXE, 187);
        wornNamed.setTagCompound(new NBTTagCompound());
        wornNamed.getTagCompound().setString("Species", "Named");
        int wornVariant = b.key(DumpCommand.stackKey(wornNamed));
        RecipeGraph graph = b.build();

        // Only the pristine axe is listed. `:187` reaches it by collapsing wear...
        StackIndex index = StackIndex.build(graph, Arrays.asList(pristine));
        assertSame(pristine, index.stackFor(worn));
        // ...and `:187#digest` must NOT then reach it by stripping onto that filled entry.
        assertNull(index.stackFor(wornVariant));
        assertEquals(1, index.damageCount());
        assertEquals(0, index.baseCount());
    }

    @Test
    public void strippingADigestIsPreferredToCollapsingWear() {
        // Both weakenings could answer this key. The digest strip is the strictly smaller
        // claim -- a variant of THIS item, against a differently-worn copy of it -- so it
        // must win, and the counters are what prove which path ran.
        ItemStack wornExact = damaged(Items.IRON_AXE, 187);
        // A named copy of the SAME worn axe, so its key is `...:187#digest` and both
        // weakenings are available to it.
        ItemStack wornNamed = damaged(Items.IRON_AXE, 187);
        wornNamed.setTagCompound(new NBTTagCompound());
        wornNamed.getTagCompound().setString("Species", "Named");

        GraphBuilder b = new GraphBuilder();
        int stem = b.key("minecraft:iron_axe");
        b.damageable(stem, Items.IRON_AXE.getMaxDamage());
        int worn = b.key(DumpCommand.stackKey(wornExact));
        int wornVariant = b.key(DumpCommand.stackKey(wornNamed));
        RecipeGraph graph = b.build();
        assertEquals("minecraft:iron_axe:187", graph.key(worn));
        assertTrue(graph.key(wornVariant).startsWith("minecraft:iron_axe:187#"));

        List<ItemStack> listed = new ArrayList<ItemStack>();
        listed.add(wornExact);
        StackIndex index = StackIndex.build(graph, listed);
        // `minecraft:iron_axe:187#digest` -> strip the digest -> `minecraft:iron_axe:187`,
        // which IS listed. It must not go on to collapse the wear.
        assertSame(wornExact, index.stackFor(wornVariant));
        assertEquals(1, index.baseCount());
        assertEquals(0, index.damageCount());
    }
}
