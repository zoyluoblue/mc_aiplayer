package io.github.zoyluo.aibot.persist;

import java.util.List;

public record MissionRuntimeRecord(MissionRecord active, List<MissionSpec> queue, boolean userPaused) {
    public MissionRuntimeRecord {
        queue = queue == null ? List.of() : List.copyOf(queue);
    }

    public static MissionRuntimeRecord empty() {
        return new MissionRuntimeRecord(null, List.of(), false);
    }
}
