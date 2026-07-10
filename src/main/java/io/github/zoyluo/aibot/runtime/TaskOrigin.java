package io.github.zoyluo.aibot.runtime;

import java.util.UUID;

public record TaskOrigin(Kind kind, UUID missionId, UUID jobId, String reason) {
    public enum Kind {
        MISSION,
        PLAYER_COMMAND,
        PLAYER_PANEL,
        LLM_TOOL,
        JOB,
        SAFETY,
        SYSTEM_BACKGROUND,
        VERIFY
    }

    public TaskOrigin {
        kind = kind == null ? Kind.SYSTEM_BACKGROUND : kind;
        reason = reason == null ? "" : reason;
    }

    public boolean safety() {
        return kind == Kind.SAFETY;
    }

    public static TaskOrigin of(Kind kind, String reason) {
        return new TaskOrigin(kind, null, null, reason);
    }

    public static TaskOrigin mission(UUID missionId, String reason) {
        return new TaskOrigin(Kind.MISSION, missionId, null, reason);
    }

    public static TaskOrigin job(UUID jobId, String reason) {
        return new TaskOrigin(Kind.JOB, null, jobId, reason);
    }

    public static TaskOrigin safety(String reason) {
        return of(Kind.SAFETY, reason);
    }
}
