package me.speedy.fastreset.mixin;

import me.speedy.fastreset.FastResetKeybinds;
import me.speedy.fastreset.FastResetLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void handleFastResetToggle(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!FastResetKeybinds.matchesToggle(event)) return;

        Minecraft client = Minecraft.getInstance();
        boolean wasActive = FastResetLogic.isActive();
        FastResetLogic.toggleFromKey(client);

        if (wasActive || FastResetLogic.isActive()) {
            cir.setReturnValue(true);
        }
    }
}
