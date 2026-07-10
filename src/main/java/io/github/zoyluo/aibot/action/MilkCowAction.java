package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;

import java.util.Comparator;

/** 挤奶:用一个空桶在最近的成年牛身上挤一桶奶。简化:不模拟右键交互,直接换物 + 日志(与 till/placeWater 同风格)。 */
public final class MilkCowAction {
    public static final double REACH = 4.0D;

    private MilkCowAction() {
    }

    public static CowEntity nearestCow(AIPlayerEntity bot, double radius) {
        ServerWorld world = bot.getServerWorld();
        return world.getEntitiesByClass(CowEntity.class, bot.getBoundingBox().expand(radius),
                        cow -> cow.isAlive() && !cow.isBaby())
                .stream()
                .filter(cow -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, cow))
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
    }

    public static ActionResult milk(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            return ActionResult.failed("missing_bucket");
        }
        CowEntity cow = nearestCow(bot, REACH);
        if (cow == null) {
            return ActionResult.failed("no_cow_in_range");
        }
        if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
            return ActionResult.failed("missing_bucket");
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.MILK_BUCKET, 1));
        BotLog.action(bot, "milk_cow", "pos", cow.getBlockPos());
        return ActionResult.SUCCESS;
    }
}
