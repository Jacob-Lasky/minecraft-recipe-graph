package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The in-game palette agrees with the browser's.
 *
 * WHY THIS IS WORTH A CROSS-LANGUAGE TEST rather than a comment saying "keep these in step".
 * Both surfaces exist until #19 Phase 6 retires the web UI, and they describe the same plan.
 * If the browser says "cut off" and the panel says "depth" for the same node, a reader has no
 * way to know they mean the same thing -- and the drift is invisible, because each side looks
 * self-consistent. This repository already reads a Python-side contract from Java for exactly
 * this reason (`DigestFixtureTest`); this is the same trick applied to wording.
 *
 * It reads `recipegraph/present.py` as TEXT rather than running Python. Crude, and the right
 * amount of machinery: the thing being guarded is that no status exists on one side and not
 * the other, and a regex over the constant list answers that without a subprocess, an
 * interpreter on the test image, or a generated file somebody has to remember to regenerate.
 */
public class NodeStatusTest {

    /** `STATUS_HAVE: ("in stock", "ok"),` and friends, from `present.STATUS_LABEL`. */
    private static final Pattern LABEL_ROW =
            Pattern.compile("STATUS_([A-Z]+):\\s*\\(\"([^\"]*)\"");

    /**
     * Just the `STATUS_LABEL` dict.
     *
     * SCOPED, because `STATUS_STYLE` two dicts below has rows of exactly the same shape --
     * `STATUS_HAVE: ("var(--okbg)", "var(--ok)")` -- and matching the whole file compares the
     * badge word against a CSS variable. Which is what it did on the first run.
     */
    private static final Pattern LABEL_DICT =
            Pattern.compile("(?s)STATUS_LABEL = \\{(.*?)\\n\\}");

    @Test
    public void everyStatusPresentPyDeclaresHasAnEntryHere() throws IOException {
        List<String> pythonStatuses = pythonStatusConstants();
        assertEquals("present.ALL_STATUSES should list ten statuses; found " + pythonStatuses,
                     10, pythonStatuses.size());
        List<String> missing = new ArrayList<String>();
        for (String status : pythonStatuses) {
            if (!NodeStatus.knows(status)) {
                missing.add(status);
            }
        }
        assertTrue("present.py has statuses this panel cannot render: " + missing,
                   missing.isEmpty());
    }

    @Test
    public void thisPanelInventsNoStatusPresentPyDoesNotHave() throws IOException {
        // The other direction, and the one that catches a well-meant addition here that the
        // browser will never show -- which reads to a user as the two tools disagreeing about
        // what happened to their plan.
        List<String> pythonStatuses = pythonStatusConstants();
        List<String> extra = new ArrayList<String>();
        for (String status : NodeStatus.all()) {
            if (!pythonStatuses.contains(status)) {
                extra.add(status);
            }
        }
        assertTrue("statuses this panel has and present.py does not: " + extra, extra.isEmpty());
    }

    @Test
    public void theBadgeWordsAreTheWordsPresentPyUses() throws IOException {
        // Not just that a status exists on both sides, but that it SAYS the same thing.
        Matcher dict = LABEL_DICT.matcher(readPresentPy());
        assertTrue("present.py should define STATUS_LABEL as one dict literal", dict.find());
        Matcher matcher = LABEL_ROW.matcher(dict.group(1));
        int checked = 0;
        while (matcher.find()) {
            String status = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String word = matcher.group(2);
            assertEquals("present.py badges `" + status + "` differently",
                         word, NodeStatus.badgeFor(status));
            checked++;
        }
        assertEquals("expected to read ten badge words out of present.py", 10, checked);
    }

    @Test
    public void theStatusOrderMatchesTheOrderPresentPyListsThemIn() throws IOException {
        // A legend that reshuffles between two plans is a legend nobody can scan.
        assertEquals(pythonStatusConstants(), NodeStatus.all());
    }

    @Test
    public void aTokenKindRefinesTheWordAndLeavesTheColourAlone() {
        // `present.status_badge`'s rule: badging a quest gate "go get" would send someone
        // hunting for an item that unlocks by playing the story.
        PlanNode gate = token("gate");
        assertEquals("locked", NodeStatus.badge(gate));
        assertEquals("a refinement changes the word, never the colour",
                     NodeStatus.colourFor(NodeStatus.TOKEN), NodeStatus.colour(gate));
        assertEquals("go get", NodeStatus.badge(token("loot")));
        assertEquals("any of class", NodeStatus.badge(token("hint")));
        assertEquals("mechanic", NodeStatus.badge(token("method")));
        // An unrecognised kind falls back to the generic word rather than to nothing.
        assertEquals("go get", NodeStatus.badge(token("something-new")));
        assertEquals("go get", NodeStatus.badge(token(null)));
    }

