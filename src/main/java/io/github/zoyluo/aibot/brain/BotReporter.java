package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.goal.GoalResult;
import io.github.zoyluo.aibot.network.AIBotServerNetworking;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BotReporter {
    public static final BotReporter INSTANCE = new BotReporter();

    private static final int MIN_REPORT_INTERVAL_TICKS = 80;
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d+)/(\\d+)");
    private final Map<UUID, ReportState> states = new ConcurrentHashMap<>();

    private BotReporter() {
    }

    public void onAssigned(AIPlayerEntity bot, TaskStatus status) {
        if (!enabled(bot)) {
            return;
        }
        ReportState state = new ReportState(status.name(), status.description(), 25);
        states.put(bot.getUuid(), state);
        report(bot, state, "开始" + summary(status) + "。", bot.getServer().getTicks(), true);
    }

    public void onStatus(MinecraftServer server, AIPlayerEntity bot, TaskStatus status) {
        if (!enabled(bot)) {
            return;
        }
        if (status.name().equals("idle")) {
            states.remove(bot.getUuid());
            return;
        }
        ReportState state = states.computeIfAbsent(bot.getUuid(),
                ignored -> new ReportState(status.name(), status.description(), 25));
        if (!state.taskName.equals(status.name())) {
            state.taskName = status.name();
            state.taskDescription = status.description();
            state.nextMilestone = 25;
            report(bot, state, "开始" + summary(status) + "。", server.getTicks(), true);
        }
        switch (status.state()) {
            case RUNNING -> reportProgress(server, bot, status, state);
            case PAUSED -> report(bot, state, "我先暂停" + summary(status) + "。", server.getTicks(), false);
            case COMPLETED -> {
                String prefix = GoalExecutor.INSTANCE.hasActivePlan(bot) ? "步骤完成:" : "完成了:";
                report(bot, state, prefix + summary(status) + "。", server.getTicks(), true);
                states.remove(bot.getUuid());
            }
            case FAILED -> {
                report(bot, state, "没完成:" + summary(status) + "。" + ReasonText.friendly(status.failureReason()), server.getTicks(), true);
                states.remove(bot.getUuid());
            }
            case CANCELLED -> {
                report(bot, state, "已取消:" + summary(status) + "。", server.getTicks(), true);
                states.remove(bot.getUuid());
            }
            default -> {
            }
        }
    }

    public void onCleared(AIPlayerEntity bot) {
        states.remove(bot.getUuid());
    }

    public void clearAll() {
        states.clear();
    }

    public void onGoalMessage(AIPlayerEntity bot, String text) {
        if (!enabled(bot)) {
            return;
        }
        AIBotServerNetworking.INSTANCE.sendBotChat(bot, "system", text);
    }

    /** Terminal Goal facts are never hidden by verbose progress settings. */
    public void onGoalResult(AIPlayerEntity bot, GoalResult.Status status, String text) {
        AIBotServerNetworking.INSTANCE.sendBotChat(bot, "system", "[" + status.name() + "] " + text);
    }

    private void reportProgress(MinecraftServer server, AIPlayerEntity bot, TaskStatus status, ReportState state) {
        int percent = (int) Math.floor(status.progress() * 100.0D);
        if (percent < state.nextMilestone || state.nextMilestone >= 100) {
            return;
        }
        int milestone = state.nextMilestone;
        state.nextMilestone += 25;
        report(bot, state, progressText(status, milestone), server.getTicks(), false);
    }

    private void report(AIPlayerEntity bot, ReportState state, String text, int tick, boolean force) {
        if (text.equals(state.lastText)) {
            return;
        }
        if (!force && tick - state.lastTick < MIN_REPORT_INTERVAL_TICKS) {
            return;
        }
        state.lastText = text;
        state.lastTick = tick;
        AIBotServerNetworking.INSTANCE.sendBotChat(bot, "system", text);
    }

    private static boolean enabled(AIPlayerEntity bot) {
        return BotRuntimeOptions.INSTANCE.verboseReportsEnabled(bot);
    }

    private static String progressText(TaskStatus status, int milestone) {
        Matcher matcher = COUNT_PATTERN.matcher(status.description());
        if (matcher.find()) {
            return "已经" + summary(status) + "," + matcher.group(1) + "/" + matcher.group(2) + "。";
        }
        return "已经完成约 " + milestone + "%:" + summary(status) + "。";
    }

    private static String summary(TaskStatus status) {
        String description = status.description() == null ? "" : status.description();
        return switch (status.name()) {
            case "mine" -> "挖" + objectAfter(description, "Mining ");
            case "craft" -> "合成" + objectAfter(description, "Crafting ");
            case "smelt" -> "烧炼" + objectAfter(description, "Smelting ");
            case "move" -> "移动到 " + description.replace("Walking to ", "");
            case "eat" -> "吃东西";
            case "sleep" -> "睡觉";
            case "combat" -> "战斗";
            case "evade" -> "躲避危险";
            case "light_area" -> "照明这片区域";
            case "build" -> "建造" + objectAfter(description, "Building ");
            case "forage" -> "收集" + objectAfter(description, "Foraging ");
            case "gather" -> "采集" + objectAfter(description, "Gathering ");
            case "hunt" -> "打猎找肉";
            case "dig_down" -> "向下挖石头";
            default -> ReasonText.taskName(status.name());
        };
    }

    private static String objectAfter(String description, String prefix) {
        if (!description.startsWith(prefix)) {
            return "";
        }
        String value = description.substring(prefix.length());
        int phase = value.indexOf(" phase=");
        if (phase >= 0) {
            value = value.substring(0, phase);
        }
        return ReasonText.itemText(value);
    }

    private static final class ReportState {
        private String taskName;
        private String taskDescription;
        private int nextMilestone;
        private String lastText = "";
        private int lastTick = Integer.MIN_VALUE / 2;

        private ReportState(String taskName, String taskDescription, int nextMilestone) {
            this.taskName = taskName;
            this.taskDescription = taskDescription;
            this.nextMilestone = nextMilestone;
        }
    }
}
