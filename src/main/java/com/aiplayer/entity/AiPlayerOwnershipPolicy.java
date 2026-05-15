package com.aiplayer.entity;

import java.util.UUID;

public final class AiPlayerOwnershipPolicy {
    private AiPlayerOwnershipPolicy() {
    }

    public static boolean canReclaimInactiveName(UUID recordOwner, UUID requestedOwner) {
        return requestedOwner != null && (recordOwner == null || recordOwner.equals(requestedOwner));
    }

    public static boolean canRemoveByName(UUID actualOwner, UUID requester) {
        return requester != null && (actualOwner == null || actualOwner.equals(requester));
    }

    public static boolean canDiscardCopyByName(UUID copyOwner, UUID requester) {
        return requester != null && (copyOwner == null || copyOwner.equals(requester));
    }

    public static boolean canDiscardNamedCopy(UUID removedOwner, UUID requester, UUID copyOwner) {
        if (removedOwner == null) {
            return copyOwner == null;
        }
        return canDiscardCopyByName(copyOwner, requester);
    }

    public static boolean canReplaceNameIndex(UUID existingOwner, UUID incomingOwner) {
        if (existingOwner == null) {
            return incomingOwner != null;
        }
        return incomingOwner != null && existingOwner.equals(incomingOwner);
    }
}
