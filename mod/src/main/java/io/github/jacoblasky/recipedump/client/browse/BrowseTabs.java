package io.github.jacoblasky.recipedump.client.browse;

import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;

/**
 * The tab strip the three browse screens share, and the list of what there is to browse.
 *
 * WHY THIS EXISTS NOW AND NOT IN #254. That change said Sources and Coverage would not each
 * take their own modifier on the calculator item -- three windows behind three chords is
 * undiscoverable -- and that the shared strip gets built when the second screen arrives. It
 * has. Shift-right-click still opens {@link Tab#MACHINES}; this is how a player reaches the
 * other two, and it is also the thing that makes the machines screen itself discoverable,
 * because until now nothing on screen said there was anywhere else to go.
 *
 * AN ENUM AND NOT A LIST OF PANELS, so adding a fourth screen is one constant and one `case`
 * rather than an edit in each of three files. `BrowseTabsTest` asserts every constant has a
 * label and that the strip fits, so a fourth cannot be added without the width being checked.
 *
 * THE CURRENT TAB IS INERT, which is the browser's rule (`_nav` renders the section's own page
 * as a span rather than a link, with `aria-current='page'`). A tab that reopened the screen you
 * are already on would throw away scroll position and any filter for no reason, and it reads as
 * a control that did nothing.
 */
public final class BrowseTabs {

    /**
     * The screens reachable from the strip, in the order they are drawn.
     *
     * MACHINES FIRST BECAUSE IT IS THE WAY IN. Shift-right-click opens it, so it is the tab a
     * player is on when they first see the strip, and a strip whose first entry is the one you
     * are looking at is the one that reads as a position rather than a menu.
     */
    public enum Tab {
        MACHINES("Machines"),
        SOURCES("Free"),
        GRAPH("Graph");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        /**
         * What the tab says.
         *
         * `Free` AND NOT `Sources`, WHICH IS A DELIBERATE DEPARTURE FROM THE BROWSER'S WORD.
         * The web tab says "Sources" beside "Machines" and "Coverage", where there is room for
         * the page's own heading to explain that a source means an INFINITE source. On a strip
         * three words wide with no room for a subtitle, "Sources" reads as "where recipes came
         * from" -- which is a real and different thing this tool also has, and which lives on
         * the Graph tab as the by-source tally. Naming the tab after what it tells you costs
         * nothing and removes the collision.
         *
         * `Graph` AND NOT `Coverage` for the same reason and a stronger one: the screen behind
         * it answers "which graph am I planning against", not "how complete is it". See
         * {@code plan.GraphFacts} for why that reframing is the whole justification for the
         * screen existing in game at all.
         */
        public String label() {
            return label;
        }
    }

    /** Width of one tab. Four characters of padding either side of the longest label. */
    public static final int TAB_WIDTH = widestLabel() * NodeRowText.CHAR_WIDTH
            + PlannerWidgets.GAP * 2;

    private BrowseTabs() {
    }

    /**
     * The widest label, in characters, INCLUDING the brackets the current tab wears.
     *
     * BY ASKING THE ENUM, not by restating its vocabulary. `MachineLabels.widestLabel` records
     * what a hand-written width cost last time: a badge column sized from a list came out too
     * narrow and the screenshot read "no known...". The `+ 2` is the brackets, and leaving them
     * out would make the CURRENT tab -- the one always on screen -- the one that gets cut.
     */
    static int widestLabel() {
        int widest = 0;
        for (Tab tab : Tab.values()) {
            widest = Math.max(widest, tab.label().length());
        }
        return widest + 2;
    }

    /**
     * The strip, as a row of clickable tabs.
     *
     * SELECTED IS BRACKETED AND COLOURED, NOT COLOURED ALONE, which is the convention #254 set
     * for the state chips and the argument carries over unchanged: a second, redundant channel
     * costs two characters and survives a greyscale screenshot, a red-green deficiency, and the
     * fact that this UI already spends colour on meaning elsewhere.
     */
    public static PlannerWidgets.Group strip(Tab current, final BrowseActions actions,
                                             int width) {
        PlannerWidgets.Group group = new PlannerWidgets.Group();
        group.size(width, PlannerWidgets.ROW_HEIGHT);
        int x = 0;
        for (final Tab tab : Tab.values()) {
            boolean here = tab == current;
            PlannerWidgets.ClickableGroup cell =
                    new PlannerWidgets.ClickableGroup(new Runnable() {
                        @Override
                        public void run() {
                            actions.openTab(tab);
                        }
                    });
            cell.size(TAB_WIDTH, PlannerWidgets.ROW_HEIGHT);
            cell.child(PlannerWidgets.line(label(tab, here), TAB_WIDTH,
                                           here ? NodeStatus.INK_CRAFT : NodeStatus.INK_MUTED)
                               .pos(0, 0));
            group.child(cell.pos(x, 0));
            x += TAB_WIDTH + PlannerWidgets.GAP;
        }
        return group;
    }

    static String label(Tab tab, boolean current) {
        return current ? "[" + tab.label() + "]" : tab.label();
    }
}
