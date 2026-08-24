package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.client.ScreenCursor;
import io.github.jacoblasky.recipedump.client.flow.FlowCanvas;
import io.github.jacoblasky.recipedump.client.flow.FlowZoom;
import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.ListWidget;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.client.planner.RecipeChoices;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PinStore;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;

/**
 * Opens a planner window against a frozen plan fixture, for `-Dmcrecipedump.shot=planner:...`.
 *
 * A FIXTURE FROM DISK RATHER THAN A BOOK MADE UP HERE. The harness shoots from the main menu,
 * so there is no player, no capability and no solver -- and the fixtures are real solved plans
 * from the reference pack, which is far better subject matter than anything written to
 * flatter the renderer. `plan-fluid-chain` alone is 634 nodes. (19 fixtures at #135; the count
 * is asserted in `PlanJsonTest` rather than restated here, because it has moved three times.)
 *
 * DEV-ONLY, and it is in `shot/` for that reason: it reads `tests/fixtures/` out of the
 * working tree, which does not exist beside a shipped jar. Nothing outside the harness calls
 * it, and `ShotScreens` loads this class only when its name is asked for.
 */
final class PlannerShot {

    /** `plan-truncated` shows the most in one picture: a deep tree AND the cut-off warning. */
    private static final String DEFAULT_FIXTURE = "plan-truncated";

    private PlannerShot() {
    }

    /** `planner`, or `planner:<fixture>`; `arg` is whatever followed the colon. */
    static void openTree(String arg) {
        PlanView plan = fixture(arg);
        armNodeActions(plan.tree());
        PlannerScreen.openPlan(plan, book(plan));
    }

