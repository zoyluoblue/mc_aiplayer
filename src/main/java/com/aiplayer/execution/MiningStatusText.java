package com.aiplayer.execution;

import net.minecraft.core.Direction;

import java.util.Locale;

public final class MiningStatusText {
    private MiningStatusText() {
    }

    public static String heightReason(String text) {
        if (text == null || text.isBlank()) {
            return "按默认高度继续";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("target_primary_midpoint")) {
            return "当前高度不在主要矿层，正下探到主要矿层中线";
        }
        if (lower.contains("current_in_primary_range")) {
            return "当前高度已在主要矿层内";
        }
        if (lower.contains("current_in_surface_fallback_range")) {
            return "当前高度在可用的地表或山地矿层内";
        }
        if (lower.contains("target_preferred_midpoint")) {
            return "当前高度不在推荐矿层，正下探到推荐矿层中线";
        }
        if (lower.contains("current_in_preferred_range")) {
            return "当前高度已在推荐矿层内";
        }
        if (lower.contains("default_y0_no_resource")) {
            return "没有专属矿层规则，默认向 Y=0 附近推进";
        }
        if (lower.contains("current_y_unbounded")) {
            return "该目标没有固定矿层，保持当前高度搜索";
        }
        if (lower.contains("layer_shift")) {
            return "正在换到相邻矿层继续搜索";
        }
        return code(text);
    }

    public static String routeStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "准备路线";
        }
        return switch (stage.toUpperCase(Locale.ROOT)) {
            case "DESCEND" -> "阶梯下探";
            case "TUNNEL" -> "水平推进";
            case "APPROACH" -> "靠近矿点";
            case "REPROSPECT" -> "重新探矿";
            case "EXPOSE_OR_MINE" -> "暴露或挖取矿物";
            case "EXPOSE" -> "暴露矿物";
            case "MINE" -> "挖取矿物";
            case "COLLECT" -> "收集掉落物";
            case "PROSPECT" -> "探矿";
            default -> code(stage);
        };
    }

    public static String routeStep(String step) {
        if (step == null || step.isBlank()) {
            return "等待下一步";
        }
        if ("missing_target_stand".equals(step)) {
            return "缺少目标站位，准备重建路线";
        }
        if ("reprospect_target_above".equals(step)) {
            return "矿点高于当前矿道，准备重新探矿";
        }
        if ("expose_or_mine_ore".equals(step)) {
            return "已到矿点旁，准备暴露或挖取矿物";
        }
        if ("rebuild_route".equals(step)) {
            return "路线缺失，准备重建路线";
        }
        String descendPrefix = "clear_forward_and_down_to_";
        if (step.startsWith(descendPrefix)) {
            return "挖开前方两格空间并下降到 " + step.substring(descendPrefix.length());
        }
        String forwardPrefix = "clear_forward_to_";
        if (step.startsWith(forwardPrefix)) {
            return "挖开前方两格通道并前进到 " + step.substring(forwardPrefix.length());
        }
        return code(step);
    }

    public static String direction(Direction direction) {
        if (direction == null) {
            return "未知";
        }
        return switch (direction) {
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
            case UP -> "上";
            case DOWN -> "下";
        };
    }

    public static String routeHint(String hint) {
        if (hint == null || hint.isBlank()) {
            return "本地搜索";
        }
        return switch (hint) {
            case "local_search", "local_stone_search" -> "附近搜索";
            case "surface_cave_or_shallow_stair" -> "地表洞穴或浅层阶梯";
            case "shallow_cave_or_stair" -> "浅层洞穴或阶梯";
            case "surface_cave_or_descend_to_mid_depth" -> "地表洞穴或下探到中层";
            case "descend_before_searching_surface_gold" -> "先下探到金矿层再搜索";
            case "deep_stair_mining" -> "深层阶梯挖矿";
            case "mid_depth_cave_or_stair" -> "中层洞穴或阶梯";
            case "mountain_biome_surface_or_high_cave" -> "山地地表或高处洞穴";
            case "nether_surface_scan" -> "下界地表扫描";
            case "nether_low_stair_mining" -> "下界低层阶梯挖矿";
            case "lava_pool_or_manual_bucket_route" -> "寻找岩浆池或水桶路线";
            default -> code(hint);
        };
    }

    public static String code(String value) {
        if (value == null || value.isBlank()) {
            return "无";
        }
        return value
            .replace("minecraft:", "")
            .replace("block:", "")
            .replace('_', ' ')
            .replace("  ", " ")
            .trim();
    }
}
