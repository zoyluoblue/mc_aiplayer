package com.aiplayer.mining;

import com.aiplayer.execution.MiningStatusText;
import net.minecraft.core.BlockPos;

public record StageMiningPlan(
    OreProspectTarget target,
    BlockPos orePos,
    ExposureTarget exposureTarget,
    int createdTick,
    Stage stage,
    int horizontalDistance,
    int verticalDelta
) {
    public enum Stage {
        PREPARE,
        PROSPECT,
        APPROACH,
        DESCEND,
        TUNNEL,
        EXPOSE,
        MINE,
        COLLECT,
        VERIFY,
        REPROSPECT
    }

    public static StageMiningPlan create(OreProspectTarget target, BlockPos currentPos, BlockPos orePos, int createdTick) {
        return create(target, currentPos, orePos, new ExposureTarget(orePos, orePos, true, true, "legacy"), createdTick);
    }

    public static StageMiningPlan create(
        OreProspectTarget target,
        BlockPos currentPos,
        BlockPos orePos,
        ExposureTarget exposureTarget,
        int createdTick
    ) {
        ExposureTarget safeExposure = exposureTarget == null
            ? new ExposureTarget(orePos, orePos, true, true, "missing_exposure_target")
            : exposureTarget;
        BlockPos routeTarget = safeExposure.routeTarget() == null ? orePos : safeExposure.routeTarget();
        int dx = Math.abs(routeTarget.getX() - currentPos.getX());
        int dz = Math.abs(routeTarget.getZ() - currentPos.getZ());
        int dy = routeTarget.getY() - currentPos.getY();
        return new StageMiningPlan(
            target,
            orePos.immutable(),
            safeExposure,
            Math.max(0, createdTick),
            stageFor(dx + dz, dy),
            dx + dz,
            dy
        );
    }

    public StageMiningPlan withStage(Stage nextStage, BlockPos currentPos) {
        BlockPos routeTarget = routeTarget();
        int dx = Math.abs(routeTarget.getX() - currentPos.getX());
        int dz = Math.abs(routeTarget.getZ() - currentPos.getZ());
        return new StageMiningPlan(target, orePos, exposureTarget, createdTick, nextStage, dx + dz, routeTarget.getY() - currentPos.getY());
    }

    public BlockPos routeTarget() {
        return exposureTarget == null || exposureTarget.routeTarget() == null ? orePos : exposureTarget.routeTarget();
    }

    public String statusText(BlockPos currentPos) {
        BlockPos routeTarget = routeTarget();
        int dx = Math.abs(routeTarget.getX() - currentPos.getX());
        int dz = Math.abs(routeTarget.getZ() - currentPos.getZ());
        int dy = routeTarget.getY() - currentPos.getY();
        return "阶段=" + MiningStatusText.routeStage(stage.name())
            + "，目标=" + target.displayName()
            + "，矿点=" + orePos.toShortString()
            + "，暴露点=" + (exposureTarget == null ? "none" : exposureTarget.exposurePos() == null ? "none" : exposureTarget.exposurePos().toShortString())
            + "，路线目标=" + routeTarget.toShortString()
            + "，水平距离=" + (dx + dz)
            + "，垂直差=" + dy;
    }

    public String toLogText() {
        return "stage=" + stage
            + ", target=" + target.displayName()
            + ", orePos=" + orePos.toShortString()
            + ", exposure={" + (exposureTarget == null ? "none" : exposureTarget.toLogText()) + "}"
            + ", routeTarget=" + routeTarget().toShortString()
            + ", createdTick=" + createdTick
            + ", horizontalDistance=" + horizontalDistance
            + ", verticalDelta=" + verticalDelta;
    }

    private static Stage stageFor(int horizontalDistance, int verticalDelta) {
        if (verticalDelta < 0) {
            return Stage.DESCEND;
        }
        if (horizontalDistance > 2) {
            return Stage.TUNNEL;
        }
        if (verticalDelta == 0 || verticalDelta == 1) {
            return Stage.EXPOSE;
        }
        return Stage.APPROACH;
    }
}
