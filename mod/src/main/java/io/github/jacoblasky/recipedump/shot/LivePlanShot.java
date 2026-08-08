package io.github.jacoblasky.recipedump.shot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import io.github.jacoblasky.recipedump.client.PlannerEntry;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PinStore;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.plan.Solver;

/**
 * The planner over a plan this client SOLVED, for `-Dmcrecipedump.shot=planner-live`.
 *
 * WHY THIS EXISTS BESIDE {@link PlannerShot}, WHICH ALREADY PHOTOGRAPHS THE PANEL. That one
 * reads a frozen fixture off disk, which is the right subject for a layout question: the
 * pictures are reproducible, they cost no graph, and `plan-fluid-chain` is a harder tree than
 * anything a shot would solve. What it cannot show is that the panel is reachable from real
 * data -- it renders a file, and a file renders identically whether or not a graph can be
 * found, loaded, priced and solved on a client.
 *
 * So this is the OTHER half, and the two are complementary rather than redundant: fixtures
 * prove the drawing, this proves the plumbing. Everything between `graph.json` on disk and a
 * `PlanView` runs here and nowhere else -- `GraphSource` finding the file, `GraphService`
 * reading 116 MB of it off the main thread, `ScenarioInputs` resolving the live document,
 * `Cost` pricing the pack, `Solver` running, and `PlanJson` handing the result to the panel.
 *
 * WITHOUT A GRAPH IT SHOOTS THE EMPTY PLANNER AND SUCCEEDS. That is every CI run and anyone
 * who has not built an oracle, and the resulting picture is worth having: "no graph.json,
 * looked in ..." is what a new player sees, so being able to look at it is the point rather
 * than a consolation.
 *
 * IT BLOCKS THE CLIENT TICK WHILE IT WAITS, which would be indefensible in a real client and
 * is correct here. The harness is one-shot and headless: there is nobody to freeze, the only
 * thing the tick still has to do is let `currentScreen` change, and the alternative -- opening
 * on an unfinished plan and hoping the settle frames covered it -- is a screenshot that races
 * a background thread. Both waits are BOUNDED and the screen opens whatever happens, so a
 * graph that never loads produces a picture of that rather than a hang.
 */
final class LivePlanShot {

    /** Overrides what gets planned. A key, not a name -- 5,095 display names are shared. */
    private static final String PROP_TARGET = "mcrecipedump.planTarget";
    private static final String PROP_QTY = "mcrecipedump.planQty";

    /**
     * Where to write the solved plan as JSON, if anywhere.
     *
     * THE PNG IS THE DELIVERABLE AND THIS IS THE EVIDENCE. A picture shows that a tree was
     * drawn; it cannot show the tree is the RIGHT one. Writing the plan out lets the same
     * target be planned by the Python oracle with the same scenario and the two compared byte
     * for byte, which is what closes the loop from "the port agrees offline" to "the mod
     * agrees in game". `docs/shots/README.md` has the invocation.
     */
    private static final String PROP_JSON_OUT = "mcrecipedump.planJsonOut";

    /**
     * Borax. Its route is the canary the cost model is tuned against -- `tools/cost-probe.py`
     * treats it resolving to `nuclearcraft_crystallizer` as the signal that the low-end
     * calibration still holds -- so a picture of this plan is one a reader can judge rather
     * than merely look at. It is also 15 nodes, which fits a panel.
     */
    private static final String DEFAULT_TARGET = "nuclearcraft:compound:7";

    private static final long GRAPH_WAIT_MILLIS = 120_000L;
    private static final long PLAN_WAIT_MILLIS = 120_000L;

    private LivePlanShot() {
    }

