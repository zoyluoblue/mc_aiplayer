package io.github.zoyluo.aibot.mining;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-backed provenance ledger for the two strict Mining First acceptance scenarios.
 *
 * <p>The ledger is dormant during normal play. The isolated verifier explicitly opens one
 * session, then ordinary survival actions add facts to it. Final acceptance deliberately combines
 * those transaction facts with vanilla statistics, so neither an inventory mutation nor a forged
 * task completion can satisfy the evidence gate.</p>
 */
public final class MiningEvidenceAudit {
    public static final int SCHEMA_VERSION = 2;
    public static final int DIAMOND_TARGET = 64;
    public static final int OBSIDIAN_TARGET = 32;

    private static final ConcurrentHashMap<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private MiningEvidenceAudit() {
    }

    public enum Target {
        DIAMOND,
        OBSIDIAN
    }

    public static void begin(AIPlayerEntity bot, Target target) {
        Session session = new Session(UUID.randomUUID(), target, StatsSnapshot.read(bot));
        SESSIONS.put(bot.getUuid(), session);
        observeTick(bot);
    }

    /** Records the actual mode and refreshes all vanilla-stat deltas for this server tick. */
    public static void observeTick(AIPlayerEntity bot) {
        Session session = SESSIONS.get(bot.getUuid());
        if (session == null) {
            return;
        }
        session.observedTicks++;
        if (bot.interactionManager.getGameMode() != GameMode.SURVIVAL) {
            session.gameModeViolations++;
        }
        session.latest = StatsSnapshot.read(bot);
    }

    /** Called at the single capability decision boundary, before any privileged operation. */
    public static void recordCapabilityDecision(AIPlayerEntity bot, boolean allowed) {
        Session session = SESSIONS.get(bot.getUuid());
        if (session != null && allowed) {
            session.privilegedAllowed++;
        }
    }

    /** Arms one exact world diamond-ore cell before the physical break begins. */
    public static void observeDiamondOreBeforeBreak(AIPlayerEntity bot, BlockPos pos, Block block) {
        Session session = diamondSession(bot);
        if (session != null && (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)) {
            session.diamondTransactions.observeBeforeBreak(pos);
        }
    }

    /** Commits a break only if that exact cell was observed as diamond ore beforehand. */
    public static void recordDiamondOreBreak(AIPlayerEntity bot, BlockPos pos) {
        Session session = diamondSession(bot);
        if (session != null) {
            session.diamondTransactions.recordExactBreak(pos);
        }
    }

    /**
     * Commits native-drop credit only against a completed exact-cell break transaction.
     * Vanilla PICKED_UP statistics are independently checked at finalization.
     */
    public static void recordDiamondNativePickup(AIPlayerEntity bot,
                                                  BlockPos breakPos,
                                                  Set<Item> acceptedDrops,
                                                  int gained) {
        Session session = diamondSession(bot);
        if (session == null || acceptedDrops == null) {
            return;
        }
        session.diamondTransactions.recordNativePickup(
                breakPos, acceptedDrops.contains(Items.DIAMOND), gained);
    }

    /** Records one successful real water-bucket placement aimed at a factual lava source. */
    public static int recordWaterPlacement(AIPlayerEntity bot, BlockPos lavaPos) {
        return recordWaterPlacement(bot, lavaPos == null ? Set.of() : Set.of(lavaPos));
    }

    /**
     * Arms every exact, observable still-lava cell that a successful real water placement can
     * reach. A later obsidian scan may commit only one of these pre-placement facts; pre-existing
     * obsidian can never be admitted after the fact.
     */
    public static int recordWaterPlacement(AIPlayerEntity bot, Set<BlockPos> observableLava) {
        Session session = obsidianSession(bot);
        if (session == null) {
            return -1;
        }
        int generation = session.obsidianTransactions.armWaterPlacement(
                observableLava == null ? Set.of() : observableLava);
        if (generation >= 0) {
            session.waterPlacements++;
        }
        return generation;
    }

    /**
     * Reconciles every still-open cell from the current physical placement, then irrevocably
     * closes that placement generation. Only cells that are both observable now and factual
     * obsidian can survive as conversion-backed break authority; unconverted candidates are
     * discarded so later natural water or a different placement cannot inherit old provenance.
     */
    public static void reconcileAndCloseWaterPlacement(AIPlayerEntity bot) {
        Session session = obsidianSession(bot);
        if (session == null) {
            return;
        }
        for (BlockPos candidate : session.obsidianTransactions.openWaterPlacementCandidates()) {
            if (ObservableWorldQuery.canObserveCell(bot, candidate)
                    && bot.getServerWorld().getBlockState(candidate).isOf(Blocks.OBSIDIAN)) {
                session.obsidianTransactions.recordConversion(candidate);
            }
        }
        session.obsidianTransactions.closeWaterPlacements();
    }

