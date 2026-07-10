package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * DESCEND_TO_Y(挖深层矿重构 P1):连续挖竖井**下到指定 Y 层**,然后交还 —— 专为"挖钻石/红石等深层矿前先到矿层"设计。
 *
 * 病根(实测):OreDigTask 把"下挖"和"找矿"耦合在一个 scan 限频循环里,从 Y=48 想挖到钻石层(Y<16)时
 * 反复"锁定斜下方够不到的矿→水平掘隧道→dist 卡死→no_progress",卡死 11 分钟。本任务把"下到矿层"独立出来:
 * 用共享 {@link BlockMiner} 连续挖脚下(不受任何限频),bot 无被动重力则主动 descendInto 下沉,
 * 遇岩浆硬停、遇水穿过(与 DigDownTask 一致),一路掘到 targetY。到层后由 GoalExecutor 接 MINE_ORE(此时矿在水平面近处)。
 *
 * 自包含状态机(G1,不自 assign),全程主线程(G2)。
 */
public final class DescendToYTask extends AbstractTask {
    private static final int MAX_ELAPSED = 4800;       // 4 分钟硬超时(最深挖 ~130 层足够)
    private static final int NO_PROGRESS_LIMIT = 200;  // 10s 没破任何块即失败(挖不动/卡住)
    private static final int MIN_Y = -60;
    private static final int MAX_LATERAL = 16; // 绕岩浆最多横移 16 格(防大岩浆湖里无限漂移)
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final int targetY;
    private final BlockMiner miner = new BlockMiner();
    private int lastProgressTick;
    private int lateralDetours; // 已横移绕岩浆/卡点的次数
    private int stairDirIndex;  // 台阶斜下的当前水平方向(HORIZONTAL 下标)
    private boolean started;    // 是否已打 descend_started 日志
    private int lastTorchY = Integer.MAX_VALUE; // P1:上次插火把的 Y(每下 TORCH_EVERY 格插一支)
    private static final int TORCH_EVERY = 6;   // 火把光照半径足够覆盖 6 格落差,不刷怪

    public DescendToYTask(int targetY) {
        this.targetY = targetY;
    }

    @Override
    public String name() {
        return "descend";
    }

