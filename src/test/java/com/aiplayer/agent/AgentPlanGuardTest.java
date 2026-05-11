package com.aiplayer.agent;

import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanStep;
import com.aiplayer.planning.PlanTarget;
import com.aiplayer.planning.PlanValidator;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPlanGuardTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void reportsStructuredViolationForForbiddenPlan() {
        RecipeResolver resolver = new RecipeResolver();
        AgentPlanGuard guard = new AgentPlanGuard(new PlanValidator(resolver));
        PlanStep forbidden = new PlanStep();
        forbidden.setStep("teleport");
        forbidden.setItem("minecraft:iron_pickaxe");
        PlanSchema bad = new PlanSchema(
            "make_item",
            new PlanTarget("minecraft:iron_pickaxe", 1),
            List.of(forbidden),
            "each_step",
            "bad"
        );

        AgentPlanGuard.GuardResult result = guard.validate(bad, null, WorldSnapshot.empty(""), null);

        assertFalse(result.valid());
        assertTrue(result.violations().stream().anyMatch(violation -> violation.repairHint().contains("生存动作")));
    }

    @Test
    void acceptsLocalRecipeFallbackPlan() {
        RecipeResolver resolver = new RecipeResolver();
        RecipePlan recipePlan = resolver.resolve(null, WorldSnapshot.empty(""), "minecraft:iron_pickaxe", 1);
        PlanSchema plan = PlanSchema.fromRecipePlan("make_item", recipePlan);
        AgentPlanGuard guard = new AgentPlanGuard(new PlanValidator(resolver));

        AgentPlanGuard.GuardResult result = guard.validate(plan, null, WorldSnapshot.empty(""), recipePlan);

        assertTrue(result.valid(), result.violations().toString());
    }
}
