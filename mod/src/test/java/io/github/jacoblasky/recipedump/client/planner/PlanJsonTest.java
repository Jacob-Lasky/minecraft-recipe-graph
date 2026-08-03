package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The plan reader, against all 19 frozen plan fixtures.
 *
 * These are real solved plans from the reference pack, so this is not a reader tested on
 * inputs written to suit it -- it is a reader tested on the contract #135 froze. When the
 * Java solver lands in #141 it produces this same shape, and anything the reader gets wrong
 * here it would get wrong there.
 */
public class PlanJsonTest {

    @Test
    public void everyFixtureParsesAndCarriesTheNodeCountItClaims() {
        List<String> names = PlanFixtures.names();
        // A directory scan that found nothing passes vacuously; assert the population first.
        //
        // COUNT PLAN TARGETS, NOT FILES, and the two numbers are both live so do not "fix"
        // one into the other. `PlanFixtures.names()` globs `plan-*`, giving 21; the
        // directory holds 24 entries, because `cost.json`, `machines.json` and
        // `machines-overridden.json` sit there too and are INPUTS rather than plans. A #170
        // review read the directory and reported "10 of 23 fixtures", which was true of
        // files and did not match this assertion -- so say which denominator this is.
        //
        // THIS NUMBER MOVES WHENEVER A TARGET IS ADDED, and that is the point rather than a
        // maintenance cost -- a fixture added without anyone noticing is one the port was
        // never held to. 19 at #135, 20 at #170's `plan-unsourced-variant`, 21 at #176's
        // `plan-unsourced-price`.
        assertEquals("21 plan fixtures; found " + names, 21, names.size());
        for (String name : names) {
            PlanView plan = PlanFixtures.load(name);
            assertNotNull(name + " has no tree", plan.tree());
            assertFalse(name + " has no target", plan.target().isEmpty());
            // `nodes` is the solver's own count. If the reader dropped a child, or read one
            // twice, this is where it shows -- across 856 nodes and 21 trees.
            assertEquals(name + " lost or gained nodes in the read",
                         plan.nodes(), plan.flatten().size());
        }
    }

