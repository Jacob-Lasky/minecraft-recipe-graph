package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The key grammar, asserted against the cases `recipegraph/model.py` documents.
 *
 * These are not tests of Java string handling; they are tests that the Java side reads a key
 * the SAME WAY the python oracle does. Every case here is one the python docstrings call out
 * as having been got wrong once, so a failure means the two implementations have drifted and
 * a plan computed on each side would describe different items.
 */
public class KeysTest {

    @Test
    public void anIdWithNoNamespaceIsAssumedVanilla() {
        assertEquals("minecraft:stone", Keys.normKey("stone", 0));
        assertEquals("modid:thing", Keys.normKey("modid:thing", 0));
    }

    @Test
    public void metaZeroIsOmittedAndTheWildcardMetaBecomesAStar() {
        assertEquals("minecraft:wool", Keys.normKey("minecraft:wool", 0));
        assertEquals("minecraft:wool:3", Keys.normKey("minecraft:wool", 3));
        assertEquals("minecraft:wool:*", Keys.normKey("minecraft:wool", Keys.WILDCARD_META));
    }

    @Test
    public void anEmptyIdMintsNoKeyAtAll() {
        // A phantom `minecraft:` key is worse than a dropped ingredient, because it looks
        // real to everything downstream.
        assertNull(Keys.normKey(null, 0));
        assertNull(Keys.normKey("   ", 0));
        assertEquals("minecraft:stone", Keys.normKey("  stone  ", 0));
    }

    @Test
    public void aDiscriminatorIsStrippedButTheMetaIsNot() {
        assertEquals("forestry:can:1", Keys.baseKey("forestry:can:1#48a337d94489"));
        assertEquals("48a337d94489", Keys.discriminator("forestry:can:1#48a337d94489"));
        assertNull(Keys.discriminator("forestry:can:1"));
        // Collapsing the meta too would merge `tconstruct:ingots:0` with `:3` into one
        // pseudo-item that appears to melt into every molten metal in the pack.
        assertEquals("tconstruct:ingots:3", Keys.baseKey("tconstruct:ingots:3"));
    }

    @Test
    public void aDiscriminatedKeyReadsAsMetaZeroBecauseSplitKeyDoesNotStripItFirst() {
        // This is what stops an NBT variant inheriting its base's wildcard recipes, so it is
        // behaviour rather than an accident of the parser. See `Keys.metaOf`.
        assertEquals(0, Keys.metaOf("forestry:can:1#48a337d94489"));
        assertEquals(1, Keys.metaOf("forestry:can:1"));
        assertEquals(0, Keys.metaOf("minecraft:stone"));
        assertEquals(Keys.META_WILDCARD, Keys.metaOf("minecraft:wool:*"));
        assertEquals(Keys.META_NONE, Keys.metaOf("fluid:water"));
    }

    @Test
    public void withoutMetaLeavesANonItemKeyAloneBecauseItsColonIsAPrefix() {
        assertEquals("minecraft:wool", Keys.withoutMeta("minecraft:wool:3"));
        assertEquals("minecraft:wool", Keys.withoutMeta("minecraft:wool:*"));
        assertEquals("minecraft:stone", Keys.withoutMeta("minecraft:stone"));
        // Stemming `fluid:water` to `fluid` would make every fluid a metadata sibling of
        // every other.
        assertEquals("fluid:water", Keys.withoutMeta("fluid:water"));
        assertNull(Keys.itemStem("fluid:water"));
        assertEquals("forestry:can", Keys.itemStem("forestry:can:1#48a337d94489"));
    }

    @Test
    public void theRegistryPathDropsTheModidTheMetaAndTheDiscriminator() {
        // `key.split(":")[-1]` was the original bug: it hands `mod:thing:3` back as "3".
        assertEquals("thing", Keys.pathOf("mod:thing:3"));
        assertEquals("can", Keys.pathOf("forestry:can:1#48a337d94489"));
        assertEquals("water", Keys.pathOf("fluid:water"));
    }

    @Test
    public void onlyTwelveLowercaseHexDigitsCountAsADigest() {
        assertTrue(Keys.isDigest("48a337d94489"));
        assertFalse(Keys.isDigest("48A337D94489"));
        assertFalse(Keys.isDigest("48a337d9448"));
        assertFalse(Keys.isDigest("perditio"));
        assertFalse(Keys.isDigest(null));
    }

    @Test
    public void aDigestVariantIsLabelledAsOneAndAWordIsCapitalised() {
        assertEquals("variant 48a337", Keys.variantLabel("48a337d94489"));
        // The word branch is for a have-file written before the reader moved onto the dump's
        // own digest. Keep it readable rather than rendering "variant perdit".
        assertEquals("Perditio", Keys.variantLabel("perditio"));
    }

