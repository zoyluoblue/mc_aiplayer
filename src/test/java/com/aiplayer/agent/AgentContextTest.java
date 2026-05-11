package com.aiplayer.agent;

import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextTest {
    @Test
    void contextContainsFactsActionsAndNoApiKey() {
        AgentContext context = AgentContext.from(
            WorldSnapshot.empty("帮我做个铁镐"),
            null,
            null,
            List.of("附近没有铁矿"),
            List.of("step-1 failed"),
            ActionManifest.survivalDefaults(),
            List.of("skill: make iron pickaxe")
        );

        String json = context.toJson();
        assertTrue(json.contains("availableItems"));
        assertTrue(json.contains("make_item"));
        assertTrue(json.contains("附近没有铁矿"));
        assertTrue(json.contains("make iron pickaxe"));
        assertFalse(json.toLowerCase().contains("api_key"));
    }
}
