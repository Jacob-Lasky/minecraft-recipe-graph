package io.github.jacoblasky.recipedump.client.machines;

import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.Evidence;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.MachineTable;
import io.github.jacoblasky.recipedump.plan.Machines;

/**
 * Machine tables for the layout tests, built through the real resolver.
 *
 * THE SAME ARGUMENT AS `MachineTableTest`'s fixture: the states are forced with
 * `Evidence.override`, which is an input the production path reads, rather than by
 * constructing `MachineInfo` directly -- so a resolve that stopped producing these states
 * fails here instead of leaving the geometry assertions green over data nothing makes.
 *
 * THE NAMES ARE DELIBERATELY LONG. `PlannerWidgets`' rule 2 is that ModularUI neither clamps
 * nor clips a child wider than its parent, so the only way a column overflow is ever caught is
 * by handing the widgets text that would overflow. A fixture of tidy eight-character names
 * would lay out perfectly and prove nothing.
 */
final class MachineTables {

    private MachineTables() {
    }

    /** The longest category title in the reference pack is comfortably shorter than this. */
    private static final String LONG_TITLE =
            "Mythic Processor: Melter and Crystalliser Assembly Controller Mk III";

    private static void category(GraphBuilder b, String uid, String mod, String title,
                                 int recipes) {
        b.categoryMod(uid, mod);
        for (int i = 0; i < recipes; i++) {
            b.beginRecipe();
            b.beginSlot(1, "item");
            b.alternative(b.key("mod:leaf"));
            b.endSlot();
            b.output(b.key("mod:out_" + uid.replace('.', '_') + "_" + i), 1);
            b.endRecipe(uid + ".r" + i, uid, title, "test", false, false);
        }
    }

    /**
     * Twenty-eight categories over four mods, every state represented, with one row carrying a
     * title far wider than the name column and one carrying a five-figure recipe count.
     */
    static MachineTable wide() {
        GraphBuilder b = new GraphBuilder();
        Evidence evidence = new Evidence();
        String[] mods = {"Modular Machinery", "NuclearCraft", "Thermal Expansion",
                         "aether_legacy"};
        int[] states = {MachineInfo.HAVE, MachineInfo.BUILDABLE, MachineInfo.UNKNOWN,
                        MachineInfo.UNAVAILABLE};
        for (int i = 0; i < 28; i++) {
            String uid = "mod" + (i % 4) + ".category_number_" + i;
            // ONE ROW WITH A RUNAWAY TITLE AND ONE WITH A HUGE COUNT, because both are column
            // widths derived from the data and a fixture where every row is average-sized
            // would exercise neither cap.
            String title = i == 3 ? LONG_TITLE : "Machine " + i;
            int recipes = i == 7 ? 14410 : (i % 9) + 1;
            // MOD CYCLES FAST AND STATE CYCLES SLOWLY, so every mod ends up holding several
            // states. Cycling both on `i % 4` gives each mod exactly one state, which makes
            // the cross-tab diagonal -- and a diagonal cross-tab collapses half the filter
            // combinations in `MachinesLayoutTest.filters` to the same two-row result. The
            // fixture would then lay out cleanly while exercising almost none of the widths
            // it exists to stress.
            category(b, uid, mods[i % 4], title, recipes);
            evidence.override(uid, states[(i / 4) % 4]);
        }
        RecipeGraph graph = b.build();
        return MachineTable.of(graph, Machines.resolve(graph, evidence));
    }

    /**
     * A single category with several candidate blocks, for the detail panel.
     *
     * SEVERAL, because `MachineInfo.candidates` exists precisely so more than one can be shown
     * (#27) and a one-candidate fixture would lay the panel out without ever stacking two rows.
     */
    static MachineTable withCandidates() {
        GraphBuilder b = new GraphBuilder();
        category(b, "mod.press", "Modular Machinery", "Industrial Press", 12);
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press_controller_mark_three_extended"));
        b.catalystKey(b.key("mod:press_b"));
        b.catalystKey(b.key("mod:press_c"));
        b.endCatalyst();
        RecipeGraph graph = b.build();
        return MachineTable.of(graph, Machines.resolve(graph, new Evidence()));
    }

    /** The one row {@link #withCandidates} describes. */
    static MachineTable.Row pressRow() {
        for (MachineTable.Row row : withCandidates().allRows()) {
            if (row.uid().equals("mod.press")) {
                return row;
            }
        }
        throw new AssertionError("the press fixture stopped describing mod.press");
    }
}
