package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class MiningMovementSimulator {
    private MiningMovementSimulator() {
    }

    public static Result simulate(Input input) {
        if (input == null || input.current() == null || input.stand() == null) {
            return Result.blocked(null, MiningPassagePolicy.MoveIntent.FORWARD_LEVEL, "movement_simulator:missing_position");
        }
        MiningPassagePolicy.MoveIntent intent = moveIntent(input.current(), input.stand());
        MiningPassagePolicy.Decision decision = MiningPassagePolicy.nextClearance(toPassageInput(input, intent));
        BlockPos actionTarget = actionTarget(input.stand(), decision);
        Action action = switch (decision.action()) {
            case READY -> Action.MOVE;
            case DIG_FEET -> Action.DIG_FEET;
            case DIG_HEAD -> Action.DIG_HEAD;
            case DIG_ENTRY_HEAD -> Action.DIG_ENTRY_HEAD;
            case BLOCKED -> supportFailure(decision.reason()) ? Action.PLACE_SUPPORT : Action.BLOCKED;
        };
        if (action == Action.PLACE_SUPPORT) {
            actionTarget = input.stand().below();
        }
        return new Result(intent, action, actionTarget, decision.reason(), decision);
    }

    private static MiningPassagePolicy.Input toPassageInput(Input input, MiningPassagePolicy.MoveIntent intent) {
        BlockInfo entryHead = input.entryHead();
        BlockInfo head = input.head();
        BlockInfo feet = input.feet();
        BlockInfo support = input.support();
        boolean descending = intent == MiningPassagePolicy.MoveIntent.FORWARD_DOWN;
        return new MiningPassagePolicy.Input(
            intent,
            entryHead.blockId(),
            !descending || entryHead.air(),
            entryHead.air() || entryHead.breakable(),
            entryHead.air() ? null : entryHead.danger(),
            head.blockId(),
            head.air(),
            head.air() || head.breakable(),
            head.air() ? null : head.danger(),
            feet.blockId(),
            feet.air(),
            feet.air() || feet.breakable(),
            feet.air() ? null : feet.danger(),
            support.blockId(),
            support.supportSafe()
        );
    }

    private static MiningPassagePolicy.MoveIntent moveIntent(BlockPos current, BlockPos stand) {
        if (stand.getY() < current.getY()) {
            return MiningPassagePolicy.MoveIntent.FORWARD_DOWN;
        }
        return MiningPassagePolicy.MoveIntent.FORWARD_LEVEL;
    }

    private static BlockPos actionTarget(BlockPos stand, MiningPassagePolicy.Decision decision) {
        return MiningPassagePolicy.digTarget(stand, decision);
    }

    private static boolean supportFailure(String reason) {
        return reason != null && reason.contains("support=");
    }

    public enum Action {
        MOVE,
        DIG_FEET,
        DIG_HEAD,
        DIG_ENTRY_HEAD,
        PLACE_SUPPORT,
        BLOCKED
    }

    public record BlockInfo(
        String blockId,
        boolean air,
        boolean breakable,
        String danger,
        boolean supportSafe
    ) {
        public static BlockInfo block(String blockId) {
            return new BlockInfo(blockId, false, true, null, false);
        }

        public static BlockInfo empty() {
            return new BlockInfo("minecraft:air", true, true, null, false);
        }

        public static BlockInfo unbreakable(String blockId) {
            return new BlockInfo(blockId, false, false, null, false);
        }

        public static BlockInfo danger(String blockId, String danger) {
            return new BlockInfo(blockId, false, true, danger, false);
        }

        public static BlockInfo support(String blockId, boolean supportSafe) {
            return new BlockInfo(blockId, false, true, null, supportSafe);
        }
    }

    public record Input(
        BlockPos current,
        BlockPos stand,
        BlockInfo entryHead,
        BlockInfo head,
        BlockInfo feet,
        BlockInfo support
    ) {
    }

    public record Result(
        MiningPassagePolicy.MoveIntent intent,
        Action action,
        BlockPos target,
        String reason,
        MiningPassagePolicy.Decision passageDecision
    ) {
        static Result blocked(BlockPos target, MiningPassagePolicy.MoveIntent intent, String reason) {
            return new Result(intent, Action.BLOCKED, target, reason, MiningPassagePolicy.Decision.blocked(reason));
        }

        public boolean readyToMove() {
            return action == Action.MOVE;
        }

        public boolean needsDig() {
            return action == Action.DIG_FEET || action == Action.DIG_HEAD || action == Action.DIG_ENTRY_HEAD;
        }
    }
}
