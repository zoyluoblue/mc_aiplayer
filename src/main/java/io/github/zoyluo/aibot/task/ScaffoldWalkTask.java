package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashSet;
import java.util.OptionalInt;

/**
 * Scaffold 走路:朝目标自由角度直线行走,走到哪脚下自动生成方块到哪(观感=辅助客户端的
 * scaffold/自动搭路)。这是大脑唯一可见的水平铺路能力:支持固定方向、坐标、空间标记、
 * 自由角度与移动玩家目标,无视水面/沟壑/虚空。
 *
 * 安全不变式(防掉水):每 tick 先检查"当前脚下 + 约 0.9 格前落点脚下(斜向时加两邻轴格)"
 * 这组支撑格,任何一格可替换(空气/水)就钉住不动、先补块,全部实心才迈步。
 * 走速 ~0.22 格/tick、探测 0.9 格 ≈ 4 tick 提前量,钉住铺块期间零位移。
 */
public final class ScaffoldWalkTask extends AbstractTask {
    private static final int MAX_ELAPSED = 3000;      // ~150s
    private static final int NO_PROGRESS_LIMIT = 100; // 5s 没挪窝也没铺块 → 卡死
    private static final int PLACE_FAIL_LIMIT = 16;
    private static final double ARRIVE_DIST_SQ = 2.25D; // 水平 1.5 格内算到

    private final String playerName; // 非空=活体目标,每 tick 刷新坐标(可追移动中的主人)
    private int targetX;
    private int targetZ;
    private final int maxBlocks;

    private int placed;
    private int placeRetry;
    private int placeDelayTicks;
    private int lastProgressElapsed;
    private BlockPos lastFeet;

    /** 朝玩家(可移动)scaffold 走过去。 */
    public ScaffoldWalkTask(String playerName, BlockPos initial, int maxBlocks) {
        this.playerName = playerName;
        this.targetX = initial.getX();
        this.targetZ = initial.getZ();
        this.maxBlocks = Math.max(1, Math.min(512, maxBlocks));
    }

    /** 朝固定水平坐标 scaffold 走过去。 */
    public ScaffoldWalkTask(int x, int z, int maxBlocks) {
        this.playerName = null;
        this.targetX = x;
        this.targetZ = z;
        this.maxBlocks = Math.max(1, Math.min(512, maxBlocks));
    }

    @Override
    public String name() {
        return "scaffold_walk";
    }

