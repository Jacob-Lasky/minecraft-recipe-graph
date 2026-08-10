package io.github.jacoblasky.recipedump.client.browse;

import io.github.jacoblasky.recipedump.client.planner.NodeRowText;
import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.client.planner.PlannerWidgets;
import io.github.jacoblasky.recipedump.plan.GraphFacts;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ListWidget;

/**
 * Which graph the planner is answering from.
 *
 * NOT `stats_page`, AND {@link GraphFacts} CARRIES THE ARGUMENT FOR WHY NOT. In short: the
 * browser's Coverage page answers "how complete is this graph" for the person who built it,
 * and in game nobody is that person. The same numbers answer a question the browser never had
 * to -- `graph.json` is a file the player copied into their config directory and it survives a
 * pack update, so the live failure is a stale graph confidently answering for a pack that has
 * moved. This screen is that check.
 *
 * SO IT LEADS WITH IDENTITY AND FOLLOWS WITH SIZE. The instance the dump came from is the line
 * a reader can act on; the totals are a fingerprint that tells two graphs apart far more
 * readably than a sha would.
 *
 * The same two `PlannerWidgets` rules bind here as everywhere: every text widget states its
 * size, and columns are fixed widths.
 */
public final class GraphWidgets {

    public static final int PANEL_WIDTH = PlannerWidgets.PANEL_WIDTH;
    public static final int PANEL_HEIGHT = PlannerWidgets.PANEL_HEIGHT;
    public static final int PADDING = PlannerWidgets.PADDING;
    public static final int CONTENT_WIDTH = PlannerWidgets.CONTENT_WIDTH;
    public static final int LINE = PlannerWidgets.LINE;
    public static final int ROW_HEIGHT = PlannerWidgets.ROW_HEIGHT;
    public static final int GAP = PlannerWidgets.GAP;

    /** The count column on a by-source row, right-aligned. */
    public static final int COUNT_WIDTH = 8 * NodeRowText.CHAR_WIDTH;

    /**
     * How many lines the instance path may wrap to.
     *
     * WRAPPED RATHER THAN CUT, which is the exception `plannerPanel` makes for its caveat and
     * for the same reason. A truncated label is a name you can still guess at; a truncated
     * PATH is the wrong path, and this one exists to be compared against the folder the player
     * knows they are running. Two lines is 128 characters, which covers a Windows instance path
     * with room to spare.
     */
    public static final int MAX_PATH_LINES = 2;

    private GraphWidgets() {
    }

