package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.MachinesScreen;
import io.github.jacoblasky.recipedump.client.machines.LiveMachinesActions;
import io.github.jacoblasky.recipedump.client.machines.MachinesEntry;
import io.github.jacoblasky.recipedump.client.machines.MachinesWidgets;
import io.github.jacoblasky.recipedump.client.planner.PlannerState;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.MachinesService;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.MachineTable;

/**
 * The machines table and its two sub-panels, for `-Dmcrecipedump.shot=machines[...]`.
 *
 * THIS ONE NEEDS A REAL GRAPH AND HAS NO FIXTURE ALTERNATIVE, which is the difference between
 * it and {@link PlannerShot}. That shot reads a frozen plan off disk because a plan tree is a
 * small document; a machines table is a verdict on every one of the pack's 503 categories,
 * resolved from placed blocks and stock against a 121 MB graph, and there is nothing to freeze
 * that would still be the subject under test. `tests/fixtures/plan/*.json` holds plans, not
 * pack-wide machine resolutions, and inventing a six-category fixture would photograph a
 * screen no player will ever see -- the columns are sized from the data, so a toy table would
 * not even have the right geometry.
 *
 * WITHOUT A GRAPH IT SHOOTS THE NOT-YET PANEL AND SUCCEEDS, per {@link LivePlanShot}'s rule.
 * That is every CI run and anyone who has not built an oracle, and the picture is worth
 * having: "no graph.json, looked in ..." is what a new player sees. It does NOT fake a table
 * to fill the frame, because a screenshot of invented data is the one artifact that can make a
 * broken screen look finished.
 *
 * SO A REVIEWER READING A PR WITH THESE PICTURES SHOULD CHECK WHICH ONE THEY GOT. A run with
 * `RECIPEGRAPH_ORACLE` set produces the table; a run without produces the failure panel, and
 * the two are easy to tell apart and easy to mistake for each other in a thumbnail. The log
 * line below names which happened.
 */
final class MachinesShot {

    private static final long MACHINES_WAIT_MILLIS = 180_000L;

    private MachinesShot() {
    }

    /**
     * `machines`, or `machines:<state>` to open with a chip already switched on.
     *
     * THE FILTER ARGUMENT EXISTS SO THE INTERESTING PICTURE CAN BE TAKEN. An unfiltered table
     * opens on `have`, which is the least informative seven rows in it -- the screen's whole
     * purpose is `no route` and `buildable`, and those are hundreds of rows down a list a
     * static screenshot cannot scroll.
     */
    static void open(String arg) {
        MachineTable table = resolveOrExplain();
        if (table == null) {
            return;
        }
        LiveMachinesActions actions = new LiveMachinesActions();
        applyFilter(actions, table, arg);
        MachinesScreen.openTable(table, actions);
    }

    /** `machines-mods`: the mod picker, over whatever the filter argument selected. */
    static void openModPicker(String arg) {
        MachineTable table = resolveOrExplain();
        if (table == null) {
            return;
        }
        LiveMachinesActions actions = new LiveMachinesActions();
        applyFilter(actions, table, arg);
        MachinesScreen.openPanel(
                MachinesWidgets.modPicker(table, actions.filterFor(table), actions));
    }

    /**
     * `machines-detail`, or `machines-detail:<category uid>`.
     *
     * DEFAULTS TO THE BUSIEST CATEGORY THE TABLE HAS rather than to a named one. A hardcoded
     * uid would be a uid that stops existing when the pack changes, and the shot would then
     * photograph an empty panel while still exiting zero -- the failure mode this whole
     * harness exists to avoid.
     */
    static void openDetail(String arg) {
        MachineTable table = resolveOrExplain();
        if (table == null) {
            return;
        }
        MachineTable.Row row = rowFor(table, arg);
        if (row == null) {
            ShotHarness.log("machines-detail: no category matched '" + arg + "'");
            MachinesScreen.openPanel(MachinesWidgets.statePanel(
                    PlannerState.failed("no category matched '" + arg + "'")));
            return;
        }
        ShotHarness.log("machines-detail: " + row.uid() + " (" + row.candidates().size()
                + " candidates)");
        MachinesScreen.openPanel(
                MachinesWidgets.detailPanel(row, new LiveMachinesActions()));
    }

    /** The named category, or the busiest one. */
    private static MachineTable.Row rowFor(MachineTable table, String arg) {
        if (arg != null && !arg.trim().isEmpty()) {
            for (MachineTable.Row row : table.allRows()) {
                if (row.uid().equals(arg.trim())) {
                    return row;
                }
            }
            return null;
        }
        MachineTable.Row best = null;
        for (MachineTable.Row row : table.allRows()) {
            if (best == null || row.recipes() > best.recipes()) {
                best = row;
            }
        }
        return best;
    }

