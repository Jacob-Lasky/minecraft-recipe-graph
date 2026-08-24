package io.github.jacoblasky.recipedump.client.jei;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.DumpPlugin;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanJson;
import io.github.jacoblasky.recipedump.common.GraphDocuments;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import mezz.jei.api.IBookmarkOverlay;
import mezz.jei.api.IIngredientFilter;
import mezz.jei.api.IIngredientListOverlay;
import mezz.jei.api.IItemListOverlay;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.IRecipesGui;
import mezz.jei.api.recipe.IFocus;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The JEI node-menu adapter, in all four of the states a real client reaches.
 *
 * THE FOUR STATES ARE THE POINT, because three of them look identical from a screenshot -- the
 * two recipe-viewer entries are simply not drawn -- and only one of them is a bug:
 *
 * <ol>
 * <li>No graph loaded. Correct during the 5.47 s load: `DumpPlugin`'s source answers null
 *     until `GraphService` reaches READY, which is later than `onRuntimeAvailable`.</li>
 * <li>A graph, but the node names no item -- a fluid, an oredict group.</li>
 * <li>A graph and an item, but no JEI runtime. Entries hidden, because opening one would do
 *     nothing.</li>
 * <li>A graph, an item and a runtime. The entries appear and they must focus the RIGHT WAY
 *     ROUND, which is the assertion nothing else in this repository makes.</li>
 * </ol>
 *
 * WHAT NEEDS A HAND ON A KEYBOARD, stated rather than implied: JEI actually rendering the
 * recipe screen it is told to show. This asserts the bridge hands JEI a focus with the right
 * mode and the right stack; whether JEI then draws a page for it is JEI's business and only a
 * launched client can say so.
 */
