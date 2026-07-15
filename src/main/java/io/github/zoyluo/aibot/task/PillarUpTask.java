package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.OptionalInt;

/**
 * Tower-style vertical scaffold. The bot is pinned to one X/Z column, jumps only after landing,
 * then uses an exact server-side placement at the next floor cell. No placement is inferred from
 * camera direction, and a partial tower is never reported as complete.
 */
public final class PillarUpTask extends AbstractTask {
    private static final int MAX_ELAPSED = 1200;
    private static final int NO_PROGRESS_LIMIT = 100;
    private static final int PLACE_FAIL_LIMIT = 8;
    private static final int JUMP_COOLDOWN_TICKS = 4;

    private final int height;
    private int baseY;
    private int columnX;
    private int columnZ;
    private int lastRisen;
    private int nextPlacementY;
    private int lastProgressElapsed;
    private int placeRetry;
    private int lastJumpElapsed;

    public PillarUpTask(int height) {
        this.height = Math.max(1, Math.min(32, height));
    }

    @Override
    public String name() {
        return "pillar_up";
    }

    @Override
    public String describe() {
        return "Tower " + lastRisen + "/" + height;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.99D, lastRisen / (double) height);
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        baseY = bot.getBlockY();
        columnX = bot.getBlockX();
        columnZ = bot.getBlockZ();
        lastRisen = 0;
        nextPlacementY = baseY;
        lastProgressElapsed = 0;
        placeRetry = 0;
        lastJumpElapsed = -JUMP_COOLDOWN_TICKS;
        bot.getActionPack().stopMovement();
        centerOnTowerColumn(bot);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("tower_timeout: risen=" + lastRisen + "/" + height);
            return;
        }
        if (elapsed - lastProgressElapsed > NO_PROGRESS_LIMIT) {
            fail("tower_stuck: risen=" + lastRisen + "/" + height);
            return;
        }

        ServerWorld world = bot.getServerWorld();
        if (!inTowerColumn(bot)) {
            fail("tower_drifted_from_column");
            return;
        }

        int risen = bot.getBlockY() - baseY;
        if (bot.isOnGround() && risen > lastRisen) {
            lastRisen = risen;
            nextPlacementY = baseY + lastRisen;
            lastProgressElapsed = elapsed;
            placeRetry = 0;
        }
        if (bot.isOnGround() && lastRisen >= height) {
            reset(bot);
            complete();
            return;
        }

        BlockPos nextFloor = new BlockPos(columnX, nextPlacementY, columnZ);
        if (!world.getBlockState(nextFloor.up()).isReplaceable()
                || !world.getBlockState(nextFloor.up(2)).isReplaceable()) {
            fail("tower_ceiling_at=" + nextFloor.up().toShortString());
            return;
        }

        if (bot.isOnGround()) {
            // Landing centered on the same column is required before the next tower cycle.
            centerOnTowerColumn(bot);
            BlockPos currentFloor = new BlockPos(columnX, bot.getBlockY() - 1, columnZ);
            if (!isSupportingFloor(world, currentFloor)) {
                fail("tower_missing_floor_at=" + currentFloor.toShortString());
                return;
            }
            OptionalInt slot = BridgeTask.fillerSlot(bot);
            if (slot.isEmpty()) {
                fail("tower_no_blocks");
                return;
            }
            InventoryAction.equipFromSlot(bot, slot.getAsInt());
            bot.getActionPack().setForward(0.0F);
            if (elapsed - lastJumpElapsed >= JUMP_COOLDOWN_TICKS) {
                lastJumpElapsed = elapsed;
                bot.getActionPack().jumpOnce();
            }
            return;
        }

        // The bottom of the entity must be above the target block before filling it. This prevents
        // placing inside the player and ensures each jump can produce at most one new floor cell.
        if (bot.getY() < nextPlacementY + 1.0D - 0.01D) {
            return;
        }
        if (isSupportingFloor(world, nextFloor)) {
            return; // Already placed this cycle; wait to land and confirm the new Y level.
        }
        OptionalInt slot = BridgeTask.fillerSlot(bot);
        if (slot.isEmpty()) {
            fail("tower_no_blocks");
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAtExactly(bot, nextFloor);
        if (result.isSuccess() && isSupportingFloor(world, nextFloor)) {
            placeRetry = 0;
            return;
        }
        if (++placeRetry > PLACE_FAIL_LIMIT) {
            fail("tower_exact_place_failed: " + result.reason());
        }
    }

    private boolean inTowerColumn(AIPlayerEntity bot) {
        return Math.abs(bot.getX() - (columnX + 0.5D)) <= 0.45D
                && Math.abs(bot.getZ() - (columnZ + 0.5D)) <= 0.45D;
    }

    private void centerOnTowerColumn(AIPlayerEntity bot) {
        if (Math.abs(bot.getX() - (columnX + 0.5D)) <= 0.02D
                && Math.abs(bot.getZ() - (columnZ + 0.5D)) <= 0.02D) {
            return;
        }
        bot.teleport(bot.getServerWorld(), columnX + 0.5D, bot.getY(), columnZ + 0.5D,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
    }

    private static boolean isSupportingFloor(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return !state.isReplaceable() && !state.getCollisionShape(world, pos).isEmpty();
    }

    private void reset(AIPlayerEntity bot) {
        bot.getActionPack().stopMovement();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        reset(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        reset(bot);
        bot.getActionPack().stopAll();
    }
}
