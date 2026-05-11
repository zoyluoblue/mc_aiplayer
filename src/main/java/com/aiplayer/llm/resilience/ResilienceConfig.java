package com.aiplayer.llm.resilience;

import com.aiplayer.llm.async.LLMException;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.core.IntervalFunction;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class ResilienceConfig {
    private static final int CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE = 10;
    private static final float CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD = 50.0f;
    private static final int CIRCUIT_BREAKER_WAIT_DURATION_SECONDS = 30;
    private static final int CIRCUIT_BREAKER_HALF_OPEN_CALLS = 3;
    private static final int RETRY_MAX_ATTEMPTS = 3;
    private static final int RETRY_INITIAL_INTERVAL_MS = 1000;
    private static final int RATE_LIMIT_PER_MINUTE = 10;
    private static final int RATE_LIMITER_TIMEOUT_SECONDS = 5;
    private static final int BULKHEAD_MAX_CONCURRENT_CALLS = 5;
    private static final int BULKHEAD_MAX_WAIT_DURATION_SECONDS = 10;

        public static CircuitBreakerConfig createCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE)
            .failureRateThreshold(CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD)
            .waitDurationInOpenState(Duration.ofSeconds(CIRCUIT_BREAKER_WAIT_DURATION_SECONDS))
            .permittedNumberOfCallsInHalfOpenState(CIRCUIT_BREAKER_HALF_OPEN_CALLS)
            .recordExceptions(IOException.class, TimeoutException.class, LLMException.class)
            .ignoreExceptions(IllegalArgumentException.class)
            .build();
    }

        public static RetryConfig createRetryConfig() {
        return RetryConfig.custom()
            .maxAttempts(RETRY_MAX_ATTEMPTS)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(RETRY_INITIAL_INTERVAL_MS, 2))
            .retryOnException(throwable -> {
                if (throwable instanceof IOException || throwable instanceof TimeoutException) {
                    return true;
                }
                if (throwable instanceof LLMException) {
                    return ((LLMException) throwable).isRetryable();
                }
                return false;
            })
            .build();
    }

        public static RateLimiterConfig createRateLimiterConfig() {
        return RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .limitForPeriod(RATE_LIMIT_PER_MINUTE)
            .timeoutDuration(Duration.ofSeconds(RATE_LIMITER_TIMEOUT_SECONDS))
            .build();
    }

        public static BulkheadConfig createBulkheadConfig() {
        return BulkheadConfig.custom()
            .maxConcurrentCalls(BULKHEAD_MAX_CONCURRENT_CALLS)
            .maxWaitDuration(Duration.ofSeconds(BULKHEAD_MAX_WAIT_DURATION_SECONDS))
            .build();
    }

        public static int getCircuitBreakerSlidingWindowSize() {
        return CIRCUIT_BREAKER_SLIDING_WINDOW_SIZE;
    }

        public static float getCircuitBreakerFailureRateThreshold() {
        return CIRCUIT_BREAKER_FAILURE_RATE_THRESHOLD;
    }

        public static int getRetryMaxAttempts() {
        return RETRY_MAX_ATTEMPTS;
    }

        public static int getRateLimitPerMinute() {
        return RATE_LIMIT_PER_MINUTE;
    }

        public static int getBulkheadMaxConcurrentCalls() {
        return BULKHEAD_MAX_CONCURRENT_CALLS;
    }
}
