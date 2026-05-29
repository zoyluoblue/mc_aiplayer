package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.BotCommandBridge;
import io.github.zoyluo.aibot.client.screen.ui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

public final class QuickActionCard extends PanelCard {
    private static final int INPUT_H = 18;

    private final String target;
    private TextFieldWidget idField;
    private TextFieldWidget countField;
    private ButtonWidget comeButton;
    private ButtonWidget stopButton;
    private ButtonWidget eatButton;
    private ButtonWidget sleepButton;
    private ButtonWidget mineButton;
    private ButtonWidget craftButton;
    private ButtonWidget smeltButton;

    public QuickActionCard(String target) {
        this.target = target == null ? "" : target;
    }

    @Override
    protected String titleKey() {
        return "card.aibot.quick";
    }

    @Override
    protected int bodyHeight() {
        return 82;
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        layoutWidgets();
    }

    @Override
    public void addWidgets(Consumer<ClickableWidget> sink) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        idField = new TextFieldWidget(renderer, 0, 0, 84, INPUT_H, Text.translatable("quick.aibot.id"));
        idField.setText("minecraft:stone");
        idField.setMaxLength(128);
        idField.setSuggestion(Theme.tr("quick.aibot.id"));
        countField = new TextFieldWidget(renderer, 0, 0, 36, INPUT_H, Text.translatable("quick.aibot.count"));
        countField.setText("1");
        countField.setMaxLength(3);
        countField.setSuggestion(Theme.tr("quick.aibot.count"));

        comeButton = button("btn.aibot.come", () -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                BotCommandBridge.command(target, "move", client.player.getBlockPos().toShortString().replace(",", ""), "", 1);
            }
        });
        stopButton = button("btn.aibot.stop", () -> BotCommandBridge.command(target, "abort", "", "", 1));
        eatButton = button("btn.aibot.eat", () -> BotCommandBridge.command(target, "eat", "", "", 1));
        sleepButton = button("btn.aibot.sleep", () -> BotCommandBridge.command(target, "sleep", "", "", 1));
        mineButton = button("btn.aibot.mine", () -> BotCommandBridge.command(target, "mine", idField.getText(), "", count()));
        craftButton = button("btn.aibot.craft", () -> BotCommandBridge.command(target, "craft", idField.getText(), "", count()));
        smeltButton = button("btn.aibot.smelt", () -> BotCommandBridge.command(target, "smelt", idField.getText(), "minecraft:iron_ingot", count()));

        layoutWidgets();
        sink.accept(idField);
        sink.accept(countField);
        sink.accept(comeButton);
        sink.accept(stopButton);
        sink.accept(eatButton);
        sink.accept(sleepButton);
        sink.accept(mineButton);
        sink.accept(craftButton);
        sink.accept(smeltButton);
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        context.drawTextWithShadow(renderer, Theme.tr("quick.aibot.input_hint"), bx, by, Theme.TEXT_DIM);
    }

    private ButtonWidget button(String key, Runnable action) {
        return ButtonWidget.builder(Text.translatable(key), button -> action.run()).dimensions(0, 0, 38, INPUT_H).build();
    }

    private void layoutWidgets() {
        if (idField == null) {
            return;
        }
        int bx = x + Theme.PAD;
        int by = y + 40;
        int bw = w - Theme.PAD * 2;
        int quarter = Math.max(28, (bw - 9) / 4);
        comeButton.setPosition(bx, by);
        comeButton.setDimensions(quarter, INPUT_H);
        stopButton.setPosition(bx + quarter + 3, by);
        stopButton.setDimensions(quarter, INPUT_H);
        eatButton.setPosition(bx + quarter * 2 + 6, by);
        eatButton.setDimensions(quarter, INPUT_H);
        sleepButton.setPosition(bx + quarter * 3 + 9, by);
        sleepButton.setDimensions(Math.max(28, bw - quarter * 3 - 9), INPUT_H);
        idField.setPosition(bx, by + 22);
        idField.setDimensions(Math.max(76, bw - 42), INPUT_H);
        countField.setPosition(bx + bw - 36, by + 22);
        countField.setDimensions(36, INPUT_H);
        int third = Math.max(34, (bw - 8) / 3);
        mineButton.setPosition(bx, by + 44);
        mineButton.setDimensions(third, INPUT_H);
        craftButton.setPosition(bx + third + 4, by + 44);
        craftButton.setDimensions(third, INPUT_H);
        smeltButton.setPosition(bx + third * 2 + 8, by + 44);
        smeltButton.setDimensions(bw - third * 2 - 8, INPUT_H);
    }

    private int count() {
        try {
            return Math.max(1, Integer.parseInt(countField.getText().trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