    /**
     * The whole graph-identity window.
     *
     * `facts` MAY BE NULL, and that is not a defensive habit -- it is the state the screen is
     * most likely to be opened in on a fresh install, where there is no graph at all. The
     * caller draws a not-yet panel for that case; this asserts nothing about it.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ModularPanel graphPanel(GraphFacts facts, String path,
                                          GraphFacts.PackCheck check,
                                          GraphFacts.SchemaCheck schema, BrowseActions nav) {
        PlannerWidgets.Group body = new PlannerWidgets.Group();
        body.pos(PADDING, PADDING);
        body.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2);

        int y = 0;
        body.child(BrowseTabs.strip(BrowseTabs.Tab.GRAPH, nav, CONTENT_WIDTH).pos(0, y));
        y += ROW_HEIGHT + 1;

        // THE VERDICT FIRST, AND EVERYTHING BELOW IT IS THE EVIDENCE FOR IT. An earlier cut of
        // this screen showed the file, the instance, the build and the totals and stopped --
        // which are the INPUTS to "is this graph stale", not the answer. A player who is not
        // already suspicious will not diff two hex strings, and the one who is suspicious is
        // the one who least needed the screen.
        body.child(PlannerWidgets.line(verdictLine(check), CONTENT_WIDTH, verdictColour(check))
                           .pos(0, y));
        y += LINE;
        body.child(PlannerWidgets.line(check == null ? "" : check.detail(), CONTENT_WIDTH,
                                       NodeStatus.INK_MUTED).pos(0, y));
        y += LINE;

        // THE SECOND VERDICT, AND IT IS A DIFFERENT QUESTION FROM THE FIRST (#285). The pack
        // check asks whether the dump saw the jars this game runs; this asks whether the FORMAT
        // it wrote is the one this build reads. Both can be wrong independently -- a dump taken
        // from the right pack by a mod two versions old passes the first and fails this -- so
        // one verdict cannot stand in for the other.
        //
        // THE SAME TWO-LINE SHAPE AS THE PACK CHECK, verdict word then numbers and fix, because
        // they are two answers to one question and a reader comparing them should not have to
        // learn a second layout. It costs the by-source table one row and that was the trade
        // made knowingly: the table is a fingerprint, and a verdict nobody can see is the
        // defect this issue exists to fix.
        //
        // MATCHES DRAWS TOO. A screen has no equivalent of silence -- see `GraphFacts.Verdict`.
        body.child(PlannerWidgets.line(schemaLine(schema), CONTENT_WIDTH, schemaColour(schema))
                           .pos(0, y));
        y += LINE;
        body.child(PlannerWidgets.line(schema == null ? "" : schema.detail(), CONTENT_WIDTH,
                                       NodeStatus.INK_MUTED).pos(0, y));
        y += LINE + 1;

        // THE FILE FIRST, because it is the only line a player can act on: it is the thing they
        // would replace. `GraphSource` puts it under `<config>/mcrecipedump/`, which is a
        // directory most players never open, so naming the full path is worth two lines.
        for (String line : NodeRowText.wrap(pathLine(path), CONTENT_WIDTH, MAX_PATH_LINES)) {
            body.child(PlannerWidgets.line(line, CONTENT_WIDTH, NodeStatus.INK_CRAFT).pos(0, y));
            y += LINE;
        }
        for (String line : NodeRowText.wrap(instanceLine(facts), CONTENT_WIDTH, MAX_PATH_LINES)) {
            body.child(PlannerWidgets.line(line, CONTENT_WIDTH, NodeStatus.INK_MUTED).pos(0, y));
            y += LINE;
        }
        body.child(PlannerWidgets.line(builtLine(facts), CONTENT_WIDTH, NodeStatus.INK_MUTED)
                           .pos(0, y));
        y += LINE + 1;
        body.child(PlannerWidgets.heading(sizeLine(facts), CONTENT_WIDTH).pos(0, y));
        y += LINE;
        body.child(PlannerWidgets.line(namesLine(facts), CONTENT_WIDTH, NodeStatus.INK_MUTED)
                           .pos(0, y));
        y += LINE + 1;

        body.child(PlannerWidgets.heading("Where the recipes came from", CONTENT_WIDTH).pos(0, y));
        y += LINE;

        int listHeight = PANEL_HEIGHT - PADDING * 2 - y;
        ListWidget list = new ListWidget();
        list.size(CONTENT_WIDTH, listHeight);
        list.crossAxisAlignment(Alignment.CrossAxis.START);
        for (GraphFacts.Source source : sources(facts)) {
            list.child(sourceRow(source, CONTENT_WIDTH));
        }
        list.pos(0, y);
        body.child(list);

        return ModularPanel.defaultPanel("mcrecipedump_graph", PANEL_WIDTH, PANEL_HEIGHT)
                .child(body);
    }

    private static List<GraphFacts.Source> sources(GraphFacts facts) {
        return facts == null ? new ArrayList<GraphFacts.Source>() : facts.sources();
    }


    /**
     * The verdict, as a word a reader cannot misread.
     *
     * A DISTINCT WORD PER STATE AND NOT ONLY A COLOUR, which is the redundant-channel rule this
     * UI already follows for the state chips and the tab strip. It matters most here: the three
     * states are "fine", "your plans are wrong" and "I did not check", and a colour-only signal
     * would make the third indistinguishable from the first in a greyscale screenshot -- which
     * is exactly the "looks fine but means never checked" failure the third state exists to
     * prevent.
     *
     * `UNCHECKED` RATHER THAN `UNKNOWN`, because the reader is being told something about what
     * the TOOL did, not about the pack. "Unknown" invites "unknown to whom".
     */
    static String verdictLine(GraphFacts.PackCheck check) {
        if (check == null) {
            return "pack: UNCHECKED";
        }
        switch (check.verdict()) {
            case MATCHES:
                return "pack: OK";
            case DIFFERS:
                return "pack: MISMATCH -- plans from this graph are suspect";
            default:
                return "pack: UNCHECKED";
        }
    }

    /**
     * Green, red, amber. AMBER AND NOT MUTED for `CANNOT_TELL`: muted is what this panel uses
     * for supporting detail, so a muted verdict would read as an aside rather than as a gap.
     */
    static int verdictColour(GraphFacts.PackCheck check) {
        if (check == null) {
            return NodeStatus.INK_WARN;
        }
        switch (check.verdict()) {
            case MATCHES:
                return NodeStatus.INK_OK;
            case DIFFERS:
                return NodeStatus.INK_NEED;
            default:
                return NodeStatus.INK_WARN;
        }
    }

