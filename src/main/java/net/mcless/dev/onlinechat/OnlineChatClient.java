package net.mcless.dev.onlinechat;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client-only entry point. On 1.20.1 the shared configuration screen (ConfigurationScreen)
 * does not exist yet, so this branch only logs the client setup; the config files remain
 * editable by hand.
 */
@OnlyIn(Dist.CLIENT)
@Mod(OnlineChat.MODID)
public class OnlineChatClient {
    public OnlineChatClient() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        OnlineChat.LOGGER.info("[OnlineChat] Client setup complete.");
    }
}
