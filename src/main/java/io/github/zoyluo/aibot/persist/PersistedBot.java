package io.github.zoyluo.aibot.persist;

public record PersistedBot(BotRecord bot, MissionRuntimeRecord missions) {
    public PersistedBot {
        missions = missions == null ? MissionRuntimeRecord.empty() : missions;
    }
}
