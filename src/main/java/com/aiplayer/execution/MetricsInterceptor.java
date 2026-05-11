package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class MetricsInterceptor implements ActionInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsInterceptor.class);

        private final ConcurrentHashMap<String, ActionMetrics> metricsMap;

        private final ConcurrentHashMap<Integer, Long> startTimes;

    public MetricsInterceptor() {
        this.metricsMap = new ConcurrentHashMap<>();
        this.startTimes = new ConcurrentHashMap<>();
    }

    @Override
    public boolean beforeAction(BaseAction action, ActionContext context) {
        String actionType = extractActionType(action);
        startTimes.put(System.identityHashCode(action), System.currentTimeMillis());
        getOrCreateMetrics(actionType).incrementExecutions();

        return true;
    }

    @Override
    public void afterAction(BaseAction action, ActionResult result, ActionContext context) {
        String actionType = extractActionType(action);
        ActionMetrics metrics = getOrCreateMetrics(actionType);
        Long startTime = startTimes.remove(System.identityHashCode(action));
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        metrics.addDuration(duration);
        if (result.isSuccess()) {
            metrics.incrementSuccesses();
        } else {
            metrics.incrementFailures();
        }

        LOGGER.debug("[METRICS] {} - duration: {}ms, total: {}, success rate: {:.1f}%",
            actionType, duration, metrics.getTotalExecutions(), metrics.getSuccessRate() * 100);
    }

    @Override
    public boolean onError(BaseAction action, Exception exception, ActionContext context) {
        String actionType = extractActionType(action);
        getOrCreateMetrics(actionType).incrementErrors();
        startTimes.remove(System.identityHashCode(action));

        return false;
    }

    @Override
    public int getPriority() {
        return 900;
    }

    @Override
    public String getName() {
        return "MetricsInterceptor";
    }

        private String extractActionType(BaseAction action) {
        String className = action.getClass().getSimpleName();
        if (className.endsWith("Action")) {
            return className.substring(0, className.length() - 6).toLowerCase();
        }
        return className.toLowerCase();
    }

        private ActionMetrics getOrCreateMetrics(String actionType) {
        return metricsMap.computeIfAbsent(actionType, k -> new ActionMetrics());
    }

        public MetricsSnapshot getMetrics(String actionType) {
        ActionMetrics metrics = metricsMap.get(actionType);
        return metrics != null ? metrics.snapshot() : null;
    }

        public Map<String, MetricsSnapshot> getAllMetrics() {
        ConcurrentHashMap<String, MetricsSnapshot> result = new ConcurrentHashMap<>();
        metricsMap.forEach((key, value) -> result.put(key, value.snapshot()));
        return result;
    }

        public void reset() {
        metricsMap.clear();
        startTimes.clear();
        AiPlayerMod.info("metrics", "Metrics reset");
    }

        private static class ActionMetrics {
        private final LongAdder totalExecutions = new LongAdder();
        private final LongAdder successes = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder totalDuration = new LongAdder();

        void incrementExecutions() { totalExecutions.increment(); }
        void incrementSuccesses() { successes.increment(); }
        void incrementFailures() { failures.increment(); }
        void incrementErrors() { errors.increment(); }
        void addDuration(long duration) { totalDuration.add(duration); }

        long getTotalExecutions() { return totalExecutions.sum(); }

        double getSuccessRate() {
            long total = totalExecutions.sum();
            return total > 0 ? (double) successes.sum() / total : 0.0;
        }

        MetricsSnapshot snapshot() {
            long total = totalExecutions.sum();
            long avgDuration = total > 0 ? totalDuration.sum() / total : 0;
            return new MetricsSnapshot(
                total,
                successes.sum(),
                failures.sum(),
                errors.sum(),
                totalDuration.sum(),
                avgDuration
            );
        }
    }

        public record MetricsSnapshot(
        long totalExecutions,
        long successes,
        long failures,
        long errors,
        long totalDurationMs,
        long avgDurationMs
    ) {
        public double getSuccessRate() {
            return totalExecutions > 0 ? (double) successes / totalExecutions : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                "Metrics{total=%d, success=%d, fail=%d, errors=%d, avgDuration=%dms, successRate=%.1f%%}",
                totalExecutions, successes, failures, errors, avgDurationMs, getSuccessRate() * 100);
        }
    }
}
