package io.github.jacoblasky.recipedump;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.oredict.OreDictionary;

/**
 * `/recipedump` -- writes recipes.ndjson, catalysts.json, oredict.json, names.json,
 * skipped.ndjson and summary.json into &lt;gamedir&gt;/mc-recipe-dump/.
 *
 * The dump is SPREAD ACROSS CLIENT TICKS rather than run inline. It is only about a
 * second of work, but a second spent inside a command handler is a second the render
 * loop never gets, so the whole game visibly freezes. Doing it in ~15ms slices lets
 * frames render in between, which also makes the progress messages actually appear
 * while it runs.
 *
 * DO NOT "simplify" this back to a single inline loop with a chat message in front of
 * it. Chat is drawn on the next frame, so with an inline loop the "starting" message
 * and the "finished" message land in the same frame and the freeze is unchanged --
 * printing a warning does not fix it, yielding to the render loop does.
 *
 * The work must stay on the client thread: JEI's registry, ItemStack and the recipe
 * wrappers are not thread-safe, so this cannot simply be handed to a worker thread.
 *
 * NDJSON (one recipe per line) rather than one big JSON document, so a 300k-recipe
 * dump streams on both ends and a single malformed recipe cannot invalidate the whole
 * file. Keep the schema in sync with recipegraph/sources/hei_dump.py.
 */
public class DumpCommand extends CommandBase {

    /** Non-null while a dump is in flight; guards against a second concurrent run. */
    private static Runner active;

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
        if (active != null) {
            reply(sender, "a dump is already running; wait for it to finish.");
            return;
        }
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

        IRecipeRegistry registry = RecipeDumpMod.runtime.getRecipeRegistry();
        List<IRecipeCategory> categories;
        try {
            categories = new ArrayList<IRecipeCategory>(registry.getRecipeCategories());
        } catch (Throwable t) {
            reply(sender, "could not list recipe categories: " + t);
            return;
        }