public class JeiNodeActionsTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String savedGraphProperty;

    /**
     * ONE `@Before` AND ONE `@After` RATHER THAN A PAIR OF EACH. JUnit 4 does not order two
     * methods carrying the same annotation, so splitting the property handling out would make
     * the isolation depend on something the framework declines to promise.
     */
    @Before
    public void isolate() {
        savedGraphProperty = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
        resetStatics();
    }

    @After
    public void restore() {
        if (savedGraphProperty == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, savedGraphProperty);
        }
        resetStatics();
    }

    private void resetStatics() {
        // Three statics, so any test that installs one must not leak it into the next.
        DumpPlugin.runtime = null;
        NodeActionsHolder.install(null);
        JeiBridge.indexFor(null);
        // A fourth, for the one test that drives the real production install path. Reset for
        // EVERY test rather than only that one: a leaked READY graph would let the no-graph
        // assertions pass against a graph, which is the direction that hides a defect.
        GraphService.get().reset();
    }

    // -- installation ---------------------------------------------------------------------

    @Test
    public void installingReplacesTheNoOpEvenWhileTheGraphIsStillLoading() {
        // THE WHOLE REASON A FALSE-ANSWERING VERSION IS INSTALLED RATHER THAN LEFT OUT. An
        // absent implementation and one that always says false give the player an identical
        // menu -- and in a real client that state lasts the whole 5.47 s load, so broken
        // registration and a graph that has not arrived would look alike for five seconds
        // and then forever.
        assertSame(NodeActions.NONE, NodeActionsHolder.actions());
        JeiNodeActions.install(JeiNodeActions.NO_GRAPH);
        assertNotSame(NodeActions.NONE, NodeActionsHolder.actions());
        assertTrue(NodeActionsHolder.actions() instanceof JeiNodeActions);
    }

    /**
     * The install log, asserted EXACTLY rather than by substring.
     *
     * A SUBSTRING ASSERTION HERE CANNOT FAIL IN THE DIRECTION THAT MATTERS, and one nearly
     * shipped. The `GraphAccess` rename reworded this message specifically so it would stop
     * saying "graph source" -- and loosened this line to `contains("no graph")`, which the
     * OLD message satisfies too. That assertion passes before the rename, after it, and after
     * a revert of it: it looks like coverage and it cannot detect the thing it guards.
     *
     * So both strings are asserted whole. Yes, that means a reword reddens this test; that IS
     * the contract. Three properties ride on these strings and none of them survive a
     * substring check: the two branches must DIFFER (a client that installed blind and one
     * that never installed produce an identical menu, and nothing but this line can tell them
     * apart), the wired branch must NAME the implementation, and NEITHER may contain
     * "graph source", because `common.GraphSource` is a different class doing a different job.
     *
     * DO NOT EXTRACT THESE STRINGS INTO A SHARED CONSTANT to remove the duplication. The
     * duplication IS the assertion: a constant that both sides read would compare the
     * production string with itself and pass for any value of it, which is the same
     * cannot-fail shape as the `contains` check, arrived at from the opposite direction.
     */
    @Test
    public void theInstallLogSaysWhichOfTheTwoIndistinguishableStatesItIsIn() {
        JeiNodeActions.GraphAccess wired = source(graphOf("minecraft:stick"));
        String blindly = JeiNodeActions.installMessage(JeiNodeActions.NO_GRAPH);
        String named = JeiNodeActions.installMessage(wired);

        assertEquals("JeiNodeActions installed with no graph access: the planner's"
                     + " recipe-viewer entries stay hidden for as long as this install stands,"
                     + " and no graph load will change that. DumpPlugin.onRuntimeAvailable"
                     + " installs the live one, so this line means some other caller reached"
                     + " install().",
                     blindly);
        assertEquals("JeiNodeActions installed, reading the graph from "
                     + wired.getClass().getName() + ".",
                     named);

        // The three properties, restated as assertions rather than trusted to the two above,
        // so a future reword is told WHICH rule it broke instead of just which characters.
        assertNotEquals(blindly, named);
        assertTrue("the wired branch must name the implementation",
                   named.contains(wired.getClass().getName()));
        assertFalse("neither message may say \"graph source\" -- common.GraphSource is a"
                    + " different class, and naming it sends the reader there",
                    blindly.contains("graph source") || named.contains("graph source"));
        assertEquals(blindly, JeiNodeActions.installMessage(null));
    }

    /**
     * The production path installs the seam, and the graph it installs arrives LATER.
     *
     * TWO CLAIMS THAT NOTHING ELSE HERE ASSERTS, and both are about `DumpPlugin` rather than
     * about this class -- which is exactly why they were missing. Every other test in this file
     * installs the seam itself, so all of them stay green on a client where nothing ever does.
     * That is the shape #191 catalogues: a seam whose producer and consumer are both tested and
     * which nothing joins in production. `PlannerHooks.setTargetListener` is the sibling that
     * has this defect for real, so the guard is worth having on the one that does not.
     *
     * THE SECOND HALF IS THE MUTATION-KILLER. `onRuntimeAvailable` fires long before
     * `GraphService` reaches READY, so the source has to be asked PER INVOCATION; caching the
     * graph in the constructor or resolving it once at the call site would answer null for the
     * rest of the session. The install here happens while the service is IDLE and the SAME
     * installed object is re-asked after the load, so a cached graph reddens the last
     * assertion while every other test in this file stays green.
     */
    @Test
    public void dumpPluginInstallsTheSeamAndTheGraphArrivesAfterwards() throws Exception {
        assertSame(NodeActions.NONE, NodeActionsHolder.actions());
        RecordingJei jei = new RecordingJei();

        new DumpPlugin().onRuntimeAvailable(jei);

        NodeActions installed = NodeActionsHolder.actions();
        assertTrue("DumpPlugin must install the seam, not just capture the runtime",
                   installed instanceof JeiNodeActions);
        // IDLE, so `GraphService.graph()` is null -- the state a real client is in for the
        // whole load. The entries are hidden and that is the correct answer, not a gap.
        PlanNode stick = node("minecraft:stick");
        assertFalse(installed.canShowUses(stick));

        loadGraph();
        ItemStack real = new ItemStack(Items.STICK);
        assertEquals("the fixture graph must key the stick the way the dump does",
                     "minecraft:stick", DumpCommand.stackKey(real));
        JeiBridge.indexFor(GraphService.get().graph(), Collections.singletonList(real));

        // NOTHING RE-INSTALLED. Same object, new answer.
        assertTrue("the installed source must be re-asked, not resolved once",
                   installed.canShowUses(stick));
        assertSame(real, installed.iconFor(stick));
    }

    @Test
    public void aNullGraphAccessBecomesNoGraphRatherThanBeingKept() {
        // `iconFor` runs once per row per frame. A null field there is a crash per frame.
        NodeActions actions = new JeiNodeActions(null);
        assertSame(ItemStack.EMPTY, actions.iconFor(node("minecraft:stick")));
    }

    // -- state 1: no graph ------------------------------------------------------------------

    @Test
    public void withNoGraphEveryNodeAnswersLikeANodeWithNoItemBehindIt() {
        NodeActions actions = new JeiNodeActions(JeiNodeActions.NO_GRAPH);
        PlanNode node = node("minecraft:stick");
        assertFalse(actions.canShowUses(node));
        assertSame(ItemStack.EMPTY, actions.iconFor(node));
        // Not reachable from the menu -- it hides the entries -- but reachable from a plan
        // reloaded between the menu opening and the click, which is a click into nothing.
        actions.showRecipes(node);
        actions.showUses(node);
    }

    @Test
    public void aNullNodeIsAnEmptyStackRatherThanAnExceptionInsideARenderPass() {
        // `PlannerWidgets` calls `iconFor(node).isEmpty()` with no null check of its own, so
        // returning null here would be an NPE per row rather than a missing icon.
        NodeActions actions = new JeiNodeActions(source(graphOf("minecraft:stick")));
        assertSame(ItemStack.EMPTY, actions.iconFor(null));
        assertFalse(actions.canShowUses(null));
    }

    @Test
    public void anIndexBuiltForOneGraphIsNeverServedForAnother() {
        // THE PAIRING INVARIANT, and the reason `JeiBridge` holds the graph and its index as
        // one object behind one volatile field rather than as two. Once `GraphService` began
        // building the index on its LOADER thread, two unordered fields let the client thread
        // see the new graph beside the old index -- every key would resolve, to the wrong
        // item. A single-threaded test cannot reach the interleaving; it CAN reach the
        // invariant, which is that an index is only ever handed out for the graph it was
        // built from.
        ItemStack stick = new ItemStack(Items.STICK);
        RecipeGraph built = graphOf(DumpCommand.stackKey(stick));
        JeiBridge.indexFor(built, Collections.singletonList(stick));
        assertSame(stick, JeiBridge.stackFor(built.keyId("minecraft:stick"), built));

        // A DIFFERENT graph that gives the same key the same id, so serving the stale index
        // would answer with the stick and look entirely correct doing it. That the two ids
        // agree is asserted, not assumed -- if they ever diverged this would pass for the
        // wrong reason.
        RecipeGraph other = graphOf("minecraft:stick");
        assertEquals(built.keyId("minecraft:stick"), other.keyId("minecraft:stick"));
        assertNull(JeiBridge.stackFor(other.keyId("minecraft:stick"), other));
    }

    // -- state 2: a graph, but the node names no item ----------------------------------------

    @Test
    public void aFluidNodeAnswersFalseWithEverythingElseWiredUp() {
        // The reason `canShowUses` is asked PER NODE rather than per session: JEI is
        // running, the graph is loaded, and this row still has nothing to focus on. 1,198 of
        // this pack's fluids exist only as `fluid:<name>`.
        Fixture f = wired();
        assertTrue(f.actions.canShowUses(node("minecraft:stick")));
        assertFalse(f.actions.canShowUses(node("fluid:water")));
        assertSame(ItemStack.EMPTY, f.actions.iconFor(node("fluid:water")));
    }

    @Test
    public void anOredictGroupAnswersFalseAndItsResolvedChildAnswersTrue() {
        // PINS THE DECISION NOT TO FOLLOW `resolvedTo()`. The solver attaches the member it
        // picked as the group's only child -- `solve.py`'s `resolve_ore` returns
        // `{"status": "oredict", "resolved_to": best, "children": [child]}` -- so the concrete
        // item is already its own row directly beneath and answers for itself. Following
        // `resolvedTo` here would give two adjacent rows opening the same JEI screen.
        Fixture f = wired();

        JsonObject group = json("ore:stickWood", "ore");
        group.addProperty("status", "oredict");
        group.addProperty("resolved_to", "minecraft:stick");
        JsonArray children = new JsonArray();
        children.add(json("minecraft:stick", "item"));
        group.add("children", children);

        PlanNode ore = PlanJson.readNode(group);
        assertEquals("minecraft:stick", ore.resolvedTo());
        assertFalse("the group is not an item, even though it knows which one it chose",
                    f.actions.canShowUses(ore));
        assertTrue("the row beneath it is the item, and answers for itself",
                   f.actions.canShowUses(ore.children().get(0)));
    }

    @Test
    public void aKeyThisGraphNeverSawAnswersFalseRatherThanGuessing() {
        // No weakening of the KEY in this direction, unlike `JeiBridge.keyIdFor`. A plan node's
        // key came out of the graph, so a miss means the graph was swapped under the plan --
        // and answering with a neighbouring item's stack would be a wrong answer dressed as a
        // right one.
        Fixture f = wired();
        assertFalse(f.actions.canShowUses(node("mod:never_dumped")));
        assertSame(ItemStack.EMPTY, f.actions.iconFor(node("mod:never_dumped")));
    }

    @Test
    public void anNbtVariantTheItemListNeverShowedStillGetsTheBaseItemsIcon() {
        // THE WEAKENING THAT DOES APPLY, and it belongs to `StackIndex` rather than here: JEI
        // lists one entry per registered item, not one per NBT state, so a key the graph holds
        // as a variant has no listing of its own. Showing the base item beats showing nothing,
        // and the distinction from the test above is that this key IS in the graph.
        Fixture f = wired();
        PlanNode variant = node("minecraft:stick#deadbeef");
        assertTrue(f.actions.canShowUses(variant));
        assertSame(f.stick, f.actions.iconFor(variant));
    }

    /**
     * `iconForKey` answers exactly what `iconFor` does for the same key.
     *
     * WHY IT MATTERS RATHER THAN BEING A TAUTOLOGY. The TODO panel draws from the plan BOOK,
     * which holds keys and not nodes, and its shopping list sits in the same window as the
     * tree. Two lookups that disagreed would draw one item with an icon in one panel and
     * without in the other, and nothing would report it -- so the two share one private
     * resolver and this asserts they still do.
     *
     * SWEPT OVER EVERY SHAPE THE INDEX ANSWERS DIFFERENTLY FOR, because a pair of methods can
     * agree on the easy case and come apart on the weakenings: a plain item, an NBT variant
     * that only the digest strip reaches, a fluid with no item form, an oredict group, and a
     * key the graph never saw.
     */
    @Test
    public void aBareKeyGetsTheSameIconItsNodeWouldGet() {
        Fixture f = wired();
        String[] keys = {DumpCommand.stackKey(f.stick), "minecraft:stick#deadbeef",
                         "fluid:water", "ore:stickWood", "mod:never_dumped"};
        for (String key : keys) {
            assertSame("iconForKey disagreed with iconFor on " + key,
                       f.actions.iconFor(node(key)), f.actions.iconForKey(key));
        }
        // AND THE SWEEP MUST CONTAIN BOTH ANSWERS, or it would pass against two methods that
        // both returned EMPTY for everything.
        assertSame(f.stick, f.actions.iconForKey(DumpCommand.stackKey(f.stick)));
        assertSame(ItemStack.EMPTY, f.actions.iconForKey("fluid:water"));
    }

    @Test
    public void aNullKeyIsAMissingIconRatherThanACrashInADrawCall() {
        // `PlannerWidgets` calls `iconForKey(key).isEmpty()` with no null check of its own, and
        // it runs once per TODO row per frame. `StringTable.idOf` hashes the string before it
        // can answer -1, so an unguarded null arrives as an NPE inside a render pass.
        Fixture f = wired();
        assertSame(ItemStack.EMPTY, f.actions.iconForKey(null));
        assertSame(ItemStack.EMPTY, new JeiNodeActions(JeiNodeActions.NO_GRAPH)
                .iconForKey("minecraft:stick"));
    }

    /**
     * A token answers NO to "Show recipes" and YES to "Show uses" (#174).
     *
     * THE TRAP THIS EXISTS FOR: a pack placeholder is a REGISTERED item, so `stackFor` succeeds and
     * every check in `canShowUses` passes. JEI will open on it happily, on the recipes that MAKE a
     * Dungeon Drop, of which there are none, because the solver classified it as an instruction and
     * kept it out of `shopping_list` for exactly that reason. #174 was reported on a reader
     * concluding a token was an item, and an entry that opens an empty screen is that conclusion
     * with a click behind it.
     *
     * BOTH HALVES ARE ASSERTED, and the second is the one that stops an over-correction. "Show
     * uses" must stay: what CONSUMES a Dungeon Drop is a real question with real answers, and it is
     * the honest form of what the reader wanted. Suppressing both would trade a misleading entry
     * for a missing one.
     */
    @Test
    public void aTokenHidesShowRecipesAndKeepsShowUses() {
        Fixture f = wired();
        // A REAL STACK BEHIND IT, asserted, or this test passes for the wrong reason: if the key did
        // not resolve, `canShowUses` would answer false and the interesting half would be vacuous.
        String key = DumpCommand.stackKey(f.stick);
        assertSame(f.stick, f.actions.iconForKey(key));

        JsonObject json = json(key, "item");
        json.addProperty("status", "token");
        json.addProperty("token_kind", "loot");
        PlanNode token = PlanJson.readNode(json);
        assertTrue("the fixture must be a token or this asserts nothing",
                   io.github.jacoblasky.recipedump.client.planner.NodeStatus.isToken(token));

        assertFalse("a token has nothing that makes it", f.actions.canShowRecipes(token));
        assertTrue("but what consumes it is a real question", f.actions.canShowUses(token));

        // AND THE SAME KEY AS AN ORDINARY ITEM ANSWERS YES TO BOTH, so the difference is the
        // STATUS and not the key. Without this the test would pass against an implementation that
        // rejected `minecraft:stick` for some unrelated reason.
        PlanNode plain = node(key);
        assertTrue(f.actions.canShowRecipes(plain));
        assertTrue(f.actions.canShowUses(plain));
    }

    // -- state 3: an item, but no JEI runtime --------------------------------------------------

    @Test
    public void aPopulatedIndexWithNoRuntimeHidesTheEntriesButKeepsTheIcon() {
        // THE ASYMMETRY, DELIBERATE AND EASY TO "TIDY" AWAY. `DumpPlugin` captures the
        // ingredient registry in `register` and the runtime in `onRuntimeAvailable`, two
        // separate callbacks -- so a populated index with a null runtime is reachable, and it
        // is exactly the state where an entry would draw and then open nothing. The ICON is
        // not a JEI feature: the planner draws it itself, so it stays.
        Fixture f = wired();
        DumpPlugin.runtime = null;

        PlanNode stick = node("minecraft:stick");
        assertFalse(JeiBridge.isAvailable());
        assertFalse(f.actions.canShowUses(stick));
        assertSame(f.stick, f.actions.iconFor(stick));
    }

    // -- state 4: everything wired ---------------------------------------------------------------

    @Test
    public void showRecipesAsksWhatMakesItAndShowUsesAsksWhatConsumesIt() {
        // THE SWAP THAT READS IDENTICALLY IN REVIEW. Both entries open a recipe screen and
        // both work; only the direction differs, and a menu whose two entries do the same
        // thing is a bug nobody notices from a screenshot.
        Fixture f = wired();
        PlanNode stick = node("minecraft:stick");

        f.actions.showRecipes(stick);
        assertNotNull("the bridge never reached JEI at all", f.jei.shown);
        assertEquals(IFocus.Mode.OUTPUT, f.jei.shown.getMode());
        assertSame(f.stick, f.jei.shown.getValue());

        f.actions.showUses(stick);
        assertEquals(IFocus.Mode.INPUT, f.jei.shown.getMode());
        assertSame(f.stick, f.jei.shown.getValue());
    }

    @Test
    public void aRuntimeThatThrowsMidClickLeavesTheScreenStanding() {
        // A recipe category whose wrapper throws while laying itself out would otherwise take
        // the GUI down from inside a click handler. `JeiBridge` catches it; this asserts the
        // adapter does not undo that by throwing on the way in or out.
        Fixture f = wired();
        DumpPlugin.runtime = new HostileJei();
        PlanNode stick = node("minecraft:stick");
        f.actions.showRecipes(stick);
        f.actions.showUses(stick);
        // Still answers, because the failure was JEI's and the runtime is still there.
        assertTrue(f.actions.canShowUses(stick));
    }

    // -- fixtures ---------------------------------------------------------------------------

    /** A graph, its index, a live recording runtime, and the adapter over all three. */
    private static final class Fixture {
        final ItemStack stick;
        final RecordingJei jei;
        final NodeActions actions;

        Fixture(ItemStack stick, RecordingJei jei, NodeActions actions) {
            this.stick = stick;
            this.jei = jei;
            this.actions = actions;
        }
    }

    /**
     * The fully wired case: one real item in the graph and in the index, an NBT variant of it
     * that only the index's weakening can reach, plus a fluid and an oredict key that are in
     * the graph and cannot be in the index at all.
     */
    private Fixture wired() {
        ItemStack stick = new ItemStack(Items.STICK);
        // Keyed through `DumpCommand.stackKey` rather than spelled, because that is the only
        // reason the index and the graph agree at all.
        RecipeGraph graph = graphOf(DumpCommand.stackKey(stick), "minecraft:stick#deadbeef",
                                    "fluid:water", "ore:stickWood");
        JeiBridge.indexFor(graph, Collections.singletonList(stick));

        RecordingJei jei = new RecordingJei();
        DumpPlugin.runtime = jei;
        return new Fixture(stick, jei, new JeiNodeActions(source(graph)));
    }

    /**
     * Load a real graph through the real production path, and wait for it.
     *
     * THROUGH `GraphService` AND NOT `GraphBuilder`, unlike every other fixture here, because
     * the claim under test is about the thing `DumpPlugin`'s source calls. A hand-built graph
     * handed to a hand-built source would assert the seam works, which four other tests
     * already do, rather than that production reaches it.
     *
     * KEYED TO A REAL ITEM, unlike `GraphDocuments.TINY`'s `mod:plate`, because this test
     * indexes an actual `ItemStack` against the loaded graph and no `Item` produces a plate.
     */
    private void loadGraph() throws Exception {
        File file = new File(folder.getRoot(), "graph.json");
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(GraphDocuments.craftedFrom("minecraft:stick", "Stick").getBytes("UTF-8"));
        } finally {
            out.close();
        }
        System.setProperty(GraphSource.PROPERTY, file.getPath());
        GraphService.get().startLoad(null);
        long deadline = System.currentTimeMillis() + 30_000L;
        while (GraphService.get().state() != GraphService.State.READY) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("graph never loaded: " + GraphService.get().describe());
            }
            Thread.sleep(5L);
        }
    }

    // -- the machine icon (#333) ------------------------------------------------------------

    /**
     * A machine step pictures the SPECIFIC controller, not the generic catalyst JEI lists first.
     *
     * <h2>THE TRAP, MEASURED AGAINST THE LIVE `data/graph.json`</h2>
     *
     * `graph.catalysts().row(cid)[0]` is the obvious implementation and it is wrong: 226 of the
     * pack's 227 `modularmachinery.*` categories list `modularmachinery:itemblueprint` FIRST,
     * because JEI lists whatever OPENS a category and one cheap blueprint opens all of them. So
     * the naive pick draws the same blueprint on essentially every Modular Machinery step, which
     * is a wrong answer that looks entirely plausible in a screenshot.
     *
     * BOTH CANDIDATES ARE REAL ITEMS HERE, AND THAT IS WHAT MAKES THIS BEHAVIOURAL RATHER THAN
     * STRUCTURAL. `modularmachinery:itemblueprint` is not in the dev client's item set, so a
     * fixture using the real key would return EMPTY for it whether or not any ordering ran --
     * the test would pass on an implementation with no ordering at all. A stick standing in for
     * the generic catalyst and a furnace for the specific one both resolve, so the assertion
     * distinguishes the two implementations: unordered gives the stick, ordered gives the
     * furnace.
     *
     * THE STICK IS MADE GENERIC THE ONLY WAY IT CAN BE -- by catalysing more categories.
     * `Machines.orderBySpecificity` ranks on breadth, fewest first, with no threshold and no
     * per-mod list, so "generic" is not a property of the key and cannot be faked by naming it.
     */
    @Test
    public void aMachineStepPicturesItsSpecificControllerAndNotTheCatalystJeiListedFirst() {
        ItemStack generic = new ItemStack(Items.STICK);
        ItemStack specific = new ItemStack(Item.getItemFromBlock(Blocks.FURNACE));
        RecipeGraph graph = catalystGraph(generic, specific);
        JeiBridge.indexFor(graph, java.util.Arrays.asList(generic, specific));
        NodeActions actions = new JeiNodeActions(source(graph));

        // THE CONTROL FIRST: both stacks really are resolvable, or the assertion below could
        // pass because neither was and EMPTY happened to equal EMPTY.
        assertFalse("the generic catalyst must resolve, or this proves nothing",
                    actions.iconForKey(DumpCommand.stackKey(generic)).isEmpty());
        assertFalse("the specific catalyst must resolve too",
                    actions.iconForKey(DumpCommand.stackKey(specific)).isEmpty());

        ItemStack drawn = actions.iconForCategory(categoryNode("mm.recipes.melter"));
        assertEquals("the machine column drew the catalyst JEI listed first, which is the"
                     + " generic one on 226 of 227 Modular Machinery categories",
                     specific.getItem(), drawn.getItem());
    }

    /**
     * A smelting step still pictures its furnace, and the tempting route would have lost it.
     *
     * <h2>TRAP 2: `MachineStates` LOOKS LIKE THE CLEAN ROUTE AND FAILS SILENTLY</h2>
     *
     * `MachineInfo.candidates()` is the obvious source for "which item is this category's
     * machine", and it is null or empty on four short-circuit paths: a manual override,
     * `Machines.ALWAYS_AVAILABLE`, hand crafting, and `needsNoMachine`. `minecraft.smelting` is
     * in `ALWAYS_AVAILABLE` -- correctly, you always have a furnace -- while carrying 23
     * catalysts in the live `graph.json`. So routing the icon through `MachineStates` draws
     * nothing on the 180 smelting steps in the committed fixtures and reads as a lookup that
     * merely failed.
     *
     * THE COUNTERFACTUAL IS ASSERTED IN THIS TEST rather than described in a comment, which is
     * the whole reason it is one test and not two. Without the second half a reader has no way
     * to tell that the route being avoided would actually have broken.
     */
    @Test
    public void aSmeltingStepPicturesItsFurnaceWhereTheMachineStatesRouteWouldHaveLostIt() {
        ItemStack furnace = new ItemStack(Item.getItemFromBlock(Blocks.FURNACE));
        GraphBuilder b = new GraphBuilder();
        // A RECIPE IN THE CATEGORY, AND WITHOUT IT THE COUNTERFACTUAL BELOW SAYS NOTHING.
        // `Machines.resolve` only builds a `MachineInfo` for a category that has recipes, so a
        // catalyst-only graph makes `info(cid)` null -- which reads as "MachineStates would have
        // lost it" for entirely the wrong reason, and arrives as an NPE rather than as a verdict.
        // Measured: that is exactly how the first version of this test failed.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:iron_ore"));
        b.endSlot();
        b.output(b.key("minecraft:iron_ingot"), 1);
        b.endRecipe("smelt-1", "minecraft.smelting", "Smelting", "test", false, false);
        b.beginCatalyst("minecraft.smelting");
        b.catalystKey(b.key(DumpCommand.stackKey(furnace)));
        b.endCatalyst();
        RecipeGraph graph = b.build();
        JeiBridge.indexFor(graph, Collections.singletonList(furnace));

        ItemStack drawn = new JeiNodeActions(source(graph))
                .iconForCategory(categoryNode("minecraft.smelting"));
        assertEquals("the catalyst route must picture the furnace",
                     furnace.getItem(), drawn.getItem());

        // AND THE ROUTE NOT TAKEN, so this test carries its own reason. `ALWAYS_AVAILABLE`
        // short-circuits before candidates are collected, so there is nothing here to draw.
        io.github.jacoblasky.recipedump.plan.MachineInfo info =
                io.github.jacoblasky.recipedump.plan.Machines
                        .resolve(graph, new io.github.jacoblasky.recipedump.plan.Evidence())
                        .info(graph.categoryId("minecraft.smelting"));
        assertNotNull("the category must have been RESOLVED for the counterfactual to be about"
                      + " the short-circuit rather than about a category the resolver skipped",
                      info);
        assertTrue("MachineStates would have to be empty here for Trap 2 to be real; if it is"
                   + " not, this test no longer says anything and the trap should be re-read",
                   info.candidates() == null || info.candidates().length == 0);
    }

    /**
     * The ordering is built once per graph and thrown away when the graph changes.
     *
     * `Machines.catalystsByCategory` is a whole-graph pass -- two maps over every category with a
     * catalyst plus a sort each -- and `PlannerWidgets.row` asks once per tree ROW.
     * `plan-fluid-chain` is 703 rows, so an uncached lookup runs that pass seven hundred times
     * per panel build.
     *
     * THE INVALIDATION IS THE HALF WORTH ASSERTING, and it is why the cache is keyed on the graph
     * by IDENTITY rather than by a boolean. A stale map would not throw: it would answer from the
     * previous graph's category ids, which is a picture of a DIFFERENT machine, and the class
     * header's rule about asking `graphs.graph()` per invocation exists for exactly this shape of
     * failure during the 5.47 s load.
     */
    @Test
    public void theCatalystOrderingIsCachedPerGraphAndDroppedWhenTheGraphChanges() {
        ItemStack stick = new ItemStack(Items.STICK);
        ItemStack furnace = new ItemStack(Item.getItemFromBlock(Blocks.FURNACE));

        GraphBuilder first = new GraphBuilder();
        first.beginCatalyst("mod.press");
        first.catalystKey(first.key(DumpCommand.stackKey(stick)));
        first.endCatalyst();
        final RecipeGraph[] current = {first.build()};

        GraphBuilder second = new GraphBuilder();
        second.beginCatalyst("mod.press");
        second.catalystKey(second.key(DumpCommand.stackKey(furnace)));
        second.endCatalyst();
        RecipeGraph replacement = second.build();

        JeiBridge.indexFor(current[0], java.util.Arrays.asList(stick, furnace));
        NodeActions actions = new JeiNodeActions(new JeiNodeActions.GraphAccess() {
            @Override
            public RecipeGraph graph() {
                return current[0];
            }
        });
        PlanNode node = categoryNode("mod.press");
        assertEquals(stick.getItem(), actions.iconForCategory(node).getItem());
        // Twice, so a cache that answered only its first caller would fail here.
        assertEquals(stick.getItem(), actions.iconForCategory(node).getItem());

        current[0] = replacement;
        JeiBridge.indexFor(replacement, java.util.Arrays.asList(stick, furnace));
        assertEquals("the ordering was cached against the OLD graph and never rebuilt",
                     furnace.getItem(), actions.iconForCategory(node).getItem());
    }

    @Test
    public void aCategoryWithNoCatalystDrawsNothingRatherThanGuessing() {
        // 37% of the fixtures' craft nodes, and every one of them is a category with no machine
        // BY NATURE -- bee and tree breeding, where the bee is the machine. "No icon" is the
        // correct rendering and not a lookup that failed; `NodeActions.iconForCategory` says so.
        ItemStack stick = new ItemStack(Items.STICK);
        RecipeGraph graph = graphOf(DumpCommand.stackKey(stick));
        JeiBridge.indexFor(graph, Collections.singletonList(stick));
        NodeActions actions = new JeiNodeActions(source(graph));
        assertSame(ItemStack.EMPTY,
                   actions.iconForCategory(categoryNode("bdew.jeibees.mutation.rootBees")));
    }

    @Test
    public void aMissingCategoryOrGraphIsAnEmptyStackRatherThanAnExceptionInARenderPass() {
        // Every caller is a GUI build; `PlannerWidgets.machineCell` calls this with no null
        // check of its own, and `StringTable.idOf` hashes its argument before it can answer -1.
        NodeActions noGraph = new JeiNodeActions(JeiNodeActions.NO_GRAPH);
        assertSame(ItemStack.EMPTY, noGraph.iconForCategory(categoryNode("mod.press")));
        assertSame(ItemStack.EMPTY, noGraph.iconForCategory(null));
        NodeActions wired = new JeiNodeActions(source(graphOf("minecraft:stick")));
        assertSame(ItemStack.EMPTY, wired.iconForCategory(null));
        assertSame("a node with no category at all", ItemStack.EMPTY,
                   wired.iconForCategory(node("minecraft:stick")));
    }

    /**
     * The lookup goes through `plan.Machines` and does not re-derive either rule.
     *
     * <h2>WHY THIS IS A CONSTANT-POOL TEST AND NOT A BEHAVIOURAL ONE</h2>
     *
     * `Machines.NOT_A_MACHINE` holds exactly `modularmachinery:itemblueprint`, and no `Item` in
     * the dev client's set registers under that name -- so a category whose only catalyst is the
     * blueprint answers EMPTY whether the filter runs or not, once because it was filtered and
     * once because it did not resolve. A behavioural assertion there would be green against an
     * implementation with no filter, which is the fabricated-artifact shape this repository has
     * been bitten by more than once. `MachinesTest` pins what the two rules DO;
     * `theGenericBlueprintIsNeverWhatAMachineIsPricedFrom` and
     * `theMostSpecificCatalystIsNamedRatherThanWhicheverJeiListedFirst` are those tests. What is
     * left for this file to assert is that the planner's icon REACHES them.
     *
     * `ClassFiles` ALREADY ANSWERS THIS SHAPE OF QUESTION HERE, in `IconWidgetTest`, and for the
     * same reason: a choice that cannot be observed at runtime can still be observed in the
     * bytecode, and "do not re-derive this" is a choice.
     */
    @Test
    public void theMachineIconGoesThroughMachinesRatherThanReDerivingItsRules() throws Exception {
        byte[] bytes = io.github.jacoblasky.recipedump.ClassFiles.read(
                new java.io.File(io.github.jacoblasky.recipedump.ClassFiles.classesRoot(),
                                 io.github.jacoblasky.recipedump.ClassFiles.ROOT_PACKAGE
                                 + "/client/jei/JeiNodeActions.class"));
        java.util.Set<String> called =
                io.github.jacoblasky.recipedump.ClassFiles.methodReferences(bytes);
        assertTrue("JeiNodeActions must take the ordering from Machines.catalystsByCategory --"
                   + " a second ordering is a second thing to get wrong, and its wrong answer"
                   + " (one blueprint on 226 of 227 categories) looks plausible: " + called,
                   references(called, "plan/Machines.catalystsByCategory"));
        assertTrue("...and it must filter Machines.isNotAMachine, which is what stops a"
                   + " blueprint being drawn on a category where it is the ONLY catalyst and"
                   + " the ordering therefore has nothing to demote it below: " + called,
                   references(called, "plan/Machines.isNotAMachine"));
    }

    private static boolean references(java.util.Set<String> called, String prefix) {
        for (String reference : called) {
            if (reference.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A graph shaped like the blueprint trap: `generic` opens four categories, `specific` one.
     *
     * THE GENERIC KEY IS LISTED FIRST IN THE CATEGORY UNDER TEST, which is JEI's own order and
     * the whole reason the ordering exists. See
     * `aMachineStepPicturesItsSpecificControllerAndNotTheCatalystJeiListedFirst`.
     */
    private static RecipeGraph catalystGraph(ItemStack generic, ItemStack specific) {
        GraphBuilder b = new GraphBuilder();
        int genericId = b.key(DumpCommand.stackKey(generic));
        int specificId = b.key(DumpCommand.stackKey(specific));
        b.beginCatalyst("mm.recipes.melter");
        b.catalystKey(genericId);
        b.catalystKey(specificId);
        b.endCatalyst();
        // THREE MORE, so the generic key's breadth is 4 against the specific one's 1. Fewer than
        // this and the two would tie on breadth and fall back to JEI's index, which is the
        // ordering doing nothing.
        for (String other : new String[] {"crucible", "reactor", "infuser"}) {
            b.beginCatalyst("mm.recipes." + other);
            b.catalystKey(genericId);
            b.endCatalyst();
        }
        return b.build();
    }

    /** A craft node that names `category` and nothing else a machine lookup reads. */
    private static PlanNode categoryNode(String category) {
        JsonObject json = json("mod:output", "item");
        json.addProperty("status", "craft");
        json.addProperty("category", category);
        json.addProperty("machine", category);
        return PlanJson.readNode(json);
    }

    private static RecipeGraph graphOf(String... keys) {
        GraphBuilder builder = new GraphBuilder();
        for (String key : keys) {
            builder.key(key);
        }
        return builder.build();
    }

    private static JeiNodeActions.GraphAccess source(final RecipeGraph graph) {
        return new JeiNodeActions.GraphAccess() {
            @Override
            public RecipeGraph graph() {
                return graph;
            }
        };
    }

    private static PlanNode node(String key) {
        return PlanJson.readNode(json(key, "item"));
    }

    /** Built as JSON and read by the real reader, so no test knows how a node is assembled. */
    private static JsonObject json(String key, String kind) {
        JsonObject node = new JsonObject();
        node.addProperty("key", key);
        node.addProperty("label", key);
        node.addProperty("kind", kind);
        return node;
    }

    // -- test doubles -----------------------------------------------------------------------

    /**
     * A JEI runtime that remembers the last focus it was shown.
     *
     * IT IS ITS OWN {@link IRecipesGui} because that interface is three methods and a separate
     * class for it would be indirection with nothing in it.
     */
    private static class RecordingJei implements IJeiRuntime, IRecipesGui {

        IFocus<?> shown;

        @Override
        public IRecipeRegistry getRecipeRegistry() {
            return FOCUS_MAKER;
        }

        @Override
        public IRecipesGui getRecipesGui() {
            return this;
        }

        @Override
        public IIngredientFilter getIngredientFilter() {
            return null;
        }

        @Override
        public IIngredientListOverlay getIngredientListOverlay() {
            return null;
        }

        @Override
        public IBookmarkOverlay getBookmarkOverlay() {
            return null;
        }

        @Override
        public IItemListOverlay getItemListOverlay() {
            return null;
        }

        @Override
        public <V> void show(IFocus<V> focus) {
            shown = focus;
        }

        @Override
        public void showCategories(List<String> categoryIds) {
        }

        @Override
        public Object getIngredientUnderMouse() {
            return null;
        }
    }

    /** The same, with a recipe screen that fails the way a broken category wrapper does. */
    private static final class HostileJei extends RecordingJei {
        @Override
        public <V> void show(IFocus<V> focus) {
            throw new IllegalStateException("a category wrapper blew up mid-layout");
        }
    }

    /**
     * An {@link IRecipeRegistry} that only knows how to make a focus.
     *
     * A PROXY RATHER THAN A HAND-WRITTEN CLASS because the interface has 29 methods and Phase
     * 4 calls exactly one of them; 28 stubs returning null would bury the one line that
     * matters. Anything else reaching here THROWS rather than answering null, so a future
     * change that starts using a second method finds out from this test instead of from a
     * silent null.
     */
    private static final IRecipeRegistry FOCUS_MAKER = (IRecipeRegistry) Proxy.newProxyInstance(
            IRecipeRegistry.class.getClassLoader(),
            new Class<?>[] {IRecipeRegistry.class},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (!"createFocus".equals(method.getName())) {
                        throw new UnsupportedOperationException(
                                "this fake models createFocus only, not " + method.getName());
                    }
                    return new RecordedFocus((IFocus.Mode) args[0], args[1]);
                }
            });

    private static final class RecordedFocus implements IFocus<Object> {

        private final Mode mode;
        private final Object value;

        RecordedFocus(Mode mode, Object value) {
            this.mode = mode;
            this.value = value;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public Mode getMode() {
            return mode;
        }
    }
}
