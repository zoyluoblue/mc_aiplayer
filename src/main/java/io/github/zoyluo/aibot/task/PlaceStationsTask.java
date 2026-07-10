package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PLACE_STATIONS(Phase2 基建目标):把背包里的工作台 / 熔炉 / 箱子摆到 bot 周围的空地上,形成一个固定的生产+存储据点。
 *
 * 复用 {@link BuildAction#placeBlockAt}(与应急掩体同一放置原语):切手持 → 放在脚边可放置的空格(脚下实心、上方净空)。
 * 背包里缺哪件就跳过哪件(GoalPlanner 会先倒推备齐三件)。自包含状态机(G1),全程主线程(G2)。
 */
public final class PlaceStationsTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600;
    private static final List<Item> STATIONS = List.of(Items.CRAFTING_TABLE, Items.FURNACE, Items.CHEST);

    private final List<Item> pending = new ArrayList<>();
    private final Set<BlockPos> used = new HashSet<>();
    private final Set<BlockPos> placedPositions = new HashSet<>();
    private int placed;

    @Override
    public String name() {
        return "place_stations";
    }

    @Override
    public String describe() {
        return "Placing stations placed=" + placed + "/" + STATIONS.size();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return STATIONS.isEmpty() ? 0.0D : Math.min(0.95D, (double) placed / STATIONS.size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        pending.clear();
        pending.addAll(STATIONS);
        used.clear();
        placedPositions.clear();
        placed = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            if (placed > 0) {
                complete();
            } else {
                fail("place_stations_timeout");
            }
            return;
        }
        if (pending.isEmpty()) {
            complete();
            return;
        }
        Item station = pending.get(0);
        int slot = findSlot(bot, station);
        if (slot < 0) {
            pending.remove(0); // 背包没这件了 → 跳过
            return;
        }
        BlockPos spot = findFreeSpot(bot);
        if (spot == null) {
            fail("no_place_spot");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, slot) < 0) {
            pending.remove(0);
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, spot);
        used.add(spot);
        if (result.isSuccess()) {
            placed++;
            placedPositions.add(spot.toImmutable());
            pending.remove(0);
        }
    }

    // bot 周围 1~2 格、尚未用过、且"上方净空 + 脚下实心"的可放置空地。
    private BlockPos findFreeSpot(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        for (int r = 1; r <= 2; r++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos p = feet.offset(direction, r);
                if (used.contains(p)) {
                    continue;
                }
                if (world.getBlockState(p).isAir()
                        && world.getBlockState(p.up()).isAir()
                        && !world.getBlockState(p.down()).getCollisionShape(world, p.down()).isEmpty()) {
                    return p;
                }
            }
        }
        return null;
    }

    private static int findSlot(AIPlayerEntity bot, Item item) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (inventory.main.get(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    public Set<BlockPos> placedPositions() {
        return Set.copyOf(placedPositions);
    }
}
