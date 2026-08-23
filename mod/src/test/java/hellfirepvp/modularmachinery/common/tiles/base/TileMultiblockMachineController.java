package hellfirepvp.modularmachinery.common.tiles.base;

import hellfirepvp.modularmachinery.common.machine.AbstractMachine;

/**
 * A FAKE of Modular Machinery's controller tile, for
 * {@link io.github.jacoblasky.recipedump.common.ModularMachineryControllersTest}. #320 phase 2
 *
 * See {@link AbstractMachine}'s header for why a fake at MM's own fully-qualified name is the only
 * way to test a reflective bridge, and for the discipline that keeps the fake honest.
 *
 * <h2>THE SHAPE IS `javap` OUTPUT. Verified 2026-08-22 against ModularMachinery-CE-2.2.2.jar</h2>
 *
 * <pre>
 *   public abstract class hellfirepvp.modularmachinery.common.tiles.base
 *           .TileMultiblockMachineController extends ...TileEntityRestrictedTick
 *           implements ...SelectiveUpdateTileEntity, ...IMachineController, ...IAnimatable {
 *     public boolean isStructureFormed();                                    descriptor ()Z
 *     public ...machine.DynamicMachine getFoundMachine();
 *     public void setFoundMachine(...machine.DynamicMachine);
 *     protected ...machine.DynamicMachine foundMachine;
 *     ...
 *   }
 * </pre>
 *
 * TWO DELIBERATE DIVERGENCES FROM THE REAL CLASS, and neither touches what is reflected on:
 *
 * <ul>
 * <li>It extends nothing. The real one extends `TileEntityRestrictedTick`, which extends
 *     `net.minecraft.tileentity.TileEntity` -- and `ModularMachineryControllers.formedMachineKey`
 *     takes `Object` precisely so that this test does not have to stand up a tile entity, a world
 *     and a position to ask a question about a registry name. Its javadoc says so.</li>
 * <li>`getFoundMachine` is typed {@link AbstractMachine} rather than `DynamicMachine`. The real
 *     `DynamicMachine` extends `AbstractMachine` and the production code reads `getRegistryName`
 *     off the ABSTRACT base for exactly that reason, so the reflective call sees the same method on
 *     either. Reproducing MM's two-level hierarchy would add a class and test nothing more.</li>
 * </ul>
 *
 * The three states a controller can be in are all reachable from here, which is what the test
 * needs: formed with a machine, formed with none (MM clears `foundMachine` when a structure check
 * fails, so this is a real state and not a contrived one), and not formed.
 */
public class TileMultiblockMachineController {

    private final boolean structureFormed;
    private final AbstractMachine foundMachine;

    public TileMultiblockMachineController(boolean structureFormed, AbstractMachine foundMachine) {
        this.structureFormed = structureFormed;
        this.foundMachine = foundMachine;
    }

    public boolean isStructureFormed() {
        return structureFormed;
    }

    public AbstractMachine getFoundMachine() {
        return foundMachine;
    }
}
