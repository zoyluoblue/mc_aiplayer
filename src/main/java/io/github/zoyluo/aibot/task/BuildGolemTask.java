package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * 造傀儡:铁傀儡(4 铁块+雕刻南瓜,守家打怪)或雪傀儡(2 雪块+雕刻南瓜,扔雪球吸引仇恨)。
 * 找身边空地按图案摆,南瓜头最后放(原版图案检测在南瓜落位瞬间触发,方块自动变傀儡)。
 * 材料不足开局直接报缺什么,不空跑。
 */
public final class BuildGolemTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600;
    private static final int PLACE_FAIL_LIMIT = 8;
    private static final int VERIFY_TICKS = 40; // 南瓜放完后等 2s 验傀儡出生
    private static final double PLACE_RANGE = 4.0D;

    private final boolean iron;

    private List<PlaceStep> steps;
    private BlockPos spot;
    private int stepIdx;
    private int placeRetry;
    private int placeDelayTicks;
    private int verifyStart = -1;

    private record PlaceStep(BlockPos pos, Item item) {
    }

    public BuildGolemTask(boolean iron) {
        this.iron = iron;
    }

    @Override
    public String name() {
        return "build_golem";
    }

    @Override
    public String describe() {
        return "build_golem " + (iron ? "iron" : "snow") + " step=" + stepIdx;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return steps == null ? 0.0D : Math.min(0.95D, stepIdx / (double) steps.size());
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        stepIdx = 0;
        placeRetry = 0;
        placeDelayTicks = 0;
        verifyStart = -1;
        Item body = iron ? Items.IRON_BLOCK : Items.SNOW_BLOCK;
        int bodyNeed = iron ? 4 : 2;
        List<String> missing = new ArrayList<>();
        if (InventoryAction.countItem(bot, body) < bodyNeed) {
            missing.add((iron ? "iron_block x" : "snow_block x") + bodyNeed);
        }
        if (InventoryAction.countItem(bot, Items.CARVED_PUMPKIN) < 1) {
            missing.add("carved_pumpkin x1(南瓜+剪刀雕刻)");
        }
        if (!missing.isEmpty()) {
            fail("golem_need: " + String.join(", ", missing));
            return;
        }
        spot = findSpot(bot);
        if (spot == null) {
            fail("golem_no_space: 附近没有 3 格高的平整空地");
            return;
        }
        steps = new ArrayList<>();
        steps.add(new PlaceStep(spot, body));
        steps.add(new PlaceStep(spot.up(), body));
        if (iron) {
            // 手臂轴取与 bot 视线垂直,让镜头正对 T 字正面。
            double dx = spot.getX() + 0.5D - bot.getX();
            double dz = spot.getZ() + 0.5D - bot.getZ();
            Direction arm = Math.abs(dx) >= Math.abs(dz) ? Direction.NORTH : Direction.EAST;
            steps.add(new PlaceStep(spot.up().offset(arm), body));
            steps.add(new PlaceStep(spot.up().offset(arm.getOpposite()), body));
        }
        steps.add(new PlaceStep(spot.up(2), Items.CARVED_PUMPKIN)); // 头最后放,触发成型
    }

    /** 找摆放点:bot 周围 2~4 格,脚下实心、竖 3 格净空(铁傀儡加两臂格净空),且离 bot ≥1.5 格防把块放进自己身体。 */
    private BlockPos findSpot(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        for (int r = 2; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos p = feet.add(dx, 0, dz);
                    if (world.getBlockState(p.down()).isReplaceable()) {
                        continue; // 脚下悬空,傀儡没地方站
                    }
                    boolean clear = world.getBlockState(p).isReplaceable()
                            && world.getBlockState(p.up()).isReplaceable()
                            && world.getBlockState(p.up(2)).isReplaceable();
                    if (clear && iron) {
                        for (Direction d : Direction.Type.HORIZONTAL) {
                            // 四个水平邻格都要空:手臂朝向到 onStart 才定,这里保守全查
                            if (!world.getBlockState(p.up().offset(d)).isReplaceable()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                    if (clear) {
                        return p.toImmutable();
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("golem_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        // 全部方块已摆:等傀儡出生验收(南瓜落位当 tick 方块即消失变实体)。
        if (stepIdx >= steps.size()) {
            Class<? extends net.minecraft.entity.passive.GolemEntity> golemClass =
                    iron ? IronGolemEntity.class : SnowGolemEntity.class;
            // 验收绑定建造坐标(≤3 格):多 bot 同场造傀儡时,别把别人刚出生的傀儡当自己的成果
            boolean born = !bot.getServerWorld()
                    .getEntitiesByClass(golemClass, bot.getBoundingBox().expand(8.0D),
                            e -> e.isAlive() && e.age < 100 && e.getBlockPos().getSquaredDistance(spot) <= 9.0D)
                    .isEmpty();
            if (born) {
                complete();
                return;
            }
            if (verifyStart < 0) {
                verifyStart = elapsed;
            } else if (elapsed - verifyStart > VERIFY_TICKS) {
                // 块都放了但没出傀儡:图案多半被环境方块干扰,如实上报
                fail("golem_not_born: 方块已摆但没成型,检查图案周围是否有遮挡");
            }
            return;
        }

        PlaceStep step = steps.get(stepIdx);
        if (bot.getEyePos().distanceTo(step.pos().toCenterPos()) > PLACE_RANGE) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                // 走位点按四个水平方向动态挑一个真能站的,别写死北侧(北侧可能是峭壁)
                BlockPos approach = null;
                for (Direction d : Direction.Type.HORIZONTAL) {
                    BlockPos cand = spot.offset(d, 2);
                    if (Standability.isStandable(bot.getServerWorld(), cand)) {
                        approach = cand;
                        break;
                    }
                }
                bot.getActionPack().startPathTo(approach != null ? approach : spot.offset(Direction.NORTH, 2));
            }
            return;
        }
        bot.getActionPack().stopMovement();
        if (!bot.getServerWorld().getBlockState(step.pos()).isReplaceable()) {
            fail("golem_space_blocked: " + step.pos().toShortString());
            return;
        }
        OptionalInt slot = InventoryAction.findItem(bot, step.item());
        if (slot.isEmpty()) {
            fail("golem_missing: " + step.item());
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAtBlock(bot, step.pos(), Direction.UP);
        ActionResult result = BuildAction.placeBlockAt(bot, step.pos());
        if (result.isSuccess()) {
            stepIdx++;
            placeRetry = 0;
            placeDelayTicks = 3;
            return;
        }
        placeRetry++;
        if (placeRetry > PLACE_FAIL_LIMIT) {
            fail("golem_place_failed: " + result.reason());
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        bot.getActionPack().stopAll(); // stopMovement 清不掉 pathExecutor,被抢占时会被旧路径拖着走
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
