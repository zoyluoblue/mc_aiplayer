package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mixin.MerchantEntityInvokerMixin;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Box;
import net.minecraft.village.TradeOffer;

import java.util.Comparator;
import java.lang.reflect.Method;
import java.util.Optional;

public final class TradeTask extends AbstractTask {
    private enum Phase {
        FIND_VILLAGER,
        MOVE_TO_VILLAGER,
        TRADE
    }

    private static final double SEARCH_RANGE = 16.0D;
    private static final double TRADE_RANGE = 4.0D;

    private final Item targetItem;
    private final int maxDistance;

    private Phase phase = Phase.FIND_VILLAGER;
    private VillagerEntity villager;
    private int phaseTicks;

    public TradeTask(Item targetItem, int maxDistance) {
        this.targetItem = targetItem;
        this.maxDistance = Math.max(4, maxDistance);
    }

    @Override
    public String name() {
        return "trade";
    }

    @Override
    public String describe() {
        return targetItem == null
                ? "Trading with nearby villager"
                : "Trading for " + Registries.ITEM.getId(targetItem);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_VILLAGER -> 0.1D;
            case MOVE_TO_VILLAGER -> 0.45D;
            case TRADE -> 0.8D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_VILLAGER;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            fail("trade_timeout");
            return;
        }
        switch (phase) {
            case FIND_VILLAGER -> findVillager(bot);
            case MOVE_TO_VILLAGER -> moveToVillager(bot);
            case TRADE -> trade(bot);
        }
    }

    private void findVillager(AIPlayerEntity bot) {
        villager = nearestVillager(bot).orElse(null);
        if (villager == null) {
            fail("no_villager_nearby");
            return;
        }
        if (bot.distanceTo(villager) <= TRADE_RANGE) {
            bot.getActionPack().stopAll();
            transition(Phase.TRADE);
            return;
        }
        bot.getActionPack().startPathTo(villager.getBlockPos());
        transition(Phase.MOVE_TO_VILLAGER);
    }

    private void moveToVillager(AIPlayerEntity bot) {
        if (villager == null || !villager.isAlive()) {
            transition(Phase.FIND_VILLAGER);
            return;
        }
        LookAction.lookAt(bot, villager.getPos().add(0.0D, villager.getHeight() * 0.5D, 0.0D));
        if (bot.distanceTo(villager) <= TRADE_RANGE) {
            bot.getActionPack().stopAll();
            transition(Phase.TRADE);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && phaseTicks > 20) {
            bot.getActionPack().startPathTo(villager.getBlockPos());
        }
        phaseTicks++;
    }

    private void trade(AIPlayerEntity bot) {
        if (villager == null || !villager.isAlive()) {
            fail("villager_lost");
            return;
        }
        LookAction.lookAt(bot, villager.getPos().add(0.0D, villager.getHeight() * 0.5D, 0.0D));
        TradeOffer offer = selectOffer(bot).orElse(null);
        if (offer == null) {
            fail("no_affordable_offer");
            return;
        }
        ItemStack firstBuy = offer.getDisplayedFirstBuyItem();
        ItemStack sell = offer.copySellItem();
        if (!canFit(bot, sell)) {
            fail("inventory_full");
            return;
        }
        if (!InventoryAction.removeItems(bot, firstBuy.getItem(), firstBuy.getCount())) {
            fail("missing_buy_item");
            return;
        }
        ActionResult give = InventoryAction.giveItem(bot, sell.copy());
        if (give.isFailed()) {
            fail("give_failed:" + give.reason());
            return;
        }
        offer.use();
        if (!afterUsing(villager, offer)) {
            fail("after_using_failed");
            return;
        }
        complete();
    }

    private Optional<VillagerEntity> nearestVillager(AIPlayerEntity bot) {
        double range = Math.min(maxDistance, SEARCH_RANGE);
        Box box = bot.getBoundingBox().expand(range);
        return bot.getServerWorld()
                .getEntitiesByClass(VillagerEntity.class, box, entity -> entity.isAlive() && !entity.isBaby())
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    private Optional<TradeOffer> selectOffer(AIPlayerEntity bot) {
        return villager.getOffers().stream()
                .filter(offer -> !offer.isDisabled())
                .filter(this::isSimpleOneInputOffer)
                .filter(offer -> targetItem == null || offer.getSellItem().isOf(targetItem))
                .filter(offer -> canAfford(bot, offer))
                .findFirst();
    }

    private boolean isSimpleOneInputOffer(TradeOffer offer) {
        return offer.getDisplayedSecondBuyItem().isEmpty();
    }

    private boolean canAfford(AIPlayerEntity bot, TradeOffer offer) {
        ItemStack firstBuy = offer.getDisplayedFirstBuyItem();
        return !firstBuy.isEmpty()
                && InventoryAction.countItem(bot, firstBuy.getItem()) >= firstBuy.getCount();
    }

    private boolean canFit(AIPlayerEntity bot, ItemStack output) {
        PlayerInventory inventory = bot.getInventory();
        for (ItemStack stack : inventory.main) {
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.isOf(output.getItem()) && stack.getCount() < Math.min(stack.getMaxCount(), output.getMaxCount())) {
                return true;
            }
        }
        return false;
    }

    private boolean afterUsing(VillagerEntity villager, TradeOffer offer) {
        try {
            ((MerchantEntityInvokerMixin) villager).aibot$invokeAfterUsing(offer);
            return true;
        } catch (LinkageError | RuntimeException ignored) {
            // Keep the reflection path as a runtime fallback for loader or mapping edge cases.
        }
        for (String methodName : new String[]{"afterUsing", "method_18008"}) {
            Class<?> type = villager.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName, TradeOffer.class);
                    method.setAccessible(true);
                    method.invoke(villager, offer);
                    return true;
                } catch (ReflectiveOperationException ignored) {
                    type = type.getSuperclass();
                }
            }
        }
        return false;
    }

    private void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }
}
