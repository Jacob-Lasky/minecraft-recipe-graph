package io.github.jacoblasky.recipedump.client.jei;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.DumpCommand;
import io.github.jacoblasky.recipedump.DumpPlugin;
import io.github.jacoblasky.recipedump.graph.GraphBuilder;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The JEI wiring's behaviour WITHOUT a JEI runtime, which is the state a unit test can
 * actually reach -- and, more importantly, a state real clients are in.
 *
 * Since #19 Phase 2 the mod declares `after:jei` rather than `required-after`, so it loads on
 * a client with no JEI at all. Every one of these paths therefore has to answer rather than
 * throw, and "answer" has to be distinguishable from "worked": a keybind that quietly
 * succeeds at nothing is worse than one that reports it did nothing.
 *
 * WHAT THIS CANNOT COVER, stated rather than implied: opening a recipe screen, reading the
 * ingredient under the mouse, and JEI actually laying out around the planner all require a
 * live runtime. Those belong to the screenshot harness, and the PR says which of them were
 * exercised there and which were not.
 */
public class JeiBridgeTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    @After
    public void resetSeams() {
        // Statics, so a test that installs a seam must not leak it into the next one. Both
        // are reset to their defaults by passing null, which is the documented contract.
        PlannerHooks.setAreaSource(null);
        PlannerHooks.setTargetListener(null);
        DumpPlugin.runtime = null;
    }

    // -- degrading without JEI ------------------------------------------------------------

    @Test
    public void withNoRuntimeEverythingAnswersInsteadOfThrowing() {
        RecipeGraph graph = new GraphBuilder().build();
        assertFalse(JeiBridge.isAvailable());
        assertNull(JeiBridge.ingredientUnderMouse());
        assertFalse(JeiBridge.showRecipesFor(new ItemStack(Items.STICK)));
        assertFalse(JeiBridge.showRecipesFor(0, graph));
    }

    @Test
    public void showingARecipeForNothingIsFalseRatherThanAnError() {
        assertFalse(JeiBridge.showRecipesFor(null));
        assertFalse(JeiBridge.showRecipesFor(ItemStack.EMPTY));
    }

    @Test
    public void theKeybindReportsThatItDidNothingRatherThanPretending() {
        // Four ways to do nothing and they are deliberately indistinguishable to the PLAYER.
        // They must not be indistinguishable to a test.
        assertFalse(PlanTargetKeybind.onPressed());
    }

    // -- the index, without a runtime to build it from -----------------------------------------

    @Test
    public void theIndexIsEmptyWithoutJeiAndEveryLookupIsAMiss() {
        // No ingredient registry means no item list, which means an empty index -- not a
        // crash, and not a half-built one.
        GraphBuilder b = new GraphBuilder();
        int stick = b.key("minecraft:stick");
        RecipeGraph graph = b.build();

        JeiBridge.indexFor(graph);
        assertNull(JeiBridge.stackFor(stick, graph));
        assertEquals(0, JeiBridge.indexOf(graph).resolvedCount());
    }

    @Test
    public void askingForADifferentGraphRebuildsTheIndexRatherThanServingTheOldOne() {
        // A stale index would answer with stacks keyed against a graph nobody is planning
        // against, which is a wrong answer that looks like a right one.
        RecipeGraph first = new GraphBuilder().build();
        GraphBuilder b = new GraphBuilder();
        b.key("minecraft:stick");
        RecipeGraph second = b.build();

        assertSame(JeiBridge.indexOf(first), JeiBridge.indexOf(first));
        assertNotSame(JeiBridge.indexOf(first), JeiBridge.indexOf(second));
    }

    // -- the seams -----------------------------------------------------------------------------

    @Test
    public void anUnwiredPlannerReportsNoScreenAreaSoJeiLaysOutExactlyAsBefore() {
        // The honest default. Handing JEI a guessed rectangle would push its item list aside
        // on screens the planner has nothing to do with.
        assertTrue(new PlannerGuiHandler().getGuiExtraAreas().isEmpty());
        assertFalse(PlannerHooks.hasTargetListener());
    }

    @Test
    public void aWiredAreaSourceIsWhatJeiIsToldToAvoid() {
        final List<Rectangle> panel =
                Collections.singletonList(new Rectangle(12, 34, 100, 200));
        PlannerHooks.setAreaSource(new PlannerHooks.AreaSource() {
            @Override
            public List<Rectangle> occupiedAreas() {
                return panel;
            }
        });
        assertEquals(panel, new PlannerGuiHandler().getGuiExtraAreas());
    }

    @Test
    public void settingASeamToNullRestoresTheDefaultRatherThanInstallingNull() {
        // JEI calls the area source every frame, so a null in there is a crash per frame.
        PlannerHooks.setAreaSource(new PlannerHooks.AreaSource() {
            @Override
            public List<Rectangle> occupiedAreas() {
                return Arrays.asList(new Rectangle(1, 2, 3, 4));
            }
        });
        PlannerHooks.setAreaSource(null);
        assertTrue(new PlannerGuiHandler().getGuiExtraAreas().isEmpty());

        PlannerHooks.setTargetListener(new PlannerHooks.TargetListener() {
            @Override
            public boolean onPlanTarget(ItemStack stack) {
                return true;
            }
        });
        assertTrue(PlannerHooks.hasTargetListener());
        PlannerHooks.setTargetListener(null);
        assertFalse(PlannerHooks.hasTargetListener());
    }

    @Test
    public void aWiredListenerReceivesTheStackAndItsRefusalIsRespected() {
        final ItemStack[] seen = new ItemStack[1];
        PlannerHooks.setTargetListener(new PlannerHooks.TargetListener() {
            @Override
            public boolean onPlanTarget(ItemStack stack) {
                seen[0] = stack;
                // A listener that declines -- the planner is mid-solve, say -- must leave the
                // player where they are rather than have the caller override it.
                return false;
            }
        });
        ItemStack stick = new ItemStack(Items.STICK);
        assertTrue(PlannerHooks.hasTargetListener());
        // Same package, so the hand-off the keybind uses is reachable with no input queue.
        assertFalse(PlannerHooks.deliver(stick));
        assertSame(stick, seen[0]);
    }

    // -- keying an ingredient the player is pointing at ------------------------------------------

    @Test
    public void anIngredientKeysTheSameWayTheDumpKeyedIt() {
        // The contract the whole of Phase 4 rests on: what the player points at has to land
        // on the key the graph holds, which is only true because both go through `stackKey`.
        ItemStack stick = new ItemStack(Items.STICK);
        GraphBuilder b = new GraphBuilder();
        int stickKey = b.key(DumpCommand.stackKey(stick));
        RecipeGraph graph = b.build();
        assertEquals("minecraft:stick", JeiBridge.keyFor(stick, graph));
        assertEquals("minecraft:stick", graph.key(stickKey));
    }




    @Test
    public void theKeyFallsBackToTheBaseItemForAnNbtVariantTheGraphNeverSaw() {
        ItemStack named = new ItemStack(Items.STICK);
        named.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
        named.getTagCompound().setString("Species", "NeverDumped");

        GraphBuilder b = new GraphBuilder();
        b.key("minecraft:stick");
        RecipeGraph graph = b.build();
        assertNotEquals("minecraft:stick", DumpCommand.stackKey(named));
        assertEquals("minecraft:stick", JeiBridge.keyFor(named, graph));
    }

    @Test
    public void theKeyIsNullWhenNothingResolvesRatherThanAGuess() {
        RecipeGraph empty = new GraphBuilder().build();
        assertNull(JeiBridge.keyFor(new ItemStack(Items.STICK), empty));
        assertNull(JeiBridge.keyFor(ItemStack.EMPTY, empty));
        assertNull(JeiBridge.keyFor(new ItemStack(Items.STICK), null));
    }

    // -- the surfaces, without a runtime to read them -------------------------------------

    @Test
    public void withNoRuntimeThereIsNoHoveredIngredientAndNoSurfaceToName() {
        assertNull(JeiBridge.hovered());
        assertNull(JeiBridge.ingredientUnderMouse());
    }

    @Test
    public void filteringWithoutJeiAnswersFalseRatherThanThrowing() {
        // The harness types into JEI's search box to make a probe reproducible. On a client
        // with no JEI there is no box, and saying so is the contract every method here keeps.
        assertFalse(JeiBridge.filterTo("@minecraft hopper"));
        assertFalse(JeiBridge.filterTo(null));
    }
}
