package io.github.jacoblasky.recipedump.client.machines;

import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.plan.MachineInfo;

/**
 * What each machine state is CALLED on screen, and what colour it is drawn in.
 *
 * TWO OF THE FOUR DISPLAY NAMES ARE NOT THE STATE NAMES, and that is the whole reason this
 * class exists rather than a call to {@link MachineInfo#stateName}. `unknown` is shown as
 * "unidentified" and `unavailable` as "no route", because the wire names are claims this tool
 * cannot make: "unknown" reads as "this machine is a mystery" when what is true is that the
 * TITLE could not be matched to a block, and "unavailable" reads as "you cannot use this" when
 * what is true is that no route to it was found. `MachineInfo`'s own class note is that folding
 * `unknown` into `unavailable` mispriced 40% of the graph; showing them under names that
 * suggest the same fold is the display half of that mistake.
 *
 * THESE WORDS ARE ALSO IN `recipegraph/present.py` AS `STATE_LABEL`, and
 * `tests/test_machines_wording.py` reads this file and fails when the two disagree. A comment
 * asking for parity is a rule with nothing enforcing it, which is this repository's recurring
 * defect -- #181 shipped a mark in Java only and the browser and the client spent a release
 * saying different things about one node. The test FAILS rather than skips when a constant is
 * missing here, because a skip in a parity test reads exactly like a pass.
 */
public final class MachineLabels {

    /**
     * Indexed by the {@link MachineInfo} state constants, so a state has exactly one spelling.
     *
     * AN ARRAY INDEXED BY THE CONSTANT rather than a switch, for the reason `MachineInfo`
     * gives for its own `STATE_NAMES`: the constant order indexes `Cost.MACHINE_COST`, so a
     * renumbering is already a repricing, and an array makes this file break loudly at the
     * same moment rather than quietly relabel.
     */
    private static final String[] LABELS = {"have", "buildable", "unidentified", "no route"};

    /**
     * Parallel to {@link #LABELS}.
     *
     * `unidentified` IS MUTED AND `no route` IS RED, which is the same split
     * `present.STATE_PILL` makes (`mut` against `no`) and it carries the same argument as the
     * labels: an unidentified machine is not a machine the player lacks, so drawing it in the
     * colour this UI uses for "you need this" would state the fold the state model exists to
     * avoid. `buildable` takes the warn colour because it is a real cost -- you must build it
     * first -- and not a blocker.
     */
    private static final int[] COLOURS = {
        NodeStatus.INK_OK,
        NodeStatus.INK_WARN,
        NodeStatus.INK_MUTED,
        NodeStatus.INK_NEED,
    };

    private MachineLabels() {
    }

    public static String label(int state) {
        return LABELS[state];
    }

    public static int colour(int state) {
        return COLOURS[state];
    }

    /**
     * The widest label, in characters.
     *
     * BY ASKING THE ARRAY, not by restating its vocabulary beside it. `PlannerWidgets`'
     * `widestChoiceState` records what the alternative costs: a badge column sized from a
     * hand-written list came out too narrow and the screenshot read "no known...". A fixed
     * vocabulary truncated is always a bug, and a second copy of the vocabulary is how the
     * first one gets a word added without the column noticing.
     */
    public static int widestLabel() {
        int widest = 0;
        for (String label : LABELS) {
            widest = Math.max(widest, label.length());
        }
        return widest;
    }
}
