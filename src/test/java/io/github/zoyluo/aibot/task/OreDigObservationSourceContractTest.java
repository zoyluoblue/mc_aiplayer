package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict-survival contracts for OreDig's observe-before-read state transitions. */
class OreDigObservationSourceContractTest {
    private static final Path ORE_DIG = Path.of(
            "src/main/java/io/github/zoyluo/aibot/task/OreDigTask.java");
    private static final Path ORE_SCAN = Path.of(
            "src/main/java/io/github/zoyluo/aibot/mining/OreScan.java");

    @Test
    void oreScanTriStateNeverReadsAnUnknownFluidCandidate() throws IOException {
        String source = Files.readString(ORE_SCAN);
        int triState = source.indexOf("public enum Observation");
        int present = source.indexOf("OBSERVED_PRESENT", triState);
        int gone = source.indexOf("OBSERVED_GONE", present);
        int unknown = source.indexOf("UNKNOWN", gone);
        int fluid = source.indexOf("public static Observation observeDangerFluid");
        int cellGate = source.indexOf("ObservableWorldQuery.canObserveCell(bot, pos)", fluid);
        int faceGate = source.indexOf("ObservableWorldQuery.canObserveBlock(bot, pos)", cellGate);
        int insetGate = source.indexOf(
                "ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos)", faceGate);
        int stateRead = source.indexOf("getBlockState(pos).getFluidState()", insetGate);
        assertTrue(triState >= 0 && present > triState && gone > present && unknown > gone
                        && fluid > unknown && cellGate > fluid && faceGate > cellGate
                        && insetGate > faceGate && stateRead > insetGate,
                "fluid state must be classified only after ordinary or inset-face observation");

        int adjacent = source.indexOf(
                "public static Observation adjacentHazard(AIPlayerEntity bot");
        int observedCandidate = source.indexOf("observeDangerFluid(bot, pos.offset(direction))", adjacent);
        int unknownAggregate = source.indexOf(
                "unknown ? Observation.UNKNOWN : Observation.OBSERVED_GONE", observedCandidate);
        assertTrue(adjacent > stateRead && observedCandidate > adjacent
                        && unknownAggregate > observedCandidate,
                "bot-aware adjacent hazard scans must aggregate UNKNOWN without raw neighbour reads");
    }

    @Test
    void hiddenFluidAndHiddenStoneTakeTheSameSealedChannelAction() throws IOException {
        String source = Files.readString(ORE_DIG);
        int tunnel = source.indexOf("private void digTowardStep(");
        int head = source.indexOf("BlockPos head = step.up()", tunnel);
        int activeSettlement = source.indexOf(
                "settleOwnedTunnelMine(bot, goal, intent, activeChannel)", head);
        int headObservation = source.indexOf("!canObserveWorldState(bot, head)", activeSettlement);
        int headRead = source.indexOf("var headState = world.getBlockState(head)", headObservation);
        int headMine = source.indexOf(
                "mineObservedTunnelObstruction(bot, world, goal, intent, head", headRead);
        int footObservation = source.indexOf("!canObserveWorldState(bot, step)", headMine);
        int footRead = source.indexOf("var feetState = world.getBlockState(step)", footObservation);
        int footMine = source.indexOf(
                "mineObservedTunnelObstruction(bot, world, goal, intent, step", footRead);
        assertTrue(tunnel >= 0 && head > tunnel && activeSettlement > head
                        && headObservation > activeSettlement && headRead > headObservation
                        && headMine > headRead && footObservation > headMine
                        && footRead > footObservation && footMine > footRead,
                "channel mining must settle an owner first, then observe/read/mine head before foot");

        int obstruction = source.indexOf("private void mineObservedTunnelObstruction");
        int sealedHazard = source.indexOf("OreScan.adjacentHazard(bot, obstruction)", obstruction);
        int observedOnly = source.indexOf("== OreScan.Observation.OBSERVED_PRESENT", sealedHazard);
        int mine = source.indexOf("settleOwnedTunnelMine(bot, goal, intent, obstruction)", observedOnly);
        assertTrue(obstruction > footMine && sealedHazard > obstruction
                        && observedOnly > sealedHazard && mine > observedOnly,
                "only an observed hazard may stop an observed staged obstruction");

        int lateral = source.indexOf(
                "private BranchFluidSealResult sealOneObservableLateralBranchFluid");
        int insetAware = source.indexOf("OreScan.observeDangerFluid(bot, candidate)", lateral);
        int seal = source.indexOf("BuildAction.placeBlockAt(bot, fluid)", insetAware);
        String lateralBody = source.substring(lateral, seal);
        assertTrue(insetAware > lateral && seal > insetAware,
                "lateral fluid sealing must use the dedicated observable candidate API");
        assertFalse(lateralBody.contains("squaredDistanceTo"),
                "edge-visible fluids must not be rejected by a center-distance prefilter");
        assertFalse(lateralBody.contains("world.getFluidState(candidate)"),
                "unknown lateral candidates must never be read to distinguish fluid from stone");
        assertFalse(source.contains("OreScan.adjacentHazard(world"),
                "strict OreDig must not call the world-only legacy adjacent hazard API");
        assertFalse(source.contains("adjacentDangerFluidOf(world"),
                "strict OreDig adjacent fluid probes must always carry the observing bot");
    }