    @Test
    public void everyStatusInEveryFixtureIsOneTheRendererKnows() {
        // The failure this prevents is the one `present.py`'s docstring describes: a status
        // nothing has an entry for renders as a blank badge, which looks like a layout bug.
        List<String> unknown = new ArrayList<String>();
        for (String name : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(name).flatten()) {
                if (!NodeStatus.knows(node.status())) {
                    unknown.add(name + ": " + node.status());
                }
            }
        }
        assertTrue("statuses with no entry in NodeStatus: " + unknown, unknown.isEmpty());
    }

    @Test
    public void everyNodeInEveryFixtureHasTheFieldsARowNeeds() {
        for (String name : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(name).flatten()) {
                String where = name + " node " + node.key();
                assertFalse(where + " has no key", node.key().isEmpty());
                assertFalse(where + " has no label", node.label().isEmpty());
                assertFalse(where + " has no kind", node.kind().isEmpty());
                assertTrue(where + " needs a non-positive quantity", node.need() > 0);
            }
        }
    }

    @Test
    public void aCraftedNodeCarriesItsRecipeAndAPlainOneDoesNot() {
        // The distinction the node menu turns on: "choose another recipe" is meaningless on
        // a leaf, and a null recipe is how a leaf says so.
        PlanNode root = PlanFixtures.load("plan-in-stock").tree();
        assertEquals(NodeStatus.CRAFT, root.status());
        assertNotNull(root.recipe());
        assertTrue(root.alternatives() > 1);

        PlanNode leaf = deepest(root);
        assertNull("a leaf has no recipe to choose between", leaf.recipe());
        assertEquals(0, leaf.alternatives());
    }

    @Test
    public void aTruncatedPlanSaysSoAndCountsWhatItCutAt() {
        PlanView plan = PlanFixtures.load("plan-truncated");
        assertTrue("plan-truncated is the fixture for exactly this", plan.truncated());
        // 49 before #172. Splitting the cycle term stopped the ranking entering cyclic
        // routes first, so the same node budget now buys three more real nodes. This is the
        // one fixture where a NODE BUDGET argument is valid at all: `max_nodes` is 40 here
        // and genuinely binds, where every other fixture runs at 4,000 and never reaches it.
        //
        // #176 MOVED NOTHING HERE, and that is worth recording because it nearly got the
        // credit for these three nodes. A comment claiming "the same node budget buys three
        // more REAL nodes now that the ranking stops spending it on routes the graph cannot
        // account for" was written against a pre-#172 base and, regenerated on top of #172,
        // measures 52 -> 52: same nodes, same work (60), same shopping list (19). What #176
        // does to this plan is swap ONE node -- `forestry:bee_drone_ge#438f0076b188`
        // "Radiant Drone", which carried `unsourced`, becomes `bee_princess_ge#438f0076b188`
        // -- and the mark goes from 1 to 0. Two changes had claimed the same three nodes by
        // different mechanisms; only #172's claim survived being measured.
        assertEquals(52, plan.nodes());
        assertTrue(plan.maxNodes() > 0);
    }

    @Test
    public void aCycleNodeIsALeafRatherThanAnInfiniteTree() {
        // The solver marks the second visit and stops. If the reader followed children here
        // it would not terminate, so this is a real property rather than a spot check.
        PlanView plan = PlanFixtures.load("plan-cycle");
        List<PlanNode> cycles = new ArrayList<PlanNode>();
        for (PlanNode node : plan.flatten()) {
            if (NodeStatus.CYCLE.equals(node.status())) {
                cycles.add(node);
            }
        }
        assertEquals(1, cycles.size());
        assertFalse("a cycle marker must not recurse", cycles.get(0).hasChildren());
    }

    @Test
    public void anOredictNodeCarriesWhatItResolvedTo() {
        PlanNode ore = null;
        for (PlanNode node : PlanFixtures.load("plan-same-name").flatten()) {
            if ("ore".equals(node.kind())) {
                ore = node;
                break;
            }
        }
        assertNotNull("plan-same-name routes through an oredict slot", ore);
        assertEquals(NodeStatus.OREDICT, ore.status());
        assertEquals("ore:ingotIron", ore.key());
        // The concrete key the solver picked. Without it the row says "any of" and never
        // says which one, which is the question the reader actually has.
        assertEquals("minecraft:iron_ingot", ore.resolvedTo());
    }

    @Test
    public void aDimensionGatedNodeNamesTheDimensionAndExplainsItself() {
        PlanNode root = PlanFixtures.load("plan-dimension-gate").tree();
        assertEquals("Sedna", root.dimension());
        assertEquals(NodeStatus.RAW, root.status());
        assertEquals("mined on Sedna, and you have not been there", root.note());
    }

    @Test
    public void aTokenNodeCarriesTheKindThatDecidesItsWording() {
        PlanNode root = PlanFixtures.load("plan-token-gate").tree();
        assertEquals(NodeStatus.TOKEN, root.status());
        assertEquals("loot", root.tokenKind());
    }

    /**
     * The in-game door: JSON text straight from the Java solver's serializer.
     *
     * Asserted to agree with the object door on the same bytes, because the two must not be
     * able to disagree -- the offline tests use one and the mod uses the other.
     */
    @Test
    public void aResultReadsTheSameFromTextAsFromAParsedObject() throws Exception {
        String text = readFixtureText("plan-truncated");
        com.google.gson.JsonObject result = new com.google.gson.JsonParser()
                .parse(text).getAsJsonObject().getAsJsonObject("result");
        PlanView fromObject = PlanJson.readResult(result);
        PlanView fromText = PlanJson.readResult(result.toString());
        assertEquals(fromObject.target(), fromText.target());
        assertEquals(fromObject.nodes(), fromText.nodes());
        assertEquals(fromObject.flatten().size(), fromText.flatten().size());
        assertEquals(fromObject.shoppingList().size(), fromText.shoppingList().size());
        assertEquals(fromObject.truncated(), fromText.truncated());
    }

    @Test
    public void aResultCarryingFieldsThePanelDoesNotDrawStillReads() {
        // The solver emits `work`, `pins_overruled`, `from_emc` and more that no widget shows.
        // Refusing a plan because it carried a field nobody draws would be the worse failure.
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.addProperty("key", "test:thing");
        tree.addProperty("label", "Thing");
        tree.addProperty("need", 1);
        tree.addProperty("status", NodeStatus.RAW);
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.add("tree", tree);
        result.addProperty("target", "test:thing");
        result.addProperty("work", 12345);
        result.addProperty("something_added_next_year", "surprise");
        result.add("pins_overruled", new com.google.gson.JsonObject());
        PlanView plan = PlanJson.readResult(result);
        assertEquals("test:thing", plan.target());
        assertEquals(1, plan.flatten().size());
    }

    private static String readFixtureText(String name) throws Exception {
        for (String prefix : new String[]{"../", "./"}) {
            java.io.File file = new java.io.File(prefix + "tests/fixtures/plan/" + name
                                                 + ".json");
            if (file.isFile()) {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                  java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("fixture " + name + " not found");
    }

    @Test
    public void theUnsourcedMarkIsReadBackFromTheFixturesThatCarryIt() {
        // #139 added the field and #147 regenerated the fixtures with it. The parser dropped
        // it silently until the review looked for a reader of `NodeStatus.UNSOURCED_BADGE`
        // and could not find one -- a mark the browser shows and the panel did not.
        int marked = 0;
        for (String name : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(name).flatten()) {
                if (node.unsourced()) {
                    marked++;
                    assertEquals("the mark refines a raw leaf and nothing else",
                                 NodeStatus.RAW, node.status());
                    assertEquals("no known source", NodeStatus.badge(node));
                }
            }
        }
        assertTrue("some fixture should carry the #139 mark, or this test proves nothing",
                   marked > 0);
    }

    @Test
    public void aFluidQuantityBiggerThanAnIntSurvivesTheRead() {
        // `need` is a long for this reason. The reference pack plans six and seven figures of
        // mB routinely, and an int field would have been a silent negative.
        long biggest = 0L;
        for (String name : PlanFixtures.names()) {
            for (PlanNode node : PlanFixtures.load(name).flatten()) {
                biggest = Math.max(biggest, node.need());
            }
        }
        assertTrue("the fixtures should contain a five-figure quantity at least, got "
                   + biggest, biggest > 10_000L);
    }

    @Test
    public void sixItemsCalledIronPlateStayDistinguishableByKey() {
        // 5,095 display names are shared on this pack. A panel that keyed anything by label
        // would merge them, and `plan-same-name` is the fixture that exists to catch it.
        PlanView plan = PlanFixtures.load("plan-same-name");
        java.util.Set<String> keys = new java.util.HashSet<String>();
        java.util.Set<String> labels = new java.util.HashSet<String>();
        for (PlanNode node : plan.flatten()) {
            keys.add(node.key());
            labels.add(node.label());
        }
        assertTrue("keys must not collapse the way labels do", keys.size() >= labels.size());
        assertEquals("thermalfoundation:material:32", plan.target());
        assertEquals("Iron Plate", plan.targetName());
    }

    @Test
    public void theMachinesToBuildListReadsBackWithItsReasons() {
        PlanView plan = PlanFixtures.load("plan-in-stock");
        // 4 before #172. The Packager dropped out: it was only on the list because the plan
        // routed through a cyclic recipe that needed it, and the ranking no longer picks
        // that route. A machine leaving this list is the plan getting SHORTER, not a loss.
        assertEquals(3, plan.machinesToBuild().size());
        PlanView.MachineRow first = plan.machinesToBuild().get(0);
        assertEquals("chisel.chiseling", first.category());
        assertEquals("Chiseling", first.machine());
        assertEquals("buildable", first.state());
        assertEquals("craftable: chisel:chisel_iron", first.why());
        assertEquals("buildable", first.stateLabel());
    }

    @Test
    public void theBiggestFixtureIsTheOneTheScrollPanelHasToSurvive() {
        // 634 nodes in one viewport. 347 before #172, 388 before #176. Named here so a future
        // change that makes the tree panel O(n^2) has something to fail against rather than
        // just feeling slow in game -- so when this number MOVES UP it is the test getting
        // harder, and the only wrong response is to stop asserting it.
        //
        // WHY IT GREW, MEASURED RATHER THAN REASONED. All 42 of this plan's `unsourced` nodes
        // were LEAVES before #176 -- dead ends resting on items the graph cannot explain.
        // Pricing them at UNSOURCED_COST made those routes lose, so the search continues into
        // routes it can account for: unsourced 42 -> 2, leaves 139 -> 243, `craft` 214 -> 347,
        // `raw` 131 -> 228. A bigger tree that says true things is the trade #176 makes.
        //
        // NOT A NODE-BUDGET EFFECT, and an earlier draft of this comment said it was. This
        // fixture runs at `max_nodes` 4,000 with `truncated` and `exhausted` both false on
        // BOTH sides, so no budget was ever binding and nothing is being reallocated -- the
        // tree simply stopped terminating early. The budget argument belongs to
        // `plan-truncated`, whose `max_nodes` is 40, and #176 does not move that one.
        //
        // WATCH `work`, NOT JUST THE NODE COUNT: it went 400 -> 28,012 against a work_budget
        // of 80,000, so this fixture now spends 35% of its search budget where it used to
        // spend 0.5%. Still passing, with much less headroom. If a later change flips
        // `exhausted` on any fixture, this is the one to look at first.
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        assertEquals(634, plan.flatten().size());
    }

    private static PlanNode deepest(PlanNode node) {
        PlanNode current = node;
        while (current.hasChildren()) {
            current = current.children().get(0);
        }
        return current;
    }
}
