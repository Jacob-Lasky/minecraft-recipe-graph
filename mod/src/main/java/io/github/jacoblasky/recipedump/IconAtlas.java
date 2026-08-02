package io.github.jacoblasky.recipedump;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Renders each item to a 16x16 sprite and writes `icons-N.png` plus `icons.json`.
 * Issue #36.
 *
 * WHY IN THE MOD AND NOT IN THE OFFLINE JAR READER. Item textures do live in the jars, at
 * `assets/&lt;modid&gt;/textures/items/&lt;name&gt;.png`, and a python-side extractor was the
 * obvious plan. Three things defeat it, and all three are free here:
 *
 *   * The texture name is not the registry name. It comes from the item's model JSON
 *     (`models/item/&lt;name&gt;.json` -&gt; textures.layer0), and blocks resolve through a
 *     blockstate instead.
 *   * Metadata variants have separate models. `thermalexpansion:machine:3` is not
 *     `machine.png`, and in this pack that is the rule rather than the exception.
 *   * Some items have no static texture at all -- anything drawn by a TESR, anything
 *     tinted at runtime (leaves, potions, petals).
 *
 * This code is standing inside the running game with the model system loaded, so it asks
 * the same renderer the player's inventory asks and gets whatever the player sees.
 *
 * IT RUNS LAST, AFTER EVERY OTHER FILE IS WRITTEN AND CLOSED, AND THAT IS THE WHOLE SAFETY
 * ARGUMENT. Rendering ~40,000 arbitrary modded ItemStacks offscreen touches GL state and
 * TESR code paths that were never written to run outside a GUI frame, and some of them will
 * throw. The expensive, irreplaceable thing in this project is the launch of the game that
 * produced the dump -- so nothing here is allowed to be upstream of recipes.ndjson. A total
 * failure of this phase costs icons and costs nothing else. DO NOT move it earlier, and do
 * not fold it into the recipe walk to save a pass.
 *
 * The counts it reports (rendered / blank / threw) are the point of reporting at all: the
 * next chance to fix a bad render is another launch, so the launch itself has to say
 * whether it worked rather than leaving it to be discovered days later.
 */
final class IconAtlas {

    /** Edge of one sprite, in pixels. 16 is the native size of a 1.12.2 item texture. */
    static final int ICON_PX = 16;

    /**
     * Edge of one atlas page. 2048 gives 16,384 sprites a page, so the reference pack's
     * item list lands in a handful of files.
     *
     * DELIBERATELY NOT 4096, which would fit the whole pack in one page. Every page is
     * read back whole and held as a BufferedImage while it is encoded, so the page edge
     * squared times 8 bytes is transient heap -- 16 MB here against 67 MB there, on a
     * client that has just finished a 245 MB dump. One extra file is the cheaper half of
     * that trade.
     */
    static final int PAGE_PX = 2048;

    static final int COLS = PAGE_PX / ICON_PX;
    static final int PER_PAGE = COLS * COLS;

    /** Per-tick render budget, matching the recipe walk's. A tick is 50ms. */
    private static final long BUDGET_NANOS = 15_000_000L;

    private final ICommandSender sender;
    private final File dir;
    private final List<Map.Entry<String, ItemStack>> targets;
    private final Runnable done;

    /**
     * {key: [page, column, row]} for every sprite that actually drew something, and the
     * page files written so far.
     *
     * Package-visible so `SchemaFiveTest` can build the one state no unit test could
     * otherwise reach -- a flushed page plus a half-rendered one -- and hand the real
     * writer's output to the python reader. Rendering itself needs a GL context.
     */
    final Map<String, int[]> placed = new LinkedHashMap<String, int[]>();
    final List<String> pages = new ArrayList<String>();

    private Framebuffer page;
    private int pageIndex;
    private int slot;
    private int cursor;
    private int blank;
    private int threw;
    private int nextProgressPercent = 25;
    private final long startedAt = System.nanoTime();

