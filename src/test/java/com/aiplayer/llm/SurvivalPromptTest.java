package com.aiplayer.llm;

import com.aiplayer.agent.FailureRecoveryAdvisor;
import com.aiplayer.planning.PlanningPromptBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalPromptTest {
    @Test
    void sharedContextDefinesMinecraftSurvivalBoundary() {
        String context = SurvivalPrompt.sharedContext();

        assertTrue(context.contains("Minecraft Java 生存模式"));
        assertTrue(context.contains("我现在是 Minecraft 生存玩家"));
        assertTrue(context.contains("你只需要根据我提供的材料"));
    }

    @Test
    void mainPromptsIncludeSharedSurvivalContext() {
        assertTrue(PromptBuilder.buildSystemPrompt().contains(SurvivalPrompt.sharedContext()));
        assertTrue(PlanningPromptBuilder.buildSystemPrompt().contains(SurvivalPrompt.sharedContext()));
        assertTrue(new FailureRecoveryAdvisor()
            .review("minecraft:iron_pickaxe", null, null, java.util.List.of("附近没有铁矿"), null)
            .deepSeekPrompt()
            .contains(SurvivalPrompt.sharedContext()));
    }
}
