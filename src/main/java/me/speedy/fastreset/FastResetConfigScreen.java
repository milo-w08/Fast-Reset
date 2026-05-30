package me.speedy.fastreset;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class FastResetConfigScreen extends Screen {

    private final Screen parent;

    public FastResetConfigScreen(Screen parent) {
        super(Component.literal("FastReset Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int left = center - 100;
        int y = this.height / 4;

        this.addRenderableWidget(new StringWidget(left, y - 28, 200, 20, this.title, this.font));

        this.addRenderableWidget(new IntSlider(
                left,
                y,
                200,
                20,
                FastResetConfig.MIN_DESERT_LIMIT,
                FastResetConfig.MAX_DESERT_LIMIT,
                FastResetConfig.desertLimit(),
                value -> value == 0
                        ? Component.literal("Deserts to keep: Unlimited")
                        : Component.literal("Deserts to keep: " + value),
                FastResetConfig::setDesertLimit
        ));

        this.addRenderableWidget(new IntSlider(
                left,
                y + 28,
                200,
                20,
                FastResetConfig.MIN_PARALLEL_WORLDS,
                FastResetConfig.MAX_PARALLEL_WORLDS,
                FastResetConfig.parallelWorlds(),
                value -> Component.literal("Parallel worlds: " + value),
                FastResetConfig::setParallelWorlds
        ));

        this.addRenderableWidget(
                Button.builder(Component.literal("Done"), button -> this.onClose())
                        .bounds(left, y + 68, 200, 20)
                        .build()
        );
    }

    @Override
    public void onClose() {
        FastResetConfig.save(Minecraft.getInstance());
        Minecraft.getInstance().setScreen(this.parent);
    }

    private static class IntSlider extends AbstractSliderButton {

        private final int min;
        private final int max;
        private final IntFunction<Component> labelFactory;
        private final IntConsumer valueConsumer;

        private IntSlider(int x, int y, int width, int height, int min, int max, int value,
                          IntFunction<Component> labelFactory, IntConsumer valueConsumer) {
            super(x, y, width, height, Component.empty(), toSliderValue(value, min, max));
            this.min = min;
            this.max = max;
            this.labelFactory = labelFactory;
            this.valueConsumer = valueConsumer;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(this.labelFactory.apply(this.currentValue()));
        }

        @Override
        protected void applyValue() {
            this.valueConsumer.accept(this.currentValue());
        }

        private int currentValue() {
            return this.min + (int) Math.round(this.value * (this.max - this.min));
        }

        private static double toSliderValue(int value, int min, int max) {
            if (max <= min) return 0.0D;
            return (double) (Math.max(min, Math.min(max, value)) - min) / (double) (max - min);
        }
    }
}
