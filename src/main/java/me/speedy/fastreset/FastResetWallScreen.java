package me.speedy.fastreset;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class FastResetWallScreen extends Screen {

    private static final int MAX_COLUMNS = 4;
    private static final int GAP = 6;
    private static final int MARGIN = 16;
    private static final int TOP_SPACE = 42;
    private static final int BOTTOM_SPACE = 34;

    private final List<Button> slots = new ArrayList<>();
    private StringWidget summary;

    public FastResetWallScreen() {
        super(Component.literal("FastReset Wall"));
    }

    @Override
    protected void init() {
        this.slots.clear();

        this.addRenderableWidget(new StringWidget(0, 8, this.width, 20, Component.literal("FastReset Wall"), this.font));

        this.summary = new StringWidget(0, 24, this.width, 14, FastResetLogic.getWallSummary(), this.font);
        this.addRenderableWidget(this.summary);

        int slotCount = FastResetConfig.parallelWorlds();
        int columns = Math.min(MAX_COLUMNS, Math.max(1, (int) Math.ceil(Math.sqrt(slotCount))));
        int rows = Math.max(1, (int) Math.ceil((double) slotCount / columns));
        int slotWidth = Math.max(80, (this.width - MARGIN * 2 - GAP * (columns - 1)) / columns);
        int slotHeight = Math.max(24, (this.height - TOP_SPACE - BOTTOM_SPACE - GAP * (rows - 1)) / rows);
        int startX = (this.width - (slotWidth * columns + GAP * (columns - 1))) / 2;
        int startY = TOP_SPACE;

        for (int index = 0; index < slotCount; index++) {
            int column = index % columns;
            int row = index / columns;
            Button slot = Button.builder(Component.empty(), button -> {})
                    .bounds(startX + column * (slotWidth + GAP), startY + row * (slotHeight + GAP), slotWidth, slotHeight)
                    .build();
            slot.active = false;
            this.slots.add(slot);
            this.addRenderableWidget(slot);
        }

        this.addRenderableWidget(
                Button.builder(Component.literal("Stop Search"), button -> {
                            FastResetLogic.requestStopFromButton();
                            button.active = false;
                        })
                        .bounds(this.width / 2 - 60, this.height - 26, 120, 20)
                        .build()
        );

        this.refresh();
    }

    @Override
    public void tick() {
        if (!FastResetLogic.isActive()) {
            Minecraft.getInstance().setScreen(new TitleScreen());
            return;
        }

        this.refresh();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void refresh() {
        if (this.summary != null) {
            this.summary.setMessage(FastResetLogic.getWallSummary());
        }

        List<FastResetLogic.WallSlot> wallSlots = FastResetLogic.getWallSlots();
        for (int index = 0; index < this.slots.size(); index++) {
            FastResetLogic.WallSlot slot = index < wallSlots.size()
                    ? wallSlots.get(index)
                    : new FastResetLogic.WallSlot(index + 1, "Empty", "Waiting", false);
            this.slots.get(index).setMessage(Component.literal(slot.label()));
        }
    }
}
