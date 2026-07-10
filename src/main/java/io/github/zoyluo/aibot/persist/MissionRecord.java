package io.github.zoyluo.aibot.persist;

import java.util.Map;

public record MissionRecord(String missionId, MissionSpec spec, Map<String, String> checkpoint) {
    public MissionRecord {
        missionId = missionId == null ? "" : missionId;
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
    }
}
