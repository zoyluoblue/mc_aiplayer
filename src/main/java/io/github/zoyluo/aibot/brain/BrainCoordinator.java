package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.observe.ReplayRecorder;
import io.github.zoyluo.aibot.observe.TpsGuard;
import io.github.zoyluo.aibot.network.AIBotServerNetworking;
import io.github.zoyluo.aibot.perception.PerceptionCollector;
import io.github.zoyluo.aibot.perception.PerceptionSnapshot;
import io.github.zoyluo.aibot.task.MemoryStore;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class BrainCoordinator {
    public static final BrainCoordinator INSTANCE = new BrainCoordinator();
    private static final int MAX_CONTINUATION_TASK_POLLS = 80;

    private final Map<UUID, BotConversation> conversations = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> manualModes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextGoalWakeTick = new ConcurrentHashMap<>();
    // FLOW-2:大脑分配长任务后置 true;任务结束后 idle-watcher 据此自动唤醒大脑决定下一步(无需人催)。
    private final Map<UUID, Boolean> awaitingTask = new ConcurrentHashMap<>();
    private ToolRegistry toolRegistry = new ToolRegistry();
    private ActionDispatcher dispatcher = new ActionDispatcher(toolRegistry);
    private AsyncDecisionExecutor executor;

    private BrainCoordinator() {
    }

    public void configure(AIBotConfig config) {
        if (executor != null) {
            executor.shutdown();
        }
        toolRegistry = new ToolRegistry();
        dispatcher = new ActionDispatcher(toolRegistry);
        executor = new AsyncDecisionExecutor(new DeepSeekApiClient(config.deepseek()));
    }

    public boolean handleMessage(AIPlayerEntity bot, String senderName, String text) {
        ensureConfigured();
        BotConversation conversation = conversations.computeIfAbsent(bot.getUuid(), ignored -> new BotConversation());
        // GOALFIX-CONT:一条新的玩家消息=显式重定向。若此时有自治目标计划在跑,清掉它并解除 busy,
        // 让新指令能立即执行——既符合"显式玩家指令可打断"原则,也避免 goal 期间 busy 长时间为 true
        // 导致玩家被"正在思考"挡住、无法叫停跑飞的目标。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.clear(bot);
            TaskManager.INSTANCE.abort(bot);
            synchronized (conversation) {
                conversation.busy = false;
            }
            BotLog.comm(bot, "goal_cleared_by_user_message");
        }
        synchronized (conversation) {
            if (conversation.busy) {
                sendPanelChat(bot, "system", bot.getGameProfile().getName() + " 正在思考,请稍等。");
                return false;
            }
            conversation.busy = true;
        }

        if (conversation.history.isEmpty()) {
            conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().getName())));
        }
        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
        conversation.history.add(ChatMessage.user("[" + senderName + "] says: " + text + "\n\nCurrent state:\n" + snapshot.toJson()));
        trimHistory(conversation);
        conversation.turnsInCurrentRequest = 0;
        conversation.continuationTaskPolls = 0;
        conversation.maxTurnsHintInjected = false;
        submit(bot, conversation);
        return true;
    }

    public void onResponse(AIPlayerEntity bot, ChatResponse response) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation == null) {
            return;
        }

        BotLog.api(bot, "api_response",
                "tokens_in", response.promptTokens(),
                "tokens_out", response.completionTokens(),
                "cache_hit", response.promptCacheHitTokens(),
                "finish_reason", response.finishReason());

        if (response.content() != null && !response.content().isBlank()) {
            sendPanelChat(bot, "bot", response.content());
        }
        conversation.lastPromptTokens = response.promptTokens();
        conversation.lastCompletionTokens = response.completionTokens();
        conversation.lastCacheHitTokens = response.promptCacheHitTokens();
        conversation.history.add(ChatMessage.assistant(response.content(), response.toolCalls()));

        if (response.wantsToolCalls()) {
            List<ChatMessage> toolResults = dispatcher.dispatch(bot, response.toolCalls());
            ReplayRecorder.INSTANCE.onDecision(bot, conversation.lastPerceptionDigest, response.toolCalls(), replayResult(toolResults));
            conversation.history.addAll(toolResults);
            conversation.turnsInCurrentRequest++;
            maybeInjectMaxTurnsHint(conversation);
            if (conversation.turnsInCurrentRequest >= AIBotConfig.get().brain().maxTurnsPerRequest()) {
                BotLog.warn(LogCategory.COMM, bot, "max_turns_reached", "turns", conversation.turnsInCurrentRequest, "last_response", response.finishReason());
                sendPanelChat(bot, "system", "工具调用轮次已达到上限。我会先停下来,请改用更高层任务或补充目标。");
                conversation.busy = false;
                trimHistory(conversation);
                return;
            }
            trimHistory(conversation);
            scheduleContinuation(bot, conversation);
            return;
        }

        ReplayRecorder.INSTANCE.onDecision(bot, conversation.lastPerceptionDigest, List.of(), response.content());
        conversation.busy = false;
        // FLOW-2:大脑收尾时若仍有活跃任务(这轮是"分配长任务后停下"),标记等待任务完成;
        // 任务结束后由 idle-watcher 自动唤醒大脑决定下一步,无需人催。
        if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
            awaitingTask.put(bot.getUuid(), true);
        }
        trimHistory(conversation);
        BotLog.comm(bot, "conversation_done", "finish_reason", response.finishReason());
    }

    public void onError(AIPlayerEntity bot, Throwable throwable) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation != null) {
            conversation.busy = false;
        }
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        BotLog.error(bot, "brain_hiccup", throwable, "message", message);
        sendPanelChat(bot, "system", "大脑请求失败: " + message);
    }

    public void reset(AIPlayerEntity bot) {
        conversations.remove(bot.getUuid());
        manualModes.remove(bot.getUuid());
        awaitingTask.remove(bot.getUuid());
        BotRuntimeOptions.INSTANCE.clear(bot);
        BotLog.comm(bot, "conversation_reset");
    }

    public void setManualMode(AIPlayerEntity bot, boolean enabled) {
        if (enabled) {
            manualModes.put(bot.getUuid(), true);
        } else {
            manualModes.remove(bot.getUuid());
        }
        BotLog.comm(bot, "manual_mode_set", "enabled", enabled);
    }

    public boolean manualMode(AIPlayerEntity bot) {
        return manualModes.getOrDefault(bot.getUuid(), false);
    }

    public boolean maybeWakeForFailure(AIPlayerEntity bot) {
        return maybeWakeForFailureOrGoal(bot);
    }

    public boolean maybeWakeForFailureOrGoal(AIPlayerEntity bot) {
        // GOALFIX-GF1 P0-A:bot 有活跃的确定性目标计划时,自动唤醒(FLOW-2/失败注入)一律让位给
        // GoalExecutor,避免两个编排器在步骤间隙抢 assign。awaitingTask 不清除:目标计划自身完成、
        // 从 activePlans 移除后,下一次本方法才会据 awaitingTask 唤醒大脑判断整体意图是否达成。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        boolean hasFailure = TaskManager.INSTANCE.peekFailure(bot).isPresent();
        boolean hasGoal = BotMemoryStore.INSTANCE.of(bot.getUuid()).hasActiveGoal();
        // FLOW-2:idle-watcher 仅在无活跃任务时调用本方法,故 awaiting=true 即代表"大脑分配的任务已结束"。
        boolean taskJustFinished = Boolean.TRUE.equals(awaitingTask.get(bot.getUuid()));
        if (!hasFailure && !shouldWakeForGoal(bot, hasGoal) && !taskJustFinished) {
            return false;
        }
        ensureConfigured();
        BotConversation conversation = conversations.computeIfAbsent(bot.getUuid(), ignored -> new BotConversation());
        synchronized (conversation) {
            if (conversation.busy) {
                return false;
            }
            conversation.busy = true;
        }
        if (conversation.history.isEmpty()) {
            conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().getName())));
        }
        conversation.turnsInCurrentRequest = 0;
        conversation.continuationTaskPolls = 0;
        conversation.maxTurnsHintInjected = false;
        if (hasFailure && maybeInjectFailure(bot, conversation)) {
            awaitingTask.remove(bot.getUuid());
            trimHistory(conversation);
            submit(bot, conversation);
            return true;
        }
        if (hasGoal && maybeInjectGoalContinuation(bot, conversation, "当前没有正在执行的任务,但还有长期目标未完成。请继续推进当前步骤;需要时先分配一个高层任务。")) {
            awaitingTask.remove(bot.getUuid());
            nextGoalWakeTick.put(bot.getUuid(), bot.getServer().getTicks() + 200);
            trimHistory(conversation);
            submit(bot, conversation);
            return true;
        }
        // FLOW-2:大脑分配的任务已结束、且无失败无长期目标 → 自动唤醒大脑决定下一步,无需人催。
        if (taskJustFinished) {
            awaitingTask.remove(bot.getUuid());
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
            conversation.lastPerceptionDigest = perceptionDigest(snapshot);
            conversation.history.add(ChatMessage.user(
                    "上一个任务已结束:" + status.name() + "(状态 " + status.state() + ":" + status.description()
                    + ")。请判断:若玩家的整体要求已达成,用 say 向玩家中文汇报完成并停止;"
                    + "否则继续执行下一步(分配下一个高层任务)。\n\nCurrent state:\n" + snapshot.toJson()));
            BotLog.comm(bot, "task_done_wake", "name", status.name(), "state", String.valueOf(status.state()));
            trimHistory(conversation);
            submit(bot, conversation);
            return true;
        }
        synchronized (conversation) {
            conversation.busy = false;
        }
        return false;
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        conversations.clear();
        manualModes.clear();
        nextGoalWakeTick.clear();
    }

    public BrainStatus status(AIPlayerEntity bot) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation == null) {
            return new BrainStatus(false, 0, 0, 0, 0);
        }
        return new BrainStatus(
                conversation.busy,
                conversation.history.size(),
                conversation.lastPromptTokens,
                conversation.lastCompletionTokens,
                conversation.lastCacheHitTokens);
    }

    public void sendPanelChat(AIPlayerEntity bot, String role, String text) {
        AIBotServerNetworking.INSTANCE.sendBotChat(bot, role, text);
    }

    public int conversationCount() {
        return conversations.size();
    }

    private void submit(AIPlayerEntity bot, BotConversation conversation) {
        List<ChatMessage> historySnapshot = MemoryStore.INSTANCE.prepareHistory(bot, List.copyOf(conversation.history));
        AIBotConfig.Brain brainConfig = AIBotConfig.get().brain();
        List<ToolDefinition> toolsSnapshot = toolRegistry.tools(
                brainConfig,
                brainConfig.exposesLowLevelTools() || manualMode(bot),
                BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                brainConfig.coordinationToolsEnabled());
        executor.submit(bot, historySnapshot, toolsSnapshot, this::onResponse, this::onError);
    }

    private void scheduleContinuation(AIPlayerEntity bot, BotConversation conversation) {
        CompletableFuture.delayedExecutor(TpsGuard.INSTANCE.continuationDelaySeconds(), TimeUnit.SECONDS).execute(() ->
                bot.getServer().execute(() -> {
                    if (conversations.get(bot.getUuid()) != conversation || !conversation.busy) {
                        return;
                    }
                    // GOALFIX-CONT:确定性目标计划运行期间,绝不重新唤醒大脑——即便在两个 step 之间
                    // getActive() 短暂为空的那 1 tick(否则大脑会醒来调 assign_task 把 goal 的当前 step
                    // abort 掉,正是实测#6的真凶)。纯等待轮询,不计入上限、不强制唤醒;goal 结束后
                    // hasActivePlan 转 false,下一轮续航自然把结果交还大脑汇报。
                    if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                        scheduleContinuation(bot, conversation);
                        return;
                    }
                    if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
                        conversation.continuationTaskPolls++;
                        if (conversation.continuationTaskPolls >= MAX_CONTINUATION_TASK_POLLS) {
                            PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                            conversation.history.add(ChatMessage.user("Task is still active after waiting for continuation. Current state:\n" + snapshot.toJson()));
                            trimHistory(conversation);
                            BotLog.warn(LogCategory.COMM, bot, "continuation_wait_limit_reached", "polls", conversation.continuationTaskPolls);
                            submit(bot, conversation);
                            return;
                        }
                        scheduleContinuation(bot, conversation);
                        return;
                    }
                    conversation.continuationTaskPolls = 0;
                    if (maybeInjectFailure(bot, conversation)) {
                        trimHistory(conversation);
                        submit(bot, conversation);
                        return;
                    }
                    TaskStatus status = TaskManager.INSTANCE.status(bot);
                    if (status.state() == io.github.zoyluo.aibot.task.TaskState.COMPLETED
                            && maybeInjectGoalContinuation(bot, conversation, "上一步任务已完成:" + status.description() + "。请根据长期目标推进下一步;如果该步骤已完成,先调用 advance_goal。")) {
                        trimHistory(conversation);
                        submit(bot, conversation);
                        return;
                    }
                    PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                    conversation.lastPerceptionDigest = perceptionDigest(snapshot);
                    conversation.history.add(ChatMessage.user("Updated state after tool calls:\n" + snapshot.toJson()));
                    trimHistory(conversation);
                    submit(bot, conversation);
                }));
    }

    private void ensureConfigured() {
        if (executor == null) {
            configure(AIBotConfig.get());
        }
    }

    private void trimHistory(BotConversation conversation) {
        int max = AIBotConfig.get().brain().maxHistoryMessages();
        if (conversation.history.size() <= max) {
            return;
        }
        ChatMessage system = conversation.history.peekFirst();
        List<ChatMessage> rest = new ArrayList<>(conversation.history);
        conversation.history.clear();
        if (system != null && "system".equals(system.role())) {
            conversation.history.add(system);
            rest = rest.subList(1, rest.size());
        }
        int keep = Math.max(0, max - conversation.history.size());
        int start = Math.max(0, rest.size() - keep);
        for (int index = start; index < rest.size(); index++) {
            conversation.history.add(rest.get(index));
        }
    }

    private void maybeInjectMaxTurnsHint(BotConversation conversation) {
        int maxTurns = AIBotConfig.get().brain().maxTurnsPerRequest();
        if (conversation.maxTurnsHintInjected || conversation.turnsInCurrentRequest < maxTurns - 2) {
            return;
        }
        conversation.history.add(ChatMessage.system("你已多次调用工具仍未完成。请改用一个高层任务(如 assign_task / strip_mine / craft),然后停止调用工具、等待其完成;若无法完成,用 say 说明原因。"));
        conversation.maxTurnsHintInjected = true;
    }

    private boolean maybeInjectFailure(AIPlayerEntity bot, BotConversation conversation) {
        return TaskManager.INSTANCE.consumeFailure(bot)
                .map(failure -> {
                    int maxRetries = AIBotConfig.get().brain().maxTaskRetries();
                    String retryHint = failure.count() >= maxRetries
                            ? " 已经连续多次同样失败,请倾向于换方法或用 say 说明无法完成。"
                            : "";
                    String strategyHint = failure.count() >= 2
                            ? " 同一任务和原因已经连续失败,禁止原样重试;必须换工具/任务策略,或先补齐前置条件。"
                            : "";
                    String executableHint = executableFailureHint(failure);
                    PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
                    conversation.lastPerceptionDigest = perceptionDigest(snapshot);
                    conversation.history.add(ChatMessage.user("上一个任务失败:"
                            + failure.name()
                            + ",原因:"
                            + failure.reason()
                            + "(第"
                            + failure.count()
                            + "次)。请判断:补齐前置条件后重试 / 换用其它方法 / 用 say 说明无法完成。"
                            + retryHint
                            + strategyHint
                            + executableHint
                            + "\n\nCurrent state:\n"
                            + snapshot.toJson()));
                    BotLog.comm(bot, "failure_injected",
                            "name", failure.name(),
                            "reason", failure.reason(),
                            "count", failure.count(),
                            "tick", failure.tick());
                    return true;
                })
                .orElse(false);
    }

    private static String executableFailureHint(TaskManager.FailureRecord failure) {
        String reason = failure.reason() == null ? "" : failure.reason();
        if (reason.startsWith("no_exposed_ore:use_strip_mine")) {
            return " 可执行建议:目标是矿石但附近没有暴露矿块,不要再用 mine;改用 strip_mine/assign_task strip_mine 并设置 target_ores 为目标矿石。";
        }
        return "";
    }

    private boolean maybeInjectGoalContinuation(AIPlayerEntity bot, BotConversation conversation, String reason) {
        String goal = BotMemoryStore.INSTANCE.of(bot.getUuid()).goalDriveStatus("");
        if (goal.isBlank()) {
            return false;
        }
        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.lastPerceptionDigest = perceptionDigest(snapshot);
        conversation.history.add(ChatMessage.user(reason
                + "\n\n长期目标状态:\n"
                + goal
                + "\n\nCurrent state:\n"
                + snapshot.toJson()));
        BotLog.comm(bot, "goal_continuation_injected", "reason", reason);
        return true;
    }

    private boolean shouldWakeForGoal(AIPlayerEntity bot, boolean hasGoal) {
        if (!hasGoal) {
            return false;
        }
        return bot.getServer().getTicks() >= nextGoalWakeTick.getOrDefault(bot.getUuid(), 0);
    }

    private static String perceptionDigest(PerceptionSnapshot snapshot) {
        String json = snapshot.toJson();
        return json.length() <= 1400 ? json : json.substring(0, 1397) + "...";
    }

    private static String replayResult(List<ChatMessage> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : toolResults) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(message.toolCallId()).append(":").append(message.content());
            if (builder.length() > 1600) {
                return builder.substring(0, 1597) + "...";
            }
        }
        return builder.toString();
    }

    private static String systemPrompt(String botName) {
        return """
                You are a player in Minecraft named %s. You exist as a real player in the world and can interact with it using the tools provided.

                Rules:
                1. Understand the human's intent first, then break it into tool calls.
                2. Coordinates are integers (block positions).
                3. Prefer high-level deterministic tasks for survival work. For ores or raw ore materials, use mine_ore; it automatically prepares the required pickaxe before mining. For an item/tool goal such as iron_pickaxe or iron_ingot, use achieve_goal. Do not manually decompose these into gather/craft/mine steps unless the goal tool fails.
                4. Low-level tools such as move_to, mine_block, select_hotbar, and place_block are for one-off manual actions only. Do not use them for gathering materials or placing a crafting table for recipes unless the human explicitly asks for manual control.
                5. High-level tasks such as craft, smelt, eat, or assign_task run over multiple ticks. Start only one such task at a time, then use get_task_status or the Current state task field on later turns until it is COMPLETED or FAILED before assigning the next task.
                6. Always reply to humans in Simplified Chinese. Use the say tool to reply to humans. Keep replies short (one sentence).
                7. For survival crafting, call plan_craft first when materials may be missing. Use missing[].source to choose assign_task mine, smelt, craft, or forage before retrying craft for the intended target. CraftTask expands recipe-table intermediates such as planks and sticks, so do not craft planks or sticks as standalone steps unless the human asks for those items.
                8. For 3x3 recipes, do not manually select or place a crafting table. If a crafting table is nearby or in inventory, the craft task can use or place it.
                9. For "挖铁矿", call mine_ore with ore=minecraft:iron_ore. For "做一把铁镐" or "给我铁锭", call achieve_goal with item=minecraft:iron_pickaxe or minecraft:iron_ingot. The deterministic goal executor will plan gathering, crafting, mining, and smelting. CRITICAL: a single mine_ore/achieve_goal call runs the ENTIRE multi-step plan autonomously (gather wood, craft tools, mine stone, mine ore). After you call it, STOP immediately — do NOT call any other tool (no say, no inventory, no assign_task, no mine, no strip_mine) and do NOT narrate intermediate steps. The system executes every step itself and will notify you only when the whole goal is finished or has truly failed. Calling other tools meanwhile will abort the goal and break it.
                10. After each action, look at the next world state (passed in user messages) and decide the next step.
                11. When the task is complete or impossible, say so and stop calling tools.
                12. You are fully autonomous and self-reliant. NEVER ask the human for help, for resources, or to move/carry you — the human will not help. NEVER mine ore with bare hands and NEVER use strip_mine or assign_task mine to dig without a proper pickaxe (that wastes blocks and drops nothing). To get ore always use mine_ore, and to get an item/tool use achieve_goal — these automatically walk to find wood, craft the needed pickaxe, then mine. If mine_ore/achieve_goal reports it cannot proceed, just retry mine_ore once; if it still cannot, state the situation in one short sentence and stop — do not flail with move/strip_mine and do not beg.

                Available tools are declared in the tools field. You MUST use them; do not invent tools.
                """.formatted(botName);
    }

    private static final class BotConversation {
        private final Deque<ChatMessage> history = new ArrayDeque<>();
        private int turnsInCurrentRequest;
        private int continuationTaskPolls;
        private boolean maxTurnsHintInjected;
        private boolean busy;
        private int lastPromptTokens;
        private int lastCompletionTokens;
        private int lastCacheHitTokens;
        private String lastPerceptionDigest = "";
    }

    public record BrainStatus(boolean busy, int historySize, int promptTokens, int completionTokens, int cacheHitTokens) {
    }
}
