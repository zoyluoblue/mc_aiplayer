package io.github.zoyluo.aibot.craft;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.List;

/**
 * 瞬时合成:CraftingHelper.plan 校验通过后直接扣材料、给产物——不走任务状态机、
 * 不找/不放工作台、不走路。直播工具的取舍:模型一次调用一轮出结果,观感上
 * "想合成就合成",省掉 plan_craft→craft→get_task_status 的多轮空转。
 * 步骤顺序由 planner 保证依赖在前(先木板再木棍再镐),执行时中间产物已在背包。
 * 主线程调用(工具执行经 mc 主线程分发)。
 */
public final class InstantCrafter {
    private InstantCrafter() {
    }

    /** 成功返回 "crafted: <id> xN";缺基础材料返回 "need: <id> xN, …"(一轮告知,模型直接去补)。 */
    public static String craft(AIPlayerEntity bot, Item target, int count) {
        int want = Math.max(1, count);
        CraftingHelper.CraftPlan plan = CraftingHelper.plan(bot, target, want);
        if (!plan.success()) {
            return "need: " + plan.missingDescription();
        }
        for (CraftingHelper.CraftStep step : plan.steps()) {
            for (RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
                take(bot, ingredient.anyOf(), ingredient.count() * step.crafts());
            }
            give(bot, step.recipe().output(), step.recipe().outputCount() * step.crafts());
        }
        return "crafted: " + Registries.ITEM.getId(target) + " x" + want;
    }

    /** 从背包(主+副手)扣走 anyOf 中任意组合共 need 个。plan 已验证总量足够,这里只做扣除。 */
    private static void take(AIPlayerEntity bot, List<Item> anyOf, int need) {
        PlayerInventory inventory = bot.getInventory();
        int remaining = need;
        for (int slot = 0; slot < inventory.main.size() && remaining > 0; slot++) {
            remaining -= shrink(inventory.main.get(slot), anyOf, remaining);
        }
        for (int slot = 0; slot < inventory.offHand.size() && remaining > 0; slot++) {
            remaining -= shrink(inventory.offHand.get(slot), anyOf, remaining);
        }
    }

    private static int shrink(ItemStack stack, List<Item> anyOf, int remaining) {
        if (stack.isEmpty() || !anyOf.contains(stack.getItem())) {
            return 0;
        }
        int take = Math.min(stack.getCount(), remaining);
        stack.decrement(take);
        return take;
    }

    /** 按最大堆叠拆分入包,塞不下的 offerOrDrop 掉在脚下。 */
    private static void give(AIPlayerEntity bot, Item item, int total) {
        int remaining = total;
        while (remaining > 0) {
            int amount = Math.min(remaining, Math.max(1, item.getMaxCount()));
            bot.getInventory().offerOrDrop(new ItemStack(item, amount));
            remaining -= amount;
        }
    }
}
