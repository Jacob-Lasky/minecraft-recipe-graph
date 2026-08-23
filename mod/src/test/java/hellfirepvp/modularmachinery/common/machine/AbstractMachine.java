package hellfirepvp.modularmachinery.common.machine;

import net.minecraft.util.ResourceLocation;

/**
 * A FAKE of Modular Machinery's `AbstractMachine`, for
 * {@link io.github.jacoblasky.recipedump.common.ModularMachineryControllersTest}. #320 phase 2
 *
 * <h2>WHY THIS LIVES AT MODULAR MACHINERY'S OWN FULLY-QUALIFIED NAME</h2>
 *
 * `ModularMachineryControllers` reaches MM BY REFLECTION, on purpose -- a compile dependency would
 * stop the mod loading on a pack without MM, which is the trap both existing bridges document at
 * length. That means the class NAME is the contract, and a name is not something a mock can stand
 * in for: `Class.forName("hellfirepvp...AbstractMachine")` either finds a class at that name or it
 * does not. So the only way to exercise the reflection is to put a class there, on the TEST
 * classpath, and the only way for the test to be worth anything is for its shape to come from the
 * real jar rather than from what I expected the real jar to look like.
 *
 * <h2>THE SHAPE IS `javap` OUTPUT, NOT A GUESS. Verified 2026-08-22 against the pack's own jar</h2>
 *
 * `javap -p -cp ModularMachinery-CE-2.2.2.jar hellfirepvp.modularmachinery.common.machine.AbstractMachine`
 * (the jar MeatballCraft ships, read out of the AMP instance's `mods/`):
 *
 * <pre>
 *   public abstract class hellfirepvp.modularmachinery.common.machine.AbstractMachine {
 *     protected final net.minecraft.util.ResourceLocation registryName;
 *     public net.minecraft.util.ResourceLocation getRegistryName();
 *     public java.lang.String getLocalizedName();
 *     ...
 *   }
 * </pre>
 *
 * Only `getRegistryName` is reflected on, so only it is reproduced. IF THIS TEST EVER PASSES WHILE
 * THE GAME FAILS, the difference will be in that signature, and re-running the `javap` above is the
 * whole of the investigation. Do not "improve" the fake by adding methods MM does not have or by
 * changing a return type to something more convenient -- the fidelity is the entire value.
 *
 * NOT abstract, unlike the real one: the test needs to construct it, and abstractness is not part
 * of what the reflection asks about.
 */
public class AbstractMachine {

    private final ResourceLocation registryName;

    public AbstractMachine(ResourceLocation registryName) {
        this.registryName = registryName;
    }

    public ResourceLocation getRegistryName() {
        return registryName;
    }
}
