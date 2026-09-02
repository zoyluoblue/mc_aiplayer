package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.mining.MiningMissionBudget;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.task.EmergencyShelterTask;
import io.github.zoyluo.aibot.task.MiningServiceTask;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** World-runtime coverage for mining plans that need bootstrapped Minecraft registries. */
public final class GoalPlannerMiningGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void surfaceCoalWithoutVisibleOreDescendsToRockLayerBeforeMining(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.MineOre(Set.of(Blocks.COAL_ORE), 8));
        int descend = indexOf(plan, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        int coal = indexOf(plan, GoalPlannerMiningGameTests::isCoalOreStep);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, descend >= 0 && plan.steps().get(descend).pos().getY() == 48,
                "strict coal search must enter the observable rock layer: " + plan.describeSteps());
        require(context, coal > descend,
                "coal branch mining must follow DESCEND_TO_Y(48): " + plan.describeSteps());

        GoalPlanner.GoalPlan visible = GoalPlanner.planFromState(null,
                new Goal.MineOre(Set.of(Blocks.COAL_ORE), 8), Map.of(), 64, 64,
                false, false, false, ignored -> true, null);
        require(context, visible.success(), "visible unresolved=" + visible.unresolved());
        require(context, visible.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y),
                "a visible surface coal vein should retain the local shortcut: "
                        + visible.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void nestedCoalTorchProvisionDoesNotRepeatTheSameLayerHandoff(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.HaveItem(Items.OBSIDIAN, 32));
        int acquireWater = indexOf(plan, step -> step.kind() == GoalStep.Kind.ACQUIRE_WATER);
        require(context, plan.success() && acquireWater > 0,
                "obsidian fixture did not reach water acquisition: " + plan.describeSteps());

        long coalLayerHandoffs = plan.steps().subList(0, acquireWater).stream()
                .filter(step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y
                        && step.pos().getY() == 48)
                .count();
        require(context, coalLayerHandoffs == 1,
                "nested coal/torch provisioning repeated Y48 after the worker was already at its "
                        + "coal layer: handoffs=" + coalLayerHandoffs + " " + plan.describeSteps());
        int coal = indexOf(plan, GoalPlannerMiningGameTests::isCoalOreStep);
        require(context, coal >= 0 && coal + 1 < plan.steps().size()
                        && plan.steps().get(coal + 1).isMiningHandoffService()
                        && !plan.steps().get(coal + 1).maintainsTunnelingTools(),
                "nested coal must free physical crafting capacity before the parent resumes: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void emptyInventoryObsidianGoalBuildsDiamondPickaxeChain(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.HaveItem(Items.OBSIDIAN, 32));
        int hunt = indexOf(plan, step -> step.kind() == GoalStep.Kind.HUNT);
        int lastHunt = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.HUNT);
        int bulkLogs = indexOf(plan, step -> step.kind() == GoalStep.Kind.GATHER
                && (step.item() == Items.OAK_LOG || step.item() == Items.BIRCH_LOG)
                && step.count() >= 8);
        int firstStoneMine = indexOf(plan, step -> step.kind() == GoalStep.Kind.MINE
                && step.block() == Blocks.STONE);
        int bootstrapStonePick = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.STONE_PICKAXE);
        int furnace = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.FURNACE);
        int furnaceStoneMine = indexOfFrom(plan, bootstrapStonePick + 1,
                step -> step.kind() == GoalStep.Kind.MINE && step.block() == Blocks.STONE);
        int cook = indexOf(plan, step -> step.kind() == GoalStep.Kind.COOK_FOOD);
        int bucketIron = indexOf(plan, GoalPlannerMiningGameTests::isIronOreStep);
        int bucket = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.BUCKET);
        int stoneSword = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.STONE_SWORD);
        int bulkReadinessStone = indexOf(plan, step -> step.kind() == GoalStep.Kind.MINE
                && step.block() == Blocks.STONE && step.count() >= 64);
        int acquireWater = indexOf(plan, step -> step.kind() == GoalStep.Kind.ACQUIRE_WATER);
        int toolIron = indexOfFrom(plan, acquireWater + 1, GoalPlannerMiningGameTests::isIronOreStep);
        int diamondOre = indexOf(plan, step -> step.kind() == GoalStep.Kind.MINE_ORE
                && (step.ores().contains(Blocks.DIAMOND_ORE)
                || step.ores().contains(Blocks.DEEPSLATE_DIAMOND_ORE)));
        int diamondPickaxe = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE);
        int obsidianPreflight = indexOf(plan, GoalStep::isObsidianPreflight);
        int obsidian = indexOf(plan, step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN
                && step.count() == 32);
        int firstDescend = indexOf(plan, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int obsidianRation = MiningBudget.obsidianExpeditionFoodTarget(32);
        int initialRation = MiningBudget.obsidianExpeditionInitialFoodTarget(32);
        List<GoalStep> cookSteps = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.COOK_FOOD)
                .toList();
        int totalCooked = cookSteps.stream().mapToInt(GoalStep::count).sum();
        require(context, !cookSteps.isEmpty()
                        && cookSteps.get(0).count() == initialRation
                        && totalCooked == obsidianRation
                        && cookSteps.stream().noneMatch(GoalStep::bestEffort),
                "obsidian readiness must stage the initial " + initialRation
                        + " ration and top up to the scaled " + obsidianRation
                        + " total: " + plan.describeSteps());
        require(context, indexOfFrom(plan, acquireWater + 1,
                        step -> step.kind() == GoalStep.Kind.COOK_FOOD) >= 0,
                "the staged ration must complete after physical water acquisition: "
                        + plan.describeSteps());
        List<GoalStep> obsidianHunts = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.HUNT)
                .toList();
        require(context, obsidianHunts.stream().mapToInt(GoalStep::count).sum() == obsidianRation
                        && obsidianHunts.stream().allMatch(step -> step.count() <= 4),
                "obsidian readiness hunt batches must be bounded at 4: " + plan.describeSteps());
        require(context, hunt >= 0 && hunt < firstStoneMine && firstStoneMine < cook,
                "surface hunt must precede furnace stone mining and cooking: "
                        + plan.describeSteps());
        require(context, firstStoneMine >= 0
                        && plan.steps().get(firstStoneMine).count() == 3
                        && firstStoneMine < bootstrapStonePick
                        && bootstrapStonePick < furnaceStoneMine
                        && furnaceStoneMine < furnace,
                "from-zero obsidian must upgrade after three cobblestone before furnace and "
                        + "bulk stone work: " + plan.describeSteps());
        require(context, hunt < bulkLogs && bulkLogs < lastHunt,
                "bulk logs must stay near the first hunt instead of following a remote second hunt: "
                        + plan.describeSteps());
        require(context, acquireWater >= 0,
                "obsidian expedition must contain physical water acquisition: " + plan.describeSteps());
        int expectedDescentTorches = plannedDescentTorchUse(
                plan, obsidianPreflight, 64);
        int firstDeepDescend = indexOf(plan, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y
                && step.pos().getY() < 48);
        int preDeepDescendTorchOutput = plan.steps().subList(
                        0, Math.max(0, firstDeepDescend)).stream()
                .filter(step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.TORCH)
                .mapToInt(GoalStep::count)
                .sum();
        int bootstrapCoalDescentTorches = (64 - 48 + 5) / 6;
        require(context, firstDescend >= 0
                        && plan.steps().get(firstDescend).pos().getY() == 48
                        && firstDeepDescend > firstDescend
                        && expectedDescentTorches == 30
                        && preDeepDescendTorchOutput
                        >= expectedDescentTorches - bootstrapCoalDescentTorches + 8,
                "after the bootstrap coal descent, obsidian readiness must fund every deeper "
                        + "descent plus eight branch torches: use=" + expectedDescentTorches
                        + " output=" + preDeepDescendTorchOutput + " "
                        + plan.describeSteps());
        int preWaterStonePicks = plan.steps().subList(0, acquireWater).stream()
                .filter(step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.STONE_PICKAXE)
                .mapToInt(GoalStep::count)
                .sum();
        int preWaterSticks = plan.steps().subList(0, acquireWater).stream()
                .filter(step -> step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK)
                .mapToInt(GoalStep::count)
                .sum();
        int preWaterStone = plan.steps().subList(0, acquireWater).stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE && step.block() == Blocks.STONE)
                .mapToInt(GoalStep::count)
                .sum();
        require(context, preWaterStonePicks >= 4 && preWaterSticks >= 47 && preWaterStone >= 78,
                "obsidian readiness missing picks/sticks/emergency blocks: " + plan.describeSteps());
        require(context, stoneSword >= 0 && stoneSword < bulkReadinessStone
                        && bulkReadinessStone < acquireWater,
                "obsidian readiness must craft a stone sword before its long stone-reserve shaft: "
                        + plan.describeSteps());
        require(context, bucketIron > cook && bucket > bucketIron && acquireWater > bucket,
                "bucket chain must follow hard surface readiness and precede water search: "
                        + plan.describeSteps());
        require(context, toolIron > acquireWater,
                "tool iron must be acquired separately after the bucket/water chain: "
                        + plan.describeSteps());
        require(context, diamondOre > toolIron,
                "missing post-water diamond acquisition: " + plan.describeSteps());
        require(context, diamondPickaxe > diamondOre,
                "diamond pickaxe must follow its ore chain: " + plan.describeSteps());
        require(context, obsidianPreflight > diamondPickaxe && obsidian > obsidianPreflight,
                "obsidian preflight must separate diamond pickaxe from the first pool search: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void mixedLogFuelInventoryOnlyPlansTheFamilyDeficit(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.of(
                Items.OAK_LOG, 1,
                Items.BIRCH_LOG, 2,
                Items.FURNACE, 1,
                Items.WOODEN_SWORD, 1);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.Food(8), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> logGathers = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.GATHER
                        && (step.item() == Items.OAK_LOG || step.item() == Items.BIRCH_LOG))
                .toList();
        require(context, logGathers.size() == 1 && logGathers.getFirst().count() == 3,
                "3 mixed logs should need only the 3-log family fuel deficit: "
                        + plan.describeSteps());
        int firstHunt = indexOf(plan, step -> step.kind() == GoalStep.Kind.HUNT);
        int lastHunt = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.HUNT);
        int gather = indexOf(plan, logGathers.getFirst()::equals);
        int cook = indexOf(plan, step -> step.kind() == GoalStep.Kind.COOK_FOOD);
        // Capture one local animal batch, provision fuel while still near its habitat, then allow
        // the second bounded hunt to range farther before cooking.
        require(context, firstHunt >= 0 && firstHunt < gather
                        && gather < lastHunt && lastHunt < cook,
                "surface hunt/gather/cook order regressed: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void preparedObsidianExpeditionAcquiresWaterBeforeMining(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.of(
                Items.BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 40,
                Items.CRAFTING_TABLE, 1,
                Items.COOKED_BEEF, 24,
                Items.TORCH, 8,
                Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().size() == 3
                        && plan.steps().get(0).kind() == GoalStep.Kind.ACQUIRE_WATER
                        && plan.steps().get(1).isObsidianPreflight()
                        && plan.steps().get(2).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "prepared empty-bucket plan must physically acquire water first: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void missingStoneSwordHasAnIndependentPreWaterBudget(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.COBBLESTONE, 78),
                Map.entry(Items.STICK, 41),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, 24),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int sword = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.STONE_SWORD && step.count() == 1);
        int acquireWater = indexOf(plan, step -> step.kind() == GoalStep.Kind.ACQUIRE_WATER);
        require(context, sword == 0 && acquireWater == 1,
                "+2 stone/+1 stick weapon margin did not craft before water: "
                        + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.MINE
                                || step.kind() == GoalStep.Kind.GATHER
                                || step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.STICK),
                "stone-sword margin spent the original service reserve: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void preparedWaterBucketSkipsDuplicateAcquisition(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.of(
                Items.WATER_BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 40,
                Items.CRAFTING_TABLE, 1,
                Items.COOKED_BEEF, 24,
                Items.TORCH, 8,
                Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().size() == 2
                        && plan.steps().getFirst().isObsidianPreflight()
                        && plan.steps().get(1).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "prepared water bucket must not be refilled: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void preparedSevenAndEightTorchesFundTheWholePreWaterDescentBatch(
            TestContext context) {
        Map<net.minecraft.item.Item, Integer> sevenTorches = Map.ofEntries(
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 43),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.FURNACE, 1),
                Map.entry(Items.COAL, 4),
                Map.entry(Items.COOKED_BEEF, 8),
                Map.entry(Items.TORCH, 7));
        Map<net.minecraft.item.Item, Integer> eightTorches = Map.ofEntries(
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 42),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.FURNACE, 1),
                Map.entry(Items.COAL, 3),
                Map.entry(Items.COOKED_BEEF, 8),
                Map.entry(Items.TORCH, 8));
        Map<net.minecraft.item.Item, Integer> underfunded =
                new java.util.HashMap<>(sevenTorches);
        underfunded.put(Items.STICK, 42);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        Map<net.minecraft.item.Item, Integer> exactDiamondDurability =
                Map.of(Items.DIAMOND_PICKAXE, 32);
        GoalPlanner.GoalPlan seven = GoalPlanner.planFromState(null, goal,
                sevenTorches, exactDiamondDurability, 64, 64,
                false, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan eight = GoalPlanner.planFromState(null, goal,
                eightTorches, exactDiamondDurability, 64, 64,
                false, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan shortByOneStick = GoalPlanner.planFromState(null, goal,
                underfunded, exactDiamondDurability, 64, 64,
                false, false, false, false, ignored -> false, null);

        require(context, seven.success(), "seven-torch kit failed: " + seven.unresolved());
        int sevenCraft = indexOf(seven, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.TORCH);
        int sevenDescend = indexOf(seven, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        require(context, sevenCraft >= 0 && seven.steps().get(sevenCraft).count() == 12
                        && sevenCraft < sevenDescend,
                "7 torches must craft 12 before the eight-torch descent, not current+4: "
                        + seven.describeSteps());
        require(context, seven.steps().stream().filter(
                        step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y).count() == 1,
                "prepared exact diamond kit should have only the pre-water iron descent: "
                        + seven.describeSteps());
        require(context, seven.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "35 sticks should exactly fund 3 torch recipes and preserve service32: "
                        + seven.describeSteps());

        require(context, eight.success(), "eight-torch kit failed: " + eight.unresolved());
        int eightCraft = indexOf(eight, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.TORCH);
        int eightDescend = indexOf(eight, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        require(context, eightCraft >= 0 && eight.steps().get(eightCraft).count() == 8
                        && eightCraft < eightDescend,
                "8 torches must craft the full consumed batch before descent: "
                        + eight.describeSteps());
        require(context, eight.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "34 sticks should exactly fund 2 torch recipes and preserve service32: "
                        + eight.describeSteps());

        require(context, !shortByOneStick.success()
                        && shortByOneStick.unresolved().stream().anyMatch(reason ->
                        reason.contains("underground_surface_resource_unavailable")),
                "seven-torch kit with only 34 sticks incorrectly spent service reserve: "
                        + shortByOneStick.describeSteps() + " " + shortByOneStick.unresolved());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void preparedObsidianKitStillPlansItsMissingCarriedCraftingTable(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.of(
                Items.WATER_BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 40,
                Items.COOKED_BEEF, 8,
                Items.TORCH, 8);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), prepared, 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, plan.success(),
                "prepared kit without table became unresolved: " + plan.unresolved());
        int table = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.CRAFTING_TABLE);
        int preflight = indexOf(plan, GoalStep::isObsidianPreflight);
        require(context, table >= 0 && table < preflight,
                "obsidian readiness did not explicitly restore the carried table: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void obsidianReadinessUsesAggregateDiamondPickDurability(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.of(
                Items.WATER_BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 4,
                Items.STONE_SWORD, 1,
                Items.COBBLESTONE, 76,
                Items.STICK, 42,
                Items.CRAFTING_TABLE, 1,
                Items.COOKED_BEEF, 8,
                Items.TORCH, 8);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan raw32 = GoalPlanner.planFromState(null, goal, prepared,
                Map.of(Items.DIAMOND_PICKAXE, 31), 64, 64,
                true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan raw33 = GoalPlanner.planFromState(null, goal, prepared,
                Map.of(Items.DIAMOND_PICKAXE, 32), 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, raw32.success(),
                "raw32 diamond pick could not plan its replacement: " + raw32.unresolved());
        require(context, raw32.steps().stream().anyMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.DIAMOND_PICKAXE),
                "raw32 incorrectly satisfied the 32-break usable durability contract: "
                        + raw32.describeSteps());
        require(context, raw32.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE),
                "raw32 has enough durability to acquire three replacement diamonds: "
                        + raw32.describeSteps());
        require(context, raw32.steps().stream().anyMatch(step ->
                        isDiamondStep(step) && step.count() == 3),
                "raw32 replacement did not acquire exactly three diamonds: "
                        + raw32.describeSteps());
        require(context, raw33.success(),
                "raw33 exact usable durability was rejected: " + raw33.unresolved());
        require(context, raw33.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.DIAMOND_PICKAXE),
                "raw33 planned an unnecessary replacement diamond pick: "
                        + raw33.describeSteps());
        require(context, raw33.steps().stream().noneMatch(step ->
                        (step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE)
                                || isDiamondStep(step)),
                "raw33 planned an unnecessary replacement acquisition chain: "
                        + raw33.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void rawTwoDiamondPickUsesLooseDiamondsBeforeAddingAnAcquisitionPick(
            TestContext context) {
        Map<net.minecraft.item.Item, Integer> noLooseDiamonds = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 44),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, 8),
                Map.entry(Items.TORCH, 8));
        Map<net.minecraft.item.Item, Integer> twoLooseDiamonds = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.DIAMOND, 2),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 42),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, 8),
                Map.entry(Items.TORCH, 8));
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        Map<net.minecraft.item.Item, Integer> rawTwoDurability =
                Map.of(Items.DIAMOND_PICKAXE, 1);
        GoalPlanner.GoalPlan withoutLoose = GoalPlanner.planFromState(null, goal,
                noLooseDiamonds, rawTwoDurability, 64, 64,
                true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan withTwoLoose = GoalPlanner.planFromState(null, goal,
                twoLooseDiamonds, rawTwoDurability, 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, withoutLoose.success(),
                "raw2 replacement with iron materials became unresolved: "
                        + withoutLoose.unresolved());
        int ironPick = indexOf(withoutLoose, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.IRON_PICKAXE);
        int threeDiamonds = indexOf(withoutLoose,
                step -> isDiamondStep(step) && step.count() == 3);
        int replacement = indexOf(withoutLoose, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        int preflight = indexOf(withoutLoose, GoalStep::isObsidianPreflight);
        require(context, ironPick >= 0 && ironPick < threeDiamonds
                        && threeDiamonds < replacement && replacement < preflight,
                "raw2 must provision iron before mining all three replacement diamonds: "
                        + withoutLoose.describeSteps());
        require(context, withoutLoose.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "exact 36-stick raw2 kit was replenished twice: "
                        + withoutLoose.describeSteps());

        require(context, withTwoLoose.success(),
                "raw2 plus two loose diamonds became unresolved: "
                        + withTwoLoose.unresolved());
        require(context, withTwoLoose.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE),
                "one safe diamond break should not manufacture an acquisition iron pick: "
                        + withTwoLoose.describeSteps());
        int oneDiamond = indexOf(withTwoLoose,
                step -> isDiamondStep(step) && step.count() == 1);
        int looseReplacement = indexOf(withTwoLoose,
                step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        require(context, oneDiamond >= 0 && oneDiamond < looseReplacement,
                "two loose diamonds must reduce the acquisition quota to exactly one: "
                        + withTwoLoose.describeSteps());
        require(context, withTwoLoose.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "exact 34-stick loose-diamond kit was replenished twice: "
                        + withTwoLoose.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void lowAndExactNetheriteDurabilityUseTheSameObsidianContract(TestContext context) {
        Map<net.minecraft.item.Item, Integer> lowKit = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.NETHERITE_PICKAXE, 1),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 44),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, MiningBudget.obsidianExpeditionFoodTarget(32)),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        Map<net.minecraft.item.Item, Integer> exactKit = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.NETHERITE_PICKAXE, 1),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 40),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, MiningBudget.obsidianExpeditionFoodTarget(32)),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan low = GoalPlanner.planFromState(null, goal, lowKit,
                Map.of(Items.NETHERITE_PICKAXE, 1), 64, 64,
                true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan exact = GoalPlanner.planFromState(null, goal, exactKit,
                Map.of(Items.NETHERITE_PICKAXE, 32), 64, 64,
                true, false, false, true, ignored -> true, null);

        require(context, low.success(), "low netherite replacement failed: " + low.unresolved());
        int ironPick = indexOf(low, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.IRON_PICKAXE);
        int diamondOre = indexOf(low, step -> isDiamondStep(step) && step.count() == 3);
        int diamondPick = indexOf(low, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        require(context, ironPick >= 0 && ironPick < diamondOre && diamondOre < diamondPick,
                "raw2 netherite must use iron to acquire its diamond replacement: "
                        + low.describeSteps());
        require(context, low.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.NETHERITE_PICKAXE),
                "planner attempted an unsupported netherite replacement recipe: "
                        + low.describeSteps());

        require(context, exact.success(), "exact netherite durability failed: " + exact.unresolved());
        require(context, exact.steps().size() == 2
                        && exact.steps().getFirst().isObsidianPreflight()
                        && exact.steps().get(1).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "usable32 netherite should directly satisfy the target-tool contract: "
                        + exact.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void mixedDiamondAndNetheriteDurabilityUsesTheAggregateBoundary(TestContext context) {
        Map<net.minecraft.item.Item, Integer> exactKit = Map.ofEntries(
                Map.entry(Items.WATER_BUCKET, 1),
                Map.entry(Items.DIAMOND_PICKAXE, 1),
                Map.entry(Items.NETHERITE_PICKAXE, 1),
                Map.entry(Items.IRON_INGOT, 3),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.STONE_SWORD, 1),
                Map.entry(Items.COBBLESTONE, 76),
                Map.entry(Items.STICK, 40),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.COOKED_BEEF, MiningBudget.obsidianExpeditionFoodTarget(32)),
                Map.entry(Items.TORCH, 8),
                Map.entry(Items.OAK_LOG, EmergencyShelterTask.MAX_PLACEMENT_BLOCKS));
        Map<net.minecraft.item.Item, Integer> belowKit = new java.util.HashMap<>(exactKit);
        belowKit.put(Items.STICK, 42);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan exact = GoalPlanner.planFromState(null, goal, exactKit,
                Map.of(Items.DIAMOND_PICKAXE, 15, Items.NETHERITE_PICKAXE, 17),
                64, 64, true, false, false, true, ignored -> true, null);
        GoalPlanner.GoalPlan below = GoalPlanner.planFromState(null, goal, belowKit,
                Map.of(Items.DIAMOND_PICKAXE, 15, Items.NETHERITE_PICKAXE, 16),
                64, 64, true, false, false, true, ignored -> true, null);

        require(context, exact.success(), "mixed usable32 failed: " + exact.unresolved());
        require(context, exact.steps().size() == 2
                        && exact.steps().getFirst().isObsidianPreflight()
                        && exact.steps().get(1).kind() == GoalStep.Kind.MAKE_OBSIDIAN,
                "mixed aggregate usable32 planned an unnecessary tool chain: "
                        + exact.describeSteps());

        require(context, below.success(), "mixed usable31 failed: " + below.unresolved());
        require(context, below.steps().stream().noneMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.IRON_PICKAXE),
                "mixed usable31 can safely mine all three replacement diamonds: "
                        + below.describeSteps());
        int diamondOre = indexOf(below, step -> isDiamondStep(step) && step.count() == 3);
        int diamondPick = indexOf(below, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.DIAMOND_PICKAXE && step.count() == 1);
        require(context, diamondOre >= 0 && diamondOre < diamondPick,
                "mixed usable31 did not add exactly one diamond replacement: "
                        + below.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void undergroundObsidianReplanUsesCarriedKitWithoutSurfaceWork(TestContext context) {
        Map<net.minecraft.item.Item, Integer> carried = Map.ofEntries(
                Map.entry(Items.RAW_IRON, 2),
                Map.entry(Items.OAK_LOG, 11),
                Map.entry(Items.STICK, 2),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.STONE_PICKAXE, 3),
                Map.entry(Items.COBBLESTONE, 200),
                Map.entry(Items.COOKED_BEEF, 8));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), carried, 64, 15,
                false, false, false, false, ignored -> true,
                GoalSnapshotCollector.Context.at(new BlockPos(0, 64, 0)));

        require(context, plan.success(), "underground carried kit must remain viable: "
                + plan.unresolved() + " " + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "underground replan emitted surface work: " + plan.describeSteps());
        int acquireWater = indexOf(plan, step -> step.kind() == GoalStep.Kind.ACQUIRE_WATER);
        int returnDescend = indexOfFrom(plan, acquireWater + 1,
                step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        int postWaterIron = indexOfFrom(plan, acquireWater + 1,
                GoalPlannerMiningGameTests::isIronOreStep);
        require(context, acquireWater >= 0 && returnDescend > acquireWater
                        && postWaterIron > returnDescend,
                "water return must descend again before post-water iron mining: "
                        + plan.describeSteps());
        require(context, plan.steps().subList(acquireWater + 1, returnDescend).stream()
                        .noneMatch(step -> step.kind() == GoalStep.Kind.MINE_ORE),
                "water return scheduled ore mining before its descent: " + plan.describeSteps());
        require(context, indexOf(plan, GoalPlannerMiningGameTests::isIronOreStep) >= 0,
                "underground replan must continue the one missing bucket iron: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void undergroundObsidianReplanConsumesMixedLogFuelAsOneFamily(TestContext context) {
        Map<net.minecraft.item.Item, Integer> carried = Map.ofEntries(
                Map.entry(Items.OAK_LOG, 6),
                Map.entry(Items.BIRCH_LOG, 9),
                Map.entry(Items.STICK, 5),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.FURNACE, 1),
                Map.entry(Items.STONE_PICKAXE, 3),
                Map.entry(Items.COBBLESTONE, 202),
                Map.entry(Items.COOKED_MUTTON, 8));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), carried, 64, 16,
                false, false, false, false, ignored -> true,
                GoalSnapshotCollector.Context.at(new BlockPos(0, 64, 0)));

        require(context, plan.success(),
                "seed3000 mixed-log underground kit became unresolved: "
                        + plan.unresolved() + " " + plan.describeSteps());
        require(context, plan.unresolved().stream().noneMatch(
                        reason -> reason.contains("minecraft:oak_log")),
                "planner bound aggregate furnace fuel to oak: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "mixed carried fuel emitted underground surface work: " + plan.describeSteps());
        int ironStages = (int) plan.steps().stream()
                .filter(GoalPlannerMiningGameTests::isIronOreStep)
                .count();
        require(context, ironStages >= 3,
                "remaining bucket/tool/spare iron stages were not all planned: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void undergroundObsidianReplanUsesBirchLogsForMissingStickPlanks(TestContext context) {
        // seed 3000 evidence inventory at the hostile-cave interruption: one stray oak plank must
        // not bind the eight-stick readiness contract to oak when nine carried birch logs can make
        // the complete missing plank quota without any surface acquisition.
        Map<net.minecraft.item.Item, Integer> carried = Map.ofEntries(
                Map.entry(Items.BIRCH_LOG, 9),
                Map.entry(Items.OAK_PLANKS, 1),
                Map.entry(Items.STICK, 2),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.STONE_PICKAXE, 3),
                Map.entry(Items.COBBLESTONE, 239),
                Map.entry(Items.COAL, 1),
                Map.entry(Items.TORCH, 2),
                Map.entry(Items.COOKED_MUTTON, 6),
                Map.entry(Items.COOKED_CHICKEN, 2));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), carried, 64, 18,
                false, false, false, false, ignored -> true,
                GoalSnapshotCollector.Context.at(new BlockPos(0, 80, 0)));

        require(context, plan.success(),
                "carried birch stick reserve became unresolved: "
                        + plan.unresolved() + " " + plan.describeSteps());
        require(context, plan.unresolved().stream().noneMatch(
                        reason -> reason.contains("minecraft:oak_log")),
                "planner still bound the plank family to unavailable oak: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "birch-backed underground replan emitted surface work: " + plan.describeSteps());
        require(context, plan.steps().stream().anyMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.STICK),
                "missing stick reserve did not retain its craft step: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void mixedLogFamiliesAggregateBeforePlanningOneRemainingPlankGap(
            TestContext context) {
        Map<net.minecraft.item.Item, Integer> mixedLogs = Map.of(
                Items.OAK_LOG, 2,
                Items.BIRCH_LOG, 6,
                Items.OAK_PLANKS, 2,
                Items.STICK, 2);
        GoalPlanner.GoalPlan exact = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.STICK, 58), mixedLogs, 64, 64,
                false, false, false, ignored -> false, null);

        require(context, exact.success(),
                "mixed-family stick plan became unresolved: " + exact.unresolved());
        require(context, exact.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.GATHER),
                "two carried log families planned redundant gathering: "
                        + exact.describeSteps());
        require(context, exact.steps().stream().anyMatch(step ->
                        step.kind() == GoalStep.Kind.CRAFT
                                && step.item() == Items.STICK
                                && step.count() == 56),
                "mixed-family capacity did not retain the final stick craft: "
                        + exact.describeSteps());

        GoalPlanner.GoalPlan oneLogShort = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.STICK, 74), mixedLogs, 64, 64,
                false, false, false, ignored -> false, null);
        List<GoalStep> gathers = oneLogShort.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.GATHER)
                .toList();
        require(context, oneLogShort.success()
                        && gathers.size() == 1
                        && gathers.getFirst().count() == 1
                        && (gathers.getFirst().item() == Items.OAK_LOG
                        || gathers.getFirst().item() == Items.BIRCH_LOG),
                "remaining plank-family gap was not planned exactly once: "
                        + oneLogShort.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void undergroundRawMeatCannotMasqueradeAsMiningFoodReserve(TestContext context) {
        Map<net.minecraft.item.Item, Integer> carried = Map.of(
                Items.BEEF, 64,
                Items.BUCKET, 1,
                Items.DIAMOND_PICKAXE, 1,
                Items.STONE_PICKAXE, 2,
                Items.COBBLESTONE, 16,
                Items.STICK, 8);
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32), carried, 64, -40,
                false, false, false, false, ignored -> false, null);

        require(context, !plan.success(), "raw meat must not satisfy the deep-mine reserve");
        require(context, plan.unresolved().stream().anyMatch(
                        reason -> reason.startsWith("deep_mining_food_reserve_depleted")),
                "missing typed reserve failure: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "failed underground plan emitted surface work: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void netheriteTierDoesNotSilentlyDowngrade(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.HavePickaxeTier(ToolTier.NETHERITE));
        require(context, !plan.success(), "netherite acquisition is not implemented and must remain explicit");
        require(context, plan.unresolved().stream().anyMatch(reason -> reason.contains("minecraft:netherite_pickaxe")),
                "wrong tier selected: " + plan.unresolved());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void surfaceDiamondStackAddsExactlyFourteenRawLogShelterReserve(
            TestContext context) {
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        // Keep every non-shelter dependency identical. A raw from-zero comparison lets the
        // carried wood change recipe-family rounding elsewhere in the bootstrap and does not
        // isolate this reserve contract.
        GoalPlanner.GoalPlan empty = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of()),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan carried = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, empty.success() && carried.success(),
                "diamond reserve fixtures did not plan: empty=" + empty.unresolved()
                        + " carried=" + carried.unresolved());
        int emptyGather = plannedLogGatherCount(empty);
        int carriedGather = plannedLogGatherCount(carried);
        require(context, emptyGather - carriedGather
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "diamond raw-log reserve delta was not exactly "
                        + EmergencyShelterTask.MAX_PLACEMENT_BLOCKS
                        + ": empty=" + emptyGather + " carried=" + carriedGather);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void surfaceDiamondStackTopsUpThirteenButNotFourteenShelterBlocks(
            TestContext context) {
        Goal goal = new Goal.HaveItem(Items.DIAMOND, 64);
        GoalPlanner.GoalPlan empty = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of()),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan thirteen = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS - 1)),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan fourteen = GoalPlanner.planFromState(null, goal,
                preparedDiamondContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, empty.success() && thirteen.success() && fourteen.success(),
                "prepared diamond reserve fixtures did not plan: empty="
                        + empty.unresolved() + " thirteen=" + thirteen.unresolved()
                        + " fourteen=" + fourteen.unresolved());
        int unrelatedWoodBaseline = plannedLogGatherCount(fourteen);
        require(context, plannedLogGatherCount(thirteen) - unrelatedWoodBaseline == 1,
                "thirteen carried shelter blocks did not add exactly one reserve log: "
                        + thirteen.describeSteps());
        require(context, plannedLogGatherCount(empty) - unrelatedWoodBaseline
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "fourteen carried shelter blocks did not eliminate the whole reserve top-up: "
                        + fourteen.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void surfaceDiamondStackDoesNotTreatPlanksAsHardShelterReserve(
            TestContext context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64),
                preparedDiamondContract(Map.of(
                        Items.OAK_PLANKS,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, plan.success(),
                "plank-only reserve fixture did not plan: " + plan.unresolved());
        require(context, plannedLogGatherCount(plan)
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "craft-spendable planks incorrectly satisfied the raw-log reserve: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void undergroundDiamondStackResumeDoesNotGatherShelterWood(
            TestContext context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64),
                preparedDiamondContract(Map.of()),
                64, -59, false, false, false, false,
                ignored -> true, null);

        require(context, plan.success(),
                "prepared underground diamond resume did not plan: " + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.GATHER),
                "underground diamond resume attempted surface shelter gathering");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void surfaceObsidianStackAlsoReservesFourteenShelterBlocks(
            TestContext context) {
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 32);
        GoalPlanner.GoalPlan empty = GoalPlanner.planFromState(null, goal,
                preparedObsidianContract(Map.of()),
                64, 64, false, false, false, ignored -> false, null);
        GoalPlanner.GoalPlan carried = GoalPlanner.planFromState(null, goal,
                preparedObsidianContract(Map.of(
                        Items.OAK_LOG,
                        EmergencyShelterTask.MAX_PLACEMENT_BLOCKS)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, empty.success() && carried.success(),
                "obsidian reserve fixtures did not plan: empty=" + empty.unresolved()
                        + " carried=" + carried.unresolved());
        require(context, plannedLogGatherCount(empty)
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "prepared obsidian plan did not reserve fourteen shelter blocks: "
                        + plannedLogGatherCount(empty));
        require(context, plannedLogGatherCount(carried) == 0,
                "prepared obsidian plan borrowed its fourteen carried shelter blocks: "
                        + plannedLogGatherCount(carried));
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void partialSurfaceObsidianStackRetainsThirtyTwoItemShelterContract(
            TestContext context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.OBSIDIAN, 32),
                preparedObsidianContract(Map.of(Items.OBSIDIAN, 1)),
                64, 64, false, false, false, ignored -> false, null);

        require(context, plan.success(),
                "partial obsidian reserve fixture did not plan: " + plan.unresolved());
        require(context, plannedLogGatherCount(plan)
                        == EmergencyShelterTask.MAX_PLACEMENT_BLOCKS,
                "31 remaining obsidian lost the original half-stack shelter contract: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void diamondStackStrictlyAlternatesBoundedBatchesAndServiceCheckpoints(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 64));

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int cook = indexOf(plan, step -> step.kind() == GoalStep.Kind.COOK_FOOD);
        int firstDescend = indexOf(plan, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y);
        require(context, cook >= 0 && firstDescend > cook,
                "hard food readiness must finish before the first descent: " + plan.describeSteps());
        require(context, !plan.steps().get(cook).bestEffort(),
                "long-expedition food readiness must not be skippable");
        require(context, plan.steps().get(cook).count() == MiningBudget.RARE_BOOTSTRAP_FOOD,
                "diamond stack readiness must cook exactly "
                        + MiningBudget.RARE_BOOTSTRAP_FOOD + " food, got "
                        + plan.steps().get(cook));
        List<GoalStep> diamondHunts = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.HUNT)
                .toList();
        require(context, diamondHunts.stream().mapToInt(GoalStep::count).sum()
                        == MiningBudget.RARE_BOOTSTRAP_FOOD
                        && diamondHunts.stream().allMatch(step -> step.count() <= 4),
                "diamond stack hunt batches must be bounded at 4: " + plan.describeSteps());
        int ironPicks = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.CRAFT && step.item() == Items.IRON_PICKAXE)
                .mapToInt(GoalStep::count)
                .sum();
        require(context, ironPicks == 3,
                "diamond stack must prepare 3 target-grade picks, got " + ironPicks);
        List<GoalStep> bootstrapIron = plan.steps().stream()
                .filter(GoalPlannerMiningGameTests::isIronOreStep)
                .toList();
        require(context, bootstrapIron.size() == 1
                        && bootstrapIron.getFirst().count() >= 15,
                "diamond stack must aggregate 3/6/6 iron into one >=15 ordinary channel mission: "
                        + plan.describeSteps());
        int finalDescent = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y
                && step.pos().getY() == -59);
        int finalDescentTorchReserve = finalDescent < 0 ? -1
                : plannedDescentTorchUse(plan, finalDescent + 1, 64)
                - plannedDescentTorchUse(plan, finalDescent, 64);
        int expectedCraftedTorches = roundUpToTorchRecipe(
                MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                        + Math.max(0, finalDescentTorchReserve));
        int expectedCoal = (expectedCraftedTorches + 3) / 4;
        int expectedCoalBatches = (expectedCoal + 15) / 16;
        List<GoalStep> bootstrapCoal = plan.steps().stream()
                .filter(GoalPlannerMiningGameTests::isCoalOreStep)
                .toList();
        require(context, bootstrapCoal.size() == expectedCoalBatches
                        && bootstrapCoal.stream().mapToInt(GoalStep::count).sum() == expectedCoal,
                "coal bootstrap must exactly fund the rare and descent torch contract: expected="
                        + expectedCoal + " batches=" + expectedCoalBatches
                        + " plan=" + plan.describeSteps());
        List<GoalStep> coalServices = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINING_SERVICE
                        && step.ores().stream().anyMatch(block -> block == Blocks.COAL_ORE
                        || block == Blocks.DEEPSLATE_COAL_ORE))
                .toList();
        List<GoalStep> maintainedCoalServices = coalServices.stream()
                .filter(GoalStep::maintainsTunnelingTools)
                .toList();
        require(context, coalServices.size() == expectedCoalBatches
                        && maintainedCoalServices.size() == expectedCoalBatches - 1,
                "coal bootstrap must expose one maintained service per completed non-final batch: "
                        + plan.describeSteps());
        for (int index = 0; index < maintainedCoalServices.size(); index++) {
            require(context, maintainedCoalServices.get(index).count() == (index + 1) * 16,
                    "coal service boundary drifted at index " + index + ": "
                            + plan.describeSteps());
        }
        require(context, coalServices.getLast().isMiningHandoffService()
                        && coalServices.getLast().count() == expectedCoal
                        && !coalServices.getLast().maintainsTunnelingTools(),
                "coal bootstrap final batch did not publish its capacity-only parent handoff: "
                        + plan.describeSteps());
        long ordinaryFreshPools = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.STONE_PICKAXE
                        && step.count() == MiningBudget.ORDINARY_CHANNEL_INITIAL_PICKAXES)
                .count();
        require(context, ordinaryFreshPools == 2,
                "from-zero diamond64 must own exactly one ordinary channel pool for aggregated "
                        + "iron and one for coal, got " + ordinaryFreshPools + ": "
                        + plan.describeSteps());
        int craftedTorches = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.TORCH)
                .mapToInt(GoalStep::count)
                .sum();
        require(context, finalDescentTorchReserve > 0
                        && craftedTorches == expectedCraftedTorches,
                "from-zero diamond64 must physically craft the full rare-epoch contract plus its "
                        + "actual final-descent reserve: base="
                        + MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES
                        + " descent=" + finalDescentTorchReserve
                        + " expected=" + expectedCraftedTorches
                        + " actual=" + craftedTorches + " plan=" + plan.describeSteps());
        require(context, plan.steps().stream().anyMatch(step -> step.kind() == GoalStep.Kind.CRAFT
                        && step.item() == Items.TORCH && !step.bestEffort()),
                "diamond64 hard torch provision was marked best-effort: " + plan.describeSteps());
        int hardStone = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE
                        && step.block() == Blocks.STONE && !step.bestEffort())
                .mapToInt(GoalStep::count)
                .sum();
        require(context, hardStone >= MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                "diamond64 did not hard-provision boundary0, retry and disposal stone: "
                        + plan.describeSteps());
        int firstDiamond = indexOf(plan, step -> step.kind() == GoalStep.Kind.MINE_ORE
                && isDiamondStep(step));
        int lastBootstrapOre = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.MINE_ORE
                && !isDiamondStep(step));
        int rareDescentKit = lastIndexOf(plan, GoalStep::isRareDescentKitService);
        int lastStoneAcquisition = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.MINE
                && step.block() == Blocks.STONE);
        int finalStickTopUp = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.STICK);
        require(context, lastBootstrapOre >= 0
                        && rareDescentKit > lastBootstrapOre
                        && rareDescentKit + 1 == finalDescent,
                "target64 rare descent service must be the last bootstrap step after every ordinary "
                        + "dependency and immediately before Y=-59: " + plan.describeSteps());
        require(context, lastStoneAcquisition >= 0
                        && finalStickTopUp > lastStoneAcquisition
                        && finalStickTopUp < rareDescentKit,
                "final rare sequence must finish stone acquisition before sealing sticks and "
                        + "entering the mission kit: " + plan.describeSteps());
        require(context, plan.steps().stream()
                        .filter(step -> step.kind() == GoalStep.Kind.MINING_SERVICE
                                && !isDiamondStep(step)
                                && !step.isMiningHandoffService())
                        .allMatch(GoalStep::maintainsTunnelingTools),
                "ordinary bootstrap services lost channel-tool maintenance: "
                        + plan.describeSteps());
        require(context, finalDescent >= 0 && firstDiamond == finalDescent + 2
                        && plan.steps().get(finalDescent + 1).isRareOreService()
                        && plan.steps().get(finalDescent + 1).count() == 0,
                "boundary0 service must immediately bridge final descent and first diamond batch: "
                        + plan.describeSteps());
        requireDiamondExpeditionSequence(context, plan, 8, true);
        MiningMissionBudget.OuterTimeoutBudget outer =
                MiningMissionBudget.diamondStack64FromZero(plan);
        int nominalStepWindows = plan.steps().stream().mapToInt(step -> switch (step.kind()) {
            case MINING_SERVICE -> MiningMissionBudget.SERVICE_HARD_WINDOW_TICKS;
            case DESCEND_TO_Y -> MiningMissionBudget.DESCEND_HARD_WINDOW_TICKS;
            case MINE_ORE -> MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS;
            default -> MiningMissionBudget.auxiliaryStepWindowTicks(
                    step.kind(), step.count());
        }).sum();
        int ordinaryCapacityUpper = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE && !isDiamondStep(step))
                .mapToInt(GoalStep::count)
                .sum();
        // The retry terms carry the eight per-batch retries plus the capped two-epoch mission
        // margin pool (F2); bootstrap coal/services scale with the margin-funded 720-torch pool.
        require(context, outer.targetOreDigBatches() == 8
                        && outer.bootstrapOreDigBatches() >= 5
                        && outer.retryOreDigBatches() == 10
                        && outer.targetServiceCheckpoints() == 9
                        && outer.bootstrapServiceCheckpoints() >= 1
                        && outer.retryServiceCheckpoints() == 10
                        && ordinaryCapacityUpper == expectedCoal
                        + bootstrapIron.getFirst().count()
                        && outer.inventoryServiceCheckpoints() == 8 + ordinaryCapacityUpper
                        && outer.serviceCheckpoints() == 9 + (expectedCoalBatches + 1)
                        + 10 + 8 + ordinaryCapacityUpper
                        && outer.descents() >= 2
                        && outer.auxiliaryTicks() >= 499_200
                        && outer.timeoutTicks() >= nominalStepWindows
                        + MiningMissionBudget.FROM_ZERO_BOOTSTRAP_MARGIN_TICKS
                        && outer.timeoutTicks() <= 2_900_000,
                "outer timeout omitted nominal bootstrap mining/service work: " + outer
                        + " capacity_upper=" + ordinaryCapacityUpper
                        + " expected_coal=" + expectedCoal
                        + " nominal=" + nominalStepWindows);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void diamondStackReplansExactNetHuntAfterShelterConsumesRawMeat(
            TestContext context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(
                null,
                new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 64),
                Map.of(Items.BEEF, 37),
                64, 64,
                false, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> hunts = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.HUNT)
                .toList();
        int cook = indexOf(plan, step -> step.kind() == GoalStep.Kind.COOK_FOOD);
        require(context, hunts.stream().mapToInt(GoalStep::count).sum()
                        == MiningBudget.RARE_BOOTSTRAP_FOOD - 37
                        && hunts.stream().allMatch(step -> step.count() <= 4),
                "37 carried raw meat must leave an exact "
                        + (MiningBudget.RARE_BOOTSTRAP_FOOD - 37)
                        + "-meat hunt deficit: " + plan.describeSteps());
        require(context, cook >= 0
                        && plan.steps().get(cook).count()
                        == MiningBudget.RARE_BOOTSTRAP_FOOD
                        && !plan.steps().get(cook).bestEffort(),
                "carried raw meat must still cook the full "
                        + MiningBudget.RARE_BOOTSTRAP_FOOD + "-unit hard reserve: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void nearBrokenStonePicksStillPlanRareKitImmediatelyBeforeFinalDescent(
            TestContext context) {
        MiningBudget rareBudget = MiningBudget.forQuota(64, true, ToolTier.IRON);
        int preKitStoneLike = rareBudget.emergencyBlocks()
                + rareBudget.tunnelingPickaxes() * MiningBudget.STONE_PICKAXE_HEAD_COST + 2;
        int preKitSticks = rareBudget.spareToolSticks()
                + rareBudget.tunnelingPickaxes() * MiningBudget.STONE_PICKAXE_STICK_COST;
        Map<net.minecraft.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.STONE_PICKAXE, 5),
                Map.entry(Items.CHEST, 1),
                Map.entry(Items.COBBLESTONE, preKitStoneLike),
                Map.entry(Items.STICK, preKitSticks),
                Map.entry(Items.TORCH, MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES),
                Map.entry(Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD),
                Map.entry(Items.CRAFTING_TABLE, 1));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared,
                Map.of(Items.STONE_PICKAXE, 5), 64, 64,
                true, false, false, true, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int finalDescent = lastIndexOf(plan, step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y
                && step.pos().getY() == -59);
        require(context, finalDescent > 0
                        && plan.steps().get(finalDescent - 1).isRareDescentKitService(),
                "near-broken count-five inventory suppressed the target64 kit service: "
                        + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void subStackRareTargetsRetainDirectFreshCraftWithoutTarget64Kit(
            TestContext context) {
        for (int target : List.of(8, 32, 63)) {
            GoalPlanner.GoalPlan plan = plan(new Goal.HaveItem(Items.DIAMOND, target));
            require(context, plan.success(), "target=" + target + " unresolved=" + plan.unresolved());
            int finalDescent = lastIndexOf(plan, step ->
                    step.kind() == GoalStep.Kind.DESCEND_TO_Y
                            && step.pos().getY() == -59);
            require(context, finalDescent > 0
                            && plan.steps().get(finalDescent - 1).kind()
                            == GoalStep.Kind.CRAFT
                            && plan.steps().get(finalDescent - 1).item()
                            == Items.STONE_PICKAXE
                            && plan.steps().get(finalDescent - 1).count() == 5,
                    "target=" + target + " lost its direct five-pick hand-off: "
                            + plan.describeSteps());
            require(context, plan.steps().stream().noneMatch(
                            GoalStep::isRareDescentKitService),
                    "target=" + target + " incorrectly inherited target64 KIT identity: "
                            + plan.describeSteps());
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void runningKitRestoreKeepsOnlyServiceAndProvenDescentTail(TestContext context) {
        Set<net.minecraft.block.Block> diamonds = Set.of(Blocks.DIAMOND_ORE);
        MiningBudget rareBudget = MiningBudget.forQuota(64, true, ToolTier.IRON);
        int preKitStoneLike = rareBudget.emergencyBlocks()
                + rareBudget.tunnelingPickaxes() * MiningBudget.STONE_PICKAXE_HEAD_COST + 2;
        int preKitSticks = rareBudget.spareToolSticks()
                + rareBudget.tunnelingPickaxes() * MiningBudget.STONE_PICKAXE_STICK_COST;
        GoalStep kit = GoalStep.rareDescentKitService(diamonds, 64);
        GoalStep descent = GoalStep.descendToY(-59);
        GoalStep boundaryZero = GoalStep.rareOreService(diamonds, 0, 64);
        GoalStep firstBatch = GoalStep.mineOre(diamonds, 8);
        GoalStep laterService = GoalStep.rareOreService(diamonds, 8, 64);
        List<GoalStep> fresh = List.of(
                GoalStep.mine(Blocks.STONE, preKitStoneLike),
                GoalStep.craft(Items.STICK, preKitSticks),
                kit,
                descent,
                boundaryZero,
                firstBatch,
                laterService);
        List<GoalStep> tail = GoalExecutor.rareDescentTail(fresh, diamonds);

        require(context, tail.size() == 4
                        && tail.getFirst().equals(descent)
                        && tail.get(1).equals(boundaryZero)
                        && tail.get(2).equals(firstBatch),
                "restore retained bootstrap work between KIT and DESCEND: " + tail);
        require(context, GoalExecutor.rareDescentTail(
                        List.of(GoalStep.mine(Blocks.STONE, preKitStoneLike), kit, firstBatch),
                        diamonds)
                        .isEmpty(),
                "restore accepted a tail without the exact DESCEND->boundary0->batch proof");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void sixteenDiamondsUseExactlyTwoBatchesWithOneCumulativeCheckpoint(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 16));

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        requireDiamondExpeditionSequence(context, plan, 2, true);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void preparedAtMineLayerStartsDirectlyWithDiamondBatch(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.of(
                Items.IRON_PICKAXE, 5,
                Items.STONE_PICKAXE, 4,
                Items.IRON_INGOT, 12,
                Items.COBBLESTONE, MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                Items.STICK, MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS,
                Items.TORCH, MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES,
                Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD,
                Items.CRAFTING_TABLE, 1);
        int occupiedSlots = occupiedInventorySlots(prepared);
        int serviceFreeSlots = MiningServiceTask.ServicePolicy
                .rareOreBatch(64, 0).freeSlotsMin();
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                true, false, false, ignored -> true, null);

        require(context, occupiedSlots + serviceFreeSlots <= PlayerInventory.MAIN_SIZE,
                "prepared rare contract does not fit the factual main-inventory capacity: occupied="
                        + occupiedSlots + " required_free=" + serviceFreeSlots);
        require(context, plan.success(), "unresolved=" + plan.unresolved());
        int firstDiamond = indexOf(plan, step -> step.kind() == GoalStep.Kind.MINE_ORE
                && isDiamondStep(step));
        require(context, plan.steps().getFirst().kind() == GoalStep.Kind.MINING_SERVICE
                        && plan.steps().getFirst().isRareOreService()
                        && plan.steps().getFirst().count() == 0
                        && plan.steps().getFirst().rareOreMissionTarget() == 64
                        && plan.steps().getFirst().maintainsTunnelingTools()
                        && firstDiamond == 1,
                "prepared mine-layer inventory must service its live kit before mining: "
                        + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(GoalStep::bestEffort),
                "prepared plan should contain only required diamond batches/checkpoints: " + plan.steps());
        requireDiamondExpeditionSequence(context, plan, 8, true);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void partialDiamondStackRetainsLongMissionServiceIdentity(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.DIAMOND, 55),
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.COBBLESTONE, 28),
                Map.entry(Items.STICK, 20),
                Map.entry(Items.TORCH, 96),
                Map.entry(Items.COOKED_BEEF, 4),
                Map.entry(Items.CRAFTING_TABLE, 1));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                false, false, false, false, ignored -> true, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> mining = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE
                        || step.kind() == GoalStep.Kind.MINING_SERVICE)
                .toList();
        require(context, mining.size() == 3
                        && mining.get(0).kind() == GoalStep.Kind.MINE_ORE
                        && mining.get(0).count() == 1
                        && mining.get(1).kind() == GoalStep.Kind.MINING_SERVICE
                        && mining.get(1).isRareOreService()
                        && mining.get(1).count() == 56
                        && mining.get(1).rareOreMissionTarget() == 64
                        && mining.get(1).maintainsTunnelingTools()
                        && mining.get(2).kind() == GoalStep.Kind.MINE_ORE
                        && mining.get(2).count() == 8,
                "55/64 resume lost long-expedition identity: " + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "deep diamond resume emitted surface work: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void fourDeliveredDiamondsResumeToTheNextEightBoundary(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.DIAMOND, 4),
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.STONE_PICKAXE, 4),
                Map.entry(Items.COBBLESTONE, 28),
                Map.entry(Items.STICK, MiningBudget.DIAMOND_STACK_BOOTSTRAP_STICKS),
                Map.entry(Items.TORCH, MiningBudget.DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES),
                Map.entry(Items.COOKED_BEEF, 4),
                Map.entry(Items.CRAFTING_TABLE, 1));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                false, false, false, false, ignored -> true, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        List<GoalStep> mining = plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE
                        || step.kind() == GoalStep.Kind.MINING_SERVICE)
                .toList();
        List<GoalStep> mineSteps = mining.stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINE_ORE)
                .toList();
        List<GoalStep> services = mining.stream()
                .filter(step -> step.kind() == GoalStep.Kind.MINING_SERVICE)
                .toList();
        require(context, mining.size() == 15
                        && mining.getFirst().kind() == GoalStep.Kind.MINE_ORE
                        && mining.getFirst().count() == 4
                        && mineSteps.size() == 8
                        && mineSteps.getFirst().count() == 4
                        && mineSteps.subList(1, mineSteps.size()).stream()
                        .allMatch(step -> step.count() == 8)
                        && services.size() == 7
                        && services.getFirst().count() == 8
                        && services.getLast().count() == 56
                        && services.stream().allMatch(step -> step.isRareOreService()
                        && step.rareOreMissionTarget() == 64),
                "4/64 plan did not close the first logical batch before seven full batches: "
                        + plan.describeSteps());
        require(context, mineSteps.stream().mapToInt(GoalStep::count).sum() == 60
                        && mineSteps.getLast().count() == 8,
                "4/64 plan emitted a duplicate four-item tail: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void finalDiamondAtMineLayerServicesMissingChannelToolsBeforeOreDig(TestContext context) {
        Map<net.minecraft.item.Item, Integer> prepared = Map.ofEntries(
                Map.entry(Items.DIAMOND, 63),
                Map.entry(Items.IRON_PICKAXE, 3),
                Map.entry(Items.IRON_INGOT, 6),
                Map.entry(Items.COBBLESTONE, 40),
                Map.entry(Items.STICK, 12),
                Map.entry(Items.COOKED_BEEF, 4),
                Map.entry(Items.CRAFTING_TABLE, 1),
                Map.entry(Items.TORCH, 40));
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.HaveItem(Items.DIAMOND, 64), prepared, 64, -59,
                false, false, false, false, ignored -> true, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().size() == 1
                        && plan.steps().getFirst().kind() == GoalStep.Kind.MINE_ORE
                        && plan.steps().getFirst().count() == 1,
                "63/64 resume split its final open batch with a synthetic service: "
                        + plan.describeSteps());
        require(context, plan.steps().stream().noneMatch(
                        GoalPlannerMiningGameTests::isSurfaceAcquisitionStep),
                "deep 63/64 resume emitted surface work: " + plan.describeSteps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void emptyInventoryBootstrapIsNotMistakenForOptionalProvisioning(TestContext context) {
        GoalPlanner.GoalPlan plan = plan(new Goal.HaveItem(Items.DIAMOND, 64));
        int ironPickaxe = indexOf(plan, step -> step.kind() == GoalStep.Kind.CRAFT
                && step.item() == Items.IRON_PICKAXE);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, ironPickaxe >= 0, "missing iron-pickaxe bootstrap: " + plan.describeSteps());
        require(context, !plan.steps().get(ironPickaxe).bestEffort(),
                "required iron-pickaxe bootstrap was marked optional: " + plan.steps());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void directFoodGoalDoesNotInheritExpeditionBestEffortFlag(TestContext context) {
        GoalPlanner.GoalPlan plan = GoalPlanner.planFromState(null,
                new Goal.Food(4), Map.of(), 64, 64,
                true, false, false, ignored -> false, null);

        require(context, plan.success(), "unresolved=" + plan.unresolved());
        require(context, plan.steps().stream().noneMatch(GoalStep::bestEffort),
                "direct Goal.Food steps must not inherit mining expedition flags: " + plan.steps());
        require(context, plan.steps().stream().anyMatch(step -> step.kind() == GoalStep.Kind.COOK_FOOD),
                "direct Goal.Food must retain its required final cooking step: " + plan.steps());
        context.complete();
    }

    private static void requireDiamondExpeditionSequence(TestContext context,
                                                         GoalPlanner.GoalPlan plan,
                                                         int expectedBatches,
                                                         boolean expectTunnelingService) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < plan.steps().size(); i++) {
            GoalStep step = plan.steps().get(i);
            if (step.kind() == GoalStep.Kind.MINE_ORE && isDiamondStep(step)) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        require(context, first >= 0 && last >= first, "missing diamond expedition: " + plan.describeSteps());
        List<GoalStep> expedition = plan.steps().subList(first, last + 1);
        int expectedSteps = expectedBatches * 2 - 1;
        require(context, expedition.size() == expectedSteps,
                "expected " + expectedSteps + " alternating expedition steps, got " + expedition);

        for (int batch = 0; batch < expectedBatches; batch++) {
            GoalStep mine = expedition.get(batch * 2);
            require(context, mine.kind() == GoalStep.Kind.MINE_ORE && isDiamondStep(mine) && mine.count() == 8,
                    "batch " + batch + " must be MINE_ORE(8), got " + mine);
            if (batch + 1 < expectedBatches) {
                GoalStep service = expedition.get(batch * 2 + 1);
                int cumulative = (batch + 1) * 8;
                require(context, service.kind() == GoalStep.Kind.MINING_SERVICE
                                && isDiamondStep(service)
                                && service.count() == cumulative
                                && service.isRareOreService()
                                && service.rareOreMissionTarget() == expectedBatches * 8
                                && service.maintainsTunnelingTools() == expectTunnelingService,
                        "checkpoint after batch " + batch + " must be MINING_SERVICE(" + cumulative
                                + "), got " + service);
            }
        }
    }

    private static boolean isDiamondStep(GoalStep step) {
        return step.ores().contains(Blocks.DIAMOND_ORE)
                || step.ores().contains(Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    private static boolean isIronOreStep(GoalStep step) {
        return step.kind() == GoalStep.Kind.MINE_ORE
                && (step.ores().contains(Blocks.IRON_ORE)
                || step.ores().contains(Blocks.DEEPSLATE_IRON_ORE));
    }

    private static boolean isCoalOreStep(GoalStep step) {
        return step.kind() == GoalStep.Kind.MINE_ORE
                && (step.ores().contains(Blocks.COAL_ORE)
                || step.ores().contains(Blocks.DEEPSLATE_COAL_ORE));
    }

    private static boolean isSurfaceAcquisitionStep(GoalStep step) {
        return step.kind() == GoalStep.Kind.GATHER
                || step.kind() == GoalStep.Kind.HUNT
                || step.kind() == GoalStep.Kind.COOK_FOOD
                || step.kind() == GoalStep.Kind.FARM
                || step.kind() == GoalStep.Kind.MILK_COW;
    }

    private static int plannedDescentTorchUse(GoalPlanner.GoalPlan plan,
                                              int untilExclusive,
                                              int originY) {
        int plannedY = originY;
        int torches = 0;
        for (int index = 0; index < Math.min(untilExclusive, plan.steps().size()); index++) {
            GoalStep step = plan.steps().get(index);
            if (step.kind() == GoalStep.Kind.ACQUIRE_WATER) {
                plannedY = originY;
            } else if (step.kind() == GoalStep.Kind.DESCEND_TO_Y) {
                int depth = Math.max(0, plannedY - step.pos().getY());
                torches += depth == 0 ? 0 : (depth + 5) / 6;
                plannedY = step.pos().getY();
            }
        }
        return torches;
    }

    private static int roundUpToTorchRecipe(int target) {
        return ((Math.max(0, target) + 3) / 4) * 4;
    }

    private static int plannedLogGatherCount(GoalPlanner.GoalPlan plan) {
        return plan.steps().stream()
                .filter(step -> step.kind() == GoalStep.Kind.GATHER
                        && RecipeRegistry.LOGS.contains(step.item()))
                .mapToInt(GoalStep::count)
                .sum();
    }

    private static Map<Item, Integer> preparedDiamondContract(
            Map<Item, Integer> shelterWood) {
        Map<Item, Integer> prepared = new HashMap<>();
        prepared.put(Items.IRON_PICKAXE, 3);
        prepared.put(Items.IRON_INGOT, 6);
        prepared.put(Items.STONE_PICKAXE, 5);
        prepared.put(Items.CHEST, 1);
        prepared.put(Items.CRAFTING_TABLE, 1);
        prepared.put(Items.COBBLESTONE, 512);
        prepared.put(Items.STICK, 512);
        // Keep the planned torch-craft deficit at its pre-margin scale (~150) so the shelter
        // raw-log accounting these fixtures pin stays unchanged by the margin-funded pool.
        prepared.put(Items.TORCH, 512 + MiningBudget.DIAMOND_STACK_EPOCH_MARGIN
                * MiningBudget.RARE_BATCH_TORCH_LIMIT);
        prepared.put(Items.COOKED_BEEF, MiningBudget.RARE_BOOTSTRAP_FOOD);
        prepared.putAll(shelterWood);
        return Map.copyOf(prepared);
    }

    private static Map<Item, Integer> preparedObsidianContract(
            Map<Item, Integer> shelterWood) {
        Map<Item, Integer> prepared = new HashMap<>();
        prepared.put(Items.WATER_BUCKET, 1);
        prepared.put(Items.DIAMOND_PICKAXE, 1);
        prepared.put(Items.STONE_PICKAXE, 4);
        prepared.put(Items.STONE_SWORD, 1);
        prepared.put(Items.CRAFTING_TABLE, 1);
        prepared.put(Items.COBBLESTONE, 512);
        prepared.put(Items.STICK, 512);
        prepared.put(Items.COAL, 64);
        prepared.put(Items.TORCH, 512);
        prepared.put(Items.COOKED_BEEF, 64);
        prepared.putAll(shelterWood);
        return Map.copyOf(prepared);
    }

    private static int occupiedInventorySlots(
            Map<net.minecraft.item.Item, Integer> inventory) {
        return inventory.entrySet().stream()
                .mapToInt(entry -> {
                    int count = Math.max(0, entry.getValue());
                    int stackSize = Math.max(1, entry.getKey().getMaxCount());
                    return (count + stackSize - 1) / stackSize;
                })
                .sum();
    }

    private static GoalPlanner.GoalPlan plan(Goal goal) {
        return GoalPlanner.planFromState(null, goal, Map.of(), 64, 64,
                false, false, false, ignored -> false, null);
    }

    private static int indexOf(GoalPlanner.GoalPlan plan,
                               java.util.function.Predicate<GoalStep> predicate) {
        return indexOfFrom(plan, 0, predicate);
    }

    private static int indexOfFrom(GoalPlanner.GoalPlan plan,
                                   int from,
                                   java.util.function.Predicate<GoalStep> predicate) {
        for (int i = Math.max(0, from); i < plan.steps().size(); i++) {
            if (predicate.test(plan.steps().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(GoalPlanner.GoalPlan plan,
                                   java.util.function.Predicate<GoalStep> predicate) {
        for (int i = plan.steps().size() - 1; i >= 0; i--) {
            if (predicate.test(plan.steps().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
