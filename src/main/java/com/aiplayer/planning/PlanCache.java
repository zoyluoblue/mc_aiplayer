package com.aiplayer.planning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class PlanCache {
    private static final int MAX_ENTRIES = 64;
    private final Map<String, PlanSchema> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PlanSchema> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public Optional<PlanSchema> get(String key) {
        return Optional.ofNullable(cache.get(key));
    }

    public void put(String key, PlanSchema plan) {
        cache.put(key, plan);
    }

    public String key(String command, String targetItem, int targetCount, Map<String, Integer> availableItems) {
        return command + "|" + targetItem + "|" + targetCount + "|" + availableItems;
    }
}
