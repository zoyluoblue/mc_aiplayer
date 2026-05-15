package com.aiplayer.execution;

import com.aiplayer.recipe.MiningGoalResolver;
import com.aiplayer.recipe.MiningResource;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningRegressionMatrixTest {
    private final RecipeResolver recipeResolver = new RecipeResolver();
    private final WorldSnapshot emptySnapshot = WorldSnapshot.empty("");

    @Test
    void cobblestoneUsesBaseResourceAndWoodenPickaxeChain() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:cobblestone", 3);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertEquals("stone", SurvivalRecipeBook.baseSource("minecraft:cobblestone").orElseThrow());
        assertEquals("minecraft:wooden_pickaxe", SurvivalRecipeBook.requiredToolForBaseResource("minecraft:cobblestone"));
        assertHasGather(plan, "minecraft:cobblestone", "stone");
        assertHasCraft(plan, "minecraft:wooden_pickaxe", "crafting_table");
        assertTrue(finalBackpackTarget(plan).contains("minecraft:cobblestone x3"));
    }

    @Test
    void commonMiningTargetsResolveToCompleteRegressionFacts() {
        List<MiningCase> cases = List.of(
            new MiningCase("煤", "coal", "minecraft:coal", "minecraft:coal", "coal_ore", "minecraft:wooden_pickaxe", "minecraft:overworld", false, 80, 80, "TUNNEL"),
            new MiningCase("铁原矿", "raw_iron", "minecraft:raw_iron", "minecraft:raw_iron", "iron_ore", "minecraft:stone_pickaxe", "minecraft:overworld", false, 80, 80, "TUNNEL"),
            new MiningCase("铁锭", "iron_ingot", "minecraft:iron_ingot", "minecraft:raw_iron", "iron_ore", "minecraft:stone_pickaxe", "minecraft:overworld", true, 80, 80, "TUNNEL"),
            new MiningCase("金原矿", "raw_gold", "minecraft:raw_gold", "minecraft:raw_gold", "gold_ore", "minecraft:iron_pickaxe", "minecraft:overworld", false, 80, -16, "DESCEND"),
            new MiningCase("金锭", "gold_ingot", "minecraft:gold_ingot", "minecraft:raw_gold", "gold_ore", "minecraft:iron_pickaxe", "minecraft:overworld", true, 80, -16, "DESCEND"),
            new MiningCase("钻石", "diamond_ore", "minecraft:diamond", "minecraft:diamond", "diamond_ore", "minecraft:iron_pickaxe", "minecraft:overworld", false, 80, -24, "DESCEND"),
            new MiningCase("红石", "redstone_ore", "minecraft:redstone", "minecraft:redstone", "redstone_ore", "minecraft:iron_pickaxe", "minecraft:overworld", false, 80, -24, "DESCEND"),
            new MiningCase("青金石", "lapis_ore", "minecraft:lapis_lazuli", "minecraft:lapis_lazuli", "lapis_ore", "minecraft:stone_pickaxe", "minecraft:overworld", false, 80, 0, "DESCEND"),
            new MiningCase("绿宝石", "emerald_ore", "minecraft:emerald", "minecraft:emerald", "emerald_ore", "minecraft:iron_pickaxe", "minecraft:overworld", false, 120, 120, "TUNNEL"),
            new MiningCase("黑曜石", "obsidian", "minecraft:obsidian", "minecraft:obsidian", "block:minecraft:obsidian", "minecraft:diamond_pickaxe", "any", false, 64, 64, "TUNNEL")
        );

        for (MiningCase testCase : cases) {
            MiningGoalResolver.Goal goal = MiningGoalResolver.resolve(testCase.input()).orElseThrow();
            assertEquals(testCase.finalItem(), goal.finalItem(), testCase.name());
            assertEquals(testCase.directMiningItem(), goal.directMiningItem(), testCase.name());
            assertEquals(testCase.source(), goal.source(), testCase.name());
            assertEquals(testCase.requiredTool(), goal.requiredTool(), testCase.name());
            assertEquals(testCase.dimension(), goal.dimension(), testCase.name());
            assertEquals(testCase.needsSmelting(), goal.needsSmelting(), testCase.name());

            MiningHeightPolicy.Decision height = MiningHeightPolicy.decide(testCase.currentY(), goal.profile());
            assertEquals(testCase.targetY(), height.targetY(), testCase.name() + " height");

            DirectMiningRoute route = routeFor(testCase, height);
            assertEquals(testCase.routeStage(), route.routeStage(), testCase.name() + " route stage");
            assertTrue(route.statusText().contains("当前矿点="), testCase.name());
            assertTrue(route.statusText().contains("预计步数="), testCase.name());

            MiningToolGate.Result gate = MiningToolGate.evaluate(goal.requiredTool(), goal.requiredTool(), Integer.MAX_VALUE, 12);
            assertTrue(gate.ready(), testCase.name() + " tool gate");
            assertEquals("mine_resource", gate.nextMilestone(), testCase.name());
        }
    }

    @Test
    void deepMiningProfilesHaveExpectedToolAndHeightBands() {
        MiningResource.Profile gold = MiningResource.findByMineTarget("gold").orElseThrow();
        MiningResource.Profile diamond = MiningResource.findByMineTarget("diamond").orElseThrow();
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();

        assertEquals("minecraft:iron_pickaxe", gold.requiredTool());
        assertEquals(-16, MiningHeightPolicy.decide(80, gold).targetY());
        assertEquals("minecraft:iron_pickaxe", diamond.requiredTool());
        assertEquals(-24, MiningHeightPolicy.decide(80, diamond).targetY());
        assertEquals("minecraft:diamond_pickaxe", ancientDebris.requiredTool());
        assertEquals(15, MiningHeightPolicy.decide(80, ancientDebris).targetY());
    }

    @Test
    void routeSimulatorRequiresTwoHighSpaceAndSupport() {
        BlockPos current = new BlockPos(0, 65, 0);
        BlockPos stand = new BlockPos(1, 64, 0);

        MiningMovementSimulator.Result feet = MiningMovementSimulator.simulate(new MiningMovementSimulator.Input(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty()
        ));

        assertEquals(MiningMovementSimulator.Action.PLACE_SUPPORT, feet.action());
    }

    @Test
    void failedCandidatesCoolDownWithoutPermanentBlacklist() {
        MiningCandidateCooldown cooldown = new MiningCandidateCooldown();
        BlockPos pos = new BlockPos(12, -16, 3);

        cooldown.reject(pos, "movement_stuck", 0);

        assertTrue(cooldown.activePositions(100).contains(pos));
        assertTrue(cooldown.activePositions(20 * 60 + 1).isEmpty());
    }

    @Test
    void ingotTargetsKeepMinedDropAndFinalBackpackItemSeparate() {
        RecipePlan iron = recipeResolver.resolve(null, emptySnapshot, "minecraft:iron_ingot", 1);
        RecipePlan gold = recipeResolver.resolve(null, emptySnapshot, "minecraft:gold_ingot", 1);

        assertTrue(iron.isSuccess(), iron.getFailureReason());
        assertTrue(gold.isSuccess(), gold.getFailureReason());
        assertHasGather(iron, "minecraft:raw_iron", "iron_ore");
        assertHasCraft(iron, "minecraft:iron_ingot", "furnace");
        assertHasGather(gold, "minecraft:raw_gold", "gold_ore");
        assertHasCraft(gold, "minecraft:gold_ingot", "furnace");
        assertFalse(finalBackpackTarget(iron).contains("minecraft:raw_iron x1"));
        assertFalse(finalBackpackTarget(gold).contains("minecraft:raw_gold x1"));
        assertTrue(finalBackpackTarget(iron).contains("minecraft:iron_ingot x1"));
        assertTrue(finalBackpackTarget(gold).contains("minecraft:gold_ingot x1"));
    }

    @Test
    void endToEndMiningMatrixDocumentsCommandsStagesAndOutcomes() {
        List<EndToEndMiningCase> cases = List.of(
            new EndToEndMiningCase("圆石", "/ai say 帮我挖三块圆石", "empty", "木镐 -> 露天石头或阶梯下挖 -> 圆石", "minecraft:cobblestone x3", "缺木材、缺镐、背包容量或无可挖石头"),
            new EndToEndMiningCase("煤", "/ai mining start coal 3", "wooden_pickaxe or craft chain", "木镐 -> 煤矿高度 -> 采煤", "minecraft:coal x3", "缺木镐、维度错误、路线阻断或背包容量"),
            new EndToEndMiningCase("铁锭", "/ai say 帮我烧两块铁锭", "empty", "木材 -> 木镐 -> 圆石 -> 石镐 -> 铁原矿 -> 熔炉/燃料 -> 铁锭", "minecraft:iron_ingot x2", "缺石镐、缺燃料、路线阻断或背包容量"),
            new EndToEndMiningCase("金锭", "/ai say 帮我挖两块金锭", "empty", "木材 -> 石镐 -> 铁原矿 -> 铁锭 -> 铁镐 -> 金原矿 -> 熔炼金锭", "minecraft:gold_ingot x2", "缺铁镐、金矿高度错误、路线阻断或熔炼前置不足"),
            new EndToEndMiningCase("钻石", "/ai mining start diamond 1", "iron_pickaxe or craft chain", "铁镐 -> 深层高度 -> 探矿直达或鱼骨搜索 -> 钻石", "minecraft:diamond x1", "缺铁镐、深层路线阻断或目标消失"),
            new EndToEndMiningCase("红石", "/ai mining start redstone 4", "iron_pickaxe or craft chain", "铁镐 -> 深层高度 -> 红石矿 -> 红石", "minecraft:redstone x4", "缺铁镐、深层路线阻断或背包容量"),
            new EndToEndMiningCase("青金石", "/ai mining start lapis 4", "stone_pickaxe or craft chain", "石镐 -> 中深层高度 -> 青金石矿 -> 青金石", "minecraft:lapis_lazuli x4", "缺石镐、目标高度错误或路线阻断"),
            new EndToEndMiningCase("绿宝石", "/ai mining start emerald 1", "iron_pickaxe or craft chain", "铁镐 -> 山地/高层策略 -> 绿宝石矿 -> 绿宝石", "minecraft:emerald x1", "缺铁镐、维度/高度不合适或附近无山地矿点"),
            new EndToEndMiningCase("黑曜石", "/ai mining start obsidian 1", "diamond_pickaxe", "钻石镐 -> 已有黑曜石或水岩浆区域 -> 黑曜石", "minecraft:obsidian x1", "缺钻石镐、目标方块不可达或背包容量")
        );

        assertEquals(9, cases.size());
        for (EndToEndMiningCase testCase : cases) {
            assertTrue(testCase.command().startsWith("/ai "), testCase.name());
            assertFalse(testCase.startBackpack().isBlank(), testCase.name());
            assertTrue(testCase.keyStages().contains("->"), testCase.name());
            assertTrue(testCase.finalBackpack().startsWith("minecraft:"), testCase.name());
            assertFalse(testCase.failureSuggestion().isBlank(), testCase.name());
        }
    }

    @Test
    void recentMiningFailureSamplesRemainReproducible() {
        List<MiningFailureSample> samples = recentFailureSamples();

        assertEquals(5, samples.size());
        for (MiningFailureSample sample : samples) {
            assertFalse(sample.id().isBlank());
            assertFalse(sample.taskId().isBlank());
            assertFalse(sample.target().isBlank());
            assertFalse(sample.step().isBlank());
            assertFalse(sample.routeStage().isBlank());
            assertFalse(sample.rejectionReason().isBlank());
            assertFalse(sample.lastInteractionTarget().isBlank());
            assertFalse(sample.lastBreakResult().isBlank());
            assertFalse(sample.backpackState().isBlank());
            assertFalse(sample.milestoneState().isBlank());
            assertFalse(sample.routeSummary().isBlank());
            assertFalse(sample.failureSignal().isBlank());
            assertTrue(sample.followUpPhase().startsWith("15."), sample.id());
            assertFalse(sample.expectedAssertion().isBlank());
        }

        MiningFailureSample embeddedIron = failureSample(samples, "R-2026-05-14-e2ad0a9a");
        DirectMiningRoute embeddedRoute = DirectMiningRoute.create(
            embeddedIron.aiPos(),
            embeddedIron.orePos(),
            embeddedIron.routeTarget(),
            Direction.SOUTH
        );
        assertTrue(embeddedRoute.arrived());
        assertEquals("EXPOSE_OR_MINE", embeddedRoute.routeStage());
        assertEquals("EXPOSE_OR_MINE", embeddedIron.routeStage());
        assertTrue(embeddedIron.failureSignal().contains("target_changed:minecraft:iron_ore"));
        assertTrue(embeddedIron.backpackState().contains("stone_pickaxe"));

        MiningFailureSample farTunnel = failureSample(samples, "R-2026-05-13-ae778bec");
        DirectMiningRoute tunnelRoute = DirectMiningRoute.create(
            farTunnel.aiPos(),
            farTunnel.orePos(),
            farTunnel.routeTarget(),
            Direction.WEST
        );
        assertEquals("TUNNEL", tunnelRoute.routeStage());
        assertTrue(tunnelRoute.horizontalDistance() >= 80);
        assertEquals(0, tunnelRoute.verticalDelta());
        assertTrue(farTunnel.failureSignal().contains("blocked_line_of_sight"));

        MiningFailureSample occlusion = failureSample(samples, "R-2026-05-14-c852ddd1");
        assertTrue(occlusion.recoverable());
        assertEquals(new BlockPos(-774, 68, -2), occlusion.targetBlock());
        assertEquals(new BlockPos(-775, 68, -2), occlusion.blocker());

        assertTrue(failureSample(samples, "S-capacity-drop-uncollected")
            .failureSignal()
            .contains("drop_uncollected"));
        assertTrue(failureSample(samples, "R-2026-05-14-6727c2a0")
            .milestoneState()
            .contains("activeTask=make_item"));
    }

    private static DirectMiningRoute routeFor(MiningCase testCase, MiningHeightPolicy.Decision height) {
        BlockPos current = new BlockPos(0, testCase.currentY(), 0);
        BlockPos ore = new BlockPos(5, height.targetY(), 0);
        BlockPos routeTarget = new BlockPos(4, height.targetY(), 0);
        return DirectMiningRoute.create(current, ore, routeTarget, Direction.EAST);
    }

    private static List<MiningFailureSample> recentFailureSamples() {
        return List.of(
            new MiningFailureSample(
                "R-2026-05-14-e2ad0a9a",
                "task-e2ad0a9a",
                "minecraft:gold_ingot x2",
                "milestone 7/13 gather minecraft:raw_iron x1",
                new BlockPos(-780, 59, -1),
                new BlockPos(-780, 58, 0),
                new BlockPos(-780, 59, 0),
                new BlockPos(-780, 58, 0),
                null,
                "EXPOSE_OR_MINE",
                "target_changed:minecraft:iron_ore + no_air_neighbor + no_path",
                "minecraft:iron_ore at -780,58,0",
                "prospect_timeout after route kept rebuilding around exposed adjacent ore",
                "cobblestone, dirt, grass_block, iron_ingot=2, spruce_planks, stone_pickaxe, wooden_pickaxe",
                "gold_ingot milestone 7/13 current=gather minecraft:raw_iron x1",
                "current=-780,59,-1, ore=-780,58,0, exposure=-780,59,0",
                "target_changed:minecraft:iron_ore, no_air_neighbor, no_path, prospect_timeout",
                "15.5",
                false,
                "adjacent exposure should route to EXPOSE_OR_MINE"
            ),
            new MiningFailureSample(
                "R-2026-05-14-c852ddd1",
                "task-c852ddd1",
                "minecraft:iron_ingot x2",
                "recoverable stone and iron line-of-sight retries",
                null,
                null,
                null,
                new BlockPos(-774, 68, -2),
                new BlockPos(-775, 68, -2),
                "RECOVERABLE_BREAK_RETRY",
                "blocked_line_of_sight",
                "minecraft:iron_ore at -774,68,-2 and -773,67,-3",
                "Survival breakBlock rejected because blocker was between AI and ore",
                "not_logged_for_recoverable_sample",
                "iron_ingot x2 route eventually succeeded after retries",
                "targetBlock=-774,68,-2, blocker=-775,68,-2",
                "blocked_line_of_sight with side or head blocker",
                "15.2",
                true,
                "clear feet/head passage before retrying block break"
            ),
            new MiningFailureSample(
                "R-2026-05-13-ae778bec",
                "task-ae778bec",
                "minecraft:iron_ingot x2",
                "long TUNNEL route to iron ore",
                new BlockPos(14, 60, -30),
                new BlockPos(-65, 59, -24),
                new BlockPos(-65, 60, -24),
                new BlockPos(-65, 59, -24),
                new BlockPos(13, 61, -30),
                "TUNNEL",
                "blocked_line_of_sight + occlusion_clear_failed + no_air_neighbor + no_path",
                "minecraft:iron_ore route target -65,60,-24",
                "blocked_line_of_sight at blocker 13,61,-30; route terminated as blocked",
                "not_logged_in_terminal_summary",
                "iron_ingot x2 gather raw_iron while following long tunnel",
                "start=14,65,-28, current=14,60,-30, target=-65,59,-24, horizontalDistance=85",
                "blocked_line_of_sight, occlusion_clear_failed, no_air_neighbor, no_path",
                "15.4",
                false,
                "far tunnel should be classified for route rebind or fishbone fallback"
            ),
            new MiningFailureSample(
                "R-2026-05-14-6727c2a0",
                "task-6727c2a0",
                "minecraft:raw_iron x2",
                "restored make_item after nbt_load",
                new BlockPos(-782, 75, 16),
                new BlockPos(-785, 71, 26),
                new BlockPos(-785, 72, 26),
                null,
                null,
                "TASK_RESTORE",
                "repeated_nbt_load_restore",
                "active make_item minecraft:raw_iron x2",
                "no break attempted in restore sample",
                "must preserve restored backpack snapshot before continuing",
                "activeTask=make_item, item=minecraft:raw_iron, quantity=2, queued=0",
                "restored startPos=-782,75,16, later ore hint=-785,71,26",
                "restored task state after nbt_load",
                "15.9",
                true,
                "keep target and milestone state after reload"
            ),
            new MiningFailureSample(
                "S-capacity-drop-uncollected",
                "synthetic-capacity",
                "mining drop pickup",
                "block broken but drop cannot enter backpack",
                null,
                null,
                null,
                null,
                null,
                "PICKUP_AFTER_BREAK",
                "inventory_full_for_drop",
                "dropped mined item",
                "block broken, drop_uncollected:3",
                "full backpack with no free slot for mined drop",
                "not_applicable_capacity_sample",
                "capacity fixture from MiningDropProgressTest",
                "drop_uncollected:3",
                "15.9",
                true,
                "classify as inventory capacity before route failure"
            )
        );
    }

    private static MiningFailureSample failureSample(List<MiningFailureSample> samples, String id) {
        return samples.stream()
            .filter(sample -> sample.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static String finalBackpackTarget(RecipePlan plan) {
        return plan.getTarget().toString();
    }

    private static void assertHasGather(RecipePlan plan, String item, String source) {
        assertTrue(
            plan.getRecipeChain().stream().anyMatch(node ->
                "gather".equals(node.getType())
                    && item.equals(node.getOutput().getItem())
                    && source.equals(node.getSource())),
            "Expected gather node for " + item + " from " + source + " in\n" + plan.toUserText()
        );
    }

    private static void assertHasCraft(RecipePlan plan, String item, String station) {
        assertTrue(
            plan.getRecipeChain().stream().anyMatch(node ->
                "craft".equals(node.getType())
                    && item.equals(node.getOutput().getItem())
                    && station.equals(node.getStation())),
            "Expected craft node for " + item + " at " + station + " in\n" + plan.toUserText()
        );
    }

    private record MiningCase(
        String name,
        String input,
        String finalItem,
        String directMiningItem,
        String source,
        String requiredTool,
        String dimension,
        boolean needsSmelting,
        int currentY,
        int targetY,
        String routeStage
    ) {
    }

    private record EndToEndMiningCase(
        String name,
        String command,
        String startBackpack,
        String keyStages,
        String finalBackpack,
        String failureSuggestion
    ) {
    }

    private record MiningFailureSample(
        String id,
        String taskId,
        String target,
        String step,
        BlockPos aiPos,
        BlockPos orePos,
        BlockPos routeTarget,
        BlockPos targetBlock,
        BlockPos blocker,
        String routeStage,
        String rejectionReason,
        String lastInteractionTarget,
        String lastBreakResult,
        String backpackState,
        String milestoneState,
        String routeSummary,
        String failureSignal,
        String followUpPhase,
        boolean recoverable,
        String expectedAssertion
    ) {
    }
}
