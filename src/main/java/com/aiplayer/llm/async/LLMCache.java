package com.aiplayer.llm.async;

import com.aiplayer.AiPlayerMod;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class LLMCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMCache.class);

    private static final int MAX_CACHE_SIZE = 500;
    private static final int TTL_MINUTES = 5;

    private final Cache<String, LLMResponse> cache;

        public LLMCache() {
        AiPlayerMod.info("llm", "Initializing LLM cache (max size: {}, TTL: {} minutes)", MAX_CACHE_SIZE, TTL_MINUTES);

        this.cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
            .recordStats()
            .build();

        AiPlayerMod.info("llm", "LLM cache initialized successfully");
    }

        public Optional<LLMResponse> get(String prompt, String model, String providerId) {
        String key = generateKey(prompt, model, providerId);
        LLMResponse cached = cache.getIfPresent(key);

        if (cached != null) {
            LOGGER.debug("Cache HIT for provider={}, model={}, promptHash={}",
                providerId, model, key.substring(0, 8));
        } else {
            LOGGER.debug("Cache MISS for provider={}, model={}, promptHash={}",
                providerId, model, key.substring(0, 8));
        }

        return Optional.ofNullable(cached);
    }

        public void put(String prompt, String model, String providerId, LLMResponse response) {
        String key = generateKey(prompt, model, providerId);
        LLMResponse cachedResponse = response.withCacheFlag(true);
        cache.put(key, cachedResponse);

        LOGGER.debug("Cached response for provider={}, model={}, promptHash={}, tokens={}",
            providerId, model, key.substring(0, 8), response.getTokensUsed());
    }

        private String generateKey(String prompt, String model, String providerId) {
        String composite = providerId + ":" + model + ":" + prompt;
        return DigestUtils.sha256Hex(composite);
    }

        public CacheStats getStats() {
        return cache.stats();
    }

        public long size() {
        return cache.estimatedSize();
    }

        public void clear() {
        long sizeBefore = cache.estimatedSize();
        cache.invalidateAll();
        AiPlayerMod.info("llm", "Cache cleared, removed ~{} entries", sizeBefore);
    }

        public void logStats() {
        CacheStats stats = getStats();
        AiPlayerMod.info("llm", "LLM Cache Stats - Size: ~{}/{}, Hit Rate: {}%, Hits: {}, Misses: {}, Evictions: {}",
            size(),
            MAX_CACHE_SIZE,
            stats.hitRate() * 100,
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount()
        );
    }
}
