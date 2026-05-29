package me.speedy.fastreset.mixin;

import me.speedy.fastreset.FastResetLogic;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(
            method = "stopServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"
            )
    )
    private void skipRejectedCandidateSave(CallbackInfo ci) {
        FastResetLogic.prepareServerForRejectedCandidateShutdown((MinecraftServer)(Object)this);
    }
}
