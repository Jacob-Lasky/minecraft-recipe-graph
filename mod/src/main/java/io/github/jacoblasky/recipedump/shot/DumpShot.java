package io.github.jacoblasky.recipedump.shot;

import java.io.File;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.DumpPlugin;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;

/**
 * `dump`: can the headless harness run `/recipedump` with nobody at the keyboard?
 *
 * THE LAST HAND-OPERATED STEP IN THIS PROJECT. #123, #90, #118, #55, #50 and #36 have all
 * queued behind "the user launches the pack and types a command", and #146 calls that the
 * project's one permanent capability asymmetry. #124 removed the equivalent constraint for
 * GUI work. This asks whether the same container removes it for dumps.
 *
 * THE OBJECTION WAS NEVER MEASURED, WHICH IS WHY THIS EXISTS. `ShotHarness`'s own header says
 * the harness is property-driven "because a COMMAND needs someone to type it". That is true of
 * CHAT and was generalised to invoking the command OBJECT, and nobody separated the two. It is
 * the third harness limitation to fall this way after "it has no input" and "it cannot render a
 * world", both of which also turned out to be inferred rather than tested.
 *
 * THROUGH THE REAL HANDLER, NOT `DumpCommand.execute` DIRECTLY, and that is the whole point.
 * `ClientCommandHandler.instance.executeCommand(mc.player, "/recipedump")` is the identical
 * path a keyboard drives: same handler, same permission check, same `CommandEvent` on the bus,
 * same sender type, same chat rendering. Calling `execute` directly would prove that a method
 * runs, which nobody doubted. A pass here is evidence about the path players use.
 *
 * WITH A WORLD AND A REAL PLAYER. `mc.player` is the sender, so `-Dmcrecipedump.shotWorld` is
 * required. The intent is that the dump's progress chat renders into the framebuffer, making
 * the screenshot a picture of the reported counts rather than of a panel -- but treat that as
 * the reason for opening no GUI, NOT as an established fact: chat is drawn by `GuiIngame` and
 * fades on its own timer, so whether the counts are still on screen at capture is a question
 * the first run answers and this comment does not.
 *
 * WHAT A FAILURE HERE MEANS. The dump path is understood well enough that a failure is
 * informative rather than merely disappointing: `DumpCommand.execute` never dereferences its
 * `server` argument, and grepping `mc.world`, `mc.player` and `EntityPlayer` across
 * `DumpCommand`, `IconAtlas`, `ProjectEBridge` and `ModularMachineryBridge` finds nothing, so
 * all three dump phases are world-free. A failure is therefore bug-shaped, not
 * capability-shaped, and WHERE it fails is the finding.
 *
 * IT ANSWERED YES ON 2026-08-03, first run: 1,663 recipes from 9 categories, `threw: 0`, 975
 * icons with 0 blank and 0 thrown, schema 5, in about three seconds on the five-jar set. The
 * only thing that failed was this screen's own bookkeeping, which is recorded at the rising-edge
 * sample in {@link #open} because it is the more useful half of the lesson.
 *
 * THE CAPTURE SHOWS A PAUSE MENU, AND A LATER PROBE MAY NOT SURVIVE THAT. OBSERVED, not
 * explained: this screen leaves `currentScreen` null -- the harness logged "opened no screen" --
 * and yet `dump-expA.png` came back with vanilla's Game Menu drawn over the chat. HYPOTHESIS,
 * untested: a windowed client under Xvfb has no focus, and 1.12.2 opens the in-game menu when
 * the display goes inactive, which it only does when no screen is already up. That would make
 * the ten entries that open a GUI immune and the `expectNoScreen` ones exposed.
 *
 * IT DID NOT MATTER HERE because the dump advances on the CLIENT tick. It would matter to a
 * no-screen probe that needs SERVER ticks, because the single-player pause halts the integrated
 * server -- `Ae2ProbeShot` waits twenty of those and would stall. Anyone writing that probe
 * should test this rather than trust the hypothesis above; the chat in the PNG is legible
 * either way, which is what the artifact claim needed.
 */
final class DumpShot {

