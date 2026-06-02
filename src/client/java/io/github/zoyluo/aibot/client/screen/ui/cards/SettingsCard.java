package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.client.BotCommandBridge;
import io.github.zoyluo.aibot.client.screen.ui.Theme;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;

public final class SettingsCard extends PanelCard {
    private static final int BUTTON_H = 18;

    private final String target;
    private ButtonWidget manualButton;
    private ButtonWidget memoryButton;
    private ButtonWidget reportsButton;
    private ButtonWidget teleportToButton;  // 传送至 AI(玩家→AI 附近)
    private ButtonWidget recallButton;      // 召回 AI(AI→玩家附近)

    public SettingsCard(String target) {
        this.target = target == null ? "" : target;
    }

    @Override
    protected String titleKey() {
        return "card.aibot.settings";
    }

    @Override
    protected int bodyHeight() {
        return 118;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        super.refresh(snapshot, chat);
        updateLabels();
    }

    @Override
    public void addWidgets(Consumer<ClickableWidget> sink) {
        manualButton = button("settings.aibot.manual", () -> toggle("manual", snapshot == null || !snapshot.manualMode()));
        memoryButton = button("settings.aibot.memory", () -> toggle("memory", snapshot == null || !snapshot.memoryToolsEnabled()));
        reportsButton = button("settings.aibot.reports", () -> toggle("reports", snapshot == null || !snapshot.verboseReportsEnabled()));
        teleportToButton = button("settings.aibot.tp_to_ai",
                () -> BotCommandBridge.teleport(target, io.github.zoyluo.aibot.network.payload.BotTeleportC2S.TO_AI));
        recallButton = button("settings.aibot.recall_ai",
                () -> BotCommandBridge.teleport(target, io.github.zoyluo.aibot.network.payload.BotTeleportC2S.RECALL_AI));
        layoutWidgets();
        updateLabels();
        sink.accept(teleportToButton);
        sink.accept(recallButton);
        sink.accept(manualButton);
        sink.accept(memoryButton);
        sink.accept(reportsButton);
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        String bot = snapshot == null ? (target.isBlank() ? Theme.tr("screen.aibot.owner_bot") : target) : snapshot.botName();
        context.drawTextWithShadow(renderer, Theme.tr("settings.aibot.target", bot), bx, by, Theme.TEXT_DIM);
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("status.aibot.waiting"), bx, by + 14, Theme.TEXT_DIM);
        }
    }

    private ButtonWidget button(String key, Runnable action) {
        return ButtonWidget.builder(Text.translatable(key), button -> action.run()).dimensions(0, 0, 120, BUTTON_H).build();
    }

    private void toggle(String key, boolean value) {
        BotCommandBridge.setOption(target, key, value);
    }

    private void layoutWidgets() {
        if (teleportToButton == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int bw = w - Theme.PAD * 2;
        // 传送行:两个按钮横排(各占一半)。
        int half = (bw - 4) / 2;
        int tpY = y + 32;
        teleportToButton.setPosition(bx, tpY);
        teleportToButton.setDimensions(half, BUTTON_H);
        recallButton.setPosition(bx + half + 4, tpY);
        recallButton.setDimensions(half, BUTTON_H);
        // 三个开关竖排。
        int by = tpY + 24;
        manualButton.setPosition(bx, by);
        manualButton.setDimensions(bw, BUTTON_H);
        memoryButton.setPosition(bx, by + 22);
        memoryButton.setDimensions(bw, BUTTON_H);
        reportsButton.setPosition(bx, by + 44);
        reportsButton.setDimensions(bw, BUTTON_H);
    }

    private void updateLabels() {
        if (manualButton == null) {
            return;
        }
        manualButton.setMessage(label("settings.aibot.manual", snapshot != null && snapshot.manualMode()));
        memoryButton.setMessage(label("settings.aibot.memory", snapshot == null || snapshot.memoryToolsEnabled()));
        reportsButton.setMessage(label("settings.aibot.reports", snapshot == null || snapshot.verboseReportsEnabled()));
    }

    private static Text label(String key, boolean enabled) {
        return Text.literal(Theme.tr(key) + ": " + Theme.tr(enabled ? "settings.aibot.on" : "settings.aibot.off"));
    }
}
