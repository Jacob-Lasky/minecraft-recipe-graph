package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every category's verdict as a table, and the two filters that narrow each other.
 *
 * THE REPLACEMENT FOR `server.machines_page` PLUS `MACHINES_JS`, which is why the narrowing
 * rules below are ported rather than reinvented: #16 and #32 were both found by a human
 * looking at the live page, and both are behaviours a fresh implementation gets wrong in the
 * same way the first one did.
 *
 * IN `plan/` AND NOT IN `client/`, DELIBERATELY, AND DO NOT MOVE IT. Nothing here draws
 * anything -- it is rows, counts and orderings over {@link MachineStates}. `tools/ci-java.sh`
 * compiles and runs `graph/` and `plan/` on every pull request and NOTHING ELSE (#244: 324
 * assertions of 826, 24 files of 68), because those two packages import only `java.*` and
 * gson. Putting the filter logic here is what gets it into the job that actually runs, and
 * putting it beside the widgets would put the one part of this screen with real behaviour
 * into the 43 files that never run in CI. The widgets in `client.machines` are a function of
 * what this returns.
 *
 * THE CROSS-TAB IS COMPUTED ONCE, at construction, exactly as `machines.mod_state_counts`
 * is. The browser used to rebuild it from 503 rendered rows on every keystroke and that put
 * a domain fact where no test could reach it; the same argument applies here with more force,
 * because a panel rebuild in ModularUI happens on the client thread inside a frame.
 */
public final class MachineTable {

    /**
     * One category: the verdict, the evidence for it, and every candidate that was judged.
     *
     * A FLAT ROW RATHER THAN A {@link MachineInfo} HANDLE, because the widgets must not need
     * the graph. `PlannerWidgets`' class note is that no method there reaches for a service,
     * which is what lets every layout assertion run with no graph and no window; a row that
     * held a category id and made the caller resolve it would end that for this screen.
     */
    public static final class Row {

        private final String uid;
        private final String title;
        private final String mod;
        private final int state;
        private final String why;
        private final int recipes;
        private final boolean manual;
        private final boolean fromCatalyst;
        private final List<Candidate> candidates;

        Row(String uid, String title, String mod, int state, String why, int recipes,
            boolean manual, boolean fromCatalyst, List<Candidate> candidates) {
            this.uid = uid;
            this.title = title;
            this.mod = mod;
            this.state = state;
            this.why = why;
            this.recipes = recipes;
            this.manual = manual;
            this.fromCatalyst = fromCatalyst;
            this.candidates = Collections.unmodifiableList(candidates);
        }

        /** The JEI category id, e.g. `minecraft.smelting`. Never null. */
        public String uid() {
            return uid;
        }

        /**
         * What to call it: the cleaned JEI title, falling back to the uid.
         *
         * THE FALLBACK IS HERE AND NOT IN THE WIDGET, so the search-and-sort key and the drawn
         * label cannot disagree. `machines_page` does the same thing (`info["title"] or uid`)
         * and it matters for the 40% of categories `MachineInfo` records as unidentifiable --
         * a blank name column would make exactly the rows a reader came for unreadable.
         */
        public String name() {
            return title == null || title.isEmpty() ? uid : title;
        }

        /** The mod's DISPLAY name, which is the axis the mod filter groups on. */
        public String mod() {
            return mod;
        }

        /** One of the {@link MachineInfo} state constants. */
        public int state() {
            return state;
        }

        /**
         * The evidence sentence, with `(from JEI)` appended when the candidates came from
         * JEI's own "made in" list rather than from name matching.
         *
         * APPENDED HERE, matching `machines_page`, because the distinction changes how much a
         * reader should trust the verdict: a catalyst-derived candidate is JEI's own mapping
         * and a name-matched one is this tool's guess. Two columns for that would spend a
         * quarter of a 400px panel on a parenthetical.
         */
        public String why() {
            return fromCatalyst ? why + " (from JEI)" : why;
        }

