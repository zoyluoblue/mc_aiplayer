package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.ArrayList;
import java.util.List;

public final class ActionDispatcher {
    // 优化2:目标失败后大脑常改用这些工具手动一格格挖矿/移动,瞬间耗尽轮次 → 失败后短时间内拦下,逼它重试 mine_ore。
    private static final java.util.Set<String> MANUAL_MINING_TOOLS =
            java.util.Set.of("strip_mine", "mine_block", "move_to");
    private static final int GOAL_FAIL_GUARD_TICKS = 600; // 30s

    private final ToolRegistry registry;

    public ActionDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public List<ChatMessage> dispatch(AIPlayerEntity bot, List<ChatToolCall> calls) {
        int maxCalls = AIBotConfig.get().brain().maxToolCallsPerTurn();
        List<ChatMessage> results = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            ChatToolCall call = calls.get(index);
            ToolDefinition.ToolResult result;
            if (index >= maxCalls) {
                result = new ToolDefinition.ToolResult(false, "throttled");
            } else {
                result = invoke(bot, call);
            }
            BotLog.action(bot, "tool_result", "tool", call.name(), "ok", result.ok(), "message", result.message());
            results.add(ChatMessage.toolResult(call.id(), result.toToolContent()));
        }
        return results;
    }

    private ToolDefinition.ToolResult invoke(AIPlayerEntity bot, ChatToolCall call) {
        try {
            // 优化2:挖矿目标刚失败时,大脑常改用 strip_mine/mine_block/move_to 手动一格格挖,瞬间耗尽轮次。
            // 拦下来逼它重试 mine_ore(系统会换层/换位置自动找矿)或停下。
            if (MANUAL_MINING_TOOLS.contains(call.name())
                    && io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.recentlyFailed(bot, GOAL_FAIL_GUARD_TICKS)) {
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.COMM, bot, "manual_mining_blocked", "tool", call.name());
                return new ToolDefinition.ToolResult(false,
                        "blocked: 挖矿目标刚失败,别手动逐格挖。请重试 mine_ore(系统会自动换层/换位置找矿),或 say 一句说明后停下。");
            }
            ToolDefinition definition = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("unknown_tool: " + call.name()));
            JsonObject args = call.parsedArguments();
            BotLog.action(bot, "tool_dispatch", "tool", call.name(), "args", args);
            return definition.handler().invoke(bot, args);
        } catch (IllegalArgumentException exception) {
            // D:参数/输入校验失败(多为大脑用错工具或传参不全,如 assign_task mine 只给坐标缺 block)属**预期**错误——
            // 简洁 warn 不打整页 stacktrace 污染日志,reason 仍清晰回传给大脑让它自行纠正。
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.COMM, bot, "tool_bad_arg", "tool", call.name(), "reason", reason);
            return new ToolDefinition.ToolResult(false, "bad_arg: " + reason);
        } catch (RuntimeException exception) {
            BotLog.error(bot, "tool_exception", exception, "tool", call.name());
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new ToolDefinition.ToolResult(false, "exception: " + reason);
        }
    }
}