    /**
     * Where `DumpCommand` writes, which this screen reports on but does NOT choose.
     *
     * HARDCODED IN `DumpCommand.execute` as `gameDir/mc-recipe-dump`, and redirecting it would
     * be a change to the production command rather than to the harness. So this screen reads
     * the same place the command writes and says what it found; it does not pretend to point
     * the dump anywhere.
     */
    private static final String OUTPUT_DIR = "mc-recipe-dump";

    private DumpShot() {
    }

    static void open(String arg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            throw new IllegalStateException("dump needs a world and a player: run it with "
                    + "-D" + ShotHarness.PROP_WORLD + "=<name>");
        }
        ShotScreens.expectReport("dump verdict");
        // NO GUI IS OPENED HERE, UNLIKE EVERY OTHER ENTRY, AND THE HARNESS HAS TO BE TOLD.
        // `DumpCommand.reply` sends a chat message, and chat in 1.12.2 is the in-game HUD
        // drawn by `GuiIngame` -- it is NOT a `GuiScreen` and does not touch
        // `Minecraft.currentScreen`. So in a world with no GUI open this leaves `currentScreen`
        // null before and after, and without this declaration `ShotHarness` would read that as
        // "the opener declined to open" and exit EXIT_NO_SCREEN before the dump did anything.
        // Opening a panel to satisfy that check would cover the chat, which is the artifact.
        ShotScreens.expectNoScreen();

        // BEFORE THE COMMAND RUNS, NOT AFTER. This is the cutoff that separates files this run
        // wrote from files an earlier one left in the persistent `mod/run`, so any instant
        // after the dump begins is too late and would classify its own early output as stale.
        //
        // FLOORED TO THE SECOND, and that is not fussiness: `File.lastModified` truncates
        // downward on these filesystems, so a file written a fraction of a second after an
        // unfloored cutoff reports a stamp just BEFORE it and reads as stale -- which would
        // fail a good dump for the one reason hardest to reproduce.
        final long startedAtMillis = (System.currentTimeMillis() / 1000L) * 1000L;

        // THE MOST LIKELY REFUSAL, NAMED ON STDOUT BEFORE IT CAN HAPPEN. Every refusal inside
        // `execute` goes to `reply`, which is chat, so the single most probable failure -- the
        // JEI runtime not being up yet -- would otherwise exist only as pixels in the PNG, and
        // a harness whose commonest failure is unreadable from the log is a harness that costs
        // a second run to diagnose. #146's spike photographed the JEI runtime live at the main
        // menu, so this is expected to be present; logging it either way is what makes a
        // failure diagnosable from the log alone.
        ShotHarness.log("dump: JEI runtime " + (DumpPlugin.runtime == null
                ? "is NULL -- /recipedump will refuse and say so only in chat"
                : "is present"));

        // THE RETURN VALUE IS THE FIRST DIAGNOSTIC AND IS NOT DISCARDED. `ClientCommandHandler`
        // documents it: 1 executed, 0 no such command or a listener cancelled the CommandEvent,
        // -1 no permission or bad usage. If `/recipedump` is not registered client-side, or a
        // mod cancels the event, this is where that shows -- and it is a different finding from
        // "the dump started and failed", which is why it fails here rather than waiting.
        int result = ClientCommandHandler.instance.executeCommand(mc.player, "/recipedump");
        ShotHarness.log("dump: executeCommand returned " + result
                + " (1 executed, 0 unknown command or event cancelled, -1 no permission"
                + " or bad usage)");
        if (result != 1) {
            ShotScreens.reportFail("the command handler returned " + result
                    + " rather than 1, so /recipedump never ran: "
                    + (result == 0
                       ? "no client command by that name, or a listener cancelled the event"
                       : "permission was refused or the usage was rejected"));
            return;
        }

        // THE RISING EDGE IS SAMPLED HERE, SYNCHRONOUSLY, AND NOT BY POLLING. This is the one
        // instant at which "did a dump begin" is answerable: `execute` assigns `active` and
        // registers its Runner before returning, so the flag is up the moment `executeCommand`
        // hands back -- and the five refusal paths return without ever setting it. One sample,
        // no tick budget, no guess.
        //
        // MEASURED THE HARD WAY. The first version watched for the edge from `Hold.busy()`,
        // which the harness only polls AFTER the settle window. On the five-jar set the whole
        // dump -- 1,663 recipes and 975 icons -- finishes in about three seconds, comfortably
        // inside twenty llvmpipe frames, so the flag had gone up and back down before anything
        // looked. The probe then reported "the dump never started" over a complete and correct
        // dump, and exited 6. A poll cannot observe an edge that has already passed, and the
        // faster the thing under test, the more certainly it is missed.
        boolean started = DumpCommand.running();
        ShotHarness.log("dump: DumpCommand.running() is " + started
                + " immediately after the handler returned");
        if (!started) {
            ShotScreens.reportFail("the handler ran the command but no dump began, so one of "
                    + "execute's refusal paths was taken. Those reply to chat, so read the PNG");
            return;
        }

