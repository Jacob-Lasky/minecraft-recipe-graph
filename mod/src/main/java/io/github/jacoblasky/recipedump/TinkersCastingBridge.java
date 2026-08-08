package io.github.jacoblasky.recipedump;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import mezz.jei.api.recipe.IRecipeWrapper;

/**
 * Tinkers' casting recipes, which say for themselves whether the cast is spent. Issue #175.
 *
 * WHAT PROBLEM THIS SOLVES. A Tinkers cast is permanent: you make one Ingot Cast and pour a
 * thousand ingots through it. The dump had no field for "not consumed", so every casting
 * recipe recorded the cast as spent on every run, and the cost model dutifully priced an
 * Ingot Cast at 43.92 against the 20.09 Iron Ingot it exists to make. The visible result is
 * that the planner routes AROUND casting: asked for 5 Iron Ingots it casts a Block of Iron
 * for 1,296 mB and breaks it up, rather than casting 5 ingots for 720 mB, because the block
 * basin takes no cast and so dodges a bill that does not exist. Jake spotted it on a hopper.
 *
 * 14,409 recipes in the reference pack consume a cast or mold; 14,354 of them are the two
 * categories this bridge covers. The rest is Modular Machinery ({@link
 * ModularMachineryBridge}) and three crafting-grid recipes (#228).
 *
 * ONE BRIDGE COVERS BOTH CATEGORIES, WHICH IS NOT A COINCIDENCE WORTH RELYING ON TWICE.
 * `tconstruct.casting_table` and `tinker_io:smart_output` are separate mods, and Tinker I/O's
 * `SmartOutputRecipeWrapper` is a field-for-field copy of Tinkers' `CastingRecipeWrapper`
 * down to the `private final CastingRecipe recipe`. So the FIELD IS FOUND BY TYPE, NOT BY
 * NAME: a third mod that copies the same wrapper under a different field name still resolves,
 * and a future Tinkers that renames the field does not silently stop reporting catalysts.
 *
 * REFLECTION FOR THE SAME REASON AS {@link ProjectEBridge} AND {@link ModularMachineryBridge}:
 * a pack without Tinkers must still dump, and a hard reference would stop DumpCommand loading
 * at all. The target is deliberately the INTERFACE method
 * `slimeknights.tconstruct.library.smeltery.ICastingRecipe#consumesCast()`, which is public
 * and abstract, so `BucketCastingRecipe`, `OreCastingRecipe` and `PreferenceCastingRecipe` all
 * answer through it. Reading the protected `consumesCast` FIELD on the concrete class would
 * work today and break on the first subclass that computes it.
 *
 * Verified by disassembling the reference pack's own jars rather than from documentation.
 */
final class TinkersCastingBridge {

    private static final String CASTING_RECIPE =
            "slimeknights.tconstruct.library.smeltery.ICastingRecipe";

    private static Class<?> castingRecipe;
    private static Method consumesCast;
    private static boolean resolved;
    private static String absence = "not resolved yet";

    /**
     * Per wrapper CLASS, the field holding the casting recipe, or null when there is none.
     *
     * THE NEGATIVE ANSWER IS CACHED TOO, and that is the point of the map rather than an
     * optimisation detail. The dump walks ~335,000 wrappers, the overwhelming majority of
     * which are not casting recipes, and `getDeclaredFields` up a class hierarchy per wrapper
     * would be paid on every one of them. A null VALUE means "this class was examined and has
     * no casting recipe"; a missing KEY means "not examined yet".
     */
    private static final Map<Class<?>, Field> FIELDS = new HashMap<Class<?>, Field>();

    private TinkersCastingBridge() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            castingRecipe = Class.forName(CASTING_RECIPE);
            consumesCast = castingRecipe.getMethod("consumesCast");
            absence = "";
        } catch (ClassNotFoundException notInstalled) {
            absence = "Tinkers' Construct is not installed";
        } catch (Throwable t) {
            absence = "Tinkers present but ICastingRecipe#consumesCast did not resolve: " + t;
        }
    }

    static boolean available() {
        resolve();
        return castingRecipe != null && consumesCast != null;
    }

    /** Why {@link #available} is false, or "" when it is true. */
    static String absence() {
        resolve();
        return absence;
    }

    private static synchronized Field fieldOf(Class<?> wrapperClass) {
        if (FIELDS.containsKey(wrapperClass)) {
            return FIELDS.get(wrapperClass);
        }
        Field found = null;
        for (Class<?> c = wrapperClass; c != null && found == null; c = c.getSuperclass()) {
            Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (Throwable t) {
                break;  // a security manager or a broken class: treat as no casting recipe
            }
            for (Field f : declared) {
                if (!castingRecipe.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable notAccessible) {
                    continue;
                }
                found = f;
                break;
            }
        }
        FIELDS.put(wrapperClass, found);
        return found;
    }

    /**
     * The consume chance for this wrapper's ITEM input slots, or null when it has nothing to
     * say. 0.0f means the cast survives the run; 1.0f means it is spent.
     *
     * WHY ONE ANSWER FOR EVERY ITEM SLOT RATHER THAN ONE PER SLOT. A casting recipe's only
     * item input IS the cast -- the metal arrives as a fluid, which this never touches. So
     * "the item inputs of this recipe" and "the cast" are the same set, and a per-slot
     * mapping would be inventing a distinction the recipe does not have. A recipe with no
     * cast at all (`hasCast()` false) simply has no item input slots for the answer to reach.
     *
     * NEVER APPLIED TO FLUIDS BY THE CALLER, and that is load-bearing rather than tidy: the
     * molten metal is genuinely consumed, so marking it permanent would make every casting
     * recipe free and is a far worse failure than the one being fixed.
     */
    static Float itemInputChance(IRecipeWrapper wrapper) {
        if (!available() || wrapper == null) {
            return null;
        }
        Field field = fieldOf(wrapper.getClass());
        if (field == null) {
            return null;
        }
        try {
            Object recipe = field.get(wrapper);
            if (recipe == null) {
                return null;
            }
            Object spent = consumesCast.invoke(recipe);
            if (!(spent instanceof Boolean)) {
                return null;
            }
            return ((Boolean) spent).booleanValue() ? Float.valueOf(1.0f) : Float.valueOf(0.0f);
        } catch (Throwable t) {
            // One unreadable recipe must not cost the other 14,353, and a missing answer
            // defaults to "spent", which is the pre-#175 behaviour rather than a new claim.
            return null;
        }
    }
}
