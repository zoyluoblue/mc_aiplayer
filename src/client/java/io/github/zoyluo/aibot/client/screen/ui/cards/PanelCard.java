package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.client.screen.ui.PanelComponent;
import io.github.zoyluo.aibot.client.screen.ui.Theme;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

public abstract class PanelCard implements PanelComponent {
    protected int x;
    protected int y;
    protected int w;
    protected int h;
    protected BotSnapshotS2C snapshot;
    protected List<BotClientState.ChatLine> chat = List.of();

    @Override
    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    @Override
    public int preferredHeight() {
        return bodyHeight() + 22;
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        this.snapshot = snapshot;
        this.chat = chat == null ? List.of() : chat;
    }

    @Override
    public final void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer) {
        Theme.panel(context, x, y, w, h, Theme.CARD_BG);
        context.drawTextWithShadow(renderer, Theme.tr(titleKey()), x + Theme.PAD, y + 7, Theme.TEXT_STRONG);
        context.drawHorizontalLine(x + Theme.PAD, x + w - Theme.PAD - 1, y + 19, Theme.BORDER);
        renderBody(context, mouseX, mouseY, delta, renderer, x + Theme.PAD, y + 25, w - Theme.PAD * 2, h - 31);
    }

    protected abstract String titleKey();

    protected abstract int bodyHeight();

    protected abstract void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh);
}
