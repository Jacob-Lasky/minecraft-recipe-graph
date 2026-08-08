package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * The words in a row, asserted as strings.
 *
 * A layout test can tell you a row is eleven pixels tall. Only this can tell you it says
 * "any of 14" rather than "14 recipes" -- two different counts that #118 is entirely about --
 * and only this can tell you a 60-character label was cut rather than wrapped over the row
 * beneath it, which is what the first real screenshot of this panel showed.
 */
public class NodeRowTextTest {

    @Test
    public void aQuantityIsGroupedWithCommasAtEveryThousand() {
        assertEquals("1x", NodeRowText.quantity(1));
        assertEquals("999x", NodeRowText.quantity(999));
        assertEquals("1,000x", NodeRowText.quantity(1000));
        // The real one, from a Borax plan drawing water in mB.
        assertEquals("934,400x", NodeRowText.quantity(934_400L));
        assertEquals("60,466,176x", NodeRowText.quantity(60_466_176L));
    }

    @Test
    public void aQuantityIsNeverAbbreviated() {
        // "934k" would round away the difference between two plans.
        assertFalse(NodeRowText.quantity(934_400L).contains("k"));
        assertTrue(NodeRowText.quantity(1_000_000L).endsWith("x"));
    }

    @Test
    public void textShorterThanItsColumnIsLeftAlone() {
        assertEquals("Iron Ore", NodeRowText.fit("Iron Ore", 240));
    }

    @Test
    public void textLongerThanItsColumnIsCutAndSaysSo() {
        // The failure this prevents is not truncation, it is WRAPPING: a TextWidget given
        // more than it can hold draws a second line over the row beneath, and the sizer
        // reports nothing because every row is still exactly its declared height.
        String long1 = "Nethengeic Mythical Void Resource Minario -- Centrifuge (buildable)";
        String fitted = NodeRowText.fit(long1, 120);
        assertEquals(20, fitted.length());
        assertTrue(fitted.endsWith("..."));
        assertTrue(long1.startsWith(fitted.substring(0, fitted.length() - 3)));
    }

    @Test
    public void aColumnTooNarrowForAnEllipsisStillReturnsSomething() {
        // Reachable at the indent cap on a narrow panel. Returning null or throwing here
        // would take out the whole resize pass, and `resizeInternal` would swallow it.
        assertEquals("Iro", NodeRowText.fit("Iron Ore", 18));
        assertEquals("", NodeRowText.fit("Iron Ore", 0));
        assertEquals("", NodeRowText.fit(null, 240));
    }

    /**
     * {@link NodeRowText#wrap}, including the cases a caller reaches by accident.
     *
     * The one place in this package where wrapping beats cutting -- the "planned without"
     * caveat is a LIST, and a cut list is a shorter, wrong list. Everywhere else `fit` is
     * correct, because a `TextWidget` that wraps itself draws over the row beneath.
     */
    @Test
    public void wrapBreaksOnWordsAndCutsOnlyTheLastLine() {
        int width = 10 * NodeRowText.CHAR_WIDTH;
        // Ten characters takes "alpha beta" and breaks at the space, not mid-word.
        assertEquals(java.util.Arrays.asList("alpha beta", "gamma"),
                     NodeRowText.wrap("alpha beta gamma", width, 2));
        assertEquals("it fits, so it is one line",
                     java.util.Arrays.asList("alpha"), NodeRowText.wrap("alpha", width, 3));
        // Three lines for three words that will not pair up at six characters.
        assertEquals(java.util.Arrays.asList("alpha", "beta", "gamma"),
                     NodeRowText.wrap("alpha beta gamma", 6 * NodeRowText.CHAR_WIDTH, 3));

        // The last permitted line is CUT rather than dropping the tail silently: an ellipsis
        // says there was more, an absence does not.
        List<String> squeezed = NodeRowText.wrap("alpha beta gamma delta", width, 1);
        assertEquals(1, squeezed.size());
        assertTrue(squeezed.get(0), squeezed.get(0).endsWith(NodeRowText.ELLIPSIS));

        // A single word longer than the line has no boundary to break on and must still
        // terminate rather than loop on a zero-length cut.
        assertEquals(java.util.Arrays.asList("abcdefghij", "klmnopqrst"),
                     NodeRowText.wrap("abcdefghijklmnopqrst", width, 2));
    }

