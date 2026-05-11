package com.aiplayer.agent;

import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FailureRecoveryAdvisorTest {
    private final FailureRecoveryAdvisor advisor = new FailureRecoveryAdvisor();

    @Test
    void rejectsIllegalDeepSeekActionAndUsesLocalFallback() {
        AgentFailureAdvice advice = advisor.review(
            "minecraft:iron_pickaxe",
            null,
            WorldSnapshot.empty("test"),
            List.of("缺少石镐，无法挖 iron"),
            "{\"strategy\":\"teleport and give item\",\"action\":\"teleport\",\"message\":\"作弊完成\"}"
        );

        assertEquals(FailureAdviceSource.LOCAL_FALLBACK, advice.source());
        assertEquals(FailureCategory.TOOL_MISSING, advice.category());
        assertEquals("make_item", advice.action());
        assertFalse(advice.acceptedDeepSeek());
    }

    @Test
    void acceptsValidStrategyLevelDeepSeekAdvice() {
        AgentFailureAdvice advice = advisor.review(
            "minecraft:iron_pickaxe",
            null,
            WorldSnapshot.empty("test"),
            List.of("附近资源不足"),
            "{\"strategy\":\"continue_prospecting\",\"action\":\"gather_resource\",\"message\":\"继续探矿\"}"
        );

        assertEquals(FailureAdviceSource.DEEPSEEK, advice.source());
        assertEquals("continue_prospecting", advice.strategy());
        assertEquals("gather_resource", advice.action());
    }
}