        public int recipes() {
            return recipes;
        }

        /** True when a human set this state by hand, which outranks every automatic verdict. */
        public boolean manual() {
            return manual;
        }

        /**
         * Every candidate that was judged, most specific first.
         *
         * ALL OF THEM AND NOT THE WINNER, which is {@link MachineInfo#candidates}' own rule:
         * "Smelting is done in more than just the controller" is true of a lot of categories,
         * and reporting one verdict hides the other blocks that would also do (#27). The
         * detail panel is the only surface in the mod that can show it.
         */
        public List<Candidate> candidates() {
            return candidates;
        }
    }

    /** One block that could BE a category's machine, with its own verdict. */
    public static final class Candidate {

        private final String key;
        private final int state;
        private final String why;

        Candidate(String key, int state, String why) {
            this.key = key;
            this.state = state;
            this.why = why;
        }

        public String key() {
            return key;
        }

        public int state() {
            return state;
        }

        public String why() {
            return why;
        }
    }

    /**
     * What the reader has switched on: any set of states, and at most one mod.
     *
     * IMMUTABLE, AND EVERY TRANSITION GOES THROUGH {@link MachineTable#reconcile}. A mutable
     * filter would let a caller set a mod, draw, and only then discover the selection matches
     * nothing -- which is the exact bug #16 was filed for, one layer down.
     */
    public static final class Filter {

        /** Indexed by the {@link MachineInfo} state constants. */
        private final boolean[] states;
        /** Null for "every mod". */
        private final String mod;

        public static final Filter NONE = new Filter(new boolean[MachineInfo.STATE_COUNT], null);

        private Filter(boolean[] states, String mod) {
            this.states = states;
            this.mod = mod;
        }

        public boolean state(int state) {
            return states[state];
        }

        /** True when no state chip is on, which means "every state" rather than "none". */
        public boolean everyState() {
            for (boolean on : states) {
                if (on) {
                    return false;
                }
            }
            return true;
        }

        public String mod() {
            return mod;
        }

        public Filter toggleState(int state) {
            boolean[] next = states.clone();
            next[state] = !next[state];
            return new Filter(next, mod);
        }

        /** @param mod null for "every mod". */
        public Filter withMod(String mod) {
            return new Filter(states.clone(), mod);
        }
    }

    /** The counts each axis shows, given what the OTHER axis has switched on. */
    public static final class Narrowed {

        private final Map<String, Integer> byMod;
        private final int[] byState;

        Narrowed(Map<String, Integer> byMod, int[] byState) {
            this.byMod = Collections.unmodifiableMap(byMod);
            this.byState = byState;
        }

        /** Zero for a mod with nothing in the selected states. Never negative. */
        public int mod(String mod) {
            Integer n = byMod.get(mod);
            return n == null ? 0 : n.intValue();
        }

        public int state(int state) {
            return byState[state];
        }
    }

    private final List<Row> rows;
    /** mod display name -> counts indexed by state constant. `machines.mod_state_counts`. */
    private final Map<String, int[]> crossTab;
    /** `machines.mod_order`, computed once. See {@link #modOrder}. */
    private final List<String> modOrder;

    private MachineTable(List<Row> rows, Map<String, int[]> crossTab, List<String> modOrder) {
        this.rows = Collections.unmodifiableList(rows);
        this.crossTab = crossTab;
        this.modOrder = Collections.unmodifiableList(modOrder);
    }