    /** Commits only a water-backed lava cell that Minecraft has subsequently made obsidian. */
    public static boolean recordLavaToObsidian(AIPlayerEntity bot, BlockPos pos) {
        Session session = obsidianSession(bot);
        return session != null && session.obsidianTransactions.recordConversion(pos);
    }

    /** Normal play remains opportunistic; an active verifier admits only exact converted cells. */
    public static boolean isObsidianBreakAuthorized(AIPlayerEntity bot, BlockPos pos) {
        Session session = obsidianSession(bot);
        return session == null || session.obsidianTransactions.isBreakAuthorized(pos);
    }

    /** Commits a physical break only for an exact conversion-backed obsidian cell. */
    public static boolean recordObsidianBreak(AIPlayerEntity bot, BlockPos pos) {
        Session session = obsidianSession(bot);
        return session == null || session.obsidianTransactions.recordExactBreak(pos);
    }

    /** Commits physical pickup quantity only against the corresponding completed break. */
    public static boolean recordObsidianPickup(AIPlayerEntity bot, BlockPos breakPos, int gained) {
        Session session = obsidianSession(bot);
        return session == null || session.obsidianTransactions.recordExactPickup(breakPos, gained);
    }

    /** Returns exact audited pickup credit when the isolated obsidian verifier is active. */
    public static OptionalInt auditedObsidianPickupCredits(AIPlayerEntity bot) {
        Session session = obsidianSession(bot);
        return session == null
                ? OptionalInt.empty()
                : OptionalInt.of(session.obsidianTransactions.pickupCredits());
    }

    public static Optional<String> failFastReason(AIPlayerEntity bot) {
        observeTick(bot);
        return snapshot(bot.getUuid()).flatMap(snapshot -> {
            if (snapshot.gameModeViolations() > 0) {
                return Optional.of("mining_provenance_non_survival_mode");
            }
            if (snapshot.privilegedAllowed() > 0) {
                return Optional.of("mining_provenance_privileged_allowed");
            }
            return Optional.empty();
        });
    }

    public static Optional<Snapshot> snapshot(AIPlayerEntity bot) {
        Session session = SESSIONS.get(bot.getUuid());
        if (session == null) {
            return Optional.empty();
        }
        session.latest = StatsSnapshot.read(bot);
        return Optional.of(session.snapshot());
    }

    public static Optional<Snapshot> snapshot(UUID botId) {
        Session session = SESSIONS.get(botId);
        return session == null ? Optional.empty() : Optional.of(session.snapshot());
    }

    public static void clear(AIPlayerEntity bot) {
        clear(bot.getUuid());
    }

