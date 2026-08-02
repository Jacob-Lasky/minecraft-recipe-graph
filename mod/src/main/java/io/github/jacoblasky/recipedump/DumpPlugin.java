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
 */
@JEIPlugin
public class DumpPlugin implements IModPlugin {

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
        RecipeDumpMod.ingredients = registry.getIngredientRegistry();
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        RecipeDumpMod.runtime = jeiRuntime;
    }
}
