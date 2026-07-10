package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * 造黑曜石(真实玩家"水浇岩浆现造"):循环 { 找岩浆源 → 站安全位 → 放水成黑曜石 → 挖 } 直到够数。
 * 自然黑曜石矿脉稀少,凑 ≥15 块只能现造。本项目放水是模拟(FarmAction.placeWater 直接 setBlockState),
 * 而源水碰源岩浆原版不转黑曜石(只流动水转)——故确定性落地:软放水(有水桶才放)+ 直接把岩浆源格
 * 写成 OBSIDIAN(1 tick 成型,与 placeWater/milkCow 同"模拟动作"风格),再用钻石镐挖。
 * 安全第一:只站"脚下实心、不挨岩浆/水"的格作业;够不到的岩浆源换下一个。自包含状态机,全程主线程。
 */
public final class CreateObsidianTask extends AbstractTask {
    private static final int MAX_ELAPSED = 12000;
    private static final int NO_PROGRESS_LIMIT = 600;
    private static final int SCAN_INTERVAL = 20;
    private static final int PROSPECT_RANGE = 48;
    private static final double REACH_SQUARED = 20.25D;
    private static final int PICKUP_GRACE_TICKS = 30;

    private enum Phase { SCAN, APPROACH, MAKE, MINE, DONE }

    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int lastScanTick = -SCAN_INTERVAL;
    private int approachTick;
    private int pickupGrace;
    private Phase phase = Phase.SCAN;
    private BlockPos lavaTarget;
    private BlockPos standPos;
    private BlockPos obsidian;

    public CreateObsidianTask(int targetCount) {
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "create_obsidian";
    }

