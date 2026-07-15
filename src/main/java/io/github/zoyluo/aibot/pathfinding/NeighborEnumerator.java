package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.AIBotConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

public final class NeighborEnumerator {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    private final boolean canPillar;
    private final boolean allowDig;
    private final boolean parkour; // nav.parkour:平跳越沟邻居开关(feature flag,误跳可一键关)
    private BlockPos pathGoal; // 终点格:岩浆预检豁免用(终点贴岩浆由任务层封堵处理,不该让唯一入口无解)

    public NeighborEnumerator() {
        this(false, true);
    }

    // NAV-9:canPillar=true 时允许"垫方块上升"邻接(仅当 bot 背包有可放置方块时由 A* 传入)。
    public NeighborEnumerator(boolean canPillar) {
        this(canPillar, true);
    }

    // NAV-OPT:allowDig=false 时**禁用 DIG_THROUGH 邻居**——只在空气格上做"纯步行"搜索。
    // 用于两阶段寻路的第一阶段:绝大多数移动靠纯步行即可达,搜索空间小(只空气格)、收敛快;
    // 而启用挖穿会把每个相邻实心方块都当邻居,使搜索退化成"3D 体积扩散",被困/地下时极易撑爆到
    // SEARCH_LIMIT(实测 5 格距离的 move 都 SEARCH_LIMIT 的机制根因)。纯步行无解再开第二阶段挖穿。
    public NeighborEnumerator(boolean canPillar, boolean allowDig) {
        this.canPillar = canPillar;
        this.allowDig = allowDig;
        this.parkour = AIBotConfig.get().nav().parkourEnabled();
    }

    public void setPathGoal(BlockPos goal) {
        this.pathGoal = goal;
    }

