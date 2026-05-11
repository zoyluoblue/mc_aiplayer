package com.aiplayer.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ActionManifest {
    private final Map<String, ActionSpec> actions;

    private ActionManifest(Map<String, ActionSpec> actions) {
        this.actions = Map.copyOf(actions);
    }

    public static ActionManifest survivalDefaults() {
        Map<String, ActionSpec> specs = new LinkedHashMap<>();
        add(specs, "make_item", List.of("item", "quantity"), "按生存链制作目标物品", false, true);
        add(specs, "gather_resource", List.of("resource", "quantity"), "采集基础资源", false, true);
        add(specs, "gather_tree", List.of("item", "count"), "采集树木 step", false, true);
        add(specs, "gather_stone", List.of("item", "count"), "采集圆石 step", false, true);
        add(specs, "gather", List.of("item", "count"), "采集基础资源 step", false, true);
        add(specs, "mine_block", List.of("block", "quantity"), "挖掘指定方块", true, true);
        add(specs, "craft", List.of("item", "quantity"), "合成物品，必须经过配方验证", false, true);
        add(specs, "craft_inventory", List.of("item", "count"), "背包合成 step", false, true);
        add(specs, "craft_station", List.of("item", "count"), "工作站合成 step", false, true);
        add(specs, "smelt", List.of("item", "quantity"), "使用工作站烧炼或烹饪", false, true);
        add(specs, "place_block", List.of("block", "x", "y", "z"), "放置背包内方块", true, true);
        add(specs, "pickup_item", List.of("item", "quantity"), "拾取附近掉落物", false, true);
        add(specs, "withdraw_from_chest", List.of("item", "quantity"), "从已观察到的箱子取物", false, true);
        add(specs, "withdraw_chest", List.of("item", "count"), "从已观察到的箱子取物 step", false, true);
        add(specs, "deposit_to_chest", List.of("item", "quantity"), "向箱子放入物品", false, true);
        add(specs, "fill_water", List.of("item", "quantity"), "用空桶从水源装水", false, true);
        add(specs, "attack_entity", List.of("target"), "攻击指定实体", true, true);
        add(specs, "move_near", List.of("target"), "移动到目标附近", false, true);
        add(specs, "follow_player", List.of("player"), "跟随玩家", false, true);
        add(specs, "return_to_owner", List.of(), "回到拥有者玩家身边", false, true);
        add(specs, "stop", List.of(), "停止当前任务", false, true);
        add(specs, "recall", List.of(), "召回到玩家身边", false, false);
        return new ActionManifest(specs);
    }

    private static void add(Map<String, ActionSpec> specs, String name, List<String> required, String description, boolean highRisk, boolean deepSeekCallable) {
        specs.put(name, new ActionSpec(name, required, description, highRisk, deepSeekCallable));
    }

    public boolean isAllowed(String action) {
        return actions.containsKey(action);
    }

    public boolean isDeepSeekCallable(String action) {
        ActionSpec spec = actions.get(action);
        return spec != null && spec.deepSeekCallable();
    }

    public ActionSpec get(String action) {
        return actions.get(action);
    }

    public Set<String> names() {
        return actions.keySet();
    }

    public List<String> validate(String action, Map<String, ?> parameters) {
        ActionSpec spec = actions.get(action);
        if (spec == null) {
            return List.of("未声明动作：" + action);
        }
        Map<String, ?> safeParameters = parameters == null ? Map.of() : parameters;
        return spec.requiredParameters()
            .stream()
            .filter(parameter -> !safeParameters.containsKey(parameter))
            .map(parameter -> action + " 缺少参数：" + parameter)
            .toList();
    }

    public String toPromptText() {
        StringBuilder builder = new StringBuilder();
        for (ActionSpec spec : actions.values()) {
            if (!spec.deepSeekCallable()) {
                continue;
            }
            builder.append("- ")
                .append(spec.name())
                .append("(")
                .append(String.join(", ", spec.requiredParameters()))
                .append("): ")
                .append(spec.description())
                .append(spec.highRisk() ? " [needs local safety check]" : "")
                .append("\n");
        }
        return builder.toString();
    }
}
