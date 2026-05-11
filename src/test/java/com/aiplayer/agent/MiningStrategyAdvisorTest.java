package com.aiplayer.agent;

import com.aiplayer.llm.SurvivalPrompt;
import com.aiplayer.execution.TaskSession;
import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanStep;
import com.aiplayer.planning.PlanTarget;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningStrategyAdvisorTest {
    @Test
    void systemPromptUsesSharedSurvivalContextAndStrategySchema() {
        String prompt = MiningStrategyAdvisor.buildSystemPrompt();

        assertTrue(prompt.contains(SurvivalPrompt.sharedContext()));
        assertTrue(prompt.contains("switch_to_stair_descent"));
        assertTrue(prompt.contains("request_user_help"));
    }

    @Test
    void userPromptContainsMiningGoalAndSnapshotFactsWithoutApiKey() {
        String prompt = MiningStrategyAdvisor.buildUserPrompt(
            "task-test",
            null,
            "minecraft:gold_ingot",
            2,
            null,
            WorldSnapshot.empty("帮我挖两块金锭")
        );

        assertTrue(prompt.contains("task-test"));
        assertTrue(prompt.contains("minecraft:gold_ingot"));
        assertTrue(prompt.contains("allowedStrategyActions"));
        assertTrue(prompt.contains("miningProfile"));
        assertTrue(prompt.contains("nearbyCaves"));
        assertFalse(prompt.toLowerCase().contains("api_key"));
    }

    @Test
    void userPromptIncludesRelevantSkillMemory() {
        String prompt = MiningStrategyAdvisor.buildUserPrompt(
            "task-test",
            null,
            "minecraft:gold_ingot",
            2,
            null,
            WorldSnapshot.empty("帮我挖两块金锭"),
            List.of("skill memory: gold_ingot success via stone_pickaxe -> iron_pickaxe")
        );

        assertTrue(prompt.contains("relevantSkillMemory"));
        assertTrue(prompt.contains("gold_ingot success"));
    }

    @Test
    void userPromptIncludesCurrentMiningProfileForDeepSeekContext() {
        PlanSchema plan = new PlanSchema(
            "make_item",
            new PlanTarget("minecraft:gold_ingot", 1),
            List.of(PlanStep.gather("gold_ore", "minecraft:raw_gold", 1)),
            "each_step",
            "test"
        );
        TaskSession session = new TaskSession("make_item", "minecraft:gold_ingot", 1, plan, WorldSnapshot.empty("test"));

        String prompt = MiningStrategyAdvisor.buildUserPrompt(
            "task-test",
            null,
            "minecraft:gold_ingot",
            1,
            session,
            WorldSnapshot.empty("test")
        );

        assertTrue(prompt.contains("regular_underground_gold"));
        assertTrue(prompt.contains("badlands_surface_gold"));
        assertTrue(prompt.contains("branchMinePreferred"));
    }

    @Test
    void parsesValidStrategyAdvice() {
        MiningStrategyAdvice advice = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"附近目标不可达，切换到阶梯式下挖","action":"switch_to_stair_descent","message":"继续阶梯下探","reason":"附近 stone 不可达","needsRebuild":false,"needsUserHelp":false}
            """);

        assertTrue(advice.accepted());
        assertTrue(advice.switchToStairDescent());
        assertFalse(advice.rebuildPlan());
    }

    @Test
    void rejectsCheatingStrategyAdvice() {
        MiningStrategyAdvice advice = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"teleport and give gold","action":"teleport","message":"作弊获得金锭","reason":"fast"}
            """);

        assertFalse(advice.accepted());
    }
}
