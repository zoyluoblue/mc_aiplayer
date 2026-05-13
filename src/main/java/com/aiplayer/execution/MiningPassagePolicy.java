package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class MiningPassagePolicy {
    private MiningPassagePolicy() {
    }

    public static Decision nextClearance(Input input) {
        if (input == null) {
            return Decision.blocked("passage_blocked:missing_input");
        }
        MoveIntent intent = input.intent() == null ? MoveIntent.FORWARD_LEVEL : input.intent();
        boolean descending = intent == MoveIntent.FORWARD_DOWN;
        if (!descending && !input.supportSafe()) {
            return Decision.blocked("passage_blocked:support=" + clean(input.supportBlock()));
        }
        if (!input.feetAir()) {
            if (hasText(input.feetDanger())) {
                return Decision.blocked("passage_blocked:feet_danger=" + input.feetDanger());
            }
            if (!input.feetBreakable()) {
                return Decision.blocked("passage_blocked:feet=" + clean(input.feetBlock()));
            }
            return Decision.digFeet("clear_feet:" + clean(input.feetBlock()));
        }
        if (!input.headAir()) {
            if (hasText(input.headDanger())) {
                return Decision.blocked("passage_blocked:head_danger=" + input.headDanger());
            }
            if (!input.headBreakable()) {
                return Decision.blocked("passage_blocked:head=" + clean(input.headBlock()));
            }
            return Decision.digHead("clear_head:" + clean(input.headBlock()));
        }
        if (descending && !input.entryHeadAir()) {
            if (hasText(input.entryHeadDanger())) {
                return Decision.blocked("passage_blocked:entry_head_danger=" + input.entryHeadDanger());
            }
            if (!input.entryHeadBreakable()) {
                return Decision.blocked("passage_blocked:entry_head=" + clean(input.entryHeadBlock()));
            }
            return Decision.digEntryHead("clear_entry_head:" + clean(input.entryHeadBlock()));
        }
        if (!input.supportSafe()) {
            return Decision.blocked("passage_blocked:support=" + clean(input.supportBlock()));
        }
        return Decision.ready();
    }

    public static BlockPos digTarget(BlockPos stand, Decision decision) {
        if (stand == null || decision == null) {
            return null;
        }
        return switch (decision.action()) {
            case DIG_ENTRY_HEAD -> stand.above(2);
            case DIG_HEAD -> stand.above();
            case DIG_FEET -> stand;
            default -> null;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record Input(
        MoveIntent intent,
        String entryHeadBlock,
        boolean entryHeadAir,
        boolean entryHeadBreakable,
        String entryHeadDanger,
        String headBlock,
        boolean headAir,
        boolean headBreakable,
        String headDanger,
        String feetBlock,
        boolean feetAir,
        boolean feetBreakable,
        String feetDanger,
        String supportBlock,
        boolean supportSafe
    ) {
    }

    public record Decision(Action action, String reason) {
        public static Decision ready() {
            return new Decision(Action.READY, "passage_ready");
        }

        public static Decision digEntryHead(String reason) {
            return new Decision(Action.DIG_ENTRY_HEAD, reason);
        }

        public static Decision digHead(String reason) {
            return new Decision(Action.DIG_HEAD, reason);
        }

        public static Decision digFeet(String reason) {
            return new Decision(Action.DIG_FEET, reason);
        }

        public static Decision blocked(String reason) {
            return new Decision(Action.BLOCKED, reason == null || reason.isBlank() ? "passage_blocked" : reason);
        }

        public boolean isReady() {
            return action == Action.READY;
        }

        public boolean needsDig() {
            return action == Action.DIG_ENTRY_HEAD || action == Action.DIG_HEAD || action == Action.DIG_FEET;
        }
    }

    public enum Action {
        READY,
        DIG_ENTRY_HEAD,
        DIG_HEAD,
        DIG_FEET,
        BLOCKED
    }

    public enum MoveIntent {
        FORWARD_LEVEL,
        FORWARD_DOWN,
        BRANCH_LEFT,
        BRANCH_RIGHT
    }
}