    @Test
    void targetOwnersUsePresentGoneUnknownInsteadOfInferringBreaks() throws IOException {
        String source = Files.readString(ORE_DIG);
        int restored = source.indexOf("OreScan.Observation restoredTarget = OreScan.observeOre(");
        int keepUnknown = source.indexOf(
                "restoredTarget != OreScan.Observation.OBSERVED_GONE", restored);
        int promoteDebt = source.indexOf("pendingPickupPos = restoredActiveTargetBreakPos", keepUnknown);
        assertTrue(restored >= 0 && keepUnknown > restored && promoteDebt > keepUnknown,
                "an unknown restored active target must retain break ownership, not invent pickup debt");

        int bonusOwner = source.indexOf("if (activeBonus)");
        int bonusTick = source.indexOf("BlockMiner.Status st = miner.tick(bot)", bonusOwner);
        int bonus = source.indexOf("OreScan.Observation bonusState = OreScan.observeAnyOre", bonusTick);
        int bonusUnknown = source.indexOf("bonusState == OreScan.Observation.UNKNOWN", bonus);
        int bonusClear = source.indexOf("bonusOre = null", bonusUnknown);
        int targetOwner = source.indexOf("if (miningTarget)", bonusClear);
        int targetGate = source.indexOf(
                "passesTargetDropCommitGate(bot, world, targetOre)", targetOwner);
        int targetTick = source.indexOf("BlockMiner.Status st = miner.tick(bot)", targetGate);
        int target = source.indexOf("OreScan.Observation targetState = OreScan.observeOre", targetTick);
        int targetUnknown = source.indexOf("targetState == OreScan.Observation.UNKNOWN", target);
        int targetContinue = source.indexOf("continueUnknownOwnerApproach(", targetUnknown);
        int targetGone = source.indexOf("targetState == OreScan.Observation.OBSERVED_GONE", targetUnknown);
        int veinOwner = source.indexOf("if (miningVein)", targetGone);
        int veinGate = source.indexOf("passesTargetDropCommitGate(bot, world, v)", veinOwner);
        int veinTick = source.indexOf("BlockMiner.Status st = miner.tick(bot)", veinGate);
        int vein = source.indexOf("OreScan.Observation veinState = OreScan.observeOre", veinTick);
        int veinUnknown = source.indexOf("veinState == OreScan.Observation.UNKNOWN", vein);
        int veinContinue = source.indexOf("continueUnknownOwnerApproach(", veinUnknown);
        int veinGone = source.indexOf("veinState == OreScan.Observation.OBSERVED_GONE", veinUnknown);
        assertTrue(bonusOwner >= 0 && bonusTick > bonusOwner && bonus > bonusTick
                        && bonusUnknown > bonus && bonusClear > bonusUnknown
                        && targetOwner > bonusClear && targetGate > targetOwner
                        && targetTick > targetGate && target > targetTick
                        && targetUnknown > target && targetContinue > targetUnknown
                        && targetGone > targetContinue
                        && veinOwner > targetGone && veinGate > veinOwner
                        && veinTick > veinGate && vein > veinTick
                        && veinUnknown > vein && veinContinue > veinUnknown
                        && veinGone > veinContinue,
                "active owners must settle first; inactive owners preserve UNKNOWN until factual GONE");

        int continueHelper = source.indexOf("private void continueUnknownOwnerApproach");
        int nextStep = source.indexOf("BlockPos next = stepToward", continueHelper);
        int exactOwnerGuard = source.indexOf(
                "next.equals(owner) || next.up().equals(owner)", nextStep);
        int inactiveMiner = source.indexOf("if (miner.target() != null)", exactOwnerGuard);
        int tunnelOnly = source.indexOf("digTowardStep(bot, world, owner, intent)", inactiveMiner);
        assertTrue(continueHelper > veinGone && nextStep > continueHelper
                        && exactOwnerGuard > nextStep && inactiveMiner > exactOwnerGuard
                        && tunnelOnly > inactiveMiner,
                "UNKNOWN liveness may open only an intermediate body column, never the owner cell");
    }