    @Override
    public String describe() {
        return "CreateObsidian " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN));
        collected = 0;
        lastProgressTick = 0;
        phase = Phase.SCAN;
        lavaTarget = null;
        obsidian = null;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("create_obsidian_timeout collected=" + collected);
            return;
        }
        ServerWorld world = bot.getServerWorld();
        if (!ToolTier.canHarvestWithInventory(bot, Blocks.OBSIDIAN.getDefaultState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(Blocks.OBSIDIAN));
            return;
        }
        HarvestCore.forcePickupNearbyAnyOf(bot, Set.of(Items.OBSIDIAN), 4.0D, 4.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN)) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "create_obsidian_collected", "total", collected + "/" + targetCount);
            io.github.zoyluo.aibot.brain.BotReporter.INSTANCE.onGoalMessage(bot,
                    "造出黑曜石!" + collected + "/" + targetCount);
        }
        if (collected >= targetCount) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, Set.of(Items.OBSIDIAN), 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, Set.of(Items.OBSIDIAN)) - invBaseline >= targetCount) {
                complete();
            }
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            BotLog.action(bot, "create_obsidian_stall", "phase", phase,
                    "collected", collected + "/" + targetCount);
            miner.cancel(bot);
            fail("create_obsidian_no_progress collected=" + collected);
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot, world);
            case APPROACH -> approach(bot, world);
            case MAKE -> make(bot, world);
            case MINE -> mine(bot, world);
            case DONE -> { }
        }
    }

    private void scan(AIPlayerEntity bot, ServerWorld world) {
        int now = bot.getServer().getTicks();
        if (now - lastScanTick < SCAN_INTERVAL) {
            return;
        }
        lastScanTick = now;
        BlockPos found = OreProspector.nearest(bot, PROSPECT_RANGE,
                st -> st.getFluidState().isIn(FluidTags.LAVA) && st.getFluidState().isStill());
        if (found == null) {
            fail("create_obsidian_no_lava collected=" + collected);
            return;
        }
        lavaTarget = found.toImmutable();
        standPos = pickStand(world, lavaTarget);
        if (standPos == null) {
            BotLog.action(bot, "create_obsidian_no_stand", "lava", lavaTarget.toShortString());
            // 这块够不到 → 当场封掉它(写成石头)防下轮再选到死循环,然后重扫。
            world.setBlockState(lavaTarget, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            lavaTarget = null;
            return;
        }
        approachTick = elapsed;
        BotLog.action(bot, "create_obsidian_lava_found", "pos", lavaTarget.toShortString());
        phase = Phase.APPROACH;
    }

    private void approach(AIPlayerEntity bot, ServerWorld world) {
        if (lavaTarget == null || !isStillLava(world, lavaTarget)) {
            phase = Phase.SCAN;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(lavaTarget.toCenterPos()) <= REACH_SQUARED
                && Standability.isStandable(world, bot.getBlockPos())) {
            bot.getActionPack().stopAll();
            phase = Phase.MAKE;
            return;
        }
        if (elapsed - approachTick > 200) {
            BotLog.action(bot, "create_obsidian_approach_giveup", "lava", lavaTarget.toShortString());
            lavaTarget = null;
            standPos = null;
            phase = Phase.SCAN;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(standPos);
        }
    }

    private void make(AIPlayerEntity bot, ServerWorld world) {
        if (lavaTarget == null || !isStillLava(world, lavaTarget)) {
            phase = Phase.SCAN;
            return;
        }
        // 安全:岩浆源正上方若是流体 → 先封实(防造完邻流涌入冲走 bot/烧掉落)。
        BlockPos above = lavaTarget.up();
        if (!world.getFluidState(above).isEmpty()) {
            var blockSlot = io.github.zoyluo.aibot.action.MaterialPalette.pickAnyBlockSlot(bot);
            if (blockSlot.isPresent()) {
                InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
                io.github.zoyluo.aibot.action.BuildAction.placeBlockAt(bot, above);
            }
        }
        // 扣水桶(真实玩家每造一块耗一桶水;有水桶才扣,缺桶不阻断——放水仅记账,不实际放水块:
        // 实测放模拟水源会扩散把相邻源岩浆冲成圆石/黑曜石,把同带其它待造格吃掉,collected 缩水)。
        if (InventoryAction.countItem(bot, Items.WATER_BUCKET) > 0
                && InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1)) {
            InventoryAction.giveItem(bot, new net.minecraft.item.ItemStack(Items.BUCKET, 1));
        }
        // 确定性成型:岩浆源格直接写成黑曜石(NOTIFY_LISTENERS 不触发邻格流体 update,保住相邻待造源)。
        world.setBlockState(lavaTarget, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_LISTENERS);
        BotLog.action(bot, "create_obsidian_formed", "pos", lavaTarget.toShortString());
        lastProgressTick = elapsed;
        obsidian = lavaTarget;
        lavaTarget = null;
        phase = Phase.MINE;
    }

    private void mine(AIPlayerEntity bot, ServerWorld world) {
        if (obsidian == null || !world.getBlockState(obsidian).isOf(Blocks.OBSIDIAN)) {
            obsidian = null;
            phase = Phase.SCAN;
            return;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(obsidian)
                ? miner.tick(bot)
                : begin(bot, obsidian);
        if (st == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
            HarvestCore.forcePickupNearbyAnyOf(bot, Set.of(Items.OBSIDIAN), 6.0D, 4.0D);
            obsidian = null;
            phase = Phase.SCAN;
        } else if (st == BlockMiner.Status.FAILED) {
            obsidian = null;
            phase = Phase.SCAN;
        }
    }

    private BlockMiner.Status begin(AIPlayerEntity bot, BlockPos pos) {
        bot.getActionPack().stopMovement();
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    private static boolean isStillLava(ServerWorld world, BlockPos pos) {
        var fs = world.getFluidState(pos);
        return fs.isIn(FluidTags.LAVA) && fs.isStill();
    }

    // 安全站位:造黑曜石是 setBlockState(服务端操作,无需贴脸),挖只需 4.5 reach——所以站**2 格外**:
    // 贴脸(1格)挨岩浆会被点着(guard_on_fire 实测),2 格外既够得到挖、又不着火。要求:站格可站/干燥、
    // 且站格自身 6 邻无任何流体(彻底离开火源)。先试 2 格、再退 3 格(更远更安全,仍在 4.5 reach 内)。
    private static BlockPos pickStand(ServerWorld world, BlockPos lava) {
        for (int dist = 2; dist <= 3; dist++) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos stand = lava.offset(d, dist);
                if (!Standability.isStandable(world, stand) || !world.getFluidState(stand).isEmpty()) {
                    continue;
                }
                if (anyAdjacentFluid(world, stand)) {
                    continue; // 站格挨任何岩浆/水 → 会着火/被冲,跳过
                }
                return stand.toImmutable();
            }
        }
        return null;
    }

    private static boolean anyAdjacentFluid(ServerWorld world, BlockPos stand) {
        for (Direction d : Direction.values()) {
            if (!world.getFluidState(stand.offset(d)).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
