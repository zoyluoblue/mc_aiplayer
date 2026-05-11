package com.aiplayer.agent;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationMatrixTest {
    @Test
    void defaultMatrixCoversTenRegressionCases() {
        assertEquals(12, AgentEvaluationMatrix.defaultCases().size());
        assertTrue(AgentEvaluationMatrix.defaultCases().stream().anyMatch(test -> test.id().equals("iron-pickaxe")));
        assertTrue(AgentEvaluationMatrix.defaultCases().stream().anyMatch(test -> test.id().equals("gold-ingot")));
        assertTrue(AgentEvaluationMatrix.defaultCases().stream().anyMatch(test -> test.id().equals("obsidian")));
        assertTrue(AgentEvaluationMatrix.defaultCases().stream().anyMatch(test -> test.id().equals("water-bucket")));
        assertTrue(AgentEvaluationMatrix.defaultCases().stream().anyMatch(test -> test.id().equals("recall")));
    }

    @Test
    void matrixCommandsMatchIntentParser() {
        AgentIntentParser parser = new AgentIntentParser(new RecipeResolver());
        WorldSnapshot snapshot = WorldSnapshot.empty("");

        for (AgentEvaluationCase testCase : AgentEvaluationMatrix.defaultCases()) {
            AgentIntent intent = parser.parse(testCase.command(), snapshot);
            assertEquals(testCase.expectedIntent(), intent.intentType(), testCase.id());
            if (!testCase.expectedTarget().isBlank()) {
                String actual = intent.targetItem() == null ? intent.targetResource() : intent.targetItem();
                assertEquals(testCase.expectedTarget(), actual, testCase.id());
            }
        }
    }
}
