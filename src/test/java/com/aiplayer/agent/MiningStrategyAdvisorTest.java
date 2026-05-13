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
        assertTrue(prompt.contains("switch_layer"));
        assertTrue(prompt.contains("ask_player_help"));
        assertTrue(prompt.contains("return_to_safe_point"));
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
        assertTrue(prompt.contains("miningReviewInputSchema"));
        assertTrue(prompt.contains("prospectingRules"));
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
            {"strategy":"当前层没有进展，按本地高度策略换层","action":"switch_layer","message":"请求本地换层","reason":"当前层没有目标产出","needsRebuild":true,"needsUserHelp":false}
            """);

        assertTrue(advice.accepted());
        assertTrue(advice.switchToStairDescent());
        assertTrue(advice.rebuildPlan());
    }

    @Test
    void rejectsCheatingStrategyAdvice() {
        MiningStrategyAdvice advice = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"teleport and give gold","action":"teleport","message":"作弊获得金锭","reason":"fast"}
            """);

        assertFalse(advice.accepted());
    }

    @Test
    void rejectsStrategyThatContainsCoordinatesOrBlockFacts() {
        MiningStrategyAdvice advice = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"去指定坐标挖矿","action":"rescan","message":"去这里","reason":"猜测有矿","x":10,"y":-20,"z":4}
            """);

        assertFalse(advice.accepted());
    }

    @Test
    void rejectsNestedCoordinatesAndCommands() {
        MiningStrategyAdvice nested = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"重扫","action":"rescan","message":"重扫","reason":"ok","target":{"x":1,"y":-20,"z":3}}
            """);
        MiningStrategyAdvice array = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"重扫","action":"rescan","message":"重扫","reason":"ok","steps":[{"command":"/setblock 0 0 0 gold_block"}]}
            """);
        MiningStrategyAdvice uppercaseCoordinates = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"重扫","action":"rescan","message":"重扫","reason":"ok","target":{"X":1,"Y":-20,"Z":3}}
            """);
        MiningStrategyAdvice uppercaseCommand = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"重扫","action":"rescan","message":"重扫","reason":"ok","Command":"/setblock 0 0 0 gold_block"}
            """);

        assertFalse(nested.accepted());
        assertFalse(array.accepted());
        assertFalse(uppercaseCoordinates.accepted());
        assertFalse(uppercaseCommand.accepted());
    }

    @Test
    void rejectsForbiddenCommandTextInReason() {
        MiningStrategyAdvice advice = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"重扫","action":"rescan","message":"重扫","reason":"可以 /tp 到矿点"}
            """);

        assertFalse(advice.accepted());
    }

    @Test
    void returnToSafePointIsNotTreatedAsSwitchLayer() {
        MiningStrategyAdvice advice = MiningStrategyAdvisor.parseAdvice("""
            {"strategy":"回退最近安全点","action":"return_to_safe_point","message":"回退","reason":"路线阻断"}
            """);

        assertTrue(advice.accepted());
        assertTrue(advice.returnToSafePoint());
        assertFalse(advice.switchToStairDescent());
    }
}