    @Override
    public String describe() {
        return "Descend to Y=" + targetY;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        // 下挖期 bot 站着挖,位置基本不变;视为 waiting 让 StuckWatcher 不误判,由本任务 NO_PROGRESS_LIMIT 看门狗兜底。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        lastProgressTick = 0;
        lateralDetours = 0;
        // 带铁套加成:下潜进危险深层前主动穿上背包里最好的甲(钻石计划已在 preamble 备了头胸甲)。
        // 深潜死因多是生存(岩浆/怪/低血),铁甲直接减伤;不等战斗触发才穿(被动伤害也护)。
        io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("descend_timeout at_y=" + bot.getBlockPos().getY());
            return;
        }
        // 到达目标层 → 完成,交还(让 GoalExecutor 接 MINE_ORE 在本层找矿)。
        if (bot.getBlockPos().getY() <= targetY) {
            miner.cancel(bot);
            complete();
            return;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        maybePlaceTorch(bot, world, feet); // P1:下潜途中定距点火把,深井不再全黑刷怪(实测下潜 Y-58 全程 light=0 被骷髅围杀)
        BlockPos below = feet.down();
        if (below.getY() <= MIN_Y) {
            fail("descend_reached_min_y");
            return;
        }
        // 卡住太久(挖不动/被挡)→ 先横移到相邻列绕过,四面不通才失败。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = elapsed;
                return;
            }
            miner.cancel(bot);
            fail("descend_no_progress at_y=" + feet.getY());
            return;
        }

        // 推进当前挖掘。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
        }

        // 台阶式斜向下挖(拟人 + 安全):挖"下一级台阶"(斜前下方),先暴露前方——下一级或其踏面是水/岩浆
        // 就换方向绕,绝不直挖脚下(避免一镐捅穿到下方的水/岩浆)。像挖楼梯一样一级一级斜下。
        Direction dir = HORIZONTAL[stairDirIndex];
        BlockPos ahead = feet.offset(dir);   // 下一级头位 (x+d, y)
        BlockPos next = ahead.down();         // 下一级站位 (x+d, y-1)
        if (isLava(world, next) || isLava(world, next.down()) || isLava(world, ahead)
                || isWater(world, next) || isWater(world, next.down())) {
            if (rotateStair(world, feet)) {
                return; // 换了个不挨水/岩浆的斜下方向
            }
            // 四个斜下方向都被水/岩浆挡 → 退回横移绕(卡死兜底)。
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = elapsed;
                return;
            }
            miner.cancel(bot);
            fail("descend_blocked at_y=" + next.getY());
            return;
        }
        // 清出下一级身位:ahead(前方头位,可见先挖) + ahead.up()(前上,头顶净空) + next(脚位)。
        // 关键补挖 ahead.up()——只清 next+ahead 的旧台阶每列仅 2 格(Y-1,Y),玩家从上一级走下来时头会撞到
        // 前方 Y+1 的实心顶,对角下台阶只剩 1 格可走高、正常玩家(1.8高)钻不进(实测下潜矿道人过不去)。
        // 补 ahead.up() 后下潜巷道沿对角线真正 2 格净空可通行。firstSolid3 跳流体防溃浆。
        BlockPos solid = firstSolid(world, ahead, ahead.up(), next);
        if (solid != null) {
            miner.begin(bot, solid);
            miner.tick(bot);
            markStarted(bot, feet);
            return;
        }
        // 身位已通 → 斜下踏到下一级台阶(bot 无被动重力,仍需主动移一格;斜向移近似踏下一级楼梯)。
        bot.getActionPack().descendInto(next);
        markStarted(bot, feet);
    }

    private void markStarted(AIPlayerEntity bot, BlockPos feet) {
        lastProgressTick = elapsed;
        if (!started) {
            started = true;
            BotLog.action(bot, "descend_started", "target_y", targetY, "from_y", feet.getY());
        }
    }

    // 竖井被岩浆(或卡点)挡住时,横移一格到"无岩浆、可下挖"的相邻列,绕过去继续下挖。
    // 挖开通往侧列的块(挨岩浆的块不挖,防溃浆淹没);通了就 teleport 平移过去(bot 无被动重力,与 descendInto 一致)。
    // 四面都不可行 → 返回 false,由调用方判失败(交规避层"困死撤离"兜底)。
    private boolean tryLateralDetour(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        // 先在当前层找无岩浆侧列绕;当前层四面都被岩浆封死(大岩浆湖,实测 at_y=50:脚下及四面 side.down 皆岩浆
        // → 当前层无解 → 整步失败)时,上退一层在岩浆湖顶上方绕——通常能爬到岩浆湖边缘外继续下挖。
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos base = feet.up(dy);
            for (Direction dir : HORIZONTAL) {
                BlockPos side = base.offset(dir);
                if (isLava(world, side) || isLava(world, side.up()) || isLava(world, side.down())) {
                    continue; // 别往岩浆方向横移
                }
                BlockPos solid = firstSolid(world, side, side.up());
                if (solid != null) {
                    if (adjacentLava(world, solid)) {
                        continue; // 要挖的块挨着岩浆,挖了会溃浆淹没,换方向
                    }
                    if (miner.target() == null || !miner.target().equals(solid)) {
                        miner.begin(bot, solid);
                    }
                    miner.tick(bot);
                    return true; // 正在挖通往侧列的路(本 tick 算进展)
                }
                // 侧列已通(脚位+头位皆空)→ teleport 平移过去(可能上退了一层),下个 tick 在新列继续下挖。
                miner.cancel(bot);
                if (!io.github.zoyluo.aibot.mode.FakePlayerMotion.stepTo(bot, side, "descend_lava_detour")) {
                    continue;
                }
                lateralDetours++;
                BotLog.action(bot, "descend_lava_detour", "dir", dir.asString(), "at_y", side.getY(), "up", dy);
                return true;
            }
        }
        return false;
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    // 台阶斜下:换到下一个"不挨水/岩浆"的斜下方向;四面都不行返回 false(交横移兜底)。
    private boolean rotateStair(ServerWorld world, BlockPos feet) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            stairDirIndex = (stairDirIndex + 1) % HORIZONTAL.length;
            BlockPos ahead = feet.offset(HORIZONTAL[stairDirIndex]);
            BlockPos next = ahead.down();
            if (!isLava(world, next) && !isLava(world, next.down()) && !isLava(world, ahead)
                    && !isWater(world, next) && !isWater(world, next.down())) {
                return true;
            }
        }
        return false;
    }

    private static boolean adjacentLava(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isLava(world, pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    // P1 下潜照明(真实玩家下矿标准操作):每下 TORCH_EVERY 格、光照<8、有火把(或煤+棍可现合)就在脚位插一支。
    // 治"下潜深井全程 light=0、骷髅/僵尸成群刷出来围杀"(实测 real_diamond 下潜 Y-58 全程 light=0 被 5 只骷髅围攻)。
    // 照明是增益不是前置:缺火把不阻塞下潜。
    private void maybePlaceTorch(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        if (lastTorchY - feet.getY() < TORCH_EVERY) {
            return;
        }
        if (world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) >= 8) {
            lastTorchY = feet.getY(); // 已够亮也推进基准,避免每 tick 重判
            return;
        }
        var torchSlot = InventoryAction.findItem(bot, net.minecraft.item.Items.TORCH);
        if (torchSlot.isEmpty()) {
            // 火把自补:有煤+棍就地合 4 支(下潜常态:顺路煤 + 随身棍),缺料不强求。
            var coal = InventoryAction.findItem(bot, net.minecraft.item.Items.COAL);
            var stick = InventoryAction.findItem(bot, net.minecraft.item.Items.STICK);
            if (coal.isPresent() && stick.isPresent()
                    && InventoryAction.removeItems(bot, net.minecraft.item.Items.COAL, 1)
                    && InventoryAction.removeItems(bot, net.minecraft.item.Items.STICK, 1)) {
                InventoryAction.giveItem(bot, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH, 4));
                torchSlot = InventoryAction.findItem(bot, net.minecraft.item.Items.TORCH);
            }
        }
        if (torchSlot.isPresent()) {
            int held = bot.getInventory().selectedSlot;
            InventoryAction.equipFromSlot(bot, torchSlot.getAsInt());
            if (!BuildAction.placeBlockAt(bot, feet).isFailed()) {
                lastTorchY = feet.getY();
                BotLog.action(bot, "descend_torch", "pos", feet.toShortString());
            }
            InventoryAction.equipFromSlot(bot, held); // 换回镐,不耽误下一格挖掘
        }
    }

    // 3 参版:依次返回第一个"固体且非流体"的格(流体跳过,绝不挖→防溃浆/溃水)。用于下潜台阶清三格身位
    // (ahead 头位 + ahead.up 头顶净空 + next 脚位),保证下潜巷道 2 格可走高。
    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b, BlockPos c) {
        for (BlockPos p : new BlockPos[]{a, b, c}) {
            if (!world.getBlockState(p).isAir() && world.getFluidState(p).isEmpty()) {
                return p.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.toImmutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.toImmutable();
        }
        return null;
    }
}
