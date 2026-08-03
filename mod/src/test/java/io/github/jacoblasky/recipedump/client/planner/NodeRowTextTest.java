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

    @Test
    public void aBlockedMachineSaysWhyInTheRowRatherThanOnlyInAPanel() {
        PlanNode root = PlanFixtures.load("plan-truncated").tree();
        String meta = NodeRowText.meta(root);
        assertTrue(meta, meta.contains("Centrifuge (buildable)"));
        assertTrue(meta, meta.contains("3 recipes"));
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

    private static PlanNode node(String ignored) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "test:thing");
        json.addProperty("label", "");
        json.addProperty("need", 1);
        json.addProperty("status", NodeStatus.RAW);
        return PlanJson.readNode(json);
    }
}