    /**
     * Switch on the chip named by `arg`, if it names one.
     *
     * A BAD NAME IS LOUD RATHER THAN IGNORED, which is `PlannerShot.openFlow`'s rule for its
     * zoom argument: a silently dropped filter produces a screenshot of the unfiltered table
     * that looks entirely correct, and the whole point of the shot is to show it is not.
     */
    private static void applyFilter(LiveMachinesActions actions, MachineTable table,
                                    String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return;
        }
        String wanted = arg.trim();
        int state = MachineInfo.stateOf(wanted);
        if (state < 0) {
            throw new IllegalArgumentException("bad machine state '" + wanted
                    + "'; expected one of have, buildable, unknown, unavailable");
        }
        actions.filterFor(table);
        actions.toggleState(state);
    }

    /**
     * Build the table, waiting for both the graph and the resolve. Null when there is none.
     *
     * IT BLOCKS THE CLIENT TICK WHILE IT WAITS, which is {@link LivePlanShot}'s argument
     * verbatim and it holds for the same reasons: the harness is one-shot and headless, there
     * is nobody to freeze, and the alternative -- opening on an unfinished resolve and hoping
     * the settle frames covered it -- is a screenshot that races a background thread. Both
     * waits are BOUNDED and a screen opens whatever happens.
     */
    private static MachineTable resolve() {
        if (!LivePlanShot.awaitGraph()) {
            ShotHarness.log("machines: " + GraphService.get().describe());
            return null;
        }
        final MachinesService machines = MachinesService.get();
        machines.ensure();
        // THROUGH `ShotWaits` AND NOT A LOOP HERE. This was a third hand-rolled bounded poll
        // until #254's review, and `LivePlanShot.awaitGraph`'s own comment already said why
        // there must not be a second: each copy carries a deadline, an interval, an interrupt
        // policy and a log line, and the copies had already drifted on two of the four.
        if (!ShotWaits.until("the machines table", MACHINES_WAIT_MILLIS,
                new ShotWaits.Busy() {
                    @Override
                    public boolean busy() {
                        return machines.state() == MachinesService.State.BUILDING
                                || machines.state() == MachinesService.State.IDLE;
                    }
                })) {
            return null;
        }
        ShotHarness.log("machines: " + machines.describe());
        return machines.table();
    }

    /**
     * The table, or null after opening the not-yet panel that explains why there is none.
     *
     * THE THREE ENTRY POINTS ALL NEED THIS AND ALL THREE HAD IT INLINE, which is three places
     * for the failure path to get a different answer -- and the failure path is the one that
     * runs on every CI machine, because none of them has the oracle.
     *
     * IT SAYS WHICH OF THE TWO PICTURES IT TOOK, AND THAT LINE IS THE POINT (#240). Both
     * outcomes are legitimate and both exit 0: this file's own README paragraph says the
     * not-yet panel is "the picture a new player sees and is worth having", so failing the run
     * on it would delete a deliberate capability. But `prodshot.sh: OK in 927s` reads
     * identically either way, and the README already warns the two are "easy to tell apart
     * full size and easy to confuse in a thumbnail". A reviewer holding a PNG and a log should
     * not have to squint at the image to learn which one they were sent.
     *
     * DO NOT reduce this to the `machines: <describe>` lines above it. Those say what the
     * SERVICES think; this says what the CAMERA got, and the whole failure being closed is one
     * where the services were fine and the picture was of the empty state.
     */
    private static MachineTable resolveOrExplain() {
        MachineTable table = resolve();
        if (table == null) {
            // ONCE, not once per use: `stateFor` reads two services and a second call can
            // legitimately answer differently, which would put one reason on the screen and a
            // different one in the log -- the exact confusion this line exists to remove.
            PlannerState why = state();
            MachinesScreen.openPanel(MachinesWidgets.statePanel(why));
            ShotHarness.log("machines: CAPTURED THE NOT-YET PANEL, not the table -- "
                    + why.message() + ". Stage a graph, or set RECIPEGRAPH_GRAPH to a path,"
                    + " if the table was what you wanted.");
            return null;
        }
        ShotHarness.log("machines: CAPTURED THE TABLE, " + table.allRows().size()
                + " categories");
        return table;
    }

    /** The not-yet panel to draw when {@link #resolve} came back with nothing. */
    private static PlannerState state() {
        PlannerState state = MachinesEntry.stateFor(GraphService.get(), MachinesService.get());
        // NEVER NULL HERE BY CONSTRUCTION -- `resolve` only returns null when the graph or the
        // resolve failed, both of which `stateFor` answers for. Guarded anyway, because a null
        // handed to `statePanel` would NPE inside a panel build, which `WidgetTree` swallows
        // and reports as a screen at 0x0.
        return state == null ? PlannerState.failed("no machines table") : state;
    }
}
