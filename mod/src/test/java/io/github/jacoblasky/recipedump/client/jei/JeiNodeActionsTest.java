package io.github.jacoblasky.recipedump.client.jei;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.DumpPlugin;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.client.planner.PlanJson;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
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
import org.junit.Test;

/**
 * The Phase 4 node-menu adapter, in all four of the states a real client reaches.
 *
 * THE FOUR STATES ARE THE POINT, because three of them look identical from a screenshot -- the
 * two recipe-viewer entries are simply not drawn -- and only one of them is a bug:
 *
 * <ol>
 * <li>No graph loaded. Correct today: nothing on the client loads one yet.</li>
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

    @Before
    @After
    public void resetStatics() {
        // Three statics, so any test that installs one must not leak it into the next.
        DumpPlugin.runtime = null;
        NodeActionsHolder.install(null);
        JeiBridge.indexFor(null);
    }

    // -- installation ---------------------------------------------------------------------

    @Test
    public void installingReplacesTheNoOpEvenWithNothingForItToDoYet() {
        // THE WHOLE REASON THE FALSE-ANSWERING VERSION IS INSTALLED RATHER THAN LEFT OUT. An
        // absent implementation and one that always says false give the player an identical
        // menu, so if registration were broken nobody would find out until the day a graph
        // landed -- and then they would be debugging two things at once.
        assertSame(NodeActions.NONE, NodeActionsHolder.actions());
        JeiNodeActions.install(JeiNodeActions.NO_GRAPH);
        assertNotSame(NodeActions.NONE, NodeActionsHolder.actions());
        assertTrue(NodeActionsHolder.actions() instanceof JeiNodeActions);
    }

    @Test
    public void theInstallLogSaysWhichOfTheTwoIndistinguishableStatesItIsIn() {
        // The line exists because "installed and answering false" and "never installed" give
        // the player an identical menu. If both branches ever said the same thing the line
        // would still be there, still be printed, and be worth nothing -- and no screenshot
        // or layout assertion in this repository could tell.
        JeiNodeActions.GraphSource wired = source(graphOf("minecraft:stick"));
        String blindly = JeiNodeActions.installMessage(JeiNodeActions.NO_GRAPH);

        assertNotEquals(blindly, JeiNodeActions.installMessage(wired));
        assertTrue(blindly.contains("no graph source"));
        // Names the source, so a log from a client that HAS one says which one.
        assertTrue(JeiNodeActions.installMessage(wired).contains(wired.getClass().getName()));
        assertEquals(blindly, JeiNodeActions.installMessage(null));
    }

    @Test
    public void aNullGraphSourceBecomesNoGraphRatherThanBeingKept() {
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

    private static RecipeGraph graphOf(String... keys) {
        GraphBuilder builder = new GraphBuilder();
        for (String key : keys) {
            builder.key(key);
        }
        return builder.build();
    }

    private static JeiNodeActions.GraphSource source(final RecipeGraph graph) {
        return new JeiNodeActions.GraphSource() {
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
