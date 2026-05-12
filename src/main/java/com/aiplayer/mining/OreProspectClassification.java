package com.aiplayer.mining;

public enum OreProspectClassification {
    DIRECT_MINEABLE,
    APPROACHABLE_EXPOSED,
    EMBEDDED_HINT,
    REJECTED,
    NOT_FOUND;

    public boolean executable() {
        return this == DIRECT_MINEABLE || this == APPROACHABLE_EXPOSED;
    }

    public int priority() {
        return switch (this) {
            case DIRECT_MINEABLE -> 4;
            case APPROACHABLE_EXPOSED -> 3;
            case EMBEDDED_HINT -> 2;
            case REJECTED -> 1;
            case NOT_FOUND -> 0;
        };
    }
}
