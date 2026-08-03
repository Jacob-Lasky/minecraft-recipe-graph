package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.client.flow.FlowCanvas;
import io.github.jacoblasky.recipedump.client.flow.FlowZoom;
import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.JeiNodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanFixtureFiles;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.client.planner.PlanView;
import io.github.jacoblasky.recipedump.client.planner.PlannerActions;
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
 * so there is no player, no capability and no solver -- and #135's fixtures are 19 real solved
 * plans from the reference pack, which is far better subject matter than anything written to
 * flatter the renderer. `plan-fluid-chain` alone is 347 nodes.
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
        armNodeActions(plan);
        PlannerScreen.openPlan(plan, book());
    }

    static void openMenu(String arg) {
        PlanView plan = fixture(arg);
        armNodeActions(plan);
        PlannerScreen.openPanel(PlannerWidgets.nodeMenu(plan.tree(), SHOT_ACTIONS));
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
        final FlowCanvas canvas = new FlowCanvas(flowTree(spec));
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
        PlannerScreen.openPanel(PlannerWidgets.todoPanel(fixture(arg), book()));
    }

    /**
     * A book with something in it, since an empty TODO panel proves only that it opens.
     *
     * Real keys from the reference pack rather than "foo": the discriminated one is the shape
     * that overflows a row, and 934,400 mB of water is a real Borax draw.
     */
    private static PlanBook book() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.setTodo("nuclearcraft:borax", 64L);
        book.setTodo("fluid:water", 934_400L);
        book.setTodo("thaumadditions:vis_pod#0116bb2287a7", 3L);
        return book;
    }

    /**
     * The tree to draw: a fixture, or `synthetic:<n>` for a plan of a chosen size.
     *
     * THE GATE IS 4,000 NODES AND THE LARGEST FIXTURE IS 347. `DEFAULT_MAX_NODES` is 4,000,
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
        public void pinRecipe(PlanNode node,
                io.github.jacoblasky.recipedump.client.planner.RecipeChoice choice) {
        }

        @Override
        public void addToTodo(PlanNode node) {
        }

        @Override
        public void toggleFavourite(PlanNode node) {
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
    static void armNodeActions(PlanView plan) {
        GraphBuilder builder = new GraphBuilder();
        intern(plan.tree(), builder);
        final RecipeGraph graph = builder.build();
        JeiBridge.indexFor(graph);
        JeiNodeActions.install(new JeiNodeActions.GraphSource() {
            @Override
            public RecipeGraph graph() {
                return graph;
            }
        });
    }

    /** Recursive for the reason `PlanJson.readNode` is: the deepest fixture is 347 nodes. */
    static void intern(PlanNode node, GraphBuilder builder) {
        builder.key(node.key());
        for (PlanNode child : node.children()) {
            intern(child, builder);
        }
    }
}
