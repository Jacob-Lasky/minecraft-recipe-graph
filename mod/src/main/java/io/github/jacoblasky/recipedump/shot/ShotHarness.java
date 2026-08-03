package io.github.jacoblasky.recipedump.shot;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * `-Dmcrecipedump.shot=<screen>` -- open one of our GUIs, write a PNG of it, exit.
 *
 * This exists because pocket-dev cannot run the game and the desktop can only run it by hand,
 * so every GUI iteration in #19 otherwise costs a manual launch of a 367-jar pack. Driven by
 * a system property rather than a command because a COMMAND needs someone to type it: the
 * whole point is that no human is at the keyboard. See harness/README.md.
 *
 * INERT UNLESS THE PROPERTY IS SET. `arm()` returns immediately in a normal client, nothing
 * subscribes to the event bus, and no class in this package beyond this one is ever loaded --
 * which is what lets the shipped jar go on declaring no ModularUI dependency.
 *
 * The harness EXITS THE JVM when it is done, non-zero on failure, because a PNG appearing on
 * disk is not evidence on its own -- a run that died would leave the PREVIOUS run's file
 * sitting there. Gradle collapses any non-zero client exit into its own failure, so
 * `harness/shot.sh` branches on zero-versus-not and the specific codes below are for reading
 * in the log beside the message that explains them.
 */
public final class ShotHarness {

    /** Which screen to shoot; `name` or `name:arg`. Absent means the harness is off. */
    public static final String PROP_SHOT = "mcrecipedump.shot";
    /** Where to write the PNG. Absent means `&lt;gamedir&gt;/shots/&lt;screen&gt;.png`. */
    public static final String PROP_OUT = "mcrecipedump.shotOut";
    /** Frames to let the screen settle before capturing. */
    public static final String PROP_SETTLE = "mcrecipedump.shotSettleFrames";
    /** Seconds to wait for the main menu before giving up. */
    public static final String PROP_TIMEOUT = "mcrecipedump.shotTimeoutSeconds";
    /** GUI scale for the shot. 0 leaves Minecraft's auto-scaling alone. */
    public static final String PROP_GUI_SCALE = "mcrecipedump.shotGuiScale";
    /** `true` keeps ModularUI's widget-outline overlay in the picture. */
    public static final String PROP_DEBUG_OVERLAY = "mcrecipedump.shotDebugOverlay";
    /** Frames to TIME after settling, driving the screen once per frame. 0 turns it off. */
    public static final String PROP_FRAMES = "mcrecipedump.shotTimedFrames";

    /**
     * Load a single-player world before opening the screen. Empty or absent means do not.
     *
     * TESTING A CLAIM THE README MADE FOR A FORTNIGHT WITHOUT CHECKING IT. That claim was
     * "It renders GUIs, not the world. No world is loaded, so anything that needs a player, a
     * tile entity or a server-side capability has nothing to draw from" -- which ruled Phase
     * 5's live AE2 read untestable here. It was never measured. Its neighbour in the same
     * section, "It has no input", was also never measured and turned out to be false, and
     * #146 found the JEI runtime, the recipe-category walk and item-model rendering all live
     * at the main menu after all three were assumed to need a world.
     *
     * OFF BY DEFAULT AND IT MUST STAY THAT WAY. Loading a world costs seconds and changes
     * what is on screen behind the panel, so every existing shot would move. This is opt-in
     * per run, not a new baseline.
     */
    public static final String PROP_WORLD = "mcrecipedump.shotWorld";

    /**
     * Frames between opening the screen and capturing it.
     *
     * NOT ZERO, and not a wall-clock delay either. ModularUI animates a panel open, and a
     * capture on the opening frame catches it mid-fade -- which produces a PNG that looks
     * like a rendering bug rather than like a timing one. Frames rather than milliseconds
     * because llvmpipe's frame rate depends on the host's spare CPU, so a fixed sleep would
     * settle a different amount of animation on a busy Tower than on an idle one.
     */
    private static final int DEFAULT_SETTLE_FRAMES = 20;

    /**
     * Wall-clock ceiling on reaching the main menu, after which the run fails.
     *
     * Generous because a cold dev client under llvmpipe spends real time in Forge's mod
     * loading and in building the texture atlas. Its job is to turn a client that will never
     * get there -- a missing dependency puts Forge on an error GUI and stops -- into a
     * non-zero exit and a log line naming the screen it is stuck on, instead of a container
     * that sits there until someone notices.
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /** 2 is what a player runs at; Minecraft's auto-scaler picks 4 at the shot resolution. */
    private static final int DEFAULT_GUI_SCALE = 2;

