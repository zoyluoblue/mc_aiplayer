package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.observe.BotProfiler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class AsyncDecisionExecutor {
    private final DeepSeekApiClient apiClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public AsyncDecisionExecutor(DeepSeekApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void submit(AIPlayerEntity bot,
                       DecisionLease lease,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       BiConsumer<DecisionLease, ChatResponse> onResponse,
                       BiConsumer<DecisionLease, Throwable> onError) {
        var server = bot.getServer();
        var botId = bot.getUuid();
        String botName = bot.getGameProfile().getName();
        executor.submit(() -> {
            long started = System.nanoTime();
            try {
                ChatResponse response = apiClient.chat(historySnapshot, tools);
                long elapsed = System.nanoTime() - started;
                server.execute(() -> onResponse.accept(lease, response));
                BotProfiler.INSTANCE.record(botId, botName, "brain_latency", elapsed);
            } catch (Exception exception) {
                long elapsed = System.nanoTime() - started;
                BotProfiler.INSTANCE.record(botId, botName, "brain_latency_error", elapsed);
                server.execute(() -> onError.accept(lease, exception));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
