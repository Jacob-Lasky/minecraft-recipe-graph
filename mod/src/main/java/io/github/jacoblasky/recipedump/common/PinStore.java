package io.github.jacoblasky.recipedump.common;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.plan.Pins;

/**
 * The player's pinned recipe choices: where they live, and how a pin made in game gets into
 * the next plan.
 *
 * WHY A FILE AND NOT THE PLAN BOOK. #140's `PlanBook` capability is per-player, server-owned
 * and NBT-serialised, and favourites and the TODO list belong there because they are about
 * this player in this world. A pin is not: it is a statement about the PACK -- "in this
 * modpack, make plates by rolling" -- and it is the same statement whichever save you load.
 * `recipegraph/pins.py` already stores it that way, and the two sides are both live until
 * #19 phase 6 retires the Python UI, so a pin made in game and a pin made on the web page
 * have to be the same fact in the same format. Putting it in a capability would fork that.
 *
 * NAMED `recipes.json`, MATCHING `defaults.DEFAULT_PINS`, and that is not a coincidence to
 * tidy away: a player who plans on the desktop and in game copies one file between the two,
 * and a rename here would mean the two halves of a live feature quietly disagreed about
 * which file is current. The reason the Python name is `recipes.json` rather than
 * `pins.json` is recorded in `defaults.py` -- named for what it holds rather than for the
 * feature, matching `machines.json` and `sources.json` beside it.
 *
 * IN `common` BECAUSE PHASE 5 PLANS SERVER-SIDE. Nothing here touches `net.minecraft.client`
 * and `CommonSideSafetyTest` will fail the build if that changes.
 *
 * <h2>A pin file that cannot be read is reported, not swallowed</h2>
 *
 * {@link Pins#load} returns an empty map for a corrupt file, deliberately -- a broken file
 * must not break planning. But an empty map is also what "you have no pins" looks like, and
 * once pins actually steer the solver those two produce visibly different plans from
 * identical-looking UI. So this reads through {@link Pins#read}, keeps the sentence, and
 * hands it to {@link ScenarioSource#PINS}, which is the mechanism the planner already uses
 * to say what its answer was built without. A player whose file is corrupt gets a red caveat
 * naming it instead of a plan that silently ignores every choice they have ever made.
 */
public final class PinStore {

    /** Overrides the location entirely. Absolute or relative to the working directory. */
    public static final String PROPERTY = "mcrecipedump.pins";

    /** Deliberately `defaults.DEFAULT_PINS`'s basename. See the class note. */
    public static final String FILE_NAME = "recipes.json";

    private static final PinStore INSTANCE = new PinStore();

    /**
     * VOLATILE, NOT GUARDED BY THE MONITOR, and the reason is a frame rather than a race.
     * The reader installed on {@link ScenarioSource#PINS} is called while the planner panel
     * is being built, on the render thread; `commit` holds the monitor across a disk write.
     * A synchronized accessor would put the render thread behind that write.
     */
    private volatile File file;
    private volatile String problem = "";

    /** Guarded by the monitor: replaced wholesale, never mutated in place. */
    private Map<String, Pins.Pin> pins = new LinkedHashMap<String, Pins.Pin>();

    private PinStore() {
    }

    public static PinStore get() {
        return INSTANCE;
    }

