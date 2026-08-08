package io.github.jacoblasky.recipedump.shot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.crafting.IShapedRecipe;

/**
 * `remainder-probe`: does `IRecipe.getRemainingItems` actually return CraftTweaker's `.reuse()`
 * ingredients? See #228.
 *
 * WHY THIS EXISTS. #175's design report states, as a property of vanilla, that
 * `IRecipe.getRemainingItems(InventoryCrafting)` covers "buckets, tools with durability" AND every
 * `.reuse()` marker in the pack with one call and no mod knowledge, and its emitter is built on
 * that. But `getRemainingItems` is an INTERFACE method, so what comes back is whatever the
 * recipe's own class returns, and a scripted recipe's class is CraftTweaker's, not Minecraft's.
 * The claim is therefore about CraftTweaker's runtime and it had never been run. 504 `.reuse()`
 * markers across the pack's scripts hang on the answer, and the two answers imply very different
 * emitters: a free ride on the vanilla path, or a third reflective bridge.
 *
 * THE CONCRETE CASE, NAMED BY #228 AND VERIFIED IN THE STAGED PACK.
 * `scripts/DraconicGating.zs:35` puts a `.reuse()` inside a shaped grid:
 *
 *   [<contenttweaker:cursed_dragon_egg>, <contenttweaker:cursed_cradle>.reuse(),
 *    <contenttweaker:cursed_dragon_egg>],
 *
 * producing {@link #OUTPUT}. So the probe finds that recipe by its output, rebuilds a grid that
 * matches it, calls `getRemainingItems`, and looks at the slot the cradle went into.
 *
 * THE CRITERIA ARE FIXED HERE, IN ADVANCE, AND THE EXIT CODE CARRIES THEM. A pass means the
 * `.reuse()` ingredient CAME BACK, which is the assumption #175 rests on. Every other way out of
 * this class reports a failure naming the step that stopped, so a run that could not observe and a
 * run that observed a NO are both non-zero, and the log says which. DO NOT read a green run as
 * merely "the probe ran".
 *
 *   1. A recipe producing {@link #OUTPUT} is in `CraftingManager.REGISTRY`.
 *   2. A grid rebuilt from that recipe's own ingredients contains {@link #REUSED}, and the recipe
 *      MATCHES that grid. Criterion 2 is not bookkeeping: `MCRecipeShaped.getRemainingItems`
 *      begins by computing the grid offset and returns an all empty list when the offset is
 *      invalid, so calling it on a grid the recipe does not match yields exactly the empty result
 *      that a genuine "no, it does not report reuse" would produce. Without this check the probe
 *      could publish a false NO and nobody could tell.
 *   3. The remainder at the cradle's slot is non empty and is the cradle.
 *
 * THE GRID IS REBUILT FROM THE RECIPE, NOT TYPED OUT FROM THE SCRIPT. The script line is the
 * reason this recipe was chosen, but hardcoding its nine items would make the probe fail whenever
 * the pack edits the recipe, and that failure would look like a finding about `getRemainingItems`.
 * `getIngredients` plus `IShapedRecipe`'s width and height is the recipe describing itself.
 *
 * IT ALSO ANSWERS #228's SECOND QUESTION, ON THE SAME RUN. A damageable tool's remainder is the
 * tool with its damage incremented, so it is NOT `ItemStack.areItemStacksEqual` to the input. Any
 * emitter deciding "was this consumed" by full stack equality would report a durability tool as
 * consumed. {@link #probeDurability} measures that directly. It does not gate the exit code,
 * because it is a separate question from criteria 1 to 3 and a pack with no such item would
 * otherwise fail a run that answered the question it was built for. It always logs a definite
 * line, including when it finds nothing, so it cannot pass silently.
 *
 * AND IT CENSUSES THE WHOLE POPULATION, because one observation decides the mechanism and only a
 * census decides the emitter. {@link #censusCraftTweaker} runs the same rebuild and call over
 * every CraftTweaker recipe in the registry and counts how many report a non empty remainder. One
 * cradle proves `.reuse()` is reported; the census is what says whether the 504 markers are
 * covered in practice.
 *
 * NO SERVER TICKS, DELIBERATELY. `getRemainingItems` is a synchronous call on a recipe object, so
 * everything here runs on the client thread inside the opener and there is nothing to wait for.
 * That matters because `DumpShot`'s header records an unexplained pause menu appearing over
 * no-screen captures, and a single player pause halts the integrated server: a probe that needed
 * server ticks would be exposed to it. This one opens the fixture screen and needs no ticks.
 */
