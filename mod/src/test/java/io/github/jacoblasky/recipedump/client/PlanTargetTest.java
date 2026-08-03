package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.client.jei.PlannerHooks;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The listener the `=` keybind hands its target to, and the fact that anything installs it.
 *
 * WHAT THIS CANNOT REACH, and it is a hard wall rather than a shortcut not taken. Accepting a
 * target opens a window, which needs `Minecraft`, which needs LWJGL -- and merely LOADING that
 * class on a JUnit classpath throws `NoClassDefFoundError` before the first line of the method
 * runs. So every case here ends in the listener DECLINING, and the accepting path is exercised
 * only in game. `PlanTarget.accept` takes the book as an argument precisely so the decision
 * about WHICH refusals are refusals stays on this side of that wall.
 *
 * It is worth having anyway for two reasons. `PlanTargetKeybind` runs inside Forge's input
 * dispatch, where a throw costs the frame rather than the feature, so "declines" has to mean
 * declines and not raises. And "installed" versus "never installed" is the exact distinction
 * #191 was about: `hasTargetListener` is the only thing that reports it, and for the whole of
 * #19 phase 4 it answered false in the shipped mod while every test that asked installed one
 * first.
 */
public class PlanTargetTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    @After
    public void resetSeam() {
        // A static, so an installed listener must not leak into `JeiBridgeTest`, which asserts
        // the UNWIRED default and would then be asserting this test's leftovers.
        PlannerHooks.setTargetListener(null);
        // The listener declines while a solve is running, which is right and is also the FIRST
        // branch it takes -- so a service another test class left mid-plan would make every
        // case here pass without reaching the behaviour it names.
        PlannerService.get().reset();
    }

    @Test
    public void installingIsWhatTurnsTheKeybindFromANoOpIntoTheFeature() {
        // The whole of #191's first defect in three lines: without the install, the key
        // resolves a target and drops it, and nothing anywhere says so.
        assertFalse(PlannerHooks.hasTargetListener());
        PlanTarget.install();
        assertTrue(PlannerHooks.hasTargetListener());
    }

    @Test
    public void noPlayerMeansTheKeypressIsDeclinedRatherThanThrowing() {
        assertFalse(PlanTarget.accept(new ItemStack(Items.STICK), null));
    }

    @Test
    public void anUnkeyableStackIsDeclinedRatherThanPlanned() {
        // `DumpCommand.stackKey` answers null for a stack the dump could not key, and a key
        // the graph cannot contain is not something to open a window about. Asserted WITH a
        // book, so it is the key that decides rather than the missing player.
        assertFalse(PlanTarget.accept(ItemStack.EMPTY, new PlanBook()));
        assertFalse(PlanTarget.accept(null, new PlanBook()));
    }
}
