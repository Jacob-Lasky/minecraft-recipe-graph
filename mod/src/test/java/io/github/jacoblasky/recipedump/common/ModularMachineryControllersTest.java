package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hellfirepvp.modularmachinery.common.machine.AbstractMachine;
import hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;

/**
 * Which machine a Modular Machinery controller currently IS, and the three ways it is none. #320
 *
 * <h2>WHAT MAKES THESE WORTH RUNNING, given that the subject is reflection into another mod</h2>
 *
 * "Mocked tests confirm parser shape, not wire shape" -- so a test built on a fake I invented would
 * prove I can read my own expectations back. What stops that here is that THE FAKES ARE DERIVED
 * FROM `javap` OUTPUT AGAINST THE JAR THE PACK ACTUALLY SHIPS, and both fake classes carry that
 * output in their headers so the derivation is checkable rather than claimed. What they test is the
 * half that is mine: the class and method NAMES, the formed gate, and the key the registry name is
 * turned into.
 *
 * WHAT THEY DO NOT TEST, said plainly rather than left to be assumed: that MM's live tile behaves
 * like the fake. Modular Machinery is not among the five jars the dev harness stages, so there is
 * no headless client that could host a real controller -- the live half of #320 phase 2 is proven
 * for the GENERIC tile-entity path (a vanilla furnace, in `ae2-placed`) and is PENDING a full-pack
 * prodshot for the MM path. Do not read a green run here as coverage of that.
 *
 * <h2>THE KEY IS THE ONE ASSERTION THAT COULD SILENTLY BE USELESS</h2>
 *
 * A formed controller has to produce `modularmachinery:&lt;registryname&gt;_controller`, because that
 * is what `recipegraph/multiblocks.py` builds the graph's machine candidates from -- all 189 MM
 * categories in the reference graph carry a catalyst naming exactly that. Any other spelling is a
 * key that matches nothing, and a machine that reads `buildable` while the player stands inside it
 * is #320's original symptom, unchanged. So the expected strings below are written out in full
 * rather than composed from the constant, which would agree with itself.
 */
public class ModularMachineryControllersTest {

    private static AbstractMachine machine(String registryName) {
        return new AbstractMachine(new ResourceLocation(registryName));
    }

    /** The whole point: a completed multiblock names the machine it has become. */
    @Test
    public void aFormedControllerContributesTheMachinesOwnKey() {
        Object formed = new TileMultiblockMachineController(
                true, machine("modularmachinery:dragonfire_crucible"));
        assertEquals("modularmachinery:dragonfire_crucible_controller",
                     ModularMachineryControllers.formedMachineKey(formed));
    }

    /**
     * And a half-built one names nothing.
     *
     * THE NEGATIVE THAT MAKES THE POSITIVE MEAN SOMETHING. Without the formed gate this would
     * report a machine for every controller block a player has ever placed, which is a claim that
     * they own 189 machines because they own one block. It is also the specific thing #320's phase-2
     * design asks for -- "check the FORMED state so a half-built structure does not read as an
     * owned machine".
     */
    @Test
    public void anUnformedControllerContributesNoMachineAtAll() {
        Object unformed = new TileMultiblockMachineController(
                false, machine("modularmachinery:dragonfire_crucible"));
        assertNull("a structure that is not formed is not a machine",
                   ModularMachineryControllers.formedMachineKey(unformed));
    }

    /**
     * Formed with no machine is a REAL state, not a defensive nicety.
     *
     * MM clears `foundMachine` when a structure check fails, and the two fields are written by
     * different code paths on different ticks -- so a controller can be seen formed with nothing
     * found. Returning a key built from a null would be a NullPointerException inside a player's
     * stock read; returning null is "no machine here", which is true.
     */
    @Test
    public void formedWithNoFoundMachineIsNotAMachine() {
        Object formed = new TileMultiblockMachineController(true, null);
        assertNull(ModularMachineryControllers.formedMachineKey(formed));
    }

    /**
     * Anything that is not a controller is not a controller.
     *
     * THE CONTROL FOR THE `isInstance` CHECK. The scan calls this once per loaded tile entity, so
     * on a real base the overwhelming majority of calls are furnaces and chests -- and a bridge
     * that answered for those would attribute a machine key to every block in the world.
     */
    @Test
    public void somethingElseEntirelyIsRefused() {
        assertNull(ModularMachineryControllers.formedMachineKey("not a tile entity"));
        assertNull(ModularMachineryControllers.formedMachineKey(new Object()));
        assertNull(ModularMachineryControllers.formedMachineKey(null));
    }

    /**
     * The suffix is appended to the WHOLE resource location, namespace included.
     *
     * `modularmachinery:x` -> `modularmachinery:x_controller`, which is `multiblocks.py`'s
     * `CONTROLLER = "modularmachinery:%s_controller"` applied to a bare registry name. A machine
     * registered under some other namespace therefore produces `other:x_controller`, which matches
     * no candidate -- a MISS rather than a false sighting, which is the direction to fail in.
     */
    @Test
    public void theSuffixLandsAfterTheNamespaceAndNotInsideIt() {
        Object formed = new TileMultiblockMachineController(
                true, machine("modularmachinery:mythic_processor_melter"));
        assertEquals("modularmachinery:mythic_processor_melter_controller",
                     ModularMachineryControllers.formedMachineKey(formed));
    }

    /**
     * The bridge reports itself resolvable, and says why when it is not.
     *
     * ON THIS CLASSPATH IT IS AVAILABLE, because the fakes are here -- so this asserts the
     * resolution path finds all four targets rather than silently latching a failure and answering
     * null for everything. Without it every other test in this file could pass by way of
     * `available()` returning false, which is the shape of a probe that cannot fail.
     */
    @Test
    public void theBridgeResolvesAndSaysSo() {
        assertTrue("the fakes are on this classpath, so resolution must succeed: "
                   + ModularMachineryControllers.unavailableBecause(),
                   ModularMachineryControllers.available());
        assertEquals("", ModularMachineryControllers.unavailableBecause());
    }
}
