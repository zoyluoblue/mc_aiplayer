package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作记忆(三层记忆模型第 1 层):一次目标(episode)内的"试过什么/走过哪"。
 *
 * 统一收编此前散落三处的同类补丁——prospect 黑名单(static+TTL)、ore_dig ignored(+一次性特赦)、
 * roam 重复选点(实测 n=3,4,5 连选同一个走不到的点)——它们本质都是工作记忆的缺位,各自手写
 * 导致同一 bug 修三遍。
 *
 * 生命周期挂在 goal 上:GoalExecutor 建立**新目标**的计划时 reset(同一目标的 replan **不** reset——
 * 黑名单必须跨 replan 存活,否则重建任务实例后又选同一个不可达目标死循环,这正是当初
 * PROSPECT_BLACKLIST 做成 static 的原因);clear()/复位时一并清。
 *
 * 排除项带 TTL:"去不了/挖空了"是短期事实,过期自动复活重试(地形/可达性可能已变)。
 * 轨迹用于漫游避重:roam 选点避开最近走过的区域,不再盲目转圈。
 */
public final class EpisodeMemory {
    public static final EpisodeMemory INSTANCE = new EpisodeMemory();

    /** 默认排除时长:走不到/采不到的目标点(60s,原 prospect 黑名单同档)。 */
    public static final int TTL_UNREACHABLE = 1200;
    /** 短排除:挖矿 approach 失败这类(30s,原 ore_dig 特赦的细腻版——TTL 过期自然复活,不再一次性大赦)。 */
    public static final int TTL_SHORT = 600;
    private static final int TRAIL_MAX = 32;       // 轨迹采样上限(约最近 32 个落脚点)
    private static final double TRAIL_SPACING = 4.0D; // 相邻采样点最小间距(去抖)
    private static final int EXCLUDE_CAP = 128;    // 排除表上限(防膨胀;满了清最旧的一半)

    private final Map<UUID, BotEpisode> episodes = new ConcurrentHashMap<>();

    private EpisodeMemory() {
    }

    private static final class BotEpisode {
        final Map<BlockPos, Integer> excludedUntil = new HashMap<>();
        final Deque<BlockPos> trail = new ArrayDeque<>();
    }

    private BotEpisode of(UUID botId) {
        return episodes.computeIfAbsent(botId, k -> new BotEpisode());
    }

    /** 新目标开始/外部复位:本 episode 的工作记忆作废(排除项与轨迹都只对"这件事"有意义)。 */
    public void reset(UUID botId) {
        episodes.remove(botId);
    }

    public void clearAll() {
        episodes.clear();
    }

    /** 排除一个目标点(走不到/挖空/试过没结果),TTL 后自动复活。 */
    public void exclude(UUID botId, BlockPos pos, int nowTick, int ttlTicks) {
        BotEpisode ep = of(botId);
        if (ep.excludedUntil.size() >= EXCLUDE_CAP) {
            // 防膨胀:按过期时间清掉最早的一半(简单有效,不引入额外结构)
            int median = ep.excludedUntil.values().stream().sorted()
                    .skip(ep.excludedUntil.size() / 2).findFirst().orElse(nowTick);
            ep.excludedUntil.values().removeIf(until -> until <= median);
        }
        ep.excludedUntil.put(pos.toImmutable(), nowTick + ttlTicks);
    }

    public boolean isExcluded(UUID botId, BlockPos pos, int nowTick) {
        BotEpisode ep = episodes.get(botId);
        if (ep == null) {
            return false;
        }
        Integer until = ep.excludedUntil.get(pos);
        if (until == null) {
            return false;
        }
        if (until < nowTick) {
            ep.excludedUntil.remove(pos);
            return false;
        }
        return true;
    }

    public int excludedCount(UUID botId) {
        BotEpisode ep = episodes.get(botId);
        return ep == null ? 0 : ep.excludedUntil.size();
    }

    /** 记录轨迹采样(自动去抖:与上一采样点距离不足 TRAIL_SPACING 则跳过)。 */
    public void recordTrail(UUID botId, BlockPos pos) {
        BotEpisode ep = of(botId);
        BlockPos last = ep.trail.peekLast();
        if (last != null && last.getSquaredDistance(pos) < TRAIL_SPACING * TRAIL_SPACING) {
            return;
        }
        ep.trail.addLast(pos.toImmutable());
        while (ep.trail.size() > TRAIL_MAX) {
            ep.trail.pollFirst();
        }
    }

    /** pos 是否落在最近轨迹 radius 内——漫游选点避开刚搜过的区域(不再盲目转圈)。 */
    public boolean nearTrail(UUID botId, BlockPos pos, double radius) {
        BotEpisode ep = episodes.get(botId);
        if (ep == null) {
            return false;
        }
        double r2 = radius * radius;
        for (BlockPos p : ep.trail) {
            if (p.getSquaredDistance(pos) <= r2) {
                return true;
            }
        }
        return false;
    }
}
