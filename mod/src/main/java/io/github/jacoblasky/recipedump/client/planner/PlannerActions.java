package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * What the planner's widgets can DO when clicked.
 *
 * SPLIT FROM {@link NodeActions} ON PURPOSE, because the two have different owners and
 * different lifetimes. `NodeActions` is the Phase 4 seam -- the things that need JEI and an
 * ItemStack, which this phase deliberately does not implement. This interface is the things
 * the planner can already do today: open its own sub-panels, and edit the plan book that #140
 * built. Folding them together would make the whole menu unimplementable until Phase 4 lands.
 *
 * An interface rather than direct calls because opening a sub-panel needs the parent panel,
 * which only exists once a screen has been built -- and because it makes every entry in the
 * menu assertable by a test that counts what was invoked, with no window anywhere.
 */
public interface PlannerActions {

    /** The Phase 4 seam. Never null; {@link NodeActions#NONE} when nothing is installed. */
    NodeActions nodeActions();

    /** Clicking a tree row. */
    void openNodeMenu(PlanNode node);

    /** "Choose another recipe". */
    void openRecipePicker(PlanNode node);

    /** "Add to TODO" -- asks the SERVER to add it, per #140's one-writer rule. */
    void addToTodo(PlanNode node);

    /** "Favourite" -- likewise a request, not a local edit. */
    void toggleFavourite(PlanNode node);

    /**
     * Does nothing but record nothing. For the screenshot harness, which has no screen to
     * open a sub-panel on, and for layout tests, which assert geometry rather than behaviour.
     *
     * NOT A "NULL OBJECT SO THE TESTS PASS": the alternative is a null check at four call
     * sites inside widget construction, which would be four chances to forget one and get an
     * NPE inside a resize pass that swallows it.
     */
    PlannerActions NONE = new PlannerActions() {
        @Override
        public NodeActions nodeActions() {
            return NodeActions.NONE;
        }

        @Override
        public void openNodeMenu(PlanNode node) {
        }

        @Override
        public void openRecipePicker(PlanNode node) {
        }

        @Override
        public void addToTodo(PlanNode node) {
        }

        @Override
        public void toggleFavourite(PlanNode node) {
        }
    };
}
