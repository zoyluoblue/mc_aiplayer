package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class MiningTunnelStepper {
    private MiningTunnelStepper() {
    }

    public static Plan from(MiningMovementSimulator.Result result) {
        if (result == null) {
            return new Plan(Action.BLOCKED, null, "tunnel_stepper:missing_result", null);
        }
        Action action = switch (result.action()) {
            case DIG_FEET -> Action.CLEAR_FEET;
            case DIG_HEAD -> Action.CLEAR_HEAD;
            case DIG_ENTRY_HEAD -> Action.CLEAR_ENTRY_HEAD;
            case PLACE_SUPPORT -> Action.PLACE_SUPPORT;
            case MOVE -> Action.MOVE;
            case BLOCKED -> Action.BLOCKED;
        };
        return new Plan(action, result.target(), result.reason(), result);
    }

    public enum Action {
        CLEAR_FEET,
        CLEAR_HEAD,
        CLEAR_ENTRY_HEAD,
        PLACE_SUPPORT,
        MOVE,
        BLOCKED
    }

    public record Plan(
        Action action,
        BlockPos target,
        String reason,
        MiningMovementSimulator.Result simulation
    ) {
        public Plan {
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
            target = target == null ? null : target.immutable();
        }

        public boolean needsClearance() {
            return action == Action.CLEAR_FEET
                || action == Action.CLEAR_HEAD
                || action == Action.CLEAR_ENTRY_HEAD;
        }

        public boolean needsSupport() {
            return action == Action.PLACE_SUPPORT;
        }

        public boolean readyToMove() {
            return action == Action.MOVE;
        }

        public boolean blocked() {
            return action == Action.BLOCKED;
        }

        public MiningPassagePolicy.Decision passageDecision() {
            return simulation == null
                ? MiningPassagePolicy.Decision.blocked(reason)
                : simulation.passageDecision();
        }

        public String phaseName() {
            return switch (action) {
                case CLEAR_FEET -> "clear_feet";
                case CLEAR_HEAD -> "clear_head";
                case CLEAR_ENTRY_HEAD -> "clear_entry_head";
                case PLACE_SUPPORT -> "place_support";
                case MOVE -> "move";
                case BLOCKED -> "blocked";
            };
        }

        public String displayName() {
            return switch (action) {
                case CLEAR_FEET -> "清理脚部空间";
                case CLEAR_HEAD -> "清理头部空间";
                case CLEAR_ENTRY_HEAD -> "清理入口头部空间";
                case PLACE_SUPPORT -> "补脚下支撑";
                case MOVE -> "移动到下一站位";
                case BLOCKED -> "通道阻断";
            };
        }

        public String toLogText() {
            return "phase=" + phaseName()
                + ", target=" + (target == null ? "none" : target.toShortString())
                + ", reason=" + reason;
        }
    }
}
