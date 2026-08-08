package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.plan.Provenance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a node status is shown: the badge word and the colour.
 *
 * A PORT OF `recipegraph/present.py`, NOT A NEW PALETTE. The web UI and this panel will
 * both exist until #19 Phase 6 retires the browser, and two surfaces describing the same
 * plan in different words is worse than either being wrong -- the reader has no way to tell
 * which one to believe. So the words come from `present.STATUS_LABEL`, the token refinements
 * from `tokens.KIND_BADGE`, and the colours from the light theme's semantic variables in
 * `render.CSS`.
 *
 * DO NOT ADD A LOCAL STATUS MAP TO A WIDGET. That instruction is copied verbatim in intent
 * from `present.py`'s own module docstring, and for the same reason: it briefly had one dict
 * per renderer, and adding a status silently drew every new node as "craft".
 * `NodeStatusTest` asserts this map covers every status the fixtures contain and every
 * status `present.py` declares, so a new one breaks a test rather than rendering blank.
 */
public final class NodeStatus {

    // The solver's statuses, spelled as `solve.py` spells them.
    public static final String HAVE = "have";
    public static final String PARTIAL = "partial";
    public static final String CRAFT = "craft";
    public static final String RAW = "raw";
    public static final String TOKEN = "token";
    public static final String SOURCE = "source";
    public static final String EMC = "emc";
    public static final String CYCLE = "cycle";
    public static final String DEPTH = "depth";
    /**
     * A display distinction rather than a resolution outcome, which is why `solve.py` has no
     * constant for it and `present.py` defines it itself. Kept in the same place here.
     */
    public static final String OREDICT = "oredict";

    /**
     * The four semantic inks, taken from `render.CSS`'s LIGHT theme.
     *
     * Light rather than dark, because a ModularUI panel is the vanilla one and the vanilla
     * panel is light grey (#c6c6c6). Reading the dark theme's inks -- picked to sit on
     * #15171a -- onto that background gives pale text on pale grey.
     *
     * `0xFF` alpha because Minecraft's font renderer treats a zero alpha as fully
     * transparent rather than as opaque, so a bare 0xRRGGBB draws nothing at all.
     */
    public static final int INK_OK = 0xFF1A7F4B;
    public static final int INK_WARN = 0xFF8A6100;
    public static final int INK_CRAFT = 0xFF2F5F96;
    public static final int INK_NEED = 0xFFA3272B;
    public static final int INK_MUTED = 0xFF6F6D68;

    /**
     * What the row says when the graph can make the item but not in the state asked for.
     *
     * ONE DEFINITION, because the tree badge, the shopping-list row and the legend all show
     * it -- the same reason `present.UNSOURCED_BADGE` exists on the Python side.
     */
    public static final String UNSOURCED_BADGE = "no known source";

    private static final Map<String, Entry> ENTRIES;
    private static final Map<String, String> TOKEN_BADGE;

    static {
        Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
        // Order follows `present.ALL_STATUSES`, so a legend built from this cannot reshuffle.
        entries.put(HAVE, new Entry("in stock", INK_OK));
        entries.put(PARTIAL, new Entry("part stock", INK_WARN));
        entries.put(CRAFT, new Entry("craft", INK_CRAFT));
        entries.put(RAW, new Entry("NEED", INK_NEED));
        // Generic; `badge` refines it per token kind, because "go get" is a lie on a quest
        // gate that unlocks by playing rather than by fetching.
        entries.put(TOKEN, new Entry("go get", INK_NEED));
        entries.put(SOURCE, new Entry("infinite", INK_OK));
        entries.put(EMC, new Entry("transmute", INK_OK));
        entries.put(CYCLE, new Entry("loop", INK_MUTED));
        entries.put(DEPTH, new Entry("cut off", INK_MUTED));
        entries.put(OREDICT, new Entry("any of", INK_MUTED));
        ENTRIES = Collections.unmodifiableMap(entries);

        Map<String, String> kinds = new LinkedHashMap<String, String>();
        kinds.put("loot", "go get");
        kinds.put("gate", "locked");
        kinds.put("hint", "any of class");
        kinds.put("method", "mechanic");
        TOKEN_BADGE = Collections.unmodifiableMap(kinds);
    }

    private NodeStatus() {
    }

