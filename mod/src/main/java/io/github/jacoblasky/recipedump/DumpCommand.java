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
 * skipped.ndjson, summary.json and nbt_trace.json into &lt;gamedir&gt;/mc-recipe-dump/.
 * `/recipedump notrace` skips the last of those.
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
        return "/recipedump [" + NO_TRACE_ARG + "] -- dump all JEI recipes for offline "
                + "crafting-tree tools; " + NO_TRACE_ARG + " skips nbt_trace.json (#80)";
    }

    /**
     * Argument that SUPPRESSES `nbt_trace.json`. Issue #80's diagnostic is ON by default.
     *
     * IT SHIPPED OPT-IN AND THAT WAS WRONG. The reasoning was that no part of
     * `recipegraph build` reads the file, so a normal dump has no reason to carry it. That
     * weighs the wrong cost. Measured on the reference pack the trace is 34.3 MB against a
     * 245 MB dump -- 14% -- and the expensive part of producing one is not the bytes, it is
     * a launch of the game.
     *
     * The asymmetry is what decides it. The file CANNOT be reconstructed afterwards: the
     * NBT it describes exists only in a running JVM, which is the whole reason #80 could not
     * be investigated for months. And proving churn needs TWO dumps carrying it, because the
     * effect only appears BETWEEN JVM runs -- so opt-in means every dump is a coin flip and
     * two in a row is the unlikely case. Default-on makes any two consecutive dumps
     * comparable, which is the only state in which the question is answerable at all.
     *
     * Fails SAFE in the right direction too: a mistyped `notrace` writes the trace anyway,
     * where a mistyped opt-in silently produced a dump that could not answer the question it
     * was run for. Compared as lower case, and a leftover `nbttrace` from the older docs is
     * simply ignored and still gets what it asked for.
     */
    static final String NO_TRACE_ARG = "notrace";

    /** True unless `args` asks to suppress the trace. Unknown args are ignored, as before. */
    static boolean wantsTrace(String[] args) {
        if (args == null) {
            return true;
        }
        for (String a : args) {
            if (a != null && NO_TRACE_ARG.equalsIgnoreCase(a.trim())) {
                return false;
            }
        }
        return true;
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

        boolean traceNbt = wantsTrace(args);
        Runner runner;
        try {
            runner = new Runner(sender, dir, registry, categories, traceNbt);
        } catch (IOException e) {
            reply(sender, "cannot open output file: " + e);
            return;
        }
        active = runner;
        MinecraftForge.EVENT_BUS.register(runner);
        reply(sender, String.format("dumping %d recipe categories...", categories.size()));
        if (traceNbt) {
            // Say it up front either way. The trace being DEFAULT is the surprising half
            // now, and a player who does not know it is being written cannot choose to
            // skip it; a player who asked to skip needs to see that it took.
            reply(sender, "  also writing nbt_trace.json (per-tag NBT digests)");
        } else {
            reply(sender, "  skipping nbt_trace.json -- this dump cannot be compared "
                    + "against another for #80 digest churn");
        }
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

        private final KeySink sink;
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
               List<IRecipeCategory> categories, boolean traceNbt) throws IOException {
            this.sender = sender;
            this.dir = dir;
            this.registry = registry;
            this.categories = categories;
            this.sink = new KeySink(traceNbt);
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
                String line = encode((IRecipeWrapper) obj, uid, title, sink);
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
            int ores = writeOreDict(new File(dir, "oredict.json"), sink.names());
            writeNames(new File(dir, "names.json"), sink.names());
            // Diagnostic, so it is written LAST and its absence is normal: every dump
            // that did not ask for it simply has no such file, exactly like a
            // pre-0.4.0 dump has no catalysts.json.
            int traced = sink.tracing()
                    ? writeNbtTrace(new File(dir, "nbt_trace.json"), sink.trace()) : 0;

            long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            reply(sender, String.format(
                    "done in %.1fs: %s recipes from %d categories, %d with a known machine, "
                            + "%s oredict entries, %s names -> %s",
                    ms / 1000.0, formatCount(recipes), perCategory.size(), catalysts.size(),
                    formatCount(ores), formatCount(sink.names().size()), dir.getName()));
            reply(sender, String.format(
                    "%s skipped, all recorded in skipped.ndjson (per-category counts in summary.json)",
                    formatCount(failed)));
            if (sink.tracing()) {
                reply(sender, String.format(
                        "nbt_trace.json: %s keys with identifying NBT", formatCount(traced)));
            }
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
                                 KeySink sink) throws IOException {
        CollectingIngredients collected = new CollectingIngredients();
        wrapper.getIngredients(collected);

        String itemIn = stackSlots(collected.rawInputs(ItemStack.class), sink);
        String itemOut = flatStacks(collected.rawOutputs(ItemStack.class), sink);
        String fluidIn = fluidSlots(collected.rawInputs(FluidStack.class));
        String fluidOut = flatFluids(collected.rawOutputs(FluidStack.class));

        if (itemOut.equals("[]") && fluidOut.equals("[]")) {
            return null;  // nothing produced: not useful as a graph edge
        }
        return "{\"cat\":\"" + uid + "\",\"title\":\"" + title + "\",\"in\":" + itemIn
                + ",\"out\":" + itemOut + ",\"fin\":" + fluidIn + ",\"fout\":" + fluidOut + "}";
    }

    /** Nested: list of slots, each slot a list of interchangeable stacks. */
    private static String stackSlots(List<List<Object>> slots, KeySink sink) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstSlot = true;
        for (List<Object> slot : slots) {
            List<String> alts = new ArrayList<String>();
            for (Object o : slot) {
                String s = stack(o, sink);
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
    private static String flatStacks(List<List<Object>> slots, KeySink sink) {
        List<String> out = new ArrayList<String>();
        for (List<Object> slot : slots) {
            for (Object o : slot) {
                String s = stack(o, sink);
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

    private static String stack(Object o, KeySink sink) {
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
        if (sink != null) {
            String key = meta == 0 ? id.toString() : id + ":" + meta;
            if (nbt != null) {
                key = key + "#" + nbt;
            }
            sink.record(key, stack);
        }
        return "{\"i\":\"" + safe(id.toString()) + "\",\"m\":" + meta
                + ",\"c\":" + stack.getCount()
                + (nbt == null ? "" : ",\"n\":\"" + nbt + "\"") + "}";
    }

    /**
     * The per-unique-key sinks a dump fills as it walks: display names always, and the
     * issue #80 NBT trace when `/recipedump nbttrace` asked for it.
     *
     * ONE object rather than two threaded parameters because they are the same kind of
     * thing -- write-once-per-discriminated-key -- and because the KEY FORMAT
     * (`id[:meta][#digest]`) has to be identical in both files or they cannot be joined.
     * Building that string in one place is what guarantees it.
     */
    static final class KeySink {

        private final Map<String, String> names = new LinkedHashMap<String, String>();
        /** null when not tracing, which is how every normal dump runs. */
        private final Map<String, String> trace;

        KeySink(boolean traceNbt) {
            this.trace = traceNbt ? new LinkedHashMap<String, String>() : null;
        }

        Map<String, String> names() {
            return names;
        }

        Map<String, String> trace() {
            return trace;
        }

        boolean tracing() {
            return trace != null;
        }

        void record(String key, ItemStack stack) {
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
            // GATED ON THE '#', not on tagDigests returning null, and the difference is
            // work rather than output. `stack` appends `#<digest>` if and only if the stack
            // has identifying NBT, so the hash IS the predicate. Testing it by calling
            // tagDigests and discarding a null instead means every stack WITHOUT identity
            // -- the majority -- is recomputed on each of its occurrences across ~117k
            // recipes, because nothing ever lands in the map to short-circuit the next one.
            // The dump runs on a 15ms per-tick budget, so that is frames.
            // `theTraceOnlyLooksAtKeysCarryingADigest` pins the coupling to the key format.
            if (trace != null && key.indexOf('#') >= 0 && !trace.containsKey(key)) {
                String digests = tagDigests(stack);
                if (digests != null) {
                    trace.put(key, digests);
                }
            }
        }
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
        NBTTagCompound copy = identityTag(stack);
        if (copy == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        canonical(copy, sb);
        return fnv(sb);
    }

    /**
     * The part of a stack's NBT that decides what it is: a copy with `COSMETIC_TAGS`
     * removed, or null when nothing identifying is left.
     *
     * EXTRACTED so `discriminator` and `tagDigests` cannot disagree about what "identity"
     * means. A trace that explained a digest computed over a DIFFERENT set of tags than
     * the digest itself would point at the wrong tag, and it would do so convincingly.
     */
    static NBTTagCompound identityTag(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        NBTTagCompound copy = tag.copy();
        for (String cosmetic : COSMETIC_TAGS) {
            copy.removeTag(cosmetic);
        }
        return copy.isEmpty() ? null : copy;
    }

    /**
     * Per-TOP-LEVEL-TAG digests for one stack, as a JSON object, or null when the stack
     * carries no identity. The whole diagnostic payload for issue #80.
     *
     * Two digests per tag:
     *
     *   "o"  the tag serialised the way the real digest serialises it, lists IN ORDER.
     *        Comparing this field for one item across TWO dumps names the tag that
     *        churned, which is the thing no dump on disk can currently answer.
     *   "u"  the same tag with list order made irrelevant. Comparing "o" against "u"
     *        WITHIN ONE dump says whether the tag could churn from list order at all:
     *        equal clears it, different makes it a suspect.
     *
     * The "u" field is what makes a SINGLE dump useful. Churn is a between-JVM-run effect,
     * so proving it needs two dumps and therefore two launches of the game; the suspect
     * list needs only one. Given #80 already measured that the churn concentrates in
     * tconstruct and plustic, a one-launch suspect list restricted to those mods is
     * plausibly enough to write the narrow fix.
     *
     * Top level only, deliberately. A deeper walk multiplies the output for a question
     * nobody has asked yet: the first thing needed is a tag NAME to put in the narrow
     * sort list, and #80's hypothesis is about a top-level trait or modifier tag. If the
     * trace comes back pointing at a tag whose own subtree needs splitting, that is a
     * second, cheaper change made with real data in hand.
     */
    static String tagDigests(ItemStack stack) {
        NBTTagCompound copy = identityTag(stack);
        if (copy == null) {
            return null;
        }
        List<String> keys = new ArrayList<String>(copy.getKeySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            NBTBase sub = copy.getTag(k);
            if (sub == null) {
                continue;
            }
            StringBuilder ordered = new StringBuilder();
            canonical(sub, ordered, false);
            StringBuilder sorted = new StringBuilder();
            canonical(sub, sorted, true);
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(safe(k)).append("\":{\"o\":\"").append(fnv(ordered))
               .append("\",\"u\":\"").append(fnv(sorted)).append("\"}");
        }
        return out.append('}').toString();
    }

    /**
     * FNV-1a over UTF-16 code units, low 48 bits, as twelve hex digits.
     *
     * Not a checksum: it only has to separate the few thousand NBT variants in one pack,
     * and 48 bits makes a collision there vanishingly unlikely while staying short enough
     * to read in a key.
     *
     * EXTRACTED so `discriminator` and the issue #80 trace hash the same way by
     * construction. A second copy of this loop is exactly the pair that drifts, and the
     * symptom of drift here would be a diagnostic that disagrees with the digest it is
     * supposed to explain. `recipegraph/nbt_digest.py` is the third implementation and is
     * held to it by `tests/fixtures/nbt_digest.json`.
     */
    static String fnv(CharSequence s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h = (h ^ s.charAt(i)) * 0x100000001b3L;
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
     *
     * The two-argument form IS that frozen format and is what `discriminator` calls. The
     * `sortLists` overload below is additive and OFF on this path: it exists only for the
     * issue #80 trace, never for a key that ships in a dump. `DigestFixtureTest` pins the
     * two-argument output against the cross-language fixture, so a change that reached the
     * digest could not pass.
     */
    static void canonical(NBTBase node, StringBuilder sb) {
        canonical(node, sb, false);
    }

    /**
     * As above, but with `sortLists` optionally making LIST order irrelevant.
     *
     * WHY THIS EXISTS, AND WHY IT IS NOT THE FIX. Issue #80: the digest moves between two
     * dumps of an unchanged pack for ~11,353 keys, concentrated in tconstruct and plustic
     * (69%), and the leading hypothesis is a trait/modifier list populated from a
     * hash-ordered collection, so its order permutes per JVM run. Comparing a tag's
     * ordered digest against its sorted one says whether that tag COULD churn that way:
     * equal means it holds no non-trivially-ordered list and is innocent, different means
     * it is a candidate.
     *
     * DO NOT promote sorting onto the digest path to "fix" the churn. List order is
     * genuinely semantic for other NBT (an inventory, a page order), so sorting globally
     * would merge items that are not the same item -- trading a split key for a wrong one,
     * which is worse because nothing reports it. #80 records this: the narrow fix sorts
     * only the specific named tags, and it cannot be written until the trace names them.
     */
    static void canonical(NBTBase node, StringBuilder sb, boolean sortLists) {
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
                if (sortLists) {
                    // Serialise each element on its own, then sort the RENDERED elements.
                    // Sorting the rendered strings rather than the tags keeps this
                    // comparable across nesting depths and needs no NBTBase ordering.
                    List<String> parts = new ArrayList<String>(l.tagCount());
                    for (int i = 0; i < l.tagCount(); i++) {
                        StringBuilder one = new StringBuilder();
                        canonical(l.get(i), one, true);
                        parts.add(one.toString());
                    }
                    Collections.sort(parts);
                    for (String p : parts) {
                        sb.append(p).append(';');
                    }
                } else {
                    for (int i = 0; i < l.tagCount(); i++) {
                        canonical(l.get(i), sb, false);
                        sb.append(';');
                    }
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
                    canonical(c.getTag(k), sb, sortLists);
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
     *
     * `nbt_trace.json` DELIBERATELY DID NOT BUMP THIS, and the "2 adds catalysts.json" entry
     * above is why that needs saying: adding a file has bumped the schema before, so the
     * next person adding one will reasonably reach for it.
     *
     * The distinction is whether the PIPELINE reads it. catalysts.json changes what
     * `recipegraph build` produces -- it is the authoritative category-to-machine mapping, so
     * its absence silently costs machine identification, and a reader is entitled to know.
     * nbt_trace.json is read by `tools/digest-churn.py` and by nothing else; `build`, `have`
     * and `serve` never open it, no existing file's shape moved, and the discriminated keys
     * are byte-identical.
     *
     * So bumping would be an active lie: the number's whole job is to tell a reader whether
     * it can parse this dump, and a reader of a schema-4 dump would conclude the keys had
     * moved. If a future change makes any of that untrue -- the trace becoming mandatory,
     * or `build` learning to read it -- bump it then. Use `mod_version` for a capability the
     * pipeline does not depend on; that is what `summary.json` stamps it for.
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

    /**
     * `nbt_trace.json`: {discriminated key: {tag: {"o": digest, "u": sorted digest}}}.
     *
     * Keyed IDENTICALLY to names.json so the two join, which is what lets the python side
     * pair an item across two dumps whose keys no longer match -- the digest is IN the key,
     * so the key itself churns and cannot be the join column. See `tools/digest-churn.py`.
     *
     * The value arrives pre-rendered from `tagDigests`, so this writer stays a writer.
     *
     * @return how many keys were written, for the chat summary
     */
    // Package-visible, not private: `NbtTraceTest` writes a real file with it and
    // `tests/fixtures/nbt_trace_sample.json` is the result, so the python reader is
    // tested against output this writer actually produced rather than a hand-typed
    // guess at its shape. Two mocks agreeing with each other is the failure mode.
    static int writeNbtTrace(File file, Map<String, String> trace) {
        int written = 0;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            boolean first = true;
            for (Map.Entry<String, String> e : trace.entrySet()) {
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write(" \"" + safe(e.getKey()) + "\": " + e.getValue());
                written++;
            }
            w.write("\n}\n");
        } catch (IOException ignored) {
            // A lost diagnostic must not fail a dump that otherwise succeeded, same as
            // catalysts.json and summary.json above.
        }
        return written;
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