    /**
     * The schema verdict, as a word a reader cannot misread.
     *
     * `format:` AND NOT `schema:`, because {@link #builtLine} lower down already says
     * "dumped by: 0.10.0 -- schema 7" and two lines both leading with the same word read as one
     * statement repeated rather than as a verdict and its evidence. The reader is being told
     * about the FORMAT of the file; the number is what the format is called.
     *
     * A DISTINCT WORD PER STATE, as {@link #verdictLine} argues at length -- and here the word
     * carries more than the colour could. `OLD GRAPH` and `OLD MOD` name WHICH ARTIFACT is
     * behind, which is the half the player acts on and the half a shared red would erase; the
     * numbers and the fix are on `detail()` under it, exactly as the pack check splits them.
     */
    static String schemaLine(GraphFacts.SchemaCheck schema) {
        if (schema == null) {
            return "format: UNCHECKED";
        }
        switch (schema.verdict()) {
            case MATCHES:
                return "format: OK";
            case BEHIND:
                return "format: OLD GRAPH -- it lacks what this build reads";
            case AHEAD:
                return "format: OLD MOD -- this build skips what it carries";
            default:
                return "format: UNCHECKED";
        }
    }

    /** Green, red, amber, on {@link #verdictColour}'s rules. */
    static int schemaColour(GraphFacts.SchemaCheck schema) {
        if (schema == null) {
            return NodeStatus.INK_WARN;
        }
        switch (schema.verdict()) {
            case MATCHES:
                return NodeStatus.INK_OK;
            case BEHIND:
            case AHEAD:
                return NodeStatus.INK_NEED;
            default:
                return NodeStatus.INK_WARN;
        }
    }

    static String pathLine(String path) {
        return "reading: " + (path == null || path.isEmpty() ? "(no file)" : path);
    }

    /**
     * Which game instance the dump was taken from.
     *
     * THE LINE THIS SCREEN EXISTS FOR. A player running two packs, or one pack reinstalled to
     * a new folder, compares this against the game they are in and knows immediately whether
     * the planner is answering for the right world.
     */
    static String instanceLine(GraphFacts facts) {
        String instance = facts == null ? "" : facts.instanceDir();
        if (instance.isEmpty()) {
            // AN OLD DUMP RECORDS NO INSTANCE, and saying so beats a blank line: it tells the
            // reader the check is unavailable rather than that the answer is "nowhere".
            return "dumped from: not recorded (pre-schema-5 dump)";
        }
        return "dumped from: " + instance;
    }

    /** The mod build that wrote the dump, and the format it wrote. */
    static String builtLine(GraphFacts facts) {
        if (facts == null) {
            return "";
        }
        String version = facts.dumpVersion();
        return "dumped by: " + (version.isEmpty() ? "unknown build" : version)
                + NodeRowText.SEPARATOR + "schema " + facts.dumpSchema();
    }

    /**
     * The headline fingerprint: recipes and categories.
     *
     * TWO NUMBERS ON THIS LINE AND TWO ON THE NEXT rather than the browser's four stat tiles.
     * A 388px panel has no room for tiles, and the pairing is not arbitrary -- recipes and
     * categories describe the RECIPE side, keys and names describe the ITEM side, and a reader
     * comparing two graphs looks at one side at a time.
     */
    static String sizeLine(GraphFacts facts) {
        if (facts == null) {
            return "";
        }
        return NodeRowText.grouped(facts.recipes()) + " recipes"
                + NodeRowText.SEPARATOR + NodeRowText.grouped(facts.categories()) + " categories";
    }

    static String namesLine(GraphFacts facts) {
        if (facts == null) {
            return "";
        }
        return NodeRowText.grouped(facts.keys()) + " items"
                + NodeRowText.SEPARATOR + NodeRowText.grouped(facts.namedKeys()) + " named"
                + NodeRowText.SEPARATOR + NodeRowText.grouped(facts.oreGroups()) + " oredict";
    }

    /**
     * One recipe source and its share.
     *
     * WHY THIS TABLE SURVIVED THE CUT when `stats_page`'s biggest-categories table did not: it
     * is the one part of the old Coverage page that speaks to whether the graph is TRUSTWORTHY
     * rather than merely large. `hei_dump` is the running game's own answer and `jar_json` is
     * a reader that cannot see CraftTweaker deletions (#227), so the split between them is a
     * statement about how much of this graph the game itself vouched for.
     */
    static PlannerWidgets.Group sourceRow(GraphFacts.Source source, int width) {
        PlannerWidgets.Group group = new PlannerWidgets.Group();
        group.size(width, ROW_HEIGHT);
        int nameWidth = Math.max(GAP, width - COUNT_WIDTH - GAP);
        group.child(PlannerWidgets.line(source.name(), nameWidth, NodeStatus.INK_MUTED)
                            .pos(0, 0));
        group.child(PlannerWidgets.line(NodeRowText.grouped(source.recipes()), COUNT_WIDTH,
                                        NodeStatus.INK_CRAFT).pos(width - COUNT_WIDTH, 0));
        return group;
    }
}