    /** Every status this knows, in the order a legend should list them. */
    public static List<String> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<String>(ENTRIES.keySet()));
    }

    /** The word a token kind refines the generic badge to. */
    public static String tokenBadge(String kind) {
        String refined = TOKEN_BADGE.get(kind);
        return refined == null ? badgeFor(TOKEN) : refined;
    }

    /** Every token kind that refines a `token` badge. */
    public static List<String> tokenKinds() {
        return Collections.unmodifiableList(new java.util.ArrayList<String>(TOKEN_BADGE.keySet()));
    }

    public static boolean knows(String status) {
        return ENTRIES.containsKey(status);
    }

    /**
     * True when this node is an INSTRUCTION rather than an item you fetch.
     *
     * A PREDICATE RATHER THAN `TOKEN.equals(node.status())` AT THE CALL SITE, because this
     * class's header forbids a widget keeping its own notion of a status and a spelled-out
     * comparison is the first half of one. `badge` already asks the same question internally.
     *
     * WHY A RENDERER NEEDS TO ASK. A token is a pack placeholder -- `contenttweaker:dungeon_drop`
     * is `LOOT` in `tokens.DEFAULT_TOKENS` -- and it is a REGISTERED ITEM, so an icon lookup for
     * it succeeds and returns a perfectly good picture of a thing that does not exist. #174 was
     * reported on exactly that read: "the osiris spinel shows it requires a Dungeon Drop which
     * implies it is an item". The solver keeps it out of `shopping_list` and reports it in
     * `tokens_needed` for the same reason. See {@link PlannerWidgets#TOKEN_MARK}.
     */
    public static boolean isToken(PlanNode node) {
        return node != null && TOKEN.equals(node.status());
    }

    /**
     * The badge word for a node.
     *
     * A TOKEN KIND WINS over `unsourced`, matching `present.status_badge`. The combination
     * cannot occur -- the solver returns at the token branch first -- but the order has to be
     * unambiguous for a caller that passes both, and "locked" is the more specific of the two.
     *
     * THE COLOUR DOES NOT CHANGE when the word does. Both refinements still describe
     * something you have to obtain; what changed is how. A second colour would imply a
     * different kind of row.
     */
    public static String badge(PlanNode node) {
        Entry entry = ENTRIES.get(node.status());
        if (TOKEN.equals(node.status())) {
            String refined = TOKEN_BADGE.get(node.tokenKind());
            if (refined != null) {
                return refined;
            }
        }
        // THE PACK'S OWN ANSWER OUTRANKS "no known source", and the two cannot both be set.
        // #171/#262: `Solver.expand` writes exactly one of them -- a declared key is excluded
        // from the pack-authored unsourced set by construction -- so the order below is stated
        // only so a caller passing both is not silently resolved. The more specific claim wins
        // again: naming the puzzle beats saying nothing is known. Mirrors
        // `present.status_badge`, which orders its two branches the same way.
        //
        // STILL NO NEW COLOUR. It remains something you have to go and obtain; what changed is
        // that this time the tool can tell you where to look.
        if (node.provenance() != null && !node.provenance().isEmpty()) {
            return Provenance.badgeFor(node.provenance());
        }
        // #139's mark. The solver resolved this identically to any other raw leaf, but a
        // reader must not treat it identically: the tool cannot say where to get it. A TOKEN
        // KIND WINS, matching `present.status_badge` -- the combination cannot occur, since
        // `expand` returns at the token branch first, but the order has to be unambiguous.
        if (node.unsourced()) {
            return UNSOURCED_BADGE;
        }
        // An unknown status prints itself rather than an empty badge: a blank cell is
        // indistinguishable from a rendering bug, and the raw word at least names the case.
        return entry == null ? node.status() : entry.badge;
    }

    /** The ink for a node's badge and quantity. */
    public static int colour(PlanNode node) {
        Entry entry = ENTRIES.get(node.status());
        return entry == null ? INK_MUTED : entry.ink;
    }

    /** The badge word for a status with no node in hand, for a legend. */
    public static String badgeFor(String status) {
        Entry entry = ENTRIES.get(status);
        return entry == null ? status : entry.badge;
    }

    public static int colourFor(String status) {
        Entry entry = ENTRIES.get(status);
        return entry == null ? INK_MUTED : entry.ink;
    }

    /**
     * True when this step's machine is something to sort out before the step can run.
     *
     * A port of `present.is_roadblock`, including the part that reads oddly: `unknown` DOES
     * count. It means the tool could not identify the machine, not that you have it, and
     * treating unidentified as fine would hide a real wall behind a tooling gap. A machine
     * you HAVE is not a roadblock, and neither is no machine at all -- hand crafting carries
     * no state, and flagging that would put a warning on most of a plan.
     */
    public static boolean isRoadblock(String machineState) {
        return machineState != null && !machineState.isEmpty() && !HAVE.equals(machineState);
    }

    /** Machine availability, worded as `present.STATE_LABEL` words it. */
    public static String machineStateLabel(String state) {
        if ("have".equals(state)) {
            return "have";
        }
        if ("buildable".equals(state)) {
            return "buildable";
        }
        // "unidentified", not "unknown": the latter invites reading it as "unknown whether
        // you can use it" when it means "this tool could not work out which block this is".
        if ("unknown".equals(state)) {
            return "unidentified";
        }
        if ("unavailable".equals(state)) {
            return "no route";
        }
        return state == null ? "" : state;
    }

    /** The statuses present in a tree, in legend order, without duplicates. */
    public static List<String> legendFor(PlanNode root) {
        java.util.Set<String> present = new java.util.LinkedHashSet<String>();
        collectStatuses(root, present);
        java.util.List<String> ordered = new java.util.ArrayList<String>();
        for (String status : ENTRIES.keySet()) {
            if (present.contains(status)) {
                ordered.add(status);
            }
        }
        // Anything the tree had that this class does not know, so a legend cannot silently
        // omit a row the tree is showing.
        for (String status : present) {
            if (!ENTRIES.containsKey(status)) {
                ordered.add(status);
            }
        }
        return ordered;
    }

    private static void collectStatuses(PlanNode node, java.util.Set<String> into) {
        into.add(node.status());
        for (PlanNode child : node.children()) {
            collectStatuses(child, into);
        }
    }

    private static final class Entry {
        final String badge;
        final int ink;

        Entry(String badge, int ink) {
            this.badge = badge;
            this.ink = ink;
        }
    }

}
