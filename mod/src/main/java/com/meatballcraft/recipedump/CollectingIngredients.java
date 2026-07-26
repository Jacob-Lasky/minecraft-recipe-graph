package com.meatballcraft.recipedump;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IIngredientType;

/**
 * An {@link IIngredients} that just records what a recipe wrapper writes into it.
 *
 * JEI's own Ingredients implementation is internal, not API, so a plugin that wants
 * to read recipe contents has to supply its own collector and hand it to
 * IRecipeWrapper#getIngredients. DO NOT try to reflect into mezz.jei.startup.*
 * instead -- that is exactly the fragile coupling that broke JEIExporter between
 * JEI 4.8 and HadEnoughItems 4.28.
 *
 * Both the IIngredientType and the deprecated Class overloads must be implemented;
 * older mods' wrappers still call the Class ones.
 */
public class CollectingIngredients implements IIngredients {

    private final Map<Class<?>, List<List<Object>>> inputs = new HashMap<>();
    private final Map<Class<?>, List<List<Object>>> outputs = new HashMap<>();

    @SuppressWarnings("unchecked")
    private static <T> List<List<T>> get(Map<Class<?>, List<List<Object>>> map, Class<?> cls) {
        List<List<Object>> found = map.get(cls);
        if (found == null) {
            return Collections.emptyList();
        }
        return (List<List<T>>) (List<?>) found;
    }

    private static void putLists(Map<Class<?>, List<List<Object>>> map, Class<?> cls,
                                 List<? extends List<?>> values) {
        List<List<Object>> slots = new ArrayList<>();
        for (List<?> slot : values) {
            List<Object> alts = new ArrayList<>();
            if (slot != null) {
                for (Object o : slot) {
                    if (o != null) {
                        alts.add(o);
                    }
                }
            }
            slots.add(alts);
        }
        map.put(cls, slots);
    }

    private static void putFlat(Map<Class<?>, List<List<Object>>> map, Class<?> cls,
                                List<?> values) {
        List<List<Object>> slots = new ArrayList<>();
        for (Object o : values) {
            slots.add(o == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(o)));
        }
        map.put(cls, slots);
    }

    public List<List<Object>> rawInputs(Class<?> cls) {
        return inputs.getOrDefault(cls, Collections.emptyList());
    }

    public List<List<Object>> rawOutputs(Class<?> cls) {
        return outputs.getOrDefault(cls, Collections.emptyList());
    }

    // ---- IIngredientType overloads ----

    @Override
    public <T> void setInput(IIngredientType<T> type, T ingredient) {
        putFlat(inputs, type.getIngredientClass(), Collections.singletonList(ingredient));
    }

    @Override
    public <T> void setInputs(IIngredientType<T> type, List<T> values) {
        putFlat(inputs, type.getIngredientClass(), values);
    }

    @Override
    public <T> void setInputLists(IIngredientType<T> type, List<List<T>> values) {
        putLists(inputs, type.getIngredientClass(), values);
    }

    @Override
    public <T> void setOutput(IIngredientType<T> type, T ingredient) {
        putFlat(outputs, type.getIngredientClass(), Collections.singletonList(ingredient));
    }

    @Override
    public <T> void setOutputs(IIngredientType<T> type, List<T> values) {
        putFlat(outputs, type.getIngredientClass(), values);
    }

    @Override
    public <T> void setOutputLists(IIngredientType<T> type, List<List<T>> values) {
        putLists(outputs, type.getIngredientClass(), values);
    }

    @Override
    public <T> List<List<T>> getInputs(IIngredientType<T> type) {
        return get(inputs, type.getIngredientClass());
    }

    @Override
    public <T> List<List<T>> getOutputs(IIngredientType<T> type) {
        return get(outputs, type.getIngredientClass());
    }

    // ---- deprecated Class overloads ----

    @Override
    public <T> void setInput(Class<? extends T> cls, T ingredient) {
        putFlat(inputs, cls, Collections.singletonList(ingredient));
    }

    @Override
    public <T> void setInputs(Class<? extends T> cls, List<T> values) {
        putFlat(inputs, cls, values);
    }

    @Override
    public <T> void setInputLists(Class<? extends T> cls, List<List<T>> values) {
        putLists(inputs, cls, values);
    }

    @Override
    public <T> void setOutput(Class<? extends T> cls, T ingredient) {
        putFlat(outputs, cls, Collections.singletonList(ingredient));
    }

    @Override
    public <T> void setOutputs(Class<? extends T> cls, List<T> values) {
        putFlat(outputs, cls, values);
    }

    @Override
    public <T> void setOutputLists(Class<? extends T> cls, List<List<T>> values) {
        putLists(outputs, cls, values);
    }

    @Override
    public <T> List<List<T>> getInputs(Class<? extends T> cls) {
        return get(inputs, cls);
    }

    @Override
    public <T> List<List<T>> getOutputs(Class<? extends T> cls) {
        return get(outputs, cls);
    }
}