    @Test
    public void anUnlocalizedLabelIsDotSeparatedWithNoSpaces() {
        assertTrue(Keys.isUnlocalized("tile.null.name"));
        assertTrue(Keys.isUnlocalized("parttype.foo.name"));
        // Half localized, and keeping it beats replacing it with a registry path.
        assertFalse(Keys.isUnlocalized("Spawn entity.blackfrost.name"));
        assertFalse(Keys.isUnlocalized("foo.name"));
        assertFalse(Keys.isUnlocalized("Iron Ingot"));
        assertFalse(Keys.isUnlocalized(null));
    }

    @Test
    public void prettifyingLeavesAWordThatAlreadyCarriesCapitalsAlone() {
        assertEquals("Boric Acid", Keys.prettify("boric_acid"));
        // `.title()` would turn `TBU` into `Tbu` and `NaOH` into `Naoh`.
        assertEquals("TBU", Keys.prettify("TBU"));
        assertEquals("NaOH Solution", Keys.prettify("NaOH_solution"));
        assertEquals("___", Keys.prettify("___"));
    }

    @Test
    public void onlyAnOrePrefixedGroupMeansMinedRatherThanMadeOf() {
        assertTrue(Keys.isWorldOreGroup("oreDiamond"));
        // `chisel:diamond` is a member of `blockDiamond`, so accepting every group would
        // readmit exactly the decorative blocks this test exists to demote.
        assertFalse(Keys.isWorldOreGroup("blockDiamond"));
        assertFalse(Keys.isWorldOreGroup("ingotIron"));
    }

    @Test
    public void everyNamespaceReportsItsOwnKind() {
        assertEquals("item", Keys.kind("minecraft:stone"));
        assertEquals("fluid", Keys.kind("fluid:water"));
        assertEquals("essentia", Keys.kind("essentia:aer"));
        assertEquals("ore", Keys.kind("ore:ingotIron"));
        assertFalse(Keys.isItemKey("fluid:water"));
        assertTrue(Keys.isItemKey("minecraft:stone"));
    }

    @Test
    public void theKindIdTheNameAndThePrefixAreAllDerivedFromOneList() {
        // The numbering is not decorative: item is 0 and the rest index NON_ITEM_KINDS
        // offset by one, which is what lets one array serve the id, the name and the prefix.
        // If a fourth namespace is added to that array and this breaks, the assertion below
        // is the reminder that the constant needs to exist too.
        assertEquals(Keys.KIND_ITEM, Keys.kindId("minecraft:stone"));
        assertEquals(Keys.KIND_FLUID, Keys.kindId("fluid:water"));
        assertEquals(Keys.KIND_ESSENTIA, Keys.kindId("essentia:aer"));
        assertEquals(Keys.KIND_ORE, Keys.kindId("ore:ingotIron"));
        assertEquals(Keys.NON_ITEM_KINDS.length + 1, 4);
        for (int kind = 0; kind <= Keys.NON_ITEM_KINDS.length; kind++) {
            assertEquals(Keys.kindName(kind), Keys.kind(kind == Keys.KIND_ITEM
                    ? "minecraft:stone" : Keys.kindName(kind) + ":thing"));
        }
        assertEquals("", Keys.kindPrefix(Keys.KIND_ITEM));
        assertEquals("[fluid] ", Keys.kindPrefix(Keys.KIND_FLUID));
        assertEquals("[essentia] ", Keys.kindPrefix(Keys.KIND_ESSENTIA));
        // Reads "oredict" rather than "ore" because it means "any member of", not "an ore".
        assertEquals("[oredict] ", Keys.kindPrefix(Keys.KIND_ORE));
    }

    @Test
    public void everyNamespaceHasAMatchingKeyConstructor() {
        assertEquals("ore:ingotIron", Keys.oreKey("ingotIron"));
        assertEquals("fluid:water", Keys.fluidKey("water"));
        // Aspects are lower-cased on the way in, so `Perditio` and `perditio` are one node.
        assertEquals("essentia:perditio", Keys.essentiaKey("Perditio"));
        assertEquals(Keys.KIND_ORE, Keys.kindId(Keys.oreKey("ingotIron")));
        assertEquals(Keys.KIND_FLUID, Keys.kindId(Keys.fluidKey("water")));
        assertEquals(Keys.KIND_ESSENTIA, Keys.kindId(Keys.essentiaKey("aer")));
    }
}
