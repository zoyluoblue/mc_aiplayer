package com.aiplayer.action;

public class ActionResult {
    private final boolean success;
    private final String message;
    private final boolean requiresReplanning;

    public ActionResult(boolean success, String message) {
        this(success, message, !success);
    }

    public ActionResult(boolean success, String message, boolean requiresReplanning) {
        this.success = success;
        this.message = message;
        this.requiresReplanning = requiresReplanning;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public boolean requiresReplanning() {
        return requiresReplanning;
    }

    public static ActionResult success(String message) {
        return new ActionResult(true, message, false);
    }

    public static ActionResult failure(String message) {
        return new ActionResult(false, message, true);
    }

    public static ActionResult failure(String message, boolean requiresReplanning) {
        return new ActionResult(false, message, requiresReplanning);
    }

    @Override
    public String toString() {
        return "ActionResult{success=" + success + ", message='" + message + "', requiresReplanning=" + requiresReplanning + "}";
    }
}

