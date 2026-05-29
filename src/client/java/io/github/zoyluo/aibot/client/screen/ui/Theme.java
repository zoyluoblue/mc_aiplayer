package io.github.zoyluo.aibot.client.screen.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;

public final class Theme {
    // 背景一律全不透明(alpha=FF),杜绝身后明亮世界透上来削弱文字对比、造成"发糊"观感
    public static final int SCRIM = 0xDC000000;
    public static final int PANEL_BG = 0xFF161A20;
    public static final int CARD_BG = 0xFF222832;
    public static final int CHAT_BG = 0xFF0D0F13;
    public static final int CHAT_INPUT_BG = 0xFF0D0F13;
    public static final int TRACK = 0xFF11141A;
    public static final int BORDER = 0xFF475265;
    public static final int BORDER_BRIGHT = 0xFF6B7689;
    // 文字整体提亮,标题用纯白,正文近白,次要文字也明显提亮(原 0xFF9AA4B2 偏灰最糊)
    public static final int TEXT = 0xFFF3F6FA;
    public static final int TEXT_STRONG = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFBEC8D6;
    public static final int ACCENT = 0xFF6BA6FF;
    public static final int OK = 0xFF8FE49A;
    public static final int SYS = 0xFFF1C24E;
    public static final int HP = 0xFFF06A62;
    public static final int FOOD = 0xFFD89A4A;

    public static final int PAD = 8;
    public static final int GUTTER = 6;
    public static final int TITLE_H = 18;
    public static final int INPUT_H = 22;
    public static final int LINE_H = 12;

    private Theme() {
    }

    public static void panel(DrawContext context, int x, int y, int w, int h, int bg) {
        context.fill(x, y, x + w, y + h, bg);
        context.drawHorizontalLine(x, x + w - 1, y, BORDER);
        context.drawHorizontalLine(x, x + w - 1, y + h - 1, BORDER);
        context.drawVerticalLine(x, y, y + h - 1, BORDER);
        context.drawVerticalLine(x + w - 1, y, y + h - 1, BORDER);
    }

    public static void divider(DrawContext context, TextRenderer renderer, int x, int y, int w, String labelKey) {
        String label = tr(labelKey);
        int labelW = renderer.getWidth(label);
        int lineY = y + 5;
        context.drawHorizontalLine(x, x + Math.max(0, (w - labelW) / 2 - 4), lineY, BORDER);
        context.drawTextWithShadow(renderer, label, x + Math.max(0, (w - labelW) / 2), y, TEXT_DIM);
        context.drawHorizontalLine(x + Math.min(w - 1, (w + labelW) / 2 + 4), x + w - 1, lineY, BORDER);
    }

    public static void bar(DrawContext context, int x, int y, int w, int h, float frac, int fill) {
        float clamped = Math.max(0.0F, Math.min(1.0F, frac));
        panel(context, x, y, w, h, TRACK);
        int fillW = Math.max(0, Math.round((w - 2) * clamped));
        if (fillW > 0) {
            context.fill(x + 1, y + 1, x + 1 + fillW, y + h - 1, fill);
        }
    }

    public static void bubble(DrawContext context, int x, int y, int w, int h, int color, boolean filled) {
        int bg = filled ? 0xFF1F2A38 : 0xFF20262F;
        context.fill(x, y, x + w, y + h, bg);
        context.drawHorizontalLine(x, x + w - 1, y, color);
        context.drawHorizontalLine(x, x + w - 1, y + h - 1, color);
        context.drawVerticalLine(x, y, y + h - 1, color);
        context.drawVerticalLine(x + w - 1, y, y + h - 1, color);
    }

    public static String tr(String key, Object... args) {
        return I18n.translate(key, args);
    }
}
