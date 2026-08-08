package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the planner treats as costing nothing, and the evidence for each.
 *
 * WHY THIS EARNS A SCREEN IN GAME, which is a question worth answering rather than assuming.
 * An infinite source is a block that emits a resource from no inputs -- a water source, a
 * cobblestone generator -- and because it has no recipe the graph cannot find it by searching.
 * It is a LIST, and the list is what stops a plan rebuilding water out of 71 snowballs. So the
 * first time a player reads `934,400 mB Water -- placed: nuclearcraft:water_source` on a plan
 * and wonders why something enormous is free, this is the screen that answers it, and the
 * answer is per-pack rather than per-plan.
 *
 * IT IS ALSO THE HALF OF `sources_page` WORTH HAVING. That page is mostly a WRITE surface:
 * add a generator by hand, switch one off, toggle the vanilla-water assumption. Those are
 * edits to `source_overrides`, which is a persistence path the mod does not have -- the same
 * one #254 deliberately left out for machine overrides, and for the same reason: a file format
 * and a one-writer question are their own piece of work rather than a corner of a read-only
 * table. What lands here is the reading; the writing is stated as absent rather than quietly
 * dropped, and the screen says so instead of offering buttons that do nothing.
 *
 * IN `plan/` for the reason {@link MachineTable} is: it computes and does not draw, which is
 * what puts it in the job `tools/ci-java.sh` runs on every pull request.
 */
public final class SourceTable {

    /** One thing the planner will not charge for. */
    public static final class Row {

        private final String key;
        private final String name;
        private final String why;

        Row(String key, String name, String why) {
            this.key = key;
            this.name = name;
            this.why = why;
        }

        /** The registry key, e.g. `fluid:water`. Never null. */
        public String key() {
            return key;
        }

        /**
         * The display name, falling back to the key.
         *
         * THE FALLBACK IS HERE rather than in the widget, so the sort key and the drawn label
         * cannot disagree -- the same rule {@link MachineTable.Row#name} follows. A fluid or an
         * oredict entry routinely has no recorded name, and those are exactly the rows this
         * screen exists to explain.
         */
        public String name() {
            return name;
        }

        /** Why it is free: `placed: nuclearcraft:water_source`, or a curated default. */
        public String why() {
            return why;
        }
    }

    private final List<Row> rows;

    private SourceTable(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
    }

    /**
     * Build the table from a resolved scenario's free sources.
     *
     * SORTED BY KEY, which is what `sources_page` does (`sorted(state.free_sources.items())`)
     * and it is the right choice for a list a reader SCANS for one entry rather than ranks.
     * The resolution order is generator-declaration order, which is stable but arbitrary to
     * anyone reading it.
     */
    public static SourceTable of(RecipeGraph graph, Map<Integer, String> freeSources) {
        List<Row> built = new ArrayList<Row>();
        for (Map.Entry<Integer, String> entry : freeSources.entrySet()) {
            int keyId = entry.getKey().intValue();
            String key = graph.key(keyId);
            if (key == null) {
                // A FREE SOURCE WHOSE KEY THE GRAPH CANNOT NAME IS SKIPPED, and it cannot
                // happen: `ScenarioInputs.resolveFreeSources` only ever puts ids it looked up
                // in the graph. Guarded because the alternative is a null drawn into a row,
                // which surfaces as an NPE inside a panel build -- swallowed by
                // `WidgetTree.resizeInternal`, leaving the whole screen at 0x0 with no message.
                continue;
            }
            String name = graph.recordedName(keyId);
            built.add(new Row(key, name == null || name.isEmpty() ? key : name,
                              entry.getValue() == null ? "" : entry.getValue()));
        }
        Collections.sort(built, new Comparator<Row>() {
            @Override
            public int compare(Row left, Row right) {
                return left.key().compareTo(right.key());
            }
        });
        return new SourceTable(built);
    }

    public List<Row> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }
}
