package io.github.zoyluo.aibot.persist;

public record BotRecord(
        String name,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        float health,
        int hunger,
        String inventoryNbt,
        String role,
        String memoryNbt,
        String ownerUuid
) {
}
