package io.github.jacoblasky.recipedump.client.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import org.junit.Test;

/**
 * The key-to-name lookup the TODO panel needed and did not have.
 *
 * The assertion worth reading is {@link #aKeyThePlanNeverMentionedComesBackAsItself}: the
 * fallback is what stops this being a source of blank rows, and it is the case a plan-derived
 * lookup will genuinely hit -- a TODO row added against one target and still on the list while
 * the player plans another.
 */
public class PlanNamesTest {

    /** Vanilla-rooted and 21 nodes, with `minecraft:iron_ingot` at two occurrences. */
    private static final String FIXTURE = "plan-same-name";

    @Test
    public void everyKeyInTheTreeIsNamed() {
        PlanView plan = PlanFixtures.load(FIXTURE);
        PlanNames names = PlanNames.of(plan);
        assertTrue("the fixture must name something or this proves nothing", names.size() > 1);
        for (PlanNode node : plan.flatten()) {
            if (node.label() == null || node.label().isEmpty()) {
                continue;
            }
            assertTrue("the tree named " + node.key() + " and the lookup did not",
                       names.knows(node.key()));
            assertEquals(node.label(), names.labelFor(node.key()));
        }
    }

    /**
     * The summary lists are a SECOND source and not a subset of the first.
     *
     * They carry different keys: a list holds what is still outstanding, or already owned, or
     * drawn from a source, so a key that was resolved rather than expanded is in a list and not in
     * the tree, and a truncated plan's lists can name a key whose subtree was cut. Reading only the
     * tree would leave the "still needed" half of the TODO panel unnamed, which is the half the
     * player is shopping from.
     */
    @Test
    public void theShoppingListIsReadAsWellAsTheTree() {
        PlanView plan = withShoppingList();
        assertFalse("this fixture must have a shopping list", plan.shoppingList().isEmpty());
        PlanNames names = PlanNames.of(plan);
        for (PlanView.EntryRow row : plan.shoppingList()) {
            assertTrue("the shopping list named " + row.key() + " and the lookup did not",
                       names.knows(row.key()));
            assertEquals(row.label(), names.labelFor(row.key()));
        }
    }

    @Test
    public void aKeyThePlanNeverMentionedComesBackAsItself() {
        PlanNames names = PlanNames.of(PlanFixtures.load(FIXTURE));
        // The exact row the TODO panel used to print at players, and the reason it is worth an
        // assertion rather than a comment: the fallback must be the KEY and not "" or "unknown".
        // A blank row hides the fact that something is on the list; a key at least says which.
        String orphan = "thaumadditions:vis_pod#0116bb2287a7";
        assertFalse(names.knows(orphan));
        assertEquals(orphan, names.labelFor(orphan));
    }

    @Test
    public void nothingAtAllIsAnAnswerRatherThanACrash() {
        // `PlanView.empty()` is a real state -- every planner open renders the book against it
        // while the solve runs -- so a lookup built from it has to work rather than be guarded
        // against at the call site.
        assertEquals("minecraft:iron_ingot",
                     PlanNames.of(PlanView.empty()).labelFor("minecraft:iron_ingot"));
        assertEquals("minecraft:iron_ingot", PlanNames.none().labelFor("minecraft:iron_ingot"));
        assertEquals("minecraft:iron_ingot", PlanNames.of(null).labelFor("minecraft:iron_ingot"));
        assertEquals("", PlanNames.none().labelFor(null));
        assertFalse(PlanNames.none().knows(null));
    }

    /** The first fixture with something outstanding, so the test names one rather than two. */
    private static PlanView withShoppingList() {
        for (String fixture : PlanFixtures.names()) {
            PlanView plan = PlanFixtures.load(fixture);
            if (!plan.shoppingList().isEmpty()) {
                return plan;
            }
        }
        throw new AssertionError("no fixture has a shopping list; this test cannot mean "
                                + "anything and should not be quietly passing");
    }
}
