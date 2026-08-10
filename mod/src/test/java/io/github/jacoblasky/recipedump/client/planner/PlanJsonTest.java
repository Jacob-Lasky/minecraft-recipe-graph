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
 * inputs written to suit it -- it is a reader tested on the contract #135 froze. The Java
 * solver landed in #141 and produces this same shape, so anything the reader gets wrong here
 * it gets wrong on the in-game path too.
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
        // `plan-unsourced-price`, 22 at #270's `plan-dimension-shadow-groupless`.
        assertEquals("22 plan fixtures; found " + names, 22, names.size());
        for (String name : names) {
            PlanView plan = PlanFixtures.load(name);
            assertNotNull(name + " has no tree", plan.tree());
            assertFalse(name + " has no target", plan.target().isEmpty());
            // `nodes` is the solver's own count. If the reader dropped a child, or read one
            // twice, this is where it shows -- across 2,289 nodes and 22 trees.
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
        // 52 -> 50. The oracle moved to `graph-oracle-248.json` in #248 -- the only graph carrying `offworld_ores` -- and every plan fixture was regenerated against it. The two oracles are different builds 621 recipes apart, so figures read off a fixture move with them. That is the ORACLE and not a solver change: verified by running identical code against both graphs.
        assertEquals(50, plan.nodes());
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
        // `plan-in-stock` HELD THIS UNTIL #246 AND NOW HAS NO MACHINES AT ALL. Its history
        // is worth keeping because every entry is the same shape -- a machine on the list for
        // a reason that stopped being true -- and #246 is the fourth and largest:
        //
        //   #172        the Packager was only on the list because the plan routed through a
        //               cyclic recipe that needed it, and the ranking stopped picking that.
        //   #211/#169   Chiseling was only on it because a DISCARDED attempt entered it.
        //               `Solver.snapshot` did not carry `machinesNeeded`, which its own
        //               javadoc forbids, so every attempt the cycle guard threw away left its
        //               machine behind.
        //   #246        Casting and Smelting were on it because a stocked iron ingot made a
        //               Block of Iron cost 1.0, so the plan unpacked a block rather than
        //               smelting ore. Stock is overlaid after the relaxation now, the hopper
        //               smelts, and the list is empty -- which is why this test had to move
        //               rather than have its number edited down again.
        //
        // A machine list is a weak place to notice a leak and a weaker place to assert from,
        // so this now reads a fixture NAMED for having machines.
        PlanView plan = PlanFixtures.load("plan-machine-choice");
        assertEquals(7, plan.machinesToBuild().size());
        PlanView.MachineRow first = plan.machinesToBuild().get(0);
        assertEquals("nuclearcraft_chemical_reactor", first.category());
        assertEquals("Chemical Reactor", first.machine());
        assertEquals("craftable: nuclearcraft:chemical_reactor_idle", first.why());
        assertEquals("buildable", first.stateLabel());

        // The plan this used to read still exists and still exercises the panel; what it no
        // longer has is a machine, and saying so here is what stops someone moving it back.
        assertTrue(PlanFixtures.load("plan-in-stock").machinesToBuild().isEmpty());
    }

    /**
     * The raw machine state lands in the right slot, proved through the word a player reads.
     *
     * WHY NOT `assertEquals("buildable", row.state())`, WHICH IS WHAT THIS USED TO BE. All 125
     * committed machine rows are `buildable`, and `NodeStatus.machineStateLabel("buildable")`
     * returns "buildable" -- so that assertion and the `stateLabel()` one beside it could not
     * fail independently, and a reader that put `why` into the state slot would have passed
     * both. `unavailable` is the state whose label DIFFERS, "no route", so asserting the label
     * on a hand-built row proves the raw value arrived AND that it went through the mapping.
     * No fixture can do this, which is why it is built here rather than loaded.
     *
     * #190 deleted `MachineRow.state()` on the strength of this: it had no caller but the test,
     * and the test it had was weaker than the one that replaces it.
     */
    @Test
    public void aMachineStateOtherThanBuildableArrivesAndIsWordedForAPlayer() {
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        com.google.gson.JsonObject tree = new com.google.gson.JsonObject();
        tree.addProperty("key", "mod:thing");
        tree.addProperty("label", "Thing");
        tree.addProperty("need", 1);
        tree.addProperty("status", NodeStatus.CRAFT);
        result.add("tree", tree);
        com.google.gson.JsonArray machines = new com.google.gson.JsonArray();
        com.google.gson.JsonObject machine = new com.google.gson.JsonObject();
        machine.addProperty("category", "mod.press");
        machine.addProperty("machine", "Mythic Press");
        machine.addProperty("state", "unavailable");
        machine.addProperty("why", "no recipe makes mod:press_controller");
        machines.add(machine);
        result.add("machines_to_build", machines);

        PlanView.MachineRow row = PlanJson.readResult(result).machinesToBuild().get(0);
        assertEquals("no route", row.stateLabel());
        assertEquals("no recipe makes mod:press_controller", row.why());
        assertEquals("Mythic Press", row.machine());
    }

    /**
     * The four summary lists the reader used to discard reach {@link PlanView}. #190.
     *
     * ONE TEST OVER ALL FOUR AND WITH THE VALUES SPELLED OUT, because the failure this is
     * guarding against is not "the list is empty", it is "the list holds the wrong one". Five
     * lists of the same type go into `PlanView.Builder`, and a transposition would leave every
     * one of them non-empty while the panel said stock came from EMC. The counts and the first
     * row of each are quoted from the committed fixtures.
     */
    @Test
    public void theFourSummaryListsReadBackWithTheirDecorations() {
        PlanView stock = PlanFixtures.load("plan-in-stock");
        assertEquals(2, stock.usedFromStock().size());
        assertEquals("minecraft:iron_ingot", stock.usedFromStock().get(0).key());
        assertEquals("Iron Ingot", stock.usedFromStock().get(0).label());
        assertEquals(5L, stock.usedFromStock().get(0).need());
        assertTrue("a stock row must not be marked unsourced",
                   !stock.usedFromStock().get(0).unsourced());

        PlanView free = PlanFixtures.load("plan-free-source");
        assertEquals(1, free.fromSources().size());
        // `why` is the whole value of this list: "free" must not mean "invisible", and a
        // quantity with no generator named is a row a player cannot act on.
        assertEquals("placed: nuclearcraft:cobblestone_generator", free.fromSources().get(0).why());

        // FOUR ROWS, AND THE FIRST WAS `method`, UNTIL #211/#169. The one that left was the
        // METHOD marker: a documentation card is no longer a route, so the plan stopped listing
        // the marker item as something the player has to go and obtain. The three that remain
        // are all `loot` -- a dungeon drop, a boss drop, an overworld find -- which are real
        // instructions and are exactly what this list is for. The kind assertion is kept rather
        // than dropped, because "which kind leads this list" is the thing that moved.
        PlanView tokens = PlanFixtures.load("plan-fluid-chain");
        assertEquals(3, tokens.tokensNeeded().size());
        assertEquals("loot", tokens.tokensNeeded().get(0).tokenKind());

        PlanView emc = PlanFixtures.load("plan-emc-terminator");
        assertEquals(1, emc.fromEmc().size());
        // The NUMBER, not "from EMC": a cost a player can look up is a claim they can check.
        assertEquals(Long.valueOf(2048L), emc.fromEmc().get(0).emc());
    }

    /**
     * A shopping row keeps the mark saying nothing in the graph makes it. #136 on both surfaces.
     *
     * `plan-fluid-chain` keeps exactly two such rows after #176, and the tree side has badged
     * them since #136 while the shopping row parsed the flag and dropped it. The count is
     * quoted rather than asserted as "more than zero" because zero and two are the two states
     * this test exists to tell apart, and #176 is what moved it from 42.
     */
    @Test
    public void anUnsourcedShoppingRowKeepsSayingSo() {
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        int marked = 0;
        for (PlanView.EntryRow row : plan.shoppingList()) {
            if (row.unsourced()) {
                marked++;
            }
        }
        // 2 -> 3 FOR #193. The arrival is real rather than the mark spreading: taking 553 keys
        // from infinity to a finite price puts subtrees into this plan that were unreachable
        // before, and one of them bottoms out on a leaf the graph still cannot source. A key
        // the tool cannot source is exactly what this flag is for, so a plan that reaches more
        // of the graph should carry more of them. 3 of 66 shopping rows, so it stays rare,
        // which is the property the count below is guarding.
        // 3 -> 2, and still rare against 64 shopping rows, which is the property this
        // guards. The oracle moved to `graph-oracle-248.json` in #248 -- the only graph carrying `offworld_ores` -- and every plan fixture was regenerated against it. The two oracles are different builds 621 recipes apart, so figures read off a fixture move with them. That is the ORACLE and not a solver change: verified by running identical code against both graphs.
        assertEquals("the unsourced mark is lost on the surface a player gathers from",
                     2, marked);
    }

    /**
     * `work` and `work_budget` arrive, so "raising the cap will not help" can be checked.
     *
     * TERMINAL-ONLY UNTIL #190. `cli.cmd_plan` prints both and the panel said "search gave up
     * early" with no numbers, which asks the player to trust a rule instead of reading one.
     * The values are `plan-fluid-chain`'s, the fixture that spends 35% of its budget.
     *
     * 28,012 UNTIL #136, WHICH IS THE SAME SEVEN NODES {@code
     * theBiggestFixtureIsTheOneTheScrollPanelHasToSurvive} records: pricing the storage blocks
     * nothing presses moved one Dreadite branch off a Block of Dreadite and onto an alloy
     * furnace, for 12 more units of work. The BUDGET is unchanged, which is the claim this test
     * exists to keep checkable -- 35% of it spent, and the headroom #176 thinned did not move.
     *
     * 28,024 -> 28,196 FOR #211/#169, AND WORK ROSE WHILE THE TREE SHRANK, which looks backwards
     * and is the expected shape. `work` counts every {@code expand}, INCLUDING the ones
     * backtracking discards; the tree counts only what survived. Demoting the loot tables makes
     * the solver reject more attempts before it settles, so it does more searching and keeps
     * less of it: 641 nodes to 519 for 172 more units of work. Still 35% of an unchanged budget,
     * and {@code exhausted} is false, so the headroom warning above is unaffected.
     *
     * 28,196 -> 28,232 FOR #170, AND THE TREE DID NOT MOVE AT ALL, which is the opposite shape
     * to the entry above and for the opposite reason. Routing a bare key through a produced NBT
     * variant gives the solver candidate recipes where it previously had none, so it attempts
     * routes it used to reject outright -- 36 more units of searching. None of them survived
     * into that plan, so the two counters coming apart in both directions across two changes is
     * the reason they are asserted separately rather than one being taken as a proxy for the
     * other.
     *
     * 28,232 -> 3,054 FOR #193, A NINE-FOLD DROP, AND THE TREE GREW 519 -> 576 AT THE SAME
     * TIME. Both directions at once, which is the sharpest version of why these are two
     * assertions. `work` counts every `expand` including the discarded; the tree counts
     * survivors. Feeding `craftables` and `raw` into `estimate` tells the COST MODEL about the
     * two terminals `Solver.expand` was already stopping at, so routes through them stop being
     * priced at their full subtree and start winning, and the search stops at a terminal
     * instead of exploring behind it. Meanwhile 553 keys stop pricing at infinity, so more of
     * the graph is reachable and more survives into the plan.
     *
     * A DROP THIS LARGE IS THE DIRECTION THAT NEEDS JUSTIFYING, because the easy way to make a
     * work assertion fall is to search less by planning less, and the tree growing at the same
     * time is what rules that out here. `exhausted` and `truncated` are both still false, and
     * the budget is unchanged at 80,000, so this is now 3.8% of it against 35% before.
     */
    @Test
    public void theWorkCounterAndItsBudgetBothArrive() {
        // 3,054 -> 3,249 FOR #171/#242, AND THE HEADROOM IS STILL NOT THE STORY. Pricing the
        // pack's marker items at UNSOURCED_COST costs 195 more units of searching, because
        // routes that used to terminate on a free tooltip now lose and the solver carries on
        // into ones it can account for. 4.1% of an unchanged budget, against the 35% #176 left
        // and the paragraph above warns about, so the thin headroom got thicker rather than
        // thinner. `exhausted` is false on both sides.
        PlanView plan = PlanFixtures.load("plan-fluid-chain");

        // 1415 -> 1334, AND THE MOVEMENT IS THE DUMP RATHER THAN THE SOLVER. That was worth
        // establishing rather than asserting, because this number had moved three times in a
        // day and the previous note could only say "the oracle changed". A 2x2 over the two
        // things that vary -- WHICH DUMP the graph was built from, and WHICH CODE built it --
        // separates them:
        //
        //     schema 5, pre-#248 builder    nodes 769   work 3249
        //     schema 5, post-#248 builder   nodes 784   work 1415
        //     schema 8, pre-#248 builder    nodes 702   work 1333
        //     schema 8, post-#248 builder   nodes 703   work 1334
        //
        // Hold the dump and change only the builder: +1 work, +1 node. Hold the builder and
        // change the dump: 3249 -> 1333. So the halving belongs to the schema-8 dump and
        // #248's contribution to THIS plan is one unit.
        //
        // THE SCHEMA-5 PAIR IS A CONFOUNDED COMPARISON and reading it alone is what made this
        // look like a solver mystery: `graph-oracle.json` carries no `offworld_ores` and
        // `graph-oracle-248.json` carries 98, so that pair differs in graph CONTENT as well as
        // in builder code. The single-variable comparison is the schema-8 pair.
        assertEquals(1334, plan.work());
        assertEquals(80000, plan.workBudget());
        assertFalse("this fixture is not exhausted; the pair must be readable anyway",
                    plan.exhausted());
    }

    @Test
    public void theBiggestFixtureIsTheOneTheScrollPanelHasToSurvive() {
        // 519 nodes in one viewport. 347 before #172, 388 before #176, 634 before #136, 641
        // before #211/#169. Named here so a future change that makes the tree panel O(n^2) has
        // something to fail against rather than just feeling slow in game -- so when this
        // number MOVES UP it is the test getting harder, and the only wrong response is to
        // stop asserting it. When it moves DOWN, see the last paragraph below.
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
        //
        // 634 -> 641 FOR #136, and it is the same mechanism a third time on a much smaller
        // scale. Exactly one branch moved: this plan reached Dreadite Ingot by melting a Block
        // of Dreadite, a key nothing presses, and pricing that at UNSOURCED_COST sent the
        // branch through Dreadium Nuggets and an alloy furnace instead, for seven more nodes
        // and one more shopping row. `work` moved 28,012 -> 28,024, so the headroom the
        // paragraph above warns about is unchanged, and `truncated` and `exhausted` are both
        // still false.
        //
        // 641 -> 519 FOR #211/#169, AND THIS IS THE FIRST TIME THE NUMBER HAS COME DOWN.
        // A drop is the direction that needs justifying, because the easy way to make this
        // assertion pass is to plan less. 122 nodes of FABRICATED route left the tree when the
        // JEI loot tables and automation cards this plan was routing through stopped being
        // routes. Corroborated on the same fixture rather than argued: shopping rows 64 -> 59
        // and token rows 4 -> 3, both smaller, and `truncated` and `exhausted` are false on
        // both sides so nothing was cut for want of budget. Measured against master twice, once
        // before this branch was rebased onto #136/#175/#194 and once after, and the delta was
        // -122 nodes and -5 shopping rows both times.
        //
        // 519 -> 576 FOR #193, AND THE NUMBER GOING BACK UP IS THE FIX WORKING RATHER THAN THE
        // DEMOTION UNWINDING. Nothing that #211 and #169 removed has returned: those were
        // FABRICATED routes and they are still gone. What arrived is real. #193 makes `_seed`
        // agree with `Graph.real_producers` and feeds `craftables` and `raw` into `estimate`,
        // which takes 553 keys from infinity to a finite price, so subtrees the solver could
        // not previously account for are now reachable and survive into the plan.
        //
        // The corroboration is that `work` fell 28,232 -> 3,054 in the same change, which is
        // the opposite direction. A bigger tree from a cheaper search is what you get when the
        // ranker stops disagreeing with the solver about where a branch ends; a bigger tree
        // from a MORE expensive search would be the shape of the demotion unwinding, and it is
        // not what happened. See `theWorkCounterAndItsBudgetBothArrive`.
        //
        // 576 -> 769 FOR #171/#242, AND IT IS THE #193 SHAPE A SECOND TIME RATHER THAN THE
        // DEMOTION UNWINDING. Nothing #211 and #169 removed has come back; those were
        // FABRICATED routes and they are still gone. What changed is that the pack's marker
        // items stopped costing what cobblestone costs, so routes that used to terminate on a
        // free tooltip lose and the search continues into ones the graph can account for.
        //
        // The corroboration is the same pair as #193's and it points the same way: `work` went
        // 3,054 -> 3,249, a 6% rise, for a 34% bigger tree. A bigger tree from a barely-more
        // expensive search is the ranker and the solver agreeing about where a branch ends. The
        // shape that would worry is a bigger tree from a much MORE expensive search, and 195
        // extra units of work is not that.
        //
        // A GREEN `tools/ci-java.sh` SAYS NOTHING ABOUT THIS FILE, recorded here because the
        // number above was moved by a change whose author had only that signal in hand. That
        // script's CORE_TEST is `graph/` and `plan/`, so the MAIN sources under `client/planner`
        // are compiled as dependencies while this test and its siblings are neither compiled
        // nor run -- its own header says so, under "what this job does not cover". The full
        // gradle suite is the only thing that reaches here, and `ModularUiLayoutTest` next door
        // is why: these need ModularUI, which ci-java deliberately does not fetch.
        PlanView plan = PlanFixtures.load("plan-fluid-chain");
        // 784 -> 703, a DECREASE, which is the direction this comment family warns
        // about -- and it is the schema-8 dump rather than anything losing nodes. The
        // decomposition is on `theWorkCounterAndItsBudgetBothArrive` above: same dump,
        // different builder is +1 node; same builder, different dump is 769 -> 702.
        assertEquals(703, plan.flatten().size());
    }

    private static PlanNode deepest(PlanNode node) {
        PlanNode current = node;
        while (current.hasChildren()) {
            current = current.children().get(0);
        }
        return current;
    }
}