        // HOLD THE CAPTURE while the dump proceeds. See `ShotScreens.Hold`: sizing a frame
        // count to cover a dump would be a guess about how busy Tower is, and an under-wait
        // would capture and exit mid-dump. Registered only once a dump is known to be in
        // flight, so neither refusal above sits holding the capture to re-report itself.
        ShotScreens.holdCapture(new Watcher(mc, startedAtMillis));
    }

    /**
     * Waits for the in-flight dump to finish, then reports on what it wrote.
     *
     * WAITS FOR THE FALL ONLY. Whether a dump BEGAN is settled synchronously in {@link #open}
     * before this is ever constructed, which is the only way to observe an edge that can pass
     * inside the settle window. `DumpCommand.running()` reads false both before a dump starts
     * and after it ends, so on its own it is not a completion signal -- but once a dump is
     * known to be in flight, its fall is exactly one.
     */
    private static final class Watcher implements ShotScreens.Hold {

        private final Minecraft mc;
        /** See {@link #finish}. Taken before the command ran, and floored to the second. */
        private final long startedAtMillis;
        private boolean done;

        Watcher(Minecraft mc, long startedAtMillis) {
            this.mc = mc;
            this.startedAtMillis = startedAtMillis;
        }

        @Override
        public boolean busy() {
            if (done) {
                return false;
            }
            if (DumpCommand.running()) {
                return true;
            }
            done = true;
            finish();
            return false;
        }

        /**
         * The dump has stopped running. Say what THIS RUN left behind.
         *
         * ONLY FILES THIS RUN WROTE COUNT, and that is the same lesson `shot.sh` already
         * encodes when it deletes the PNG before starting. `mod/run` persists between runs, so
         * once any dump has succeeded here the directory is permanently non-empty -- and a
         * later dump that starts, throws mid-walk and writes nothing would find the old files
         * and be reported as a pass. A stale artifact read as a fresh one is the failure this
         * whole harness exists to make impossible.
         *
         * By mtime rather than by deleting the directory first, because these dumps are
         * expensive and destroying the previous one to prove the next is a bad trade.
         */
        private void finish() {
            File dir = new File(mc.gameDir, OUTPUT_DIR);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                ShotScreens.reportFail("the dump ran and wrote nothing to " + dir);
                return;
            }
            StringBuilder wrote = new StringBuilder();
            long bytes = 0L;
            int fresh = 0;
            int stale = 0;
            for (File f : files) {
                if (f.lastModified() < startedAtMillis) {
                    stale++;
                    continue;
                }
                fresh++;
                if (wrote.length() > 0) {
                    wrote.append(", ");
                }
                wrote.append(f.getName()).append(' ').append(f.length()).append('B');
                bytes += f.length();
            }
            ShotHarness.log("dump: " + dir + " holds " + fresh + " file(s) from this run"
                    + (stale > 0 ? " and " + stale + " left by an earlier one" : "")
                    + (fresh > 0 ? ": " + wrote : ""));
            if (fresh == 0) {
                ShotScreens.reportFail("the dump ran and wrote nothing new to " + dir
                        + "; the " + stale + " file(s) there are older than this run");
                return;
            }
            // A PASS MEANS "THE HARNESS DROVE THE COMMAND AND IT PRODUCED OUTPUT", and that is
            // deliberately all it means. Nothing here inspects the CONTENT, so this cannot
            // distinguish a complete dump from a truncated one -- see #146 on diffing
            // `names.json` against a known-good in-world dump before trusting any of it.
            if (bytes == 0L) {
                ShotScreens.reportFail("the dump wrote " + fresh
                        + " file(s) and every one of them is empty");
                return;
            }
            ShotScreens.reportPass();
        }
    }
}