    @Test
    public void wrapReturnsNothingRatherThanThrowingOnTheDegenerateCases() {
        assertTrue(NodeRowText.wrap(null, 60, 2).isEmpty());
        assertTrue(NodeRowText.wrap("", 60, 2).isEmpty());
        assertTrue("asking for no lines must give none",
                   NodeRowText.wrap("alpha", 60, 0).isEmpty());
        // A zero-width box: one character a line rather than an infinite loop.
        assertEquals(java.util.Arrays.asList("a", "b"), NodeRowText.wrap("ab", 0, 2));
    }

    @Test
    public void aMissingLabelFallsBackToTheKeyRatherThanBlank() {
        assertEquals("test:thing", NodeRowText.label(node("{\'key\':\'test:thing\',\'label\':\'\'}")));
    }

    @Test
    public void aLeafWithNothingToSayHasNoMeta() {
        PlanNode leaf = PlanFixtures.load("plan-token-gate").tree();
        assertEquals("", NodeRowText.meta(leaf));
    }

    @Test
    public void theMetaPartsComeInTheBrowsersOrder() {
        // A reader moving between the browser and the panel should find the same fact in the
        // same place. This is the fixture that carries several parts at once.
        PlanNode ore = null;
        for (PlanNode candidate : PlanFixtures.load("plan-same-name").flatten()) {
            if ("ore:stickWood".equals(candidate.key())) {
                ore = candidate;
            }
        }
        assertEquals("any of 2 · -> natura:sticks:*", NodeRowText.meta(ore));
    }

    /**
     * `pinned` comes first, so a narrow row cannot cut the one word that says the route was
     * the player's own choice rather than the solver's.
     *
     * Found by a screenshot: a pinned iron ingot in the reference pack rendered as
     * `Iron Ingot -- Crafting -- 172 recip...`, with `pinned` past the cut. See
     * {@link NodeRowText#meta} for why this is the one departure from `render.py`'s order.
     */
    @Test
    public void anArbitraryPickSaysSoINSTEADOfCountingRecipes() {
        // #181, and the substitution is the point rather than an aesthetic choice. On
        // `fluid:lifeessence` this node has 65 real producers and 62 of them are the same
        // offer, so "65 recipes" is the false-comfort number the issue exists to stop
        // showing -- it counts three Blood God Altar routes priced at infinity.
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "fluid:lifeessence");
        json.addProperty("name", "Life Essence");
        json.addProperty("status", NodeStatus.CRAFT);
        json.addProperty("alternatives", 65);
        json.addProperty("interchangeable", 62);
        String meta = NodeRowText.meta(PlanJson.readNode(json));

