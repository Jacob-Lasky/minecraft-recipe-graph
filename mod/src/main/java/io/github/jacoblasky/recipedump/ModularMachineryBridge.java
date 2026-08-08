package io.github.jacoblasky.recipedump;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Modular Machinery's machine registry, read reflectively. Issue #55.
 *
 * WHAT PROBLEM THIS SOLVES. All 261 `modularmachinery:itemblueprint` variants in the
 * reference pack share one display name, "Machine Blueprint", because that genuinely IS
 * what the game returns for the item. Only the blueprint's NBT says which machine it
 * builds, and the dump deliberately reduces NBT to an opaque digest, so a plan for any
 * multiblock reads "1 Machine Blueprint" -- one of 261 possibilities, and the blueprint is
 * exactly the roadblock the player needs named.
 *
 * #55 measured and REJECTED deriving the name from the recipe the blueprint feeds. It is
 * right for blueprints and wrong in general: across the graph 1,536 NBT variants sharing a
 * display name are each consumed by a recipe with exactly one output, and a reagent
 * container, a gene template and an enchanted book are all in that set while designating
 * nothing. The distinction is Modular Machinery's semantics, not the graph's shape, so it
 * has to come from Modular Machinery.
 *
 * REFLECTION FOR THE SAME REASON AS {@link ProjectEBridge}: a pack without Modular
 * Machinery must still dump completely, and a hard reference would stop DumpCommand
 * loading at all. Verified against ModularMachinery-CE-2.2.2, which the reference pack
 * ships. The three entry points are all public:
 *
 *   MachineRegistry.getLoadedMachines()          static, List&lt;DynamicMachine&gt;
 *   AbstractMachine#getRegistryName/#getLocalizedName
 *   ItemBlueprint.getAssociatedMachineKey(stack) static, ResourceLocation
 */
final class ModularMachineryBridge {

    private static final String REGISTRY_CLASS =
            "hellfirepvp.modularmachinery.common.machine.MachineRegistry";
    private static final String BLUEPRINT_CLASS =
            "hellfirepvp.modularmachinery.common.item.ItemBlueprint";

    /** The blueprint item's registry id. Used to gate the per-stack lookup. */
    static final String BLUEPRINT_ITEM = "modularmachinery:itemblueprint";

    private static Method loadedMachines;
    private static Method associatedMachineKey;
    private static Item blueprintItem;
    private static boolean resolved;
    private static String absence = "not resolved yet";

