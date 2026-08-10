package io.github.jacoblasky.recipedump;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;
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
 * damageable.json, emc.json, machine_names.json, skipped.ndjson, summary.json,
 * nbt_trace.json and the icons-N.png / icons.json atlas into
 * &lt;gamedir&gt;/mc-recipe-dump/. `/recipedump notrace noicons` skips the last two of
 * those.
 *
 * IT RUNS IN THREE PHASES, and which phase a file comes out of is the thing to know:
 *
 *   1. CATEGORIES -- walk every JEI recipe category and its wrappers. Fills
 *      recipes.ndjson, catalysts.json, names.json and the NBT trace.
 *   2. ITEMS -- walk JEI's complete ingredient list. Fills emc.json (#50) and
 *      machine_names.json's blueprint half (#55), and decides what the atlas renders.
 *      A SEPARATE POPULATION, not an optimisation: an item nothing crafts and nothing
 *      consumes never appears in phase 1, and drop-only items are exactly #50's subject.
 *   3. ICONS -- render the atlas (#36). LAST, after every other file is closed, because
 *      it is the one phase that can plausibly fail; see IconAtlas.
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
    private static volatile Runner active;

    /**
     * Is a dump in flight? Read by the headless harness to know when to stop waiting.
     *
     * AN ACCESSOR RATHER THAN WIDENING THE FIELD, so nothing outside this class can clear it.
     * The field is nulled at both completion paths -- in the `IconAtlas` callback when icons
     * are drawn and inline when they are not -- and those two are the definition of "done".
     *
     * DO NOT SUBSTITUTE "WAIT FOR summary.json". `writeSummary` swallows its own IOException,
     * so the file's absence is not a failure signal and its presence is not a completion one.
     *
     * AND THIS ALONE CANNOT REPORT SUCCESS. `execute` returns early WITHOUT setting `active`
     * on five refusal paths -- a dump already running, no JEI runtime, the output directory
     * uncreatable, the category list throwing, the output file unopenable -- so a caller that
     * polls this and sees false has learned nothing about whether a dump ever began. It must
     * first observe this go TRUE. Those refusals go to `reply`, which is chat, so the reason
     * appears in the framebuffer and not on stdout.
     *
     * Volatile because the harness polls it from the render thread while the walk advances on
     * the client tick.
     */
    public static boolean running() {
        return active != null;
    }

    @Override
    public String getName() {
        return "recipedump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/recipedump [" + NO_TRACE_ARG + "] [" + NO_ICONS_ARG + "] [" + FORCE_ARG
                + "] -- dump all JEI recipes for offline crafting-tree tools; "
                + NO_TRACE_ARG + " skips nbt_trace.json (#80), " + NO_ICONS_ARG + " skips "
                + "the icon atlas (#36), " + FORCE_ARG + " overwrites a dump written by a "
                + "different set of mods (#194)";
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

    /**
     * Argument that SUPPRESSES the item icon atlas. Issue #36's icons are ON by default,
     * for the same asymmetry that decided `notrace`: producing them costs a launch of the
     * game, and nothing about them can be reconstructed from a dump on disk.
     *
     * The atlas is the largest thing a dump writes after recipes.ndjson and the slowest
     * phase to produce, so the flag exists for a dump taken to answer a recipe question in
     * a hurry. It fails safe the same way -- a mistyped `noicons` renders them anyway.
     */
    static final String NO_ICONS_ARG = "noicons";

    /**
     * Argument that lets a dump OVERWRITE a dump directory written by a different jar set.
     *
     * OPT-IN, UNLIKE THE OTHER TWO, and the asymmetry is the point. `notrace` and `noicons`
     * fail safe when mistyped because they suppress; this one enables, so a mistyped `force`
     * refuses -- and refusing is the outcome that costs a retyped command rather than an
     * artifact that took a game launch to make. See {@link #refuseToClobber}.
     */
    static final String FORCE_ARG = "force";

    /** True unless `args` asks to suppress the trace. Unknown args are ignored, as before. */
    static boolean wantsTrace(String[] args) {
        return !carries(args, NO_TRACE_ARG);
    }

    /** True unless `args` asks to suppress the icon atlas. */
    static boolean wantsIcons(String[] args) {
        return !carries(args, NO_ICONS_ARG);
    }

    /** True when `args` carries `force`, allowing an overwrite across mod sets. */
    static boolean forced(String[] args) {
        return carries(args, FORCE_ARG);
    }

    /**
     * Whether `args` names `flag`. NAMED FOR PRESENCE, NOT FOR SUPPRESSION: it was `suppressed`
     * while every flag reading it turned something off, and #194 added `force`, which turns
     * something ON. `return suppressed(args, FORCE_ARG)` reads as the opposite of what it does,
     * and a predicate whose name inverts at one of three call sites is a bug waiting for a
     * reader in a hurry.
     */
    private static boolean carries(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (a != null && flag.equalsIgnoreCase(a.trim())) {
                return true;
            }
        }
        return false;
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
        if (DumpPlugin.runtime == null) {
            reply(sender, "JEI runtime not available yet -- open the recipe GUI once, then retry.");
            return;
        }
        File dir = new File(Minecraft.getMinecraft().gameDir, "mc-recipe-dump");
        // BEFORE `createDirectories`, and long before a byte is written. See the method.
        String refusal = refuseToClobber(dir, activeModIds(), forced(args));
        if (refusal != null) {
            reply(sender, refusal);
            return;
        }
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            reply(sender, "cannot create " + dir + ": " + e);
            return;
        }

        IRecipeRegistry registry = DumpPlugin.runtime.getRecipeRegistry();
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
            runner = new Runner(sender, dir, registry, categories, traceNbt, wantsIcons(args));
        } catch (IOException e) {
            reply(sender, "cannot open output file: " + e);
            return;
        }
        active = runner;
        MinecraftForge.EVENT_BUS.register(runner);
        reply(sender, String.format("dumping %d recipe categories...", categories.size()));
        // Name what the optional mods contribute BEFORE the walk starts, so a dump that
        // silently has no emc.json says why at the moment it can still be acted on -- the
        // alternative is finding the file missing after the launch is over.
        if (!ProjectEBridge.available()) {
            reply(sender, "  no emc.json: " + ProjectEBridge.absence());
        }
        if (!ModularMachineryBridge.available()) {
            reply(sender, "  no machine_names.json: " + ModularMachineryBridge.absence());
        }
        // #175's sources have NO SIDECAR FILE TO BE MISSING, which is why they are announced
        // here rather than left to be noticed. A bridge that fails to resolve produces a dump
        // in which nothing is ever a catalyst, and that is byte-for-byte the dump a pack with
        // no catalysts produces -- the exact "absence is indistinguishable from zero" failure
        // this whole summary exists to end. `catalyst_slots` in summary.json is the other
        // half: this says the reader is off, that says it found nothing.
        if (!TinkersCastingBridge.available()) {
            reply(sender, "  no cast retention: " + TinkersCastingBridge.absence());
        }
        // THE ONE READER BEHIND BOTH `p` AND `q` FROM MODULAR MACHINERY, announced separately
        // from the machine-name line above because it resolves different classes and can fail
        // on its own. When it does, this dump has no catalysts AND no chance outputs, which on
        // the reference pack is thousands of missing facts rather than a rounding error, so
        // silence here is the difference between a plan that is right and one that understates
        // every run through a chance recipe by up to 1000x. #175, #223.
        if (!ModularMachineryBridge.chancesAvailable()) {
            reply(sender, "  no recipe chances: " + ModularMachineryBridge.chanceAbsence());
        }
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

        /** {discriminated key: ProjectE EMC value}, phase 2. Empty without ProjectE. #50 */
        private final Map<String, Long> emc = new LinkedHashMap<String, Long>();
        /** {discriminated blueprint key: machine registry name}, phase 2. #55 */
        private final Map<String, String> blueprints = new LinkedHashMap<String, String>();
        /** {base key: the stack to draw for it}, phase 2, consumed by phase 3. #36 */
        private final Map<String, ItemStack> iconTargets =
                new LinkedHashMap<String, ItemStack>();

        private int catIndex = -1;
        private Iterator<?> wrappers;
        private String uid = "";
        private String title = "";
        private String modName = "";
        private int[] tally;
        private int wrapperIndex;

        private boolean categoriesDone;
        /** Set when recipes.ndjson can no longer be written to; stops the walk. */
        private boolean fatal;
        /** finish() is reachable from two paths and must only run once. */
        private boolean finished;
        /** Phase 2's cursor; null until the item walk starts. */
        private Iterator<ItemStack> items;
        private int itemsSeen;
        private int itemsTotal;

        private int recipes;
        private int failed;
        private final boolean icons;
        private int nextProgressPercent = 25;
        private final long startedAt = System.nanoTime();

        Runner(ICommandSender sender, File dir, IRecipeRegistry registry,
               List<IRecipeCategory> categories, boolean traceNbt, boolean icons)
                throws IOException {
            this.sender = sender;
            this.dir = dir;
            this.registry = registry;
            this.categories = categories;
            this.icons = icons;
            this.sink = new KeySink(traceNbt);
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(new File(dir, "recipes.ndjson").toPath()),
                    StandardCharsets.UTF_8));
        }

        /**
         * One slice of whichever phase is current.
         *
         * The two phases are separate methods against a shared deadline rather than one
         * loop with a mode flag, because they iterate different things and their
         * termination conditions have nothing to do with each other. A leftover slice of
         * budget rolls straight into the next phase, so finishing the categories mid-tick
         * does not waste the rest of that tick.
         */
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            long deadline = System.nanoTime() + BUDGET_NANOS;
            if (!categoriesDone) {
                walkCategories(deadline);
                if (fatal) {
                    // recipes.ndjson is truncated, so nothing downstream of it is worth
                    // producing. Go straight to finish so summary.json records the failure.
                    finish();
                    return;
                }
                if (!categoriesDone) {
                    reportProgress();
                    return;
                }
            }
            if (walkItems(deadline)) {
                finish();
            }
        }

        private void walkCategories(long deadline) {
            while (!fatal && System.nanoTime() < deadline) {
                if (wrappers == null || !wrappers.hasNext()) {
                    if (!nextCategory()) {
                        categoriesDone = true;
                        return;
                    }
                    continue;
                }
                Object obj = wrappers.next();
                wrapperIndex++;
                handle(obj);
            }
        }

        /**
         * Phase 2: walk JEI's complete item list. True when it is finished.
         *
         * @see RecipeDumpMod#ingredients for why this population is not the recipe walk's
         */
        private boolean walkItems(long deadline) {
            if (items == null) {
                List<ItemStack> all = itemList();
                itemsTotal = all.size();
                items = all.iterator();
                reply(sender, String.format(
                        "  %,d recipes; now scanning %,d items for EMC, blueprints and icons",
                        recipes, itemsTotal));
            }
            while (System.nanoTime() < deadline) {
                if (!items.hasNext()) {
                    return true;
                }
                recordItem(items.next());
                itemsSeen++;
            }
            return false;
        }

        /** JEI's every registered ItemStack, or an empty list if it cannot be reached. */
        private List<ItemStack> itemList() {
            try {
                if (DumpPlugin.ingredients == null) {
                    skips.add(skipLine("", "", -1, null, null,
                                       "JEI ingredient registry unavailable"));
                    return Collections.emptyList();
                }
                Collection<ItemStack> all =
                        DumpPlugin.ingredients.getAllIngredients(VanillaTypes.ITEM);
                return new ArrayList<ItemStack>(all);
            } catch (Throwable t) {
                skips.add(skipLine("", "", -1, null, t, "getAllIngredients failed"));
                return Collections.emptyList();
            }
        }

        /**
         * Everything phase 2 wants to know about one stack.
         *
         * Keyed the same way `stack()` keys a recipe ingredient -- `id[:meta][#digest]` --
         * so emc.json and machine_names.json join names.json and recipes.ndjson by
         * construction rather than by a reader's convention.
         */
        private void recordItem(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            String key = stackKey(stack);
            if (key == null) {
                return;
            }
            // The raw components, for the icon target only. NOT reassembled into a key --
            // that string comes from `stackKey` and nowhere else.
            Item item = stack.getItem();
            ResourceLocation id = item.getRegistryName();
            int meta = stack.getItemDamage();

            long value = ProjectEBridge.emc(stack);
            if (value > 0L) {
                emc.put(key, value);
            }
            String machine = ModularMachineryBridge.machineOf(stack);
            if (machine != null) {
                blueprints.put(key, machine);
            }
            addIconTarget(item, id, meta);
        }

        /**
         * Remember one stack to draw, unless something equivalent is already remembered.
         *
         * TWO COLLAPSES, both of which cut work AND cut wrong output:
         *
         *   * The DIGEST is dropped, because the atlas is keyed by base key. An icon per
         *     NBT variant would be 261,095 sprites for a pack with about 40,000 pictures in
         *     it, and the stack rebuilt here carries no NBT anyway. The cost is that an
         *     item whose appearance is NBT-driven (a bee, a Tinkers tool) draws its default
         *     look, which is the honest meaning of "the icon for this item".
         *   * DAMAGE metas collapse to meta 0, on `damageable`, which is the same predicate
         *     #118 writes into damageable.json. 46 keys called Iron Axe are 46 pictures of
         *     one axe; a chisel:lapis meta is a genuinely different block and keeps its own.
         *     Sharing the predicate is what keeps the atlas keyed the way the python side
         *     will collapse the names.
         *
         * The WILDCARD meta is skipped outright: 32767 means "any damage" in a recipe, not
         * an item, and `new ItemStack(item, 1, 32767)` draws whatever the model system makes
         * of a nonsense damage value.
         */
        private void addIconTarget(Item item, ResourceLocation id, int meta) {
            if (!icons || meta == OreDictionary.WILDCARD_VALUE) {
                return;
            }
            int drawn = damageable(item) ? 0 : meta;
            String key = drawn == 0 ? id.toString() : id + ":" + drawn;
            if (!iconTargets.containsKey(key)) {
                iconTargets.put(key, new ItemStack(item, 1, drawn));
            }
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
                    // The BASE key, deliberately: a catalyst is a claim about an ITEM and
                    // not about one NBT state of it. A wildcard-meta catalyst names the
                    // whole family; the python side normalises 32767 to `:*`, so it passes
                    // through unchanged.
                    String key = baseStackKey(stack);
                    if (key == null) {
                        continue;
                    }
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
                //
                // A FLAG RATHER THAN CALLING finish() FROM HERE. It used to call it inline,
                // and control then returned into the walk loop, which went on handing
                // wrappers to a closed writer until the tick budget ran out -- every one of
                // them throwing and re-entering finish(), so summary.json and the rest were
                // written dozens of times over. The flag stops the walk at its own loop
                // condition, which is the only place that can stop it cleanly.
                failed++;
                skips.add(skipLine(uid, modName, wrapperIndex, obj, io, "write failed"));
                fatal = true;
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
            if (finished) {
                return;
            }
            finished = true;
            MinecraftForge.EVENT_BUS.unregister(this);
            try {
                writer.close();
            } catch (IOException ignored) {
                // content is already flushed per-line by the BufferedWriter on close
            }

            writeLines(new File(dir, "skipped.ndjson"), skips);
            int namesFailed = sink.namesFailed();
            // ASKED A SECOND TIME rather than carried from `execute`'s clobber check, and
            // that is safe because Forge's active mod list is fixed once the load phase is
            // over: no mod arrives or leaves while a world is running. If that ever stopped
            // being true the two answers could differ, and the digest this dump RECORDS would
            // not be the digest the guard COMPARED -- so thread the list through Runner
            // rather than adding a third call site.
            List<String> modIds = activeModIds();
            writeSummary(new File(dir, SUMMARY_FILE), perCategory, categoryMod,
                         recipes, failed, skips.size(), sink.names().size(), namesFailed,
                         sink.catalystSlots(), sink.chanceOutputs(), modIds);
            writeCatalysts(new File(dir, "catalysts.json"), catalysts);
            int ores = writeOreDict(new File(dir, "oredict.json"));
            writeNames(new File(dir, "names.json"), sink.names());
            int damageables = writeDamageable(new File(dir, "damageable.json"));
            Map<String, String> machines = ModularMachineryBridge.machines();
            writeMachineNames(new File(dir, "machine_names.json"), machines, blueprints);
            writeEmc(new File(dir, "emc.json"), emc);
            // Diagnostic, so it is written LAST and its absence is normal: every dump
            // that did not ask for it simply has no such file, exactly like a
            // pre-0.4.0 dump has no catalysts.json.
            int traced = sink.tracing()
                    ? writeNbtTrace(new File(dir, "nbt_trace.json"), sink.trace()) : 0;

            long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            boolean drawing = icons && !iconTargets.isEmpty();
            // "done" ONLY WHEN IT IS DONE. This line used to open with "done in 14.9s"
            // whether or not a multi-minute icon phase was about to start, and on the first
            // real run that is exactly how it was read: the player saw four completion
            // messages and closed the game seven seconds into the render, losing it. The
            // recipe walk finishing is not the dump finishing, and only one of those two
            // sentences may use the word.
            reply(sender, String.format(
                    "%s %.1fs: %s recipes from %d categories, %d with a known machine, "
                            + "%s oredict entries, %s names -> %s",
                    drawing ? "recipes and items done in" : "done in",
                    ms / 1000.0, formatCount(recipes), perCategory.size(), catalysts.size(),
                    formatCount(ores), formatCount(sink.names().size()), dir.getName()));
            // TWO NUMBERS, BECAUSE THEY ANSWER TWO QUESTIONS AND ONLY ONE OF THEM IS THE
            // SIZE OF THE FILE. This line used to read "N skipped, all recorded in
            // skipped.ndjson" with N = the wrapper-failure count, which is 0 on a healthy
            // pack -- while skipped.ndjson held 22,188 lines. It asserted a relationship
            // that does not hold, and a player who opened the file after being told 0 found
            // 22,188. "Did anything break" and "how much did JEI decline to give us" are
            // different questions; see #90.
            // THE THIRD NUMBER IS ALWAYS PRINTED, INCLUDING WHEN IT IS ZERO, for the same
            // reason the other two are: a line that only appears on failure makes a healthy
            // dump and an unreported one look identical, which is the #194 defect itself.
            reply(sender, String.format(
                    "%s wrappers threw; %s display names could not be read; %s entries "
                            + "recorded in skipped.ndjson (per-category counts in summary.json)",
                    formatCount(failed), formatCount(namesFailed),
                    formatCount(skips.size())));
            reply(sender, String.format(
                    "%s items scanned: %s with EMC, %s blueprints across %s machines, "
                            + "%s damageable item types",
                    formatCount(itemsSeen), formatCount(emc.size()),
                    formatCount(blueprints.size()), formatCount(machines.size()),
                    formatCount(damageables)));
            // WHICH JARS THIS DUMP SAW, said out loud at the moment it can still be acted
            // on. A player who meant to dump the full pack and finds "7 mods" here has lost
            // a minute; the same person finding out three days later, from a graph that
            // looked normal, has lost the three days. #194
            reply(sender, modIds == null
                    ? "mod set: Forge would not list it, so this dump does not record which "
                            + "jars produced it"
                    : String.format("mod set: %d mods, digest %s (recorded in summary.json, "
                            + "so a graph built from this dump can name its pack)",
                            modIds.size(), modDigest(modIds)));
            if (sink.tracing()) {
                reply(sender, String.format(
                        "nbt_trace.json: %s keys with identifying NBT", formatCount(traced)));
            }

            if (drawing) {
                // `active` stays set until the atlas is done, so a second /recipedump
                // during the render is still refused rather than writing over a live page.
                new IconAtlas(sender, dir, iconTargets, new Runnable() {
                    @Override
                    public void run() {
                        active = null;
                        nextStep(sender);
                    }
                }).start();
            } else {
                active = null;
                nextStep(sender);
            }
        }
    }

    /**
     * The last line of a dump: what to do with the files.
     *
     * The files are useless on their own, and in-game chat is the only place the player is
     * looking at this moment, so name the next step and the URL.
     *
     * ONE step, not two: `serve` builds the graph itself when the dump is newer, so telling
     * anyone to run `build` first is telling them to do work the tool already did. Keep this
     * in step with cli.ensure_graph.
     *
     * The port matches recipegraph.server.DEFAULT_PORT; it cannot be shared across the
     * language boundary, so changing one means grepping 8765 for the others.
     *
     * Phrased as an instruction, NOT as "open http://localhost:8765" on its own: the planner
     * is a separate program that may not be installed or running, and pointing at a dead URL
     * is worse than saying nothing. DO NOT reduce this to just the link.
     */
    private static void nextStep(ICommandSender sender) {
        reply(sender, "next: run `recipegraph serve` and open http://localhost:8765 "
                + "-- it will load this dump on the way up");
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

        List<List<Object>> itemInSlots = collected.rawInputs(ItemStack.class);
        String itemIn = stackSlots(itemInSlots, sink, consumeChances(wrapper, itemInSlots));
        String itemOut = flatOutputs(collected.rawOutputs(ItemStack.class), sink, wrapper);
        String fluidIn = fluidSlots(collected.rawInputs(FluidStack.class));
        String fluidOut = flatFluids(collected.rawOutputs(FluidStack.class));

        if (itemOut.equals("[]") && fluidOut.equals("[]")) {
            return null;  // nothing produced: not useful as a graph edge
        }
        return "{\"cat\":\"" + uid + "\",\"title\":\"" + title + "\",\"in\":" + itemIn
                + ",\"out\":" + itemOut + ",\"fin\":" + fluidIn + ",\"fout\":" + fluidOut + "}";
    }

    /**
     * How much of each ITEM input slot a run actually spends. Issue #175.
     *
     * ONLY ITEM INPUTS, NEVER FLUIDS. Every catalyst source here describes a thing that sits
     * in the machine; the fluid is the material being worked. Marking a fluid permanent would
     * make its recipe free, which is a far worse failure than the one this fixes.
     *
     * TWO SOURCES, ASKED IN ORDER OF HOW MUCH THEY COVER, and null from both leaves the array
     * absent so every slot keeps the pre-#175 default of "spent". The third source, vanilla
     * `getRemainingItems`, is #228 and is not wired here: it reaches 3 of the 14,409 recipes
     * that consume a cast, so it is not worth a per-wrapper `InventoryCrafting` until that
     * issue settles what it actually reports for a `.reuse()` marker.
     */
    private static float[] consumeChances(IRecipeWrapper wrapper, List<List<Object>> itemSlots) {
        if (itemSlots.isEmpty()) {
            return null;
        }
        Float casting = TinkersCastingBridge.itemInputChance(wrapper);
        if (casting != null) {
            float[] out = new float[itemSlots.size()];
            Arrays.fill(out, casting.floatValue());
            return out;
        }
        return ModularMachineryBridge.itemInputChances(wrapper, itemSlots);
    }

    /**
     * The item outputs, each carrying how often a run YIELDS it. Issue #223.
     *
     * THE MIRROR OF {@link #consumeChances} AND NOT A COPY OF IT. `p` scales a cost the run
     * spends; `q` scales the yield, which downstream appears in a DIVISOR, so a recipe that
     * yields its product 10% of the time needs ten times the runs and ten times every input
     * those runs consume. The reference pack's fractional output chances reach 0.001, so the
     * multiplier this recovers reaches 1000x; the census is on `ModularMachineryBridge`.
     *
     * ONE SLOT LIST, PASSED TO BOTH HALVES, and that is the whole reason this method exists
     * rather than two statements in `encode`. The chance array is positional, so the list it
     * is DERIVED from and the list it is WRITTEN against must be the same object; two locals
     * spelled `rawOutputs` and `rawInputs` one letter apart, either of which type-checks in
     * either position, is a shift of the entire dump that no test on this classpath could
     * catch. Here there is nothing to mismatch. DO NOT re-split this into a chance lookup and
     * a separate `flatStacks` call at the call site.
     *
     * ONE SOURCE, UNLIKE THE INPUT SIDE. {@link TinkersCastingBridge} answers a question about
     * a cast that SURVIVES its recipe, which is an input fact and has no output analogue, so
     * asking it here would only ever return the same 1.0 an absent array already means.
     *
     * ONLY ITEM OUTPUTS, NEVER FLUIDS, and that limit is measured rather than assumed.
     * {@link ModularMachineryBridge#itemOutputChances} carries the fluid census and the
     * method that produced it. No emptiness guard either, unlike `consumeChances`, which needs
     * one only because it asks a second source first: the bridge already answers null for an
     * empty slot list, and a second spelling of that rule is a place for the two to drift.
     */
    private static String flatOutputs(List<List<Object>> slots, KeySink sink,
                                      IRecipeWrapper wrapper) {
        return flatStacks(slots, sink, ModularMachineryBridge.itemOutputChances(wrapper, slots));
    }

    /**
     * Nested: list of slots, each slot a list of interchangeable stacks.
     *
     * Package-visible, not private, for the same reason `writeSummary` is: `SchemaEightTest`
     * calls this and {@link #flatStacks} with real ItemStacks and asserts the bytes each
     * writes, so the rule that `p` belongs to inputs and `q` to outputs is held to the actual
     * emitter rather than to two mocks agreeing with each other. Neither method can be reached
     * with a non-default chance through `encode`, because the only source of one is a bridge
     * that needs Modular Machinery on the classpath. #223.
     */
    static String stackSlots(List<List<Object>> slots, KeySink sink, float[] chances) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstSlot = true;
        int index = -1;
        for (List<Object> slot : slots) {
            index++;
            // Indexed over the RAW slot list rather than over emitted slots, because the
            // `continue` below drops empty grid spacers and a chance array built by the
            // bridges is aligned to what the wrapper reported, not to what survives here.
            float chance = chances == null || index >= chances.length ? 1.0f : chances[index];
            if (chance != 1.0f && sink != null) {
                // Counted per SLOT, not per alternative: an oredict slot is one requirement
                // however many stacks satisfy it, and counting stacks would make the number
                // vary with how wide the oredict happens to be.
                sink.recordCatalystSlot();
            }
            List<String> alts = new ArrayList<String>();
            for (Object o : slot) {
                String s = inputStack(o, sink, chance);
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

    /**
     * Flat: outputs collapse to one stack per slot; alternatives do not matter.
     *
     * TWO LISTS OF DIFFERENT LENGTHS, WALKED TOGETHER. `chances` is aligned to the RAW slot
     * list, because that is the list Modular Machinery's requirements correspond to one for
     * one, while `out` holds only the slots that produced a stack. So the index used to read a
     * chance is counted over `slots` and never over `out`; taking it from the emitted list
     * would move every `q` after the first empty slot onto the wrong product. #223.
     *
     * Package-visible for the reason given on {@link #stackSlots}.
     */
    static String flatStacks(List<List<Object>> slots, KeySink sink, float[] chances) {
        List<String> out = new ArrayList<String>();
        int index = -1;
        for (List<Object> slot : slots) {
            index++;
            float chance = chances == null || index >= chances.length ? 1.0f : chances[index];
            for (Object o : slot) {
                String s = outputStack(o, sink, chance);
                if (s != null) {
                    out.add(s);
                    // COUNTED HERE, ON THE EMISSION, not beside the chance lookup as the
                    // input side counts. A slot that yields no serializable stack writes no
                    // `q` into the file, and this number's job is to be checkable against
                    // what the file actually contains.
                    if (chance != 1.0f && sink != null) {
                        sink.recordChanceOutput();
                    }
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

    /**
     * An INPUT stack, whose only chance is `p`: how much of itself a run spends. #175.
     *
     * TWO NAMED ENTRY POINTS RATHER THAN ONE FOUR-ARGUMENT CALL, so that no call site is able
     * to put a yield in `p` or a consumption in `q`. That mistake has no compile error and no
     * test that would fail on shape: an output carrying `p` parses cleanly and reads as "this
     * output is a catalyst" to every consumer, which is the wrong answer stated confidently.
     * Before #223 the same guarantee was a two-argument overload documenting that outputs must
     * never acquire a `p`; the pair below keeps it now that outputs carry a chance of their
     * own. DO NOT collapse these into one method taking both numbers.
     */
    private static String inputStack(Object o, KeySink sink, float chance) {
        return stack(o, sink, chance, 1.0f);
    }

    /**
     * An OUTPUT stack, whose only chance is `q`: how often a run yields it. #223.
     *
     * `q` IS NOT `p` AND MUST NOT BE WRITTEN AS ONE. `p` says a run may not SPEND this input,
     * which makes the recipe cheaper; `q` says a run may not PRODUCE this output, which makes
     * it dearer by landing in a divisor. See {@link #inputStack} for why they are separate
     * methods rather than one.
     */
    private static String outputStack(Object o, KeySink sink, float yieldChance) {
        return stack(o, sink, 1.0f, yieldChance);
    }

    private static String stack(Object o, KeySink sink, float chance, float yieldChance) {
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
            sink.record(stackKey(stack), stack);
        }
        // `p` IS OMITTED AT 1.0 RATHER THAN WRITTEN, and that is a size decision with a
        // correctness consequence worth stating. 1.0 is the reader's default for an absent
        // field, so omitting it costs nothing to parse and keeps a 335,000-recipe NDJSON
        // from growing by a field that would be constant on all but ~14,400 lines. It also
        // means a schema-7 dump is byte-identical to a schema-6 one wherever nothing is a
        // catalyst, so a diff between the two shows exactly the recipes this issue changed.
        //
        // `q` FOLLOWS THE SAME RULE FOR THE SAME REASONS. #223. A schema-8 dump is
        // byte-identical to a schema-7 one wherever nothing is chance-yielded, so the diff
        // between them is exactly the outputs #223 changed and nothing else.
        return "{\"i\":\"" + safe(id.toString()) + "\",\"m\":" + meta
                + ",\"c\":" + stack.getCount()
                + (nbt == null ? "" : ",\"n\":\"" + nbt + "\"")
                + (chance == 1.0f ? "" : ",\"p\":" + chance)
                + (yieldChance == 1.0f ? "" : ",\"q\":" + yieldChance) + "}";
    }

    /**
     * The per-unique-key sinks a dump fills as it walks: display names always, and the
     * issue #80 NBT trace when `/recipedump nbttrace` asked for it.
     *
     * ONE object rather than two threaded parameters because they are the same kind of
     * thing -- write-once-per-discriminated-key -- and because the KEY FORMAT
     * (`id[:meta][#digest]`) has to be identical in both files or they cannot be joined.
     * {@link DumpCommand#stackKey} is where that string is built, and since #19 Phase 4 it
     * really is the only place: three sites spelled it inline before, which made this
     * sentence a claim rather than a guarantee.
     */
    static final class KeySink {

        private final Map<String, String> names = new LinkedHashMap<String, String>();
        /**
         * Keys whose `getDisplayName()` threw at least once. NOT the reported count -- see
         * {@link #namesFailed()}, which subtracts the ones a later occurrence got.
         */
        private final Set<String> nameThrew = new LinkedHashSet<String>();
        /** null when not tracing, which is how every normal dump runs. */
        private final Map<String, String> trace;

        /**
         * Input slots this dump wrote a non-default `p` onto. #175.
         *
         * COUNTED SO THAT ZERO IS A MEASUREMENT RATHER THAN A SILENCE. A bridge that fails to
         * resolve emits no `p` at all, which is byte-for-byte what a pack with no catalysts
         * emits. Every other "absence is indistinguishable from zero" hole in this file got a
         * count for the same reason; this one has no sidecar file whose absence could hint.
         */
        private int catalystSlots;

        /**
         * Output stacks this dump wrote a non-default `q` onto. #223.
         *
         * COUNTED FOR THE SAME REASON {@link #catalystSlots} IS, and the reason is sharper
         * here: a chance output that reads as guaranteed does not merely misprice one
         * ingredient, it divides the whole plan by a yield up to a thousand times too high.
         * A reader that stopped resolving emits no `q` at all, which is byte-for-byte what a
         * pack with no chance outputs emits, and the only symptom is that plans quietly
         * understate the runs they need.
         *
         * COUNTS EMITTED STACKS, not slots examined, which is what makes it checkable: it is
         * exactly how many `q` fields recipes.ndjson contains.
         */
        private int chanceOutputs;

        KeySink(boolean traceNbt) {
            this.trace = traceNbt ? new LinkedHashMap<String, String>() : null;
        }

        void recordCatalystSlot() {
            catalystSlots++;
        }

        int catalystSlots() {
            return catalystSlots;
        }

        void recordChanceOutput() {
            chanceOutputs++;
        }

        int chanceOutputs() {
            return chanceOutputs;
        }

        Map<String, String> names() {
            return names;
        }

        /**
         * How many discriminated keys this dump has NO display name for. #194
         *
         * SUBTRACTED AT THE END RATHER THAN COUNTED AS IT GOES, because a key can throw and
         * then succeed. `record` re-enters the try on every occurrence of a key it has no
         * name for, and the documented cause of the throw -- being outside a render pass --
         * is a property of WHEN it is called, not of the item. Counting throws would report
         * a loss for an item whose name the dump went on to write, and the number's whole
         * job is to be comparable against `names.json`'s length.
         */
        int namesFailed() {
            int lost = 0;
            for (String key : nameThrew) {
                if (!names.containsKey(key)) {
                    lost++;
                }
            }
            return lost;
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
                    // A few modded items throw on getDisplayName outside a render pass.
                    // STILL SWALLOWED -- one unnameable item must not end a dump -- but no
                    // longer SILENT: before #194 this catch discarded the fact as well as
                    // the exception, so a dump that lost 40,000 names wrote a shorter
                    // names.json and said nothing, and nothing downstream could tell.
                    nameThrew.add(key);
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
        "ench",           // enchantments; see below, #63 and #80
    };

    /**
     * WHY `ench` IS ON THAT LIST, AND WHY `StoredEnchantments` IS NOT.
     *
     * #63 wanted `ench` stripped on the planning argument: an enchanted sword is not a
     * different crafting ingredient from a plain one, and six Pluton Scythes that differ
     * only by enchantment are six unplannable variants of one item. That argument was
     * sound but unmeasured, and #63 says so plainly -- it could not even be sized offline,
     * because a dump keeps the digest and discards the NBT behind it.
     *
     * #80's trace measured it, and found a second and stronger reason: `ench` DOES NOT HOLD
     * STILL. On the 10,694 churned pairs whose pairing is forced (one candidate each side,
     * so the pair is certain), `ench` moved on 1,335 -- and those 1,335 items carry just
     * FIVE distinct digest transitions between them, one of which covers 1,330 items. A
     * handful of values moved and every item carrying them moved along. That is what a
     * per-launch numeric enchantment id looks like: 1.12.2 stores `ench` as {id, lvl} with
     * `id` a registry-allocated short, so the same enchantment serialises differently in the
     * next JVM and every stack wearing it gets a new key. Sorting cannot fix it -- the
     * measurement agrees, `order_only` was 0 of 2,423 -- and stripping it fixes it exactly.
     *
     * `StoredEnchantments` is the same shape of data (an enchanted BOOK's payload) and is
     * deliberately NOT stripped:
     *
     *   * There is no evidence it churns. It changed on 4 pairs, and all 4 sat in ambiguous
     *     groups where the pairing is a guess; on forced pairs it moved ZERO times. Four
     *     unverified observations do not carry a COSMETIC_TAGS entry.
     *   * The planning argument INVERTS for it. An enchanted book's identity IS its
     *     enchantment: a Sharpness V book and a Fortune III book are different ingredients,
     *     and merging them is precisely the "wrong entry" this list's javadoc warns about.
     *
     * If books are ever seen churning on forced pairs, the fix is not to strip the tag but
     * to make the id stable -- which the dump cannot do from outside the registry, and which
     * is a new issue rather than a line here.
     */

    /**
     * Tags whose LIST ORDER carries no information, so the digest is taken over them sorted.
     *
     * MEASURED, NOT GUESSED, and that distinction is the whole licence for this list.
     * Issue #80: two `/recipedump` runs against an unchanged pack gave 12,443 items two
     * different keys each. `nbt_trace.json` from both runs, compared by
     * `tools/digest-churn.py`, splits that into two disjoint populations. On the 10,694 pairs
     * where the pairing is forced (one candidate on each side, so the pair is certain, not
     * inferred from tag agreement):
     *
     *   `Special`   9,359 items, order-only EVERY time, and only tconstruct + plustic
     *   `ench`      1,335 items, never order-related -- stripped instead, see COSMETIC_TAGS
     *
     * The two never moved on the same item. Two independent causes, not one with a spread.
     *
     * "Order-only" is a measurement: for all 9,359, serialising `Special` with list order
     * made irrelevant produced the SAME digest in both dumps. The two runs therefore held
     * the same multiset of elements in a different sequence, and a sort cannot discard
     * information that was never there.
     *
     * DO NOT ADD A TAG HERE WITHOUT THAT MEASUREMENT. Sorting a list whose order IS
     * semantic -- an inventory, a page order, a slot layout -- merges two stacks that are
     * not the same stack, and nothing reports it: the key looks fine and matches the wrong
     * thing. `canonical`'s javadoc records why the GLOBAL sort was rejected for that exact
     * reason. This list is the narrow version of it and it stays narrow.
     *
     * Applied wherever the name appears, at any depth, because the reason `Special` permutes
     * is a property of the code that builds it (an unordered collection serialised in
     * iteration order) rather than of where it sits. The trace measures the top-level
     * occurrence, which is where the Tinkers family puts it.
     *
     * MUST MATCH `nbt_digest.SORTED_LIST_TAGS` on the python side.
     * `tests/test_nbt_digest.JavaSourceContractTest` asserts that by reading this source, so
     * editing one language and not the other fails with no JVM needed.
     */
    static final String[] SORTED_LIST_TAGS = {
        "Special",        // tconstruct + plustic, permutes per JVM run; #80
    };

    /** Whether a compound key's value is serialised with list order made irrelevant. */
    static boolean sortedListTag(String key) {
        for (String name : SORTED_LIST_TAGS) {
            if (name.equals(key)) {
                return true;
            }
        }
        return false;
    }

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
     * item's identity in NBT has its own structure, and there are 370-odd of them. The
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
     * The graph key for a stack: `id[:meta][#digest]`. Null when the stack has no identity.
     *
     * THE ONE PLACE THIS FORMAT IS SPELLED. `KeySink` below has always claimed that, and
     * until #19 Phase 4 it was not true -- three sites built the string inline and the
     * claim was aspirational. It matters more now than it did: the JEI wiring has to INVERT
     * this to get from a planned key back to an `ItemStack`, and an inverter matched against
     * one of three spellings is an inverter that works until it silently does not.
     *
     * Null for an empty stack or an item with no registry name, because a key minted for
     * either would name nothing and would look real everywhere downstream.
     *
     * PUBLIC because the JEI wiring in `client.jei` inverts it. That is the whole reason it
     * has to be one function: the inverter and the writer agreeing is not something a reader
     * can check across three inline copies, and it is not something a test can pin either.
     */
    public static String stackKey(ItemStack stack) {
        String base = baseStackKey(stack);
        if (base == null) {
            return null;
        }
        String nbt = discriminator(stack);
        return nbt == null ? base : base + "#" + nbt;
    }

    /**
     * {@link #stackKey} without the NBT discriminator: `id[:meta]`.
     *
     * A SEPARATE ENTRY POINT RATHER THAN A FLAG, because the one caller that wants it -- the
     * catalyst walk -- wants it for a reason worth naming. A catalyst is a claim about an
     * ITEM, not about one NBT state of it, and JEI lists the bare item while the recipes
     * that craft it may all output a discriminated variant. Widening happens on the python
     * side through the variant index; the dump must not pre-empt it by writing a digest here.
     */
    public static String baseStackKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = stack.getItem().getRegistryName();
        if (id == null) {
            return null;
        }
        int meta = stack.getItemDamage();
        return meta == 0 ? id.toString() : id + ":" + meta;
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
     *   "o"  the tag serialised the way the real digest serialises it -- lists in order,
     *        unless the tag is in `SORTED_LIST_TAGS`, because then the digest sorts it too.
     *        Comparing this field for one item across TWO dumps names the tag that
     *        churned, which is the thing no dump on disk can currently answer.
     *   "u"  the same tag with list order made irrelevant. Comparing "o" against "u"
     *        WITHIN ONE dump hints at whether list order could matter for that tag.
     *
     * "u" ALSO CARRIES THE PROOF, and that turned out to be its real value. Comparing "u"
     * across two dumps separates a permutation from a content change: if "o" moved and "u"
     * did not, list order is the whole difference and sorting that tag fixes it.
     *
     * DO NOT read the one-dump comparison as a verdict. Measured on the reference pack it is
     * wrong in both directions: `Special` read as "equal" on 5,163 keys and then churned on
     * 10,010, so a list that happens to be in sorted order in one dump hides there; and
     * `Traits` led the one-dump table at 7,962 while churning zero times. The equal case is
     * not a clearance. Two dumps are what answer the question.
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
            // `sortedListTag(k)` rather than a bare `false`, so "o" keeps meaning "the way
            // the real digest serialises this tag". Without it the trace would go on
            // reporting `Special` as churning after the fix stopped that churn from reaching
            // a key, which is a diagnostic contradicting the thing it exists to explain.
            StringBuilder ordered = new StringBuilder();
            canonical(sub, ordered, sortedListTag(k));
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
     * NBT as a deterministic, LANGUAGE-NEUTRAL string. Compound keys sorted, lists in order
     * EXCEPT under a `SORTED_LIST_TAGS` name, every value tagged with its type.
     *
     * Deliberately not `NBTBase.toString()`, which is a Java implementation detail:
     * a byte renders "5b", a string comes back quoted and escaped, a float carries an
     * "f". The world-save reader on the python side has to be able to compute the SAME
     * digest for the same stack, or your bees in AE2 will not match the bees in a recipe,
     * and it cannot reproduce Java's formatting. Floats go in as their IEEE-754 bits for
     * the same reason: decimal float formatting differs between the two languages and
     * would silently split one item into two keys.
     *
     * THIS FORMAT IS PART OF SCHEMA 4. Changing it changes every discriminated key, which
     * costs a whole-pack redump plus a re-run of `have`, so it is a schema bump every time.
     *
     * The two-argument form IS that frozen format and is what `discriminator` calls. It
     * enters the `sortLists` overload with sorting OFF, and the only thing that ever turns
     * it on for a shipped key is a `SORTED_LIST_TAGS` name -- see that list for the
     * measurement licensing each entry, and the overload below for why the global version
     * stays rejected. `DigestFixtureTest` pins this output against the cross-language
     * fixture, so any other change reaching the digest could not pass.
     */
    static void canonical(NBTBase node, StringBuilder sb) {
        canonical(node, sb, false);
    }

    /**
     * As above, but with `sortLists` optionally making LIST order irrelevant.
     *
     * WHY THIS EXISTS. Issue #80: the digest moved between two dumps of an unchanged pack.
     * Comparing a tag's ordered rendering against its sorted one is what identifies a
     * permuting list, and it is what `tagDigests` publishes as "u" so two dumps can be
     * compared after the fact.
     *
     * TWO CALLERS, AND ONLY ONE OF THEM SHIPS A KEY. The trace passes `true` to render the
     * order-irrelevant comparison. The digest path passes `false` and gets sorting only for
     * a `SORTED_LIST_TAGS` name, which the compound case turns on per key. `Special` earned
     * its place there by measurement: 9,359 forced pairs, order-only every one.
     *
     * DO NOT PROMOTE SORTING GLOBALLY ONTO THE DIGEST PATH. That prohibition survives the
     * #80 fix intact and is the reason the fix is a NAMED LIST rather than a flag flip. List
     * order is genuinely semantic for other NBT (an inventory, a page order, a slot layout),
     * so a global sort merges items that are not the same item -- trading a split key, which
     * is visible as a duplicate, for a WRONG key, which nothing reports at all. Adding a name
     * to `SORTED_LIST_TAGS` requires the same two-dump order-only measurement that `Special`
     * has; `tools/digest-churn.py` is the thing that produces it.
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
                    // `|| sortedListTag(k)` is the narrow #80 fix and the ONLY place it
                    // applies. Once inside a sorted subtree it stays on, so a named tag
                    // sorts its own nested lists too -- which is exactly what the trace
                    // measured as order-only, since its "u" field sorts the whole subtree.
                    canonical(c.getTag(k), sb, sortLists || sortedListTag(k));
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
     * Bumped when the SHAPE of any dumped file changes, OR the MEANING of a value a reader
     * recomputes -- never merely because the mod version moved. The reader compares it and
     * says so rather than misparsing a dump in silence.
     *
     *   1  recipes.ndjson + oredict + names + skipped + summary
     *   2  adds catalysts.json, and summary.json gains mod_version and schema
     *   3  adds the NBT discriminator `n` on stacks; names.json keys by the discriminated id
     *   4  CHANGES HOW `n` IS COMPUTED: `SORTED_LIST_TAGS` sorts named lists, and `ench`
     *      joins `COSMETIC_TAGS`. Every discriminated key in the pack moves. See #80, #63.
     *   5  summary.json's `skipped` becomes `threw` and gains `skip_lines`; adds
     *      damageable.json, emc.json, machine_names.json and the icons-N.png atlas with
     *      icons.json. See #90, #118, #50, #55, #36.
     *   6  summary.json gains `names` and `names_failed`, so a short names.json stops being
     *      undetectable in principle, and `mod_count` / `mod_digest`, so a dump can say
     *      which jars it saw. See #194.
     *   7  an item input stack may carry `p`, the probability a run SPENDS it, absent meaning
     *      1.0. Written for Tinkers casts that survive and Modular Machinery's `setChance`.
     *      See #175.
     *   8  an item output stack may carry `q`, the probability a run YIELDS it, absent
     *      meaning 1.0; summary.json gains `chance_outputs`. See #223.
     *
     * SEVEN AND EIGHT ARE ADDED FIELDS, SO AN OLDER READER IS NOT WRONG, ONLY OLDER, and the
     * bumps are still right. Both are absent wherever they would be 1.0, so an older reader
     * sees exactly the dump it saw before on every unmarked line and never misparses one.
     * What it cannot do is tell a genuinely permanent catalyst from an unmarked one, or a 0.1%
     * yield from a guaranteed one, which is a difference in what the plan COSTS rather than in
     * what it parses -- and the number's job is to let a reader say "this dump knows something
     * I do not" rather than to gate a crash.
     *
     * `q` IS A SEPARATE FIELD FROM `p` AND MUST STAY ONE. `p` answers "how much of this input
     * does a run spend"; `q` answers "how often does a run yield this output". `stack` already
     * refuses to conflate them from the emitting end, and the reader halves are two functions
     * over one validator in `sources/hei_dump.py` for the same reason. They also differ in
     * which direction a missing value is safe: an absent `p` overstates a price, which is
     * harmless, while an absent `q` leaves a 0.1% recipe reading as guaranteed, which is the
     * defect #223 exists to fix.
     *
     * EIGHT IS THE SAME KIND OF BUMP AND NOT THE SAME SIZE OF ERROR. `q` is likewise absent
     * at 1.0, so a 7 reader parses an 8 dump unchanged. What it silently loses is larger than
     * what a 6 reader loses on `p`: an unread `p` overstates one ingredient's cost by at most
     * its own price, while an unread `q` divides the entire plan by a yield that can be a
     * thousand times too high, and every input of every run under it goes with it. #223.
     *
     * ONE NUMBER FOR FIVE CHANGES, DELIBERATELY. They shipped in one jar because the
     * expensive step is a launch of the game, not the code, and five increments on one
     * branch would only buy a partial revert nobody can exercise -- reverting half of a jar
     * still costs the launch. One number is also the number the reader can verify straight
     * out of the class constant pool.
     *
     * THE SECOND CLAUSE IS WHY 4 EXISTS, and it is the subtler half of the rule. Schema 4
     * moves no file's shape and adds no field: recipes.ndjson still carries `n`, spelled the
     * same way, in the same place. What changed is the FUNCTION behind it -- and `n` is
     * recomputed independently by `recipegraph/nbt_digest.py` from the world save, so a
     * python side at schema 4 reading a schema-3 graph computes a different digest for the
     * same stack and every discriminated key silently fails to match. Stock reads as zero
     * and plans buy things you already own, which is #21 exactly. A field only this mod
     * writes and reads could change without a bump. `n` cannot, because someone else
     * recomputes it, and the version is their only way to notice.
     *
     * `nbt_trace.json` DELIBERATELY DID NOT BUMP THIS, and the "2 adds catalysts.json" entry
     * is why that needs saying: adding a file has bumped the schema before, so the next
     * person adding one will reasonably reach for it.
     *
     * The distinction is whether the PIPELINE reads it. catalysts.json changes what
     * `recipegraph build` produces -- it is the authoritative category-to-machine mapping, so
     * its absence silently costs machine identification, and a reader is entitled to know.
     * nbt_trace.json is read by `tools/digest-churn.py` and by nothing else; `build`, `have`
     * and `serve` never open it, no existing file's shape moved, and at the time it shipped
     * the discriminated keys were byte-identical. Bumping for it would have been an active
     * lie, because the number's job is to tell a reader whether its own recomputation still
     * agrees -- and back then it did.
     *
     * Use `mod_version` for a capability the pipeline does not depend on; that is what
     * `summary.json` stamps it for.
     *
     * PUBLIC FOR THE GRAPH SCREEN AND THE PLANNER (#285), on {@link #activeModIds}'s precedent
     * from #255. `client.PlannerScreen` and `client.BrowseScreen` compare it against the loaded
     * graph's `dump_schema`, which is the whole point of writing it: for the number to tell a
     * reader whether their graph still agrees, something has to read both.
     *
     * IT STAYS A LITERAL IN THIS FILE. `tests/test_catalysts.py` greps
     * `SCHEMA\\s*=\\s*(\\d+)` out of `DumpCommand.java` and pins it to `dump_meta.SCHEMA`, which
     * is what keeps the Java and Python sides from drifting apart -- so DO NOT move this to a
     * shared constants class or compute it from anything.
     */
    public static final int SCHEMA = 8;

    /**
     * WHICH JARS THIS DUMP CAN SEE, as modids. Null when Forge will not say. #194
     *
     * The question a dump could not answer about itself, and the reason #119's parity gap ran
     * for months on quoted numbers: five jars and the pack's 367 produce provenance lines
     * identical in form, and the CONTENTS cannot settle it either -- a client-only mod that
     * registers no JEI category leaves no trace in the output at all. #208 settled that one at
     * 0 keys by walking both jar sets on the desktop, because no artifact could be asked; this
     * is what makes the next such question answerable from the artifact instead.
     *
     * NULL, NOT AN EMPTY LIST, when `Loader` throws. "Could not ask" and "asked and found
     * nothing" are different facts and the reader distinguishes them; an empty list would
     * write `mod_count: 0`, which is a measurement nobody took.
     */
    /**
     * PUBLIC FOR THE GRAPH SCREEN (#255), which compares the running jar set against the one
     * the loaded graph records. That check is stronger in game than the CLI's equivalent:
     * `index._refuse_the_wrong_pack` compares two artifacts, and this compares a graph against
     * the pack it is actually being used in. Null still means "could not ask", never "no mods".
     */
    public static List<String> activeModIds() {
        try {
            List<String> ids = new ArrayList<String>();
            for (net.minecraftforge.fml.common.ModContainer mod
                    : net.minecraftforge.fml.common.Loader.instance().getActiveModList()) {
                if (mod != null && mod.getModId() != null) {
                    ids.add(mod.getModId());
                }
            }
            // AN EMPTY LIST IS ALSO "COULD NOT ASK". A live Forge always lists at least
            // `minecraft`, `mcp` and `FML`, so an empty result does not mean a pack with no
            // mods -- it means this JVM is not inside a running Forge, which is where the
            // unit tests are. Returning it as a measurement would let a dump claim a jar set
            // of zero and make every later comparison against it a false mismatch.
            //
            // NOT SORTED HERE. Order matters to exactly one thing, `modDigest`, and it sorts
            // its own input -- see there for why the invariant cannot live at this level.
            return ids.isEmpty() ? null : ids;
        } catch (Throwable ignored) {
            // A dump is worth more than its provenance stamp; see writeSummary's null case.
            return null;
        }
    }

    /**
     * A digest of the modid SET. Null for a null list, so "could not ask" survives the hop.
     *
     * MODIDS ONLY, DELIBERATELY NOT VERSIONS. The hazard this exists to catch is a dump
     * taken against a DIFFERENT SET of jars -- the server pack's 364 rather than the
     * client's 367, or the harness's six -- and versions do not speak to that. What they do
     * is churn the digest on every routine pack update, which would fire the mismatch
     * refusal on a legitimate redump and get it forced past out of habit. This project has
     * already written down what that costs: "a warning that cries wolf gets trained away
     * before the one time it matters." Which BUILD wrote the dump is `mod_version`'s job.
     *
     * `fnv` and not a fresh hash, so this file holds one hashing loop rather than two. It is
     * a different input domain from the NBT discriminator and reusing the function does not
     * touch schema 4's frozen key format -- `canonical` is what that format is, not `fnv`.
     */
    /** PUBLIC FOR THE GRAPH SCREEN (#255); see {@link #activeModIds}. Null in, null out. */
    public static String modDigest(List<String> modIds) {
        if (modIds == null) {
            return null;
        }
        // SORTED HERE AND NOWHERE ELSE. DO NOT move this up into `activeModIds`: that holds
        // the invariant only for the one caller that happens to sort, and the digest's whole
        // promise is that it is of a SET -- the same pack enumerated in a different order must
        // digest the same, or the clobber refusal fires on a redump of the pack it just read.
        // A COPY, because `Collections.sort` on an `Arrays.asList` view writes through to the
        // caller's array.
        List<String> sorted = new ArrayList<String>(modIds);
        Collections.sort(sorted);
        return fnv(String.join("\n", sorted));
    }

    /**
     * The provenance file, named ONCE because #194 gave it a second speller.
     *
     * `writeSummary` has always written it; `readModSet` now reads it back to decide whether
     * a dump may overwrite another. A writer and a reader of the same file that each spell
     * its name are two places to change and one to forget, and forgetting here does not
     * throw -- `readModSet` would simply find no file, report "cannot say", and the
     * clobber guard would wave every dump through while looking exactly as green.
     */
    static final String SUMMARY_FILE = "summary.json";

    /** What a dump directory already on disk says about the jars that wrote it. */
    static final class ModSet {
        /** -1 when unrecorded, which is every dump written before schema 6. */
        final int count;
        /** null when unrecorded. */
        final String digest;

        ModSet(int count, String digest) {
            this.count = count;
            this.digest = digest;
        }
    }

    /** `mod_count` and `mod_digest` out of a summary.json, unrecorded reading as absent. */
    static ModSet readModSet(File summary) {
        int count = -1;
        String digest = null;
        if (summary.isFile()) {
            try (JsonReader r = new JsonReader(new InputStreamReader(
                    Files.newInputStream(summary.toPath()), StandardCharsets.UTF_8))) {
                r.beginObject();
                while (r.hasNext()) {
                    String field = r.nextName();
                    if (r.peek() == JsonToken.NULL) {
                        r.nextNull();
                    } else if (field.equals("mod_count")) {
                        count = r.nextInt();
                    } else if (field.equals("mod_digest")) {
                        digest = r.nextString();
                    } else {
                        r.skipValue();
                    }
                }
            } catch (Throwable ignored) {
                // An unreadable summary is a dump we cannot vouch for either way, and
                // `refuseToClobber` treats "cannot say" as permission to proceed. Refusing
                // on a corrupt summary would strand anyone whose last dump was interrupted.
            }
        }
        return new ModSet(count, digest);
    }

    /**
     * Why a dump must not be written here, or null to go ahead. #194
     *
     * THE ONE IRREVERSIBLE MISTAKE AVAILABLE IN THIS COMMAND. Everything else a dump gets
     * wrong is fixed by dumping again; this one destroys the input to that fix. The output
     * directory is `<gamedir>/mc-recipe-dump` with no way to redirect it, so a run against a
     * SMALLER jar set -- a dev client, a server-side instance, the headless harness -- lands
     * on top of the pack's real dump and replaces it. That artifact costs a launch of the full
     * 367-jar pack to reproduce, which is the whole reason #123, #87 and #90 spent months
     * queued behind one.
     *
     * A DISTINCT PATH FOR NON-PLAYER DUMPS WAS THE OTHER CANDIDATE AND IS WEAKER. It only
     * protects the artifact when the caller correctly declares itself the odd one out, so it
     * covers the harness and misses the dev client someone runs by hand -- and the caller
     * that most needs protecting is the one that did not realise which pack it was in. This
     * compares what is actually on disk against what is actually loaded, so it does not care
     * who is asking.
     *
     * SILENT WHENEVER IT CANNOT COMPARE: no directory, no summary, a summary from before
     * schema 6, or a `Loader` that would not answer. Every one of those is an absence of
     * evidence, and refusing on one would block the first dump after any mod upgrade -- a
     * guard that fires on the normal path is a guard that gets forced past without reading.
     */
    static String refuseToClobber(File dir, List<String> modIds, boolean force) {
        String digest = modDigest(modIds);
        if (force || digest == null || !dir.isDirectory()) {
            return null;
        }
        ModSet existing = readModSet(new File(dir, SUMMARY_FILE));
        if (existing.digest == null || existing.digest.equals(digest)) {
            return null;
        }
        return "REFUSING to dump: " + dir.getName() + " already holds a dump written by a "
                + "DIFFERENT set of mods ("
                + (existing.count >= 0 ? String.valueOf(existing.count) : "an unrecorded"
                        + " number of") + " mods there, " + modIds.size() + " loaded here)."
                + " Overwriting it would destroy an artifact that costs a game launch to"
                + " reproduce. Move or delete it, or re-run `/recipedump " + FORCE_ARG
                + "` if replacing it is what you meant.";
    }

    /**
     * @param threw       wrapper failures -- things that went wrong
     * @param skipLines   lines in skipped.ndjson -- everything JEI declined to give us,
     *                    failures included
     * @param names       entries names.json is about to receive, so a reader can tell a
     *                    truncated names.json from a short one
     * @param namesFailed keys this dump has no display name for at all. #194
     * @param catalystSlots  input slots that got a non-default `p`. #175
     * @param chanceOutputs  output stacks that got a non-default `q`. #223. A SECOND NUMBER
     *                    rather than a sum with the one above, because the two sides fail
     *                    independently and a total of zero would not say which reader broke.
     * @param modIds      the modids of every loaded mod, or null when Forge would not
     *                    say -- in which case NEITHER field is written, because a reader
     *                    must be able to tell "not recorded" from a recorded number. #194
     */
    // Package-visible, not private, for the same reason `writeNbtTrace` is: `SchemaSixTest`
    // writes a real summary.json with it and asserts the fields #194 added are in the
    // output, so the python reader is held to bytes this writer actually produced rather
    // than to a hand-typed guess at its shape. Two mocks agreeing with each other is the
    // failure mode.
    static void writeSummary(File file, Map<String, int[]> perCategory,
                             Map<String, String> categoryMod,
                             int recipes, int threw, int skipLines,
                             int names, int namesFailed, int catalystSlots,
                             int chanceOutputs, List<String> modIds) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            // Stamp what produced this. Without it a dump is undatable: the only signal
            // that catalysts.json was missing because the mod predated it, rather than
            // because the category list genuinely had none, was its absence.
            w.write("{\n \"mod_version\": \"" + safe(RecipeDumpMod.version()) + "\""
                    + ",\n \"schema\": " + SCHEMA);
            // `threw` was spelled `skipped` through schema 4 while counting nothing of the
            // sort -- 0 on a dump whose skipped.ndjson had 22,188 lines. It now matches the
            // per-category tally beside it, and matches what gaps.py has always called it.
            // The line count it was mistaken for is its own field. #90
            w.write(",\n \"recipes\": " + recipes + ",\n \"threw\": " + threw
                    + ",\n \"skip_lines\": " + skipLines);
            // TWO NUMBERS BECAUSE ONE CANNOT BE CHECKED. `names_failed` alone says a dump
            // lost some display names; it cannot say whether names.json then arrived
            // intact, because the total it should hold is not knowable from anything else
            // the dump records. `names` is that total, so the python reader can compare it
            // against the file's actual length and refuse a truncated one. #194
            w.write(",\n \"names\": " + names + ",\n \"names_failed\": " + namesFailed);
            // ZERO HERE IS A MEASUREMENT, ABSENT IS NOT, which is the same distinction
            // `names_failed` was added to make and the reason this is written
            // unconditionally. A schema-7 dump saying 0 has looked and found no permanent
            // input; a schema-6 dump says nothing and could be either. Without it a bridge
            // that quietly stopped resolving -- a Tinkers update renaming its recipe class,
            // a pack dropping Modular Machinery -- produces a dump indistinguishable from a
            // pack that genuinely has no catalysts, and the only symptom is that plans get
            // slowly more expensive. #175
            w.write(",\n \"catalyst_slots\": " + catalystSlots);
            // THE SAME MEASUREMENT ON THE OTHER SIDE, and unconditional for the same reason.
            // A schema-8 dump saying 0 has looked and found no chance output; a schema-7 dump
            // says nothing and could be either. Nearly every output chance in the reference
            // pack is fractional, so a 0 from THIS pack means the reader broke rather than
            // that the pack is clean. #223
            w.write(",\n \"chance_outputs\": " + chanceOutputs);
            // OMITTED ENTIRELY when Forge would not answer, rather than written as 0 or
            // null. The fields exist to let a reader tell a five-jar dump from a full-pack
            // one, and a recorded zero is a claim about the jar set; absence is the honest
            // shape for "not measured", and it is the shape every schema-5 dump already
            // has, so the reader needs no second spelling of the same absence. #194
            if (modIds != null) {
                w.write(",\n \"mod_count\": " + modIds.size()
                        + ",\n \"mod_digest\": \"" + safe(modDigest(modIds)) + "\"");
            }
            w.write(",\n \"categories\": {");
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

    private static int writeOreDict(File file) {
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

    /**
     * Whether this item's METADATA IS ITS DURABILITY rather than a subtype. Issue #118.
     *
     * THIS IS THE WHOLE ANSWER TO "ARE THESE 46 ROWS ONE ITEM". On the reference graph 46
     * keys are called Iron Axe -- `minecraft:iron_axe`, `:1`, `:2` ... `:250`, plus the
     * 32767 wildcard -- because every durability value the axe has ever been seen at
     * becomes its own key with its own stock count. 804 families do this, covering 9,579
     * keys, and the reported symptom is a search listing dozens of identical rows with the
     * count sitting on exactly one.
     *
     * THE TEMPTING DETECTOR IS WRONG AND #110 IS THE PROOF. "Same base id, same display
     * name, different meta, therefore the same thing" also matches `chisel:lapis:0` through
     * `:8`, nine decorative blocks that are genuinely nine different blocks all called Lapis
     * Lazuli Block, and `ebwizardry:spell_book`, whose 286 metas are 286 different spells.
     * Collapsing those would undo the distinction #110 established. Nothing in a dump could
     * separate them, which is why this predicate needs the item registry and therefore
     * needs the game.
     *
     * BOTH CLAUSES ARE LOAD-BEARING. `maxDamage > 0` alone would merge an item that has
     * durability AND subtypes, and vanilla's own `Item#isDamageable` is not usable here
     * because it lets `hasSubtypes` through on any stack-size-1 item -- which is most tools,
     * so it would answer "damageable" for exactly the ambiguous case this has to refuse.
     *
     * THE DEPRECATED NO-ARG `getMaxDamage()` IS THE RIGHT ONE HERE, and Forge's suggested
     * replacement is the wrong question. `getMaxDamage(ItemStack)` asks "how much damage can
     * THIS stack take", which a mod may answer from the stack's own NBT; the question being
     * asked is "does meta mean damage for this ITEM", which is a property of the registered
     * item and has no stack to ask about. Handing it a synthesised stack invites every
     * override in 370-odd mods to read NBT that is not there. DO NOT "fix" this to the
     * stack-sensitive overload.
     */
    @SuppressWarnings("deprecation")
    static boolean damageable(Item item) {
        return item != null && item.getMaxDamage() > 0 && !item.getHasSubtypes();
    }

    /**
     * `damageable.json`: {registry id: {"d": maxDamage, "s": hasSubtypes}}.
     *
     * ONLY items with a positive maxDamage are written, which is what keeps the file small:
     * maxDamage 0 means the meta is a subtype, and that is the default the reader assumes
     * for everything absent. `s` is carried even though every entry could be filtered on it
     * here, because a reader that can see both fields can say WHY it declined to collapse a
     * family; one handed a pre-filtered list can only say that it did.
     *
     * @return how many entries were written, for the chat summary
     */
    @SuppressWarnings("deprecation")  // see damageable(Item) for why the no-arg getter
    private static int writeDamageable(File file) {
        int count = 0;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            boolean first = true;
            for (Item item : Item.REGISTRY) {
                if (item == null) {
                    continue;
                }
                ResourceLocation id = item.getRegistryName();
                int max;
                boolean subtypes;
                try {
                    max = item.getMaxDamage();
                    subtypes = item.getHasSubtypes();
                } catch (Throwable t) {
                    // A modded item that throws on a registry getter is not worth losing
                    // the other 35,000 over; absent means "meta is a subtype", which is the
                    // conservative answer and the one that changes no behaviour.
                    continue;
                }
                if (id == null || max <= 0) {
                    continue;
                }
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write(" \"" + safe(id.toString()) + "\": {\"d\": " + max + ", \"s\": "
                        + subtypes + "}");
                count++;
            }
            w.write(first ? "}\n" : "\n}\n");
        } catch (IOException ignored) {
            // Losing this costs the meta collapse in search, not correctness: without it
            // every damage value stays its own row, which is exactly today's behaviour.
        }
        return count;
    }

    /**
     * `machine_names.json`: Modular Machinery's own answer to "which machine is this".
     * Issue #55.
     *
     * <pre>
     * {"machines":   {"modularmachinery:dragonfire_crucible": "Dragonfire Crucible"},
     *  "blueprints": {"modularmachinery:itemblueprint#010c58f252c0":
     *                 "modularmachinery:dragonfire_crucible"}}
     * </pre>
     *
     * TWO MAPS RATHER THAN ONE FLATTENED {blueprint key: display name}, because they go
     * stale independently and because the machine id is worth having on its own. A
     * blueprint's key changes with every redump that moves the digest; a machine's registry
     * name does not, and the python side links `modularmachinery.recipes.&lt;reg&gt;` and
     * `modularmachinery:&lt;reg&gt;_controller` by exactly that string already.
     *
     * A SEPARATE FILE RATHER THAN OVERRIDES MERGED INTO names.json, which #55 left open.
     * names.json is {key: display name} and has nowhere to put the machine id, so merging
     * would have thrown away the durable half to save a file. It also makes the staleness
     * legible: a dump with an empty `machines` map was taken on a pack without Modular
     * Machinery, and that is visibly different from a name that merely looks wrong.
     */
    private static void writeMachineNames(File file, Map<String, String> machines,
                                          Map<String, String> blueprints) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n \"machines\": {");
            boolean first = true;
            for (Map.Entry<String, String> e : machines.entrySet()) {
                w.write((first ? "\n" : ",\n") + "  \"" + safe(e.getKey()) + "\": \""
                        + safe(e.getValue()) + "\"");
                first = false;
            }
            w.write(first ? "}," : "\n },");
            w.write("\n \"blueprints\": {");
            first = true;
            for (Map.Entry<String, String> e : blueprints.entrySet()) {
                w.write((first ? "\n" : ",\n") + "  \"" + safe(e.getKey()) + "\": \""
                        + safe(e.getValue()) + "\"");
                first = false;
            }
            w.write(first ? "}\n}\n" : "\n }\n}\n");
        } catch (IOException ignored) {
            // Losing this costs a label. Plans through these keys stay correct; they just
            // go on saying "1 Machine Blueprint" without saying which.
        }
    }

    /**
     * `emc.json`: {discriminated key: ProjectE EMC value}. Issue #50.
     *
     * ONLY POSITIVE VALUES ARE WRITTEN, and that is the file's whole safety property. 0 is
     * ProjectE's answer for "no EMC, cannot be transmuted", and it is also what
     * {@link ProjectEBridge#emc} returns when the lookup throws -- so absence from this
     * file always means "this tool has no evidence of a transmutation route", never "there
     * probably is one". #50's stated worst case is asserting a route the pack has actually
     * disabled, which would be worse than the dead end it replaces.
     *
     * PACK DATA ONLY. What the player has LEARNED and how much EMC they hold is world
     * state, belongs in the have file, and is read from playerdata rather than from here --
     * the same split #112 drew between `graph.dimension_ores` and a visited dimension. Bake
     * a player's knowledge into a graph and it is wrong the moment they learn something.
     */
    private static void writeEmc(File file, Map<String, Long> emc) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n");
            boolean first = true;
            for (Map.Entry<String, Long> e : emc.entrySet()) {
                if (!first) {
                    w.write(",\n");
                }
                first = false;
                w.write(" \"" + safe(e.getKey()) + "\": " + e.getValue());
            }
            w.write(first ? "}\n" : "\n}\n");
        } catch (IOException ignored) {
            // Losing this costs the EMC terminator, and a drop-only item goes back to
            // dead-ending on its loot token, which is where it was before #50.
        }
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

    /**
     * Minimal JSON string escaping; avoids depending on a JSON library.
     *
     * Package-visible so {@link IconAtlas} escapes its keys the same way every other file
     * does. A second copy would be a second chance to disagree about a key that has to join
     * across files.
     */
    static String safe(String s) {
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
