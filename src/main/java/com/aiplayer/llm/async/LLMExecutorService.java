package com.aiplayer.llm.async;

import com.aiplayer.AiPlayerMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LLMExecutorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LLMExecutorService.class);
    private static final LLMExecutorService INSTANCE = new LLMExecutorService();

    private static final int THREAD_COUNT = 5;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final ExecutorService deepseekExecutor;
    private volatile boolean isShutdown = false;

    private LLMExecutorService() {
        this.deepseekExecutor = Executors.newFixedThreadPool(
            THREAD_COUNT,
            new NamedThreadFactory("llm-deepseek")
        );
        AiPlayerMod.info("llm", "DeepSeek executor service initialized with {} threads", THREAD_COUNT);
    }

    public static LLMExecutorService getInstance() {
        return INSTANCE;
    }

    public ExecutorService getExecutor(String providerId) {
        if (isShutdown) {
            throw new IllegalStateException("LLMExecutorService has been shut down");
        }
        if (!"deepseek".equalsIgnoreCase(providerId)) {
            throw new IllegalArgumentException("Only the DeepSeek provider is available: " + providerId);
        }
        return deepseekExecutor;
    }

    public synchronized void shutdown() {
        if (isShutdown) {
            LOGGER.debug("LLMExecutorService already shut down, ignoring duplicate shutdown call");
            return;
        }

        AiPlayerMod.info("llm", "Shutting down DeepSeek executor service...");
        isShutdown = true;
        shutdownExecutor(deepseekExecutor);
        AiPlayerMod.info("llm", "DeepSeek executor service shut down successfully");
    }

    private void shutdownExecutor(ExecutorService executor) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("DeepSeek executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while shutting down DeepSeek executor", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger threadNumber = new AtomicInteger(0);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + threadNumber.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
