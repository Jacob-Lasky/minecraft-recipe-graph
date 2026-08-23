package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static io.github.jacoblasky.recipedump.common.ae2.Snapshots.NO_MACHINES;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.common.PlannerService;
import io.github.jacoblasky.recipedump.common.ScenarioSource;
import io.github.jacoblasky.recipedump.common.ae2.Snapshots;
import io.github.jacoblasky.recipedump.common.ae2.StockSnapshot;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import io.github.jacoblasky.recipedump.plan.MachineInfo;
import io.github.jacoblasky.recipedump.plan.ScenarioInputs;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The join #191 was about: a snapshot the client holds becoming the `have` a plan is priced
 * against, and a plan waiting for the read rather than guessing ahead of it.
 *
 * WHAT THIS CAN REACH WITHOUT A CLIENT. There is no `Minecraft` here, so `canAsk` is false and
 * `planWhenRead` runs straight through -- which is the disconnected path, and worth asserting
 * because it is also the screenshot harness's path. The WAITING path is reached through
 * {@link PlannerStock#hold}, which is the same decision with the ask separated out, and the
 * reply through {@link PlannerStock#accept}, which is what the proxy's scheduled task calls.
 *
 * What no test here can prove is that the proxy installs the reader and schedules the reply at
 * all; `SeamInstallationTest` makes that claim about the seam, and it is the claim whose
 * absence let this whole path ship dead.
 */
public class PlannerStockTest {

    @Before
    @After
    public void isolate() {
        // All three are static, so a case that installs a reader or leaves a snapshot behind
        // would decide the next one's answer.
        LiveStock.forget();
        PlannerStock.forgetWaiting();
        ScenarioSource.resetReaders();
    }

    // -- what a held snapshot means for a plan -------------------------------------------------

    @Test
    public void nobodyHasAskedIsAReasonThatSaysWhatToDo() {
        ScenarioSource.Status status = PlannerStock.statusOf(null);
        assertFalse(status.live());
        assertTrue(status.note(), status.note().contains("own nothing"));
        assertTrue("a caveat has to name an action; this is the string #191 removed from"
                   + " ScenarioSource for failing that", status.note().contains("open the"));
        assertEquals("nothing was read, so nothing may be priced against it",
                     null, status.contents());
    }

    @Test
    public void aRefusalIsReportedAsTheREASONRatherThanAsNotReadYet() {
        // The point of the whole seam. "Out of range" tells the player to walk to their base;
        // "not read yet" tells them nothing, and the two are indistinguishable to a planner
        // that only knows whether a snapshot exists.
        ScenarioSource.Status status = PlannerStock.statusOf(
                StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        assertFalse(status.live());
        assertEquals(StockSnapshot.Reason.OUT_OF_RANGE.message(), status.note());
        assertEquals(null, status.contents());
    }

    @Test
    public void aReadNetworkBecomesTheHaveFieldAndTheCaveatGoes() {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("minecraft:iron_ingot", Long.valueOf(377L));
        LiveStock.accept(StockSnapshot.of(counts, NO_MACHINES));
        PlannerStock.install();

        assertTrue(ScenarioSource.HAVE.live());
        assertEquals("", ScenarioSource.HAVE.note());
        JsonObject have = PlannerService.liveScenario().getAsJsonObject("have");
        assertEquals(377L, have.get("minecraft:iron_ingot").getAsLong());
    }

    /**
     * THE BUG THIS WHOLE ISSUE IS ABOUT, asserted from the other side.
     *
     * Before #191 the snapshot arrived, was stored, and `liveScenario` emitted an empty `have`
     * anyway -- so the plan said "go and get 377 iron ingots" about ingots sitting in the ME
     * system, with a caveat underneath saying stock had not been read. Both halves were
     * individually correct.
     */
    @Test
    public void anEmptyHaveIsNotWhatAReadNetworkProduces() {
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:iron_ingot", Long.valueOf(377L)), NO_MACHINES));
        PlannerStock.install();
        assertFalse("the read landed and the scenario still claims the player owns nothing",
                    PlannerService.liveScenario().getAsJsonObject("have").entrySet().isEmpty());
    }

    @Test
    public void anEmptyNetworkIsStillARead() {
        // An available snapshot may legitimately be empty, and that is a different statement
        // from every refusal: the plan is correct and carries no caveat.
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(), NO_MACHINES));
        PlannerStock.install();
        assertTrue(ScenarioSource.HAVE.live());
        assertTrue(PlannerService.liveScenario().getAsJsonObject("have").entrySet().isEmpty());
    }

    @Test
    public void aRefusalDoesNotSilentlyPriceAPlanAgainstNothing() {
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL));
        PlannerStock.install();
        assertFalse(ScenarioSource.HAVE.live());
        assertTrue(ScenarioSource.summary(),
                   ScenarioSource.summary().contains(ScenarioSource.HAVE.field()));
        assertTrue(ScenarioSource.missingNotes()
                                 .contains(StockSnapshot.Reason.NO_TERMINAL.message()));
    }

    @Test
    public void theReaderFollowsTheHeldSnapshotRatherThanTheOneItWasInstalledWith() {
        // A grid can go out of range between two plans, so the reader has to look each time.
        PlannerStock.install();
        assertFalse(ScenarioSource.HAVE.live());
        LiveStock.accept(StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)), NO_MACHINES));
        assertTrue(ScenarioSource.HAVE.live());
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        assertFalse(ScenarioSource.HAVE.live());
    }

    // -- waiting for the read ------------------------------------------------------------------

    @Test
    public void withNobodyToAskThePlanRunsNowRatherThanNever() {
        // The disconnected client and the screenshot harness. Waiting for a reply that cannot
        // come would leave the planner sitting on "nothing planned" with no explanation.
        //
        // Through `hold` and not `planWhenRead`, because the latter reaches `Minecraft` to
        // decide whether there is anyone to ask and a JUnit classpath cannot load that class
        // at all. What is asserted here is the decision; that the decision is fed by the
        // connection is one line, and it is stated in the PR as untested rather than implied.
        Ran plan = new Ran();
        assertFalse("nobody to ask, so nothing is left waiting for a reply",
                    PlannerStock.hold(plan, false));
        assertTrue(plan.ran);
    }

    @Test
    public void anEarlierQuestionIsDroppedRatherThanAnsweredAfterANewerOne() {
        // Two outstanding asks means the player moved on. `PlannerService.plan` refuses a
        // second solve while one is running, so answering the abandoned question first would
        // let it win and the one on screen lose.
        Ran abandoned = new Ran();
        Ran current = new Ran();
        assertTrue("the first question sends the ask", PlannerStock.hold(abandoned, true));
        assertFalse("and the second must NOT send a second one -- the reply to the first is"
                    + " already coming, and a read is a megabyte on the wire",
                    PlannerStock.hold(current, true));
        PlannerStock.accept(
                StockSnapshot.of(Collections.<String, Long>emptyMap(), NO_MACHINES).serializeNBT());
        assertFalse(abandoned.ran);
        assertTrue(current.ran);
    }

    @Test
    public void theAskGoesOutAgainOnceTheReplyHasLanded() {
        // The other side of the no-second-ask rule: it must suppress a DUPLICATE ask, not
        // every later one. A snapshot that has aged out is asked for again.
        assertTrue(PlannerStock.hold(new Ran(), true));
        PlannerStock.accept(
                StockSnapshot.unavailable(StockSnapshot.Reason.NO_AE2).serializeNBT());
        LiveStock.forget();
        assertTrue("nothing is outstanding any more, so a new question asks",
                   PlannerStock.hold(new Ran(), true));
    }

    @Test
    public void aReplyRunsThePlanThatWasWaitingOnIt() {
        Ran plan = new Ran();
        assertTrue("nothing has been read, so the plan waits", PlannerStock.hold(plan, true));
        assertFalse(plan.ran);

        StockSnapshot snapshot = StockSnapshot.of(
                Collections.singletonMap("minecraft:stone", Long.valueOf(64L)), NO_MACHINES);
        PlannerStock.accept(snapshot.serializeNBT());

        assertTrue("the reply landed and the question it was asked for went unanswered",
                   plan.ran);
        assertEquals(64L, LiveStock.latest().count("minecraft:stone"));
        assertTrue(LiveStock.isFresh());
    }

    @Test
    public void aRefusalStillAnswersTheQuestionRatherThanLeavingItHanging() {
        // The direction that matters: "no wireless terminal" must not mean the planner shows
        // nothing forever. The plan runs, and the caveat says why it is a lower bound.
        Ran plan = new Ran();
        PlannerStock.hold(plan, true);
        PlannerStock.accept(
                StockSnapshot.unavailable(StockSnapshot.Reason.NO_TERMINAL).serializeNBT());
        assertTrue(plan.ran);
        assertFalse(LiveStock.latest().isAvailable());
    }

    @Test
    public void aSecondReplyWithNothingWaitingIsHeldRatherThanPlannedOn() {
        Ran first = new Ran();
        PlannerStock.hold(first, true);
        PlannerStock.accept(StockSnapshot.of(
                Collections.singletonMap("a", Long.valueOf(1L)), NO_MACHINES).serializeNBT());
        assertTrue(first.ran);
        first.ran = false;

        PlannerStock.accept(StockSnapshot.of(
                Collections.singletonMap("b", Long.valueOf(2L)), NO_MACHINES).serializeNBT());
        assertFalse("a solve nobody asked for would replace what the player is looking at",
                    first.ran);
        assertEquals(2L, LiveStock.latest().count("b"));
    }

    @Test
    public void aFreshReadIsNotAskedForTwice() {
        // Opening the planner twice in a row must not put a megabyte of item list on the wire
        // twice -- which is the whole reason `isFresh` exists.
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(), NO_MACHINES));
        assertTrue(LiveStock.isFresh());
        Ran plan = new Ran();
        assertFalse("a fresh read must not be asked for again",
                    PlannerStock.hold(plan, true));
        assertTrue(plan.ran);
    }

    @Test
    public void aBulkCountSurvivesTheWireAndReachesTheScenario() {
        // End to end through the join, on the value most likely to be quietly narrowed: this
        // pack's reference network holds 71.8 million of some items, and an `int` anywhere on
        // the path wraps negative and reads as owning none.
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("minecraft:cobblestone", Long.valueOf(3_000_000_000L));
        PlannerStock.accept(StockSnapshot.of(counts, NO_MACHINES).serializeNBT());
        PlannerStock.install();
        assertEquals(3_000_000_000L,
                     PlannerService.liveScenario().getAsJsonObject("have")
                                   .get("minecraft:cobblestone").getAsLong());
    }

    // -- the placed half of the same reply (#320) -----------------------------------------------

    /**
     * A graph with one category whose machine is CRAFTABLE, so its verdict turns on `placed`.
     *
     * CRAFTABLE AND NOT UNAVAILABLE, because the point is to distinguish the two answers that
     * differ. With no placed evidence `mod:press` has producers, so `candidateVerdict` returns
     * BUILDABLE -- the state a machine you are standing next to used to read as, which is #320's
     * whole complaint. With placed evidence it is HAVE. An unbuildable machine would go from
     * UNAVAILABLE to HAVE and the test would pass on a weaker distinction.
     */
    private static RecipeGraph pressGraph() {
        GraphBuilder b = new GraphBuilder();
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key("mod:a"), 1);
        b.endRecipe("r", "mod.press", "Press", "test", false, false);
        // The machine itself is craftable by hand, which is what makes it BUILDABLE unplaced.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:leaf"));
        b.endSlot();
        b.output(b.key("mod:press"), 1);
        b.endRecipe("r_press", "minecraft.crafting", "Crafting Table", "test", false, false);
        b.beginCatalyst("mod.press");
        b.catalystKey(b.key("mod:press"));
        b.endCatalyst();
        return b.build();
    }

    private static MachineInfo verdictFor(RecipeGraph graph) {
        return ScenarioInputs.resolve(graph, PlannerService.liveScenario())
                             .machineStates()
                             .info(graph.categoryId("mod.press"));
    }

    @Test
    public void nobodyHasAskedIsAReasonThatSaysWhatToDoAboutMachinesToo() {
        // The sentences must DIFFER from `statusOf`'s, or the caveat panel prints the same line
        // twice with nothing to tell `have` and `placed` apart.
        ScenarioSource.Status status = PlannerStock.placedStatusOf(null);
        assertFalse(status.live());
        assertTrue(status.note(), status.note().contains("no machine counts as owned"));
        assertTrue("a caveat has to name an action", status.note().contains("open the"));
        assertEquals("nothing was read, so nothing may be priced against it",
                     null, status.contents());
        assertFalse("the two readers must not answer with the same sentence",
                    status.note().equals(PlannerStock.statusOf(null).note()));
    }

    @Test
    public void aRefusedReadReportsTheREASONForMachinesRatherThanAnEmptyGrid() {
        // The failure this whole path exists to avoid: "out of power" and "your grid carries no
        // machines" are opposite claims and carry the same data.
        ScenarioSource.Status status = PlannerStock.placedStatusOf(
                StockSnapshot.unavailable(StockSnapshot.Reason.NO_POWER));
        assertFalse(status.live());
        assertTrue(status.note(),
                   status.note().startsWith(StockSnapshot.Reason.NO_POWER.message()));
        assertTrue(status.note(), status.note().contains("counts as owned"));
        assertEquals(null, status.contents());
    }

    @Test
    public void installWiresPlacedAsWellAsHave() {
        // ONE REPLY, TWO INPUTS. A second install site would be the #191 shape again: the
        // machines arriving, being stored, and every one still pricing as buildable.
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(),
                                          Snapshots.machines("mod:press", 1)));
        PlannerStock.install();
        assertTrue(ScenarioSource.PLACED.live());
        assertEquals("", ScenarioSource.PLACED.note());
        assertEquals(1, PlannerService.liveScenario().getAsJsonObject("placed")
                                      .get("mod:press").getAsInt());
    }

    /**
     * THE BUG #320 IS ABOUT, asserted as the difference between two verdicts.
     *
     * On master this test fails at the second assertion with BUILDABLE, because `placed` was
     * read by nothing: a Press you are standing next to priced as a Press you have to build.
     * The evidence string is asserted too, because "HAVE" alone would also be produced by the
     * machine ITEM sitting in stock -- a route that already worked -- and this has to prove the
     * verdict came from the grid walk rather than from the item list.
     */
    @Test
    public void aMachineOnTheGridReadsAsOwnedRatherThanBuildable() {
        RecipeGraph graph = pressGraph();

        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(), NO_MACHINES));
        PlannerStock.install();
        assertEquals("nothing on the grid, so the machine is something to build",
                     MachineInfo.BUILDABLE, verdictFor(graph).state());

        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(),
                                          Snapshots.machines("mod:press", 1)));
        MachineInfo owned = verdictFor(graph);
        assertEquals("the machine is on the grid and the plan still says build one",
                     MachineInfo.HAVE, owned.state());
        assertEquals("placed: mod:press", owned.why());
    }

    @Test
    public void aRefusedReadDoesNotSilentlyPriceEveryMachineAsBuildable() {
        // The direction that matters. A refusal reaching `placed` as an empty object would look
        // exactly like a grid with no machines on it -- so the assertion is on the CAVEAT, not
        // on the verdict: the verdict is the same either way and only one of them is honest.
        RecipeGraph graph = pressGraph();
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        PlannerStock.install();

        assertFalse(ScenarioSource.PLACED.live());
        assertTrue(ScenarioSource.summary(),
                   ScenarioSource.summary().contains(ScenarioSource.PLACED.field()));
        assertTrue(PlannerService.liveScenario().getAsJsonObject("placed")
                                 .entrySet().isEmpty());
        assertEquals(MachineInfo.BUILDABLE, verdictFor(graph).state());
    }

    @Test
    public void aReadGridWithNoMachinesDropsTheCaveatWithoutClaimingAnything() {
        // An available snapshot may legitimately carry no machines, and that is a different
        // statement from every refusal: the caveat goes and the verdict stays BUILDABLE.
        RecipeGraph graph = pressGraph();
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(), NO_MACHINES));
        PlannerStock.install();

        assertTrue(ScenarioSource.PLACED.live());
        assertFalse(ScenarioSource.summary(),
                    ScenarioSource.summary().contains(ScenarioSource.PLACED.field()));
        assertEquals(MachineInfo.BUILDABLE, verdictFor(graph).state());
    }

    /**
     * THE PLACED EVIDENCE REACHES THE COST CACHE KEY, so two grids do not share one price table.
     *
     * `PlannerService.costsFor` memoises on `Resolved.costSignature`, which carries
     * `machineStates.summarise()` and `describedCategories()` -- and machine states are exactly
     * what placed evidence changes. Without this, a plan made with the Press on the grid and one
     * made after dismantling it would be served the same prices, which is
     * `costSignature`'s own stated failure ("one inventory's prices served to another").
     */
    @Test
    public void thePlacedReadChangesTheCostSignature() {
        RecipeGraph graph = pressGraph();
        PlannerStock.install();

        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(), NO_MACHINES));
        String bare = ScenarioInputs.resolve(graph, PlannerService.liveScenario())
                                    .costSignature();
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(),
                                          Snapshots.machines("mod:press", 1)));
        String owned = ScenarioInputs.resolve(graph, PlannerService.liveScenario())
                                     .costSignature();
        assertFalse("two grids sharing one cost table means one base's prices served to"
                    + " another: " + bare, bare.equals(owned));
    }

    @Test
    public void thePlacedReaderFollowsTheHeldSnapshotRatherThanTheOneItWasInstalledWith() {
        // A grid can go out of range between two plans, so the reader has to look each time --
        // the same rule the `have` reader obeys, asserted separately because it is a second
        // reader and a second chance to cache.
        PlannerStock.install();
        assertFalse(ScenarioSource.PLACED.live());
        LiveStock.accept(StockSnapshot.of(Collections.<String, Long>emptyMap(),
                                          Snapshots.machines("mod:press", 1)));
        assertTrue(ScenarioSource.PLACED.live());
        LiveStock.accept(StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE));
        assertFalse(ScenarioSource.PLACED.live());
    }

    @Test
    public void placedMachinesSurviveTheWireAndReachTheScenario() {
        // End to end through the packet, which is the half `placedStatusOf` on its own cannot
        // prove: the reply is deserialised by `accept` and only then read by the source.
        PlannerStock.accept(StockSnapshot.of(
                Collections.<String, Long>emptyMap(),
                Snapshots.machines("appliedenergistics2:drive", 8,
                                   "thermalexpansion:machine:1", 1)).serializeNBT());
        PlannerStock.install();
        JsonObject placed = PlannerService.liveScenario().getAsJsonObject("placed");
        assertEquals(8, placed.get("appliedenergistics2:drive").getAsInt());
        assertEquals(1, placed.get("thermalexpansion:machine:1").getAsInt());
    }

    /** A plan that records whether it was run. */
    private static final class Ran implements Runnable {

        boolean ran;

        @Override
        public void run() {
            ran = true;
        }
    }
}
