package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.screen.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class StatusCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.aibot.status";
    }

    @Override
    protected int bodyHeight() {
        return 86;
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("status.aibot.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        drawStat(context, renderer, bx, by, bw, Theme.tr("status.aibot.hp"), snapshot.health(), snapshot.maxHealth(), Theme.HP);
        drawStat(context, renderer, bx, by + 20, bw, Theme.tr("status.aibot.food"), snapshot.food(), 20.0F, Theme.FOOD);
        drawStat(context, renderer, bx, by + 40, bw, Theme.tr("status.aibot.progress"), snapshot.progress(), 1.0F, Theme.OK);

        String task = Theme.tr("task.aibot." + snapshot.taskName());
        if (task.equals("task.aibot." + snapshot.taskName())) {
            task = snapshot.taskName();
        }
        String brain = snapshot.brainBusy() ? Theme.tr("status.aibot.brain.busy") : Theme.tr("status.aibot.brain.idle");
        int brainColor = snapshot.brainBusy() ? Theme.ACCENT : Theme.TEXT_DIM;
        context.drawTextWithShadow(renderer, Theme.tr("status.aibot.task", task, snapshot.taskState()), bx, by + 61, Theme.TEXT);
        context.drawTextWithShadow(renderer, brain, bx, by + 73, brainColor);
        String tokens = Theme.tr("status.aibot.tokens", snapshot.promptTokens(), snapshot.completionTokens());
        context.drawTextWithShadow(renderer, tokens, bx + Math.max(0, bw - renderer.getWidth(tokens)), by + 73, Theme.TEXT_DIM);
    }

    private static void drawStat(DrawContext context, TextRenderer renderer, int x, int y, int w, String label, float value, float max, int color) {
        String text = max == 1.0F ? label + " " + (int) (value * 100) + "%" : label + " " + (int) value + "/" + (int) max;
        context.drawTextWithShadow(renderer, text, x, y, Theme.TEXT);
        Theme.bar(context, x, y + 10, w, 7, max <= 0.0F ? 0.0F : value / max, color);
    }
}
