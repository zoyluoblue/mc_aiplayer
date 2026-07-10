package io.github.zoyluo.aibot.persist;

import io.github.zoyluo.aibot.coordination.Job;

import java.util.List;

public record RuntimeSnapshot(
        int schemaVersion,
        String savedAtUtc,
        String buildVersion,
        String runtimeSessionId,
        List<PersistedBot> bots,
        List<Job> jobs
) {
    public static final int CURRENT_SCHEMA = 1;

    public RuntimeSnapshot {
        savedAtUtc = savedAtUtc == null ? "" : savedAtUtc;
        buildVersion = buildVersion == null ? "" : buildVersion;
        runtimeSessionId = runtimeSessionId == null ? "" : runtimeSessionId;
        bots = bots == null ? List.of() : List.copyOf(bots);
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
