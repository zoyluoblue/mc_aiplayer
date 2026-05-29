package io.github.zoyluo.aibot.brain;

import java.util.Locale;
import java.util.Map;

public final class ReasonText {
    private static final Map<String, String> EXACT = Map.ofEntries(
            Map.entry("pickup_timeout", "没捡到掉落物,我会换个办法再试。"),
            Map.entry("stuck", "卡住了,我换个办法。"),
            Map.entry("no_flat_site", "附近没有合适的平地。"),
            Map.entry("no_reachable_target_block_in_range", "附近没找到够得着的目标方块。"),
            Map.entry("pathfinding_throttled", "刚刚寻路失败过,我先稍微停一下再试。"),
            Map.entry("did_not_reach", "没能走到目标位置。"),
            Map.entry("mine_timeout", "挖掘时间太久了,我先停下来重新判断。"),
            Map.entry("craft_timeout", "合成花太久了,我先停下来重新判断。"),
            Map.entry("move_timeout", "移动花太久了,我先停下来重新判断。"),
            Map.entry("build_timeout", "建造时间太久了,我先停下来重新判断。"),
            Map.entry("forage_timeout", "收集花太久了,我先停下来重新判断。"),
            Map.entry("aborted", "任务被手动停止了。"),
            Map.entry("invalid_block_id", "方块 ID 不对,我没法识别。"),
            Map.entry("unknown_block_id", "这个方块 ID 我不认识。"),
            Map.entry("unknown_palette", "蓝图里的材料表我识别不了。"),
            Map.entry("inventory_full", "背包满了,需要先回仓库存放。"),
            Map.entry("no_resource_nearby", "附近没找到可采集的资源。"),
            Map.entry("unsupported_resource_type", "这种资源暂时还不能自动采集。"),
            Map.entry("gather_timeout", "采集时间太久了,我先停下来重新判断。"),
            Map.entry("no_base", "还没有标记基地,先让我记住基地位置。"),
            Map.entry("no_base_container", "基地附近没找到可用箱子。"),
            Map.entry("container_full", "附近容器满了,东西还留在背包里。"),
            Map.entry("partial_stockpile_container_full", "已经存了一部分,但基地箱子装不下剩余物品。"),
            Map.entry("stockpile_timeout", "归仓时间太久了,我先停下来重新判断。"),
            Map.entry("no_supply", "基地没有可用补给,材料也不够现造。"),
            Map.entry("no_food", "背包里没有可吃的食物。"),
            Map.entry("resupply_timeout", "补给时间太久了,我先停下来重新判断。"),
            Map.entry("out_of_fuel", "燃料不够,背包和基地箱子里都没找到可用燃料。")
    );

    private ReasonText() {
    }

    public static String friendly(String reason) {
        if (reason == null || reason.isBlank()) {
            return "没有拿到具体失败原因。";
        }
        String trimmed = reason.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("need:")) {
            return "还缺少 " + itemText(trimmed.substring(trimmed.indexOf(':') + 1).trim()) + "。";
        }
        if (lower.startsWith("stuck:")) {
            return "卡住了,我会换个办法处理 " + taskName(trimmed.substring(trimmed.indexOf(':') + 1).trim()) + "。";
        }
        if (lower.startsWith("pathfinding_failed:")) {
            return "路走不过去: " + pathReason(trimmed.substring(trimmed.indexOf(':') + 1).trim()) + "。";
        }
        if (lower.startsWith("place_crafting_table_failed:")) {
            return "工作台没放成功: " + friendly(trimmed.substring(trimmed.indexOf(':') + 1).trim());
        }
        String exact = EXACT.get(lower);
        if (exact != null) {
            return exact;
        }
        return "失败原因: " + itemText(trimmed) + "。";
    }

    public static String taskName(String name) {
        return switch (name) {
            case "mine" -> "挖掘";
            case "craft" -> "合成";
            case "smelt" -> "烧炼";
            case "move" -> "移动";
            case "eat" -> "进食";
            case "sleep" -> "睡觉";
            case "combat" -> "战斗";
            case "evade" -> "躲避危险";
            case "light_area" -> "插火把照明";
            case "build" -> "建造";
            case "forage" -> "收集";
            case "container" -> "整理容器";
            case "stockpile" -> "归仓";
            case "resupply" -> "自动补给";
            case "strip_mine" -> "鱼骨挖矿";
            case "farm" -> "农场工作";
            case "breed" -> "繁殖动物";
            case "fish" -> "钓鱼";
            case "follow" -> "跟随";
            case "hold" -> "待命";
            case "guard" -> "护卫";
            case "shelter" -> "应急避难";
            default -> itemText(name);
        };
    }

    public static String itemText(String text) {
        if (text == null || text.isBlank()) {
            return "未知内容";
        }
        return text.replace("minecraft:", "")
                .replace('_', ' ')
                .replace(" x", " x")
                .trim();
    }

    private static String pathReason(String reason) {
        return switch (reason.toUpperCase(Locale.ROOT)) {
            case "NO_START" -> "起点站不住";
            case "GOAL_UNREACHABLE" -> "目标不可达";
            case "SEARCH_LIMIT" -> "搜索范围太大";
            case "TIMEOUT" -> "寻路超时";
            case "GOAL_NOT_STANDABLE" -> "目标位置站不住";
            default -> itemText(reason);
        };
    }
}
