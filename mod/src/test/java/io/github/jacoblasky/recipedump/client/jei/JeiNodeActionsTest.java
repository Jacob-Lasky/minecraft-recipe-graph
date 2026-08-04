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
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
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
        assertFalse(installed.canShowInRecipeViewer(stick));

        loadGraph();
        ItemStack real = new ItemStack(Items.STICK);
        assertEquals("the fixture graph must key the stick the way the dump does",
                     "minecraft:stick", DumpCommand.stackKey(real));
        JeiBridge.indexFor(GraphService.get().graph(), Collections.singletonList(real));

        // NOTHING RE-INSTALLED. Same object, new answer.
        assertTrue("the installed source must be re-asked, not resolved once",
                   installed.canShowInRecipeViewer(stick));
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
        assertFalse(actions.canShowInRecipeViewer(node));
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
        assertFalse(actions.canShowInRecipeViewer(null));
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
        // The reason `canShowInRecipeViewer` is asked PER NODE rather than per session: JEI is
        // running, the graph is loaded, and this row still has nothing to focus on. 1,198 of
        // this pack's fluids exist only as `fluid:<name>`.
        Fixture f = wired();
        assertTrue(f.actions.canShowInRecipeViewer(node("minecraft:stick")));
        assertFalse(f.actions.canShowInRecipeViewer(node("fluid:water")));
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
                    f.actions.canShowInRecipeViewer(ore));
        assertTrue("the row beneath it is the item, and answers for itself",
                   f.actions.canShowInRecipeViewer(ore.children().get(0)));
    }

    @Test
    public void aKeyThisGraphNeverSawAnswersFalseRatherThanGuessing() {
        // No weakening of the KEY in this direction, unlike `JeiBridge.keyIdFor`. A plan node's
        // key came out of the graph, so a miss means the graph was swapped under the plan --
        // and answering with a neighbouring item's stack would be a wrong answer dressed as a
        // right one.
        Fixture f = wired();
        assertFalse(f.actions.canShowInRecipeViewer(node("mod:never_dumped")));
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
        assertTrue(f.actions.canShowInRecipeViewer(variant));
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
        assertFalse(f.actions.canShowInRecipeViewer(stick));
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
        assertTrue(f.actions.canShowInRecipeViewer(stick));
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
