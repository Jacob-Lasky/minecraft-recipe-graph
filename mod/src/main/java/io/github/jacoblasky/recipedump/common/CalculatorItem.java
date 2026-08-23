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
     * The name to show when the game will not resolve the lang entry. #316
     *
     * VERBATIM FROM `assets/mcrecipedump/lang/en_us.lang`, and it has to stay that way: two
     * spellings of this item's name is a worse bug than one wrong one. If the lang entry changes,
     * change this too.
     */
    static final String DISPLAY_NAME = "Crafting Planner";

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

    /**
     * The item's name, falling back to {@link #DISPLAY_NAME} when the game returns the raw key.
     * #316
     *
     * <h2>WHAT WAS ACTUALLY WRONG, because it was not any of the four obvious things</h2>
     *
     * The flagship item read `item.mcrecipedump.calculator.name` in the inventory, in JEI, and in
     * `config/AppliedEnergistics2/items.csv`. #316 ruled out every ordinary cause AGAINST THE JAR
     * INSTALLED IN `mods/`, by measurement rather than by inspection: the lang entry is present,
     * correctly pathed and correctly keyed; the file is 51 bytes of ASCII, LF-terminated, with no
     * BOM (hexdumped, so the classic first-key-corrupted-by-BOM cause is excluded); the client is
     * on `lang:en_us`, so it is not a locale fallback; and `setTranslationKey(MODID + "." + NAME)`
     * plus the `.name` suffix `getItemStackDisplayName` appends is exactly the key the file holds.
     * The game returned the raw key anyway. 68 of the 258,242 items in the schema-8 dump do the
     * same, from natura, erebus, railcraft, magicbees and tardis -- those mods may genuinely lack
     * entries; this one does not, which is what makes it a defect rather than a pack fact.
     *
     * <h2>WHY THIS FIX AND NOT THE OTHER ONE #316 OFFERS</h2>
     *
     * The alternative was to make `dump_names`' relabel guard record that it fired, so a raw key on
     * our own item shows up instead of being cosmetically repaired to "Calculator". That is worth
     * doing and it is not a fix: it makes the symptom VISIBLE IN THE GRAPH PIPELINE and leaves the
     * name in the player's hand wrong, and the name in the player's hand is the whole report. This
     * makes the item say what it is on every surface at once, including the next dump's
     * `names.json`, because the dump records whatever `getDisplayName()` answers.
     *
     * <h2>IT DEFERS TO A REAL TRANSLATION AND ONLY REPLACES A FAILED ONE</h2>
     *
     * `super` returns `I18n.translateToLocal(key).trim()`, and 1.12.2's `translateToLocal` returns
     * THE KEY ITSELF when it has no entry -- which is the whole reason the raw key was on screen.
     * So the comparison below is exact: a resolved name never equals its own key, and a
     * hard-coded string is used only in the case where the game has already given up. A pack that
     * ships a `de_de.lang` for this item still gets German. DO NOT "simplify" this to returning
     * {@link #DISPLAY_NAME} unconditionally; that would silently discard every translation,
     * including ours the moment it starts working.
     *
     * KNOWN AND ACCEPTED: the GRAPH keeps calling it "Calculator" until the next dump, because a
     * graph carries the labels the dump recorded. One redump fixes it, and #316 already asks
     * whether that churn is worth a note.
     */
    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        return displayName(super.getItemStackDisplayName(stack),
                           getTranslationKey(stack) + ".name");
    }

    /**
     * The decision, with both strings handed in.
     *
     * SEPARATE BECAUSE `getItemStackDisplayName` CANNOT BE REACHED FROM A TEST WITHOUT A LOADED
     * LANGUAGE MAP, the same wall `PlanTarget.accept` is split around: `I18n.translateToLocal`
     * answers off a static map the game populates from resource packs, so a JUnit case calling
     * through would be asserting against whatever that map happens to hold rather than against the
     * branch. Everything worth getting wrong -- which string wins, and on exactly what comparison
     * -- is on this side of that wall.
     */
    static String displayName(String translated, String key) {
        return key.equals(translated) ? DISPLAY_NAME : translated;
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
