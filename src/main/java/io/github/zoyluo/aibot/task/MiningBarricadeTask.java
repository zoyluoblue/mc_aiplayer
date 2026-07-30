package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.OptionalInt;

/**
 * Mining-specific hostile recovery: walk back through the already opened branch, then leave a
 * permanent two-block gate between the miner and the hostile cave.  Unlike a general emergency
 * shelter, this task never closes the known rear corridor and never reopens the hostile-facing
 * wall after its hold interval.
 */
public final class MiningBarricadeTask extends AbstractTask {
    private static final int RETREAT_LIMIT = 120;
    private static final int SEAL_NO_PROGRESS_LIMIT = 40;
    private static final int HOLD_TICKS = 20;
    private static final int OPEN_GATE_BLOCKS = 2;

    private enum Phase {
        RETREAT,
        SEAL,
        HOLD
    }

    private final BlockPos retreatFeet;
    private final BlockPos barrierFeet;
    private Phase phase = Phase.RETREAT;
    private int phaseStartedElapsed;
    private int lastProgressElapsed;
    private String lastPlacementFailure = "none";

    public MiningBarricadeTask(BlockPos retreatFeet, BlockPos barrierFeet) {
        this.retreatFeet = retreatFeet == null ? null : retreatFeet.toImmutable();
        this.barrierFeet = barrierFeet == null ? null : barrierFeet.toImmutable();
    }

    /** A freshly selected narrow mining gate always has one open feet and one open head cell. */
    static boolean hasMaterialsForOpenGate(AIPlayerEntity bot) {
        return MaterialPalette.countShelterBlocks(bot) >= OPEN_GATE_BLOCKS;
    }

    @Override
    public String name() {
        return "mining_barricade";
    }

    @Override
    public String describe() {
        return "Mining barricade phase=" + phase
                + " retreat=" + compact(retreatFeet)
                + " gate=" + compact(barrierFeet);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case RETREAT -> 0.2D;
            case SEAL -> 0.5D;
            case HOLD -> 0.9D;
        };
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.RETREAT;
        phaseStartedElapsed = 0;
        lastProgressElapsed = 0;
        lastPlacementFailure = "none";
        if (retreatFeet == null || barrierFeet == null
                || retreatFeet.getY() != barrierFeet.getY()
                || horizontalManhattan(retreatFeet, barrierFeet) != 1) {
            fail("mining_barricade_invalid_plan");
            return;
        }
        Standability.clearCache();
        if (!Standability.isStandable(bot.getServerWorld(), retreatFeet)) {
            fail("mining_barricade_retreat_not_standable");
            return;
        }
        int required = (isSealed(bot, barrierFeet) ? 0 : 1)
                + (isSealed(bot, barrierFeet.up()) ? 0 : 1);
        int available = MaterialPalette.countShelterBlocks(bot);
        if (available < required) {
            fail("missing barricade_blocks required=" + required + " available=" + available);
            return;
        }
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        switch (phase) {
            case RETREAT -> tickRetreat(bot);
            case SEAL -> tickSeal(bot);
            case HOLD -> tickHold(bot);
        }
    }

    private void tickRetreat(AIPlayerEntity bot) {
        if (bot.getBlockPos().equals(retreatFeet)) {
            bot.getActionPack().stopAll();
            enterPhase(Phase.SEAL);
            return;
        }
        if (phaseAge() > RETREAT_LIMIT) {
            fail("mining_barricade_retreat_timeout");
            return;
        }
        if (!bot.getActionPack().isPathExecutorIdle()) {
            return;
        }
        BlockPos here = bot.getBlockPos();
        if (here.getY() == retreatFeet.getY()
                && horizontalManhattan(here, retreatFeet) == 1
                && FakePlayerMotion.stepToStandable(bot, retreatFeet, "mining_barricade_retreat")) {
            lastProgressElapsed = elapsed;
            return;
        }
        ActionResult route = bot.getActionPack().startSurfacePathTo(retreatFeet);
        if (route.isFailed()) {
            fail("mining_barricade_retreat_unreachable:" + route.reason());
        }
    }

    private void tickSeal(AIPlayerEntity bot) {
        if (!bot.getBlockPos().equals(retreatFeet)) {
            bot.getActionPack().stopAll();
            enterPhase(Phase.RETREAT);
            return;
        }
        BlockPos target = !isSealed(bot, barrierFeet) ? barrierFeet
                : !isSealed(bot, barrierFeet.up()) ? barrierFeet.up() : null;
        if (target == null) {
            bot.getActionPack().stopAll();
            enterPhase(Phase.HOLD);
            BotLog.danger(bot, "mining_barricade_sealed",
                    "gate", barrierFeet.toShortString(),
                    "retreat", retreatFeet.toShortString());
            return;
        }
        OptionalInt blockSlot = MaterialPalette.pickShelterBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            fail("missing barricade_block");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            fail("cannot_equip_barricade_block");
            return;
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, target);
        if (placed.isSuccess() && isSealed(bot, target)) {
            lastProgressElapsed = elapsed;
            lastPlacementFailure = "none";
            return;
        }
        lastPlacementFailure = placed.reason();
        if (elapsed - lastProgressElapsed > SEAL_NO_PROGRESS_LIMIT) {
            fail("mining_barricade_unsealable:" + target.toShortString()
                    + ":" + lastPlacementFailure);
        }
    }

    private void tickHold(AIPlayerEntity bot) {
        if (!isSealed(bot, barrierFeet) || !isSealed(bot, barrierFeet.up())) {
            enterPhase(Phase.SEAL);
            return;
        }
        bot.getActionPack().stopAll();
        if (phaseAge() >= HOLD_TICKS) {
            complete();
        }
    }

    private void enterPhase(Phase next) {
        phase = next;
        phaseStartedElapsed = elapsed;
        lastProgressElapsed = elapsed;
    }

    private int phaseAge() {
        return Math.max(0, elapsed - phaseStartedElapsed);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    private static boolean isSealed(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null) {
            return false;
        }
        var world = bot.getServerWorld();
        BlockState state = world.getBlockState(pos);
        return !state.isReplaceable() && !state.getCollisionShape(world, pos).isEmpty();
    }

    private static int horizontalManhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ());
    }

    private static String compact(BlockPos pos) {
        return pos == null ? "missing" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
