package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.perception.PerceptionCollector;
import io.github.zoyluo.aibot.perception.PerceptionSnapshot;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class BrainCoordinator {
    public static final BrainCoordinator INSTANCE = new BrainCoordinator();

    private final Map<UUID, BotConversation> conversations = new HashMap<>();
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
        if (conversation.busy) {
            broadcast(bot, bot.getGameProfile().getName() + " is thinking, please wait.");
            return false;
        }

        if (conversation.history.isEmpty()) {
            conversation.history.add(ChatMessage.system(systemPrompt(bot.getGameProfile().getName())));
        }
        PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
        conversation.history.add(ChatMessage.user("[" + senderName + "] says: " + text + "\n\nCurrent state:\n" + snapshot.toJson()));
        trimHistory(conversation);
        conversation.busy = true;
        conversation.turnsInCurrentRequest = 0;
        submit(bot, conversation);
        return true;
    }

    public void onResponse(AIPlayerEntity bot, ChatResponse response) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation == null) {
            return;
        }

        AIBotMod.LOGGER.info("[AIBot] {} <- DeepSeek finish_reason={} prompt_tokens={} completion_tokens={} cache_hit_tokens={}",
                bot.getGameProfile().getName(),
                response.finishReason(),
                response.promptTokens(),
                response.completionTokens(),
                response.promptCacheHitTokens());

        if (response.content() != null && !response.content().isBlank()) {
            broadcast(bot, "<" + bot.getGameProfile().getName() + "> " + response.content());
        }
        conversation.lastPromptTokens = response.promptTokens();
        conversation.lastCompletionTokens = response.completionTokens();
        conversation.lastCacheHitTokens = response.promptCacheHitTokens();
        conversation.history.add(ChatMessage.assistant(response.content(), response.toolCalls()));

        if (response.wantsToolCalls()) {
            List<ChatMessage> toolResults = dispatcher.dispatch(bot, response.toolCalls());
            conversation.history.addAll(toolResults);
            conversation.turnsInCurrentRequest++;
            if (conversation.turnsInCurrentRequest >= AIBotConfig.get().brain().maxTurnsPerRequest()) {
                broadcast(bot, "<" + bot.getGameProfile().getName() + "> max turns reached.");
                conversation.busy = false;
                trimHistory(conversation);
                return;
            }
            trimHistory(conversation);
            scheduleContinuation(bot, conversation);
            return;
        }

        conversation.busy = false;
        trimHistory(conversation);
        AIBotMod.LOGGER.info("[AIBot] conversation turn done, finish_reason={}", response.finishReason());
    }

    public void onError(AIPlayerEntity bot, Throwable throwable) {
        BotConversation conversation = conversations.get(bot.getUuid());
        if (conversation != null) {
            conversation.busy = false;
        }
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        AIBotMod.LOGGER.warn("[AIBot] Brain request failed for {}: {}", bot.getGameProfile().getName(), message);
        broadcast(bot, "<" + bot.getGameProfile().getName() + "> brain error: " + message);
    }

    public void reset(AIPlayerEntity bot) {
        conversations.remove(bot.getUuid());
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        conversations.clear();
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

    public int conversationCount() {
        return conversations.size();
    }

    private void submit(AIPlayerEntity bot, BotConversation conversation) {
        List<ChatMessage> historySnapshot = List.copyOf(conversation.history);
        List<ToolDefinition> toolsSnapshot = toolRegistry.allTools();
        executor.submit(bot, historySnapshot, toolsSnapshot, this::onResponse, this::onError);
    }

    private void scheduleContinuation(AIPlayerEntity bot, BotConversation conversation) {
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() ->
                bot.getServer().execute(() -> {
                    if (conversations.get(bot.getUuid()) != conversation || !conversation.busy) {
                        return;
                    }
                    PerceptionSnapshot snapshot = PerceptionCollector.collect(bot);
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

    private static void broadcast(AIPlayerEntity bot, String message) {
        bot.getServer().getPlayerManager().broadcast(Text.literal(message), false);
    }

    private static String systemPrompt(String botName) {
        return """
                You are a player in Minecraft named %s. You exist as a real player in the world and can interact with it using the tools provided.

                Rules:
                1. Understand the human's intent first, then break it into tool calls.
                2. Coordinates are integers (block positions).
                3. To move close to a block before mining it, call move_to with coords adjacent to the target, not the target itself.
                4. After each action, look at the next world state (passed in user messages) and decide the next step.
                5. Use the say tool to reply to humans. Keep replies short (one sentence).
                6. When the task is complete or impossible, say so and stop calling tools.

                Available tools are declared in the tools field. You MUST use them; do not invent tools.
                """.formatted(botName);
    }

    private static final class BotConversation {
        private final Deque<ChatMessage> history = new ArrayDeque<>();
        private int turnsInCurrentRequest;
        private boolean busy;
        private int lastPromptTokens;
        private int lastCompletionTokens;
        private int lastCacheHitTokens;
    }

    public record BrainStatus(boolean busy, int historySize, int promptTokens, int completionTokens, int cacheHitTokens) {
    }
}
