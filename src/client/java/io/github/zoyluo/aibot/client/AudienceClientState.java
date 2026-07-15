package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.network.payload.AudienceSnapshotS2C;

import java.util.List;

public final class AudienceClientState {
    public static final AudienceClientState INSTANCE = new AudienceClientState();

    private AudienceSnapshotS2C snapshot = new AudienceSnapshotS2C(List.of(), "", "", "", "等待观众数据");

    private AudienceClientState() {
    }

    public synchronized AudienceSnapshotS2C snapshot() {
        return snapshot;
    }

    public synchronized void setSnapshot(AudienceSnapshotS2C snapshot) {
        this.snapshot = snapshot;
    }

    public synchronized String audienceBotName() {
        return snapshot.audienceBotName();
    }
}
