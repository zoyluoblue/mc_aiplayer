package com.aiplayer.agent;

import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.planning.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillLibraryTest {
    @Test
    void remembersRetrievesAndSerializesSuccessfulSkills() {
        AgentSkillLibrary library = new AgentSkillLibrary();
        library.rememberSuccess(
            "make_item",
            "minecraft:gold_ingot",
            2,
            "帮我挖两块金锭",
            List.of(new ExecutionStep(PlanStep.gather("stone", "minecraft:cobblestone", 3))),
            List.of(),
            List.of("旧矿道不可达"),
            100L
        );

        List<AgentSkillRecord> found = library.findRelevant("minecraft:gold_ingot", "再给我金锭", 3, 120L);
        assertEquals(1, found.size());
        assertTrue(found.getFirst().toPromptSummary().contains("minecraft:gold_ingot"));
        assertTrue(found.getFirst().toPromptSummary().contains("gather_stone"));
        assertTrue(found.getFirst().toPromptSummary().contains("旧矿道不可达"));

        String json = library.toJson();
        AgentSkillLibrary restored = new AgentSkillLibrary();
        restored.loadJson(json);

        assertFalse(restored.allSkills().isEmpty());
        assertTrue(restored.toPromptSummary("minecraft:gold_ingot", "", 1, 140L).contains("minecraft:gold_ingot"));
    }
}