    public List<NeighborCandidate> getNeighbors(BlockPos current, ServerWorld world) {
        List<NeighborCandidate> result = new ArrayList<>(HORIZONTAL.length);
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            if (Standability.isStandable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.WALK, 0));
                continue;
            }

            BlockPos jumpTarget = target.up();
            if (canJumpOnto(world, current, target) && Standability.isStandable(world, jumpTarget)) {
                result.add(new NeighborCandidate(jumpTarget, MoveType.JUMP_UP, 0));
                continue;
            }

            NeighborCandidate drop = findDrop(world, target);
            if (drop != null) {
                result.add(drop);
                continue;
            }

            if (allowDig && digEnterable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.DIG_THROUGH, 0));
            }
            // 斜上挖登(DIG 垂直分量之上行):目标=邻位高一格,挖开其脚头两格后跳进去。
            // 仅当自己头顶跳跃空间已空才生成(执行器只挖目标两格,不清自己头顶)——坡面/露天爬坡够用,
            // 全封闭竖井上行交给 pillar。治 geo_slope:坡体内矿(高 3 格)水平 DIG 永远够不到。
            BlockPos upTarget = target.up();
            if (allowDig && digEnterable(world, upTarget) && collisionEmpty(world, current.up(2))) {
                result.add(new NeighborCandidate(upTarget, MoveType.DIG_THROUGH, 0));
            }
            // 斜向下挖成楼梯。目标脚位低一格、头位就是相邻列当前高度；挖开两格后走下去，
            // 比原地竖直下挖更安全，也让 follow/chase 能追到地下房间或矿道。
            BlockPos downTarget = target.down();
            if (allowDig && digEnterable(world, downTarget)) {
                result.add(new NeighborCandidate(downTarget, MoveType.DIG_THROUGH, 0));
            }
        }
        // 垂直向下挖落(DIG 垂直分量之下行):挖开脚下一格掉下去站稳。治 geo_deep/埋矿族:
        // 矿在正下方若干格,水平 DIG 在本层泛洪永远够不到(实测 ore_dig_buried/deep 同源)。
        if (allowDig) {
            BlockPos below = current.down();
            if (isMineable(world, below) && !collisionEmpty(world, below.down())) {
                result.add(new NeighborCandidate(below, MoveType.DIG_THROUGH, 0));
            }
        }
        addDiagonals(current, world, result);
        addParkour(current, world, result);
        addPillar(current, world, result);
        addSwim(current, world, result);
        addScaffold(current, world, result);
        return result;
    }

    /**
     * 平跳越沟(治"遇 1~2 格小沟只能绕远/掉坑"):规划器此前没有任何跨 gap 节点,执行器只能
     * 走路时凑巧反应式跳单格窄沟。真沟判定=gap 列脚/头/头上/脚下全空(有支撑就是 WALK/DROP 领域);
     * 落点=同高或低一格且可站。执行由 PathExecutor.tickParkour 预蓄冲刺后沿边起跳。
     */
    private void addParkour(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        if (!parkour || !canJumpFrom(world, current)) {
            return;
        }
        for (Direction direction : HORIZONTAL) {
            BlockPos gap1 = current.offset(direction);
            if (!gapColumn(world, gap1)) {
                continue;
            }
            BlockPos landing = parkourLanding(world, current.offset(direction, 2));
            if (landing != null) {
                result.add(new NeighborCandidate(landing, MoveType.PARKOUR, 0));
                continue; // 1 格沟已可跳,同方向不再嗅 2 格
            }
            BlockPos gap2 = current.offset(direction, 2);
            if (!gapColumn(world, gap2)) {
                continue;
            }
            landing = parkourLanding(world, current.offset(direction, 3));
            if (landing != null) {
                result.add(new NeighborCandidate(landing, MoveType.PARKOUR, 0));
            }
        }
    }

    /** 真沟列:脚/头/头上净空(跳跃弧线)+ 脚下也空;沟底 1~2 格内有岩浆的沟不跳(跳空即死)。 */
    private static boolean gapColumn(ServerWorld world, BlockPos feet) {
        if (!collisionEmpty(world, feet) || !collisionEmpty(world, feet.up())
                || !collisionEmpty(world, feet.up(2)) || !collisionEmpty(world, feet.down())) {
            return false;
        }
        return !world.getFluidState(feet.down()).isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                && !world.getFluidState(feet.down(2)).isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    /** 落点解析:同高可站优先;否则允许低一格(跳下沿)。落点头上两格需净空(带着跳跃弧线落地)。 */
    private static BlockPos parkourLanding(ServerWorld world, BlockPos same) {
        if (Standability.isStandable(world, same) && collisionEmpty(world, same.up(2))) {
            return same;
        }
        BlockPos lower = same.down();
        if (collisionEmpty(world, same) && collisionEmpty(world, same.up())
                && Standability.isStandable(world, lower)) {
            return lower;
        }
        return null;
    }

    // NAV-3:同高对角移动。仅当目标格可站、且两个正交相邻格都"可穿过"(不切墙角)时才允许。
    private static void addDiagonals(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        Direction[][] pairs = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : pairs) {
            BlockPos diag = current.offset(pair[0]).offset(pair[1]);
            if (!Standability.isStandable(world, diag)) {
                continue;
            }
            if (!passableColumn(world, diag)) {
                continue;
            }
            if (!passableColumn(world, current.offset(pair[0])) || !passableColumn(world, current.offset(pair[1]))) {
                continue;
            }
            result.add(new NeighborCandidate(diag, MoveType.DIAGONAL, 0));
        }
    }

    // NAV-9:垫方块上升一格(原地)。bot 会在脚下放方块并跳上去。需要头顶两格净空。
    private void addPillar(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        if (!canPillar) {
            return;
        }
        BlockPos up1 = current.up();
        BlockPos up2 = current.up(2);
        // up1 = 新脚位(当前头位,应为空);up2 = 新头位,需净空
        if (collisionEmpty(world, up1) && collisionEmpty(world, up2) && !Standability.isDangerous(world.getBlockState(up1))) {
            result.add(new NeighborCandidate(up1, MoveType.PILLAR_UP, 0));
        }
    }

    // 无支撑的相邻空气列:先在目标脚位下一格铺实体方块,再走过去。规划阶段允许连续生成这种
    // 虚拟落脚点,执行器会逐格落块并在每次放置后使路径缓存失效。
    private void addScaffold(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        if (!canPillar) {
            return;
        }
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            BlockPos floor = target.down();
            // Normal navigation crosses water by SWIM. A scaffold node here used to place exactly
            // one block at the shoreline, then replan against the same lake forever.
            if (world.getFluidState(target).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                    || world.getFluidState(floor).isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
                continue;
            }
            if (!collisionEmpty(world, target) || !collisionEmpty(world, target.up())) {
                continue;
            }
            BlockState floorState = world.getBlockState(floor);
            if (!floorState.isReplaceable() || !floorState.getCollisionShape(world, floor).isEmpty()) {
                continue;
            }
            if (Standability.isDangerous(world.getBlockState(target))
                    || Standability.isDangerous(world.getBlockState(target.up()))) {
                continue;
            }
            result.add(new NeighborCandidate(target, MoveType.SCAFFOLD, 0));
        }
    }

    /** Surface-water neighbors. Keep Y constant so routes swim across instead of diving. */
    private static void addSwim(BlockPos current, ServerWorld world, List<NeighborCandidate> result) {
        for (Direction direction : HORIZONTAL) {
            BlockPos target = current.offset(direction);
            if (Standability.isSwimmable(world, target)) {
                result.add(new NeighborCandidate(target, MoveType.SWIM, 0));
            }
        }
    }

    private static boolean collisionEmpty(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean passableColumn(ServerWorld world, BlockPos feet) {
        return collisionEmpty(world, feet) && collisionEmpty(world, feet.up());
    }

    private static boolean canJumpFrom(ServerWorld world, BlockPos current) {
        return collisionEmpty(world, current.up()) && collisionEmpty(world, current.up(2));
    }

    private static boolean canJumpOnto(ServerWorld world, BlockPos current, BlockPos front) {
        if (!canJumpFrom(world, current)) {
            return false;
        }
        BlockState frontState = world.getBlockState(front);
        if (frontState.getCollisionShape(world, front).isEmpty()) {
            return false;
        }
        if (frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y) > 1.0D) {
            return false;
        }
        return collisionEmpty(world, front.up()) && collisionEmpty(world, front.up(2));
    }

    private static NeighborCandidate findDrop(ServerWorld world, BlockPos target) {
        if (!collisionEmpty(world, target)) {
            return null;
        }
        if (!collisionEmpty(world, target.up())) {
            return null;
        }
        int maxFall = AIBotConfig.get().nav().maxSafeFall();
        for (int fall = 1; fall <= maxFall; fall++) {
            BlockPos landing = target.down(fall);
            if (Standability.isStandable(world, landing)) {
                return new NeighborCandidate(landing, MoveType.DROP_DOWN, fall);
            }
            if (!collisionEmpty(world, landing)) {
                return null;
            }
        }
        return null;
    }

    // DIG 可进入:脚位与头位各自"可挖 或 已通行"(但不全空——全空是 WALK/JUMP 的领域),
    // 且脚下有支撑(挖完站得住)。修"脚空头实"死角:终点=矿正下方时站位空气、头顶是矿,
    // 原 isMineable 要求脚位非空气 → 四种邻居全拒,goal 节点永不入队,A* 万格泛洪 TIMEOUT(geo_wall 实测)。
    private boolean digEnterable(ServerWorld world, BlockPos target) {
        BlockPos head = target.up();
        boolean footOpen = collisionEmpty(world, target);
        boolean headOpen = collisionEmpty(world, head);
        if (footOpen && headOpen) {
            return false;
        }
        boolean footOk = footOpen || isMineable(world, target);
        boolean headOk = headOpen || isMineable(world, head);
        if (!footOk || !headOk || collisionEmpty(world, target.down())) {
            return false;
        }
        // P0 安全预检(深层挖矿头号死因):挖开这两格后侧面/上方岩浆会涌入——-59 钻石层就是岩浆层,
        // 实操挖钻石最常见死法。脚/头任一格暴露面贴岩浆 → 这条路不挖,A* 自然绕行。
        boolean isGoal = pathGoal != null && (target.equals(pathGoal) || head.equals(pathGoal));
        if (!isGoal && (adjacentLava(world, target) || adjacentLava(world, head))) {
            return false; // 终点格豁免:贴岩浆的矿仍可达,挖前由任务层先封岩浆(ore_dig_lava_seal)
        }
        // P0 沙砾坍塌预检:头位上方是悬沙/砾(FallingBlock)→ 挖开即连环下落,砸头窒息+填回通道。
        if (world.getBlockState(head.up()).getBlock() instanceof net.minecraft.block.FallingBlock) {
            return false;
        }
        return true;
    }

    // 暴露面岩浆:四水平邻+上方任一岩浆即危险(下方由 target.down 实心保证不漏)。
    private static boolean adjacentLava(ServerWorld world, BlockPos pos) {
        if (world.getFluidState(pos.up()).isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
            return true;
        }
        for (Direction d : HORIZONTAL) {
            if (world.getFluidState(pos.offset(d)).isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHeadroom(ServerWorld world, BlockPos target) {
        // 挖掘语义的头位:已空 或 可挖(执行器 tickDigThrough 会把脚位+头位都挖开)。
        // 原"头上两格必须已空"把穿实心山体判成无路——每一步头位都是石头,DIG 邻居一个都生成不出,
        // 这正是 geo_slope/wall/pocket 全卡 no_progress 的根因(挖掘寻路只能贴地刨坑、不能穿山)。
        BlockPos head = target.up();
        return collisionEmpty(world, head) || isMineable(world, head);
    }

    private static boolean isMineable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getHardness(world, pos) < 0.0F || world.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        // 导航破障不应只认识天然石土。木板、玻璃、门等普通可破坏方块同样可能挡住跟随或追击；
        // 方块实体(箱子/熔炉等)、流体、危险块和不可破坏块仍在上面明确排除。
        return true;
    }
}
