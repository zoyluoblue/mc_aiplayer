package io.github.zoyluo.aibot.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 左下角实时大脑过程 HUD:滚动显示服务端推来的人话化过程行,自动滚动(永远显示尾部)。
 * 无背景、0.7 倍缩放(用户反馈:带底太挡视野)。请求在途时显示"思考中 Ns"计时,
 * ≥30s 变红——卡住一眼可见。默认开启,快捷键(默认 H)开关。连续重复行去重。
 */
public final class BrainTraceHud {
    private static final int MAX_KEPT = 100;
    private static final int VISIBLE_LINES = 10;
    private static final int MAX_WIDTH = 200;
    private static final int LINE_HEIGHT = 10;
    private static final int BOTTOM_MARGIN = 96;
    private static final float SCALE = 0.7F;

    private static final Deque<String> lines = new ArrayDeque<>();
    private static final Set<String> seenBots = new HashSet<>();
    private static volatile boolean visible = true;
    private static long pendingSinceMillis;

    private BrainTraceHud() {
    }

    /** 仅在客户端主线程调用(收包处已 context.client().execute)。 */
    public static void add(String botName, String line) {
        if (botName != null && !botName.isBlank()) {
            seenBots.add(botName);
        }
        String text = seenBots.size() > 1 && botName != null && !botName.isBlank()
                ? "[" + botName + "] " + line
                : line;
        if (!text.equals(lines.peekLast())) { // 连续重复行(如狂按打断)只留一条
            lines.addLast(text);
        }
        while (lines.size() > MAX_KEPT) {
            lines.removeFirst();
        }
        // "->"=请求发出,开始计时;任何后续行(响应/说话/工具/失败/打断)都说明大脑活着,停表
        if (line.startsWith("->")) {
            pendingSinceMillis = System.currentTimeMillis();
        } else {
            pendingSinceMillis = 0L;
        }
    }

    public static void toggle() {
        visible = !visible;
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!visible || client.player == null || client.options.hudHidden || lines.isEmpty()) {
            return;
        }
        var font = client.textRenderer;
        boolean thinking = pendingSinceMillis > 0L;
        long thinkingSeconds = thinking ? (System.currentTimeMillis() - pendingSinceMillis) / 1000L : 0L;
        int totalRows = Math.min(lines.size(), VISIBLE_LINES) + (thinking ? 1 : 0);

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(4.0F, context.getScaledWindowHeight() - BOTTOM_MARGIN - totalRows * LINE_HEIGHT * SCALE, 0.0F);
        matrices.scale(SCALE, SCALE, 1.0F);

        int y = 0;
        int skip = Math.max(0, lines.size() - VISIBLE_LINES); // 自动滚动 = 永远渲染尾部最新行
        int index = 0;
        for (String line : lines) {
            if (index++ < skip) {
                continue;
            }
            context.drawTextWithShadow(font, font.trimToWidth(line, MAX_WIDTH), 0, y, colorOf(line));
            y += LINE_HEIGHT;
        }
        if (thinking) {
            String wait = "... 思考中 " + thinkingSeconds + "s";
            context.drawTextWithShadow(font, wait, 0, y, thinkingSeconds >= 30 ? 0xFFFF5555 : 0xFFFFFF55);
        }
        matrices.pop();
    }

    private static int colorOf(String line) {
        String body = line.startsWith("[") && line.indexOf("] ") > 0
                ? line.substring(line.indexOf("] ") + 2)
                : line;
        if (body.startsWith(">>")) {
            return 0xFFFFFFFF;
        }
        if (body.startsWith("->")) {
            return 0xFF55FFFF;
        }
        if (body.startsWith("说:")) {
            return 0xFFFFFF55;
        }
        if (body.startsWith("*")) {
            return 0xFFFFAA00;
        }
        if (body.startsWith("OK")) {
            return 0xFF55FF55;
        }
        if (body.startsWith("!!") || body.startsWith("==")) {
            return 0xFFFF5555;
        }
        return 0xFFBBBBBB;
    }
}
