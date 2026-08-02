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
 * so every GUI iteration in #19 otherwise costs a manual launch of a 410-mod pack. Driven by
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
    /** `true` keeps ModularUI's widget-outline overlay in the picture. */
    public static final String PROP_DEBUG_OVERLAY = "mcrecipedump.shotDebugOverlay";

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
        private final int settleFrames;
        private final long deadlineNanos;

        /** null until the screen has been asked for; then counts frames down to the capture. */
        private Integer framesLeft;
        private long lastReportNanos;

        Runner(String spec) {
            this.spec = spec;
            this.settleFrames = intProperty(PROP_SETTLE, DEFAULT_SETTLE_FRAMES);
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
            if (!(mc.currentScreen instanceof GuiMainMenu)) {
                waitOrTimeOut(mc.currentScreen);
                return;
            }
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
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.END || framesLeft == null) {
                return;
            }
            if (framesLeft > 0) {
                framesLeft = framesLeft - 1;
                return;
            }
            capture();
        }

        /** Log what the client is sitting on every 15s, and fail once past the deadline. */
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
    private static void exit(int code) {
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
     */
    private static void log(String message) {
        System.out.println("[mcrecipedump-shot] " + message);
    }
}
