package io.github.zoyluo.aibot.coordination;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskBoard {
    public static final TaskBoard INSTANCE = new TaskBoard();

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private volatile UUID runtimeSessionId = UUID.randomUUID();

    private TaskBoard() {
    }

    public UUID postForOwner(UUID ownerUuid, String kind, Map<String, String> params, String role) {
        return post(Job.Scope.OWNER, java.util.Objects.requireNonNull(ownerUuid, "ownerUuid"), kind, params, role);
    }

    public UUID postGlobal(String kind, Map<String, String> params, String role) {
        return post(Job.Scope.GLOBAL_ADMIN, null, kind, params, role);
    }

    private UUID post(Job.Scope scope, UUID ownerUuid, String kind, Map<String, String> params, String role) {
        UUID id = UUID.randomUUID();
        Job job = new Job(id, clean(kind), Map.copyOf(params), clean(role), scope, ownerUuid,
                Job.Status.OPEN, null, null, null, "");
        jobs.put(id, job);
        BotLog.task(null, "job_posted", "id", id, "kind", job.kind(), "role", job.role(),
                "scope", scope, "owner_uuid", ownerUuid == null ? "-" : ownerUuid);
        return id;
    }

    public Optional<Job> claimNext(AIPlayerEntity bot, Set<String> roles) {
        UUID ownerUuid = AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null);
        for (Job job : snapshot()) {
            if (job.status() != Job.Status.OPEN || !job.claimableBy(ownerUuid) || !roleMatches(job.role(), roles)) {
                continue;
            }
            UUID id = job.id();
            Job claimed = jobs.compute(id, (ignored, current) -> {
                if (current == null || current.status() != Job.Status.OPEN
                        || !current.claimableBy(ownerUuid) || !roleMatches(current.role(), roles)) {
                    return current;
                }
                return current.claim(bot.getUuid(), runtimeSessionId);
            });
            if (claimed != null && claimed.status() == Job.Status.CLAIMED && bot.getUuid().equals(claimed.claimant())) {
                BotLog.task(bot, "job_claimed", "id", claimed.id(), "kind", claimed.kind(), "role", claimed.role());
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    public void markDone(UUID jobId) {
        jobs.computeIfPresent(jobId, (ignored, job) -> job.withStatus(Job.Status.DONE, job.claimant(), ""));
        BotLog.task(null, "job_done", "id", jobId);
    }

    public void markFailed(UUID jobId, String why) {
        jobs.computeIfPresent(jobId, (ignored, job) -> job.withStatus(Job.Status.FAILED, job.claimant(), why));
        BotLog.task(null, "job_failed", "id", jobId, "reason", why);
    }

    public List<Job> snapshot() {
        List<Job> list = new ArrayList<>(jobs.values());
        list.sort(Comparator.comparing(job -> job.id().toString()));
        return list;
    }

    public List<Job> snapshotForOwner(UUID ownerUuid) {
        if (ownerUuid == null) {
            return List.of();
        }
        return snapshot().stream().filter(job -> job.visibleTo(ownerUuid, false)).toList();
    }

    public void replaceAll(List<Job> loaded) {
        jobs.clear();
        for (Job job : loaded) {
            boolean valid = job != null
                    && job.id() != null
                    && job.params() != null
                    && job.scope() != null
                    && ((job.scope() == Job.Scope.OWNER && job.ownerUuid() != null)
                        || (job.scope() == Job.Scope.GLOBAL_ADMIN && job.ownerUuid() == null));
            if (!valid) {
                BotLog.security("legacy_job_scope_rejected",
                        "job_uuid", job == null ? "-" : job.id(),
                        "reason", "missing_or_invalid_scope");
                continue;
            }
            Job normalized = new Job(
                    job.id(),
                    clean(job.kind()),
                    new LinkedHashMap<>(job.params()),
                    clean(job.role()),
                    job.scope(),
                    job.ownerUuid(),
                    job.status() == null ? Job.Status.OPEN : job.status(),
                    job.claimant(),
                    job.leaseSessionId(),
                    job.leaseId(),
                    job.failureReason() == null ? "" : job.failureReason());
            if (normalized.status() == Job.Status.CLAIMED
                    && !runtimeSessionId.equals(normalized.leaseSessionId())) {
                normalized = normalized.reopen("recovered_stale_claim");
                BotLog.task(null, "job_claim_recovered", "id", normalized.id(), "session", runtimeSessionId);
            }
            jobs.put(job.id(), normalized);
        }
    }

    public UUID beginRuntimeSession() {
        runtimeSessionId = UUID.randomUUID();
        return runtimeSessionId;
    }

    public UUID runtimeSessionId() {
        return runtimeSessionId;
    }

    public void clear() {
        jobs.clear();
        BotLog.task(null, "jobs_cleared");
    }

    private static boolean roleMatches(String jobRole, Set<String> roles) {
        return jobRole == null || jobRole.isBlank() || roles.contains(jobRole.toLowerCase(java.util.Locale.ROOT));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
