package io.github.zoyluo.aibot.coordination;

import java.util.Map;
import java.util.UUID;

public record Job(
        UUID id,
        String kind,
        Map<String, String> params,
        String role,
        Scope scope,
        UUID ownerUuid,
        Status status,
        UUID claimant,
        UUID leaseSessionId,
        UUID leaseId,
        String failureReason
) {
    public enum Scope {
        OWNER,
        GLOBAL_ADMIN
    }

    public enum Status {
        OPEN,
        CLAIMED,
        DONE,
        FAILED
    }

    public Job withStatus(Status newStatus, UUID newClaimant, String reason) {
        return new Job(id, kind, Map.copyOf(params), role, scope, ownerUuid,
                newStatus, newClaimant, leaseSessionId, leaseId, reason == null ? "" : reason);
    }

    public Job claim(UUID botUuid, UUID runtimeSessionId) {
        return new Job(id, kind, Map.copyOf(params), role, scope, ownerUuid,
                Status.CLAIMED, botUuid, runtimeSessionId, UUID.randomUUID(), "");
    }

    public Job reopen(String reason) {
        return new Job(id, kind, Map.copyOf(params), role, scope, ownerUuid,
                Status.OPEN, null, null, null, reason == null ? "" : reason);
    }

    public boolean visibleTo(UUID viewerOwnerUuid, boolean globalAdmin) {
        return globalAdmin || (scope == Scope.OWNER && ownerUuid != null && ownerUuid.equals(viewerOwnerUuid));
    }

    public boolean claimableBy(UUID botOwnerUuid) {
        return scope == Scope.GLOBAL_ADMIN
                || (scope == Scope.OWNER && ownerUuid != null && ownerUuid.equals(botOwnerUuid));
    }
}
