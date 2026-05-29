package io.github.zoyluo.aibot.client.screen.ui;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

public final class ChatView implements PanelComponent {
    private static final int BUBBLE_PAD = 6;
    private static final int GAP = 6;
    private static final int ROLE_H = 10;
    private static final int EDGE_PAD = 8;
    private static final int SCROLL_W = 5;

    private int x;
    private int y;
    private int w;
    private int h;
    private int scrollOffset;
    private int contentHeight;
    private boolean stickBottom = true;
    private int lastLineCount;
    private List<BotClientState.ChatLine> lines = List.of();

    @Override
    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    @Override
    public int preferredHeight() {
        return h;
    }

    @Override
    public void refresh(BotSnapshotS2C snapshot, List<BotClientState.ChatLine> chat) {
        this.lines = chat == null ? List.of() : chat;
        if (lines.size() != lastLineCount && stickBottom) {
            scrollOffset = 0;
        }
        lastLineCount = lines.size();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer renderer) {
        Theme.panel(context, x, y, w, h, Theme.CHAT_BG);
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xF7101216);
        context.drawHorizontalLine(x + 1, x + w - 2, y + 1, 0xFF2F3743);
        context.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        try {
            if (lines.isEmpty()) {
                drawEmpty(context, renderer);
                contentHeight = 0;
                return;
            }
            List<RenderLine> renderLines = layout(renderer);
            int drawY = y + h - scrollOffset - EDGE_PAD;
            for (int index = renderLines.size() - 1; index >= 0; index--) {
                RenderLine line = renderLines.get(index);
                drawY -= line.height();
                drawBubble(context, renderer, line, drawY);
                drawY -= GAP;
            }
        } finally {
            context.disableScissor();
        }
        drawScrollbar(context);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h || contentHeight <= h) {
            return false;
        }
        scrollOffset = clamp(scrollOffset + (int) Math.round(amount * 16.0D), 0, Math.max(0, contentHeight - h + 16));
        stickBottom = scrollOffset == 0;
        return true;
    }

    private List<RenderLine> layout(TextRenderer renderer) {
        List<RenderLine> result = new ArrayList<>();
        int bubbleMax = Math.max(86, (int) (w * 0.78D));
        int textMax = Math.max(40, bubbleMax - BUBBLE_PAD * 2);
        int height = 0;
        for (BotClientState.ChatLine line : lines) {
            List<String> wrapped = wrap(renderer, line.text(), textMax);
            int bubbleW = 0;
            for (String part : wrapped) {
                bubbleW = Math.max(bubbleW, renderer.getWidth(part));
            }
            String label = roleLabel(line.role());
            bubbleW = Math.max(bubbleW, renderer.getWidth(label));
            bubbleW = Math.min(bubbleMax, bubbleW + BUBBLE_PAD * 2);
            int bubbleH = ROLE_H + wrapped.size() * Theme.LINE_H + BUBBLE_PAD * 2;
            result.add(new RenderLine(line.role(), label, wrapped, bubbleW, bubbleH));
            height += bubbleH + GAP;
        }
        contentHeight = Math.max(0, height + EDGE_PAD * 2);
        if (stickBottom) {
            scrollOffset = 0;
        } else {
            scrollOffset = clamp(scrollOffset, 0, Math.max(0, contentHeight - h + 16));
        }
        return result;
    }

    private void drawBubble(DrawContext context, TextRenderer renderer, RenderLine line, int by) {
        int border = switch (line.role()) {
            case "user" -> 0xFF73AEFF;
            case "bot" -> 0xFF8BE896;
            case "system" -> 0xFFEBC35D;
            default -> Theme.BORDER;
        };
        int bg = switch (line.role()) {
            case "user" -> 0xF022416A;
            case "bot" -> 0xF01F3B2A;
            case "system" -> 0xF03A3019;
            default -> 0xF0242830;
        };
        int labelColor = switch (line.role()) {
            case "user" -> 0xFFD9EAFF;
            case "bot" -> 0xFFD7F9DB;
            case "system" -> 0xFFFFE1A3;
            default -> Theme.TEXT_DIM;
        };
        int bx = switch (line.role()) {
            case "user" -> x + w - line.width() - EDGE_PAD - SCROLL_W;
            case "system" -> x + Math.max(EDGE_PAD, (w - line.width()) / 2);
            default -> x + EDGE_PAD;
        };
        context.fill(bx, by, bx + line.width(), by + line.height(), bg);
        context.drawHorizontalLine(bx, bx + line.width() - 1, by, border);
        context.drawHorizontalLine(bx, bx + line.width() - 1, by + line.height() - 1, border);
        context.drawVerticalLine(bx, by, by + line.height() - 1, border);
        context.drawVerticalLine(bx + line.width() - 1, by, by + line.height() - 1, border);
        context.drawTextWithShadow(renderer, line.label(), bx + BUBBLE_PAD, by + BUBBLE_PAD, labelColor);
        int ty = by + BUBBLE_PAD + ROLE_H;
        for (String part : line.parts()) {
            context.drawTextWithShadow(renderer, part, bx + BUBBLE_PAD, ty, Theme.TEXT_STRONG);
            ty += Theme.LINE_H;
        }
    }

    private void drawEmpty(DrawContext context, TextRenderer renderer) {
        String first = Theme.tr("chat.aibot.empty");
        int maxTextW = Math.max(40, w - EDGE_PAD * 4);
        String second = trimToWidth(renderer, Theme.tr("chat.aibot.hint"), maxTextW);
        int emptyW = Math.min(w - EDGE_PAD * 2, Math.max(renderer.getWidth(first), renderer.getWidth(second)) + BUBBLE_PAD * 2);
        int emptyX = x + Math.max(EDGE_PAD, (w - emptyW) / 2);
        int emptyY = y + h / 2 - 18;
        context.fill(emptyX, emptyY, emptyX + emptyW, emptyY + 34, 0xF01B2028);
        context.drawHorizontalLine(emptyX, emptyX + emptyW - 1, emptyY, Theme.BORDER_BRIGHT);
        context.drawHorizontalLine(emptyX, emptyX + emptyW - 1, emptyY + 33, Theme.BORDER);
        context.drawVerticalLine(emptyX, emptyY, emptyY + 33, Theme.BORDER);
        context.drawVerticalLine(emptyX + emptyW - 1, emptyY, emptyY + 33, Theme.BORDER);
        context.drawTextWithShadow(renderer, first, x + Math.max(0, (w - renderer.getWidth(first)) / 2), emptyY + 7, Theme.TEXT);
        context.drawTextWithShadow(renderer, second, x + Math.max(0, (w - renderer.getWidth(second)) / 2), emptyY + 20, Theme.TEXT_DIM);
    }

    private void drawScrollbar(DrawContext context) {
        if (contentHeight <= h) {
            return;
        }
        int trackX = x + w - SCROLL_W;
        int trackH = h - 12;
        int thumbH = Math.max(18, trackH * h / Math.max(h, contentHeight));
        int maxOffset = Math.max(1, contentHeight - h + 16);
        int thumbY = y + 6 + (trackH - thumbH) * scrollOffset / maxOffset;
        context.fill(trackX, y + 6, trackX + 2, y + h - 6, 0xFF252B35);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbH, Theme.ACCENT);
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "user" -> Theme.tr("screen.aibot.role.user");
            case "bot" -> Theme.tr("screen.aibot.role.bot");
            case "system" -> Theme.tr("screen.aibot.role.system");
            default -> role == null || role.isBlank() ? Theme.tr("screen.aibot.role.system") : role;
        };
    }

    private static String trimToWidth(TextRenderer renderer, String value, int maxWidth) {
        if (renderer.getWidth(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        StringBuilder builder = new StringBuilder();
        for (int offset = 0; offset < value.length(); offset++) {
            String candidate = builder.toString() + value.charAt(offset) + suffix;
            if (renderer.getWidth(candidate) > maxWidth) {
                break;
            }
            builder.append(value.charAt(offset));
        }
        return builder + suffix;
    }

    private static List<String> wrap(TextRenderer renderer, String text, int maxWidth) {
        String value = text == null || text.isBlank() ? " " : text;
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String token : value.split(" ")) {
            String candidate = current.isEmpty() ? token : current + " " + token;
            if (renderer.getWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
                current.setLength(0);
            }
            splitLong(renderer, token, maxWidth, lines, current);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of(" ") : lines;
    }

    private static void splitLong(TextRenderer renderer, String token, int maxWidth, List<String> lines, StringBuilder current) {
        StringBuilder part = new StringBuilder();
        for (int offset = 0; offset < token.length(); offset++) {
            String candidate = part.toString() + token.charAt(offset);
            if (renderer.getWidth(candidate) > maxWidth && !part.isEmpty()) {
                lines.add(part.toString());
                part.setLength(0);
            }
            part.append(token.charAt(offset));
        }
        current.append(part);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RenderLine(String role, String label, List<String> parts, int width, int height) {
    }
}
