package io.github.jacoblasky.recipedump.shot;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

/**
 * `world-probe`: falsifies, or fails to falsify, one sentence in `harness/README.md`.
 *
 * THE SENTENCE. "It renders GUIs, not the world. No world is loaded, so anything that needs a
 * player, a tile entity or a server-side capability has nothing to draw from. Phase 5's live
 * AE2 read is not testable here." It sat in the Limits section for a fortnight and was never
 * measured -- inferred from there being no window manager, exactly like the "It has no input"
 * bullet beside it, which turned out to be false. #146 is the third strike against the same
 * assumption: the JEI runtime, the recipe-category walk and item-model rendering were all
 * believed to need a world and are all live at the main menu.
 *
 * IT HAS THREE CLAUSES AND THIS TESTS ALL THREE, because falsifying one would leave the other
 * two quotable. A player, a tile entity, and a server-side capability:
 *
 *   - the PLAYER comes from `-Dmcrecipedump.shotWorld`, which `ShotHarness` proves by logging
 *     a dimension, a position and the block underfoot before any screen opens;
 *   - a TILE ENTITY is placed here, a vanilla chest, and read back from the world;
 *   - a SERVER-SIDE CAPABILITY is queried off it, `CapabilityItemHandler`, which is the same
 *     shape of call as Phase 5's AE2 read: `hasCapability` then `getCapability` on a tile
 *     entity, then use the handler. Vanilla only, so the result does not depend on which
 *     mods the dev set happens to carry.
 *
 * WHAT IT DOES NOT SHOW. AE2 is not installed in the dev mod set, so this does not prove
 * AE2's grid capability answers -- only that the mechanism Phase 5 needs is available. That
 * is the difference between "the harness can host this test" and "the test passes", and the
 * first is the one the README was wrong about.
 *
 * IT MUST FAIL LOUDLY. A probe that cannot place a block, or reads back air, has to say so
 * rather than log its way to a reassuring conclusion -- the cursor probe's first version
 * reported six lines of AGREE while comparing nothing to nothing, and a world probe has the
 * identical failure available to it. Every branch below either reports a concrete fact or
 * says exactly which step did not happen.
 */
final class WorldProbeShot {

    private WorldProbeShot() {
    }

    static void open(String arg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            // Not an assertion failure so much as a misuse: without the world property this
            // screen is being asked a question it cannot answer, and saying which flag is
            // missing is more use than saying "no world".
            throw new IllegalStateException("world-probe needs a world: run it with "
                    + "-D" + ShotHarness.PROP_WORLD + "=<name>");
        }

        // Two above the floor and one across, so the chest is in loaded chunks, is not inside
        // the player, and is not replacing the ground the player is standing on.
        BlockPos at = new BlockPos(mc.player.posX + 1, mc.player.posY, mc.player.posZ);
        boolean placed = mc.world.setBlockState(at, Blocks.CHEST.getDefaultState());
        String block = mc.world.getBlockState(at).getBlock().getRegistryName().toString();
        TileEntity tile = mc.world.getTileEntity(at);

        String capability;
        if (tile == null) {
            capability = "no tile entity to ask";
        } else if (!tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,
                EnumFacing.UP)) {
            capability = "tile entity has no ITEM_HANDLER_CAPABILITY";
        } else {
            IItemHandler handler = tile.getCapability(
                    CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
            // THE SLOT COUNT, not merely a non-null handler. A capability that answers with an
            // object which then does nothing useful is the shape of a stub, and 27 is a fact
            // about a real vanilla chest that a stub would have to know to fake.
            capability = handler == null
                    ? "getCapability returned null after hasCapability said yes"
                    : "IItemHandler with " + handler.getSlots() + " slots";
        }

        ShotHarness.log("world-probe: setBlockState=" + placed
                + ", block now " + block
                + ", tileEntity=" + (tile == null ? "null" : tile.getClass().getSimpleName())
                + ", capability: " + capability);
        ShotHarness.log("world-probe: loadedTileEntities now "
                + mc.world.loadedTileEntityList.size()
                + ", integratedServer=" + (mc.getIntegratedServer() != null));

        // A screen so the run still produces a PNG, and so the picture shows the world behind
        // it rather than the main menu's dirt.
        HarnessFixtureScreen.open();
    }
}
