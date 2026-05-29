package io.github.zoyluo.aibot.client.screen.ui.cards;

import io.github.zoyluo.aibot.client.screen.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class GoalCard extends PanelCard {
    @Override
    protected String titleKey() {
        return "card.aibot.goal";
    }

    @Override
    protected int bodyHeight() {
        return 74;
    }

    @Override
    protected void renderBody(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer, int bx, int by, int bw, int bh) {
        if (snapshot == null) {
            context.drawTextWithShadow(renderer, Theme.tr("status.aibot.waiting"), bx, by, Theme.TEXT_DIM);
            return;
        }
        if (snapshot.goalTotalSteps() <= 0) {
            context.drawTextWithShadow(renderer, Theme.tr("goal.aibot.empty"), bx, by, Theme.TEXT_DIM);
            return;
        }
        String title = snapshot.goalTitle().isBlank() ? Theme.tr("goal.aibot.untitled") : snapshot.goalTitle();
        context.drawTextWithShadow(renderer, trim(renderer, title, bw), bx, by, Theme.TEXT_STRONG);
        int currentNumber = Math.min(snapshot.goalCurrentStepIndex() + 1, snapshot.goalTotalSteps());
        String progress = Theme.tr("goal.aibot.progress", currentNumber, snapshot.goalTotalSteps());
        context.drawTextWithShadow(renderer, progress, bx, by + 12, Theme.TEXT_DIM);
        int maxRows = Math.min(3, snapshot.goalSteps().size());
        int start = Math.max(0, Math.min(snapshot.goalCurrentStepIndex(), Math.max(0, snapshot.goalSteps().size() - maxRows)));
        for (int index = 0; index < maxRows; index++) {
            int stepIndex = start + index;
            if (stepIndex >= snapshot.goalSteps().size()) {
                break;
            }
            boolean current = stepIndex == snapshot.goalCurrentStepIndex();
            int color = current ? Theme.ACCENT : stepIndex < snapshot.goalCurrentStepIndex() ? Theme.OK : Theme.TEXT_DIM;
            String marker = current ? ">" : stepIndex < snapshot.goalCurrentStepIndex() ? "x" : "-";
            context.drawTextWithShadow(renderer, trim(renderer, marker + " " + snapshot.goalSteps().get(stepIndex), bw), bx, by + 27 + index * Theme.LINE_H, color);
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
