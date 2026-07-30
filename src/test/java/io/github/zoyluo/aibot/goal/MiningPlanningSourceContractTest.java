package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks down mining dependency and tool-routing invariants in the ordinary JVM test suite. */
class MiningPlanningSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void obsidianPlanningBindsAcquisitionAndTargetToolContracts() throws IOException {
        String planner = read("goal/GoalPlanner.java");
        int obsidianBranch = planner.indexOf("if (item == Items.OBSIDIAN)");
        int provision = planner.indexOf("obsidianToolProvision(missing)", obsidianBranch);
        int readiness = planner.indexOf("ensureObsidianExpeditionReadiness", provision);
        int requireBucket = planner.indexOf("ensureItem(Items.BUCKET", readiness);
        int acquireWater = planner.indexOf("GoalStep.acquireWater()", requireBucket);
        int acquisitionTool = planner.indexOf(
                "ensureObsidianAcquisitionTool(toolProvision", acquireWater);
        int targetTool = planner.indexOf(
                "ensureObsidianTargetToolDurability(", acquisitionTool);
        int preflight = planner.indexOf("GoalStep.obsidianPreflight(missing)", targetTool);
        int makeObsidian = planner.indexOf("GoalStep.makeObsidian(missing)", preflight);

        assertTrue(obsidianBranch >= 0 && provision > obsidianBranch && readiness > provision
                        && requireBucket > readiness && acquireWater > requireBucket
                        && acquisitionTool > acquireWater && targetTool > acquisitionTool
                        && preflight > targetTool && makeObsidian > preflight,
                "HaveItem(OBSIDIAN) must bind one durability provision, acquire visible water, "
                        + "then provision acquisition and target tools before preflight");
        assertTrue(planner.contains("OBSIDIAN_EXPEDITION_FOOD = 8"));
        assertTrue(planner.contains("OBSIDIAN_EXPEDITION_STONE_PICKS = 4"));
        assertTrue(planner.contains("bootstrapStoneLikeTarget(targetCount)"));
        assertTrue(planner.contains("bootstrapStickTarget(targetCount)"));
        assertTrue(planner.contains("ensureItem(Items.CRAFTING_TABLE, 1"));
        int readinessMethod = planner.indexOf("private boolean ensureObsidianExpeditionReadiness");
        int stonePicks = planner.indexOf(
                "ensureItem(Items.STONE_PICKAXE, OBSIDIAN_EXPEDITION_STONE_PICKS",
                readinessMethod);
        int stoneSword = planner.indexOf("ensureItem(Items.STONE_SWORD, 1", stonePicks);
        int bulkStone = planner.indexOf("ensureItem(Items.COBBLESTONE,", stoneSword);
        int serviceSticks = planner.indexOf("ensureItem(Items.STICK,", bulkStone);
        assertTrue(readinessMethod >= 0 && stonePicks > readinessMethod
                        && stoneSword > stonePicks && bulkStone > stoneSword
                        && serviceSticks > bulkStone,
                "obsidian readiness must craft STONE_SWORD before the long bulk-stone shaft, "
                        + "then replenish the untouched stone/stick service ledgers");
        assertTrue(planner.contains("diamondsToMine"));
        assertTrue(planner.contains("acquisitionIronPicks"));
        assertTrue(planner.contains("toolProvision.postReadinessSticks()"));
        assertTrue(planner.contains("ensureObsidianTargetToolDurability("));

