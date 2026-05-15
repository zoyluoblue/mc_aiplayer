package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;

public final class MiningEnvironmentPolicy {
    private MiningEnvironmentPolicy() {
    }

    public static Decision decide(String currentDimension, int currentY, MiningResource.Profile profile) {
        String dimension = currentDimension == null || currentDimension.isBlank() ? "unknown" : currentDimension;
        MiningHeightPolicy.Decision height = MiningHeightPolicy.decide(currentY, profile);
        boolean dimensionAllowed = profile == null || profile.allowsDimension(dimension);
        boolean shouldDescend = dimensionAllowed
            && profile != null
            && profile.prospectable()
            && !height.currentInTargetRange()
            && currentY > height.targetY();
        boolean shouldMoveHigher = dimensionAllowed
            && profile != null
            && profile.prospectable()
            && !height.currentInTargetRange()
            && currentY < height.minY();
        return new Decision(
            dimension,
            profile == null ? "any" : profile.dimension(),
            dimensionAllowed,
            height,
            profile == null ? "local_search" : profile.routeHint(),
            profile != null && profile.specialEnvironment(),
            profile != null && profile.branchMinePreferred(),
            shouldDescend,
            shouldMoveHigher
        );
    }

    public record Decision(
        String currentDimension,
        String requiredDimension,
        boolean dimensionAllowed,
        MiningHeightPolicy.Decision height,
        String routeHint,
        boolean specialEnvironment,
        boolean branchMinePreferred,
        boolean shouldDescend,
        boolean shouldMoveHigher
    ) {
        public boolean canSearchHere() {
            return dimensionAllowed && height.currentInTargetRange();
        }

        public String dimensionFailureText(String resourceName) {
            return resourceName + " 需要在 " + requiredDimension + " 获取，当前维度是 " + currentDimension;
        }

        public String toLogText() {
            return "dimension=" + currentDimension
                + ", requiredDimension=" + requiredDimension
                + ", dimensionAllowed=" + dimensionAllowed
                + ", height={" + height.toLogText() + "}"
                + ", routeHint=" + routeHint
                + ", specialEnvironment=" + specialEnvironment
                + ", branchMinePreferred=" + branchMinePreferred
                + ", shouldDescend=" + shouldDescend
                + ", shouldMoveHigher=" + shouldMoveHigher;
        }

        public String toStatusText() {
            return "维度=" + currentDimension + "/" + requiredDimension
                + "，维度状态=" + (dimensionAllowed ? "可用" : "错误")
                + "，" + height.statusText()
                + "，路线=" + MiningStatusText.routeHint(routeHint)
                + "，环境=" + (specialEnvironment ? "特殊目标" : "普通矿物")
                + "，动作=" + actionText();
        }

        private String actionText() {
            if (!dimensionAllowed) {
                return "切换维度";
            }
            if (shouldDescend) {
                return "下探";
            }
            if (shouldMoveHigher) {
                return "上移或换区域";
            }
            return canSearchHere() ? "可搜索" : "保持当前位置";
        }
    }
}
