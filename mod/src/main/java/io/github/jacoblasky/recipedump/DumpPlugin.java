package io.github.jacoblasky.recipedump;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
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

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        RecipeDumpMod.runtime = jeiRuntime;
    }
}
