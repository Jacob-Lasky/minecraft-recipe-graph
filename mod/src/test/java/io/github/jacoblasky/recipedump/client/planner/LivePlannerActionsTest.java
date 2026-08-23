package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.SecondaryPanel;

/**
 * The sub-panel lifecycle {@link LivePlannerActions#reopen} owns. #315.
 *
 * <h2>Two halves, and neither is worth much alone</h2>
 *
 * The bug is a POLICY bug -- `open()` asked ModularUI to do two things that are both no-ops while
 * the panel is open -- so the assertion that matters is about the sequence of calls. That can only
 * be driven with a stand-in, because `SecondaryPanel.openPanel` reaches
 * `Platform.getClientPlayer()`, which is `Minecraft.getMinecraft().player` and NPEs in a JUnit JVM.
 *
 * A stand-in is only evidence if it behaves like the real class, and this file's whole risk is
 * that it does not: a comparison against a model I wrote from the same reading that produced the
 * bug is a test of my reading. So {@link #theRealSecondaryPanelIsInertWhileOpenInBothDirections}
 * pins the three semantics {@link Handler} copies AGAINST THE REAL CLASS, using the parts of it
 * that need no Minecraft. What is modelled is then measured rather than assumed.
 */
public class LivePlannerActionsTest {

    /**
     * A stand-in for `SecondaryPanel`, with its measured semantics and nothing else.
     *
     * EVERY BRANCH BELOW IS TRANSCRIBED FROM THE 3.1.5 BYTECODE and asserted against the real
     * class by the test named above:
     *
     * <pre>
     *   openPanel()        if (open) return;  ... open = true;
     *   deleteCachedPanel  if (open) { queueDelete = true; } else { panel = null; }
     *   closePanelInternal open = false; if (queueDelete) { panel = null; queueDelete = false; }
     *   closePanel()       if (!open) return; panel.closeIfOpen();
     * </pre>
     *
     * `closePanel` IS THE ONE PLACE THIS DEPARTS FROM THE REAL CLASS, and it departs in the
     * direction the pack actually runs. `closeIfOpen` ends in `panelManager.closePanel(this)` --
     * which calls `closePanelInternal()` on the handler -- whenever `shouldAnimate()` is false, and
     * that is false without NeverEnoughAnimations, which is in neither jar set. So a close takes
     * effect before the call returns. {@link #closesOnRequest} exists to run the OTHER case, where
     * it does not.
     */
    private static final class Handler implements IPanelHandler {

        /** What the builder would have been asked for, one entry per real open. */
        private final List<String> built = new ArrayList<String>();

        /** What the panel currently on screen was built for, or null when nothing is up. */
        private String showing;

        private boolean open;
        private boolean queueDelete;
        private String cached;

        /** The subject the next build would use. Stands in for `LivePlannerActions.current`. */
        private String subject = "";

        /** False to model an animating or re-entrant close, which leaves the panel open. */
        private final boolean closesOnRequest;

        Handler(boolean closesOnRequest) {
            this.closesOnRequest = closesOnRequest;
        }

        @Override
        public boolean isPanelOpen() {
            return open;
        }

        @Override
        public void openPanel() {
            if (open) {
                return;
            }
            if (cached == null) {
                cached = subject;
                built.add(subject);
            }
            showing = cached;
            open = true;
        }

        @Override
        public void closePanel() {
            if (!open) {
                return;
            }
            if (closesOnRequest) {
                closePanelInternal();
                showing = null;
            }
        }

        @Override
        public void closeSubPanels() {
        }

        @Override
        public void closePanelInternal() {
            open = false;
            if (queueDelete) {
                cached = null;
                queueDelete = false;
            }
        }

        @Override
        public void deleteCachedPanel() {
            if (open) {
                queueDelete = true;
            } else {
                cached = null;
            }
        }

        @Override
        public boolean isSubPanel() {
            return true;
        }
    }

    /**
     * THE WITNESS: clicking a second node while the first node's menu is open acts on the second.
     *
     * WHAT MASTER DOES HERE, measured by reverting {@link LivePlannerActions#reopen}'s body to the
     * two lines it replaced (`deleteCachedPanel(); openPanel();`): the panel on screen stays
     * `node-a` and `built` stays `[node-a]`. Both ModularUI calls are no-ops while the panel is
     * open, so the second click changes nothing about the menu -- while the click ALSO reaches the
     * node beneath and moves the selection, because `closeOnOutOfBoundsClick` is false and
     * click-through is on. The player sees the highlight move to B and a menu describing A, and
     * "Add to TODO" from it adds A.
     *
     * `built` IS THE ASSERTION AND `showing` IS THE CONSEQUENCE. Asserting only what is on screen
     * would pass against a version that reopened the CACHED panel -- which is the same wrong menu,
     * arrived at differently -- so the number of real builds is what says the panel was made again
     * for the new subject.
     */
    @Test
    public void askingForASecondSubjectWhileThePanelIsOpenShowsTheSecondSubject() {
        Handler handler = new Handler(true);

        handler.subject = "node-a";
        LivePlannerActions.reopen(handler);
        assertEquals("node-a", handler.showing);
        assertEquals(java.util.Arrays.asList("node-a"), handler.built);

        handler.subject = "node-b";
        LivePlannerActions.reopen(handler);
        assertEquals("the menu must describe the node that was just clicked", "node-b",
                     handler.showing);
        assertEquals("and it must have been BUILT for it, not reopened from the cache",
                     java.util.Arrays.asList("node-a", "node-b"), handler.built);
        assertTrue(handler.isPanelOpen());
    }