    @Test
    public void everyTokenKindTokensPyDeclaresIsRefinedHere() throws IOException {
        String tokens = read(new File(repoRoot(), "recipegraph/tokens.py"));
        Matcher matcher = Pattern.compile("(?s)KIND_BADGE = \\{(.*?)\\}").matcher(tokens);
        assertTrue("tokens.py should define KIND_BADGE", matcher.find());
        Matcher rows = Pattern.compile("([A-Z]+):\\s*\"([^\"]*)\"").matcher(matcher.group(1));
        int checked = 0;
        while (rows.find()) {
            String kind = rows.group(1).toLowerCase(java.util.Locale.ROOT);
            assertEquals("tokens.py badges the `" + kind + "` kind differently",
                         rows.group(2), NodeStatus.badge(token(kind)));
            checked++;
        }
        assertEquals("tokens.KIND_BADGE should have four kinds", 4, checked);
    }

    /**
     * #139's mark: the graph can make it, but not in the state that was asked for.
     *
     * The constant existed and nothing read it, which the review caught -- and by then the
     * fixtures had gained the field, so the panel was silently dropping a mark the browser
     * shows. That is the exact drift `NodeStatusTest` exists to prevent, missed because the
     * completeness tests check STATUSES and this is a flag.
     */
    @Test
    public void anUnsourcedLeafSaysSoAndKeepsTheNeedColour() {
        PlanNode plain = node(NodeStatus.RAW, null, false);
        PlanNode unsourced = node(NodeStatus.RAW, null, true);
        assertEquals("NEED", NodeStatus.badge(plain));
        assertEquals("no known source", NodeStatus.badge(unsourced));
        assertEquals("the claim is the same kind of claim, so the colour does not move",
                     NodeStatus.colour(plain), NodeStatus.colour(unsourced));
    }

    @Test
    public void aTokenKindOutranksTheUnsourcedMark() {
        // `present.status_badge`'s documented order. The combination cannot occur -- `expand`
        // returns at the token branch first -- but a caller that passes both must get one
        // answer, and "locked" is the more specific instruction.
        PlanNode both = node(NodeStatus.TOKEN, "gate", true);
        assertEquals("locked", NodeStatus.badge(both));
    }

    @Test
    public void theUnsourcedWordMatchesPresentPysConstant() throws IOException {
        Matcher matcher = Pattern.compile("UNSOURCED_BADGE = \"([^\"]*)\"")
                                 .matcher(readPresentPy());
        assertTrue("present.py should define UNSOURCED_BADGE", matcher.find());
        assertEquals(matcher.group(1), NodeStatus.UNSOURCED_BADGE);
    }

    @Test
    public void anUnknownStatusPrintsItselfRatherThanNothing() {
        // A blank badge is indistinguishable from a rendering bug. This cannot happen while
        // the completeness tests above pass, which is exactly why the fallback has to be
        // something a reader could act on if it ever did.
        PlanNode odd = node("something-the-solver-learned-later", null);
        assertEquals("something-the-solver-learned-later", NodeStatus.badge(odd));
        assertEquals(NodeStatus.INK_MUTED, NodeStatus.colour(odd));
    }

    @Test
    public void everyColourIsFullyOpaque() {
        // Minecraft's font renderer reads a zero alpha as fully transparent, so a bare
        // 0xRRGGBB draws nothing -- and an invisible label looks like a layout failure.
        for (String status : NodeStatus.all()) {
            int argb = NodeStatus.colourFor(status);
            assertEquals(status + " needs a 0xFF alpha or it draws nothing",
                         0xFF, (argb >>> 24) & 0xFF);
        }
    }

    @Test
    public void aMachineYouHaveIsNotARoadblockAndAnUnidentifiedOneIs() {
        assertFalse(NodeStatus.isRoadblock("have"));
        assertFalse("hand crafting carries no state", NodeStatus.isRoadblock(null));
        assertFalse(NodeStatus.isRoadblock(""));
        assertTrue(NodeStatus.isRoadblock("buildable"));
        assertTrue(NodeStatus.isRoadblock("unavailable"));
        // `unknown` counts. Treating "this tool could not identify it" as fine would hide a
        // real wall behind a tooling gap.
        assertTrue(NodeStatus.isRoadblock("unknown"));
    }

    @Test
    public void unidentifiedIsWordedForAReaderRatherThanForTheSolver() {
        assertEquals("unidentified", NodeStatus.machineStateLabel("unknown"));
        assertEquals("no route", NodeStatus.machineStateLabel("unavailable"));
        assertEquals("buildable", NodeStatus.machineStateLabel("buildable"));
        assertEquals("have", NodeStatus.machineStateLabel("have"));
    }