    IconAtlas(ICommandSender sender, File dir, Map<String, ItemStack> targets, Runnable done) {
        this.sender = sender;
        this.dir = dir;
        this.targets = new ArrayList<Map.Entry<String, ItemStack>>(targets.entrySet());
        this.done = done;
    }

    void start() {
        MinecraftForge.EVENT_BUS.register(this);
        // SAY THAT THE DUMP IS NOT OVER, because the first real run was closed seven
        // seconds in. A phase that starts after a wall of completion messages has to say it
        // is a phase, and has to name the line that really ends the run.
        //
        // NO TIME ESTIMATE. The first version of this said "THIS TAKES A FEW MINUTES", which
        // was a guess extrapolated from a run that was killed -- measured, 35,675 icons
        // render in 8.3 seconds. A wrong estimate in the reassuring direction is how the
        // first run was lost; a wrong one in the other direction is a tool that cries wolf,
        // and the honest instruction ("wait for this line") needs no number at all.
        reply(String.format("rendering %,d item icons -- NOT DONE YET, leave the game open "
                + "until the \"next:\" line appears (%s to skip next time)",
                targets.size(), DumpCommand.NO_ICONS_ARG));
        reply(String.format("  written %,d at a time, so a run cut short still keeps the "
                + "pages it finished", PER_PAGE));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            step();
        } catch (Throwable fatal) {
            // The phase itself came apart. Say so and stop, rather than throwing on every
            // tick forever with the dump already on disk and nobody able to tell why.
            reply("icon rendering failed and was abandoned: " + fatal);
            abandon();
        }
    }

    private void step() {
        if (cursor >= targets.size()) {
            flushPage();
            writeIndex();
            report();
            abandon();
            return;
        }
        if (page == null) {
            page = new Framebuffer(PAGE_PX, PAGE_PX, true);
            slot = 0;
        }

        page.bindFramebuffer(true);
        beginPage();
        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (cursor < targets.size() && slot < PER_PAGE && System.nanoTime() < deadline) {
            Map.Entry<String, ItemStack> target = targets.get(cursor);
            int col = slot % COLS;
            int row = slot / COLS;
            try {
                Minecraft.getMinecraft().getRenderItem()
                        .renderItemAndEffectIntoGUI(target.getValue(),
                                col * ICON_PX, row * ICON_PX);
                placed.put(target.getKey(), new int[] {pageIndex, col, row});
            } catch (Throwable t) {
                threw++;
                // A throw mid-render leaves GL wherever it stopped, and the next item would
                // inherit it. Re-establishing the known state is cheaper and far more
                // legible than glPushAttrib, which bypasses GlStateManager's cache and
                // desynchronises it from the driver.
                beginPage();
            }
            cursor++;
            slot++;
        }
        endPage();
        unbind();
        reportProgress();

        if (slot >= PER_PAGE) {
            flushPage();
            // THE INDEX IS REWRITTEN AFTER EVERY PAGE, NOT ONCE AT THE END, AND THAT IS THE
            // WHOLE DIFFERENCE BETWEEN A SHORT RUN BEING USEFUL AND BEING WASTE.
            //
            // Measured, on the first real run of this code: the phase announced 35,675
            // icons, filled and wrote page 0 -- 16,361 of 16,384 sprites, verified good --
            // and the game was closed seven seconds later. `icons.json` was written only in
            // the terminal branch, so a complete, correct 3.6 MB atlas page was left on disk
            // with nothing to say which item was where, which makes it exactly as useful as
            // no page at all. Writing here costs one rewrite of a ~1.5 MB JSON file per
            // page and makes every completed page survive.
            //
            // AFTER `flushPage`, not before: `placed` still holds entries for the page being
            // rendered until the flush settles them, and `dropBlanks` runs inside the flush.
            // Written earlier, the index would name sprites on a page that does not exist.
            writeIndex();
        }
    }

    /**
     * A percentage every quarter, matching the recipe walk's.
     *
     * NOT decoration. This phase runs for minutes after a line that used to say "done", and
     * a player watching a still screen with no output has no way to tell rendering from
     * hung -- which is a reason to close the game, and closing the game is precisely what
     * cost the first run its icons.
     */
    private void reportProgress() {
        int percent = (int) (100L * cursor / Math.max(targets.size(), 1));
        if (percent >= nextProgressPercent && percent < 100) {
            reply(String.format("  icons %d%% -- %,d of %,d", percent, cursor,
                                targets.size()));
            while (nextProgressPercent <= percent) {
                nextProgressPercent += 25;
            }
        }
    }

    /**
     * The GUI render state `renderItemAndEffectIntoGUI` expects: an ortho projection in
     * pixel coordinates with y running down, and the standard inventory lighting.
     *
     * This is vanilla's own GUI setup with the ScaledResolution taken out, because the
     * target is an atlas page rather than the window. The near/far pair and the -2000
     * translate are copied deliberately: `setupGuiTransform` places an item at
     * z = 100 + zLevel, which is only inside the frustum for these exact numbers.
     */
    private void beginPage() {
        RenderItem render = Minecraft.getMinecraft().getRenderItem();
        render.zLevel = 0.0F;
        GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, PAGE_PX, PAGE_PX, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        RenderHelper.enableGUIStandardItemLighting();
    }

    private void endPage() {
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
    }

    private void unbind() {
        page.unbindFramebuffer();
        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
    }

    /** Read the current page back, write it as a PNG, and drop it. No-op when empty. */
    private void flushPage() {
        if (page == null || slot == 0) {
            return;
        }
        String name = "icons-" + pageIndex + ".png";
        try {
            page.bindFramebuffer(true);
            ByteBuffer buf = BufferUtils.createByteBuffer(PAGE_PX * PAGE_PX * 4);
            GL11.glReadPixels(0, 0, PAGE_PX, PAGE_PX, GL11.GL_RGBA,
                              GL11.GL_UNSIGNED_BYTE, buf);
            unbind();
            BufferedImage img = toImage(buf);
            dropBlanks(img);
            ImageIO.write(img, "PNG", new File(dir, name));
            pages.add(name);
        } catch (Throwable t) {
            // Lose this page's sprites rather than the whole phase: forget where they were
            // so icons.json never points at a file that is not there.
            for (Map.Entry<String, int[]> e :
                    new ArrayList<Map.Entry<String, int[]>>(placed.entrySet())) {
                if (e.getValue()[0] == pageIndex) {
                    placed.remove(e.getKey());
                }
            }
            reply("icon page " + pageIndex + " could not be written: " + t);
        } finally {
            try {
                page.deleteFramebuffer();
            } catch (Throwable ignored) {
                // Leaking one FBO at the end of a dump is not worth failing over.
            }
            page = null;
            pageIndex++;
            slot = 0;
        }
    }

    /**
     * glReadPixels rows arrive bottom-up, so row r of the buffer is row height-1-r of the
     * image. Getting this backwards produces a page that looks plausible -- every sprite is
     * intact, the grid is the right shape -- and maps every key to the wrong picture.
     */
    private static BufferedImage toImage(ByteBuffer buf) {
        BufferedImage img = new BufferedImage(PAGE_PX, PAGE_PX, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < PAGE_PX; y++) {
            int src = (PAGE_PX - 1 - y) * PAGE_PX * 4;
            for (int x = 0; x < PAGE_PX; x++) {
                int i = src + x * 4;
                int r = buf.get(i) & 0xff;
                int g = buf.get(i + 1) & 0xff;
                int b = buf.get(i + 2) & 0xff;
                int a = buf.get(i + 3) & 0xff;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /**
     * Forget any sprite that came out fully transparent, and count it.
     *
     * A MISSING ICON READS BETTER THAN AN EMPTY BOX, which is #36's own conclusion and the
     * reason this is not merely a statistic. An item with no static texture -- a TESR item,
     * anything the renderer declines to draw offscreen -- produces 256 transparent pixels,
     * and publishing that as an icon gives every such row a hole where its neighbours have
     * art. Dropping the entry lets the UI fall back to the per-mod hue chip it already
     * draws, which is a real signal rather than an absence pretending to be one.
     */
    private void dropBlanks(BufferedImage img) {
        List<String> empty = new ArrayList<String>();
        for (Map.Entry<String, int[]> e : placed.entrySet()) {
            int[] at = e.getValue();
            if (at[0] != pageIndex) {
                continue;
            }
            if (!hasPixels(img, at[1] * ICON_PX, at[2] * ICON_PX)) {
                empty.add(e.getKey());
            }
        }
        for (String key : empty) {
            placed.remove(key);
            blank++;
        }
    }

    private static boolean hasPixels(BufferedImage img, int x0, int y0) {
        for (int y = 0; y < ICON_PX; y++) {
            for (int x = 0; x < ICON_PX; x++) {
                if ((img.getRGB(x0 + x, y0 + y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * `icons.json`: the atlas geometry plus {key: [page, column, row]}.
     *
     * Column and row rather than pixel offsets, so the reader multiplies by `icon` and
     * cannot disagree with the writer about the sprite size. `pages` is a list of file
     * names in page order, which is also the list of files that exist -- a page that failed
     * to write is absent from it and from every entry, so a reader never has to handle a
     * dangling reference.
     */
    private void writeIndex() {
        writeIndex(new File(dir, "icons.json"));
    }

    /**
     * The file-taking form, so a test can write a PARTIAL index -- one flushed page, with
     * entries for it and none for the page still being rendered -- and hand the result to
     * the python reader. That partial case is the whole point of #123's follow-up and it is
     * the one shape no unit test could otherwise reach, because rendering needs a GL
     * context and a running game.
     *
     * Package-visible for the same reason `writeNbtTrace` is: the python side is then tested
     * against bytes this writer actually produced, rather than against a hand-typed guess at
     * its shape. Two mocks agreeing with each other is the failure mode.
     */
    void writeIndex(File file) {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8))) {
            w.write("{\n \"icon\": " + ICON_PX + ",\n \"page\": " + PAGE_PX
                    + ",\n \"cols\": " + COLS + ",\n \"pages\": [");
            for (int i = 0; i < pages.size(); i++) {
                w.write((i == 0 ? "" : ",") + "\"" + pages.get(i) + "\"");
            }
            w.write("],\n \"keys\": {");
            boolean first = true;
            for (Map.Entry<String, int[]> e : placed.entrySet()) {
                int[] at = e.getValue();
                w.write((first ? "\n" : ",\n") + "  \"" + DumpCommand.safe(e.getKey())
                        + "\": [" + at[0] + "," + at[1] + "," + at[2] + "]");
                first = false;
            }
            w.write(first ? "}\n}\n" : "\n }\n}\n");
        } catch (IOException e) {
            reply("icons.json could not be written: " + e);
        }
    }

    private void report() {
        long ms = (System.nanoTime() - startedAt) / 1_000_000L;
        reply(String.format(
                "icons: %,d rendered across %d page(s) in %.1fs; %,d blank, %,d threw",
                placed.size(), pages.size(), ms / 1000.0, blank, threw));
    }

    private void abandon() {
        MinecraftForge.EVENT_BUS.unregister(this);
        if (page != null) {
            try {
                page.deleteFramebuffer();
            } catch (Throwable ignored) {
                // See flushPage.
            }
            page = null;
        }
        done.run();
    }

    private void reply(String msg) {
        sender.sendMessage(new TextComponentString("[recipedump] " + msg));
    }
}
