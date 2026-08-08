package io.github.jacoblasky.recipedump.client.browse;

import com.cleanroommc.modularui.api.widget.Interactable;

/**
 * Real navigation between the three browse screens.
 *
 * A SEAM RATHER THAN A DIRECT `ClientGUI.open`, and the reason is the one `PlannerActions`
 * gives for every entry on it: opening a window from inside a widget's click handler needs a
 * screen, which the layout tests and the screenshot harness do not have, and a call that
 * cannot be stubbed is a click that cannot be asserted. `BrowseTabsTest` counts what was
 * invoked with no window anywhere.
 *
 * THE OPENER IS INJECTED because `client.browse` must not depend on `client.MachinesScreen`:
 * that class is one of the three destinations, and a package that reaches back up to a screen
 * in order to draw a tab is a cycle waiting to be a class-loading problem on a client without
 * ModularUI. The screen layer knows how to open screens; this only knows which one was asked
 * for.
 */
public final class LiveBrowseActions implements BrowseActions {

    /** Opens one browse screen. Implemented in the screen layer. */
    public interface Opener {
        void open(BrowseTabs.Tab tab);
    }

    private final Opener opener;
    private final BrowseTabs.Tab current;

    /**
     * @param current the tab already showing, which this refuses to reopen
     * @param opener  how to reach the other two
     */
    public LiveBrowseActions(BrowseTabs.Tab current, Opener opener) {
        this.current = current;
        this.opener = opener;
    }

    /**
     * Open `tab`, unless it is the one already showing.
     *
     * THE NO-OP LIVES HERE AND NOT IN THE STRIP, so the strip stays a pure function of
     * `current` and every tab keeps the same geometry whichever one is lit. Reopening the
     * current screen would throw away its scroll position and, on the machines tab, the
     * filter -- for a click that visibly did nothing.
     */
    @Override
    public void openTab(BrowseTabs.Tab tab) {
        if (tab == current) {
            return;
        }
        // The click sound lives here rather than in the widget: it reaches the sound handler
        // and therefore LWJGL, and a widget that made a noise could not be clicked in a
        // headless test.
        Interactable.playButtonClickSound();
        opener.open(tab);
    }
}