    /**
     * Build the table from a resolved scenario's machine verdicts.
     *
     * ROWS COME OUT IN THE ORDER THE PAGE SHOWS THEM, sorted here rather than by the widget,
     * for the reason the cross-tab is computed here: a sort is a domain decision with a test,
     * and a widget that sorted would be a sort no CI job runs.
     */
    public static MachineTable of(RecipeGraph graph, MachineStates states) {
        List<Row> built = new ArrayList<Row>();
        for (int categoryId : states.describedCategories()) {
            MachineInfo info = states.info(categoryId);
            if (info == null) {
                continue;
            }
            built.add(rowFor(graph, categoryId, info));
        }
        Collections.sort(built, ROW_ORDER);

        Map<String, int[]> crossTab = new LinkedHashMap<String, int[]>();
        for (Row row : built) {
            int[] counts = crossTab.get(row.mod());
            if (counts == null) {
                counts = new int[MachineInfo.STATE_COUNT];
                crossTab.put(row.mod(), counts);
            }
            counts[row.state()]++;
        }
        return new MachineTable(built, crossTab, modOrder(crossTab));
    }

    private static Row rowFor(RecipeGraph graph, int categoryId, MachineInfo info) {
        int[] keys = info.candidates();
        int[] verdicts = info.candidateStates();
        String[] why = info.candidateWhy();
        List<Candidate> candidates = new ArrayList<Candidate>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            candidates.add(new Candidate(
                    graph.key(keys[i]),
                    // DEFENSIVE ON LENGTH RATHER THAN ASSUMED PARALLEL. `MachineInfo` says the
                    // three arrays are parallel and they are, but this reads a resolution built
                    // from live world state rather than from a fixture, and a short array here
                    // would surface as an AIOOBE thrown while a panel is being built -- which
                    // `WidgetTree.resizeInternal` swallows, leaving the whole screen at 0x0 with
                    // no message. See PlannerWidgets' rule 1 for the same failure shape.
                    i < verdicts.length ? verdicts[i] : MachineInfo.UNKNOWN,
                    i < why.length && why[i] != null ? why[i] : ""));
        }
        // `info.title()` IS ALREADY CLEANED -- `Machines.resolve` runs `cleanLabel` on it
        // before building the record. Re-running it here would be idempotent and would still
        // be wrong, because it would tell a reader the title arrives dirty.
        return new Row(graph.categoryName(categoryId), info.title(),
                       info.mod() == null ? "" : info.mod(), info.state(),
                       info.why() == null ? "" : info.why(), info.recipeCount(),
                       info.manual(), info.fromCatalyst(), candidates);
    }

    /**
     * State first, then the busiest category, then the uid.
     *
     * THE STATE CONSTANT IS THE SORT RANK, and that is not a coincidence worth re-deriving.
     * Python builds `present.STATE_RANK` by enumerating the same `STATES` tuple that orders
     * the cost bands, so its row sort and its band index are one number there too -- which is
     * what {@link MachineInfo}'s "THE CONSTANT ORDER IS LOAD-BEARING TWICE" records. Writing a
     * separate rank table here would be a second ordering free to drift from the first.
     *
     * RECIPES DESCENDING SECOND, because "which machine matters" is a question about how much
     * of the pack it opens, and a category with 1,441 recipes is a different kind of missing
     * from one with two.
     */
    private static final Comparator<Row> ROW_ORDER = new Comparator<Row>() {
        @Override
        public int compare(Row left, Row right) {
            if (left.state() != right.state()) {
                return left.state() - right.state();
            }
            if (left.recipes() != right.recipes()) {
                return right.recipes() - left.recipes();
            }
            return left.uid().compareTo(right.uid());
        }
    };

    /**
     * Mods in the order the picker lists them: most categories first, then by name.
     *
     * THE ONE PLACE ALPHABETICAL ORDER IS DECIDED, carried across from `machines.mod_order`
     * with its reason intact. In the browser the reason was that Python's `sorted` is
     * codepoint order and JavaScript's `localeCompare` is locale-aware, and over the 77 mod
     * names they disagreed at every position -- which only showed inside a TIE, and filtering
     * to `no route` leaves 74 of 77 mods tied at zero, so the whole group got re-alphabetised
     * by a rule written down nowhere.
     *
     * THE SAME TRAP EXISTS HERE AND IS NOT THE SAME BUG. `String.compareTo` is codepoint order
     * like Python's `sorted`, but a `Collator` or a `toLowerCase()` under a Turkish default
     * locale is not -- `"I".toLowerCase()` is `"ı"` there, which moves every mod beginning
     * with I. So the case fold is pinned to {@link Locale#ROOT} rather than left to the
     * player's, and the comparison after it is codepoint.
     *
     * `Locale.ROOT` LOWERCASE IS THE NEAREST THING JAVA HAS TO PYTHON'S `casefold`, and it is
     * not identical -- casefold maps `ß` to `ss` and this does not. No mod name in the pack
     * contains one, and the day one does the two languages disagree about one tie rather than
     * about the whole list. Recorded because a future reader comparing the two files should
     * find the gap named rather than have to find it.
     */
    private static List<String> modOrder(Map<String, int[]> crossTab) {
        List<String> mods = new ArrayList<String>(crossTab.keySet());
        final Map<String, Integer> totals = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, int[]> entry : crossTab.entrySet()) {
            int sum = 0;
            for (int n : entry.getValue()) {
                sum += n;
            }
            totals.put(entry.getKey(), Integer.valueOf(sum));
        }
        Collections.sort(mods, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int byCount = totals.get(right).intValue() - totals.get(left).intValue();
                if (byCount != 0) {
                    return byCount;
                }
                return left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
            }
        });
        return mods;
    }

    /** Every row, in display order, before any filter. */
    public List<Row> allRows() {
        return rows;
    }

    /** Every mod that has a category, in {@link #modOrder} order. */
    public List<String> mods() {
        return modOrder;
    }

    /** How many categories are in each state, indexed by the state constants. */
    public int[] stateTotals() {
        int[] totals = new int[MachineInfo.STATE_COUNT];
        for (int[] counts : crossTab.values()) {
            for (int state = 0; state < counts.length; state++) {
                totals[state] += counts[state];
            }
        }
        return totals;
    }

    /**
     * How many RECIPES are in each state, indexed by the state constants.
     *
     * A SECOND FIGURE BESIDE THE CATEGORY COUNT because the two answer different questions and
     * the page shows both. 96 categories on hand sounds like most of the pack until the recipe
     * column says they carry a tenth of its recipes; a category count alone flatters whichever
     * state holds the long tail of two-recipe categories.
     */
    public int[] recipeTotals() {
        int[] totals = new int[MachineInfo.STATE_COUNT];
        for (Row row : rows) {
            totals[row.state()] += row.recipes();
        }
        return totals;
    }

    /**
     * The counts each axis shows, given what the other one has switched on.
     *
     * COUNTS COME FROM THE OTHER AXIS ONLY, which is `MACHINES_JS.narrowed`'s rule and its
     * reason survives the port: counting a chip against its own selection makes every number
     * move as you click, and a moving target is harder to read than a slightly generous one.
     *
     * The browser also excluded its text box from these counts. There is no text box here
     * (see the class note on `client.machines.MachinesWidgets`), so that clause has nothing
     * to exclude and is not carried across as dead reasoning.
     */
    public Narrowed narrowed(Filter filter) {
        Map<String, Integer> byMod = new LinkedHashMap<String, Integer>();
        int[] byState = new int[MachineInfo.STATE_COUNT];
        boolean everyState = filter.everyState();
        for (Map.Entry<String, int[]> entry : crossTab.entrySet()) {
            String mod = entry.getKey();
            int[] counts = entry.getValue();
            int total = 0;
            boolean modPasses = filter.mod() == null || filter.mod().equals(mod);
            for (int state = 0; state < counts.length; state++) {
                if (everyState || filter.state(state)) {
                    total += counts[state];
                }
                if (modPasses) {
                    byState[state] += counts[state];
                }
            }
            // ABSENT RATHER THAN ZERO, matching `narrowed`'s `if(n)`. A mod with nothing in
            // the selected states is not a mod with zero of something; it is a mod the reader
            // cannot currently choose, and {@link Narrowed#mod} answers 0 for both.
            if (total > 0) {
                byMod.put(mod, Integer.valueOf(total));
            }
        }
        return new Narrowed(byMod, byState);
    }

    /**
     * Drop any selection that can no longer match anything, and return what survived.
     *
     * A SELECTION THAT MATCHES NOTHING IS CLEARED rather than left showing an empty table,
     * which is `MACHINES_JS.reconcile` and the more important half of #16: the reported bug
     * was "picking a mod then yields an empty table", and an empty table with both filters
     * still lit gives the reader nothing to undo.
     *
     * ONE PASS CONVERGES, and the reason is worth keeping because it is not obvious: dropping
     * a selection only ever WIDENS the result, so nothing cleared here can make something else
     * newly impossible. A loop would be a loop that runs once forever.
     */
    public Filter reconcile(Filter filter) {
        Narrowed narrowed = narrowed(filter);
        Filter out = filter;
        if (out.mod() != null && narrowed.mod(out.mod()) == 0) {
            out = out.withMod(null);
            // RE-NARROWED BEFORE THE STATES ARE JUDGED, AND THIS DIVERGES FROM `MACHINES_JS`
            // DELIBERATELY. That function takes ONE snapshot and tests both axes against it, so
            // the state counts it reads were computed while the doomed mod was still selected
            // -- and they are therefore zero for exactly the chip the reader just clicked.
            // Picking `buildable` while filtered to a mod that has none clears the mod AND the
            // chip in the browser, which throws away the click that caused the reconcile and
            // dumps the reader back to the unfiltered table.
            //
            // Clearing the mod WIDENS the result, so re-narrowing here can only turn zeroes
            // into positives; the chip then survives and the reader gets what they asked for.
            // It is still one pass, which is the property the loop-free shape depends on:
            // nothing cleared below can make anything else newly impossible.
            //
            // NOT A BUG FIX PORTED BACK TO PYTHON, because #19 Phase 6 deletes that page. If
            // this port is ever the follower again, `MACHINES_JS.reconcile` is the thing that
            // has to move.
            narrowed = narrowed(out);
        }
        for (int state = 0; state < MachineInfo.STATE_COUNT; state++) {
            if (out.state(state) && narrowed.state(state) == 0) {
                out = out.toggleState(state);
            }
        }
        return out;
    }

    /** The rows a reconciled filter leaves visible, in display order. */
    public List<Row> rows(Filter filter) {
        List<Row> visible = new ArrayList<Row>();
        boolean everyState = filter.everyState();
        for (Row row : rows) {
            if (filter.mod() != null && !filter.mod().equals(row.mod())) {
                continue;
            }
            if (!everyState && !filter.state(row.state())) {
                continue;
            }
            visible.add(row);
        }
        return visible;
    }

    /**
     * The mods a filter offers, live ones first.
     *
     * #32, AND IT IS A SEPARATE DECISION FROM DISABLING THEM. Greying a zero-count mod in
     * place (#16) answered "why is this mod not listed" but left the four mods that DO match
     * scattered among the 71 that do not, so the answer was unreachable -- filtering to `no
     * route` leaves 74 of 77 mods at zero. They stay in the list and keep their count; they
     * just stop being in the way.
     *
     * WITHIN EACH GROUP THE BASE ORDER IS KEPT, never recomputed from the narrowed counts.
     * That is the browser's `data-order` rule: the empty group is one enormous tie, and
     * re-sorting a tie by anything is how the two languages came to disagree about it.
     */
    public List<String> modsInOfferOrder(Narrowed narrowed) {
        List<String> live = new ArrayList<String>();
        List<String> empty = new ArrayList<String>();
        for (String mod : modOrder) {
            if (narrowed.mod(mod) > 0) {
                live.add(mod);
            } else {
                empty.add(mod);
            }
        }
        live.addAll(empty);
        return live;
    }
}