    /** `planner-live`, or `planner-live:<item key>`. */
    static void open(String arg) {
        String target = arg != null && !arg.trim().isEmpty()
                ? arg.trim() : System.getProperty(PROP_TARGET, DEFAULT_TARGET);
        PlanBook book = new PlanBook();
        book.setTodo(target, qty());

        if (awaitGraph()) {
            log("graph: " + GraphService.get().describe());
            logPins();
            // THE VERDICT IS READ AND SAID OUT LOUD. `awaitPlan` returned void until #254's
            // review, so a solve that ran past its two minutes produced a screenshot of a
            // half-drawn planner and a zero exit code with nothing in the log to distinguish
            // it from a finished one. `describe()` below reports the SERVICE's state, which
            // still says PLANNING -- true, and easy to read past in a wall of harness output.
            if (PlannerService.get().plan(target, qty(), Solver.DEFAULT_MAX_NODES)
                    && !awaitPlan()) {
                log("plan: NOT FINISHED -- the picture below is of an unfinished plan");
            }
            log("plan: " + PlannerService.get().describe());
            writeJson();
        } else {
            log("graph: " + GraphService.get().describe());
        }
        // THROUGH THE SAME CHOOSER THE ITEM USES, so the picture is of what a player gets
        // rather than of what the harness decided to draw. Without a graph that is the
        // failure panel naming the path it looked in, which is the second shot in
        // docs/shots/ and is worth photographing for exactly that reason.
        PlannerEntry.open(book);
    }

    /**
     * What the pin file contributed, in one line.
     *
     * EVIDENCE BESIDE THE PICTURE, for the reason {@link #PROP_JSON_OUT} exists. A plan that
     * ignores a pin and a plan with no pins to ignore produce the SAME tree, so a screenshot
     * cannot tell them apart -- which is exactly how a broken pin path gets shipped behind a
     * picture that looks right. This says which of the two happened before the picture is
     * taken.
     */
    private static void logPins() {
        PinStore store = PinStore.get();
        log("pins: " + store.pins().size() + " from "
                + (store.file() == null ? "nowhere" : store.file().getPath())
                + (store.problem().isEmpty() ? "" : " -- " + store.problem()));
    }

    private static long qty() {
        try {
            return Math.max(1L, Long.parseLong(System.getProperty(PROP_QTY, "1")));
        } catch (NumberFormatException bad) {
            return 1L;
        }
    }

    /**
     * True once the graph is READY. False for every other outcome, including a timeout.
     *
     * PACKAGE-VISIBLE because `PlannerShot`'s recipe picker needs the same wait for the same
     * reason, and a second copy of a bounded-poll loop is a second timeout to keep in step.
     */
    static boolean awaitGraph() {
        final GraphService graphs = GraphService.get();
        ShotWaits.until("the graph", GRAPH_WAIT_MILLIS, new ShotWaits.Busy() {
            @Override
            public boolean busy() {
                return graphs.state() == GraphService.State.IDLE
                        || graphs.state() == GraphService.State.LOADING;
            }
        });
        // THE RETURN IS THE STATE AND NOT THE WAIT'S VERDICT, deliberately. A timeout and a
        // load that finished as MISSING are both "no graph", and this method's callers only
        // ever ask that question; `ShotWaits.until` has already logged which of the two it was.
        return graphs.state() == GraphService.State.READY;
    }

    /**
     * @return false when the plan never finished, which the caller MUST NOT ignore.
     *
     * IT USED TO RETURN VOID, and that is the defect the shared wait was extracted to kill: a
     * solve that ran past its two minutes was photographed exactly as though it had finished,
     * and the run exited zero. The log line said so and nothing else did.
     */
    private static boolean awaitPlan() {
        final PlannerService planner = PlannerService.get();
        return ShotWaits.until("the plan", PLAN_WAIT_MILLIS, new ShotWaits.Busy() {
            @Override
            public boolean busy() {
                return planner.state() == PlannerService.State.PLANNING;
            }
        });
    }

    private static void writeJson() {
        String path = System.getProperty(PROP_JSON_OUT);
        String json = PlannerService.get().resultJson();
        if (path == null || path.trim().isEmpty() || json == null) {
            return;
        }
        try {
            File file = new File(path.trim());
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Writer out = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try {
                out.write(json);
            } finally {
                out.close();
            }
            log("wrote the plan to " + file.getPath());
        } catch (IOException e) {
            // Logged and swallowed: the PNG is the deliverable and the JSON is evidence
            // beside it, so failing to write the evidence must not lose the picture.
            log("could not write the plan JSON: " + e);
        }
    }

    private static void log(String message) {
        System.out.println("[mcrecipedump/shot] " + message);
    }
}
