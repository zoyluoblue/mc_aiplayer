package com.aiplayer.planning;

import com.aiplayer.agent.ActionManifest;
import com.aiplayer.agent.AgentContext;
import com.aiplayer.llm.SurvivalPrompt;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;

public final class PlanningPromptBuilder {
    private PlanningPromptBuilder() {
    }

    public static String buildSystemPrompt() {
        return SurvivalPrompt.sharedContext() + "\n" + """
            You are a Minecraft survival planning agent. Respond ONLY with valid JSON.
            You produce high-level verified steps, not coordinates or low-level block operations.
            Do not invent recipes, item IDs, tools, chest contents, blocks, or creative-mode abilities.
            Valid action capabilities:
            %s
            Forbidden steps: give, summon, teleport, fill, setblock, creative, spawn_item.
            Keep text fields in Simplified Chinese, but keep JSON keys, item IDs, goal, and step names in English.
            JSON format:
            {"goal":"make_item","target":{"item":"minecraft:oak_door","count":1},"plan":[{"step":"gather_tree","resource":"tree","item":"minecraft:oak_log","count":2}],"replanAfter":"each_step","reason":"..."}
            Output only JSON.
            """.formatted(ActionManifest.survivalDefaults().toPromptText());
    }

    public static String buildUserPrompt(String command, WorldSnapshot snapshot, RecipePlan recipePlan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("PLAYER_COMMAND:\n").append(command == null ? "" : command).append("\n\n");
        prompt.append("WORLD_SNAPSHOT_JSON:\n").append(SnapshotSerializer.toCompactJson(snapshot)).append("\n\n");
        prompt.append("LOCAL_RECIPE_PLAN:\n").append(recipePlan == null ? "none" : recipePlan.toUserText()).append("\n\n");
        prompt.append("Use LOCAL_RECIPE_PLAN as authoritative recipe and material math. If your idea conflicts with it, follow LOCAL_RECIPE_PLAN.\n");
        return prompt.toString();
    }

    public static String buildFailureReviewPrompt(AgentContext context, String latestFailure) {
        return SurvivalPrompt.sharedContext() + "\n" + """
            FAILURE_REVIEW:
            latestFailure=%s
            agentContext=%s
            Choose only a strategy-level next action from allowedActions. Do not invent Minecraft facts.
            Output JSON: {"strategy":"...","action":"...","message":"..."}
            """.formatted(latestFailure == null ? "" : latestFailure, context == null ? "{}" : context.toJson());
    }
}
