package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.AbstractScrollWidget;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.common.GraphDocuments;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Solver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The two decisions the planner window makes on every client tick, taken out of the window.
 *
 * NEITHER CAN BE ASSERTED ON THE WINDOW ITSELF. It is a `ModularScreen` that reopens itself
 * through `ClientGUI`, and none of that exists in this JVM -- so
 * `PlannerScreen.clearSelectionIfPlanChanged` is a method rather than two lines inside
 * `onUpdate`, and `PlannerScreen.stamp` is package-visible rather than private. This is the
 * file that made both worth being so.
 *
 * <h2>#213: does a selection survive a re-solve?</h2>
 *
 * The assertion that earns its keep is {@link #aBookEditDoesNotClearTheSelection}. Clearing on a
 * NEW PLAN is the obvious half and reads correct either way; clearing on the COMBINED counter
 * that `PlannerScreen.stamp` produces looks equally correct in review and drops the highlight
 * whenever the player stars the node they are looking at.
 *
 * <h2>#201: what does the window notice?</h2>
 *
 * `stamp` IS the window's ability to notice anything at all -- it rebuilds when that number
 * changes and does nothing when it does not. It watched two counters that both describe a PLAN,
 * and no plan can exist during the 5.47 s graph read, so a planner opened in that gap watched
 * two numbers frozen by design and never rebuilt. {@link #theStampMovesWhenTheGraphLandsSoTheWindowRebuilds}
 * is the witness; {@link #aBookEditAndANewPlanBothStillMoveTheStamp} is the guard that stops
 * the graph term being added by replacing one of the two that were already right.
 *
 * <h2>#271: what does the window notice DURING the read?</h2>
 *
 * #201's graph term moves when the read starts and when it ends, and the read is 5.47 s of one
 * state in between -- so the panel built at 0% was the panel still on screen at 99%, and the
 * one thing on the window that could tell a player the load was progressing told them it was
 * not. {@link #theStampMovesThroughTheGraphReadRatherThanSittingOnZero} is the witness.
 *
 * THE OTHER THREE ARE NOT DECORATION. `PlannerWindow` carries an explicit prohibition against
 * redrawing on anything cheaper than a generation bump, and #271's term is cheaper than one, so
 * the exception has to be paid for rather than asserted:
 * {@link #aReadyGraphCostsExactlyTheRebuildsItCostBefore271} and
 * {@link #theLoadingPanelHasNoScrollPositionToThrowAway} are the two facts that buy it, and
 * {@link #theLoadTermCanNeverOutweighTheGraphCounter} is the arithmetic that keeps the summed
 * counter monotone now that one of its terms falls back to zero.
 */
public class PlannerScreenTest {

    private static final PlanNode NODE = new PlanNode.Builder()
            .key("minecraft:iron_ingot")
            .name("Iron Ingot")
            .label("Iron Ingot")
            .kind("item")
            .need(4L)
            .status("craft")
            .build();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String savedGraphProperty;

    @Before
    @After
    public void clearTheHolder() {
        // A STATIC HOLDER IS SHARED BY EVERY TEST IN THIS JVM. A leftover selection makes an
        // unrelated layout assertion draw a highlight it never set up.
        PlanSelection.clear();
    }

    /**
     * The two services are singletons and `GraphSource.PROPERTY` is process-wide, so the #201
     * cases below have to own all three the way `PlannerEntryTest` does. Restored rather than
     * merely cleared: another test class in this JVM may have set the property on purpose.
     */
    @Before
    public void isolateTheServices() {
        savedGraphProperty = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
        GraphService.get().reset();
        PlannerService.get().reset();
    }

    @After
    public void restoreTheServices() {
        if (savedGraphProperty == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, savedGraphProperty);
        }
        GraphService.get().reset();
        PlannerService.get().reset();
    }

    @Test
    public void aNewPlanClearsTheSelection() {
        PlanSelection.select(NODE);
        assertTrue(PlannerScreen.clearSelectionIfPlanChanged(7L, 8L));
        assertEquals("", PlanSelection.selectedKey());
        assertNull("and the NODE goes too, or an action reads a need from a dead plan",
                   PlanSelection.selectedNode());
    }

    /**
     * A book edit is not a new plan.
     *
     * "Favourite" and "Add to TODO" both bump `PlanBook.revision`, which `PlannerScreen.stamp`
     * folds into the counter the window watches -- so a version that compared the STAMP would
     * clear the highlight the moment the player used either entry on the menu of the node they
     * had just selected. The click would look like it had cancelled itself.
     */
    @Test
    public void aBookEditDoesNotClearTheSelection() {
        PlanSelection.select(NODE);
        assertFalse("the same plan generation must leave the selection alone",
                    PlannerScreen.clearSelectionIfPlanChanged(7L, 7L));
        assertTrue(PlanSelection.isSelected(NODE.key()));
        assertSame(NODE, PlanSelection.selectedNode());
    }

    @Test
    public void clearingWhenNothingIsSelectedIsNotAnError() {
        // The usual case by a wide margin: every tick of every open window with no selection.
        assertTrue(PlannerScreen.clearSelectionIfPlanChanged(1L, 2L));
        assertEquals("", PlanSelection.selectedKey());
    }

    // -- #201: what the window is watching -----------------------------------------------------

    /**
     * THE COUNTER THE WINDOW WATCHES HAS TO MOVE WHEN THE GRAPH LANDS.
     *
     * `PlannerWindow.onUpdate` rebuilds when {@code stamp} changes and does nothing otherwise,
     * so this number IS the window's ability to notice anything. Before #201 it was the plan's
     * generation and the book's revision -- two counters that describe a PLAN, and no plan can
     * exist before the graph is read. A window opened inside the 5.47 s load therefore watched
     * two numbers that were frozen for good reasons and sat on "reading graph.json" until the
     * player closed it and used the item again.
     *
     * ASSERTED HERE RATHER THAN ON THE WINDOW because the window is a `ModularScreen` that
     * reopens itself through `ClientGUI` and needs a live client, which is why `stamp` is
     * package-visible rather than private: it was private when #201 was filed, and that is
     * most of why the issue's evidence had to be read off the code instead of executed.
     */
    @Test
    public void theStampMovesWhenTheGraphLandsSoTheWindowRebuilds() throws Exception {
        PlanBook book = new PlanBook();
        // What a window opened a second after joining records as drawn.
        assertEquals(GraphService.State.IDLE, GraphService.get().state());
        long drawn = PlannerScreen.stamp(book);

        GraphDocuments.loadTinyGraphFrom(folder.getRoot());

        assertEquals(GraphService.State.READY, GraphService.get().state());
        assertNotEquals("the graph landed and the window has nothing to notice, so it never"
                        + " rebuilds and never asks stateFor again -- #201",
                        drawn, PlannerScreen.stamp(book));
    }

    /**
     * A load that ENDS BADLY moves it too.
     *
     * The same defect, other branch, and the one no picture would be taken of: a truncated or
     * hand-edited graph.json surfaces as a parse error several seconds in, and a window opened
     * before that would otherwise show a progress bar for a read that has stopped -- for the
     * rest of the session. MISSING is decided synchronously and so was never reachable this
     * way; FAILED is not.
     *
     * COUNTED RATHER THAN COMPARED ACROSS THE START OF THE LOAD, and that is a fix rather than
     * a preference. This read `stamp` on the line AFTER `startLoad` and asserted it moved
     * again later -- which is only true if the loader thread has NOT finished by the time that
     * line runs. The file is 26 bytes and the parse fails almost immediately, so on a busy host
     * the read lands after the failure, both bumps are already in the number and nothing moves
     * afterwards. Observed 2026-08-10: green on two runs of an identical tree and
     * `Actual: 80911` on the third, which is a test that reports the scheduler rather than the
     * code. There is no seam to hold the load open at, so the assertion moves to the thing that
     * is actually deterministic: `startLoad` publishes ONE transition (IDLE to LOADING) and
     * `Loader.fail` publishes the second, so EXACTLY TWO is the claim, and it is strictly
     * stronger than the original -- it says the failure published its own bump rather than
     * merely that the counter differs at two moments.
     */
    @Test
    public void theStampMovesWhenTheLoadFailsRatherThanSucceeding() throws Exception {
        File broken = folder.newFile("graph.json");
        FileOutputStream out = new FileOutputStream(broken);
        try {
            out.write("{\"dump_schema\":5,\"names\":{".getBytes("UTF-8"));
        } finally {
            out.close();
        }
        System.setProperty(GraphSource.PROPERTY, broken.getPath());
        PlanBook book = new PlanBook();
        // BOTH TAKEN BEFORE THE LOAD STARTS, which is what makes them a fixed point. The
        // service is IDLE here -- `isolateTheServices` resets it -- so nothing has been
        // published yet that either number could already contain.
        long drawn = PlannerScreen.stamp(book);
        long published = GraphService.get().generation();
        GraphService.get().startLoad(null);

        long deadline = System.currentTimeMillis() + 30_000L;
        while (GraphService.get().state() == GraphService.State.LOADING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the load never finished");
            }
            Thread.sleep(5L);
        }

        assertEquals(GraphService.State.FAILED, GraphService.get().state());
        assertEquals("a failure must publish its own transition, not ride on LOADING's",
                     2L, GraphService.get().generation() - published);
        assertNotEquals("a progress bar for a load that has stopped, for ever",
                        drawn, PlannerScreen.stamp(book));
    }

    /**
     * The two counters the stamp already watched still move it.
     *
     * A GUARD AND NOT A WITNESS, and it is here so the graph term cannot be added by REPLACING
     * one of them. The failure that would leave is exactly the one #201 is: a window that
     * stops noticing the thing it used to notice, silently, because a counter it watches keeps
     * moving for a different reason.
     */
    @Test
    public void aBookEditAndANewPlanBothStillMoveTheStamp() throws Exception {
        PlanBook book = new PlanBook();
        long empty = PlannerScreen.stamp(book);
        book.setTodo("minecraft:iron_ingot", 4L);
        assertNotEquals("\"Add to TODO\" must still redraw the footer's count", empty,
                        PlannerScreen.stamp(book));

        long beforePlan = PlannerScreen.stamp(book);
        GraphDocuments.loadTinyGraphFrom(folder.getRoot());
        PlannerService.get().plan("mod:plate", 1L, Solver.DEFAULT_MAX_NODES);
        assertNotEquals("a plan starting must still redraw", beforePlan,
                        PlannerScreen.stamp(book));
        // AWAITED RATHER THAN LEFT RUNNING. `reset` in the fixture does not stop the solver
        // thread, so an unawaited plan lands DONE and bumps a generation partway through
        // whichever test ran next -- a flake that would read as a stamp defect.
        awaitPlan();
    }

    // -- #271: what the window is watching WHILE the graph is being read ------------------------

    /**
     * THE COUNTER HAS TO MOVE DURING THE READ, AND NOT ONLY AT EITHER END OF IT.
     *
     * #201 put the graph's generation in the stamp, so the window notices the graph landing.
     * That counter moves on state TRANSITIONS and LOADING is one state, so between entering it
     * and leaving it every term of the stamp was constant -- and the panel built at 0% was the
     * panel still on screen at 99%. `GraphService.progress()` carries a long justification for
     * reporting real bytes rather than a spinner and it does report them; nothing redrew.
     *
     * SWEPT RATHER THAN SPOT-CHECKED, and the sweep is the assertion. A test at 0.0 and 1.0
     * would pass against a stamp that jumped once in the middle, which is a progress bar with
     * two positions. Walking the whole range and counting the distinct values says exactly how
     * many times the window redraws, which is the number `PlannerWindow`'s prohibition wants.
     */
    @Test
    public void theStampMovesThroughTheGraphReadRatherThanSittingOnZero() {
        PlanBook book = new PlanBook();
        Set<Long> drawn = new LinkedHashSet<Long>();
        long previous = Long.MIN_VALUE;
        for (int permille = 0; permille <= 1000; permille++) {
            long now = PlannerScreen.stamp(4L, 9L, book.revision(), permille / 1000.0f);
            assertTrue("the stamp went DOWN as more of the file was read, at " + permille
                       + " permille -- a window that has drawn the higher value would then sit"
                       + " on it for the rest of the load", now >= previous);
            previous = now;
            drawn.add(now);
        }
        assertEquals("a whole graph read must redraw the window LOAD_STEPS + 1 times: once on"
                     + " open and once per twentieth. Fewer is a bar with fewer positions than"
                     + " it claims; more is the per-tick redraw PlannerWindow forbids",
                     PlannerScreen.LOAD_STEPS + 1L, (long) drawn.size());
    }

    /**
     * AND IT COSTS NOTHING ONCE THERE IS NO READ RUNNING.
     *
     * This is the half that answers `PlannerWindow`'s prohibition rather than the half that
     * fixes the bug, and it is the one a future edit is likelier to break: a load term that
     * kept reporting after the read would redraw a window holding a scrolled plan tree, which
     * is precisely the cost the prohibition exists to refuse. `GraphService.progress()` returns
     * -1 outside LOADING, so the whole guard is that this reads it as zero.
     */
    @Test
    public void aReadyGraphCostsExactlyTheRebuildsItCostBefore271() {
        long before271 = 4L * PlannerScreen.GRAPH_WEIGHT + 9L * PlannerScreen.PLAN_WEIGHT + 2L;
        assertEquals("no read running must mean no load term at all",
                     before271, PlannerScreen.stamp(4L, 9L, 2L, -1.0f));
        assertEquals(0L, PlannerScreen.loadStep(-1.0f));
        assertEquals("progress() clamps at 1.0 and so must this, or a rounding error above the"
                     + " top of the range walks the term past LOAD_STEPS",
                     PlannerScreen.LOAD_STEPS, PlannerScreen.loadStep(1.5f));
    }

    /**
     * THE LOAD TERM IS THE ONE TERM THAT FALLS BACK TO ZERO, AND THE GRAPH WEIGHT PAYS FOR IT.
     *
     * `stamp` states that the sum only ever goes up and that nothing may be added that can
     * decrease. The load term decreases -- to zero, the instant the read ends. What makes that
     * safe is that a read can only END by bumping the graph counter (READY, FAILED and `reset`
     * all do; MISSING never enters LOADING), so the fall of at most `LOAD_STEPS` is always paid
     * for by a jump of `GRAPH_WEIGHT`.
     *
     * SO THE INEQUALITY IS THE FIX'S LOAD-BEARING FACT, and it is arithmetic rather than
     * timing, which is why it can be asserted here instead of hoped for. If it ever stops
     * holding, a window that drew at 99% of one load sees a LOWER number afterwards, matches a
     * value it has already recorded as drawn, and sits on a stale panel with nothing on screen
     * to say so.
     */
    @Test
    public void theLoadTermCanNeverOutweighTheGraphCounter() {
        assertTrue("LOAD_STEPS=" + PlannerScreen.LOAD_STEPS + " must stay under GRAPH_WEIGHT="
                   + PlannerScreen.GRAPH_WEIGHT,
                   PlannerScreen.LOAD_STEPS < PlannerScreen.GRAPH_WEIGHT);
        for (long step = 0L; step <= PlannerScreen.LOAD_STEPS; step++) {
            float progress = (float) step / PlannerScreen.LOAD_STEPS;
            long midLoad = PlannerScreen.stamp(4L, 9L, 2L, progress);
            // The same book and the same plan, one graph transition later: the read ended.
            long afterwards = PlannerScreen.stamp(5L, 9L, 2L, -1.0f);
            assertTrue("a stamp drawn at " + progress + " of the read (" + midLoad + ") must be"
                       + " below the stamp after the read ends (" + afterwards + ")",
                       midLoad < afterwards);
        }
    }

    /**
     * THE WINDOW REALLY READS `progress()`, RATHER THAN ARITHMETIC THAT WOULD BE RIGHT IF IT DID.
     *
     * The three tests above sweep {@link PlannerScreen#stamp(long, long, long, float)}, which is
     * the policy and cannot tell whether anything calls it with a live reading. This is the
     * wiring, and it is the only assertion here that needs a real load in flight.
     *
     * SAMPLED FROM A SPIN LOOP AND NOT ON A SLEEP. The document is padded so the read is wide
     * enough to look at more than once, but it is still well under a second, and a sampler that
     * slept between looks would be asserting about the scheduler. The loader runs at
     * `Thread.MIN_PRIORITY` -- `GraphService.startLoad` says why -- so a spinning sampler wins
     * the race it needs to win.
     *
     * IT CANNOT PASS BY MISSING THE LOAD. Seeing fewer than two samples is its own named
     * failure rather than a quiet skip: "the stamp never moved" and "nobody looked" are the two
     * things this must not confuse, and a version that treated an unsampled load as a pass
     * would be green against the defect on any host fast enough.
     */
    @Test
    public void theStampReallyReadsTheLiveProgressAndNotJustTheCounters() throws Exception {
        PlanBook book = new PlanBook();
        GraphDocuments.startPaddedLoadFrom(folder.getRoot(), PADDED_GRAPH_BYTES);

        Set<Long> whileLoading = new LinkedHashSet<Long>();
        Set<GraphService.State> statesSeen = new LinkedHashSet<GraphService.State>();
        boolean chooserSaidLoading = false;
        // COUNTED AND REPORTED, NOT JUST THRESHOLDED. `k distinct` is the MARGIN this witness
        // has over its own `>= 2`, and it is the only number that says whether the next run is
        // safe: a k of 17 has fifteen steps of headroom and a k of 3 is one bad schedule from
        // red, and a green run reports neither unless it is asked to. #291 measured this class
        // of test at 13 failures in 1,000 runs under contention, so re-running until it passes
        // proves almost nothing and the count proves nearly everything. Observed on the rebased
        // tree: see the commit message.
        int samples = 0;
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            GraphService.State state = GraphService.get().state();
            statesSeen.add(state);
            if (state != GraphService.State.LOADING) {
                break;
            }
            samples++;
            whileLoading.add(PlannerScreen.stamp(book));
            // THE OTHER HALF OF `PlannerWindow`'s EXCEPTION, asked of the live service rather
            // than assumed: while the graph is being read the chooser must want a state panel,
            // because that is what makes the extra rebuilds free of scroll to lose.
            PlannerState state271 =
                    PlannerEntry.stateFor(GraphService.get(), PlannerService.get());
            if (state271 != null && state271.kind() == PlannerState.Kind.LOADING) {
                chooserSaidLoading = true;
            }
        }
        awaitGraph();

        String margin = samples + " samples, " + whileLoading.size() + " distinct";
        // PRINTED ON EVERY RUN AND NOT ONLY ON FAILURE, for `HeadlessLayout.dump`'s reason one
        // class over: an assertion message is invisible exactly when the run passes, and a pass
        // is when the margin is worth reading. Without this the only way to learn k is to break
        // the test on purpose.
        System.out.println("[#271] stamp during the read: " + margin + " -> " + whileLoading);
        assertTrue("the sampler never saw the load at all (" + margin + "), so this run says"
                   + " nothing either way -- raise PADDED_GRAPH_BYTES. States seen: "
                   + statesSeen,
                   samples >= 1);
        assertTrue("the stamp was " + whileLoading + " for the whole read (" + margin + "):"
                   + " every term of it moves on a state TRANSITION and LOADING is one state,"
                   + " so the panel built at 0% is the panel shown at 99% -- #271",
                   whileLoading.size() >= 2);
        assertTrue("while the graph is being read the chooser must want a state panel",
                   chooserSaidLoading);
        assertEquals("the padded document must still parse to the same graph TINY does",
                     GraphService.State.READY, GraphService.get().state());
    }

    /**
     * THERE IS NOTHING FOR THE EXTRA REBUILDS TO THROW AWAY (#271's other half).
     *
     * `PlannerWindow` prohibits redrawing on anything cheaper than a generation bump, because a
     * rebuild loses scroll position and any open sub-panel. #271's load term redraws up to
     * `LOAD_STEPS` times without one, and the reason that is allowed is that while the graph is
     * being read the window can only be showing `statePanel` -- an eyebrow and one line of
     * text.
     *
     * ASSERTED RATHER THAN READ OFF THE PANEL BUILDER, because it is a claim about OTHER code:
     * `PlannerWidgets.statePanel` is shared with the machines table (#254) and is exactly the
     * kind of two-line panel someone adds a progress bar or a "cancel" button to. The day that
     * happens the exception stops being paid for, and nothing about the change would look like
     * it touched the planner's redraw policy. This is the tripwire.
     *
     * `AbstractScrollWidget` IS THE THING BEING EXCLUDED, not `ListWidget`, because it is the
     * base both `ListWidget` and `ScrollWidget` extend and it is where the scroll position
     * lives. Excluding the leaf classes would pass against a hand-rolled third one.
     */
    @Test
    public void theLoadingPanelHasNoScrollPositionToThrowAway() {
        ModularPanel panel = PlannerWidgets.statePanel(
                PlannerState.loading("reading graph.json, 40%"));
        HeadlessLayout.layOut(panel);

        List<IWidget> widgets = HeadlessLayout.flatten(panel);
        for (IWidget widget : widgets) {
            assertFalse("the loading panel grew " + widget.getClass().getName() + ", which holds"
                        + " a scroll position -- so #271's up-to-" + PlannerScreen.LOAD_STEPS
                        + " rebuilds now cost the player something. Read PlannerWindow's"
                        + " prohibition before changing this test",
                        widget instanceof AbstractScrollWidget);
        }
        assertEquals("the loading panel is the panel, its group and two lines of text. A fifth"
                     + " widget is not automatically wrong, but it is the moment to re-read why"
                     + " PlannerWindow lets the load term redraw at all: " + widgets,
                     4, widgets.size());
    }

    /**
     * Sized so the read is wide enough to sample twice and no wider.
     *
     * `GraphJsonReader` wraps the counted stream in a 1 MB `BufferedInputStream`, so
     * `readBytes` advances a megabyte at a time -- a document under 2 MB can therefore reach
     * READY having only ever reported 0%. Sixteen megabytes is sixteen of those steps and
     * measured at roughly a fifth of a second on this host, which a spin loop samples hundreds
     * of times. It is whitespace, so it costs the parse nothing but the skipping.
     */
    private static final int PADDED_GRAPH_BYTES = 16 * 1024 * 1024;

    /** The load `startPaddedLoadFrom` deliberately did not wait for. See that method. */
    private static void awaitGraph() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (GraphService.get().state() == GraphService.State.LOADING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the graph never finished loading: "
                                         + GraphService.get().describe());
            }
            Thread.sleep(5L);
        }
    }

    private static void awaitPlan() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (PlannerService.get().state() == PlannerService.State.PLANNING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the plan never finished");
            }
            Thread.sleep(5L);
        }
    }
}
