package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.registry.Registries;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * 骑乘:走到最近的船/矿车/(已驯服的)马旁边坐上去。直播画面感强("上船跟主人划船"),
 * 也是真人高频操作。坐上即完成——之后 bot 不自己驾驶(船/矿车本来就随流/随轨),
 * 想下来用 dismount 工具。未驯服的马会把 bot 掀下来,先 tame。
 */
public final class RideTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600; // ~30s
    private static final double SEARCH = 16.0D;
    private static final double MOUNT_RANGE = 2.8D;

    private final String wanted; // boat / minecart / horse / 空=任意可骑

    public RideTask(String wanted) {
        this.wanted = normalize(wanted);
    }

    private static String normalize(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (v.contains("boat") || v.contains("raft") || v.contains("船")) {
            return "boat";
        }
        if (v.contains("cart") || v.contains("矿车")) {
            return "minecart";
        }
        if (v.contains("horse") || v.contains("donkey") || v.contains("mule") || v.contains("马") || v.contains("驴")) {
            return "horse";
        }
        return "";
    }

    @Override
    public String name() {
        return "ride";
    }

    @Override
    public String describe() {
        return "Riding " + (wanted.isBlank() ? "any" : wanted);
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.9D, elapsed / (double) MAX_ELAPSED);
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        bot.getActionPack().stopMovement();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.hasVehicle()) {
            bot.getActionPack().stopMovement();
            complete();
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            fail("ride_timeout");
            return;
        }
        Optional<Entity> target = nearestRideable(bot);
        if (target.isEmpty()) {
            fail("no_rideable_nearby: " + (wanted.isBlank() ? "boat/minecart/horse" : wanted));
            return;
        }
        Entity vehicle = target.get();
        if (bot.distanceTo(vehicle) <= MOUNT_RANGE) {
            bot.getActionPack().stopMovement();
            if (!bot.startRiding(vehicle, true)) {
                fail("mount_rejected");
            }
            return; // 成功与否下 tick 由 hasVehicle 判定收尾
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(vehicle.getBlockPos());
        }
    }

    private Optional<Entity> nearestRideable(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getOtherEntities(bot, bot.getBoundingBox().expand(SEARCH), this::matches)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    private boolean matches(Entity entity) {
        if (!entity.isAlive() || entity.hasPassengers()) {
            return false;
        }
        String path = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
        boolean isBoat = path.contains("boat") || path.contains("raft");
        boolean isCart = path.equals("minecart"); // 只坐空载客矿车,不坐漏斗/箱子矿车
        boolean isHorse = entity instanceof AbstractHorseEntity horse && horse.isTame();
        return switch (wanted) {
            case "boat" -> isBoat;
            case "minecart" -> isCart;
            case "horse" -> isHorse;
            default -> isBoat || isCart || isHorse;
        };
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
