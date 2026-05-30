package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

public final class AStarPathfinder {
    private static final int DEFAULT_MAX_NODES = 10_000;
    private static final long DEFAULT_MAX_MILLIS = 50L;
    private static final int MAX_CACHE_ENTRIES = 256;
    private static final long SUCCESS_CACHE_MILLIS = 2_000L;
    private static final long FAILURE_CACHE_MILLIS = 5_000L;
    private static final Map<CacheKey, CachedResult> RESULT_CACHE = new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedResult> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private final ServerWorld world;
    private final BlockPos start;
    private final BlockPos goal;
    private final NeighborEnumerator enumerator;
    private final int maxNodes;
    private final long maxMillis;

    public AStarPathfinder(ServerWorld world, BlockPos start, BlockPos goal) {
        this(world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, false);
    }

    public AStarPathfinder(ServerWorld world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis) {
        this(world, start, goal, maxNodes, maxMillis, false);
    }

    // NAV-9:canPillar=true 允许垫方块越障(由有方块的调用方传入)。
    public AStarPathfinder(ServerWorld world, BlockPos start, BlockPos goal, boolean canPillar) {
        this(world, start, goal, DEFAULT_MAX_NODES, DEFAULT_MAX_MILLIS, canPillar);
    }

    public AStarPathfinder(ServerWorld world, BlockPos start, BlockPos goal, int maxNodes, long maxMillis, boolean canPillar) {
        this.world = world;
        this.start = start.toImmutable();
        this.goal = goal.toImmutable();
        this.enumerator = new NeighborEnumerator(canPillar);
        this.maxNodes = maxNodes;
        this.maxMillis = maxMillis;
    }

    public PathfindingResult findPath() {
        long startTime = System.currentTimeMillis();
        BotLog.path(null, "findpath_start", "start", LogFields.pos(start), "goal", LogFields.pos(goal));
        Standability.clearCache();
        BlockPos effectiveStart = resolveEndpoint(start, true);
        if (effectiveStart == null) {
            return done(PathfindingResult.failure(FailureReason.NO_START, 0, elapsed(startTime)));
        }
        BlockPos effectiveGoal = resolveEndpoint(goal, false);
        if (effectiveGoal == null) {
            return done(PathfindingResult.failure(FailureReason.GOAL_NOT_STANDABLE, 0, elapsed(startTime)));
        }
        CacheKey cacheKey = new CacheKey(world.getRegistryKey().getValue().toString(), effectiveStart, effectiveGoal, maxNodes, maxMillis);
        PathfindingResult cached = cached(cacheKey, startTime);
        if (cached != null) {
            return cached;
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator
                .comparingDouble(Node::fCost)
                .thenComparingDouble(Node::hCost));
        Map<BlockPos, Double> gScore = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node startNode = new Node(effectiveStart, 0.0D, CostModel.heuristic(effectiveStart, effectiveGoal), MoveType.WALK, null);
        open.add(startNode);
        gScore.put(effectiveStart, 0.0D);

        int explored = 0;
        while (!open.isEmpty()) {
            if (explored >= maxNodes) {
                return done(cache(cacheKey, PathfindingResult.failure(FailureReason.SEARCH_LIMIT, explored, elapsed(startTime)), startTime));
            }
            if (elapsed(startTime) > maxMillis) {
                return done(cache(cacheKey, PathfindingResult.failure(FailureReason.TIMEOUT, explored, elapsed(startTime)), startTime));
            }

            Node current = open.poll();
            if (!closed.add(current.pos())) {
                continue;
            }
            explored++;
            if (current.pos().equals(effectiveGoal)) {
                return done(cache(cacheKey, PathfindingResult.success(reconstruct(current), explored, elapsed(startTime)), startTime));
            }

            for (NeighborCandidate neighbor : enumerator.getNeighbors(current.pos(), world)) {
                if (closed.contains(neighbor.pos())) {
                    continue;
                }
                double tentativeG = current.gCost() + CostModel.stepCost(current, neighbor, world);
                double knownG = gScore.getOrDefault(neighbor.pos(), Double.POSITIVE_INFINITY);
                if (knownG <= tentativeG) {
                    continue;
                }
                gScore.put(neighbor.pos(), tentativeG);
                open.add(new Node(
                        neighbor.pos(),
                        tentativeG,
                        CostModel.heuristic(neighbor.pos(), effectiveGoal),
                        neighbor.moveType(),
                        current));
            }
        }
        return done(cache(cacheKey, PathfindingResult.failure(FailureReason.GOAL_UNREACHABLE, explored, elapsed(startTime)), startTime));
    }

    private BlockPos resolveEndpoint(BlockPos requested, boolean startPoint) {
        if (Standability.isStandable(world, requested)) {
            return requested;
        }
        Optional<BlockPos> snapped = Standability.findNearestStandable(world, requested, 8, 128, 32);
        if (snapped.isEmpty()) {
            return null;
        }
        BotLog.path(null,
                startPoint ? "findpath_start_snapped" : "findpath_goal_snapped",
                "from", LogFields.pos(requested),
                "to", LogFields.pos(snapped.get()));
        return snapped.get();
    }

    private static PathfindingResult cached(CacheKey key, long startTime) {
        synchronized (RESULT_CACHE) {
            CachedResult cached = RESULT_CACHE.get(key);
            if (cached == null || cached.expiredAtMillis < startTime) {
                RESULT_CACHE.remove(key);
                return null;
            }
            PathfindingResult result = cached.toResult();
            BotLog.path(null, "findpath_cache_hit",
                    "success", result.success(),
                    "fail_reason", result.reason(),
                    "ttl_ms", cached.expiredAtMillis - startTime);
            return done(result);
        }
    }

    private static PathfindingResult cache(CacheKey key, PathfindingResult result, long nowMillis) {
        long ttl = result.success() ? SUCCESS_CACHE_MILLIS : FAILURE_CACHE_MILLIS;
        synchronized (RESULT_CACHE) {
            RESULT_CACHE.put(key, new CachedResult(result, nowMillis + ttl));
        }
        return result;
    }

    private static PathfindingResult done(PathfindingResult result) {
        BotLog.path(null, "findpath_done",
                "success", result.success(),
                "nodes", result.nodesExplored(),
                "ms", result.elapsedMs(),
                "fail_reason", result.reason());
        return result;
    }

    private static List<Node> reconstruct(Node end) {
        List<Node> path = new ArrayList<>();
        for (Node current = end; current != null; current = current.parent()) {
            path.add(current);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    private static long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    private record CacheKey(String dimension, BlockPos start, BlockPos goal, int maxNodes, long maxMillis) {
        private CacheKey {
            start = start.toImmutable();
            goal = goal.toImmutable();
        }
    }

    private record CachedResult(List<Node> path, boolean success, FailureReason reason, int nodesExplored, long expiredAtMillis) {
        private CachedResult(PathfindingResult result, long expiredAtMillis) {
            this(List.copyOf(result.path()), result.success(), result.reason(), result.nodesExplored(), expiredAtMillis);
        }

        private PathfindingResult toResult() {
            if (success) {
                return PathfindingResult.success(path, nodesExplored, 0L);
            }
            return PathfindingResult.failure(reason, nodesExplored, 0L);
        }
    }
}
