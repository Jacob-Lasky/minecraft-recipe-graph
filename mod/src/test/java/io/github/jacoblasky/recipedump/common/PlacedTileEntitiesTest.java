package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The one invariant of the loaded-chunk scan that a JUnit case can reach. #320 phase 2
 *
 * <h2>WHY THIS FILE IS SMALL, said plainly rather than left to look like thin coverage</h2>
 *
 * {@link PlacedTileEntities#scan} needs a `MinecraftServer` with loaded worlds, and nothing on a
 * JUnit classpath can stand one up -- a mock would be measuring my own fake. The scan's behaviour is
 * proven live by the `ae2-placed` probe, which drives the production read against a real world.
 *
 * WHAT IS REACHABLE, AND IT IS THE PART THAT ACTUALLY BROKE, is the key derivation:
 * {@link PlacedTileEntities#keyOfPickBlock} is pure, takes an `ItemStack`, and carries the never-null
 * invariant the scan's cache and its dereference both depend on.
 *
 * <h2>THE DEFECT THIS PINS</h2>
 *
 * Review caught a NullPointerException here before it shipped. The absence of an item form was
 * normalised on the way INTO the per-blockstate cache while the local variable kept the null, so the
 * FIRST tile entity of any such block dereferenced null. The blast radius was the whole read rather
 * than the scan: the NPE escaped `scan`, escaped `Ae2StockReader.read`, and `Ae2Stock.read`'s
 * `catch (Throwable)` reported it as `NETWORK_GONE`, so the player was told "the linked network no
 * longer exists" and lost the item list and the grid machines too -- after `usePower` had charged
 * the terminal. The `ae2-placed` probe proves that end to end, both ways round.
 *
 * <h2>WHICH FUNCTION ANSWERS "DOES THIS BLOCK HAVE AN ITEM" -- AND THE TRAP THAT COST TWO PASSES</h2>
 *
 * `Item.getItemFromBlock(block)` AND `Block.getItem(world, pos, state)` ARE DIFFERENT QUESTIONS AND
 * VANILLA OVERRIDES THE SECOND IN BOTH DIRECTIONS. Measured on this classpath, 2026-08-23:
 *
 * <ul>
 * <li>`mob_spawner` HAS an `ItemBlock`, so `getItemFromBlock` is non-air -- and `getItem` returns
 *     `ItemStack.EMPTY` anyway, because vanilla refuses to let you pick-block a spawner;</li>
 * <li>`lit_furnace` has NO `ItemBlock`, so `getItemFromBlock` is air -- and `getItem` returns a
 *     perfectly good `minecraft:furnace`, because `BlockFurnace` overrides it to.</li>
 * </ul>
 *
 * THE SCAN CALLS `Block.getItem`, so that is the only function whose answer matters here. A census
 * built on `getItemFromBlock` produced a confidently wrong list of 14 blocks and a live probe
 * criterion that passed for the wrong reason -- the probe's mutation run is what caught it, by NOT
 * failing. Do not measure the other one.
 *
 * BY `Block.getItem`, four blocks with tile entities answer EMPTY: `piston_extension` (any piston
 * mid-push), `mob_spawner`, `end_portal` and `end_gateway` -- exactly the four the review named.
 */
public class PlacedTileEntitiesTest {

    /**
     * The four blocks measured to answer `ItemStack.EMPTY` from `Block.getItem`, with a tile entity.
     *
     * A METHOD AND NOT A STATIC FIELD, which is not style: touching `Blocks` runs its static
     * initialiser, and that needs `Bootstrap.register()` to have happened. A `static final` array
     * here is evaluated at CLASS INIT, before `@BeforeClass`, and dies with
     * `ExceptionInInitializerError` -- reported as a failure in `classMethod`, which names neither
     * the field nor the cause. Measured, twice now in this repository.
     *
     * LISTED HERE AND DERIVED BELOW TOO, which is not redundancy: the list is what the live probe's
     * choice is checked against, and the derivation is what notices a fifth arriving.
     */
    private static Block[] noPickBlockWithTileEntity() {
        return new Block[] {
            Blocks.PISTON_EXTENSION, Blocks.MOB_SPAWNER, Blocks.END_PORTAL, Blocks.END_GATEWAY,
        };
    }

    @BeforeClass
    public static void bootstrap() {
        // The item and block registries, needed before an `ItemStack` means anything. Headless-safe;
        // `CalculatorItemTest` relies on the same call.
        Bootstrap.register();
    }

    /**
     * A block with no item form yields "" and NOT null. THE BLOCKER, as an assertion.
     *
     * ASSERTED ON THE EMPTINESS AND NOT MERELY ON not-null, because both halves are load-bearing in
     * opposite directions: null crashes the caller, and any NON-empty answer would count a key for a
     * block the player cannot hold, putting `minecraft:air` or worse into the placed map.
     */
    @Test
    public void aBlockWithNoItemFormKeysAsEmptyRatherThanNull() {
        String key = PlacedTileEntities.keyOfPickBlock(ItemStack.EMPTY);
        assertNotNull("null here is the NullPointerException the scan used to die on, one frame"
                      + " later and reported to the player as NETWORK_GONE", key);
        assertEquals("", key);
    }

    /**
     * And so does a null stack, which a third-party `getItem` override really can return.
     *
     * THE API SAYS NON-NULL AND A THIRD-PARTY IMPLEMENTATION IS NOT THE API -- the same reasoning
     * `Ae2StockReader.placedOf` records for `getMachineRepresentation`. `pickBlock` already maps null
     * to `ItemStack.EMPTY`, so this is the belt to that braces, and it costs one line.
     */
    @Test
    public void aNullStackKeysAsEmptyToo() {
        assertEquals("", PlacedTileEntities.keyOfPickBlock(null));
    }

    /**
     * The four real blocks, through the function the scan actually calls.
     *
     * THIS IS THE CASE THAT WOULD HAVE CAUGHT THE WRONG-FUNCTION MISTAKE. Its predecessor asked
     * `Item.getItemFromBlock` and therefore asserted something true about a function the scan never
     * calls; this asks `Block.getItem`, with the same `(null world, ORIGIN)` arguments that the
     * measurement used, and all four answer without touching the world.
     *
     * A NULL WORLD IS SAFE FOR THESE FOUR AND NOT IN GENERAL, which is why this names them instead
     * of walking the registry with a null: 21 vanilla blocks (beds, banners, skulls, flower pots and
     * every shulker box) read the world in their override and throw on a null one. That is an
     * artifact of the harness, not a production hazard -- the scan always has a real world -- and
     * `everyBlockThePickCanBeTakenFromKeysWithoutNull` skips them for exactly that reason.
     */
    @Test
    public void theFourNoPickBlockTileEntitiesKeyAsEmpty() {
        for (Block block : noPickBlockWithTileEntity()) {
            IBlockState state = block.getDefaultState();
            assertTrue(block.getRegistryName() + " must have a tile entity for the scan to meet it",
                       block.hasTileEntity(state));
            ItemStack pick = block.getItem(null, BlockPos.ORIGIN, state);
            assertTrue(block.getRegistryName() + " is expected to have no pick-block; `getItem`"
                       + " answered " + pick, pick == null || pick.isEmpty());
            assertEquals(block.getRegistryName() + " must key as \"\" and never null",
                         "", PlacedTileEntities.keyOfPickBlock(pick));
        }
    }

    /**
     * Derived: no block the pick can be taken from keys as null, and some really do key as "".
     *
     * THE DERIVATION IS WHAT NOTICES A FIFTH BLOCK ARRIVING, which a hand-list cannot. Blocks whose
     * override needs a world are skipped rather than failed -- see the case above on why that is a
     * harness limit and not a gap -- and the two counts at the end are what stop the loop passing by
     * examining nothing or by finding no empty case at all.
     */
    @Test
    public void everyBlockThePickCanBeTakenFromKeysWithoutNull() {
        int examined = 0;
        int empty = 0;
        for (ResourceLocation id : Block.REGISTRY.getKeys()) {
            Block block = Block.REGISTRY.getObject(id);
            ItemStack pick;
            IBlockState state;
            try {
                state = block.getDefaultState();
                pick = block.getItem(null, BlockPos.ORIGIN, state);
            } catch (Throwable needsAWorld) {
                continue;
            }
            examined++;
            String key = PlacedTileEntities.keyOfPickBlock(pick);
            assertNotNull(id + " keyed as null, which is the NullPointerException the scan died on",
                          key);
            if (key.isEmpty()) {
                empty++;
            }
        }
        assertTrue("the walk examined almost nothing, so this asserted almost nothing; 233 of 254"
                   + " were reachable on 2026-08-23, examined " + examined, examined > 200);
        assertTrue("no block keyed as empty, so the branch the blocker lived on was never taken and"
                   + " this test cannot have exercised it; 12 were measured on 2026-08-23",
                   empty >= 8);
    }

    /**
     * An ordinary block keys as its item, so the empty answers above are not the only answer.
     *
     * THE CONTROL. Without it a `keyOfPickBlock` that returned "" for everything would pass every
     * other case in this file, and the scan would silently stop counting anything at all -- a
     * regression indistinguishable from a base with no machines in it.
     */
    @Test
    public void anOrdinaryBlockKeysAsItsOwnItem() {
        assertEquals("minecraft:furnace",
                     PlacedTileEntities.keyOfPickBlock(new ItemStack(Blocks.FURNACE)));
    }

    /**
     * `lit_furnace` keys as a plain furnace, which is the other half of the wrong-function trap.
     *
     * IT HAS NO `ItemBlock` AND IS STILL NOT A NO-ITEM-FORM BLOCK, because `BlockFurnace` overrides
     * `getItem` to hand back the unlit furnace. A census built on `getItemFromBlock` put this in its
     * empty list and called it "a furnace mid-smelt, so this was every base" -- a vivid claim that
     * was simply false. Pinned so the correction cannot decay back.
     */
    @Test
    public void aSmeltingFurnaceStillKeysAsAnOrdinaryFurnace() {
        IBlockState lit = Blocks.LIT_FURNACE.getDefaultState();
        ItemStack pick = Blocks.LIT_FURNACE.getItem(null, BlockPos.ORIGIN, lit);
        assertEquals("minecraft:furnace", PlacedTileEntities.keyOfPickBlock(pick));
    }

    /**
     * Metadata rides along when it is non-zero, which is what makes the key a key.
     *
     * `baseStackKey` spells `id:meta` for a variant stack, and machine blocks in this pack really do
     * live at non-zero metas -- `thermalexpansion:machine:1` is a Pulverizer. A derivation that
     * dropped the meta would key every Thermal machine as the same block.
     */
    @Test
    public void aVariantItemKeepsItsMetadata() {
        String key = PlacedTileEntities.keyOfPickBlock(new ItemStack(Blocks.WOOL, 1, 14));
        assertTrue("expected the meta to survive, got " + key, key.endsWith(":14"));
    }
}
