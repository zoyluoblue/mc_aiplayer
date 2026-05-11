package com.aiplayer.llm.resilience;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.llm.async.AsyncLLMClient;
import com.aiplayer.llm.async.LLMCache;
import com.aiplayer.llm.async.LLMException;
import com.aiplayer.llm.async.LLMResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public class ResilientLLMClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientLLMClient.class);

    private final AsyncLLMClient delegate;
    private final LLMCache cache;
    private final LLMFallbackHandler fallbackHandler;

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;

        public ResilientLLMClient(AsyncLLMClient delegate, LLMCache cache, LLMFallbackHandler fallbackHandler) {
        this.delegate = delegate;
        this.cache = cache;
        this.fallbackHandler = fallbackHandler;

        String providerId = delegate.getProviderId();
        AiPlayerMod.info("llm", "Initializing resilient client for provider: {}", providerId);
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
            ResilienceConfig.createCircuitBreakerConfig());
        RetryRegistry retryRegistry = RetryRegistry.of(
            ResilienceConfig.createRetryConfig());
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.of(
            ResilienceConfig.createRateLimiterConfig());
        BulkheadRegistry bhRegistry = BulkheadRegistry.of(
            ResilienceConfig.createBulkheadConfig());

        this.circuitBreaker = cbRegistry.circuitBreaker(providerId);
        this.retry = retryRegistry.retry(providerId);
        this.rateLimiter = rlRegistry.rateLimiter(providerId);
        this.bulkhead = bhRegistry.bulkhead(providerId);
        registerEventListeners(providerId);

        AiPlayerMod.info("llm", "Resilient client initialized for provider: {} (circuit breaker: {}, retry: {}, rate limiter: {}, bulkhead: {})",
            providerId, circuitBreaker.getName(), retry.getName(), rateLimiter.getName(), bulkhead.getName());
    }

        private void registerEventListeners(String providerId) {
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> {
                LOGGER.warn("[{}] Circuit breaker state: {} -> {}",
                    providerId,
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState());
            });
        circuitBreaker.getEventPublisher()
            .onError(event -> {
                LOGGER.debug("[{}] Circuit breaker recorded error: {} (duration: {}ms)",
                    providerId,
                    event.getThrowable().getClass().getSimpleName(),
                    event.getElapsedDuration().toMillis());
            });
        retry.getEventPublisher()
            .onRetry(event -> {
                LOGGER.warn("[{}] Retry attempt {} of {} after {} (reason: {})",
                    providerId,
                    event.getNumberOfRetryAttempts(),
                    ResilienceConfig.getRetryMaxAttempts(),
                    event.getWaitInterval(),
                    event.getLastThrowable() != null ? event.getLastThrowable().getMessage() : "unknown");
            });
        rateLimiter.getEventPublisher()
            .onFailure(event -> {
                LOGGER.warn("[{}] Rate limiter rejected request (limit: {} req/min)",
                    providerId,
                    ResilienceConfig.getRateLimitPerMinute());
            });
        bulkhead.getEventPublisher()
            .onCallRejected(event -> {
                LOGGER.warn("[{}] Bulkhead rejected request (max concurrent: {})",
                    providerId,
                    ResilienceConfig.getBulkheadMaxConcurrentCalls());
            });
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        String model = (String) params.getOrDefault("model", "unknown");
        String providerId = delegate.getProviderId();
        Optional<LLMResponse> cached = cache.get(prompt, model, providerId);
        if (cached.isPresent()) {
            LOGGER.debug("[{}] Cache hit for prompt (hash: {})", providerId, prompt.hashCode());
            return CompletableFuture.completedFuture(cached.get());
        }

        LOGGER.debug("[{}] Cache miss, executing request with resilience patterns", providerId);
        return executeWithResilience(prompt, params);
    }

        private CompletableFuture<LLMResponse> executeWithResilience(String prompt, Map<String, Object> params) {
        String providerId = delegate.getProviderId();
        String model = (String) params.getOrDefault("model", "unknown");
        Supplier<CompletableFuture<LLMResponse>> asyncSupplier = () -> delegate.sendAsync(prompt, params);
        Supplier<CompletableFuture<LLMResponse>> decoratedSupplier = decorateWithResilience(asyncSupplier);

        try {
            return decoratedSupplier.get()
                .thenApply(response -> {
                    cache.put(prompt, model, providerId, response);
                    LOGGER.debug("[{}] Request successful, cached response (latency: {}ms, tokens: {})",
                        providerId, response.getLatencyMs(), response.getTokensUsed());
                    return response;
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof CompletionException ?
                        throwable.getCause() : throwable;

                    LOGGER.error("[{}] Request failed after all retries, using fallback: {}",
                        providerId, cause.getMessage());
                    return fallbackHandler.generateFallback(prompt, cause);
                });

        } catch (Exception e) {
            LOGGER.error("[{}] Request rejected by resilience layer: {}", providerId, e.getMessage());
            return CompletableFuture.completedFuture(fallbackHandler.generateFallback(prompt, e));
        }
    }

        private Supplier<CompletableFuture<LLMResponse>> decorateWithResilience(
            Supplier<CompletableFuture<LLMResponse>> supplier) {
        Supplier<CompletableFuture<LLMResponse>> withRetry = Retry.decorateSupplier(retry, supplier);
        Supplier<CompletableFuture<LLMResponse>> withCircuitBreaker =
            CircuitBreaker.decorateSupplier(circuitBreaker, withRetry);
        Supplier<CompletableFuture<LLMResponse>> withBulkhead =
            Bulkhead.decorateSupplier(bulkhead, withCircuitBreaker);
        Supplier<CompletableFuture<LLMResponse>> withRateLimiter =
            RateLimiter.decorateSupplier(rateLimiter, withBulkhead);

        return withRateLimiter;
    }

    @Override
    public String getProviderId() {
        return delegate.getProviderId();
    }

    @Override
    public boolean isHealthy() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN;
    }

        public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

        public CircuitBreaker.Metrics getCircuitBreakerMetrics() {
        return circuitBreaker.getMetrics();
    }

        public RateLimiter.Metrics getRateLimiterMetrics() {
        return rateLimiter.getMetrics();
    }

        public Bulkhead.Metrics getBulkheadMetrics() {
        return bulkhead.getMetrics();
    }

        public void resetCircuitBreaker() {
        circuitBreaker.reset();
        AiPlayerMod.info("llm", "[{}] Circuit breaker manually reset to CLOSED", delegate.getProviderId());
    }
}
