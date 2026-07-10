package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * DIGDOWN:站着挖竖井,采集 N 个目标方块(如圆石)。专为 GoalExecutor 的 MINE 步设计。
 *
 * 它**不定位特定方块、不寻路、不走路**——只挖脚下的方块柱往下掘进:
 *  - 脚下若已是目标块(石头)→ 挖它,计入产出;
 *  - 脚下是泥/草/沙等非目标块 → 也挖掉穿过去(地表 bot 必须先穿过表层土才到石层),
 *    这正是实测#9 "站在草地上、相邻没石头就秒 no_reachable" 的对症修复;
 *  - 挖一格、bot 自然下落一格、再挖下一格,如此往下,直到采够。
 *
 * 安全:开挖每格前查正下方两格是否岩浆/深渊;碰到流体或挖到基岩层失败,交 GoalExecutor 处理。
 * 挖掘走共享原语 {@link BlockMiner}(只在空闲时发起、绝不中途重发清零进度、正确 face)。
 *
 * 自包含状态机(铁律 G1),不在内部 assign;全程主线程(G2)。
 */
public final class DigDownTask extends AbstractTask {
    private static final int MAX_ELAPSED = 2400;        // 2 分钟硬超时
    private static final int NO_PROGRESS_LIMIT = 200;   // 10s 无进展(没破任何块)即失败
    private static final int PICKUP_GRACE_TICKS = 30;   // 采够后多等一会儿确保掉落物落袋
    private static final int MIN_Y = -60;               // 别挖穿到基岩以下
    private static final int RETURN_LIMIT = 600;        // 采够后爬回井口的回程超时(30s),回不去也别卡死
    private static final int MAX_DESCENT = 24;          // 下挖深度上限(格):超了还没采够多半是掉落物没捡到,扫拾再判,绝不无限挖到深处被怪围杀

    private final Block targetBlock;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    private static final Direction[] HDIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private int hdirIndex;    // 撞基岩后水平掘进的当前方向
    private int stairDirIndex; // 台阶斜下的当前水平方向(HDIRS 下标)
    private boolean horizontalMode; // 下挖到上限/撞基岩后永久转横挖,经主体统一原语+看门狗(治 MAX_DESCENT 每tick自废)

    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int pickupGrace;
    private BlockPos startPos;       // 开挖前的井口位置(地表);采够后爬回这里再完成,免得困在井底出不来
    private boolean returning;       // 已采够、正在爬回井口
    private int returnStartTick;

    public DigDownTask(Block targetBlock, int targetCount) {
        this.targetBlock = targetBlock;
        this.targetCount = Math.max(1, targetCount);
        Set<Item> drops = new HashSet<>(HarvestCore.expectedDropsFor(Set.of(targetBlock)));
        if (targetBlock == Blocks.STONE) {
            // 深层(Y<0)全是深板岩(挖了掉 cobbled_deepslate)、远古遗迹是黑石——都算"石料",
            // 否则深层永远凑不够 cobblestone、做不了熔炉(实测 Y=-59 死循环根因)。
            drops.add(Items.COBBLED_DEEPSLATE);
            drops.add(Items.BLACKSTONE);
        }
        this.targetDrops = drops;
    }

    @Override
    public String name() {
        return "dig_down";
    }

    @Override
    public String describe() {
        return "DigDown " + net.minecraft.registry.Registries.BLOCK.getId(targetBlock).getPath()
                + " " + collected + "/" + targetCount;
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
        // 下挖期 bot 站着挖,位置基本不变;视为 waiting 让 StuckWatcher 不误判,
        // 由本任务自己的 NO_PROGRESS_LIMIT 看门狗负责卡死保护。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        // 干列自检:bot 漫游到湖边/含水层上才开挖的话,阶梯必挖进水里泡死(实测 stall dump 四面全 water,
        // 场景锚点避水管不到任务自己选的开挖点)。脚下 12 格内有水 → 8 方向×4/8 格找干列挪过去再开挖。
        ServerWorld world = bot.getServerWorld();
        if (!dryColumn(world, bot.getBlockPos())) {
            BlockPos feet = bot.getBlockPos();
            int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
            outer:
            for (int dist : new int[]{4, 8, 14}) {
                for (int[] d : dirs) {
                    int x = feet.getX() + d[0] * dist;
                    int z = feet.getZ() + d[1] * dist;
                    int ty = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos cand = new BlockPos(x, ty, z);
                    if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(world, cand)
                            && dryColumn(world, cand)) {
                        boolean moved = io.github.zoyluo.aibot.mode.CapabilityRuntime.run(
                                bot, io.github.zoyluo.aibot.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                                "dig_down_dry_relocate", () -> {
                                    bot.getActionPack().stopAll();
                                    bot.teleport(world, x + 0.5D, ty, z + 0.5D,
                                            java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                                });
                        if (moved) {
                            BotLog.action(bot, "dig_down_dry_relocate", "to", cand.toShortString());
                        }
                        break outer;
                    }
                }
            }
        }
        startPos = bot.getBlockPos();   // 记井口,采够后回这里
        returning = false;
        returnStartTick = 0;
    }

