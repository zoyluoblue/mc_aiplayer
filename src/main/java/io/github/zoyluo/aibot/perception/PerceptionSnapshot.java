package io.github.zoyluo.aibot.perception;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public record PerceptionSnapshot(
        SelfState self,
        TaskInfo task,
        Highlights highlights,
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

    public record Highlights(
            List<NearbyBlock> nearest_tree,
            List<NearbyBlock> nearest_stone,
            List<NearbyBlock> nearest_ore,
            List<NearbyBlock> nearest_water,
            List<NearbyBlock> nearest_lava,
            List<NearbyBlock> nearest_furnace,
            List<NearbyBlock> nearest_chest,
            List<NearbyBlock> nearest_bed,
            List<NearbyBlock> nearest_crafting_table,
            List<NearbyEntity> nearest_hostile,
            // 治"感知瞎":此前主人位置/血量/挨打、动物、其他玩家在自动快照里全不可见,必须先 scan。
            // Gson 跳过 null 字段:主人离线/不同维度时 owner 缺席,模型按"看不到主人"理解。
            OwnerInfo owner,
            List<NearbyEntity> nearest_passive,
            NearbyEntity nearest_player
    ) {
    }

    /** 主人状态:recentlyHurt=5s 内被打过(lastAttacker 是打人的),bot 该有反应了。 */
    public record OwnerInfo(String name, double x, double y, double z, double distance,
                            float hp, boolean recentlyHurt, String lastAttacker) {
    }

    public record NearbyEntity(String type, double x, double y, double z, double distance, boolean hostile, float hp) {
    }

    public record NearbyItem(String type, double x, double y, double z) {
    }

    public record TaskInfo(String name, String state, double progress, int elapsedTicks, String description, String failureReason) {
    }

    public record TimeInfo(long worldTime, boolean isDay, int light) {
    }
}
