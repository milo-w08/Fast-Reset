package me.speedy.fastreset.mixin;

import me.speedy.fastreset.FastResetLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.quickplay.QuickPlayLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuickPlayLog.class)
public abstract class QuickPlayLogMixin {

    @Inject(method = "log", at = @At("HEAD"), cancellable = true)
    private void skipFastResetQuickPlayLog(Minecraft client, CallbackInfo ci) {
        if (FastResetLogic.isActive()) {
            ci.cancel();
        }
    }
}
