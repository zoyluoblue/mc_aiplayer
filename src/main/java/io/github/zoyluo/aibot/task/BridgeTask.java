package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.OptionalInt;
import java.util.Set;

/**
 * 搭路/搭桥:从 bot 当前脚位朝目标水平坐标铺一条**同高、连续、可走**的地板线,让主人(和 bot)
 * 能走过水面/沟壑/岩浆。这是主播实测"搭路过桥过河"时唯一能真正做到的能力——build_house(硬套 1×N)
 * 和 move_to(被 manual_mining 守卫拦)都做不了,只会假装 finish。
 *
 * 机理(不作弊、纯物理走位,每 tick 以活体 getBlockPos() 为脚位自纠正):
 *   1. 前方那格能站(Standability)→ 面朝它、setForward 走过去(1 格上坡由原版 autostep 兜)。
 *   2. 前方是空/水(可替换)但脚下缺地板 → 停下,装备铺料,朝脚前那格放一块(placeBlockAt 会用
 *      当前脚下实心块作依托放进水里,水源是 replaceable 合法覆盖),下一 tick 那格变可站再走。
 *   3. 前方是实心地形(到岸了)→ best-effort 收工,不挖山(挖山交给别的工具)。
 * 只在"前方那格已可站"时才前进,绝不先迈步后铺块 → 不会掉水里。
 */
public final class BridgeTask extends AbstractTask {
    private static final int MAX_ELAPSED = 3000;       // 硬超时(~150s):防任何意外空转
    private static final int NO_PROGRESS_LIMIT = 80;   // 4s 位置没变且没铺出新块 → 卡死认输
    private static final int PLACE_FAIL_LIMIT = 16;    // 同一格反复放不上 → 认输(缺依托/视线)
    private static final double ARRIVE_MANHATTAN = 1.0D;

    private final int targetX;
    private final int targetZ;
    private final int maxBlocks;   // 铺块数上限(安全护栏,也是"方向+长度"模式的长度)

    private int placed;
    private int placeDelayTicks;
    private int placeRetry;
    private int lastProgressElapsed;
    private BlockPos lastFeet;

    public BridgeTask(int targetX, int targetZ, int maxBlocks) {
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.maxBlocks = Math.max(1, maxBlocks);
    }

    @Override
    public String name() {
        return "bridge";
    }

    @Override
    public String describe() {
        return "Bridging toward " + targetX + "," + targetZ + " placed=" + placed + "/" + maxBlocks;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, placed / (double) maxBlocks);
    }

    @Override
    public boolean isWaiting() {
        // 搭桥=站着放块、间歇迈步,位置可能几 tick 不变属正常施工;卡死由本任务 NO_PROGRESS_LIMIT 兜底,
        // 不交给 StuckWatcher(200t 位置不变会误判施工为卡死)。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        placed = 0;
        placeDelayTicks = 0;
        placeRetry = 0;
        lastProgressElapsed = 0;
        lastFeet = bot.getBlockPos();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("bridge_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }

        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();

        // 进展跟踪:脚位变了就算进展,重置卡死计时(放块成功时另行重置)。
        if (!feet.equals(lastFeet)) {
            lastFeet = feet;
            lastProgressElapsed = elapsed;
        }
        if (elapsed - lastProgressElapsed > NO_PROGRESS_LIMIT) {
            fail("bridge_stuck");
            return;
        }

        // 到达(水平曼哈顿 ≤1)或铺满上限 → 收工。
        int hdx = targetX - feet.getX();
        int hdz = targetZ - feet.getZ();
        if (Math.abs(hdx) + Math.abs(hdz) <= ARRIVE_MANHATTAN) {
            bot.getActionPack().setForward(0.0F);
            complete();
            return;
        }
        if (placed >= maxBlocks) {
            bot.getActionPack().setForward(0.0F);
            complete();
            return;
        }

        // 主轴优先的四方向单步(保持地板连续,不走斜角留缝)。
        Direction dir = Math.abs(hdx) >= Math.abs(hdz)
                ? (hdx > 0 ? Direction.EAST : Direction.WEST)
                : (hdz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos front = feet.offset(dir);

        // 前方同高能站 → 走过去。
        if (Standability.isStandable(world, front)) {
            stepTo(bot, front);
            return;
        }
        // 前方上坡一格能站 → 走(autostep 自动上台阶)。
        if (Standability.isStandable(world, front.up())) {
            stepTo(bot, front.up());
            return;
        }
        // 前方是空/水(两格净空)但脚下缺地板 → 铺一块。
        boolean frontOpen = passable(world, front) && passable(world, front.up());
        if (frontOpen) {
            placeFloor(bot, world, front.down());
            return;
        }
        // 前方是实心地形/太高的坎 → 已到岸,best-effort 收工,不挖山。
        bot.getActionPack().setForward(0.0F);
        complete();
    }

    /** 朝目标格中心迈一步(仅在该格已可站时调用)。 */
    private void stepTo(AIPlayerEntity bot, BlockPos cell) {
        LookAction.lookAt(bot, cell.toCenterPos());
        bot.getActionPack().setSprinting(false); // 不冲刺:防冲过未铺格掉水
        bot.getActionPack().setSneaking(false);
        bot.getActionPack().setForward(1.0F);
        placeRetry = 0;
    }

    private void placeFloor(AIPlayerEntity bot, ServerWorld world, BlockPos floor) {
        if (!passable(world, floor)) { // 脚下已有块(上一 tick 成功/地形),让走位分支接管,别重复放
            return;
        }
        OptionalInt slot = fillerSlot(bot);
        if (slot.isEmpty()) {
            fail("bridge_no_blocks");
            return;
        }
        bot.getActionPack().setForward(0.0F); // 放块时钉住,绝不迈步
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAtBlock(bot, floor, Direction.UP);
        ActionResult result = BuildAction.placeBlockAt(bot, floor);
        if (result.isSuccess()) {
            placed++;
            placeRetry = 0;
            placeDelayTicks = 3;
            lastProgressElapsed = elapsed;
            return;
        }
        placeRetry++;
        if (placeRetry > PLACE_FAIL_LIMIT) {
            fail("bridge_place_failed: " + result.reason());
        }
    }

    /** 可替换格(空气/水/岩浆源/草丛)=可放块穿过,视为"开阔",区别于实心墙。 */
    private static boolean passable(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isReplaceable();
    }

    /** 铺料选料:优先便宜、不会掉落的满块(木板/圆石/石头/泥土/深板岩等),避开沙/沙砾(会掉进水留缝);
     *  背包没有这些再退回任意可放置方块。包内共享:PillarUpTask 垫块选料同一逻辑。 */
    static OptionalInt fillerSlot(AIPlayerEntity bot) {
        var main = bot.getInventory().main;
        for (int i = 0; i < main.size(); i++) {
            ItemStack stack = main.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            if (isPreferredFiller(blockItem.getBlock())) {
                return OptionalInt.of(i);
            }
        }
        return MaterialPalette.pickAnyBlockSlot(bot);
    }

    private static final Set<Block> STONE_FILLERS = Set.of(
            Blocks.COBBLESTONE, Blocks.STONE, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.NETHERRACK,
            Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
            Blocks.STONE_BRICKS, Blocks.END_STONE, Blocks.BLACKSTONE);

    private static boolean isPreferredFiller(Block block) {
        if (STONE_FILLERS.contains(block)) {
            return true;
        }
        // 任意 *_planks(白桦/橡木/云杉…):主播搭路场景背包里最常见的就是木板。
        String path = Registries.BLOCK.getId(block).getPath();
        return path.endsWith("_planks");
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
