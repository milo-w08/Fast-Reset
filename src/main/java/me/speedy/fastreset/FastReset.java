package me.speedy.fastreset;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class FastReset implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FastResetKeybinds.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FastResetKeybinds.tick(client);
            FastResetLogic.tick(client);
        });
    }
}
