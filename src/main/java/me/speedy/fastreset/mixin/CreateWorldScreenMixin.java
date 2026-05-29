package me.speedy.fastreset.mixin;

import me.speedy.fastreset.FastResetLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Shadow
    protected abstract void onCreate();

    @Unique
    private boolean handled = false;

    @Inject(method = "init", at = @At("TAIL"))
    private void init(CallbackInfo ci) {
        if (handled) return;
        if (!FastResetLogic.consumeCreateWorldRequest()) return;

        handled = true;
        Minecraft client = Minecraft.getInstance();
        CreateWorldScreen screen = (CreateWorldScreen)(Object)this;
        WorldCreationUiState creator = screen.getUiState();

        String name = FastResetLogic.prepareCandidateWorld(client);
        if (name == null) return;

        creator.setName(name);
        creator.setDifficulty(Difficulty.EASY);

        client.execute(this::onCreate);
    }
}
