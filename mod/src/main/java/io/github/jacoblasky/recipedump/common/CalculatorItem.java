package io.github.jacoblasky.recipedump.common;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The planner, as a thing you hold. Right-click opens it.
 *
 * The entry point is an item rather than a keybind because that is what Just Enough
 * Calculation does and it is the comparison this whole feature invites: a keybind is a
 * setting you have to find, an item is a thing you craft and then have.
 */
@Mod.EventBusSubscriber(modid = RecipeDumpMod.MODID)
public class CalculatorItem extends Item {

    /** Registry path, and therefore half of the recipe's output id and the model's name. */
    public static final String NAME = "calculator";

    /**
     * The registered instance, set by {@link #onRegisterItems}.
     *
     * A static handle rather than a lookup, because the model registration and the creative
     * tab both need the exact object Forge holds, and `Item.getByNameOrId` returns null during
     * the window in which those run.
     */
    public static CalculatorItem INSTANCE;

    public CalculatorItem() {
        setRegistryName(new ResourceLocation(RecipeDumpMod.MODID, NAME));
        // `item.<modid>.<name>` is what the lang file keys on. `setTranslationKey` prepends
        // "item." itself, which is why the modid is here and the prefix is not.
        //
        // `setTranslationKey`, NOT `setUnlocalizedName`: this MCP channel (stable_39) renamed
        // it, and the old name does not exist to be deprecated -- it simply will not compile.
        setTranslationKey(RecipeDumpMod.MODID + "." + NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.TOOLS);
    }

    @SubscribeEvent
    public static void onRegisterItems(RegistryEvent.Register<Item> event) {
        INSTANCE = new CalculatorItem();
        event.getRegistry().register(INSTANCE);
    }

    /**
     * Open the planner.
     *
     * THROUGH THE PROXY, not by calling a screen. This method runs on BOTH sides -- the server
     * runs it too and its return value is what decides whether the swing animation plays -- so
     * a `net.minecraft.client` reference here would be a class a dedicated server cannot load.
     * `CommonProxy.openPlanner` is a no-op and `ClientProxy` opens the window.
     *
     * `world.isRemote` gates the call rather than `@SideOnly`, because both sides genuinely
     * execute this and only one of them has a screen to show.
     */
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player,
                                                    EnumHand hand) {
        if (world.isRemote) {
            // SNEAK OPENS THE MACHINES TABLE (#254). One item, two windows, chosen by the
            // modifier a 1.12.2 player already uses for "the other thing this does".
            //
            // FROM THE ITEM RATHER THAN FROM INSIDE THE PLANNER, and that is a correctness
            // decision rather than a convenience. The planner shows one of four not-yet panels
            // until a graph is read and a plan is solved; the machines table needs only the
            // graph. Reaching it through the planner would make it unreachable during the
            // 5.47 s load and unreachable when nothing has been planned -- both cases in which
            // it can answer perfectly well, and the second of which is when a player is most
            // likely to be asking "what can I even build with".
            //
            // WHEN #255 ADDS SOURCES AND COVERAGE they do NOT each take a modifier; three
            // windows behind three chords is undiscoverable. The shared nav strip is built
            // then, on the screens themselves, and nothing decided here has to be undone --
            // this stays the way into the group.
            if (player.isSneaking()) {
                RecipeDumpMod.proxy.openMachines(player);
            } else {
                RecipeDumpMod.proxy.openPlanner(player);
            }
        }
        // SUCCESS on both sides so the arm swings; the item is not consumed and nothing is
        // placed, so there is no state for the two sides to disagree about.
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }
}
