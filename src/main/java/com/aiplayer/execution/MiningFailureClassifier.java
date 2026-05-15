package com.aiplayer.execution;

import java.util.Locale;

public final class MiningFailureClassifier {
    private MiningFailureClassifier() {
    }

    public static Classification classify(String reason) {
        String normalized = normalize(reason);
        if (containsAny(normalized, "wrong_tool_tier", "missing_tool", "tool_missing", "low_tool_durability", "replacement_tool_not_ready")) {
            return new Classification("tool_requirement", "缺少合适工具或工具耐久不足", "给 AI 放入合适等级且耐久足够的镐，或提供制作替换工具的材料", "prepare_tool");
        }
        if (containsAny(normalized, "unsafe", "danger", "lava", "water", "fire", "fall", "void")) {
            return new Classification("environment_blocked", "环境风险阻断当前路线", "当前 AI 已尽量忽略伤害，但仍需要换路线或让玩家清理无法通行的环境阻断", "switch_stair_direction");
        }
        if (containsAny(normalized, "unbreakable", "bedrock", "breakable_null", "stair_clearance_blocked")) {
            return new Classification("unbreakable_block", "目标方块不可破坏或当前工具不能破坏", "换一个矿点，或确认目标不是基岩、不可破坏方块、沙砾/沙子等路线阻断方块", "rescan_target");
        }
        if (containsAny(normalized, "chunk_unloaded", "not_loaded", "unloaded")) {
            return new Classification("chunk_unloaded", "目标区块未加载", "靠近目标区域或等待区块加载后重试", "ask_player");
        }
        if (containsAny(normalized, "out_of_world", "world_boundary", "below_min_y", "target_above_current_layer", "above_current_layer")) {
            return new Classification("world_or_layer_boundary", "目标高度不在当前可执行路线范围内", "让 AI 重新探矿，或移动到与目标矿层更接近的位置后重试", "rescan_target");
        }
        if (containsAny(normalized, "target_air", "target_changed", "target_removed_before_mine", "prospect_target_invalid", "ore_block_opened")) {
            return new Classification("target_missing", "目标方块已经消失或发生变化", "重新探矿并选择新的矿点", "rescan_target");
        }
        if (containsAny(normalized, "prospect_not_found", "not_prospectable_no_target", "no_downward_target", "guided_tunnel_no_target")) {
            return new Classification("no_target", "当前扫描没有找到可执行目标", "重新探矿、换起挖方向，或移动到更可能存在目标资源的位置", "rescan_target");
        }
        if (containsAny(normalized, "blocked_line_of_sight", "occlusion", "interaction_target_changed", "interaction_changed")) {
            return new Classification("interaction_blocked", "交互目标被遮挡或交互站位发生变化", "先清理遮挡方块，或换一个可触及站位后继续", "switch_stair_direction");
        }
        if (containsAny(normalized, "break_failed", "destroy_failed")) {
            return new Classification("break_failed", "服务器拒绝破坏目标方块或破坏结果未生效", "重新验证目标方块、工具和触及范围，必要时重扫目标", "rescan_target");
        }
        if (containsAny(normalized, "no_progress", "no_item_progress", "timeout", "limit", "too_many")) {
            return new Classification("no_progress", "长时间没有实际进展", "查看状态里的当前阶段，必要时把 AI 移到矿洞、低处或给 AI 放入缺少的前置材料", "reset_descent_start");
        }
        if (containsAny(normalized, "movement_stuck", "move_stuck", "navigation_stuck", "adjacent_mining_step_stuck", "unreachable", "no_path")) {
            return new Classification("unreachable", "路线站位不可达或移动卡住", "换一个起挖点，或清理 AI 周围阻挡后重试", "switch_stair_direction");
        }
        if (containsAny(normalized, "inventory_full", "inventory_full_for_drop", "drop_uncollected")) {
            return new Classification("inventory_full", "背包空间不足，无法完整收集掉落", "打开 AI 背包取出材料，或提供附近箱子后重试", "ask_player");
        }
        if (containsAny(normalized, "passage_blocked", "guided_tunnel_blocked", "branch_tunnel_blocked", "no_clearance", "stand_invalid")) {
            return new Classification("path_blocked", "矿道无法形成两格高可通行空间", "让 AI 重新探矿或换方向继续，必要时移动到更开阔的矿洞入口", "switch_stair_direction");
        }
        return new Classification("unknown_hard_failure", "未知硬失败", "查看 mining.log 中的最近路线决策和最后失败点", "ask_player");
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String reason) {
        return reason == null ? "" : reason.toLowerCase(Locale.ROOT);
    }

    public record Classification(String code, String cause, String suggestion, String recoveryAction) {
        public Classification {
            code = code == null || code.isBlank() ? "unknown_hard_failure" : code;
            cause = cause == null || cause.isBlank() ? "未知硬失败" : cause;
            suggestion = suggestion == null || suggestion.isBlank() ? "查看 mining.log 获取更多信息" : suggestion;
            recoveryAction = recoveryAction == null || recoveryAction.isBlank() ? "ask_player" : recoveryAction;
        }

        public String toLogText() {
            return "category=" + code + ", cause=" + cause + ", recoveryAction=" + recoveryAction + ", suggestion=" + suggestion;
        }
    }
}
