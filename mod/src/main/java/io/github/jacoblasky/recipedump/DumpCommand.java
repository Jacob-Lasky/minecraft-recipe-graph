package io.github.jacoblasky.recipedump;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * `/recipedump` -- writes recipes.ndjson, oredict.json and names.json into
 * &lt;gamedir&gt;/mc-recipe-dump/.
 *
 * NDJSON (one recipe per line) rather than one big JSON document, so a 100k-recipe
 * dump streams on both ends and a single malformed recipe cannot invalidate the
 * whole file. Keep the schema in sync with recipegraph/sources/hei_dump.py.
 *
 * Every per-recipe call is individually guarded: third-party recipe wrappers do
 * throw (missing items, broken NBT, wrappers that assume a live GUI), and one bad
 * wrapper must not abort a dump that is otherwise complete.
 */
public class DumpCommand extends CommandBase {

    @Override
    public String getName() {
        return "recipedump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/recipedump -- dump all JEI recipes for offline crafting-tree tools";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(net.minecraft.server.MinecraftServer server, ICommandSender sender,
                        String[] args) {
        if (RecipeDumpMod.runtime == null) {
            reply(sender, "JEI runtime not available yet -- open the recipe GUI once, then retry.");
            return;
        }
        File dir = new File(Minecraft.getMinecraft().gameDir, "mc-recipe-dump");
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            reply(sender, "cannot create " + dir + ": " + e);
            return;
        }

        Map<String, String> names = new LinkedHashMap<>();
        // Per-category tallies so a coverage gap can be attributed to a category and mod
        // rather than merely counted. A bare failure count says coverage is incomplete
        // but not where, which cannot tell you whether a missing recipe matters.
        Map<String, int[]> perCategory = new LinkedHashMap<>();  // uid -> {dumped, threw, empty}
        Map<String, String> categoryMod = new LinkedHashMap<>();
        List<String> skips = new ArrayList<>();
        int recipes = 0;
        int categories = 0;
        int failed = 0;

        IRecipeRegistry registry = RecipeDumpMod.runtime.getRecipeRegistry();
        File out = new File(dir, "recipes.ndjson");
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(out.toPath()), StandardCharsets.UTF_8))) {
            for (IRecipeCategory<?> category : registry.getRecipeCategories()) {
                categories++;
                String uid = safe(category.getUid());
                String title = safe(category.getTitle());
                String modName = "";
                try {
                    modName = safe(category.getModName());
                } catch (Throwable ignored) {
                    // getModName is best-effort; its absence must not skip a category
                }
                categoryMod.put(uid, modName);
                int[] tally = perCategory.get(uid);
                if (tally == null) {
                    tally = new int[3];
                    perCategory.put(uid, tally);
                }

                List<?> wrappers;
                try {
                    wrappers = registry.getRecipeWrappers(cast(category));
                } catch (Throwable t) {
                    failed++;
                    tally[1]++;
                    skips.add(skipLine(uid, modName, -1, null, t, "getRecipeWrappers failed"));
                    continue;
                }
                int index = -1;
                for (Object obj : wrappers) {
                    index++;
                    if (!(obj instanceof IRecipeWrapper)) {
                        continue;
                    }
                    try {
                        String line = encode((IRecipeWrapper) obj, uid, title, names);
                        if (line != null) {
                            w.write(line);
                            w.write('\n');
                            recipes++;
                            tally[0]++;
                        } else {
                            // Parsed fine but yielded no outputs, so it is not a usable
                            // graph edge. Recorded separately from a thrown failure
                            // because the causes and the fixes are different.
                            tally[2]++;
                            skips.add(skipLine(uid, modName, index, obj, null, "no outputs"));
                        }
                    } catch (Throwable t) {
                        failed++;
                        tally[1]++;
                        skips.add(skipLine(uid, modName, index, obj, t, "threw"));
                    }
                }
            }
        } catch (IOException e) {
            reply(sender, "write failed: " + e);
            return;
        }

        writeLines(new File(dir, "skipped.ndjson"), skips);
        writeSummary(new File(dir, "summary.json"), perCategory, categoryMod, recipes, failed);

        int ores = writeOreDict(new File(dir, "oredict.json"), names);
        writeNames(new File(dir, "names.json"), names);

        reply(sender, String.format(
                "dumped %d recipes from %d categories, %d oredict entries, %d names -> %s",
                recipes, categories, ores, names.size(), dir.getName()));
        reply(sender, String.format(
                "%d skipped (%d recorded in skipped.ndjson; see summary.json for per-category counts)",
                failed, skips.size()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IRecipeCategory cast(IRecipeCategory<?> c) {
        return (IRecipeCategory) c;
    }

    /**
     * One NDJSON record describing a recipe that did not make it into the dump.
     *
     * The wrapper's class name is the useful field: it names the mod and the recipe type
     * far more precisely than the category uid does, which is what makes the log
     * actionable rather than just a count.
     */
    private static String skipLine(String uid, String modName, int index, Object wrapper,
                                   Throwable t, String reason) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"cat\":\"").append(uid).append('"');
        if (!modName.isEmpty()) {
            sb.append(",\"mod\":\"").append(modName).append('"');
        }
        sb.append(",\"i\":").append(index);
        sb.append(",\"reason\":\"").append(safe(reason)).append('"');
        if (wrapper != null) {
            sb.append(",\"wrapper\":\"").append(safe(wrapper.getClass().getName())).append('"');
        }
        if (t != null) {
            sb.append(",\"err\":\"").append(safe(t.getClass().getName())).append('"');
            String msg = t.getMessage();
            if (msg != null) {
                if (msg.length() > 300) {
                    msg = msg.substring(0, 300) + "...";
                }
                sb.append(",\"msg\":\"").append(safe(msg)).append('"');
            }
            StackTraceElement[] trace = t.getStackTrace();
            if (trace != null && trace.length > 0) {
                sb.append(",\"at\":\"").append(safe(trace[0].toString())).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static void writeLines(File file, List<String> lines) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException ignored) {
            // the dump itself already succeeded; losing the skip log must not fail it
        }
    }

    private static void writeSummary(File file, Map<String, int[]> perCategory,
                                     Map<String, String> categoryMod,
                                     int recipes, int failed) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n \"recipes\": " + recipes + ",\n \"skipped\": " + failed
                    + ",\n \"categories\": {");
            boolean first = true;
            for (Map.Entry<String, int[]> e : perCategory.entrySet()) {
                if (!first) {
                    w.write(",");
                }
                first = false;
                int[] t = e.getValue();
                String mod = categoryMod.get(e.getKey());
                w.write("\n  \"" + e.getKey() + "\": {\"dumped\": " + t[0]
                        + ", \"threw\": " + t[1] + ", \"empty\": " + t[2]
                        + (mod != null && !mod.isEmpty() ? ", \"mod\": \"" + mod + "\"" : "")
                        + "}");
            }
            w.write(first ? "}\n}\n" : "\n }\n}\n");
        } catch (IOException ignored) {
            // same: a missing summary is not worth failing a good dump over
        }
    }

    private static String encode(IRecipeWrapper wrapper, String uid, String title,
                                 Map<String, String> names) {
        CollectingIngredients collected = new CollectingIngredients();
        wrapper.getIngredients(collected);

        String itemIn = stackSlots(collected.rawInputs(ItemStack.class), names);
        String itemOut = flatStacks(collected.rawOutputs(ItemStack.class), names);
        String fluidIn = fluidSlots(collected.rawInputs(FluidStack.class));
        String fluidOut = flatFluids(collected.rawOutputs(FluidStack.class));

        if (itemOut.equals("[]") && fluidOut.equals("[]")) {
            return null;  // nothing produced: not useful as a graph edge
        }
        return "{\"cat\":\"" + uid + "\",\"title\":\"" + title + "\",\"in\":" + itemIn
                + ",\"out\":" + itemOut + ",\"fin\":" + fluidIn + ",\"fout\":" + fluidOut + "}";
    }

    /** Nested: list of slots, each slot a list of interchangeable stacks. */
    private static String stackSlots(List<List<Object>> slots, Map<String, String> names) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstSlot = true;
        for (List<Object> slot : slots) {
            List<String> alts = new ArrayList<>();
            for (Object o : slot) {
                String s = stack(o, names);
                if (s != null) {
                    alts.add(s);
                }
            }
            if (alts.isEmpty()) {
                continue;  // empty slot (a spacer in the recipe grid)
            }
            if (!firstSlot) {
                sb.append(',');
            }
            firstSlot = false;
            sb.append('[').append(String.join(",", alts)).append(']');
        }
        return sb.append(']').toString();
    }

    /** Flat: outputs collapse to one stack per slot; alternatives do not matter. */
    private static String flatStacks(List<List<Object>> slots, Map<String, String> names) {
        List<String> out = new ArrayList<>();
        for (List<Object> slot : slots) {
            for (Object o : slot) {
                String s = stack(o, names);
                if (s != null) {
                    out.add(s);
                    break;
                }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    private static String fluidSlots(List<List<Object>> slots) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstSlot = true;
        for (List<Object> slot : slots) {
            List<String> alts = new ArrayList<>();
            for (Object o : slot) {
                String s = fluid(o);
                if (s != null) {
                    alts.add(s);
                }
            }
            if (alts.isEmpty()) {
                continue;
            }
            if (!firstSlot) {
                sb.append(',');
            }
            firstSlot = false;
            sb.append('[').append(String.join(",", alts)).append(']');
        }
        return sb.append(']').toString();
    }

    private static String flatFluids(List<List<Object>> slots) {
        List<String> out = new ArrayList<>();
        for (List<Object> slot : slots) {
            for (Object o : slot) {
                String s = fluid(o);
                if (s != null) {
                    out.add(s);
                    break;
                }
            }
        }
        return "[" + String.join(",", out) + "]";
    }

    private static String stack(Object o, Map<String, String> names) {
        if (!(o instanceof ItemStack)) {
            return null;
        }
        ItemStack stack = (ItemStack) o;
        if (stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = stack.getItem().getRegistryName();
        if (id == null) {
            return null;
        }
        int meta = stack.getItemDamage();
        if (names != null) {
            String key = meta == 0 ? id.toString() : id + ":" + meta;
            if (!names.containsKey(key)) {
                try {
                    names.put(key, stack.getDisplayName());
                } catch (Throwable ignored) {
                    // a few modded items throw on getDisplayName outside a render pass
                }
            }
        }
        return "{\"i\":\"" + safe(id.toString()) + "\",\"m\":" + meta
                + ",\"c\":" + stack.getCount() + "}";
    }

    private static String fluid(Object o) {
        if (!(o instanceof FluidStack)) {
            return null;
        }
        FluidStack fs = (FluidStack) o;
        if (fs.getFluid() == null) {
            return null;
        }
        return "{\"f\":\"" + safe(fs.getFluid().getName()) + "\",\"a\":" + fs.amount + "}";
    }

    private static int writeOreDict(File file, Map<String, String> names) {
        int count = 0;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            boolean first = true;
            for (String ore : OreDictionary.getOreNames()) {
                if (ore == null) {
                    continue;
                }
                List<String> members = new ArrayList<>();
                for (ItemStack stack : OreDictionary.getOres(ore, false)) {
                    ResourceLocation id = stack.getItem().getRegistryName();
                    if (id == null) {
                        continue;
                    }
                    members.add("\"" + safe(id.toString()) + ":" + stack.getItemDamage() + "\"");
                }
                if (members.isEmpty()) {
                    continue;
                }
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write(" \"" + safe(ore) + "\": [" + String.join(",", members) + "]");
                count++;
            }
            w.write("\n}\n");
        } catch (IOException ignored) {
            // a partial oredict is still better than none; the python side tolerates it
        }
        return count;
    }

    private static void writeNames(File file, Map<String, String> names) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            boolean first = true;
            for (Map.Entry<String, String> e : names.entrySet()) {
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write(" \"" + safe(e.getKey()) + "\": \"" + safe(e.getValue()) + "\"");
            }
            w.write("\n}\n");
        } catch (IOException ignored) {
            // names.csv from AE2 is an adequate fallback on the python side
        }
    }

    /** Minimal JSON string escaping; avoids depending on a JSON library. */
    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static void reply(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString("[recipedump] " + msg));
    }
}
