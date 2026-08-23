package io.github.jacoblasky.recipedump.common;

import java.lang.reflect.Method;

import net.minecraft.util.ResourceLocation;

/**
 * Which Modular Machinery machine a controller tile entity currently IS, read reflectively.
 * #320 phase 2.
 *
 * <h2>WHY A CONTROLLER NEEDS ITS OWN ANSWER AT ALL</h2>
 *
 * Every Modular Machinery machine in the pack is the same block. `modularmachinery:blockcontroller`
 * is one item, one tile entity class, and 189 different machines depending on what has been built
 * around it -- so the generic "key a tile entity by its block's item" path in
 * {@link PlacedTileEntities} answers `modularmachinery:blockcontroller` for a Dragonfire Crucible,
 * a Mythic Processor and a bare controller sitting on a hill alike. That is a true statement about
 * a block and a useless one about a machine: the graph's machine candidates are
 * `modularmachinery:&lt;registryname&gt;_controller`, and none of them is `blockcontroller`.
 *
 * Jake's words on #320, which is why phase 1's grid walk was not the end of it: *"the modular
 * machines are built with the multiblock ... the entire structure is completed and you can see the
 * machine"*. Measured on #320: ModularMachinery-CE only REFERENCES `IGridHost` and declares it on
 * nothing, so a controller is not a grid node and phase 1 could never see one.
 *
 * <h2>FORMED IS THE WHOLE GATE, and it is MM's own answer</h2>
 *
 * A controller with no structure around it is a block, not a machine. `isStructureFormed()` is
 * Modular Machinery's own verdict on that -- the same one it uses to decide whether the machine
 * may run -- so a half-built multiblock contributes nothing here and the player's own progress
 * decides when it starts counting. An unformed controller still reaches the player as
 * `modularmachinery:blockcontroller` through the generic path, which is the honest claim about it:
 * you own the controller block, not the machine.
 *
 * <h2>REFLECTION, FOR {@link io.github.jacoblasky.recipedump.ModularMachineryBridge}'S REASON</h2>
 *
 * A direct call is a class reference in a signature, and a 1.12.2 client resolves those eagerly
 * enough that naming an MM type here would stop this class -- and therefore every stock read --
 * from loading on a pack without Modular Machinery. MM is a {@code compileOnly} absence: it is not
 * among the five jars the dev harness stages, so there is nothing to compile against even here.
 *
 * <h2>SEPARATE FROM `ModularMachineryBridge` ON PURPOSE. DO NOT MERGE THEM.</h2>
 *
 * That class answers a DIFFERENT question (which machine does this blueprint ITEM build) from
 * different targets (`MachineRegistry.getLoadedMachines`, `ItemBlueprint.getAssociatedMachineKey`),
 * for the dump, on the client. Two reasons not to fold this into it:
 *
 * <ul>
 * <li>Its {@code available()} is gated on `modularmachinery:itemblueprint` being registered. A
 *     controller's formed state has nothing to do with the blueprint item, so sharing that gate
 *     would silently disable this whole path on any pack that renamed or removed the blueprint.</li>
 * <li>It runs once per dump; this runs once per placed controller per stock read, on the server
 *     thread. The resolution caches want different lifetimes and the failure strings want
 *     different words.</li>
 * </ul>
 *
 * <h2>Targets verified against the pack's own jar, not guessed</h2>
 *
 * `javap -p` against `ModularMachinery-CE-2.2.2.jar` (the jar MeatballCraft ships), 2026-08-22:
 *
 * <pre>
 *   hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController
 *     public boolean isStructureFormed();                             descriptor ()Z
 *     public ...machine.DynamicMachine getFoundMachine();
 *   hellfirepvp.modularmachinery.common.machine.AbstractMachine       (DynamicMachine's super)
 *     public net.minecraft.util.ResourceLocation getRegistryName();
 * </pre>
 *
 * The BASE tile class and the ABSTRACT machine class, not `TileMachineController` and
 * `DynamicMachine`: `TileFactoryController` and `TileParallelController` are the other two
 * concrete controllers in that jar and all three extend the base, so naming the base is what makes
 * a factory controller count. Same argument `ModularMachineryBridge.machines()` makes for reading
 * through `AbstractMachine`'s getters.
 */
public final class ModularMachineryControllers {

    private static final String CONTROLLER_CLASS =
            "hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController";
    private static final String MACHINE_CLASS =
            "hellfirepvp.modularmachinery.common.machine.AbstractMachine";

    /**
     * How the graph spells a machine's controller item.
     *
     * ONE STRING, AND IT IS THE PYTHON SIDE'S. `recipegraph/multiblocks.py` builds both the
     * category (`modularmachinery.recipes.&lt;reg&gt;`) and the controller item
     * (`modularmachinery:&lt;reg&gt;_controller`) from the machine's `registryname`, and its own
     * comment records that all 189 MM categories in the reference graph carry a catalyst naming
     * exactly this. So a formed controller's key is its registry name with this appended -- if the
     * suffix were wrong the key would simply never match a candidate, which is a miss and not a
     * false sighting.
     */
    private static final String CONTROLLER_SUFFIX = "_controller";