    public static void clear(UUID botId) {
        SESSIONS.remove(botId);
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    public static boolean hasSession(UUID botId) {
        return SESSIONS.containsKey(botId);
    }

    /** Returns the opaque identity of the active strict session for checkpoint binding. */
    public static Optional<UUID> sessionToken(AIPlayerEntity bot, Target target) {
        Session session = SESSIONS.get(bot.getUuid());
        return session != null && session.target == target
                ? Optional.of(session.token) : Optional.empty();
    }

    /** A strict checkpoint may resume only inside the exact audit session that created it. */
    public static boolean sessionMatches(AIPlayerEntity bot, Target target, UUID token) {
        Session session = SESSIONS.get(bot.getUuid());
        return token != null && session != null
                && session.target == target && session.token.equals(token);
    }

    private static Session diamondSession(AIPlayerEntity bot) {
        Session session = SESSIONS.get(bot.getUuid());
        return session != null && session.target == Target.DIAMOND ? session : null;
    }

    private static Session obsidianSession(AIPlayerEntity bot) {
        Session session = SESSIONS.get(bot.getUuid());
        return session != null && session.target == Target.OBSIDIAN ? session : null;
    }

    public record Snapshot(Target target,
                           int observedTicks,
                           int gameModeViolations,
                           int privilegedAllowed,
                           int deathDelta,
                           int diamondNaturalOreBreaks,
                           int diamondNativeDrops,
                           int diamondPhysicalPickups,
                           int waterPlacements,
                           int lavaConversions,
                           int obsidianBreaks,
                           int obsidianPhysicalPickups,
                           int vanillaObsidianBreaks) {
        public boolean passes() {
            if (observedTicks <= 0 || gameModeViolations != 0 || privilegedAllowed != 0
                    || deathDelta != 0) {
                return false;
            }
            return switch (target) {
                case DIAMOND -> diamondNaturalOreBreaks >= DIAMOND_TARGET
                        && diamondNativeDrops >= DIAMOND_TARGET
                        && diamondPhysicalPickups >= DIAMOND_TARGET;
                case OBSIDIAN -> waterPlacements >= 1
                        && lavaConversions >= OBSIDIAN_TARGET
                        && obsidianBreaks >= OBSIDIAN_TARGET
                        && vanillaObsidianBreaks >= OBSIDIAN_TARGET
                        && obsidianPhysicalPickups >= OBSIDIAN_TARGET;
            };
        }

        public String verdict() {
            return passes() ? "PASS" : "FAIL";
        }
    }

    private static final class Session {
        private final UUID token;
        private final Target target;
        private final StatsSnapshot baseline;
        private StatsSnapshot latest;
        private int observedTicks;
        private int gameModeViolations;
        private int privilegedAllowed;
        private int waterPlacements;
        private final DiamondTransactions diamondTransactions = new DiamondTransactions();
        private final ObsidianTransactions obsidianTransactions = new ObsidianTransactions();

        private Session(UUID token, Target target, StatsSnapshot baseline) {
            this.token = token;
            this.target = target;
            this.baseline = baseline;
            this.latest = baseline;
        }

        private Snapshot snapshot() {
            int vanillaDiamondBreaks = nonNegative(latest.diamondOreMined - baseline.diamondOreMined)
                    + nonNegative(latest.deepslateDiamondOreMined - baseline.deepslateDiamondOreMined);
            int naturalDiamondBreaks = diamondTransactions.auditedBreaksBoundedBy(vanillaDiamondBreaks);
            int diamondPickups = nonNegative(latest.diamondPickedUp - baseline.diamondPickedUp);
            int waterUses = nonNegative(latest.waterBucketUsed - baseline.waterBucketUsed);
            int vanillaObsidianMined = nonNegative(latest.obsidianMined - baseline.obsidianMined);
            int obsidianPickups = nonNegative(latest.obsidianPickedUp - baseline.obsidianPickedUp);
            int deathDelta = nonNegative(latest.deaths - baseline.deaths);
            return new Snapshot(
                    target,
                    observedTicks,
                    gameModeViolations,
                    privilegedAllowed,
                    deathDelta,
                    naturalDiamondBreaks,
                    Math.min(diamondTransactions.nativePickupCredits(), diamondPickups),
                    diamondPickups,
                    Math.min(waterPlacements, waterUses),
                    obsidianTransactions.conversionCredits(),
                    Math.min(obsidianTransactions.breakCredits(), vanillaObsidianMined),
                    Math.min(obsidianTransactions.pickupCredits(), obsidianPickups),
                    vanillaObsidianMined);
        }

        private static int nonNegative(int value) {
            return Math.max(0, value);
        }
    }

    /**
     * Exact-cell diamond transaction ledger. Counters are monotonic; pending sets only prevent one
     * observed block or pickup from being committed twice. Vanilla statistics remain an independent
     * upper bound at snapshot time instead of being allowed to create audited transactions.
     */
    static final class DiamondTransactions {
        private final Set<BlockPos> observedBeforeBreak = new HashSet<>();
        private final Set<BlockPos> awaitingNativePickup = new HashSet<>();
        private int auditedBreaks;
        private int nativePickupCredits;

        void observeBeforeBreak(BlockPos pos) {
            if (pos != null) {
                observedBeforeBreak.add(pos.toImmutable());
            }
        }

        boolean recordExactBreak(BlockPos pos) {
            if (pos == null || !observedBeforeBreak.remove(pos)
                    || !awaitingNativePickup.add(pos.toImmutable())) {
                return false;
            }
            auditedBreaks++;
            return true;
        }

        boolean recordNativePickup(BlockPos breakPos, boolean acceptsDiamond, int gained) {
            if (breakPos == null || !acceptsDiamond || gained <= 0
                    || !awaitingNativePickup.remove(breakPos)) {
                return false;
            }
            // The current from-zero chain does not use Fortune. Credit conservatively: an arbitrary
            // inventory delta cannot turn one exact ore transaction into a stack of native drops.
            nativePickupCredits++;
            return true;
        }

        int auditedBreaksBoundedBy(int vanillaMinedDelta) {
            return Math.min(auditedBreaks, Math.max(0, vanillaMinedDelta));
        }

        int nativePickupCredits() {
            return nativePickupCredits;
        }
    }

    /**
     * Exact-cell obsidian transaction ledger. Every state transition consumes its predecessor, so
     * replay cannot increase a counter and one physical block can credit at most one pickup even if
     * an unrelated inventory delta is larger.
     */
    static final class ObsidianTransactions {
        private final Map<Integer, Set<BlockPos>> openWaterPlacements = new LinkedHashMap<>();
        private final Set<BlockPos> convertedObsidian = new HashSet<>();
        private final Set<BlockPos> brokenObsidian = new HashSet<>();
        private int nextWaterPlacementGeneration = 1;
        private int conversionCredits;
        private int breakCredits;
        private int pickupCredits;

        int armWaterPlacement(Set<BlockPos> observableLava) {
            // CreateObsidianTask owns one reusable bucket and therefore one live placement at a
            // time. A new successful placement is an exact causality boundary: close any stale
            // unconverted candidates before arming the next generation, never union them.
            closeWaterPlacements();
            Set<BlockPos> candidates = new LinkedHashSet<>();
            for (BlockPos pos : observableLava) {
                if (pos != null && !convertedObsidian.contains(pos) && !brokenObsidian.contains(pos)) {
                    candidates.add(pos.toImmutable());
                }
            }
            if (candidates.isEmpty()) {
                return -1;
            }
            int generation = nextWaterPlacementGeneration++;
            openWaterPlacements.put(generation, candidates);
            return generation;
        }

        boolean recordConversion(BlockPos pos) {
            if (pos == null || convertedObsidian.contains(pos) || brokenObsidian.contains(pos)) {
                return false;
            }
            boolean armed = false;
            for (Set<BlockPos> candidates : openWaterPlacements.values()) {
                armed |= candidates.remove(pos);
            }
            if (!armed || !convertedObsidian.add(pos.toImmutable())) {
                return false;
            }
            conversionCredits++;
            return true;
        }

        Set<BlockPos> openWaterPlacementCandidates() {
            Set<BlockPos> candidates = new LinkedHashSet<>();
            openWaterPlacements.values().forEach(candidates::addAll);
            return Set.copyOf(candidates);
        }

        void closeWaterPlacements() {
            openWaterPlacements.clear();
        }

        boolean isBreakAuthorized(BlockPos pos) {
            return pos != null && convertedObsidian.contains(pos);
        }

        boolean recordExactBreak(BlockPos pos) {
            if (pos == null || !convertedObsidian.remove(pos)
                    || !brokenObsidian.add(pos.toImmutable())) {
                return false;
            }
            breakCredits++;
            return true;
        }

        boolean recordExactPickup(BlockPos breakPos, int gained) {
            if (breakPos == null || gained <= 0 || !brokenObsidian.remove(breakPos)) {
                return false;
            }
            pickupCredits++;
            return true;
        }

        int conversionCredits() {
            return conversionCredits;
        }

        int breakCredits() {
            return breakCredits;
        }

        int pickupCredits() {
            return pickupCredits;
        }
    }

    private record StatsSnapshot(int diamondOreMined,
                                 int deepslateDiamondOreMined,
                                 int diamondPickedUp,
                                 int waterBucketUsed,
                                 int obsidianMined,
                                 int obsidianPickedUp,
                                 int deaths) {
        private static StatsSnapshot read(AIPlayerEntity bot) {
            return new StatsSnapshot(
                    bot.getStatHandler().getStat(Stats.MINED, Blocks.DIAMOND_ORE),
                    bot.getStatHandler().getStat(Stats.MINED, Blocks.DEEPSLATE_DIAMOND_ORE),
                    bot.getStatHandler().getStat(Stats.PICKED_UP, Items.DIAMOND),
                    bot.getStatHandler().getStat(Stats.USED, Items.WATER_BUCKET),
                    bot.getStatHandler().getStat(Stats.MINED, Blocks.OBSIDIAN),
                    bot.getStatHandler().getStat(Stats.PICKED_UP, Items.OBSIDIAN),
                    bot.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.DEATHS)));
        }
    }
}
