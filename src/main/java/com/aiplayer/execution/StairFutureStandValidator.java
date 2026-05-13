package com.aiplayer.execution;

public final class StairFutureStandValidator {
    private StairFutureStandValidator() {
    }

    public static Result validate(Input input) {
        if (input == null) {
            return Result.invalid("future_stand_invalid:missing_input");
        }
        if (!input.supportSafe()) {
            return Result.invalid("future_stand_invalid:support=" + clean(input.supportBlock()));
        }
        if (!input.horizontalAir() && !input.horizontalBreakable()) {
            return Result.invalid("future_stand_invalid:head_blocked=" + clean(input.horizontalBlock()));
        }
        if (!input.verticalAir() && !input.verticalBreakable()) {
            return Result.invalid("future_stand_invalid:feet_blocked=" + clean(input.verticalBlock()));
        }
        if (input.horizontalDanger() != null && !input.horizontalDanger().isBlank()) {
            return Result.invalid("future_stand_invalid:horizontal_danger=" + input.horizontalDanger());
        }
        if (input.verticalDanger() != null && !input.verticalDanger().isBlank()) {
            return Result.invalid("future_stand_invalid:vertical_danger=" + input.verticalDanger());
        }
        return Result.ok();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public record Input(
        String horizontalBlock,
        boolean horizontalAir,
        boolean horizontalBreakable,
        String verticalBlock,
        boolean verticalAir,
        boolean verticalBreakable,
        String supportBlock,
        boolean supportSafe,
        String horizontalDanger,
        String verticalDanger
    ) {
    }

    public record Result(boolean valid, String reason) {
        public static Result ok() {
            return new Result(true, "ok");
        }

        public static Result invalid(String reason) {
            return new Result(false, reason == null || reason.isBlank() ? "future_stand_invalid" : reason);
        }
    }
}
