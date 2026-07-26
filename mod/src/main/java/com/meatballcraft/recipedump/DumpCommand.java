package com.meatballcraft.recipedump;

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
 * `/mbcdump` -- writes recipes.ndjson, oredict.json and names.json into
 * &lt;gamedir&gt;/mbc-recipe-dump/.
 *
 * NDJSON (one recipe per line) rather than one big JSON document, so a 100k-recipe
 * dump streams on both ends and a single malformed recipe cannot invalidate the
 * whole file. Keep the schema in sync with mbcgraph/sources/hei_dump.py.
 *
 * Every per-recipe call is individually guarded: third-party recipe wrappers do
 * throw (missing items, broken NBT, wrappers that assume a live GUI), and one bad
 * wrapper must not abort a dump that is otherwise complete.
 */
public class DumpCommand extends CommandBase {

    @Override
    public String getName() {
        return "mbcdump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mbcdump -- dump all JEI recipes for offline crafting-tree tools";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(net.minecraft.server.MinecraftServer server, ICommandSender sender,
                        String[] args) {
        if (MbcRecipeDump.runtime == null) {
            reply(sender, "JEI runtime not available yet -- open the recipe GUI once, then retry.");
            return;
        }
        File dir = new File(Minecraft.getMinecraft().gameDir, "mbc-recipe-dump");
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            reply(sender, "cannot create " + dir + ": " + e);
            return;
        }

        Map<String, String> names = new LinkedHashMap<>();
        int recipes = 0;
        int categories = 0;
        int failed = 0;

        IRecipeRegistry registry = MbcRecipeDump.runtime.getRecipeRegistry();
        File out = new File(dir, "recipes.ndjson");
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(out.toPath()), StandardCharsets.UTF_8))) {
            for (IRecipeCategory<?> category : registry.getRecipeCategories()) {
                categories++;
                String uid = safe(category.getUid());
                String title = safe(category.getTitle());
                List<?> wrappers;
                try {
                    wrappers = registry.getRecipeWrappers(cast(category));
                } catch (Throwable t) {
                    failed++;
                    continue;
                }
                for (Object obj : wrappers) {
                    if (!(obj instanceof IRecipeWrapper)) {
                        continue;
                    }
                    try {
                        String line = encode((IRecipeWrapper) obj, uid, title, names);
                        if (line != null) {
                            w.write(line);
                            w.write('\n');
                            recipes++;
                        }
                    } catch (Throwable t) {
                        failed++;
                    }
                }
            }
        } catch (IOException e) {
            reply(sender, "write failed: " + e);
            return;
        }

        int ores = writeOreDict(new File(dir, "oredict.json"), names);
        writeNames(new File(dir, "names.json"), names);

        reply(sender, String.format(
                "dumped %d recipes from %d categories (%d skipped), %d oredict entries, %d names -> %s",
                recipes, categories, failed, ores, names.size(), dir.getName()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IRecipeCategory cast(IRecipeCategory<?> c) {
        return (IRecipeCategory) c;
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
        sender.sendMessage(new TextComponentString("[mbcdump] " + msg));
    }
}
