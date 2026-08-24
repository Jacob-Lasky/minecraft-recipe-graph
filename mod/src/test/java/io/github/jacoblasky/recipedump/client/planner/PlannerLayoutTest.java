package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.cleanroommc.modularui.widgets.ListWidget;

import io.github.jacoblasky.recipedump.HeadlessLayout;
import io.github.jacoblasky.recipedump.common.PlanBook;
import org.junit.Test;

/**
 * The planner's geometry, over the real fixtures, with no window (#125's harness).
 *
 * WHAT THIS IS FOR, given that the PR also carries screenshots: a screenshot costs about two
 * minutes and shows one plan. This runs every fixture in seconds and can assert things a
 * picture cannot -- that no row overlaps the next across 519 of them, that nothing overflows
 * the panel, that the deepest chain in the pack still leaves room for a label. Screenshots
 * answer "does it look right"; this answers "is it right", and it is the one a future change
 * gets immediately.
 */
public class PlannerLayoutTest {

    /**
     * THE PANEL READS TWO STATIC SINGLETONS AND THIS CLASS USED TO RESET NEITHER.
     *
     * `plannerPanel` calls `PlanCaveats.summaryLine` (`ScenarioSource`) and, since #331,
     * `PlanCaveats.stockRefusalLine` (`LiveStock` through `PlannerStock`). Both are process-wide,
     * so a case in ANY class that leaves a refused snapshot behind adds a red line to the top of
     * every panel built here and shifts every `y` below it -- and the cases that count clickables
     * or measure the caveat block's box would then be measuring a panel nobody arranged.
     *
     * IT WAS ALREADY A LATENT HAZARD AND #331 MADE IT A REAL ONE. Before that line existed the
     * inherited state could only change the caveat block's HEIGHT, and
     * `clickingTheCaveatOpensWhatThePlanCouldNotSee` asserted `summaryLine` was non-empty rather
     * than making it so, which is a test reading whatever the JVM handed it. `@Before` as well as
     * `@After` for `PlanCaveatsTest`'s stated reason: another class can leave this behind.
     */
    @org.junit.Before
    @org.junit.After
    public void isolate() {
        io.github.jacoblasky.recipedump.common.ScenarioSource.resetReaders();
        io.github.jacoblasky.recipedump.client.LiveStock.forget();
        // THREE STATICS NOW, NOT TWO (#333). `machineCell` reads `NodeActionsHolder.actions()`
        // for the machine column exactly as the item column already did for `iconFor`, so a
        // `JeiNodeActions` left installed by another class -- `JeiNodeActionsTest` installs one
        // per case and resets in its own `@After`, but a case that dies between the two does not
        // -- would add icon widgets to rows this class counts and measures. `install(null)`
        // collapses to `NodeActions.NONE`, which is what an unwired client has.
        NodeActionsHolder.install(null);
    }

    /**
     * A SHOPPING ROW KEEPS THE QUANTITY A CLICK WOULD HAVE TO ACT ON. #251.
     *
     * `addEntries` built `new Line(key, text, colour)` and dropped everything else, so the row
     * was identifiable (#236 resolves an icon from the key) while the number was gone. That is
     * why #251 could not simply be wired up at `rowList`: the aggregate `need` that makes the
     * issue's option 3 correct is discarded one layer above the place the click attaches.
     *
     * THE AGGREGATE IS THE POINT, not merely that a number survives. `_need_entry`'s docstring
     * states the division of labour where the data is produced -- "The tree is the diagnosis;
     * this is what gets acted on while gathering" -- so a shopping row's `need` is the total the
     * plan wants, and a tree node's is one parent's share. #251's two rejected options both
     * route through `PlanSelection.selectedNode` and read the per-occurrence number, which is
     * one defect with two spellings rather than two objections.
     *
     * ONE ROW, ONE ACTION. A wrapped row produces several lines and only the first carries the
     * row, so a player never gets two clickable things that mean one thing.
     */
    @Test
    public void aShoppingLineCarriesTheRowItCameFromAndItsAggregateNeed() {
        PlanView.EntryRow row = PlanFixtures.load("plan-cycle").shoppingList().get(0);
        List<PlannerWidgets.Line> lines = new java.util.ArrayList<PlannerWidgets.Line>();
        PlannerWidgets.addEntries(lines, "shopping list",
                                  java.util.Collections.singletonList(row),
                                  NodeStatus.INK_MUTED, 240);

        PlannerWidgets.Line header = lines.get(0);
        assertNull("a header is not about one item, so it carries no row", header.row);

        PlannerWidgets.Line first = lines.get(1);
        assertSame("the first line of a row must carry that row", row, first.row);
        assertEquals("and the quantity a click acts on is the row's own aggregate",
                     row.need(), first.row.need());

        for (int i = 2; i < lines.size(); i++) {
            assertNull("a continuation line must not be separately actionable",
                       lines.get(i).row);
        }
    }

    /**
     * #251's witness, and it has TWO clauses because one of them is not enough.
     *
     * A shopping row is CLICKABLE, and the click carries THAT ROW'S OWN AGGREGATE `need`.
     * Asserting only the first clause passes against an implementation that made the row
     * clickable and then routed the action through `PlanSelection.selectedNode` -- #251's
     * rejected option 1 -- because a node's `need()` is a perfectly good number and simply the
     * wrong one. It is the quantity, not the clickability, that this issue is about.
     *
     * SO THE EXPECTED NUMBER COMES FROM THE FIXTURE'S OWN ROW rather than being written in
     * here. A literal would freeze whatever `plan-cycle` happened to hold on the day, and the
     * assertion would then be about the fixture instead of about the wiring -- and it would
     * start failing for the wrong reason the next time the oracle moves, which #280 says it is
     * about to.
     */
    /**
     * #280: does scrolling a laid-out tree list actually STICK?
     *
     * WRITTEN BECAUSE A PACK BOOT SAID NO AND COULD NOT SAY WHY. `planner-yield` scrolled to
     * 367px of 550 -- the log proves the call was made with sane numbers -- and the PNG came
     * back showing rows 0 to 13, the top of the tree. So the offset was either never applied
     * or applied and then reset, and a 20-minute boot cannot tell those apart.
     *
     * This can, in three minutes, with no client: lay the panel out, scroll it, read the offset
     * back. If it reads back, the shot's problem is WHEN it scrolls rather than HOW.
     */
    @Test
    public void scrollingALaidOutTreeListSticks() {
        ModularPanel panel = laidOut("plan-truncated");
        ListWidget<?, ?> tree = findList(panel);
        assertTrue("the tree must overflow or there is nothing to scroll",
                   tree.getScrollData().getScrollSize() > tree.getArea().h());

        int target = tree.getScrollData().getScrollSize() - tree.getArea().h();
        tree.getScrollArea().getScrollY().scrollTo(tree.getScrollArea(), target);

        assertEquals("a scroll offset set on a laid-out list must read back",
                     target, tree.getScrollArea().getScrollY().getScroll());
    }

    @Test
    public void clickingAShoppingRowOpensItsMenuCarryingTheRowsOwnAggregateNeed() {
        PlanView plan = PlanFixtures.load("plan-cycle");
        assertFalse("plan-cycle must have shopping rows or this asserts nothing",
                    plan.shoppingList().isEmpty());
        PlanView.EntryRow first = plan.shoppingList().get(0);

        Recorder actions = new Recorder();
        ModularPanel todo = PlannerWidgets.todoPanel(plan, emptyBook(), actions);
        HeadlessLayout.layOut(todo);

        List<PlannerWidgets.ClickableGroup> rows = clickables(todo);
        assertFalse("a shopping row must be clickable at all", rows.isEmpty());

        // The FIRST clickable is the first shopping row: since #332 `todoLines` opens with the
        // summed shopping list, and headers carry no row, so nothing clickable precedes it. It
        // held before that too, but only because this book is empty -- a populated one used to
        // put its own clickable rows above the shopping list.
        rows.get(0).onMousePressed(0);

        assertEquals("clicking a shopping row opens the ROW menu, carrying that row's own"
                     + " aggregate need rather than any node's per-occurrence share",
                     java.util.Collections.singletonList(
                             "rowMenu:" + first.key() + ":" + first.need()),
                     actions.calls);
    }


    private static PlanBook emptyBook() {
        return new PlanBook();
    }

    private static ModularPanel laidOut(String fixture) {
        ModularPanel panel =
                PlannerWidgets.plannerPanel(PlanFixtures.load(fixture), emptyBook(),
                                            matching(), recorder());
        HeadlessLayout.layOut(panel);
        return panel;
    }