    @Test
    void hiddenTransitionOverheadAndRaisedGeometryFailClosedBeforeReads() throws IOException {
        String source = Files.readString(ORE_DIG);
        int transition = source.indexOf("private PickupEgressResult tickPickupEgressClearance");
        int transitionObserve = source.indexOf(
                "OreScan.Observation openState = observePickupEgressClearance(", transition);
        int transitionUnknown = source.indexOf("openState == OreScan.Observation.UNKNOWN", transitionObserve);
        int transitionRead = source.indexOf("world.getBlockState(transitionHead)", transitionUnknown);
        assertTrue(transition >= 0 && transitionObserve > transition
                        && transitionUnknown > transitionObserve && transitionRead > transitionUnknown,
                "hidden lower-transition headroom must keep the target staged before any state read");

        int highPose = source.indexOf("private static BlockPos approachGoalFor");
        int footObserve = source.indexOf(
                "!ObservableWorldQuery.canObserveCell(bot, candidate)", highPose);
        int headObserve = source.indexOf(
                "!ObservableWorldQuery.canObserveCell(bot, candidate.up())", footObserve);
        int floorObserve = source.indexOf(
                "!ObservableWorldQuery.canObserveBlock(bot, candidate.down())", headObserve);
        int standable = source.indexOf("Standability.isStandable(world, candidate)", floorObserve);
        assertTrue(highPose >= 0 && footObserve > highPose && headObserve > footObserve
                        && floorObserve > headObserve && standable > floorObserve,
                "a high target work pose must be fully observed before standability is read");

        int raised = source.indexOf("private RaisedBoundaryLanding inspectRaisedBoundaryLanding");
        int supportObserve = source.indexOf("!canObserveWorldState(bot, support)", raised);
        int unknownUnsafe = source.indexOf("return RaisedBoundaryLanding.UNSAFE", supportObserve);
        int raisedRead = source.indexOf("world.getBlockState(support)", unknownUnsafe);
        assertTrue(raised >= 0 && supportObserve > raised && unknownUnsafe > supportObserve
                        && raisedRead > unknownUnsafe,
                "an unknown raised ledge must be UNSAFE before structural state is read or mined");
    }

    @Test
    void stairOpenEscapeAndFreshRerouteObserveTheirCompleteGeometryFirst() throws IOException {
        String source = Files.readString(ORE_DIG);
        int stair = source.indexOf("private Direction safeStairDir(AIPlayerEntity bot");
        int stairAhead = source.indexOf("!canObserveWorldState(bot, ahead)", stair);
        int stairSupport = source.indexOf(
                "!ObservableWorldQuery.canObserveBlock(bot, support)", stairAhead);
        int stairRead = source.indexOf("world.getBlockState(support)", stairSupport);
        assertTrue(stair >= 0 && stairAhead > stair && stairSupport > stairAhead
                        && stairRead > stairSupport,
                "safe stair selection must reject unknown body/support cells before state reads");

        int open = source.indexOf("private boolean isObservedSafeOpenEscapeCorridor");
        int openFeet = source.indexOf("!canObserveWorldState(bot, feet)", open);
        int openFloor = source.indexOf(
                "!ObservableWorldQuery.canObserveBlock(bot, floor)", openFeet);
        int openRead = source.indexOf("world.getBlockState(feet)", openFloor);
        int openHazard = source.indexOf("OreScan.adjacentHazard(bot, feet)", openRead);
        int openObservedDanger = source.indexOf(
                "== OreScan.Observation.OBSERVED_PRESENT", openHazard);
        assertTrue(open >= 0 && openFeet > open && openFloor > openFeet
                        && openRead > openFloor && openHazard > openRead
                        && openObservedDanger > openHazard,
                "open escape requires factual body/floor but only observed danger rejects it");

        int fresh = source.indexOf("private boolean isFreshSafeLateralBranch");
        int headGate = source.indexOf("!canObserveWorldState(bot, head)", fresh);
        int headRead = source.indexOf("world.getBlockState(head)", headGate);
        int safeHead = source.indexOf("if (solidHead)", headRead);
        int feetGate = source.indexOf("!canObserveWorldState(bot, feet)", safeHead);
        int feetRead = source.indexOf("world.getBlockState(feet)", feetGate);
        int observedHazardOnly = source.indexOf(
                "== OreScan.Observation.OBSERVED_PRESENT", feetRead);
        assertTrue(fresh >= 0 && headGate > fresh && headRead > headGate
                        && safeHead > headRead && feetGate > safeHead
                        && feetRead > feetGate && observedHazardOnly > feetRead,
                "fresh reroutes must classify a safe observed head before inspecting the foot cell");
    }
}
