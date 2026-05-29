package me.speedy.fastreset.mixin;

import me.speedy.fastreset.FastResetLogic;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addStopSearchButton(CallbackInfo ci) {
        if (!FastResetLogic.isActive()) return;

        this.addRenderableWidget(
                Button.builder(Component.literal("Stop Search"), button -> {
                            FastResetLogic.requestStopFromButton();
                            button.active = false;
                        })
                        .bounds(5, 29, 120, 20)
                        .build()
        );
    }
}
