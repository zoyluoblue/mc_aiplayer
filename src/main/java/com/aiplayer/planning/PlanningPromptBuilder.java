package com.aiplayer.planning;

import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;

public final class PlanningPromptBuilder {
    private PlanningPromptBuilder() {
    }

    public static String buildSystemPrompt() {
        return """
            You are a Minecraft survival planning agent. Respond ONLY with valid JSON.
            You produce high-level verified steps, not coordinates or low-level block operations.
            Do not invent recipes, item IDs, tools, chest contents, blocks, or creative-mode abilities.
            Valid steps: gather_tree, gather_stone, gather, craft_inventory, craft_station, fill_water, withdraw_chest, deposit_chest, place_block, return_to_owner.
            Forbidden steps: give, summon, teleport, fill, setblock, creative, spawn_item.
            Keep text fields in Simplified Chinese, but keep JSON keys, item IDs, goal, and step names in English.
            JSON format:
            {"goal":"make_item","target":{"item":"minecraft:oak_door","count":1},"plan":[{"step":"gather_tree","resource":"tree","item":"minecraft:oak_log","count":2}],"replanAfter":"each_step","reason":"..."}
            Output only JSON.
            """;
    }

    public static String buildUserPrompt(String command, WorldSnapshot snapshot, RecipePlan recipePlan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("PLAYER_COMMAND:\n").append(command == null ? "" : command).append("\n\n");
        prompt.append("WORLD_SNAPSHOT_JSON:\n").append(SnapshotSerializer.toCompactJson(snapshot)).append("\n\n");
        prompt.append("LOCAL_RECIPE_PLAN:\n").append(recipePlan == null ? "none" : recipePlan.toUserText()).append("\n\n");
        prompt.append("Use LOCAL_RECIPE_PLAN as authoritative recipe and material math. If your idea conflicts with it, follow LOCAL_RECIPE_PLAN.\n");
        return prompt.toString();
    }
}
