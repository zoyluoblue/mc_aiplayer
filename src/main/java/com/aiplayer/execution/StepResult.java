package com.aiplayer.execution;

public final class StepResult {
    public enum Status {
        RUNNING,
        SUCCESS,
        FAILURE,
        STUCK
    }

    private final Status status;
    private final String message;
    private final boolean replanAllowed;

    private StepResult(Status status, String message) {
        this(status, message, status == Status.FAILURE || status == Status.STUCK);
    }

    private StepResult(Status status, String message, boolean replanAllowed) {
        this.status = status;
        this.message = message;
        this.replanAllowed = replanAllowed;
    }

    public static StepResult running(String message) {
        return new StepResult(Status.RUNNING, message);
    }

    public static StepResult success(String message) {
        return new StepResult(Status.SUCCESS, message);
    }

    public static StepResult failure(String message) {
        return new StepResult(Status.FAILURE, message);
    }

    public static StepResult terminalFailure(String message) {
        return new StepResult(Status.FAILURE, message, false);
    }

    public static StepResult stuck(String message) {
        return new StepResult(Status.STUCK, message);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean requiresReplan() {
        return replanAllowed && (status == Status.FAILURE || status == Status.STUCK);
    }
}
