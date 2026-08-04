package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A whole solved plan, as the panel needs to read it.
 *
 * The same decoupling as {@link PlanNode}: this is the frozen `result` object from
 * `tests/fixtures/plan/*.json`, and it is also what the Java solver hands back since #141 --
 * `PlannerService` runs `plan.PlanJson.toJson` and `client.planner.PlanJson.readResult`
 * parses it into this. Nothing here computes a plan; it reads one.
 *
 * IT READS THE WHOLE RESULT NOW, AND USED TO READ A SUBSET. Until #190 this class held nine
 * of the plan's fields and `PlanJson` read past the rest, which was correct while the browser
 * was the product: the in-game panel was a second view and `render.py` drew everything it
 * skipped. #19 makes this the ONLY view, so every field with no accessor here was a feature
 * scheduled for deletion the moment Phase 6 removes the Python renderer. See
 * {@link #usedFromStock} for the four summary lists that were closest to being lost.
 */
public final class PlanView {

    private final String target;
    private final String targetName;
    private final long qty;
    private final PlanNode tree;
    private final boolean truncated;
    private final boolean exhausted;
    private final int nodes;
    private final int maxNodes;
    private final int work;
    private final int workBudget;
    private final List<EntryRow> shoppingList;
    private final List<EntryRow> usedFromStock;
    private final List<EntryRow> fromSources;
    private final List<EntryRow> tokensNeeded;
    private final List<EntryRow> fromEmc;
    private final List<MachineRow> machinesToBuild;
    private final List<String> pinsOverruled;

    private PlanView(Builder b) {
        this.target = b.target;
        this.targetName = b.targetName;
        this.qty = b.qty;
        this.tree = b.tree;
        this.truncated = b.truncated;
        this.exhausted = b.exhausted;
        this.nodes = b.nodes;
        this.maxNodes = b.maxNodes;
        this.work = b.work;
        this.workBudget = b.workBudget;
        this.shoppingList = Collections.unmodifiableList(b.shoppingList);
        this.usedFromStock = Collections.unmodifiableList(b.usedFromStock);
        this.fromSources = Collections.unmodifiableList(b.fromSources);
        this.tokensNeeded = Collections.unmodifiableList(b.tokensNeeded);
        this.fromEmc = Collections.unmodifiableList(b.fromEmc);
        this.machinesToBuild = Collections.unmodifiableList(b.machinesToBuild);
        this.pinsOverruled = Collections.unmodifiableList(b.pinsOverruled);
    }

    /**
     * A BUILDER RATHER THAN A CONSTRUCTOR, AND THE REASON IS THE FIVE `List<EntryRow>` FIELDS.
     *
     * This took eleven positional arguments until #190 and now carries seventeen values, five
     * of which are lists of the SAME type sitting next to each other. A transposition between
     * two of those compiles, passes every round-trip test whose fixture leaves both lists
     * empty -- which is most of them, since `from_emc` and `tokens_needed` are empty until #50
     * and #112 land -- and shows up in game as a plan claiming stock came from EMC. There is
     * no type to catch it and no assertion that would have.
     *
     * DO NOT add a positional constructor beside this. `PlanNode.Builder` is the same pattern
     * for the same reason and the two should stay recognisably one idea.
     */
    public static final class Builder {
        private String target = "";
        private String targetName = "";
        private long qty = 1L;
        private PlanNode tree;
        private boolean truncated;
        private boolean exhausted;
        private int nodes;
        private int maxNodes;
        private int work;
        private int workBudget;
        private List<EntryRow> shoppingList = Collections.emptyList();
        private List<EntryRow> usedFromStock = Collections.emptyList();
        private List<EntryRow> fromSources = Collections.emptyList();
        private List<EntryRow> tokensNeeded = Collections.emptyList();
        private List<EntryRow> fromEmc = Collections.emptyList();
        private List<MachineRow> machinesToBuild = Collections.emptyList();
        private List<String> pinsOverruled = Collections.emptyList();

        public Builder target(String value) {
            this.target = value;
            return this;
        }

        public Builder targetName(String value) {
            this.targetName = value;
            return this;
        }

        public Builder qty(long value) {
            this.qty = value;
            return this;
        }

        public Builder tree(PlanNode value) {
            this.tree = value;
            return this;
        }

        public Builder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        public Builder exhausted(boolean value) {
            this.exhausted = value;
            return this;
        }

        public Builder nodes(int value) {
            this.nodes = value;
            return this;
        }

        public Builder maxNodes(int value) {
            this.maxNodes = value;
            return this;
        }

        public Builder work(int value) {
            this.work = value;
            return this;
        }

        public Builder workBudget(int value) {
            this.workBudget = value;
            return this;
        }

        public Builder shoppingList(List<EntryRow> value) {
            this.shoppingList = value;
            return this;
        }

        public Builder usedFromStock(List<EntryRow> value) {
            this.usedFromStock = value;
            return this;
        }

        public Builder fromSources(List<EntryRow> value) {
            this.fromSources = value;
            return this;
        }

        public Builder tokensNeeded(List<EntryRow> value) {
            this.tokensNeeded = value;
            return this;
        }

        public Builder fromEmc(List<EntryRow> value) {
            this.fromEmc = value;
            return this;
        }

        public Builder machinesToBuild(List<MachineRow> value) {
            this.machinesToBuild = value;
            return this;
        }

        public Builder pinsOverruled(List<String> value) {
            this.pinsOverruled = value;
            return this;
        }

        public PlanView build() {
            if (tree == null) {
                throw new IllegalStateException("a plan view must carry a tree");
            }
            return new PlanView(this);
        }
    }

    /**
     * A plan with nothing in it, for the panels that can be shown before one is solved.
     *
     * NOT NULL, which is the point. The TODO panel is real and usable today; handing it a
     * null plan would put a null check in every accessor for a case that is normal rather
     * than exceptional.
     */
    public static PlanView empty() {
        PlanNode root = new PlanNode.Builder()
                .key("")
                .label("no plan yet")
                .name("no plan yet")
                .kind("item")
                .status(NodeStatus.CRAFT)
                .build();
        return new Builder().targetName("no plan yet").qty(0L).tree(root).nodes(1).build();
    }

    public String target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    public long qty() {
        return qty;
    }

    public PlanNode tree() {
        return tree;
    }

    /**
     * True when the node budget cut the tree short.
     *
     * THE PANEL MUST SAY SO. A truncated plan looks exactly like a complete one -- the
     * branches that were cut are simply not there -- so a reader who is not told will act on
     * a shopping list that is missing items. The web UI prints it and so does this.
     */
    public boolean truncated() {
        return truncated;
    }

    /** True when the search ran out of work budget rather than out of nodes. */
    public boolean exhausted() {
        return exhausted;
    }

    public int nodes() {
        return nodes;
    }

    public int maxNodes() {
        return maxNodes;
    }

    /**
     * Search steps spent, against {@link #workBudget}.
     *
     * THE PAIR IS WHAT MAKES `exhausted` ACTIONABLE, and it was terminal-only until #190.
     * `cli.cmd_plan` prints both; the in-game panel said "search gave up early" and left the
     * player to guess whether raising the node cap would help. It does, because the budget
     * derives from the cap -- but a reader cannot know that from the sentence alone, and the
     * numbers are what turn it from a rule they have to trust into one they can check.
     */
    public int work() {
        return work;
    }

    /** What {@link #work} was measured against. See there. */
    public int workBudget() {
        return workBudget;
    }

    /** What you still have to obtain. */
    public List<EntryRow> shoppingList() {
        return shoppingList;
    }

    /**
     * What the plan drew out of the stock it was given, and the first of the four summary
     * lists the client used to discard.
     *
     * THIS ONE ANSWERS THE QUESTION THE `have` CAVEAT ASKS. `ScenarioSource.HAVE` exists
     * because a plan built on an unread inventory tells a player to go and fetch iron they
     * already own, and since #191 the ME network really is read -- so this list is the direct
     * evidence that the read worked and what it was worth. A planner that reads stock and
     * never says what it found is asking to be doubted.
     */
    public List<EntryRow> usedFromStock() {
        return usedFromStock;
    }

    /**
     * Drawn from infinite generators, with the generator named per row in {@link EntryRow#why}.
     *
     * SHOWN EVEN THOUGH IT IS FREE, which is `render._sources_html`'s own argument and worth
     * keeping: "free" must not mean "invisible", because a plan that quietly consumed 64,000
     * buckets of water reads as though it needed nothing. The quantity is the useful signal
     * even where the cost is zero.
     */
    public List<EntryRow> fromSources() {
        return fromSources;
    }

    /**
     * Placeholders standing for an instruction rather than an item, kind in
     * {@link EntryRow#tokenKind}.
     *
     * EMPTY IN GAME UNTIL #112, because `ScenarioSource.VISITED_DIMENSIONS` is not live and a
     * trip is what most tokens stand for. Read anyway rather than skipped: an accessor that
     * appears when the feature does is a second change to remember, and this is the shape the
     * write-only sweep behind #190 was looking for.
     */
    public List<EntryRow> tokensNeeded() {
        return tokensNeeded;
    }

    /**
     * Items the ProjectE network transmutes rather than crafts, cost in {@link EntryRow#emc}.
     *
     * EMPTY IN GAME UNTIL #50, for the reason {@link #tokensNeeded} is empty until #112, and
     * read now for the same reason.
     */
    public List<EntryRow> fromEmc() {
        return fromEmc;
    }

    /** Machines the plan routes through that the player does not have yet. */
    public List<MachineRow> machinesToBuild() {
        return machinesToBuild;
    }

    /**
     * One sentence per recipe choice the solver could not honour, sorted.
     *
     * THE PANEL MUST SAY THESE, and until this field existed it silently did not. A pin the
     * cycle guard overrules -- `9 nuggets -> 1 ingot`, whose nuggets come from ingots -- is
     * the case where the picker's click appears to have worked and did not, and the plan
     * comes back using the route the player just rejected. `Solver.noteOverruledPin`'s own
     * comment says the plan says it; `render.py` puts it in the warnbar; the in-game panel
     * read past it. Found by a screenshot: pinning "Iron Ingot from Iron Nugget" against the
     * reference pack produced a byte-identical picture to not pinning anything at all.
     *
     * SORTED, matching `render.py` and `cmd_plan`, so the same plan reads the same way
     * wherever it is shown -- a map's iteration order is not a thing to expose to a reader.
     */
    public List<String> pinsOverruled() {
        return pinsOverruled;
    }

    /** Every node of the tree, parents before children. */
    public List<PlanNode> flatten() {
        List<PlanNode> all = new ArrayList<PlanNode>();
        collect(tree, all);
        return all;
    }

    private static void collect(PlanNode node, List<PlanNode> into) {
        into.add(node);
        for (PlanNode child : node.children()) {
            collect(child, into);
        }
    }

    /**
     * One row of any of the plan's five summary lists.
     *
     * ONE CLASS FOR FIVE LISTS, mirroring `plan.PlanEntry`, which is likewise one class the
     * serializer writes all five from. The optional fields are the per-list decorations and
     * only one of them is ever set on a given row: `why` on `from_sources`, `tokenKind` on
     * `tokens_needed`, `emc` on `from_emc`, `unsourced` on `shopping_list`. It was named
     * `ShoppingRow` while the shopping list was the only one the client read.
     */
    public static final class EntryRow {
        private final String key;
        private final String label;
        private final long need;
        private final String kind;
        private final String why;
        private final String tokenKind;
        private final Long emc;
        private final boolean unsourced;

        EntryRow(String key, String label, long need, String kind, String why,
                 String tokenKind, Long emc, boolean unsourced) {
            this.key = key;
            this.label = label;
            this.need = need;
            this.kind = kind;
            this.why = why;
            this.tokenKind = tokenKind;
            this.emc = emc;
            this.unsourced = unsourced;
        }

        /**
         * The registry key, which is the row's IDENTITY and not decoration.
         *
         * THE LABEL IS NOT UNIQUE AND CANNOT BE MADE UNIQUE. 5,095 display names are shared on
         * the reference pack. On a SHOPPING LIST the collision is rarer than on a tree but it
         * is real and committed: `plan-fluid-chain` holds two distinct keys both labelled
         * "Soul Vial", the only such pair across 266 summary rows in the fixture set, and a
         * list shown by label alone draws them as two rows a player cannot tell apart.
         * `NodeRowText.entryLines` uses this to disambiguate the rows that collide. DO NOT drop
         * it from the row text on the grounds that a key is ugly; see that method for why only
         * the colliding rows pay for it.
         *
         * `plan-same-name`'s six items called "Iron Plate" are on the TREE, not on its shopping
         * list, and the tree draws them from `PlanNode`. Worth keeping straight: it decides
         * which surface a fix belongs on, and conflating the two is how #190's own table came
         * to overstate two of its rows.
         */
        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public long need() {
            return need;
        }

        /** `item` or `fluid`. A fluid quantity is mB and the row says so. */
        public String kind() {
            return kind;
        }

        /** `from_sources` only: which generator supplies this, in words a player can act on. */
        public String why() {
            return why;
        }

        /** `tokens_needed` only: what kind of instruction this placeholder stands for. */
        public String tokenKind() {
            return tokenKind;
        }

        /** `from_emc` only: the transmutation cost, or null. */
        public Long emc() {
            return emc;
        }

        /**
         * `shopping_list` only: nothing in the graph makes this key (#136).
         *
         * THE MARK BELONGS ON BOTH SURFACES AND HAD IT ON ONE. The tree node carries the same
         * flag and `NodeStatus` has badged it since #136; the shopping row parsed it and threw
         * it away, so after #176 a list could hold an unsourced item with nothing marking it
         * and the player would read it as an ordinary thing to go and fetch. `_need_entry`'s
         * docstring is the reason both surfaces get it: "the tree is the diagnosis; this is
         * what gets acted on while gathering."
         */
        public boolean unsourced() {
            return unsourced;
        }
    }

    /** One machine the plan needs that is not simply available. */
    public static final class MachineRow {
        private final String category;
        private final String machine;
        private final String state;
        private final String why;

        MachineRow(String category, String machine, String state, String why) {
            this.category = category;
            this.machine = machine;
            this.state = state;
            this.why = why;
        }

        public String category() {
            return category;
        }

        public String machine() {
            return machine;
        }

        // NO RAW `state()` ACCESSOR, AND THAT IS #190's OWN LESSON APPLIED TO ITSELF. One was
        // here and its only caller was `PlanJsonTest`, which is what the issue says about all
        // four of these. Three of them got readers; this one could not honestly get one, because
        // `stateLabel()` is the only form anything draws and no fixture can tell them apart --
        // all 125 committed machine rows are `buildable`, whose label happens to be the same
        // word. So the round-trip is asserted THROUGH the label instead, on a hand-built
        // `unavailable` row where the two differ. DO NOT add a raw accessor back for a test.

        /**
         * What stands between the player and this machine, in words they can act on.
         *
         * THE FOOTER'S "3 machine(s) to build" IS A COUNT AND THIS IS THE ANSWER. Until #190
         * nothing in game read any of these four accessors, so a player was told how many
         * machines were in the way and could never learn which, let alone why. `render.py`
         * puts this string in the badge tooltip and `NodeRowText.machineLines` puts it in the
         * TODO panel's machine section.
         */
        public String why() {
            return why;
        }

        /** What the badge says, worded by {@link NodeStatus#machineStateLabel}. */
        public String stateLabel() {
            return NodeStatus.machineStateLabel(state);
        }
    }
}
