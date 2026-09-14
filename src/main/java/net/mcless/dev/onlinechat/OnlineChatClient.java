package net.mcless.dev.onlinechat;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point. Registers the mod config screen so users can edit the split config files
 * from the Mods menu.
 */
@Mod(value = OnlineChat.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = OnlineChat.MODID, value = Dist.CLIENT)
public class OnlineChatClient {
    public OnlineChatClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        OnlineChat.LOGGER.info("[OnlineChat] Client setup complete.");
    }
}