    @Override
    public String describe() {
        return "scaffold_walk -> " + targetX + "," + targetZ + " placed=" + placed;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, placed / (double) maxBlocks);
    }

    @Override
    public boolean isWaiting() {
        // 钉住铺块的间歇位置几 tick 不变属正常;卡死由 NO_PROGRESS_LIMIT 自兜,不劳 StuckWatcher。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        placed = 0;
        placeRetry = 0;
        placeDelayTicks = 0;
        lastProgressElapsed = 0;
        lastFeet = bot.getBlockPos();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            failWithProgress(bot, "scaffold_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        // 活体目标:玩家在线且同维度就刷新坐标;下线/换维度沿用最后已知点。
        if (playerName != null) {
            ServerPlayerEntity target = bot.getServer().getPlayerManager().getPlayer(playerName);
            if (target != null && target.getServerWorld() == bot.getServerWorld()) {
                targetX = target.getBlockPos().getX();
                targetZ = target.getBlockPos().getZ();
            }
        }

        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        if (!feet.equals(lastFeet)) {
            lastFeet = feet;
            lastProgressElapsed = elapsed;
        }
        if (elapsed - lastProgressElapsed > NO_PROGRESS_LIMIT) {
            failWithProgress(bot, "scaffold_stuck");
            return;
        }

        double dx = targetX + 0.5D - bot.getX();
        double dz = targetZ + 0.5D - bot.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq <= ARRIVE_DIST_SQ) {
            bot.getActionPack().setForward(0.0F);
            complete();
            return;
        }
        if (placed >= maxBlocks) {
            failWithProgress(bot, "scaffold_budget_exhausted");
            return;
        }
        double dist = Math.sqrt(distSq);
        double dirX = dx / dist;
        double dirZ = dz / dist;

        // 落水自救:边跳边由下面的支撑格逻辑往脚下补块,几 tick 内顶出水面。
        if (bot.isTouchingWater()) {
            bot.getActionPack().jumpOnce();
        }

        // 支撑格:当前脚下 + 前落点脚下;斜向跨格时补两邻轴格(防切角瞬间两脚悬空掉缝)。
        Vec3d pos = bot.getPos();
        BlockPos aheadFeet = BlockPos.ofFloored(pos.x + dirX * 0.9D, pos.y, pos.z + dirZ * 0.9D);
        LinkedHashSet<BlockPos> supports = new LinkedHashSet<>(4);
        supports.add(feet.down());
        supports.add(aheadFeet.down());
        if (aheadFeet.getX() != feet.getX() && aheadFeet.getZ() != feet.getZ()) {
            supports.add(new BlockPos(aheadFeet.getX(), feet.getY() - 1, feet.getZ()));
            supports.add(new BlockPos(feet.getX(), feet.getY() - 1, aheadFeet.getZ()));
        }
        for (BlockPos support : supports) {
            var supportState = world.getBlockState(support);
            if (supportState.isReplaceable()) {
                placeFloor(bot, world, support);
                return; // 一 tick 铺一块,铺完下 tick 复查;期间钉住零位移
            }
            if (supportState.getCollisionShape(world, support).isEmpty()) {
                failWithProgress(bot, "scaffold_non_solid_support: " + support.toShortString());
                return;
            }
        }

        // 前方脚高被实心挡:一格台阶跳上去;两格高墙=到岸(不挖山,挖山交给别的工具)。
        BlockPos frontCell = BlockPos.ofFloored(pos.x + dirX * 1.1D, pos.y, pos.z + dirZ * 1.1D);
        if (!frontCell.equals(feet)
                && !(world.getBlockState(frontCell).isReplaceable() && world.getBlockState(frontCell.up()).isReplaceable())) {
            if (Standability.isStandable(world, frontCell.up())) {
                LookAction.lookAt(bot, frontCell.up().toCenterPos());
                bot.getActionPack().setForward(1.0F);
                bot.getActionPack().jumpOnce();
                return;
            }
            bot.getActionPack().setForward(0.0F);
            failWithProgress(bot, "scaffold_blocked_by_terrain");
            return;
        }

        // 支撑齐全、前方开阔 → 直视目标自由角度迈步(不冲刺:防冲过探测提前量)。
        LookAction.lookAt(bot, new Vec3d(targetX + 0.5D, bot.getEyeY(), targetZ + 0.5D));
        bot.getActionPack().setSneaking(false);
        bot.getActionPack().setSprinting(false);
        bot.getActionPack().setForward(1.0F);
    }

    private void placeFloor(AIPlayerEntity bot, ServerWorld world, BlockPos floor) {
        if (!world.getBlockState(floor).isReplaceable()) {
            return;
        }
        OptionalInt slot = MaterialPalette.pickScaffoldBlockSlot(bot);
        if (slot.isEmpty()) {
            failWithProgress(bot, "scaffold_no_blocks: 背包没有可铺方块(木板/圆石/泥土)");
            return;
        }
        bot.getActionPack().setForward(0.0F); // 钉住,绝不边走边铺
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAtBlock(bot, floor, net.minecraft.util.math.Direction.UP);
        ActionResult result = BuildAction.placeBlockAt(bot, floor);
        if (result.isSuccess() && isSupportingFloor(world, floor)) {
            placed++;
            placeRetry = 0;
            placeDelayTicks = 2;
            lastProgressElapsed = elapsed;
            return;
        }
        placeRetry++;
        if (placeRetry > PLACE_FAIL_LIMIT) {
            failWithProgress(bot, "scaffold_place_failed: " + result.reason());
        }
    }

    private void failWithProgress(AIPlayerEntity bot, String reason) {
        bot.getActionPack().setForward(0.0F);
        double remaining = Math.sqrt(Math.pow(targetX + 0.5D - bot.getX(), 2.0D)
                + Math.pow(targetZ + 0.5D - bot.getZ(), 2.0D));
        fail(reason + ": placed=" + placed + ", remaining="
                + String.format(java.util.Locale.ROOT, "%.1f", remaining));
    }

    private static boolean isSupportingFloor(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return !state.isReplaceable() && !state.getCollisionShape(world, pos).isEmpty();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        bot.getActionPack().setForward(0.0F);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().setForward(0.0F);
        bot.getActionPack().stopAll();
    }
}
