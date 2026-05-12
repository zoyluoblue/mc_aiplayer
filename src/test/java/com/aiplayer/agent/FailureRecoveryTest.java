package com.aiplayer.agent;

import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureRecoveryTest {
    @Test
    void classifiesCommonFailuresAndSuggestsActionableFallbacks() {
        FailureRecoveryAdvisor advisor = new FailureRecoveryAdvisor();

        assertEquals(FailureCategory.MATERIAL_MISSING, advisor.classify("材料不足，无法合成"));
        assertEquals(FailureCategory.PATH_STUCK, advisor.classify("前往 step 目标时卡住"));
        assertEquals(FailureCategory.RESOURCE_NOT_FOUND, advisor.classify("附近没有找到铁矿"));
        assertEquals(FailureCategory.MINING_ROUTE_FAILED, advisor.classify("分支矿道四个方向都被危险方块或不可破坏方块阻挡，无法继续寻找 铁矿"));
        assertEquals(FailureCategory.MINING_ROUTE_FAILED, advisor.classify("候选=12084，可达=0，拒绝原因={no_air_neighbor=12084}"));

        AgentFailureAdvice advice = advisor.review(
            "minecraft:iron_pickaxe",
            null,
            WorldSnapshot.empty(""),
            List.of("前往 step 目标时卡住"),
            null
        );

        assertEquals(FailureAdviceSource.LOCAL_FALLBACK, advice.source());
        assertTrue(advice.userMessage().contains("结束任务") || advice.userMessage().contains("回到玩家身边"));
    }
}