    private static Class<?> controllerClass;
    private static Method isStructureFormed;
    private static Method getFoundMachine;
    private static Method getRegistryName;
    private static boolean resolved;
    private static String absence = "not resolved yet";

    private ModularMachineryControllers() {
    }

    /**
     * Look the four targets up once.
     *
     * SYNCHRONIZED AND LATCHED ON `resolved`, like the other two bridges. The stock read runs on
     * the server thread and a dump runs on the client thread; nothing stops the two overlapping,
     * and `Class.forName` twice is cheap while a half-populated set of Methods is a
     * NullPointerException in the middle of somebody's plan.
     *
     * EVERY FAILURE IS A SENTENCE, not a swallowed exception. `absence` is what
     * {@link #unavailableBecause} reports, so "no MM machine read as owned" can be told apart
     * from "MM is not installed" without attaching a debugger.
     */
    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            controllerClass = Class.forName(CONTROLLER_CLASS);
            isStructureFormed = controllerClass.getMethod("isStructureFormed");
            getFoundMachine = controllerClass.getMethod("getFoundMachine");
            getRegistryName = Class.forName(MACHINE_CLASS).getMethod("getRegistryName");
            absence = "";
        } catch (ClassNotFoundException notInstalled) {
            absence = "Modular Machinery is not installed";
            controllerClass = null;
        } catch (Throwable t) {
            absence = "Modular Machinery present but its controller tile did not resolve: " + t;
            controllerClass = null;
        }
    }

    /** True when a controller's formed machine can be read. */
    public static boolean available() {
        resolve();
        return controllerClass != null && isStructureFormed != null && getFoundMachine != null
                && getRegistryName != null;
    }

    /** Why {@link #available} is false, or "" when it is true. */
    public static String unavailableBecause() {
        resolve();
        return absence;
    }

    /**
     * The graph key for the machine this tile entity currently IS, or null.
     *
     * Null for: a tile that is not an MM controller, an MM that is not installed, a controller
     * whose structure is not formed, and a formed controller whose machine or registry name MM
     * answers oddly for. EVERY ONE OF THOSE IS "no machine here", which is the safe direction --
     * the alternative is claiming a player owns a machine they have not finished building.
     *
     * NEVER THROWS. This is called once per loaded tile entity on the server thread during a
     * player's stock read; a reflective surprise from one mod's tile must not take the read down,
     * for the reason `Ae2Stock.read` catches `Throwable` around the grid walk.
     *
     * TAKES `Object` AND NOT `TileEntity`, deliberately. Nothing in here touches a tile entity as a
     * tile entity -- every access is reflective and MM's controller is reached by name -- so the
     * narrower type would buy no safety and would cost the only way to test this without a running
     * game: a fake declared at MM's own class name, which is what `Class.forName` resolves and what
     * `ModularMachineryControllersTest` builds from the real jar's `javap` output. DO NOT narrow it
     * back; the reflection targets are the contract here, not the parameter's type.
     */
    public static String formedMachineKey(Object tile) {
        if (tile == null || !available() || !controllerClass.isInstance(tile)) {
            return null;
        }
        try {
            Object formed = isStructureFormed.invoke(tile);
            if (!(formed instanceof Boolean) || !((Boolean) formed).booleanValue()) {
                return null;
            }
            Object machine = getFoundMachine.invoke(tile);
            if (machine == null) {
                // FORMED WITH NO MACHINE IS A REAL STATE to guard rather than an impossible one:
                // MM clears `foundMachine` when a structure check fails, and the two fields are
                // written by different code paths on different ticks.
                return null;
            }
            Object id = getRegistryName.invoke(machine);
            if (!(id instanceof ResourceLocation)) {
                return null;
            }
            String registry = id.toString();
            return registry.isEmpty() ? null : registry + CONTROLLER_SUFFIX;
        } catch (Throwable t) {
            return null;
        }
    }

    // THERE IS DELIBERATELY NO `forgetResolution()` HERE, and one was written and deleted in
    // review. It claimed to let a test observe the MM-ABSENT case, and it cannot: resolution goes
    // through `Class.forName`, the test classpath carries fakes at MM's own names
    // (`ModularMachineryControllersTest` needs them), and forgetting a latch does not unload a
    // class. So every caller it could ever have had would be re-resolving to the same present
    // state -- a seam with no reachable purpose, which is the written-and-never-called shape
    // `SeamInstallationTest.everyNewJoinOfThisBatchIsActuallyCalledFromProduction` exists to
    // catch, one level down. DO NOT add it back without a test that its presence makes possible.
    //
    // Reaching the absent branch for real needs a child-first classloader that hides
    // `hellfirepvp.**`, which is a harness nobody has asked for; until then the branch is covered
    // by inspection and `unavailableBecause()` names itself in the probe log on every run.
}
