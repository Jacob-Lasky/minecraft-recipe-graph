package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * What the planner's widgets can DO when clicked.
 *
 * SPLIT FROM {@link NodeActions} ON PURPOSE, AND DO NOT FOLD THEM BACK TOGETHER, because the
 * two have different owners and different lifetimes. `NodeActions` is the JEI seam -- the
 * things that need JEI and an ItemStack, implemented in `client.jei` (#157) and installed at
 * `onRuntimeAvailable`, so that a client without JEI never loads any of it. This interface is
 * the things the planner does on its own, from the moment a panel is built: open its
 * sub-panels, and edit the plan book that #140 built. One interface for both would pull
 * `mezz.jei` across the seam and make every entry in the menu wait on a JEI runtime.
 *
 * An interface rather than direct calls because opening a sub-panel needs the parent panel,
 * which only exists once a screen has been built -- and because it makes every entry in the
 * menu assertable by a test that counts what was invoked, with no window anywhere.
 */
public interface PlannerActions {

    /** The JEI seam. Never null; {@link NodeActions#NONE} when nothing is installed. */
    NodeActions nodeActions();

    /** Clicking a tree row. Implementations select the node too; see {@link #selectNode}. */
    void openNodeMenu(PlanNode node);

    /**
     * Make `node` the selection, without opening anything.
     *
     * SEPARATE FROM {@link #openNodeMenu} EVEN THOUGH THAT ONE ALSO SELECTS, because the two
     * questions come apart on the diagram: `client.flow` wants to highlight every occurrence
     * of an item as the reader moves over the canvas, and it must be able to do that without
     * a panel appearing. Folding selection into the menu would make "show me where else this
     * goes" cost a window.
     *
     * ON THIS INTERFACE RATHER THAN A DIRECT `PlanSelection.select` CALL, so a click is
     * assertable by a test that counts what was invoked -- the same reason every other entry
     * here is an interface method -- and so the flow package keeps its one dependency on this
     * one pointing the same way as the rest.
     */
    void selectNode(PlanNode node);

    /** "Choose another recipe". */
    void openRecipePicker(PlanNode node);

    /**
     * Take `choice` for this node's key, or give up the pin when `choice` is already pinned.
     *
     * A TOGGLE, UNLIKE {@link #toggleFavourite}, WHICH ONLY ADDS -- and the asymmetry is
     * evidence, not taste. That method cannot toggle because the panel is built from a plan
     * and has no copy of the book to consult, so "remove" would be a guess that deletes
     * something. The picker is built from `RecipeChoices`, which read the pins to mark the
     * rows: {@link RecipeChoice#pinned} is the stored state rather than an inference from it,
     * so a click on a pinned row can safely mean "stop pinning this".
     *
     * Takes the whole {@link RecipeChoice} rather than a recipe id because the choice carries
     * the fingerprint that was computed against the graph the picker was opened on. See that
     * class for the reload window an id would leave open.
     *
     * A PIN IS NOT AN EDIT TO THE PLAN. It changes an INPUT and the plan has to be solved
     * again; there is no way to patch one subtree, because a different recipe takes different
     * ingredients and the whole cost of the branch moves. Implementations re-solve.
     */
    void pinRecipe(PlanNode node, RecipeChoice choice);

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
     *
     * DO NOT MAKE {@link #nodeActions} RETURN {@link NodeActionsHolder#actions()}. It looks
     * like the obvious improvement -- the harness would then photograph the real menu -- and
     * it would put GLOBAL, MUTABLE state underneath every layout test in this package: a test
     * that installed a `NodeActions` would silently change the geometry another test asserts,
     * because an installed one adds an icon widget and two menu entries. The harness gets the
     * real holder through its own `PlannerShot.SHOT_ACTIONS`, which is where that coupling
     * belongs, and this one stays inert so the geometry tests stay independent.
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
        public void selectNode(PlanNode node) {
        }

        @Override
        public void openRecipePicker(PlanNode node) {
        }

        @Override
        public void pinRecipe(PlanNode node, RecipeChoice choice) {
        }

        @Override
        public void addToTodo(PlanNode node) {
        }

        @Override
        public void toggleFavourite(PlanNode node) {
        }
    };
}
