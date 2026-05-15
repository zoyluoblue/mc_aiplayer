package com.aiplayer.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlayerOwnershipPolicyTest {
    @Test
    void ownerCanReclaimOwnInactiveNameAndOrphanName() {
        UUID owner = UUID.randomUUID();

        assertTrue(AiPlayerOwnershipPolicy.canReclaimInactiveName(owner, owner));
        assertTrue(AiPlayerOwnershipPolicy.canReclaimInactiveName(null, owner));
        assertFalse(AiPlayerOwnershipPolicy.canReclaimInactiveName(null, null));
    }

    @Test
    void ownerCannotReclaimAnotherOwnersInactiveName() {
        assertFalse(AiPlayerOwnershipPolicy.canReclaimInactiveName(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void nameRemovalAllowsOwnedOrOrphanRecordsOnly() {
        UUID owner = UUID.randomUUID();

        assertTrue(AiPlayerOwnershipPolicy.canRemoveByName(owner, owner));
        assertTrue(AiPlayerOwnershipPolicy.canRemoveByName(null, owner));
        assertFalse(AiPlayerOwnershipPolicy.canRemoveByName(UUID.randomUUID(), owner));
        assertFalse(AiPlayerOwnershipPolicy.canRemoveByName(null, null));
    }

    @Test
    void nameBasedCopyDiscardDoesNotAffectOtherOwners() {
        UUID requester = UUID.randomUUID();

        assertTrue(AiPlayerOwnershipPolicy.canDiscardCopyByName(null, requester));
        assertTrue(AiPlayerOwnershipPolicy.canDiscardCopyByName(requester, requester));
        assertFalse(AiPlayerOwnershipPolicy.canDiscardCopyByName(UUID.randomUUID(), requester));
        assertFalse(AiPlayerOwnershipPolicy.canDiscardCopyByName(null, null));
    }

    @Test
    void ownerlessRemovalOnlyDiscardsOwnerlessSameNameCopies() {
        UUID requester = UUID.randomUUID();

        assertTrue(AiPlayerOwnershipPolicy.canDiscardNamedCopy(null, requester, null));
        assertFalse(AiPlayerOwnershipPolicy.canDiscardNamedCopy(null, requester, requester));
        assertFalse(AiPlayerOwnershipPolicy.canDiscardNamedCopy(null, requester, UUID.randomUUID()));
    }

    @Test
    void ownedRemovalCanDiscardRequesterOrOrphanSameNameCopies() {
        UUID requester = UUID.randomUUID();

        assertTrue(AiPlayerOwnershipPolicy.canDiscardNamedCopy(requester, requester, null));
        assertTrue(AiPlayerOwnershipPolicy.canDiscardNamedCopy(requester, requester, requester));
        assertFalse(AiPlayerOwnershipPolicy.canDiscardNamedCopy(requester, requester, UUID.randomUUID()));
    }

    @Test
    void loadedNameIndexCanOnlyBeReplacedBySameOwnerOrOwnedRecordReplacingOrphan() {
        UUID owner = UUID.randomUUID();

        assertTrue(AiPlayerOwnershipPolicy.canReplaceNameIndex(owner, owner));
        assertTrue(AiPlayerOwnershipPolicy.canReplaceNameIndex(null, owner));
        assertFalse(AiPlayerOwnershipPolicy.canReplaceNameIndex(owner, UUID.randomUUID()));
        assertFalse(AiPlayerOwnershipPolicy.canReplaceNameIndex(owner, null));
        assertFalse(AiPlayerOwnershipPolicy.canReplaceNameIndex(null, null));
    }
}
