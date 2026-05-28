package io.github.zoyluo.aibot.action;

public record ActionResult(Status status, String reason) {
    public enum Status {
        SUCCESS,
        FAILED,
        IN_PROGRESS
    }

    public static final ActionResult SUCCESS = new ActionResult(Status.SUCCESS, "");
    public static final ActionResult IN_PROGRESS = new ActionResult(Status.IN_PROGRESS, "");

    public static ActionResult failed(String reason) {
        return new ActionResult(Status.FAILED, reason);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isInProgress() {
        return status == Status.IN_PROGRESS;
    }
}