final class RemainderProbeShot {

    /** The recipe under test, from `scripts/DraconicGating.zs:35`. */
    private static final String OUTPUT = "contenttweaker:summons_of_the_cursed_wyvern";

    /** The `.reuse()` ingredient in that recipe's centre slot. */
    private static final String REUSED = "contenttweaker:cursed_cradle";

    /** Grid width and height. A crafting table, which is what the script declares the recipe for. */
    private static final int GRID = 3;

    /** How many durability findings to print. Enough to see a pattern, short enough to read. */
    private static final int DURABILITY_SAMPLES = 8;

    /** How many census examples to print, for the same reason. */
    private static final int CENSUS_SAMPLES = 10;

    /**
     * The `Container` an `InventoryCrafting` needs.
     *
     * A REAL ONE IS NOT AVAILABLE AND IS NOT NEEDED. `InventoryCrafting` calls back into its
     * container only on `markDirty`, which nothing here triggers, so the probe would rather supply
     * an inert one than open a workbench GUI it does not want and then have to photograph.
     */
    private static final Container INERT = new Container() {
        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return true;
        }
    };

    private RemainderProbeShot() {
    }

    static void open(String arg) {
        Minecraft mc = Minecraft.getMinecraft();
        // A WORLD, BECAUSE `matches` TAKES ONE. Most implementations ignore the argument, but
        // passing null to every recipe class in a 400 mod pack is a bet on all of them ignoring
        // it, and an NPE out of somebody's `matches` would read as a finding about remainders.
        // A world also guarantees CraftTweaker's script actions have been applied, so the recipes
        // this probe walks are the scripted ones rather than the pre-tweak set.
        if (mc.world == null) {
            throw new IllegalStateException("remainder-probe needs a world: run it with "
                    + "-D" + ShotHarness.PROP_WORLD + "=<name>");
        }
        // BEFORE ANY PATH THAT CAN REPORT. Same ordering lesson as `Ae2ProbeShot`: a `reportFail`
        // that runs before the debt is declared would be cleared by the declaration and the run
        // would say "the screen never reported", throwing away the real reason.
        ShotScreens.expectReport("remainder-probe verdict");
        try {
            probe(mc.world);
        } catch (Throwable t) {
            // Reported rather than swallowed: a probe that dies quietly leaves a log with no
            // verdict line, which reads as "did not run" and not as "failed".
            stopped("the probe threw " + t);
            t.printStackTrace();
        }
        HarnessFixtureScreen.open();
    }

    /**
     * Say why the probe stopped, once, to both places that need to hear it.
     *
     * ONE STRING, LOGGED AND REPORTED, for the reason `Ae2ProbeShot.stopped` gives: the log line
     * and the exit message are the only two things a reader has, and two wordings that have
     * drifted apart send them hunting a third cause that does not exist.
     */
    private static void stopped(String reason) {
        ShotHarness.log("remainder-probe: STOPPED, " + reason);
        ShotScreens.reportFail(reason);
    }

    private static void probe(net.minecraft.world.World world) {
        boolean answered = reuseAnswered(world);

        // THE OTHER TWO QUESTIONS RUN WHATEVER THE FIRST ONE ANSWERED, and they run outside the
        // first one's early returns on purpose. #228 asks for the durability finding on the same
        // run, and it does not depend on the cradle recipe existing: dropping it because step 1
        // could not find a recipe would spend a full pack boot and answer one question.
        probeDurability();
        censusCraftTweaker(world);

        if (answered) {
            ShotScreens.reportPass();
            return;
        }
        // ONLY IF NOTHING MORE SPECIFIC WAS SAID. Every step that stops has already reported the
        // criterion that did not hold, and overwriting that with this summary is the exact
        // reason-drift `stopped` exists to prevent: it would replace "the .reuse() ingredient did
        // not come back", which is the finding, with "no candidate yielded an observation", which
        // is not.
        if (ShotScreens.failedVerdict() == null) {
            stopped("no recipe for " + OUTPUT + " yielded an observation, and no step said why");
        }
    }

    /** Criteria 1 to 3. @return true when the `.reuse()` ingredient came back. */
    private static boolean reuseAnswered(net.minecraft.world.World world) {
        // CRITERION 1: the recipe exists.
        ResourceLocation wanted = new ResourceLocation(OUTPUT);
        List<IRecipe> candidates = new ArrayList<IRecipe>();
        for (IRecipe recipe : CraftingManager.REGISTRY) {
            ItemStack out = recipe.getRecipeOutput();
            if (!out.isEmpty() && out.getItem().getRegistryName() != null
                    && out.getItem().getRegistryName().equals(wanted)) {
                candidates.add(recipe);
            }
        }
        ShotHarness.log("remainder-probe: " + candidates.size() + " recipe(s) output " + OUTPUT);
        if (candidates.isEmpty()) {
            stopped("step 1: no recipe in CraftingManager.REGISTRY outputs " + OUTPUT
                    + ", so the pack does not declare the recipe #228 names");
            return false;
        }

        Item reused = Item.REGISTRY.getObject(new ResourceLocation(REUSED));
        if (reused == null) {
            stopped("step 1: no item registered as " + REUSED);
            return false;
        }

        for (IRecipe recipe : candidates) {
            ShotHarness.log("remainder-probe: candidate " + recipe.getRegistryName()
                    + " is a " + recipe.getClass().getName());
            if (report(recipe, reused, world)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild a grid for one recipe, call `getRemainingItems`, and print every slot.
     *
     * @return true when criteria 2 and 3 both held, which is the YES answer to #228.
     */
    private static boolean report(IRecipe recipe, Item reused, net.minecraft.world.World world) {
        InventoryCrafting grid = buildGrid(recipe);
        int cradleSlot = -1;
        for (int slot = 0; slot < grid.getSizeInventory(); slot++) {
            ItemStack in = grid.getStackInSlot(slot);
            if (!in.isEmpty() && in.getItem() == reused) {
                cradleSlot = slot;
            }
        }
        if (cradleSlot < 0) {
            ShotHarness.log("remainder-probe:   step 2: the rebuilt grid holds no " + REUSED
                    + ", so this recipe is not the one #228 names");
            return false;
        }

        // CRITERION 2. See the class header: an unmatched grid produces an all empty remainder
        // list indistinguishable from a genuine NO, so this is checked rather than assumed.
        boolean matches = recipe.matches(grid, world);
        ShotHarness.log("remainder-probe:   step 2: " + REUSED + " is in slot " + cradleSlot
                + ", recipe.matches(grid) is " + matches
                + " (criterion: matches must be true or the remainder is meaningless)");
        if (!matches) {
            ShotHarness.log("remainder-probe:   step 2: the grid rebuilt from this recipe's own "
                    + "getIngredients does not match it, so getRemainingItems cannot be trusted "
                    + "here and this probe reports nothing about reuse from it");
            return false;
        }

        NonNullList<ItemStack> remaining = recipe.getRemainingItems(grid);
        ShotHarness.log("remainder-probe:   getRemainingItems returned " + remaining.size()
                + " slot(s):");
        for (int slot = 0; slot < remaining.size(); slot++) {
            ItemStack in = slot < grid.getSizeInventory() ? grid.getStackInSlot(slot)
                    : ItemStack.EMPTY;
            ItemStack out = remaining.get(slot);
            ShotHarness.log("remainder-probe:     slot " + slot
                    + " in=" + describe(in)
                    + " remainder=" + describe(out)
                    + " sameItem=" + (!in.isEmpty() && !out.isEmpty()
                            && in.getItem() == out.getItem())
                    + " stacksEqual=" + ItemStack.areItemStacksEqual(in, out));
        }

        // CRITERION 3, AND THE ANSWER TO #228.
        ItemStack out = remaining.get(cradleSlot);
        boolean came = !out.isEmpty() && out.getItem() == reused;
        ShotHarness.log("remainder-probe: ANSWER " + (came ? "YES" : "NO")
                + ": getRemainingItems slot " + cradleSlot + " for the .reuse() ingredient is "
                + describe(out) + ", expected " + REUSED
                + ". So vanilla's getRemainingItems "
                + (came ? "DOES" : "DOES NOT")
                + " report CraftTweaker .reuse() ingredients.");
        if (!came) {
            stopped("step 3: the .reuse() ingredient did NOT come back from getRemainingItems; "
                    + "slot " + cradleSlot + " is " + describe(out) + ". #175's emitter needs a "
                    + "CraftTweaker bridge for the .reuse() population");
            return false;
        }
        return true;
    }

    /**
     * Build a grid this recipe should match, from the recipe's own declared ingredients.
     *
     * SHAPED RECIPES ARE LAID OUT BY THEIR OWN WIDTH, NOT BY THE GRID'S. `getIngredients` returns
     * a flat list in recipe order, so a 2 wide recipe's third ingredient is the start of its
     * second row and belongs in grid slot 3, not slot 2. Forge's `IShapedRecipe` is what exposes
     * those dimensions, and CraftTweaker's `MCRecipeShaped` implements it. A recipe that is not
     * shaped is filled in order, which is what shapeless means.
     */
    private static InventoryCrafting buildGrid(IRecipe recipe) {
        InventoryCrafting grid = new InventoryCrafting(INERT, GRID, GRID);
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int width = GRID;
        boolean shaped = recipe instanceof IShapedRecipe;
        if (shaped) {
            width = ((IShapedRecipe) recipe).getRecipeWidth();
        }
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack[] options = ingredients.get(i).getMatchingStacks();
            if (options.length == 0) {
                // An empty slot in the shape, or an ore entry nothing is registered under. Left
                // empty rather than skipped in the index, so the layout stays aligned.
                continue;
            }
            int slot = shaped && width > 0 ? (i / width) * GRID + (i % width) : i;
            if (slot >= 0 && slot < grid.getSizeInventory()) {
                grid.setInventorySlotContents(slot, options[0].copy());
            }
        }
        return grid;
    }

    /**
     * #228's second question: is a damageable tool's remainder unequal to the input it came from?
     *
     * THROUGH `ForgeHooks.getContainerItem`, WHICH IS THE CALL THE REMAINDER PATH MAKES. Both
     * vanilla's default `IRecipe.getRemainingItems` and CraftTweaker's `MCRecipeShaped` fall
     * through to exactly this for any slot without a transformer, so measuring it here measures
     * the same code a craft would run, without needing to find a recipe that happens to use a
     * tool. Item identity is compared beside stack equality because that is the distinction the
     * emitter has to get right.
     */
    private static void probeDurability() {
        int scanned = 0;
        int found = 0;
        int shown = 0;
        int equalToInput = 0;
        int sameItemAsInput = 0;
        for (Item item : Item.REGISTRY) {
            scanned++;
            ItemStack in = new ItemStack(item);
            if (in.isEmpty() || !in.isItemStackDamageable() || !item.hasContainerItem(in)) {
                continue;
            }
            found++;
            ItemStack out = ForgeHooks.getContainerItem(in);
            boolean stacksEqual = ItemStack.areItemStacksEqual(in, out);
            boolean sameItem = !out.isEmpty() && out.getItem() == in.getItem();
            if (stacksEqual) {
                equalToInput++;
            }
            if (sameItem) {
                sameItemAsInput++;
            }
            if (shown < DURABILITY_SAMPLES) {
                shown++;
                ShotHarness.log("remainder-probe: durability " + item.getRegistryName()
                        + " in=" + describe(in) + " remainder=" + describe(out)
                        + " stacksEqual=" + stacksEqual + " sameItem=" + sameItem);
            }
        }
        // ALWAYS A DEFINITE LINE, INCLUDING THE ZERO CASE. A probe that prints nothing when it
        // finds nothing is indistinguishable from one that never ran, which is the failure
        // `ShotScreens.expectReport` exists for.
        ShotHarness.log("remainder-probe: durability scanned " + scanned + " item(s), "
                + found + " are damageable AND declare a container item; of those "
                + equalToInput + " are areItemStacksEqual to their input and "
                + sameItemAsInput + " are the same Item."
                + (found == 0
                   ? " NONE FOUND, so this run says nothing about the durability half of #228"
                   : " Equality therefore " + (equalToInput < found ? "DOES" : "does not")
                     + " misreport at least one durability tool as consumed, and item identity"
                     + " is the comparison that survives."));
    }

    /**
     * How much of the `.reuse()` population the vanilla call actually covers.
     *
     * ONE OBSERVATION SETTLES THE MECHANISM, A CENSUS SETTLES THE EMITTER. #175's cost estimate
     * turns on whether the 504 markers come back for free, and a single cradle does not establish
     * that the other recipes are shaped the same way or that their grids can even be rebuilt. So
     * the same rebuild and call runs over every CraftTweaker recipe in the registry.
     *
     * RECIPES WHOSE REBUILT GRID DOES NOT MATCH ARE COUNTED SEPARATELY AND NOT AS ZEROES, because
     * an unmatched grid returns an all empty remainder list and folding those into "no remainder"
     * would understate the coverage with numbers that look like measurements.
     */
    private static void censusCraftTweaker(net.minecraft.world.World world) {
        int craftTweakerRecipes = 0;
        int matched = 0;
        int unmatched = 0;
        int threw = 0;
        int withRemainder = 0;
        int remainderSlots = 0;
        int shown = 0;
        for (IRecipe recipe : CraftingManager.REGISTRY) {
            if (!recipe.getClass().getName().startsWith("crafttweaker.")) {
                continue;
            }
            craftTweakerRecipes++;
            try {
                InventoryCrafting grid = buildGrid(recipe);
                if (!recipe.matches(grid, world)) {
                    unmatched++;
                    continue;
                }
                matched++;
                NonNullList<ItemStack> remaining = recipe.getRemainingItems(grid);
                int slots = 0;
                for (ItemStack out : remaining) {
                    if (!out.isEmpty()) {
                        slots++;
                    }
                }
                if (slots > 0) {
                    withRemainder++;
                    remainderSlots += slots;
                    if (shown < CENSUS_SAMPLES) {
                        shown++;
                        ShotHarness.log("remainder-probe: census example "
                                + recipe.getRegistryName() + " (" + slots + " slot(s) kept) -> "
                                + describeAll(remaining));
                    }
                }
            } catch (Throwable t) {
                // Counted, not fatal. A single mod's recipe throwing on a synthetic grid is a fact
                // about that recipe, and aborting the census over it would lose the population
                // measurement this method exists to produce.
                threw++;
            }
        }
        ShotHarness.log("remainder-probe: census over CraftingManager.REGISTRY found "
                + craftTweakerRecipes + " CraftTweaker recipe(s): " + matched
                + " matched a grid rebuilt from their own ingredients, " + unmatched
                + " did not (not counted either way), " + threw + " threw. Of the matched, "
                + withRemainder + " reported a non empty remainder, totalling " + remainderSlots
                + " kept slot(s).");
    }

    /** One stack, printed so two of them can be told apart by eye. */
    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "EMPTY";
        }
        return stack.getItem().getRegistryName() + "#" + stack.getMetadata()
                + " x" + stack.getCount()
                + (stack.isItemStackDamageable()
                        ? " dmg=" + stack.getItemDamage() + "/" + stack.getMaxDamage() : "");
    }

    /** The non empty entries of a remainder list, for the census examples. */
    private static String describeAll(NonNullList<ItemStack> stacks) {
        StringBuilder out = new StringBuilder();
        for (int slot = 0; slot < stacks.size(); slot++) {
            if (stacks.get(slot).isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append("slot ").append(slot).append('=').append(describe(stacks.get(slot)));
        }
        return out.length() == 0 ? "nothing" : out.toString();
    }
}
