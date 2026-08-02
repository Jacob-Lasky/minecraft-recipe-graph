package io.github.jacoblasky.recipedump;

import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.client.jei.PlannerGuiHandler;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IIngredientRegistry;

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
    public static IIngredientRegistry ingredients;

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
        // A GLOBAL handler, not an advanced one. `IAdvancedGuiHandler` is bounded to
        // `GuiContainer` and the planner opens a plain `GuiScreen`, so an advanced handler
        // would register and never fire. See PlannerGuiHandler for the javap evidence.
        registry.addGlobalGuiHandlers(new PlannerGuiHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        // HERE AND NOT IN `register`, because this is the callback that proves a runtime
        // exists -- and a NodeActions installed without one would answer true to
        // `canShowInRecipeViewer` and then open nothing.
        //
        // THE REAL HOLDER, which is the one argument #157 said this would become. It answers
        // null until the graph is READY -- exactly what NO_GRAPH did -- so the two
        // recipe-viewer entries stay hidden during the 5.47 s load and appear afterwards
        // without anything re-installing.
        //
        // A STATIC GETTER RATHER THAN A CACHED GRAPH. `GraphService.graph()` must be called
        // per invocation, not resolved once here: `onRuntimeAvailable` fires long before the
        // load finishes, so a value captured now would be null for the rest of the session.
        // It is a volatile field read, which is what makes calling it per frame free.
        JeiNodeActions.install(new JeiNodeActions.GraphSource() {
            @Override
            public RecipeGraph graph() {
                return GraphService.get().graph();
            }
        });
    }
}
