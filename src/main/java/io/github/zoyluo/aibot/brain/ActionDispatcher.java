package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.ArrayList;
import java.util.List;

public final class ActionDispatcher {
    // 优化2:目标失败后大脑常改用这些工具/子任务手动一格格挖矿、盲目移动,瞬间耗尽轮次(还会把 bot 挖进
    // 水/岩浆/怪堆送命)→ 失败后短时间内拦下,逼它用高层目标(mine_ore/gather)重试或停下。
    private static final java.util.Set<String> MANUAL_MINING_TOOLS =
            java.util.Set.of("strip_mine", "mine_block", "move_to");
    // 缺口补全(实测致死链):大脑还能用 assign_task{task_type=move/mine/strip_mine} 绕过上面的工具名拦截
    //(走 assign_task 同样创建 MoveTask/挖矿任务)→ 这些危险子类型一并拦下;mine_ore/gather 等高层目标放行。
    private static final java.util.Set<String> MANUAL_MINING_TASK_TYPES =
            java.util.Set.of("move", "mine", "strip_mine");
    private static final int GOAL_FAIL_GUARD_TICKS = 600; // 30s

    private final ToolRegistry registry;

    public ActionDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public List<ChatMessage> dispatch(AIPlayerEntity bot, List<ChatToolCall> calls) {
        int maxCalls = AIBotConfig.get().brain().maxToolCallsPerTurn();
        List<ChatMessage> results = new ArrayList<>();
        if (calls == null || calls.isEmpty()) {
            return results;
        }
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
            // 优化2:目标刚失败时,大脑常改用 strip_mine/mine_block/move_to(或 assign_task{move/mine/strip_mine})
            // 手动一格格挖、盲目移动,瞬间耗尽轮次,还会把 bot 挖进水/岩浆/怪堆送命(实测两次死亡)。
            // 拦下来逼它用高层目标(mine_ore 自动找矿 / gather 自动找资源)重试或停下。
            if (isManualMiningOrMove(call)
                    && io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.recentlyFailed(bot, GOAL_FAIL_GUARD_TICKS)) {
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.COMM, bot, "manual_mining_blocked", "tool", call.name());
                return new ToolDefinition.ToolResult(false,
                        "blocked: 目标刚失败,别手动逐格挖/盲目移动(易耗尽轮次或把自己挖进水/岩浆/怪堆)。"
                                + "请用高层目标重试(mine_ore 自动换层换位找矿 / gather 自动找资源),或 say 一句说明后停下待命。");
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

    // 是否"手动挖矿/盲目移动"类调用——含直接低层工具(strip_mine/mine_block/move_to)与
    // assign_task{task_type=move/mine/strip_mine}(补上后者绕过工具名拦截的缺口;mine_ore/gather 等高层目标放行)。
    private static boolean isManualMiningOrMove(ChatToolCall call) {
        if (MANUAL_MINING_TOOLS.contains(call.name())) {
            return true;
        }
        if ("assign_task".equals(call.name())) {
            try {
                JsonObject args = call.parsedArguments();
                if (args != null && args.has("task_type") && args.get("task_type").isJsonPrimitive()) {
                    return MANUAL_MINING_TASK_TYPES.contains(args.get("task_type").getAsString());
                }
            } catch (RuntimeException ignored) {
                // 参数解析异常 → 不在此拦,交后续正常流程报 bad_arg
            }
        }
        return false;
    }
}
