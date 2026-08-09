package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.client.flow.FlowCanvas;
import io.github.jacoblasky.recipedump.client.flow.FlowZoom;
import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.client.planner.PlanSelection;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
import com.cleanroommc.modularui.screen.ModularPanel;
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
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book(plan), SHOT_ACTIONS);
        PlannerScreen.openPanel(panel);
        ShotHarness.log("planner-yield: " + plan.target() + " has its first chanced row at "
                        + row + "; scrolling it into a viewport of about 14 rows");
        ShotScreens.holdCapture(new ScrollToRow(panel, row));
    }

    /** The fixture `planner-yield` uses when none is named: the smallest tree with a chance. */
    private static final String YIELD_FIXTURE = "plan-truncated";

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
        FlowCanvas canvas = new FlowCanvas(plan.tree());
        canvas.pos(4, 4).size(612, 372);
        PlannerScreen.openPanel(PlannerWidgets.flowPanel(canvas));
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
     */
    private static final int SWEEP = 300;

    /**
     * Horizontal passes per vertical descent.
     *
     * 8 rather than 1, because the diagram is far taller than it is wide -- depth is capped at
     * 24 columns by `solve.py` while the leaf level is thousands of rows -- so one pass per
     * descent is a diagonal, and a diagonal over a shape like this is a sweep of the empty
     * corner. Eight is enough that every pass crosses the leaf column while the descent is
     * still fine-grained.
     */
    private static final int PASSES = 8;

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
        PlanNode tree = flowTree(spec);
        // ARMED HERE TOO, AND IT WAS NOT UNTIL #213. `openTree` armed the seam and this did
        // not, so every screenshot of the diagram was taken against `NodeActions.NONE` -- the
        // icon column was charged on all 634 nodes of `plan-fluid-chain` and nothing was ever
        // drawn in it. The picture looked like a diagram with no icons, which is also what a
        // diagram with a broken icon lookup looks like.
        armNodeActions(tree);
        final FlowCanvas canvas = new FlowCanvas(tree);
        canvas.pos(4, 4).size(612, 372);
        canvas.setZoom(zoom);
        PlannerScreen.openPanel(PlannerWidgets.flowPanel(canvas));
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
        final FlowCanvas canvas = new FlowCanvas(flowTree(spec));
        canvas.pos(4, 4).size(612, 372);
        canvas.setZoom(zoom);
        PlannerScreen.openPanel(PlannerWidgets.flowPanel(canvas));
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
        String wanted = arg == null ? "" : arg.trim();
        if (wanted.startsWith("synthetic:")) {
            return synthetic(Integer.parseInt(wanted.substring("synthetic:".length())));
        }
        return fixture(wanted).tree();
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
     */
    static void armNodeActions(PlanNode tree) {
        GraphBuilder builder = new GraphBuilder();
        intern(tree, builder);
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
}