        int netherite = planner.indexOf("if (tier >= ToolTier.NETHERITE)");
        int diamond = planner.indexOf("if (tier >= ToolTier.DIAMOND)", netherite);
        int iron = planner.indexOf("if (tier >= ToolTier.IRON)", diamond);
        assertTrue(netherite >= 0 && diamond > netherite && iron > diamond,
                "pickaxeForTier must preserve NETHERITE > DIAMOND > IRON ordering");
        assertTrue(planner.indexOf("return Items.NETHERITE_PICKAXE", netherite) < diamond);
        assertTrue(planner.indexOf("return Items.DIAMOND_PICKAXE", diamond) < iron);
    }

    @Test
    void mineOreParserFailsClosedInsteadOfChoosingCommonOres() throws IOException {
        String registry = read("brain/ToolRegistry.java");
        int parser = registry.indexOf("Set<Block> oreTargetsFrom");
        int parserEnd = registry.indexOf("private static String escape", parser);
        String body = registry.substring(parser, parserEnd);

        assertFalse(body.contains("return OreScan.COMMON_ORES"),
                "invalid mine_ore input must never become an unrelated common-ore mission");
        assertTrue(body.contains("throw new IllegalArgumentException(\"unsupported_mine_ore_target:"));
        assertTrue(body.contains("use achieve_goal with item="));
    }

    @Test
    void optionalExpeditionProvisioningSurvivesRuntimeResourceMisses() throws IOException {
        String planner = read("goal/GoalPlanner.java");
        String executor = read("goal/GoalExecutor.java");

        assertTrue(planner.contains("steps.set(i, steps.get(i).asBestEffort())"),
                "resolved optional branches must retain runtime best-effort semantics");
        assertTrue(planner.contains("if (bestEffortDepth > 0)"),
                "optional work must be tagged before addStep can merge it with required work");
        assertTrue(executor.contains("plan.current.bestEffort()"),
                "executor must skip failed optional provisioning steps");
    }

    @Test
    void longRareExpeditionReadinessPrecedesSealedDescentHandoff() throws IOException {
        String planner = read("goal/GoalPlanner.java");
        String executor = read("goal/GoalExecutor.java");
        int mining = planner.indexOf("private boolean ensureMineOre");
        int hardFood = planner.indexOf(
                "ensureMiningFoodReserveTo(", mining);
        int targetTool = planner.indexOf("ensurePickaxeTier(tier", hardFood);
        int hardTorches = planner.indexOf("ensureTorchesTo(requiredTorches", targetTool);
        int finalSealedKit = planner.indexOf(
                "ensureRareDescentKit(expanded, count", hardTorches);
        int descend = planner.indexOf("addStep(GoalStep.descendToY(mineY))", finalSealedKit);

        assertTrue(mining >= 0 && hardFood > mining && targetTool > hardFood
                        && hardTorches > targetTool && finalSealedKit > hardTorches
                        && descend > finalSealedKit,
                "food, target tools and torches must finish before the sealed KIT/descent");
        assertTrue(planner.contains("count == 64")
                        && planner.contains("? ensureRareDescentKit(expanded, count")
                        && planner.contains(": ensureDirectRareDescentKit(missionBudget"),
                "only target64 may enter the mission-depot KIT; smaller rare targets retain direct craft");
        assertTrue(planner.contains("steps.size() == rareBootstrapStart")
                        && planner.contains("MiningServiceTask.rareDescentKitReady(bot)")
                        && planner.contains("MiningServiceTask.ownedMissionDepot(bot, missionId)"),
                "live KIT omission must bind no-new-bootstrap work, exact readiness and mission ownership");
        assertTrue(planner.contains("budget.spareToolSticks(), craftSticks"),
                "final craft inputs must leave the complete 228-stick reserve");
        assertTrue(planner.contains("budget.emergencyBlocks(), craftStone"),
                "final craft inputs must leave the complete 60-stone reserve");
        int rareKit = planner.indexOf("private boolean ensureRareDescentKit");
        int rareStone = planner.indexOf("ensureItem(Items.COBBLESTONE, requiredStone", rareKit);
        int rareSticks = planner.indexOf("ensureItem(Items.STICK, requiredSticks", rareKit);
        int rareService = planner.indexOf("steps.add(GoalStep.rareDescentKitService", rareKit);
        assertTrue(rareKit >= 0 && rareStone > rareKit && rareSticks > rareStone
                        && rareService > rareSticks,
                "target64 hand-off must finish stone acquisition before sealing sticks and KIT");
        String rareBody = planner.substring(rareKit,
                planner.indexOf("private boolean ensureDirectRareDescentKit", rareKit));
        assertTrue(rareBody.contains("consumeItem(Items.CHEST, 1)")
                        && rareBody.contains(
                        "consumeItem(Items.COBBLESTONE, saturatedAdd(craftStone, 2))")
                        && rareBody.contains("consumeItem(Items.STICK, craftSticks)")
                        && rareBody.contains(
                        "counts.merge(Items.STONE_PICKAXE, freshPickaxes, Integer::sum)"),
                "KIT must mirror chest/seal/handle consumption and five fresh picks symbolically");
        int directKit = planner.indexOf("private boolean ensureDirectRareDescentKit");
        int directCraft = planner.indexOf(
                "appendFreshStonePickaxeCraft(freshPickaxes", directKit);
        assertTrue(directKit > rareKit && directCraft > directKit,
                "targets 8..63 must retain the direct incremental five-pick craft");
        assertTrue(planner.contains("longRareExpedition || ordinaryChannelMission"),
                "ordinary mine-layer replans must retain channel-tool service maintenance");
        assertTrue(planner.contains("budget.ordinaryChannelRepairSticks()"),
                "ordinary coal/iron must own an explicit finite repair horizon");
        assertTrue(planner.contains("aggregatedIronTarget"),
                "long rare target picks and six spare ingots must share one iron acquisition");
        assertTrue(planner.contains("cumulative, maintainTunnelingTools"),
                "from-zero service checkpoints must retain the channel-tool contract");
        assertTrue(planner.contains("GoalStep.miningHandoffService("),
                "final ordinary batches must publish a capacity-only parent handoff");
        assertFalse(executor.contains("|| plan.current.kind() == GoalStep.Kind.HUNT"),
                "hard mining readiness must not be skipped merely because its task kind is HUNT");
        assertFalse(executor.contains("|| plan.current.kind() == GoalStep.Kind.COOK_FOOD"),
                "hard mining readiness must not be skipped merely because its task kind is COOK_FOOD");
        assertTrue(executor.contains("new HuntTask(step.count(), !step.bestEffort())"),
                "hard readiness must require the full hunted-food quota");
        assertTrue(executor.contains("new SmeltTask(step.count(), !step.bestEffort())"),
                "hard readiness must require the full cooked-food quota");

        String danger = read("task/DangerWatcher.java");
        assertTrue(danger.contains("active.filter(CraftTask.class::isInstance)"),
                "CraftTask must not spend sealed inputs through held-tool generic resupply");

        String hunt = read("task/HuntTask.java");
        String smelt = read("task/SmeltTask.java");
        String service = read("task/MiningServiceTask.java");
        assertTrue(hunt.contains("collected > 0 && !requireFullQuota"));
        assertTrue(smelt.contains("collected >= targetCount || (collected > 0 && !requireCookedQuota)"));
        assertTrue(service.contains("values.put(\"channel_tools\""),
                "restart checkpoints must preserve channel-tool maintenance");
        assertTrue(service.contains("totalTunnelingPicks + deficit"),
                "near-broken picks must not satisfy the service craft target");
        assertFalse(service.contains("ResupplyTask.food()"),
                "underground mining service must never start a surface food acquisition task");
        assertFalse(service.contains("FOOD_SERVICE_LEVEL"),
                "full hunger cannot replace a carried safe-food reserve");
        assertTrue(service.contains("MiningFoodReserve.MIN_DEEP_MINE_UNITS"));
        assertTrue(planner.contains("deep_mining_food_reserve_depleted"));
        assertTrue(planner.contains("underground_surface_resource_unavailable"));
    }

    @Test
    void stoneBootstrapMustPhysicallyReturnBeforePublishingCompletion() throws IOException {
        String dig = read("task/DigDownTask.java");

        assertTrue(dig.contains("descentTrail"));
        assertTrue(dig.contains("dig_down_return_trail"),
                "stone bootstrap must reverse its factual staircase one adjacent cell at a time");
        assertTrue(dig.contains("fail(\"dig_down_return_failed"),
                "an exhausted return budget must fail instead of publishing a false surface success");
        assertFalse(dig.contains("|| elapsed - returnStartTick > RETURN_LIMIT)"),
                "return timeout must not share the successful completion branch");
        assertFalse(dig.contains("\"surfaced\", String.valueOf"),
                "DigDown may only emit return_done after actually reaching the surface");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
