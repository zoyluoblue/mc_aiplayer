package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.screen.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * 目标视图(GOAL 模式):展示 bot 当前目标的**完整执行链**与所处节点。
 * 与左栏的小 GoalCard 不同,这里按可用高度尽量显示全部步骤,并让当前步滚动居中。
 */
public final class GoalView extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.aibot.goal";
    }

    @Override
    protected int bodyHeight() {
        return 220; // GOAL 全屏视图,占满左栏
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("goal.aibot.empty"), bx, by, Theme.TEXT_DIM);
            return;
        }
        if (snapshot.goalTotalSteps() <= 0) {
            if (snapshot.goalResultStatus().isBlank()) {
                context.drawTextWithShadow(renderer, Theme.tr("goal.aibot.empty"), bx, by, Theme.TEXT_DIM);
            } else {
                context.drawTextWithShadow(renderer,
                        Theme.tr("goal.aibot.result." + snapshot.goalResultStatus().toLowerCase(java.util.Locale.ROOT)),
                        bx, by, "COMPLETED".equals(snapshot.goalResultStatus()) ? Theme.OK
                                : "PARTIAL".equals(snapshot.goalResultStatus()) ? Theme.SYS
                                : "FAILED".equals(snapshot.goalResultStatus()) ? Theme.HP : Theme.TEXT_DIM);
                context.drawTextWithShadow(renderer, trim(renderer, snapshot.goalResultSummary(), bw), bx, by + 16, Theme.TEXT);
                context.drawTextWithShadow(renderer,
                        Theme.tr("goal.aibot.evidence", snapshot.goalResultMatched(), snapshot.goalResultRequired()),
                        bx, by + 32, Theme.TEXT_DIM);
            }
            return;
        }
        String title = snapshot.goalTitle().isBlank() ? Theme.tr("goal.aibot.untitled") : snapshot.goalTitle();
        context.drawTextWithShadow(renderer, trim(renderer, title, bw), bx, by, Theme.TEXT_STRONG);
        int cur = snapshot.goalCurrentStepIndex();
        int total = snapshot.goalTotalSteps();
        int currentNumber = Math.min(cur + 1, total);
        context.drawTextWithShadow(renderer, Theme.tr("goal.aibot.progress", currentNumber, total), bx, by + 12, Theme.TEXT_DIM);

        int rows = snapshot.goalSteps().size();
        int maxRows = Math.max(1, (bh - 27) / Theme.LINE_H);
        int start = 0;
        if (rows > maxRows) {
            start = Math.max(0, Math.min(cur - maxRows / 2, rows - maxRows)); // 让当前步滚动居中
        }
        for (int i = 0; i < maxRows && start + i < rows; i++) {
            int idx = start + i;
            boolean current = idx == cur;
            int color = current ? Theme.ACCENT : idx < cur ? Theme.OK : Theme.TEXT_DIM;
            String marker = current ? ">" : idx < cur ? "x" : "-";
            context.drawTextWithShadow(renderer, trim(renderer, marker + " " + snapshot.goalSteps().get(idx), bw),
                    bx, by + 27 + i * Theme.LINE_H, color);
        }
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