    @Test
    public void aLegendListsOnlyWhatTheTreeContainsAndInTheFixedOrder() {
        PlanView plan = PlanFixtures.load("plan-cycle");
        List<String> legend = NodeStatus.legendFor(plan.tree());
        assertFalse(legend.isEmpty());
        assertTrue("plan-cycle contains a loop marker", legend.contains(NodeStatus.CYCLE));

        // THE `ONLY` HALF, which nothing here asserted. `legendFor` returning the whole of
        // `NodeStatus.all()` -- the legend a player reads as "this plan has an EMC route and
        // a locked token in it" when it has neither -- satisfied the containment check and
        // the ordering check both, because `all()` is in canonical order by construction.
        // Derived from the tree rather than written out, so a regenerated fixture cannot
        // quietly make it agree.
        java.util.Set<String> inTree = new java.util.LinkedHashSet<String>();
        collectStatuses(plan.tree(), inTree);
        assertEquals(inTree, new java.util.LinkedHashSet<String>(legend));
        assertEquals("a legend must not repeat a status", inTree.size(), legend.size());

        // And that the fixture discriminates: `plan-cycle` has to be MISSING statuses, or
        // "only what the tree contains" and "everything there is" are the same list.
        assertTrue("plan-cycle must not contain every status, or this test is vacuous",
                   inTree.size() < NodeStatus.all().size());
        // Measured out of the fixture, not guessed: `plan-cycle`'s tree holds craft, cycle,
        // oredict, raw and source. `source` IS in it, which is why it is not on this list.
        for (String absent : new String[] {NodeStatus.EMC, NodeStatus.TOKEN,
                                           NodeStatus.HAVE, NodeStatus.PARTIAL,
                                           NodeStatus.DEPTH}) {
            assertFalse("plan-cycle was expected not to contain " + absent
                        + "; pick another fixture or another status",
                        legend.contains(absent));
        }

        // Order follows the canonical list, not the order of first appearance in the tree.
        List<String> canonical = NodeStatus.all();
        int last = -1;
        for (String status : legend) {
            int at = canonical.indexOf(status);
            assertTrue("legend is out of canonical order at " + status, at > last);
            last = at;
        }
    }

    /**
     * The tree's own statuses, so the legend is compared against its input.
     *
     * DELIBERATELY A SECOND COPY OF `NodeStatus`'s PRIVATE WALK. DO NOT replace this with a
     * call into `NodeStatus` -- there is no public accessor, and exposing one so this could
     * share the production walk is the change that makes the comparison agree with itself: a
     * walk that missed a subtree would then miss it on both sides and the legend would match
     * a set that is wrong in the same way. The duplication IS the independence.
     */
    private static void collectStatuses(PlanNode node, java.util.Set<String> into) {
        into.add(node.status());
        for (PlanNode child : node.children()) {
            collectStatuses(child, into);
        }
    }

    private static PlanNode token(String kind) {
        return node(NodeStatus.TOKEN, kind);
    }

    private static PlanNode node(String status, String tokenKind) {
        return node(status, tokenKind, false);
    }

    private static PlanNode node(String status, String tokenKind, boolean unsourced) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "test:thing");
        json.addProperty("label", "Thing");
        json.addProperty("need", 1);
        json.addProperty("status", status);
        if (tokenKind != null) {
            json.addProperty("token_kind", tokenKind);
        }
        if (unsourced) {
            json.addProperty("unsourced", true);
        }
        return PlanJson.readNode(json);
    }

    /** The statuses `present.ALL_STATUSES` names, in its order, lower-cased. */
    private static List<String> pythonStatusConstants() throws IOException {
        String source = readPresentPy();
        Matcher matcher = Pattern.compile("(?s)ALL_STATUSES = \\((.*?)\\)").matcher(source);
        assertTrue("present.py should define ALL_STATUSES", matcher.find());
        List<String> statuses = new ArrayList<String>();
        Matcher names = Pattern.compile("STATUS_([A-Z]+)").matcher(matcher.group(1));
        while (names.find()) {
            statuses.add(names.group(1).toLowerCase(java.util.Locale.ROOT));
        }
        return statuses;
    }

    private static String readPresentPy() throws IOException {
        return read(new File(repoRoot(), "recipegraph/present.py"));
    }

    private static String read(File file) throws IOException {
        assertTrue("expected " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** `..` then `.`, for the same reason `PlanFixtures` does it. */
    private static File repoRoot() {
        for (String prefix : Arrays.asList("../", "./")) {
            if (new File(prefix + "recipegraph/present.py").isFile()) {
                return new File(prefix);
            }
        }
        throw new IllegalStateException("recipegraph/ not found -- mount the repository root");
    }
}
