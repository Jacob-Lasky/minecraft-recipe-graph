package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.common.CalculatorItem;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Item models, which only a client has.
 *
 * `@Mod.EventBusSubscriber(value = Side.CLIENT)` rather than a `@SideOnly` method on a common
 * class: the annotation is what stops FML registering this subscriber at all on a server, so
 * the class is never loaded there and `ModelLoader` never has to resolve.
 *
 * `ModelRegistryEvent` rather than preInit. Registering a model any earlier than this event
 * silently does nothing in 1.12.2 -- the item renders as the black-and-magenta missing
 * texture, which reads as a broken texture path rather than as wrong timing.
 */
@Mod.EventBusSubscriber(modid = RecipeDumpMod.MODID, value = Side.CLIENT)
public final class ClientRegistration {

    private ClientRegistration() {
    }

    @SubscribeEvent
    public static void onRegisterModels(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(
                CalculatorItem.INSTANCE, 0,
                new ModelResourceLocation(CalculatorItem.INSTANCE.getRegistryName(),
                                          "inventory"));
    }
}