    /**
     * `planner-yield`: a CHANCE-YIELDED row, scrolled into frame so the percentage is visible.
     *
     * IT SCROLLS BECAUSE ARITHMETIC SAYS A PLAIN `planner` SHOT CANNOT SHOW ONE (#252, #280).
     * The tree viewport is about 14 rows -- `PANEL_HEIGHT` 220 less padding, header and footer,
     * over `ROW_HEIGHT` 11 -- and the EARLIEST chance-yielded row in any committed fixture is
     * index 38. Measured across all four fixtures that carry one: 38, 121, 123, 533, 559, 619,
     * 644. So the row this screen exists to photograph is off the bottom of the panel in every
     * one of them, and a `planner` shot would come back a perfectly good picture of the wrong
     * fourteen rows.
     *
     * THAT IS #252's OWN FAILURE MODE AND IT COST THREE BOOTS. Its first two planner shots were
     * byte-identical because the width cut landed before the part that differed; this is the
     * same class one axis over, the viewport rather than the width. It was caught here by
     * counting rows before booting rather than by looking at a PNG afterwards.
     *
     * SCROLLING IS HONEST HERE AND RE-ROOTING WOULD NOT BE. This photographs the real panel
     * built from the real fixture, moved to where a player would move it; building a synthetic
     * plan rooted at the chanced node would photograph a plan that never existed. `flow-hit`
     * establishes that a shot may drive a widget after layout, and `FlowCanvas` already uses
     * the same `scrollTo`.
     *
     * A HOLD RATHER THAN `animate`, because this needs one action after layout rather than a
     * measured sequence of frames -- and `animate` is gated on `-Dmcrecipedump.shotTimedFrames`,
     * which a caller who did not know that would omit, getting a capture before the scroll and
     * no sign of it in the log.
     */
    static void openYield(String arg) {
        final PlanView plan = fixture(arg == null || arg.trim().isEmpty()
                                      ? YIELD_FIXTURE : arg);
        final int row = firstChancedRow(plan);
        if (row < 0) {
            // LOUDLY. A fixture with no chance-yielded node cannot exercise this surface, and a
            // picture of an unscrolled tree would look like a successful shot of the render.
            throw new IllegalStateException(
                    "planner-yield needs a fixture with a chance-yielded node; "
                    + plan.target() + " has none. `plan-truncated` has one at row 38.");
        }
        armNodeActions(plan.tree());
        // BUILT HERE RATHER THAN THROUGH `openPlan`, ONLY so the panel can be kept. The scroll
        // has to reach the tree list after layout, and fishing it back out of
        // `Minecraft.currentScreen` means unwrapping ModularUI's `GuiScreenWrapper` -- the
        // indirection `ShotScreens.Animated` exists to avoid. This is the same panel `openPlan`
        // would have built; `openMenu` opens a hand-built panel for the same reason.
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book(plan),
                                                        PlannerScreen.schemaCheck(), SHOT_ACTIONS);
        PlannerScreen.openPanel(panel);
        ShotHarness.log("planner-yield: " + plan.target() + " has its first chanced row at "
                        + row + "; scrolling it into a viewport of about 14 rows");
        ShotScreens.holdCapture(new ScrollToRow(panel, row));
    }

    /** The fixture `planner-yield` uses when none is named: the smallest tree with a chance. */
    private static final String YIELD_FIXTURE = "plan-truncated";

    /**
     * `planner-collide`: TWO ROWS THAT SHARE A NAME, IN ONE FRAME, so #232's rule can be seen.
     *
     * SAME ARITHMETIC AS `planner-yield` AND THE SAME CONCLUSION. Counted over the committed
     * fixtures, the only same-name pair inside an unscrolled viewport is `plan-variant-table`'s
     * two "Brown Concrete" rows at indices 0 and 1 -- and BOTH of those are at capacity, so
     * that fixture can only ever photograph one branch of the width rule. Every pair that
     * straddles the rule sits at index 155 or beyond in a 675-to-703-row tree, off the bottom
     * of a 14-row panel. So this scrolls, for the reason #280 scrolls: the alternative is a
     * perfectly good picture of the wrong fourteen rows.
     *
     * NEAREST PAIR, NOT FIRST COLLISION, and that distinction is the whole selection rule. The
     * first colliding row in `plan-fluid-chain` is an "Iron Ore" at index 25 whose partner is
     * at 617 -- a shot of it shows ONE row and proves nothing about telling two apart. What a
     * reader needs is two rows carrying one name close enough to appear together, so this picks
     * the first row that HAS a same-label, different-key partner within a viewport of it.
     *
     * IT DOES NOT DECIDE WHICH BRANCH EACH ROW TAKES, deliberately. That is the width rule's
     * job and photographing it is the point; a shot that selected rows by predicting the answer
     * would be a picture of this method's opinion. It logs the pair it framed so the PNG can be
     * checked against what was asked for.
     */
    static void openCollision(String arg) {
        PlanView plan = fixture(arg == null || arg.trim().isEmpty() ? COLLIDE_FIXTURE : arg);
        int row = firstNearbyCollidingRow(plan);
        if (row < 0) {
            // LOUDLY, for `planner-yield`'s reason: a fixture with no framable pair cannot
            // exercise this surface, and an unscrolled tree would look like a successful shot.
            throw new IllegalStateException(
                    "planner-collide needs two rows sharing a label within " + VIEWPORT_ROWS
                    + " rows of each other; " + plan.target() + " has none. "
                    + "`plan-fluid-chain` has a pair.");
        }
        armNodeActions(plan.tree());
        // THE SCHEMA CHECK IS PASSED THROUGH, NOT OMITTED. #285 added it to `plannerPanel` and
        // this call site was written before that landed; the rebase merged both cleanly because
        // they are in different files, and the three-argument call compiled against nothing.
        // `openYield` above passes the same thing, and a shot that skipped it would photograph
        // a panel with no schema banner -- a difference invisible in this fixture and wrong in
        // the one that matters.
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book(plan),
                                                        PlannerScreen.schemaCheck(), SHOT_ACTIONS);
        PlannerScreen.openPanel(panel);
        ShotHarness.log("planner-collide: " + plan.target() + " has its first framable "
                        + "same-name pair at row " + row + " ("
                        + NodeRowText.label(plan.flatten().get(row))
                        + "); scrolling it into a viewport of about " + VIEWPORT_ROWS + " rows");
        ShotScreens.holdCapture(new ScrollToRow(panel, row));
    }

    /** The fixture `planner-collide` uses when none is named. */
    private static final String COLLIDE_FIXTURE = "plan-fluid-chain";

    /**
     * The tree viewport in rows, as `planner-yield`'s reasoning already counts it.
     *
     * APPROXIMATE ON PURPOSE AND USED ONLY AS A SEARCH RADIUS. The exact height depends on how
     * many footer lines the plan draws, and a pair four rows apart is in frame under any of
     * them; a pair thirteen apart may not be. Erring small picks a tighter pair, which is the
     * safe direction -- the failure this can produce is a good picture of a closer pair, not a
     * picture missing half of what it claims.
     */
    static final int VIEWPORT_ROWS = 14;

    /**
     * The first row in display order that shares its label with a DIFFERENT key nearby, or -1.
     *
     * DIFFERENT KEY, NOT MERELY THE SAME LABEL. A tree draws the same key on every row that
     * uses it, so `plan-fluid-chain` repeats "Iron Ore" seven times for one item; those rows
     * SHOULD read alike and framing them would photograph correct behaviour as the bug.
     */
    static int firstNearbyCollidingRow(PlanView plan) {
        java.util.List<PlanNode> rows = plan.flatten();
        for (int i = 0; i < rows.size(); i++) {
            String label = NodeRowText.label(rows.get(i));
            int from = Math.max(0, i - VIEWPORT_ROWS);
            int to = Math.min(rows.size(), i + VIEWPORT_ROWS + 1);
            for (int j = from; j < to; j++) {
                if (j != i && NodeRowText.label(rows.get(j)).equals(label)
                        && !rows.get(j).key().equals(rows.get(i).key())) {
                    return Math.min(i, j);
                }
            }
        }
        return -1;
    }

    /**
     * Index of the first chance-yielded row in display order, or -1.
     *
     * DISPLAY ORDER AND NOT `flatten()` ORDER, because the number is used to scroll a list whose
     * rows are in the order the tree is walked. They are the same walk today and this comment is
     * the reason to keep them so.
     */
    private static int firstChancedRow(PlanView plan) {
        int index = 0;
        for (PlanNode node : plan.flatten()) {
            if (node.runs() > 0L && node.yieldChance() > 0.0 && node.yieldChance() < 1.0) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /**
     * Scroll the tree list so a given row is in frame, once, after layout has run.
     *
     * ONE POLL AND DONE. The list has no box until `WidgetTree` has sized it, so this cannot
     * happen in the opener; by the first hold poll the layout is up and the scroll area knows
     * its own size. Returning false immediately after means the harness captures the next frame.
     */
    private static final class ScrollToRow implements ShotScreens.Hold {

        private final ModularPanel panel;

        private final int row;

        ScrollToRow(ModularPanel panel, int row) {
            this.panel = panel;
            this.row = row;
        }

        /** Polls to keep re-applying the scroll before letting the capture happen. */
        private static final int HOLD_POLLS = 8;

        private int polls;

        @Override
        public boolean busy() {
            ListWidget<?, ?> list = firstList(panel);
            if (list == null || list.getArea().h() <= 0) {
                // NOT YET LAID OUT, or there is no list. Both are "come back next poll", and
                // the run's own timeout is the backstop -- the same shape `JeiKeybindShot`'s
                // sweep uses rather than a frame count nobody can justify.
                return true;
            }
            // A THIRD OF THE WAY DOWN THE VIEWPORT RATHER THAN AT THE TOP, so the picture shows
            // the chanced row with ordinary rows above and below it. A row pinned to the first
            // line reads as a cropped panel rather than as a row in a list.
            int target = Math.max(0, row * PlannerWidgets.ROW_HEIGHT
                                     - list.getArea().h() / 3);
            list.getScrollArea().getScrollY().scrollTo(list.getScrollArea(), target);

            // RE-APPLIED EVERY POLL, AND THAT IS THE WHOLE FIX. The first version scrolled once
            // and returned false, and the PNG came back showing rows 0 to 13 while the log said
            // "scrolled to 367px of 550" -- the call was made, with sane numbers, and the
            // capture did not show it. `scrollingALaidOutTreeListSticks` then proved headlessly
            // that the offset DOES read back on a laid-out list, so the problem was never the
            // API: something between that poll and the capture put it back. Holding for a few
            // polls means the last frame before the capture is a scrolled one whatever that
            // something is, and it costs eight render ticks.
            //
            // THE READ-BACK IS LOGGED ON THE LAST POLL rather than the first, so the log
            // answers "did it stick" instead of "was it called" -- which is exactly the
            // distinction the wasted boot could not make.
            polls++;
            if (polls < HOLD_POLLS) {
                return true;
            }
            ShotHarness.log("planner-yield: asked for " + target + "px of "
                            + list.getScrollData().getScrollSize() + " for row " + row
                            + "; after " + HOLD_POLLS + " polls the list reads back "
                            + list.getScrollArea().getScrollY().getScroll() + "px");
            return false;
        }
    }

    /**
     * The first `ListWidget` under `parent`, depth first, which in the planner is the tree.
     *
     * FIRST RATHER THAN ONLY. The panel holds one list today and this returns as soon as it
     * finds one, so a second list added below the tree would not change what this picks. If a
     * second one is ever added ABOVE it, this returns the wrong widget and the shot scrolls
     * something else -- which the log line at the call site would show as a scroll of the wrong
     * size rather than as silence.
     */
    private static ListWidget<?, ?> firstList(com.cleanroommc.modularui.api.widget.IWidget parent) {
        if (parent instanceof ListWidget) {
            return (ListWidget<?, ?>) parent;
        }
        for (com.cleanroommc.modularui.api.widget.IWidget child : parent.getChildren()) {
            ListWidget<?, ?> found = firstList(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * `planner-selected`: the tree with one node's key selected, so the highlight is in shot.
     *
     * A SCREEN OF ITS OWN RATHER THAN A FLAG ON `planner`, because a highlight is not the
     * default state of a freshly opened planner and a shot that always drew one would be
     * photographing something no player sees on open. `ShotScreens` costs one line per screen
     * precisely so this is the cheap option.
     *
     * THE SELECTED KEY IS ONE THAT OCCURS MORE THAN ONCE. Highlighting is by KEY, so every
     * occurrence lights up -- that is `PlanSelection`'s whole design and the property a picture
     * has to show, since a shot of one highlighted row is equally consistent with a holder that
     * only ever lit the box that was clicked.
     */
    static void openSelectedTree(String arg) {
        PlanView plan = fixture(arg == null || arg.trim().isEmpty() ? SELECTION_FIXTURE : arg);
        armNodeActions(plan.tree());
        PlanSelection.select(mostRepeated(plan));
        PlannerScreen.openPlan(plan, book(plan));
    }

    /** `flow-selected`: the same, on the diagram, where the wash sits on a busy canvas. */
    static void openSelectedFlow(String arg) {
        PlanView plan = fixture(arg == null || arg.trim().isEmpty() ? SELECTION_FIXTURE : arg);
        armNodeActions(plan.tree());
        PlanSelection.select(mostRepeated(plan));
        // `forPanel` RATHER THAN A HAND-SIZED CANVAS (#323). These three screens each carried
        // their own `pos(4, 4).size(612, 372)`, which is how the first production opener came to
        // have no source for the two numbers at all; the factory is now the only statement of
        // them and the panel's own constants are what it reads.
        //
        // `PlannerActions.NONE` IS WRITTEN OUT (#325). It used to be the default the deleted
        // three-argument `planNodeContent` supplied, which is how a diagram of inert nodes came to
        // have three green screenshots. This screen photographs the SELECTION WASH and clicks
        // nothing, so inert is the right answer here -- said out loud rather than inherited.
        FlowCanvas canvas = FlowCanvas.forPanel(plan, PlannerActions.NONE);
        PlannerScreen.openPanel(
                PlannerWidgets.flowPanel(plan, canvas, PlannerActions.NONE));
    }

    /**
     * A fixture with one key at more than one occurrence. `PlanSelectionTest`'s choice, and for
     * its reason: `plan-in-stock` uses every key once, so it is IMMUNE to the property being
     * photographed -- one highlighted row proves nothing about "every occurrence".
     */
    private static final String SELECTION_FIXTURE = "plan-same-name";

    /**
     * The node whose key appears most often in `plan`, first occurrence wins ties.
     *
     * DETERMINISTIC, so a shot of a given fixture is the same picture every time -- the same
     * rule {@link #mostAlternatives} follows and for the same reason.
     */
    static PlanNode mostRepeated(PlanView plan) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
        for (PlanNode node : plan.flatten()) {
            Integer seen = counts.get(node.key());
            counts.put(node.key(), Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        }
        PlanNode best = plan.tree();
        int bestCount = 0;
        for (PlanNode node : plan.flatten()) {
            int count = counts.get(node.key()).intValue();
            if (count > bestCount) {
                best = node;
                bestCount = count;
            }
        }
        return best;
    }

    static void openMenu(String arg) {
        PlanView plan = fixture(arg);
        armNodeActions(plan.tree());
        PlannerScreen.openPanel(PlannerWidgets.nodeMenu(plan.tree(), SHOT_ACTIONS));
    }

    /**
     * `row-menu`: the menu a SHOPPING ROW opens, which is the only thing #251 makes visible.
     *
     * THE TODO PANEL ITSELF IS NOT THE ARTIFACT, and that is worth stating because it is the
     * obvious choice and it would prove nothing. `ClickableGroup`'s own header says what it is
     * for: "a click target the width of the row and no visual change at all". So a screenshot
     * of the TODO panel is byte-identical before and after a row becomes clickable, and
     * shooting one would be the failure #252 paid three pack boots for -- a green run whose
     * picture cannot show the change it was taken for.
     *
     * The menu is genuinely new pixels, and it carries the number the whole issue is about:
     * its title is the row's own aggregate `need`, so a reader can see WHICH quantity the menu
     * would act on rather than taking the wiring on trust.
     */
    static void openRowMenu(String arg) {
        PlanView plan = fixture(arg);
        if (plan.shoppingList().isEmpty()) {
            // LOUDLY, rather than photographing an empty menu. A fixture with no shopping list
            // cannot exercise this surface at all, and a picture of the fallback would look
            // like a rendered menu to anyone reading the PNG rather than the log.
            throw new IllegalStateException(
                    "row-menu needs a fixture with a shopping list; " + plan.target()
                    + " has none. `plan-cycle` has twelve rows.");
        }
        armNodeActions(plan.tree());
        PlannerScreen.openPanel(
                PlannerWidgets.rowMenu(plan.shoppingList().get(0), SHOT_ACTIONS));
    }

    /**
     * The recipe picker, on the root of a fixture, with candidates from the loaded graph.
     *
     * THE SUBJECT MATTER IS DECIDED BY WHETHER AN ORACLE IS MOUNTED, exactly as
     * `planner-live` is, and both outcomes are pictures worth having. With one, this is the
     * real alternatives for a real item, which no fixture can supply -- the plan shape
     * carries `alternatives` as a COUNT, so a candidate list simply is not in there and the
     * only sources are a real graph or an invented one. Without one it is the empty case
     * saying which of the three reasons applies, which is what a player without a
     * `graph.json` gets.
     *
     * IT WAITS FOR THE GRAPH for the reason `LivePlanShot` does: the read is 5.47 s off the
     * main thread, so a shot that opened immediately would photograph "loading" every time
     * and the wait is bounded. Instant when there is no graph to wait for.
     */
    static void openRecipePicker(String arg) {
        PlanNode node = mostAlternatives(fixture(arg).tree());
        LivePlanShot.awaitGraph();
        PlannerScreen.openPanel(PlannerWidgets.recipePicker(
                node,
                RecipeChoices.forNode(GraphService.get().graph(), node,
                                      PinStore.get().pins()),
                SHOT_ACTIONS));
    }

    /**
     * The node in `tree` with the most candidates, which is the one worth photographing.
     *
     * NOT THE ROOT, which is what this shot used before and is a poor subject: a root with
     * one alternative photographs a picker containing a single row, and the fixtures' roots
     * run from 1 to 22 while nodes deeper in reach 172. The picker's whole job is the list,
     * so the shot should be of a list -- including the case where it is capped, which no
     * fixture root can produce.
     *
     * Ties go to the first in depth-first order, so the choice is deterministic and a shot
     * of a given fixture is the same picture every time.
     */
    static PlanNode mostAlternatives(PlanNode tree) {
        PlanNode best = tree;
        for (PlanNode child : tree.children()) {
            PlanNode candidate = mostAlternatives(child);
            if (candidate.alternatives() > best.alternatives()) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Frames one full corner-to-corner sweep takes.
     *
     * 300, matching the default `-Dmcrecipedump.shotTimedFrames`, so a standard timing run
     * traverses the diagram exactly once. A longer run simply sweeps it again rather than
     * running off the end and parking, which would report a stationary viewport as the cost of
     * panning.
     *
     * AND THAT EQUALITY IS ALSO WHY THE SCREENSHOT WAS BLANK FOR FIVE RUNS (#293). Traversing
     * exactly once means ENDING WHERE YOU STARTED: both fractions are periodic in this number,
     * so the last driven frame is at (0, 0), and `FlowCanvas.panToFraction`'s header explains
     * that (0, 0) of this layout is the one place guaranteed to hold nothing. The timing is
     * unaffected -- every frame in between is real -- but the photograph taken afterwards was
     * of an empty corner, every time, deterministically.
     *
     * DO NOT "FIX" THAT BY DETUNING THIS NUMBER so the run stops somewhere luckier. The
     * one-traversal property above is the reason it is 300 and it is worth more than a
     * coincidental stopping point, which the next edit to `PASSES` or the frame budget would
     * silently remove anyway. The capture poses instead, through
     * {@link ShotScreens.PreCapture}.
     *
     * PACKAGE-PRIVATE SO THE TEST CAN DERIVE THE STOPPING FRAME rather than hard-code a 0 that
     * stops meaning anything the moment either constant moves.
     */
    static final int SWEEP = 300;

    /**
     * Horizontal passes per vertical descent.
     *
     * 8 rather than 1, because the diagram is far taller than it is wide -- depth is capped at
     * 24 columns by `solve.py` while the leaf level is thousands of rows -- so one pass per
     * descent is a diagonal, and a diagonal over a shape like this is a sweep of the empty
     * corner. Eight is enough that every pass crosses the leaf column while the descent is
     * still fine-grained.
     *
     * PACKAGE-PRIVATE FOR THE SAME REASON AS {@link #SWEEP}: the test that pins the stopping
     * frame computes it from these two, so the two cannot drift apart from what it asserts.
     */
    static final int PASSES = 8;

    /**
     * `flow`, or `flow:<fixture>`: the plan as a pannable diagram.
     *
     * Registers itself as {@link ShotScreens.Animated} so a timing run measures PANNING.
     * A static screenshot of a canvas measures redraw; the frame cost that the 60 fps gate is
     * about is the one paid while the viewport moves and the culler re-decides what is on
     * screen, and those are different numbers.
     */
    static void openFlow(String arg) {
        // `<plan>@<zoom>`, so the zoom can be photographed. The suffix is stripped BEFORE
        // `flowTree` sees the argument, since `synthetic:4000@0.5` still has to parse as a
        // synthetic plan of four thousand nodes.
        String spec = arg;
        float zoom = FlowZoom.DEFAULT;
        int at = arg == null ? -1 : arg.lastIndexOf('@');
        if (at >= 0) {
            spec = arg.substring(0, at);
            String value = arg.substring(at + 1);
            try {
                zoom = Float.parseFloat(value);
            } catch (NumberFormatException e) {
                // LOUDLY. A silently ignored zoom produces a screenshot at 1.0 that looks
                // entirely correct, and the whole point of the shot is to show it is not.
                throw new IllegalArgumentException("bad zoom '" + value + "' in '" + arg + "'");
            }
        }
        PlanView plan = flowPlan(spec);
        // ARMED HERE TOO, AND IT WAS NOT UNTIL #213. `openTree` armed the seam and this did
        // not, so every screenshot of the diagram was taken against `NodeActions.NONE` -- the
        // icon column was charged on all 634 nodes of `plan-fluid-chain` and nothing was ever
        // drawn in it. The picture looked like a diagram with no icons, which is also what a
        // diagram with a broken icon lookup looks like.
        armNodeActions(plan.tree());
        // INERT ACTIONS, SAID OUT LOUD (#325). This screen is a frame-timing sweep and a
        // photograph; it presses nothing, so a live `PlannerActions` would add a menu nobody opens.
        // `planner-view-flow` is the screen that drives the real one.
        final FlowCanvas canvas = FlowCanvas.forPanel(plan, PlannerActions.NONE);
        canvas.setZoom(zoom);
        PlannerScreen.openPanel(PlannerWidgets.flowPanel(plan, canvas, PlannerActions.NONE));
        ShotScreens.animate(new ShotScreens.Animated() {
            /** Most nodes drawn in any one frame so far. See the note where it is updated. */
            private int peakDrawn;

            @Override
            public void step(int frame) {
                // A SERPENTINE OVER THE WHOLE DIAGRAM: several passes across while descending
                // once. Both axes, because the culler indexes columns and searches rows, so
                // panning on one only exercises half of it.
                //
                // IN FRACTIONS BECAUSE PIXELS MEASURED AN EMPTY VIEWPORT. `panTo(frame * 7,
                // frame * 3)` covers about two thousand pixels over three hundred frames, and a
                // 4,000 node plan is seventy thousand pixels tall -- so the sweep stayed in the
                // top-left corner. See `FlowCanvas.panToFraction`.
                //
                // AND NOT A DIAGONAL, which was the first fix and was still wrong. This layout
                // is roughly 2,200 x 69,000: depth is bounded at 24 columns while the leaf
                // level is thousands of rows, so a diagonal spends nearly all of its frames at
                // small x -- the shallow columns, which hold one node, then two, then five. It
                // reaches the leaf column only in its last few frames. A raster covers the
                // plane; a diagonal covers the sparse corner of it.
                canvas.panToFraction(((frame * PASSES) % SWEEP) / (double) SWEEP,
                        (frame % SWEEP) / (double) SWEEP);
                // THE DENOMINATOR FOR THE FRAME TIMINGS, and it is here because without it the
                // timings cannot be compared with each other at all. Wall-clock draw cost on
                // this host varies more between runs of identical work than the thing being
                // varied does -- on 2026-08-03, p50 for the same sweep came back at 6.13ms and
                // at 18.04ms hours apart, and one set of runs put zoom 0.75 FASTER than zoom
                // 1.0, which cannot be true since 0.75 draws strictly more. How many nodes were
                // drawn does not depend on the host, the rasteriser or what else Tower is
                // doing, so it is the number that can actually be quoted.
                //
                // THE RUNNING MAXIMUM, NOT THE INSTANTANEOUS COUNT. A plan diagram is mostly
                // empty: depth is capped at 24 columns and only the LEAF column is dense, so
                // every other column spreads its nodes over the full height at a pitch far
                // larger than the viewport. Sampling the count every hundred frames therefore
                // reports 0 most of the time, which says nothing about the worst frame -- and
                // the worst frame is the whole question a frame budget asks.
                peakDrawn = Math.max(peakDrawn, canvas.drawnLastFrame());
                if (frame % 100 == 0) {
                    ShotHarness.log("flow: zoom " + canvas.zoom() + " peak " + peakDrawn
                            + " of " + canvas.nodeCount() + " nodes drawn in one frame, by "
                            + "frame " + frame);
                }
            }
        });
        ShotScreens.preCapture(new ShotScreens.PreCapture() {
            /**
             * THE ROOT, AND THE PICTURE IS STILL MOSTLY EMPTY -- WHICH IS THE PLAN AND NOT THE
             * CAMERA. Measured on `synthetic:4000` after this fix: one node in frame, and the
             * sweep's own peak over 300 frames is 20 of 4,000. A layered layout caps depth at
             * 24 columns while the leaf level is thousands of rows, so it is roughly 2,200 by
             * 69,000 and every column except the last spreads its nodes at a pitch far larger
             * than a 372px viewport. There is no viewport position at zoom 1.0 from which a
             * 4,000 node plan looks like a diagram.
             *
             * SAID HERE SO THE ARTIFACT IS NOT MISREAD AS THE BUG COMING BACK. A reviewer who
             * opens `flow293b.png`, sees one node on grey, and concludes the pose failed would
             * be wrong -- and would very reasonably reach that conclusion, because the picture
             * this fix produces and the picture the fix replaced differ by one node. The
             * difference that matters is that the run now SAYS which it is, and can fail.
             *
             * For a diagram that reads as a diagram, shoot a real fixture rather than the
             * synthetic ceiling case: `flow:plan-in-stock` fits five columns on screen.
             */
            @Override
            public void beforeCapture() {
                canvas.panToBox(canvas.rootBox());
                ShotHarness.log("flow: composed on the root box for the capture");
            }
        });
        ShotScreens.expectDrawn(new ShotScreens.Drawn() {
            /**
             * THE POSITIVE CONTROL, and it is the half of #293 that outlives the pan fix.
             *
             * Moving the camera onto the root stops THIS blank screenshot. It does not make a
             * blank screenshot DETECTABLE, and that is the property that was actually missing:
             * an empty grey rectangle is what a correct render of an empty viewport looks
             * like, so for five artifacts across two PRs a broken probe and a true negative
             * were the same picture and the picture was believed.
             *
             * IT ASKS WHETHER THE AIMED-AT BOX IS WHOLLY ON SCREEN, NOT WHETHER THE COUNT IS
             * ABOVE ZERO, AND THE DIFFERENCE IS NOT PEDANTRY -- `> 0` SHIPPED AND PASSED A
             * BLANK PICTURE. The first `panToBox` wrapped a negative offset instead of
             * clamping it, so the run reported `drawn check PASSED -- 1 node(s) drawn`,
             * exited 0, and wrote a panel holding a two-pixel sliver of one node jammed
             * against the left edge. A human calling that image blank and a check calling it a
             * pass were both right about their own question, which means the check was asking
             * the wrong one.
             *
             * "The thing I aimed at is fully in frame" is the question with no such gap. It
             * cannot be satisfied by a clipped edge, by a neighbour that happens to overlap
             * the viewport, or by a culler that returns a box it is not drawing.
             */
            @Override
            public boolean drewSomething() {
                return canvas.drawnLastFrame() > 0 && canvas.fullyShowing(canvas.rootBox());
            }

            @Override
            public String describe() {
                int root = canvas.rootBox();
                return "flow: " + canvas.drawnLastFrame() + " node(s) drawn on the composed "
                        + "frame; box " + root + " of " + canvas.nodeCount()
                        + (canvas.fullyShowing(root) ? " is wholly in frame"
                                                     : " is NOT wholly in frame");
            }
        });
    }

    /**
     * `flow-hit`: does ModularUI's own hit-testing agree with the layout, through the scroll
     * viewport AND the zoom matrix?
     *
     * WHY THIS EXISTS AT ALL. Two comments in this codebase disagree. `FlowCanvas`'s header
     * says viewport hit-testing "comes for free" from `AbstractScrollWidget` and that
     * hand-rolling a canvas gets it wrong; `PlannerWidgets.planNodeContent` says a diagram
     * node "has its own hit-testing through the viewport transform, and a canvas of clickable
     * rows would fight it". Both cannot be the better design, and which one is right decides
     * whether the click path is `ClickableGroup` rows or a hand-rolled `boxAt`. Nobody had
     * measured it, because the harness had no mouse.
     *
     * IT HAS ONE NOW. `Mouse.setCursorPosition` moves the real cursor on the Xvfb display, and
     * Minecraft reads its position from LWJGL rather than from an event queue, so the hover
     * pass runs for real. That is a genuine capability the README used to deny: "It has no
     * input. Nothing clicks, scrolls or types."
     *
     * The screen parks the cursor over the centre of a chosen node and logs, side by side,
     * which box `IWidget.isHovering()` reports and which box the layout says is at that point.
     * They must agree. A disagreement is a node you can see and cannot click.
     */
    static void openFlowHit(String arg) {
        String spec = arg;
        float zoom = FlowZoom.DEFAULT;
        int at = arg == null ? -1 : arg.lastIndexOf('@');
        if (at >= 0) {
            spec = arg.substring(0, at);
            zoom = Float.parseFloat(arg.substring(at + 1));
        }
        PlanView plan = flowPlan(spec);
        // INERT ACTIONS, DELIBERATELY, AND THIS IS THE ONE SCREEN WHERE THAT MATTERS (#325). The
        // probe compares `isHovering()` against the layout's own answer and NEVER PRESSES -- it
        // parks a cursor and reads two booleans -- so a live `PlannerActions` here would change
        // nothing it measures and would put a `LivePlannerActions` with no attached panel behind
        // every node. `planner-view-flow` is the screen that drives the real one end to end.
        final FlowCanvas canvas = FlowCanvas.forPanel(plan, PlannerActions.NONE);
        canvas.setZoom(zoom);
        PlannerScreen.openPanel(PlannerWidgets.flowPanel(plan, canvas, PlannerActions.NONE));
        ShotScreens.animate(new ShotScreens.Animated() {
            @Override
            public void step(int frame) {
                // PARK, THEN WAIT FIVE FRAMES, THEN READ. The hover pass runs from the
                // cursor position sampled at the start of a tick, so `isHovering` lags a move
                // by at least one frame -- and at one frame's gap the probe reported ModularUI
                // answering for the PREVIOUS node on every line. Six tidy DISAGREE lines that
                // were really six agreements, one row out of step. A log that needs the reader
                // to shift it by one is a log that gets read wrong.
                int target = frame / SETTLE;
                if (target >= PROBE_NODES) {
                    return;
                }
                if (frame % SETTLE == HOLD) {
                    report(canvas, target);
                    return;
                }
                if (frame % SETTLE != 0) {
                    return;
                }
                if (!canvas.parkCursorOverBox(target)) {
                    // LOUDLY. A skipped probe that says nothing is a probe that passes.
                    ShotHarness.log("flow-hit: node " + target + " is off screen, not probed");
                }
            }
        });
    }

    /** How many nodes `flow-hit` probes. Enough to cross a column boundary and a row gap. */
    private static final int PROBE_NODES = 6;

    /** Frames per probe: park on frame 0 of the group, read on frame {@link #HOLD}. */
    private static final int SETTLE = 10;

    /** Frames to wait after moving the cursor before believing `isHovering`. */
    private static final int HOLD = 5;

    private static void report(FlowCanvas canvas, int expected) {
        int hovered = canvas.hoveredBox();
        int fromLayout = canvas.boxAtCursor();
        ShotHarness.log("flow-hit: node " + expected + " -- ModularUI says " + hovered
                + ", the layout says " + fromLayout
                + (hovered == fromLayout ? "  AGREE" : "  DISAGREE")
                + "   [" + canvas.cursorDiagnostic() + "]");
    }

    /** `planner-view-flow`: the diagram, opened by clicking the button that offers it. */
    static void openViewFlow(String arg) {
        openView(arg, PlannerWidgets.SHOW_DIAGRAM, "mcrecipedump_flow");
    }

    /**
     * `planner-view-todo`: the totals panel, likewise.
     *
     * THE SHOT NAME AND THE PANEL ID KEEP THE OLD WORD ON PURPOSE. #332 renamed the BUTTON from
     * `Show TODO list` to `Show totals` and the panel's heading with it, but `planner-view-todo` is
     * the name `ShotScreens` registers, the entry `ShotScreensTest` holds in its measured list of
     * screen names, and the stem of a committed screenshot in `docs/shots/`. None of those is
     * something a player reads, and the panel does still carry the plan book's TODO section.
     */
    static void openViewTodo(String arg) {
        openView(arg, PlannerWidgets.SHOW_TOTALS, "mcrecipedump_todo");
    }

    /**
     * Open the planner a player gets, click one view button with the real mouse, and check what
     * came up. #323.
     *
     * WHAT THESE TWO PROVE THAT NO OTHER SHOT DOES. `flow` and `planner-todo` photograph those
     * panels by calling their builders directly -- which is precisely how #323 shipped: three
     * green screenshots of a diagram no player could reach, every one produced by the only
     * caller. So these do not build a panel at all. They open the panel `PlannerScreen` builds,
     * park the real cursor over a button, and click it.
     *
     * THE PANEL IS OPENED THROUGH `PlannerScreen.openPlan` AND THEN FOUND AGAIN, rather than
     * built here and kept. A panel built here would carry a `FlowSource` and a `PlannerActions`
     * chosen by the harness, and the question being asked is whether the ones PRODUCTION chooses
     * work. `openPlan` is also the player's own path, so nothing about the subject is arranged.
     *
     * FINDING IT BACK COSTS THE `GuiScreenWrapper` UNWRAP THAT `openYield` AVOIDS, and here that
     * is the cheaper side of the trade rather than a regression. The alternative was making
     * `planPanel` public to hold a reference, and `PanelReachabilityTest` -- this issue's own
     * gate -- reported it as a panel builder with no opener outside `shot/` the moment it became
     * visible. Widening production API to photograph it is precisely the move that gate exists
     * to refuse, so the indirection lives in the harness instead. See {@link #livePanel}.
     *
     * A HOLD RATHER THAN `animate`, for `planner-yield`'s stated reason: `animate` is gated on
     * `-Dmcrecipedump.shotTimedFrames`, which a caller who did not know that would omit -- and
     * the capture would then happen before the click, producing a perfectly good picture of an
     * unclicked planner and no sign in the log that anything was skipped.
     *
     * AND IT OWES A VERDICT. A screenshot of an open window is equally consistent with the WRONG
     * window, so the screen asserts the panel by name and `ShotScreens.expectReport` makes
     * silence a failed run rather than a clean one -- `jei-keybind`'s discipline.
     */
    private static void openView(String arg, String label, String expected) {
        PlanView plan = fixture(arg);
        // ARMED, like `openTree` and for its reason: the tree and the TODO panel both draw an
        // icon per row, and a shot taken against `NodeActions.NONE` is a picture of the icon
        // column being charged and never filled.
        armNodeActions(plan.tree());
        PlannerScreen.openPlan(plan, book(plan));
        ShotScreens.expectReport(expected);
        ShotScreens.holdCapture(new ClickViewButton(label, expected));
    }

    /**
     * The planner panel that is on screen right now, or null if there is not one yet.
     *
     * `ModularScreen.getCurrent()` AND NOT `Minecraft.currentScreen`, WHICH DOES NOT COMPILE HERE.
     * `ClientGUI.open` displays a `GuiScreenWrapper` around the `ModularScreen`, so the vanilla
     * field holds the wrapper and the obvious route is to cast it to `IMuiScreen` and ask. That
     * interface extends `com.cleanroommc.neverenoughanimations.api.IAnimatedScreen`, which is not
     * on this project's compile classpath -- NEA is not one of the five staged dev jars -- and
     * javac refuses the cast with "cannot access IAnimatedScreen" rather than anything that names
     * the real problem. Measured while writing this screen. ModularUI's own static accessor needs
     * no unwrap and no dependency.
     *
     * NULL IS A NORMAL ANSWER AND NOT AN ERROR. `openPlan` hands `ClientGUI` a BUILDER, so the
     * panel does not exist until the screen builds its UI, and a `Hold` polling on the first
     * render tick can legitimately arrive first. The caller treats null as "come back next poll",
     * which is `ScrollToRow`'s shape.
     *
     * `getMainPanel` AND NOT `getTopMostPanel`, deliberately. The whole point of these two screens
     * is that a sub-panel comes UP over the planner, so the top-most panel is the thing being
     * measured -- reading the buttons off it after the click would find them on whatever opened.
     */
    private static ModularPanel livePanel() {
        ModularScreen screen = ModularScreen.getCurrent();
        return screen == null ? null : screen.getPanelManager().getMainPanel();
    }

    /**
     * Park the real cursor on one view button, click what is under it, and check what opened.
     *
     * THE CLICK IS ONLY DELIVERED IF THE FRAMEWORK AGREES SOMETHING IS THERE, and that is the
     * whole difference between this and a layout test. `PlannerLayoutTest` calls `onMousePressed`
     * on a button it found by walking the widget tree, which passes whether or not the button is
     * anywhere a mouse can reach -- that is stated on `theCaveatsClickTargetCoversItsLines`, and
     * it is why that test exists at all. Here the cursor is sent to where the LAYOUT says the
     * button is, and the click happens only if `IWidget.isHovering()` -- ModularUI's own verdict,
     * from `getWidgetsAt` rather than from these coordinates -- says the cursor landed on it. Two
     * answers from two different pieces of code, which is what `flow-hit` established the mouse
     * can do on an Xvfb display.
     *
     * READ AFTER A WAIT AND NEVER ON THE POLL THAT MOVED, which is `flow-hit`'s measured lesson
     * and not caution: the hover pass runs from the cursor position sampled at the start of a
     * tick, so `isHovering` lags a move by at least one frame. At one frame's gap that probe
     * reported ModularUI answering for the PREVIOUS node on every line -- six tidy DISAGREE lines
     * that were really six agreements, one row out of step.
     *
     * THE OTHER BUTTONS ARE CHECKED TOO, and that is the assertion that makes this discriminate.
     * "The cursor is over a button" is satisfied by being over the WRONG button, and a shot of
     * the TODO panel is a fine picture of a successful run of the flow screen. So the cursor must
     * be over the button asked for and over no other.
     */
    /**
     * Park the real cursor inside a tree row and report what the running client did about it.
     *
     * ONE HOLD FOR BOTH SCREENS, differing only in which child of the row it aims at and which
     * question it then asks. See {@link #openHover} and {@link #openMachineTip}.
     *
     * RE-PARKED EVERY POLL, WHICH IS `ClickViewButton`'S MEASURED RULE AND NOT CAUTION. The hover
     * pass runs from the cursor position sampled at the START of a tick, so an answer read on the
     * poll that moved the mouse describes where it used to be -- `flow-hit` published six tidy
     * DISAGREE lines that were really six agreements, one row out of step, before that was
     * understood. Re-parking to the SAME point every poll makes the rule harmless: the previous
     * position and the current one are the same position.
     */
    private static final class ParkOnTreeRow implements ShotScreens.Hold {

        /**
         * Polls before the hover answer is worth reading. `flow-hit` measured 5.
         *
         * FIVE AND NOT ONE, AND THAT IS A MEASUREMENT RATHER THAN A MARGIN. One frame's gap is
         * what produced `flow-hit`'s six off-by-one DISAGREE lines; `ClickViewButton` records
         * that waiting longer than this does not rescue a flag that is not going to set, so a
         * failure here after five polls is a real answer and not an impatient one.
         */
        private static final int SETTLE_POLLS = 5;

        /**
         * Polls before the TOOLTIP is read, which is longer, and for a different reason.
         *
         * `ITooltip.tooltipShowUpTimer` EXISTS, so a tooltip can be resolved and not yet drawn.
         * The verdict below reads the RESOLUTION, which the timer does not gate -- but the PNG is
         * half the artifact and the picture wants the box actually on screen, so this holds well
         * past any plausible default rather than reading at the earliest moment that would pass.
         * Twenty render ticks against a boot is free.
         */
        private static final int TOOLTIP_POLLS = 20;

        private final PlanView plan;

        private final boolean onMachineCell;

        private int poll;

        ParkOnTreeRow(PlanView plan, boolean onMachineCell) {
            this.plan = plan;
            this.onMachineCell = onMachineCell;
        }

        /** What this screen calls itself in the log and in its verdict. */
        private String screen() {
            return onMachineCell ? "planner-machine-tip" : "planner-hover";
        }

        @Override
        public boolean busy() {
            ModularPanel panel = livePanel();
            if (panel == null) {
                return true;
            }
            ListWidget<?, ?> list = firstList(panel);
            if (list == null || list.getArea().h() <= 0) {
                // NOT LAID OUT YET, which is `ScrollToRow`'s shape: the run's own timeout is the
                // backstop rather than a frame count nobody can justify.
                return true;
            }
            List<PlannerWidgets.ClickableGroup> rows = treeRows(list);
            if (rows.size() != plan.flatten().size()) {
                // THE INDEX-TO-NODE MAPPING IS THE ONLY WAY THIS SCREEN CAN NAME THE ROW IT
                // PARKED ON, and it rests on the tree being built in `flatten()` order --
                // `firstChancedRow`'s javadoc states that they are the same walk and that the
                // comment is the reason to keep them so. If a wrapper ever sits between the list
                // and its rows this count is what says so, rather than the log naming the wrong
                // item with total confidence.
                ShotScreens.reportFail(screen() + ": the tree has " + rows.size()
                                       + " clickable rows against " + plan.flatten().size()
                                       + " nodes, so a row cannot be named by index");
                return false;
            }
            int index = firstVisibleRowWithAMachineCell(rows, list);
            if (index < 0) {
                ShotScreens.reportFail(screen() + ": no row in the tree's viewport carries a"
                                       + " machine cell, so there is nothing to park on. Shoot a"
                                       + " fixture whose first rows run in machines --"
                                       + " `plan-truncated` does.");
                return false;
            }
            PlannerWidgets.ClickableGroup row = rows.get(index);
            PlanNode node = plan.flatten().get(index);
            IWidget target = onMachineCell ? machineCell(row) : widestTextCell(row);
            if (target == null) {
                ShotScreens.reportFail(screen() + ": row " + index + " ("
                                       + NodeRowText.label(node) + ") has no "
                                       + (onMachineCell ? "machine cell" : "text cell")
                                       + " to aim at");
                return false;
            }
            Area box = target.getArea();
            int targetX = box.x + box.width / 2;
            int targetY = box.y + box.height / 2;
            ScreenCursor.park(targetX, targetY);
            if (poll == 0) {
                ShotHarness.log(screen() + ": parking the cursor on row " + index + " -- "
                                + NodeRowText.label(node) + " -- at its "
                                + (onMachineCell ? "machine cell" : "label") + " " + targetX + ","
                                + targetY + " (cell box " + box.x + "," + box.y + " " + box.width
                                + "x" + box.height + "; row box " + row.getArea().x + ","
                                + row.getArea().y + " " + row.getArea().width + "x"
                                + row.getArea().height + "); machine words "
                                + NodeRowText.machineTooltipLines(node));
            }
            poll++;
            if (poll < (onMachineCell ? TOOLTIP_POLLS : SETTLE_POLLS)) {
                return true;
            }
            return onMachineCell ? tooltipVerdict(panel, row, node, targetX, targetY)
                                 : hoverVerdict(panel, row, node, targetX, targetY);
        }

        /**
         * Did this row paint the hover band on the frame about to be captured?
         *
         * ASKED OF `rowWash` WITH THE LIVE FLAG, which is the only form of this question that is
         * about the running client. `PlannerLayoutTest` already sweeps all four wash states, so
         * asserting the arithmetic again would add nothing; what no test can supply is a true
         * `isHovering()`, and that is exactly what this hands it.
         *
         * AND IT MUST NOT BE THE SELECTION WASH, which is the assertion that makes this
         * discriminate rather than merely be non-zero. Nothing selects a row on this screen, but
         * a future change that painted the selection wash on hover would satisfy "something was
         * drawn" while destroying the distinction `HOVER_FILL`'s javadoc argues for -- a hue on a
         * cursor makes the panel look as though it were reporting a status about whatever row the
         * pointer rests on. Compared against `rowWash`'s own answer for the selected case, so no
         * colour constant has to be exposed to ask it.
         */
        private boolean hoverVerdict(ModularPanel panel, PlannerWidgets.ClickableGroup row,
                                     PlanNode node, int targetX, int targetY) {
            boolean hovering = row.isHovering();
            int wash = PlannerWidgets.ClickableGroup.rowWash(hovering, row.drawsAsSelected());
            int selectionWash = PlannerWidgets.ClickableGroup.rowWash(false, true);
            // READ ONCE, LIKE `tooltipVerdict`, and for the same reason: `hovered` is replaced
            // wholesale each frame. Nothing in this verdict depends on it -- the wash is the
            // claim -- so an unreadable set prints its cause in the diagnostic and changes
            // nothing else, which is why there is no availability branch here.
            HoveredWidgets.Hovered hovered =
                    HoveredWidgets.underCursor(ModularScreen.getCurrent());
            ShotHarness.log(screen() + ": after " + poll + " polls on "
                            + NodeRowText.label(node) + " -- isHovering=" + hovering
                            + " drawsAsSelected=" + row.drawsAsSelected()
                            + " wash=0x" + Integer.toHexString(wash)
                            + " selectionWash=0x" + Integer.toHexString(selectionWash)
                            + " anyHovered=" + panel.isAnyHovered()
                            + " belowMouse="
                            + panel.getScreen().getPanelManager().isBelowMouseInTopPanel(row)
                            + " resolved=[" + hoveredDiagnostic(hovered) + "] ["
                            + cursorDiagnostic(targetX, targetY) + "]");
            if (wash == 0) {
                // THE PREDICTION THIS SCREEN EXISTS TO TEST, STATED IN THE FAILURE. `ClickViewButton`
                // measured `isHovering()` never setting for a view-bar button and diagnosed it as
                // the button's parent not being an `IViewport`; a tree row's parent IS one, so this
                // failing means either that diagnosis is wrong or the band does not draw at all.
                // Both are findings and neither is a flaky run, so it says which to go and read.
                ShotScreens.reportFail(screen() + ": the row under the cursor painted no wash, so"
                                       + " #333's hover band does not draw in game. isHovering="
                                       + hovering + "; if that is false, `ClickViewButton`'s"
                                       + " IViewport diagnosis needs re-reading -- a tree row's"
                                       + " parent is a ListWidget, which IS one.");
                return false;
            }
            if (wash == selectionWash) {
                ShotScreens.reportFail(screen() + ": a hovered row painted the SELECTION wash,"
                                       + " which makes a cursor look like a status; see"
                                       + " HOVER_FILL's javadoc");
                return false;
            }
            ShotScreens.reportPass();
            return false;
        }

        /**
         * Did ModularUI resolve a tooltip for the cell under the cursor, carrying our words?
         *
         * READ OFF THE SET ModularUI RESOLVED AND NOT OFF THE CELL WE AIMED AT. Asking the cell
         * whether it has a tooltip is a question about the widget tree and was green before this
         * screen existed. This asks the set ModularUI itself resolves from the live cursor -- the
         * same one the tooltip renderer draws from -- so a pass means the render path found it.
         *
         * THAT SET ARRIVES FROM {@link HoveredWidgets} AND NEVER FROM
         * `ModularGuiContext.getAllHovered()`. This javadoc recommended that call by name until
         * #333's fix, and it is the sentence a later author would have followed straight back
         * into the `StackOverflowError` -- the call reads as ordinary ModularUI API, compiles
         * fine, and kills the client on exhaustion. `HoveredWidgetsTest` bans the CALL; nothing
         * can ban prose recommending it, so the prose is corrected here rather than left as the
         * one unguarded route back to the crash.
         *
         * THEN THE WORDS, COMPARED AGAINST `NodeRowText`. `RichTooltip` exposes no line reader,
         * but the `RichText` it wraps does (`getAsStrings`), reached through `getRichText()` and
         * an instanceof-guarded cast -- guarded so a future ModularUI that returns something else
         * downgrades this to "could not read the words" instead of throwing inside a shot. The
         * comparison is what makes it end to end: the strings ModularUI is about to draw are the
         * strings `machineTooltipLines` produced, which is #333's whole words-live-in-NodeRowText
         * discipline asserted through the renderer for the first time.
         */
        private boolean tooltipVerdict(ModularPanel panel, PlannerWidgets.ClickableGroup row,
                                       PlanNode node, int targetX, int targetY) {
            List<String> expected = NodeRowText.machineTooltipLines(node);
            HoveredWidgets.Hovered hovered =
                    HoveredWidgets.underCursor(ModularScreen.getCurrent());
            com.cleanroommc.modularui.screen.RichTooltip resolved = resolvedTooltip(hovered);
            ShotHarness.log(screen() + ": after " + poll + " polls on "
                            + NodeRowText.label(node) + " -- expected " + expected
                            + " resolvedTooltip=" + (resolved != null)
                            + " rowHovering=" + row.isHovering()
                            + " anyHovered=" + panel.isAnyHovered()
                            + " resolved=[" + hoveredDiagnostic(hovered) + "] ["
                            + cursorDiagnostic(targetX, targetY) + "]");
            if (expected.isEmpty()) {
                ShotScreens.reportFail(screen() + ": " + NodeRowText.label(node) + " has no"
                                       + " machine words, so this row cannot show a tooltip and"
                                       + " the row search picked wrong");
                return false;
            }
            String unmeasurable = unmeasurableProblem(hovered.available(), hovered.why());
            if (unmeasurable != null) {
                ShotScreens.reportFail(screen() + ": " + unmeasurable);
                return false;
            }
            if (resolved == null) {
                // THE DEFECT THIS SCREEN EXISTS TO CATCH, AND IT IS NOT COSMETIC. Since #333 the
                // tooltip is the only place a tree row names its machine.
                ShotScreens.reportFail(screen() + ": nothing under the cursor resolved a tooltip,"
                                       + " so the machine's name is not reachable in game at all"
                                       + " -- #333 moved it out of the row's text and into this");
                return false;
            }
            if (resolved.isEmpty()) {
                ShotScreens.reportFail(screen() + ": the resolved tooltip is EMPTY, so a box will"
                                       + " be drawn with none of " + expected + " in it");
                return false;
            }
            List<String> words = tooltipWords(resolved);
            ShotHarness.log(screen() + ": the resolved tooltip carries " + words);
            if (words == null) {
                // NOT A FAILURE, AND SAYING SO IS THE POINT. The tooltip resolved and is not
                // empty, which is the claim; only the read-back is unavailable, and downgrading
                // to a pass with the reason logged is honest where failing would blame the mod
                // for a ModularUI internal this screen has no contract with.
                ShotHarness.log(screen() + ": could not read the words back -- getRichText() is"
                                + " not a RichText in this ModularUI build, so the PNG is the"
                                + " only witness to WHICH words. The tooltip itself resolved.");
                ShotScreens.reportPass();
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!containsLine(words, expected.get(i))) {
                    ShotScreens.reportFail(screen() + ": the resolved tooltip is missing \""
                                           + expected.get(i) + "\"; it carries " + words
                                           + " against NodeRowText's " + expected);
                    return false;
                }
            }
            ShotScreens.reportPass();
            return false;
        }

        /**
         * The first tooltip ModularUI resolves under the live cursor, or null.
         *
         * THE WHOLE RESOLVED STACK RATHER THAN `getTopHovered()`, because the top of the stack is
         * the innermost widget and the tooltip may belong to it OR to a parent --
         * `AbstractParentWidget` overrides `getTooltip` precisely so a hovered child can answer
         * for its parent. Walking the whole resolved stack asks the question the renderer asks.
         *
         * THE STACK ARRIVES FROM {@link HoveredWidgets} AND NOT FROM `getAllHovered()`, WHICH
         * CRASHES THE CLIENT. Read that class before reaching for the obvious call: in
         * `modularui-3.1.5` the iterator behind `getAllHovered()` recurses into itself instead of
         * ending, so this loop -- which runs to exhaustion whenever nothing under the cursor has
         * a tooltip, i.e. exactly the defect this screen exists to catch -- died with a
         * `StackOverflowError` in `ModularGuiContext$1.computeNext`.
         *
         * `hasTooltip()` BEFORE `getTooltip()`, because the getter is documented on `ITooltip` as
         * the builder entry point and creates one on demand; calling it first would manufacture
         * the very thing this is checking for and pass on every row in the game.
         */
        private static com.cleanroommc.modularui.screen.RichTooltip resolvedTooltip(
                HoveredWidgets.Hovered stack) {
            for (Object hovered : stack.elements()) {
                if (hovered instanceof com.cleanroommc.modularui.api.widget.ITooltip
                        && ((com.cleanroommc.modularui.api.widget.ITooltip<?>) hovered)
                                .hasTooltip()) {
                    return ((com.cleanroommc.modularui.api.widget.ITooltip<?>) hovered)
                            .getTooltip();
                }
            }
            return null;
        }

        /**
         * Every widget ModularUI says the cursor is over, for a log line that can be argued with.
         *
         * SAYS WHY WHEN THERE IS NO ANSWER instead of printing "nothing", because those two
         * differ by everything: one is a cursor over empty space and the other is a probe that
         * has gone blind. Reading "nothing" off a blind probe is what would turn this diagnostic
         * into the misleading half of a failure report.
         */
        private static String hoveredDiagnostic(HoveredWidgets.Hovered stack) {
            if (!stack.available()) {
                return "unreadable: " + stack.why();
            }
            StringBuilder sb = new StringBuilder();
            for (Object hovered : stack.elements()) {
                if (sb.length() > 0) {
                    sb.append(" > ");
                }
                sb.append(hovered.getClass().getSimpleName());
                if (hovered instanceof IWidget) {
                    Area box = ((IWidget) hovered).getArea();
                    sb.append('@').append(box.x).append(',').append(box.y)
                      .append(' ').append(box.width).append('x').append(box.height);
                }
                if (hovered instanceof com.cleanroommc.modularui.api.widget.ITooltip
                        && ((com.cleanroommc.modularui.api.widget.ITooltip<?>) hovered)
                                .hasTooltip()) {
                    sb.append("(tip)");
                }
            }
            return sb.length() == 0 ? "nothing" : sb.toString();
        }
    }

    /**
     * The strings a resolved tooltip will draw, or null when this build will not say.
     *
     * SEE `ParkOnTreeRow.tooltipVerdict` for why the cast is guarded rather than assumed.
     */
    private static List<String> tooltipWords(
            com.cleanroommc.modularui.screen.RichTooltip tooltip) {
        Object rich = tooltip.getRichText();
        if (!(rich instanceof com.cleanroommc.modularui.drawable.text.RichText)) {
            return null;
        }
        List<String> out = new java.util.ArrayList<String>();
        for (Object line : ((com.cleanroommc.modularui.drawable.text.RichText) rich)
                .getAsStrings()) {
            out.add(String.valueOf(line));
        }
        return out;
    }

    /**
     * Whether `words` holds `wanted`, allowing the renderer to have wrapped or padded it.
     *
     * `contains` AND NOT `equals`, DELIBERATELY, and the looseness is bounded by the caller: the
     * assertion is that every line `NodeRowText` produced is present, which is what "the tooltip
     * carries our words" means. A `RichText` line is a rendered run and may carry formatting or a
     * wrap that an equality test would fail on while the box on screen reads perfectly -- and a
     * shot that cried wolf on a wrap would be turned off, which is worse than one that is exact.
     */
    private static boolean containsLine(List<String> words, String wanted) {
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i) != null && words.get(i).contains(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tree's rows, in display order, which is `plan.flatten()` order. See `ParkOnTreeRow`.
     *
     * DIRECT CHILDREN AND NOT A DEEP WALK, because a deep walk would also collect the
     * `ClickableGroup` a diagram node is built from if one ever appeared under this list, and the
     * caller's index-to-node mapping would then be silently off. The caller cross-checks the count
     * against the node count, so a wrapper appearing between the list and its rows fails loudly.
     */
    private static List<PlannerWidgets.ClickableGroup> treeRows(ListWidget<?, ?> list) {
        List<PlannerWidgets.ClickableGroup> rows =
                new java.util.ArrayList<PlannerWidgets.ClickableGroup>();
        for (IWidget child : list.getChildren()) {
            if (child instanceof PlannerWidgets.ClickableGroup) {
                rows.add((PlannerWidgets.ClickableGroup) child);
            }
        }
        return rows;
    }

    /**
     * The first row wholly inside the tree's viewport that carries a machine cell, or -1.
     *
     * WHOLLY INSIDE, because a row clipped by the viewport edge is a row the cursor can be parked
     * on the invisible half of. `plan-truncated`'s root runs in a Centrifuge, so this is row 0
     * there and no scrolling is needed -- which is why neither screen scrolls, unlike
     * `planner-yield`. Written as a search anyway so a fixture whose first rows are hand-crafted
     * gets a picture instead of a failure.
     */
    private static int firstVisibleRowWithAMachineCell(
            List<PlannerWidgets.ClickableGroup> rows, ListWidget<?, ?> list) {
        for (int i = 0; i < rows.size(); i++) {
            Area box = rows.get(i).getArea();
            if (box.h() <= 0 || box.y() < list.getArea().y()
                    || box.ey() > list.getArea().ey()) {
                continue;
            }
            if (machineCell(rows.get(i)) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The machine cell in a tree row, or null. Found by CARRYING A TOOLTIP.
     *
     * BY THE TOOLTIP AND NOT BY TYPE, because `PlannerWidgets.Icon` is package-private and this
     * class is not in that package -- and the property is the better discriminator anyway. The
     * machine cell is the only tooltipped widget the planner builds: #333's spec put P8's
     * tooltips-on-everything pass explicitly out of scope, so "has a tooltip" means "is the
     * machine cell" today. `theOnlyTooltipInATreeRowIsTheMachineCell` is what keeps that true --
     * without it, P8 landing would silently point this screen at a label.
     */
    private static IWidget machineCell(PlannerWidgets.ClickableGroup row) {
        for (IWidget child : row.getChildren()) {
            if (child instanceof com.cleanroommc.modularui.api.widget.ITooltip
                    && ((com.cleanroommc.modularui.api.widget.ITooltip<?>) child).hasTooltip()) {
                return child;
            }
        }
        return null;
    }

    /**
     * The widest text cell in a row, which is its label. See `ParkOnTreeRow`.
     *
     * BY WIDTH AND NOT BY CHILD ORDER, which is the lesson `PlannerLayoutTest`'s collision helper
     * learned at #333: the right cluster is added AFTER the label, so "the cell after the
     * quantity" became the badge and the helper started reporting `[NEED]` as every row's label.
     * A width cannot drift that way.
     */
    private static IWidget widestTextCell(PlannerWidgets.ClickableGroup row) {
        IWidget widest = null;
        for (IWidget child : row.getChildren()) {
            if (!(child instanceof com.cleanroommc.modularui.widgets.TextWidget)) {
                continue;
            }
            if (widest == null || child.getArea().w() > widest.getArea().w()) {
                widest = child;
            }
        }
        return widest;
    }

    /**
     * Where a probe thinks everything is, for when the probe itself is the thing wrong.
     *
     * THE RAW LWJGL PAIR IS PART OF THE LINE, not a redundant echo of the gui one. This is
     * `FlowCanvas.cursorDiagnostic`'s argument and it earned its place here on the first run:
     * "the raw y is what I set and the gui y is the mirror of it" is the reading that names
     * the conversion rather than the panel, and without it a false negative is unattributable.
     *
     * AT FILE SCOPE SINCE #333, BECAUSE A SECOND CURSOR PROBE ARRIVED. It was private to
     * `ClickViewButton`, and `ParkOnTreeRow` needs exactly the same line for exactly the same
     * reason -- a park that reports a false negative is unattributable without it. Java would
     * have let the second class reach into the first one's private, which compiles and reads as
     * though the two probes are related; they are not, they just both move a mouse.
     */
    private static String cursorDiagnostic(int targetX, int targetY) {
        int scale = ScreenCursor.scale();
        return "asked=" + targetX + "," + targetY
                + " mouseRaw=" + org.lwjgl.input.Mouse.getX() + ","
                + org.lwjgl.input.Mouse.getY()
                + " scale=" + scale
                + " gui=" + ScreenCursor.guiX() + "," + ScreenCursor.guiY()
                + " screen=" + ScreenCursor.guiWidth() + "x" + ScreenCursor.guiHeight()
                + " display=" + ScreenCursor.displayHeight();
    }

    private static final class ClickViewButton implements ShotScreens.Hold {

        /**
         * The earliest poll on which `isHovering` is worth reading. `flow-hit` measured 5.
         *
         * READ-THEN-MOVE, AND THE ANSWER ON ANY POLL BELONGS TO THE PREVIOUS POSITION. The hover
         * pass runs from the cursor position sampled at the start of a tick, so a read taken on
         * the poll that moved the mouse describes where it used to be -- `flow-hit` published six
         * tidy DISAGREE lines that were really six agreements, one row out of step, before that
         * was understood. Re-parking to the SAME point every poll is what makes the rule harmless
         * here: the previous position and the current one are the same position.
         */
        private static final int SETTLE_POLLS = 5;

        /**
         * Polls between the click and asking what opened.
         *
         * THE PANEL IS ADDED IMMEDIATELY AND DRAWN LATER, so this is about the PICTURE rather
         * than about the answer: `isPanelOpen` would be true on the next poll, and a capture
         * taken then can still be of a panel that has not had a layout pass. `ScrollToRow` holds
         * eight polls for the same kind of reason, and both are cheap against a boot.
         */
        private static final int OPEN_POLLS = 4;

        /**
         * The label of the button to park on, resolved to a position through
         * {@link PlannerWidgets#VIEW_LABELS}.
         *
         * BY LABEL AND NOT BY INDEX, WHICH IS `SHOW_DIAGRAM`'S OWN STATED CONTRACT and was broken
         * here: this class took a hand-written 0 and 1 while logging
         * `VIEW_LABELS.get(index)`, so the day a third view is inserted the probe parks its cursor
         * on the wrong button and reports the right NAME. That is precisely the drift that javadoc
         * says "cannot" happen because the harness looks the button up by label -- and
         * `FlowInteractionShot` does, and this did not. `indexOf` in the measured vocabulary is the
         * form that goes red instead of lying.
         */
        private final String label;
        private final String expected;

        /** The panel the buttons were read off, kept only so the verdict can reach its screen. */
        private ModularPanel panel;

        private int poll;

        /** Set once the click has been delivered; {@link #poll} then counts the open wait. */
        private boolean clicked;

        ClickViewButton(String label, String expected) {
            this.label = label;
            this.expected = expected;
        }

        /**
         * Where this label sits in the view bar, or -1 when the vocabulary does not hold it.
         *
         * -1 IS A CALLER BUG AND IS REPORTED RATHER THAN CLAMPED. A missing label means somebody
         * renamed a button without renaming its shot, and the honest outcome is a run that times
         * out with its verdict still owed -- see `busy()`.
         */
        private int index() {
            return PlannerWidgets.VIEW_LABELS.indexOf(label);
        }

        @Override
        public boolean busy() {
            ModularPanel current = livePanel();
            if (current != null) {
                this.panel = current;
            }
            List<PlannerWidgets.ClickableGroup> buttons =
                    panel == null ? new java.util.ArrayList<PlannerWidgets.ClickableGroup>()
                                  : viewButtons(panel);
            int index = index();
            if (index < 0) {
                // LOUDLY, AND EVERY POLL IS FINE HERE: the run is going to time out anyway and a
                // silent wait for a button that cannot exist is the failure `flow-hit`'s
                // "a skipped probe that says nothing is a probe that passes" names.
                ShotHarness.log("planner-view: '" + label + "' is not in VIEW_LABELS "
                                + PlannerWidgets.VIEW_LABELS + " -- a button was renamed and its"
                                + " shot was not");
                return true;
            }
            if (buttons.size() <= index || buttons.get(index).getArea().height <= 0) {
                // NOT LAID OUT YET, or the bar is not there at all. Both are "come back next
                // poll" and the run's own timeout is the backstop -- `ScrollToRow`'s shape,
                // rather than a frame count nobody can justify. A bar that never appears fails
                // as a timeout with the verdict still owed, which is the honest outcome.
                return true;
            }
            PlannerWidgets.ClickableGroup button = buttons.get(index);
            if (clicked) {
                if (poll < OPEN_POLLS) {
                    poll++;
                    return true;
                }
                return verdict(label);
            }

            // RE-PARKED EVERY POLL, WHICH IS `ScrollToRow`'S FIX ONE AXIS OVER. That screen
            // scrolled once, logged sane numbers, and photographed an unscrolled list; holding
            // and re-applying is what makes the last poll before the read a parked one whatever
            // swallowed the first attempt. It is idempotent -- the same point every time -- so
            // the read-then-move rule above stays satisfied.
            Area box = button.getArea();
            int targetX = box.x + box.width / 2;
            int targetY = box.y + box.height / 2;
            ScreenCursor.park(targetX, targetY);
            if (poll == 0) {
                ShotHarness.log("planner-view: parking the cursor on '" + label + "' at "
                                + targetX + "," + targetY + " (button box " + box.x + ","
                                + box.y + " " + box.width + "x" + box.height + "); clicking"
                                + " through the screen on poll " + SETTLE_POLLS);
            }
            poll++;
            if (poll < SETTLE_POLLS) {
                return true;
            }
            // DISPATCHED THROUGH THE SCREEN, WHICH IS THE CLICK PATH THE GAME USES.
            // `GuiScreenWrapper.mouseClicked` calls exactly this, and `ModularScreen` does its own
            // hit-testing from the live cursor to decide which widget receives it -- so the button
            // is reached BY POSITION and nothing here names the widget that gets the click.
            //
            // AND NOT GATED ON `IWidget.isHovering()`, WHICH THIS PROBE MEASURED UNRELIABLE HERE.
            // The first two runs parked the cursor dead centre of the button and polled that flag
            // forty times, about a second, and it never set: `asked=171,112 mouseRaw=342,576
            // scale=2 gui=171,112` against a laid-out box of `126,107 90x11`. The conversion round
            // trips and the point is inside the box, so the flag is not reporting the cursor.
            // `flow-hit` gets a true answer from the same flag, but its widgets sit inside a
            // `FlowCanvas`, which is an `IViewport` and walks its own children -- a plain
            // `ParentWidget` is not. Waiting longer for it was tried and does not help.
            //
            // THE VERDICT IS WHAT DISCRIMINATES, so nothing is lost by not gating: a click that
            // landed on the wrong button opens the wrong panel and {@link #verdict} fails on the
            // panel's NAME, and a click that landed on nothing opens nothing and fails too.
            // The hit-test answers are logged rather than asserted, because they are evidence
            // about the harness rather than about the mod.
            ModularScreen screen = panel.getScreen();
            ShotHarness.log("planner-view: dispatching a click at the cursor after " + poll
                            + " poll(s); ModularUI's own hit tests say belowMouse="
                            + screen.getPanelManager().isBelowMouseInTopPanel(button)
                            + " isHovering=" + button.isHovering()
                            + " anyHovered=" + panel.isAnyHovered()
                            + " otherButtons=" + othersHovered(buttons, button)
                            + " panelOpen=" + panel.isOpen() + " ["
                            + cursorDiagnostic(targetX, targetY) + "]");
            boolean taken = screen.onMousePressed(0);
            ShotHarness.log("planner-view: the screen " + (taken ? "took" : "did NOT take")
                            + " the click aimed at '" + label + "'");
            clicked = true;
            poll = 0;
            return true;
        }

        /** How many OTHER view buttons claim the cursor. Non-zero means it is on the wrong one. */
        private static int othersHovered(List<PlannerWidgets.ClickableGroup> buttons,
                                        PlannerWidgets.ClickableGroup button) {
            int count = 0;
            for (PlannerWidgets.ClickableGroup other : buttons) {
                if (other != button && other.isHovering()) {
                    count++;
                }
            }
            return count;
        }

        private boolean verdict(String label) {
            ModularScreen screen = panel.getScreen();
            boolean open = screen.isPanelOpen(expected);
            ModularPanel top = screen.getPanelManager().getTopMostPanel();
            String name = top == null ? "none" : top.getName();
            ShotHarness.log("planner-view: after clicking '" + label + "' there are "
                            + screen.getPanelManager().getOpenPanelCount() + " panel(s) open,"
                            + " the top one is '" + name + "', and '" + expected + "' is "
                            + (open ? "open" : "NOT open"));
            if (open && expected.equals(name)) {
                ShotScreens.reportPass();
            } else {
                // THE NAME IS THE ASSERTION. A panel being open somewhere is not the claim the
                // button makes, and the picture cannot tell the two apart -- `flow` and
                // `planner-todo` are both perfectly good photographs of a panel that opened for
                // a different reason.
                ShotScreens.reportFail("clicking '" + label + "' left '" + name + "' on top and"
                                       + " '" + expected + "' " + (open ? "merely open behind it"
                                       : "not open at all"));
            }
            return false;
        }
    }

    /**
     * The view bar's buttons, in the order they are drawn, or empty until the panel is laid out.
     *
     * FOUND BY TYPE RATHER THAN BY POSITION, for the reason `PlannerWidgets.ViewBar` exists: an
     * index into the panel's click targets is right until a third view is added, and then it
     * starts parking the cursor on a tree row while still reporting a button.
     */
    private static List<PlannerWidgets.ClickableGroup> viewButtons(IWidget parent) {
        List<PlannerWidgets.ClickableGroup> found =
                new java.util.ArrayList<PlannerWidgets.ClickableGroup>();
        PlannerWidgets.ViewBar bar = firstViewBar(parent);
        if (bar == null) {
            return found;
        }
        for (IWidget child : bar.getChildren()) {
            if (child instanceof PlannerWidgets.ClickableGroup) {
                found.add((PlannerWidgets.ClickableGroup) child);
            }
        }
        return found;
    }

    /** The panel's view bar, depth first. Null before layout or on a panel that has none. */
    private static PlannerWidgets.ViewBar firstViewBar(IWidget parent) {
        if (parent instanceof PlannerWidgets.ViewBar) {
            return (PlannerWidgets.ViewBar) parent;
        }
        for (IWidget child : parent.getChildren()) {
            PlannerWidgets.ViewBar found = firstViewBar(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static void openTodo(String arg) {
        PlanView plan = fixture(arg);
        // ARMED, LIKE `openTree`, AND FOR THE SAME REASON IT WAS MISSING HERE: the TODO panel
        // draws an icon per row now, and a shot taken against `NodeActions.NONE` is a picture
        // of the icon column being charged and never filled.
        armNodeActions(plan.tree());
        PlannerScreen.openPanel(PlannerWidgets.todoPanel(plan, book(plan), SHOT_ACTIONS));
    }

    /**
     * `planner-caveats`: the five sentences saying what this plan could not see.
     *
     * NO FIXTURE ARGUMENT, because the subject is not the plan. What this panel draws comes
     * from `ScenarioSource`, which answers for the RUNTIME rather than for a plan.
     *
     * SO IT PHOTOGRAPHS WHATEVER THE CLIENT INSTALLED, AND THAT IS #191's WORDINGS RATHER THAN
     * THE DECLARED CONSTANTS. An earlier version of this comment claimed the opposite -- "no
     * readers installed, so it photographs the declared constants" -- and the screenshot
     * disproved it: `ClientProxy` runs in this harness, `PlannerStock.install()` puts a reader
     * on `have`, and the picture came back reading "your ME network has not been read yet ...
     * open the planner again to ask". Which is the better subject, because it is what a player
     * with a working client actually sees. To photograph a declared constant you would have to
     * call `ScenarioSource.resetReaders()` first, and that is a different picture.
     */
    static void openCaveats(String arg) {
        PlannerScreen.openPanel(PlannerWidgets.caveatsPanel());
    }

    /** The fixture the two hover screens use when none is named. See {@link #openHover}. */
    private static final String HOVER_FIXTURE = "plan-truncated";

    /**
     * `planner-hover`: A REAL CURSOR ON A REAL TREE ROW, so #333's hover band has an artifact.
     *
     * <h2>THE TWO THINGS #333 SHIPPED THAT NO SCREEN COULD PHOTOGRAPH</h2>
     *
     * P5's band and the machine tooltip were the only parts of that pass with no picture, and the
     * reason is the same for both: they exist only while a cursor is somewhere. Every planner
     * screen before these opens a panel and captures it, and a captured panel has no cursor on
     * any row. `PlannerLayoutTest.theRowWashLayersHoverUnderSelectionAndNeitherWhenNeither` sweeps
     * all four wash states headlessly, which is worth having and is arithmetic:
     * `PlannerWidgets.ClickableGroup.rowWash`'s own javadoc records that `isHovering()` is false
     * in every test, so the one state the band exists for is the one a test cannot reach.
     *
     * <h2>AND THERE IS A MEASURED REASON TO DOUBT IT WORKS AT ALL, WHICH IS WHY THIS IS A PROBE</h2>
     *
     * `ClickViewButton` above records that `IWidget.isHovering()` NEVER SET for a view-bar button
     * -- forty polls, cursor dead centre of a laid-out box, coordinates round-tripping -- and its
     * diagnosis was that `flow-hit` gets a true answer only because its widgets sit inside a
     * `FlowCanvas`, which is an `IViewport` and walks its own children, "a plain `ParentWidget` is
     * not". The band is drawn from exactly that flag.
     *
     * SO THE PREDICTION IS THAT A TREE ROW HOVERS AND A VIEW BUTTON DOES NOT, and the difference
     * is structural rather than lucky: the view bar is a `ViewBar`, a plain `ParentWidget`, while
     * a tree row is a child of the tree's `ListWidget` -- and `ListWidget extends
     * AbstractScrollWidget implements IViewport`, the same interface that makes the canvas work.
     * Verified against `modularui-3.1.5`'s class hierarchy. If that is right this screen passes
     * and the earlier measurement is explained rather than merely recorded; if it is wrong, #333
     * shipped a band that never draws and THIS is the run that says so. Either way the verdict is
     * the artifact and the PNG is the corroboration.
     *
     * <h2>WHY IT DOES NOT PARK ON THE MACHINE CELL, WHICH IS THE OBVIOUS PLACE</h2>
     *
     * A tooltip would be drawn over the row. The band is a 9%-black wash behind a 388x10 row and
     * a `RichTooltip` is an opaque box next to the cursor, so a picture of the two together is a
     * picture of the tooltip. `planner-machine-tip` is the screen that wants that; this one parks
     * on the row's LABEL, which is inside the row and outside the only tooltipped cell it has.
     */
    static void openHover(String arg) {
        openParked(arg, false);
    }

    /**
     * `planner-machine-tip`: THE MACHINE TOOLTIP, RESOLVED BY MODULARUI AND READ BACK.
     *
     * <h2>THIS IS THE ONE CLAIM IN #333 THAT IS NOT COSMETIC</h2>
     *
     * The machine left `NodeRowText.meta` at #333, so the tooltip is now the ONLY place a tree row
     * says which machine a step runs in. Measured over the fixtures, 979 rows carry a machine name
     * and about 619 of them have a cell to hang it on -- so if the tooltip does not render, the
     * pass replaced a readable phrase with a sprite on all of them and nothing in the suite would
     * notice. `RichTooltip` is drawn under a GL context, so no headless test can reach it, and
     * before #333 this mod had ZERO uses of `ITooltip` -- the capability is real in the jar and had
     * never once been exercised here.
     *
     * <h2>THROUGH MODULARUI'S OWN RESOLUTION AND NOT THROUGH THE WIDGET WE PARKED ON</h2>
     *
     * The weak version of this probe asks the cell it aimed at whether it has a tooltip, which is
     * a question about the widget tree and would have been green before the cursor existed. The
     * verdict here instead asks {@link HoveredWidgets} for `ModularGuiContext`'s own `hovered`
     * list -- the set ModularUI itself resolves from the live cursor, and the one whose members
     * are the widgets whose `drawForeground` draws a tooltip -- for a widget carrying a tooltip,
     * and then reads the STRINGS back out of it and compares them against
     * `NodeRowText.machineTooltipLines`. That is the whole path: cursor to hit test to resolved
     * widget to tooltip to words, against the class that produced the words.
     *
     * VIA THAT CLASS AND NEVER VIA `ModularGuiContext.getAllHovered()`, which reads as the
     * obvious call and crashes the client -- read `HoveredWidgets` for the bytecode. The first
     * run of this screen in the 365-jar client died on it with a `StackOverflowError`.
     *
     * FAILS LOUDLY AND NEVER SILENTLY, which is the point of it being a verdict screen: a run
     * where nothing resolves reports NO and takes the build with it, rather than producing a
     * perfectly good photograph of a row with no tooltip on it.
     */
    static void openMachineTip(String arg) {
        openParked(arg, true);
    }

    /**
     * Why `planner-machine-tip` cannot put its claim to the client at all, or null when it can.
     *
     * A VERDICT SCREEN THAT CANNOT MEASURE MUST FAIL, AND THIS BRANCH ONCE RETURNED A PASS.
     * When `HoveredWidgets` cannot read `ModularGuiContext.hovered`, nothing has been
     * established -- no widget known to be hovered, no tooltip known to resolve, no words known
     * to exist -- so a green from here is a claim about nothing, on the one screen whose entire
     * purpose is the assertion it just skipped. {@link #openMachineTip}'s own javadoc already
     * promised this screen "FAILS LOUDLY AND NEVER SILENTLY", which the pass contradicted from
     * forty lines away.
     *
     * NOT THE SAME CASE AS `tooltipWords` RETURNING NULL, which is the analogy the pass was
     * argued from and it does not hold. There the tooltip HAS resolved and is non-empty, so this
     * screen's claim is proven and only WHICH strings it carries is unavailable, with the PNG as
     * the witness to those. Here the claim was never put to the client.
     *
     * AND THE PRECEDENT ONE FILE OVER IS THE SECOND HALF OF ITS RULE, NOT THE FIRST -- GETTING
     * THIS BACKWARDS WOULD JUSTIFY REINTRODUCING #330. `FlowInteractionShot`'s first version
     * FAILED when nothing was measurable, and that was the defect: on `plan-truncated` every
     * pair had an end clipped, so "'nothing measurable' was being reported as a failure of the
     * code" while the PNG showed the fix working. The correction was to NAVIGATE
     * (`navigateToAMeasurablePair`), not to switch a pass to a failure. What it still fails on is
     * the residual case that no navigation can reach -- a pair needing 916px in a 612px canvas --
     * which "fails loudly with the numbers".
     *
     * AN UNREADABLE HOVER SET IS THAT RESIDUAL CASE, which is why this is a failure and not a
     * navigation step. There is no cursor move, no extra poll and no zoom that makes a renamed
     * or retyped field readable, so there is nothing to navigate to and nothing to report but NO.
     * A guard that CAN reach a measurable state and declines to is still the #330 mistake; do not
     * cite this method for that.
     *
     * AND IT DOES NOT BLAME #333 FOR A ModularUI INTERNAL, which was the objection.
     * `HoveredWidgetsTest` pins the field at SUITE time, so a rename cannot reach a shot while
     * the suite is green; if this fires anyway, the ModularUI the client loaded is not the one
     * the suite pinned. The message says so rather than leaving a reader to guess.
     *
     * PRIMITIVES RATHER THAN THE {@link HoveredWidgets.Hovered} ITSELF, for the reason
     * `PlannerRecoveryShot.progressProblem` takes six of them: a guard that a shot exercises
     * only when a dependency has already broken is otherwise read-off-the-code forever, and the
     * last unexercised guard in this package had been wired to invert its own signal. Taking
     * booleans lets `PlannerShotTest` drive both directions with no live screen. The seam
     * between this and its one caller is covered separately, by asserting the call is installed.
     *
     * @return why the run proves nothing, or null if it can prove what it claims
     */
    static String unmeasurableProblem(boolean hoverSetRead, String why) {
        if (hoverSetRead) {
            return null;
        }
        return "could not read what ModularUI resolved, so this screen measured NOTHING -- "
               + why + ". The tooltip may well render; this run cannot say either way, and that"
               + " is a failure rather than a pass. Read `HoveredWidgets`, which reads"
               + " `ModularGuiContext.hovered` because `getAllHovered()` cannot be iterated to"
               + " the end in modularui-3.1.5. A GREEN `HoveredWidgetsTest` alongside this"
               + " failure means the client's ModularUI is not the one the suite pinned, so"
               + " compare the pack's jar against deps/.";
    }

    /**
     * Open a tree and hand the hold a row to park on. Shared by the two screens above.
     *
     * ONE OPENER FOR BOTH, because everything except the park point and the verdict is identical
     * and a second copy of the panel-building line is a second thing to forget `schemaCheck` in --
     * which `openCollision`'s own comment records happening once already.
     */
    private static void openParked(String arg, boolean onMachineCell) {
        PlanView plan = fixture(arg == null || arg.trim().isEmpty() ? HOVER_FIXTURE : arg);
        armNodeActions(plan.tree());
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book(plan),
                                                        PlannerScreen.schemaCheck(), SHOT_ACTIONS);
        PlannerScreen.openPanel(panel);
        ShotScreens.expectReport(onMachineCell ? "planner-machine-tip" : "planner-hover");
        ShotScreens.holdCapture(new ParkOnTreeRow(plan, onMachineCell));
    }

    /**
     * A book with something in it, since an empty TODO panel proves only that it opens.
     *
     * ROWS FROM THE PLAN FIRST, WHICH IS THE ONLY WAY A ROW GETS ON THIS LIST IN GAME: "Add to
     * TODO" in the node menu sends `node.key()` and nothing else writes it. A book of keys the
     * plan has never heard of is not the usual case, it is the leftover case -- and while it was
     * the ONLY case this screen photographed, the shot could not show a resolved display name
     * at all, so the panel printing raw keys looked like the only thing it could do.
     *
     * THE THREE HAND-WRITTEN KEYS STAY, and one of them is the point: nothing in the fixture set
     * names `thaumadditions:vis_pod#0116bb2287a7`, so it is the row that exercises the fallback
     * to the key. Keeping it means one picture shows both the fix and what happens when the
     * plan cannot help. The discriminated key is also the shape that overflows a row, and
     * 934,400 mB of water is a real Borax draw.
     */
    private static PlanBook book(PlanView plan) {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        for (PlanNode node : plan.flatten()) {
            book.setTodo(node.key(), node.need());
            if (book.todoKeys().size() >= PLAN_TODO_ROWS) {
                break;
            }
        }
        book.setTodo("nuclearcraft:borax", 64L);
        book.setTodo("fluid:water", 934_400L);
        book.setTodo("thaumadditions:vis_pod#0116bb2287a7", 3L);
        return book;
    }

    /**
     * How many rows the shot's book takes from the plan.
     *
     * Three, so the panel is not all TODO and no shopping list: the "still needed" half below
     * is the other thing the screen is for, and `TODO_MAX_LIST_HEIGHT` scrolls at sixteen rows.
     */
    private static final int PLAN_TODO_ROWS = 3;

    /**
     * The tree to draw: a fixture, or `synthetic:<n>` for a plan of a chosen size.
     *
     * THE GATE IS 4,000 NODES AND THE LARGEST FIXTURE IS 634. `DEFAULT_MAX_NODES` is 4,000,
     * so that is the size the 60 fps claim has to be made at, and no real solved plan in the
     * fixture set comes close. A generated tree is honest subject matter for a PERFORMANCE
     * measurement in a way it would not be for a rendering one: what costs frames is the node
     * count and the geometry, and both are real here. Every visual claim in this package is
     * still made against a fixture.
     */
    private static PlanNode flowTree(String arg) {
        return flowPlan(arg).tree();
    }

    /**
     * The same, as the PLAN the flow panel now needs. #327.
     *
     * A PLAN AND NOT A TREE BECAUSE THE PANEL DRAWS THE PLAN'S WARNINGS. `PlannerWidgets.flowPanel`
     * takes a `PlanView` since #327 -- the truncation line and the overruled pins go above the
     * diagram, and the canvas is sized to what is left -- so a synthetic tree has to arrive wearing
     * one. It carries the node count it really has rather than a zero, so `NodeRowText.heading`
     * and the truncation check describe the picture instead of describing nothing.
     *
     * `truncated(false)` IS THE HONEST VALUE AND NOT A DEFAULT. A synthetic tree is exactly the
     * size it was asked for -- `syntheticIsExactAtEverySizeAndAlwaysHasOneRoot` counts it at 600
     * sizes -- so it is not a plan the solver gave up on, and claiming otherwise would put a
     * warning on a performance shot that is measuring frames rather than reporting a plan.
     */
    private static PlanView flowPlan(String arg) {
        String wanted = arg == null ? "" : arg.trim();
        if (!wanted.startsWith("synthetic:")) {
            return fixture(wanted);
        }
        int nodes = Integer.parseInt(wanted.substring("synthetic:".length()));
        PlanNode tree = synthetic(nodes);
        return new PlanView.Builder()
                .target("synthetic:node" + (nodes - 1))
                .targetName("Synthetic Node " + (nodes - 1))
                .qty(1L)
                .tree(tree)
                .nodes(nodes)
                .maxNodes(nodes)
                .truncated(false)
                .build();
    }

    /**
     * A balanced tree of EXACTLY `nodes` nodes, shaped like a plan: a few children per level.
     *
     * IT USED TO BUILD ABOUT HALF WHAT IT WAS ASKED FOR, and every 60 fps measurement taken
     * before 2026-08-03 -- including the ones quoted on #166 as "4,000 nodes" -- was really
     * taken on 2,006. The old version started the deepest level at `nodes / 3` and shrank by a
     * third per level, so the total converged to about `nodes / 2` and the loop then exited on
     * `width == 1` with the budget still unspent. Its comment claimed "the count is exact
     * rather than approached", which is exactly the kind of claim that stops anyone checking.
     *
     * The widths are computed first now, and the deepest one is trimmed so they sum to the
     * requested total. `syntheticBuildsExactlyTheNodeCountAskedFor` asserts it, because a
     * generator quietly producing the wrong size makes every performance number downstream
     * wrong in a way no other test can see.
     */
    static PlanNode synthetic(int nodes) {
        int[] widths = levelWidths(nodes);
        java.util.List<PlanNode> level = new java.util.ArrayList<PlanNode>();
        int made = 0;
        for (int depth = 0; depth < widths.length; depth++) {
            int width = widths[depth];
            java.util.List<PlanNode> next = new java.util.ArrayList<PlanNode>(width);
            for (int i = 0; i < width; i++) {
                // A STRICT PARTITION: every node on the level below goes to exactly one
                // parent, and a parent that gets none is simply a leaf.
                //
                // It used to be `Math.max(from + 1, ...)`, which guaranteed every parent at
                // least one child by OVERLAPPING the ranges when a level was wider than the
                // one below it. That makes the result a DAG rather than a tree: the same node
                // hangs off two parents, `FlowLayout` lays its whole subtree out twice, and
                // the plan is bigger than the number it was asked for by however much got
                // shared. It could not happen while the widths always shrank; it happens as
                // soon as the deepest level is trimmed to hit an exact total, which is what
                // `levelWidths` now does. Caught by `syntheticIsExactAtEverySizeAndAlways`
                // `HasOneRoot`, which counts the tree at 600 consecutive sizes.
                int from = Math.min(level.size(), level.size() * i / width);
                int to = Math.min(level.size(), level.size() * (i + 1) / width);
                java.util.List<PlanNode> kids = level.subList(from, to);
                next.add(new PlanNode.Builder()
                        .key("synthetic:node" + made)
                        .name("Synthetic Node " + made)
                        .label("Synthetic Node " + made)
                        .kind("item")
                        .need(made + 1)
                        .status(made % 3 == 0 ? "craft" : "raw")
                        .children(new java.util.ArrayList<PlanNode>(kids))
                        .build());
                made++;
            }
            level = next;
        }
        return level.get(0);
    }

    /**
     * Level widths, deepest first, summing to exactly `nodes` and ending at a single root.
     *
     * The shrink rule is the original's -- each level is a third of the one below it, plus one
     * -- so the shape the timings are taken on has not changed. What changed is that the
     * DEEPEST level is chosen to make the total come out right, instead of being guessed at
     * `nodes / 3` and the remainder abandoned.
     */
    private static int[] levelWidths(int nodes) {
        int wanted = Math.max(1, nodes);
        // Smallest leaf count whose tree is big enough. Monotonic in `leaves`, and the search
        // starts at half because the total is about 1.5x the leaf count -- a plain scan from 1
        // would be right and would walk a couple of thousand steps to get here.
        int leaves = Math.max(1, wanted / 2);
        java.util.List<Integer> widths = shrinkFrom(leaves);
        while (total(widths) < wanted) {
            widths = shrinkFrom(++leaves);
        }
        // Trim the deepest level to land exactly. The excess is at most one leaf's worth of
        // knock-on, since `leaves` is the SMALLEST count that overshot.
        int excess = total(widths) - wanted;
        widths.set(0, Math.max(1, widths.get(0) - excess));
        int[] out = new int[widths.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = widths.get(i);
        }
        return out;
    }

    /** Deepest level `leaves` wide, each level above a third of it plus one, down to one root. */
    private static java.util.List<Integer> shrinkFrom(int leaves) {
        java.util.List<Integer> widths = new java.util.ArrayList<Integer>();
        widths.add(leaves);
        while (widths.get(widths.size() - 1) > 1) {
            widths.add(1 + widths.get(widths.size() - 1) / 3);
        }
        return widths;
    }

    private static int total(java.util.List<Integer> widths) {
        int sum = 0;
        for (int width : widths) {
            sum += width;
        }
        return sum;
    }

    private static PlanView fixture(String name) {
        String wanted = name == null || name.trim().isEmpty() ? DEFAULT_FIXTURE : name.trim();
        return PlanFixtureFiles.load(wanted);
    }

    /**
     * The same, for a screen in another file that wants its own default.
     *
     * PACKAGE-VISIBLE AND EXPLICIT ABOUT THE FALLBACK, rather than making {@link #fixture} visible
     * and its `DEFAULT_FIXTURE` shared. `FlowInteractionShot` deliberately defaults to a TRUNCATED
     * plan, because the chrome it drives has a warning line to draw only on one -- and inheriting
     * this file's default would have made that a property of whichever screen happened to run.
     */
    static PlanView fixtureFor(String name, String fallback) {
        String wanted = name == null || name.trim().isEmpty() ? fallback : name.trim();
        return PlanFixtureFiles.load(wanted);
    }

    /** {@link #book}, for a screen in another file. See {@link #fixtureFor}. */
    static PlanBook bookFor(PlanView plan) {
        return book(plan);
    }

    /**
     * {@link PlannerActions#NONE} in every respect EXCEPT that it asks the real
     * {@link NodeActionsHolder} what the recipe-viewer entries should do.
     *
     * WITHOUT THIS THE MENU SHOT CANNOT PHOTOGRAPH THE MENU. `PlannerActions.NONE` answers
     * with `NodeActions.NONE`, which says false to everything -- so the screen registered
     * specifically to show the node menu was structurally incapable of ever showing its two
     * recipe-viewer entries, no matter what Phase 4 installed, and the picture would have
     * looked correct while proving nothing. The CLICKING half stays inert for the reason
     * `PlannerActions.NONE` gives: the harness shoots from the main menu, where there is no
     * panel to hang a sub-panel off and no server to send a plan-book edit to.
     */
    static final PlannerActions SHOT_ACTIONS = new PlannerActions() {
        @Override
        public NodeActions nodeActions() {
            return NodeActionsHolder.actions();
        }

        @Override
        public void openNodeMenu(PlanNode node) {
        }

        @Override
        public void selectNode(PlanNode node) {
        }

        @Override
        public void openRecipePicker(PlanNode node) {
        }

        @Override
        public void openCaveats() {
        }

        // INERT LIKE THE REST, AND THE SCREENS THAT EXERCISE THESE TWO DO NOT USE THIS OBJECT
        // (#323). `planner-view-flow` and `planner-view-todo` open the panel `PlannerScreen`
        // builds, so their buttons are wired to a real `LivePlannerActions` with a real panel to
        // hang a sub-panel off. Making these live instead would have been the tempting shortcut
        // and it would photograph the harness's own wiring -- which is the defect #323 is, since
        // the three flow screenshots that existed were all produced by the only caller.
        @Override
        public void openFlow(
                io.github.jacoblasky.recipedump.client.planner.PlanView plan) {
        }

        @Override
        public void openTodo(io.github.jacoblasky.recipedump.client.planner.PlanView plan,
                             PlanBook book) {
        }

        @Override
        public void pinRecipe(PlanNode node,
                io.github.jacoblasky.recipedump.client.planner.RecipeChoice choice) {
        }

        @Override
        public void addToTodo(PlanNode node) {
        }

        @Override
        public void toggleFavourite(PlanNode node) {
        }

        // INERT LIKE THE REST, for the reason above: this seam exists so the icon column and
        // the recipe-viewer entries are REAL in a shot, not so a screenshot can edit a plan
        // book. `row-menu` photographs the menu by opening it directly, the way `planner-menu`
        // photographs the node one.
        @Override
        public void openRowMenu(
                io.github.jacoblasky.recipedump.client.planner.PlanView.EntryRow row) {
        }

        @Override
        public void addRowToTodo(
                io.github.jacoblasky.recipedump.client.planner.PlanView.EntryRow row) {
        }

        @Override
        public void favouriteRow(
                io.github.jacoblasky.recipedump.client.planner.PlanView.EntryRow row) {
        }
    };

    /**
     * Give the Phase 4 seam a graph to answer from, so the icons and the two recipe-viewer
     * entries appear in a shot instead of only in a description of one.
     *
     * THE KEY TABLE IS BUILT HERE RATHER THAN A GRAPH LOADED, and the distinction is what
     * keeps the picture honest. Nothing on the client loads a `RecipeGraph` yet, and a second
     * loader living in the harness would be a second thing to keep in step with the dump
     * format -- the exact duplication `NodeActions` was written to avoid. A key table is also
     * all {@link JeiNodeActions} reads: keying the item list, the digest and durability
     * weakenings, and the decision to draw an entry at all run through the production classes
     * over the client's REAL JEI item list. One thing is synthetic -- where the keys came from
     * -- and nothing else is.
     *
     * WHICH FIXTURE YOU SHOOT DECIDES WHETHER ANYTHING RESOLVES. The dev set is Forge,
     * MixinBooter, ModularUI, HEI and JEC, so a plan rooted in a modded item has no stack
     * behind it in this client and correctly draws no icon and no entries. Shoot
     * `plan-in-stock` (minecraft:hopper) or `plan-free-source` (minecraft:cobblestone) to see
     * the populated case.
     *
     * <h2>THE CATALYSTS ARE INTERNED TOO, SINCE #333, AND WITHOUT THEM THE SHOT LIED</h2>
     *
     * `iconForCategory` resolves a machine's sprite through `graph.categoryId(node.category())`
     * and `RecipeGraph.catalysts()`. This method interned KEYS only, so every `categoryId` came
     * back -1 and every machine column in every screenshot would have been empty -- a picture of
     * a feature that is working, shaped exactly like a picture of a feature that is not. That is
     * the failure this whole file exists to prevent, and it would have been diagnosed as a broken
     * lookup rather than as a harness gap.
     *
     * THE CATALYST MUST BE AN ITEM KEY AND NOT THE CATEGORY'S OWN NAME, WHICH IS WHERE THIS WAS
     * WRONG FIRST AND WOULD HAVE PRODUCED A LYING PICTURE. `internCatalysts` originally interned
     * `builder.key(category)` -- so the catalyst key was `minecraft.smelting`, and this comment
     * claimed the dev client would picture a furnace for it. It cannot: `StackIndex.build` keys
     * the real JEI item list through `DumpCommand.stackKey`, which produces `minecraft:furnace`,
     * and its only two weakenings strip a `#digest` or collapse durability. Nothing turns a
     * category id into an item key, so EVERY machine column in EVERY screenshot would have been
     * empty while this paragraph promised otherwise -- a picture of a broken feature next to a
     * comment saying it works, which is the worst of the shapes this file exists to prevent.
     *
     * SO THE STAND-IN IS A REAL VANILLA BLOCK, picked from {@link #SHOT_MACHINES} by the
     * category's own hash. A real `graph.json` maps `nuclearcraft_centrifuge` to
     * `nuclearcraft:centrifuge_idle` and this harness has no dump to read that from -- see the
     * paragraph above on why a second loader is not the answer -- but it can hand the lookup a
     * key the dev client really resolves, which exercises the production path end to end:
     * `categoryId`, `Machines.catalystsByCategory`, `isNotAMachine` and `JeiBridge.stackFor`.
     * Hashed rather than constant so the column reads as a column of different machines instead
     * of one furnace repeated, and deterministic so two shots of one fixture are comparable.
     *
     * WHAT THE SHOT THEREFORE DOES NOT PROVE: that the SPECIFICITY ORDERING picked the right
     * candidate. Every category here has exactly one catalyst, so there is nothing to order.
     * `JeiNodeActionsTest.aMachineStepPicturesItsSpecificControllerAndNotTheCatalystJeiListed`
     * `First` is where that is proved, behaviourally, with two resolvable candidates. Do not
     * read a correct-looking machine column as evidence of the ordering.
     *
     * THE ROADBLOCK FRAME DOES NOT DEPEND ON ANY OF THIS, which is what makes the shot worth
     * taking either way: `machineCell` builds a frame-only cell from `machine_state` alone, and
     * that field is in the fixture. `plan-truncated` carries 28 machine rows and
     * `plan-fluid-chain` several hundred.
     */
    static void armNodeActions(PlanNode tree) {
        GraphBuilder builder = new GraphBuilder();
        intern(tree, builder);
        internCatalysts(tree, builder, new java.util.HashSet<String>());
        final RecipeGraph graph = builder.build();
        JeiBridge.indexFor(graph);
        JeiNodeActions.install(new JeiNodeActions.GraphAccess() {
            @Override
            public RecipeGraph graph() {
                return graph;
            }
        });
    }

    /** Recursive for the reason `PlanJson.readNode` is: the deepest fixture is 634 nodes. */
    static void intern(PlanNode node, GraphBuilder builder) {
        builder.key(node.key());
        for (PlanNode child : node.children()) {
            intern(child, builder);
        }
    }

    /**
     * Give every category in the tree one catalyst, so the machine column has something to look
     * up. See {@link #armNodeActions} for why this is synthetic and what it still exercises.
     *
     * ONCE PER CATEGORY, WHICH THE `seen` SET IS FOR AND NOT AN OPTIMISATION.
     * `GraphBuilder.beginCatalyst` interns the category and `catalystKey` appends a row, so a
     * category visited twice would list the same key twice -- and `Machines.orderBySpecificity`'s
     * breadth count reads exactly that, so a duplicated key would look twice as generic as it is
     * and could be demoted below a genuinely broader one.
     *
     * HAND CRAFTING IS SKIPPED, matching what the panel does with it: `NodeRowText.machineName`
     * returns null for the three `Machines.isHandCrafting` prefixes, so a catalyst for them would
     * be interned and never asked for.
     */
    static void internCatalysts(PlanNode node, GraphBuilder builder,
                                java.util.Set<String> seen) {
        String category = node.category();
        if (category != null && !category.isEmpty() && seen.add(category)
                && !io.github.jacoblasky.recipedump.plan.Machines.isHandCrafting(category)) {
            builder.beginCatalyst(category);
            builder.catalystKey(builder.key(shotMachineKey(category)));
            builder.endCatalyst();
        }
        for (PlanNode child : node.children()) {
            internCatalysts(child, builder, seen);
        }
    }

    /**
     * The vanilla blocks that stand in for a machine in a screenshot. See {@link #internCatalysts}.
     *
     * VANILLA AND BLOCK-SHAPED, so a reader of the picture sees something that could plausibly be
     * a machine rather than a stick. All six are in the dev client's item list unconditionally,
     * which is the only property that matters: a stand-in the index cannot resolve puts the
     * harness straight back into the empty-column failure this array was added to fix.
     *
     * EVERY ENTRY MUST HAVE A REGISTERED `ItemBlock`, WHICH RULES OUT THE OBVIOUS CANDIDATES.
     * `new ItemStack(Block)` goes through `Item.getItemFromBlock`, and a block whose item is
     * registered separately -- `Blocks.CAULDRON` and `Blocks.BREWING_STAND` are the two that
     * belong on a list of machines -- answers `Items.AIR`, so the stack is EMPTY and
     * `DumpCommand.stackKey` has nothing to key. That fails as a null key at graph-build time
     * during a screenshot, which is the worst place to find it, so
     * `everyShotMachineStandInIsAnItemTheIndexCanActuallyResolve` checks all six by construction
     * rather than only the ones a given fixture's categories happen to hash to.
     */
    static final net.minecraft.block.Block[] SHOT_MACHINES = {
        net.minecraft.init.Blocks.FURNACE,
        net.minecraft.init.Blocks.CRAFTING_TABLE,
        net.minecraft.init.Blocks.ANVIL,
        net.minecraft.init.Blocks.DISPENSER,
        net.minecraft.init.Blocks.ENCHANTING_TABLE,
        net.minecraft.init.Blocks.HOPPER,
    };

    /**
     * The item key this harness pretends is `category`'s machine.
     *
     * `DumpCommand.stackKey` AND NOT A SPELLED-OUT `"minecraft:furnace"`, because that method is
     * what {@link io.github.jacoblasky.recipedump.client.StackIndex} keys the real JEI item list
     * through, and a hand-written key that disagreed with it by one character would resolve to
     * nothing while looking entirely correct -- which is the same failure, one layer down.
     *
     * `Math.abs` ON THE HASH, AND `Integer.MIN_VALUE` IS WHY IT IS NOT ENOUGH ON ITS OWN.
     * `Math.abs(Integer.MIN_VALUE)` is negative, so the remainder would be negative and the array
     * read would throw -- on one category name in 4 billion, at screenshot time, in the harness
     * everything else is evidenced through. Masking the sign bit cannot do that.
     */
    static String shotMachineKey(String category) {
        int at = (category.hashCode() & 0x7FFFFFFF) % SHOT_MACHINES.length;
        return io.github.jacoblasky.recipedump.DumpCommand.stackKey(
                new net.minecraft.item.ItemStack(SHOT_MACHINES[at]));
    }
}