    // 该列从 top 向下 12 格内无流体(干燥可下挖)。
    private static boolean dryColumn(ServerWorld world, BlockPos top) {
        for (int dy = 0; dy <= 12; dy++) {
            if (!world.getFluidState(top.down(dy)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("dig_down_timeout collected=" + collected);
            return;
        }
        if (returning) {                // 已采够,正在爬回井口
            returnToSurface(bot);
            return;
        }
        ServerWorld world = bot.getServerWorld();
        // 下挖深度兜底:超过 MAX_DESCENT 还没采够,多半是掉落物没及时捡(collected 不增)→ 大范围扫拾一次再判;
        // 仍不够则【一次性】永久转水平掘进(置 horizontalMode 标志),绝不继续无限往深里挖(实测无限下挖到 y6 被蜘蛛围杀)。
        // 关键:此处绝不每 tick cancel+begin+return——旧逻辑那样会绕过下面的 miner.tick(永不推进)和
        // L207 无进展看门狗(白等满 MAX_DESCENT 硬超时),每 tick 自废零破块(seed20260610 dig_down_timeout 根因)。
        // 改为只置标志一次,之后正常流经主体统一原语:miner.tick 逐块推进 + 看门狗真卡时 200t 干净失败交回 replan。
        if (!horizontalMode && startPos != null && startPos.getY() - bot.getBlockPos().getY() >= MAX_DESCENT) {
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 12.0D, 64);
            int got = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
            if (got >= targetCount) {
                collected = got;
                miner.cancel(bot);
                returning = true;
                returnStartTick = elapsed;
                BotLog.action(bot, "dig_down_return_start", "from", bot.getBlockPos().toShortString(),
                        "to", startPos.toShortString());
                return;
            }
            horizontalMode = true;        // 永久转横挖,后续走 L236 的 digHorizontal 决策(经 miner.tick + 看门狗)
            lastProgressTick = elapsed;   // 给横挖一个干净的看门狗起算窗口
            BotLog.action(bot, "dig_down_go_horizontal", "from", bot.getBlockPos().toShortString(),
                    "collected", collected + "/" + targetCount);
        }

        // 海平面/含水层下挖防淹:封堵脚位+头位四周的侧向水(见方法注释)。封了本 tick 收手,下 tick 续挖。
        if (sealLateralWater(bot, world)) {
            return;
        }
        // 工具闸:挖不动目标(无合格镐)直接失败,交 GoalExecutor 倒推补镐。
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.getDefaultState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return;
        }

        // 收集计数:绝对增量(固定基线),刚破的块的掉落物随后落袋会被算进来。
        // 垂直半径放大:下挖时刚破的 cobblestone 落在上层台阶,2.5 格够不到 → collected 永不增 → 无限下挖到深处被怪围杀。
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 4.0D, 12.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "dig_down_collected", "total", collected + "/" + targetCount);
        }
        if (collected >= targetCount) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline >= targetCount) {
                // 采够 → 进入回程,爬回井口再完成(否则困在井底,下一个任务如打猎追地表猎物会出不来→活锁)。
                returning = true;
                returnStartTick = elapsed;
                BotLog.action(bot, "dig_down_return_start", "from", bot.getBlockPos().toShortString(),
                        "to", startPos.toShortString());
            }
            return;
        }

        // 无进展看门狗:NO_PROGRESS_LIMIT 内没破任何块 → 干净失败,不空转。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            // 取证 dump:山地/自然地形 no_progress(实测 424t 零破块)光靠失败原因无法定位——
            // 把脚位、阶梯方向、下一级三格(头/脚/踏面)的方块、能否破障打出来,供日志诊断几何卡点。
            BlockPos feetNow = bot.getBlockPos();
            Direction dirNow = HDIRS[stairDirIndex];
            BlockPos aheadNow = feetNow.offset(dirNow);
            BlockPos nextNow = aheadNow.down();
            ServerWorld worldNow = bot.getServerWorld();
            BotLog.action(bot, "dig_down_stall_dump",
                    "feet", feetNow.toShortString(),
                    "dir", dirNow.asString(),
                    "ahead", worldNow.getBlockState(aheadNow).getBlock().toString(),
                    "next", worldNow.getBlockState(nextNow).getBlock().toString(),
                    "below_next", worldNow.getBlockState(nextNow.down()).getBlock().toString(),
                    "under_feet", worldNow.getBlockState(feetNow.down()).getBlock().toString(),
                    "can_harvest", ToolTier.canHarvestWithInventory(bot, targetBlock.getDefaultState()));
            fail("dig_down_no_progress collected=" + collected);
            return;
        }

        // 推进当前挖掘。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return; // 正在挖,等它破/超时
        }
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed; // 破了一格 = 进展(无论是石头还是表层土)
        }
        // DONE / FAILED / IDLE → 决定下一格(脚下柱)。

        BlockPos feet = bot.getBlockPos();
        if (horizontalMode || feet.down().getY() <= MIN_Y) {
            // horizontalMode:下挖到 MAX_DESCENT 上限后永久横挖(见上方一次性置标志处)。
            // 或到基岩上方、向下已无空间 → 转水平掘进继续挖石料(实测 Y=-59 时第一步 below 就 <= MIN_Y,
            // 旧逻辑直接 fail collected=0 → MINE stone 失败 → goal replan 死循环)。
            // 两路皆复用统一 digHorizontal:其 begin 有防重入守卫,经上面 miner.tick 推进 + L207 看门狗止损。
            digHorizontal(bot, world, feet);
            return;
        }

        // 台阶式斜向下挖(拟人 + 安全):绝不直挖脚下——下方可能是水/岩浆,一镐捅穿就溺水/葬身岩浆。
        // 改挖"下一级台阶"(斜前下方:ahead 头位 + next 脚位),深层这两格通常都是石料,顺带计入 collected;
        // 下一级或其踏面是水/岩浆就换斜下方向绕,像挖楼梯一样一级一级斜下(与 DescendToYTask 台阶逻辑一致)。
        Direction dir = HDIRS[stairDirIndex];
        BlockPos ahead = feet.offset(dir);   // 下一级头位 (x+d, y)
        BlockPos next = ahead.down();         // 下一级站位 (x+d, y-1)
        if (isLava(world, next) || isLava(world, next.down()) || isLava(world, ahead)
                || isWater(world, next) || isWater(world, next.down())) {
            if (rotateStair(world, feet)) {
                return; // 换了个不挨水/岩浆的斜下方向
            }
            // 四个斜下方向都被水/岩浆挡 → 转水平掘进(此层还能继续凑石料),实在不行那里再判失败。
            digHorizontal(bot, world, feet);
            return;
        }
        // 清出下一级身位:ahead(前方身位,眼睛永远看得见,先挖) → ahead.up()(前上头顶净空) → next(前下踏面)。
        // ① 顺序:斜坡/上坡里 ahead 是挡在正前方的实心墙遮住通往 next 的视线,先挖被遮的 next 会射线撞墙
        //    判够不到→FAILED→零破块卡死(seed20260610 主因);ahead 先挖清遮挡。
        // ② 补挖 ahead.up():只清 next+ahead 每列仅 2 格(Y-1,Y),玩家从上一级下来时头撞前方 Y+1 实心顶,
        //    下台阶只剩 1 格可走高、正常玩家过不去。补头顶净空→下潜巷道 2 格可通行。firstSolid3 跳流体防溃浆。
        BlockPos solid = firstSolid(world, ahead, ahead.up(), next);
        if (solid != null) {
            miner.begin(bot, solid);
            miner.tick(bot); // 立即发起本格挖掘,不浪费一 tick
            return;
        }
        // 身位已通 → 斜下踏到下一级台阶。bot 无被动重力,仍需主动移一格;斜向 1 格微位移=踏下一级楼梯,
        // 不是 roam 那种跨图大范围闪现(下沉后刚破块的掉落物正好落入拾取半径,一并修掉 collected=0)。
        bot.getActionPack().descendInto(next);
    }

    // 采够后沿阶梯爬回井口(startPos);回到起始高度 / 到位 / 回程超时即完成(石料已到手,绝不卡死)。
    private void returnToSurface(AIPlayerEntity bot) {
        miner.cancel(bot);
        BlockPos at = bot.getBlockPos();
        if (at.getY() >= startPos.getY()
                || at.isWithinDistance(startPos, 2.0D)
                || elapsed - returnStartTick > RETURN_LIMIT) {
            bot.getActionPack().stopAll();
            BotLog.action(bot, "dig_down_return_done", "pos", at.toShortString(),
                    "surfaced", String.valueOf(at.getY() >= startPos.getY()));
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(startPos);   // 沿阶梯寻路爬回井口
        }
    }

    // 海平面/含水层下挖防淹:阶梯斜下会逐步漂向相邻海洋,水从【侧向】涌入脚位/头位泡死
    // (实测沙滩 Y63 出生 dig_down 连淹 12 次→guard 抢断→上浮→replan 重挖同列→死循环;
    // stair 只查正下/正前的水(L260),管不住侧向)。真人挖矿砌墙挡水:每 tick 封堵脚位+头位四周的水,
    // 从根上防淹,而非泡死再被生存层兜底。干燥地形无水→直接返回零放置、不改行为(geo_deep/shaft/cave 不回归)。
    // 返回 true=本 tick 封了一格(调用方收手,下 tick 续;BlockMiner 开挖会自动换回镐)。
    private boolean sealLateralWater(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        // ① 侧向:脚位+头位四周(阶梯斜下漂向海洋时水平涌入)。
        for (BlockPos level : new BlockPos[]{feet, feet.up()}) {
            for (Direction d : HDIRS) {
                if (trySealWater(bot, world, level.offset(d))) {
                    return true;
                }
            }
        }
        // ② 封顶:头顶上方一格——重水种子只封侧向仍 drown 的根因是水从竖井【上方】灌到头位
        //(实测 seal 侧向 25 次仍 drown 12);把头顶也堵上,bot 在 1×2 旱泡里下挖,氧不再被顶部灌水耗光。
        return trySealWater(bot, world, feet.up(2));
    }

    // 该格是水则封一块(真人砌墙/封顶挡水)。主手是镐时对水格交互被原版判 PASS 静默吞掉
    //(同 OreDigTask 封浆教训)→ 先装方块再放。返回 true=封了一格(调用方收手下 tick 续,BlockMiner 自动换回镐);
    // 无水/无块可封→false(交后续逻辑/生存层兜底,命比这格值钱,不卡死)。
    private boolean trySealWater(AIPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!isWater(world, pos)) {
            return false;
        }
        OptionalInt blockSlot = MaterialPalette.pickAnyBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        if (BuildAction.placeBlockAt(bot, pos).isFailed()) {
            return false;
        }
        BotLog.action(bot, "dig_down_seal_water", "at", pos.toShortString());
        lastProgressTick = elapsed; // 封堵=进展,别被 NO_PROGRESS 看门狗误杀
        return true;
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    // 台阶斜下:换到下一个"不挨水/岩浆"的斜下方向;四面都不行返回 false(交 digHorizontal 兜底)。
    private boolean rotateStair(ServerWorld world, BlockPos feet) {
        for (int i = 0; i < HDIRS.length; i++) {
            stairDirIndex = (stairDirIndex + 1) % HDIRS.length;
            BlockPos ahead = feet.offset(HDIRS[stairDirIndex]);
            BlockPos next = ahead.down();
            if (!isLava(world, next) && !isLava(world, next.down()) && !isLava(world, ahead)
                    && !isWater(world, next) && !isWater(world, next.down())) {
                return true;
            }
        }
        return false;
    }

    // 撞基岩(向下到底)后转水平掘进:沿一个方向逐格挖石料,挖通就走进去换列继续。深层全深板岩,
    // 配合构造里把 cobbled_deepslate 计入 targetDrops,即可在 Y<0 凑够做熔炉的石料,不再死循环。
    private void digHorizontal(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        for (int tries = 0; tries < HDIRS.length; tries++) {
            Direction dir = HDIRS[hdirIndex];
            BlockPos side = feet.offset(dir);
            // 对称岩浆检查也避开水:横挖挖进水里同样溺水(原仅查 lava 是缺口);四面皆水/浆则下面 walled 失败交生存层。
            if (isLava(world, side) || isLava(world, side.up()) || isLava(world, side.down())
                    || isWater(world, side) || isWater(world, side.up()) || isWater(world, side.down())) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length; // 这个方向挨水/岩浆,换一个
                continue;
            }
            BlockPos solid = firstSolid(world, side, side.up());
            if (solid != null) {
                if (miner.target() == null || !miner.target().equals(solid)) {
                    miner.begin(bot, solid); // 由 onTick 顶部的 miner.tick 推进
                }
                return;
            }
            // side 脚位+头位皆空 → 走进去换列,继续水平挖
            miner.cancel(bot);
            bot.getActionPack().startWalkTo(side.toCenterPos());
            return;
        }
        fail("dig_down_walled collected=" + collected); // 四面皆岩浆,交还安全网
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    // 3 参版:依次返回第一个"固体且非流体"的格(流体跳过不挖→防溃浆/溃水)。下潜台阶清三格身位
    // (ahead 头位 + ahead.up 头顶净空 + next 脚位),保证下潜巷道 2 格可走高、正常玩家能通过。
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