        Runner runner;
        try {
            runner = new Runner(sender, dir, registry, categories);
        } catch (IOException e) {
            reply(sender, "cannot open output file: " + e);
            return;
        }
        active = runner;
        MinecraftForge.EVENT_BUS.register(runner);
        reply(sender, String.format("dumping %d recipe categories...", categories.size()));
    }

    /**
     * Incremental dump driven by the client tick, with a per-tick time budget.
     *
     * State is explicit (category index + wrapper iterator) rather than implicit in a
     * nested loop, because the walk has to be suspendable partway through a category.
     */
    public static class Runner {

        /** Per-tick work budget. A tick is 50ms, so this leaves most of it for rendering. */
        private static final long BUDGET_NANOS = 15_000_000L;

        private final ICommandSender sender;
        private final File dir;
        private final IRecipeRegistry registry;
        private final List<IRecipeCategory> categories;
        private final Writer writer;

        private final Map<String, String> names = new LinkedHashMap<String, String>();
        private final Map<String, int[]> perCategory = new LinkedHashMap<String, int[]>();
        private final Map<String, String> categoryMod = new LinkedHashMap<String, String>();
        private final List<String> skips = new ArrayList<String>();
        /**
         * {category uid: the item ids JEI shows as "made in"}.
         *
         * This is the AUTHORITATIVE category -> machine mapping and the reason it is worth
         * dumping: the python side otherwise has to guess the machine from the category's
         * display title, and a title is frequently the recipe TYPE rather than the machine
         * ("Casting" is made in a Casting Table, "Smelting" in a Smeltery Controller).
         * That heuristic failed on 343 of 521 categories in the reference pack, which then
         * had to be priced as "machine unidentified" -- 37,857 recipes the planner could
         * not reason about. Catalysts remove the guess entirely.
         */
        private final Map<String, List<String>> catalysts =
                new LinkedHashMap<String, List<String>>();

        private int catIndex = -1;
        private Iterator<?> wrappers;
        private String uid = "";
        private String title = "";
        private String modName = "";
        private int[] tally;
        private int wrapperIndex;

        private int recipes;
        private int failed;
        private int nextProgressPercent = 25;
        private final long startedAt = System.nanoTime();

        Runner(ICommandSender sender, File dir, IRecipeRegistry registry,
               List<IRecipeCategory> categories) throws IOException {
            this.sender = sender;
            this.dir = dir;
            this.registry = registry;
            this.categories = categories;
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(new File(dir, "recipes.ndjson").toPath()),
                    StandardCharsets.UTF_8));
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            long deadline = System.nanoTime() + BUDGET_NANOS;
            while (System.nanoTime() < deadline) {
                if (wrappers == null || !wrappers.hasNext()) {
                    if (!nextCategory()) {
                        finish();
                        return;
                    }
                    continue;
                }
                Object obj = wrappers.next();
                wrapperIndex++;
                handle(obj);
            }
            reportProgress();
        }

        /** Advance to the next category; false when every category is done. */
        private boolean nextCategory() {
            catIndex++;
            if (catIndex >= categories.size()) {
                return false;
            }
            IRecipeCategory<?> category = categories.get(catIndex);
            uid = safe(category.getUid());
            title = safe(category.getTitle());
            modName = "";
            try {
                modName = safe(category.getModName());
            } catch (Throwable ignored) {
                // getModName is best-effort; its absence must not skip a category
            }
            categoryMod.put(uid, modName);
            collectCatalysts(category);
            tally = perCategory.get(uid);
            if (tally == null) {
                tally = new int[3];
                perCategory.put(uid, tally);
            }
            wrapperIndex = -1;
            try {
                wrappers = registry.getRecipeWrappers(cast(category)).iterator();
            } catch (Throwable t) {
                failed++;
                tally[1]++;
                skips.add(skipLine(uid, modName, -1, null, t, "getRecipeWrappers failed"));
                wrappers = null;
            }
            return true;
        }

        /**
         * Record what JEI lists as the machine for this category.
         *
         * Only ItemStacks are kept. A catalyst can also be a FluidStack (a few categories
         * list a fluid), but a fluid is not a block the player can be told to place, and
         * the python side compares candidates against placed tile entities and inventory,
         * so a fluid catalyst would only ever fail to match.
         *
         * Order is preserved: JEI lists the primary machine first and upgraded or
         * alternative variants after it, so the first entry is the one to name in a
         * "machines to build" list.
         */
        private void collectCatalysts(IRecipeCategory<?> category) {
            List<String> ids = new ArrayList<String>();
            try {
                for (Object o : registry.getRecipeCatalysts(cast(category))) {
                    if (!(o instanceof ItemStack)) {
                        continue;
                    }
                    ItemStack stack = (ItemStack) o;
                    if (stack.isEmpty()) {
                        continue;
                    }
                    ResourceLocation id = stack.getItem().getRegistryName();
                    if (id == null) {
                        continue;
                    }
                    int meta = stack.getItemDamage();
                    // A wildcard-meta catalyst names the whole family; the python side
                    // normalises 32767 to `:*`, so pass it through unchanged.
                    String key = meta == 0 ? id.toString() : id + ":" + meta;
                    if (!ids.contains(key)) {
                        ids.add(key);
                    }
                }
            } catch (Throwable t) {
                // Best-effort, exactly like getModName: a category whose catalysts throw
                // must still have its recipes dumped. It falls back to title matching.
                skips.add(skipLine(uid, modName, -1, null, t, "getRecipeCatalysts failed"));
            }
            if (!ids.isEmpty()) {
                catalysts.put(uid, ids);
            }
        }

        private void handle(Object obj) {
            if (!(obj instanceof IRecipeWrapper)) {
                return;
            }
            try {
                String line = encode((IRecipeWrapper) obj, uid, title, names);
                if (line != null) {
                    writer.write(line);
                    writer.write('\n');
                    recipes++;
                    tally[0]++;
                } else {
                    // Parsed fine but yielded no outputs, so it is not a usable graph
                    // edge. Recorded separately from a thrown failure because the causes
                    // and the fixes are different.
                    tally[2]++;
                    skips.add(skipLine(uid, modName, wrapperIndex, obj, null, "no outputs"));
                }
            } catch (IOException io) {
                // Losing the output stream is fatal to the dump, unlike a bad wrapper.
                failed++;
                skips.add(skipLine(uid, modName, wrapperIndex, obj, io, "write failed"));
                finish();
            } catch (Throwable t) {
                failed++;
                tally[1]++;
                skips.add(skipLine(uid, modName, wrapperIndex, obj, t, "threw"));
            }
        }

        private void reportProgress() {
            int percent = (int) (100L * (catIndex + 1) / Math.max(categories.size(), 1));
            if (percent >= nextProgressPercent && percent < 100) {
                reply(sender, String.format("  %d%% -- %s recipes so far", percent,
                        formatCount(recipes)));
                while (nextProgressPercent <= percent) {
                    nextProgressPercent += 25;
                }
            }
        }

        private void finish() {
            MinecraftForge.EVENT_BUS.unregister(this);
            active = null;
            try {
                writer.close();
            } catch (IOException ignored) {
                // content is already flushed per-line by the BufferedWriter on close
            }

            writeLines(new File(dir, "skipped.ndjson"), skips);
            writeSummary(new File(dir, "summary.json"), perCategory, categoryMod,
                         recipes, failed);
            writeCatalysts(new File(dir, "catalysts.json"), catalysts);
            int ores = writeOreDict(new File(dir, "oredict.json"), names);
            writeNames(new File(dir, "names.json"), names);

            long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            reply(sender, String.format(
                    "done in %.1fs: %s recipes from %d categories, %d with a known machine, "
                            + "%s oredict entries, %s names -> %s",
                    ms / 1000.0, formatCount(recipes), perCategory.size(), catalysts.size(),
                    formatCount(ores), formatCount(names.size()), dir.getName()));
            reply(sender, String.format(
                    "%s skipped, all recorded in skipped.ndjson (per-category counts in summary.json)",
                    formatCount(failed)));
            // The files are useless on their own, and in-game chat is the only place the
            // player is looking at this moment, so name the next step and the URL.
            //
            // ONE step, not two: `serve` builds the graph itself when the dump is newer,
            // so telling anyone to run `build` first is telling them to do work the tool
            // already did. Keep this in step with cli.ensure_graph.
            //
            // The port matches recipegraph.server.DEFAULT_PORT; it cannot be shared across
            // the language boundary, so changing one means grepping 8765 for the others.
            //
            // Phrased as an instruction, NOT as "open http://localhost:8765" on its own: the
            // planner is a separate program that may not be installed or running, and
            // pointing at a dead URL is worse than saying nothing. DO NOT reduce this to
            // just the link.
            reply(sender, "next: run `recipegraph serve` and open http://localhost:8765 "
                    + "-- it will load this dump on the way up");
        }
    }

    private static String formatCount(int n) {
        return String.format("%,d", n);
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
        if (modName != null && !modName.isEmpty()) {
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

    private static String encode(IRecipeWrapper wrapper, String uid, String title,
                                 Map<String, String> names) throws IOException {
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
            List<String> alts = new ArrayList<String>();
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
        List<String> out = new ArrayList<String>();
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
            List<String> alts = new ArrayList<String>();
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
        List<String> out = new ArrayList<String>();
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
        String nbt = discriminator(stack);
        if (names != null) {
            String key = meta == 0 ? id.toString() : id + ":" + meta;
            if (nbt != null) {
                key = key + "#" + nbt;
            }
            if (!names.containsKey(key)) {
                try {
                    // Keyed by the DISCRIMINATED id, so "Forest Drone" and "Meadows
                    // Drone" get their own names. The digest is unreadable on its own;
                    // this is what makes it usable, and it is why the two must be
                    // written from the same place.
                    names.put(key, stack.getDisplayName());
                } catch (Throwable ignored) {
                    // a few modded items throw on getDisplayName outside a render pass
                }
            }
        }
        return "{\"i\":\"" + safe(id.toString()) + "\",\"m\":" + meta
                + ",\"c\":" + stack.getCount()
                + (nbt == null ? "" : ",\"n\":\"" + nbt + "\"") + "}";
    }

    /**
     * Tags that never change WHAT an item is, only its condition or presentation.
     *
     * Stripped before the digest so a repaired pickaxe and a fresh one stay one key.
     * Keep this list SHORT and justified: every entry is a claim that two stacks
     * differing only by that tag are interchangeable in a recipe, and a wrong entry
     * silently merges two ingredients that are not the same thing.
     */
    static final String[] COSMETIC_TAGS = {
        "RepairCost",     // anvil work penalty
        "display",        // rename and lore
        "HideFlags",      // tooltip presentation
        "Damage",         // durability; the meta already carries the variant
    };

    /**
     * A short, stable id for the part of an ItemStack's NBT that decides WHAT IT IS,
     * or null when the stack carries no identity beyond its id and meta.
     *
     * WHY THIS EXISTS. Every bee in the pack was the same four item keys, because the
     * species lives in the `Genome` tag and this method used to not exist. All 437
     * mutations dumped as one repeated edge, `produce.rootBees` read as one generic
     * drone making 323 unrelated items, and the breeding hierarchy was not merely
     * uncodified but unrepresentable. Same for trees, butterflies and chickens.
     *
     * A DIGEST rather than a decoded species name, on purpose. Reading Forestry's
     * chromosome layout would fix Forestry and nothing else; every mod that hides an
     * item's identity in NBT has its own structure, and there are ~410 of them. The
     * digest asks only "is this the same stack", which is the question the graph
     * actually needs. Readability comes from names.json, which is keyed by the
     * discriminated id and holds JEI's own display name -- "Forest Drone".
     *
     * The serialisation is canonical (compound keys sorted) because NBTTagCompound is
     * backed by a HashMap and its toString order is an implementation detail. A
     * discriminator that changed between dumps would split one item into two keys.
     */
    static String discriminator(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        NBTTagCompound copy = tag.copy();
        for (String cosmetic : COSMETIC_TAGS) {
            copy.removeTag(cosmetic);
        }
        if (copy.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        canonical(copy, sb);
        // FNV-1a, 48 bits kept. Not a checksum: it only has to separate the few thousand
        // NBT variants in one pack, and 48 bits makes a collision there vanishingly
        // unlikely while staying short enough to read in a key.
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < sb.length(); i++) {
            h = (h ^ sb.charAt(i)) * 0x100000001b3L;
        }
        return String.format("%012x", h & 0xffffffffffffL);
    }

    /**
     * NBT as a deterministic, LANGUAGE-NEUTRAL string. Compound keys sorted, lists in
     * order, every value tagged with its type.
     *
     * Deliberately not `NBTBase.toString()`, which is a Java implementation detail:
     * a byte renders "5b", a string comes back quoted and escaped, a float carries an
     * "f". The world-save reader on the python side has to be able to compute the SAME
     * digest for the same stack, or your bees in AE2 will not match the bees in a recipe,
     * and it cannot reproduce Java's formatting. Floats go in as their IEEE-754 bits for
     * the same reason: decimal float formatting differs between the two languages and
     * would silently split one item into two keys.
     *
     * THIS FORMAT IS PART OF SCHEMA 3. Changing it changes every discriminated key.
     */
    static void canonical(NBTBase node, StringBuilder sb) {
        switch (node.getId()) {
            case 1: sb.append('b').append(((NBTPrimitive) node).getByte()); break;
            case 2: sb.append('s').append(((NBTPrimitive) node).getShort()); break;
            case 3: sb.append('i').append(((NBTPrimitive) node).getInt()); break;
            case 4: sb.append('l').append(((NBTPrimitive) node).getLong()); break;
            case 5:
                sb.append('f').append(Float.floatToIntBits(((NBTPrimitive) node).getFloat()));
                break;
            case 6:
                sb.append('d').append(
                        Double.doubleToLongBits(((NBTPrimitive) node).getDouble()));
                break;
            case 7:
                sb.append('B');
                for (byte v : ((NBTTagByteArray) node).getByteArray()) {
                    sb.append(v).append(',');
                }
                break;
            case 8:
                // Raw characters, length-prefixed so a string can never be confused with
                // the structure around it.
                String s = ((NBTTagString) node).getString();
                sb.append('t').append(s.length()).append(':').append(s);
                break;
            case 9: {
                NBTTagList l = (NBTTagList) node;
                sb.append('[');
                for (int i = 0; i < l.tagCount(); i++) {
                    canonical(l.get(i), sb);
                    sb.append(';');
                }
                sb.append(']');
                break;
            }
            case 10: {
                NBTTagCompound c = (NBTTagCompound) node;
                List<String> keys = new ArrayList<String>(c.getKeySet());
                Collections.sort(keys);
                sb.append('{');
                for (String k : keys) {
                    sb.append(k.length()).append(':').append(k).append('=');
                    canonical(c.getTag(k), sb);
                    sb.append(';');
                }
                sb.append('}');
                break;
            }
            case 11:
                sb.append('I');
                for (int v : ((NBTTagIntArray) node).getIntArray()) {
                    sb.append(v).append(',');
                }
                break;
            default:
                // NBTTagEnd and anything a future Forge adds. Tagged by id so an unknown
                // type still contributes, and still contributes the SAME way every run.
                sb.append('x').append(node.getId()).append(':').append(node.toString());
                break;
        }
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

    /**
     * Bumped whenever the SHAPE of any dumped file changes, not when the mod version does.
     * The reader compares it and says so rather than misparsing a newer or older dump in
     * silence. 1 = recipes.ndjson + oredict + names + skipped + summary; 2 adds
     * catalysts.json; 3 adds the NBT discriminator `n` on stacks, and names.json keys
     * by the discriminated id.
     */
    static final int SCHEMA = 3;

    private static void writeSummary(File file, Map<String, int[]> perCategory,
                                     Map<String, String> categoryMod,
                                     int recipes, int failed) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            // Stamp what produced this. Without it a dump is undatable: the only signal
            // that catalysts.json was missing because the mod predated it, rather than
            // because the category list genuinely had none, was its absence.
            w.write("{\n \"mod_version\": \"" + safe(RecipeDumpMod.version()) + "\""
                    + ",\n \"schema\": " + SCHEMA);
            w.write(",\n \"recipes\": " + recipes + ",\n \"skipped\": " + failed
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

    private static void writeCatalysts(File file, Map<String, List<String>> catalysts) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            boolean first = true;
            for (Map.Entry<String, List<String>> e : catalysts.entrySet()) {
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                List<String> quoted = new ArrayList<String>();
                for (String id : e.getValue()) {
                    quoted.add("\"" + safe(id) + "\"");
                }
                w.write(" \"" + safe(e.getKey()) + "\": [" + String.join(",", quoted) + "]");
            }
            w.write(first ? "}\n" : "\n}\n");
        } catch (IOException ignored) {
            // Losing this file costs route quality, not correctness: the python side falls
            // back to matching the category title against item names.
        }
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
                List<String> members = new ArrayList<String>();
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