    private ModularMachineryBridge() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            loadedMachines = Class.forName(REGISTRY_CLASS).getMethod("getLoadedMachines");
            associatedMachineKey = Class.forName(BLUEPRINT_CLASS)
                    .getMethod("getAssociatedMachineKey", ItemStack.class);
            blueprintItem = Item.getByNameOrId(BLUEPRINT_ITEM);
            absence = blueprintItem == null
                    ? "Modular Machinery present but " + BLUEPRINT_ITEM + " is not registered"
                    : "";
        } catch (ClassNotFoundException notInstalled) {
            absence = "Modular Machinery is not installed";
        } catch (Throwable t) {
            absence = "Modular Machinery present but its machine registry did not resolve: " + t;
        }
    }

    static boolean available() {
        resolve();
        return blueprintItem != null && loadedMachines != null && associatedMachineKey != null;
    }

    /** Why {@link #available} is false, or "" when it is true. */
    static String absence() {
        resolve();
        return absence;
    }

    /**
     * {machine registry name: its localized name}, in registry order, or empty.
     *
     * The localized name is the one the player sees in JEI and on the controller, which is
     * the point: "Dragonfire Crucible" is what a shopping list has to say.
     *
     * Read through the ABSTRACT base's getters rather than the concrete DynamicMachine, so
     * a future MM that returns a different subclass still resolves. Both are public.
     */
    static Map<String, String> machines() {
        if (!available()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            Object raw = loadedMachines.invoke(null);
            if (!(raw instanceof List)) {
                return out;
            }
            for (Object machine : (List<?>) raw) {
                if (machine == null) {
                    continue;
                }
                try {
                    Object id = machine.getClass().getMethod("getRegistryName").invoke(machine);
                    Object name =
                            machine.getClass().getMethod("getLocalizedName").invoke(machine);
                    if (id instanceof ResourceLocation && name instanceof String
                            && !((String) name).isEmpty()) {
                        out.put(id.toString(), (String) name);
                    }
                } catch (Throwable perMachine) {
                    // One unreadable machine must not cost the other 258.
                }
            }
        } catch (Throwable t) {
            // Same policy as catalysts: losing this file costs a label, not correctness.
        }
        return out;
    }

    /**
     * The registry name of the machine this stack's blueprint builds, or null.
     *
     * GATED ON THE ITEM, not on the call succeeding. `getAssociatedMachineKey` reads a
     * string out of the stack's tag compound, so handing it an unrelated stack is at best
     * an empty answer and at worst a throw on a null tag -- and doing that once per item in
     * the pack would turn a cheap lookup into 35,000 caught exceptions.
     */
    static String machineOf(ItemStack stack) {
        if (!available() || stack == null || stack.isEmpty()
                || stack.getItem() != blueprintItem) {
            return null;
        }
        try {
            Object rl = associatedMachineKey.invoke(null, stack);
            if (rl == null) {
                return null;
            }
            String id = rl.toString();
            return id.isEmpty() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- #175 and #223: the chance on an item requirement, either side ----

    private static final String WRAPPER_CLASS =
            "hellfirepvp.modularmachinery.common.integration.recipe.DynamicRecipeWrapper";
    private static final String REQUIREMENT_ITEM =
            "hellfirepvp.modularmachinery.common.crafting.requirement.RequirementItem";

    private static Class<?> wrapperClass;
    private static Class<?> requirementItem;
    private static Field orderedComponents;
    private static Field chanceField;
    private static Field requiredField;
    private static Field oreDictField;
    private static boolean chanceResolved;
    private static String chanceAbsence = "not resolved yet";

    /**
     * The two keys of `finalOrderedComponents`, matched against `IOType`'s `toString()`.
     *
     * NAMED RATHER THAN INLINE BECAUSE A TYPO HERE CANNOT FAIL A TEST ON THIS CLASSPATH.
     * Every assertion about this reader without Modular Machinery present is that it returns
     * null, so `"OUPUT"` would pass the whole suite and silently emit a dump in which no
     * output is ever chanced. `IOType` is a plain enum with no `toString` override, verified
     * with `javap -p` against ModularMachinery-CE-2.2.2, so these ARE its constant names;
     * {@link #itemRequirements} is package-visible so the selection can be exercised against
     * a hand-built map rather than only against a mod that is not here.
     */
    static final String IO_INPUT = "INPUT";
    static final String IO_OUTPUT = "OUTPUT";

    /**
     * ONE RESOLUTION FOR BOTH SIDES, because there is one field. `RequirementItem` is a single
     * class carrying `public float chance`, and its `actionType` is what makes an instance an
     * input or an output; verified with `javap -p` against the pack's own
     * ModularMachinery-CE-2.2.2.jar rather than taken from docs.
     *
     * WHAT THE NUMBER MEANS DEPENDS ON THE SIDE IT SITS ON, and that is the whole of #223.
     * On an INPUT it is the probability the slot is CONSUMED, which is the direction the
     * dump's `p` uses, so it passes through unscaled, and `setChance(0.0)` is a catalyst. On
     * an OUTPUT it is the probability the run YIELDS the stack, which is `q`. Writing a yield
     * into `p` would tell every consumer the output is a catalyst.
     *
     * THE CENSUS BEHIND BOTH, re-measured for #223 over the pack's 479 `scripts/*.zs` by
     * attributing every `setChance` to the nearest preceding `add*Input` / `add*Output` in
     * file order, which places all 1,937 of them with zero orphans: 1,102 on `addItemInput`,
     * of which 1,078 are 0.0 and 24 fractional, and 835 on `addItemOutput`, of which 834 are
     * fractional and 1 is exactly 1.0. Nothing else takes a chance at all.
     *
     * THE "1,942" THIS COMMENT CARRIED BEFORE #223 COUNTED A FILE THE GAME DOES NOT LOAD.
     * A plain recursive grep of `config/` finds 1,942, and the extra five are all in
     * `scripts/ModularPrimalMana.txt`, which CraftTweaker never reads because it is not `.zs`.
     * Count over `*.zs` or the number describes a script that has no effect on any recipe.
     */
    private static synchronized void resolveChance() {
        if (chanceResolved) {
            return;
        }
        chanceResolved = true;
        try {
            wrapperClass = Class.forName(WRAPPER_CLASS);
            requirementItem = Class.forName(REQUIREMENT_ITEM);
            orderedComponents = wrapperClass.getDeclaredField("finalOrderedComponents");
            orderedComponents.setAccessible(true);
            chanceField = requirementItem.getField("chance");
            requiredField = requirementItem.getField("required");
            oreDictField = requirementItem.getField("oreDictName");
            chanceAbsence = "";
        } catch (ClassNotFoundException notInstalled) {
            wrapperClass = null;
            chanceAbsence = "Modular Machinery is not installed";
        } catch (Throwable t) {
            wrapperClass = null;  // any missing piece disables the whole reader
            chanceAbsence = "Modular Machinery present but its recipe chances did not "
                    + "resolve: " + t;
        }
    }

    /** Whether {@link #itemInputChances} and {@link #itemOutputChances} can answer at all. */
    static boolean chancesAvailable() {
        resolveChance();
        return wrapperClass != null;
    }

    /**
     * Why {@link #chancesAvailable} is false, or "" when it is true.
     *
     * SEPARATE FROM {@link #absence()} BECAUSE THEY ARE SEPARATE READERS. `available` gates the
     * machine-name lookup and resolves `MachineRegistry` plus `ItemBlueprint`; this gates the
     * chance lookup and resolves `DynamicRecipeWrapper` plus `RequirementItem`. Either can
     * break while the other works, and reporting one as if it covered both would announce a
     * dump was fine on exactly the axis that had failed.
     */
    static String chanceAbsence() {
        resolveChance();
        return chanceAbsence;
    }

    /**
     * `chance` for each ITEM INPUT slot of a Modular Machinery wrapper, or null. #175.
     *
     * <p>See {@link #chances} for how the correspondence is established and why.
     */
    static float[] itemInputChances(Object wrapper, List<List<Object>> itemSlots) {
        return chances(wrapper, itemSlots, IO_INPUT);
    }

    /**
     * `chance` for each ITEM OUTPUT slot of a Modular Machinery wrapper, or null. #223.
     *
     * ALIGNED TO THE RAW OUTPUT SLOT LIST, NOT TO WHAT THE DUMP EMITS. `DumpCommand.flatStacks`
     * collapses each slot to its first stack and drops a slot that yields none, so its emitted
     * list is shorter than the slot list whenever a recipe has an empty output slot. Index i
     * here is index i of `CollectingIngredients.rawOutputs(ItemStack.class)`, which
     * `putLists` keeps one-to-one with what the wrapper reported, and the caller is what walks
     * the two together. Aligning to the emitted list instead would shift every `q` after the
     * first empty slot onto the wrong product.
     *
     * ITEM OUTPUTS ONLY, AND THAT IS A MEASURED LIMIT RATHER THAN AN OVERSIGHT. The pack's 479
     * `scripts/*.zs` make 1,957 `addFluidOutput` calls and attach ZERO `setChance` to any of
     * them, so a fluid-side reader would be dead code today. Same census as
     * {@link #resolveChance}, which is where the item-side numbers live. If a future pack
     * starts chancing fluid outputs the
     * shape to add is this one, keyed on `FluidStack.class` and threaded through
     * `DumpCommand.flatFluids`; nothing here needs to change to make room for it.
     */
    static float[] itemOutputChances(Object wrapper, List<List<Object>> outSlots) {
        return chances(wrapper, outSlots, IO_OUTPUT);
    }

    /**
     * The shared reader behind both sides. `ioType` is the enum's name, "INPUT" or "OUTPUT".
     *
     * READ OFF `finalOrderedComponents` RATHER THAN `MachineRecipe#getCraftingRequirements`,
     * because it is filtered and bucketed the same way the wrapper's own `getIngredients`
     * filters and buckets. Both walk `getCraftingRequirements()` in list order, drop anything
     * whose `provideJEIComponent()` is null, and split what is left by `getActionType()` and
     * by the JEI requirement class, so the ItemStack sublist under a given IO type is in the
     * order JEI is handed it. Read out of the bytecode of ModularMachinery-CE-2.2.2, not
     * assumed. The raw requirement list is in declaration order and mixes both sides with
     * energy and fluids, so lining it up with one side's slots would mean reimplementing that
     * filtering and hoping it stays in step.
     *
     * THE TWO FILTERS ARE NOT QUITE IDENTICAL, and the difference is why the content check
     * below is not optional. `getIngredients` also skips `RequirementEnergy` outright, which
     * `reloadWrapper` does not; it costs nothing here only because energy's JEI class is not
     * `ItemStack`, so the ItemStack sublists still agree. That is a coincidence of this MM
     * version rather than a contract, and it is exactly the kind of extra filter that would
     * shift the whole list by one in the next.
     *
     * INDEX CORRESPONDENCE IS VERIFIED BY CONTENT, NOT ASSUMED. Position i of the ItemStack
     * list for this IO type should describe slot i, and when it does not, because a future MM
     * filters one more case or an addon injects a requirement, the answer silently shifts by
     * one. On the input side that marks the WRONG ingredient permanent and invents a free
     * recipe; on the output side it hangs a 0.001 yield on the wrong product and inflates
     * every plan through it a thousandfold. Neither fails loudly. So each candidate is checked
     * against the slot it claims to describe, and a mismatch falls back to the default of 1.0
     * instead of guessing.
     */
    private static float[] chances(Object wrapper, List<List<Object>> slots, String ioType) {
        resolveChance();
        if (wrapperClass == null || wrapper == null
                || !wrapperClass.isInstance(wrapper) || slots.isEmpty()) {
            return null;
        }
        try {
            Object raw = orderedComponents.get(wrapper);
            if (!(raw instanceof Map)) {
                return null;
            }
            List<?> reqs = itemRequirements((Map<?, ?>) raw, ioType);
            if (reqs == null || reqs.size() < slots.size()) {
                return null;
            }
            float[] out = new float[slots.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = 1.0f;
                Object req = reqs.get(i);
                if (!requirementItem.isInstance(req) || !describes(req, slots.get(i))) {
                    continue;
                }
                Object chance = chanceField.get(req);
                if (chance instanceof Float) {
                    float c = ((Float) chance).floatValue();
                    // Out of range is refused rather than clamped, matching
                    // `hei_dump._consume_chance`: a negative chance would price an
                    // ingredient NEGATIVE downstream, which is arbitrage rather than a
                    // mispriced route. On the output side a negative or above-one yield is
                    // worse still, because the yield lands in a DIVISOR.
                    //
                    // ZERO STAYS IN RANGE ON BOTH SIDES. On an input it is the catalyst case
                    // #175 exists for. On an output it would mean a recipe that never yields
                    // the stack, which the reader's [0.0, 1.0] contract admits and which the
                    // reference pack does not contain (see `resolveChance` for the census).
                    // Refusing it would silently restore the every-run yield #223 is about.
                    if (c >= 0.0f && c <= 1.0f) {
                        out[i] = c;
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The ItemStack requirement list for one IO type out of the wrapper's ordered map.
     *
     * Package-visible so `SchemaEightTest` can drive it with a hand-built map. It is the one
     * piece of this reader that needs no Modular Machinery to exercise, and it is where the
     * `IO_INPUT` / `IO_OUTPUT` keys are actually matched, so leaving it private would leave
     * the string comparison that selects a whole side of the dump entirely unasserted.
     */
    static List<?> itemRequirements(Map<?, ?> byIoType, String ioType) {
        for (Map.Entry<?, ?> e : byIoType.entrySet()) {
            if (e.getKey() == null || !ioType.equals(e.getKey().toString())
                    || !(e.getValue() instanceof Map)) {
                continue;
            }
            Object list = ((Map<?, ?>) e.getValue()).get(ItemStack.class);
            return list instanceof List ? (List<?>) list : null;
        }
        return null;
    }

    /**
     * Whether this requirement is plausibly the one that produced this slot.
     *
     * Deliberately loose: it is a guard against a whole-list SHIFT, not an equality test.
     * An oredict requirement legitimately expands to many stacks, and a stack requirement's
     * count and NBT are allowed to differ from what JEI displayed, so demanding more than a
     * shared registry name would reject correct matches and lose the fix.
     */
    private static boolean describes(Object req, List<Object> slot) {
        try {
            if (slot.isEmpty()) {
                return false;
            }
            Object ore = oreDictField.get(req);
            if (ore instanceof String && !((String) ore).isEmpty()) {
                return true;  // an oredict slot's members cannot be checked by identity
            }
            Object required = requiredField.get(req);
            if (!(required instanceof ItemStack) || ((ItemStack) required).isEmpty()) {
                return false;
            }
            Item want = ((ItemStack) required).getItem();
            for (Object alt : slot) {
                if (alt instanceof ItemStack && !((ItemStack) alt).isEmpty()
                        && ((ItemStack) alt).getItem() == want) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
