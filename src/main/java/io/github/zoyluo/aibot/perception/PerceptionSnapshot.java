package io.github.zoyluo.aibot.perception;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public record PerceptionSnapshot(
        SelfState self,
        List<NearbyBlock> blocks,
        List<NearbyEntity> entities,
        List<NearbyItem> items,
        TimeInfo time
) {
    private static final Gson GSON = new Gson();

    public String toJson() {
        return GSON.toJson(this);
    }

    public record SelfState(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float hp,
            int hunger,
            String holdingItem,
            Map<String, Integer> inventoryCount
    ) {
    }

    public record NearbyBlock(String type, int x, int y, int z, double distance) {
    }

    public record NearbyEntity(String type, double x, double y, double z, double distance, boolean hostile, float hp) {
    }

    public record NearbyItem(String type, double x, double y, double z) {
    }

    public record TimeInfo(long worldTime, boolean isDay, int light) {
    }
}
