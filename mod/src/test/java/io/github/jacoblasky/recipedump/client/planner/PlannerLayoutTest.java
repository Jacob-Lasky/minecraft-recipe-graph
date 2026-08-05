package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Pins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    private static PlanBook emptyBook() {
        return new PlanBook();
    }

    private static ModularPanel laidOut(String fixture) {
        ModularPanel panel =
                PlannerWidgets.plannerPanel(PlanFixtures.load(fixture), emptyBook(), recorder());
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
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        ModularPanel panel = laidOut("plan-fluid-chain");
        ListWidget<?, ?> tree = findList(panel);
        assertEquals(576, tree.getChildren().size());
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
        ModularPanel todo = PlannerWidgets.todoPanel(PlanFixtures.load("plan-in-stock"), book);
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
        List<String> lines = new java.util.ArrayList<String>();
        for (IWidget widget : HeadlessLayout.flatten(panel)) {
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
            ModularPanel todo = PlannerWidgets.todoPanel(plan, emptyBook());
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
        ModularPanel panel = PlannerWidgets.todoPanel(PlanView.empty(), book);
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
        ModularPanel panel = PlannerWidgets.plannerPanel(PlanView.empty(), emptyBook(), recorder());
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
                PlannerWidgets.plannerPanel(plan, book, recorder()),
                PlannerWidgets.todoPanel(plan, book),
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

        ModularPanel panel = PlannerWidgets.todoPanel(plan, emptyBook());
        HeadlessLayout.layOut(panel);
        ListWidget<?, ?> list = findList(panel);
        int rows = list.getChildren().size();
        int inner = PlannerWidgets.TODO_WIDTH - PlannerWidgets.PADDING * 2;
        // Recomposed rather than hardcoded, so this stays an identity between the two layers
        // instead of a number that has to be edited whenever a section's wording changes.
        int expected = 1 + 1 // "nothing on the list", "still needed for this plan:"
                + 1          // "nothing outstanding": plan-in-stock has an empty shopping list
                + 1 + NodeRowText.machineLines(plan.machinesToBuild(), inner).size()
                + 1 + NodeRowText.entryLines(plan.usedFromStock(), inner).size();
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
        ModularPanel panel = PlannerWidgets.todoPanel(plan, emptyBook());
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
                    PlannerWidgets.planNodeContent(node, width, PlannerWidgets.ROW_HEIGHT);
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
                    PlannerWidgets.planNodeContent(node, size[0], size[1]);
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
        ModularPanel empty = PlannerWidgets.todoPanel(plan, emptyBook());
        ModularPanel full = PlannerWidgets.todoPanel(plan, book);
        HeadlessLayout.layOut(empty);
        HeadlessLayout.layOut(full);
        assertTrue(full.getArea().h() > empty.getArea().h());
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
     * A diagram node opens the node menu, the same way a tree row does.
     *
     * MY OWN COMMENT ON `planNodeContent` SAID THIS WAS IMPOSSIBLE -- "a canvas of clickable
     * rows would fight" the viewport's hit-testing -- and it was an assumption I had written
     * as though it were a measured constraint. s1harness measured it in #185 with a real
     * cursor: `IWidget.isHovering()` agrees with the layout at zoom 0.5, 1.0 and 2.0, so
     * `getWidgetsAt` routes correctly through both the scroll offset and the zoom matrix.
     *
     * This is the headless half of that. It cannot prove hit-testing through a transform --
     * only a real cursor can, which is what `flow-hit` is for -- but it does prove the widget
     * is an `Interactable` wired to the actions it was handed, which is the part that was
     * absent while the comment stood.
     */
    @Test
    public void aDiagramNodeOpensTheNodeMenuLikeATreeRowDoes() {
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
     * The three-argument overload is INERT, and says so rather than defaulting quietly.
     *
     * The failure it enables is a canvas of nodes that look right and do nothing, which no
     * layout assertion can catch because a layout assertion is what the overload is for.
     */
    @Test
    public void theThreeArgumentNodeContentIsInertAndNotSilentlyWiredToSomething() {
        PlanNode node = PlanFixtures.load("plan-in-stock").tree();
        ParentWidget<?> box = PlannerWidgets.planNodeContent(node, 214, 26);
        HeadlessLayout.layOut(wrap(box));
        List<PlannerWidgets.ClickableGroup> clickable = clickables(box);
        assertEquals(1, clickable.size());
        // It clicks, and nothing happens. `PlannerActions.NONE` rather than a null check at
        // the call site -- see that constant for why.
        clickable.get(0).onMousePressed(0);
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
                PlannerWidgets.plannerPanel(plan, emptyBook(), PlannerActions.NONE);
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
                PlannerWidgets.plannerPanel(plan, emptyBook(), PlannerActions.NONE);
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
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), recorder);
        HeadlessLayout.layOut(panel);

        List<PlannerWidgets.ClickableGroup> rows = clickables(panel);
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
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), recorder);
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
        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), recorder());
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
        assertEquals("64x " + named.label(), lines.get(0).text);
        assertFalse("the key must not appear in the words: " + lines.get(0).text,
                    lines.get(0).text.contains(named.key()));
        // AND THE KEY IS STILL REACHABLE, on the row, because that is what the icon lookup
        // needs. Showing a name and losing the identity would be the same defect mirrored.
        assertEquals(named.key(), lines.get(0).key);
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
        assertEquals("3x thaumadditions:vis_pod#0116bb2287a7", lines.get(0).text);
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
        ParentWidget<?> box = PlannerWidgets.planNodeContent(node, width, 26);
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
                PlannerWidgets.planNodeContent(node, 209, PlannerWidgets.ROW_HEIGHT);
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
        PlannerWidgets.NodeContent box = PlannerWidgets.planNodeContent(node, 209, 26);
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

        ModularPanel panel = PlannerWidgets.plannerPanel(plan, emptyBook(), recorder());
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
        PlannerWidgets.NodeContent box = PlannerWidgets.planNodeContent(node, 209, 26);
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

        List<IWidget> marked = columnsOf(PlannerWidgets.planNodeContent(token, 209, 26));
        List<IWidget> plain = columnsOf(PlannerWidgets.planNodeContent(item, 209, 26));
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
}
