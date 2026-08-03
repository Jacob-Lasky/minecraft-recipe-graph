package io.github.jacoblasky.recipedump.common;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the planner's graph file comes from, and the reasoning for that being one answer.
 *
 * WHY A PREBUILT FILE AT ALL, RATHER THAN BUILDING FROM JEI AT STARTUP. #126 showed the graph
 * fits comfortably in a client -- 45.3 MB retained, and 5.47 s to read the 116 MB oracle,
 * re-measured here on Java 8 under a compacting collector -- which raised the far more
 * attractive option of building it live and letting the dump file stop existing (#12). That
 * option is REJECTED for now, on the dump's own measurement rather than on taste:
 * `DumpCommand` walks JEI's 334,205 entries at `BUDGET_NANOS`, a deliberate 15 ms per client
 * tick, precisely because the walk cannot be done in a frame. Paying it at every world join
 * is not a startup cost, it is minutes of a progress bar; and the walk is only the FIRST half
 * of `index.build`, which also infers the oredict, drops non-recipes, expands variant tables,
 * parses 259 multiblocks and reads planetDefs. None of that is ported.
 *
 * The version worth building later is the CACHED one: walk once, write a compact binary,
 * reuse it until the pack changes. That really would close #12. It is a large piece of work
 * and it is not on the path to a working planner, so it stays unbuilt and the reason stays
 * written down. What is NOT a reason to avoid it is heap or load time; #126 settled those.
 *
 * WHERE THE FILE LIVES. `<config>/mcrecipedump/graph.json`, with a
 * `-Dmcrecipedump.graph=<path>` override.
 *
 *   * The CONFIG directory rather than the instance root or the dump directory. It is the
 *     one place a 1.12.2 player already knows to put a file, it survives a pack update, and
 *     it is per-instance, so two packs cannot end up sharing one graph. The dump directory
 *     was the obvious alternative and is wrong: `mc-recipe-dump/` is written BY this mod, and
 *     putting a hand-supplied input among generated outputs invites a redump to look like it
 *     should have replaced it. Inputs and outputs stay apart.
 *   * A SYSTEM PROPERTY for the override, not a Forge config entry. `-Dmcrecipedump.shot=` is
 *     already how the screenshot harness drives this mod, so this follows a convention the
 *     repository has rather than introducing config plumbing for a single string. A config
 *     entry is the right answer the day a player needs to change it without editing a launch
 *     profile; nobody does yet.
 *
 * NOTHING HERE TOUCHES `net.minecraft.client`, AND DO NOT ADD AN IMPORT THAT DOES. The graph
 * is pack data rather than a client resource, so this is common from the start rather than
 * moved later, and a dedicated server has loaded it since #19 Phase 2. Phase 5's live AE2 read
 * landed server-side (#150) and turned out not to need the graph, so every graph consumer
 * today is client-side -- that is NOT a reason to move this file under `client.`.
 * `CommonSideSafetyTest` enforces the rule for the mod class; this file keeps to it too.
 */
public final class GraphSource {

    /** Overrides the search entirely. Absolute or relative to the working directory. */
    public static final String PROPERTY = "mcrecipedump.graph";

    /** Subdirectory of the config directory, so the mod's files sit together. */
    public static final String CONFIG_SUBDIR = "mcrecipedump";

    public static final String FILE_NAME = "graph.json";

    private GraphSource() {
    }

    /**
     * The graph file to load, or null when there is none.
     *
     * `configDir` is Forge's `FMLPreInitializationEvent.getModConfigurationDirectory()`, and
     * may be null in a harness that never ran preInit -- which is why this takes it as an
     * argument instead of reaching for a global. A null config directory simply drops that
     * candidate rather than throwing, so the property alone is enough to run headless.
     */
    public static File locate(File configDir) {
        for (File candidate : candidates(configDir)) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Every path {@link #locate} would try, in order, whether or not it exists.
     *
     * SEPARATE FROM `locate` SO THE FAILURE CAN NAME THEM. "No graph found" is useless to a
     * player who has one on disk and put it somewhere else; "looked in A, then B" is
     * actionable, and it is the message `GraphService` shows. Reconstructing the list at the
     * point of failure would be a second copy of the search order, free to disagree with the
     * one that actually ran.
     */
    public static List<File> candidates(File configDir) {
        List<File> out = new ArrayList<File>(2);
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            // The override is taken ALONE. A property that names a missing file is a typo the
            // player wants to hear about, and quietly falling back to the config directory
            // would load a different graph than the one they asked for -- which is the same
            // class of bug as `data/graph.json` silently standing in for an oracle.
            out.add(new File(override.trim()));
            return out;
        }
        if (configDir != null) {
            out.add(new File(new File(configDir, CONFIG_SUBDIR), FILE_NAME));
        }
        return out;
    }

    /** "looked in: a, b", for the message a failed load shows. */
    public static String describeSearch(File configDir) {
        List<File> candidates = candidates(configDir);
        if (candidates.isEmpty()) {
            return "nowhere to look: no config directory and no -D" + PROPERTY;
        }
        StringBuilder sb = new StringBuilder("looked in: ");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(candidates.get(i).getPath());
        }
        return sb.toString();
    }
}
