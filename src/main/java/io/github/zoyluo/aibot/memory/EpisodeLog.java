package io.github.zoyluo.aibot.memory;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 情景记忆(三层记忆模型第 2 层):per-bot 的事件时间线——"何时何地发生了什么"。
 * 短期(重启即清,环形 256 条);持久的是蒸馏产物(见 {@link KnowledgeBase}:情景是矿,知识是炼出来的锭)。
 * 每次 record 顺手触发规则蒸馏(确定性、零 LLM 成本)。
 */
public final class EpisodeLog {
    public static final EpisodeLog INSTANCE = new EpisodeLog();
    private static final int CAP = 256;

    public enum Type { DEATH, THREAT, RESOURCE_FOUND, GOAL_DONE, GOAL_FAILED }

    public record EpisodeEvent(long gameTick, Type type, BlockPos pos, String detail) {
    }

    private final Map<UUID, Deque<EpisodeEvent>> events = new ConcurrentHashMap<>();

    private EpisodeLog() {
    }

    public void record(AIPlayerEntity bot, Type type, BlockPos pos, String detail) {
        Deque<EpisodeEvent> deque = events.computeIfAbsent(bot.getUuid(), k -> new ArrayDeque<>());
        EpisodeEvent event = new EpisodeEvent(bot.getServer().getTicks(), type,
                pos.toImmutable(), detail == null ? "" : detail);
        synchronized (deque) {
            deque.addLast(event);
            while (deque.size() > CAP) {
                deque.pollFirst();
            }
        }
        // 蒸馏钩子:情景流入 → 语义知识沉淀(死亡聚类→危险区/资源发现→资源点/失败→教训)。
        KnowledgeBase.INSTANCE.distill(bot, event, snapshot(bot.getUuid()));
    }

    /** 测试隔离:清掉该 bot 的情景流(套件场景互染:残留 RESOURCE_FOUND 让蒸馏去重拦下后续场景的预热点)。 */
    public void clearFor(UUID botId) {
        Deque<EpisodeEvent> deque = events.get(botId);
        if (deque != null) {
            synchronized (deque) {
                deque.clear();
            }
        }
    }

    /** 最近 n 条(新→旧)。 */
    public List<EpisodeEvent> recent(UUID botId, int n) {
        List<EpisodeEvent> all = snapshot(botId);
        int from = Math.max(0, all.size() - n);
        List<EpisodeEvent> out = new ArrayList<>(all.subList(from, all.size()));
        java.util.Collections.reverse(out);
        return out;
    }

    public List<EpisodeEvent> recentOfType(UUID botId, Type type, int n) {
        List<EpisodeEvent> out = new ArrayList<>();
        for (EpisodeEvent e : recent(botId, CAP)) {
            if (e.type() == type) {
                out.add(e);
                if (out.size() >= n) {
                    break;
                }
            }
        }
        return out;
    }

    private List<EpisodeEvent> snapshot(UUID botId) {
        Deque<EpisodeEvent> deque = events.get(botId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }
}
