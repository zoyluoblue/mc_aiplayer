package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public final class CraftTask extends AbstractTask {
    private enum Phase {
        PLANNING,
        ENSURING_TABLE,
        CRAFTING
    }

    private final Item target;
    private final int targetCount;
    private Phase phase = Phase.PLANNING;
    private CraftingHelper.CraftPlan plan;
    private int nextStep;
    private int craftedCount;

    public CraftTask(Item target, int targetCount) {
        this.target = target;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String describe() {
        return "Crafting " + Registries.ITEM.getId(target) + " x" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (plan == null || plan.steps().isEmpty()) {
            return 0.0D;
        }
        return Math.min(0.95D, (double) nextStep / plan.steps().size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.PLANNING;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 400) {
            fail("craft_timeout");
            return;
        }
        switch (phase) {
            case PLANNING -> plan(bot);
            case ENSURING_TABLE -> ensureTable(bot);
            case CRAFTING -> craftNext(bot);
        }
    }

    private void plan(AIPlayerEntity bot) {
        // 幂等短路:工作台/熔炉这类功能方块,若附近已有(够得着)或背包已有,直接完成,不浪费材料重复制造。
        if (utilityAlreadyAvailable(bot)) {
            BotLog.action(bot, "craft_skipped_already_available", "item", Registries.ITEM.getId(target).toString());
            complete();
            return;
        }
        plan = CraftingHelper.plan(bot, target, targetCount);
        if (!plan.success()) {
            fail("need: " + plan.missingDescription());
            return;
        }
        if (plan.needsCraftingTable() && nearbyCraftingTable(bot) == null && InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isEmpty()) {
            CraftingHelper.CraftPlan tablePlan = CraftingHelper.plan(bot, Items.CRAFTING_TABLE, 1);
            if (!tablePlan.success()) {
                fail("need: minecraft:crafting_table x1 (" + tablePlan.missingDescription() + ")");
                return;
            }
            List<CraftingHelper.CraftStep> steps = new ArrayList<>(tablePlan.steps());
            steps.addAll(plan.steps());
            plan = new CraftingHelper.CraftPlan(target, targetCount, List.copyOf(steps), plan.missing(), true);
        }
        if (plan.steps().isEmpty()) {
            complete();
            return;
        }
        phase = Phase.CRAFTING;
    }

    private void ensureTable(AIPlayerEntity bot) {
        if (nearbyCraftingTable(bot) != null) {
            phase = Phase.CRAFTING;
            return;
        }
        OptionalInt tableSlot = InventoryAction.findItem(bot, Items.CRAFTING_TABLE);
        if (tableSlot.isEmpty()) {
            fail("need: minecraft:crafting_table x1");
            return;
        }
        BlockPos placePos = adjacentAir(bot);
        if (placePos == null) {
            fail("no_place_for_crafting_table");
            return;
        }
        InventoryAction.equipFromSlot(bot, tableSlot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, placePos);
        if (result.isFailed()) {
            fail("place_crafting_table_failed: " + result.reason());
        }
    }

    private void craftNext(AIPlayerEntity bot) {
        if (nextStep >= plan.steps().size()) {
            complete();
            return;
        }
        CraftingHelper.CraftStep step = plan.steps().get(nextStep);
        RecipeRegistry.Recipe recipe = step.recipe();
        // 工作台"持有即可":附近有工作台方块 或 背包里有工作台物品,都允许 3x3 合成(直接背包变换)。
        // 避免"放下→走远→找不到→重造"的循环,也不在世界里留一地工作台。
        if (recipe.needsCraftingTable()
                && nearbyCraftingTable(bot) == null
                && InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isEmpty()) {
            phase = Phase.ENSURING_TABLE;
            return;
        }
        PreparedCraft prepared = prepareCraft(bot, step);
        if (prepared.missingIngredient() != null) {
            fail("need: " + describeIngredient(
                    prepared.missingIngredient(), prepared.missingCount()));
            return;
        }
        if (prepared.availableOutput() < step.outputCount()) {
            fail("craft_output_capacity:item=" + Registries.ITEM.getId(recipe.output())
                    + ":count=" + step.outputCount()
                    + ":available=" + prepared.availableOutput());
            return;
        }
        commitPreparedCraft(bot, prepared);
        BotLog.action(bot, "craft_atomic",
                "item", Registries.ITEM.getId(recipe.output()).toString(),
                "count", step.outputCount());
        if (recipe.output() == target) {
            craftedCount += step.outputCount();
        }
        nextStep++;
    }

    /**
     * Builds the complete post-craft inventory on deep copies. The live inventory is not touched
     * until every ingredient has been consumed and the entire output has been proven insertable.
     */
    private static PreparedCraft prepareCraft(
            AIPlayerEntity bot, CraftingHelper.CraftStep step) {
        List<ItemStack> main = copyStacks(bot.getInventory().main);
        List<ItemStack> offHand = copyStacks(bot.getInventory().offHand);
        for (RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
            int required = ingredient.count() * step.crafts();
            if (!removeIngredient(main, offHand, ingredient, required)) {
                return new PreparedCraft(main, offHand, ingredient, required, 0);
            }
        }

        ItemStack output = new ItemStack(step.recipe().output(), step.outputCount());
        int available = outputCapacity(main, output);
        if (available >= output.getCount()) {
            insertEntireStack(main, output);
        }
        return new PreparedCraft(main, offHand, null, 0, available);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> source) {
        return source.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean removeIngredient(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient ingredient,
            int count) {
        int total = 0;
        for (Item item : ingredient.anyOf()) {
            total += countItem(main, offHand, item);
        }
        if (total < count) {
            return false;
        }
        int remaining = count;
        for (Item item : ingredient.anyOf()) {
            if (remaining <= 0) {
                return true;
            }
            for (List<ItemStack> region : List.of(main, offHand)) {
                for (ItemStack stack : region) {
                    if (remaining <= 0) {
                        return true;
                    }
                    if (!stack.isOf(item)) {
                        continue;
                    }
                    int take = Math.min(remaining, stack.getCount());
                    stack.decrement(take);
                    remaining -= take;
                }
            }
        }
        return remaining == 0;
    }

    private static int countItem(List<ItemStack> main, List<ItemStack> offHand, Item item) {
        int count = 0;
        for (List<ItemStack> region : List.of(main, offHand)) {
            for (ItemStack stack : region) {
                if (stack.isOf(item)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private static int outputCapacity(List<ItemStack> main, ItemStack output) {
        long available = 0L;
        for (ItemStack stack : main) {
            if (stack.isEmpty()) {
                available += output.getMaxCount();
            } else if (ItemStack.areItemsAndComponentsEqual(stack, output)) {
                available += Math.max(0, stack.getMaxCount() - stack.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, available);
    }

    private static void insertEntireStack(List<ItemStack> main, ItemStack output) {
        for (ItemStack stack : main) {
            if (output.isEmpty()) {
                return;
            }
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, output)) {
                int moved = Math.min(output.getCount(), stack.getMaxCount() - stack.getCount());
                if (moved > 0) {
                    stack.increment(moved);
                    output.decrement(moved);
                }
            }
        }
        for (int slot = 0; slot < main.size() && !output.isEmpty(); slot++) {
            if (!main.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(output.getCount(), output.getMaxCount());
            main.set(slot, output.copyWithCount(moved));
            output.decrement(moved);
        }
        if (!output.isEmpty()) {
            throw new IllegalStateException("craft output preflight capacity mismatch");
        }
    }

    private static void commitPreparedCraft(AIPlayerEntity bot, PreparedCraft prepared) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            inventory.main.set(slot, prepared.main().get(slot));
        }
        for (int slot = 0; slot < inventory.offHand.size(); slot++) {
            inventory.offHand.set(slot, prepared.offHand().get(slot));
        }
        inventory.markDirty();
    }

    private record PreparedCraft(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient missingIngredient,
            int missingCount,
            int availableOutput) {
    }

    private static BlockPos nearbyCraftingTable(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-8, -2, -8), origin.add(8, 3, 8))
                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(Blocks.CRAFTING_TABLE))
                .map(BlockPos::toImmutable)
                .findFirst()
                .orElse(null);
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = origin.offset(direction);
            if (bot.getServerWorld().getBlockState(candidate).isAir()) {
                return candidate.toImmutable();
            }
        }
        BlockPos above = origin.up();
        return bot.getServerWorld().getBlockState(above).isAir() ? above.toImmutable() : null;
    }

    private static String describeIngredient(RecipeRegistry.Ingredient ingredient, int count) {
        List<String> ids = ingredient.anyOf().stream()
                .map(item -> Registries.ITEM.getId(item).toString())
                .toList();
        return String.join("|", ids) + " x" + count;
    }

    /** 目标是工作台/熔炉时,附近已有(够得着)或背包已有则视为"已具备",无需重复制造。 */
    private boolean utilityAlreadyAvailable(AIPlayerEntity bot) {
        if (target == Items.CRAFTING_TABLE) {
            return nearbyCraftingTable(bot) != null
                    || InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isPresent();
        }
        if (target == Items.FURNACE) {
            return nearbyBlock(bot, Blocks.FURNACE) != null
                    || InventoryAction.findItem(bot, Items.FURNACE).isPresent();
        }
        return false;
    }

    private static BlockPos nearbyBlock(AIPlayerEntity bot, net.minecraft.block.Block block) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-8, -2, -8), origin.add(8, 3, 8))
                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(block))
                .map(BlockPos::toImmutable)
                .findFirst()
                .orElse(null);
    }
}
