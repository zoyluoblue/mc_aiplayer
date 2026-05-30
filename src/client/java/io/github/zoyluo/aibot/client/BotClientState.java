package io.github.zoyluo.aibot.client;

import io.github.zoyluo.aibot.network.payload.BotSnapshotS2C;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public final class BotClientState {
    public static final BotClientState INSTANCE = new BotClientState();

    private static final int MAX_TRANSCRIPT = 300;  // 保留最近 300 条对话,配合 ChatView 上拉回溯
    private String targetBot = "";
    private BotSnapshotS2C snapshot;
    private final Deque<ChatLine> transcript = new ArrayDeque<>();

    private BotClientState() {
    }

    public synchronized String targetBot() {
        return targetBot;
    }

    public synchronized void setTargetBot(String targetBot) {
        String cleaned = targetBot == null ? "" : targetBot.trim();
        if (!this.targetBot.equals(cleaned)) {
            transcript.clear();
            snapshot = null;
        }
        this.targetBot = cleaned;
    }

    public synchronized boolean matchesTarget(String botName) {
        return targetBot.isBlank() || normalize(targetBot).equals(normalize(botName));
    }

    public synchronized BotSnapshotS2C snapshot() {
        if (snapshot == null || !matchesTarget(snapshot.botName())) {
            return null;
        }
        return snapshot;
    }

    public synchronized void setSnapshot(BotSnapshotS2C snapshot) {
        if (matchesTarget(snapshot.botName())) {
            if (targetBot.isBlank()) {
                targetBot = snapshot.botName();
            }
            this.snapshot = snapshot;
        }
    }

    public synchronized void addTranscript(String role, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ChatLine last = transcript.peekLast();
        if (last != null && last.role().equals(role) && last.text().equals(text)) {
            return;
        }
        transcript.addLast(new ChatLine(role, text));
        while (transcript.size() > MAX_TRANSCRIPT) {
            transcript.removeFirst();
        }
    }

    public synchronized List<ChatLine> transcript() {
        return new ArrayList<>(transcript);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record ChatLine(String role, String text) {
    }
}
