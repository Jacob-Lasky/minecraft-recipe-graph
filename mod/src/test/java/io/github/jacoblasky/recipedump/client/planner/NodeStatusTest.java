package io.github.jacoblasky.recipedump.client.planner;

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
        // Order follows the canonical list, not the order of first appearance in the tree.
        List<String> canonical = NodeStatus.all();
        int last = -1;
        for (String status : legend) {
            int at = canonical.indexOf(status);
            assertTrue("legend is out of canonical order at " + status, at > last);
            last = at;
        }
    }

    private static PlanNode token(String kind) {
        return node(NodeStatus.TOKEN, kind);
    }

    private static PlanNode node(String status, String tokenKind) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("key", "test:thing");
        json.addProperty("label", "Thing");
        json.addProperty("need", 1);
        json.addProperty("status", status);
        if (tokenKind != null) {
            json.addProperty("token_kind", tokenKind);
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
