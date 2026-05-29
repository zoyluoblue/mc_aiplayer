package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.screen.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class TaskCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.aibot.task";
    }

    @Override
    protected int bodyHeight() {
        return 58;
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("status.aibot.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        String task = Theme.tr("task.aibot." + snapshot.taskName());
        if (task.equals("task.aibot." + snapshot.taskName())) {
            task = snapshot.taskName();
        }
        context.drawTextWithShadow(renderer, task + " / " + snapshot.taskState(), bx, by, Theme.TEXT_STRONG);
        Theme.bar(context, bx, by + 14, bw, 8, snapshot.progress(), Theme.OK);
        String progress = (int) (snapshot.progress() * 100.0F) + "%";
        context.drawTextWithShadow(renderer, progress, bx + Math.max(0, bw - renderer.getWidth(progress)), by, Theme.TEXT_DIM);
        String phase = Theme.tr("task.aibot.phase", snapshot.taskName(), snapshot.taskState());
        context.drawTextWithShadow(renderer, trim(renderer, phase, bw), bx, by + 28, Theme.TEXT_DIM);
    }

    private static String trim(TextRenderer renderer, String value, int maxWidth) {
        if (renderer.getWidth(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            String candidate = builder.toString() + value.charAt(index) + suffix;
            if (renderer.getWidth(candidate) > maxWidth) {
                break;
            }
            builder.append(value.charAt(index));
        }
        return builder + suffix;
    }
}
