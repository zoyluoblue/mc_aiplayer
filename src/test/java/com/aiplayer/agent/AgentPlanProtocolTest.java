package com.aiplayer.agent;

import com.aiplayer.planning.PlanSchema;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanProtocolTest {
    @Test
    void convertsRecipePlanToAgentPlanProtocol() {
        RecipePlan recipePlan = new RecipeResolver().resolve(null, WorldSnapshot.empty(""), "minecraft:iron_pickaxe", 1);
        PlanSchema schema = PlanSchema.fromRecipePlan("make_item", recipePlan);
        AgentPlan agentPlan = AgentPlan.fromPlanSchema(schema);
        String json = AgentPlanProtocol.toJson(agentPlan);

        assertTrue(json.contains("minecraft:iron_pickaxe"));
        assertTrue(json.contains("stepId"));
        List<String> errors = AgentPlanProtocol.validateProtocol(agentPlan, ActionManifest.survivalDefaults());
        assertTrue(errors.isEmpty(), errors.toString());
    }
}
