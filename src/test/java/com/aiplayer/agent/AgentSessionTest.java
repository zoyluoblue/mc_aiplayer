package com.aiplayer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionTest {
    @Test
    void storesTaskMemoryFailuresAndPreferences() {
        AgentSession session = new AgentSession();
        session.startTask("task-1", "make_item", "minecraft:iron_pickaxe");
        session.recordCommand("帮我做个铁镐");
        session.recordFailure("刚刚那棵树不可达");
        session.rejectTarget("tree:10,64,10");
        session.rememberChest("0,64,2");
        session.setPreference("avoid_home_damage", "true");
        session.rememberMiningFact("mineEntrance", "10,63,10");
        session.rememberMiningFact("poorBranch:east@Y-40", "cooldown");

        assertEquals("task-1", session.getCurrentTaskId());
        assertTrue(session.hasRejectedTarget("tree:10,64,10"));
        assertEquals("true", session.preference("avoid_home_damage"));
        assertEquals("10,63,10", session.miningFact("mineEntrance"));
        assertTrue(session.getMiningMemory().containsKey("poorBranch:east@Y-40"));
        assertTrue(session.summarizeForPrompt().contains("minecraft:iron_pickaxe"));
        assertTrue(session.summarizeForPrompt().contains("miningMemory"));
    }
}
