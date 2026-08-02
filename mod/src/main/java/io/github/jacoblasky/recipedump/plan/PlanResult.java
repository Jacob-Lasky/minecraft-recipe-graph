package io.github.jacoblasky.recipedump.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything {@link Solver#solve} returns. THIS IS THE WIRE FORMAT, field for field with
 * `solve.py`'s `solve()`.
 *
 * FIVE OF THESE LISTS ARE ORDERED BY `Counter.most_common()`, AND THAT ORDER IS PART OF THE
 * CONTRACT, NOT AN ACCIDENT OF THE IMPLEMENTATION. `most_common` sorts by count descending
 * and breaks ties by INSERTION order, which is the order the solver first reached each key --
 * roughly the order a player would work through the tree. It is deliberately NOT alphabetical
 * and NOT by key. {@link Counters#mostCommon} is the only thing that may build them.
 *
 * `machinesToBuild` is the odd one out and IS sorted by category, on purpose: it is a
 * checklist rather than a worklist, so a stable alphabetical order is easier to scan than one
 * that shuffles as the plan changes.
 */
public final class PlanResult {

    public String target;
    public String targetName;
    /** `{item key: why the pin was not used}`. Empty rather than absent, as in Python. */
    public Map<String, String> pinsOverruled = new LinkedHashMap<String, String>();
    public long qty;
    public PlanNode tree;

    /** What you still have to go and get. `most_common` order. */
    public List<PlanEntry> shoppingList;
    /** What the plan spent out of your network. `most_common` order. */
    public List<PlanEntry> usedFromStock;
    /** Drawn from an infinite generator you own; each row carries its `why`. */
    public List<PlanEntry> fromSources;
    /** Pack placeholders standing in for instructions; each row carries its `tokenKind`. */
    public List<PlanEntry> tokensNeeded;
    /** Transmutable through ProjectE; each row carries its `emc` so the claim is checkable. */
    public List<PlanEntry> fromEmc;
    /** Sorted by category. A checklist, not a worklist. */
    public List<MachineToBuild> machinesToBuild;

    public int nodes;
    public int work;
    public boolean truncated;
    /**
     * TWO CAUSES, and a reader who is about to wait for a bigger plan needs to know which.
     * `exhausted` means the WORK budget went first: the search spent itself on branches it
     * backtracked out of, so the node count is far below the cap and quoting the cap at that
     * reader is simply wrong. Raising `maxNodes` still helps, because `workBudget` derives
     * from it.
     */
    public boolean exhausted;
    /**
     * What the cap WAS, so a notice can say "4,000" rather than quoting the count it happened
     * to stop at as though that were the limit.
     */
    public int maxNodes;
    public int workBudget;

    /** One row of `machines_to_build`. */
    public static final class MachineToBuild {
        public final String category;
        public final String machine;
        public final String state;
        public final String why;

        MachineToBuild(String category, String machine, String state, String why) {
            this.category = category;
            this.machine = machine;
            this.state = state;
            this.why = why;
        }
    }
}