    /** 0 is the PNG; everything else is the harness saying, in the log, why there is not one. */
    private static final int EXIT_OK = 0;
    private static final int EXIT_NO_SCREEN = 2;
    private static final int EXIT_TIMEOUT = 3;
    private static final int EXIT_WRITE_FAILED = 4;

    private ShotHarness() {
    }

    /**
     * Subscribe the harness if `-Dmcrecipedump.shot` asked for a screenshot.
     *
     * Called from the mod's init event, which in 1.12.2 runs during `Minecraft.init()` --
     * after the window and the GL context exist, and before the main loop. So a tick
     * subscriber registered here is guaranteed to see every frame from the first one.
     */
    public static void arm() {
        String spec = System.getProperty(PROP_SHOT);
        if (spec == null || spec.trim().isEmpty()) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new ShotHarness.Runner(spec.trim()));
        // The heap ceiling is printed because this runs in a memory-capped container on a
        // host that also runs the household's automation, and the cap is applied by a
        // build-script argument that nothing else would report. An unexpectedly large number
        // here is the warning that the cap did not take, BEFORE the container is OOM-killed.
        log("armed for screen '" + spec.trim() + "'; max heap "
                + (Runtime.getRuntime().maxMemory() / (1024L * 1024L)) + " MB");
    }

    /**
     * Waits for the main menu, opens the screen, counts down frames, captures, exits.
     *
     * Split across the two tick events on purpose. The screen is OPENED from the client tick,
     * where `displayGuiScreen` is a normal thing to do; it is CAPTURED from the render tick,
     * where the frame that was just drawn is still in `framebufferMc`. `runTick` runs before
     * the render section of the same `runGameLoop` pass, so the ordering within a frame is
     * open-then-draw-then-capture and never the other way round.
     */
    public static class Runner {

        private final String spec;
        /** {@link #PROP_WORLD}: empty for no world, otherwise the save folder to use. */
        private final String world;
        /** True once the integrated server has been asked to start. */
        private boolean worldRequested;
        private final int settleFrames;
        private final int timedFrames;
        private final long deadlineNanos;
        /**
         * DRAW cost per frame: render-tick START to END, in nanoseconds.
         *
         * THE DRAW AND NOT THE PERIOD, and that distinction is the whole reason this mode
         * produces a usable number. Minecraft throttles hard when no world is loaded, so the
         * gap between one frame and the next is a deliberate sleep and measuring it reports
         * the throttle rather than the work -- the first version of this did exactly that and
         * came back with 33.2ms, which is 30.0 fps to three figures, on a screen holding four
         * widgets. Disabling vsync changed nothing, because vsync was never the cause.
         *
         * START to END brackets the render and excludes `Display.update` and the frame
         * limiter's wait, both of which happen after `onRenderTickEnd`. "Does the draw fit in
         * 16.67ms" is also the question the 60 fps gate is actually asking.
         */
        private long[] periods;
        /** Wall-clock period, kept only to show how much of it was throttle. */
        private long[] wallPeriods;
        private int timed;
        private long previousFrameNanos;
        private long drawStartedNanos;

        /** null until the screen has been asked for; then counts frames down to the capture. */
        private Integer framesLeft;
        private long lastReportNanos;

        Runner(String spec) {
            this.spec = spec;
            this.world = System.getProperty(PROP_WORLD, "").trim();
            this.settleFrames = intProperty(PROP_SETTLE, DEFAULT_SETTLE_FRAMES);
            this.timedFrames = Math.max(0, intProperty(PROP_FRAMES, 0));
            this.deadlineNanos = System.nanoTime()
                    + intProperty(PROP_TIMEOUT, DEFAULT_TIMEOUT_SECONDS) * 1_000_000_000L;
            this.lastReportNanos = System.nanoTime();
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || framesLeft != null) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (!world.isEmpty()) {
                if (!worldReady(mc)) {
                    return;
                }
            } else if (!(mc.currentScreen instanceof GuiMainMenu)) {
                waitOrTimeOut(mc.currentScreen);
                return;
            }
            setGuiScale();
            setModularUiDebugOverlay(Boolean.getBoolean(PROP_DEBUG_OVERLAY));
            String problem = ShotScreens.open(spec);
            if (problem != null) {
                log("cannot open the requested screen: " + problem);
                exit(EXIT_NO_SCREEN);
                return;
            }
            if (mc.currentScreen == null || mc.currentScreen instanceof GuiMainMenu) {
                // The opener ran without throwing and the screen did not change. Reported
                // separately from a throw because the cause is different: the screen decided
                // not to open, rather than failing to.
                log("screen '" + spec + "' did not replace the main menu");
                exit(EXIT_NO_SCREEN);
                return;
            }
            log("opened " + mc.currentScreen.getClass().getName()
                    + "; settling " + settleFrames + " frames");
            framesLeft = settleFrames;
        }

        @SubscribeEvent
        public void onRenderTickStart(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START && framesLeft != null) {
                drawStartedNanos = System.nanoTime();
            }
        }

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END || framesLeft == null) {
                return;
            }
            if (framesLeft > 0) {
                framesLeft = framesLeft - 1;
                return;
            }
            if (timedFrames > 0 && timed <= timedFrames) {
                measure();
                return;
            }
            capture();
        }

        /**
         * One timed frame: record how long the last one took, then drive the screen.
         *
         * THE PERIOD BETWEEN SUCCESSIVE RENDER TICKS is the frame time, which is what "fps"
         * means here -- not the duration of any one draw call. The first timed frame has no
         * predecessor and so contributes no period; that is why the loop runs to
         * `timedFrames` inclusive and the sample count is one lower.
         */
        private void measure() {
            long now = System.nanoTime();
            if (periods == null) {
                periods = new long[timedFrames];
                wallPeriods = new long[timedFrames];
                unclamp();
                log("timing " + timedFrames + " frames"
                        + (ShotScreens.animated() == null
                           ? " of a STATIC screen -- it registered nothing to drive, so this "
                             + "measures redraw and not panning"
                           : ", driving the screen once per frame"));
            } else {
                periods[timed - 1] = now - drawStartedNanos;
                wallPeriods[timed - 1] = now - previousFrameNanos;
            }
            previousFrameNanos = now;
            ShotScreens.Animated screen = ShotScreens.animated();
            if (screen != null) {
                try {
                    screen.step(timed);
                } catch (Throwable t) {
                    log("the screen threw while being driven at frame " + timed + ": " + t);
                    exit(EXIT_WRITE_FAILED);
                    return;
                }
            }
            timed++;
            if (timed > timedFrames) {
                report();
            }
        }

        /**
         * Turn off vsync and the frame limiter before timing anything.
         *
         * WITHOUT THIS THE HARNESS MEASURES THE CLAMP, NOT THE WORK, and it does so
         * convincingly. Minecraft 1.12.2 defaults to vsync ON and a 120 fps limit; under
         * Xvfb the first timing run reported p50 = 33.23ms on a screen holding four widgets,
         * which is 30.0 fps to three figures. That is not a rasteriser being slow, it is
         * vsync missing the 60 Hz deadline and landing on the next one -- the classic
         * halving. A number that lands on an exact submultiple of 60 is the tell.
         *
         * `Display.setSwapInterval(0)` as well as the settings field, because the field is
         * only read when the options screen applies it and nothing here opens that screen.
         */
        /**
         * Pin the GUI scale, because Minecraft's automatic one is not what anybody plays at.
         *
         * AUTO-SCALING PICKS 4 AT THIS RESOLUTION, giving a 320x200 logical screen -- 1280/4.
         * That is a quarter of the area a player has at 1920x1080 on scale 2, and any panel
         * wider than 320 silently runs off the edge. It cost real time: a diagram panel sized
         * 620x380 came back as one flat rectangle with no border and no content, which reads
         * as a rendering bug and was a screen four times smaller than the one being designed
         * for. Every shot taken before this was at scale 4.
         *
         * Scale 2 at 1280x800 gives 640x400, which is the shape a real client has. DO NOT
         * remove this and rely on `--width` alone: resolution and scale are separate inputs,
         * and raising the first raises the auto scale with it.
         */
        private void setGuiScale() {
            int scale = intProperty(PROP_GUI_SCALE, DEFAULT_GUI_SCALE);
            if (scale <= 0) {
                return;
            }
            try {
                Minecraft mc = Minecraft.getMinecraft();
                mc.gameSettings.guiScale = scale;
                log("gui scale pinned to " + scale + "; logical screen is "
                        + (mc.displayWidth / scale) + "x" + (mc.displayHeight / scale));
            } catch (Throwable t) {
                log("could not pin the gui scale (" + t + "); the picture is at whatever "
                        + "Minecraft chose, which at this resolution is 4");
            }
        }

        private void unclamp() {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                mc.gameSettings.enableVsync = false;
                // 260 is what vanilla's own slider treats as unlimited.
                mc.gameSettings.limitFramerate = 260;
                org.lwjgl.opengl.Display.setSwapInterval(0);
            } catch (Throwable t) {
                // Worth continuing: a clamped measurement is still a measurement, and the
                // report says it is a floor either way. But say so, because an exact
                // submultiple of 60 in the output would otherwise look like a real result.
                log("could not disable vsync (" + t + "); frame times may be a clamp rather "
                        + "than the cost of the work");
            }
        }

        /**
         * The frame times, as percentiles.
         *
         * A FLOOR AND NOT A PREDICTION, and the log says so every run rather than leaving it
         * to whoever reads the number. This harness rasterises in software on a shared host:
         * llvmpipe's fill cost is far above a GPU's while the CPU-side widget work is
         * comparable, so HOLDING a frame budget here is strong evidence it holds on real
         * hardware, and MISSING one here is not evidence of anything -- it could be the
         * rasteriser, or another container on the box. Quote a pass; do not quote a fail.
         */
        private void report() {
            long[] sorted = java.util.Arrays.copyOf(periods, timedFrames - 1);
            java.util.Arrays.sort(sorted);
            long[] wall = java.util.Arrays.copyOf(wallPeriods, timedFrames - 1);
            java.util.Arrays.sort(wall);
            long budget = 16_666_667L;   // 60 fps
            int over = 0;
            for (long period : sorted) {
                if (period > budget) {
                    over++;
                }
            }
            log(String.format(java.util.Locale.ROOT,
                    "draw: n=%d  p50=%.2fms  p95=%.2fms  p99=%.2fms  max=%.2fms  "
                            + "over-16.67ms=%d (%.1f%%)",
                    sorted.length, ms(percentile(sorted, 50)), ms(percentile(sorted, 95)),
                    ms(percentile(sorted, 99)), ms(sorted[sorted.length - 1]), over,
                    100.0 * over / sorted.length));
            log(String.format(java.util.Locale.ROOT,
                    "wall period p50=%.2fms -- the difference is the client's own throttle, "
                            + "not work this diagram does", ms(percentile(wall, 50))));
            log("that is a FLOOR: software rasteriser on a shared host. A pass here implies "
                    + "a pass on a GPU; a miss here implies nothing.");
        }

        private static long percentile(long[] sorted, int percent) {
            int at = (int) Math.min(sorted.length - 1L, (long) sorted.length * percent / 100L);
            return sorted[at];
        }

        private static double ms(long nanos) {
            return nanos / 1_000_000.0;
        }

        /** Log what the client is sitting on every 15s, and fail once past the deadline. */
        /**
         * Drive the load of a single-player world, and answer whether it is ready.
         *
         * THE FAILURE MODE IS WHY THIS IS AS LONG AS IT IS. A superflat that does not
         * generate leaves the client sitting at the main menu, where the screen would open
         * perfectly and the screenshot would be indistinguishable from a run where the world
         * loaded -- the exact shape of the cursor probe's first version, which reported six
         * lines of AGREE while comparing nothing to nothing. So this returns true only on
         * POSITIVE evidence that a world exists, and on the way past it logs facts that
         * cannot be true at the main menu, rather than logging "ok".
         *
         * THE SAVE IS DELETED FIRST. `mod/run/saves` persists between runs, so a world left
         * behind by an earlier run -- or half-written by a crashed one -- would be silently
         * reused, and a harness whose result depends on what the last run left behind is not
         * a harness. Deleting costs milliseconds on a superflat.
         */
        private boolean worldReady(Minecraft mc) {
            if (!worldRequested) {
                if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiMainMenu)) {
                    waitOrTimeOut(mc.currentScreen);   // still on the loading screens
                    return false;
                }
                worldRequested = true;
                try {
                    mc.getSaveLoader().deleteWorldDirectory(world);
                } catch (Throwable t) {
                    // Not fatal: the usual reason is that there was nothing to delete.
                    log("could not clear a previous '" + world + "': " + t);
                }
                // SUPERFLAT AND CREATIVE, chosen so the world is the cheapest thing that is
                // still a world: no terrain generation to wait for, no survival tick load,
                // and a player who can fly rather than fall. `false, false` are map features
                // and hardcore, neither of which a probe wants.
                net.minecraft.world.WorldSettings settings =
                        new net.minecraft.world.WorldSettings(
                                1L, net.minecraft.world.GameType.CREATIVE, false, false,
                                net.minecraft.world.WorldType.FLAT);
                log("launching integrated server for a superflat world '" + world + "'");
                mc.launchIntegratedServer(world, world, settings);
                return false;
            }
            if (mc.world == null || mc.player == null) {
                waitOrTimeOut(mc.currentScreen);
                return false;
            }
            // POSITIVE EVIDENCE, not the absence of an error. Every fact here is one that is
            // simply unavailable at the main menu, so a reader can tell a loaded world from a
            // failed load without trusting this method.
            net.minecraft.util.math.BlockPos under =
                    new net.minecraft.util.math.BlockPos(mc.player.posX,
                            mc.player.posY - 1, mc.player.posZ);
            log("world loaded: dimension " + mc.player.dimension
                    + ", player at " + String.format("%.1f,%.1f,%.1f",
                            mc.player.posX, mc.player.posY, mc.player.posZ)
                    + ", block beneath = "
                    + mc.world.getBlockState(under).getBlock().getRegistryName()
                    + ", integratedServer=" + (mc.getIntegratedServer() != null)
                    + ", loadedTileEntities=" + mc.world.loadedTileEntityList.size());
            return true;
        }

        private void waitOrTimeOut(GuiScreen current) {
            long now = System.nanoTime();
            if (now - lastReportNanos > 15_000_000_000L) {
                lastReportNanos = now;
                log("waiting for the main menu; current screen is "
                        + (current == null ? "none" : current.getClass().getName()));
            }
            if (now > deadlineNanos) {
                // NAME THE SCREEN. A Forge error GUI, a missing-mod screen and a genuinely
                // slow load all look identical from outside, and the class name separates
                // them in one line without anyone having to read the whole client log.
                log("timed out before the main menu; current screen is "
                        + (current == null ? "none" : current.getClass().getName()));
                exit(EXIT_TIMEOUT);
            }
        }

        private void capture() {
            Minecraft mc = Minecraft.getMinecraft();
            File out = outputFile();
            try {
                // `ScreenShotHelper.createScreenshot` rather than a hand-written readback:
                // it is vanilla's own framebuffer-to-image path, so it already handles the
                // FBO-vs-backbuffer split, the BGRA byte order and the vertical flip -- three
                // things that are individually easy to get wrong and collectively produce a
                // picture that is upside down in the wrong colours.
                //
                // DO NOT swap this for `saveScreenshot`, which is the same readback plus a
                // writer: it hardcodes a `screenshots/` subdirectory under the directory you
                // pass and (in the 4-arg form) a timestamped filename, so the harness could
                // not put the PNG at the exact path the runner script is waiting for. It is
                // synchronous in 1.12.2, so that is the only reason it is rejected.
                BufferedImage image = ScreenShotHelper.createScreenshot(
                        mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    log("cannot create " + parent);
                    exit(EXIT_WRITE_FAILED);
                    return;
                }
                if (!ImageIO.write(image, "png", out)) {
                    log("no PNG writer accepted the image");
                    exit(EXIT_WRITE_FAILED);
                    return;
                }
                log("wrote " + out.getAbsolutePath() + " (" + image.getWidth() + "x"
                        + image.getHeight() + ", " + out.length() + " bytes)");
                exit(EXIT_OK);
            } catch (IOException e) {
                log("could not write " + out + ": " + e);
                exit(EXIT_WRITE_FAILED);
            } catch (Throwable t) {
                // A GL failure during readback surfaces here, and it is the interesting
                // outcome for #124: it is llvmpipe declining rather than the harness
                // misbehaving, so it must not be swallowed into a silent hang.
                log("screenshot failed: " + t);
                t.printStackTrace();
                exit(EXIT_WRITE_FAILED);
            }
        }

        private File outputFile() {
            String path = System.getProperty(PROP_OUT);
            if (path != null && !path.trim().isEmpty()) {
                return new File(path.trim());
            }
            String name = spec.replace(':', '-').replaceAll("[^A-Za-z0-9._-]", "_");
            return new File(new File(Minecraft.getMinecraft().gameDir, "shots"), name + ".png");
        }
    }

    /**
     * Turn ModularUI's widget-outline overlay off (or on) for the shot.
     *
     * IT IS ON BY DEFAULT IN A DEV WORKSPACE and it is not subtle: pink widget dumps down the
     * left edge, a "Debug Options" bar across the bottom, an outline on every widget. Left
     * alone it lands in every artifact attached to every GUI PR, where it obscures the thing
     * the reviewer is being asked to look at. `-Dmcrecipedump.shotDebugOverlay=true` gets it
     * back, which is genuinely useful when the question is where a widget resolved to.
     *
     * BY REFLECTION, and that is the point rather than laziness: this class must stay loadable
     * in a client with no ModularUI, because the shipped jar declares no dependency on it.
     * Naming `ModularUIConfig` directly would put it in this class's constant pool and make
     * every normal client's `arm()` a NoClassDefFoundError waiting to happen. Failure is
     * ignored for the same reason -- a missing overlay knob is not worth losing a screenshot.
     */
    private static void setModularUiDebugOverlay(boolean enabled) {
        try {
            Class.forName("com.cleanroommc.modularui.ModularUIConfig")
                    .getField("guiDebugMode").setBoolean(null, enabled);
        } catch (Throwable ignored) {
            // No ModularUI, or it moved the field. Either way the screen still renders.
        }
    }

    /** Package-visible so `ShotHarnessTest` can pin the fallback without a running client. */
    static int intProperty(String name, int fallback) {
        String raw = System.getProperty(name);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log("ignoring unparseable -D" + name + "=" + raw + "; using " + fallback);
            return fallback;
        }
    }

    /**
     * Stop the JVM with `code`.
     *
     * `FMLCommonHandler.exitJava` rather than `Minecraft.shutdown()`, because shutdown()
     * only asks the game loop to stop and then runs the full teardown, which can block on
     * the sound engine -- and a harness that sometimes hangs AFTER writing the PNG is a
     * harness nobody trusts the exit code of. The PNG is already flushed to disk by the time
     * this is called, so there is nothing left to lose by leaving early.
     */
    /**
     * Flush, tear down any world, and quit.
     *
     * THE WORLD MUST GO FIRST OR THE PROCESS HANGS, and it hangs after writing a perfectly
     * good PNG. Measured: the first `-Dmcrecipedump.shotWorld` run captured its screenshot,
     * logged "Stopping server", and was still alive thirteen minutes later holding the gradle
     * file-hash lock -- which then failed the NEXT build with a lock timeout that names
     * neither the world nor the harness. `exitJava` runs shutdown hooks, and 1.12.2's joins
     * the integrated server thread, which is itself waiting on the client thread that called
     * exit. Classic mutual wait, and invisible unless you notice the container never stopped.
     *
     * `loadWorld(null)` on the client thread disconnects and stops the integrated server
     * before anything joins it. Harmless with no world loaded, which is why it is not
     * conditional: an exit path that behaves differently in the case nobody exercises is an
     * exit path with an untested branch.
     */
    private static void exit(int code) {
        System.out.flush();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world != null) {
                mc.world.sendQuittingDisconnectingPacket();
            }
            mc.loadWorld(null);
        } catch (Throwable t) {
            // Never let teardown turn a successful capture into a failure. The exit code has
            // already been decided by then, and a stack trace here is more use than a hang.
            log("world teardown before exit failed, exiting anyway: " + t);
        }
        System.out.flush();
        FMLCommonHandler.instance().exitJava(code, false);
    }

    /**
     * Straight to stdout, not through a logger.
     *
     * These lines are the harness's report and `harness/shot.sh` greps them out of the
     * client's output, so they have to appear on stdout whatever log4j is configured to do
     * with mod logging in the pack this eventually runs against. A println cannot be routed
     * elsewhere by a config file; a logger can.
     *
     * PACKAGE-VISIBLE so a screen can report its own numbers under the same prefix. A screen
     * that printed through its own logger would be the one line missing from the harness's
     * output in the pack, which is the run where it matters most.
     */
    static void log(String message) {
        System.out.println("[mcrecipedump-shot] " + message);
    }
}
