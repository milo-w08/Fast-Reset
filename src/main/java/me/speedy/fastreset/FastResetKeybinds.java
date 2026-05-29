package me.speedy.fastreset;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

public class FastResetKeybinds {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("fastreset", "fastreset")
    );

    private static KeyMapping toggleSearch;

    public static void register() {
        toggleSearch = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fastreset.seed_search",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_K,
                CATEGORY
        ));
    }

    public static void tick(Minecraft client) {
        if (client.screen != null) return;

        while (toggleSearch.consumeClick()) {
            FastResetLogic.toggleFromKey(client);
        }
    }

    public static boolean matchesToggle(KeyEvent event) {
        return toggleSearch != null && toggleSearch.matches(event);
    }
}
