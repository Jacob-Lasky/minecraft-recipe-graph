package io.github.jacoblasky.recipedump.client.machines;

import io.github.jacoblasky.recipedump.plan.MachineTable;

/**
 * What the machines screen can DO when clicked.
 *
 * THE SAME SHAPE AS `PlannerActions` AND FOR THE SAME TWO REASONS, which are worth restating
 * because they are what makes this screen testable at all: opening a sub-panel needs the
 * parent panel, which does not exist until the screen has been built, and an interface makes
 * every click assertable by a test that counts what was invoked with no window anywhere.
 *
 * SEPARATE FROM `PlannerActions` RATHER THAN ADDED TO IT. That interface is about a node in a
 * plan -- every method takes a `PlanNode` -- and this one is about a category in the pack.
 * Folding them together would put six plan-shaped methods in front of a screen that has no
 * plan, and would make the machines screen wait on the JEI seam that `PlannerActions.nodeActions`
 * exposes. The two screens genuinely do different things.
 */
public interface MachinesActions {

    /**
     * A state chip was clicked. Implementations reconcile and redraw.
     *
     * THE FILTER IS NOT PASSED BACK IN. The widgets are built from a filter and never own one;
     * whoever holds the current filter applies the toggle and rebuilds, which is what keeps
     * `MachinesWidgets` a pure function of its arguments. A widget that mutated a filter would
     * be a widget with state, and the layout tests would then depend on click order.
     */
    void toggleState(int state);

    /** "every mod" was clicked: offer the list of mods. */
    void openModPicker();

    /** @param mod the chosen mod, or null for "every mod". */
    void chooseMod(String mod);

    /** A table row was clicked: show what is known about that category. */
    void openDetail(MachineTable.Row row);

    /**
     * Plan one of a category's candidate machines.
     *
     * TAKES A KEY AND NOT A ROW, WHICH IS THE POINT. A category has several candidates
     * (`MachineInfo.candidates`: "Smelting is done in more than just the controller"), so
     * "plan this machine" is ambiguous at the row and unambiguous beside a named candidate.
     * That is also what keeps this clear of #251: the target is one item key the reader
     * picked, never an aggregate the screen chose for them.
     */
    void planMachine(String key);

    /**
     * Does nothing but record nothing. For the screenshot harness, which has no screen to open
     * a sub-panel on, and for the layout tests, which assert geometry rather than behaviour.
     *
     * NOT A "NULL OBJECT SO THE TESTS PASS", per `PlannerActions.NONE`: the alternative is a
     * null check at every call site inside widget construction, which is one chance per site
     * to forget one and get an NPE inside a resize pass that swallows it and leaves the whole
     * tree at 0x0.
     */
    MachinesActions NONE = new MachinesActions() {
        @Override
        public void toggleState(int state) {
        }

        @Override
        public void openModPicker() {
        }

        @Override
        public void chooseMod(String mod) {
        }

        @Override
        public void openDetail(MachineTable.Row row) {
        }

        @Override
        public void planMachine(String key) {
        }
    };
}