    /**
     * A first open still works, which is the case the old code got right.
     *
     * WORTH ITS OWN TEST BECAUSE THE FIX ADDED A BRANCH IN FRONT OF IT. A `reopen` that closed
     * unconditionally, or that returned early on a handler that was never open, would break every
     * menu in the planner -- and the witness above cannot see it, since its first call is exactly
     * this case and it would fail for a reason that reads like the same bug.
     */
    @Test
    public void aFirstOpenIsUntouched() {
        Handler handler = new Handler(true);
        handler.subject = "node-a";
        LivePlannerActions.reopen(handler);
        assertTrue(handler.isPanelOpen());
        assertEquals("node-a", handler.showing);
        assertEquals(1, handler.built.size());
    }

    /**
     * When the close does not take, nothing is reopened and the OLD panel is not left describing
     * the NEW subject.
     *
     * THE PATH THIS COVERS IS REAL EVEN THOUGH IT IS UNREACHABLE ON THIS PACK. `closeIfOpen`
     * defers on `cantCloseNow` and animates when `shouldAnimate()` -- which needs
     * NeverEnoughAnimations, absent from both jar sets. "Correct because a mod is absent" is the
     * expired justification this repository keeps paying for, so the branch exists and is
     * asserted: on that path `openPanel` would be the no-op that IS the bug, so the honest outcome
     * is one wasted click rather than a menu that acts on something else.
     */
    @Test
    public void aCloseThatDidNotTakeDoesNotLeaveTheOldPanelDescribingTheNewSubject() {
        Handler handler = new Handler(false);
        handler.subject = "node-a";
        LivePlannerActions.reopen(handler);
        assertEquals("node-a", handler.showing);

        handler.subject = "node-b";
        LivePlannerActions.reopen(handler);
        assertEquals("nothing new may be built while the close is pending",
                     java.util.Arrays.asList("node-a"), handler.built);
        assertEquals("and the stale panel must not be claiming to be node-b's", "node-a",
                     handler.showing);
    }

    /** A null handler is the harness and the layout tests: nothing attached, nothing to open. */
    @Test
    public void aNullHandlerIsANoOpBecauseNothingHasAttached() {
        LivePlannerActions.reopen(null);
    }

    /**
     * THE FIDELITY CHECK: the real `SecondaryPanel` really is inert while open, in both
     * directions.
     *
     * WHY BOTH DIRECTIONS. "`openPanel()` while open does not throw" is only evidence if
     * `openPanel()` while CLOSED does -- otherwise the method might be inert for some other
     * reason, or the reflection might not have set the field this test thinks it set. Closed, it
     * runs on to `buildPanel()`, which dereferences a null `screen`; open, it returns at the guard
     * before touching anything. So the exception and its absence together locate the guard.
     *
     * REFLECTION ON PRIVATE FIELDS, DELIBERATELY, and this is the only way to reach the real state
     * machine here: `open` is set by `openPanel()`, which cannot run in a JUnit JVM. The field
     * names are part of the assertion -- {@link #field} fails loudly if either is renamed, which is
     * the signal that {@link Handler}'s model needs re-reading rather than a silent skip.
     */
    @Test
    public void theRealSecondaryPanelIsInertWhileOpenInBothDirections() throws Exception {
        ModularPanel parent = new ModularPanel("mcrecipedump_test_parent");
        SecondaryPanel real = new SecondaryPanel(parent, new SecondaryPanel.IPanelBuilder() {
            @Override
            public ModularPanel build(ModularPanel opener,
                                      net.minecraft.entity.player.EntityPlayer player) {
                return new ModularPanel("mcrecipedump_test_child");
            }
        }, true);

        Field open = field(real, "open");
        Field queueDelete = field(real, "queueDelete");
        Field cached = field(real, "panel");

        // OPEN: `openPanel` must return at its guard, which is the only way it can avoid the
        // null-screen dereference below.
        open.setBoolean(real, true);
        assertTrue(real.isPanelOpen());
        real.openPanel();

        // OPEN: `deleteCachedPanel` must QUEUE rather than delete -- the second half of the
        // defect, and the reason `open()` deleting first achieved nothing.
        cached.set(real, new ModularPanel("mcrecipedump_test_cached"));
        real.deleteCachedPanel();
        assertTrue("deleteCachedPanel must only queue while the panel is open",
                   queueDelete.getBoolean(real));
        assertTrue("and must leave the cached panel in place", cached.get(real) != null);

        // The queued delete is applied by `closePanelInternal`, which is what the panel manager
        // calls when the panel really closes.
        real.closePanelInternal();
        assertFalse(real.isPanelOpen());
        assertFalse(queueDelete.getBoolean(real));
        assertTrue("closePanelInternal must apply the queued delete", cached.get(real) == null);

        // CLOSED: the same call now runs past the guard and dies on the null screen. Without this
        // arm, the assertion above is consistent with `openPanel` being inert for any reason at
        // all -- including the field never having been set.
        try {
            real.openPanel();
            fail("openPanel with no screen must reach buildPanel and throw; if it did not, the "
                 + "'inert while open' arm above proves nothing");
        } catch (RuntimeException expected) {
            // IllegalStateException in practice: with a fresh un-opened parent the first throw
            // is `parent.getScreen()`'s not-valid check, before any of the nulls
            // (`screen.getMainPanel()`, `Platform.getClientPlayer()`) can be reached. Caught as
            // RuntimeException because WHICH failure it hits first is not the claim.
        }
    }

    /** A private field of `SecondaryPanel`, failing by name so a rename is loud. */
    private static Field field(SecondaryPanel panel, String name) throws Exception {
        Field field = panel.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
