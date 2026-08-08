package io.github.jacoblasky.recipedump.client.machines;

import io.github.jacoblasky.recipedump.common.ScenarioSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Why the verdicts on this screen may be wrong, in the reader's terms.
 *
 * A DIFFERENT SUBSET FROM THE PLANNER'S CAVEAT, AND NARROWER ON PURPOSE. `PlanCaveats` names
 * every scenario input a PLAN consumes, which is seven of them; a machine verdict is decided
 * by four. Showing the planner's line here would name `emc_knowledge` and `visited_dimensions`
 * as reasons this table might be wrong, and they are not -- `Machines.resolve` never reads
 * either. Over-claiming a caveat is not the safe direction it looks like: a reader who checks
 * one of the named inputs, finds it irrelevant, and concludes the warning is boilerplate will
 * skip the two entries that are real.
 *
 * AND THE OMISSION IS THE MORE DANGEROUS HALF. `ScenarioSource.PLACED`'s own note is already a
 * sentence about this screen -- "placed machines and generators are not scanned yet, so every
 * machine reads as buildable rather than owned" -- so a machines table drawn with `placed`
 * unread is a table whose `have` column is empty for a reason that has nothing to do with what
 * the player owns. Saying nothing would make the tool's biggest blind spot invisible on the
 * one screen it is about.
 */
public final class MachineCaveats {

    /**
     * The scenario inputs a machine verdict is actually built from.
     *
     * DERIVED FROM `Machines.resolve`'S SIGNATURE, not from taste: it takes an `Evidence`
     * carrying placed tile entities, stock and hand-set overrides, plus the `no_machine` list.
     * Nothing else reaches it. If that ever takes a fifth input, this array is the thing that
     * has to grow with it -- and `MachineCaveatsTest` asserts the four are spelled the way
     * `ScenarioSource` spells them, so a rename breaks here rather than silently shortening
     * the warning.
     */
    private static final ScenarioSource[] FEEDS_A_VERDICT = {
        ScenarioSource.HAVE,
        ScenarioSource.PLACED,
        ScenarioSource.MACHINE_OVERRIDES,
        ScenarioSource.NO_MACHINE,
    };

    private MachineCaveats() {
    }

    /**
     * The unread inputs among {@link #FEEDS_A_VERDICT}, by field name.
     *
     * EMPTY WHEN EVERY ONE IS LIVE, which is what the panel treats as "reserve no caveat
     * line". Two of the four (`machine_overrides`, `no_machine`) report live-with-nothing
     * rather than unavailable, because there is no UI to set one and "the player has set
     * none" is the truth rather than a gap -- so in practice this line is about `have` and
     * `placed`, and it says so by naming them rather than by counting.
     */
    public static List<String> missing() {
        List<String> out = new ArrayList<String>();
        for (ScenarioSource source : FEEDS_A_VERDICT) {
            if (!source.live()) {
                out.add(source.field());
            }
        }
        return out;
    }

    /** The one-line warning, or "" when every input a verdict uses was read. */
    public static String summaryLine() {
        List<String> missing = missing();
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("verdicts computed without: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(missing.get(i));
        }
        return out.toString();
    }
}