    /**
     * Where the pins are, or null when there is nowhere to put them.
     *
     * ONE PATH, NOT A SEARCH, which is the difference from {@link GraphSource}. The graph is
     * an input the player supplies and may reasonably have put in one of several places, so
     * that class looks in each and names them all when it fails. This file is one this mod
     * WRITES: a search would mean reading from one path and writing to another the first
     * time the preferred one did not exist yet, which is how a player's choices end up in a
     * file nothing loads.
     *
     * `configDir` is Forge's `getModConfigurationDirectory()`, null in a harness that never
     * ran preInit -- hence the property, which is also what lets a test point at a temporary
     * directory without a Forge lifecycle.
     */
    public static File fileIn(File configDir) {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim());
        }
        if (configDir == null) {
            return null;
        }
        return new File(new File(configDir, GraphSource.CONFIG_SUBDIR), FILE_NAME);
    }

    /**
     * Read the pin file and start answering for {@link ScenarioSource#PINS}.
     *
     * Called from `CommonProxy.preInit`, beside the graph load. Cheap -- the file is a
     * handful of kilobytes at worst -- so it happens inline rather than on a thread.
     *
     * ONCE PER LAUNCH, AND THIS SIDE IS THEN THE AUTHORITY. Nothing re-reads the file, so a
     * pin made on the web page while the game is running does not appear until a restart --
     * and the next in-game pin will overwrite that edit, because {@link #commit} writes the
     * whole map. That is the right default while the two front ends usually live on
     * different machines (the README says to copy the file); a watch or a reload control is
     * the answer the day somebody runs both at once, and it needs UI rather than a stat().
     */
    public synchronized void load(File configDir) {
        file = fileIn(configDir);
        Pins.Loaded loaded = Pins.read(file);
        pins = new LinkedHashMap<String, Pins.Pin>(loaded.pins);
        problem = loaded.problem;
        if (problem.isEmpty() && file == null) {
            // NOT AN ERROR YET AND STILL WORTH SAYING. With no config directory a pin can be
            // made and will vanish at the next launch, and a control that silently forgets is
            // worse than one that is absent. The planner shows this on its caveat line.
            problem = "no config directory, so recipe choices cannot be saved";
        }
        ScenarioSource.PINS.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                String why = problem();
                return why.isEmpty() ? ScenarioSource.Status.available()
                                     : ScenarioSource.Status.unavailable(why);
            }
        });
    }

    /** The pins as {@link Pins#resolve} wants them. A snapshot; edits do not show through. */
    public synchronized Map<String, Pins.Pin> pins() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Pins.Pin>(pins));
    }

    /** What went wrong with the pin file, or "" when nothing did. Safe to show a player. */
    public String problem() {
        return problem;
    }

    /** Where the pins are read from and written to, or null. */
    public File file() {
        return file;
    }

    /**
     * Pin `key` to `pin`, and write the file. False when the write failed.
     *
     * WRITTEN IMMEDIATELY RATHER THAN AT SHUTDOWN. A pin is a decision a player made once and
     * expects to still hold in a month; deferring the write means a crash -- which is a thing
     * that happens to a 1.12.2 pack -- loses it, and the player has no way to know it did.
     * The file is small and this happens on a click, not on a tick.
     *
     * The in-memory map is updated FIRST and kept even when the write fails, so the pin
     * applies to this session's plans while {@link #problem} explains that it will not
     * survive a restart. Discarding it would be a second failure on top of the first.
     */
    public synchronized boolean pin(String key, Pins.Pin pin) {
        if (key == null || key.isEmpty() || pin == null) {
            return false;
        }
        Map<String, Pins.Pin> next = new LinkedHashMap<String, Pins.Pin>(pins);
        next.put(key, pin);
        return commit(next);
    }

    /** Remove the pin on `key`, and write the file. False when the write failed. */
    public synchronized boolean unpin(String key) {
        if (key == null || !pins.containsKey(key)) {
            return true;
        }
        Map<String, Pins.Pin> next = new LinkedHashMap<String, Pins.Pin>(pins);
        next.remove(key);
        return commit(next);
    }

    private boolean commit(Map<String, Pins.Pin> next) {
        pins = next;
        if (file == null) {
            // `problem` already says the choices cannot be saved; see `load`. Returning false
            // lets the caller tell the difference between "saved" and "held for now".
            return false;
        }
        try {
            Pins.save(file, next);
            // A SUCCESSFUL WRITE CLEARS A LOAD PROBLEM, and it is right that it does: the
            // file has just been REPLACED with what this store holds, so "3 choices in it
            // name no fingerprint" describes a file that no longer exists. The entries it
            // named are gone with it -- which is what a caveat saying they were being ignored
            // already told the player, and the alternative is a warning that outlives its
            // cause and teaches people to ignore the line.
            problem = "";
            return true;
        } catch (IOException | RuntimeException failed) {
            problem = "cannot write " + file.getPath() + ": "
                    + failed.getClass().getSimpleName()
                    + (failed.getMessage() == null ? "" : ": " + failed.getMessage());
            return false;
        }
    }

    /**
     * The `pins` field of a scenario document, in the shape `ScenarioInputs.resolvePins`
     * reads and a plan fixture writes.
     *
     * BUILT FROM THE SAME MAP THE FILE HOLDS AND BY THE SAME WRITER, so "what is pinned",
     * "what is on disk" and "what the solver was told" cannot drift -- `Pins.toJson` and
     * `Pins.fromJson` are the only two places the field names are spelled. `ScenarioInputs`
     * is the resolver the golden gate exercises, so a pin reaching the solver by any other
     * door would be a route the fixtures never test.
     */
    public synchronized JsonObject document() {
        return Pins.toJson(pins);
    }

    /** Forget everything, including the installed reader. For tests and a world unload. */
    public synchronized void reset() {
        file = null;
        pins = new LinkedHashMap<String, Pins.Pin>();
        problem = "";
        ScenarioSource.PINS.readBy(null);
    }
}
