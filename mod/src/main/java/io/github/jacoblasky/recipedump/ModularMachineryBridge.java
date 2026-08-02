package io.github.jacoblasky.recipedump;

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
}
