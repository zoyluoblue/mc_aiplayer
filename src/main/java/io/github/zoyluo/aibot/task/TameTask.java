package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;

import java.util.Comparator;
import java.util.Locale;

/**
 * 驯服宠物:狼(骨头)/猫(生鳕鱼·生鲑鱼)/鹦鹉(小麦种子)喂食驯服;马/驴/骡反复骑上去磨到驯服。
 * 直播养宠物话题性极强("给DeepSeek养条狗")。原版机制是概率驯服——喂到成功为止,
 * 缺料时干净失败并报要什么。驯服归属:原版把 owner 设为交互者=bot 本人。
 */
public final class TameTask extends AbstractTask {
    private static final int MAX_ELAPSED = 1600; // ~80s(概率驯服需要多次喂)
    private static final double SEARCH = 24.0D;
    private static final double FEED_RANGE = 3.0D;
    private static final int FEED_INTERVAL = 12;

    private final String animal; // wolf / cat / parrot / horse
    private final Item food;     // null = 骑乘驯服(马系)
    private int lastFeedTick;

    public TameTask(String animal) {
        String v = animal == null ? "" : animal.trim().toLowerCase(Locale.ROOT);
        if (v.contains("wolf") || v.contains("dog") || v.contains("狼") || v.contains("狗")) {
            this.animal = "wolf";
            this.food = Items.BONE;
        } else if (v.contains("cat") || v.contains("猫")) {
            this.animal = "cat";
            this.food = Items.COD;
        } else if (v.contains("parrot") || v.contains("鹦鹉")) {
            this.animal = "parrot";
            this.food = Items.WHEAT_SEEDS;
        } else if (v.contains("horse") || v.contains("donkey") || v.contains("mule")
                || v.contains("马") || v.contains("驴") || v.contains("骡")) {
            this.animal = "horse";
            this.food = null;
        } else {
            this.animal = "";
            this.food = null;
        }
    }

    @Override
    public String name() {
        return "tame";
    }

    @Override
    public String describe() {
        return "Taming " + animal;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / (double) MAX_ELAPSED);
    }

    @Override
    public boolean isWaiting() {
        return true; // 喂食/骑乘期间站桩属正常
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        lastFeedTick = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (animal.isBlank()) {
            fail("unsupported_animal: 只会驯 wolf/cat/parrot/horse");
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            abandonMount(bot);
            fail("tame_timeout");
            return;
        }
        // 马系:正骑着 → 等原版好感度磨满
        if (bot.hasVehicle()) {
            Entity vehicle = bot.getVehicle();
            if (vehicle instanceof AbstractHorseEntity horse && horse.isTame()) {
                bot.stopRiding();
                complete();
            }
            return;
        }
        LivingEntity target = nearestUntamed(bot);
        if (target == null) {
            fail("no_untamed_" + animal + "_nearby");
            return;
        }
        if (food != null && InventoryAction.countItem(bot, food) <= 0) {
            fail("need_food: 驯" + animal + "要 " + Registries.ITEM.getId(food).getPath()
                    + ",背包里没有——先弄到再来");
            return;
        }
        if (bot.getEyePos().distanceTo(target.getEyePos()) <= FEED_RANGE) {
            bot.getActionPack().stopMovement();
            LookAction.lookAt(bot, target.getEyePos());
            if (elapsed - lastFeedTick < FEED_INTERVAL) {
                return; // 原版驯服有概率,喂太密浪费材料
            }
            lastFeedTick = elapsed;
            if (food != null) {
                InventoryAction.findItem(bot, food).ifPresent(slot -> InventoryAction.equipFromSlot(bot, slot));
            } else {
                // 骑乘驯服:空手上马(手里有东西可能变成喂食/装鞍)
                int empty = InventoryAction.firstEmptyHotbar(bot.getInventory());
                if (empty >= 0) {
                    InventoryAction.selectHotbar(bot, empty);
                }
            }
            ActionResult result = InteractAction.useItemOnEntity(bot, target, Hand.MAIN_HAND);
            bot.swingHand(Hand.MAIN_HAND);
            if (result.isSuccess() && isTamed(target)) {
                complete();
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(target.getBlockPos());
        }
    }

    private LivingEntity nearestUntamed(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH), this::matches)
                .stream()
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
    }

    private boolean matches(LivingEntity entity) {
        if (!entity.isAlive() || isTamed(entity)) {
            return false;
        }
        String path = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
        return switch (animal) {
            case "wolf" -> path.equals("wolf");
            // 1.21.3 豹猫(ocelot)不可驯服(喂鱼只加 trusting 不设 owner),绝不能当猫目标,否则喂到超时
            case "cat" -> path.equals("cat");
            case "parrot" -> path.equals("parrot");
            // 幼马 interactMob 走育种分支骑不上去,永远驯不了,排除
            case "horse" -> !entity.isBaby()
                    && (path.equals("horse") || path.equals("donkey") || path.equals("mule"));
            default -> false;
        };
    }

    private static boolean isTamed(Entity entity) {
        if (entity instanceof TameableEntity tameable) {
            return tameable.isTamed();
        }
        if (entity instanceof AbstractHorseEntity horse) {
            return horse.isTame();
        }
        return false;
    }

    private static void abandonMount(AIPlayerEntity bot) {
        if (bot.hasVehicle()) {
            bot.stopRiding();
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        abandonMount(bot);
        bot.getActionPack().stopAll(); // stopMovement 清不掉 pathExecutor,被抢占时会被旧路径拖着走
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        abandonMount(bot);
        bot.getActionPack().stopAll();
    }
}
