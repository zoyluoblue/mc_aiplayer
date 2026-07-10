package io.github.zoyluo.aibot.coordination;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JobScopeTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void ownerScopedJobIsOnlyVisibleAndClaimableWithinOwnerBoundary() {
        Job job = job(Job.Scope.OWNER, OWNER);
        assertTrue(job.visibleTo(OWNER, false));
        assertTrue(job.claimableBy(OWNER));
        assertFalse(job.visibleTo(OTHER, false));
        assertFalse(job.claimableBy(OTHER));
        assertFalse(job.claimableBy(null));
    }

    @Test
    void globalAdminJobCanBeClaimedButOnlyGloballyListedByAdmin() {
        Job job = job(Job.Scope.GLOBAL_ADMIN, null);
        assertTrue(job.claimableBy(OWNER));
        assertTrue(job.claimableBy(null));
        assertFalse(job.visibleTo(OWNER, false));
        assertTrue(job.visibleTo(OWNER, true));
    }

    @Test
    void legacyJobWithoutScopeIsRejectedOnLoad() {
        Job legacy = new Job(UUID.randomUUID(), "mine", Map.of(), "miner",
                null, null, Job.Status.OPEN, null, null, null, "");
        TaskBoard.INSTANCE.replaceAll(java.util.List.of(legacy));
        try {
            assertEquals(0, TaskBoard.INSTANCE.snapshot().size());
        } finally {
            TaskBoard.INSTANCE.clear();
        }
    }

    @Test
    void claimedJobFromPreviousRuntimeSessionReopens() {
        UUID oldSession = UUID.randomUUID();
        Job claimed = job(Job.Scope.OWNER, OWNER).claim(UUID.randomUUID(), oldSession);
        TaskBoard.INSTANCE.beginRuntimeSession();
        TaskBoard.INSTANCE.replaceAll(java.util.List.of(claimed));
        try {
            Job restored = TaskBoard.INSTANCE.snapshot().get(0);
            assertEquals(Job.Status.OPEN, restored.status());
            assertEquals(null, restored.claimant());
            assertEquals(null, restored.leaseId());
        } finally {
            TaskBoard.INSTANCE.clear();
        }
    }

    private static Job job(Job.Scope scope, UUID ownerUuid) {
        return new Job(UUID.randomUUID(), "mine", Map.of("block", "minecraft:iron_ore"), "miner",
                scope, ownerUuid, Job.Status.OPEN, null, null, null, "");
    }
}
