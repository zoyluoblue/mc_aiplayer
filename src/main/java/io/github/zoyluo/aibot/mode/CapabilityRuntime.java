package io.github.zoyluo.aibot.mode;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Minecraft adapter for the pure capability policy. Every privileged execution is decided first. */
public final class CapabilityRuntime {
    private static final int REPEATED_DECISION_LOG_INTERVAL = 100;
    private static final ConcurrentHashMap<AuditKey, Integer> NEXT_LOG_TICK = new ConcurrentHashMap<>();

    private CapabilityRuntime() {
    }

    public static CapabilityDecision decide(AIPlayerEntity bot,
                                            PrivilegedCapability capability,
                                            String context) {
        AIBotConfig config = AIBotConfig.get();
        CapabilityDecision decision = CapabilityPolicy.decide(
                config.profile(), config.operatorCapabilities(), capability);
        String normalizedContext = context == null ? "" : context;
        int now = bot.getServer().getTicks();
        AuditKey key = new AuditKey(bot.getUuid(), capability, normalizedContext, decision.allowed());
        Integer next = NEXT_LOG_TICK.get(key);
        boolean alwaysAudit = capability == PrivilegedCapability.MANUAL_TELEPORT
                || (capability == PrivilegedCapability.EMERGENCY_TELEPORT && decision.allowed());
        if (alwaysAudit || next == null || now >= next) {
            NEXT_LOG_TICK.put(key, now + REPEATED_DECISION_LOG_INTERVAL);
            BotLog.action(bot, "capability_decision",
                    "profile", decision.profile().configValue(),
                    "capability", decision.capability(),
                    "allowed", decision.allowed(),
                    "reason", decision.reason(),
                    "context", normalizedContext);
        }
        return decision;
    }

    public static boolean run(AIPlayerEntity bot,
                              PrivilegedCapability capability,
                              String context,
                              Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (!decide(bot, capability, context).allowed()) {
            return false;
        }
        operation.run();
        return true;
    }

    public static <T> Result<T> call(AIPlayerEntity bot,
                                     PrivilegedCapability capability,
                                     String context,
                                     Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CapabilityDecision decision = decide(bot, capability, context);
        return decision.allowed()
                ? new Result<>(decision, true, operation.get())
                : new Result<>(decision, false, null);
    }

    public static void clear(AIPlayerEntity bot) {
        NEXT_LOG_TICK.keySet().removeIf(key -> key.botId().equals(bot.getUuid()));
    }

    public static void clearAll() {
        NEXT_LOG_TICK.clear();
    }

    public record Result<T>(CapabilityDecision decision, boolean executed, T value) {
    }

    private record AuditKey(UUID botId, PrivilegedCapability capability, String context, boolean allowed) {
    }
}
