package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;

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
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       BiConsumer<AIPlayerEntity, ChatResponse> onResponse,
                       BiConsumer<AIPlayerEntity, Throwable> onError) {
        executor.submit(() -> {
            try {
                ChatResponse response = apiClient.chat(historySnapshot, tools);
                bot.getServer().execute(() -> onResponse.accept(bot, response));
            } catch (Throwable throwable) {
                bot.getServer().execute(() -> onError.accept(bot, throwable));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
