package io.github.jacoblasky.recipedump.client.browse;

/**
 * Moving between the three browse screens.
 *
 * ITS OWN INTERFACE RATHER THAN A METHOD ON `MachinesActions`, and the split is the same one
 * `PlannerActions` and `NodeActions` make: these have different owners. Navigation is a
 * property of the strip and is identical on all three screens; `MachinesActions` is about a
 * chip, a mod and a category row, and two of the three screens have none of those. Folding
 * them together would put four machine-shaped methods in front of a screen that lists free
 * items, and would make `SourcesWidgets` unbuildable without stubbing them.
 *
 * One object may implement both, and {@code LiveBrowseActions} does -- one interface per
 * concern is about what the WIDGETS are handed, not about how many objects exist at runtime.
 */
public interface BrowseActions {

    /**
     * Open `tab`. Implementations ignore a request for the tab already showing.
     *
     * THE NO-OP IS THE IMPLEMENTATION'S JOB AND NOT THE WIDGET'S, so the strip stays a pure
     * function of `current` and every tab is a live click target. A strip that omitted the
     * handler for the current tab would be a strip whose geometry changed with the state,
     * which is the sort of thing a layout test then has to special-case.
     */
    void openTab(BrowseTabs.Tab tab);

    /**
     * Does nothing but record nothing. For the screenshot harness, which has no screen to open
     * another on, and for the layout tests, which assert geometry rather than behaviour.
     *
     * NOT A "NULL OBJECT SO THE TESTS PASS", per `PlannerActions.NONE`: the alternative is a
     * null check inside widget construction, which is one chance to forget it and get an NPE
     * inside a resize pass that swallows it and leaves the whole tree at 0x0.
     */
    BrowseActions NONE = new BrowseActions() {
        @Override
        public void openTab(BrowseTabs.Tab tab) {
        }
    };
}