    /**
     * The guard on everything else here.
     *
     * `WidgetTree.resizeInternal` catches every Throwable and only logs it, so a sizer that
     * died leaves boxes at their construction values and returns normally. #140 hit exactly
     * that: one self-sizing `TextWidget` reaching for the font renderer took the entire tree
     * to 0x0 while looking like a layout bug.
     */
    @Test
    public void everyFixtureLaysOutWithEveryWidgetGettingARealBox() {
        for (String fixture : PlanFixtures.names()) {
            ModularPanel panel = laidOut(fixture);
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(fixture + ": " + widget.getClass().getSimpleName()
                           + " has no width: " + area, area.w() > 0);
                assertTrue(fixture + ": " + widget.getClass().getSimpleName()
                           + " has no height: " + area, area.h() > 0);
            }
        }
    }

    @Test
    public void thePanelIsTheDeclaredSizeOnTheSmallestSupportedScreen() {
        Area area = laidOut("plan-in-stock").getArea();
        assertEquals(PlannerWidgets.PANEL_WIDTH, area.w());
        assertEquals(PlannerWidgets.PANEL_HEIGHT, area.h());
        // 427x240 is 854x480 at GUI scale 2. If the panel ever outgrows it, it is clipped on
        // the one screen size every Minecraft install can produce.
        assertTrue("the panel must fit the smallest screen",
                   area.w() <= HeadlessLayout.SCREEN_WIDTH
                   && area.h() <= HeadlessLayout.SCREEN_HEIGHT);
    }

    @Test
    public void nothingInAnyFixtureOverflowsThePanel() {
        // #125 measured that ModularUI neither clamps nor clips a child wider than its
        // parent: it overflows and nothing reports it. This is the report.
        for (String fixture : PlanFixtures.names()) {
            ModularPanel panel = laidOut(fixture);
            Area bounds = panel.getArea();
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                Area area = widget.getArea();
                assertTrue(fixture + ": " + area + " overflows the panel's right edge at "
                           + bounds.ex(), area.ex() <= bounds.ex());
                assertTrue(fixture + ": " + area + " overflows the panel's left edge at "
                           + bounds.x(), area.x() >= bounds.x());
            }
        }
    }

    @Test
    public void theTreeHasOneRowPerNodeAndKnowsHowTallItsContentIs() {
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            ModularPanel panel = laidOut(fixture);
            ListWidget<?, ?> tree = findList(panel);
            assertEquals(fixture + " should show every node",
                         plan.flatten().size(), tree.getChildren().size());
            // A ListWidget publishes its content height; a plain ScrollWidget never would,
            // and its scrollbar would be permanently dead. #125.
            assertEquals(fixture + " scroll extent should be rows times row height",
                         plan.flatten().size() * PlannerWidgets.ROW_HEIGHT,
                         tree.getScrollData().getScrollSize());
        }
    }

    @Test
    public void theBiggestTreeStillFitsInTheViewportAndOverflowsIt() {
        // The hard case the scroll area exists for: 519 nodes in a 400x220 panel. 347 before
        // #172 reordered the cycle term, 388 before #176 priced the unsourced set and the plan
        // stopped dead-ending on 40 items the graph could not explain, 634 before #136 priced
        // the storage blocks nothing presses, 641 before #211 and #169 stopped the JEI loot
        // tables and automation cards it was routing through from being routes.
        //
        // THE ONLY DECREASE IN THAT LIST IS THE LAST ONE, and it is the one to be suspicious
        // of: planning less is the cheap way to make this assertion pass. See
        // `PlanJsonTest.theBiggestFixtureIsTheOneTheScrollPanelHasToSurvive`, which carries the
        // corroboration that the plan got more honest rather than merely smaller.
        //
        // NAMED FOR "BIGGEST" RATHER THAN FOR A COUNT, because the count has now moved five
        // times and the method was still called `aThreeHundredNodeTree` at 388. A test name
        // that states a number goes stale silently -- the assertion below is the thing that
        // must fail when the number moves, and it does. It did, on all five.
        //
        // 519 -> 576 FOR #193, which is an INCREASE and therefore not the suspicious direction
        // this comment warns about. The corroboration is in `PlanJsonTest`'s sibling: `work`
        // fell nine-fold in the same change, so the tree grew out of a cheaper search rather
        // than a longer one.
        //
        // 576 -> 769 FOR #171/#242, an INCREASE again and again not the suspicious direction.
        // Pricing the pack's marker items stopped routes terminating on a free tooltip, so the
        // search continues into ones the graph can account for. The corroboration is the same
        // sibling: `work` rose only 3,054 -> 3,249 for a 34% bigger tree, so this grew out of a
        // barely-more-expensive search rather than a much longer one.
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        ModularPanel panel = laidOut("plan-fluid-chain");
        ListWidget<?, ?> tree = findList(panel);
        // 784 -> 703. The tree shrank with the dump, not with the layout: see the 2x2 on
        // `PlanJsonTest.theWorkCounterAndItsBudgetBothArrive`. What this test is actually
        // about is unchanged -- the content still exceeds the viewport, which the two
        // assertions below check and which is why a smaller tree is not a weaker case.
        assertEquals(703, tree.getChildren().size());
        assertTrue("the content must exceed the viewport, or nothing is being scrolled",
                   tree.getScrollData().getScrollSize() > tree.getArea().h());
        assertTrue("the viewport itself must stay inside the panel",
                   tree.getArea().h() < PlannerWidgets.PANEL_HEIGHT);
    }

    @Test
    public void noTwoRowsOverlapInAnyFixture() {
        for (String fixture : PlanFixtures.names()) {
            List<IWidget> rows = findList(laidOut(fixture)).getChildren();
            for (int i = 1; i < rows.size(); i++) {
                Area above = rows.get(i - 1).getArea();
                Area below = rows.get(i).getArea();
                assertTrue(fixture + " row " + i + " starts at " + below.y()
                           + " but the one above ends at " + above.ey(),
                           below.y() >= above.ey());
            }
        }
    }

    @Test
    public void aDeeplyIndentedRowStillLeavesRoomForItsLabel() {
        // Indentation is capped for this reason. Without the cap the label column of a
        // ten-deep chain would be pushed under the badge, and per #125 nothing would say so.
        PlanNode leaf = deepest(PlanFixtures.load("plan-fluid-chain").tree());
        com.cleanroommc.modularui.widget.ParentWidget<?> row =
                PlannerWidgets.row(leaf, 40, PlannerWidgets.CONTENT_WIDTH, recorder());
        ModularPanel panel = HeadlessLayout.layOutPanel(
                "deep", PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT, row);
        int widest = 0;
        for (IWidget widget : HeadlessLayout.flatten(row)) {
            widest = Math.max(widest, widget.getArea().w());
        }
        assertTrue("a 40-deep row should still have a label column wider than the badge, "
                   + "got " + widest, widest > PlannerWidgets.BADGE);
        assertTrue(panel.getArea().w() >= row.getArea().w());
    }

    @Test
    public void aTruncatedPlanShowsItsWarningAndAnUntruncatedOneDoesNot() {
        // The warning is a whole extra line, so its presence changes the layout -- which is
        // why it is worth asserting here rather than only in the row-text test.
        int withWarning = countRows(laidOut("plan-truncated"));
        int without = countRows(laidOut("plan-in-stock"));
        assertTrue("the truncated fixture should carry one line the other does not",
                   withWarning > 0 && without > 0);
        assertTrue(NodeRowText.truncationWarning(PlanFixtures.load("plan-truncated"))
                              .length() > 0);
        assertEquals("", NodeRowText.truncationWarning(PlanFixtures.load("plan-in-stock")));
    }

    @Test
    public void theNodeMenuHidesTheRecipeViewerEntriesWhenNothingIsInstalled() {
        // The seam, asserted as behaviour rather than as an interface that exists. With no
        // Phase 4 the menu must be shorter, not greyed out.
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        ModularPanel menu = PlannerWidgets.nodeMenu(node, PlannerActions.NONE);
        HeadlessLayout.layOut(menu);
        String text = HeadlessLayout.dump(menu);
        assertFalse("no JEI installed, so no recipe-viewer entry may appear",
                    text.contains("Show recipes"));
        for (IWidget widget : HeadlessLayout.flatten(menu)) {
            assertTrue("menu widget has no box: " + widget.getArea(),
                       widget.getArea().w() > 0 && widget.getArea().h() > 0);
        }
    }

    /**
     * No secondary panel cuts its own longest line.
     *
     * The screenshots found both of these: the menu rendered "Choose another recip..." and
     * the picker cut its explanation. A truncated label in a TREE row is correct behaviour --
     * a registry id genuinely does not fit -- but a panel whose own fixed strings do not fit
     * is just too narrow, and only a picture had been able to tell the difference.
     */
    @Test
    public void noSecondaryPanelCutsItsOwnWording() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        assertNoEllipsis(PlannerWidgets.nodeMenu(node, withRecipeViewer()));
        assertNoEllipsis(PlannerWidgets.nodeMenu(node, PlannerActions.NONE));
        assertNoEllipsis(pickerFor(node, RecipeChoices.MAX_SHOWN));
        assertNoEllipsis(pickerFor(node, 0));
        // The TODO panel's own furniture, not the keys in it: a key is a tree label and may
        // legitimately be cut.
        PlanBook book = new PlanBook();
        ModularPanel todo = PlannerWidgets.todoPanel(PlanFixtures.load("plan-in-stock"), book, PlannerActions.NONE);
        HeadlessLayout.layOut(todo);
        assertFalse(HeadlessLayout.dump(todo), dumpText(todo).contains("still needed for th"
                                                                      + NodeRowText.ELLIPSIS));
    }

    private static void assertNoEllipsis(ModularPanel panel) {
        HeadlessLayout.layOut(panel);
        for (String line : texts(panel)) {
            assertFalse("a panel cut its own wording: " + line,
                        line.endsWith(NodeRowText.ELLIPSIS));
        }
    }

    /** Every string the panel's text widgets ended up holding, after truncation. */
    private static List<String> texts(ModularPanel panel) {
        return textIn(panel);
    }

    /** The same, for any subtree rather than a whole panel. */
    private static List<String> textIn(IWidget root) {
        List<String> lines = new java.util.ArrayList<String>();
        for (IWidget widget : HeadlessLayout.flatten(root)) {
            if (widget instanceof com.cleanroommc.modularui.widgets.TextWidget) {
                lines.add(((com.cleanroommc.modularui.widgets.TextWidget<?>) widget)
                                  .getKey().getFormatted());
            }
        }
        return lines;
    }

    private static String dumpText(ModularPanel panel) {
        StringBuilder sb = new StringBuilder();
        for (String line : texts(panel)) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void theNodeMenuGrowsWhenPhaseFourInstallsItsActions() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        ModularPanel without = PlannerWidgets.nodeMenu(node, PlannerActions.NONE);
        ModularPanel with = PlannerWidgets.nodeMenu(node, withRecipeViewer());
        HeadlessLayout.layOut(without);
        HeadlessLayout.layOut(with);
        assertTrue("installing the actions should add the two entries",
                   with.getArea().h() > without.getArea().h());
    }

    @Test
    public void theRecipePickerAndTodoPanelLayOutForEveryFixture() {
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            ModularPanel picker = pickerFor(plan.tree(), 3);
            ModularPanel todo = PlannerWidgets.todoPanel(plan, emptyBook(), PlannerActions.NONE);
            HeadlessLayout.layOut(picker);
            HeadlessLayout.layOut(todo);
            for (ModularPanel panel : new ModularPanel[]{picker, todo}) {
                for (IWidget widget : HeadlessLayout.flatten(panel)) {
                    Area area = widget.getArea();
                    assertTrue(fixture + ": " + widget.getClass().getSimpleName()
                               + " has no box: " + area, area.w() > 0 && area.h() > 0);
                }
            }
        }
    }

    /**
     * The window the calculator item actually opens.
     *
     * THE EMPTY PLAN IS A REAL STATE, NOT A PLACEHOLDER, and that is why this still asserts
     * something. {@link io.github.jacoblasky.recipedump.client.PlannerEntry#open} shows the
     * book and calls `startPlan`, and the solve runs off-thread -- so every open renders the
     * book against {@link PlanView#empty()} first and swaps the tree in when the solve lands.
     * A panel that only lays out once a plan exists would be broken for that whole window.
     *
     * Replaces the equivalent assertion in #140's `PlannerScreenLayoutTest`. Said "there is
     * no solver yet" until #176 corrected it; the solver was ported in fe445a2 (#141) and
     * `PlannerService` has solved since, so the claim had been false for 35 merged PRs while
     * reading as the reason the test exists.
     */
    @Test
    public void theWindowTheCalculatorItemOpensLaysOutWithAPopulatedBook() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.addFavourite("thaumadditions:vis_pod#0116bb2287a7");
        book.setTodo("fluid:water", 934_400L);
        ModularPanel panel = PlannerWidgets.todoPanel(PlanView.empty(), book, PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        System.out.println("todo:\n" + HeadlessLayout.dump(panel));
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            Area area = widget.getArea();
            assertTrue(widget.getClass().getSimpleName() + " has no box: " + area,
                       area.w() > 0 && area.h() > 0);
        }
        assertTrue("an empty plan must still produce a usable panel",
                   panel.getArea().h() > 0);
    }

    /** An empty plan is a normal state, not an error one. */
    @Test
    public void anEmptyPlanRendersRatherThanThrowing() {
        ModularPanel panel = PlannerWidgets.plannerPanel(PlanView.empty(), emptyBook(),
                                                          matching(), recorder());
        HeadlessLayout.layOut(panel);
        assertEquals(PlannerWidgets.PANEL_WIDTH, panel.getArea().w());
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            assertTrue("empty plan left " + widget.getClass().getSimpleName() + " unsized",
                       widget.getArea().w() > 0 && widget.getArea().h() > 0);
        }
    }

    /**
     * EVERY panel this package builds fits the smallest screen, for every fixture.
     *
     * The assertion that was missing. `everyFixtureLaysOutWithEveryWidgetGettingARealBox`
     * asserts a widget HAS a box; it says nothing about whether the box is on the screen. The
     * TODO panel sized itself to its contents, and `plan-truncated`'s shopping list (20 rows
     * then, 19 since #172) made it 23 rows tall -- a panel running off the top and bottom of
     * a 240-pixel screen,
     * which only the screenshot showed. A panel that does not fit is clipped, and Minecraft
     * clips silently.
     */
    @Test
    public void everyPanelFitsTheSmallestScreen() {
        PlanBook book = new PlanBook();
        book.setTodo("fluid:water", 934_400L);
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            ModularPanel[] panels = {
                PlannerWidgets.plannerPanel(plan, book, behind(), recorder()),
                PlannerWidgets.todoPanel(plan, book, PlannerActions.NONE),
                pickerFor(plan.tree(), RecipeChoices.MAX_SHOWN + 7),
                PlannerWidgets.nodeMenu(plan.tree(), PlannerActions.NONE),
            };
            for (ModularPanel panel : panels) {
                HeadlessLayout.layOut(panel);
                Area area = panel.getArea();
                assertTrue(fixture + ": a panel is " + area.w() + " wide, wider than the "
                           + HeadlessLayout.SCREEN_WIDTH + " screen",
                           area.w() <= HeadlessLayout.SCREEN_WIDTH);
                assertTrue(fixture + ": a panel is " + area.h() + " tall, taller than the "
                           + HeadlessLayout.SCREEN_HEIGHT + " screen",
                           area.h() <= HeadlessLayout.SCREEN_HEIGHT);
                assertTrue(fixture + ": a panel starts off the top of the screen at "
                           + area.y(), area.y() >= 0);
                assertTrue(fixture + ": a panel starts off the left of the screen at "
                           + area.x(), area.x() >= 0);
            }
        }
    }

    /**
     * The caveats panel fits the screen. #190.
     *
     * ITS OWN TEST AND NOT A ROW IN `everyPanelFitsTheSmallestScreen`, because it is the one
     * panel in this package that does not depend on a plan at all -- it is built from
     * `ScenarioSource`, which answers for the runtime. Asserting it once per fixture would run
     * it 24 times and report a failure against whichever fixture the loop happened to be on,
     * which is a name that would send the next reader looking in the wrong place.
     *
     * IT IS ALSO THE ONLY PANEL SIZED TO ITS CONTENT WITH NO CAP, which is deliberate -- a
     * cut-off list of what the planner could not see is a shorter, wrong list -- and this is
     * the guard that keeps the trade honest. If the notes grow past the screen, the answer is
     * to scroll them, not to drop one.
     */
    @Test
    public void theCaveatsPanelFitsTheSmallestScreen() {
        ModularPanel panel = PlannerWidgets.caveatsPanel();
        HeadlessLayout.layOut(panel);
        Area area = panel.getArea();
        assertTrue("the caveats panel is " + area.h() + " tall, taller than the "
                   + HeadlessLayout.SCREEN_HEIGHT + " screen",
                   area.h() <= HeadlessLayout.SCREEN_HEIGHT);
        assertTrue("the caveats panel is " + area.w() + " wide, wider than the "
                   + HeadlessLayout.SCREEN_WIDTH + " screen",
                   area.w() <= HeadlessLayout.SCREEN_WIDTH);
        assertTrue("it starts off the screen at " + area.x() + "," + area.y(),
                   area.x() >= 0 && area.y() >= 0);
    }

    /**
     * An empty summary list contributes no header, and a non-empty one contributes both. #190.
     *
     * THE BRANCH THAT RUNS ON EVERY REAL PANEL. `tokens_needed` and `from_emc` are empty in game
     * until #112 and #50, so the suppression path is the one a player hits today and the
     * non-empty path is the one they hit after those land. A header standing over blank space
     * reads as a list that failed to load, which is the opposite of what four labelled sections
     * are for.
     *
     * ASSERTED ON THE LINE BUILDER RATHER THAN THE PANEL, because a `TextWidget`'s string cannot
     * be read back out of a laid-out tree -- which is the same reason `NodeRowText` exists as a
     * separate class. `theTodoPanelDrawsOneRowPerLine` below is what ties these lines to widgets.
     */
    @Test
    public void anEmptySummaryListContributesNeitherRowsNorAHeader() {
        // THE PARALLEL `List<String>` / `List<Integer>` THIS USED TO PASS IS NOW ONE `List<Line>`,
        // because a row's icon needs the row's KEY and two lists appended in lockstep had already
        // thrown it away. The assertions are unchanged in substance: a header appears only over
        // rows, it is muted rather than the section's colour, and a null header adds nothing.
        List<PlannerWidgets.Line> lines = new java.util.ArrayList<PlannerWidgets.Line>();

        PlannerWidgets.addSection(lines, "transmuted from EMC:",
                                  java.util.Collections.<String>emptyList(),
                                  NodeStatus.INK_OK);
        assertTrue("an empty list must not leave its header behind: " + lines, lines.isEmpty());

        PlannerWidgets.addSection(lines, "used from your stock:",
                                  java.util.Arrays.asList("5x Iron Ingot", "2x Chest"),
                                  NodeStatus.INK_OK);
        assertEquals(java.util.Arrays.asList("used from your stock:", "5x Iron Ingot", "2x Chest"),
                     textOf(lines));
        assertEquals("the header is muted, not the section's colour",
                     NodeStatus.INK_MUTED, lines.get(0).colour);
        assertEquals(NodeStatus.INK_OK, lines.get(1).colour);
        // AND NO SECTION ROW CLAIMS A KEY. These come from `machineLines`, which names a machine
        // rather than an item, so an icon column drawn from one would look up a category.
        for (PlannerWidgets.Line line : lines) {
            assertEquals("a plain section row has no key: " + line, "", line.key);
        }

        // A null header is the shopping list, which writes its own heading and its own
        // "nothing outstanding" row because it is the one section whose emptiness is worth
        // stating. It must not gain a second heading from here.
        int before = lines.size();
        PlannerWidgets.addSection(lines, null,
                                  java.util.Arrays.asList("3x Iron Ore"), NodeStatus.INK_NEED);
        assertEquals(before + 1, lines.size());
        assertEquals("3x Iron Ore", lines.get(lines.size() - 1).text);
    }

    /** The words of each line, so a list of rows can be compared with a list of strings. */
    private static List<String> textOf(List<PlannerWidgets.Line> lines) {
        List<String> out = new java.util.ArrayList<String>();
        for (PlannerWidgets.Line line : lines) {
            out.add(line.text);
        }
        return out;
    }

    /**
     * Every line the TODO panel composes becomes exactly one row, up to the scroll cap.
     *
     * WHAT THIS CATCHES that the string test above cannot: a section whose rows are composed and
     * then dropped by the widget layer, which is how a header could still end up over blank
     * space. `plan-in-stock` is the fixture that exercises it -- two `used_from_stock` rows and
     * three machines, with `tokens_needed` and `from_emc` both empty -- so the panel has both a
     * suppressed section and two populated ones.
     */
    @Test
    public void theTodoPanelDrawsOneRowPerLine() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        assertFalse("the fixture must have stock rows to draw", plan.usedFromStock().isEmpty());
        assertTrue("and no EMC rows, so a section is suppressed", plan.fromEmc().isEmpty());

        ModularPanel panel = PlannerWidgets.todoPanel(plan, emptyBook(), PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        ListWidget<?, ?> list = findList(panel);
        int rows = list.getChildren().size();
        int inner = PlannerWidgets.TODO_WIDTH - PlannerWidgets.PADDING * 2;
        // Recomposed rather than hardcoded, so this stays an identity between the two layers
        // instead of a number that has to be edited whenever a section's wording changes.
        // `addSection` RETURNS EARLY ON AN EMPTY BODY, so the header is conditional and this
        // recomposition has to be too. It was `1 + size`, which was right only while
        // plan-in-stock had machines to build; at #246 its list went empty and the identity
        // broke by exactly one row. Mirroring the production rule rather than the count keeps
        // this an identity between the two layers instead of a number that rots.
        List<String> machines = NodeRowText.machineLines(plan.machinesToBuild(), inner);
        // #332 REORDERED THESE AND ADDED ONE. The plan book's rows moved from the top to the
        // bottom and grew a header of their own -- `on your TODO list:` over the placeholder --
        // which is one more row than the bare `nothing on the list` that used to lead the panel.
        // Written in panel order so a future reorder is visible in the diff.
        int expected = 1          // "still needed for this plan:"
                + 1              // "nothing outstanding": plan-in-stock has an empty shopping list
                + (machines.isEmpty() ? 0 : 1 + machines.size())
                + 1 + NodeRowText.entryLines(plan.usedFromStock(), inner).size()
                + 1 + 1;         // "on your TODO list:", "nothing on the list"
        assertEquals("every composed line must become a row", expected, rows);
    }

    @Test
    public void aTodoListLongerThanTheScreenScrollsRatherThanGrowing() {
        // `plan-truncated` is the fixture with the long shopping list -- 19 rows, 20 before
        // #172 -- which is what made the panel taller than the screen before it scrolled.
        // The floor is a FIXTURE-SELECTION check and nothing more: the assertion that the
        // list really overflows its cap is the one below, and it measures rather than
        // assumes. Kept anyway, so that a fixture quietly becoming short fails HERE with a
        // clear reason instead of failing there with a confusing one.
        PlanView plan = PlanFixtures.load("plan-truncated");
        assertTrue("the fixture should have a long shopping list",
                   plan.shoppingList().size() >= 15);
        ModularPanel panel = PlannerWidgets.todoPanel(plan, emptyBook(), PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        ListWidget<?, ?> list = findList(panel);
        assertTrue("the list must be capped", list.getArea().h()
                   <= PlannerWidgets.TODO_MAX_LIST_HEIGHT);
        assertTrue("and its content must exceed the cap, or nothing is scrolling",
                   list.getScrollData().getScrollSize() > list.getArea().h());
    }

    /**
     * No badge is ever cut, for any node in any fixture.
     *
     * A badge is fixed vocabulary; a LABEL is a registry name and may legitimately be cut, so
     * the two need different rules and only the badge gets an absolute one. The screenshot of
     * `plan-variant-table` is what found it -- #139's "no known source" rendered as
     * "no known...", which is a mark saying half of itself.
     */
    @Test
    public void theBadgeColumnFitsEveryWordTheVocabularyCanProduce() {
        for (String status : NodeStatus.all()) {
            assertBadgeFits(NodeStatus.badgeFor(status));
        }
        for (String kind : NodeStatus.tokenKinds()) {
            assertBadgeFits(NodeStatus.tokenBadge(kind));
        }
        assertBadgeFits(NodeStatus.UNSOURCED_BADGE);

        // And in practice, over every node of every fixture.
        for (String fixture : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(fixture).flatten()) {
                assertBadgeFits(NodeStatus.badge(node));
            }
        }
    }

    private static void assertBadgeFits(String badge) {
        assertEquals("the badge column cuts \"" + badge + "\"",
                     badge, NodeRowText.fit(badge, PlannerWidgets.BADGE));
    }

    /**
     * A node either carries its whole badge or none of it, and the LABEL wins.
     *
     * Phase 3b measured the old behaviour across the diagram's node widths and found the
     * middle of the range worse than either end: at 148px the badge was a perfect
     * 15-character "no known source" and the label was zero characters, because the badge was
     * sized first and the label took what was left. An item's name is the point of the node.
     *
     * The widths here are the ones they measured, so a future change to `BADGE` or `QTY` is
     * checked against the sizes a diagram actually uses rather than against a round number.
     */
    @Test
    public void aNodeTooNarrowForBothDropsTheBadgeAndKeepsTheLabel() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        for (int width : new int[]{96, 120, 148, 180, 239, 388}) {
            com.cleanroommc.modularui.widget.ParentWidget<?> box =
                    PlannerWidgets.planNodeContent(node, width, PlannerWidgets.ROW_HEIGHT,
                                                   PlannerActions.NONE);
            HeadlessLayout.layOutPanel("node", PlannerWidgets.PANEL_WIDTH,
                                       PlannerWidgets.PANEL_HEIGHT, box);
            int widest = 0;
            for (IWidget child : box.getChildren()) {
                widest = Math.max(widest, child.getArea().w());
            }
            // Whatever else it drops, the label column is never squeezed to nothing.
            assertTrue("at " + width + "px every column collapsed; widest was " + widest,
                       widest >= PlannerWidgets.MIN_LABEL);
        }
    }

    @Test
    public void theBadgeIsNeverPartiallyDrawn() {
        // All-or-nothing. A shrunken badge is a truncated badge, and a truncated badge is
        // always a bug because the vocabulary is fixed.
        for (int width = 40; width <= 400; width += 4) {
            int badge = PlannerWidgets.badgeWidthFor(width, PlannerWidgets.ICON
                                                     + PlannerWidgets.GAP
                                                     + PlannerWidgets.QTY
                                                     + PlannerWidgets.GAP);
            assertTrue("at " + width + "px the badge came out " + badge
                       + ", which is neither nothing nor the whole vocabulary",
                       badge == 0 || badge == PlannerWidgets.BADGE);
        }
    }

    @Test
    public void aFullWidthTreeRowStillCarriesItsBadgeEvenAtTheIndentCap() {
        // The all-or-nothing rule must not cost the tree its badges, which is the case it was
        // NOT introduced for. The deepest indent is the worst case.
        int deepestStart = PlannerWidgets.MAX_INDENT_DEPTH * PlannerWidgets.INDENT
                           + PlannerWidgets.ICON + PlannerWidgets.GAP
                           + PlannerWidgets.QTY + PlannerWidgets.GAP;
        assertEquals("a fully indented tree row must still show its badge",
                     PlannerWidgets.BADGE,
                     PlannerWidgets.badgeWidthFor(PlannerWidgets.CONTENT_WIDTH, deepestStart));
    }

    /**
     * The shared row seam (#19 Phase 3b): same content, caller's geometry.
     *
     * The diagram positions a couple of hundred of these at absolute coordinates, so the one
     * thing it needs from this method is that it honours BOTH dimensions and assumes nothing
     * about a panel.
     */
    @Test
    public void theSharedNodeContentHonoursWhateverSizeItIsGiven() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        int[][] sizes = {{120, 10}, {80, 14}, {200, 20}};
        for (int[] size : sizes) {
            com.cleanroommc.modularui.widget.ParentWidget<?> box =
                    PlannerWidgets.planNodeContent(node, size[0], size[1], PlannerActions.NONE);
            ModularPanel panel = HeadlessLayout.layOutPanel(
                    "node", PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT, box);
            assertEquals("width " + size[0], size[0], box.getArea().w());
            assertEquals("height " + size[1], size[1], box.getArea().h());
            for (IWidget widget : HeadlessLayout.flatten(box)) {
                Area area = widget.getArea();
                assertTrue("a child has no box at " + size[0] + "x" + size[1] + ": " + area,
                           area.w() > 0 && area.h() > 0);
                assertTrue("a child overflows the box it was given: " + area,
                           area.ex() <= box.getArea().ex());
            }
            assertTrue(panel.getArea().w() > 0);
        }
    }

    /**
     * DELETED AND REPLACED: this used to assert `theSharedNodeContentIsNotClickable`.
     *
     * It pinned a claim that turned out to be false -- my comment on `planNodeContent` said a
     * canvas of clickable rows would fight the viewport's hit-testing, and s1harness measured
     * in #185 that `getWidgetsAt` routes correctly through both the scroll offset and the zoom
     * matrix. The test was doing its job: it held the code to what the comment said. What was
     * wrong was the comment, which was an assumption written in the register of a constraint.
     *
     * Worth leaving this note rather than deleting silently. A green test asserting a false
     * claim is not neutral -- it is the claim's strongest-looking evidence, and the next
     * person to doubt the comment finds an assertion agreeing with it.
     *
     * The replacements are `aDiagramNodeOpensTheNodeMenuLikeATreeRowDoes` and
     * `theThreeArgumentNodeContentIsInertAndNotSilentlyWiredToSomething`.
     */

    /** The panels shown before a plan exists. Each says something different on purpose. */
    @Test
    public void everyPlannerStateRendersItsOwnMessage() {
        PlannerState[] states = {
            PlannerState.IDLE,
            PlannerState.loading("loading graph, 40%"),
            PlannerState.solving("solving"),
            PlannerState.failed("no graph at data/graph.json"),
        };
        java.util.Set<String> messages = new java.util.HashSet<String>();
        for (PlannerState state : states) {
            ModularPanel panel = PlannerWidgets.statePanel(state);
            HeadlessLayout.layOut(panel);
            assertEquals(PlannerWidgets.PANEL_WIDTH, panel.getArea().w());
            assertTrue("a state panel must fit the screen",
                       panel.getArea().h() <= HeadlessLayout.SCREEN_HEIGHT);
            for (IWidget widget : HeadlessLayout.flatten(panel)) {
                assertTrue("state " + state.kind() + " left a widget unsized",
                           widget.getArea().w() > 0 && widget.getArea().h() > 0);
            }
            messages.add(state.message());
        }
        assertEquals("four states, four distinct sentences -- a shared one would make "
                     + "'still loading' and 'no graph found' indistinguishable",
                     4, messages.size());
        assertEquals("only a failure is red", NodeStatus.INK_NEED,
                     PlannerState.failed("x").colour());
        assertEquals(NodeStatus.INK_MUTED, PlannerState.loading("x").colour());
    }

    @Test
    public void aPopulatedTodoPanelIsTallerThanAnEmptyOne() {
        // BELOW the cap, which is where the panel still sizes to its contents.
        // `plan-in-stock` has an empty shopping list; `plan-truncated` has nineteen rows and
        // is already at the cap either way, so it would compare two identical heights and pass
        // for the wrong reason. `aTodoListLongerThanTheScreenScrollsRatherThanGrowing` covers
        // the other side.
        PlanView plan = PlanFixtures.load("plan-in-stock");
        assertTrue("this fixture must be under the cap for the comparison to mean anything",
                   plan.shoppingList().isEmpty());
        PlanBook book = new PlanBook();
        book.setTodo("minecraft:iron_ingot", 64L);
        book.setTodo("fluid:water", 934_400L);
        ModularPanel empty = PlannerWidgets.todoPanel(plan, emptyBook(), PlannerActions.NONE);
        ModularPanel full = PlannerWidgets.todoPanel(plan, book, PlannerActions.NONE);
        HeadlessLayout.layOut(empty);
        HeadlessLayout.layOut(full);
        assertTrue(full.getArea().h() > empty.getArea().h());
    }

    /**
     * TWO TREE ROWS FOR TWO DIFFERENT ITEMS NEVER RENDER THE SAME WORDS. #232.
     *
     * The defect as a player meets it, asserted through the widgets the screen actually
     * builds rather than through the helper that writes the string. That matters here: the
     * disambiguation is decided by {@link PlannerWidgets#tree}, which asks once for the whole
     * list, so a test that called {@link NodeRowText#meta(PlanNode, boolean)} directly would
     * pass while the screen still drew two identical rows.
     *
     * THE POPULATION IS MEASURED AND IT IS NOT THE ONE #232 FILED. The issue says
     * `plan-same-name` draws six rows called "Iron Plate"; it draws ONE, because its six keys
     * are on the PACK and the plan routes through exactly one of them -- which is the property
     * that fixture exists to prove. Counted across every plan fixture instead: 13 labels carry
     * more than one distinct key, on 61 tree rows out of 2,288 nodes in 21 fixtures, and none
     * is in `plan-same-name`.
     *
     * AND IT MOVED UNDER THIS BRANCH, WHICH IS WHY THE COUNT IS ASSERTED. The first version of
     * this test said 17 across 2,488 nodes, and that was true of the base it was written on.
     * Rebasing onto the schema-8 fixture regeneration retired `Aluminum Ingot` and
     * `Divination Catalyst` outright and dropped `fluid:crystallized_obsidian` from
     * `Crystallized Obsidian`, taking 17 collisions to 13. A HARD-CODED FIXTURE LIST WOULD HAVE
     * SURVIVED THAT SILENTLY, so the loop walks {@link PlanFixtures#names()} and the count
     * below is what fails: the population is a fact about the fixtures, and a test that lets it
     * drift is a test measuring a set nobody chose.
     *
     * DISTINCT KEYS, NOT REPEATED ROWS. A tree draws the same key on every row that uses it --
     * `plan-fluid-chain` is 703 nodes for 262 labels -- so two identical rows are only a defect
     * when they are two different ITEMS. The expectation is built from the plan model here, so
     * that the code under test is not also the thing deciding what the answer should be.
     */
    @Test
    public void twoTreeRowsForDifferentItemsAreTellableApart() {
        List<String> problems = new java.util.ArrayList<String>();
        List<String> ambiguous = new java.util.ArrayList<String>();
        int checked = 0;
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            if (plan.tree() == null) {
                continue;
            }
            List<PlanNode> nodes = new java.util.ArrayList<PlanNode>();
            flattenRows(plan.tree(), nodes);

            // THE TREE ALONE, NOT `plannerPanel`. The summary lists on that panel already
            // disambiguate their own rows (#190), and their lines start with the same labels --
            // so a whole-panel version of this test would find the shopping list's keys and
            // pass while the tree still drew two identical rows. Built by hand rather than with
            // `layOutPanel`, which dumps every widget to stdout: 2,288 rows of that is
            // megabytes of log for no reader.
            ModularPanel panel = new ModularPanel("tree-" + fixture);
            panel.size(PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT);
            panel.child(PlannerWidgets.tree(plan, PlannerWidgets.CONTENT_WIDTH,
                                            PlannerWidgets.PANEL_HEIGHT, recorder()));
            HeadlessLayout.layOut(panel);
            List<String> lines = rowLabelLines(fixture, nodes, panel);

            // KEY -> THE LINES ITS ROWS DRAW, which is the whole correction described above.
            java.util.Map<String, java.util.Map<String, java.util.Set<String>>> byLabel =
                    new java.util.LinkedHashMap<String,
                            java.util.Map<String, java.util.Set<String>>>();
            for (int i = 0; i < nodes.size(); i++) {
                String label = NodeRowText.label(nodes.get(i));
                java.util.Map<String, java.util.Set<String>> linesByKey = byLabel.get(label);
                if (linesByKey == null) {
                    linesByKey = new java.util.LinkedHashMap<String, java.util.Set<String>>();
                    byLabel.put(label, linesByKey);
                }
                java.util.Set<String> drawn = linesByKey.get(nodes.get(i).key());
                if (drawn == null) {
                    drawn = new java.util.LinkedHashSet<String>();
                    linesByKey.put(nodes.get(i).key(), drawn);
                }
                drawn.add(lines.get(i));
            }

            for (java.util.Map.Entry<String,
                    java.util.Map<String, java.util.Set<String>>> entry : byLabel.entrySet()) {
                if (entry.getValue().size() < 2) {
                    continue;
                }
                checked++;
                // COLLECTED, NOT THROWN, so one run names every label a player cannot tell
                // apart instead of the first one. Each of these costs a gated container to
                // learn, and a fail-fast assertion turns "what does this fix still miss" into
                // one question per run.
                String problem = indistinguishable(fixture, entry.getKey(), entry.getValue());
                if (problem != null) {
                    problems.add(problem);
                    ambiguous.add(fixture + " / " + entry.getKey());
                }
            }
        }
        // THE CONTROL, ASSERTED FIRST. If the fixtures ever stop holding a collision this test
        // passes while asserting nothing, which is the unearned zero this repository keeps
        // rediscovering -- and it would pass most loudly exactly when the population moved.
        assertEquals("the fixtures must still carry the collisions this exists to catch; "
                     + "if a regeneration moved the population, measure the new one and say so "
                     + "here rather than editing this number to match",
                     13, checked);

        // NOT EMPTY, AND THE MEASUREMENT IS WHY. The first version of this asserted the strong
        // form -- nothing ambiguous at all -- on the strength of a detector that could not see
        // the case that breaks it. Measured on UNMODIFIED master through this same walk: SIX
        // labels are ambiguous, not three. Three are `Soul Vial`, whose two rows have empty
        // meta and both draw the bare label; three are `Iron Ore`, where
        // `minecraft:iron_ore` and `erebus:ore_iron` both draw
        //
        //     Iron Ore · mined, not crafted            29 characters into a 29-column row
        //
        // The fix resolves the `Soul Vial` three, which is what it was built to do. It CANNOT
        // resolve the `Iron Ore` three at any width: that line is already exactly full, so
        // every fragment is an eviction, and no-eviction means the row keeps what it drew.
        // That is #273's population stated as a test rather than as a hope -- and the set is
        // pinned rather than counted, so a NEW ambiguous label fails here just as loudly as a
        // fixed one, and neither can hide inside a number.
        assertEquals("labels whose rows still cannot be told apart on their line; these are "
                     + "#273's, and this list is pinned so that gaining one fails as loudly as "
                     + "losing one:\n  " + join(problems, "\n  "),
                     java.util.Arrays.asList("plan-fluid-chain / Iron Ore",
                                             "plan-multiblock / Iron Ore",
                                             "plan-no-machine-declared / Iron Ore"),
                     ambiguous);
    }

    /**
     * HOW FAR THE FIX ACTUALLY REACHES, ASSERTED RATHER THAN READ OFF A RUN. #232.
     *
     * THIS EXISTS BECAUSE THE NUMBER HAD NO HOME. The reach was quoted for two rounds of review
     * as "23 of 88" and then as "22 of 61", and neither was ever asserted anywhere: the first
     * came from a model that omitted `machineBit`'s state suffix, the second from reading a
     * throwaway probe's summary line during a green run. A number nothing checks is a number
     * that is true until the day it is not, and nothing tells you which day that was -- which
     * is exactly what happened to the first one.
     *
     * IT ALSO PINS THE DENOMINATOR, and that half matters as much. 61 is the size of the
     * population the fix operates on; if a fixture regeneration moves it, a reach of 23 means
     * something different and this fails rather than quietly re-scaling. The same regeneration
     * already took the collision count from 17 to 13 under this branch once.
     *
     * 22 UNTIL #333 AND 23 AFTER IT, and the method was renamed with the number rather than
     * having the number quietly edited under the old name -- which is precisely what the
     * paragraph above says goes wrong with a figure nothing checks. The reason for the extra row
     * is in the assertion itself.
     *
     * AND IT IS A LABEL-COLUMN TEST WITHOUT SAYING SO, WHICH #332 DISCOVERED THE HARD WAY. The
     * reach is decided by `labelAndMeta`, which takes the fragment only if the whole line still
     * fits the column -- so this number moves with `PlannerWidgets.INDENT` and
     * `MAX_INDENT_DEPTH`. It survived #332's change (INDENT 6 -> 10, cap 8 -> 5) at 22 of 61
     * because the product was held near 48px and the column at the cap stayed 29 characters, and
     * the deliberate mutation to indent 10 / cap 8 -- which narrows it to 24 -- takes this test
     * red along with `theWholeIndentBudgetStillLeavesTheLabelColumnItsMeasuredFloor`. Recorded
     * because the next person moving those constants will want to know this is one of the tests
     * that answers; the mutation row is in `evidence-331-332/mutation-reds.txt`.
     *
     * AND IT IS A META-RUN TEST TOO, WHICH #333 DISCOVERED THE SAME WAY. The fragment is taken
     * only if the whole line still fits, and the line is `label (fragment) · meta` -- so evicting
     * the machine from `meta` widens the reach as surely as widening the column does. That is
     * where the extra row came from.
     *
     * The counted property is the row SHAPE the fix emits -- `label (fragment)` -- read off the
     * widgets the screen builds, not off the helper that writes the string.
     */
    @Test
    public void twentyThreeOfSixtyOneCollidingRowsCanAffordTheFragment() {
        int collidingRows = 0;
        int tookFragment = 0;
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            if (plan.tree() == null) {
                continue;
            }
            List<PlanNode> nodes = new java.util.ArrayList<PlanNode>();
            flattenRows(plan.tree(), nodes);

            java.util.Map<String, java.util.Set<String>> keysByLabel =
                    new java.util.HashMap<String, java.util.Set<String>>();
            for (PlanNode node : nodes) {
                String label = NodeRowText.label(node);
                java.util.Set<String> keys = keysByLabel.get(label);
                if (keys == null) {
                    keys = new java.util.HashSet<String>();
                    keysByLabel.put(label, keys);
                }
                keys.add(node.key());
            }

            ModularPanel panel = new ModularPanel("reach-" + fixture);
            panel.size(PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT);
            panel.child(PlannerWidgets.tree(plan, PlannerWidgets.CONTENT_WIDTH,
                                            PlannerWidgets.PANEL_HEIGHT, recorder()));
            HeadlessLayout.layOut(panel);
            List<String> lines = rowLabelLines(fixture, nodes, panel);

            for (int i = 0; i < nodes.size(); i++) {
                String label = NodeRowText.label(nodes.get(i));
                if (keysByLabel.get(label).size() < 2) {
                    continue;
                }
                collidingRows++;
                if (lines.get(i).startsWith(label + " (")) {
                    tookFragment++;
                }
            }
        }
        assertEquals("the population the fix operates on; if a fixture regeneration moved it, "
                     + "re-measure the reach below rather than rescaling it",
                     61, collidingRows);
        // 23 SINCE #333, RE-MEASURED AND NOT RESCALED, WHICH IS WHAT THIS TEST'S OWN JAVADOC
        // ASKS FOR. The population is unchanged at 61; one more row can now afford the fragment,
        // and it is a named one. `plan-variant-table`'s `Brown Concrete` was at capacity ONLY
        // because its whole meta run was `Chiseling (buildable)`, 21 of its 37 characters -- the
        // machine, which #333 moved into an icon and a tooltip. With the run empty the row has
        // room to spare and takes `(chisel)`.
        //
        // WHY ONE AND NOT MORE, WHICH IS THE HONEST HALF. The eviction frees width only on rows
        // whose meta run WAS the machine, and the rest of #273's population is not that shape:
        // `Iron Ore`'s meta is `mined, not crafted`, a `note`, so it is 29 characters before and
        // after and still cannot afford the 12 the fragment costs.
        // `aRowWithRoomTakesTheFragmentAndARowAtCapacityLosesNothingItDrew` names both rows and
        // asserts the two directions separately, so this number cannot move without one of them
        // moving too.
        assertEquals("rows that can afford to say which item they are; the other "
                     + (61 - 23) + " are at capacity and keep what they drew, which is #273",
                     23, tookFragment);
    }

    /**
     * The complaint for a label whose distinct keys draw a line in common, or null.
     *
     * DO NOT REDUCE THIS TO A COUNT OF DISTINCT STRINGS. That is what it was, it passed
     * `Iron Ore`, and the count and the correct answer agree on every case except the one
     * that matters.
     *
     * COUNTING DISTINCT LINES WAS THE BUG, AND IT WAS MEASURED RATHER THAN SPOTTED. The first
     * version took the label's rows, collected the distinct strings among them, and passed when
     * there were at least as many strings as keys. `Iron Ore` in `plan-fluid-chain` defeats
     * that exactly:
     *
     *     minecraft:iron_ore   draws  `Iron Ore · any of 14 · min...`  AND  `Iron Ore · mined...`
     *     erebus:ore_iron      draws                                        `Iron Ore · mined...`
     *
     * Two keys, two distinct strings, and the old rule said tellable -- while the two rows a
     * player is actually looking at are the same words for different items. ONE KEY DRAWING
     * SEVERAL SHAPES CANNOT PAY FOR ANOTHER KEY'S. So this asks the question a player asks: is
     * there a line that two different items both draw?
     *
     * EXTRACTED SO A CONTROL CAN PROVE IT FIRES, which is the other reason it is not inline.
     * {@link #theCollisionDetectorReportsARowItCannotTellApart} feeds it the cases it must
     * report and the cases it must not -- including the shape above, which is the one the old
     * control was missing and the reason the old rule survived.
     */
    private static String indistinguishable(String fixture, String label,
                                            java.util.Map<String,
                                                    java.util.Set<String>> linesByKey) {
        List<String> keys = new java.util.ArrayList<String>(linesByKey.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                for (String line : linesByKey.get(keys.get(i))) {
                    if (linesByKey.get(keys.get(j)).contains(line)) {
                        return fixture + " / " + label + ": " + keys.get(i) + " and "
                               + keys.get(j) + " are different items and both draw ["
                               + line + "]";
                    }
                }
            }
        }
        return null;
    }

    /**
     * The label line each tree row draws, in row order, one per node.
     *
     * READ PER ROW BECAUSE A LINE HAS TO BE ATTRIBUTED TO A KEY. A flat list of every text on
     * the panel cannot say which item drew which line, and that is precisely what the question
     * "do two different items draw the same words" needs.
     *
     * THE ANCHOR IS THE QUANTITY AND IT COMES FROM THE PLAN. `PlannerWidgets.row` draws an
     * optional token mark, then the quantity, then the label, then the badge -- three texts on
     * an ordinary row and FOUR on a token row -- so the label's index within a row is not
     * fixed. A probe written against a fixed stride of two was wrong on every fixture and
     * silently so; it was caught only because it asserted its own mapping. Finding the text
     * equal to `NodeRowText.quantity(node.need())` and taking the one after it cannot drift,
     * because the expected string is derived from the node rather than from the widgets.
     */
    private static List<String> rowLabelLines(String fixture, List<PlanNode> nodes,
                                              ModularPanel panel) {
        List<PlannerWidgets.ClickableGroup> rows =
                new java.util.ArrayList<PlannerWidgets.ClickableGroup>();
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (widget instanceof PlannerWidgets.ClickableGroup) {
                rows.add((PlannerWidgets.ClickableGroup) widget);
            }
        }
        assertEquals("the tree must draw exactly one row per node in " + fixture,
                     nodes.size(), rows.size());

        List<String> lines = new java.util.ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            // THE WIDEST TEXT CELL IS THE LABEL, AND IT IS FOUND THAT WAY BECAUSE CHILD ORDER
            // WAS NEVER THE PROPERTY THIS WANTED (#333).
            //
            // This used to find the quantity in child order and take the NEXT widget. That held
            // only while `row` happened to add [icon] quantity label [badge] in that sequence,
            // and #333 anchored the quantity and the badge into a right cluster added AFTER the
            // label -- so "the one after the quantity" became the BADGE, and this helper started
            // reporting `[NEED]` as every row's label. Two tests then failed with numbers that
            // looked like a production regression: `twentyTwoOfSixtyOne...` measured a reach of
            // zero and `twoTreeRowsForDifferentItemsAreTellableApart` reported four new
            // ambiguous labels, all of which drew the same badge as each other.
            //
            // It is the same fragility `VIEW_LABELS`' javadoc records about an index into the
            // view bar: correct until someone adds a widget, then silently about the wrong thing.
            // A width cannot drift that way. The tree is always built at `CONTENT_WIDTH` here, so
            // the label column is 174px at its narrowest against a badge of at most 90 and a
            // quantity of at most 52 -- and the assertion below refuses to guess if that ever
            // stops being true, rather than quietly returning a badge again.
            com.cleanroommc.modularui.widgets.TextWidget<?> label = null;
            List<String> inRow = new java.util.ArrayList<String>();
            for (IWidget widget : HeadlessLayout.flatten(rows.get(i))) {
                if (!(widget instanceof com.cleanroommc.modularui.widgets.TextWidget)) {
                    continue;
                }
                com.cleanroommc.modularui.widgets.TextWidget<?> text =
                        (com.cleanroommc.modularui.widgets.TextWidget<?>) widget;
                inRow.add(text.getKey().getFormatted());
                if (label == null || text.getArea().w() > label.getArea().w()) {
                    label = text;
                }
            }
            assertNotNull("row " + i + " of " + fixture + " drew no text at all", label);
            assertTrue("row " + i + " of " + fixture + " has no cell wider than the badge"
                       + " column, so the label cannot be identified by width; drew " + inRow,
                       label.getArea().w() > PlannerWidgets.BADGE);
            lines.add(label.getKey().getFormatted());
        }
        return lines;
    }

    private static void flattenRows(PlanNode node, List<PlanNode> into) {
        if (node == null) {
            return;
        }
        into.add(node);
        for (PlanNode child : node.children()) {
            flattenRows(child, into);
        }
    }

    /**
     * THE CONTROL ON THE DETECTOR ITSELF, and it is not ceremony.
     *
     * The test above is "collect failures, assert the list is empty", which this repository has
     * now been bitten by repeatedly: a search that has quietly stopped matching reports perfect
     * compliance in exactly the same words as real compliance. This proves the detector can
     * still SAY something -- and, in the second half, that it does not say it about a tree that
     * is fine, because a detector that reports everything is no more use than one that reports
     * nothing.
     */
    @Test
    public void theCollisionDetectorReportsARowItCannotTellApart() {
        String reported = indistinguishable("synthetic", "Widget",
                                            drawn("mod:a", lines("Widget"),
                                                  "mod:b", lines("Widget")));
        assertTrue("two items drawing one line must be reported", reported != null);
        assertTrue("the complaint must name the label: " + reported,
                   reported.contains("Widget"));

        assertTrue("two items drawing two different lines must NOT be reported",
                   indistinguishable("synthetic", "Widget",
                                     drawn("mod:a",
                                           lines("Widget" + NodeRowText.SEPARATOR + "mod:a"),
                                           "mod:b",
                                           lines("Widget" + NodeRowText.SEPARATOR + "mod:b")))
                           == null);

        // THE SHAPE THE FIX ITSELF PRODUCES, which the two cases above do not cover and which
        // is how an earlier control passed while the detector was blind. Both of them use the
        // `label · meta` form, so a matcher that recognised only that form satisfied them --
        // and then found ZERO rows for `Soul Vial (32d8050d982c)` and reported the fix working
        // as the fix failing. A CONTROL ONLY COVERS THE INPUTS IT WAS GIVEN: it is not enough
        // for the cases to be one positive and one negative, they have to span the shapes the
        // code under test can emit.
        assertTrue("a disambiguated row must be recognised as one of the label's rows",
                   indistinguishable("synthetic", "Widget",
                                     drawn("mod:a", lines("Widget (a)"),
                                           "mod:b", lines("Widget (b)"))) == null);
        assertTrue("two rows disambiguated to the SAME text are still indistinguishable",
                   indistinguishable("synthetic", "Widget",
                                     drawn("mod:a", lines("Widget (x)"),
                                           "mod:b", lines("Widget (x)"))) != null);

        // AND THE CASE THAT KILLED THE PREVIOUS RULE, WHICH IS A REAL ROW AND NOT A HYPOTHETICAL.
        // `Iron Ore` in `plan-fluid-chain`: `minecraft:iron_ore` appears at several depths and
        // draws TWO different lines, `erebus:ore_iron` draws one, and that one is a line
        // `minecraft:iron_ore` also draws. Counting distinct strings gives two for two keys and
        // calls it tellable; the player is looking at two identical rows for different items.
        // ONE KEY DRAWING SEVERAL SHAPES CANNOT PAY FOR ANOTHER KEY'S -- that is the whole
        // difference between the old rule and this one, and this is the case that says so.
        assertTrue("a key drawing two shapes must not cover for a second key drawing one of "
                   + "them",
                   indistinguishable("synthetic", "Iron Ore",
                                     drawn("minecraft:iron_ore",
                                           lines("Iron Ore · any of 14 · min...",
                                                 "Iron Ore · mined, not crafted"),
                                           "erebus:ore_iron",
                                           lines("Iron Ore · mined, not crafted"))) != null);
    }

    /** A key -> drawn-lines map for two keys, for the control above. */
    private static java.util.Map<String, java.util.Set<String>> drawn(
            String firstKey, java.util.Set<String> firstLines,
            String secondKey, java.util.Set<String> secondLines) {
        java.util.Map<String, java.util.Set<String>> map =
                new java.util.LinkedHashMap<String, java.util.Set<String>>();
        map.put(firstKey, firstLines);
        map.put(secondKey, secondLines);
        return map;
    }

    private static java.util.Set<String> lines(String... drawn) {
        return new java.util.LinkedHashSet<String>(java.util.Arrays.asList(drawn));
    }

    /**
     * BOTH DIRECTIONS OF THE WIDTH DECISION -- AND THEY NEEDED TWO FIXTURES, NOT ONE. #232.
     *
     * THE FIRST VERSION OF THIS TEST ASSERTED A ROW THAT CANNOT EXIST, and it is worth writing
     * down because the mistake was invisible: it put both branches on `plan-variant-table`,
     * whose two "Brown Concrete" rows look like they straddle the rule and do not. The depth-0
     * row is ALREADY over its column before any fragment is offered --
     *
     *     Brown Concrete · Chiseling (buildable)     38 characters into a 37-column row
     *
     * -- because `machineBit` appends the STATE, `shown + " (" + machineStateLabel(...) + ")"`.
     * Omit that suffix, as the model that picked this fixture did, and the line measures 27 and
     * looks like it has room to spare. So BOTH rows decline, the positive branch had nothing to
     * find, and the test failed against code that was working correctly.
     *
     * THE FIX WAS TO MOVE THE TEST, NOT TO LOOSEN IT. Relaxing the assertion until a fixture
     * with no take-the-fragment row could satisfy it would have deleted the only check that the
     * feature ever fires. So the positive branch moved to a row measured to have the room:
     *
     *   `plan-fluid-chain`,   Soul Vial, empty meta      -> takes `(32d8050d982c)`
     *   `plan-variant-table`, Brown Concrete, at capacity -> declines, keeps "Fluid Transposer"
     *
     * The negative branch is the no-eviction rule stated as a test: a row that cannot afford
     * the fragment must still say WHICH MACHINE, because that is what renders on master today
     * and #232 must not take it away. Those rows are #273's population.
     *
     * <h2>#333 RE-POINTED THE NEGATIVE BRANCH, EXACTLY AS THE PARAGRAPH ABOVE SAID IT WOULD</h2>
     *
     * `Brown Concrete` was at capacity BECAUSE of the machine: its whole meta run was
     * `Chiseling (buildable)`, 21 of its 37 characters. #333 moved the machine to an icon and a
     * tooltip, so that row's meta is now empty, it has room to spare, and it takes the fragment
     * -- which means it is no longer an example of this branch. The test's own instruction was to
     * re-point it at a row that is, not to loosen the assertion, so it is pointed at `Iron Ore`.
     *
     * `Iron Ore` IS THE ROW THAT SURVIVES THE EVICTION AT CAPACITY, and it is #273's own example.
     * Its meta is `mined, not crafted`, a `note` rather than a machine, so #333 freed it nothing:
     * `Iron Ore · mined, not crafted` is 29 characters and the disambiguated form is 41 against a
     * 37-character column at depth 0. It declines, and it keeps what it drew.
     *
     * AND WHAT "KEEPS" MEANS CHANGED WITH IT, WHICH IS THE HONEST HALF. The old branch asserted a
     * declining row still said WHICH MACHINE. There is no machine in a row's text any more, so
     * that assertion cannot be made and must not be faked; what the rule protects is that a row
     * which cannot afford the fragment loses NOTHING it was already drawing, and that is what is
     * asserted -- the note arrives whole and un-truncated. The machine's own survival moved to
     * `theMachineCellCarriesTheWordsTheRowStoppedPrinting` and to `NodeRowTextTest`.
     */
    @Test
    public void aRowWithRoomTakesTheFragmentAndARowAtCapacityLosesNothingItDrew() {
        // THE DIGEST TAIL, NOT MERELY "SOMETHING IN BRACKETS". Two `enderio:item_soul_vial:1`
        // keys differ only after the '#', so the fragment rule's whole job here is to skip the
        // three tokens they share; asserting only that a bracket appeared would pass on
        // `Soul Vial (1)`, which is unique and tells a player nothing.
        List<String> fluidChain = treeLines("plan-fluid-chain");
        boolean firstVial = false;
        boolean secondVial = false;
        for (String line : fluidChain) {
            if (line.startsWith("Soul Vial (")) {
                assertFalse("a row that took the fragment must not have been cut to fit it: "
                            + line, line.endsWith("..."));
            }
            if (line.startsWith("Soul Vial (32d8050d982c)")) {
                firstVial = true;
            }
            if (line.startsWith("Soul Vial (40f3a0f3892d)")) {
                secondVial = true;
            }
        }
        assertTrue("the row with room must say which item it is; drew "
                   + matching(fluidChain, "Soul Vial"), firstVial);
        assertTrue("both Soul Vial rows have room, so both must say which item they are; drew "
                   + matching(fluidChain, "Soul Vial"), secondVial);

        // AND THE ROW THAT #333 GAVE ROOM TO, so this test records the move rather than only
        // surviving it. `Brown Concrete` was the at-capacity example because its meta run WAS the
        // machine; with the machine gone it has room and takes the fragment.
        List<String> variantTable = treeLines("plan-variant-table");
        boolean concreteTookIt = false;
        for (String line : variantTable) {
            if (line.startsWith("Brown Concrete (")) {
                concreteTookIt = true;
                assertFalse("a row that took the fragment must not have been cut to fit it: "
                            + line, line.endsWith("..."));
            }
        }
        assertTrue("Brown Concrete was at capacity only because `Chiseling (buildable)` was 21 "
                   + "of its 37 characters; with the machine in an icon it must now afford the "
                   + "fragment. If this fails the eviction did not reach the row. Drew "
                   + matching(variantTable, "Brown Concrete"), concreteTookIt);

        // THE AT-CAPACITY BRANCH, RE-POINTED AT A ROW THE EVICTION DID NOT HELP. `Iron Ore`'s
        // meta is a `note` and not a machine, so it is 29 characters before and after #333
        // against a 37-character column, and the disambiguated form is 41.
        List<String> declining = treeLines("plan-fluid-chain");
        boolean keptWhatItDrew = false;
        for (String line : declining) {
            assertFalse("Iron Ore is the AT-CAPACITY example and must decline the fragment; if "
                        + "it now fits, this branch needs a different row: " + line,
                        line.startsWith("Iron Ore ("));
            if (line.startsWith("Iron Ore ") && line.contains("mined, not crafted")) {
                keptWhatItDrew = true;
                assertFalse("...and it must keep what it drew WHOLE: " + line,
                            line.endsWith("..."));
            }
        }
        assertTrue("the row at capacity must lose nothing it was already drawing to a "
                   + "disambiguator it cannot afford; drew "
                   + matching(declining, "Iron Ore"), keptWhatItDrew);
    }

    /** Every text the tree draws for `fixture`, through the widgets the screen builds. */
    private static List<String> treeLines(String fixture) {
        PlanView plan = PlanFixtures.load(fixture);
        ModularPanel panel = new ModularPanel("tree-" + fixture);
        panel.size(PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT);
        panel.child(PlannerWidgets.tree(plan, PlannerWidgets.CONTENT_WIDTH,
                                        PlannerWidgets.PANEL_HEIGHT, recorder()));
        HeadlessLayout.layOut(panel);
        return texts(panel);
    }

    /**
     * The lines starting with `label`, for a failure message.
     *
     * `plan-fluid-chain` draws 703 rows and a failure that pastes all of them is a failure
     * nobody reads. What a reader needs is the rows the assertion was looking at.
     */
    private static String matching(List<String> lines, String label) {
        List<String> hits = new java.util.ArrayList<String>();
        for (String line : lines) {
            if (line.startsWith(label)) {
                hits.add(line);
            }
        }
        return hits.toString();
    }

    private static String join(List<String> lines, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static PlannerActions recorder() {
        return new Recorder();
    }

    /** As if Phase 4 had installed itself, so the two extra menu entries appear. */
    private static PlannerActions withRecipeViewer() {
        Recorder recorder = new Recorder();
        recorder.recipeViewerAvailable = true;
        return recorder;
    }

    /**
     * Records what a click asked for, so the menu can be tested as BEHAVIOUR.
     *
     * The alternative -- asserting the menu contains a line reading "Add to TODO" -- passes
     * on a menu whose entries do nothing, which is exactly what the first version of this
     * panel was. Clicking is reachable headlessly because a row is `Interactable` and
     * `onMousePressed` is an ordinary method.
     */
    /**
     * A node built with actions opens THEIR node menu, the same way a tree row does.
     *
     * RENAMED FROM `aDiagramNodeOpensTheNodeMenuLikeATreeRowDoes`, AND THE RENAME IS THE POINT
     * (#325). Under the old name this test was cited as the assertion covering the diagram, and it
     * was green for the whole three releases the diagram was inert -- because it built the node
     * itself, with its own recorder, and never asked `FlowCanvas` what IT built. `FlowCanvas`
     * passed `PlannerActions.NONE`. The test performed the wiring production omitted, which is the
     * #191/#323 family one level down, and a test that carries a diagram's name while asserting
     * nothing about the diagram is worse than no test: it is the claim's strongest-looking
     * evidence.
     *
     * SO THE DIAGRAM'S OWN WITNESS MOVED TO `FlowCanvasTest.everyDiagramNodeOpensTheMenuForThe`
     * `NodeThatWasClicked`, WHICH IS WHERE IT CAN LIVE. That file may import `client.flow`; this
     * one may not -- `PlannerLayoutTest` writes `FlowLayout`'s node width out as a literal for
     * exactly that reason, and importing the canvas here "would be the first thing in the package
     * to do so". What is left here is the factory's own contract: given actions, the node it
     * returns is a single click target wired to them. That is worth keeping and it is all this can
     * honestly claim.
     *
     * MEASURED BY s1harness IN #185 for the half neither of these tests can reach: `flow-hit`
     * parks a real cursor and `IWidget.isHovering()` agrees with the layout at zoom 0.5, 1.0 and
     * 2.0, so `getWidgetsAt` routes correctly through both the scroll offset and the zoom matrix.
     */
    @Test
    public void aNodeContentBuiltWithActionsOpensTheirNodeMenu() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        Recorder recorder = new Recorder();
        ParentWidget<?> box = PlannerWidgets.planNodeContent(node, 214, 26, recorder);
        HeadlessLayout.layOut(wrap(box));

        List<PlannerWidgets.ClickableGroup> clickable = clickables(box);
        assertEquals("the node itself is the click target, not a child", 1, clickable.size());
        clickable.get(0).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("menu:" + node.key()), recorder.calls);
    }

    /**
     * `PlannerActions.NONE` really is inert, and a caller now has to ask for it by name.
     *
     * WHAT THIS USED TO ASSERT AND WHY IT CHANGED. It was
     * `theThreeArgumentNodeContentIsInertAndNotSilentlyWiredToSomething`, about an overload that
     * supplied `PlannerActions.NONE` for you. That overload is DELETED (#325): its own javadoc
     * predicted "a canvas of nodes that look right and do nothing when clicked", `FlowCanvas` was
     * built on it, and naming the hazard in a comment did not stop the hazard. An inert node is
     * now something a call site writes out, so the failure is visible where it is made.
     *
     * THE ASSERTION IS STILL WORTH HAVING, because the constant is what every layout test in this
     * file passes: if `NONE` ever started doing something, dozens of assertions about geometry
     * would begin exercising behaviour and this is the only place that would say so.
     */
    @Test
    public void theInertActionsConstantIsStillInert() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        ParentWidget<?> box = PlannerWidgets.planNodeContent(node, 214, 26, PlannerActions.NONE);
        HeadlessLayout.layOut(wrap(box));
        List<PlannerWidgets.ClickableGroup> clickable = clickables(box);
        assertEquals(1, clickable.size());
        // It clicks, and nothing happens. `PlannerActions.NONE` rather than a null check at
        // the call site -- see that constant for why.
        clickable.get(0).onMousePressed(0);
    }

    // -- #327: the flow panel's chrome ----------------------------------------------------------

    /**
     * A TRUNCATED PLAN'S DIAGRAM SAYS SO. The witness for #327.
     *
     * `NodeRowText.truncationWarning` carries the comment "SAID, NEVER SILENT", and until now it
     * was rendered only by {@link PlannerWidgets#plannerPanel}: the flow panel was
     * `defaultPanel(...).child(canvas)` and nothing else. So a plan cut off at the node cap drew a
     * diagram shaped exactly like a complete one -- and that is worse on this surface than on the
     * tree, because a tree that stops has a visible last row while a diagram that stops has a last
     * column that looks like the end of the plan.
     *
     * THE CONTROL IS THE COMPLETE PLAN, and it is what makes this a test rather than a search for a
     * string. A panel that drew the warning unconditionally -- or drew any red line at all -- would
     * pass a one-armed version, and would then be crying wolf on every diagram.
     *
     * ASSERTED ON THE DRAWN TEXT, through the same `fit` every other line here goes through, so a
     * warning too long for the panel fails as a cut string rather than passing as a substring
     * match on the source value.
     */
    @Test
    public void aTruncatedPlansDiagramCarriesTheSameWarningTheTreeDoes() {
        PlanView truncated = PlanFixtures.load("plan-truncated");
        assertFalse("the fixture must actually be truncated, or this measures nothing",
                    NodeRowText.truncationWarning(truncated).isEmpty());
        List<String> onDiagram = flowPanelText(truncated);
        assertTrue("the diagram must carry the truncation warning: " + onDiagram,
                   onDiagram.contains(NodeRowText.fit(
                           NodeRowText.truncationWarning(truncated),
                           PlannerWidgets.FLOW_WIDTH - PlannerWidgets.FLOW_PAD * 2)));

        PlanView complete = PlanFixtures.load("plan-in-stock");
        assertTrue("the control fixture must NOT be truncated",
                   NodeRowText.truncationWarning(complete).isEmpty());
        for (String line : flowPanelText(complete)) {
            assertFalse("a complete plan's diagram must not warn about truncation: " + line,
                        line.contains("truncated"));
        }
    }

    /**
     * The panel says what it is and how to drive it, and neither line is cut.
     *
     * A CUT HINT IS A HINT THAT OMITS THE GESTURE AT THE END, which is the failure `BADGE` records
     * one surface over: a vocabulary measured from a second copy of itself came out at 66 pixels
     * and the screenshot read "no known...". Here the string is fixed and the width is fixed, so
     * this is an arithmetic fact that can simply be asserted -- and it is the only thing on screen
     * that mentions Escape at all.
     */
    @Test
    public void theFlowPanelSaysWhatItIsAndHowToDriveItWithoutBeingCut() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        List<String> text = flowPanelText(plan);
        assertTrue("the diagram must name the plan it is of: " + text,
                   text.contains(NodeRowText.fit(NodeRowText.heading(plan),
                                                 PlannerWidgets.FLOW_WIDTH
                                                         - PlannerWidgets.FLOW_PAD * 2)));

        int room = PlannerWidgets.FLOW_WIDTH - PlannerWidgets.FLOW_PAD * 2
                - PlannerWidgets.FLOW_BUTTON_LABELS.size()
                        * (PlannerWidgets.FLOW_BUTTON_WIDTH + PlannerWidgets.GAP);
        assertEquals("the gesture hint must fit beside the buttons, or it silently loses whatever"
                     + " gesture is written last", PlannerWidgets.FLOW_HINT,
                     NodeRowText.fit(PlannerWidgets.FLOW_HINT, room));
        assertTrue("and it must be on the panel: " + text,
                   text.contains(PlannerWidgets.FLOW_HINT));
    }

    /**
     * Every label the flow panel's buttons carry is in the vocabulary they were measured from.
     *
     * {@link PlannerWidgets#VIEW_LABELS}' rule, and it exists for the reason recorded there: a
     * fourth button added to `flowButtons` and not to `FLOW_BUTTON_LABELS` would be measured
     * against the old widest label and cut, and a truncated button is always a bug because the
     * vocabulary is fixed.
     */
    @Test
    public void everyFlowButtonLabelIsInTheMeasuredVocabulary() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        ModularPanel panel = PlannerWidgets.flowPanel(plan, new StubDiagram(),
                                                      PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        PlannerWidgets.ViewBar bar = firstViewBarIn(panel);
        assertNotNull("the flow panel must carry a button row", bar);

        int buttons = 0;
        for (IWidget child : bar.getChildren()) {
            if (!(child instanceof PlannerWidgets.ClickableGroup)) {
                continue;
            }
            buttons++;
            List<String> labels = textIn(child);
            assertEquals("a button draws exactly one label", 1, labels.size());
            assertTrue("'" + labels.get(0) + "' is drawn but was never measured, so the button"
                       + " row is sized from a vocabulary that does not contain it",
                       PlannerWidgets.FLOW_BUTTON_LABELS.contains(labels.get(0)));
            assertTrue("and a measured label must not be cut at the width it was measured for",
                       child.getArea().width >= labels.get(0).length()
                               * NodeRowText.CHAR_WIDTH);
        }
        assertEquals("every label in the vocabulary must actually be drawn, or the row is being"
                     + " sized for a button that does not exist",
                     PlannerWidgets.FLOW_BUTTON_LABELS.size(), buttons);
    }

    /**
     * The three buttons do three different things, and Close closes.
     *
     * CLICKED THROUGH THE WIDGETS RATHER THAN ASSERTED AS LABELS, which is `Recorder`'s whole
     * argument: "the panel contains a line reading Close" passes on a panel whose buttons do
     * nothing, and that is what the first version of every menu in this file was.
     *
     * CLOSE IS ASSERTED BY WHERE IT LANDS RATHER THAN BY `isOpen()`, and the reason is a property
     * of this harness rather than of the button. `HeadlessLayout` opens the panel it is given as
     * the screen's MAIN panel, so `closeIfOpen` takes its `isMainPanel()` branch and calls
     * `MCHelper.popScreen`, which needs a Minecraft instance -- measured: it dies in
     * `MCHelper.hasMc`'s static init. In game the flow panel is a SECONDARY panel opened off
     * `LivePlannerActions`, where the same call takes `panelManager.closePanel(this)` and returns
     * with the panel closed. So what is provable here is that the button reaches ModularUI's own
     * close path, which is the part that was missing: a button wired to nothing throws nothing.
     */
    @Test
    public void theFlowPanelsButtonsCloseItAndDriveTheDiagram() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        StubDiagram diagram = new StubDiagram();
        ModularPanel panel = PlannerWidgets.flowPanel(plan, diagram, PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        assertTrue("the panel must be open before Close can mean anything", panel.isOpen());

        List<PlannerWidgets.ClickableGroup> buttons = new java.util.ArrayList<
                PlannerWidgets.ClickableGroup>();
        for (IWidget child : firstViewBarIn(panel).getChildren()) {
            if (child instanceof PlannerWidgets.ClickableGroup) {
                buttons.add((PlannerWidgets.ClickableGroup) child);
            }
        }

        // ORDER IS READ OFF THE VOCABULARY, not assumed, so a reordering fails on the button that
        // moved rather than on whichever assertion happened to be first.
        int close = PlannerWidgets.FLOW_BUTTON_LABELS.indexOf(PlannerWidgets.CLOSE_DIAGRAM);
        int root = PlannerWidgets.FLOW_BUTTON_LABELS.indexOf(PlannerWidgets.JUMP_TO_ROOT);
        int selected = PlannerWidgets.FLOW_BUTTON_LABELS.indexOf(PlannerWidgets.JUMP_TO_SELECTED);

        buttons.get(root).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("root"), diagram.calls);
        buttons.get(selected).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("root", "selected"), diagram.calls);

        String reached = null;
        try {
            buttons.get(close).onMousePressed(0);
        } catch (Throwable expected) {
            reached = stackOf(expected);
        }
        assertNotNull("Close must reach ModularUI's close path; a button wired to nothing gets "
                      + "through this harness silently, which is what 'no close affordance at "
                      + "all' looked like before #327", reached);
        assertTrue("the click must land in ModularPanel.closeIfOpen, not somewhere else: "
                   + reached, reached.contains("ModularPanel.closeIfOpen"));
        assertEquals("and Close must not also move the viewport",
                     java.util.Arrays.asList("root", "selected"), diagram.calls);
    }

    /** A throwable's stack as `Class.method` lines, for asserting WHERE a click landed. */
    private static String stackOf(Throwable error) {
        StringBuilder sb = new StringBuilder(error.toString());
        for (StackTraceElement frame : error.getStackTrace()) {
            String owner = frame.getClassName();
            sb.append("\n  ").append(owner.substring(owner.lastIndexOf('.') + 1))
              .append('.').append(frame.getMethodName());
        }
        return sb.toString();
    }

    /**
     * A diagram that records what it was asked to do and draws nothing.
     *
     * NOT A `FlowCanvas`, and the reason is the direction rule this file already obeys: `flow`
     * names `planner` and never the reverse, and `PlannerLayoutTest` writes `FlowLayout`'s node
     * width out as a literal rather than importing it. `Diagram` is the interface that exists so
     * this panel can be tested -- and driven -- without the canvas.
     */
    private static final class StubDiagram
            extends com.cleanroommc.modularui.widget.ParentWidget<StubDiagram>
            implements Diagram {

        private final List<String> calls = new java.util.ArrayList<String>();

        StubDiagram() {
            size(PlannerWidgets.FLOW_CANVAS_WIDTH, 100);
        }

        @Override
        public void jumpToRoot() {
            calls.add("root");
        }

        @Override
        public boolean jumpToSelected() {
            calls.add("selected");
            return true;
        }
    }

    /** Every string the flow panel draws, in tree order. */
    private static List<String> flowPanelText(PlanView plan) {
        ModularPanel panel = PlannerWidgets.flowPanel(plan, new StubDiagram(),
                                                      PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        return textIn(panel);
    }

    /** The panel's button row, depth first. */
    private static PlannerWidgets.ViewBar firstViewBarIn(IWidget parent) {
        if (parent instanceof PlannerWidgets.ViewBar) {
            return (PlannerWidgets.ViewBar) parent;
        }
        for (IWidget child : parent.getChildren()) {
            PlannerWidgets.ViewBar found = firstViewBarIn(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** A panel to lay a bare widget out in, since the sizer runs over a panel. */
    private static ModularPanel wrap(ParentWidget<?> widget) {
        widget.pos(4, 4);
        ModularPanel panel = ModularPanel.defaultPanel("mcrecipedump_test_wrap", 240, 60);
        panel.child(widget);
        return panel;
    }

    /**
     * A pin the solver could not honour reaches the panel.
     *
     * FOUND BY A SCREENSHOT AND NOT BY A TEST, which is why it is written down here. Pinning
     * "Iron Ingot from Iron Nugget" against the reference pack produced a plan
     * byte-identical to pinning nothing -- the pin resolved, the solver applied it, the cycle
     * guard overruled it (9 nuggets come from an ingot) and recorded a sentence saying so,
     * and the panel read past the field. The click looked like it had worked. `render.py`
     * has shown this in its warnbar since #30.
     */
    @Test
    public void aPinTheSolverCouldNotHonourIsSaidOutLoud() {
        PlanView plan = planWithOverruledPins(1);
        assertEquals(java.util.Arrays.asList("every recipe you pinned for Thing 0 loops back"),
                     PlannerWidgets.warnings(plan));

        ModularPanel panel =
                PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        assertTrue("the panel must say it: " + dumpText(panel),
                   texts(panel).contains("every recipe you pinned for Thing 0 loops back"));
    }

    /**
     * More overruled pins than there is room for are COUNTED, and the tree survives.
     *
     * An uncapped list eats the panel from the top: one line per pin, and a player has as
     * many of those as they made choices. The failure is not a scrollbar, it is a tree with
     * no height at all, which renders as an empty plan.
     */
    @Test
    public void tooManyOverruledPinsAreCountedRatherThanAllowedToEatTheTree() {
        PlanView plan = planWithOverruledPins(9);
        List<String> shown = PlannerWidgets.warnings(plan);
        assertEquals(PlannerWidgets.MAX_WARNINGS, shown.size());
        assertEquals("+7 more recipe choice(s) could not be used",
                     shown.get(shown.size() - 1));
        // SORTED, matching `render.py`'s warnbar and `cli.cmd_plan`, so the same plan reads
        // the same way wherever it is shown. A map's iteration order is not a thing to put
        // in front of a reader.
        assertEquals(java.util.Arrays.asList(
                             "every recipe you pinned for Thing 0 loops back",
                             "every recipe you pinned for Thing 1 loops back"),
                     shown.subList(0, 2));

        ModularPanel panel =
                PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            Area area = widget.getArea();
            assertTrue(widget.getClass().getSimpleName() + " has no box: " + area,
                       area.w() > 0 && area.h() > 0);
        }
    }

    /**
     * The "planned without" caveat wraps instead of losing its last input.
     *
     * The one place in this package where wrapping beats cutting. The caveat is the list of
     * inputs the plan could NOT see; a cut list is a shorter, wrong list, and the reader has
     * no way to tell it was cut except a "..." that could equally be part of a name.
     */
    @Test
    public void theCaveatWrapsRatherThanLosingItsLastInput() {
        // `PlanCaveats.summaryLine` AND NOT `ScenarioSource.summary`, because the panel wraps
        // the former. This asserted the latter until #190, and once the line grew a pointer at
        // the detail panel that made it a test of a string nothing draws: it would have stayed
        // green while the line a player reads truncated.
        String caveat = PlanCaveats.summaryLine();
        assertFalse("the fixture for this test is the real caveat; it must not be empty",
                    caveat.isEmpty());
        List<String> lines = NodeRowText.wrap(caveat, PlannerWidgets.CONTENT_WIDTH,
                                              PlannerWidgets.MAX_CAVEAT_LINES);
        StringBuilder rejoined = new StringBuilder();
        for (String line : lines) {
            if (rejoined.length() > 0) {
                rejoined.append(' ');
            }
            rejoined.append(line);
        }
        assertEquals("every input the plan could not see must survive the wrap",
                     caveat, rejoined.toString());
        for (String line : lines) {
            assertFalse("a wrapped line must not also be cut: " + line,
                        line.endsWith(NodeRowText.ELLIPSIS));
        }
    }

    // -- the stale-graph warning (#285) --------------------------------------------------------
    //
    // WHY THESE ARE HERE AND NOT ONLY IN `GraphFactsTest`. That file proves the COMPARISON, and
    // `tools/ci-java.sh` runs it on every pull request. What it cannot prove is that the verdict
    // reaches a player: `GraphJsonReader` has read `dump_schema` since the first port and
    // `DumpCommand.SCHEMA` has sat beside it the whole time, and the defect #285 names is
    // precisely that nothing put the two on a screen. A check whose result is computed and
    // dropped is the shape this repository keeps re-finding -- `stage-instance.sh`'s swallowed
    // refusal, `missingNotes` with no caller but a test -- so the assertion that matters is over
    // the laid-out panel, not over the string.

    /** The in-game normal: the graph was written by a dump of this build's own format. */
    private static io.github.jacoblasky.recipedump.plan.GraphFacts.SchemaCheck matching() {
        return io.github.jacoblasky.recipedump.plan.GraphFacts.checkSchema(8, 8);
    }

    /** The #279 failure: a graph one schema behind the jar reading it. */
    private static io.github.jacoblasky.recipedump.plan.GraphFacts.SchemaCheck behind() {
        return io.github.jacoblasky.recipedump.plan.GraphFacts.checkSchema(7, 8);
    }

    @Test
    public void aStaleGraphIsSaidOnThePlanItselfRatherThanOnlyOnTheGraphTab() {
        ModularPanel panel = PlannerWidgets.plannerPanel(
                PlanFixtures.load("plan-in-stock"), emptyBook(), behind(), recorder());
        HeadlessLayout.layOut(panel);

        String expected = PlannerWidgets.staleGraphWarning(behind());
        assertFalse("a BEHIND graph must produce a line at all", expected.isEmpty());
        assertTrue("the plan must say the graph is stale: " + dumpText(panel),
                   texts(panel).contains(expected));
        // BOTH NUMBERS, because "this plan may be wrong" with nothing to check is a scare rather
        // than a report, and the player cannot act on it or dismiss it.
        assertTrue(expected, expected.contains("7") && expected.contains("8"));
    }

    @Test
    public void aMatchingSchemaPutsNoLineOnThePlanAndCostsTheTreeNothing() {
        // THE OTHER HALF OF THE ASSERTION, and the reason the line is conditional. A green
        // "graph is fine" on every plan for the rest of the game is noise that gets tuned out,
        // and it takes the red one with it.
        assertEquals("", PlannerWidgets.staleGraphWarning(matching()));

        PlanView plan = PlanFixtures.load("plan-in-stock");
        ModularPanel ok = PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), recorder());
        ModularPanel stale = PlannerWidgets.plannerPanel(plan, emptyBook(), behind(), recorder());
        HeadlessLayout.layOut(ok);
        HeadlessLayout.layOut(stale);

        // RESERVED, NOT DRAWN OVER THE TREE. ModularUI neither clamps nor clips a child (#125),
        // so a line appended without taking its height out first looks correct in a screenshot
        // and overlaps the first row. The tree must be exactly one line shorter.
        assertEquals("the warning must come out of the tree's height",
                     PlannerWidgets.LINE,
                     treeHeight(ok) - treeHeight(stale));
    }

    @Test
    public void aGraphAheadOfTheModSaysTheOppositeThingRatherThanTheSameThing() {
        // Redumping an AHEAD graph overwrites the newer file with an older dump. Two mismatches
        // with two opposite fixes cannot share one sentence.
        String ahead = PlannerWidgets.staleGraphWarning(
                io.github.jacoblasky.recipedump.plan.GraphFacts.checkSchema(9, 8));
        assertFalse(ahead.isEmpty());
        assertNotEquals(PlannerWidgets.staleGraphWarning(behind()), ahead);
    }

    @Test
    public void theStaleWarningIsNotSwallowedByAFullWarningList() {
        // THE CAP IS THE HAZARD, and it is why this line is not an entry in `warnings`. Nine
        // overruled pins saturate MAX_WARNINGS and roll the rest up as "+7 more recipe
        // choice(s) could not be used" -- a sentence that would be describing the wrong thing
        // if it had eaten a schema warning, and a plan that would then be silently stale.
        PlanView plan = planWithOverruledPins(9);
        assertEquals("the fixture must saturate the cap, or this proves nothing",
                     PlannerWidgets.MAX_WARNINGS, PlannerWidgets.warnings(plan).size());

        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), behind(), recorder());
        HeadlessLayout.layOut(panel);
        assertTrue("the schema warning must survive a full warning list: " + dumpText(panel),
                   texts(panel).contains(PlannerWidgets.staleGraphWarning(behind())));
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            Area area = widget.getArea();
            assertTrue(widget.getClass().getSimpleName() + " has no box: " + area,
                       area.w() > 0 && area.h() > 0);
        }
    }

    @Test
    public void everyStaleWarningFitsOneLineAndTheHarnessCaseIsSilent() {
        // 64 characters is CONTENT_WIDTH at 6px a character. `line()` CUTS rather than wraps,
        // and the cut lands at the end -- which is where the numbers are.
        for (int graphSchema : new int[] {7, 9, 0}) {
            String said = PlannerWidgets.staleGraphWarning(
                    io.github.jacoblasky.recipedump.plan.GraphFacts.checkSchema(graphSchema, 8));
            assertFalse("schema " + graphSchema + " must not be silent", said.isEmpty());
            assertTrue(said + " is " + said.length() + " chars",
                       said.length() <= PlannerWidgets.CONTENT_WIDTH / NodeRowText.CHAR_WIDTH);
        }
        // NULL IS THE ONLY SILENCE, and it is the harness drawing a stored fixture that never
        // came from a loaded graph. In game `PlannerScreen.openPlanner` draws a not-yet panel
        // for every graph state that is not READY, so a tree on screen means a graph was loaded.
        assertEquals("", PlannerWidgets.staleGraphWarning(null));
    }

    /** The height ModularUI gave the tree's scrolling list. */
    private static int treeHeight(ModularPanel panel) {
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (widget instanceof ListWidget) {
                return widget.getArea().h();
            }
        }
        throw new AssertionError("no ListWidget in the planner panel");
    }

    /** A plan carrying `count` overruled pins and nothing else out of the ordinary. */
    private static PlanView planWithOverruledPins(int count) {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("target", "mod:thing");
        result.addProperty("target_name", "Thing");
        result.addProperty("qty", 1);
        result.addProperty("nodes", 1);
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.addProperty("key", "mod:thing");
        tree.addProperty("label", "Thing");
        tree.addProperty("need", 1);
        tree.addProperty("status", NodeStatus.CRAFT);
        result.add("tree", tree);
        com.google.gson.JsonObject overruled = new com.google.gson.JsonObject();
        for (int i = 0; i < count; i++) {
            overruled.addProperty("mod:thing" + i,
                                  "every recipe you pinned for Thing " + i + " loops back");
        }
        result.add("pins_overruled", overruled);
        return PlanJson.readResult(result);
    }

    /**
     * A picker for `node` offering `ways` real candidates, or the empty case at zero.
     *
     * THROUGH `RecipeChoices` AND A REAL GRAPH rather than by fabricating `RecipeChoice`
     * values, so a layout assertion is made against the strings the picker will really be
     * handed. `Pins.label` produces "Plate from ingot"; a made-up "Recipe 1" would be
     * shorter than anything real and would pass a truncation test the shipped panel fails.
     */
    private static ModularPanel pickerFor(PlanNode node, int ways) {
        return PlannerWidgets.recipePicker(node, choicesFor(node, ways), PlannerActions.NONE);
    }

    private static RecipeChoices choicesFor(PlanNode node, int ways) {
        if (ways <= 0) {
            return RecipeChoices.forNode(ChoiceGraphs.makingKey("mod:nothing", 1), node, null);
        }
        return RecipeChoices.forNode(ChoiceGraphs.makingKey(node.key(), ways), node, null);
    }

    /**
     * Every candidate gets a row, and clicking one asks for that recipe on that node.
     *
     * BOTH HALVES OF THE IDENTITY, because a picker whose rows all pin the first candidate
     * would pass any test that only counted them -- the same single-row blind spot
     * `aDifferentRowAsksForADifferentNode` was written for on the tree.
     */
    @Test
    public void everyRecipePickerRowPinsItsOwnRecipe() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        RecipeChoices choices = choicesFor(node, 3);
        assertEquals(3, choices.shown().size());
        Recorder recorder = new Recorder();
        ModularPanel picker = PlannerWidgets.recipePicker(node, choices, recorder);
        HeadlessLayout.layOut(picker);

        List<PlannerWidgets.ClickableGroup> rows = clickables(picker);
        assertEquals("one clickable per candidate and nothing else", 3, rows.size());
        List<String> expected = new java.util.ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).onMousePressed(0);
            expected.add("pin:" + node.key() + ":" + choices.shown().get(i).rid());
        }
        assertEquals(expected, recorder.calls);
    }

    /**
     * The empty picker prints the reason and offers nothing to click.
     *
     * A picker with no rows and no sentence is the failure this whole family is about: a
     * player cannot tell "no graph loaded" from "nothing makes this", and one of those is a
     * file they forgot to install.
     */
    @Test
    public void anEmptyRecipePickerSaysWhyAndHasNothingToClick() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        RecipeChoices nothing = choicesFor(node, 0);
        assertTrue(nothing.isEmpty());
        ModularPanel picker = PlannerWidgets.recipePicker(node, nothing, new Recorder());
        HeadlessLayout.layOut(picker);
        assertTrue("the reason must be on screen verbatim: " + HeadlessLayout.dump(picker),
                   texts(picker).contains(nothing.why()));
        assertEquals("nothing to click when there is nothing to choose",
                     0, clickables(picker).size());
    }

    /**
     * A capped list says so, and an uncapped one does not claim to be capped.
     *
     * The cap is `RecipeChoices.MAX_SHOWN`; the fixtures reach 172 alternatives on one node,
     * so this is the normal case on a real pack rather than an edge. A list of 24 out of 172
     * that stays quiet is a player concluding the pack has 24 ways to make a thing.
     */
    @Test
    public void aCappedRecipePickerSaysHowManyItLeftOut() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        ModularPanel capped = pickerFor(node, RecipeChoices.MAX_SHOWN + 7);
        HeadlessLayout.layOut(capped);
        assertTrue("a capped picker must say so: " + dumpText(capped),
                   dumpText(capped).contains("7 not shown"));

        ModularPanel whole = pickerFor(node, 3);
        HeadlessLayout.layOut(whole);
        assertFalse("an uncapped picker must not: " + dumpText(whole),
                    dumpText(whole).contains("not shown"));
    }

    /**
     * The category column survives, and the LABEL is what gets cut.
     *
     * The inversion of the tree row's rule, and it was a screenshot that settled it: the
     * picker for `minecraft:iron_ingot` -- 172 candidates, the fixture set's worst case --
     * came out as fourteen rows all reading "Iron Ingot from ..." with the category cut off
     * the end. Every row is the same item, so the label's leading words are identical down
     * the list and the category is the only column that tells them apart.
     */
    @Test
    public void theRecipePickerCutsTheLabelRatherThanTheCategory() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        RecipeChoices choices = choicesFor(node, RecipeChoices.MAX_SHOWN);
        ModularPanel picker = PlannerWidgets.recipePicker(node, choices, PlannerActions.NONE);
        HeadlessLayout.layOut(picker);

        List<String> lines = texts(picker);
        for (RecipeChoice choice : choices.shown()) {
            assertTrue("a category was cut or dropped: " + choice.category() + " in " + lines,
                       lines.contains(choice.category()));
        }
    }

    /**
     * A category longer than the cap is cut, and the label keeps {@link
     * PlannerWidgets#MIN_LABEL}.
     *
     * `modularmachinery.recipes.ender_stone` is 36 characters and real; a column sized for it
     * would spend the whole row on one pack's naming habit. So the cap wins over the category
     * and the label floor wins over the cap, in that order, and neither can starve the other.
     */
    @Test
    public void theCategoryColumnIsCappedAndNeverStarvesTheLabel() {
        int inner = PlannerWidgets.PICKER_WIDTH - PlannerWidgets.PADDING * 2;
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        int capped = PlannerWidgets.categoryWidth(choicesFor(node, 3), inner);
        assertTrue("the category column must not exceed the cap",
                   capped <= PlannerWidgets.MAX_CATEGORY_CHARS * NodeRowText.CHAR_WIDTH);
        assertTrue("a row that is all category leaves no label",
                   inner - PlannerWidgets.CHOICE_STATE - PlannerWidgets.GAP * 2 - capped
                           >= PlannerWidgets.MIN_LABEL);
        // Squeezed to nothing rather than overflowing, which is what #125 measured ModularUI
        // does NOT do for you: a child wider than its parent just draws past the edge.
        assertEquals(0, PlannerWidgets.categoryWidth(choicesFor(node, 3), 40));
    }

    /**
     * A pin outranks the solver's own choice in the colour, because it outranks it in fact.
     *
     * The row that is BOTH says so in words as well; a state drawn only as a colour is a
     * state a colour-blind player cannot read, which is the argument `NodeStatus` exists on.
     */
    @Test
    public void aPinnedRowReadsAsPinnedEvenWhenItIsAlsoTheOneInUse() {
        assertEquals("pinned", PlannerWidgets.choiceState(choice(false, true)));
        assertEquals("in use", PlannerWidgets.choiceState(choice(true, false)));
        assertEquals("both", PlannerWidgets.choiceState(choice(true, true)));
        assertEquals("", PlannerWidgets.choiceState(choice(false, false)));

        assertEquals(NodeStatus.INK_OK, PlannerWidgets.choiceColour(choice(false, true)));
        assertEquals("a pin is the player's decision and outranks the solver's",
                     NodeStatus.INK_OK, PlannerWidgets.choiceColour(choice(true, true)));
        assertEquals(NodeStatus.INK_CRAFT, PlannerWidgets.choiceColour(choice(true, false)));
        assertEquals(NodeStatus.INK_MUTED, PlannerWidgets.choiceColour(choice(false, false)));
    }

    private static RecipeChoice choice(boolean inUse, boolean pinned) {
        return new RecipeChoice(-1, "hei:x:1", new Pins.Pin("f", "cat", "Plate from ingot"),
                                inUse, pinned);
    }

    /**
     * The state column fits every word it can hold, for the badge column's reason.
     *
     * A recipe state is FIXED VOCABULARY, unlike a label, so a truncated one is always a bug
     * rather than an honest cut. "in use..." says the opposite of "in use" only to a reader
     * who does not notice the dots.
     */
    @Test
    public void theStateColumnFitsEveryWordItCanHold() {
        for (int combination = 0; combination < 4; combination++) {
            RecipeChoice choice = new RecipeChoice(-1, "hei:x:1",
                                                   new Pins.Pin("f", "cat", "Plate from ingot"),
                                                   (combination & 1) != 0,
                                                   (combination & 2) != 0);
            String word = PlannerWidgets.choiceState(choice);
            assertEquals("the state column cuts \"" + word + "\"",
                         word, NodeRowText.fit(word, PlannerWidgets.CHOICE_STATE));
        }
    }

    private static final class Recorder implements PlannerActions, NodeActions {
        boolean recipeViewerAvailable;
        final List<String> calls = new java.util.ArrayList<String>();

        @Override
        public NodeActions nodeActions() {
            return this;
        }

        @Override
        public boolean canShowRecipes(PlanNode node) {
            // The seam's own rule, so the menu tests exercise the token asymmetry rather than
            // only the installed/not-installed one. See `JeiNodeActions.canShowRecipes`.
            return recipeViewerAvailable && !NodeStatus.isToken(node);
        }

        @Override
        public boolean canShowUses(PlanNode node) {
            return recipeViewerAvailable;
        }

        /**
         * EMPTY, so the geometry assertions in this class do not depend on whether a stack
         * came back. That is the whole reason `PlannerActions.NONE` refuses to hand out
         * `NodeActionsHolder.actions()`: an installed one adds an icon widget and would
         * silently change the boxes another test asserts.
         */
        @Override
        public net.minecraft.item.ItemStack iconFor(PlanNode node) {
            return net.minecraft.item.ItemStack.EMPTY;
        }

        @Override
        public net.minecraft.item.ItemStack iconForKey(String key) {
            return net.minecraft.item.ItemStack.EMPTY;
        }

        /** EMPTY, for the reason {@link #iconFor} is. #333. */
        @Override
        public net.minecraft.item.ItemStack iconForCategory(PlanNode node) {
            return net.minecraft.item.ItemStack.EMPTY;
        }

        @Override
        public void showRecipes(PlanNode node) {
            calls.add("showRecipes:" + node.key());
        }

        @Override
        public void showUses(PlanNode node) {
            calls.add("showUses:" + node.key());
        }

        @Override
        public void openNodeMenu(PlanNode node) {
            calls.add("menu:" + node.key());
        }

        @Override
        public void selectNode(PlanNode node) {
            calls.add("select:" + node.key());
        }

        @Override
        public void openRecipePicker(PlanNode node) {
            calls.add("picker:" + node.key());
        }

        @Override
        public void openCaveats() {
            calls.add("caveats");
        }

        // THE PLAN'S TARGET IS RECORDED AND NOT MERELY THE FACT OF THE CALL, so the witness
        // below can fail against an implementation that opens a view of the WRONG plan -- which
        // is the shape a click that reached for `PlannerService` instead of the panel's own plan
        // would have. A recorder that logged only "flow" would agree with that bug.
        @Override
        public void openFlow(PlanView plan) {
            calls.add("flow:" + plan.target());
        }

        @Override
        public void openTodo(PlanView plan, PlanBook book) {
            calls.add("todo:" + plan.target() + ":" + book.todoKeys().size());
        }

        @Override
        public void pinRecipe(PlanNode node, RecipeChoice choice) {
            calls.add("pin:" + node.key() + ":" + choice.rid());
        }

        @Override
        public void addToTodo(PlanNode node) {
            calls.add("todo:" + node.key());
        }

        @Override
        public void toggleFavourite(PlanNode node) {
            calls.add("favourite:" + node.key());
        }

        // THE QUANTITY IS RECORDED, and that is what makes the witness below able to fail. A
        // recorder that logged only the key would pass against an implementation that had
        // routed the click through a node and taken a per-occurrence `need` -- which is the
        // defect #251 exists to prevent, and it is invisible unless the number is in the log.
        @Override
        public void openRowMenu(PlanView.EntryRow row) {
            calls.add("rowMenu:" + row.key() + ":" + row.need());
        }

        @Override
        public void addRowToTodo(PlanView.EntryRow row) {
            calls.add("rowTodo:" + row.key() + ":" + row.need());
        }

        @Override
        public void favouriteRow(PlanView.EntryRow row) {
            calls.add("rowFavourite:" + row.key());
        }
    }

    /**
     * Every row-shaped clickable in a tree, in order.
     *
     * `ClickableGroup` and not `Interactable`, which was the first attempt: a `ListWidget` is
     * an `AbstractScrollWidget` and therefore `Interactable` itself, so the loose test counted
     * the scroll viewport as a seventh row and reported an off-by-one that was its own.
     */
    private static List<PlannerWidgets.ClickableGroup> clickables(IWidget root) {
        List<PlannerWidgets.ClickableGroup> found =
                new java.util.ArrayList<PlannerWidgets.ClickableGroup>();
        for (IWidget widget : HeadlessLayout.flatten(root)) {
            if (widget instanceof PlannerWidgets.ClickableGroup) {
                found.add((PlannerWidgets.ClickableGroup) widget);
            }
        }
        return found;
    }

    /**
     * The panel's click targets with the view bar's buttons taken out. #323.
     *
     * BY TYPE AND NOT BY DROPPING THE FIRST TWO. The view bar is drawn above the tree, so an
     * index-based skip is correct only while there are exactly two views -- and the assertion it
     * feeds counts what is left against `plan.flatten().size()`, so a third button would make it
     * off by one against a number the test also computes, which reads as a tree defect. Asking
     * `ViewBar` for its own children cannot go wrong that way, which is what that type is for.
     */
    private static List<PlannerWidgets.ClickableGroup> treeClickables(IWidget root) {
        List<PlannerWidgets.ClickableGroup> found = clickables(root);
        found.removeAll(viewButtons(root));
        return found;
    }

    /** The view bar's buttons, in the order they are drawn. Empty when there is no bar. */
    private static List<PlannerWidgets.ClickableGroup> viewButtons(IWidget root) {
        for (IWidget widget : HeadlessLayout.flatten(root)) {
            if (widget instanceof PlannerWidgets.ViewBar) {
                return clickables(widget);
            }
        }
        return new java.util.ArrayList<PlannerWidgets.ClickableGroup>();
    }

    /**
     * Clicking a tree row asks for that row's menu.
     *
     * THE TEST THE REVIEW ADDED. Everything else here asserts geometry, and geometry is
     * perfectly happy with a menu nothing can open -- which is what this panel had until the
     * review looked for the caller and could not find one.
     */
    @Test
    public void clickingATreeRowOpensThatNodesMenu() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        Recorder recorder = new Recorder();
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), recorder);
        HeadlessLayout.layOut(panel);

        // THE VIEW BAR'S BUTTONS ARE EXCLUDED BY TYPE AND COUNTED SEPARATELY (#323). They are
        // click targets on this panel and they are not tree rows, so folding them into the
        // number below would weaken the assertion that follows into one that cannot tell a
        // button from a row.
        assertEquals("the two view buttons", 2, viewButtons(panel).size());
        List<PlannerWidgets.ClickableGroup> rows = treeClickables(panel);
        // ONE PER NODE PLUS THE CAVEAT BLOCK, which #190 made clickable. Counted rather than
        // filtered out, so that a second unexplained click target added later fails here: this
        // assertion is the only thing standing between "every row opens its own menu" and "a
        // stray widget swallows a click somewhere in the panel".
        assertEquals("one clickable per node, plus the caveat",
                     plan.flatten().size() + (PlanCaveats.summaryLine().isEmpty() ? 0 : 1),
                     rows.size());
        rows.get(0).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("menu:" + plan.tree().key()), recorder.calls);

        // A DIFFERENT row asks for a DIFFERENT node, which is the bug a single-row test
        // would miss entirely: one shared handler that always reports the root.
        recorder.calls.clear();
        rows.get(1).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("menu:" + plan.tree().children().get(0).key()),
                     recorder.calls);
    }

    /**
     * THE WITNESS FOR #323: the planner panel offers a way into the other two windows.
     *
     * RED ON MASTER, AND RED AS AN ASSERTION RATHER THAN AS A COMPILE ERROR ONLY BECAUSE THE
     * INTERFACE HAD TO GROW. What master had was a panel whose click targets were one per tree
     * row plus the caveat and nothing else, so `viewButtons` came back empty -- there was no
     * `ViewBar` to find, because nothing built one. `PanelReachabilityTest` is the half of this
     * witness that runs against the compiled mod and names the two unreachable builders; this is
     * the half that says what a player can now click and what it does.
     *
     * BOTH BUTTONS, AND EACH ASSERTED TO DO ITS OWN THING. One button tested is consistent with a
     * bar whose two entries share a handler -- which is a real bug rather than a hypothetical:
     * `clickingATreeRowOpensThatNodesMenu` says so about tree rows and gives the reason ("one
     * shared handler that always reports the root"), and a two-entry bar is where that is easiest
     * to write.
     *
     * THE PLAN IS IN THE RECORDED CALL, so this also fails against a button that opens a view of
     * some other plan -- see the recorder.
     */
    @Test
    public void theViewButtonsOpenTheDiagramAndTheTodoList() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        PlanBook book = emptyBook();
        book.setTodo("minecraft:iron_ingot", 4L);
        Recorder recorder = new Recorder();
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, book, matching(), recorder);
        HeadlessLayout.layOut(panel);

        List<PlannerWidgets.ClickableGroup> buttons = viewButtons(panel);
        assertEquals("one button per view", 2, buttons.size());

        buttons.get(0).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("flow:" + plan.target()), recorder.calls);

        recorder.calls.clear();
        buttons.get(1).onMousePressed(0);
        // THE BOOK IS PASSED THROUGH AS WELL AS THE PLAN, and the recorded size is what says so:
        // the TODO panel is made of both, and a button that handed over an empty book would draw
        // "nothing on the list" over a list with something on it.
        assertEquals(java.util.Arrays.asList("todo:" + plan.target() + ":1"), recorder.calls);
    }

    /**
     * The buttons are where a mouse can reach them, and inside the panel.
     *
     * THE FAILURE THIS CATCHES IS INVISIBLE ABOVE: `onMousePressed` called directly does not care
     * where the box is, or whether there is one. That is `theCaveatsClickTargetCoversItsLines`'
     * argument, and it applies harder here -- a control whose whole purpose is to be FOUND is
     * useless at zero height, and ModularUI reports nothing for it because every row in the panel
     * is still exactly the height it was built with.
     *
     * AND THEY DO NOT OVERLAP, which no size assertion on its own would catch. Two buttons laid
     * out at the same x are both clickable, both drawn, and the one underneath can never be hit;
     * #125 measured that ModularUI neither clamps nor clips, so nothing complains.
     *
     * THE SHOT IS THE OTHER HALF OF THIS AND NOT A DUPLICATE OF IT. `planner-view-flow` parks the
     * real cursor on the box these coordinates describe and asks ModularUI's own hit-testing
     * whether it agrees; this asserts the geometry across every fixture in seconds. Neither
     * subsumes the other.
     */
    @Test
    public void theViewButtonsAreClickableBoxesInsideThePanel() {
        ModularPanel panel = laidOut("plan-in-stock");
        List<PlannerWidgets.ClickableGroup> buttons = viewButtons(panel);
        assertEquals(2, buttons.size());

        Area box = panel.getArea();
        for (int i = 0; i < buttons.size(); i++) {
            Area button = buttons.get(i).getArea();
            assertEquals("button " + i + " is not a full row tall",
                         PlannerWidgets.ROW_HEIGHT, button.h());
            assertEquals("button " + i + " is not the measured width",
                         PlannerWidgets.VIEW_BUTTON_WIDTH, button.w());
            // ABSOLUTE COORDINATES AFTER LAYOUT: the panel is centred on the screen, so its own
            // box is the thing to compare against rather than PANEL_WIDTH.
            assertTrue("button " + i + " runs off the right of the panel",
                       button.x() + button.w() <= box.x() + box.w());
            assertTrue("button " + i + " is drawn past the bottom of the panel",
                       button.y() + button.h() <= box.y() + box.h());
        }
        Area first = buttons.get(0).getArea();
        Area second = buttons.get(1).getArea();
        assertTrue("the two buttons overlap, so one of them cannot be clicked at all",
                   first.x() + first.w() <= second.x());
    }

    /**
     * Every label the view bar can draw survives `fit` UNCUT, and is one the width was measured
     * against.
     *
     * A TRUNCATED BUTTON IS ALWAYS A BUG, because the vocabulary is fixed -- {@link
     * PlannerWidgets#BADGE}'s rule, and the badge column is what happens when it is broken:
     * sized from a second copy of the vocabulary it came out at 66 pixels and the screenshot read
     * "no known...". `theBadgeColumnFitsEveryWordTheVocabularyCanProduce` is this assertion one
     * column over.
     *
     * THE SECOND HALF IS THE ONE THAT WILL EARN ITS KEEP. `VIEW_BUTTON_WIDTH` measures
     * `VIEW_LABELS`, and the buttons are built from whatever `views` returns -- two lists that
     * agree today. A third view added to `views` alone would be measured against the old widest
     * label and cut, and the cut is silent.
     */
    @Test
    public void everyViewLabelIsInTheMeasuredVocabularyAndFitsItsButton() {
        List<PlannerWidgets.Entry> views =
                PlannerWidgets.views(PlanView.empty(), emptyBook(), PlannerActions.NONE);
        assertEquals("the view bar draws one button per entry", 2, views.size());
        for (PlannerWidgets.Entry view : views) {
            assertTrue("`" + view.text + "` is drawn on a button whose width was never measured"
                       + " against it; VIEW_LABELS holds " + PlannerWidgets.VIEW_LABELS,
                       PlannerWidgets.VIEW_LABELS.contains(view.text));
        }
        // AGAINST A LITERAL, AND THAT IS THE ONLY FORM OF THIS ASSERTION THAT CAN FAIL. The
        // obvious version -- `fit(label, VIEW_BUTTON_WIDTH)` equals `label` -- is VACUOUS, and it
        // sat here until a reviewer pointed at it: the width is DERIVED from the longest entry of
        // `VIEW_LABELS`, so for any label drawn from that same list the budget is its own length
        // plus slack and `fit` cannot cut. It agreed with every bug it could have had. So the
        // number is spelt out instead: 12 characters of "Show diagram" at `CHAR_WIDTH` 6, plus
        // `GAP` twice. A test that spells its expectation with the thing it is testing is the
        // shape `SeamInstallationTest.aDescriptorIsSpeltTheWayTheConstantPoolSpellsIt` refuses for
        // the same reason, and the membership assertion above is what keeps the two lists welded.
        //
        // IT WAS 90 UNTIL #332, WHEN THE WIDEST LABEL CHANGED HANDS. "Show TODO list" was 14
        // characters and "Show totals" is 11, so `SHOW_DIAGRAM` is now the one the cell is
        // measured against -- which is exactly the recount this literal exists to force, and it
        // did force it.
        assertEquals("VIEW_BUTTON_WIDTH is no longer the widest label plus two gaps, so either a"
                     + " label changed length or the slack did; recount rather than adjusting"
                     + " this number",
                     78, PlannerWidgets.VIEW_BUTTON_WIDTH);
    }

    /**
     * Clicking the caveat asks for the panel explaining it. #190.
     *
     * THE ONE ASSERTION THAT WOULD HAVE FAILED FOR THE WHOLE LIFE OF THE DEFECT. Everything
     * else about the caveat -- that it wraps, that it does not cut, that it sits above the
     * footer -- was already asserted and already passing while the five sentences explaining
     * what each missing input costs a player reached nothing at all. Geometry is perfectly
     * happy with text that leads nowhere.
     *
     * ON THE LAST CLICKABLE, because the caveat block is added after the tree and the footer.
     * The count above is what pins that; if it moves, that assertion fails first and says so.
     */
    @Test
    public void clickingTheCaveatOpensWhatThePlanCouldNotSee() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        assertFalse("the fixture for this test is the real caveat; it must not be empty",
                    PlanCaveats.summaryLine().isEmpty());
        Recorder recorder = new Recorder();
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), recorder);
        HeadlessLayout.layOut(panel);

        List<PlannerWidgets.ClickableGroup> rows = clickables(panel);
        rows.get(rows.size() - 1).onMousePressed(0);
        assertEquals(java.util.Arrays.asList("caveats"), recorder.calls);
    }

    /**
     * The caveat's click target really covers the text a player would aim at.
     *
     * A ZERO-HEIGHT OR ZERO-WIDTH TARGET IS THE FAILURE THIS CATCHES, and it is invisible in
     * the test above: `onMousePressed` called directly does not care where the box is. The
     * caveat is drawn as several stacked lines inside one group, and a group that forgot to
     * size itself would draw all of them and be unclickable -- which is the exact shape of the
     * bug the "look right and do nothing" comment on `planNodeContent`'s inert overload warns
     * about, and ModularUI reports nothing for it.
     */
    @Test
    public void theCaveatsClickTargetCoversItsLines() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), recorder());
        HeadlessLayout.layOut(panel);

        List<PlannerWidgets.ClickableGroup> rows = clickables(panel);
        Area caveat = rows.get(rows.size() - 1).getArea();
        int lines = NodeRowText.wrap(PlanCaveats.summaryLine(), PlannerWidgets.CONTENT_WIDTH,
                                     PlannerWidgets.MAX_CAVEAT_LINES).size();
        assertEquals("the target must be as tall as the caveat is",
                     PlannerWidgets.LINE * lines, caveat.h());
        assertEquals("and as wide as the line it sits under",
                     PlannerWidgets.CONTENT_WIDTH, caveat.w());
        // ABSOLUTE COORDINATES AFTER LAYOUT, so this compares against the PANEL's box rather
        // than against `PANEL_HEIGHT`: the panel is centred on the screen and its `y` is not 0.
        // #125 -- ModularUI neither clamps nor clips a child, so a caveat reserved wrongly is
        // drawn past the bottom edge and nothing reports it.
        Area box = panel.getArea();
        assertTrue("the caveat is drawn past the bottom of the panel: " + caveat.y() + " + "
                   + caveat.h() + " exceeds " + (box.y() + box.h()),
                   caveat.y() + caveat.h() <= box.y() + box.h());
    }

    @Test
    public void everyMenuEntryDoesWhatItSays() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        Recorder recorder = new Recorder();
        recorder.recipeViewerAvailable = true;
        ModularPanel menu = PlannerWidgets.nodeMenu(node, recorder);
        HeadlessLayout.layOut(menu);

        List<PlannerWidgets.ClickableGroup> entries = clickables(menu);
        assertEquals("recipes, uses, choose, todo, favourite", 5, entries.size());
        for (PlannerWidgets.ClickableGroup entry : entries) {
            entry.onMousePressed(0);
        }
        assertEquals(java.util.Arrays.asList(
                             "showRecipes:" + node.key(),
                             "showUses:" + node.key(),
                             "picker:" + node.key(),
                             "todo:" + node.key(),
                             "favourite:" + node.key()),
                     recorder.calls);
    }

    @Test
    public void withNoRecipeViewerTheMenuHasThreeEntriesAndNoneOfThemAreJeis() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        Recorder recorder = new Recorder();
        ModularPanel menu = PlannerWidgets.nodeMenu(node, recorder);
        HeadlessLayout.layOut(menu);
        List<PlannerWidgets.ClickableGroup> entries = clickables(menu);
        assertEquals("choose, todo, favourite", 3, entries.size());
        for (PlannerWidgets.ClickableGroup entry : entries) {
            entry.onMousePressed(0);
        }
        for (String call : recorder.calls) {
            assertFalse("nothing may reach the recipe viewer when it is not installed: " + call,
                        call.startsWith("show"));
        }
    }

    /**
     * The TODO panel says an item's NAME, not its graph key.
     *
     * WHAT THE SCREENSHOT SHOWED. `planner-todo` printed
     * `3x thaumadditions:vis_pod#0116bb2287a7` three rows above `500x Life Essence`, in one
     * window -- the graph names were already on screen, and nothing joined them to the rows
     * that came out of the plan book. Red on the pre-change tree, where every TODO row was
     * `quantity(key) + " " + key`.
     */
    @Test
    public void theTodoPanelShowsNamesRatherThanGraphKeys() {
        PlanView plan = PlanFixtures.load("plan-same-name");
        PlanNode named = plan.tree();
        assertTrue("the fixture's root must carry a label for this to mean anything",
                   named.label() != null && !named.label().isEmpty());
        assertFalse("and the label must not simply BE the key, or the assertion is vacuous",
                    named.label().equals(named.key()));

        PlanBook book = new PlanBook();
        book.setTodo(named.key(), 64L);
        List<PlannerWidgets.Line> lines = PlannerWidgets.todoLines(plan, book, 248);
        // THE LAST ROW SINCE #332, NOT THE FIRST. The book's rows are an appendix under their own
        // heading now that the summed totals lead the panel, so this reads from the end -- and
        // `anEmptyPlanBookStillGetsItsHeadingAndSaysItIsEmpty` is what pins that they are last.
        PlannerWidgets.Line row = lines.get(lines.size() - 1);
        assertEquals("64x " + named.label(), row.text);
        assertFalse("the key must not appear in the words: " + row.text,
                    row.text.contains(named.key()));
        // AND THE KEY IS STILL REACHABLE, on the row, because that is what the icon lookup
        // needs. Showing a name and losing the identity would be the same defect mirrored.
        assertEquals(named.key(), row.key);
    }

    /**
     * A key this plan never named still says something.
     *
     * THE CASE A PLAN-DERIVED LOOKUP REALLY HITS: a row added while planning one target and
     * still on the list while the player plans another. The fallback is the key, per
     * `NodeRowText.label`'s rule -- a blank row would hide that anything is on the list.
     */
    @Test
    public void aTodoKeyTheCurrentPlanNeverMentionedFallsBackToTheKey() {
        PlanBook book = new PlanBook();
        book.setTodo("thaumadditions:vis_pod#0116bb2287a7", 3L);
        List<PlannerWidgets.Line> lines =
                PlannerWidgets.todoLines(PlanFixtures.load("plan-in-stock"), book, 248);
        // LAST, SINCE #332 MOVED THE BOOK BELOW THE PLAN'S TOTALS.
        assertEquals("3x thaumadditions:vis_pod#0116bb2287a7",
                     lines.get(lines.size() - 1).text);
    }

    /**
     * Every row that is about one item carries its key, and no heading pretends to.
     *
     * The key is what the icon column is looked up by, so a shopping row without one would
     * draw text where every row beside it draws an item -- and "still needed for this plan:"
     * carrying a key would try to draw an icon for a sentence.
     */
    @Test
    public void everyTodoRowAboutAnItemCarriesItsKeyAndNoHeadingDoes() {
        PlanView plan = PlanFixtures.load("plan-truncated");
        assertFalse("this fixture must have a shopping list", plan.shoppingList().isEmpty());
        PlanBook book = new PlanBook();
        book.setTodo(plan.tree().key(), 1L);

        List<PlannerWidgets.Line> lines = PlannerWidgets.todoLines(plan, book, 248);

        // NOT AN EXACT COUNT, and it used to be one. That was wrong twice over once #190's five
        // summary sections landed: they contribute keyed rows of their own, and a row long enough
        // to wrap contributes a CONTINUATION line which correctly carries no key. Counting is the
        // wrong shape for a property about which rows may claim an item.
        int withKeys = 0;
        for (PlannerWidgets.Line line : lines) {
            if (!line.key.isEmpty()) {
                withKeys++;
            }
        }
        assertTrue("the TODO row and the shopping rows must all claim their item, saw " + withKeys,
                   withKeys >= 1 + plan.shoppingList().size());
        for (PlannerWidgets.Line line : lines) {
            if (line.text.endsWith(":")) {
                assertEquals("a heading must not claim an item: " + line, "", line.key);
            }
            if (line.text.startsWith(NodeRowText.CONTINUATION)) {
                assertEquals("a wrapped row's tail is not a second item: " + line, "", line.key);
            }
        }
    }

    /**
     * A node given two lines of height puts the label on the second one and gives it real room.
     *
     * THE MEASUREMENT GAP 2 WAS ABOUT. On one line the label competed with a 90px badge, so a
     * 209px diagram node gave the item's NAME 48 pixels -- eight characters -- and the first
     * screenshots of the canvas read "Iron ..." and "Block...". A dependency diagram whose
     * boxes do not say what they are is decoration. Red on the pre-change tree, where the
     * widest label widget in a 209x24 node was 48.
     */
    @Test
    public void aTallNodePutsItsLabelOnASecondLineWithRoomForAWholeName() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        // `FlowLayout.NODE_WIDTH` and `NODE_HEIGHT`, spelled rather than imported: `flow`
        // depends on `planner` and not the reverse, and a test that inverted that would be the
        // first thing in the package to do so. `FlowCanvasTest` pins the two numbers.
        int width = 209;
        ParentWidget<?> box = PlannerWidgets.planNodeContent(node, width, 26, PlannerActions.NONE);
        HeadlessLayout.layOutPanel("two-line-node", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, box);

        int widest = 0;
        for (IWidget child : box.getChildren()) {
            widest = Math.max(widest, child.getArea().w());
        }
        // The label is the widest column on a two-line node, because it has a line to itself.
        // 24 characters is "Sodium Fluoride Solution"; the old node had eight.
        assertTrue("the widest column is " + widest + "px, which is not a name",
                   widest >= 24 * NodeRowText.CHAR_WIDTH);

        // AND THE TWO LINES DO NOT OVERLAP, which is the failure a wider label invites: a
        // TextWidget handed a box it does not fit wraps and draws over what is beneath it,
        // and the sizer reports nothing (see NodeRowText.fit).
        for (IWidget first : box.getChildren()) {
            for (IWidget second : box.getChildren()) {
                if (first == second) {
                    continue;
                }
                assertFalse("two columns overlap: " + first.getArea() + " and "
                            + second.getArea(), overlaps(first.getArea(), second.getArea()));
            }
        }
        assertTrue("and everything stays inside the box",
                   box.getArea().h() == 26 && box.getArea().w() == width);
    }

    /** A row-height node keeps the single-line columns, which is what the seam promised. */
    @Test
    public void aRowHeightNodeStillDrawsOneLine() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        ParentWidget<?> box =
                PlannerWidgets.planNodeContent(node, 209, PlannerWidgets.ROW_HEIGHT,
                                               PlannerActions.NONE);
        HeadlessLayout.layOutPanel("one-line-node", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, box);
        for (IWidget child : box.getChildren()) {
            assertEquals("a one-line node draws everything on the same row", 0,
                         child.getArea().ry);
        }
    }

    private static boolean overlaps(Area a, Area b) {
        return a.x() < b.ex() && b.x() < a.ex() && a.y() < b.ey() && b.y() < a.ey();
    }

    /**
     * The badge is all-or-nothing on the line it shares with the quantity too.
     *
     * The two-line node asks {@link PlannerWidgets#badgeWidthBeside} rather than
     * {@link PlannerWidgets#badgeWidthFor}, because on that line no label is competing. The
     * rule it must not lose in the move is that a truncated badge is always a bug: the
     * vocabulary is fixed, so the alternative to truncating is omitting.
     */
    @Test
    public void theBadgeBesideTheQuantityIsAlsoNeverPartiallyDrawn() {
        for (int room = 0; room <= 400; room += 3) {
            int badge = PlannerWidgets.badgeWidthBeside(room);
            assertTrue("with " + room + "px beside the quantity the badge came out " + badge,
                       badge == 0 || badge == PlannerWidgets.BADGE);
        }
        assertEquals("exactly enough room is enough", PlannerWidgets.BADGE,
                     PlannerWidgets.badgeWidthBeside(PlannerWidgets.BADGE));
        assertEquals("one pixel short is nothing", 0,
                     PlannerWidgets.badgeWidthBeside(PlannerWidgets.BADGE - 1));
    }

    /**
     * A node can shed its label without losing its icon or its quantity.
     *
     * WHAT THE DIAGRAM DOES BELOW `FlowZoom.LABEL_LEGIBLE`. The quantity must survive, because
     * `FlowZoom.MIN`'s own comment says the badge COLOUR and the quantity are what a reader
     * zoomed out is going by -- and `NodeStatus.colour` is applied to the quantity, so hiding
     * it would take the status channel with it.
     */
    @Test
    public void hidingDetailKeepsTheQuantityAndTakesTheLabel() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        PlannerWidgets.NodeContent box =
                PlannerWidgets.planNodeContent(node, 209, 26, PlannerActions.NONE);
        HeadlessLayout.layOutPanel("detail-node", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, box);

        int all = box.getChildren().size();
        assertTrue("a node must have more than one column for this to say anything", all > 1);
        for (IWidget child : box.getChildren()) {
            assertTrue("everything is built enabled, so the sizer never skips it",
                       child.isEnabled());
        }

        box.showDetail(false);
        int left = 0;
        for (IWidget child : box.getChildren()) {
            if (child.isEnabled()) {
                left++;
            }
        }
        assertTrue("something must go, or the level of detail has no levels", left < all);
        assertTrue("and something must stay: the quantity carries the status colour", left > 0);

        box.showDetail(true);
        for (IWidget child : box.getChildren()) {
            assertTrue("the labels must come back when the zoom does", child.isEnabled());
        }
    }

    /**
     * A craft node carries its machine badge bottom-right, and it goes away with the label. #333.
     *
     * <h2>WHY THE NODE AND NOT THE EDGE, WHICH IS WHAT #333 LITERALLY ASKED FOR</h2>
     *
     * A craft node has N input edges and exactly one machine, so an icon "on the line between
     * nodes" is N copies of one fact or an arbitrary pick of which edge gets it; and edges are
     * drawn by `FlowCanvas` rather than being widgets, whose own javadoc says why -- "making each
     * one a widget would double the child count for something that never needs to be hit-tested"
     * -- so a hoverable icon there means hand-rolling point-in-rect tests in the one class that
     * declined to host widgets. `twoLineNode`'s own comment carries the full argument.
     *
     * <h2>IN `detail`, WHICH IS THE HALF A SCREENSHOT WOULD NOT CATCH</h2>
     *
     * A 10px sprite at `FlowZoom.MIN` is five physical pixels. `FlowZoom.MIN`'s comment already
     * establishes what survives a zoom-out -- the icon and the coloured quantity -- so the
     * machine badge belongs with the label and the badge word, which go below
     * `FlowZoom.LABEL_LEGIBLE`. The ITEM icon is deliberately NOT detail, and asserting that both
     * of those are still true is what stops the badge being added to the wrong list: a badge in
     * the permanent set would be an unreadable smear at every zoom-out, and the item icon in the
     * detail set would leave an empty box.
     */
    @Test
    public void aCraftNodesMachineBadgeSitsBottomRightAndHidesWithTheLabel() {
        PlanNode node = machineNode("unavailable", "no blueprint in reach");
        PlannerWidgets.NodeContent box =
                PlannerWidgets.planNodeContent(node, 209, 26, PlannerActions.NONE);
        HeadlessLayout.layOutPanel("machine-node", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, box);

        PlannerWidgets.Icon badge = null;
        for (IWidget child : box.getChildren()) {
            if (child instanceof PlannerWidgets.Icon) {
                badge = (PlannerWidgets.Icon) child;
            }
        }
        assertNotNull("a roadblocked craft node must carry a machine badge", badge);
        assertEquals("and it must carry the roadblock mark, which is the signal a player scans"
                     + " the canvas for", NodeStatus.INK_NEED, badge.frameColour());
        assertNotNull("...and the tooltip naming the machine", badge.getTooltip());

        // BOTTOM-RIGHT, INSIDE `NODE_PAD`. Asserted as a position rather than trusted, because
        // `FlowCanvas` gives every node a `MC_BACKGROUND` nine-slice whose frame occupies the
        // outer two or three pixels -- `NODE_PAD`'s own javadoc records the screenshot where
        // content positioned from 0 was drawn underneath that bevel.
        // RELATIVE TO THE BOX AND NOT TO THE PANEL. `getArea()` is absolute after layout, so
        // comparing against the node's declared width would be off by the panel's own offset --
        // the same mistake `theRightClusterIsTheSameWidthAtEveryDepth` records making once.
        assertEquals("the badge's right edge must sit exactly NODE_PAD inside the box",
                     box.getArea().ex() - PlannerWidgets.NODE_PAD, badge.getArea().ex());
        assertTrue("...and it must be on the lower of the two lines",
                   badge.getArea().y() - box.getArea().y() >= PlannerWidgets.LINE);

        box.showDetail(false);
        assertFalse("the machine badge is detail: at zoom 0.5 a 10px sprite is five physical"
                    + " pixels, which is what FlowZoom.MIN says a reader is NOT reading",
                    badge.isEnabled());
        box.showDetail(true);
        assertTrue(badge.isEnabled());
    }

    /**
     * A diagram node's label gets the same room whether or not a machine resolved. #333.
     *
     * `ICON`'s COLUMN RULE, ON THE SURFACE WHERE IT WAS BROKEN FIRST. `twoLineNode` subtracted
     * the machine column only when `machineCell` returned one, and that method answers null for a
     * machine with no sprite and no roadblock -- which is the state of EVERY client for the 5.47 s
     * of the graph load, and of the 37% of craft nodes whose category has no catalyst at all. So
     * the label was re-fitted from 28 characters to 30 the moment a graph landed under an open
     * diagram, and two adjacent nodes gave the same name different room depending on whether
     * their categories happened to have catalysts.
     *
     * ASSERTED ACROSS THE THREE CASES, because two of them agree by accident otherwise: a node
     * whose machine has no cell at all (hand crafting), one whose machine is a roadblock and
     * therefore always has a cell, and one with a machine name and neither sprite nor roadblock
     * -- the last is the case that used to differ and the one `NodeActions.NONE` puts every node
     * in here.
     */
    @Test
    public void aDiagramNodesLabelGetsTheSameRoomWhetherOrNotAMachineResolved() {
        int handCrafted = labelRoomOfNode(handCraftedNode());
        int roadblocked = labelRoomOfNode(machineNode("unavailable", "no blueprint in reach"));
        int quietMachine = labelRoomOfNode(machineNode("have", null));
        assertEquals("a hand-crafted node and a roadblocked one must give their labels the same"
                     + " room; the machine column is charged either way", handCrafted, roadblocked);
        assertEquals("...and so must a node whose machine has a name but no picture, which is the"
                     + " case that re-flowed when a graph finished loading",
                     handCrafted, quietMachine);
        // AND THE ROOM MUST BE THE CHARGED ONE, not the unreduced line. Without this all three
        // could agree at `right - x` and the column would be free again.
        int charged = NODE_WIDTH - PlannerWidgets.NODE_PAD * 2
                - PlannerWidgets.NODE_ICON - PlannerWidgets.GAP
                - PlannerWidgets.ICON - PlannerWidgets.GAP;
        assertEquals("the label column must be the node minus both icon columns", charged,
                     handCrafted);
    }

    /**
     * `FlowLayout.NODE_WIDTH` and `NODE_HEIGHT`, SPELLED RATHER THAN IMPORTED.
     *
     * `flow` depends on `planner` and not the reverse, and a test that inverted that would be
     * the first thing in the package to do so -- `aTallNodePutsItsLabelOnASecondLineWithRoomFor`
     * `AWholeName` states the rule and `aCraftNodesMachineBadgeSitsBottomRightAndHidesWithThe`
     * `Label` follows it. `FlowCanvasTest` is where the two numbers are pinned, so a change to
     * them fails there rather than passing quietly here.
     */
    private static final int NODE_WIDTH = 209;
    private static final int NODE_HEIGHT = 26;

    /** The width of the label cell on `node`'s second line, from a laid-out diagram node. */
    private static int labelRoomOfNode(PlanNode node) {
        PlannerWidgets.NodeContent box = PlannerWidgets.planNodeContent(
                node, NODE_WIDTH, NODE_HEIGHT, PlannerActions.NONE);
        HeadlessLayout.layOutPanel("machine-room", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, box);
        // THE WIDEST TEXT CELL IS THE LABEL, found by width rather than by child order for the
        // reason `entryLines`' helper records: #333 changed the order once already and an index
        // silently started reporting the badge.
        int widest = 0;
        for (IWidget child : HeadlessLayout.flatten(box)) {
            if (child instanceof com.cleanroommc.modularui.widgets.TextWidget
                    && child.getArea().w() > widest) {
                widest = child.getArea().w();
            }
        }
        assertTrue("the node drew no text cell at all", widest > 0);
        return widest;
    }

    /** A craft node whose category is hand crafting, so `machineCell` builds nothing for it. */
    private static PlanNode handCraftedNode() {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "mod:thing");
        json.addProperty("label", "Thing");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.CRAFT);
        json.addProperty("category", "crafting_shaped");
        json.addProperty("machine", "Crafting (shaped)");
        return PlanJson.readNode(json);
    }

    /**
     * #213: every occurrence of the selected ITEM highlights, in the tree.
     *
     * THE MISSING HALF. `LivePlannerActions.openNodeMenu` wrote a selection on every click and
     * nothing in `mod/src/main` read `isSelected`, `selectedKey` or `selectedNode` -- so the
     * write was correct and no highlight was drawn on any surface. This is red on the
     * pre-change tree because `ClickableGroup` had no notion of a key at all.
     *
     * BY KEY AND NOT BY NODE, which is `PlanSelection`'s design and the property a picture
     * cannot distinguish: a shot of one highlighted row is equally consistent with a holder
     * that only lit the box that was clicked. `plan-same-name` uses `minecraft:iron_ingot`
     * twice, so a key-only read lights two rows and a node-identity read lights one.
     */
    @Test
    public void everyOccurrenceOfTheSelectedItemHighlightsInTheTree() {
        PlanView plan = PlanFixtures.load("plan-same-name");
        String repeated = "minecraft:iron_ingot";
        int occurrences = 0;
        for (PlanNode node : plan.flatten()) {
            if (repeated.equals(node.key())) {
                occurrences++;
            }
        }
        assertTrue("the fixture must use " + repeated + " more than once, saw " + occurrences,
                   occurrences > 1);

        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), matching(), recorder());
        HeadlessLayout.layOut(panel);
        List<PlannerWidgets.ClickableGroup> rows = clickables(panel);
        try {
            for (PlannerWidgets.ClickableGroup row : rows) {
                assertFalse("nothing is selected yet", row.drawsAsSelected());
            }

            PlanSelection.select(occurrenceOf(plan, repeated));
            int lit = 0;
            for (PlannerWidgets.ClickableGroup row : rows) {
                if (row.drawsAsSelected()) {
                    lit++;
                }
            }
            assertEquals("every row for the selected key lights up, and only those",
                         occurrences, lit);
        } finally {
            // A STATIC HOLDER IS SHARED BY EVERY TEST IN THIS JVM. A leftover selection makes
            // an unrelated geometry assertion draw a highlight it never set up.
            PlanSelection.clear();
        }
    }

    /**
     * A diagram node reads the same selection the tree does, from the same one change.
     *
     * #213 predicted this: "a tree row and a diagram node already share `planNodeContent`, so
     * one change covers both surfaces". They share `ClickableGroup` rather than that method --
     * `row` is deliberately separate, see its own note -- and the click target is what carries
     * the key, so the prediction holds through a different seam than the issue named.
     */
    @Test
    public void aDiagramNodeHighlightsFromTheSameSelectionTheTreeReads() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        PlannerWidgets.NodeContent box =
                PlannerWidgets.planNodeContent(node, 209, 26, PlannerActions.NONE);
        try {
            assertFalse(box.drawsAsSelected());
            PlanSelection.select(node);
            assertTrue("the diagram node must read the selection too", box.drawsAsSelected());
        } finally {
            PlanSelection.clear();
        }
    }

    /**
     * A menu row and a picker row are not about one item, and must never light up.
     *
     * `PlanSelection.isSelected("")` is false and `PlanSelectionTest` asserts it -- otherwise a
     * node whose key failed to parse would read as permanently selected. This is the other end
     * of that: the rows that legitimately have no key pass "" and stay dark whatever is
     * selected, including while the menu FOR the selected node is open, which is every time
     * one is open at all.
     */
    @Test
    public void aMenuRowNeverHighlightsEvenWhileItsOwnNodeIsSelected() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        Recorder recorder = new Recorder();
        recorder.recipeViewerAvailable = true;
        ModularPanel menu = PlannerWidgets.nodeMenu(node, recorder);
        HeadlessLayout.layOut(menu);
        try {
            PlanSelection.select(node);
            for (PlannerWidgets.ClickableGroup entry : clickables(menu)) {
                assertFalse("a menu entry is not the item", entry.drawsAsSelected());
            }
        } finally {
            PlanSelection.clear();
        }
    }

    /**
     * A TOKEN draws a mark in the icon column, where an item would draw its sprite.
     *
     * WHY, AND IT IS NOT A STYLE CHOICE. A pack token is a REGISTERED item -- `plan-token-gate`
     * is `contenttweaker:dungeon_drop`, `status=token`, `token_kind=loot` -- so `iconFor`
     * succeeds and hands back a perfectly good picture of a thing that does not exist. #174 was
     * reported on exactly that read: "the osiris spinel shows it requires a Dungeon Drop which
     * implies it is an item". Sprite plus red quantity plus a count is three signals all saying
     * "item".
     *
     * ASSERTED AS A CHILD IN THE ICON COLUMN, which is the strongest thing a headless test can
     * say here: a `TextWidget`'s `IKey` cannot be read back without a font renderer, so this
     * cannot check that the character is `!`. What it can check is that a token node carries a
     * column an otherwise identical non-token node does not, at the icon's own x and width. Red
     * on the pre-change tree, where a token drew a sprite and nothing else.
     *
     * THE SPRITE BEING SKIPPED IS STRUCTURAL AND NOT ASSERTED HERE: `twoLineNode`, `oneLineNode`
     * and `row` all read `if (!tokenMark(...)) iconIfAny(...)`, and proving the negative would
     * need a populated `NodeActions` and therefore a real `ItemStack` and Bootstrap, which would
     * put global mutable state under every geometry assertion in this class. See
     * `PlannerActions.NONE` for why that trade is refused.
     */
    @Test
    public void aTokenNodeMarksTheIconColumnInsteadOfDrawingASprite() {
        PlanNode token = PlanFixtures.load("plan-token-gate").tree();
        PlanNode item = PlanFixtures.load("plan-in-stock").tree();
        assertTrue("the fixture must actually be a token, or this asserts nothing",
                   NodeStatus.isToken(token));
        assertFalse("and the control must not be one", NodeStatus.isToken(item));

        List<IWidget> marked = columnsOf(
                PlannerWidgets.planNodeContent(token, 209, 26, PlannerActions.NONE));
        List<IWidget> plain = columnsOf(
                PlannerWidgets.planNodeContent(item, 209, 26, PlannerActions.NONE));
        assertEquals("a token carries exactly one column a plain item does not",
                     plain.size() + 1, marked.size());

        // AND IT IS IN THE ICON COLUMN, not appended after the label. The position is the point:
        // `NodeContent.showDetail` drops the label and the badge word below
        // `FlowZoom.LABEL_LEGIBLE`, and the icon column is not `detail`, so a mark placed there
        // is the one carrier that survives a zoomed-out diagram. A mark in the label would be
        // dropped at exactly the zoom where hue becomes the only difference from a genuine NEED.
        IWidget mark = marked.get(0);
        assertEquals("the mark starts where the icon would", PlannerWidgets.NODE_PAD,
                     mark.getArea().rx);
        assertEquals("and is the icon column's width", PlannerWidgets.NODE_ICON,
                     mark.getArea().w());
    }

    /** A tree row marks a token the same way, so the two surfaces cannot disagree. */
    @Test
    public void aTreeRowMarksATokenToo() {
        PlanNode token = PlanFixtures.load("plan-token-gate").tree();
        PlanNode item = PlanFixtures.load("plan-in-stock").tree();
        int marked = columnsOf(PlannerWidgets.row(token, 0, PlannerWidgets.CONTENT_WIDTH,
                                                 PlannerActions.NONE)).size();
        int plain = columnsOf(PlannerWidgets.row(item, 0, PlannerWidgets.CONTENT_WIDTH,
                                                PlannerActions.NONE)).size();
        assertEquals("the tree row marks a token as well as the diagram node",
                     plain + 1, marked);
    }

    /** A node's own columns, laid out. Not `flatten`, which would include the node itself. */
    private static List<IWidget> columnsOf(ParentWidget<?> box) {
        HeadlessLayout.layOutPanel("columns", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, box);
        return new java.util.ArrayList<IWidget>(box.getChildren());
    }

    private static PlanNode occurrenceOf(PlanView plan, String key) {
        for (PlanNode node : plan.flatten()) {
            if (key.equals(node.key())) {
                return node;
            }
        }
        throw new AssertionError("no node with key " + key);
    }

    /**
     * A token's menu offers "Show uses" and not "Show recipes" (#174).
     *
     * The node-menu half of the same defect the icon column has: a token is a registered item, so
     * every JEI check passes and "Show recipes" would open a recipe screen for what makes a Dungeon
     * Drop, which is nothing. Red on the pre-change tree, where one `canShowInRecipeViewer` gated
     * both entries together and a token therefore got both.
     */
    @Test
    public void aTokensMenuOffersUsesAndNotRecipes() {
        PlanNode token = PlanFixtures.load("plan-token-gate").tree();
        PlanNode item = PlanFixtures.load("plan-in-stock").tree();
        assertTrue(NodeStatus.isToken(token));
        assertFalse(NodeStatus.isToken(item));

        Recorder recorder = new Recorder();
        recorder.recipeViewerAvailable = true;

        // ASSERTED BY CLICKING EVERY ENTRY, NOT BY COMPARING THE TWO MENUS' SIZES. The first
        // version of this test asserted the token menu had one entry fewer than the item's, and it
        // FAILED for a reason that had nothing to do with tokens: `plan-in-stock`'s root carries
        // `alternatives = 5` so it also gets "Choose another recipe", and `plan-token-gate`'s root
        // carries none, so the two menus differ by two entries rather than one. Two fixtures that
        // differ in more than the property under test is the trap this package keeps re-finding,
        // and a count cannot say WHICH entry was dropped anyway. What each menu invokes can.
        assertEquals("an ordinary item offers both",
                     java.util.Arrays.asList("showRecipes:" + item.key(),
                                             "showUses:" + item.key()),
                     viewerCalls(PlannerWidgets.nodeMenu(item, recorder), recorder));
        assertEquals("a token offers what uses it and not what makes it",
                     java.util.Arrays.asList("showUses:" + token.key()),
                     viewerCalls(PlannerWidgets.nodeMenu(token, recorder), recorder));
    }

    /** Click every entry of `menu` and return only the recipe-viewer calls, in menu order. */
    private static List<String> viewerCalls(ModularPanel menu, Recorder recorder) {
        HeadlessLayout.layOut(menu);
        recorder.calls.clear();
        for (PlannerWidgets.ClickableGroup row : clickables(menu)) {
            row.onMousePressed(0);
        }
        List<String> viewer = new java.util.ArrayList<String>();
        for (String call : recorder.calls) {
            if (call.startsWith("showRecipes:") || call.startsWith("showUses:")) {
                viewer.add(call);
            }
        }
        return viewer;
    }

    private static int countRows(ModularPanel panel) {
        return findList(panel).getChildren().size();
    }

    private static ListWidget<?, ?> findList(IWidget root) {
        for (IWidget widget : HeadlessLayout.flatten(root)) {
            if (widget instanceof ListWidget) {
                return (ListWidget<?, ?>) widget;
            }
        }
        throw new AssertionError("no ListWidget in the planner tree");
    }

    private static PlanNode deepest(PlanNode node) {
        PlanNode current = node;
        while (current.hasChildren()) {
            current = current.children().get(0);
        }
        return current;
    }

    // -- #331: a refused stock read is unmissable on the plan -----------------------------------

    /**
     * Install a refused ME read the way the game does, so `PlanCaveats` answers from it.
     *
     * WHICH REASON IS ARBITRARY, AND THAT IS THE POINT OF THE PROMINENCE LINE: it says the same
     * thing for every refusal, because what it names is the CONSEQUENCE for the rows below rather
     * than the cause. So these cases pick refusals that can actually be handed to a player -- never
     * the retired `OUT_OF_RANGE` (#331) -- and nothing here depends on which one.
     *
     * THROUGH `LiveStock` AND `PlannerStock.install`, NEVER BY STUBBING A `ScenarioSource` READER.
     * The claim under test is that the refusal travels from the held snapshot to the panel, and a
     * stubbed reader returning a string I supplied asserts that a string I supplied comes back --
     * which `PlanCaveatsTest`'s own header calls the shape of a test that passes for as long as the
     * defect exists.
     */
    private static void refuse(
            io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason reason) {
        io.github.jacoblasky.recipedump.client.LiveStock.accept(
                io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.unavailable(reason));
        io.github.jacoblasky.recipedump.client.PlannerStock.install();
    }

    /** The y of the first text widget on `panel` whose string is exactly `text`, or -1. */
    private static int yOfLine(ModularPanel panel, String text) {
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (widget instanceof com.cleanroommc.modularui.widgets.TextWidget
                    && text.equals(((com.cleanroommc.modularui.widgets.TextWidget<?>) widget)
                                           .getKey().getFormatted())) {
                return widget.getArea().y();
            }
        }
        return -1;
    }

    /**
     * WITNESS. A refused stock read is said ABOVE the tree, not only under it.
     *
     * ON MASTER THIS FAILS, AND THE FAILING HALF IS THE POSITION RATHER THAN THE WORDS. #321
     * already got the refusal's own reason onto `PlanCaveats.summaryLine`, which renders at the
     * FOOTER -- so a test that only looked for a string about the refusal passed while the defect
     * was live. What Jake actually read on 0.11.0 was a tree of rows saying `needed`, in red, at
     * full height, and he reported "the ingredients still aren't being detected correctly". So the
     * assertion is that the admission is above the thing it contradicts.
     *
     * ASSERTED ON THE DRAWN STRING, through the same `fit` every line here goes through, so a
     * sentence too long for the column fails as a cut string rather than passing as a substring
     * match on the source constant.
     */
    @Test
    public void aRefusedStockReadIsSaidAboveTheTreeAndNotOnlyInTheFooter() {
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NO_POWER);
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), matching(),
                                                        recorder());
        HeadlessLayout.layOut(panel);

        int refusalY = yOfLine(panel, PlanCaveats.STOCK_REFUSED);
        assertTrue("the refusal never reached the panel at all: " + dumpText(panel),
                   refusalY >= 0);
        int treeY = findList(panel).getArea().y();
        assertTrue("the refusal is drawn at " + refusalY + ", at or below the tree it contradicts"
                   + " at " + treeY + " -- a reader who has just been told he needs everything"
                   + " does not scroll past it to find out why",
                   refusalY < treeY);
    }

    /**
     * CONTROL. A successful read draws no refusal line.
     *
     * WITHOUT THIS THE WITNESS IS SATISFIED BY A PANEL THAT ALWAYS SHOUTS, which is worse than
     * silence: a red line on every plan is noise that gets tuned out, and it takes the line that
     * matters with it. That is `staleGraphWarning`'s own argument for staying silent on `MATCHES`.
     */
    @Test
    public void aSuccessfulStockReadDrawsNoRefusalLine() {
        io.github.jacoblasky.recipedump.client.LiveStock.accept(
                io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.of(
                        java.util.Collections.singletonMap("minecraft:stone", Long.valueOf(1L)),
                        io.github.jacoblasky.recipedump.common.ae2.Snapshots.NO_MACHINES));
        io.github.jacoblasky.recipedump.client.PlannerStock.install();
        ModularPanel panel = PlannerWidgets.plannerPanel(PlanFixtures.load("plan-in-stock"),
                                                        emptyBook(), matching(), recorder());
        HeadlessLayout.layOut(panel);
        assertEquals("a read that succeeded must not claim it was refused",
                     "", PlanCaveats.stockRefusalLine());
        assertEquals("...and nothing on the panel may say so either: " + dumpText(panel),
                     -1, yOfLine(panel, PlanCaveats.STOCK_REFUSED));
    }

    /**
     * CONTROL. A pack with no Applied Energistics is silent, forever.
     *
     * THE ONE REFUSAL THAT IS NOT A STATE THE PLAYER CAN LEAVE. Every other reason is somewhere he
     * is standing; `NO_AE2` is a property of the modpack, so a red line about an ME network would
     * be permanent furniture on a game that has none. `PlannerStock.refusedReason` is where that
     * exception is argued.
     */
    @Test
    public void withoutAppliedEnergisticsThereIsNoRefusalLineToDraw() {
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NO_AE2);
        assertEquals("a pack with no AE2 has no ME network to fail to read",
                     "", PlanCaveats.stockRefusalLine());
        // AND THE DISCRIMINATOR: the same call on a real refusal is not empty, so this is a
        // statement about `NO_AE2` rather than about a method that always answers "".
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NO_POWER);
        assertEquals(PlanCaveats.STOCK_REFUSED, PlanCaveats.stockRefusalLine());
    }

    /**
     * GUARD. The refusal line fits the tree panel's column with nothing cut.
     *
     * IT IS FIXED TEXT AND THE COLUMN IS FIXED, so this is an arithmetic fact that can simply be
     * asserted -- and it is the assertion that makes `PlanCaveats.STOCK_REFUSED`'s "fits FOREVER"
     * claim checkable rather than a promise. Both panels that draw it are checked, because the tree
     * panel is the narrower of the two and a line that fits there cannot wrap on the diagram.
     */
    @Test
    public void theRefusalLineIsNeverCutOnEitherPanelThatDrawsIt() {
        assertEquals("the refusal line does not fit the tree panel's column; shorten the words"
                     + " rather than widening the panel",
                     PlanCaveats.STOCK_REFUSED,
                     NodeRowText.fit(PlanCaveats.STOCK_REFUSED, PlannerWidgets.CONTENT_WIDTH));
        assertEquals("...nor the diagram's chrome band",
                     PlanCaveats.STOCK_REFUSED,
                     NodeRowText.fit(PlanCaveats.STOCK_REFUSED,
                                     PlannerWidgets.FLOW_WIDTH - PlannerWidgets.FLOW_PAD * 2));
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NOT_LINKED);
        ModularPanel panel = PlannerWidgets.plannerPanel(PlanFixtures.load("plan-in-stock"),
                                                        emptyBook(), matching(), recorder());
        HeadlessLayout.layOut(panel);
        assertTrue("and it must reach the panel uncut: " + dumpText(panel),
                   texts(panel).contains(PlanCaveats.STOCK_REFUSED));
    }

    /**
     * Clicking the refusal line opens the panel that explains it.
     *
     * A RED LINE THAT LOOKS LIKE THE CLICKABLE CAVEAT AND IS NOT ONE is the "look right and do
     * nothing" failure `planNodeContent`'s inert overload warns about, and ModularUI reports
     * nothing for it. The gesture is written down on the footer line rather than here -- see
     * `plannerPanel` -- so this is the affordance a player finds by trying.
     *
     * BY POSITION AND NOT BY INDEX. The refusal block is the FIRST clickable outside the view bar,
     * because it is added directly under it; `clickingTheCaveatOpensWhatThePlanCouldNotSee` takes
     * the LAST one, and the two together pin that the panel has two independent ways in.
     */
    @Test
    public void clickingTheRefusalLineOpensWhatThePlanCouldNotSee() {
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NETWORK_GONE);
        Recorder recorder = new Recorder();
        ModularPanel panel = PlannerWidgets.plannerPanel(PlanFixtures.load("plan-in-stock"),
                                                        emptyBook(), matching(), recorder);
        HeadlessLayout.layOut(panel);

        PlannerWidgets.ClickableGroup block = blockHolding(panel, PlanCaveats.STOCK_REFUSED);
        assertNotNull("the refusal is drawn on nothing clickable: " + dumpText(panel), block);
        assertEquals("a zero-height target draws its line and cannot be hit",
                     PlannerWidgets.LINE, block.getArea().h());
        assertEquals("and it must be as wide as the line it sits under",
                     PlannerWidgets.CONTENT_WIDTH, block.getArea().w());
        block.onMousePressed(0);
        assertEquals(java.util.Arrays.asList("caveats"), recorder.calls);
    }

    /** The innermost clickable block that draws `text`, or null. */
    private static PlannerWidgets.ClickableGroup blockHolding(ModularPanel panel, String text) {
        PlannerWidgets.ClickableGroup found = null;
        for (PlannerWidgets.ClickableGroup group : clickables(panel)) {
            if (textIn(group).contains(text)) {
                found = group;
            }
        }
        return found;
    }

    // -- #329: the diagram carries the caveat, not only the truncation warning ------------------

    /**
     * WITNESS. The diagram admits a stock-blind plan instead of looking fully-informed.
     *
     * ON MASTER THIS FAILS AND `aTruncatedPlansDiagramCarriesTheSameWarningTheTreeDoes` PASSES,
     * which is the whole of #329: #327 repeated the LOUD problem on this panel and left the quiet
     * one on the tree's footer. A player who opens the diagram and never scrolls the tree learned
     * that the plan was CUT and not that it had been solved against an empty inventory -- and
     * every node in the picture reads as needed.
     *
     * BOTH LINES, BECAUSE THEY SAY DIFFERENT THINGS. The refusal line is the consequence for the
     * nodes on screen; the caveat line is the cause, the field list and the way in.
     */
    @Test
    public void theDiagramCarriesTheCaveatLineAndTheRefusalAndNotOnlyTheWarnings() {
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NO_POWER);
        PlanView plan = PlanFixtures.load("plan-truncated");
        List<String> text = flowPanelText(plan);
        assertTrue("the diagram must say the plan was solved without your stock: " + text,
                   text.contains(PlanCaveats.STOCK_REFUSED));
        List<String> caveat = PlannerWidgets.flowCaveatLines();
        assertFalse("the fixture must produce a caveat line, or this measures nothing",
                    caveat.isEmpty());
        for (String line : caveat) {
            assertTrue("the diagram dropped a caveat line: " + line + " from " + text,
                       text.contains(line));
        }
        assertTrue("the way in has to survive onto this panel too: " + text,
                   rejoin(caveat).contains(PlanCaveats.POINTER));
    }

    /**
     * GUARD. The chrome band reserves the height its new lines take.
     *
     * WHAT FAILS SILENTLY OTHERWISE. `FlowCanvas.forPanel` sizes the canvas from
     * `flowCanvasHeight`, which is the panel less `flowChromeHeight` -- so a line DRAWN by
     * `flowPanel` and not counted by `flowChromeHeight` puts the button row past the bottom of its
     * own band and the canvas underneath it. ModularUI neither clamps nor clips (#125), so it is
     * simply drawn wrong and nothing reports it. That is the same trap `flowChromeHeight`'s
     * "ONE PLACE THE SPLIT IS DECIDED" note was written for.
     *
     * THE BUTTON ROW IS THE PROBE BECAUSE IT IS LAST IN THE BAND. Anything uncounted above it
     * pushes it out, so one assertion covers the refusal line and the caveat block together.
     */
    @Test
    public void theDiagramsChromeReservesTheHeightItDraws() {
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NO_POWER);
        PlanView plan = PlanFixtures.load("plan-truncated");
        assertTrue("the fixture must carry a warning too, so this covers all three kinds of line",
                   PlannerWidgets.warningCount(plan) > 0);
        ModularPanel panel = PlannerWidgets.flowPanel(plan, new StubDiagram(),
                                                     PlannerActions.NONE);
        HeadlessLayout.layOut(panel);

        int bandBottom = panel.getArea().y() + PlannerWidgets.FLOW_PAD
                         + PlannerWidgets.flowChromeHeight(plan);
        Area bar = firstViewBarIn(panel).getArea();
        assertTrue("the button row ends at " + (bar.y() + bar.h()) + ", past the chrome band at "
                   + bandBottom + " -- a line is drawn that flowChromeHeight does not count, so"
                   + " the canvas is sized over the top of it",
                   bar.y() + bar.h() <= bandBottom);
        // AND NOTHING THE CHROME DREW REACHES INTO THE CANVAS. `FlowCanvas.forPanel` starts the
        // diagram at `flowCanvasTop`, so a chrome line below that point is drawn over the picture
        // -- the same defect from the other side, and the one the button-row check above cannot
        // see if some line is drawn out of order rather than uncounted.
        //
        // NOT `flowCanvasHeight == FLOW_HEIGHT - flowCanvasTop - FLOW_PAD`, WHICH WAS HERE FIRST
        // AND IS VACUOUS: that is the body of `flowCanvasHeight` restated, so it agrees with every
        // bug it could have had. Same shape as the `fit(label, VIEW_BUTTON_WIDTH)` assertion a
        // reviewer caught on `everyViewLabelIsInTheMeasuredVocabularyAndFitsItsButton`.
        int canvasTop = panel.getArea().y() + PlannerWidgets.flowCanvasTop(plan);
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            if (widget instanceof com.cleanroommc.modularui.widgets.TextWidget) {
                Area box = widget.getArea();
                assertTrue("a chrome line runs to " + (box.y() + box.h()) + ", into the canvas"
                           + " that starts at " + canvasTop + ": "
                           + ((com.cleanroommc.modularui.widgets.TextWidget<?>) widget)
                                   .getKey().getFormatted(),
                           box.y() + box.h() <= canvasTop);
            }
        }
    }

    /**
     * Clicking the diagram's caveat opens the same panel the tree's caveat opens.
     *
     * `PlanCaveats.POINTER` IS INSIDE THOSE WORDS AND IT NAMES A GESTURE, so drawing them inert
     * would put a written-down affordance on screen that does nothing -- worse than saying nothing
     * at all, and the reason #329 called a third sub-panel opener a real scope increase rather
     * than optional.
     */
    @Test
    public void clickingTheDiagramsCaveatOpensWhatThePlanCouldNotSee() {
        refuse(io.github.jacoblasky.recipedump.common.ae2.StockSnapshot.Reason.NO_POWER);
        Recorder recorder = new Recorder();
        ModularPanel panel = PlannerWidgets.flowPanel(PlanFixtures.load("plan-in-stock"),
                                                     new StubDiagram(), recorder);
        HeadlessLayout.layOut(panel);
        List<String> caveat = PlannerWidgets.flowCaveatLines();
        PlannerWidgets.ClickableGroup block = blockHolding(panel, caveat.get(0));
        assertNotNull("the diagram's caveat is drawn on nothing clickable: " + dumpText(panel),
                      block);
        assertEquals("the target must be as tall as the caveat is",
                     PlannerWidgets.LINE * caveat.size(), block.getArea().h());
        block.onMousePressed(0);
        assertEquals(java.util.Arrays.asList("caveats"), recorder.calls);
    }

    private static String rejoin(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    // -- #332: the totals stop hiding, and a level of indent reads as one -----------------------

    /**
     * Every section this panel can draw, in the order it must draw them. #332
     *
     * ONE LIST RATHER THAN SEVEN ASSERTIONS, so a section inserted in `todoLines` and not here
     * fails rather than slipping in wherever it landed. It is the same argument
     * {@link PlannerWidgets#VIEW_LABELS} makes about a measured vocabulary: a second copy of an
     * ordering that nothing welds to the first is an ordering that drifts.
     */
    private static final List<String> SECTIONS_IN_ORDER =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    "still needed for this plan:",
                    "machines to build:",
                    "used from your stock:",
                    "drawn from infinite sources:",
                    "go and get:",
                    "transmuted from EMC:",
                    "on your TODO list:"));

    /**
     * WITNESS. The panel leads with what the plan still needs, and the plan book comes last.
     *
     * ON MASTER THIS FAILS ON THE ORDER ALONE, which is the point: every section asserted here
     * already rendered before #332, and a membership test would have passed for the whole life of
     * the defect. What was wrong is that the first thing a player read was the personal list --
     * usually "nothing on the list" -- so a panel that sums the plan advertised itself as a
     * favourites feature and never got opened twice.
     *
     * <h2>OVER EVERY FIXTURE, BECAUSE NO SINGLE ONE CARRIES ALL SEVEN SECTIONS</h2>
     *
     * The first version of this test named `plan-truncated` and asserted it had a from-stock
     * section. It does not -- it has a nineteen-row shopping list and no `used_from_stock` at all
     * -- so the test failed on its own premise, and the version that would have "passed" is the one
     * that quietly dropped the assertion. `addEntries` and `addSection` both omit an empty section
     * entirely, so ANY single-fixture ordering check is really a check over whichever subset that
     * fixture happens to populate.
     *
     * SO THE SWEEP ASSERTS RELATIVE ORDER OVER WHAT IS PRESENT, and then asserts that the union
     * across fixtures covered every section -- which is what stops a run where three sections
     * never appeared from reading as a check of seven. That second half is the control, and
     * without it this is `PlanFixtureTest`'s skipped-gate shape: an ordering nothing exercised,
     * reported green.
     */
    @Test
    public void everyTotalsSectionAppearsInOrderWithTheShoppingListFirstAndTheBookLast() {
        java.util.Set<String> everSeen = new java.util.LinkedHashSet<String>();
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            PlanBook book = new PlanBook();
            book.setTodo(plan.tree().key(), 7L);
            List<PlannerWidgets.Line> lines =
                    PlannerWidgets.todoLines(plan, book, PlannerWidgets.TODO_WIDTH);

            int previous = -1;
            String previousName = "(nothing)";
            for (String section : SECTIONS_IN_ORDER) {
                int at = indexOfText(lines, section);
                if (at < 0) {
                    continue;
                }
                everSeen.add(section);
                assertTrue(fixture + ": `" + section + "` is drawn at " + at + ", above `"
                           + previousName + "` at " + previous + " -- the panel's sections are"
                           + " out of order: " + lines, at > previous);
                previous = at;
                previousName = section;
            }

            assertEquals(fixture + ": the summed shopping list has to be the FIRST thing on the"
                         + " panel, not the personal list: " + lines,
                         0, indexOfText(lines, "still needed for this plan:"));
            int todo = indexOfText(lines, "on your TODO list:");
            assertTrue(fixture + ": the plan book always gets its heading", todo >= 0);
            assertEquals(fixture + ": the plan book is an appendix, so its one row is the last"
                         + " thing on the panel: " + lines, lines.size() - 2, todo);
        }
        assertEquals("the sweep never exercised every section, so it checked a shorter order than"
                     + " it claims; seen: " + everSeen,
                     new java.util.LinkedHashSet<String>(SECTIONS_IN_ORDER), everSeen);
    }

    /**
     * The plan book's section keeps its header and its placeholder when it is empty.
     *
     * THE ONE PLACE THIS PANEL DEPARTS FROM `addSection`'s HEADER RULE, deliberately. That rule
     * refuses a header over no rows because it reads as a list that failed to load -- but the book
     * is a WRITE path (`PlanBookEdit.SET_TODO` since #140), so a player who adds an item and then
     * finds no section at all cannot tell a working write from a broken one. It is
     * `PlanCaveats.detailLines`' "NOT AN EMPTY PANEL" argument, one panel over.
     */
    @Test
    public void anEmptyPlanBookStillGetsItsHeadingAndSaysItIsEmpty() {
        List<PlannerWidgets.Line> lines =
                PlannerWidgets.todoLines(PlanFixtures.load("plan-in-stock"), emptyBook(),
                                         PlannerWidgets.TODO_WIDTH);
        int todo = indexOfText(lines, "on your TODO list:");
        assertTrue("an empty book still owes the player its heading: " + lines, todo >= 0);
        assertEquals("...and has to say it is empty rather than trailing off",
                     "nothing on the list", lines.get(todo + 1).text);
        assertEquals("which is the last thing on the panel", lines.size() - 1, todo + 1);
    }

    /** Where `text` first appears in `lines`, or -1. */
    private static int indexOfText(List<PlannerWidgets.Line> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (text.equals(lines.get(i).text)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The button and the panel heading are about TOTALS, and neither says TODO. #332
     *
     * ASSERTED ON THE WORDS THE PLAYER READS, not on the constants. `SHOW_TOTALS` and
     * `TOTALS_TITLE` could both be renamed to anything and this would still be checking that the
     * two surfaces agree with each other; what it is really pinning is that the word `TODO` is no
     * longer the NAME of a panel whose contents are five sections of plan arithmetic. The plan
     * book's own section heading still says TODO, and must, because that section really is the
     * TODO list -- so this checks the button and the heading rather than the whole panel.
     */
    @Test
    public void theTotalsButtonAndHeadingNameTotalsRatherThanTheTodoList() {
        assertTrue("the view bar must offer the totals: " + PlannerWidgets.VIEW_LABELS,
                   PlannerWidgets.VIEW_LABELS.contains(PlannerWidgets.SHOW_TOTALS));
        for (String label : PlannerWidgets.VIEW_LABELS) {
            assertFalse("a view button still advertises the TODO list: " + label,
                        label.contains("TODO"));
        }
        assertFalse("the panel heading still names its smallest section: "
                    + PlannerWidgets.TOTALS_TITLE,
                    PlannerWidgets.TOTALS_TITLE.contains("TODO"));
        ModularPanel panel = PlannerWidgets.todoPanel(PlanFixtures.load("plan-in-stock"),
                                                      emptyBook(), PlannerActions.NONE);
        HeadlessLayout.layOut(panel);
        assertTrue("the heading has to actually reach the panel: " + dumpText(panel),
                   texts(panel).contains(PlannerWidgets.TOTALS_TITLE));
    }

    /**
     * GUARD. One level of tree indent is wider than one character of the font.
     *
     * THE ASSERTION THAT GOES RED IF ANYONE PUTS `INDENT` BACK TO 6, which is the whole reason it
     * is a floor rather than an equality: 6 is exactly `NodeRowText.CHAR_WIDTH`, and an equality
     * against 10 would fail for a 12 that is also fine. Reported on 0.11.0 as "it doesn't indent
     * enough when i'm in list view of the ingredients" -- at one glyph a level, and with no text at
     * the start of a row to count the step against, an eight-deep plan read as a flat list.
     *
     * AND THE RENDERED OFFSET, NOT ONLY THE CONSTANT. `row` is what applies the indent, and a
     * change that raised the constant while dropping the multiplication would leave this half
     * green -- which is the shape of the vacuous assertion
     * `everyViewLabelIsInTheMeasuredVocabularyAndFitsItsButton` records a reviewer catching.
     */
    @Test
    public void oneLevelOfIndentIsWiderThanOneCharacterAndTheRowHonoursIt() {
        assertTrue("INDENT is " + PlannerWidgets.INDENT + " against a " + NodeRowText.CHAR_WIDTH
                   + "px glyph, so a level of nesting does not read as one (#332)",
                   PlannerWidgets.INDENT > NodeRowText.CHAR_WIDTH);

        PlanNode leaf = deepest(PlanFixtures.load("plan-fluid-chain").tree());
        int shallow = firstChildX(PlannerWidgets.row(leaf, 0, PlannerWidgets.CONTENT_WIDTH,
                                                    recorder()));
        int deeper = firstChildX(PlannerWidgets.row(leaf, 1, PlannerWidgets.CONTENT_WIDTH,
                                                   recorder()));
        assertEquals("one level of depth must move the row by exactly INDENT; the constant and"
                     + " `row`'s arithmetic have come apart",
                     PlannerWidgets.INDENT, deeper - shallow);
    }

    /**
     * The cap and the indent move together, so the label column does not silently narrow.
     *
     * THE PRODUCT IS THE CONSTRAINT AND NEITHER FACTOR IS. `MAX_INDENT_DEPTH`'s javadoc does this
     * arithmetic out loud: at 8 levels of 10 pixels the label column would be 24 characters, which
     * after `meta`'s 16 and a 3-character separator leaves five characters of item NAME. So this
     * pins the budget rather than either number, and it is the assertion that fails if someone
     * raises `INDENT` again without re-doing the sum.
     *
     * 29 CHARACTERS IS THE FLOOR, AND `NodeRowTextTest` IS WHERE IT COMES FROM.
     * `theYieldSurvivesOnlyTheShortestLabelsAndTheColumnSaysWhy` requires a 10-character label
     * plus a 3-character separator plus a 16-character meta to survive at the cap, which is 29
     * characters, which is 174 pixels.
     *
     * <h2>MEASURED OVER THE FIXTURES SINCE #333, NOT DERIVED FROM CONSTANTS</h2>
     *
     * It used to assert `INDENT * MAX_INDENT_DEPTH <= 53`, and 53 was itself derived: the column
     * was `CONTENT_WIDTH` less the indent, less 68 for the icon and quantity columns, less 93 for
     * the badge, so a 29-character floor left 53 pixels for the indent. Both of those middle
     * numbers are gone -- the quantity and badge columns are per-plan now
     * (`PlannerWidgets.TreeColumns`) and the whole cluster is anchored to the right edge -- so
     * there is no single arithmetic ceiling left to write down.
     *
     * WHAT REPLACES IT IS STRICTLY STRONGER. A literal derived from the widest possible columns
     * is a claim about a row no plan draws; this measures the label column every committed
     * fixture actually gets at the indent cap and takes the narrowest. It fails for the same
     * reasons the old form did -- a bigger `INDENT`, a bigger `MAX_INDENT_DEPTH`, a wider cluster
     * -- and additionally if a future fixture arrives whose own columns are the thing that
     * narrows the label past the floor, which the old form could not see at all.
     */
    @Test
    public void theWholeIndentBudgetStillLeavesTheLabelColumnItsMeasuredFloor() {
        int budget = PlannerWidgets.INDENT * PlannerWidgets.MAX_INDENT_DEPTH;
        assertTrue("the indent must actually indent something", budget > 0);

        int floor = 29 * NodeRowText.CHAR_WIDTH;
        int narrowest = Integer.MAX_VALUE;
        String worst = null;
        for (String fixture : PlanFixtures.names()) {
            PlannerWidgets.TreeColumns columns =
                    PlannerWidgets.TreeColumns.forTree(PlanFixtures.load(fixture).tree());
            int labelStart = budget + PlannerWidgets.ICON + PlannerWidgets.GAP;
            int cluster = columns.clusterWidth(PlannerWidgets.CONTENT_WIDTH, labelStart);
            int label = PlannerWidgets.CONTENT_WIDTH - cluster - PlannerWidgets.GAP - labelStart;
            if (label < narrowest) {
                narrowest = label;
                worst = fixture;
            }
        }
        assertTrue("at the indent cap " + worst + " leaves the label column " + narrowest
                   + "px, which is " + narrowest / NodeRowText.CHAR_WIDTH + " characters against"
                   + " a measured floor of 29; drop MAX_INDENT_DEPTH rather than the column",
                   narrowest >= floor);
        // AND THE FLOOR MUST STILL BE TIGHT, or this passes on a panel with room to spare and
        // stops being a constraint on anything. It is exactly 174px on the three 700-node
        // fixtures, which is the whole reason the number is worth pinning.
        assertTrue("the narrowest column is " + narrowest + "px against a 174px floor, so the"
                   + " budget has slack and this assertion no longer constrains the indent",
                   narrowest <= floor + NodeRowText.CHAR_WIDTH);
    }

    /**
     * THE BUG #333 FIXED, STATED AS A TEST: the right cluster does not move with depth.
     *
     * `row` used to set `x = indent` and place the icon, the quantity and the label from there,
     * so every column slid right by `INDENT` per level and the quantity run drifted diagonally
     * down `disclose-planner.png`. This is that defect's regression guard, and it is deliberately
     * an EQUALITY over every depth rather than a bound: a cluster that moved by one pixel per
     * level would satisfy any "roughly aligned" assertion while looking exactly as wrong.
     *
     * MEASURED FROM THE LAID-OUT WIDGETS AND NOT FROM THE ARITHMETIC. `columnAtDepth`-shaped
     * derivations agree with the production formula by construction and would pass on a `row`
     * that never applied it; the boxes ModularUI's own sizer produced cannot.
     *
     * PAST THE CAP TOO. `plan-fluid-chain` runs to depth 20 against `MAX_INDENT_DEPTH` 5, so the
     * common case in the fixtures is a row whose indent is clamped -- and a clamp applied to the
     * left group but not to the cluster would be invisible below the cap.
     */
    @Test
    public void theRightClusterIsTheSameWidthAtEveryDepth() {
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        PlanNode leaf = deepest(plan.tree());
        PlannerWidgets.TreeColumns columns =
                PlannerWidgets.TreeColumns.forTree(plan.tree());

        int quantityX = -1;
        int badgeX = -1;
        int rowRight = -1;
        int badgeRight = -1;
        for (int depth = 0; depth <= PlannerWidgets.MAX_INDENT_DEPTH + 3; depth++) {
            ParentWidget<?> row = PlannerWidgets.row(leaf, depth, PlannerWidgets.CONTENT_WIDTH,
                                                     recorder(), null, columns);
            List<IWidget> cells = columnsOf(row);
            // The cluster is the two rightmost cells: the quantity, then the badge. Found by
            // WIDTH rather than by index, because the icon column is present only when a stack
            // resolves and `NodeActions.NONE` resolves none, so the index moves in production.
            int qtyAt = -1;
            int badgeAt = -1;
            for (IWidget cell : cells) {
                if (cell.getArea().w() == columns.qty) {
                    qtyAt = cell.getArea().x();
                } else if (columns.badge > 0 && cell.getArea().w() == columns.badge) {
                    badgeAt = cell.getArea().x();
                }
            }
            assertTrue("depth " + depth + " drew no quantity cell of " + columns.qty + "px",
                       qtyAt >= 0);
            assertTrue("depth " + depth + " drew no badge cell of " + columns.badge + "px",
                       badgeAt >= 0);
            if (depth == 0) {
                quantityX = qtyAt;
                badgeX = badgeAt;
                // RELATIVE TO THE ROW, NOT TO THE PANEL. `getArea().x()` is absolute after
                // layout -- the panel offsets its body -- so comparing a cell's x against
                // `CONTENT_WIDTH` is off by that offset and says nothing. The two right EDGES
                // are the pair that means something.
                rowRight = row.getArea().ex();
                badgeRight = badgeAt + columns.badge;
            } else {
                assertEquals("the quantity column moved between depth 0 and depth " + depth
                             + "; the right cluster is sliding with the indent again (#333)",
                             quantityX, qtyAt);
                assertEquals("the badge column moved between depth 0 and depth " + depth,
                             badgeX, badgeAt);
            }
        }
        // AND IT IS ANCHORED TO THE RIGHT EDGE, not merely constant. A cluster pinned at a fixed
        // x would also pass everything above while leaving a gap down the right of the panel.
        assertEquals("the badge must end at the row's own right edge", rowRight, badgeRight);
        assertTrue("the row must be the full content width, or the assertion above is about a"
                   + " row nobody draws", rowRight - badgeX > 0);
    }

    /**
     * The indent moves the NAME and nothing else. #333.
     *
     * THE OTHER HALF OF THE ASSERTION ABOVE, and it needs saying separately: a `row` that had
     * stopped indenting altogether would keep the cluster perfectly still and pass that test
     * while losing the tree structure #332 was reported about. `oneLevelOfIndentIsWiderThanOne`
     * `CharacterAndTheRowHonoursIt` measures the step; this measures that the step reaches the
     * LEFT group only.
     */
    @Test
    public void oneMoreLevelOfDepthMovesTheLabelAndLeavesTheColumnsWhereTheyWere() {
        PlanView plan = PlanFixtures.load("plan-truncated");
        PlanNode leaf = deepest(plan.tree());
        PlannerWidgets.TreeColumns columns = PlannerWidgets.TreeColumns.forTree(plan.tree());

        int[] labelX = new int[2];
        int[] labelWidth = new int[2];
        for (int depth = 0; depth < 2; depth++) {
            ParentWidget<?> row = PlannerWidgets.row(leaf, depth, PlannerWidgets.CONTENT_WIDTH,
                                                     recorder(), null, columns);
            // THE WIDEST CELL IS THE LABEL, and it is found that way rather than by index
            // because the row's first cell is present only when a token mark or an icon was
            // drawn. `TOKEN_MARK`'s cell is `ICON` wide, so "not a cluster column" would also
            // match it and would then be mistaken for the label on a token row -- and
            // `plan-truncated` has tokens.
            for (IWidget cell : columnsOf(row)) {
                if (cell.getArea().w() > labelWidth[depth]) {
                    labelX[depth] = cell.getArea().x();
                    labelWidth[depth] = cell.getArea().w();
                }
            }
            assertTrue("depth " + depth + " drew no label cell wider than the cluster",
                       labelWidth[depth] > columns.qty);
        }
        assertEquals("one level of depth must move the label by exactly INDENT",
                     PlannerWidgets.INDENT, labelX[1] - labelX[0]);
        assertEquals("...and the label is what pays for it, since the cluster does not move",
                     PlannerWidgets.INDENT, labelWidth[0] - labelWidth[1]);
    }

    /**
     * Every badge every fixture draws fits THAT PLAN's own badge column. #333.
     *
     * THE STRENGTHENED FORM OF `theBadgeColumnFitsEveryWordTheVocabularyCanProduce`, which is
     * what pays for `TreeColumns` shrinking the column. `BADGE`'s javadoc says a truncated badge
     * is always a bug because the vocabulary is fixed, and a per-plan width keeps that invariant
     * by construction -- so this asserts the construction rather than trusting it.
     *
     * IT IS A STRONGER CLAIM THAN THE VOCABULARY SWEEP AND NOT A WEAKER ONE. That test walks
     * `NodeStatus.all()`, `tokenKinds()` and `UNSOURCED_BADGE`; this walks `NodeStatus.badge(node)`
     * over 2,289 real nodes, which is the only path that reaches the `Provenance` refinements --
     * `badgeFor(provenance)` is not in the vocabulary the other test enumerates at all.
     */
    @Test
    public void everyBadgeEveryFixtureDrawsFitsThatPlansOwnColumn() {
        int checked = 0;
        int narrower = 0;
        for (String fixture : PlanFixtures.names()) {
            PlanNode root = PlanFixtures.load(fixture).tree();
            PlannerWidgets.TreeColumns columns = PlannerWidgets.TreeColumns.forTree(root);
            if (columns.badge < PlannerWidgets.BADGE) {
                narrower++;
            }
            for (PlanNode node : PlanFixtures.load(fixture).flatten()) {
                String badge = NodeStatus.badge(node);
                assertEquals(fixture + "'s badge column (" + columns.badge + "px) cuts \""
                             + badge + "\"",
                             badge, NodeRowText.fit(badge, columns.badge));
                checked++;
            }
            assertTrue(fixture + " derived a wider badge column than the vocabulary allows",
                       columns.badge <= PlannerWidgets.BADGE);
        }
        assertTrue("this asserted nothing: no fixture nodes were walked", checked > 2000);
        // THE CONTROL. Without it the test passes on a `forTree` that simply returned `BADGE`,
        // which is the change it exists to guard and the one a careless revert produces.
        assertTrue("no fixture derived a narrower badge column than the vocabulary, so nothing"
                   + " was reclaimed and `forTree` may as well be a constant",
                   narrower >= 15);
    }

    /**
     * Every quantity every fixture draws fits that plan's own quantity column. #333.
     *
     * `QTY` WAS 52 FOR ONE BORAX PLAN and was charged on every row of every other. The same
     * no-truncation rule as the badge, and it matters more than it looks: `fit` cuts the TAIL,
     * and a quantity's tail is its `x` and its unit, so a cut quantity reads as a smaller number
     * rather than as a truncated one. That is the one truncation on this panel that produces a
     * WRONG answer instead of an incomplete one.
     */
    @Test
    public void everyQuantityEveryFixtureDrawsFitsThatPlansOwnColumn() {
        int narrower = 0;
        for (String fixture : PlanFixtures.names()) {
            PlanNode root = PlanFixtures.load(fixture).tree();
            PlannerWidgets.TreeColumns columns = PlannerWidgets.TreeColumns.forTree(root);
            if (columns.qty < PlannerWidgets.QTY) {
                narrower++;
            }
            for (PlanNode node : PlanFixtures.load(fixture).flatten()) {
                String quantity = NodeRowText.quantity(node.need());
                assertEquals(fixture + "'s quantity column (" + columns.qty + "px) cuts \""
                             + quantity + "\"",
                             quantity, NodeRowText.fit(quantity, columns.qty));
            }
            assertTrue(fixture + " derived a quantity column below the floor",
                       columns.qty >= PlannerWidgets.MIN_QTY);
        }
        assertEquals("every committed fixture draws a quantity narrower than `QTY`, so all 22"
                     + " should have derived a narrower column",
                     PlanFixtures.names().size(), narrower);
    }

    /**
     * The derived-column figures the #333 javadocs quote, asserted rather than written down.
     *
     * <h2>WHY THIS TEST EXISTS AT ALL</h2>
     *
     * `TreeColumns` made the row's geometry a function of the PLAN, so the javadocs on
     * {@link PlannerWidgets#MAX_INDENT_DEPTH}, {@link PlannerWidgets#MIN_QTY},
     * {@link PlannerWidgets#QTY} and `NodeRowTextTest.columnAtDepth` all quote a spread instead
     * of a single width -- a median, a narrowest, a widest, a count of plans sitting on the
     * quantity floor. Those are exactly the figures this file has already had to re-measure
     * twice: #332 moved `INDENT`, #333 moved the whole layout, and each time a number in prose
     * went on being quoted after it stopped being true. Three of the four were wrong when this
     * test was written -- the median was quoted as 61px against a real 85, the label column at
     * that median as 44 characters against a real 39, and the floor count as 13 against a real
     * 14 -- and nothing failed, because prose has no assertion behind it.
     *
     * SO THE NUMBERS LIVE HERE AND THE JAVADOCS CITE THIS METHOD BY NAME. Re-deriving them is
     * one loop; the point is that changing the fixtures, `NodeStatus.badge`'s vocabulary or
     * either column constant now breaks a test that names what moved, instead of silently
     * falsifying five comments.
     *
     * A MEDIAN OF AN EVEN POPULATION IS NOT ANY FIXTURE'S OWN CLUSTER, which is where the 61
     * came from. A cluster is `ICON + GAP + qty + GAP + badge` with both derived terms a whole
     * number of {@link NodeRowText#CHAR_WIDTH}s, so every real cluster is 4 mod 6 -- 61 is not,
     * and could not have been produced by any plan. It is the mean of the two middle fixtures
     * (82 and 88), which is what a median over 22 values is. Averaged here the same way, and
     * said to be an average, so the next reader does not go looking for the plan that draws it.
     */
    @Test
    public void theDerivedColumnCensusIsWhatTheseJavadocsCite() {
        List<String> names = PlanFixtures.names();
        assertEquals("the census is quoted as being over 22 fixtures", 22, names.size());

        int[] clusters = new int[names.size()];
        int atTheQuantityFloor = 0;
        int narrowerBadge = 0;
        int widest = 0;
        int widestCount = 0;
        for (int i = 0; i < names.size(); i++) {
            PlanNode root = PlanFixtures.load(names.get(i)).tree();
            PlannerWidgets.TreeColumns columns = PlannerWidgets.TreeColumns.forTree(root);
            // THE LABEL START AT THE INDENT CAP, which is the narrowest row production draws and
            // the one every quoted character count is taken at.
            int labelStart = PlannerWidgets.MAX_INDENT_DEPTH * PlannerWidgets.INDENT
                    + PlannerWidgets.ICON + PlannerWidgets.GAP;
            clusters[i] = columns.clusterWidth(PlannerWidgets.CONTENT_WIDTH, labelStart);
            if (clusters[i] > widest) {
                widest = clusters[i];
                widestCount = 0;
            }
            if (clusters[i] == widest) {
                widestCount++;
            }
            // THE DERIVED QUANTITY BEFORE THE FLOOR IS APPLIED, because "sits on the floor" is a
            // statement about the plan and `columns.qty` has already lost it to `Math.max`.
            if (PlannerWidgets.widestQuantityIn(root) <= PlannerWidgets.MIN_QTY) {
                atTheQuantityFloor++;
            }
            if (columns.badge < PlannerWidgets.BADGE) {
                narrowerBadge++;
            }
        }
        java.util.Arrays.sort(clusters);

        assertEquals("MIN_QTY's javadoc says 14 of the 22 plans derive a quantity at or below"
                     + " the floor", 14, atTheQuantityFloor);
        assertEquals("TreeColumns' javadoc says 19 of them derive a narrower badge column than"
                     + " the vocabulary allows", 19, narrowerBadge);
        assertEquals("the widest cluster any fixture derives, quoted in four places", 148,
                     clusters[clusters.length - 1]);
        assertEquals("...and it is the three 700-node fixtures that derive it, which is what"
                     + " `theWholeIndentBudgetStillLeavesTheLabelColumnItsMeasuredFloor`'s"
                     + " tightness assertion is measuring", 3, widestCount);
        assertEquals("the narrowest cluster any fixture derives", 64, clusters[0]);
        int median = (clusters[clusters.length / 2 - 1] + clusters[clusters.length / 2]) / 2;
        assertEquals("the median cluster, quoted by MAX_INDENT_DEPTH and by"
                     + " `NodeRowTextTest.columnAtDepth`", 85, median);

        // AND THE CHARACTER COUNTS THOSE WIDTHS ARE QUOTED AS, through the same division `fit`
        // spends, so a comment saying "39 characters" cannot drift from the width beside it.
        int labelStart = PlannerWidgets.MAX_INDENT_DEPTH * PlannerWidgets.INDENT
                + PlannerWidgets.ICON + PlannerWidgets.GAP;
        int atMedian = PlannerWidgets.CONTENT_WIDTH - median - PlannerWidgets.GAP - labelStart;
        assertEquals("the median cluster leaves the label column this many characters at the cap",
                     39, atMedian / NodeRowText.CHAR_WIDTH);
        int atNarrowest = PlannerWidgets.CONTENT_WIDTH - clusters[0] - PlannerWidgets.GAP
                - labelStart;
        assertEquals("and the narrowest cluster this many, which is the widest label column any"
                     + " plan gets at the cap", 43, atNarrowest / NodeRowText.CHAR_WIDTH);
    }

    /**
     * The quantity cell is right-aligned and no other cell is. #333.
     *
     * THE ONLY HEADLESS VIEW OF P2's OTHER HALF. `TextWidget` applies its alignment inside `draw`,
     * through `TextRenderer.setAlignment`, so the drawn position needs a font renderer and a GL
     * context and a layout assertion cannot reach it. What a test CAN read is the property the
     * draw pass will consult, and reading it is what stops the call being silently dropped --
     * exactly the gap `ClickableGroup.drawsAsSelected` was made public to close for #213.
     *
     * AND THAT THE LABEL IS NOT, which is the half that makes this a statement about the design
     * rather than about one call: `rightLine`'s javadoc says a quantity is a magnitude and every
     * other cell is a word, and a right-aligned label would be the panel forgetting that.
     */
    @Test
    public void theQuantityCellIsRightAlignedAndTheLabelIsNot() {
        PlanView plan = PlanFixtures.load("plan-truncated");
        PlannerWidgets.TreeColumns columns = PlannerWidgets.TreeColumns.forTree(plan.tree());
        ParentWidget<?> row = PlannerWidgets.row(plan.tree(), 0, PlannerWidgets.CONTENT_WIDTH,
                                                 recorder(), null, columns);
        columnsOf(row);

        int aligned = 0;
        int left = 0;
        for (IWidget cell : row.getChildren()) {
            if (!(cell instanceof com.cleanroommc.modularui.widgets.TextWidget)) {
                continue;
            }
            com.cleanroommc.modularui.widgets.TextWidget<?> text =
                    (com.cleanroommc.modularui.widgets.TextWidget<?>) cell;
            if (com.cleanroommc.modularui.utils.Alignment.CenterRight == text.getAlignment()) {
                aligned++;
                assertEquals("only the quantity is right-aligned, and this cell is "
                             + cell.getArea().w() + "px", columns.qty, cell.getArea().w());
            } else {
                left++;
            }
        }
        assertEquals("exactly one cell on the row is right-aligned", 1, aligned);
        assertTrue("...and the rest are not, or this asserts nothing", left >= 2);
    }

    /**
     * A row draws one indent rail per level it was actually indented by. #333.
     *
     * WHY THE RAILS EXIST: anchoring the right cluster took depth's second carrier away. Before
     * #333 the whole row slid, so nesting was legible from the ragged left edge of the quantity
     * column; now the indent is white space in front of a sprite, and #332 measured that white
     * space alone does not read as a level.
     *
     * CAPPED, AND THAT IS THE ASSERTION THAT MATTERS. `plan-fluid-chain` runs to depth 20 against
     * `MAX_INDENT_DEPTH` 5, so a rail count taken from the raw depth would draw fifteen rails
     * across the item's own icon on three quarters of that plan's rows. `RAIL`'s javadoc states
     * that constraint; this is it as a test.
     */
    @Test
    public void aRowDrawsOneRailPerLevelOfIndentAndStopsAtTheCap() {
        PlanNode leaf = deepest(PlanFixtures.load("plan-fluid-chain").tree());
        for (int depth = 0; depth <= PlannerWidgets.MAX_INDENT_DEPTH + 4; depth++) {
            ParentWidget<?> row = PlannerWidgets.row(leaf, depth,
                                                     PlannerWidgets.CONTENT_WIDTH, recorder());
            assertTrue("a tree row must be the widget that draws the rails",
                       row instanceof PlannerWidgets.ClickableGroup);
            int rails = ((PlannerWidgets.ClickableGroup) row).railCount();
            assertEquals("depth " + depth + " drew " + rails + " rails",
                         Math.min(depth, PlannerWidgets.MAX_INDENT_DEPTH), rails);
        }
        // The last rail must sit inside the indent it belongs to, clear of the icon column that
        // starts at `MAX_INDENT_DEPTH * INDENT`. `RAIL`'s draw puts it at the level's midpoint.
        assertTrue("a rail at the last level's midpoint must be left of the icon column",
                   (PlannerWidgets.MAX_INDENT_DEPTH - 1) * PlannerWidgets.INDENT
                   + PlannerWidgets.INDENT / 2
                   < PlannerWidgets.MAX_INDENT_DEPTH * PlannerWidgets.INDENT);
    }

    /**
     * A roadblocked machine is marked in the column WITHOUT hovering, sprite or no sprite. #333.
     *
     * <h2>THE CONSTRAINT THIS IS THE TEST FOR, AND WHO PAID FOR IT</h2>
     *
     * `machineWhyBit`'s javadoc established that the machine's name and its STATE are "what a
     * reader scans for", which is why only the `why` prose was ever allowed to be cut. Moving all
     * three into a hover-only tooltip would have deleted a scannable signal in the name of a
     * tidier row -- a net loss of exactly the kind #190 and #252 fixed. So the icon carries the
     * state as a 1px frame and the tooltip carries the detail.
     *
     * THE NO-SPRITE CASE IS THE ONE THAT MATTERS AND IT IS THE ONE UNDER TEST HERE.
     * `NodeActions.NONE` resolves nothing, which is also the state of a real client during the
     * 5.47 s graph load and of the 37% of craft nodes whose category has no catalyst. A mark that
     * needed a picture to hang on would be missing on all of them, and 438 of the fixtures' rows
     * are roadblocks.
     *
     * ONE INK AND NOT ONE PER STATE, which is `NodeStatus`'s rule rather than a shortcut: its
     * header forbids a widget keeping its own notion of a status, and a
     * `buildable`-is-amber / `no route`-is-red mapping written at the widget is precisely the
     * local status map that made adding a status draw silently wrong.
     */
    @Test
    public void aRoadblockedMachineIsMarkedInTheColumnWithoutHovering() {
        PlannerWidgets.Icon blocked = machineCellOf(machineNode("unavailable",
                                                               "no blueprint in reach"));
        assertNotNull("a roadblocked machine must get a cell even with no sprite to draw",
                      blocked);
        assertTrue("...and it must draw nothing but the frame here, or this test is measuring"
                   + " a sprite instead of a mark", blocked.stack().isEmpty());
        assertEquals("the roadblock mark must be NodeStatus's own ink and not a new one",
                     NodeStatus.INK_NEED, blocked.frameColour());

        // A MACHINE YOU HAVE IS NOT A ROADBLOCK, which `isRoadblock`'s javadoc says out loud, so
        // it gets no mark -- and with no sprite either there is nothing to draw and no cell.
        assertNull("a machine you have and cannot picture has nothing to show",
                   machineCellOf(machineNode("have", null)));

        // AND `unknown` COUNTS, which is the part of `isRoadblock` that reads oddly and is
        // deliberate: the tool failing to identify a machine is a real wall, not a clean bill.
        PlannerWidgets.Icon unidentified = machineCellOf(machineNode("unknown", null));
        assertNotNull("an unidentified machine is a wall too", unidentified);
        assertEquals(NodeStatus.INK_NEED, unidentified.frameColour());
    }

    /**
     * The machine cell carries the words the row stopped printing, and `NodeRowText` owns them.
     *
     * A TOOLTIP IS THE ONE SURFACE ON THIS PANEL A HEADLESS TEST CANNOT READ THE TEXT OF --
     * `RichTooltip` is drawn under a GL context -- so the discipline is that the STRINGS live in
     * `NodeRowText`, where `NodeRowTextTest` asserts every one of them with no window, and the
     * widget only has to be shown to have taken them. That split is what this asserts: the cell
     * has a tooltip, and the words it was built from are the ones `machineTooltipLines` produced.
     */
    @Test
    public void theMachineCellCarriesTheWordsTheRowStoppedPrinting() {
        PlanNode node = machineNode("unavailable", "no blueprint in reach");
        PlannerWidgets.Icon cell = machineCellOf(node);
        assertNotNull(cell);
        assertNotNull("the machine cell must carry a tooltip, or the machine's name is gone from"
                      + " the panel entirely", cell.getTooltip());
        // THE WORDS THEMSELVES ARE ASSERTED IN `NodeRowTextTest`, and that this is where they
        // come from is asserted here: three of them for a roadblock, and the row's own text has
        // none of them.
        assertEquals(3, NodeRowText.machineTooltipLines(node).size());
        assertFalse("the machine must have left the row's text: " + NodeRowText.meta(node),
                    NodeRowText.meta(node).contains("Pulverizer"));
    }

    /**
     * The machine cell is the ONLY tooltipped widget in a tree row. #333.
     *
     * <h2>THIS IS A CONTRACT WITH THE SCREENSHOT HARNESS, NOT A TIDINESS CLAIM</h2>
     *
     * `PlannerShot.machineCell` finds the cell to park a cursor on by asking which child carries a
     * tooltip, because `PlannerWidgets.Icon` is package-private and `shot/` cannot name the type.
     * That identification is only sound while "has a tooltip" means "is the machine cell", which
     * is true today because #333's spec put P8 -- tooltips on every truncated label, badge and
     * caveat -- explicitly out of scope.
     *
     * SO WHEN P8 LANDS, THIS FAILS, AND THAT IS THE POINT. Without it, P8 would silently point
     * `planner-hover` and `planner-machine-tip` at a label: both would still produce a green
     * verdict and a plausible PNG, and the machine tooltip would stop being the thing under test.
     * A shot that quietly changes its subject is the failure `PlannerShotTest`'s own header is
     * about. Whoever lands P8 should give the harness a stronger discriminator and then relax
     * this.
     *
     * ASSERTED ON A ROADBLOCKED ROW, so the cell exists whether or not a sprite resolved -- which
     * is the state `NodeActions.NONE` puts every row in here, and the state of a real client for
     * the 5.47 s of the graph load.
     */
    @Test
    public void theOnlyTooltipInATreeRowIsTheMachineCell() {
        PlanNode node = machineNode("unavailable", "no blueprint in reach");
        ParentWidget<?> row = PlannerWidgets.row(node, 0, PlannerWidgets.CONTENT_WIDTH,
                                                 recorder());
        HeadlessLayout.layOutPanel("one-tooltip", PlannerWidgets.PANEL_WIDTH,
                                   PlannerWidgets.PANEL_HEIGHT, row);

        int tooltipped = 0;
        boolean onTheMachineCell = false;
        for (IWidget child : row.getChildren()) {
            if (!(child instanceof com.cleanroommc.modularui.api.widget.ITooltip)) {
                continue;
            }
            if (!((com.cleanroommc.modularui.api.widget.ITooltip<?>) child).hasTooltip()) {
                continue;
            }
            tooltipped++;
            onTheMachineCell = child instanceof PlannerWidgets.Icon;
        }
        assertEquals("the row must carry exactly one tooltip, because `PlannerShot.machineCell`"
                     + " identifies the cell to park a cursor on by that property and cannot name"
                     + " the package-private type", 1, tooltipped);
        assertTrue("...and the one tooltip must be on the machine cell itself", onTheMachineCell);

        // AND THE LABEL MUST NOT HAVE ONE, which is the half that names what would break it: P8
        // puts the full item name on the cell `fit` shortened, and the label is the widest cell in
        // the row, so it is the first thing a park-by-tooltip search would find instead.
        for (IWidget child : row.getChildren()) {
            if (child instanceof com.cleanroommc.modularui.widgets.TextWidget) {
                assertFalse("a text cell grew a tooltip (P8?), which silently re-points the"
                            + " harness's cursor shots at it",
                            ((com.cleanroommc.modularui.api.widget.ITooltip<?>) child)
                                    .hasTooltip());
            }
        }
    }

    /**
     * A hand-crafted step draws no machine cell at all. #333.
     *
     * `NodeRowText.machineName` OWNS THIS DECISION AND THE WIDGET DOES NOT SECOND-GUESS IT.
     * `machineCell` returns null when `machineTooltipLines` is empty, so there is exactly one
     * answer to "is there a machine worth naming" and it is in the class that holds the words.
     * A cell that tested the category itself would be a second answer to the same question, and
     * the old `machineBit` is what happens when the two drift: it had the rule and reached it
     * only when JEI had given the category no title, so 335 rows announced hand crafting anyway.
     */
    @Test
    public void aHandCraftedStepDrawsNoMachineCell() {
        for (String category : new String[] {"minecraft.crafting", "crafting_shaped",
                                             "crafting_shapeless"}) {
            com.google.gson.JsonObject json = new com.google.gson.JsonObject();
            json.addProperty("key", "mod:thing");
            json.addProperty("label", "Thing");
            json.addProperty("need", 1);
            json.addProperty("status", NodeStatus.CRAFT);
            json.addProperty("category", category);
            // THE JEI TITLE, which is the case the old code got wrong. Without it this test
            // passes against the very bug it exists to pin.
            json.addProperty("machine", "Crafting (shapeless)");
            json.addProperty("machine_state", "have");
            assertNull(category + " drew a machine cell", machineCellOf(PlanJson.readNode(json)));
        }
    }

    /**
     * Every fixture's tree lays out with the machine column charged whether or not it is filled.
     *
     * {@link PlannerWidgets#ICON}'s COLUMN RULE, ON THE NEW COLUMN. A width that depended on
     * whether a stack came back would re-flow every row the moment a `NodeActions` was installed
     * or a graph finished loading mid-session, which is a whole panel twitching under the cursor.
     * `NodeActions.NONE` fills none of them here, so this is the empty end of that rule; the
     * filled end is `JeiNodeActionsTest`'s behavioural pair.
     */
    @Test
    public void theMachineColumnIsChargedOnEveryRowWhetherOrNotItIsFilled() {
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            PlannerWidgets.TreeColumns columns =
                    PlannerWidgets.TreeColumns.forTree(plan.tree());
            int labelStart = PlannerWidgets.ICON + PlannerWidgets.GAP;
            int cluster = columns.clusterWidth(PlannerWidgets.CONTENT_WIDTH, labelStart);
            assertTrue(fixture + "'s cluster is " + cluster + "px, which does not include a "
                       + PlannerWidgets.ICON + "px machine column plus a gap",
                       cluster >= PlannerWidgets.ICON + PlannerWidgets.GAP + columns.qty);
        }
    }

    /**
     * The row wash layers hover under selection, and draws neither when neither. #333.
     *
     * <h2>WHY THE DECISION IS A FUNCTION AND NOT A LINE INSIDE `draw`</h2>
     *
     * `isHovering()` reads the screen's cursor and `HeadlessLayout` has no screen, so it is false
     * in every test here -- a hover band decided inline would be a behaviour with no headless
     * witness whatsoever, inside a `draw` body that already cannot be reached. `rowWash` takes
     * the two states as arguments, which leaves exactly one untestable step (that `draw` passes
     * `isHovering()` in) and makes all four combinations assertable. `IconWidgetTest`'s header
     * makes the same argument about `draw` from the other direction.
     *
     * THE LAYERING IS THE ASSERTION THAT MATTERS. A hovered AND selected row must draw the
     * selection, not the hover and not both: a neutral 9% black over a blue 25% wash muds it, and
     * the selection is the stronger claim -- it says something about the ITEM where hover says
     * something about the POINTER.
     */
    @Test
    public void theRowWashLayersHoverUnderSelectionAndNeitherWhenNeither() {
        assertEquals("a row that is neither hovered nor selected must paint nothing at all; a"
                     + " wash on every row is a wall of bands",
                     0, PlannerWidgets.ClickableGroup.rowWash(false, false));
        int hover = PlannerWidgets.ClickableGroup.rowWash(true, false);
        int selected = PlannerWidgets.ClickableGroup.rowWash(false, true);
        assertTrue("a hovered row must paint something, or the band does not exist", hover != 0);
        assertTrue("a selected row must paint something", selected != 0);
        assertNotEquals("the hover band and the selection wash must be different colours, or"
                        + " hover is silently claiming the row is selected", selected, hover);
        assertEquals("a hovered AND selected row draws the SELECTION; painting the hover over it"
                     + " muds a 25% blue with a 9% black, and painting both is the same thing",
                     selected, PlannerWidgets.ClickableGroup.rowWash(true, true));

        // AND THE HOVER BAND IS NEUTRAL, WHICH IS THE CONSTRAINT `HOVER_FILL` ARGUES. A hue would
        // make the panel look as though it were reporting a status about whatever row the pointer
        // happened to rest on. Read off the channels rather than the constant, so this fails if
        // someone tints it rather than if someone renames it.
        int red = (hover >> 16) & 0xFF;
        int green = (hover >> 8) & 0xFF;
        int blue = hover & 0xFF;
        assertEquals("the hover band must be neutral: red and green differ", green, red);
        assertEquals("...and green and blue differ", blue, green);
        assertTrue("...and it must be faint, or it swallows INK_MUTED text",
                   ((hover >>> 24) & 0xFF) < 0x40);
    }

    /**
     * A heading is drawn on a strip and a data row is not. #333, defect six of the design spec.
     *
     * `heading` WAS `line(text, width, INK_CRAFT)` -- blue text, same size, same weight, same
     * height as the data under it, on the same undifferentiated grey field. So the panel's title
     * read as a row, and the view bar one line below it -- also `INK_CRAFT` -- read as a second
     * title. `SHOW_DIAGRAM`'s javadoc records that collision from the other side.
     *
     * ASSERTED AS "HAS A BACKGROUND AND A ROW DOES NOT", which is the only part of this a headless
     * test can see: what the nine-slice looks like is a screenshot's business. The pair is what
     * makes it a statement about hierarchy rather than about one call -- a background added to
     * `line` as well would satisfy half of this and undo all of it.
     */
    @Test
    public void aHeadingIsDrawnOnAStripAndADataRowIsNot() {
        assertNotNull("a heading must carry a background, or it looks like a row",
                      PlannerWidgets.heading("Totals", PlannerWidgets.CONTENT_WIDTH)
                              .getBackground());
        assertNull("...and a data row must NOT, or the whole panel is strips and the hierarchy"
                   + " is back where it started",
                   PlannerWidgets.line("64x Hopper", PlannerWidgets.CONTENT_WIDTH,
                                       NodeStatus.INK_MUTED).getBackground());
    }

    /**
     * The footer is a row of stat cells whose VALUES carry the ink, and it fits. #333.
     *
     * <h2>WHAT WAS WRONG WITH THE SENTENCE IT REPLACES</h2>
     *
     * `52 nodes · 19 still needed · 9 machine(s) to build · 3 on TODO`, all of it `INK_MUTED`.
     * `19 still needed` is the answer to the question the panel exists to answer and `52 nodes`
     * is a fact about the solver's work, drawn identically.
     *
     * <h2>THE WIDTH IS THE HALF THAT CAN FAIL SILENTLY</h2>
     *
     * Two widgets per cell means the second one's x is arithmetic -- `CHAR_WIDTH` per character --
     * and #125 measured that ModularUI neither clamps nor clips a child wider than its parent. So
     * a footer that overran `CONTENT_WIDTH` would simply be drawn off the panel with every layout
     * assertion still green. `NodeRowText.footerWidth` is the same arithmetic the layout uses, so
     * this asserts the sum rather than reading boxes off a panel -- which would only ever confirm
     * that ModularUI put the widgets where it was told.
     *
     * EVERY FIXTURE, because the widest cell set is the one with a five-digit node count and a
     * populated plan book, and no single fixture is the worst case for all four cells at once.
     */
    @Test
    public void theFooterStatRowFitsTheContentWidthOnEveryFixture() {
        // A POPULATED BOOK, because the `on TODO` cell only exists when there is one and it is
        // the widest label of the four. An empty book leaves that branch unexercised.
        PlanBook book = new PlanBook();
        book.setTodo("minecraft:iron_ingot", 64L);
        book.setTodo("fluid:water", 934_400L);
        int widest = 0;
        String worst = null;
        boolean sawInkedValue = false;
        boolean sawUninkedValue = false;
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            List<NodeRowText.FooterCell> cells = NodeRowText.footerCells(plan, book);
            assertFalse(fixture + " produced no footer cells at all; the node count is"
                        + " unconditional", cells.isEmpty());
            assertEquals("the first cell is always the node count", "nodes",
                         cells.get(0).label());
            for (NodeRowText.FooterCell cell : cells) {
                // EACH LABEL'S OWN STATUS, NOT "SOMETHING IS INKED" (#333). This loop first
                // asserted only that SOME cell carried a status and some cell did not, and the
                // deliberate revert of `still needed` to no status went GREEN through it --
                // `machines` and `on TODO` still carried theirs, so both flags stayed satisfied
                // while the one number a player acts on went back to grey. Measured, in
                // `evidence-333/mutants/results.txt`, mutant M9. An assertion about the
                // POPULATION cannot catch a change to one MEMBER of it.
                assertEquals("the footer cell labelled \"" + cell.label() + "\" must carry"
                             + " exactly this status, or its value loses the ink that is the"
                             + " whole point of the stat row",
                             expectedFooterStatus(cell.label()), cell.status());
                if (cell.status().isEmpty()) {
                    sawUninkedValue = true;
                    assertEquals("a cell with no status must fall back to INK_MUTED through"
                                 + " colourFor, not through a status it does not have",
                                 NodeStatus.INK_MUTED, NodeStatus.colourFor(cell.status()));
                } else {
                    sawInkedValue = true;
                    assertNotEquals("a cell WITH a status must be drawn in that status's ink,"
                                    + " or the stat row is a grey sentence again: " + cell,
                                    NodeStatus.INK_MUTED,
                                    NodeStatus.colourFor(cell.status()));
                }
            }
            int width = NodeRowText.footerWidth(cells);
            if (width > widest) {
                widest = width;
                worst = fixture;
            }
        }
        assertTrue("the footer row is " + widest + "px on " + worst + " against a "
                   + PlannerWidgets.CONTENT_WIDTH + "px panel; ModularUI neither clamps nor"
                   + " clips (#125), so the overflow would simply be drawn off the edge",
                   widest <= PlannerWidgets.CONTENT_WIDTH);
        // BOTH KINDS OF CELL MUST HAVE BEEN SEEN, or the loop above asserted one branch and the
        // other is unexercised -- which is how "the values carry the ink" passes on a footer
        // where none of them does.
        assertTrue("no cell carried a status, so nothing was inked", sawInkedValue);
        assertTrue("no cell lacked a status, so the INK_MUTED fallback is unexercised",
                   sawUninkedValue);
    }

    /**
     * The caveat panel separates its notes and reserves the height it spends doing it. #333.
     *
     * <h2>THE RHYTHM IS THE POINT AND THE HEIGHT IS THE THING THAT CAN FAIL SILENTLY</h2>
     *
     * Every note on this panel runs to two or three wrapped lines, so at a uniform 10px rhythm
     * the gap between note 1's last line and note 2's first is the same as the gap inside note 1
     * -- five paragraphs drawn as eleven sentences, which is what `caveats320.png` shows.
     *
     * The half-line between notes is cheap to add and easy to get wrong in the one direction
     * nothing reports: `caveatsPanel` sizes the panel BEFORE it lays the notes out, so a height
     * computed without the gaps draws the last notes past the panel's own bottom edge and every
     * layout assertion stays green, because #125 measured that ModularUI neither clamps nor clips.
     * So this asserts the two agree, and it asserts the gaps EXIST -- a `caveatGaps` that always
     * answered 0 would satisfy the first half perfectly.
     */
    @Test
    public void theCaveatPanelSeparatesItsNotesAndReservesTheHeightForIt() {
        List<String> lines = io.github.jacoblasky.recipedump.client.planner.PlanCaveats
                .detailLines(PlannerWidgets.CONTENT_WIDTH);
        assertTrue("the caveat panel must have notes for this to say anything; if the default"
                   + " scenario stopped producing them, point this at one that does",
                   lines.size() >= 2);

        int gaps = PlannerWidgets.caveatGaps(lines);
        assertTrue("no gap was reserved between any two notes, so the rhythm this test is about"
                   + " does not exist: " + lines, gaps > 0);
        // AND NOT A GAP PER LINE, which is the other way to be wrong: the wrapped continuations
        // of one note must stay tight against it. `wrapRow` marks them, so the count of gaps has
        // to be strictly fewer than the count of lines.
        assertTrue("a gap was reserved on every line, so the continuations of a note are being"
                   + " separated from it too: " + gaps + "px over " + lines.size() + " lines",
                   gaps < (lines.size() - 1) * PlannerWidgets.LINE / 2);

        ModularPanel panel = PlannerWidgets.caveatsPanel();
        HeadlessLayout.layOut(panel);
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
            assertTrue("a caveat line is drawn past the panel's own bottom edge, which is what"
                       + " happens when the height forgets the gaps the layout spends: "
                       + widget.getArea() + " against " + panel.getArea(),
                       widget.getArea().ey() <= panel.getArea().ey());
        }
        assertTrue("and the panel must still fit the smallest screen Minecraft allows",
                   panel.getArea().h() <= 240);
    }

    /**
     * Which `NodeStatus` word each footer label's value must be inked with.
     *
     * HELD HERE RATHER THAN READ OFF `footerCells`, which is the only way it says anything: a
     * table derived from the thing under test agrees with it by construction. This is the
     * argument `NodeRowText.footerCells` makes, written down where a change to it has to come
     * and edit an expectation -- `still needed` is what a `raw` node is, `machines` is the
     * `partial` case (reachable, not reached), the plan book's rows are already `INK_CRAFT` on the
     * totals panel, and `nodes` is deliberately not a status at all because it is a fact about
     * the solver's work rather than a claim about any item.
     *
     * IT FAILS ON AN UNKNOWN LABEL rather than passing it, so a fifth cell added to the footer
     * cannot arrive uninked and unnoticed.
     */
    private static String expectedFooterStatus(String label) {
        if ("nodes".equals(label)) {
            return "";
        }
        if ("still needed".equals(label)) {
            return NodeStatus.RAW;
        }
        if ("machines".equals(label)) {
            return NodeStatus.PARTIAL;
        }
        if ("on TODO".equals(label)) {
            return NodeStatus.CRAFT;
        }
        throw new AssertionError("the footer grew a cell labelled \"" + label + "\" and nothing"
                                + " says which ink its value takes; add it here deliberately");
    }

    /** The machine cell `PlannerWidgets.row` would build for `node`, or null. */
    private static PlannerWidgets.Icon machineCellOf(PlanNode node) {
        ParentWidget<?> row = PlannerWidgets.row(node, 0, PlannerWidgets.CONTENT_WIDTH,
                                                 recorder());
        columnsOf(row);
        PlannerWidgets.Icon found = null;
        for (IWidget child : row.getChildren()) {
            if (child instanceof PlannerWidgets.Icon) {
                assertNull("a row drew two icons; this helper cannot tell the item column from"
                           + " the machine column, and with NodeActions.NONE neither should"
                           + " exist unless it is the machine's frame", found);
                found = (PlannerWidgets.Icon) child;
            }
        }
        return found;
    }

    /** A craft node whose machine is `Pulverizer` in the given state. */
    private static PlanNode machineNode(String state, String why) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "test:thing");
        json.addProperty("label", "Thing");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.CRAFT);
        json.addProperty("category", "thermalexpansion.pulverizer");
        json.addProperty("machine", "Pulverizer");
        json.addProperty("machine_state", state);
        if (why != null) {
            json.addProperty("machine_why", why);
        }
        return PlanJson.readNode(json);
    }

    /** The x of a row's first positioned child, after layout. */
    private static int firstChildX(ParentWidget<?> row) {
        ModularPanel panel = HeadlessLayout.layOutPanel(
                "indent", PlannerWidgets.PANEL_WIDTH, PlannerWidgets.PANEL_HEIGHT, row);
        int leftmost = Integer.MAX_VALUE;
        for (IWidget child : row.getChildren()) {
            leftmost = Math.min(leftmost, child.getArea().x());
        }
        assertTrue("the row drew no children to measure", leftmost < Integer.MAX_VALUE);
        assertTrue(panel.getArea().w() > 0);
        return leftmost;
    }
}