        assertTrue(meta, meta.contains("any of 62 interchangeable"));
        assertFalse("65 is the number #181 exists to stop showing; both together would put "
                    + "the flattering one first: " + meta, meta.contains("65 recipes"));
    }

    @Test
    public void withNoTieTheRecipeCountIsStillShown() {
        // The other direction, so the substitution above is not simply deleting a feature.
        // A node with alternatives and no interchangeable mark reads exactly as before.
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "mod:thing");
        json.addProperty("name", "Thing");
        json.addProperty("status", NodeStatus.CRAFT);
        json.addProperty("alternatives", 65);
        String meta = NodeRowText.meta(PlanJson.readNode(json));

        assertTrue(meta, meta.contains("65 recipes"));
        assertFalse(meta, meta.contains("interchangeable"));
    }

    @Test
    public void pinnedComesFirstSoTruncationCannotEatIt() {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "mod:plate");
        json.addProperty("label", "Plate");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.CRAFT);
        json.addProperty("pinned", true);
        json.addProperty("alternatives", 172);
        json.addProperty("machine", "Rolling Machine");
        json.addProperty("category", "techreborn.rolling");
        String meta = NodeRowText.meta(PlanJson.readNode(json));

        assertTrue(meta, meta.startsWith("pinned"));
        // The part that matters: still there at a width that loses everything else.
        String cut = NodeRowText.fit(meta, 9 * NodeRowText.CHAR_WIDTH);
        assertTrue(cut, cut.startsWith("pinned"));
        assertTrue("the rest keeps the browser's order: " + meta,
                   meta.indexOf("Rolling Machine") < meta.indexOf("172 recipes"));
    }

    /**
     * THIS TEST'S NAME WAS ASPIRATIONAL UNTIL #190 and now describes what it asserts.
     *
     * It said the row says WHY and checked only that the row says `(buildable)`, which is the
     * STATE -- a fixed word from `NodeStatus`'s vocabulary saying a machine is in the way, not
     * what to do about it. `machine_why` carries that and had no reader anywhere, main or test,
     * so the name was the only thing in the repository claiming the feature existed. A test
     * named for a behaviour it does not check is what stops the next reader noticing, exactly
     * as a javadoc naming a caller that was never written is.
     */
    @Test
    public void aBlockedMachineSaysWhyInTheRowRatherThanOnlyInAPanel() {
        PlanNode root = PlanFixtures.load("plan-truncated").tree();
        String meta = NodeRowText.meta(root);
        assertTrue(meta, meta.contains("Centrifuge (buildable)"));
        assertTrue(meta, meta.contains("3 recipes"));
        // The part the name has always promised: what to do about the roadblock.
        assertTrue("the row says a machine is in the way and not what to do about it: " + meta,
                   meta.contains("craftable: nuclearcraft:centrifuge_idle"));
        // AND NOT RUN TOGETHER WITH THE STATE. `buildable` and `craftable: ...` restate each
        // other, so packing the reason inside the state's parentheses read as a formatting
        // fault: "Centrifuge (buildable: craftable: nuclearcraft:centrifuge_idle)".
        assertFalse(meta, meta.contains("buildable: craftable:"));
    }

    @Test
    public void handCraftingIsNotAnnouncedOnEverySecondRow() {
        // `crafting`-prefixed categories are a crafting table; naming it on every row is noise.
        PlanNode shapeless = null;
        for (PlanNode candidate : PlanFixtures.load("plan-cycle").flatten()) {
            if ("crafting_shapeless".equals(candidate.category())) {
                shapeless = candidate;
                break;
            }
        }
        assertTrue("plan-cycle should contain a shapeless craft", shapeless != null);
        assertFalse(NodeRowText.meta(shapeless).contains("crafting_shapeless"));
    }

    @Test
    public void aPlanThatGaveUpEarlySaysSoEvenWhenItWasNotTruncated() {
        // `exhausted` is a SECOND way to be incomplete -- out of work budget rather than out
        // of nodes -- and it was silent until the review found the accessor had no reader.
        // A short tree with no warning reads as a complete plan.
        assertTrue(NodeRowText.truncationWarning(exhausted()).contains("gave up early"));
    }

    private static PlanView exhausted() {
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.addProperty("key", "test:thing");
        tree.addProperty("label", "Thing");
        tree.addProperty("need", 1);
        tree.addProperty("status", NodeStatus.RAW);
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.add("tree", tree);
        result.addProperty("target", "test:thing");
        result.addProperty("target_name", "Thing");
        result.addProperty("exhausted", true);
        result.addProperty("truncated", false);
        result.addProperty("nodes", 1);
        return PlanJson.readResult(result);
    }

    @Test
    public void theTruncationWarningNamesTheBudgetRatherThanTwoConfusingNumbers() {
        PlanView plan = PlanFixtures.load("plan-truncated");
        String warning = NodeRowText.truncationWarning(plan);
        assertTrue(warning, warning.contains("node budget"));
        // The emitted count can EXCEED the budget, so "cut off at 49 of 40" reads like an
        // arithmetic bug. It said exactly that in the first screenshot.
        assertFalse(warning, warning.contains(" of "));
        assertEquals("", NodeRowText.truncationWarning(PlanFixtures.load("plan-in-stock")));
    }

    /**
     * The exhausted warning quotes the WORK budget, which is why `work` is read at all. #190.
     *
     * IT SAID "search gave up early" WITH NO NUMBERS, and `PlanResult.exhausted`'s javadoc is
     * emphatic about why the numbers matter here: the node count is far below its cap in this
     * case, so quoting the cap is wrong, and the reader is being asked to accept "raise the node
     * budget" on trust. `work` and `work_budget` were parsed into `PlanView` and drawn nowhere,
     * which is the same write-only shape this issue is about, so this test is what stops them
     * going back to having no reader.
     *
     * HAND-BUILT, because no committed fixture is exhausted: `plan-truncated` hits the NODE cap
     * and `plan-fluid-chain` spends 35% of its work budget without exhausting it. A fixture that
     * exhausted would have to be generated, and this asserts wording rather than solver output.
     */
    @Test
    public void theExhaustedWarningQuotesTheWorkBudgetRatherThanTheNodeCap() {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.addProperty("key", "mod:thing");
        tree.addProperty("label", "Thing");
        tree.addProperty("need", 1);
        tree.addProperty("status", NodeStatus.CRAFT);
        result.add("tree", tree);
        result.addProperty("exhausted", true);
        result.addProperty("nodes", 118);
        result.addProperty("max_nodes", 4000);
        result.addProperty("work", 80001);
        result.addProperty("work_budget", 80000);

        String warning = NodeRowText.truncationWarning(PlanJson.readResult(result));
        assertTrue(warning, warning.contains("80,001"));
        assertTrue(warning, warning.contains("80,000"));
        // AND NOT THE NODE CAP, which is the specific mistake `PlanResult.exhausted` warns
        // against: 118 of 4,000 nodes were emitted, so "cut off at the 4,000 node budget" would
        // be a false explanation of a real problem.
        assertFalse("the node cap is the wrong number to quote here: " + warning,
                    warning.contains("4,000"));
        // The lever the player actually has. `workBudget` derives from `maxNodes`, and naming a
        // budget they cannot set would be worse than naming none.
        assertTrue(warning, warning.contains("node budget"));
    }

    /**
     * Two shopping rows with the same name are told apart. #190.
     *
     * `plan-fluid-chain` holds two distinct keys both labelled "Soul Vial", which is the
     * shopping list's own instance of the collision `plan-same-name` was built for -- 5,095
     * display names are shared on this pack. A list drawn as quantity plus label renders them
     * as two rows a player cannot tell apart, and the quantity does not help: it is what they
     * are being asked to gather, not which item it is.
     *
     * AND THE ROWS THAT DO NOT COLLIDE STAY CLEAN, which is the second half of the rule and the
     * reason this takes the whole list. A registry key on all 67 rows would push the name out
     * of the panel to serve four of them.
     *
     * TWO COLLIDING GROUPS SINCE #171, NOT ONE, AND THE SECOND IS THE BETTER EXAMPLE. Pricing
     * the pack's marker items grew this plan's shopping list 63 -> 67 rows, and one of the
     * arrivals collides: `minecraft:iron_ore` and `erebus:ore_iron` are both drawn "Iron Ore".
     * That is two ores in two different worlds sharing a label, where the Soul Vials are two
     * NBT states of one item -- so the rule is now demonstrated on both shapes of collision
     * rather than only the NBT one, which is a strictly better fixture than it was.
     *
     * SEARCHED RATHER THAN INDEXED, because a row may wrap over two lines and the indices then
     * stop lining up. An index-aligned version of this passed while asserting nothing about
     * half the rows, which is worse than failing.
     */
    @Test
    public void aShoppingRowWithADuplicateNameCarriesItsKeyAndTheOthersDoNot() {
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        List<String> lines = NodeRowText.entryLines(plan.shoppingList(), TODO_INNER);
        String all = join(lines);
        int colliding = 0;
        int keyed = 0;
        for (PlanView.EntryRow row : plan.shoppingList()) {
            boolean hasKey = all.contains(row.key());
            if (hasKey) {
                keyed++;
            }
            if ("Soul Vial".equals(row.label()) || "Iron Ore".equals(row.label())) {
                assertTrue("two rows share the label " + row.label()
                           + " and neither says which: " + all, hasKey);
                colliding++;
            }
        }
        assertEquals("the fixture must still hold both colliding pairs", 4, colliding);
        assertEquals("only the colliding rows pay for the key", 4, keyed);
    }

    /**
     * An unsourced shopping row says so, in the same words the tree badge uses.
     *
     * ONE VOCABULARY FOR BOTH SURFACES, which is why this asserts against
     * `NodeStatus.UNSOURCED_BADGE` rather than against a string spelled here: `present.py`'s
     * own docstring records four components each keeping a private dict of bare status words,
     * and adding one drew silently wrong.
     */
    @Test
    public void anUnsourcedShoppingRowSaysSoInTheTreesWords() {
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        String all = join(NodeRowText.entryLines(plan.shoppingList(), TODO_INNER));
        int marked = 0;
        for (PlanView.EntryRow row : plan.shoppingList()) {
            if (row.unsourced()) {
                marked++;
                assertTrue("an unsourced row reads as an ordinary thing to go and fetch: "
                           + row.label(), all.contains(row.label()));
            }
        }
        // THREE SINCE #193, not two. The count is asserted rather than left to the loop above
        // because a loop over zero rows passes every assertion inside it, so this line is what
        // makes the body mean anything. It moved because #193 took 553 keys from infinity to a
        // finite price, so this plan now reaches subtrees it could not before and one more of
        // them bottoms out on a leaf nothing sources. See `PlanJsonTest`'s sibling assertion.
        assertEquals("the fixture must still hold three unsourced rows", 3, marked);
        // COUNTED, so a mark that appears on every row would fail here rather than pass. The
        // badge is only believable while it is rare.
        assertEquals("the mark must appear exactly as often as the flag does",
                     marked, occurrences(all, NodeStatus.UNSOURCED_BADGE));
    }

    /** A fluid quantity is mB and says so, matching `render._rows`. Never buckets. */
    @Test
    public void aFluidRowIsMeasuredInMilliBucketsAndAnItemRowIsNot() {
        assertEquals("30,100 mB", NodeRowText.amount(30100L, "fluid"));
        assertEquals("64x", NodeRowText.amount(64L, "item"));
    }

    /**
     * The machines the footer counts are nameable, with the roadblock spelled out. #190.
     *
     * "3 machine(s) to build" was the whole of what the panel said, and every `MachineRow`
     * accessor except `size()` was called only from `PlanJsonTest`. The values are
     * `plan-in-stock`'s, asserted there too.
     *
     * NAMED CHISELING UNTIL #211/#169. That row was never real: it was on the list only because
     * a DISCARDED attempt entered the category, and nothing in this plan's tree is chiselled.
     * See `Solver.snapshot`. Casting is a machine the plan actually routes through.
     */
    @Test
    public void theMachinesToBuildAreNameableWithTheirReasons() {
        // MOVED OFF `plan-in-stock` AT #246, and the move is the point rather than a rename.
        // That plan used to route through a Tinkers casting table, because a stocked ingot
        // made a Block of Iron cost 1.0 and unpacking one beat smelting ore. With stock
        // overlaid after the relaxation it smelts, so its machine list is now EMPTY and this
        // test would have gone on passing while asserting nothing about a machine row.
        // `plan-machine-choice` is named for having machines and has seven.
        PlanView plan = PlanFixtures.load("plan-machine-choice");
        List<String> lines = NodeRowText.machineLines(plan.machinesToBuild(), TODO_INNER);
        assertTrue("several machines, at least one line each: " + lines, lines.size() >= 2);
        String all = join(lines);
        assertTrue(all, all.contains("Chemical Reactor"));
        assertTrue(all, all.contains("buildable"));
        // THE REASON IS ASSERTED ON THE ROW, NOT ON THE JOINED LINES, and that is a real
        // distinction rather than a dodge. `nuclearcraft:chemical_reactor_idle` is long
        // enough that `wrapRow` splits it at this width, so a `contains` on the rejoined text
        // is really asking "did the wrap happen to fall outside this substring" -- which
        // would pass or fail on the column width rather than on the behaviour. That a long
        // row survives wrapping WHOLE is the next test in this file, which is where the claim
        // belongs; this one is that the reason reaches the renderer at all.
        assertEquals("craftable: nuclearcraft:chemical_reactor_idle",
                     plan.machinesToBuild().get(0).why());
        assertTrue("every line carries its reason marker: " + all, all.contains("craftable:"));
    }

    /**
     * The longest machine row in the fixture set survives whole, over as many lines as it takes.
     *
     * 109 CHARACTERS, WHICH IS 654 PIXELS AND FITS NO SCREEN MINECRAFT RUNS ON. `fit` would cut
     * it, and what it would cut is the registry name of the thing the player has to go and
     * craft: `craftable: modularmachinery:mythical_resource_miner_tier5_controller`. So these
     * wrap, and this asserts both halves of that -- nothing lost, and nothing left over the
     * panel's width for the caller's `fit` to cut after all.
     *
     * THE ROW IT NAMES CHANGED WITH #211/#169 AND THE CLAIM DID NOT. It was the 124-character
     * Mythic Processor Chemical Reactor; that machine left this fixture's list entirely when
     * the loot tables and automation cards stopped being routes and the list went 81 rows to
     * 36. The new longest is still far past the panel, which is all this test needs -- it is
     * about wrapping, not about which machine happens to be worst.
     */
    @Test
    public void theLongestMachineRowSurvivesWholeAcrossLines() {
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        List<String> lines = NodeRowText.machineLines(plan.machinesToBuild(), TODO_INNER);
        String rejoined = join(lines).replace(NodeRowText.CONTINUATION, " ");
        assertTrue("the machine's own registry name was cut: " + lines,
                   rejoined.contains(
                           "modularmachinery:mythical_resource_miner_tier5_controller"));
        assertFalse("a wrapped row must not also be cut", rejoined.contains(NodeRowText.ELLIPSIS));
        for (String line : lines) {
            assertTrue("this line still overflows the panel and `fit` will cut it: " + line,
                       line.length() * NodeRowText.CHAR_WIDTH <= TODO_INNER);
        }
    }

    /** The TODO panel's inner width, which is what its rows are wrapped against. */
    private static final int TODO_INNER =
            PlannerWidgets.TODO_WIDTH - PlannerWidgets.PADDING * 2;

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * A roadblocked machine's reason rides in the node's meta run. #190.
     *
     * `machine_why` was parsed into `PlanNode` and read by NOTHING, main or test, until #190.
     * The state label alone says a machine is in the way; the `why` says what to do about it,
     * which is the difference `ScenarioSource.Status.unavailable` argues about at length.
     */
    @Test
    public void aRoadblockedMachineSaysWhyInTheMetaRun() {
        String meta = NodeRowText.meta(machineNode("unavailable", "no blueprint in reach"));
        assertTrue(meta, meta.contains("no blueprint in reach"));
        // And a machine the player HAS gets no reason, because `solve.py` writes none.
        String owned = NodeRowText.meta(machineNode("have", null));
        assertFalse(owned, owned.contains("no blueprint in reach"));
    }

    @Test
    public void aCraftNodeSaysHowManyRunsAndWhatEachOneYields() {
        // #190 found `runs` and `per_run` parsed and drawn by nothing, main or test. This is
        // the assertion that they reach a row at all.
        String meta = NodeRowText.meta(yieldNode(2L, Double.valueOf(4.0), null));
        assertTrue(meta, meta.contains("2 runs"));
        assertTrue(meta, meta.contains("4 per run"));
    }

    @Test
    public void aWholePerRunIsGroupedLikeTheQuantityBesideIt() {
        // One panel must not measure the same thing two ways. A #190 screenshot caught exactly
        // this on fluids, `934,400x` above `934400 mB`, which no test saw because both halves
        // were individually correct and only the adjacency was wrong.
        String meta = NodeRowText.meta(yieldNode(1L, Double.valueOf(60_466_176.0), null));
        assertTrue(meta, meta.contains("60,466,176 per run"));
        assertFalse("a whole yield must not render as a decimal: " + meta,
                    meta.contains("60466176.0"));
    }

    @Test
    public void aFractionalYieldSaysHowOftenItPaysOut() {
        // The #223 case this exists for: 834 of the pack's 835 output chances are fractional.
        String meta = NodeRowText.meta(
                yieldNode(1000L, Double.valueOf(0.004), Double.valueOf(0.001)));
        assertTrue(meta, meta.contains("1,000 runs"));
        assertTrue(meta, meta.contains("yields 0.1% of the time"));
        // NOT `0.0%`, which is what a fixed one-decimal format renders and which reads as
        // "never" for a route that does work.
        assertFalse("a rare yield must not round to zero: " + meta, meta.contains("0.0%"));
    }

    @Test
    public void aOneForOneCraftSaysNothingAboutItsYield() {
        // FOUND BY LOOKING AT THE SHOT, not by a test. "1 run, 1 per run" is the default and
        // it is 28.4% of committed craft nodes, 400 of 1,406, so on a real panel it was most
        // rows, and `fit` was cutting the machine name off the end to make room for it.
        String meta = NodeRowText.meta(yieldNode(1L, Double.valueOf(1.0), null));
        assertFalse("a one-for-one craft must not spend row width saying so: " + meta,
                    meta.contains("run"));
    }

    @Test
    public void aBatchYieldIsWorthSayingEvenOnASingleRun() {
        // The boundary of the rule above: one run that makes 64 is NOT the default and is the
        // difference between needing one craft and needing sixty-four.
        String meta = NodeRowText.meta(yieldNode(1L, Double.valueOf(64.0), null));
        assertTrue(meta, meta.contains("1 run"));
        assertTrue(meta, meta.contains("64 per run"));
        // AND IT IS SINGULAR. This is the only surviving row shape with a run count of one,
        // since the one-for-one case is now suppressed, so the plural rule is asserted here
        // rather than in a test of its own.
        assertFalse("a single run must not read as `1 runs`: " + meta, meta.contains("1 runs"));
    }

    @Test
    public void aRareYieldIsSaidEvenWhenTheRunCountIsOne() {
        // The other boundary: a single run that pays out one time in a thousand is the whole
        // point of #223 and must survive the suppression rule.
        String meta = NodeRowText.meta(
                yieldNode(1L, Double.valueOf(1.0), Double.valueOf(0.001)));
        assertTrue(meta, meta.contains("yields 0.1% of the time"));
    }

    @Test
    public void aFractionalPerRunIsDrawnAtThePrecisionItCarries() {
        // The companion to the percentage: `per_run` is the expected yield after #223 and is
        // itself fractional, so the amount must not round to a whole number and imply the
        // machine produces one each time.
        String meta = NodeRowText.meta(
                yieldNode(1000L, Double.valueOf(0.004), Double.valueOf(0.001)));
        assertTrue(meta, meta.contains("0.004 per run"));
    }

    @Test
    public void aNodeWithRunsButNoRecordedYieldSaysOnlyTheRuns() {
        // `solve.py` writes `or 1` rather than emit a zero yield, so an absent `per_run` means
        // "not recorded" and not "yields nothing". Saying "0 per run" would be the second
        // claim, which is the exact shape of the defect #252 is about.
        String meta = NodeRowText.meta(yieldNode(7L, null, null));
        assertTrue(meta, meta.contains("7 runs"));
        assertFalse("an absent yield must not be drawn as zero: " + meta,
                    meta.contains("per run"));
    }

    @Test
    public void aFullYieldSaysNothingAboutChance() {
        // `yield_chance` is written only when the expectation falls short, so a row without it
        // is already saying "this yields all of it". Repeating that on every craft row would
        // make the mark unbelievable on the rows that carry it.
        String meta = NodeRowText.meta(yieldNode(3L, Double.valueOf(1.0), null));
        assertFalse(meta, meta.contains("yields"));
        assertFalse(meta, meta.contains("%"));
    }

    @Test
    public void aLeafSaysNothingAboutRuns() {
        // A raw node has no recipe, so "0 runs" would be a claim about work that is not done.
        assertFalse(NodeRowText.meta(node("raw")).contains("run"));
    }

    @Test
    public void theYieldComesBeforeTheMachineReasonSoACutTakesTheReasonFirst() {
        // WHY IT IS EARLY IN THE RUN. `machineWhyBit` is last so `fit` drops it first, because
        // its full text lives on the TODO panel's machine section. The yield has no second
        // home and it is the part that says the plan may not be worth starting, so a cut must
        // reach the reason before it reaches the yield.
        //
        // BUILT WITH BOTH PARTS PRESENT, and asserted on their ORDER rather than on their
        // presence. An earlier version of this test allowed the machine to be absent, which
        // made it pass on a node that could not have failed it.
        PlanNode node = yieldNode(1000L, Double.valueOf(0.004), Double.valueOf(0.001),
                                  "buildable", "craftable: mod:pulverizer");
        String meta = NodeRowText.meta(node);
        int yieldAt = meta.indexOf("yields");
        int whyAt = meta.indexOf("craftable: mod:pulverizer");
        assertTrue("the yield is missing from: " + meta, yieldAt >= 0);
        assertTrue("the machine reason is missing from: " + meta, whyAt >= 0);
        assertTrue("the yield must precede the machine reason: " + meta, yieldAt < whyAt);
    }

    private static PlanNode yieldNode(long runs, Double perRun, Double yieldChance) {
        return yieldNode(runs, perRun, yieldChance, null, null);
    }

    private static PlanNode yieldNode(long runs, Double perRun, Double yieldChance,
                                      String machineState, String machineWhy) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "test:thing");
        json.addProperty("label", "Thing");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.CRAFT);
        json.addProperty("recipe", "r:1");
        json.addProperty("runs", Long.valueOf(runs));
        if (perRun != null) {
            json.addProperty("per_run", perRun);
        }
        if (yieldChance != null) {
            json.addProperty("yield_chance", yieldChance);
        }
        if (machineState != null) {
            json.addProperty("category", "thermalexpansion.pulverizer");
            json.addProperty("machine", "Pulverizer");
            json.addProperty("machine_state", machineState);
            json.addProperty("machine_why", machineWhy);
        }
        return PlanJson.readNode(json);
    }

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

    private static PlanNode node(String ignored) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "test:thing");
        json.addProperty("label", "");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.RAW);
        return PlanJson.readNode(json);
    }
}
