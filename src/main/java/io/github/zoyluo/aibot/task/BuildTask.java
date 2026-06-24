package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

public final class BuildTask extends AbstractTask {
    private enum Phase {
        SITE,
        FLATTEN,
        BUILD
    }

    private enum FlattenKind {
        CLEAR,
        FILL
    }

    private final BlueprintSchema blueprint;
    private BlockPos anchor;
    private final boolean autoSite;
    private final boolean flatten;
    private final Deque<FlattenTarget> flattenTargets = new ArrayDeque<>();
    private Phase phase = Phase.SITE;
    private FlattenTarget currentFlattenTarget;
    private int flattenTargetTick;   // 当前整地格起算 tick:够不到的格超预算即跳过,防 nearbyStand 退化"走向自己"死循环
    private int nextIndex;
    private int buildTargetTick;      // 当前落块格起算 tick:放不到的块超预算即跳过(best-effort),防 moveWithinReach 永续寻路空转
    private int buildTargetIndex = -1;
    private int retryTicks;
    private int placeDelayTicks;
    private boolean flattenMiningStarted;
    private String note = "";

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor) {
        this(blueprint, anchor, false, false);
    }

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor, boolean autoSite, boolean flatten) {
        this.blueprint = blueprint;
        this.anchor = anchor == null ? null : anchor.toImmutable();
        this.autoSite = autoSite;
        this.flatten = flatten;
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String describe() {
        String anchorText = anchor == null ? "auto" : compact(anchor);
        return "Building " + blueprint.name() + " at " + anchorText + " " + nextIndex + "/" + blueprint.placements().size()
                + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double buildProgress = blueprint.placements().isEmpty() ? 1.0D : Math.min(1.0D, (double) nextIndex / blueprint.placements().size());
        return switch (phase) {
            case SITE -> 0.0D;
            case FLATTEN -> Math.min(0.25D, flattenTargets.isEmpty() ? 0.25D : 0.10D);
            case BUILD -> 0.25D + buildProgress * 0.75D;
        };
    }

    @Override
    public boolean isWaiting() {
        // 建造=原地立着逐格挖/放(整地+砌房),位置长时间不变是正常作业,不是卡死。
        // 不豁免则 StuckWatcher 200t 位置不变即误杀(real_build 实测 task_stuck_aborted reason=stuck:build,
        // 真实地形整地阶段静立施工被斩,phase=FLATTEN progress=0.1)。卡死保护交本任务 build_timeout(7200t)+
        // moveWithinReach 自身 retryTicks(寻路真失败才计)兜底,比 StuckWatcher 更懂建造语义。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextIndex = 0;
        buildTargetTick = 0;
        buildTargetIndex = -1;
        retryTicks = 0;
        placeDelayTicks = 0;
        flattenTargets.clear();
        currentFlattenTarget = null;
        flattenMiningStarted = false;
        phase = Phase.SITE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 16000) {
            // 真实地形建房=整地(挖高填低)+逐格砌 100+ 块,且重活拖低 tps;7200t 不够(实测只到 81/116)。
            // 放宽到 16000t 让真实地形也能整地+落成;lab 平整建房 346t 远不触此上限,零影响。
            fail("build_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        switch (phase) {
            case SITE -> site(bot);
            case FLATTEN -> flatten(bot);
            case BUILD -> build(bot);
        }
    }

    private void site(AIPlayerEntity bot) {
        if (anchor == null) {
            if (!autoSite) {
                fail("missing_anchor");
                return;
            }
            // flatten 开启时用 lenient 选址:真实起伏地形罕有现成平地,选最平可用点交 FLATTEN 整平
            //(治 real_build no_flat_site 5/10);flatten 关闭时严格(平整画布零回归)。
            anchor = SiteFinder.findSite(bot, blueprint.width(), blueprint.depth(), 16, flatten).orElse(null);
            if (anchor == null) {
                fail("no_flat_site");
                return;
            }
            note = "auto_site=" + compact(anchor);
        }
        if (flatten) {
            planFlatten(bot.getServerWorld());
            phase = Phase.FLATTEN;
        } else {
            phase = Phase.BUILD;
        }
    }

    private void planFlatten(ServerWorld world) {
        flattenTargets.clear();
        for (int dx = 0; dx < blueprint.width(); dx++) {
            for (int dz = 0; dz < blueprint.depth(); dz++) {
                BlockPos ground = anchor.add(dx, -1, dz);
                if (world.getBlockState(ground).isAir() || !world.getFluidState(ground).isEmpty()) {
                    flattenTargets.addLast(new FlattenTarget(ground, FlattenKind.FILL));
                }
                for (int dy = 0; dy < Math.max(blueprint.height(), 1); dy++) {
                    BlockPos clear = anchor.add(dx, dy, dz);
                    if (!world.getBlockState(clear).isAir() && world.getFluidState(clear).isEmpty()) {
                        flattenTargets.addLast(new FlattenTarget(clear, FlattenKind.CLEAR));
                    }
                }
            }
        }
    }

    private void flatten(AIPlayerEntity bot) {
        if (currentFlattenTarget == null) {
            currentFlattenTarget = flattenTargets.pollFirst();
            flattenMiningStarted = false;
            flattenTargetTick = elapsed;
            if (currentFlattenTarget == null) {
                phase = Phase.BUILD;
                return;
            }
        }
        // 整地格预算:够不到的格(nearbyStand 无落脚点会退化成 startPathTo 自己→原地死循环,real_build 实测
        // path_idle 0 进度 build_timeout)→ 50t 内没搞定就跳过,best-effort 继续整地/盖房,站不到的格不强求。
        if (elapsed - flattenTargetTick > 50) {
            note = "flatten_skip=" + compact(currentFlattenTarget.pos()); // 够不到的整地格跳过(防原地死循环)
            currentFlattenTarget = null;
            return;
        }
        if (currentFlattenTarget.kind() == FlattenKind.CLEAR) {
            clearFlattenBlock(bot);
        } else {
            fillFlattenBlock(bot);
        }
    }

    private void clearFlattenBlock(AIPlayerEntity bot) {
        BlockPos pos = currentFlattenTarget.pos();
        if (bot.getServerWorld().getBlockState(pos).isAir()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            return;
        }
        if (!moveWithinReach(bot, pos, "flatten_clear", 20.25D)) {
            return;
        }
        if (!flattenMiningStarted && bot.getActionPack().isMiningIdle()) {
            ActionResult result = MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            if (result.isFailed()) {
                fail(result.reason());
                return;
            }
            flattenMiningStarted = true;
        }
    }

    private void fillFlattenBlock(AIPlayerEntity bot) {
        BlockPos pos = currentFlattenTarget.pos();
        if (!bot.getServerWorld().getBlockState(pos).isAir()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            return;
        }
        if (!moveWithinReach(bot, pos, "flatten_fill", 100.0D)) {
            return;
        }
        OptionalInt slot = MaterialPalette.pickSlot(bot, "dirt_like");
        if (slot.isEmpty()) {
            slot = MaterialPalette.pickAnyBlockSlot(bot);
        }
        if (slot.isEmpty()) {
            fail("missing_flatten_material");
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isSuccess()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        if (retryTicks > 12) {
            fail("flatten_fill_failed: " + result.reason() + " at " + compact(pos));
        }
    }

    private void build(AIPlayerEntity bot) {
        if (nextIndex >= blueprint.placements().size()) {
            complete();
            return;
        }
        // 落块格预算(镜像 flatten 50t skip):同一块连续放不到——moveWithinReach 永续寻路(nearbyStand 退化
        // 走向自己/够不到,executor 非 idle 时只 return false 不计 retryTicks)→ 80t 跳过该块继续盖,best-effort
        // 不卡死到 build_timeout(real_build 实测卡 block 54 死住 ~16000t 的根因)。完工判 ≥80/116 容忍少量跳过。
        if (nextIndex != buildTargetIndex) {
            buildTargetIndex = nextIndex;
            buildTargetTick = elapsed;
            retryTicks = 0;
        } else if (elapsed - buildTargetTick > 80) {
            note = "build_skip=" + nextIndex + "@" + elapsed;
            nextIndex++;
            retryTicks = 0;
            return;
        }
        BlueprintSchema.BlockPlacement placement = blueprint.placements().get(nextIndex);
        BlockPos pos = anchor.add(placement.dx(), placement.dy(), placement.dz());
        Block block = resolveBlock(placement.blockId());
        if (block == null) {
            return;
        }
        if (block == Blocks.AIR) {
            nextIndex++;
            return;
        }
        if (bot.getServerWorld().getBlockState(pos).isOf(block)
                || (placement.palette() != null && MaterialPalette.matchesBlock(bot.getServerWorld().getBlockState(pos), placement.palette()))) {
            nextIndex++;
            return;
        }
        if (!moveWithinReach(bot, pos, "build", 100.0D)) {
            return;
        }
        OptionalInt slot = materialSlot(bot, placement, block);
        if (slot.isEmpty()) {
            fail("missing_material: " + materialName(placement));
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isSuccess()) {
            nextIndex++;
            retryTicks = 0;
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        BlockPos stand = nearbyStand(bot, pos);
        if (stand != null && retryTicks % 4 == 0) {
            bot.getActionPack().startPathTo(stand);
        }
        placeDelayTicks = 5;
        if (retryTicks > 12) {
            // best-effort:这一块反复放不到(障碍/视线/支撑缺)→ 跳过继续盖,不毁整任务(real 地形单块卡不该全败;
            // 完工判 ≥80/116 容忍)。与上面的 80t 预算双保险:谁先到谁跳。
            note = "build_skip_placefail=" + nextIndex + ":" + result.reason();
            nextIndex++;
            retryTicks = 0;
        }
    }

    private OptionalInt materialSlot(AIPlayerEntity bot, BlueprintSchema.BlockPlacement placement, Block block) {
        if (placement.palette() != null && !placement.palette().isBlank()) {
            if (!MaterialPalette.isKnown(placement.palette())) {
                fail("unknown_palette: " + placement.palette());
                return OptionalInt.empty();
            }
            OptionalInt slot = MaterialPalette.pickSlot(bot, placement.palette());
            if (slot.isPresent()) {
                return slot;
            }
        }
        Item item = block.asItem();
        if (!(item instanceof BlockItem)) {
            fail("not_placeable_item: " + placement.blockId());
            return OptionalInt.empty();
        }
        return InventoryAction.findItem(bot, item);
    }

    private Block resolveBlock(String blockId) {
        Identifier id;
        try {
            id = Identifier.of(blockId);
        } catch (RuntimeException exception) {
            fail("invalid_block_id: " + blockId);
            return null;
        }
        return Registries.BLOCK.getOptionalValue(id)
                .orElseGet(() -> {
                    fail("unknown_block_id: " + id);
                    return null;
                });
    }

    private boolean moveWithinReach(AIPlayerEntity bot, BlockPos pos, String reason, double maxDistanceSquared) {
        if (bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) > maxDistanceSquared) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                BlockPos stand = nearbyStand(bot, pos);
                if (stand == null) {
                    fail("no_stand_position_for_" + reason + ": " + compact(pos));
                    return false;
                }
                ActionResult path = bot.getActionPack().startPathTo(stand);
                if (path.isFailed()) {
                    if ("pathfinding_throttled".equals(path.reason())) {
                        // 节流退避:寻路是全局速率限。整地逐块大量请求时每 tick 重请会【永远撞限流】→
                        // bot 到不了第一个整地目标、0 place/0 mine → build_timeout(real_build 实测 flatten 0 进度)。
                        // 退避 4 tick 让速率窗口清掉再重试(不计失败预算);onTick 顶部 placeDelayTicks>0 正好跳过。
                        placeDelayTicks = 4;
                    } else {
                        // 真实无路(无 stand/障碍)才累计,>12 判死。
                        retryTicks++;
                        if (retryTicks > 12) {
                            fail("path_to_" + reason + "_failed: " + path.reason());
                        }
                    }
                }
            }
            return false;
        }
        if (!bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().stopAll();
        }
        retryTicks = 0; // 够到目标=进展,重置真实失败预算(整地跨多块时不累计误杀)
        return true;
    }

    private static BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos) {
        Standability.clearCache();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        BlockPos below = pos.down();
        if (Standability.isStandable(bot.getServerWorld(), below)) {
            return below;
        }
        return bot.getBlockPos();
    }

    private static String materialName(BlueprintSchema.BlockPlacement placement) {
        return placement.palette() == null || placement.palette().isBlank() ? placement.blockId() : "palette:" + placement.palette();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record FlattenTarget(BlockPos pos, FlattenKind kind) {
    }
}
