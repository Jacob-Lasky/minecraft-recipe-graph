package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The selection holder, and the split that makes it two things rather than one.
 *
 * The assertions worth reading are {@link #everyOccurrenceOfTheItemHighlights} and
 * {@link #actingUsesTheOccurrenceThatWasClickedAndNotTheFirstOne}. They are the two halves of
 * one decision, and a holder that stored only a key would pass the first and fail the second
 * by handing back a plausible wrong quantity -- with no error anywhere.
 */
public class PlanSelectionTest {

    @Before
    @After
    public void clearTheHolder() {
        // A STATIC HOLDER IS SHARED BY EVERY TEST IN THIS JVM, and a leftover selection makes
        // an unrelated layout assertion draw a highlight it never set up.
        PlanSelection.clear();
    }

    @Test
    public void nothingIsSelectedUntilSomethingIs() {
        assertEquals("", PlanSelection.selectedKey());
        assertNull(PlanSelection.selectedNode());
        assertFalse(PlanSelection.isSelected("minecraft:iron_ingot"));
        // The empty key must not match an empty candidate either, or a node whose key failed
        // to parse would read as permanently selected.
        assertFalse("\"\" is not a selection", PlanSelection.isSelected(""));
    }

    /**
     * Highlighting is by KEY, so every place the item is used lights up.
     *
     * That is the whole value of it on a diagram: the question a reader has is "where else
     * does this go", and a highlight on the one box they clicked answers a question nobody
     * asked.
     */
    @Test
    public void theFixtureItselfCanExposeTheBug() {
        // ASSERTED, NOT ASSUMED. Both tests below are vacuous against a plan whose keys occur
        // once each -- they pass for a key-only holder too. This is the guard that says the
        // subject matter is still capable of failing, so a regenerated fixture cannot quietly
        // turn the two assertions underneath it into decoration.
        List<PlanNode> ingots = occurrencesOf("minecraft:iron_ingot");
        assertTrue(FIXTURE + " must use iron ingot more than once, saw " + ingots.size(),
                   ingots.size() > 1);
        assertTrue(FIXTURE + " must want DIFFERENT amounts at two occurrences",
                   differentNeedFrom(ingots, ingots.get(0)) != null);
    }

    @Test
    public void everyOccurrenceOfTheItemHighlights() {
        List<PlanNode> ingots = occurrencesOf("minecraft:iron_ingot");
        assertTrue("the fixture must use iron ingot more than once: " + ingots.size(),
                   ingots.size() > 1);

        PlanSelection.select(ingots.get(0));
        for (PlanNode occurrence : ingots) {
            assertTrue("every occurrence of the selected key must light up",
                       PlanSelection.isSelected(occurrence.key()));
        }
        assertFalse(PlanSelection.isSelected("minecraft:hopper"));
    }

    /**
     * Acting uses the node that was CLICKED, because `need` differs per occurrence.
     *
     * THIS IS THE ONE THAT BITES. `openNodeMenu`, `addToTodo` and the recipe picker all read
     * `node.need()`. A holder that kept only the key would have to find the node again, the
     * search would return the first occurrence, and "Add to TODO" would add 4 where the player
     * meant 18 -- a plausible number, no error, and nothing in the result that says which
     * occurrence answered.
     */
    @Test
    public void actingUsesTheOccurrenceThatWasClickedAndNotTheFirstOne() {
        List<PlanNode> ingots = occurrencesOf("minecraft:iron_ingot");
        PlanNode first = ingots.get(0);
        PlanNode other = differentNeedFrom(ingots, first);
        assertTrue("the fixture must have two occurrences wanting different amounts",
                   other != null);

        PlanSelection.select(other);
        assertSame("the node handed back must be the one selected", other,
                   PlanSelection.selectedNode());
        assertNotEquals("and so the quantity an action reads is the clicked one",
                        first.need(), PlanSelection.selectedNode().need());
    }

    @Test
    public void selectingSomethingElseReplacesTheSelectionRatherThanAddingToIt() {
        PlanNode hopper = PlanFixtures.load(FIXTURE).tree();
        PlanNode child = hopper.children().get(0);
        PlanSelection.select(hopper);
        PlanSelection.select(child);
        assertFalse(PlanSelection.isSelected(hopper.key()));
        assertTrue(PlanSelection.isSelected(child.key()));
    }

    @Test
    public void clearingAndSelectingNullBothMeanNothingIsSelected() {
        PlanSelection.select(PlanFixtures.load(FIXTURE).tree());
        PlanSelection.select(null);
        assertNull(PlanSelection.selectedNode());
        assertEquals("", PlanSelection.selectedKey());

        PlanSelection.select(PlanFixtures.load(FIXTURE).tree());
        PlanSelection.clear();
        assertNull(PlanSelection.selectedNode());
    }

    /**
     * Selection matches on VALUE, because plan keys are not interned strings.
     *
     * `PlanJson.readNode` pulls keys out of gson, which does not `String.intern()`, so a
     * reference compare would never match and the symptom would be highlighting that simply
     * does not happen -- no exception, nothing to grep for. `PlannerShotTest`'s
     * "interned" means interned into `RecipeGraph`'s key-id table, which is a different claim.
     */
    @Test
    public void anEqualKeyFromSomewhereElseStillCountsAsSelected() {
        PlanNode node = PlanFixtures.load(FIXTURE).tree();
        PlanSelection.select(node);
        String rebuilt = new String(node.key().toCharArray());
        assertNotEquals("the fixture for this test must not be the same object",
                        System.identityHashCode(node.key()),
                        System.identityHashCode(rebuilt));
        assertTrue("a value-equal key must match", PlanSelection.isSelected(rebuilt));
    }

    /**
     * A fixture whose root's key appears more than once AT DIFFERENT QUANTITIES.
     *
     * NOT `plan-in-stock`, WHICH WAS MY FIRST CHOICE AND WAS IMMUNE. Every key in it occurs at
     * most once at a given need, so a holder that stored only the key and re-found the node
     * would have passed both assertions below -- the first occurrence and the clicked one are
     * the same object when there is only one. I picked the fixture I had used in every other
     * test in this package rather than the fixture that could fail, which is s1harness's #182
     * finding in miniature: a case chosen for a reason unrelated to the bug.
     *
     * `plan-same-name` has `minecraft:iron_ingot` twice, wanting 2 and 1. Asserted rather than
     * assumed, below, so a regenerated fixture cannot quietly make this test immune again.
     */
    private static final String FIXTURE = "plan-same-name";

    private static List<PlanNode> occurrencesOf(String key) {
        List<PlanNode> found = new java.util.ArrayList<PlanNode>();
        for (PlanNode node : PlanFixtures.load(FIXTURE).flatten()) {
            if (key.equals(node.key())) {
                found.add(node);
            }
        }
        return found;
    }

    /** The first occurrence wanting a different amount from `from`, or null. */
    private static PlanNode differentNeedFrom(List<PlanNode> nodes, PlanNode from) {
        for (PlanNode node : nodes) {
            if (node.need() != from.need()) {
                return node;
            }
        }
        return null;
    }
}
