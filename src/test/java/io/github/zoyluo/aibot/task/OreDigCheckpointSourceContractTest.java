package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks down cursor identity and saved-face return without bootstrapping a Minecraft registry. */
class OreDigCheckpointSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/task/OreDigTask.java");

    @Test
    void checkpointMatchesOreFamilyAndReturnsToSavedFaceBeforeMining() throws IOException {
        String source = Files.readString(SOURCE);
        int constructor = source.indexOf("this.restoredCheckpoint = OreDigCheckpoint.decode(");
        int missionIdentity = source.indexOf(
                "values, this.targetOres, expectedRareMissionTarget", constructor);
        int invalidGuard = source.indexOf("this.invalidCheckpoint = !values.isEmpty()", constructor);
        int matcher = source.indexOf("static Optional<MiningCursor> matchingCursor");
        int fingerprintGuard = source.indexOf(
                "!OreDigTask.oreFingerprint(ores).equals(fingerprint)", matcher);
        assertTrue(constructor >= 0 && missionIdentity > constructor && invalidGuard > missionIdentity
                        && matcher > invalidGuard && fingerprintGuard > matcher,
                "OreDig restore must reject another ore family or rare mission identity");

        int restoreFlag = source.indexOf(
                "restoringFace = !bot.getBlockPos().equals(cursor.face())", constructor);
        int tick = source.indexOf("protected void onTick", restoreFlag);
        int returnGate = source.indexOf("if (restoringFace)", tick);
        int scanWork = source.indexOf("lastFace = factualFace", returnGate);
        assertTrue(restoreFlag > constructor && returnGate > tick && scanWork > returnGate,
                "OreDig must return to the exact cursor.face before scanning or extending the saved branch");

        int restoreMethod = source.indexOf("private void returnToSavedFace", scanWork);
        int exactGate = source.indexOf("bot.getBlockPos().equals(lastFace)", restoreMethod);
        int faceOverwrite = source.indexOf(
                "lastFace = bot.getBlockPos().toImmutable()", exactGate);
        assertTrue(restoreMethod > scanWork && exactGate > restoreMethod && faceOverwrite > exactGate,
                "OreDig may replace the durable face only after reaching its exact coordinate");
    }

    @Test
    void blindAdvanceRequiresAnExplicitRuntimeOwnerThatIsNotCheckpointed() throws IOException {
        String source = Files.readString(SOURCE);
        int ownerType = source.indexOf("private record PendingBlindAdvance");
        int blindWalk = source.indexOf("ActionResult walk = bot.getActionPack().startWalkTo(");
        int ownerPublish = source.indexOf(
                "pendingBlindAdvance = new PendingBlindAdvance(", blindWalk);
        int detector = source.indexOf(
                "private PendingBlindAdvanceInspection inspectPendingBlindAdvance");
        int ownerGate = source.indexOf(
                "PendingBlindAdvance pending = pendingBlindAdvance", detector);
        int geometryGate = source.indexOf("boolean exactOwner =", ownerGate);
        int rearPhase = source.indexOf(
                "PendingBlindAdvancePhase.IN_FLIGHT_AT_REAR", geometryGate);
        assertTrue(ownerType >= 0 && blindWalk > ownerType && ownerPublish > blindWalk
                        && detector > ownerType && ownerGate > detector
                        && geometryGate > ownerGate && rearPhase > geometryGate,
                "a blind cursor step must require the runtime owner created by its own direct walker");

        int tick = source.indexOf("protected void onTick");
        int inspection = source.indexOf(
                "PendingBlindAdvanceInspection pendingAdvance = inspectPendingBlindAdvance", tick);
        int inFlightGate = source.indexOf(
                "pendingAdvance.phase() == PendingBlindAdvancePhase.IN_FLIGHT_AT_REAR", inspection);
        int retryGate = source.indexOf(
                "pendingAdvance.phase() == PendingBlindAdvancePhase.RETRY_AT_REAR", inFlightGate);
        int competingOwner = source.indexOf("advanceVein(bot, world)", retryGate);
        assertTrue(inspection > tick && inFlightGate > inspection && retryGate > inFlightGate
                        && competingOwner > retryGate,
                "an in-flight/retry blind walker must settle before any competing scan owner");

        int checkpoint = source.indexOf("public Map<String, String> checkpoint()");
        int checkpointEnd = source.indexOf(
                "/** Keeps only the structurally exact branch-owned rear identity", checkpoint);
        assertTrue(checkpoint >= 0 && checkpointEnd > checkpoint
                        && !source.substring(checkpoint, checkpointEnd)
                        .contains("pendingBlindAdvance"),
                "the vanished ActionPack walker must not become durable checkpoint authorization");

        int pause = source.indexOf("protected void onPause");
        int pausePublish = source.indexOf("publishInterruptionCursor(bot, true)", pause);
        int pauseClear = source.indexOf("clearPendingBlindAdvance()", pausePublish);
        int pauseStop = source.indexOf("bot.getActionPack().stopAll()", pauseClear);
        assertTrue(pausePublish > pause && pauseClear > pausePublish && pauseStop > pauseClear,
                "pause must settle the blind transaction before discarding its stopped walker owner");

        int richPath = source.indexOf(
                "bot.getActionPack().startPathTo(zone, protectedStoneLikeReserve)");
        int richRelease = source.lastIndexOf("clearStripMovementOwnership()", richPath);
        assertTrue(richRelease >= 0 && richRelease < richPath,
                "rich-zone movement must release blind ownership before starting a foreign path");
    }

    @Test
    void blockedBodyUsesOnlyItsExactOwnedRearWithoutLineOfSight() throws IOException {
        String source = Files.readString(SOURCE);
        int recovery = source.indexOf("private boolean recoverBlockedBody");
        int findCall = source.indexOf("findBlockedBodyRetreat(bot, world, feet)", recovery);
        int physicalCommit = source.indexOf(
                "FakePlayerMotion.stepToStandable(", findCall);
        int finder = source.indexOf("private BlockPos findBlockedBodyRetreat", physicalCommit);
        int liveOwner = source.indexOf(
                "ownsActiveBlindBranchCollision(bot, feet, lastFace)", finder);
        int resumedRear = source.indexOf(
                "ownsActiveBlindBranchCollision(bot, feet, ownedRear)", liveOwner);
        int ordinaryObservation = source.indexOf(
                "isAdjacentDryLanding(bot, world, feet, lastFace)", resumedRear);
        assertTrue(recovery >= 0 && findCall > recovery && physicalCommit > findCall
                        && finder > physicalCommit && liveOwner > finder
                        && resumedRear > liveOwner && ordinaryObservation > resumedRear,
                "only the exact durable rear may bypass LOS; physical commit still rechecks it");
    }

    @Test
    void lowerTargetStepObservesEverySafetyCellBeforeWorldReads() throws IOException {
        String source = Files.readString(SOURCE);
        int tunnel = source.indexOf("private void digTowardStep(");
        int headGate = source.indexOf("!canObserveWorldState(bot, head)", tunnel);
        int headRead = source.indexOf("world.getBlockState(head)", headGate);
        int footGate = source.indexOf("!canObserveWorldState(bot, step)", headRead);
        int footRead = source.indexOf("world.getBlockState(step)", footGate);
        int lowerDispatch = source.indexOf(
                "descendAcrossObservedOneBlockDrop(bot, world, step, goal)", footRead);
        int sameLevelHazard = source.indexOf(
                "hasObservedAdjacentDangerFluid(bot, step, head)", lowerDispatch);
        assertTrue(tunnel >= 0 && headGate > tunnel && headRead > headGate
                        && footGate > headRead && footRead > footGate
                        && lowerDispatch > footRead && sameLevelHazard > lowerDispatch,
                "the staged open lower-step path must observe head then foot before movement checks");

        int helper = source.indexOf("private boolean descendAcrossObservedOneBlockDrop");
        int helperEnd = source.indexOf("/**\n     * Releases exactly one known ore-approach owner", helper);
        assertTrue(helper >= 0 && helperEnd > helper,
                "lower-step helper boundary was not found");
        String body = source.substring(helper, helperEnd);
        int floorObserved = body.indexOf("ObservableWorldQuery.canObserveBlock(bot, floor)");
        int floorRead = body.indexOf("var floorState = world.getBlockState(floor)");
        int adjacentGate = body.indexOf("isObservedAdjacentFluidSafe(bot, world, step, landing)");
        int movement = body.indexOf("bot.getActionPack().descendInto(landing)");
        assertTrue(floorObserved >= 0 && floorRead > floorObserved
                        && adjacentGate > floorRead && movement > adjacentGate
                        && !body.contains("OreScan.adjacentHazard"),
                "the lower target step must observe its floor and neighbour envelope before reading or moving");

        int envelope = source.indexOf("static boolean isObservedAdjacentFluidSafe");
        int observedCell = source.indexOf(
                "ObservableWorldQuery.canObserveCell(bot, adjacent)", envelope);
        int observedBlock = source.indexOf(
                "ObservableWorldQuery.canObserveBlock(bot, adjacent)", observedCell);
        int fluidRead = source.indexOf("world.getFluidState(adjacent)", observedBlock);
        assertTrue(envelope > helper && observedCell > envelope && observedBlock > observedCell
                        && fluidRead > observedBlock,
                "every adjacent fluid read must be preceded by a strict observable-cell/block gate");
    }

    @Test
    void targetAndVeinMiningLeaveTheCurrentSupportBeforeBreakingIt() throws IOException {
        String source = Files.readString(SOURCE);
        int mainBranch = source.indexOf("if (!miningTarget && isCurrentSupport(bot, targetOre))");
        int mainMine = source.indexOf("beginTargetMine(bot, targetOre)", mainBranch);
        int veinBranch = source.indexOf("if (!miningVein && isCurrentSupport(bot, v))");
        int veinMine = source.indexOf("beginTargetMine(bot, v)", veinBranch);
        int observedCell = source.indexOf(
                "ObservableWorldQuery.canObserveCell(bot, candidate)", veinMine);
        int observedSupport = source.indexOf("ObservableWorldQuery.canObserveBlock(bot, candidate.down())",
                observedCell);
        int standability = source.indexOf("Standability.isStandable", observedSupport);

        assertTrue(mainBranch >= 0 && mainMine > mainBranch,
                "direct ore mining must relocate before breaking the bot's support");
        assertTrue(veinBranch >= 0 && veinMine > veinBranch,
                "vein mining must apply the same support safety gate");
        assertTrue(observedCell > veinMine && observedSupport > observedCell && standability > observedSupport,
                "support relocation must gate feet, head and support observations before standability reads");
    }

    @Test
    void finiteDropCatchAndBlindBodyFluidChecksCommitBeforeMining() throws IOException {
        String source = Files.readString(SOURCE);
        int mainGate = source.indexOf(
                "passesTargetDropCommitGate(bot, world, targetOre)");
        int mainMine = source.indexOf("beginTargetMine(bot, targetOre)", mainGate);
        int veinGate = source.indexOf("passesTargetDropCommitGate(bot, world, v)");
        int veinMine = source.indexOf("beginTargetMine(bot, v)", veinGate);
        assertTrue(mainGate >= 0 && mainMine > mainGate
                        && veinGate > mainMine && veinMine > veinGate,
                "primary and vein ore must prove a drop catch before opening a target-break ledger");
        assertTrue(source.contains(
                        "if (!passesTargetDropCommitGate(bot, world, targetOre))")
                        && source.contains(
                        "if (!passesTargetDropCommitGate(bot, world, v))"),
                "an already-active target miner must re-prove its catch on every tick");

        int catchGate = source.indexOf("private boolean passesTargetDropCommitGate");
        int observedCatch = source.indexOf("hasReliableObservedDropCatch", catchGate);
        int reserveSelector = source.indexOf(
                "MaterialPalette.pickPathSupportBlockSlot(", observedCatch);
        int typedAbandon = source.indexOf(
                "abandonTargetApproach(bot, ore, \"drop_support_required\"", reserveSelector);
        int cancelMiner = source.lastIndexOf("miner.cancel(bot)", typedAbandon);
        int clearActive = source.lastIndexOf("clearActiveTargetBreak(ore)", typedAbandon);
        assertTrue(catchGate > veinMine && observedCatch > catchGate
                        && reserveSelector > observedCatch
                        && cancelMiner > reserveSelector && clearActive > cancelMiner
                        && typedAbandon > clearActive,
                "an unproven shaft must retain one support above reserve or abandon intact");

        int tunnel = source.indexOf("private void digTowardStep(");
        int twoLevelPreflight = source.indexOf(
                "preflightBlindBodyEnvelope(bot, world, step, intent, factualRear)", tunnel);
        int firstBodyMine = source.indexOf(
                "mineObservedTunnelObstruction(bot, world, goal, intent, head", tunnel);
        int preflightHelper = source.indexOf(
                "private boolean preflightBlindBodyEnvelope", firstBodyMine);
        int helper = source.indexOf(
                "private BranchFluidSealResult sealOneObservableLateralBranchFluid");
        int bodyLevels = source.indexOf(
                "new BlockPos[]{branchCell, branchCell.up()}", helper);
        int lateralOnly = source.indexOf("Direction.Type.HORIZONTAL", bodyLevels);
        int visibleGate = source.indexOf(
                "OreScan.observeDangerFluid(bot, candidate)", lateralOnly);
        int singleSeal = source.indexOf("return BranchFluidSealResult.SEALED", visibleGate);
        assertTrue(twoLevelPreflight > tunnel && firstBodyMine > twoLevelPreflight
                        && preflightHelper > firstBodyMine && helper > preflightHelper
                        && bodyLevels > helper
                        && lateralOnly > bodyLevels && visibleGate > lateralOnly
                        && singleSeal > visibleGate,
                "blind mining must visibly preflight both body levels and seal at most one cell");
    }

    @Test
    void primaryAndVeinTargetsShareTheCloseBreakAndApproachPolicy() throws IOException {
        String source = Files.readString(SOURCE);
        int helper = source.indexOf("private static boolean hasRecoverableTargetBreakPose");
        int vertical = source.indexOf("int vertical = pos.getY() - feet.getY()", helper);
        int manhattan = source.indexOf("int horizontalManhattan =", vertical);
        int lowerLimit = source.indexOf("vertical >= MIN_TARGET_BREAK_DY", manhattan);
        int upperLimit = source.indexOf("vertical <= MAX_TARGET_BREAK_DY", lowerLimit);
        int closeLimit = source.indexOf("horizontalManhattan <= 1", upperLimit);
        int raisedLimit = source.indexOf(
                "vertical < MAX_TARGET_BREAK_DY || horizontalManhattan == 0", closeLimit);
        int vanillaReach = source.indexOf("withinReach(bot, pos)", raisedLimit);
        int breakHelper = source.indexOf("private static boolean canBreakTargetFromHere");
        int sharedEnvelope = source.indexOf("hasRecoverableTargetBreakPose(bot, pos)", breakHelper);
        int idlePath = source.indexOf("isPathExecutorIdle()", sharedEnvelope);
        int idleWalk = source.indexOf("isWalkToIdle()", idlePath);
        assertTrue(helper >= 0 && vertical > helper && manhattan > vertical
                        && lowerLimit > manhattan && upperLimit > lowerLimit
                        && closeLimit > upperLimit && raisedLimit > closeLimit
                        && vanillaReach > raisedLimit && breakHelper >= 0
                        && sharedEnvelope > breakHelper && idlePath > sharedEnvelope
                        && idleWalk > idlePath,
                "target breaking must wait for idle exact approach and stay inside the recoverable vertical envelope");

        int mainGate = source.indexOf(
                "if (miningTarget || canBreakTargetFromHere(bot, targetOre))");
        int mainMine = source.indexOf("beginTargetMine(bot, targetOre)", mainGate);
        int mainApproach = source.indexOf("approachTargetOre(bot, world, targetOre)", mainMine);
        assertTrue(mainGate >= 0 && mainMine > mainGate && mainApproach > mainMine,
                "primary ore must keep an active break or approach into the shared envelope before opening one");

        int veinGate = source.indexOf(
                "if (!miningVein && !canBreakTargetFromHere(bot, v))");
        int veinApproach = source.indexOf("approachTargetOre(bot, world, v)", veinGate);
        int veinMine = source.indexOf("beginTargetMine(bot, v)", veinApproach);
        assertTrue(veinGate >= 0 && veinApproach > veinGate && veinMine > veinApproach,
                "queued vein ore must use the same close-break and approach policy");
    }

    @Test
    void overheadTargetRequiresARealWorkPoseAndRestoredBreakRechecksTheEnvelope() throws IOException {
        String source = Files.readString(SOURCE);
        int approach = source.indexOf("private void approachTargetOre");
        int approachPathIdle = source.indexOf("isPathExecutorIdle()", approach);
        int approachWalkIdle = source.indexOf("isWalkToIdle()", approachPathIdle);
        int workPose = source.indexOf("BlockPos workPose = approachGoalFor(bot, world, ore)", approachWalkIdle);
        int missingPose = source.indexOf("if (workPose == null)", workPose);
        int rememberedRoute = source.indexOf(
                "tryRememberedHighWorkPoseRoute(bot, world, ore)", missingPose);
        int highColumn = source.indexOf(
                "ore.getY() - feet.getY() > MAX_TARGET_BREAK_DY", rememberedRoute);
        int typedAbandon = source.indexOf(
                "\"overhead_drop_catch_unproven\"", highColumn);
        int approachPath = source.indexOf("startDigPathTo(", typedAbandon);
        assertTrue(approachPathIdle > approach && approachWalkIdle > approachPathIdle
                        && workPose > approachWalkIdle && missingPose > workPose
                        && rememberedRoute > missingPose && highColumn > rememberedRoute
                        && typedAbandon > highColumn
                        && approachPath > typedAbandon,
                "high overhead ore must use a live/remembered side pose or be released intact");

        int commitGate = source.indexOf("private boolean passesTargetDropCommitGate");
        int poseGate = source.indexOf("if (!hasRecoverableTargetBreakPose(bot, ore))", commitGate);
        int clearActive = source.indexOf("clearActiveTargetBreak(ore)", poseGate);
        int abandon = source.indexOf("\"drop_pose_unrecoverable\"", clearActive);
        int restoredGate = source.indexOf("activeTargetBreakPos.equals(targetOre)");
        int restoredCall = source.indexOf(
                "!passesTargetDropCommitGate(bot, world, targetOre)", restoredGate);
        assertTrue(commitGate >= 0 && poseGate > commitGate && clearActive > poseGate
                        && abandon > clearActive && restoredGate >= 0 && restoredCall > restoredGate,
                "initial and restored target breaks must share the recoverable-pose commit gate");
    }

    @Test
    void rememberedHighWorkPoseIsObservedPersistedAndRoutedWithoutDigging() throws IOException {
        String source = Files.readString(SOURCE);
        int nearest = source.indexOf("private BlockPos nearestOre");
        int observable = source.indexOf("ObservableWorldQuery.canObserveBlock(bot, pos)", nearest);
        int targetState = source.indexOf("OreScan.isOre(world.getBlockState(pos), targetOres)", observable);
        int capture = source.indexOf("rememberObservedHighWorkPose(bot, world, pos)", targetState);
        assertTrue(nearest >= 0 && observable > nearest && targetState > observable
                        && capture > targetState,
                "all visible scan targets must publish a high side pose before nearest selection");
        int finishBreak = source.indexOf("private void finishTargetBreak");
        int newlyExposedScan = source.indexOf("nearestOre(bot, bot.getServerWorld())", finishBreak);
        int stabilize = source.indexOf("stabilizeBrokenTargetDrop(bot, pos)", newlyExposedScan);
        assertTrue(finishBreak >= 0 && newlyExposedScan > finishBreak
                        && stabilize > newlyExposedScan,
                "a lower break must capture newly exposed high poses before pickup movement");

        int helper = source.indexOf("private boolean tryRememberedHighWorkPoseRoute");
        int lookup = source.indexOf("rememberedHighWorkPose(bot, world, ore)", helper);
        int surface = source.indexOf("startSurfacePathTo(workPose)", lookup);
        int exactGoal = source.indexOf("activePathGoal()", surface);
        int helperEnd = source.indexOf("private void continueUnknownOwnerApproach", helper);
        assertTrue(helper >= 0 && lookup > helper && surface > lookup
                        && exactGoal > surface && helperEnd > exactGoal,
                "remembered work pose must use an exact non-destructive surface route");
        String helperBody = source.substring(helper, helperEnd);
        assertTrue(!helperBody.contains("startDigPathTo")
                        && !helperBody.contains("digTowardStep"),
                "an occluded remembered pose must never authorize terrain modification");

        int codecKey = source.indexOf("\"remembered_high_work_poses\"");
        int decode = source.indexOf("decodeRememberedHighWorkPoses", codecKey);
        int exactShape = source.indexOf("isExactHighWorkPose(entry.getKey(), entry.getValue())", decode);
        int boundedShape = source.indexOf(
                "isRememberedHighWorkPoseNearFace(face, entry.getKey())", exactShape);
        int transformCopies = source.split(
                "restored\\.rememberedHighWorkPoses\\(\\)", -1).length - 1;
        assertTrue(codecKey >= 0 && decode > codecKey && exactShape > decode
                        && boundedShape > exactShape && transformCopies == 3,
                "checkpoint codec and all service transforms must preserve bounded pose facts");
    }

    @Test
    void oreDigOptsIntoChannelToolPolicyWithoutChangingOtherMiners() throws IOException {
        String oreDig = Files.readString(SOURCE);
        String miner = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/action/BlockMiner.java"));
        String selector = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/action/ToolSelector.java"));

        assertTrue(oreDig.contains("miner.begin(bot, pos, true)"),
                "OreDig must explicitly opt into mining-channel tool conservation");
        assertTrue(miner.contains("public void begin(AIPlayerEntity bot, BlockPos pos) {\n        begin(bot, pos, false);"),
                "generic BlockMiner callers must retain the original tool policy");
        assertTrue(miner.contains("ToolSelector.equipMiningChannelTool(bot, equipTarget)"));
        assertTrue(miner.contains("ToolSelector.requiredMiningChannelTool(equipTarget)"),
                "channel failure must retain the blocked block's actual minimum tool");
        assertTrue(miner.contains("missing_mining_channel_tool"),
                "channel exhaustion must replan instead of consuming a higher-tier mission tool");
        assertTrue(selector.contains("Math.max(ToolTier.STONE, requiredTier)"),
                "ordinary rock must use at least stone while target ores preserve higher requirements");
        assertTrue(selector.contains("tier > maximumTier"),
                "ordinary branch rock must not silently consume iron/diamond durability");
        assertTrue(oreDig.contains("fail(\"need_mining_channel_tool:\" + required)"),
                "OreDig must preserve the exact typed channel-tool failure");
        assertTrue(oreDig.contains("rerouteBlindBranchAroundHigherTierOre"),
                "blind strip branches must rotate around an unharvestable non-target ore");
        assertTrue(oreDig.contains("rerouteBlindBranchAtObservedBoundary"));
        assertTrue(oreDig.contains("isFreshSafeLateralBranch"));
        assertTrue(oreDig.contains("ore_dig_branch_boundary_trapped:"),
                "a blind boundary without fresh lateral work must fail finitely");
        assertTrue(oreDig.contains("|| !veinQueue.isEmpty() || bonusOre != null"));
        assertTrue(oreDig.contains("|| blockedBodyRecoveryTarget != null"),
                "lava watcher reroute must not steal queued/bonus/body-clear ownership");
        assertTrue(oreDig.contains("int clockwise = (rejectedDirection + 1)"));
        assertTrue(oreDig.contains("int counterClockwise = (rejectedDirection + STRIP_DIRS.length - 1)"),
                "boundary reroute must try both perpendicular directions deterministically");
        assertTrue(oreDig.contains("int reverse = (rejectedDirection + 2)"),
                "a zero-progress cascade must retain the sole unvisited geometric reverse");
        assertTrue(oreDig.contains(
                        "boolean sameOriginCascade = origin.equals(boundaryRerouteOrigin)"));
        assertTrue(oreDig.contains(
                        "? new int[]{clockwise, counterClockwise, reverse}"));
        assertTrue(oreDig.contains(
                        ": new int[]{clockwise, counterClockwise}"),
                "an unmarked initial/ordinary boundary must still exclude geometric reverse");
        assertTrue(oreDig.contains("publishCompletedStripSuccessor")
                        && oreDig.contains("boolean factualCorner = completedDirection != null")
                        && oreDig.contains("&& factualRear != null")
                        && oreDig.contains("bot.getBlockPos().offset(completedDirection.getOpposite())")
                        && oreDig.contains("boundaryRerouteOrigin = stripProgressPos")
                        && oreDig.contains("controlledStripRear = factualRear"),
                "every exact final physical step may publish its origin and factual rear");
        assertTrue(oreDig.contains("ownsFactualCornerRear(here, forward)")
                        && oreDig.contains("rear = forward.rotateYClockwise()")
                        && oreDig.contains("Direction gateFacing = rear.getOpposite()")
                        && oreDig.contains(
                        "isAheadOfDirection(here, hostilePos, gateFacing)")
                        && oreDig.contains("candidateGate = candidateRetreat.offset(gateFacing)")
                        && oreDig.contains("isObservableRearCorridor")
                        && oreDig.contains("isObservableNarrowMiningGate"),
                "hostile recovery at a factual corner must retreat through the crossed old leg");
        assertTrue(oreDig.contains("stripStepsLeft == stripLegLength")
                        && oreDig.contains("stepsLeft == legLength"),
                "only a full untouched successor leg may own the factual-corner rear pair");
        assertTrue(oreDig.contains("isObservedSafeOpenEscapeCorridor")
                        && oreDig.contains("ore_dig_branch_boundary_backtrack")
                        && oreDig.contains("stripStepsLeft = 1"),
                "a zero-movement fluid cascade must retain one bounded observed rear step");
        assertTrue(oreDig.contains("boundaryRerouteOrigin = origin.toImmutable()")
                        && oreDig.contains("values.put(\"boundary_reroute_origin\"")
                        && oreDig.contains("restoredCheckpoint.boundaryRerouteOrigin()"),
                "the zero-movement reverse exception must survive an exact checkpoint restart");
        assertTrue(oreDig.contains("!feet.equals(boundaryRerouteOrigin)"),
                "the first factual move must restore ordinary controlled-rear semantics");
        assertTrue(oreDig.split("failMissingMiningChannelTool\\(bot\\)", -1).length - 1 >= 5,
                "every BlockMiner FAILED exit must intercept channel-tool exhaustion");
    }

    @Test
    void activeBudgetsAreDurableAndCommittedCursorStartsANewBoundedBatch() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("private static final int MAX_ELAPSED_BASE = MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS"),
                "common-ore seed3000 needs the bounded 24000 tick base budget");
        assertTrue(source.contains("values.put(\"batch_open\""));
        assertTrue(source.contains("values.put(\"budget_used\""));
        assertTrue(source.contains("values.put(\"last_progress_budget\""));
        assertTrue(source.contains("values.put(\"pending_pickup_started_budget\""));
        assertTrue(source.contains("values.put(\"pickup_gain_budget\""));
        assertTrue(source.contains("int durableBudget = committed ? 0"),
                "completed cursor must not leak an active-batch hard budget");
        assertTrue(source.contains("state == TaskState.FAILED && collected > 0"));
        assertTrue(source.contains("partialDeliverySuccessor ? durableBudget : lastProgressBudget"),
                "only a failed attempt with physical target delivery may rebase the stall window");

        int tick = source.indexOf("protected void onTick");
        int hardBudget = source.indexOf("if (totalBudget() > maxElapsed)", tick);
        int faceRestore = source.indexOf("if (restoringFace)", tick);
        int noProgress = source.indexOf(
                "if (totalBudget() - lastProgressBudget > NO_PROGRESS_LIMIT)", faceRestore);
        assertTrue(hardBudget > tick && faceRestore > hardBudget && noProgress > faceRestore,
                "hard/no-progress checks must use the durable budget and face return may not bypass hard timeout");
    }

    @Test
    void everyTargetBreakPausesForDurablePhysicalPickup() throws IOException {
        String source = Files.readString(SOURCE);
        int tick = source.indexOf("protected void onTick");
        int recoveryGate = source.indexOf("if (recoverPendingTargetDrop(bot))", tick);
        int veinDispatch = source.indexOf("advanceVein(bot, world)", recoveryGate);
        int scanDispatch = source.indexOf("BlockPos found = nearestOre(bot, world)", veinDispatch);
        assertTrue(recoveryGate > tick && veinDispatch > recoveryGate && scanDispatch > veinDispatch,
                "a pending target drop must block vein, scan and strip work until vanilla pickup is confirmed");

        assertTrue(source.contains("values.put(\"pending_pickup_pos\""));
        assertTrue(source.contains("values.put(\"pending_pickup_last_seen_pos\""));
        assertTrue(source.contains("values.put(\"pending_pickup_inventory\""));
        assertTrue(source.contains("restoredPendingPickupPos"));
        assertTrue(source.contains("restoredPendingPickupLastSeenPos"));
        assertTrue(source.contains("pendingPickupLastSeenPos = drop.getBlockPos().toImmutable()"),
                "a visible moving target drop must advance the durable recovery coordinate");
        assertTrue(source.contains("ore_dig_drop_unrecovered:"),
                "drop loss must fail closed instead of silently consuming the finite ore field");
    }

    @Test
    void exhaustedPickupDebtKeepsItsOriginalMissionFailure() throws IOException {
        String executor = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java"));
        int handler = executor.indexOf("private void handleStepFailure");
        int terminal = executor.indexOf(
                "reason.startsWith(\"ore_dig_drop_unrecovered\")", handler);
        int planner = executor.indexOf("GoalPlanner.GoalPlan fresh", handler);
        assertTrue(handler >= 0 && terminal > handler && planner > terminal,
                "an exhausted physical pickup debt must fail with its factual coordinate before replanning");
    }

    @Test
    void trappedBlindBranchFailsMissionBeforeReplayingItsCheckpoint() throws IOException {
        String executor = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java"));
        int handler = executor.indexOf("private void handleStepFailure");
        int terminal = executor.indexOf(
                "reason.startsWith(\"ore_dig_branch_boundary_trapped:\")", handler);
        int planner = executor.indexOf("GoalPlanner.GoalPlan fresh", handler);
        assertTrue(handler >= 0 && terminal > handler && planner > terminal,
                "a factually trapped branch must terminate before the same cursor is replanned");
    }
}
