package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;

import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
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
        GraphService.get().startLoad(null);
        PlanBook book = new PlanBook();
        long drawn = PlannerScreen.stamp(book);

        long deadline = System.currentTimeMillis() + 30_000L;
        while (GraphService.get().state() == GraphService.State.LOADING) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the load never finished");
            }
            Thread.sleep(5L);
        }

        assertEquals(GraphService.State.FAILED, GraphService.get().state());
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
