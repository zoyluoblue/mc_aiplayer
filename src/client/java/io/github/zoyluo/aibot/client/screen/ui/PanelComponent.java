package io.github.zoyluo.aibot.client.screen.ui;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.List;
import java.util.function.Consumer;

public interface PanelComponent {
    void setBounds(int x, int y, int w, int h);

    int preferredHeight();

    void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat);

    void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer);

    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    default void addWidgets(Consumer<ClickableWidget> sink) {
    }
}
