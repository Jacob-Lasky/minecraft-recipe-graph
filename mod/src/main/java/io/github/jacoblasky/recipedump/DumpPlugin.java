package io.github.jacoblasky.recipedump;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;

/**
 * Captures the JEI runtime so the dump command can reach the recipe registry.
 *
 * onRuntimeAvailable is the only sanctioned way to get an IJeiRuntime; it fires
 * after every mod's categories and recipes are registered, which is exactly when a
 * complete dump becomes possible. Dumping any earlier yields a partial registry.
 *
 * THE TWO CAPTURED REFERENCES LIVE HERE RATHER THAN ON `RecipeDumpMod` because their types
 * are JEI's. Since #19 Phase 2 the mod class loads on a dedicated server, where JEI is not
 * installed; a field typed `IJeiRuntime` on that class is a resolution failure waiting for
 * whichever JVM decides to resolve field types eagerly. This class is only ever loaded BY
 * JEI, so if it loads at all, JEI is present.
 */
@JEIPlugin
public class DumpPlugin implements IModPlugin {

    /** Set by {@link #onRuntimeAvailable}; null before then, and forever without JEI. */
    public static IJeiRuntime runtime;

    /**
     * JEI's complete item list, captured in {@link #register}; null before then.
     *
     * SEPARATE FROM `runtime` BECAUSE IT HAS TO BE -- see `register` below. It is the source
     * for every per-ITEM file (emc.json, machine_names.json's blueprint half, the icon
     * atlas), as opposed to the per-RECIPE walk that fills recipes.ndjson. The two
     * populations differ: an item nothing crafts and nothing consumes appears here and
     * nowhere in the recipe stream, and #50's whole subject is drop-only items.
     */
    public static mezz.jei.api.ingredients.IIngredientRegistry ingredients;

    /**
     * The ingredient registry has to be taken HERE, not off the runtime, because
     * `IJeiRuntime` in HadEnoughItems 4.28.1 simply does not expose it -- six getters and
     * not one of them `getIngredientRegistry`. `IModRegistry` does, and this is the only
     * callback that hands one over. DO NOT go looking for it on the runtime again.
     *
     * Taking the reference this early is safe because the registry is LIVE: `register` runs
     * while plugins are still adding ingredients, and the object goes on filling up
     * afterwards. Nothing reads it until `/recipedump`, by which time it is complete.
     */
    @Override
    public void register(IModRegistry registry) {
        ingredients = registry.getIngredientRegistry();
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }
}
